// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * {@code RoaringU32} — a sparse, compressed 32-bit integer set (a Roaring-style
 * bitmap). See {@code spec/features/roaring-u32.md}.
 *
 * <p>The universe ({@code 2^32} values) is split into {@code 2^16} <b>chunks</b>
 * keyed by the high 16 bits of a value. Each non-empty chunk is stored as a
 * <b>container</b>: an ARRAY (sorted distinct {@code u16[]}) for cardinality
 * {@code 1 .. 4096}, or a BITMAP ({@code 1024 × u64}) for cardinality
 * {@code 4097 .. 65536}. The container type is a <b>pure function of the chunk's
 * current cardinality</b> (history-independent), which makes the serialized form
 * canonical.
 *
 * <p>This is a <b>bespoke mapdb-java fork type</b>: Eclipse Collections ships no
 * Roaring bitmap, so there is no EC base to wrap. <b>Java carve-out</b>: values
 * are carried in signed {@code int} (Java has no unsigned types) but every
 * comparison is <b>unsigned</b> — the high/low split uses {@code >>>}/masks and
 * chunk ordering / {@code min} / {@code max} use {@link Integer#compareUnsigned}.
 * The BITMAP container is a {@code long[1024]} with {@code >>>} logical shifts.
 * Cardinality is {@code long} (it holds {@code 2^32}). Serialization is
 * <b>bit-exact little-endian</b> (via {@link ByteBuffer} with
 * {@link ByteOrder#LITTLE_ENDIAN}) — there is <b>NO serialization relaxation</b>:
 * Java is on the cross-language byte oracle.
 *
 * <p>Ordering is <b>UNSIGNED u32 ascending</b> throughout (iteration, {@code min}
 * / {@code max}, serialized chunk order). An {@code i32} element is
 * <b>bit-reinterpreted</b> to {@code u32} (not sign-extended), so {@code i32 -1}
 * is {@code 0xFFFFFFFF} and sorts last.
 */
public final class RoaringU32
{
    /**
     * Cardinality at and below which a chunk is an ARRAY; above which it is a
     * BITMAP. {@code 4096} is the classic Roaring break-even
     * ({@code 4096 × 2 bytes == 8192}, the bitmap size).
     * {@code c <= 4096 => ARRAY}, {@code c > 4096 => BITMAP}.
     */
    private static final int ARRAY_MAX = 4096;

    /** A BITMAP container is always exactly 1024 {@code u64} words ({@code 2^16} bits). */
    private static final int BITMAP_WORDS = 1024;

    /** Serialized header magic: {@code 0x32523055} (LE bytes {@code 55 30 52 32}). */
    private static final int MAGIC = 0x32523055;
    /** Serialized format version. */
    private static final int VERSION = 1;

    private static final byte TAG_ARRAY = 0x01;
    private static final byte TAG_BITMAP = 0x02;

    /**
     * Non-empty chunks in <b>unsigned high-key ascending</b> order. Each chunk's
     * high key is a {@code u16} carried in {@code char}; invariant: strictly
     * ascending, no empty containers.
     */
    private final List<Chunk> chunks;

    /** A (high key, container) pair. */
    private static final class Chunk
    {
        final char high;       // u16 high key
        Container container;

        Chunk(char high, Container container)
        {
            this.high = high;
            this.container = container;
        }
    }

    /** An empty set. */
    public RoaringU32()
    {
        this.chunks = new ArrayList<>();
    }

    private RoaringU32(List<Chunk> chunks)
    {
        this.chunks = chunks;
    }

    // ---- value split (UNSIGNED, bit-reinterpret — no sign extend) ----------

    /** High 16 bits as a u16 ({@code char}). */
    private static char high(int value)
    {
        return (char) (value >>> 16);
    }

    /** Low 16 bits as a u16 ({@code char}). */
    private static char low(int value)
    {
        return (char) (value & 0xFFFF);
    }

    /** Reassemble a full u32 (carried in signed {@code int}) from its halves. */
    private static int join(char high, char low)
    {
        return (high << 16) | low;
    }

    // ---- chunk lookup (unsigned high-key binary search) --------------------

    /**
     * Index of the chunk whose high key equals {@code high}, or {@code -(ins+1)}
     * where {@code ins} is the insertion point preserving unsigned-ascending
     * order (mirrors {@code Arrays.binarySearch} but unsigned on the {@code char}
     * keys — {@code char} compares are already unsigned, so a plain compare
     * suffices).
     */
    private int find(char high)
    {
        int lo = 0;
        int hi = chunks.size() - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            char midKey = chunks.get(mid).high;
            if (midKey < high)
            {
                lo = mid + 1;
            }
            else if (midKey > high)
            {
                hi = mid - 1;
            }
            else
            {
                return mid;
            }
        }
        return -(lo + 1);
    }

    // ---- per-element ops ---------------------------------------------------

    /**
     * Insert {@code value} (an {@code i32} bit-reinterpreted to {@code u32}).
     * Returns whether the set changed (was newly added). Idempotent.
     */
    public boolean add(int value)
    {
        char h = high(value);
        char l = low(value);
        int i = find(h);
        if (i >= 0)
        {
            Chunk c = chunks.get(i);
            boolean changed = c.container.add(l);
            if (changed && c.container instanceof ArrayContainer
                    && c.container.cardinality() > ARRAY_MAX)
            {
                // ARRAY -> BITMAP up-conversion at cardinality 4097.
                c.container = BitmapContainer.fromLows(c.container.lows());
            }
            return changed;
        }
        ArrayContainer ac = new ArrayContainer();
        ac.add(l);
        chunks.add(-(i + 1), new Chunk(h, ac));
        return true;
    }

    /**
     * Remove {@code value}. Returns whether the set changed (was present).
     * Removing the last value of a chunk drops the chunk (Empty normalization).
     */
    public boolean remove(int value)
    {
        char h = high(value);
        char l = low(value);
        int i = find(h);
        if (i < 0)
        {
            return false;
        }
        Chunk c = chunks.get(i);
        if (!c.container.remove(l))
        {
            return false;
        }
        int card = c.container.cardinality();
        if (card == 0)
        {
            // Empty-chunk normalization: drop the chunk entirely.
            chunks.remove(i);
        }
        else if (card <= ARRAY_MAX && c.container instanceof BitmapContainer)
        {
            // BITMAP -> ARRAY down-conversion at cardinality 4096.
            c.container = ArrayContainer.fromLows(c.container.lows());
        }
        return true;
    }

    /** Whether {@code value} is present. */
    public boolean contains(int value)
    {
        int i = find(high(value));
        return i >= 0 && chunks.get(i).container.contains(low(value));
    }

    /** Logical cardinality (up to {@code 2^32}; held in a {@code long}). */
    public long cardinality()
    {
        long total = 0;
        for (Chunk c : chunks)
        {
            total += c.container.cardinality();
        }
        return total;
    }

    /** Whether the set is empty. */
    public boolean isEmpty()
    {
        return chunks.isEmpty();
    }

    /** Remove all values (canonical empty set). */
    public void clear()
    {
        chunks.clear();
    }

    /** Number of non-empty chunks (the serialized {@code CHUNK_COUNT}). */
    public int chunkCount()
    {
        return chunks.size();
    }

    /**
     * Unsigned minimum present value (the {@code i32} reinterpret of the
     * {@code u32}), or empty if the set is empty.
     */
    public OptionalInt min()
    {
        if (chunks.isEmpty())
        {
            return OptionalInt.empty();
        }
        Chunk c = chunks.get(0);
        return OptionalInt.of(join(c.high, c.container.minLow()));
    }

    /**
     * Unsigned maximum present value (the {@code i32} reinterpret of the
     * {@code u32}), or empty if the set is empty.
     */
    public OptionalInt max()
    {
        if (chunks.isEmpty())
        {
            return OptionalInt.empty();
        }
        Chunk c = chunks.get(chunks.size() - 1);
        return OptionalInt.of(join(c.high, c.container.maxLow()));
    }

    /**
     * All values in <b>unsigned u32 ascending</b> order, emitted as {@code i32}
     * bit reinterprets (so {@code -1} is last).
     */
    public int[] toSortedArray()
    {
        long card = cardinality();
        if (card > Integer.MAX_VALUE)
        {
            // A Java int[] cannot hold more than Integer.MAX_VALUE elements; a set
            // this large (cardinality fits in long, but not an int[]) cannot be
            // materialized into an array. Iterate per-chunk instead. The i32
            // cross-language suite stays well under 2^31, so this never triggers
            // there; it is a defensive guard against silent narrowing.
            throw new IllegalStateException(
                    "cardinality " + card + " exceeds Integer.MAX_VALUE; cannot materialize as int[]");
        }
        int[] out = new int[(int) card];
        int idx = 0;
        for (Chunk c : chunks)
        {
            for (char l : c.container.lows())
            {
                out[idx++] = join(c.high, l);
            }
        }
        return out;
    }

    /** Per-chunk container-type tags in chunk order ({@code "array"} / {@code "bitmap"}). */
    public String[] containerTypes()
    {
        String[] out = new String[chunks.size()];
        for (int i = 0; i < chunks.size(); i++)
        {
            out[i] = chunks.get(i).container instanceof ArrayContainer ? "array" : "bitmap";
        }
        return out;
    }

    // ---- set algebra (container-granularity, scalar) -----------------------

    /** Union ({@code v in A} or {@code v in B}). */
    public RoaringU32 or(RoaringU32 other)
    {
        return combine(other, true, true, MergeOp.UNION);
    }

    /** Intersection ({@code v in A} and {@code v in B}). */
    public RoaringU32 and(RoaringU32 other)
    {
        return combine(other, false, false, MergeOp.INTERSECT);
    }

    /** Difference ({@code v in A} and {@code v not in B}; asymmetric {@code A \ B}). */
    public RoaringU32 andNot(RoaringU32 other)
    {
        return combine(other, true, false, MergeOp.AND_NOT);
    }

    /** Symmetric difference (exactly one of {@code A}, {@code B}). */
    public RoaringU32 xor(RoaringU32 other)
    {
        return combine(other, true, true, MergeOp.XOR);
    }

    private enum MergeOp
    {
        UNION, INTERSECT, AND_NOT, XOR
    }

    /**
     * Generic chunk-merge driver. {@code keepA}/{@code keepB} decide whether an
     * only-in-A / only-in-B chunk contributes (a rebuilt, normalized copy — never
     * a shared/aliased container, so the result is independent of both operands).
     * A shared chunk is combined per {@code op}, then dropped if empty or stored
     * in the canonical container type for its <b>result</b> cardinality.
     */
    private RoaringU32 combine(RoaringU32 other, boolean keepA, boolean keepB, MergeOp op)
    {
        List<Chunk> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        int na = chunks.size();
        int nb = other.chunks.size();
        while (i < na && j < nb)
        {
            Chunk ca = chunks.get(i);
            Chunk cb = other.chunks.get(j);
            // char compares are unsigned.
            if (ca.high < cb.high)
            {
                if (keepA)
                {
                    out.add(new Chunk(ca.high, Container.canonicalFromLows(ca.container.lows())));
                }
                i++;
            }
            else if (ca.high > cb.high)
            {
                if (keepB)
                {
                    out.add(new Chunk(cb.high, Container.canonicalFromLows(cb.container.lows())));
                }
                j++;
            }
            else
            {
                char[] lows = mergeLows(ca.container.lows(), cb.container.lows(), op);
                if (lows.length > 0)
                {
                    out.add(new Chunk(ca.high, Container.canonicalFromLows(lows)));
                }
                i++;
                j++;
            }
        }
        if (keepA)
        {
            while (i < na)
            {
                Chunk ca = chunks.get(i++);
                out.add(new Chunk(ca.high, Container.canonicalFromLows(ca.container.lows())));
            }
        }
        if (keepB)
        {
            while (j < nb)
            {
                Chunk cb = other.chunks.get(j++);
                out.add(new Chunk(cb.high, Container.canonicalFromLows(cb.container.lows())));
            }
        }
        return new RoaringU32(out);
    }

    /** Scalar sorted-list merge of two unsigned-ascending low-key arrays. */
    private static char[] mergeLows(char[] a, char[] b, MergeOp op)
    {
        char[] tmp = new char[a.length + b.length];
        int n = 0;
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length)
        {
            char x = a[i];
            char y = b[j];
            if (x < y) // unsigned (char)
            {
                if (op == MergeOp.UNION || op == MergeOp.AND_NOT || op == MergeOp.XOR)
                {
                    tmp[n++] = x;
                }
                i++;
            }
            else if (x > y)
            {
                if (op == MergeOp.UNION || op == MergeOp.XOR)
                {
                    tmp[n++] = y;
                }
                j++;
            }
            else
            {
                if (op == MergeOp.UNION || op == MergeOp.INTERSECT)
                {
                    tmp[n++] = x;
                }
                // AND_NOT / XOR: equal keys cancel.
                i++;
                j++;
            }
        }
        if (op == MergeOp.UNION || op == MergeOp.AND_NOT || op == MergeOp.XOR)
        {
            while (i < a.length)
            {
                tmp[n++] = a[i++];
            }
        }
        if (op == MergeOp.UNION || op == MergeOp.XOR)
        {
            while (j < b.length)
            {
                tmp[n++] = b[j++];
            }
        }
        if (n == tmp.length)
        {
            return tmp;
        }
        char[] out = new char[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    // ---- serialization (little-endian, canonical) --------------------------

    /** Serialize to the canonical little-endian v1 byte image. */
    public byte[] serialize()
    {
        // Compute exact length: 12-byte header + per chunk (6-byte chunk header
        // + payload). ARRAY payload = card*2; BITMAP payload = 8192.
        int len = 12;
        for (Chunk c : chunks)
        {
            len += 6;
            if (c.container instanceof ArrayContainer)
            {
                len += c.container.cardinality() * 2;
            }
            else
            {
                len += BITMAP_WORDS * 8;
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC);
        buf.putShort((short) VERSION);
        buf.putShort((short) 0); // RESERVED
        buf.putInt(chunks.size());
        for (Chunk c : chunks)
        {
            buf.putShort((short) c.high);
            int card = c.container.cardinality();
            if (c.container instanceof ArrayContainer)
            {
                buf.put(TAG_ARRAY);
                buf.put((byte) 0); // PAD
                buf.putShort((short) (card - 1)); // CARDINALITY_MINUS_1
                for (char l : ((ArrayContainer) c.container).keys())
                {
                    buf.putShort((short) l);
                }
            }
            else
            {
                buf.put(TAG_BITMAP);
                buf.put((byte) 0); // PAD
                buf.putShort((short) (card - 1));
                for (long w : ((BitmapContainer) c.container).words())
                {
                    buf.putLong(w);
                }
            }
        }
        return buf.array();
    }

    /**
     * Deserialize a canonical v1 byte image. Throws
     * {@link IllegalArgumentException} for any non-canonical / corrupt / foreign
     * image (see the spec reader-MUST-reject rules).
     */
    public static RoaringU32 deserialize(byte[] bytes)
    {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = getInt(buf);
        if (magic != MAGIC)
        {
            throw new IllegalArgumentException(
                    String.format("bad MAGIC: 0x%08x", magic));
        }
        int version = getShort(buf) & 0xFFFF;
        if (version != VERSION)
        {
            throw new IllegalArgumentException("unsupported VERSION: " + version);
        }
        int reserved = getShort(buf) & 0xFFFF;
        if (reserved != 0)
        {
            throw new IllegalArgumentException(
                    String.format("non-zero RESERVED: 0x%04x", reserved));
        }
        long chunkCount = getInt(buf) & 0xFFFFFFFFL;
        if (chunkCount > 65536L)
        {
            throw new IllegalArgumentException("CHUNK_COUNT > 65536: " + chunkCount);
        }
        List<Chunk> chunks = new ArrayList<>((int) chunkCount);
        int prevHigh = -1; // -1 means "none yet" (high keys are 0..65535)
        for (long n = 0; n < chunkCount; n++)
        {
            int high = getShort(buf) & 0xFFFF;
            if (prevHigh >= 0 && high <= prevHigh)
            {
                throw new IllegalArgumentException(String.format(
                        "non-ascending or duplicate high key: 0x%04x after 0x%04x", high, prevHigh));
            }
            prevHigh = high;
            int tag = get(buf) & 0xFF;
            int pad = get(buf) & 0xFF;
            if (pad != 0)
            {
                throw new IllegalArgumentException(String.format("non-zero PAD: 0x%02x", pad));
            }
            int card = (getShort(buf) & 0xFFFF) + 1; // CARDINALITY_MINUS_1 + 1
            if (tag == TAG_ARRAY)
            {
                if (card > ARRAY_MAX)
                {
                    throw new IllegalArgumentException(
                            "non-canonical ARRAY cardinality " + card + " (> " + ARRAY_MAX + ")");
                }
                char[] keys = new char[card];
                int prev = -1;
                for (int k = 0; k < card; k++)
                {
                    int lowKey = getShort(buf) & 0xFFFF;
                    if (prev >= 0 && lowKey <= prev)
                    {
                        throw new IllegalArgumentException(String.format(
                                "non-ascending or duplicate ARRAY low key: 0x%04x after 0x%04x",
                                lowKey, prev));
                    }
                    prev = lowKey;
                    keys[k] = (char) lowKey;
                }
                chunks.add(new Chunk((char) high, new ArrayContainer(keys)));
            }
            else if (tag == TAG_BITMAP)
            {
                if (card <= ARRAY_MAX)
                {
                    throw new IllegalArgumentException(
                            "non-canonical BITMAP cardinality " + card + " (<= " + ARRAY_MAX + ")");
                }
                long[] words = new long[BITMAP_WORDS];
                long popcount = 0;
                for (int w = 0; w < BITMAP_WORDS; w++)
                {
                    long word = getLong(buf);
                    popcount += Long.bitCount(word);
                    words[w] = word;
                }
                if (popcount != card)
                {
                    throw new IllegalArgumentException(
                            "BITMAP popcount " + popcount + " != stored cardinality " + card);
                }
                chunks.add(new Chunk((char) high, new BitmapContainer(words, card)));
            }
            else
            {
                throw new IllegalArgumentException(
                        String.format("unknown CONTAINER_TYPE tag: 0x%02x", tag));
            }
        }
        if (buf.hasRemaining())
        {
            throw new IllegalArgumentException(
                    buf.remaining() + " trailing bytes after chunk records");
        }
        return new RoaringU32(chunks);
    }

    // Bounds-checked little-endian reads (translate underflow to the reject path).
    private static byte get(ByteBuffer b)
    {
        require(b, 1);
        return b.get();
    }

    private static int getShort(ByteBuffer b)
    {
        require(b, 2);
        return b.getShort();
    }

    private static int getInt(ByteBuffer b)
    {
        require(b, 4);
        return b.getInt();
    }

    private static long getLong(ByteBuffer b)
    {
        require(b, 8);
        return b.getLong();
    }

    private static void require(ByteBuffer b, int n)
    {
        if (b.remaining() < n)
        {
            throw new IllegalArgumentException(
                    "truncated: need " + n + " bytes, have " + b.remaining());
        }
    }

    // ---- containers --------------------------------------------------------

    /** A per-chunk container; always the canonical type for its cardinality. */
    private abstract static class Container
    {
        abstract int cardinality();

        abstract boolean contains(char low);

        /** Insert {@code low}; returns whether the container changed. */
        abstract boolean add(char low);

        /** Remove {@code low}; returns whether the container changed. */
        abstract boolean remove(char low);

        /** All present low keys in unsigned ascending order. */
        abstract char[] lows();

        abstract char minLow();

        abstract char maxLow();

        /**
         * Normalize a low-key array (assumed sorted, distinct, non-empty) into the
         * canonical container for its cardinality.
         */
        static Container canonicalFromLows(char[] lows)
        {
            if (lows.length <= ARRAY_MAX)
            {
                return new ArrayContainer(lows);
            }
            return BitmapContainer.fromLows(lows);
        }
    }

    /** Sorted, distinct low-16-bit keys (length == cardinality). */
    private static final class ArrayContainer extends Container
    {
        private char[] keys;
        private int size;

        ArrayContainer()
        {
            this.keys = new char[4];
            this.size = 0;
        }

        /** Takes ownership of a sorted, distinct {@code keys} array. */
        ArrayContainer(char[] keys)
        {
            this.keys = keys;
            this.size = keys.length;
        }

        static ArrayContainer fromLows(char[] lows)
        {
            return new ArrayContainer(lows);
        }

        /** Index of {@code low} or {@code -(ins+1)} (unsigned binary search on char). */
        private int search(char low)
        {
            int lo = 0;
            int hi = size - 1;
            while (lo <= hi)
            {
                int mid = (lo + hi) >>> 1;
                char midKey = keys[mid];
                if (midKey < low)
                {
                    lo = mid + 1;
                }
                else if (midKey > low)
                {
                    hi = mid - 1;
                }
                else
                {
                    return mid;
                }
            }
            return -(lo + 1);
        }

        @Override
        int cardinality()
        {
            return size;
        }

        @Override
        boolean contains(char low)
        {
            return search(low) >= 0;
        }

        @Override
        boolean add(char low)
        {
            int i = search(low);
            if (i >= 0)
            {
                return false;
            }
            int pos = -(i + 1);
            if (size == keys.length)
            {
                char[] grown = new char[keys.length * 2];
                System.arraycopy(keys, 0, grown, 0, size);
                keys = grown;
            }
            System.arraycopy(keys, pos, keys, pos + 1, size - pos);
            keys[pos] = low;
            size++;
            return true;
        }

        @Override
        boolean remove(char low)
        {
            int i = search(low);
            if (i < 0)
            {
                return false;
            }
            System.arraycopy(keys, i + 1, keys, i, size - i - 1);
            size--;
            return true;
        }

        @Override
        char[] lows()
        {
            char[] out = new char[size];
            System.arraycopy(keys, 0, out, 0, size);
            return out;
        }

        /** The backing keys, exactly {@code size} long (only valid right after a copy/build). */
        char[] keys()
        {
            return lows();
        }

        @Override
        char minLow()
        {
            return keys[0];
        }

        @Override
        char maxLow()
        {
            return keys[size - 1];
        }
    }

    /** Dense bitmap: 1024 {@code u64} words; bit {@code (w*64 + b)} is low key {@code w*64+b}. */
    private static final class BitmapContainer extends Container
    {
        private final long[] words;
        private int count;

        BitmapContainer(long[] words, int count)
        {
            this.words = words;
            this.count = count;
        }

        static BitmapContainer fromLows(char[] lows)
        {
            long[] words = new long[BITMAP_WORDS];
            for (char low : lows)
            {
                int v = low; // char is already unsigned 0..65535
                words[v >>> 6] |= 1L << (v & 63);
            }
            return new BitmapContainer(words, lows.length);
        }

        long[] words()
        {
            return words;
        }

        @Override
        int cardinality()
        {
            return count;
        }

        @Override
        boolean contains(char low)
        {
            int v = low;
            return (words[v >>> 6] & (1L << (v & 63))) != 0;
        }

        @Override
        boolean add(char low)
        {
            int v = low;
            long bit = 1L << (v & 63);
            int w = v >>> 6;
            if ((words[w] & bit) == 0)
            {
                words[w] |= bit;
                count++;
                return true;
            }
            return false;
        }

        @Override
        boolean remove(char low)
        {
            int v = low;
            long bit = 1L << (v & 63);
            int w = v >>> 6;
            if ((words[w] & bit) != 0)
            {
                words[w] &= ~bit;
                count--;
                return true;
            }
            return false;
        }

        @Override
        char[] lows()
        {
            char[] out = new char[count];
            int idx = 0;
            for (int w = 0; w < BITMAP_WORDS; w++)
            {
                long bits = words[w];
                while (bits != 0)
                {
                    int b = Long.numberOfTrailingZeros(bits);
                    out[idx++] = (char) (w * 64 + b);
                    bits &= bits - 1;
                }
            }
            return out;
        }

        @Override
        char minLow()
        {
            for (int w = 0; w < BITMAP_WORDS; w++)
            {
                if (words[w] != 0)
                {
                    return (char) (w * 64 + Long.numberOfTrailingZeros(words[w]));
                }
            }
            throw new IllegalStateException("non-empty bitmap has a set bit");
        }

        @Override
        char maxLow()
        {
            for (int w = BITMAP_WORDS - 1; w >= 0; w--)
            {
                if (words[w] != 0)
                {
                    return (char) (w * 64 + (63 - Long.numberOfLeadingZeros(words[w])));
                }
            }
            throw new IllegalStateException("non-empty bitmap has a set bit");
        }
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.mmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalInt;
import java.util.function.IntConsumer;

import org.mapdb.collections.impl.RoaringU32;

/**
 * {@code MappedRoaringBitmap} — a <b>memory-mapped, read-only view</b> over a
 * {@link RoaringU32} bitmap that has been written to a file in its <b>frozen
 * canonical serialized form</b> ({@code spec/features/roaring-u32.md}). This is
 * the archeology-2 <b>B1 / memory-mapped feasibility probe</b>: it demonstrates
 * that the family's already-accepted, cross-language-validated byte format is
 * directly mmap-readable, so mmap-backed persistence needs <b>no new wire
 * format</b> (contrast the greenfield {@code memory-mapped.md} "MAPDBCOL" design,
 * which is a forever cross-language decision deferred to a cross-port effort).
 *
 * <h2>Why this is more than "read a file via mmap"</h2>
 * <p>{@link RoaringU32#deserialize(byte[])} materialises every container on the
 * Java heap. This view does <b>not</b>. At {@link #open} it parses only the
 * O(chunks) <b>chunk directory</b> (high key, container tag, cardinality, and the
 * absolute byte offset of each container payload) into small on-heap arrays; the
 * O(cardinality) payload — array key runs and 8 KiB bitmap words — stays in the
 * mapped file and is touched lazily, one page at a time, only when a query needs
 * it. So {@link #contains} reads the directory (heap) plus at most one payload
 * page, and a 100 MB bitmap answers membership without a 100 MB heap allocation.
 * This is the "working set larger than RAM" property the B-tier is built on.
 *
 * <h2>Reader posture (matches the frozen spec byte-for-byte)</h2>
 * <p>All multi-byte fields are <b>little-endian</b>. The layout constants below
 * mirror {@link RoaringU32}'s private serializer; they are <b>the frozen contract
 * </b> and MUST NOT drift. Values are carried in signed {@code int} but every
 * comparison is <b>unsigned u32</b> (the same Java carve-out as {@code RoaringU32}).
 *
 * <h2>What {@link #open} validates, and what it defers</h2>
 * <p>{@code open} performs the <b>structural</b> reader-MUST-reject checks that are
 * O(chunks): magic / version / reserved, {@code CHUNK_COUNT <= 65536}, strictly
 * ascending unsigned high keys, known container tag, zero pad, canonical
 * cardinality band per tag, every payload lies within the file, and no trailing
 * bytes. It <b>defers</b> the O(cardinality) <b>deep</b> checks (intra-array key
 * ordering/distinctness and bitmap popcount-equals-cardinality) because a lazy
 * view need not read the whole payload to answer a point query; a caller that
 * wants the full frozen validation over a trusted-length file should call
 * {@link #toRoaringU32()}, which runs {@link RoaringU32#deserialize(byte[])}. A
 * corrupt-but-structurally-valid payload can therefore yield a wrong
 * {@link #contains} answer — acceptable for the accidental-corruption model of a
 * probe, called out here as a known limitation.
 *
 * <h2>Lifecycle / platform</h2>
 * <p>{@code open} maps the whole file {@code READ_ONLY} into a single
 * {@link MappedByteBuffer} and closes the channel (the mapping stays valid until
 * the buffer is GC'd, per the {@code FileChannel#map} contract). The single-region
 * mapping caps at {@link Integer#MAX_VALUE} bytes ({@code open} rejects larger
 * files; multi-region mapping is a follow-up). {@link #close} flips a guard flag
 * so subsequent queries fail fast, but the JDK 17 baseline offers no portable
 * unmap, so the OS mapping is released on GC — fine on the Linux probe host,
 * noted for a Windows port.
 *
 * <p><b>Thread-safety:</b> immutable after {@code open} (the mapped buffer is read
 * with absolute, position-independent gets), so concurrent readers are safe.
 */
public final class MappedRoaringBitmap
{
    // ---- frozen layout constants (mirror RoaringU32's serializer) ----------

    /** Serialized header magic: {@code 0x32523055} (LE bytes {@code 55 30 52 32}). */
    private static final int MAGIC = 0x32523055;
    /** Serialized format version. */
    private static final int VERSION = 1;
    /** Fixed header size in bytes: MAGIC(4) + VERSION(2) + RESERVED(2) + CHUNK_COUNT(4). */
    private static final int HEADER_BYTES = 12;
    /** Per-chunk record header size: HIGH(2) + TAG(1) + PAD(1) + CARDINALITY_MINUS_1(2). */
    private static final int CHUNK_HEADER_BYTES = 6;

    private static final byte TAG_ARRAY = 0x01;
    private static final byte TAG_BITMAP = 0x02;

    /** Container-type break-even: {@code card <= 4096 => ARRAY}, else BITMAP. */
    private static final int ARRAY_MAX = 4096;
    /** A BITMAP is always 1024 {@code u64} words == 8192 payload bytes. */
    private static final int BITMAP_WORDS = 1024;
    private static final int BITMAP_PAYLOAD_BYTES = BITMAP_WORDS * 8;

    // ---- state -------------------------------------------------------------

    private final MappedByteBuffer buf;
    private final int byteLength;

    /** Per-chunk directory, parallel arrays, in unsigned high-key ascending order. */
    private final char[] highs;      // u16 high key of each chunk
    private final byte[] tags;        // TAG_ARRAY / TAG_BITMAP
    private final int[] cardinalities; // container cardinality (1..65536)
    private final int[] payloadPos;   // absolute byte offset of each container payload

    private volatile boolean closed;

    private MappedRoaringBitmap(MappedByteBuffer buf, int byteLength, char[] highs,
            byte[] tags, int[] cardinalities, int[] payloadPos)
    {
        this.buf = buf;
        this.byteLength = byteLength;
        this.highs = highs;
        this.tags = tags;
        this.cardinalities = cardinalities;
        this.payloadPos = payloadPos;
    }

    // ---- factory: write + open --------------------------------------------

    /**
     * Write {@code bitmap} to {@code path} in its frozen canonical serialized form
     * (creating or truncating the file), and return the number of bytes written.
     * The companion to {@link #open}; produces a file every language in the family
     * can read.
     */
    public static long write(Path path, RoaringU32 bitmap) throws IOException
    {
        byte[] image = bitmap.serialize();
        Files.write(path, image,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return image.length;
    }

    /**
     * Memory-map {@code path} read-only and return a lazy view over the frozen
     * canonical bitmap image it holds. Parses only the O(chunks) directory; the
     * payload stays in the mapped file. Throws {@link IllegalArgumentException} for
     * a structurally non-canonical / corrupt / foreign image (see the class doc for
     * which checks are deferred).
     */
    public static MappedRoaringBitmap open(Path path) throws IOException
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
        {
            long size = channel.size();
            if (size > Integer.MAX_VALUE)
            {
                throw new IllegalArgumentException(
                        "file too large for a single mapping: " + size + " bytes (> Integer.MAX_VALUE)");
            }
            if (size < HEADER_BYTES)
            {
                throw new IllegalArgumentException(
                        "truncated: file has " + size + " bytes, need at least " + HEADER_BYTES);
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return parseDirectory(mapped, (int) size);
        }
    }

    /**
     * Parse and structurally validate the header and chunk directory of an
     * already-mapped image. Package-visible seam so a caller can build a view over
     * a heap {@link ByteBuffer} too (the same reader is buffer-agnostic).
     */
    static MappedRoaringBitmap parseDirectory(MappedByteBuffer mapped, int byteLength)
    {
        mapped.order(ByteOrder.LITTLE_ENDIAN);
        int magic = mapped.getInt(0);
        if (magic != MAGIC)
        {
            throw new IllegalArgumentException(String.format("bad MAGIC: 0x%08x", magic));
        }
        int version = mapped.getShort(4) & 0xFFFF;
        if (version != VERSION)
        {
            throw new IllegalArgumentException("unsupported VERSION: " + version);
        }
        int reserved = mapped.getShort(6) & 0xFFFF;
        if (reserved != 0)
        {
            throw new IllegalArgumentException(String.format("non-zero RESERVED: 0x%04x", reserved));
        }
        long chunkCount = mapped.getInt(8) & 0xFFFFFFFFL;
        if (chunkCount > 65536L)
        {
            throw new IllegalArgumentException("CHUNK_COUNT > 65536: " + chunkCount);
        }

        int n = (int) chunkCount;
        char[] highs = new char[n];
        byte[] tags = new byte[n];
        int[] cards = new int[n];
        int[] payloadPos = new int[n];

        int pos = HEADER_BYTES;
        int prevHigh = -1; // -1 means "none yet"
        for (int i = 0; i < n; i++)
        {
            require(byteLength, pos, CHUNK_HEADER_BYTES, "chunk record header");
            int high = mapped.getShort(pos) & 0xFFFF;
            if (prevHigh >= 0 && high <= prevHigh)
            {
                throw new IllegalArgumentException(String.format(
                        "non-ascending or duplicate high key: 0x%04x after 0x%04x", high, prevHigh));
            }
            prevHigh = high;
            int tag = mapped.get(pos + 2) & 0xFF;
            int pad = mapped.get(pos + 3) & 0xFF;
            if (pad != 0)
            {
                throw new IllegalArgumentException(String.format("non-zero PAD: 0x%02x", pad));
            }
            int card = (mapped.getShort(pos + 4) & 0xFFFF) + 1; // CARDINALITY_MINUS_1 + 1
            pos += CHUNK_HEADER_BYTES;

            int payloadBytes;
            if (tag == TAG_ARRAY)
            {
                if (card > ARRAY_MAX)
                {
                    throw new IllegalArgumentException(
                            "non-canonical ARRAY cardinality " + card + " (> " + ARRAY_MAX + ")");
                }
                payloadBytes = card * 2;
            }
            else if (tag == TAG_BITMAP)
            {
                if (card <= ARRAY_MAX)
                {
                    throw new IllegalArgumentException(
                            "non-canonical BITMAP cardinality " + card + " (<= " + ARRAY_MAX + ")");
                }
                payloadBytes = BITMAP_PAYLOAD_BYTES;
            }
            else
            {
                throw new IllegalArgumentException(
                        String.format("unknown CONTAINER_TYPE tag: 0x%02x", tag));
            }
            require(byteLength, pos, payloadBytes, "container payload");

            highs[i] = (char) high;
            tags[i] = (byte) tag;
            cards[i] = card;
            payloadPos[i] = pos;
            pos += payloadBytes;
        }
        if (pos != byteLength)
        {
            throw new IllegalArgumentException(
                    (byteLength - pos) + " trailing bytes after chunk records");
        }
        return new MappedRoaringBitmap(mapped, byteLength, highs, tags, cards, payloadPos);
    }

    private static void require(int byteLength, int pos, int need, String what)
    {
        // pos + need can overflow int for pathological headers; compare as long.
        if ((long) pos + need > byteLength)
        {
            throw new IllegalArgumentException(String.format(
                    "truncated: %s needs %d bytes at offset %d, file has %d",
                    what, need, pos, byteLength));
        }
    }

    // ---- queries (read straight from the mapped payload) -------------------

    /** Whether {@code value} (an {@code i32} bit-reinterpreted to {@code u32}) is present. */
    public boolean contains(int value)
    {
        ensureOpen();
        int ci = findChunk((char) (value >>> 16));
        if (ci < 0)
        {
            return false;
        }
        char low = (char) (value & 0xFFFF);
        if (tags[ci] == TAG_ARRAY)
        {
            return arraySearch(payloadPos[ci], cardinalities[ci], low) >= 0;
        }
        // BITMAP: one word read, one bit test.
        int v = low; // char is unsigned 0..65535
        long word = buf.getLong(payloadPos[ci] + (v >>> 6) * 8);
        return (word & (1L << (v & 63))) != 0;
    }

    /** Logical cardinality (up to {@code 2^32}; held in a {@code long}). O(chunks), from the directory. */
    public long cardinality()
    {
        ensureOpen();
        long total = 0;
        for (int c : cardinalities)
        {
            total += c;
        }
        return total;
    }

    /** Number of non-empty chunks (the serialized {@code CHUNK_COUNT}). */
    public int chunkCount()
    {
        ensureOpen();
        return highs.length;
    }

    /** Whether the set is empty. */
    public boolean isEmpty()
    {
        ensureOpen();
        return highs.length == 0;
    }

    /** Total mapped byte length of the frozen image. */
    public int byteLength()
    {
        ensureOpen();
        return byteLength;
    }

    /**
     * Unsigned minimum present value (as the {@code i32} reinterpret), or empty if
     * the set is empty. The first chunk (lowest unsigned high) carries it.
     */
    public OptionalInt min()
    {
        ensureOpen();
        if (highs.length == 0)
        {
            return OptionalInt.empty();
        }
        return OptionalInt.of(join(highs[0], minLow(0)));
    }

    /** Unsigned maximum present value (as the {@code i32} reinterpret), or empty if empty. */
    public OptionalInt max()
    {
        ensureOpen();
        if (highs.length == 0)
        {
            return OptionalInt.empty();
        }
        int last = highs.length - 1;
        return OptionalInt.of(join(highs[last], maxLow(last)));
    }

    /**
     * Feed every present value, in <b>unsigned u32 ascending</b> order, to
     * {@code consumer} — reading straight from the mapped payload, never
     * materialising a container on the heap. Values arrive as {@code i32}
     * bit-reinterprets.
     */
    public void forEach(IntConsumer consumer)
    {
        ensureOpen();
        for (int ci = 0; ci < highs.length; ci++)
        {
            char high = highs[ci];
            int base = payloadPos[ci];
            if (tags[ci] == TAG_ARRAY)
            {
                int card = cardinalities[ci];
                for (int k = 0; k < card; k++)
                {
                    int low = buf.getShort(base + k * 2) & 0xFFFF;
                    consumer.accept(join(high, (char) low));
                }
            }
            else
            {
                for (int w = 0; w < BITMAP_WORDS; w++)
                {
                    long bits = buf.getLong(base + w * 8);
                    while (bits != 0)
                    {
                        int b = Long.numberOfTrailingZeros(bits);
                        consumer.accept(join(high, (char) (w * 64 + b)));
                        bits &= bits - 1;
                    }
                }
            }
        }
    }

    /**
     * Eagerly materialise the whole bitmap on the heap via the frozen
     * {@link RoaringU32#deserialize(byte[])} — which additionally runs the
     * <b>deep</b> canonical validation this lazy view defers. Copies the mapped
     * bytes; use only when the full in-RAM structure (set algebra, mutation) is
     * needed. Round-trips byte-for-byte: {@code toRoaringU32().serialize()} equals
     * the mapped image.
     */
    public RoaringU32 toRoaringU32()
    {
        ensureOpen();
        byte[] bytes = new byte[byteLength];
        ByteBuffer dup = buf.duplicate();
        dup.position(0);
        dup.get(bytes);
        return RoaringU32.deserialize(bytes);
    }

    /**
     * Release this view. Flips a guard so later queries throw; the underlying OS
     * mapping is released when the buffer is GC'd (no portable unmap on JDK 17).
     */
    public void close()
    {
        closed = true;
    }

    // ---- internals ---------------------------------------------------------

    /** Index of the chunk with high key {@code h}, or {@code -1} if absent (unsigned char bsearch). */
    private int findChunk(char h)
    {
        int lo = 0;
        int hi = highs.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            char midKey = highs[mid];
            if (midKey < h) // char compares are already unsigned
            {
                lo = mid + 1;
            }
            else if (midKey > h)
            {
                hi = mid - 1;
            }
            else
            {
                return mid;
            }
        }
        return -1;
    }

    /** Unsigned binary search of {@code low} in an ARRAY payload; index or {@code -(ins+1)}. */
    private int arraySearch(int base, int card, char low)
    {
        int lo = 0;
        int hi = card - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            char midKey = (char) (buf.getShort(base + mid * 2) & 0xFFFF);
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

    /** Lowest low-key of chunk {@code ci}, read from the payload. */
    private char minLow(int ci)
    {
        int base = payloadPos[ci];
        if (tags[ci] == TAG_ARRAY)
        {
            return (char) (buf.getShort(base) & 0xFFFF); // ARRAY keys are ascending
        }
        for (int w = 0; w < BITMAP_WORDS; w++)
        {
            long word = buf.getLong(base + w * 8);
            if (word != 0)
            {
                return (char) (w * 64 + Long.numberOfTrailingZeros(word));
            }
        }
        // Deep-corrupt image: a BITMAP declared non-empty but with no set bit. This
        // is one of the O(cardinality) checks open() defers, so surface it as the
        // IllegalArgumentException reject path (matching toRoaringU32()'s popcount
        // check) rather than crashing with a different exception type.
        throw new IllegalArgumentException("corrupt BITMAP payload: no set bit for a non-empty chunk");
    }

    /** Highest low-key of chunk {@code ci}, read from the payload. */
    private char maxLow(int ci)
    {
        int base = payloadPos[ci];
        if (tags[ci] == TAG_ARRAY)
        {
            return (char) (buf.getShort(base + (cardinalities[ci] - 1) * 2) & 0xFFFF);
        }
        for (int w = BITMAP_WORDS - 1; w >= 0; w--)
        {
            long word = buf.getLong(base + w * 8);
            if (word != 0)
            {
                return (char) (w * 64 + (63 - Long.numberOfLeadingZeros(word)));
            }
        }
        // See minLow: deep-corrupt (non-empty BITMAP with no set bit) -> reject path.
        throw new IllegalArgumentException("corrupt BITMAP payload: no set bit for a non-empty chunk");
    }

    /** Reassemble a full u32 (carried in signed {@code int}) from its halves. */
    private static int join(char high, char low)
    {
        return (high << 16) | low;
    }

    private void ensureOpen()
    {
        if (closed)
        {
            throw new IllegalStateException("view is closed");
        }
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.RoaringU32;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link RoaringU32} (spec {@code features/roaring-u32.md},
 * Java carve-out). Covers: the threshold in both directions, container type as
 * a pure function of cardinality (history-independence), unsigned ordering with
 * signed extremes, set-algebra canonicalization (XOR→array, OR→bitmap,
 * ANDNOT empties a chunk), serialize↔deserialize round-trip, every deserialize
 * rejection, empty/single/full-chunk, range build, and BITMAP bit order. The
 * <b>bytes are bit-exact</b> — there is NO Java relaxation.
 */
class RoaringU32Test
{
    private static final int ARRAY_MAX = 4096;
    private static final int MAGIC = 0x32523055;
    private static final int VERSION = 1;
    private static final byte TAG_ARRAY = 0x01;
    private static final byte TAG_BITMAP = 0x02;

    private static RoaringU32 build(int... vals)
    {
        RoaringU32 s = new RoaringU32();
        for (int v : vals)
        {
            s.add(v);
        }
        return s;
    }

    private static RoaringU32 range(int from, int toInclusive)
    {
        RoaringU32 s = new RoaringU32();
        for (int v = from; v <= toInclusive; v++)
        {
            s.add(v);
        }
        return s;
    }

    // ---- basic / empty / single -------------------------------------------

    @Test
    void emptySet()
    {
        RoaringU32 s = new RoaringU32();
        assertTrue(s.isEmpty());
        assertEquals(0L, s.cardinality());
        assertEquals(0, s.chunkCount());
        assertFalse(s.min().isPresent());
        assertFalse(s.max().isPresent());
        assertArrayEquals(new int[0], s.toSortedArray());
        assertEquals("0x553052320100000000000000", hex(s.serialize()));
        assertEquals(12, s.serialize().length);
    }

    @Test
    void singleElementArray()
    {
        RoaringU32 s = build(42);
        assertEquals(1L, s.cardinality());
        assertEquals(1, s.chunkCount());
        assertArrayEquals(new String[] {"array"}, s.containerTypes());
        assertEquals(42, s.min().getAsInt());
        assertEquals(42, s.max().getAsInt());
        // header(12) + chunk header(6) + one u16 low key (0x002a).
        assertEquals("0x5530523201000000010000000000010000002a00", hex(s.serialize()));
    }

    @Test
    void basicDistinctChunks()
    {
        RoaringU32 s = build(1, 70000, 140000, 200000);
        assertEquals(4L, s.cardinality());
        assertEquals(4, s.chunkCount());
        assertArrayEquals(new String[] {"array", "array", "array", "array"}, s.containerTypes());
        assertArrayEquals(new int[] {1, 70000, 140000, 200000}, s.toSortedArray());
        assertEquals(1, s.min().getAsInt());
        assertEquals(200000, s.max().getAsInt());
        // The frozen byte oracle (matches roaring_basic.json).
        assertEquals(
                "0x55305232010000000400000000000100000001000100010000007011020001000000e022030001000000400d",
                hex(s.serialize()));
    }

    @Test
    void idempotentAddRemove()
    {
        RoaringU32 s = build(5);
        assertFalse(s.add(5));
        assertTrue(s.add(6));
        assertTrue(s.remove(6));
        assertFalse(s.remove(6));
        assertFalse(s.remove(99));
        s.clear();
        assertTrue(s.isEmpty());
    }

    // ---- unsigned ordering with signed extremes ---------------------------

    @Test
    void unsignedOrderWithSignedExtremes()
    {
        RoaringU32 s = build(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE);
        // unsigned order: 0x00000000, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF.
        assertArrayEquals(new int[] {0, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}, s.toSortedArray());
        assertEquals(0, s.min().getAsInt());
        assertEquals(-1, s.max().getAsInt());
        assertEquals(4, s.chunkCount());
        // i32 -1 splits to high 0xFFFF (NOT sign-extended), low 0xFFFF.
        assertTrue(s.contains(-1));
        assertTrue(s.contains(Integer.MIN_VALUE));
        assertEquals(
                "0x5530523201000000040000000000010000000000ff7f01000000ffff0080010000000000ffff01000000ffff",
                hex(s.serialize()));
    }

    @Test
    void i32ReinterpretNotSignExtend()
    {
        // -1 => 0xFFFFFFFF => high 0xFFFF, low 0xFFFF (one chunk, not spread).
        RoaringU32 s = build(-1);
        assertEquals(1, s.chunkCount());
        assertArrayEquals(new int[] {-1}, s.toSortedArray());
        // INT_MIN => 0x80000000 => high 0x8000, low 0x0000.
        assertEquals(0x8000, build(Integer.MIN_VALUE).serialize()[12] & 0xFF
                | ((build(Integer.MIN_VALUE).serialize()[13] & 0xFF) << 8));
    }

    // ---- threshold both directions / history independence -----------------

    @Test
    void thresholdArray4096Bitmap4097()
    {
        RoaringU32 s = range(0, 4095); // cardinality 4096
        assertEquals(4096L, s.cardinality());
        assertArrayEquals(new String[] {"array"}, s.containerTypes()); // exactly 4096 is ARRAY
        s.add(4096); // -> 4097
        assertEquals(4097L, s.cardinality());
        assertArrayEquals(new String[] {"bitmap"}, s.containerTypes()); // 4097 is first BITMAP
    }

    @Test
    void arrayToBitmapAndBackSameBytes()
    {
        RoaringU32 grown = range(0, 4096); // 4097 -> BITMAP
        assertArrayEquals(new String[] {"bitmap"}, grown.containerTypes());
        grown.remove(4096); // back to 4096 -> ARRAY
        assertArrayEquals(new String[] {"array"}, grown.containerTypes());
        RoaringU32 never = range(0, 4095);
        assertArrayEquals(never.serialize(), grown.serialize()); // history-independent
    }

    @Test
    void containerTypePureFunctionOfCardinality()
    {
        // Reach cardinality 4096 by two different add/remove paths.
        RoaringU32 a = range(0, 4999); // 5000 -> BITMAP
        for (int v = 4096; v < 5000; v++)
        {
            a.remove(v);
        }
        RoaringU32 b = new RoaringU32();
        for (int v = 4095; v >= 0; v--)
        {
            b.add(v);
        }
        assertArrayEquals(new String[] {"array"}, a.containerTypes());
        assertArrayEquals(b.serialize(), a.serialize());
    }

    // ---- full chunk / bitmap bit order ------------------------------------

    @Test
    void fullChunk()
    {
        RoaringU32 s = range(0, 65535); // 65536 -> BITMAP
        assertEquals(65536L, s.cardinality());
        assertArrayEquals(new String[] {"bitmap"}, s.containerTypes());
        assertEquals(0, s.min().getAsInt());
        assertEquals(65535, s.max().getAsInt());
        byte[] bytes = s.serialize();
        // CARDINALITY_MINUS_1 == 0xFFFF at offset 12+4.
        assertEquals((byte) 0xFF, bytes[16]);
        assertEquals((byte) 0xFF, bytes[17]);
        assertEquals(12 + 6 + 8192, bytes.length);
        assertEquals(s.serialize().length, 8210);
        assertEquals(hex(bytes), hex(RoaringU32.deserialize(bytes).serialize()));
    }

    @Test
    void bitmapBitOrder()
    {
        // Force a BITMAP, then sparse low keys across distant words.
        RoaringU32 s = range(0, 4096); // 4097 -> BITMAP
        s.add(5000);
        s.add(9000);
        s.add(60000);
        s.add(65535);
        assertArrayEquals(new String[] {"bitmap"}, s.containerTypes());
        assertEquals(4101L, s.cardinality());
        byte[] bytes = s.serialize();
        RoaringU32 back = RoaringU32.deserialize(bytes);
        assertArrayEquals(s.toSortedArray(), back.toSortedArray());
        assertArrayEquals(bytes, back.serialize());
        assertTrue(s.contains(60000));
        assertTrue(s.contains(65535));
        assertFalse(s.contains(5001));
    }

    @Test
    void dropEmptyChunk()
    {
        RoaringU32 s = build(100000, 5);
        assertEquals(2, s.chunkCount());
        s.remove(100000);
        assertEquals(1, s.chunkCount());
        assertArrayEquals(build(5).serialize(), s.serialize());
    }

    // ---- range helpers ----------------------------------------------------

    @Test
    void addRangeRemoveRange()
    {
        RoaringU32 s = range(0, 4095);
        assertEquals(4096L, s.cardinality());
        for (int v = 100; v <= 200; v++)
        {
            s.remove(v);
        }
        assertEquals(4096L - 101, s.cardinality());
    }

    // ---- set algebra canonicalization -------------------------------------

    @Test
    void setAlgebraBasic()
    {
        RoaringU32 a = build(1, 2, 3, 70000);
        RoaringU32 b = build(2, 3, 4, 140000);
        assertArrayEquals(new int[] {1, 2, 3, 4, 70000, 140000}, a.or(b).toSortedArray());
        assertArrayEquals(new int[] {2, 3}, a.and(b).toSortedArray());
        assertArrayEquals(new int[] {1, 70000}, a.andNot(b).toSortedArray());
        assertArrayEquals(new int[] {1, 4, 70000, 140000}, a.xor(b).toSortedArray());
        // operands unchanged
        assertArrayEquals(new int[] {1, 2, 3, 70000}, a.toSortedArray());
    }

    @Test
    void xorBitmapNormalizesToArray()
    {
        RoaringU32 a = range(0, 4999); // BITMAP
        RoaringU32 b = range(0, 4999); // BITMAP
        for (int v = 5000; v < 5030; v++)
        {
            a.add(v);
        }
        assertArrayEquals(new String[] {"bitmap"}, a.containerTypes());
        assertArrayEquals(new String[] {"bitmap"}, b.containerTypes());
        RoaringU32 x = a.xor(b);
        assertEquals(30L, x.cardinality());
        assertArrayEquals(new String[] {"array"}, x.containerTypes()); // canonical for 30
    }

    @Test
    void orArrayNormalizesToBitmap()
    {
        RoaringU32 a = range(0, 2999); // ARRAY (3000)
        RoaringU32 b = range(2000, 5999); // BITMAP (4000)... 4000<=4096 so ARRAY
        assertArrayEquals(new String[] {"array"}, a.containerTypes());
        RoaringU32 u = a.or(b);
        assertEquals(6000L, u.cardinality());
        assertArrayEquals(new String[] {"bitmap"}, u.containerTypes()); // 6000 -> BITMAP
    }

    @Test
    void andNotEmptiesChunk()
    {
        RoaringU32 a = build(1, 2, 70000, 70001);
        RoaringU32 other = build(70000, 70001);
        RoaringU32 d = a.andNot(other);
        assertEquals(1, d.chunkCount());
        assertArrayEquals(new int[] {1, 2}, d.toSortedArray());
        assertArrayEquals(build(1, 2).serialize(), d.serialize());
    }

    @Test
    void intersectBitmapToArray()
    {
        RoaringU32 a = range(0, 4999); // BITMAP
        RoaringU32 b = range(4500, 9499); // BITMAP
        RoaringU32 i = a.and(b);
        assertEquals(500L, i.cardinality()); // 4500..4999
        assertArrayEquals(new String[] {"array"}, i.containerTypes()); // result type from result card
    }

    @Test
    void resultIndependentOfOperands()
    {
        RoaringU32 a = build(1, 2, 3);
        RoaringU32 b = build(3, 4, 5);
        RoaringU32 u = a.or(b);
        u.add(999);
        assertFalse(a.contains(999));
        assertFalse(b.contains(999));
        // only-A chunk copied, not aliased
        RoaringU32 a2 = build(1);
        RoaringU32 u2 = a2.or(new RoaringU32());
        a2.add(2);
        assertFalse(u2.contains(2));
    }

    // ---- serialize <-> deserialize round-trip -----------------------------

    @Test
    void roundtripRandom()
    {
        RoaringU32 s = new RoaringU32();
        long x = 0x12345678L;
        for (int i = 0; i < 20000; i++)
        {
            x = x * 6364136223846793005L + 1442695040888963407L;
            s.add((int) (x >>> 16));
        }
        byte[] bytes = s.serialize();
        RoaringU32 back = RoaringU32.deserialize(bytes);
        assertArrayEquals(s.toSortedArray(), back.toSortedArray());
        assertArrayEquals(bytes, back.serialize());
    }

    // ---- deserialize rejections -------------------------------------------

    private static byte[] validBytes()
    {
        return build(1, 70000).serialize();
    }

    @Test
    void rejectBadMagic()
    {
        byte[] b = validBytes();
        b[0] = 0x00;
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectBadVersion()
    {
        byte[] b = validBytes();
        b[4] = 0x02;
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonzeroReserved()
    {
        byte[] b = validBytes();
        b[6] = 0x01;
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonzeroPad()
    {
        byte[] b = validBytes();
        b[15] = 0x01; // first chunk PAD at offset 12+2+1
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectUnknownTag()
    {
        byte[] b = validBytes();
        b[14] = 0x03; // first chunk tag at offset 12+2 (a future RUN tag)
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectTrailingBytes()
    {
        byte[] b = validBytes();
        byte[] b2 = Arrays.copyOf(b, b.length + 1);
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b2));
    }

    @Test
    void rejectTruncated()
    {
        byte[] b = validBytes();
        assertThrows(IllegalArgumentException.class,
                () -> RoaringU32.deserialize(Arrays.copyOf(b, b.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> RoaringU32.deserialize(Arrays.copyOf(b, 5)));
    }

    @Test
    void rejectChunkCountTooLarge()
    {
        byte[] b = validBytes();
        // CHUNK_COUNT at offset 8..12, LE 70000.
        b[8] = (byte) 0x70;
        b[9] = (byte) 0x11;
        b[10] = (byte) 0x01;
        b[11] = (byte) 0x00;
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonCanonicalArrayCardinality()
    {
        // Hand-craft an ARRAY with cardinality 4097 (> ARRAY_MAX): illegal.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        leU32(out, MAGIC);
        leU16(out, VERSION);
        leU16(out, 0);
        leU32(out, 1); // 1 chunk
        leU16(out, 0); // high
        out.write(TAG_ARRAY);
        out.write(0);
        leU16(out, ARRAY_MAX); // card-1 = 4096 => card 4097
        for (int low = 0; low < 4097; low++)
        {
            leU16(out, low);
        }
        byte[] b = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonCanonicalBitmapCardinality()
    {
        // BITMAP with cardinality 1 (<= ARRAY_MAX): illegal.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        leU32(out, MAGIC);
        leU16(out, VERSION);
        leU16(out, 0);
        leU32(out, 1);
        leU16(out, 0);
        out.write(TAG_BITMAP);
        out.write(0);
        leU16(out, 0); // card 1
        for (int w = 0; w < 1024; w++)
        {
            leU64(out, w == 0 ? 1L : 0L);
        }
        byte[] b = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonAscendingArrayLows()
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        leU32(out, MAGIC);
        leU16(out, VERSION);
        leU16(out, 0);
        leU32(out, 1);
        leU16(out, 0);
        out.write(TAG_ARRAY);
        out.write(0);
        leU16(out, 1); // card 2
        leU16(out, 5);
        leU16(out, 5); // duplicate
        byte[] b = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectBitmapPopcountMismatch()
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        leU32(out, MAGIC);
        leU16(out, VERSION);
        leU16(out, 0);
        leU32(out, 1);
        leU16(out, 0);
        out.write(TAG_BITMAP);
        out.write(0);
        leU16(out, 4096); // claims card 4097
        for (int w = 0; w < 1024; w++)
        {
            leU64(out, 0L); // popcount 0
        }
        byte[] b = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    @Test
    void rejectNonAscendingChunkHighs()
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        leU32(out, MAGIC);
        leU16(out, VERSION);
        leU16(out, 0);
        leU32(out, 2);
        // chunk 1: high 5
        leU16(out, 5);
        out.write(TAG_ARRAY);
        out.write(0);
        leU16(out, 0);
        leU16(out, 0);
        // chunk 2: high 5 again (non-ascending)
        leU16(out, 5);
        out.write(TAG_ARRAY);
        out.write(0);
        leU16(out, 0);
        leU16(out, 0);
        byte[] b = out.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> RoaringU32.deserialize(b));
    }

    // ---- helpers ----------------------------------------------------------

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes)
        {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void leU16(java.io.ByteArrayOutputStream out, int v)
    {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void leU32(java.io.ByteArrayOutputStream out, int v)
    {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void leU64(java.io.ByteArrayOutputStream out, long v)
    {
        for (int i = 0; i < 8; i++)
        {
            out.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }
}

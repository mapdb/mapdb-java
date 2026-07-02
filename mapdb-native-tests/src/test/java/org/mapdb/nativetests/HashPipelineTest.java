// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.Hash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Native tests for the deterministic, byte-exact hash pipeline
 * ({@link Hash}, spec {@code features/hash-pipeline.md}). This feature has
 * <b>no Java carve-out</b>: it must be bit-identical to the other four ports.
 *
 * <p>Asserts the full 24-row {@code hash32} and 24-row {@code hash64}
 * test-vector tables, the 5 {@code positions_from_hashes} rows, the per-type
 * encoder pins (i32 reinterpret, i32-&gt;u64 zero-extend, LE+length byte fold,
 * tail handling), and the seed-fold identity — all compared as <b>bit patterns
 * (hex)</b>, never signed decimal, since Java prints {@code int}/{@code long}
 * signed.
 */
class HashPipelineTest
{
    // The four seeds from the spec's input matrix.
    private static final long[] SEEDS = {
            0x0000000000000000L,
            0x0000000000000001L,
            0x00000000ffffffffL,
            0xffffffff00000000L
    };

    // ---- hash32 (spec §"Authoritative hash32 outputs", 24 rows) ----------

    private static final int[] HASH32_WORDS = {
            0x00000000, 0x00000001, 0xffffffff, 0x80000000, 0x7fffffff, 0x04030201
    };

    // Rows × seeds (as in the spec table); values are u32 bit patterns.
    private static final int[][] HASH32_EXPECTED = {
            {0x00000000, 0x514e28b7, 0x81f16f39, 0x81f16f39},
            {0x514e28b7, 0x00000000, 0x7995c304, 0x7995c304},
            {0x81f16f39, 0x7995c304, 0x00000000, 0x00000000},
            {0x6d3c65a0, 0x8b7f7a6a, 0xf9cc0ea8, 0xf9cc0ea8},
            {0xf9cc0ea8, 0x551b50f6, 0x6d3c65a0, 0x6d3c65a0},
            {0xd839eaff, 0x54ec0422, 0xaf02bbbc, 0xaf02bbbc}
    };

    @Test
    void hash32VectorTable()
    {
        for (int row = 0; row < HASH32_WORDS.length; row++)
        {
            for (int col = 0; col < SEEDS.length; col++)
            {
                int got = Hash.hash32(HASH32_WORDS[row], SEEDS[col]);
                assertEquals(
                        hex32(HASH32_EXPECTED[row][col]),
                        hex32(got),
                        "hash32(" + hex32(HASH32_WORDS[row]) + ", " + hexSeed(SEEDS[col]) + ")");
            }
        }
    }

    @Test
    void hash32ZeroAnchor()
    {
        assertEquals(hex32(0x00000000), hex32(Hash.hash32(0, 0L)));
        assertEquals(hex32(0x514e28b7), hex32(Hash.hash32(1, 0L)));
        assertEquals(hex32(0x6d3c65a0), hex32(Hash.hash32(0x80000000, 0L)));
    }

    // ---- hash64 (spec §"Authoritative hash64 outputs", 24 rows) ----------

    private static final long[] HASH64_WORDS = {
            0x0000000000000000L,
            0x0000000000000001L,
            0x00000000ffffffffL,
            0x0000000080000000L,
            0xffffffffffffffffL,
            0x0807060504030201L
    };

    private static final long[][] HASH64_EXPECTED = {
            {0x0000000000000000L, 0xb456bcfc34c2cb2cL, 0xcc71ecda2aa8bcc6L, 0xc9213cd20c528300L},
            {0xb456bcfc34c2cb2cL, 0x0000000000000000L, 0x0789620c2ee64a3eL, 0x2640647a5ca0376bL},
            {0xcc71ecda2aa8bcc6L, 0x0789620c2ee64a3eL, 0x0000000000000000L, 0x64b5720b4b825f21L},
            {0xe3beca1f9a7e4886L, 0x81b875318ee00b8eL, 0x8a662c1a93a26b91L, 0xc4ca27146b0a922fL},
            {0x64b5720b4b825f21L, 0x3a8593886c55a02bL, 0xc9213cd20c528300L, 0xcc71ecda2aa8bcc6L},
            {0x9b57670c60240a13L, 0xda66ed8bc89ffb5fL, 0xbe7f6184429515e7L, 0x916bf52bf4cf0681L}
    };

    @Test
    void hash64VectorTable()
    {
        for (int row = 0; row < HASH64_WORDS.length; row++)
        {
            for (int col = 0; col < SEEDS.length; col++)
            {
                long got = Hash.hash64(HASH64_WORDS[row], SEEDS[col]);
                long expected = HASH64_EXPECTED[row][col];
                assertEquals(
                        hex64(expected),
                        hex64(got),
                        "hash64(" + hex64(HASH64_WORDS[row]) + ", " + hexSeed(SEEDS[col]) + ")");
                // Lane split (pins hash64_hi / hash64_lo).
                assertEquals(hex32((int) (expected >>> 32)), hex32(Hash.hash64Hi(HASH64_WORDS[row], SEEDS[col])));
                assertEquals(hex32((int) expected), hex32(Hash.hash64Lo(HASH64_WORDS[row], SEEDS[col])));
            }
        }
    }

    @Test
    void hash64Anchors()
    {
        assertEquals(hex64(0L), hex64(Hash.hash64(0L, 0L)));
        assertEquals(hex64(0xb456bcfc34c2cb2cL), hex64(Hash.hash64(1L, 0L)));
        // All-ones probes the logical >>> 33 (h>>33 = 0x7fffffff, top 33 bits 0).
        assertEquals(hex64(0x64b5720b4b825f21L), hex64(Hash.hash64(0xffffffffffffffffL, 0L)));
    }

    // ---- positions_from_hashes oracle (spec §position matrix, 5 rows) ----

    @Test
    void positionsFromHashesRows()
    {
        assertArrayEquals(new int[] {0, 1, 2, 3},
                Hash.positionsFromHashes(0x00000000, 0x00000001, 16, 4));
        assertArrayEquals(new int[] {10, 13, 0, 3},
                Hash.positionsFromHashes(0x0000000a, 0x00000003, 16, 4));
        assertArrayEquals(new int[] {15, 0, 1},
                Hash.positionsFromHashes(0xffffffff, 0x00000001, 16, 3));
        // i*h2 multiply wrap: i=2, 2*0x80000000 = 0x100000000 -> 0.
        assertArrayEquals(new int[] {2, 0, 2},
                Hash.positionsFromHashes(0x80000000, 0x80000000, 7, 3));
        // addition wrap + unsigned mod with high bit set.
        assertArrayEquals(new int[] {293, 295, 1, 3, 5},
                Hash.positionsFromHashes(0xfffffffd, 0x00000002, 1000, 5));
    }

    @Test
    void positionsPow2EqualsModulo()
    {
        byte[] input = {(byte) 42, 0, 0, 0};
        int[] v = Hash.positions(input, 64, 7);
        int h1 = Hash.hash32Bytes(input, 0L);
        int h2 = Hash.hash32Bytes(input, Hash.SALT2);
        for (int i = 0; i < v.length; i++)
        {
            int combined = h1 + i * h2;
            assertEquals(combined & 63, v[i]);
            assertEquals(Integer.remainderUnsigned(combined, 64), v[i]);
        }
    }

    @Test
    void positionsPublicUsesInternalSeeds()
    {
        byte[] input = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        int h1 = Hash.hash32Bytes(input, 0L);
        int h2 = Hash.hash32Bytes(input, Hash.SALT2);
        assertArrayEquals(
                Hash.positionsFromHashes(h1, h2, 1000, 5),
                Hash.positions(input, 1000, 5));
    }

    // ---- Encoder pins (spec §"Input → input-word derivation") ------------

    @Test
    void i32ReinterpretNotSignExtend()
    {
        // i32(-1) -> u32 0xffffffff (reinterpret; in Java the int bits ARE that).
        assertEquals(hex32(0xffffffff), hex32(Hash.encodeI32Word32(-1)));
        assertEquals(hex32(Hash.hash32(0xffffffff, 0L)), hex32(Hash.hash32Int32(-1, 0L)));
        assertEquals(hex32(0x80000000), hex32(Hash.encodeI32Word32(Integer.MIN_VALUE)));
        assertEquals(hex32(0x7fffffff), hex32(Hash.encodeI32Word32(Integer.MAX_VALUE)));
    }

    @Test
    void i32ZeroExtendForHash64()
    {
        // i32(-1) -> u64 0x00000000ffffffff (ZERO-extend, NOT 0xffffffffffffffff).
        assertEquals(hex64(0x00000000ffffffffL), hex64(Hash.encodeI32Word64(-1)));
        assertEquals(hex64(Hash.hash64(0x00000000ffffffffL, 0L)), hex64(Hash.hash64Int32(-1, 0L)));
        // Sign-extend trap made observable: zero-extended != all-ones.
        assertNotEquals(hex64(Hash.hash64Int32(-1, 0L)), hex64(Hash.hash64(0xffffffffffffffffL, 0L)));
        assertEquals(hex64(0x0000000080000000L), hex64(Hash.encodeI32Word64(Integer.MIN_VALUE)));
    }

    @Test
    void bytesLeFold32()
    {
        // [01 02 03 04] reads LE to lane 0x04030201 then XOR len(4).
        assertEquals(hex32(0x04030201 ^ 4), hex32(Hash.encodeBytesWord32(new byte[] {1, 2, 3, 4})));
        assertEquals(hex32(Hash.hash32(0x04030201 ^ 4, 0L)),
                hex32(Hash.hash32Bytes(new byte[] {1, 2, 3, 4}, 0L)));
        // Scenario pin: hash_bytes 0x01020304 == hash_word32 0x04030205. Ref 0x318f91ff.
        assertEquals(hex32(0x318f91ff), hex32(Hash.hash32Bytes(new byte[] {1, 2, 3, 4}, 0L)));
    }

    @Test
    void bytesLeFold64()
    {
        // [01..08] reads LE to lane 0x0807060504030201 then XOR len(8).
        byte[] b = {1, 2, 3, 4, 5, 6, 7, 8};
        assertEquals(hex64(0x0807060504030201L ^ 8L), hex64(Hash.encodeBytesWord64(b)));
        assertEquals(hex64(Hash.hash64(0x0807060504030201L ^ 8L, 0L)), hex64(Hash.hash64Bytes(b, 0L)));
        // Scenario pin: input word 0x0807060504030209, ref hash64 0xa1dfdbe3d274f81c.
        assertEquals(hex64(0xa1dfdbe3d274f81cL), hex64(Hash.hash64Bytes(b, 0L)));
    }

    @Test
    void bytesTailAndLengthDistinguish()
    {
        int h3 = Hash.hash32Bytes(new byte[] {1, 2, 3}, 0L);
        int h2 = Hash.hash32Bytes(new byte[] {1, 2}, 0L);
        int h4 = Hash.hash32Bytes(new byte[] {1, 2, 3, 0}, 0L);
        assertNotEquals(hex32(h3), hex32(h2));
        assertNotEquals(hex32(h3), hex32(h4));
        // [00] != [00,00] (length XOR distinguishes equal-byte tails).
        assertNotEquals(hex32(Hash.hash32Bytes(new byte[] {0}, 0L)),
                hex32(Hash.hash32Bytes(new byte[] {0, 0}, 0L)));
        // Tail in LOW bytes: [01] folds to lane 0x00000001, then XOR len 1.
        assertEquals(hex32(0x00000001 ^ 1), hex32(Hash.encodeBytesWord32(new byte[] {1})));
        // Cross-language tail-probe reference values from the shared scenarios.
        assertEquals(hex32(0xbb675c79), hex32(Hash.hash32Bytes(new byte[] {1, 2, 3}, 0L)));
        assertEquals(hex32(0xa79ac21a), hex32(Hash.hash32Bytes(new byte[] {1, 2}, 0L)));
        assertEquals(hex32(0x0849ef57), hex32(Hash.hash32Bytes(new byte[] {1, 2, 3, 0}, 0L)));
    }

    // ---- Seed fold identity (spec Invariant: high seed word participates) -

    @Test
    void seedFoldIdentity()
    {
        // 0x00000000ffffffff and 0xffffffff00000000 both fold to seed32=0xffffffff.
        assertEquals(
                hex32(Hash.hash32(0x12345678, 0x00000000ffffffffL)),
                hex32(Hash.hash32(0x12345678, 0xffffffff00000000L)));
        // Different folds -> different hashes.
        assertNotEquals(
                hex32(Hash.hash32(0x12345678, 0x0000000000000001L)),
                hex32(Hash.hash32(0x12345678, 0x0000000000000002L)));
        // High word genuinely affects the fold.
        assertNotEquals(
                hex32(Hash.hash32(0x12345678, 0x0000000100000000L)),
                hex32(Hash.hash32(0x12345678, 0x0000000000000000L)));
    }

    // ---- hllSplit sanity (pre-stated) ------------------------------------

    @Test
    void hllSplitBasic()
    {
        byte[] input = {(byte) 'x'};
        long x = Hash.hash64Bytes(input, 0L);
        int[] r = Hash.hllSplit(input, 12);
        assertEquals((int) (x >>> (64 - 12)), r[0]);
        org.junit.jupiter.api.Assertions.assertTrue(r[1] >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(Integer.compareUnsigned(r[0], 1 << 12) < 0);
    }

    // ---- helpers: compare as bit patterns, never signed decimal -----------

    private static String hex32(int v)
    {
        return String.format("0x%08x", v & 0xFFFFFFFFL);
    }

    private static String hex64(long v)
    {
        return "0x" + String.format("%016x", v);
    }

    private static String hexSeed(long v)
    {
        return "0x" + String.format("%016x", v);
    }
}

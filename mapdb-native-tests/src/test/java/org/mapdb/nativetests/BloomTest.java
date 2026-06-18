// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.Bloom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the Bloom filter ({@link Bloom}, spec
 * {@code features/bloom.md}). This feature has <b>no bit-array carve-out</b>: its
 * bits/popcount/serialization must be bit-identical to the other four ports. The
 * only Java specific is the boxed element edge (here exercised via primitive
 * {@code int} adds). Bit patterns are compared as <b>hex strings</b>, never
 * signed decimal.
 */
class BloomTest
{
    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ---- The worked example (spec §"Serialized bit-array form") ----------

    @Test
    void workedExampleAdd7()
    {
        // with_params(16, 4); add(7). positions(7, 16, 4) = [7, 0, 9, 2] (from
        // 12-hash-pipeline/positions_basic.json). Bits {0, 2, 7, 9} set.
        Bloom b = Bloom.withParams(16, 4);
        assertTrue(b.isEmpty());
        b.add(7);
        assertArrayEquals(new int[] {0, 2, 7, 9}, b.setBits());
        assertEquals(4, b.bitCount());
        assertFalse(b.isEmpty());
        // Byte 0: bits 0,2,7 -> 0x01|0x04|0x80 = 0x85. Byte 1: bit 9 -> 0x02.
        assertArrayEquals(new byte[] {(byte) 0x85, (byte) 0x02}, b.toBytes());
        assertEquals("0x8502", hex(b.toBytes()));
        assertTrue(b.mightContain(7));
        assertTrue(b.contains(7));
    }

    // ---- The five spec sanity checks (hex bit patterns) ------------------

    @Test
    void sanityChecks()
    {
        // (16,4)+add(7) -> 0x8502 bit_count 4.
        Bloom a = Bloom.withParams(16, 4);
        a.add(7);
        assertEquals("0x8502", hex(a.toBytes()));
        assertEquals(4, a.bitCount());

        // (64,3,{10,20,30}) -> 0x0020002200000298 bit_count 7.
        Bloom c = Bloom.withParams(64, 3);
        c.add(10);
        c.add(20);
        c.add(30);
        assertEquals("0x0020002200000298", hex(c.toBytes()));
        assertEquals(7, c.bitCount());

        // (5,3,add 0) -> 0x18 bit_count 2 (positions collapse to {3,4}).
        Bloom d = Bloom.withParams(5, 3);
        d.add(0);
        assertEquals("0x18", hex(d.toBytes()));
        assertEquals(2, d.bitCount());

        // (8,3,{1,2,3}) -> 0xf5; contains_9 true (false positive), contains_4 false.
        Bloom f = Bloom.withParams(8, 3);
        f.add(1);
        f.add(2);
        f.add(3);
        assertEquals("0xf5", hex(f.toBytes()));
        assertTrue(f.mightContain(9));
        assertFalse(f.mightContain(4));

        // two (32,3) union -> 0xd0614504 union_bit_count 10.
        Bloom u1 = Bloom.withParams(32, 3);
        u1.add(1);
        u1.add(2);
        Bloom u2 = Bloom.withParams(32, 3);
        u2.add(100);
        u2.add(200);
        Bloom union = u1.union(u2);
        assertEquals("0xd0614504", hex(union.toBytes()));
        assertEquals(10, union.bitCount());
    }

    // ---- optimal() pinned integer table (spec §"Construction") -----------

    @Test
    void optimalIntegerTable()
    {
        long[][] cases = {
                {1000, 9586, 7},
                {1000, 14378, 10},
                {10000, 95851, 7},
                {100, 480, 3},
                {1, 2, 1}
        };
        double[] ps = {0.01, 0.001, 0.01, 0.1, 0.5};
        for (int i = 0; i < cases.length; i++)
        {
            Bloom b = Bloom.optimal(cases[i][0], ps[i]);
            assertEquals((int) cases[i][1], b.mBits(), "optimal(" + cases[i][0] + ", " + ps[i] + ") m");
            assertEquals((int) cases[i][2], b.k(), "optimal(" + cases[i][0] + ", " + ps[i] + ") k");
        }
    }

    @Test
    void optimalInvalidInputsTrap()
    {
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(100, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(100, 1.0));
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(100, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(100, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Bloom.optimal(100, Double.NEGATIVE_INFINITY));
    }

    // ---- m = 0 trap; k = 0 vacuous-true ----------------------------------

    @Test
    void mZeroTraps()
    {
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(0, 4));
    }

    @Test
    void negativeParamsTrap()
    {
        // The Java subset carries m/k as signed int; a negative m (a u32 with the
        // high bit set) or negative k is outside the subset and must trap rather
        // than build a half-unsigned object.
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(Integer.MIN_VALUE, 4));
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(16, -1));
    }

    @Test
    void kZeroVacuousTrue()
    {
        // k=0: add sets no bits; mightContain is vacuously true for everything.
        Bloom b = Bloom.withParams(16, 0);
        b.add(5);
        assertEquals(0, b.bitCount());
        assertTrue(b.isEmpty());
        assertEquals("0x0000", hex(b.toBytes()));
        assertTrue(b.mightContain(5));
        assertTrue(b.mightContain(9999));
        assertTrue(b.mightContain(-1));
    }

    // ---- union OR + mismatch throw ---------------------------------------

    @Test
    void unionIsBitwiseOr()
    {
        Bloom a = Bloom.withParams(32, 3);
        a.add(1);
        a.add(2);
        Bloom c = Bloom.withParams(32, 3);
        c.add(100);
        c.add(200);
        Bloom u = a.union(c);
        // No false negative for either operand's elements.
        assertTrue(u.mightContain(1));
        assertTrue(u.mightContain(2));
        assertTrue(u.mightContain(100));
        assertTrue(u.mightContain(200));
        // Union bits are exactly the OR of the two serialized bit arrays.
        byte[] ab = a.toBytes();
        byte[] cb = c.toBytes();
        byte[] ub = u.toBytes();
        assertEquals(ab.length, ub.length);
        for (int i = 0; i < ub.length; i++)
        {
            assertEquals((byte) (ab[i] | cb[i]), ub[i], "byte " + i);
        }
    }

    @Test
    void unionMMismatchThrows()
    {
        Bloom a = Bloom.withParams(16, 4);
        Bloom b = Bloom.withParams(32, 4);
        assertThrows(IllegalArgumentException.class, () -> a.union(b));
    }

    @Test
    void unionKMismatchThrows()
    {
        Bloom a = Bloom.withParams(16, 4);
        Bloom b = Bloom.withParams(16, 3);
        assertThrows(IllegalArgumentException.class, () -> a.union(b));
    }

    // ---- idempotent / order-independent add ------------------------------

    @Test
    void addIsIdempotent()
    {
        Bloom once = Bloom.withParams(16, 4);
        once.add(7);
        Bloom twice = Bloom.withParams(16, 4);
        twice.add(7);
        twice.add(7);
        assertArrayEquals(once.toBytes(), twice.toBytes());
        assertEquals(once.bitCount(), twice.bitCount());
    }

    @Test
    void addIsOrderIndependent()
    {
        Bloom forward = Bloom.withParams(64, 3);
        forward.add(10);
        forward.add(20);
        forward.add(30);
        Bloom reverse = Bloom.withParams(64, 3);
        reverse.add(30);
        reverse.add(20);
        reverse.add(10);
        assertArrayEquals(forward.toBytes(), reverse.toBytes());
    }

    // ---- signed extremes (reinterpret, not sign-extend) ------------------

    @Test
    void signedExtremesReinterpret()
    {
        // -1 -> LE bytes ff ff ff ff (NOT sign-extend to 8 bytes); INT_MIN -> 00 00 00 80.
        Bloom b = Bloom.withParams(128, 4);
        b.add(-1);
        b.add(Integer.MIN_VALUE);
        assertEquals("0x00000000062000000044000880004000", hex(b.toBytes()));
        assertEquals(8, b.bitCount());
        assertArrayEquals(new int[] {33, 34, 45, 74, 78, 91, 103, 118}, b.setBits());
        assertTrue(b.mightContain(-1));
        assertTrue(b.mightContain(Integer.MIN_VALUE));
    }

    // ---- no false negative over a set ------------------------------------

    @Test
    void noFalseNegativeOverASet()
    {
        Bloom b = Bloom.withParams(256, 5);
        int[] elems = {0, 1, -1, 7, 42, 100, -2147483648, 2147483647, 123456, -987654};
        for (int v : elems)
        {
            b.add(v);
        }
        for (int v : elems)
        {
            assertTrue(b.mightContain(v), "no false negative for " + v);
        }
    }

    // ---- LSB-first / tail-zero serialization (m not a multiple of 8) -----

    @Test
    void tailBitsAreZero()
    {
        // m=13: bytes length ceil(13/8)=2; positions stay < 13. Bits {3,4,7,10}.
        Bloom b = Bloom.withParams(13, 3);
        b.add(7);
        b.add(42);
        assertArrayEquals(new int[] {3, 4, 7, 10}, b.setBits());
        assertEquals(4, b.bitCount());
        // The unused high bits of byte 1 (bits 13,14,15) MUST be 0 -> byte1 = 0x04.
        assertEquals("0x9804", hex(b.toBytes()));
        assertEquals(2, b.toBytes().length);
        for (int p : b.setBits())
        {
            assertTrue(p < 13, "set bit " + p + " must be < m=13");
        }
    }

    @Test
    void lsbFirstBitOrder()
    {
        // bit 0 -> 0x01 of byte 0; bit 8 -> 0x01 of byte 1. positions(7,16,4)
        // sets bit 0 -> byte0 low bit; bit 9 -> byte1 bit1 = 0x02.
        Bloom b = Bloom.withParams(16, 4);
        b.add(7);
        byte[] out = b.toBytes();
        assertEquals((byte) 0x85, out[0]); // bits 0,2,7
        assertEquals((byte) 0x02, out[1]); // bit 9
    }

    // ---- empty filter --------------------------------------------------

    @Test
    void emptyFilterSerializesAllZeroOfFullLength()
    {
        Bloom b = Bloom.withParams(16, 4);
        assertEquals(0, b.bitCount());
        assertTrue(b.isEmpty());
        assertEquals("0x0000", hex(b.toBytes()));
        assertArrayEquals(new int[] {}, b.setBits());
        assertFalse(b.mightContain(7)); // k>=1: some position always clear
    }
}

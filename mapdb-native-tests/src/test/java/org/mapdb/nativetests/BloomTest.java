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
        // setBits() returns long[] (u32-correct, non-negative); bitCount() long.
        long[] sb = b.setBits();
        assertArrayEquals(new long[] {0L, 2L, 7L, 9L}, sb);
        long bc = b.bitCount();
        assertEquals(4L, bc);
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
            assertEquals(cases[i][1], b.mBits(), "optimal(" + cases[i][0] + ", " + ps[i] + ") m");
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
    void outOfDomainParamsTrap()
    {
        // m is the full u32 domain (1..=2^32-1); k is bounded to
        // 0..=Integer.MAX_VALUE (the boxed-Java carve-out). A negative value, or
        // an m past the u32 ceiling, is outside the domain and must trap.
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(-1L, 4L));
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(Long.MIN_VALUE, 4L));
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(16L, -1L));
        // m past the u32 ceiling (2^32 == 4294967296) -> out of domain.
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(4294967296L, 4L));
    }

    @Test
    void mU32DomainAcceptsValuesAboveIntMax()
    {
        // m is the full u32 domain (with_params(m_bits: u32, ...)); a value
        // > Integer.MAX_VALUE is LEGAL and must NOT be rejected — bit/word
        // indexing is unsigned, so the whole range is meaningful. A value past
        // the u32 ceiling, on the other hand, is rejected.
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(4294967296L, 4L));

        // A moderately large, allocatable m (5_000_000 bits ~= 78125 longs) must
        // round-trip add -> mightContain with NO overflow in bitCount/setBits.
        long allocM = 5_000_000L;
        Bloom m2 = Bloom.withParams(allocM, 4L);
        assertEquals(allocM, m2.mBits());
        assertTrue(m2.isEmpty());
        m2.add(7);
        assertEquals(4, m2.bitCount());
        assertEquals(4, m2.setBits().length);
        assertTrue(m2.mightContain(7));
        // toBytes is ceil(allocM/8) bytes long.
        assertEquals((int) ((allocM + 7L) / 8L), m2.toBytes().length);
    }

    @Test
    void mBitsReturnsNonNegativeLongForLargeU32()
    {
        // Witness: m >= 2^31 is stored as a u32 bit pattern in a signed int.
        // mBits() previously returned int, so withParams(2^31, 0).mBits() was
        // -2147483648. It now returns the unsigned value as a non-negative long.
        // (2^31 bits => a ~256 MiB long[], allocatable under the -Xmx2048m test
        // heap; k = 0 so add/contain do no work.)
        long m = 2147483648L; // 2^31
        Bloom b = Bloom.withParams(m, 0L);
        assertEquals(m, b.mBits());
        assertTrue(b.mBits() >= 0L, "mBits() must never be negative");
    }

    @Test
    void bitCountAndSetBitsAreLongTyped()
    {
        // Parity guard: the spec's bit_count and set_bits live in the u32 domain
        // (0..=2^32-1). Java has no unsigned int, so this port returns them as
        // non-negative longs. Assert the STATIC return types are long / long[]
        // (a regression to int / int[] would not compile these lines).
        Bloom b = Bloom.withParams(16, 4);
        b.add(7);
        long bc = b.bitCount();         // must be assignable to long
        long[] sb = b.setBits();        // must be a long[]
        assertEquals(4L, bc);
        assertArrayEquals(new long[] {0L, 2L, 7L, 9L}, sb);
    }

    @Test
    void u32IndexMathDoesNotOverflowConceptually()
    {
        // The count/index logic must hold the full u32 domain without wrapping
        // negative — the bug being fixed. We exercise the math directly rather
        // than allocating a multi-GB filter:
        //
        //   bit_count: a popcount up to 2^32-1 must stay a non-negative long
        //   (a 32-bit signed counter would wrap negative past 2^31).
        long bigCountA = 0xFFFF_FFFFL;          // 2^32-1, the u32 ceiling
        long bigCountB = (long) Integer.MAX_VALUE + 1L; // 2^31, the int wrap point
        assertTrue(bigCountA > 0L, "u32-max count stays positive as a long");
        assertTrue(bigCountB > 0L, "2^31 count stays positive as a long");
        // The same value cast to a (buggy) signed int would be negative:
        assertTrue((int) bigCountA < 0, "u32-max would wrap negative in an int");
        assertTrue((int) bigCountB < 0, "2^31 would wrap negative in an int");
        //
        //   set_bits index: (long) wordIndex * 64 + bitInWord must stay a
        //   non-negative long even when the bit index exceeds 2^31. Reproduce
        //   the exact arithmetic Bloom.setBits() uses for a word index whose
        //   resulting bit index is > 2^31 (and would be a negative int).
        int highWordIndex = 0x0400_0000; // 2^26 words -> bit index 2^32, > int range
        int bitInWord = 5;
        long idx = (long) highWordIndex * 64L + bitInWord; // the setBits() formula
        assertEquals((1L << 32) + 5L, idx);
        assertTrue(idx > (long) Integer.MAX_VALUE, "index exceeds int range");
        assertTrue(idx > 0L, "index stays non-negative as a long");
        // The 32-bit-truncated version of the same computation silently produces
        // the WRONG value (the * 64 overflows int), proving long arithmetic is
        // required: highWordIndex*64 = 2^32 truncates to 0 in int, so the int
        // result is 5 rather than the true 2^32+5.
        assertEquals(5, highWordIndex * 64 + bitInWord);
        assertTrue((highWordIndex * 64 + bitInWord) != idx,
                "the int-arithmetic version is wrong (truncated)");

        // A word index whose bit index lands strictly between 2^31 and 2^32
        // would render NEGATIVE under int arithmetic but stays positive as long.
        int midWordIndex = (1 << 25) + 1; // bit index ~2^31+64, in (2^31, 2^32)
        long midIdx = (long) midWordIndex * 64L;
        assertTrue(midIdx > (long) Integer.MAX_VALUE && midIdx < (1L << 32));
        assertTrue(midIdx > 0L, "stays non-negative as a long");
        assertTrue(midWordIndex * 64 < 0, "would be negative under int arithmetic");
    }

    @Test
    void highRepresentableKAddAndContain()
    {
        // A high-but-representable k (well within int[] limits) must exercise the
        // add/mightContain position loop without crashing. positions are derived
        // and the k bits set; no false negative for the added element.
        int k = 1000;
        Bloom b = Bloom.withParams(8192, k);
        assertTrue(b.isEmpty());
        b.add(7);
        assertFalse(b.isEmpty());
        // bit_count counts DISTINCT set bits (positions collide for large k over
        // a finite m), so it is <= k.
        assertTrue(b.bitCount() > 0);
        assertTrue(b.bitCount() <= k);
        assertTrue(b.mightContain(7));         // no false negative
        // setBits length equals bitCount, no overflow.
        assertEquals(b.bitCount(), b.setBits().length);
    }

    @Test
    void kAboveIntMaxRejected()
    {
        // The boxed-Java carve-out: k is bounded to 0..=Integer.MAX_VALUE because
        // each element's positions are a Java int[] of length k. A k > 2^31-1 is
        // rejected up front with IllegalArgumentException (NOT a later
        // NegativeArraySizeException crash inside add/mightContain).
        long overK = (long) Integer.MAX_VALUE + 1L; // 2^31
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Bloom.withParams(16L, overK));
        assertTrue(ex.getMessage().contains("int[]"),
                "message should explain the int[] positions limit: " + ex.getMessage());
        // The full u32 ceiling for k is likewise rejected (it was accepted before
        // the fix, then crashed on add with NegativeArraySizeException).
        assertThrows(IllegalArgumentException.class, () -> Bloom.withParams(16L, 0xFFFF_FFFFL));
        // Integer.MAX_VALUE itself is the boundary and is ACCEPTED (representable).
        Bloom edge = Bloom.withParams(16L, (long) Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, edge.k());
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
        assertEquals(8L, b.bitCount());
        assertArrayEquals(new long[] {33L, 34L, 45L, 74L, 78L, 91L, 103L, 118L}, b.setBits());
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
        assertArrayEquals(new long[] {3L, 4L, 7L, 10L}, b.setBits());
        assertEquals(4L, b.bitCount());
        // The unused high bits of byte 1 (bits 13,14,15) MUST be 0 -> byte1 = 0x04.
        assertEquals("0x9804", hex(b.toBytes()));
        assertEquals(2, b.toBytes().length);
        for (long p : b.setBits())
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
        assertEquals(0L, b.bitCount());
        assertTrue(b.isEmpty());
        assertEquals("0x0000", hex(b.toBytes()));
        assertArrayEquals(new long[] {}, b.setBits());
        assertFalse(b.mightContain(7)); // k>=1: some position always clear
    }
}

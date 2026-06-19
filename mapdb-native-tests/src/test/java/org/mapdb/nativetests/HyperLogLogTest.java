// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.HyperLogLog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link HyperLogLog} (spec {@code features/hyperloglog.md}).
 *
 * <p>The INTEGER register array is the cross-language oracle (covered by the
 * shared 13-hyperloglog scenarios); these tests pin the Java-side internals the
 * shared suite cannot reach: the rho/{@code numberOfLeadingZeros}/guard-bit
 * path (incl. the all-zero remainder), the top-p-bits index, the unsigned
 * register max + idempotence + order-independence, zero-extend (NOT sign-extend),
 * merge max + p-mismatch throw, serialization round-trip + every fromBytes
 * rejection, p-range errors, and the float-quarantined estimate (native-only,
 * tolerance-bounded). Register bytes are compared as UNSIGNED values.
 */
class HyperLogLogTest
{
    // ---- a re-derived oracle split, independent of the implementation -----

    /** {@code idx} = top p bits; {@code rho} = clz64((x<<p)|guard)+1. */
    private static int[] expectedSplit(int item, int p)
    {
        long x = Hash.hash64(item & 0xFFFFFFFFL, 0L);
        int idx = (int) (x >>> (64 - p));
        long w = (x << p) | (1L << (p - 1));
        int rho = Long.numberOfLeadingZeros(w) + 1;
        return new int[] {idx, rho};
    }

    private static int unsigned(byte b)
    {
        return b & 0xFF;
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ---- construction & p-range -------------------------------------------

    @Test
    void withPrecisionAllocatesMRegisters()
    {
        for (int p = HyperLogLog.MIN_PRECISION; p <= HyperLogLog.MAX_PRECISION; p++)
        {
            HyperLogLog h = HyperLogLog.withPrecision(p);
            assertEquals(1 << p, h.registerCount());
            assertEquals(0, h.nonzeroRegisters());
            assertEquals(0, h.maxRegister());
            for (byte r : h.registers())
            {
                assertEquals(0, r);
            }
        }
    }

    @Test
    void pOutOfRangeThrowsNeverClamps()
    {
        for (int p : new int[] {3, 19, 0, -1, 255})
        {
            assertThrows(IllegalArgumentException.class, () -> HyperLogLog.withPrecision(p));
        }
    }

    // ---- rho / numberOfLeadingZeros / guard-bit exactness -----------------

    @Test
    void guardBitAllZeroRemainderGivesMaxRho()
    {
        // add(0): hash64(0,0) == 0, so the remaining bits are all zero; the guard
        // bit pins rho = 64 - p + 1 (the per-p max) at idx 0.
        assertEquals(0L, Hash.hash64(0L, 0L), "hash64(0,0) must be 0 for the all-zero-remainder probe");
        for (int p : new int[] {4, 7, 14, 18})
        {
            HyperLogLog h = HyperLogLog.withPrecision(p);
            h.add(0);
            int ceiling = 64 - p + 1;
            assertEquals(ceiling, unsigned(h.registers()[0]), "all-zero-remainder rho at p=" + p);
            assertEquals(ceiling, h.maxRegister());
            assertEquals(1, h.nonzeroRegisters());
        }
    }

    @Test
    void idxIsTopPBitsLogicalShift()
    {
        // A high-bit-set hash must use a LOGICAL (>>>) shift for the index; the
        // re-derived split agrees with what add() writes.
        for (int p : new int[] {4, 10, 18})
        {
            HyperLogLog h = HyperLogLog.withPrecision(p);
            int[] split = expectedSplit(123456789, p);
            h.add(123456789);
            assertTrue(split[0] >= 0 && split[0] < (1 << p), "idx in range at p=" + p);
            assertEquals(split[1], unsigned(h.registers()[split[0]]), "add writes rho at idx at p=" + p);
        }
    }

    @Test
    void rhoWithinPerPBounds()
    {
        for (int p = HyperLogLog.MIN_PRECISION; p <= HyperLogLog.MAX_PRECISION; p++)
        {
            int ceiling = 64 - p + 1;
            for (int item = 0; item < 2000; item++)
            {
                int[] split = expectedSplit(item, p);
                assertTrue(split[0] >= 0 && split[0] < (1 << p));
                assertTrue(split[1] >= 1 && split[1] <= ceiling, "rho in [1," + ceiling + "] at p=" + p);
            }
        }
    }

    // ---- register max-update, idempotence, order-independence -------------

    @Test
    void addUpdatesExpectedRegisterToRho()
    {
        int p = 14;
        HyperLogLog h = HyperLogLog.withPrecision(p);
        int[] split = expectedSplit(42, p);
        h.add(42);
        assertEquals(split[1], unsigned(h.registers()[split[0]]));
        assertEquals(1, h.nonzeroRegisters());
        assertEquals(split[1], h.maxRegister());
    }

    @Test
    void addIsMaxNotOverwriteAndIdempotent()
    {
        int p = 4;
        HyperLogLog a = HyperLogLog.withPrecision(p);
        a.add(7);
        HyperLogLog b = HyperLogLog.withPrecision(p);
        b.add(7);
        b.add(7);
        b.add(7);
        assertArrayEquals(a.registers(), b.registers());
    }

    @Test
    void addOrderIndependent()
    {
        int p = 6;
        HyperLogLog ab = HyperLogLog.withPrecision(p);
        ab.add(11);
        ab.add(99999);
        HyperLogLog ba = HyperLogLog.withPrecision(p);
        ba.add(99999);
        ba.add(11);
        assertArrayEquals(ab.registers(), ba.registers());
    }

    // ---- zero-extend, NOT sign-extend -------------------------------------

    @Test
    void negOneZeroExtendDiffersFromSignExtend()
    {
        // add(-1) encodes 0x00000000ffffffff (zero-extend). The would-be
        // sign-extend (0xffffffffffffffff) routes to a different (idx, rho).
        int p = 4;
        HyperLogLog h = HyperLogLog.withPrecision(p);
        h.add(-1);
        long zx = Hash.hash64(0x00000000ffffffffL, 0L);
        long sx = Hash.hash64(0xffffffffffffffffL, 0L);
        int zi = (int) (zx >>> (64 - p));
        int zr = Long.numberOfLeadingZeros((zx << p) | (1L << (p - 1))) + 1;
        int si = (int) (sx >>> (64 - p));
        int sr = Long.numberOfLeadingZeros((sx << p) | (1L << (p - 1))) + 1;
        assertEquals(zr, unsigned(h.registers()[zi]), "zero-extend register written");
        assertTrue(zi != si || zr != sr, "sign-extend would route differently");
    }

    // ---- merge: element-wise max, p-mismatch throw ------------------------

    @Test
    void mergeIsElementwiseMax()
    {
        int p = 4;
        HyperLogLog a = HyperLogLog.withPrecision(p);
        for (int v : new int[] {1, 2, 3})
        {
            a.add(v);
        }
        HyperLogLog b = HyperLogLog.withPrecision(p);
        for (int v : new int[] {3, 4, 5})
        {
            b.add(v);
        }
        byte[] ar = a.registers();
        byte[] br = b.registers();
        byte[] expected = new byte[ar.length];
        for (int i = 0; i < ar.length; i++)
        {
            expected[i] = (byte) Math.max(unsigned(ar[i]), unsigned(br[i]));
        }
        a.merge(b);
        assertArrayEquals(expected, a.registers());
    }

    @Test
    void mergeCommutativeAndIdempotent()
    {
        int p = 5;
        HyperLogLog ab = build(p, 10, 20, 30);
        ab.merge(build(p, 30, 40, 50));
        HyperLogLog ba = build(p, 30, 40, 50);
        ba.merge(build(p, 10, 20, 30));
        assertArrayEquals(ab.registers(), ba.registers());

        HyperLogLog a = build(p, 10, 20, 30);
        HyperLogLog aa = build(p, 10, 20, 30);
        aa.merge(build(p, 10, 20, 30));
        assertArrayEquals(a.registers(), aa.registers());
    }

    @Test
    void mergePMismatchThrows()
    {
        HyperLogLog a = HyperLogLog.withPrecision(4);
        HyperLogLog b = HyperLogLog.withPrecision(5);
        assertThrows(IllegalArgumentException.class, () -> a.merge(b));
    }

    private static HyperLogLog build(int p, int... items)
    {
        HyperLogLog h = HyperLogLog.withPrecision(p);
        for (int v : items)
        {
            h.add(v);
        }
        return h;
    }

    // ---- serialization round-trip + all rejections ------------------------

    @Test
    void serializeRoundtripAndHeader()
    {
        HyperLogLog h = HyperLogLog.withPrecision(4);
        h.add(1);
        h.add(7);
        h.add(-1);
        byte[] bytes = h.toBytes();
        assertEquals(5 + 16, bytes.length);
        assertEquals(0x48, bytes[0] & 0xFF);
        assertEquals(0x4c, bytes[1] & 0xFF);
        assertEquals(0x4c, bytes[2] & 0xFF);
        assertEquals(0x31, bytes[3] & 0xFF);
        assertEquals(4, bytes[4] & 0xFF);
        HyperLogLog back = HyperLogLog.fromBytes(bytes);
        assertArrayEquals(h.registers(), back.registers());
        assertArrayEquals(bytes, back.toBytes());
    }

    @Test
    void emptyP4RegisterHexAnchor()
    {
        HyperLogLog h = HyperLogLog.withPrecision(4);
        assertEquals("484c4c310400000000000000000000000000000000", toHex(h.toBytes()));
    }

    @Test
    void fromBytesRejectsTooShort()
    {
        assertThrows(IllegalArgumentException.class,
                () -> HyperLogLog.fromBytes(new byte[] {0x48, 0x4c, 0x4c}));
    }

    @Test
    void fromBytesRejectsBadMagic()
    {
        byte[] bytes = HyperLogLog.withPrecision(4).toBytes();
        bytes[0] = 0x00;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(bytes));
    }

    @Test
    void fromBytesRejectsBadPrecision()
    {
        byte[] lo = HyperLogLog.withPrecision(4).toBytes();
        lo[4] = 3;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(lo));
        byte[] hi = HyperLogLog.withPrecision(4).toBytes();
        hi[4] = 19;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(hi));
    }

    @Test
    void fromBytesRejectsLengthMismatch()
    {
        byte[] tooLong = new byte[5 + 16 + 1];
        byte[] base = HyperLogLog.withPrecision(4).toBytes();
        System.arraycopy(base, 0, tooLong, 0, base.length);
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(tooLong));

        byte[] tooShort = new byte[base.length - 1];
        System.arraycopy(base, 0, tooShort, 0, tooShort.length);
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(tooShort));
    }

    @Test
    void fromBytesRejectsRegisterAboveCeilingP4()
    {
        // p=4 ceiling = 64-4+1 = 61. 62 rejected; 61 accepted.
        byte[] bad = HyperLogLog.withPrecision(4).toBytes();
        bad[5] = 62;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(bad));
        byte[] ok = HyperLogLog.withPrecision(4).toBytes();
        ok[5] = 61;
        assertEquals(61, unsigned(HyperLogLog.fromBytes(ok).registers()[0]));
    }

    @Test
    void fromBytesCeilingIsPerPP18()
    {
        // p=18 ceiling = 64-18+1 = 47. 48 rejected; 47 accepted.
        byte[] bad = HyperLogLog.withPrecision(18).toBytes();
        bad[5] = 48;
        assertThrows(IllegalArgumentException.class, () -> HyperLogLog.fromBytes(bad));
        byte[] ok = HyperLogLog.withPrecision(18).toBytes();
        ok[5] = 47;
        assertEquals(47, unsigned(HyperLogLog.fromBytes(ok).registers()[0]));
    }

    // ---- estimate(): native-only, tolerance-bounded (float-quarantine) ----

    @Test
    void freshHllEstimatesZero()
    {
        for (int p : new int[] {4, 7, 14})
        {
            assertEquals(0.0, HyperLogLog.withPrecision(p).estimate(),
                    "fresh HLL at p=" + p + " must estimate exactly 0");
        }
    }

    @Test
    void estimateWithinTolerance()
    {
        // Documented tolerance: relative error < 5% (HLL's ~1.04/sqrt(m) standard
        // error plus cross-libm float drift). p=14 -> m=16384.
        int p = 14;
        int n = 10_000;
        HyperLogLog h = HyperLogLog.withPrecision(p);
        for (int i = 0; i < n; i++)
        {
            h.add(i * 0x9e3779b1); // wrapping multiply -> distinct, well-spread i32
        }
        double est = h.estimate();
        double rel = Math.abs(est - n) / n;
        assertTrue(rel < 0.05, "estimate " + est + " for n=" + n + ": rel " + rel + " >= 0.05");
    }

    @Test
    void estimateSmallCardinalityLinearCounting()
    {
        int p = 14;
        int n = 300;
        HyperLogLog h = HyperLogLog.withPrecision(p);
        for (int i = 0; i < n; i++)
        {
            h.add(i * 0x9e3779b1);
        }
        double est = h.estimate();
        double rel = Math.abs(est - n) / n;
        assertTrue(rel < 0.05, "small-card estimate " + est + " for n=" + n + ": rel " + rel);
    }

    @Test
    void estimateLargeRangeCorrectionIsFinite()
    {
        // All registers at ceiling-1 (the reachable high-register regime) drives
        // raw E into the large-range band; the 2^64 ceiling keeps ln(1 - E/2^64)
        // finite (a 2^32 ceiling would return NaN here).
        int p = 4;
        int nearMax = 64 - p + 1 - 1; // 60
        byte[] bytes = HyperLogLog.withPrecision(p).toBytes();
        for (int i = 5; i < bytes.length; i++)
        {
            bytes[i] = (byte) nearMax;
        }
        HyperLogLog h = HyperLogLog.fromBytes(bytes);
        double est = h.estimate();
        assertTrue(Double.isFinite(est), "large-range estimate must be finite, got " + est);
    }

    @Test
    void registersIsDefensiveCopy()
    {
        HyperLogLog h = HyperLogLog.withPrecision(4);
        h.add(0);
        byte[] snapshot = h.registers();
        snapshot[0] = 0; // mutate the copy
        assertNotEquals(0, unsigned(h.registers()[0]), "registers() must be a copy, not the live array");
        assertFalse(h.nonzeroRegisters() == 0);
    }
}

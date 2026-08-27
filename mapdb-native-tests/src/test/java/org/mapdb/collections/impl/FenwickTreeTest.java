// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the Fenwick tree / Binary Indexed Tree port (spec
 * {@code features/fenwick.md}), mirroring the frozen Rust reference suite.
 *
 * <p>These pin the per-port obligations the shared scenario suite cannot reach:
 * the {@code long} two's-complement WRAP (not saturation) and its post-wrap
 * invertibility (seeded near {@code Long.MAX_VALUE} via the white-box
 * {@code addLongForTesting}, the Java analog of the Rust reference's
 * {@code add_internal}), out-of-range traps ({@code IndexOutOfBoundsException})
 * at both {@code i = -1} and {@code i = n}, the {@code rangeSum}
 * endpoint-validation vs {@code lo > hi}-empty ordering, signed-extreme widening
 * to {@code long}, and the {@code fromValues}-vs-{@code update}-sequence
 * canonical-tree identity. Placed in {@code org.mapdb.collections.impl} to reach
 * the package-private test seam.
 */
public class FenwickTreeTest
{
    // A brute-force long reference with the same wrapping arithmetic.
    private static final class Brute
    {
        final long[] vals;

        Brute(int n)
        {
            this.vals = new long[n];
        }

        void update(int i, int delta)
        {
            vals[i] += delta;
        }

        void set(int i, int value)
        {
            vals[i] = value;
        }

        long get(int i)
        {
            return vals[i];
        }

        long prefixSum(int i)
        {
            long acc = 0L;
            for (int k = 0; k <= i; k++)
            {
                acc += vals[k];
            }
            return acc;
        }

        long rangeSum(int lo, int hi)
        {
            if (lo > hi)
            {
                return 0L;
            }
            long acc = 0L;
            for (int k = lo; k <= hi; k++)
            {
                acc += vals[k];
            }
            return acc;
        }

        long total()
        {
            long acc = 0L;
            for (long v : vals)
            {
                acc += v;
            }
            return acc;
        }
    }

    // A tiny deterministic LCG so the property tests need no external dep.
    private static final class Lcg
    {
        long state;

        Lcg(long seed)
        {
            this.state = seed;
        }

        long nextLong()
        {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return state;
        }

        int nextInt()
        {
            return (int) nextLong();
        }

        int nextIndex(int bound)
        {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }
    }

    @Test
    public void workedExampleFromSpec()
    {
        FenwickTree f = FenwickTree.withSize(8);
        f.update(0, 5);
        f.update(3, 2);
        f.update(7, 9);
        assertEquals(5L, f.prefixSum(0));
        assertEquals(7L, f.prefixSum(3));
        assertEquals(7L, f.prefixSum(6));
        assertEquals(16L, f.prefixSum(7));
        assertEquals(16L, f.total());
        assertEquals(11L, f.rangeSum(1, 7));
        assertEquals(2L, f.get(3));
        assertEquals(8, f.size());
        assertEquals(8, f.length());
        assertFalse(f.isEmpty());
        assertArrayEquals(new long[] {5, 5, 0, 7, 0, 0, 0, 16}, f.canonicalTree());
    }

    @Test
    public void inclusiveConventions()
    {
        FenwickTree f = FenwickTree.fromValues(new int[] {3, 1, 4, 1, 5, 9, 2, 6});
        // prefixSum(0) is the first value (NOT 0 -- inclusive).
        assertEquals(3L, f.prefixSum(0));
        // single-element inclusive range == that value (NOT 0).
        assertEquals(4L, f.rangeSum(2, 2));
        assertEquals(4L, f.get(2));
        assertEquals(31L, f.prefixSum(7));
        assertEquals(31L, f.total());
        // total == prefixSum(n-1) == rangeSum(0, n-1).
        assertEquals(f.prefixSum(7), f.total());
        assertEquals(f.rangeSum(0, 7), f.total());
    }

    @Test
    public void fromValuesMatchesUpdates()
    {
        int[][] cases = {
                {},
                {42},
                {3, 1, 4, 1, 5, 9, 2, 6},
                {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 7},
                {-5, -5, -5, -5, -5, -5, -5},
        };
        for (int[] vals : cases)
        {
            FenwickTree built = FenwickTree.fromValues(vals);
            FenwickTree updated = FenwickTree.withSize(vals.length);
            for (int i = 0; i < vals.length; i++)
            {
                updated.update(i, vals[i]);
            }
            assertArrayEquals(built.canonicalTree(), updated.canonicalTree());
            for (int i = 0; i < vals.length; i++)
            {
                assertEquals(updated.prefixSum(i), built.prefixSum(i));
                assertEquals(updated.get(i), built.get(i));
            }
            assertEquals(updated.total(), built.total());
        }
    }

    @Test
    public void setReplacesNotAdds()
    {
        FenwickTree f = FenwickTree.withSize(4);
        f.update(1, 5);
        f.set(1, 3); // replace, NOT add: get(1) must be 3, not 8.
        f.update(2, 7);
        assertEquals(3L, f.get(1));
        assertEquals(7L, f.get(2));
        assertEquals(3L, f.prefixSum(1));
        assertEquals(10L, f.prefixSum(3));
        assertEquals(10L, f.total());
    }

    @Test
    public void negativeDeltasCrossZero()
    {
        FenwickTree f = FenwickTree.withSize(5);
        f.update(0, 10);
        f.update(1, -4);
        f.update(2, -20);
        f.update(3, 7);
        assertEquals(10L, f.prefixSum(0));
        assertEquals(6L, f.prefixSum(1));
        assertEquals(-14L, f.prefixSum(2));
        assertEquals(-7L, f.prefixSum(4));
        assertEquals(-7L, f.total());
        assertEquals(-17L, f.rangeSum(1, 3));
    }

    @Test
    public void signedExtremesWidenToLong()
    {
        FenwickTree f = FenwickTree.withSize(3);
        f.set(0, Integer.MAX_VALUE); // 2147483647
        f.set(1, Integer.MIN_VALUE); // -2147483648
        f.update(2, Integer.MAX_VALUE);
        f.update(2, 1); // value becomes 2147483648 as long (NOT int-wrapped).
        assertEquals(2147483647L, f.get(0));
        assertEquals(-2147483648L, f.get(1));
        assertEquals(2147483648L, f.get(2));
        assertEquals(-1L, f.prefixSum(1));
        assertEquals(2147483647L, f.total());
    }

    @Test
    public void largeI64SumExceeds2Pow53()
    {
        FenwickTree f = FenwickTree.withSize(4);
        for (int i = 0; i < 4; i++)
        {
            f.set(i, Integer.MAX_VALUE);
        }
        assertEquals(8589934588L, f.total()); // 4 * (2^31 - 1)
        assertEquals(8589934588L, f.prefixSum(3));
        assertEquals(4294967294L, f.rangeSum(1, 2));
    }

    @Test
    public void wrapIsTwoComplementNotSaturating()
    {
        // Seed a slot to Long.MAX_VALUE - 1 via the white-box long-add seam, then
        // push past it: (MAX-1) + 5 wraps two's-complement to a negative long.
        FenwickTree f = FenwickTree.withSize(1);
        f.addLongForTesting(0, Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE - 1L, f.get(0));
        f.addLongForTesting(0, 5L);
        long expected = (Long.MAX_VALUE - 1L) + 5L; // Java long wraps natively.
        assertTrue(expected < 0L, "expected wrap to negative");
        assertEquals(expected, f.get(0));
        assertEquals(expected, f.total());
        assertEquals(expected, f.prefixSum(0));
    }

    @Test
    public void rangeSumEqualsPrefixDiffAfterWrap()
    {
        // Invertibility holds even after the running total has wrapped.
        FenwickTree f = FenwickTree.withSize(3);
        f.addLongForTesting(0, Long.MAX_VALUE - 10L);
        f.addLongForTesting(1, 100L);
        f.addLongForTesting(2, -7L);
        assertEquals(Long.MAX_VALUE - 10L, f.get(0));
        assertEquals(100L, f.get(1));
        assertEquals(-7L, f.get(2));
        assertEquals(100L, f.rangeSum(1, 1));
        assertEquals(-7L, f.rangeSum(2, 2));
        long total = (Long.MAX_VALUE - 10L) + 100L + (-7L);
        assertTrue(total < 0L, "expected the running total to have wrapped");
        assertEquals(total, f.rangeSum(0, 2));
        assertEquals(total, f.total());
        // Invertibility: rangeSum == prefixSum(hi) - prefixSum(lo-1) for every
        // sub-range even after the wrap (two's-complement subtract is exact).
        for (int lo = 0; lo < 3; lo++)
        {
            for (int hi = lo; hi < 3; hi++)
            {
                long direct = f.rangeSum(lo, hi);
                long via = f.prefixSum(hi) - (lo == 0 ? 0L : f.prefixSum(lo - 1));
                assertEquals(via, direct, "lo=" + lo + " hi=" + hi);
            }
        }
    }

    @Test
    public void singleElement()
    {
        FenwickTree f = FenwickTree.withSize(1);
        f.update(0, 42);
        assertEquals(1, f.size());
        assertEquals(42L, f.get(0));
        assertEquals(42L, f.prefixSum(0));
        assertEquals(42L, f.rangeSum(0, 0));
        assertEquals(42L, f.total());
    }

    @Test
    public void emptyTreeEdges()
    {
        FenwickTree f = FenwickTree.withSize(0);
        assertEquals(0, f.size());
        assertTrue(f.isEmpty());
        assertEquals(0L, f.total());

        FenwickTree g = FenwickTree.fromValues(new int[] {});
        assertEquals(0, g.size());
        assertTrue(g.isEmpty());
        assertEquals(0L, g.total());
        assertEquals(0, g.canonicalTree().length);
    }

    @Test
    public void loGtHiReturnsZero()
    {
        FenwickTree f = FenwickTree.fromValues(new int[] {3, 1, 4, 1, 5, 9, 2, 6});
        assertEquals(0L, f.rangeSum(5, 2)); // both endpoints valid, lo > hi.
        assertEquals(0L, f.rangeSum(7, 0));
    }

    @Test
    public void negativeSizeThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> FenwickTree.withSize(-1));
        assertThrows(IllegalArgumentException.class, () -> FenwickTree.withSize(Integer.MAX_VALUE));
    }

    @Test
    public void outOfRangeThrowsAtN()
    {
        FenwickTree f = FenwickTree.withSize(4);
        assertThrows(IndexOutOfBoundsException.class, () -> f.update(4, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.set(4, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> f.prefixSum(4));
        // rangeSum validates the out-of-domain endpoint (NOT inferred empty).
        assertThrows(IndexOutOfBoundsException.class, () -> f.rangeSum(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> f.rangeSum(4, 0));
    }

    @Test
    public void outOfRangeThrowsAtNegativeOne()
    {
        FenwickTree f = FenwickTree.withSize(4);
        assertThrows(IndexOutOfBoundsException.class, () -> f.update(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.set(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.prefixSum(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.rangeSum(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> f.rangeSum(0, -1));
    }

    @Test
    public void emptyTreeQueriesThrow()
    {
        FenwickTree f = FenwickTree.withSize(0);
        assertThrows(IndexOutOfBoundsException.class, () -> f.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> f.prefixSum(0));
        assertThrows(IndexOutOfBoundsException.class, () -> f.rangeSum(0, 0));
    }

    @Test
    public void identityVsBruteForceRandomized()
    {
        Lcg rng = new Lcg(0x123456789abcdef0L);
        for (int trial = 0; trial < 200; trial++)
        {
            int n = 1 + rng.nextIndex(20);
            FenwickTree f = FenwickTree.withSize(n);
            Brute b = new Brute(n);
            int ops = 5 + rng.nextIndex(40);
            for (int o = 0; o < ops; o++)
            {
                int i = rng.nextIndex(n);
                long pick = Long.remainderUnsigned(rng.nextLong(), 5);
                int v;
                if (pick == 0)
                {
                    v = Integer.MIN_VALUE;
                }
                else if (pick == 1)
                {
                    v = Integer.MAX_VALUE;
                }
                else
                {
                    v = rng.nextInt();
                }
                if (Long.remainderUnsigned(rng.nextLong(), 2) == 0)
                {
                    f.update(i, v);
                    b.update(i, v);
                }
                else
                {
                    f.set(i, v);
                    b.set(i, v);
                }
            }
            for (int i = 0; i < n; i++)
            {
                assertEquals(b.get(i), f.get(i), "trial " + trial + " get " + i);
                assertEquals(b.prefixSum(i), f.prefixSum(i), "trial " + trial + " prefix " + i);
            }
            for (int lo = 0; lo < n; lo++)
            {
                for (int hi = 0; hi < n; hi++)
                {
                    assertEquals(b.rangeSum(lo, hi), f.rangeSum(lo, hi),
                            "trial " + trial + " range " + lo + ".." + hi);
                }
            }
            assertEquals(b.total(), f.total(), "trial " + trial + " total");
        }
    }

    @Test
    public void buildDeterminismRandomized()
    {
        Lcg rng = new Lcg(0xdeadbeefcafebabeL);
        for (int t = 0; t < 200; t++)
        {
            int n = rng.nextIndex(20);
            int[] vals = new int[n];
            for (int i = 0; i < n; i++)
            {
                long pick = Long.remainderUnsigned(rng.nextLong(), 4);
                if (pick == 0)
                {
                    vals[i] = Integer.MIN_VALUE;
                }
                else if (pick == 1)
                {
                    vals[i] = Integer.MAX_VALUE;
                }
                else
                {
                    vals[i] = rng.nextInt();
                }
            }
            FenwickTree built = FenwickTree.fromValues(vals);
            FenwickTree updated = FenwickTree.withSize(n);
            for (int i = 0; i < n; i++)
            {
                updated.update(i, vals[i]);
            }
            assertArrayEquals(built.canonicalTree(), updated.canonicalTree());
        }
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.CountMin;
import org.mapdb.collections.impl.Hash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link CountMin} (spec {@code features/count-min.md}),
 * mirroring the Rust reference port's {@code count_min.rs} test module. Counts
 * are u64-in-long (unsigned bits): {@code -1L} is the saturating ceiling
 * {@code u64::MAX = 18446744073709551615}. All count comparisons are unsigned.
 *
 * <p>This is NOT a carve-out feature: the counter matrix, estimate (MIN), total,
 * and element encoding are bit-identical to the other four ports. The only
 * float, {@link CountMin#optimal}, is quarantined here (never in the shared
 * suite) and pinned to the integer table.
 */
class CountMinTest
{
    private static final long U64_MAX = -1L;

    /** The LE-4-byte encoding of an i32, the input the byte positions path consumes. */
    private static byte[] encode(int item)
    {
        return new byte[] {
                (byte) item,
                (byte) (item >>> 8),
                (byte) (item >>> 16),
                (byte) (item >>> 24)
        };
    }

    private static int[] columns(CountMin c, int item)
    {
        return Hash.positions(encode(item), c.width(), c.depth());
    }

    @Test
    void rowHashMatchesPositions()
    {
        CountMin c = CountMin.withParams(4, 16);
        int[] cols = columns(c, 7);
        assertArrayEquals(Hash.positions(encode(7), 16, 4), cols);
        // Pinned worked example: add(7) over (d=4,w=16) touches [7,0,9,2]
        // (12-hash-pipeline/positions_basic.json).
        assertArrayEquals(new int[] {7, 0, 9, 2}, cols);
    }

    @Test
    void addOneTouchesTheFourColumns()
    {
        CountMin c = CountMin.withParams(4, 16);
        c.addOne(7);
        long[] m = c.toCounters();
        assertEquals(1L, m[7]);              // row 0, col 7
        assertEquals(1L, m[16]);             // row 1, col 0
        assertEquals(1L, m[2 * 16 + 9]);     // row 2, col 9
        assertEquals(1L, m[3 * 16 + 2]);     // row 3, col 2
        int ones = 0;
        for (long v : m)
        {
            if (v == 1L)
            {
                ones++;
            }
        }
        assertEquals(4, ones);
        assertEquals(1L, c.estimate(7));
        assertEquals(1L, c.total());
        assertEquals(64, m.length);
    }

    @Test
    void addByCountEqualsRepeatedAddOne()
    {
        CountMin a = CountMin.withParams(3, 13);
        CountMin b = CountMin.withParams(3, 13);
        a.add(42, 5L);
        for (int i = 0; i < 5; i++)
        {
            b.addOne(42);
        }
        assertArrayEquals(a.toCounters(), b.toCounters());
        assertEquals(5L, a.estimate(42));
        assertEquals(5L, a.total());
    }

    @Test
    void addCountAccumulates()
    {
        CountMin c = CountMin.withParams(4, 16);
        c.add(7, 5L);
        c.add(7, 3L);
        assertEquals(8L, c.estimate(7));
        assertEquals(8L, c.total());
    }

    @Test
    void countZeroIsCounterNoopButUpdatesTotal()
    {
        CountMin c = CountMin.withParams(3, 7);
        c.add(1, 0L);
        for (long v : c.toCounters())
        {
            assertEquals(0L, v);
        }
        assertEquals(0L, c.total()); // += 0
        c.add(1, 4L);
        c.add(1, 0L);
        assertEquals(4L, c.estimate(1));
        assertEquals(4L, c.total());
    }

    @Test
    void collisionAcrossRowsNotDeduped()
    {
        int d = 3;
        for (int w = 2; w < 32; w++)
        {
            for (int item = 0; item < 256; item++)
            {
                int[] cols = Hash.positions(encode(item), w, d);
                int r0 = -1;
                int r1 = -1;
                int col = -1;
                java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
                for (int r = 0; r < cols.length; r++)
                {
                    Integer prev = seen.get(cols[r]);
                    if (prev != null)
                    {
                        r0 = prev;
                        r1 = r;
                        col = cols[r];
                        break;
                    }
                    seen.put(cols[r], r);
                }
                if (col >= 0)
                {
                    CountMin c = CountMin.withParams(d, w);
                    c.addOne(item);
                    long[] m = c.toCounters();
                    // Both row r0 col `col` and row r1 col `col` incremented (distinct counters).
                    assertEquals(1L, m[r0 * w + col]);
                    assertEquals(1L, m[r1 * w + col]);
                    assertEquals(1L, c.estimate(item));
                    return;
                }
            }
        }
        throw new AssertionError("no cross-row column collision found in the search space");
    }

    @Test
    void estimateIsMinNotAverageOrRow0()
    {
        CountMin c = CountMin.withParams(4, 8);
        int target = 5;
        c.add(target, 1L);
        int[] cols = columns(c, target);
        for (int other = 0; other < 200; other++)
        {
            if (other == target)
            {
                continue;
            }
            c.add(other, 7L);
        }
        long[] m = c.toCounters();
        long min = U64_MAX;
        long max = 0L;
        for (int r = 0; r < cols.length; r++)
        {
            long v = m[r * 8 + cols[r]];
            if (Long.compareUnsigned(v, min) < 0)
            {
                min = v;
            }
            if (Long.compareUnsigned(v, max) > 0)
            {
                max = v;
            }
        }
        assertEquals(min, c.estimate(target));
        assertTrue(Long.compareUnsigned(c.estimate(target), 1L) >= 0); // no under-estimate
        assertTrue(Long.compareUnsigned(max, min) >= 0);
    }

    @Test
    void overflowSaturatesNotWraps()
    {
        CountMin c = CountMin.withParams(2, 4);
        c.add(9, U64_MAX);
        c.add(9, 5L);
        // Each selected counter clamps at u64::MAX (not wrap to 4).
        assertEquals(U64_MAX, c.estimate(9));
        assertEquals(U64_MAX, c.total());
        long[] m = c.toCounters();
        int[] cols = columns(c, 9);
        for (int r = 0; r < cols.length; r++)
        {
            assertEquals(U64_MAX, m[r * 4 + cols[r]]);
        }
        assertEquals("18446744073709551615", Long.toUnsignedString(c.estimate(9)));
    }

    @Test
    void noUnderEstimate()
    {
        CountMin c = CountMin.withParams(5, 64);
        c.add(-1, 3L);
        c.add(Integer.MIN_VALUE, 10L);
        assertTrue(Long.compareUnsigned(c.estimate(-1), 3L) >= 0);
        assertTrue(Long.compareUnsigned(c.estimate(Integer.MIN_VALUE), 10L) >= 0);
    }

    @Test
    void orderIndependence()
    {
        CountMin a = CountMin.withParams(4, 16);
        CountMin b = CountMin.withParams(4, 16);
        int[][] seq = {{1, 3}, {2, 5}, {1, 2}, {-7, 9}, {Integer.MAX_VALUE, 1}};
        for (int[] s : seq)
        {
            a.add(s[0], s[1]);
        }
        for (int i = seq.length - 1; i >= 0; i--)
        {
            b.add(seq[i][0], seq[i][1]);
        }
        assertArrayEquals(a.toCounters(), b.toCounters());
        assertEquals(a.total(), b.total());
    }

    @Test
    void dZeroIsLegalVacuousMax()
    {
        CountMin c = CountMin.withParams(0, 16);
        c.add(5, 1L);
        assertEquals(0, c.toCounters().length);
        assertEquals(1L, c.total());
        // MIN over zero rows = u64::MAX.
        assertEquals(U64_MAX, c.estimate(5));
        assertEquals("18446744073709551615", Long.toUnsignedString(c.estimate(5)));
    }

    @Test
    void emptyMatrixIsAllZeroDense()
    {
        CountMin c = CountMin.withParams(4, 16);
        long[] m = c.toCounters();
        assertEquals(64, m.length);
        for (long v : m)
        {
            assertEquals(0L, v);
        }
        assertEquals(0L, c.estimate(7));
        assertEquals(0L, c.total());
    }

    @Test
    void wZeroTraps()
    {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> CountMin.withParams(4, 0));
        assertTrue(ex.getMessage().contains("width w must be non-zero"));
    }

    @Test
    void elementEncodingBytePath()
    {
        CountMin c = CountMin.withParams(4, 16);
        // Reuses the byte positions path (length fold), NOT the scalar word.
        assertArrayEquals(Hash.positions(encode(7), 16, 4), columns(c, 7));
        // -1 reinterprets to 0xffffffff -> LE bytes [ff,ff,ff,ff].
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}, encode(-1));
        assertArrayEquals(new byte[] {0x00, 0x00, 0x00, (byte) 0x80}, encode(Integer.MIN_VALUE));
    }

    // ---- optimal() pinned integer table (native-only, float-quarantined) ----

    @Test
    void optimalIntegerTable()
    {
        // (epsilon, delta) -> (w, d) per spec/features/count-min.md.
        double[][] cases = {
                {0.01, 0.01, 272, 5},
                {0.001, 0.001, 2719, 7},
                {0.1, 0.05, 28, 3},
                {0.01, 0.001, 272, 7},
                {0.5, 0.5, 6, 1}
        };
        for (double[] cse : cases)
        {
            CountMin c = CountMin.optimal(cse[0], cse[1]);
            assertEquals((int) cse[2], c.width(), "w for (" + cse[0] + "," + cse[1] + ")");
            assertEquals((int) cse[3], c.depth(), "d for (" + cse[0] + "," + cse[1] + ")");
        }
    }

    @Test
    void optimalRejectsBadEpsilon()
    {
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(0.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(1.0, 0.5));
    }

    @Test
    void optimalRejectsBadDelta()
    {
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(0.5, 1.0));
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(0.5, 0.0));
    }

    @Test
    void optimalRejectsNonFinite()
    {
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(Double.NaN, 0.5));
        assertThrows(IllegalArgumentException.class, () -> CountMin.optimal(0.5, Double.POSITIVE_INFINITY));
    }
}

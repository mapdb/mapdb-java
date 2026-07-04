// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.CountMin;
import org.mapdb.collections.impl.SpaceSaving;
import org.mapdb.collections.impl.stream.SortedStream;
import org.mapdb.collections.impl.stream.StreamSketches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "C4" streaming sketch operators
 * ({@link StreamSketches}).
 */
public class StreamSketchesTest
{
    private static SortedStream<Integer> range(int loInclusive, int hiExclusive)
    {
        List<Integer> xs = new ArrayList<>();
        for (int i = loInclusive; i < hiExclusive; i++)
        {
            xs.add(i);
        }
        return SortedStream.ofSorted(xs);
    }

    private static SortedStream<Integer> sortedOf(int... vals)
    {
        List<Integer> xs = new ArrayList<>();
        for (int v : vals)
        {
            xs.add(v);
        }
        return SortedStream.ofSorted(xs);
    }

    private static List<Integer> drain(SortedStream<Integer> s)
    {
        return new ArrayList<>(s.toList());
    }

    // ---- approxDistinctCount (HyperLogLog) ----

    @Test
    public void approxDistinctCountIsWithinTolerance()
    {
        long est = StreamSketches.approxDistinctCount(range(0, 10_000));
        // precision 14 -> ~0.8% standard error; ±3% is a very safe band.
        assertTrue(Math.abs(est - 10_000) <= 300, "estimate=" + est);
    }

    @Test
    public void approxDistinctCountIgnoresDuplicates()
    {
        // 3 distinct values, each repeated -> estimate near 3.
        long est = StreamSketches.approxDistinctCount(sortedOf(1, 1, 1, 2, 2, 3), 14);
        assertEquals(3, est);
    }

    @Test
    public void approxDistinctCountComposesOverA1Union()
    {
        long est = StreamSketches.approxDistinctCount(range(0, 5_000).union(range(2_500, 7_500)));
        // union distinct = 0..7499 = 7500
        assertTrue(Math.abs(est - 7_500) <= 250, "estimate=" + est);
    }

    // ---- topK (Space-Saving) ----

    @Test
    public void topKExactWhenCapacityCoversDistinct()
    {
        // 1 x5, 2 x3, 3 x1 ; capacity 10 >= 3 distinct -> exact, no eviction.
        SortedStream<Integer> s = sortedOf(1, 1, 1, 1, 1, 2, 2, 2, 3);
        List<SpaceSaving.SSEntry> top = StreamSketches.topK(s, 10, 2);
        assertEquals(2, top.size());
        assertEquals(1, top.get(0).item);
        assertEquals(5L, top.get(0).count);
        assertEquals(2, top.get(1).item);
        assertEquals(3L, top.get(1).count);
    }

    @Test
    public void topKReturnsFewerWhenMonitoringFewer()
    {
        List<SpaceSaving.SSEntry> top = StreamSketches.topK(sortedOf(1, 2, 3), 10, 5);
        assertEquals(3, top.size());
    }

    // ---- frequencies (Count-Min) ----

    @Test
    public void frequenciesNeverUnderestimate()
    {
        SortedStream<Integer> s = sortedOf(1, 1, 1, 1, 1, 2, 2, 2, 3);
        CountMin cm = StreamSketches.frequencies(s, 5, 2048);
        // Count-Min is a one-sided (never-under) estimator.
        assertTrue(cm.estimate(1) >= 5);
        assertTrue(cm.estimate(2) >= 3);
        assertTrue(cm.estimate(3) >= 1);
        // Wide sketch, tiny domain -> collisions unlikely, so estimates are tight.
        assertEquals(5, cm.estimate(1));
        assertEquals(0, cm.estimate(999));  // absent item: >= 0, and here exactly 0
    }

    // ---- sampleGate / sample ----

    @Test
    public void sampleGateIsDeterministicAndSeedSensitive()
    {
        Predicate<Integer> a = StreamSketches.sampleGate(0.5, 42L);
        Predicate<Integer> b = StreamSketches.sampleGate(0.5, 42L);
        Set<Integer> keptA = new HashSet<>();
        Set<Integer> keptB = new HashSet<>();
        Set<Integer> keptOtherSeed = new HashSet<>();
        Predicate<Integer> c = StreamSketches.sampleGate(0.5, 43L);
        for (int i = 0; i < 2_000; i++)
        {
            if (a.test(i))
            {
                keptA.add(i);
            }
            if (b.test(i))
            {
                keptB.add(i);
            }
            if (c.test(i))
            {
                keptOtherSeed.add(i);
            }
        }
        assertEquals(keptA, keptB);                 // same (p, seed) -> identical subset
        assertNotEquals(keptA, keptOtherSeed);      // different seed -> different subset
    }

    @Test
    public void sampleIsRepeatableOrderPreservingAndApproximatelyRight()
    {
        List<Integer> r1 = drain(StreamSketches.sample(range(0, 10_000), 0.3, 7L));
        List<Integer> r2 = drain(StreamSketches.sample(range(0, 10_000), 0.3, 7L));
        assertEquals(r1, r2);                       // repeatable

        // still ascending (filter preserves order)
        for (int i = 1; i < r1.size(); i++)
        {
            assertTrue(r1.get(i - 1) < r1.get(i));
        }
        // kept fraction near p=0.3
        assertTrue(Math.abs(r1.size() - 3_000) <= 300, "kept=" + r1.size());
    }

    @Test
    public void sampleBoundaryProbabilities()
    {
        assertEquals(0L, StreamSketches.sample(range(0, 100), 0.0, 1L).count());
        assertEquals(100L, StreamSketches.sample(range(0, 100), 1.0, 1L).count());
    }

    @Test
    public void sampleGateRejectsBadProbability()
    {
        assertThrows(IllegalArgumentException.class, () -> StreamSketches.sampleGate(-0.1, 0L));
        assertThrows(IllegalArgumentException.class, () -> StreamSketches.sampleGate(1.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> StreamSketches.sampleGate(Double.NaN, 0L));
    }

    @Test
    public void sampleGateOrderIndependent()
    {
        Predicate<Integer> gate = StreamSketches.sampleGate(0.5, 99L);
        Set<Integer> asc = new HashSet<>();
        Set<Integer> desc = new HashSet<>();
        for (int i = 0; i < 500; i++)
        {
            if (gate.test(i))
            {
                asc.add(i);
            }
        }
        for (int i = 499; i >= 0; i--)
        {
            if (gate.test(i))
            {
                desc.add(i);
            }
        }
        assertEquals(asc, desc);
        assertFalse(asc.isEmpty());
    }
}

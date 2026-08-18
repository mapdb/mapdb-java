// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.HyperLogLog;
import org.mapdb.collections.impl.stream.SortedStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SortedStreamSamplingTest
{
    private static SortedStream<Integer> range(int upper)
    {
        List<Integer> values = new ArrayList<>();
        for (int value = 0; value < upper; value++)
        {
            values.add(value);
        }
        return SortedStream.ofSorted(values);
    }

    @Test
    public void sampleIsRepeatableOrderPreservingAndApproximatelyRight()
    {
        List<Integer> first = new ArrayList<>(SortedStream.sampleIntegers(range(10_000), 0.3, 7L).toList());
        List<Integer> second = new ArrayList<>(SortedStream.sampleIntegers(range(10_000), 0.3, 7L).toList());
        assertEquals(first, second);
        for (int index = 1; index < first.size(); index++)
        {
            assertTrue(first.get(index - 1) < first.get(index));
        }
        assertTrue(Math.abs(first.size() - 3_000) <= 300, "kept=" + first.size());
    }

    @Test
    public void sampleGateIsDeterministicSeedSensitiveAndOrderIndependent()
    {
        Predicate<Integer> firstGate = SortedStream.integerSampleGate(0.5, 42L);
        Predicate<Integer> sameGate = SortedStream.integerSampleGate(0.5, 42L);
        Predicate<Integer> otherGate = SortedStream.integerSampleGate(0.5, 43L);
        Set<Integer> first = collect(firstGate, false);
        Set<Integer> same = collect(sameGate, true);
        Set<Integer> other = collect(otherGate, false);
        assertEquals(first, same);
        assertNotEquals(first, other);
        assertFalse(first.isEmpty());
    }

    private static Set<Integer> collect(Predicate<Integer> gate, boolean reverse)
    {
        Set<Integer> values = new HashSet<>();
        for (int offset = 0; offset < 2_000; offset++)
        {
            int value = reverse ? 1_999 - offset : offset;
            if (gate.test(value))
            {
                values.add(value);
            }
        }
        return values;
    }

    @Test
    public void sampleBoundaryProbabilitiesAndValidation()
    {
        assertEquals(0L, SortedStream.sampleIntegers(range(100), 0.0, 1L).count());
        assertEquals(100L, SortedStream.sampleIntegers(range(100), 1.0, 1L).count());
        assertThrows(IllegalArgumentException.class, () -> SortedStream.integerSampleGate(-0.1, 0L));
        assertThrows(IllegalArgumentException.class, () -> SortedStream.integerSampleGate(1.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> SortedStream.integerSampleGate(Double.NaN, 0L));
    }

    @Test
    public void samplingHashIsDomainSeparatedFromHyperLogLog()
    {
        for (long seed : new long[] {0L, 7L, 42L})
        {
            SortedStream<Integer> sampled = SortedStream.sampleIntegers(range(100_000), 0.5, seed);
            HyperLogLog sketch = HyperLogLog.withPrecision(14);
            sampled.forEach(sketch::add);
            long estimate = Math.round(sketch.estimate());
            assertTrue(Math.abs(estimate - 50_000) <= 3_000,
                    "seed=" + seed + " estimate=" + estimate);
        }
    }
}

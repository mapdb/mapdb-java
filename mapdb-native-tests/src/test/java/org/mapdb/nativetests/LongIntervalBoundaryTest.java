/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.nativetests;

import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.LongIterable;
import org.mapdb.collections.api.iterator.LongIterator;
import org.mapdb.collections.impl.list.primitive.LongInterval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LongInterval boundary tests, per spec/algorithms.md "Interval over signed
 * integers". For a long-typed interval the "wider-than-element" arithmetic the
 * spec calls for is unsigned 64-bit: {@code (to - from)} is the unsigned
 * distance mod 2^64, so size/contains/get must use unsigned long math
 * (Long.divideUnsigned / Long.remainderUnsigned / Long.compareUnsigned) to
 * avoid wrapping at numeric bounds (a range like
 * {@code [Long.MIN_VALUE, Long.MAX_VALUE]} overflows naive signed long math).
 * step 0 throws, and toReversed at the minimum step throws explicitly rather
 * than wrapping the negation silently.
 */
public class LongIntervalBoundaryTest
{
    @Test
    public void boundaryDoesNotWrap()
    {
        // A tight interval at the very top of the long domain.
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1);
        assertEquals(2, iv.size());
        assertEquals(Long.MAX_VALUE - 1, iv.get(0));
        assertEquals(Long.MAX_VALUE, iv.get(1));
        assertTrue(iv.contains(Long.MAX_VALUE - 1));
        assertTrue(iv.contains(Long.MAX_VALUE));
        assertFalse(iv.contains(Long.MAX_VALUE - 2));
    }

    @Test
    public void fullLongRangeDoesNotWrap()
    {
        // [Long.MIN_VALUE, Long.MAX_VALUE] by Long.MAX_VALUE.
        // Unsigned distance is 2^64 - 1 == 0xFFFF...FFFF; step is 2^63 - 1.
        // Reachable points: MIN, MIN + MAX (== -1), MIN + 2*MAX (== MAX - 1).
        // divideUnsigned(0xFFFFFFFFFFFFFFFF, 0x7FFFFFFFFFFFFFFF) == 2, so size 3.
        LongInterval iv = LongInterval.fromToBy(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(3, iv.size());
        assertEquals(Long.MIN_VALUE, iv.get(0));
        assertEquals(Long.MIN_VALUE + Long.MAX_VALUE, iv.get(1)); // == -1
        assertEquals(Long.MIN_VALUE + 2L * Long.MAX_VALUE, iv.get(2)); // == MAX - 1
        assertTrue(iv.contains(Long.MIN_VALUE));
        assertTrue(iv.contains(Long.MIN_VALUE + Long.MAX_VALUE));
        assertTrue(iv.contains(Long.MIN_VALUE + 2L * Long.MAX_VALUE));
        // A point that is within [from,to] but NOT on the step grid must not match.
        assertFalse(iv.contains(0L));
        // The endpoint Long.MAX_VALUE itself is NOT reachable (last value is MAX-1).
        assertFalse(iv.contains(Long.MAX_VALUE));
    }

    @Test
    public void negativeRangeContainsNoWrap()
    {
        LongInterval iv = LongInterval.fromToBy(Long.MIN_VALUE, Long.MIN_VALUE + 10, 2);
        assertEquals(6, iv.size());
        assertEquals(Long.MIN_VALUE, iv.get(0));
        assertEquals(Long.MIN_VALUE + 10, iv.get(5));
        assertTrue(iv.contains(Long.MIN_VALUE + 4));
        assertFalse(iv.contains(Long.MIN_VALUE + 5));
    }

    @Test
    public void fromToByStepZeroThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> LongInterval.fromToBy(0L, 10L, 0L));
    }

    @Test
    public void oversizedRangeThrows()
    {
        // A range whose element count exceeds Integer.MAX_VALUE must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> LongInterval.fromToBy(Long.MIN_VALUE, Long.MAX_VALUE, 1L));
    }

    @Test
    public void toReversedMinimumStepThrows()
    {
        // Build a valid interval whose step is Long.MIN_VALUE (size 2),
        // then reverse it: -step is unrepresentable and must be rejected
        // explicitly instead of silently wrapping back to MIN_VALUE.
        LongInterval iv = LongInterval.fromToBy(0L, Long.MIN_VALUE, Long.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::toReversed);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    public void toReversedNormalStepWorks()
    {
        LongInterval iv = LongInterval.fromToBy(1L, 10L, 1L);
        LongInterval rev = iv.toReversed();
        assertEquals(10L, rev.get(0));
        assertEquals(1L, rev.get(rev.size() - 1));
        assertEquals(iv.size(), rev.size());
    }

    @Test
    public void fullRangeTraversalDoesNotWrapOrOverRun()
    {
        // Regression guard for the traversal-wrap bug: value-by-comparison loops
        // (`i += step` with a signed `i <= to` test) wrap past the end for a
        // full-domain long interval and over-run / never terminate.
        LongInterval iv = LongInterval.fromToBy(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(3, iv.size());

        long[] expected = {Long.MIN_VALUE, -1L, Long.MAX_VALUE - 1L};

        // toArray must not over-run its size()-length backing array, and must
        // hold exactly the three reachable values.
        long[] array = iv.toArray();
        assertEquals(iv.size(), array.length);
        assertArrayEquals(expected, array);

        // each() must visit exactly size() elements (no over-run / no infinite loop).
        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());

        // forEachWithIndex must visit exactly size() elements with the right values at each index.
        long[] seen = new long[iv.size()];
        AtomicInteger fewiCount = new AtomicInteger();
        iv.forEachWithIndex((value, index) ->
        {
            seen[index] = value;
            fewiCount.incrementAndGet();
        });
        assertEquals(iv.size(), fewiCount.get());
        assertArrayEquals(expected, seen);

        // The iterator must yield exactly size() elements then report hasNext()==false.
        LongIterator iterator = iv.longIterator();
        int iterCount = 0;
        while (iterator.hasNext())
        {
            assertEquals(expected[iterCount], iterator.next());
            iterCount++;
        }
        assertEquals(iv.size(), iterCount);
        assertFalse(iterator.hasNext());
    }

    @Test
    public void builderFullRangeEqualsFromToBy()
    {
        // Regression guard for the calculateAdjustedStep overflow: `to - from`
        // wraps to -1 across the full domain and wrongly flips the step sign,
        // making the builder reject a range that fromToBy accepts.
        LongInterval built = LongInterval.from(Long.MIN_VALUE).by(Long.MAX_VALUE).to(Long.MAX_VALUE);
        LongInterval direct = LongInterval.fromToBy(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        assertEquals(direct.size(), built.size());
        assertEquals(direct, built);
        assertEquals(direct.get(0), built.get(0));
        assertEquals(direct.get(1), built.get(1));
        assertEquals(direct.get(2), built.get(2));
    }

    @Test
    public void descendingFullRangeTraversalDoesNotWrapOrOverRun()
    {
        // Descending full-domain analogue, exercising the `>=` / negative-step path.
        // Unsigned distance MAX - MIN == 2^64 - 1; step magnitude 2^63, so size 2.
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);
        assertEquals(2, iv.size());

        long[] expected = {Long.MAX_VALUE, Long.MAX_VALUE + Long.MIN_VALUE};
        assertArrayEquals(expected, iv.toArray());

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());

        LongIterator iterator = iv.longIterator();
        int iterCount = 0;
        while (iterator.hasNext())
        {
            assertEquals(expected[iterCount], iterator.next());
            iterCount++;
        }
        assertEquals(iv.size(), iterCount);
        assertFalse(iterator.hasNext());
    }

    @Test
    public void singletonIntervalsEqualByElementNotStep()
    {
        // Two single-element intervals with from != to but the same sole
        // element [1] (the step overshoots `to`, so size is 1): they are equal
        // as lists, so equals must compare elements, NOT step. A step-comparing
        // fast path would wrongly report them unequal.
        LongInterval a = LongInterval.fromToBy(1L, 5L, 10L);   // (5-1)/10 == 0 -> size 1, [1]
        LongInterval b = LongInterval.fromToBy(1L, 100L, 1000L); // size 1, [1]
        LongInterval c = LongInterval.fromToBy(1L, 1L, 1L);    // size 1, [1]
        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.hashCode(), c.hashCode());
    }

    @Test
    public void sumMatchesElementwiseAccumulationWhenTrueSumOverflows()
    {
        // MINOR 1 regression: for odd size the old code halved the WRAPPED
        // (first + last) before multiplying, which does not commute with mod
        // 2^64 once the mathematical sum overflows. sum() must equal the plain
        // element-wise accumulation (substitutability with LongArrayList).
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE - 2, Long.MAX_VALUE, 1L);
        assertEquals(3, iv.size());

        long elementwise = 0L;
        for (long value : iv.toArray())
        {
            elementwise += value;
        }
        assertEquals(elementwise, iv.sum());
        // And it equals n * middle element.
        assertEquals(3L * (Long.MAX_VALUE - 1L), iv.sum());
    }

    @Test
    public void containsNoneAcceptsLongRangeValues()
    {
        // MINOR 2 regression: containsNone now takes long..., so values outside
        // the int range are testable on a long-typed interval.
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE - 2, Long.MAX_VALUE, 1L);
        assertFalse(iv.containsNone(Long.MAX_VALUE));
        assertTrue(iv.containsNone(Long.MIN_VALUE, 0L));
    }

    @Test
    public void chunkAtExtremeTerminatesAndPartitionsAllElements()
    {
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE - 2, Long.MAX_VALUE, 1L);
        assertEquals(3, iv.size());
        RichIterable<LongIterable> chunks = iv.chunk(2);
        assertEquals(2, chunks.size());
        assertArrayEquals(new long[] {Long.MAX_VALUE - 2, Long.MAX_VALUE - 1},
                chunks.getFirst().toArray());
        assertArrayEquals(new long[] {Long.MAX_VALUE}, chunks.getLast().toArray());
    }

    @Test
    public void chunkOfSingletonReturnsSingleBatch()
    {
        RichIterable<LongIterable> chunks = LongInterval.fromToBy(5L, 5L, 1L).chunk(2);
        assertEquals(1, chunks.size());
        assertArrayEquals(new long[] {5L}, chunks.getFirst().toArray());
    }

    @Test
    public void spliteratorAtExtremeTerminatesWithExactElements()
    {
        LongInterval iv = LongInterval.fromToBy(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1L);
        assertEquals(2, iv.size());
        long[] streamed = StreamSupport.longStream(iv.spliterator(), false).toArray();
        assertArrayEquals(new long[] {Long.MAX_VALUE - 1, Long.MAX_VALUE}, streamed);
    }

    @Test
    public void exhaustedSpliteratorReturnsFalseWithoutInvokingAction()
    {
        Spliterator.OfLong s = LongInterval.fromToBy(1L, 2L, 1L).spliterator();
        AtomicInteger count = new AtomicInteger();
        java.util.function.LongConsumer counter = value -> count.incrementAndGet();
        assertTrue(s.tryAdvance(counter));
        assertTrue(s.tryAdvance(counter));
        assertFalse(s.tryAdvance(counter));
        assertFalse(s.tryAdvance(counter));
        assertEquals(2, count.get());
    }
}

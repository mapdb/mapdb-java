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
import org.mapdb.collections.api.IntIterable;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.iterator.IntIterator;
import org.mapdb.collections.impl.list.primitive.IntInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IntInterval boundary tests, per spec/algorithms.md "Interval over signed
 * integers". size/contains/get must not wrap at numeric bounds (IntInterval is
 * int-typed and computes in long, the uint64-equivalent), step 0 throws, and
 * toReversed at the minimum step throws explicitly rather than wrapping the
 * negation silently.
 */
public class IntIntervalBoundaryTest
{
    @Test
    public void boundaryDoesNotWrap()
    {
        // The [126, 127] / Int8 analogue: a tight interval at the top.
        IntInterval iv = IntInterval.fromToBy(126, 127, 1);
        assertEquals(2, iv.size());
        assertEquals(126, iv.get(0));
        assertEquals(127, iv.get(1));
        assertTrue(iv.contains(126));
        assertTrue(iv.contains(127));
        assertFalse(iv.contains(128));
        assertFalse(iv.contains(125));
    }

    @Test
    public void fullIntRangeDoesNotWrap()
    {
        IntInterval iv = IntInterval.fromToBy(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(3, iv.size());
        assertEquals(Integer.MIN_VALUE, iv.get(0));
        assertTrue(iv.contains(Integer.MIN_VALUE));
        assertTrue(iv.contains(-1));   // MIN + MAX == -1
        assertTrue(iv.contains(Integer.MAX_VALUE - 1)); // MIN + 2*MAX
    }

    @Test
    public void negativeRangeContainsNoWrap()
    {
        IntInterval iv = IntInterval.fromToBy(Integer.MIN_VALUE, Integer.MIN_VALUE + 10, 2);
        assertEquals(6, iv.size());
        assertTrue(iv.contains(Integer.MIN_VALUE + 4));
        assertFalse(iv.contains(Integer.MIN_VALUE + 5));
    }

    @Test
    public void fromToByStepZeroThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> IntInterval.fromToBy(0, 10, 0));
    }

    @Test
    public void toReversedMinimumStepThrows()
    {
        // Build a valid interval whose step is Integer.MIN_VALUE (size 2),
        // then reverse it: -step is unrepresentable and must be rejected
        // explicitly instead of silently wrapping back to MIN_VALUE.
        IntInterval iv = IntInterval.fromToBy(0, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::toReversed);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    public void toReversedNormalStepWorks()
    {
        IntInterval iv = IntInterval.fromToBy(1, 10, 1);
        IntInterval rev = iv.toReversed();
        assertEquals(10, rev.get(0));
        assertEquals(1, rev.get(rev.size() - 1));
        assertEquals(iv.size(), rev.size());
    }

    @Test
    @Timeout(10)
    public void fullRangeTraversalDoesNotWrapOrOverRun()
    {
        // Regression guard for the traversal-wrap bug: value-by-comparison loops
        // (`i += step` with a signed `i <= to` test) wrap past the end for a
        // full-domain int interval and over-run / never terminate.
        IntInterval iv = IntInterval.fromToBy(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(3, iv.size());

        int[] expected = {Integer.MIN_VALUE, -1, Integer.MAX_VALUE - 1};

        int[] array = iv.toArray();
        assertEquals(iv.size(), array.length);
        assertArrayEquals(expected, array);

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());

        int[] seen = new int[iv.size()];
        AtomicInteger fewiCount = new AtomicInteger();
        iv.forEachWithIndex((value, index) ->
        {
            seen[index] = value;
            fewiCount.incrementAndGet();
        });
        assertEquals(iv.size(), fewiCount.get());
        assertArrayEquals(expected, seen);

        IntIterator iterator = iv.intIterator();
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
    @Timeout(10)
    public void descendingFullRangeTraversalDoesNotWrapOrOverRun()
    {
        // Descending full-domain analogue, exercising the negative-step path.
        IntInterval iv = IntInterval.fromToBy(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(2, iv.size());

        int[] expected = {Integer.MAX_VALUE, Integer.MAX_VALUE + Integer.MIN_VALUE}; // {MAX, -1}
        assertArrayEquals(expected, iv.toArray());

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());
    }

    @Test
    @Timeout(10)
    public void chunkAtExtremeTerminatesAndPartitionsAllElements()
    {
        // Pre-fix this HANGS: the value-based `(lastUpdated + step) <= to` chunk
        // loop wraps to Integer.MIN_VALUE after emitting MAX_VALUE and runs away.
        IntInterval iv = IntInterval.fromToBy(Integer.MAX_VALUE - 2, Integer.MAX_VALUE, 1);
        assertEquals(3, iv.size());
        RichIterable<IntIterable> chunks = iv.chunk(2);
        assertEquals(2, chunks.size());
        assertArrayEquals(new int[] {Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1},
                chunks.getFirst().toArray());
        assertArrayEquals(new int[] {Integer.MAX_VALUE}, chunks.getLast().toArray());
    }

    @Test
    public void chunkOfSingletonReturnsSingleBatch()
    {
        // Pre-fix this returns [] (element dropped); must be [[5]].
        RichIterable<IntIterable> chunks = IntInterval.fromToBy(5, 5, 1).chunk(2);
        assertEquals(1, chunks.size());
        assertArrayEquals(new int[] {5}, chunks.getFirst().toArray());
    }

    @Test
    public void chunkSizeNotLessThanIntervalReturnsSingleBatch()
    {
        // size <= chunkSize must yield exactly one batch of all elements.
        RichIterable<IntIterable> chunks = IntInterval.fromToBy(1, 3, 1).chunk(10);
        assertEquals(1, chunks.size());
        assertArrayEquals(new int[] {1, 2, 3}, chunks.getFirst().toArray());
    }

    @Test
    @Timeout(10)
    public void spliteratorAtExtremeTerminatesWithExactElements()
    {
        // Pre-fix this HANGS: `current += step` wraps after MAX_VALUE and the
        // signed `current <= to` test stays true forever.
        IntInterval iv = IntInterval.fromToBy(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1);
        assertEquals(2, iv.size());
        int[] streamed = StreamSupport.intStream(iv.spliterator(), false).toArray();
        assertArrayEquals(new int[] {Integer.MAX_VALUE - 1, Integer.MAX_VALUE}, streamed);

        AtomicInteger feCount = new AtomicInteger();
        iv.spliterator().forEachRemaining((java.util.function.IntConsumer) value -> feCount.incrementAndGet());
        assertEquals(2, feCount.get());
    }

    @Test
    public void exhaustedSpliteratorReturnsFalseWithoutInvokingAction()
    {
        // Spliterator contract: tryAdvance on an exhausted spliterator must
        // return false WITHOUT invoking the action (the old code accept-ed
        // before checking exhaustion).
        Spliterator.OfInt s = IntInterval.fromToBy(1, 2, 1).spliterator();
        AtomicInteger count = new AtomicInteger();
        java.util.function.IntConsumer counter = value -> count.incrementAndGet();
        assertTrue(s.tryAdvance(counter));
        assertTrue(s.tryAdvance(counter));
        assertFalse(s.tryAdvance(counter));
        assertFalse(s.tryAdvance(counter));
        assertEquals(2, count.get());
    }

    @Test
    public void builderFullRangeEqualsFromToBy()
    {
        // Regression guard for calculateAdjustedStep: `signum(to - from)` wraps
        // (MAX - MIN == -1 across the full int domain), wrongly flipping the step
        // sign so the builder rejects a range that fromToBy accepts. Uses
        // Integer.compare instead, mirroring LongInterval's builder.
        IntInterval built = IntInterval.from(Integer.MIN_VALUE).by(Integer.MAX_VALUE).to(Integer.MAX_VALUE);
        IntInterval direct = IntInterval.fromToBy(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(direct.size(), built.size());
        assertEquals(direct, built);
        for (int i = 0; i < direct.size(); i++)
        {
            assertEquals(direct.get(i), built.get(i));
        }
    }

    @Test
    public void chunkPartitionsDescendingAndNonUnitStepBatches()
    {
        // Locks in the index-driven chunk arithmetic (batchEnd = idx + min(size,
        // remaining), which never overflows int the way `idx + size` does) for
        // descending, non-unit-step, exact-size and partial-last-batch cases.
        RichIterable<IntIterable> descending = IntInterval.fromToBy(10, 1, -3).chunk(2);
        assertEquals(2, descending.size());
        assertArrayEquals(new int[] {10, 7}, descending.getFirst().toArray());
        assertArrayEquals(new int[] {4, 1}, descending.getLast().toArray());

        RichIterable<IntIterable> exact = IntInterval.fromToBy(0, 8, 2).chunk(5);
        assertEquals(1, exact.size());
        assertArrayEquals(new int[] {0, 2, 4, 6, 8}, exact.getFirst().toArray());

        MutableList<IntIterable> partial = IntInterval.fromToBy(1, 5, 1).chunk(2).toList();
        assertEquals(3, partial.size());
        assertArrayEquals(new int[] {1, 2}, partial.get(0).toArray());
        assertArrayEquals(new int[] {3, 4}, partial.get(1).toArray());
        assertArrayEquals(new int[] {5}, partial.get(2).toArray());
    }
}

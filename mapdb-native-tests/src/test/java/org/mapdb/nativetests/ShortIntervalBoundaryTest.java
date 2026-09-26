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

import java.util.concurrent.atomic.AtomicInteger;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.ShortIterable;
import org.mapdb.collections.api.iterator.ShortIterator;
import org.mapdb.collections.impl.list.primitive.ShortInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShortInterval boundary tests, per spec/algorithms.md "Interval over signed
 * integers". A short-typed interval computes in long (the wide type), which
 * trivially cannot overflow for short-magnitude operands, so size/contains/get
 * never wrap at numeric bounds. step 0 throws, and toReversed at the minimum
 * step throws explicitly because -Short.MIN_VALUE is unrepresentable as a short.
 */
public class ShortIntervalBoundaryTest
{
    @Test
    public void boundaryDoesNotWrap()
    {
        // A tight interval at the top of the short domain.
        ShortInterval iv = ShortInterval.fromToBy((short) 32766, (short) 32767, (short) 1);
        assertEquals(2, iv.size());
        assertEquals((short) 32766, iv.get(0));
        assertEquals((short) 32767, iv.get(1));
        assertTrue(iv.contains((short) 32766));
        assertTrue(iv.contains((short) 32767));
        assertFalse(iv.contains((short) 32765));
    }

    @Test
    public void fullShortRangeDoesNotWrap()
    {
        // [Short.MIN_VALUE, Short.MAX_VALUE] by Short.MAX_VALUE.
        // distance 65535 / step 32767 == 2, so size 3.
        // values: -32768, -32768+32767 == -1, -32768+65534 == 32766.
        ShortInterval iv = ShortInterval.fromToBy(Short.MIN_VALUE, Short.MAX_VALUE, Short.MAX_VALUE);
        assertEquals(3, iv.size());
        assertEquals(Short.MIN_VALUE, iv.get(0));
        assertEquals((short) -1, iv.get(1));
        assertEquals((short) 32766, iv.get(2));
        assertTrue(iv.contains(Short.MIN_VALUE));
        assertTrue(iv.contains((short) -1));
        assertTrue(iv.contains((short) 32766));
        // Short.MAX_VALUE (32767) is within [from, to] but NOT on the step grid.
        assertFalse(iv.contains(Short.MAX_VALUE));
    }

    @Test
    public void negativeRangeContainsNoWrap()
    {
        ShortInterval iv = ShortInterval.fromToBy(Short.MIN_VALUE, (short) (Short.MIN_VALUE + 10), (short) 2);
        assertEquals(6, iv.size());
        assertEquals(Short.MIN_VALUE, iv.get(0));
        assertEquals((short) (Short.MIN_VALUE + 10), iv.get(5));
        assertTrue(iv.contains((short) (Short.MIN_VALUE + 4)));
        assertFalse(iv.contains((short) (Short.MIN_VALUE + 5)));
    }

    @Test
    public void fromToByStepZeroThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> ShortInterval.fromToBy((short) 0, (short) 10, (short) 0));
    }

    @Test
    public void toReversedMinimumStepThrows()
    {
        // Build a valid interval whose step is Short.MIN_VALUE (size 2),
        // then reverse it: -step (32768) is not representable as a short and
        // must be rejected explicitly instead of narrowing back to
        // Short.MIN_VALUE.
        ShortInterval iv = ShortInterval.fromToBy((short) 0, Short.MIN_VALUE, Short.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::toReversed);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    public void toReversedNormalStepWorks()
    {
        ShortInterval iv = ShortInterval.fromToBy((short) 1, (short) 10, (short) 1);
        ShortInterval rev = iv.toReversed();
        assertEquals((short) 10, rev.get(0));
        assertEquals((short) 1, rev.get(rev.size() - 1));
        assertEquals(iv.size(), rev.size());
    }

    @Test
    @Timeout(10)
    public void fullDomainTraversalDoesNotWrapOrOverRun()
    {
        // The literal full short domain [MIN, MAX] step 1 == 65536 elements.
        ShortInterval iv = ShortInterval.fromToBy(Short.MIN_VALUE, Short.MAX_VALUE, (short) 1);
        assertEquals(65536, iv.size());

        short[] expected = new short[65536];
        for (int i = 0; i < 65536; i++)
        {
            expected[i] = (short) (Short.MIN_VALUE + i);
        }

        short[] array = iv.toArray();
        assertEquals(iv.size(), array.length);
        assertArrayEquals(expected, array);

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());

        short[] seen = new short[iv.size()];
        AtomicInteger fewiCount = new AtomicInteger();
        iv.forEachWithIndex((value, index) ->
        {
            seen[index] = value;
            fewiCount.incrementAndGet();
        });
        assertEquals(iv.size(), fewiCount.get());
        assertArrayEquals(expected, seen);

        ShortIterator iterator = iv.shortIterator();
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
    public void descendingFullDomainTraversalDoesNotWrapOrOverRun()
    {
        ShortInterval iv = ShortInterval.fromToBy(Short.MAX_VALUE, Short.MIN_VALUE, (short) -1);
        assertEquals(65536, iv.size());

        short[] expected = new short[65536];
        for (int i = 0; i < 65536; i++)
        {
            expected[i] = (short) (Short.MAX_VALUE - i);
        }
        assertArrayEquals(expected, iv.toArray());

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());
    }

    @Test
    public void chunkAtExtremePartitionsAllElements()
    {
        ShortInterval iv = ShortInterval.fromToBy((short) (Short.MAX_VALUE - 2), Short.MAX_VALUE, (short) 1);
        assertEquals(3, iv.size());
        RichIterable<ShortIterable> chunks = iv.chunk(2);
        assertEquals(2, chunks.size());
        assertArrayEquals(new short[] {(short) (Short.MAX_VALUE - 2), (short) (Short.MAX_VALUE - 1)},
                chunks.getFirst().toArray());
        assertArrayEquals(new short[] {Short.MAX_VALUE}, chunks.getLast().toArray());
    }

    @Test
    public void chunkOfSingletonReturnsSingleBatch()
    {
        // Pre-fix this returns [] (element dropped); must be [[5]].
        RichIterable<ShortIterable> chunks = ShortInterval.fromToBy((short) 5, (short) 5, (short) 1).chunk(2);
        assertEquals(1, chunks.size());
        assertArrayEquals(new short[] {5}, chunks.getFirst().toArray());
    }

    @Test
    public void chunkSizeNotLessThanIntervalReturnsSingleBatch()
    {
        RichIterable<ShortIterable> chunks = ShortInterval.fromToBy((short) 1, (short) 3, (short) 1).chunk(10);
        assertEquals(1, chunks.size());
        assertArrayEquals(new short[] {1, 2, 3}, chunks.getFirst().toArray());
    }

    @Test
    public void reversedOffGridKeepsElements()
    {
        // spec/algorithms.md "Reversed() starts from the last element": the reverse
        // begins at the last element actually produced, not at the constructor's
        // `to`, which may sit off the step grid. The old {to, from, -step} form
        // gave 10, 7, 4, 1 for the first case below.
        assertReversed(new short[]{9, 6, 3, 0}, ShortInterval.fromToBy((short) 0, (short) 10, (short) 3));
        assertReversed(new short[]{1, 4, 7, 10}, ShortInterval.fromToBy((short) 10, (short) 0, (short) -3));
        // A step larger than the range yields only `from`; its reverse is `from`, not `to`.
        assertReversed(new short[]{0}, ShortInterval.fromToBy((short) 0, (short) 5, Short.MAX_VALUE));
        assertReversed(new short[]{8, 3, -2, -7}, ShortInterval.fromToBy((short) -7, (short) 9, (short) 5));
        // Max boundary: to = MAX_VALUE off the grid, the last element is found without wrapping.
        assertReversed(
                new short[]{Short.MAX_VALUE - 1, Short.MAX_VALUE - 4, Short.MAX_VALUE - 7},
                ShortInterval.fromToBy((short) (Short.MAX_VALUE - 7), Short.MAX_VALUE, (short) 3));
        // Min boundary descending: to = MIN_VALUE off the grid.
        assertReversed(
                new short[]{Short.MIN_VALUE + 1, Short.MIN_VALUE + 4, Short.MIN_VALUE + 7},
                ShortInterval.fromToBy((short) (Short.MIN_VALUE + 7), Short.MIN_VALUE, (short) -3));
        // Step MIN_VALUE + 1 negates without overflow; only MIN_VALUE itself throws
        // (toReversedMinimumStepThrows).
        assertReversed(
                new short[]{Short.MIN_VALUE + 1, 0},
                ShortInterval.fromToBy((short) 0, (short) (Short.MIN_VALUE + 1), (short) (Short.MIN_VALUE + 1)));
    }

    /**
     * {@code source.toReversed()} yields {@code expected}, agrees with the lazy
     * {@code asReversed()} view, has the source's size and element set (contains
     * agrees on every element and its neighbours), and reversed twice restores
     * the source sequence.
     */
    private static void assertReversed(short[] expected, ShortInterval source)
    {
        ShortInterval reversed = source.toReversed();
        assertArrayEquals(expected, reversed.toArray());
        assertArrayEquals(expected, source.asReversed().toArray());
        assertEquals(expected[0], reversed.getFirst());
        assertEquals(expected[expected.length - 1], reversed.getLast());
        assertEquals(source.size(), reversed.size());
        for (short element : expected)
        {
            assertTrue(source.contains(element), "source missing " + element);
            assertTrue(reversed.contains(element), "reversed missing " + element);
            // Neighbours element-1 and element+1, skipping one that leaves the
            // domain (the sum wraps; a plain `<=` loop bound would never end at MAX).
            for (int offset = -1; offset <= 1; offset += 2)
            {
                short neighbour = (short) (element + offset);
                if (offset < 0 ? neighbour > element : neighbour < element)
                {
                    continue;
                }
                assertEquals(
                        source.contains(neighbour),
                        reversed.contains(neighbour),
                        "contains disagrees at " + neighbour);
            }
        }
        // Reversed twice gives the source sequence (and compares equal: intervals
        // compare by elements, so the normalised `to` is not observable).
        ShortInterval twice = reversed.toReversed();
        assertArrayEquals(source.toArray(), twice.toArray());
        assertEquals(source, twice);
        assertEquals(source.hashCode(), twice.hashCode());
    }
}

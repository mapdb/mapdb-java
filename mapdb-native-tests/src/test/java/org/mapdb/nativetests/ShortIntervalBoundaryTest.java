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
}

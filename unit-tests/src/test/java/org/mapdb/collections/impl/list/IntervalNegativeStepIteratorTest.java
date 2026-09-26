/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 */

package org.mapdb.collections.impl.list;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.iterator.ByteIterator;
import org.mapdb.collections.api.iterator.IntIterator;
import org.mapdb.collections.api.iterator.LongIterator;
import org.mapdb.collections.api.iterator.ShortIterator;
import org.mapdb.collections.impl.list.primitive.ByteInterval;
import org.mapdb.collections.impl.list.primitive.IntInterval;
import org.mapdb.collections.impl.list.primitive.LongInterval;
import org.mapdb.collections.impl.list.primitive.ShortInterval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntervalNegativeStepIteratorTest
{
    @Test
    public void singleElementNegativeStepIteratorsExhaust()
    {
        for (byte value : new byte[]{Byte.MIN_VALUE, 5, Byte.MAX_VALUE})
        {
            ByteIterator iterator = ByteInterval.fromTo(value, value).toReversed().byteIterator();
            assertTrue(iterator.hasNext());
            assertEquals(value, iterator.next());
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
        }

        for (short value : new short[]{Short.MIN_VALUE, 5, Short.MAX_VALUE})
        {
            ShortIterator iterator = ShortInterval.fromTo(value, value).toReversed().shortIterator();
            assertTrue(iterator.hasNext());
            assertEquals(value, iterator.next());
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
        }

        for (int value : new int[]{Integer.MIN_VALUE, 5, Integer.MAX_VALUE})
        {
            IntIterator iterator = IntInterval.fromTo(value, value).toReversed().intIterator();
            assertTrue(iterator.hasNext());
            assertEquals(value, iterator.next());
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);

            var boxed = Interval.fromTo(value, value).reverseThis().iterator();
            assertTrue(boxed.hasNext());
            assertEquals(value, boxed.next());
            assertFalse(boxed.hasNext());
            assertThrows(NoSuchElementException.class, boxed::next);
        }
    }

    @Test
    public void singleElementNegativeStepEqualsOrdinaryListInBothDirections()
    {
        Interval interval = Interval.fromTo(5, 5).reverseThis();
        List<Integer> list = List.of(5);
        assertEquals(list, interval);
        assertEquals(interval, list);
    }

    @Test
    public void negativeStepAtMinimumAndOrdinaryDescendingIntervalExhaust()
    {
        IntIterator minimumStep = IntInterval.fromToBy(5, 5, Integer.MIN_VALUE).intIterator();
        assertEquals(5, minimumStep.next());
        assertFalse(minimumStep.hasNext());

        IntIterator descending = IntInterval.fromToBy(5, 1, -2).intIterator();
        assertEquals(5, descending.next());
        assertEquals(3, descending.next());
        assertEquals(1, descending.next());
        assertFalse(descending.hasNext());

        LongIterator longIterator = LongInterval.fromToBy(5, 5, -1).longIterator();
        assertEquals(5L, longIterator.next());
        assertFalse(longIterator.hasNext());
    }
}

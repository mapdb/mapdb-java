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

import java.util.ArrayList;
import java.util.List;

import org.mapdb.collections.impl.list.Interval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boxed {@link Interval#reverseThis()} per spec/algorithms.md "Reversed() starts
 * from the last element, and panics at minimum step": the reverse begins at the
 * last element actually produced (the {@code to} bound pulled onto the step
 * grid), not at the constructor's {@code to}. Mirrors the primitive
 * {@code *IntervalBoundaryTest.reversedOffGridKeepsElements} tests.
 */
public class IntervalReversedTest
{
    @Test
    public void reversedOffGridKeepsElements()
    {
        // The old {to, from, -step} form gave 10, 7, 4, 1 here.
        assertReversed(new Integer[]{9, 6, 3, 0}, Interval.fromToBy(0, 10, 3));
        assertReversed(new Integer[]{1, 4, 7, 10}, Interval.fromToBy(10, 0, -3));
        // A step larger than the range yields only `from`; its reverse is `from`, not `to`.
        assertReversed(new Integer[]{0}, Interval.fromToBy(0, 5, Integer.MAX_VALUE));
        assertReversed(new Integer[]{8, 3, -2, -7}, Interval.fromToBy(-7, 9, 5));
        // Max boundary: to = MAX_VALUE off the grid, found without wrapping.
        assertReversed(
                new Integer[]{Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 4, Integer.MAX_VALUE - 7},
                Interval.fromToBy(Integer.MAX_VALUE - 7, Integer.MAX_VALUE, 3));
        // Min boundary descending: to = MIN_VALUE off the grid.
        assertReversed(
                new Integer[]{Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 4, Integer.MIN_VALUE + 7},
                Interval.fromToBy(Integer.MIN_VALUE + 7, Integer.MIN_VALUE, -3));
        // Step MIN_VALUE + 1 negates without overflow; only MIN_VALUE itself throws.
        assertReversed(
                new Integer[]{Integer.MIN_VALUE + 1, 0},
                Interval.fromToBy(0, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1));
    }

    @Test
    public void reverseThisMinimumStepThrows()
    {
        Interval iv = Interval.fromToBy(0, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::reverseThis);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    private static void assertReversed(Integer[] expected, Interval source)
    {
        Interval reversed = source.reverseThis();
        assertArrayEquals(expected, reversed.toArray());
        // The lazy reverse traversal of the source visits the same sequence.
        List<Integer> lazy = new ArrayList<>();
        source.reverseForEach(lazy::add);
        assertArrayEquals(expected, lazy.toArray(new Integer[0]));
        // And the iterator of the reverse agrees with its array form.
        List<Integer> iterated = new ArrayList<>();
        for (Integer each : reversed)
        {
            iterated.add(each);
        }
        assertArrayEquals(expected, iterated.toArray(new Integer[0]));
        assertEquals(expected[0], reversed.getFirst());
        assertEquals(expected[expected.length - 1], reversed.getLast());
        assertEquals(source.size(), reversed.size());
        for (int element : expected)
        {
            assertTrue(source.contains(element), "source missing " + element);
            assertTrue(reversed.contains(element), "reversed missing " + element);
            // Neighbours element-1 and element+1, skipping one that leaves the
            // domain (the sum wraps; a plain `<=` loop bound would never end at MAX).
            for (int offset = -1; offset <= 1; offset += 2)
            {
                int neighbour = (int) (element + offset);
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
        // Reversed twice gives the source sequence and compares equal (by elements).
        Interval twice = reversed.reverseThis();
        assertArrayEquals(source.toArray(), twice.toArray());
        assertEquals(source, twice);
        assertEquals(source.hashCode(), twice.hashCode());
    }
}

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

import org.mapdb.collections.impl.list.primitive.IntInterval;
import org.junit.jupiter.api.Test;

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
}

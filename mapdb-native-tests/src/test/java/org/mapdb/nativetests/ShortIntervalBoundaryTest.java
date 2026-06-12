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

import org.mapdb.collections.impl.list.primitive.ShortInterval;
import org.junit.jupiter.api.Test;

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
}

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

import org.mapdb.collections.impl.list.primitive.ByteInterval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ByteInterval boundary tests, per spec/algorithms.md "Interval over signed
 * integers". A byte-typed interval computes in long (the wide type), which
 * trivially cannot overflow for byte-magnitude operands, so size/contains/get
 * never wrap at numeric bounds. step 0 throws, and toReversed at the minimum
 * step throws explicitly because -Byte.MIN_VALUE is unrepresentable as a byte.
 */
public class ByteIntervalBoundaryTest
{
    @Test
    public void boundaryDoesNotWrap()
    {
        // A tight interval at the top of the byte domain.
        ByteInterval iv = ByteInterval.fromToBy((byte) 126, (byte) 127, (byte) 1);
        assertEquals(2, iv.size());
        assertEquals((byte) 126, iv.get(0));
        assertEquals((byte) 127, iv.get(1));
        assertTrue(iv.contains((byte) 126));
        assertTrue(iv.contains((byte) 127));
        assertFalse(iv.contains((byte) 125));
    }

    @Test
    public void fullByteRangeDoesNotWrap()
    {
        // [Byte.MIN_VALUE, Byte.MAX_VALUE] by Byte.MAX_VALUE.
        // distance 255 / step 127 == 2, so size 3.
        // values: -128, -128+127 == -1, -128+254 == 126.
        ByteInterval iv = ByteInterval.fromToBy(Byte.MIN_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE);
        assertEquals(3, iv.size());
        assertEquals(Byte.MIN_VALUE, iv.get(0));
        assertEquals((byte) -1, iv.get(1));
        assertEquals((byte) 126, iv.get(2));
        assertTrue(iv.contains(Byte.MIN_VALUE));
        assertTrue(iv.contains((byte) -1));
        assertTrue(iv.contains((byte) 126));
        // Byte.MAX_VALUE (127) is within [from, to] but NOT on the step grid.
        assertFalse(iv.contains(Byte.MAX_VALUE));
    }

    @Test
    public void negativeRangeContainsNoWrap()
    {
        ByteInterval iv = ByteInterval.fromToBy(Byte.MIN_VALUE, (byte) (Byte.MIN_VALUE + 10), (byte) 2);
        assertEquals(6, iv.size());
        assertEquals(Byte.MIN_VALUE, iv.get(0));
        assertEquals((byte) (Byte.MIN_VALUE + 10), iv.get(5));
        assertTrue(iv.contains((byte) (Byte.MIN_VALUE + 4)));
        assertFalse(iv.contains((byte) (Byte.MIN_VALUE + 5)));
    }

    @Test
    public void fromToByStepZeroThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> ByteInterval.fromToBy((byte) 0, (byte) 10, (byte) 0));
    }

    @Test
    public void toReversedMinimumStepThrows()
    {
        // Build a valid interval whose step is Byte.MIN_VALUE (size 2),
        // then reverse it: -step (128) is not representable as a byte and must
        // be rejected explicitly instead of narrowing back to Byte.MIN_VALUE.
        ByteInterval iv = ByteInterval.fromToBy((byte) 0, Byte.MIN_VALUE, Byte.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::toReversed);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    public void toReversedNormalStepWorks()
    {
        ByteInterval iv = ByteInterval.fromToBy((byte) 1, (byte) 10, (byte) 1);
        ByteInterval rev = iv.toReversed();
        assertEquals((byte) 10, rev.get(0));
        assertEquals((byte) 1, rev.get(rev.size() - 1));
        assertEquals(iv.size(), rev.size());
    }
}

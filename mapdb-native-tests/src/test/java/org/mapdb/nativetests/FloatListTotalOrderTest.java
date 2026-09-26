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

import org.mapdb.collections.impl.list.mutable.primitive.DoubleArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.FloatArrayList;
import org.mapdb.collections.impl.set.mutable.primitive.FloatHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Float/double min, max, sort and binarySearch follow IEEE 754 totalOrder
 * (spec/algorithms.md float order): a negative NaN sorts below -Inf, a positive
 * NaN above +Inf, and NaN payloads are ordered by their raw bits. Findings
 * fable-spec-allign 06-java F21/F22.
 */
public class FloatListTotalOrderTest
{
    private static final float NEG_NAN = Float.intBitsToFloat(0xFFC00000);
    private static final float NEG_NAN_PAYLOAD = Float.intBitsToFloat(0xFFC00001);
    private static final float POS_NAN = Float.intBitsToFloat(0x7FC00000);
    private static final float POS_NAN_PAYLOAD = Float.intBitsToFloat(0x7FC00001);

    private static int[] bits(float[] values)
    {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++)
        {
            result[i] = Float.floatToRawIntBits(values[i]);
        }
        return result;
    }

    private static long[] bits(double[] values)
    {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++)
        {
            result[i] = Double.doubleToRawLongBits(values[i]);
        }
        return result;
    }

    @Test
    public void minMaxWithNegativeNaN()
    {
        FloatArrayList list = FloatArrayList.newListWith(NEG_NAN, 1.0f);
        assertEquals(0xFFC00000, Float.floatToRawIntBits(list.min()));
        assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(list.max()));

        FloatArrayList mixed = FloatArrayList.newListWith(1.0f, POS_NAN, Float.NEGATIVE_INFINITY, NEG_NAN, -0.0f);
        assertEquals(0xFFC00000, Float.floatToRawIntBits(mixed.min()));
        assertEquals(0x7FC00000, Float.floatToRawIntBits(mixed.max()));

        FloatHashSet set = FloatHashSet.newSetWith(NEG_NAN, 1.0f);
        assertEquals(0xFFC00000, Float.floatToRawIntBits(set.min()));
        assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(set.max()));

        assertEquals(0xFFC00000, Float.floatToRawIntBits(FloatArrayList.newListWith(1.0f, NEG_NAN).toImmutable().min()));
    }

    @Test
    public void payloadOrderingInMinMax()
    {
        FloatArrayList list = FloatArrayList.newListWith(POS_NAN, POS_NAN_PAYLOAD, NEG_NAN, NEG_NAN_PAYLOAD);
        // totalOrder: 0xFFC00001 < 0xFFC00000 < ... < 0x7FC00000 < 0x7FC00001
        assertEquals(0xFFC00001, Float.floatToRawIntBits(list.min()));
        assertEquals(0x7FC00001, Float.floatToRawIntBits(list.max()));
    }

    @Test
    public void doubleMinMaxWithNegativeNaN()
    {
        double negNaN = Double.longBitsToDouble(0xFFF8000000000000L);
        DoubleArrayList list = DoubleArrayList.newListWith(negNaN, 1.0);
        assertEquals(0xFFF8000000000000L, Double.doubleToRawLongBits(list.min()));
        assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(list.max()));
    }

    @Test
    public void sortThisIsTotalOrder()
    {
        FloatArrayList list = FloatArrayList.newListWith(
                POS_NAN_PAYLOAD, 1.0f, NEG_NAN, 0.0f, Float.POSITIVE_INFINITY,
                POS_NAN, -0.0f, NEG_NAN_PAYLOAD, Float.NEGATIVE_INFINITY, -1.0f);
        float[] expected = {
                NEG_NAN_PAYLOAD, NEG_NAN, Float.NEGATIVE_INFINITY, -1.0f, -0.0f,
                0.0f, 1.0f, Float.POSITIVE_INFINITY, POS_NAN, POS_NAN_PAYLOAD};
        assertArrayEquals(bits(expected), bits(list.toSortedArray()));
        assertArrayEquals(bits(expected), bits(list.toSortedList().toArray()));
        assertArrayEquals(bits(expected), bits(FloatHashSet.newSetWith(list.toArray()).toSortedArray()));
        assertArrayEquals(bits(expected), bits(list.sortThis().toArray()));

        // binarySearch agrees with the sorted order, including NaNs.
        for (int i = 0; i < expected.length; i++)
        {
            assertEquals(i, list.binarySearch(expected[i]), "index " + i);
        }
        assertEquals(-3, list.binarySearch(Float.intBitsToFloat(0xFFBFFFFF)), "insertion point after NEG_NAN");
    }

    @Test
    public void sortThisOnlyNaNsAndSubRange()
    {
        FloatArrayList list = FloatArrayList.newListWith(POS_NAN, NEG_NAN, NEG_NAN_PAYLOAD, POS_NAN_PAYLOAD);
        assertArrayEquals(
                bits(new float[] {NEG_NAN_PAYLOAD, NEG_NAN, POS_NAN, POS_NAN_PAYLOAD}),
                bits(list.sortThis().toArray()));

        // Backing array larger than size: only [0, size) is sorted.
        FloatArrayList grown = new FloatArrayList(16);
        grown.addAll(2.0f, NEG_NAN, 1.0f);
        assertArrayEquals(bits(new float[] {NEG_NAN, 1.0f, 2.0f}), bits(grown.sortThis().toArray()));
    }

    @Test
    public void doubleSortThisIsTotalOrder()
    {
        double negNaN = Double.longBitsToDouble(0xFFF8000000000000L);
        double posNaN = Double.longBitsToDouble(0x7FF8000000000000L);
        DoubleArrayList list = DoubleArrayList.newListWith(posNaN, 1.0, negNaN, Double.NEGATIVE_INFINITY);
        assertArrayEquals(
                bits(new double[] {negNaN, Double.NEGATIVE_INFINITY, 1.0, posNaN}),
                bits(list.sortThis().toArray()));
        assertEquals(0, list.binarySearch(negNaN));
        assertEquals(3, list.binarySearch(posNaN));
    }
}

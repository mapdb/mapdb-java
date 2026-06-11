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

import java.util.Arrays;

import org.mapdb.collections.impl.utility.FloatTotalOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IEEE 754 totalOrder comparator tests, per spec/algorithms.md "Float ordering
 * for tree collections". Verifies the full ordering chain, distinct NaN-payload
 * ordering, and consistency with raw-bit equality. Bit-identical to Rust
 * f32::total_cmp.
 */
public class FloatTotalOrderTest
{
    private static int sgn(int x)
    {
        return Integer.compare(x, 0);
    }

    @Test
    public void floatFullOrderingChain()
    {
        float negNaN = Float.intBitsToFloat(0xFFC00000);
        float negInf = Float.NEGATIVE_INFINITY;
        float negOne = -1.0f;
        float negZero = -0.0f;
        float posZero = 0.0f;
        float one = 1.0f;
        float posInf = Float.POSITIVE_INFINITY;
        float posNaN = Float.intBitsToFloat(0x7FC00000);

        // -NaN < -Inf < -1 < -0.0 < +0.0 < 1 < +Inf < +NaN
        float[] ordered = {negNaN, negInf, negOne, negZero, posZero, one, posInf, posNaN};
        for (int i = 0; i + 1 < ordered.length; i++)
        {
            assertTrue(FloatTotalOrder.totalCompare(ordered[i], ordered[i + 1]) < 0,
                    "expected ordered[" + i + "] < ordered[" + (i + 1) + "]");
            assertTrue(FloatTotalOrder.totalCompare(ordered[i + 1], ordered[i]) > 0,
                    "expected ordered[" + (i + 1) + "] > ordered[" + i + "]");
        }
    }

    @Test
    public void floatSortMatchesTotalOrder()
    {
        Float[] in = {
                1.0f, Float.intBitsToFloat(0x7FC00000), -0.0f, 0.0f,
                Float.NEGATIVE_INFINITY, Float.intBitsToFloat(0xFFC00000),
                Float.POSITIVE_INFINITY, -1.0f};
        Arrays.sort(in, FloatTotalOrder.FLOAT_COMPARATOR);
        Float[] expected = {
                Float.intBitsToFloat(0xFFC00000), Float.NEGATIVE_INFINITY, -1.0f,
                -0.0f, 0.0f, 1.0f, Float.POSITIVE_INFINITY,
                Float.intBitsToFloat(0x7FC00000)};
        for (int i = 0; i < expected.length; i++)
        {
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(in[i]),
                    "mismatch at " + i);
        }
    }

    @Test
    public void floatDistinctNaNPayloadsOrderAscending()
    {
        float p0 = Float.intBitsToFloat(0x7FC00000);
        float p1 = Float.intBitsToFloat(0x7FC00001);
        float p2 = Float.intBitsToFloat(0x7FC00002);
        assertTrue(FloatTotalOrder.totalCompare(p0, p1) < 0);
        assertTrue(FloatTotalOrder.totalCompare(p1, p2) < 0);
        assertTrue(FloatTotalOrder.totalCompare(p0, p2) < 0);
    }

    @Test
    public void floatSignedZeroOrdered()
    {
        assertTrue(FloatTotalOrder.totalCompare(-0.0f, 0.0f) < 0);
        assertTrue(FloatTotalOrder.totalCompare(0.0f, -0.0f) > 0);
    }

    @Test
    public void floatConsistentWithRawBitEquality()
    {
        // totalCompare == 0 iff raw bits are equal.
        float[] sample = {
                0.0f, -0.0f, 1.0f, -1.0f, Float.MIN_VALUE, Float.MAX_VALUE,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.intBitsToFloat(0x7FC00000), Float.intBitsToFloat(0x7FC00001),
                Float.intBitsToFloat(0xFFC00000)};
        for (float a : sample)
        {
            for (float b : sample)
            {
                boolean bitsEqual = Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
                assertEquals(bitsEqual, FloatTotalOrder.totalCompare(a, b) == 0,
                        "raw-bit-eq vs totalCompare==0 mismatch for "
                                + Integer.toHexString(Float.floatToRawIntBits(a)) + " / "
                                + Integer.toHexString(Float.floatToRawIntBits(b)));
            }
        }
    }

    @Test
    public void floatMatchesIntegerCompareOfTransformedBits()
    {
        // The construction is bit-identical to Rust total_cmp; cross-check
        // against a straightforward signed-key transform.
        float[] sample = {
                -1.0f, 0.0f, -0.0f, 1.0f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.intBitsToFloat(0x7FC00000), Float.intBitsToFloat(0xFFC00000)};
        for (float a : sample)
        {
            for (float b : sample)
            {
                int ka = key(a);
                int kb = key(b);
                assertEquals(sgn(Integer.compare(ka, kb)),
                        sgn(FloatTotalOrder.totalCompare(a, b)));
            }
        }
    }

    private static int key(float f)
    {
        int bits = Float.floatToRawIntBits(f);
        return bits < 0 ? bits ^ 0x7FFFFFFF : bits;
    }

    // ---- double ----

    @Test
    public void doubleFullOrderingChain()
    {
        double[] ordered = {
                Double.longBitsToDouble(0xFFF8000000000000L), Double.NEGATIVE_INFINITY,
                -1.0d, -0.0d, 0.0d, 1.0d, Double.POSITIVE_INFINITY,
                Double.longBitsToDouble(0x7FF8000000000000L)};
        for (int i = 0; i + 1 < ordered.length; i++)
        {
            assertTrue(FloatTotalOrder.totalCompare(ordered[i], ordered[i + 1]) < 0,
                    "expected ordered[" + i + "] < ordered[" + (i + 1) + "]");
        }
    }

    @Test
    public void doubleConsistentWithRawBitEquality()
    {
        double[] sample = {
                0.0d, -0.0d, 1.0d, -1.0d, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7FF8000000000000L),
                Double.longBitsToDouble(0x7FF8000000000001L),
                Double.longBitsToDouble(0xFFF8000000000000L)};
        for (double a : sample)
        {
            for (double b : sample)
            {
                boolean bitsEqual = Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
                assertEquals(bitsEqual, FloatTotalOrder.totalCompare(a, b) == 0);
            }
        }
    }
}

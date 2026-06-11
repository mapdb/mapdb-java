/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.utility;

import java.util.Comparator;

/**
 * IEEE 754 {@code totalOrder} comparison for {@code float} and {@code double}.
 *
 * <p>This is the sign-flip construction from the mapdb spec
 * ({@code spec/algorithms.md} &sect;"Float ordering for tree collections"),
 * bit-identical to Rust's {@code f32::total_cmp} / {@code f64::total_cmp}. It is
 * the comparator that float-keyed tree collections (TreeSet/TreeMap/TreeBag)
 * MUST use: a raw bit compare is not a total order when NaN coexists with
 * negative floats, and branching on {@code isNaN} first is intransitive.
 *
 * <p>Resulting order, least to greatest:
 * <pre>
 * -NaN (all payloads) &lt; -Inf &lt; negative finite &lt; -0.0
 *                     &lt; +0.0 &lt; positive finite &lt; +Inf &lt; +NaN (all payloads)
 * </pre>
 *
 * <p>Every distinct NaN bit pattern is a distinct, orderable key; {@code +0.0}
 * and {@code -0.0} are distinct ({@code -0.0} immediately precedes {@code +0.0}).
 * This is consistent with the raw-bit hash and equality used elsewhere.
 */
public final class FloatTotalOrder
{
    /**
     * A {@link Comparator} ordering {@link Float} by IEEE 754 totalOrder.
     */
    public static final Comparator<Float> FLOAT_COMPARATOR = FloatTotalOrder::totalCompare;

    /**
     * A {@link Comparator} ordering {@link Double} by IEEE 754 totalOrder.
     */
    public static final Comparator<Double> DOUBLE_COMPARATOR = FloatTotalOrder::totalCompare;

    private FloatTotalOrder()
    {
    }

    /**
     * Compares two {@code float} values by IEEE 754 totalOrder.
     *
     * @return a negative integer, zero, or a positive integer as {@code a} is
     *         less than, equal to, or greater than {@code b} in totalOrder.
     */
    public static int totalCompare(float a, float b)
    {
        // 1. reinterpret the float's bits as a same-width signed integer.
        int ai = Float.floatToRawIntBits(a);
        int bi = Float.floatToRawIntBits(b);

        // 2. if the sign bit is set (i < 0), flip all bits except the sign bit.
        //    Arithmetic-shift the sign bit across the word, then logical-shift
        //    it back by one so the sign bit stays fixed, building the mask
        //    0x7FFFFFFF for negatives and 0x00000000 for non-negatives, and XOR.
        ai ^= (ai >> 31) >>> 1;
        bi ^= (bi >> 31) >>> 1;

        // 3. compare the transformed integers as signed.
        return Integer.compare(ai, bi);
    }

    /**
     * Compares two {@code double} values by IEEE 754 totalOrder.
     *
     * @return a negative integer, zero, or a positive integer as {@code a} is
     *         less than, equal to, or greater than {@code b} in totalOrder.
     */
    public static int totalCompare(double a, double b)
    {
        long ai = Double.doubleToRawLongBits(a);
        long bi = Double.doubleToRawLongBits(b);

        ai ^= (ai >> 63) >>> 1;
        bi ^= (bi >> 63) >>> 1;

        return Long.compare(ai, bi);
    }

    private static int totalCompare(Float a, Float b)
    {
        return totalCompare(a.floatValue(), b.floatValue());
    }

    private static int totalCompare(Double a, Double b)
    {
        return totalCompare(a.doubleValue(), b.doubleValue());
    }
}

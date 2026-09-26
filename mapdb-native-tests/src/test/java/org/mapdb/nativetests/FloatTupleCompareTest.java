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

import org.mapdb.collections.api.tuple.primitive.DoubleDoublePair;
import org.mapdb.collections.api.tuple.primitive.FloatFloatPair;
import org.mapdb.collections.api.tuple.primitive.FloatObjectPair;
import org.mapdb.collections.api.tuple.primitive.ObjectFloatPair;
import org.mapdb.collections.impl.block.comparator.primitive.FloatFunctionComparator;
import org.mapdb.collections.impl.tuple.primitive.PrimitiveTuples;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Float/double tuple {@code compareTo} and the primitive function comparators
 * follow IEEE 754 totalOrder, consistent with the raw-bit {@code equals}
 * (fable-spec-allign 06-java F10).
 */
public class FloatTupleCompareTest
{
    private static final float NEG_NAN = Float.intBitsToFloat(0xFFC00000);
    private static final float POS_NAN = Float.intBitsToFloat(0x7FC00000);
    private static final float POS_NAN_PAYLOAD = Float.intBitsToFloat(0x7FC00001);

    @Test
    public void distinctNaNPayloadsCompareConsistentWithEquals()
    {
        FloatFloatPair a = PrimitiveTuples.pair(POS_NAN, 1.0f);
        FloatFloatPair b = PrimitiveTuples.pair(POS_NAN_PAYLOAD, 1.0f);
        assertNotEquals(a, b);
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, a.compareTo(PrimitiveTuples.pair(POS_NAN, 1.0f)));

        FloatFloatPair c = PrimitiveTuples.pair(1.0f, POS_NAN);
        FloatFloatPair d = PrimitiveTuples.pair(1.0f, POS_NAN_PAYLOAD);
        assertNotEquals(c, d);
        assertTrue(c.compareTo(d) < 0);
    }

    @Test
    public void negativeNaNSortsBelowNegativeInfinity()
    {
        FloatFloatPair negNaN = PrimitiveTuples.pair(NEG_NAN, 0.0f);
        FloatFloatPair negInf = PrimitiveTuples.pair(Float.NEGATIVE_INFINITY, 0.0f);
        assertTrue(negNaN.compareTo(negInf) < 0);

        ObjectFloatPair<String> o1 = PrimitiveTuples.pair("k", NEG_NAN);
        ObjectFloatPair<String> o2 = PrimitiveTuples.pair("k", Float.NEGATIVE_INFINITY);
        assertTrue(o1.compareTo(o2) < 0);

        FloatObjectPair<String> p1 = PrimitiveTuples.pair(NEG_NAN, "k");
        FloatObjectPair<String> p2 = PrimitiveTuples.pair(Float.NEGATIVE_INFINITY, "k");
        assertTrue(p1.compareTo(p2) < 0);

        DoubleDoublePair d1 = PrimitiveTuples.pair(Double.longBitsToDouble(0xFFF8000000000000L), 0.0);
        DoubleDoublePair d2 = PrimitiveTuples.pair(Double.NEGATIVE_INFINITY, 0.0);
        assertTrue(d1.compareTo(d2) < 0);
    }

    @Test
    public void functionComparatorIsTotalOrder()
    {
        FloatFunctionComparator<Float> cmp = new FloatFunctionComparator<>(Float::floatValue);
        assertTrue(cmp.compare(NEG_NAN, Float.NEGATIVE_INFINITY) < 0);
        assertTrue(cmp.compare(POS_NAN, POS_NAN_PAYLOAD) < 0);
        assertTrue(cmp.compare(-0.0f, 0.0f) < 0);
    }
}

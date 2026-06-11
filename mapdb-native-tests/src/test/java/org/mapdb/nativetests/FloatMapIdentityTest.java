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

import org.eclipse.collections.impl.map.mutable.primitive.DoubleIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntDoubleHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntFloatHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw-bit float identity for float-keyed maps (FloatIntHashMap/DoubleIntHashMap)
 * and float-valued maps (IntFloatHashMap/IntDoubleHashMap), per spec/algorithms.md
 * "Tests required" and the float-valued-map additions.
 */
public class FloatMapIdentityTest
{
    private static final float NAN_CANON = Float.intBitsToFloat(0x7FC00000);
    private static final float NAN_PAYLOAD = Float.intBitsToFloat(0x7FC00001);
    private static final float NAN_NEG = Float.intBitsToFloat(0xFFC00000);

    private static final double DNAN_CANON = Double.longBitsToDouble(0x7FF8000000000000L);

    // ---- Float-keyed map: NaN key contract ----

    @Test
    public void floatNaNKey_Findable()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(NAN_CANON, 42);
        assertTrue(map.containsKey(NAN_CANON));
        assertEquals(42, map.get(NAN_CANON));
    }

    @Test
    public void floatNaNKey_Replaces()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(NAN_CANON, 1);
        map.put(NAN_CANON, 2);
        assertEquals(2, map.get(NAN_CANON));
        assertEquals(1, map.size());
    }

    @Test
    public void floatNaNKey_Remove()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(NAN_CANON, 1);
        map.remove(NAN_CANON);
        assertFalse(map.containsKey(NAN_CANON));
        assertEquals(0, map.size());
    }

    @Test
    public void floatNegativeZeroDistinctKeys()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(0.0f, 1);
        map.put(-0.0f, 2);
        assertEquals(2, map.size());
        assertEquals(1, map.get(0.0f));
        assertEquals(2, map.get(-0.0f));
    }

    @Test
    public void floatInfinityKeys()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(Float.POSITIVE_INFINITY, 1);
        map.put(Float.NEGATIVE_INFINITY, 2);
        assertEquals(2, map.size());
        assertEquals(1, map.get(Float.POSITIVE_INFINITY));
        assertEquals(2, map.get(Float.NEGATIVE_INFINITY));
    }

    /** Headline proof: FAILS on stock EC (NaN keys collapse to one). */
    @Test
    public void floatDistinctNaNPayloadKeys()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(NAN_CANON, 1);
        map.put(NAN_PAYLOAD, 2);
        map.put(NAN_NEG, 3);
        assertEquals(3, map.size());
        assertEquals(1, map.get(NAN_CANON));
        assertEquals(2, map.get(NAN_PAYLOAD));
        assertEquals(3, map.get(NAN_NEG));
    }

    // ---- Double-keyed map ----

    @Test
    public void doubleNaNKey_Findable()
    {
        DoubleIntHashMap map = new DoubleIntHashMap();
        map.put(DNAN_CANON, 7);
        assertTrue(map.containsKey(DNAN_CANON));
        assertEquals(7, map.get(DNAN_CANON));
    }

    @Test
    public void doubleNegativeZeroDistinctKeys()
    {
        DoubleIntHashMap map = new DoubleIntHashMap();
        map.put(0.0d, 1);
        map.put(-0.0d, 2);
        assertEquals(2, map.size());
    }

    // ---- Float-valued map: NaN value contract ----

    @Test
    public void floatNaNValue_ContainsValue()
    {
        IntFloatHashMap map = new IntFloatHashMap();
        map.put(1, NAN_CANON);
        assertTrue(map.containsValue(NAN_CANON));
    }

    @Test
    public void floatNaNValue_GetReturnsNaN()
    {
        IntFloatHashMap map = new IntFloatHashMap();
        map.put(1, NAN_CANON);
        assertTrue(Float.isNaN(map.get(1)));
    }

    @Test
    public void floatNaNValue_DistinctPayloadContainsValue()
    {
        IntFloatHashMap map = new IntFloatHashMap();
        map.put(1, NAN_CANON);
        // a different NaN payload must NOT be reported as present (raw-bit value identity)
        assertTrue(map.containsValue(NAN_CANON));
        assertFalse(map.containsValue(NAN_PAYLOAD));
    }

    @Test
    public void doubleNaNValue_ContainsValueAndGet()
    {
        IntDoubleHashMap map = new IntDoubleHashMap();
        map.put(1, DNAN_CANON);
        assertTrue(map.containsValue(DNAN_CANON));
        assertTrue(Double.isNaN(map.get(1)));
    }
}

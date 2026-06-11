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

import org.eclipse.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sentinel-machinery boundary tests, mandated by spec/style/java.md
 * "Sentinel maps -- documented SPEC DEBT". EC's primitive maps reserve keys 0
 * and 1 into a SentinelValues side object; these tests prove the reserved keys
 * work as real data, that float +0.0/-0.0/1.0 are handled bit-exactly (the
 * raw-bit change removed the Float.compare hazard), and that NaN-payload keys
 * probe the sentinel machinery correctly.
 */
public class SentinelBoundaryTest
{
    // ---- int keys: reserved sentinels 0 and 1 as real data ----

    @Test
    public void intKeysZeroAndOneAsRealData()
    {
        IntIntHashMap map = new IntIntHashMap();
        map.put(0, 100);
        map.put(1, 101);
        map.put(2, 102);
        assertEquals(3, map.size());
        assertTrue(map.containsKey(0));
        assertTrue(map.containsKey(1));
        assertEquals(100, map.get(0));
        assertEquals(101, map.get(1));
        assertEquals(102, map.get(2));

        map.remove(0);
        assertFalse(map.containsKey(0));
        assertTrue(map.containsKey(1));
        assertEquals(2, map.size());

        map.remove(1);
        assertFalse(map.containsKey(1));
        assertEquals(1, map.size());
    }

    @Test
    public void intKeyZeroReplaceAndUpdate()
    {
        IntIntHashMap map = new IntIntHashMap();
        map.put(0, 1);
        map.put(0, 2);
        assertEquals(2, map.get(0));
        assertEquals(1, map.size());
        map.addToValue(0, 5);
        assertEquals(7, map.get(0));
    }

    // ---- float keys: +0.0 / -0.0 / 1.0 (sentinel-adjacent) bit-exact ----

    @Test
    public void floatSentinelKeysBitExact()
    {
        // EC's FloatIntHashMap reserves 0.0f and 1.0f as table EMPTY/REMOVED
        // and routes the literal keys 0.0f/1.0f through SentinelValues. After
        // the raw-bit change, -0.0f (bits 0x80000000) is distinct from the
        // +0.0f sentinel (bits 0x0).
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(0.0f, 1);
        map.put(-0.0f, 2);
        map.put(1.0f, 3);
        assertEquals(3, map.size());
        assertEquals(1, map.get(0.0f));
        assertEquals(2, map.get(-0.0f));
        assertEquals(3, map.get(1.0f));
        assertTrue(map.containsKey(0.0f));
        assertTrue(map.containsKey(-0.0f));
        assertTrue(map.containsKey(1.0f));
    }

    @Test
    public void floatSentinelKeysRemove()
    {
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(0.0f, 1);
        map.put(-0.0f, 2);
        map.put(1.0f, 3);
        map.remove(0.0f);
        assertFalse(map.containsKey(0.0f));
        assertTrue(map.containsKey(-0.0f));
        assertTrue(map.containsKey(1.0f));
        assertEquals(2, map.size());
    }

    @Test
    public void floatNaNPayloadKeysProbingSentinelMachinery()
    {
        // Mix the reserved-sentinel float keys (0.0/1.0) with distinct NaN
        // payloads to exercise both the sentinel side object and the open
        // table probe in the same map.
        FloatIntHashMap map = new FloatIntHashMap();
        map.put(0.0f, 1);
        map.put(1.0f, 2);
        map.put(Float.intBitsToFloat(0x7FC00000), 3);
        map.put(Float.intBitsToFloat(0x7FC00001), 4);
        map.put(Float.intBitsToFloat(0xFFC00000), 5);
        assertEquals(5, map.size());
        assertEquals(1, map.get(0.0f));
        assertEquals(2, map.get(1.0f));
        assertEquals(3, map.get(Float.intBitsToFloat(0x7FC00000)));
        assertEquals(4, map.get(Float.intBitsToFloat(0x7FC00001)));
        assertEquals(5, map.get(Float.intBitsToFloat(0xFFC00000)));
    }
}

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

import java.lang.reflect.Field;

import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity / resize obligation, mandated by spec/style/java.md "Load factor --
 * stricter threshold". EC's open-addressing primitive maps grow at ~50%
 * occupancy ({@code maxOccupiedWithData() == capacity / 2}); the normative
 * invariant in spec/algorithms.md is "load factor strictly below 0.75". This
 * test pins both: across a wide insertion range the live occupancy never
 * exceeds the stricter ~50% threshold (and therefore stays well under the 0.75
 * ceiling), the table actually grows (resize happens, no data lost), and the
 * backing array stays a power-of-two length.
 *
 * <p>The internal {@code keysValues} array and {@code occupiedWithData} counter
 * are {@code private} on the generated map, so this reads them reflectively --
 * acceptable for a port-specific native test of an internal layout invariant.
 */
public class CapacityResizeTest
{
    private static int[] keysValues(IntIntHashMap map) throws Exception
    {
        Field f = IntIntHashMap.class.getDeclaredField("keysValues");
        f.setAccessible(true);
        return (int[]) f.get(map);
    }

    private static int occupiedWithData(IntIntHashMap map) throws Exception
    {
        Field f = IntIntHashMap.class.getDeclaredField("occupiedWithData");
        f.setAccessible(true);
        return f.getInt(map);
    }

    @Test
    public void occupancyStaysUnderHalfAndTableGrows() throws Exception
    {
        IntIntHashMap map = new IntIntHashMap();
        int initialCapacity = keysValues(map).length / 2;
        int maxCapacitySeen = initialCapacity;

        int n = 5000;
        for (int i = 0; i < n; i++)
        {
            map.put(i, i * 2);

            int capacity = keysValues(map).length / 2;   // key+value interleaved
            int occupied = occupiedWithData(map);
            maxCapacitySeen = Math.max(maxCapacitySeen, capacity);

            // Stricter ~50% growth threshold: occupied never exceeds capacity / 2.
            assertTrue(occupied * 2 <= capacity,
                    "occupancy exceeded 50%: " + occupied + " of " + capacity + " at i=" + i);
            // Therefore comfortably below the spec's 0.75 ceiling.
            assertTrue(occupied * 4 < capacity * 3,
                    "occupancy reached the 0.75 ceiling: " + occupied + " of " + capacity);
            // Power-of-two backing length (mask-based indexing depends on it).
            assertTrue(Integer.bitCount(capacity) == 1,
                    "capacity not a power of two: " + capacity);
        }

        // The table actually resized (grew) -- otherwise the threshold is vacuous.
        assertTrue(maxCapacitySeen > initialCapacity,
                "table never grew from initial capacity " + initialCapacity);
        assertEquals(n, map.size());

        // No data lost across the resizes.
        for (int i = 0; i < n; i++)
        {
            assertTrue(map.containsKey(i), "lost key " + i);
            assertEquals(i * 2, map.get(i));
        }
    }
}

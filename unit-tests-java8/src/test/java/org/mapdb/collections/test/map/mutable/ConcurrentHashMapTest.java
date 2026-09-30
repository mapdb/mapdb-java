/*
 * Copyright (c) 2021 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.test.map.mutable;

import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import org.mapdb.collections.test.map.MapKeySetTestCase;
import org.mapdb.collections.test.map.MapValuesCollectionTestCase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.mapdb.collections.test.IterableTestCase.assertIterablesEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ConcurrentHashMapTest implements MutableMapTestCase
{
    private static final long CURRENT_TIME_MILLIS = System.currentTimeMillis();

    @Override
    public <T> MutableMap<Object, T> newWith(T... elements)
    {
        Random random = new Random(CURRENT_TIME_MILLIS);
        MutableMap<Object, T> result = new ConcurrentHashMap<>();
        for (T each : elements)
        {
            assertNull(result.put(random.nextDouble(), each));
        }
        return result;
    }

    @Override
    public <K, V> MutableMap<K, V> newWithKeysValues(Object... elements)
    {
        if (elements.length % 2 != 0)
        {
            fail(String.valueOf(elements.length));
        }

        MutableMap<K, V> result = new ConcurrentHashMap<>();
        for (int i = 0; i < elements.length; i += 2)
        {
            assertNull(result.put((K) elements[i], (V) elements[i + 1]));
        }
        return result;
    }

    @Override
    public boolean supportsNullKeys()
    {
        return false;
    }

    // ConcurrentMap redefines putIfAbsent using key presence, including null mappings.
    @Override
    @Test
    public void Map_putIfAbsent()
    {
        Map<Integer, String> map = this.newWithKeysValues(1, "1", 2, "2", 3, "3");

        assertEquals("1", map.putIfAbsent(1, "One"));
        assertIterablesEqual(this.newWithKeysValues(1, "1", 2, "2", 3, "3"), map);

        assertNull(map.putIfAbsent(4, "4"));
        assertIterablesEqual(this.newWithKeysValues(1, "1", 2, "2", 3, "3", 4, "4"), map);

        Map<Integer, String> map2 = this.newWithKeysValues(1, "1", 2, "2");
        assertNull(map2.putIfAbsent(5, null));
        assertTrue(map2.containsKey(5));

        // ConcurrentMap.putIfAbsent uses key presence; a present null
        // mapping is retained, unlike Map.computeIfAbsent.
        Map<Integer, String> map3 = this.newWithKeysValues(1, null, 2, "2");
        assertNull(map3.putIfAbsent(1, "One"));
        assertNull(map3.get(1));
    }

    @Nested
    public class KeySetView implements MapKeySetTestCase
    {
        @Override
        public boolean allowsSerialization()
        {
            return false;
        }

        @SafeVarargs
        @Override
        public final <T> Set<T> newWith(T... elements)
        {
            Random random = new Random(CURRENT_TIME_MILLIS);
            MutableMap<T, Object> result = new ConcurrentHashMap<>();
            for (T element : elements)
            {
                assertNull(result.put(element, random.nextDouble()));
            }
            return result.keySet();
        }
    }

    @Nested
    public class ValuesCollectionView implements MapValuesCollectionTestCase
    {
        @Override
        public boolean allowsSerialization()
        {
            return false;
        }

        @SafeVarargs
        @Override
        public final <T> Collection<T> newWith(T... elements)
        {
            return ConcurrentHashMapTest.this.newWith(elements).values();
        }
    }
}

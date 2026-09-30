package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.map.primitive.MutableObjectBooleanMap;
import org.mapdb.collections.impl.block.factory.HashingStrategies;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMapWithHashingStrategy;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectBooleanValuesRemovalTest
{
    @Test public void ordinaryEmptyValuesRemoval()
    {
        checkEmpty(new ObjectBooleanHashMap<>());
    }

    @Test public void strategyEmptyValuesRemoval()
    {
        checkEmpty(new ObjectBooleanHashMapWithHashingStrategy<>(HashingStrategies.defaultStrategy()));
    }

    @Test public void ordinaryPopulatedValuesRemoval()
    {
        checkPopulated(new ObjectBooleanHashMap<>());
    }

    @Test public void strategyPopulatedValuesRemoval()
    {
        checkPopulated(new ObjectBooleanHashMapWithHashingStrategy<>(HashingStrategies.defaultStrategy()));
    }

    private void checkEmpty(MutableObjectBooleanMap<Integer> map)
    {
        assertFalse(map.values().remove(false));
        assertFalse(map.values().remove(true));
    }

    private void checkPopulated(MutableObjectBooleanMap<Integer> map)
    {
        map.put(null, true);
        map.put(2, true);
        map.put(3, true);
        assertFalse(map.values().remove(false));
        assertEquals(3, map.size());
        assertTrue(map.containsKey(null));
        assertTrue(map.get(null));
        assertTrue(map.get(2));
        assertTrue(map.get(3));
        assertTrue(map.values().remove(true));
        assertEquals(2, map.size());
        assertFalse(map.values().remove(false));
        while (!map.isEmpty()) assertTrue(map.values().remove(true));
        assertFalse(map.values().remove(true));
        map.put(4, false);
        assertTrue(map.values().remove(false));
        assertTrue(map.isEmpty());
        assertFalse(map.values().remove(false));
    }
}

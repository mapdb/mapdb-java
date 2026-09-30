package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.api.map.primitive.MutableObjectBooleanMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMapWithHashingStrategy;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectBooleanKeysViewNullTest
{
    private static final HashingStrategy<String> STRING_STRATEGY = new HashingStrategy<>()
    {
        public int computeHashCode(String key) { return key == null ? 0 : 1; }
        public boolean equals(String left, String right) { return Objects.equals(left, right); }
    };

    private MutableObjectBooleanMap<String> strategyMap()
    {
        return new ObjectBooleanHashMapWithHashingStrategy<>(STRING_STRATEGY);
    }

    @Test public void ordinaryNullOnly() { checkNullOnly(ObjectBooleanHashMap::new); }
    @Test public void strategyNullOnly() { checkNullOnly(this::strategyMap); }
    @Test public void ordinaryMixedKeys() { checkMixed(ObjectBooleanHashMap::new); }
    @Test public void strategyMixedKeys() { checkMixed(this::strategyMap); }

    private void checkNullOnly(Supplier<MutableObjectBooleanMap<String>> factory)
    {
        MutableObjectBooleanMap<String> map = factory.get();
        map.put(null, false);
        Iterator<String> iterator = map.keysView().iterator();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertTrue(iterator.hasNext());
        String key = iterator.next();
        assertNull(key);
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals(1, map.size());
        assertTrue(map.containsKey(null));
        assertFalse(map.get(null));
    }

    private void checkMixed(Supplier<MutableObjectBooleanMap<String>> factory)
    {
        MutableObjectBooleanMap<String> map = factory.get();
        map.put(null, true);
        map.put("a", false);
        map.put("b", true);
        Set<String> expected = new HashSet<>(Arrays.asList(null, "a", "b"));
        Set<String> eachKeys = new HashSet<>();
        map.keysView().each(eachKeys::add);
        assertEquals(expected, eachKeys);
        assertEquals(expected, new HashSet<>(map.keySet()));
        Set<String> iteratorKeys = new HashSet<>();
        Iterator<String> iterator = map.keysView().iterator();
        int count = 0;
        while (iterator.hasNext())
        {
            String key = iterator.next();
            assertTrue(iteratorKeys.add(key), "iterator must emit each user key once");
            count++;
        }
        assertEquals(3, count);
        assertEquals(expected, iteratorKeys);
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals(3, map.size());
        assertTrue(map.containsKey(null));
    }
}

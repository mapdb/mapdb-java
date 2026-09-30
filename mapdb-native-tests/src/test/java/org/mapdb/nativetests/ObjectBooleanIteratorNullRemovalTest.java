package org.mapdb.nativetests;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.api.iterator.MutableBooleanIterator;
import org.mapdb.collections.api.map.primitive.MutableObjectBooleanMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectBooleanHashMapWithHashingStrategy;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectBooleanIteratorNullRemovalTest
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

    private MutableBooleanIterator iterator(MutableObjectBooleanMap<String> map, boolean valuesView)
    {
        return (MutableBooleanIterator) (valuesView ? map.values().booleanIterator() : map.booleanIterator());
    }

    private void checkNullOnly(Supplier<MutableObjectBooleanMap<String>> factory)
    {
        for (boolean value : new boolean[]{false, true})
        {
            for (boolean valuesView : new boolean[]{false, true})
            {
                MutableObjectBooleanMap<String> map = factory.get();
                map.put(null, value);
                MutableBooleanIterator iterator = iterator(map, valuesView);
                assertThrows(IllegalStateException.class, iterator::remove);
                assertTrue(iterator.hasNext());
                assertEquals(value, iterator.next());
                iterator.remove();
                assertEquals(0, map.size());
                assertFalse(map.containsKey(null));
                assertFalse(iterator.hasNext());
                assertThrows(IllegalStateException.class, iterator::remove);
                assertThrows(NoSuchElementException.class, iterator::next);
                map.put(null, !value);
                assertTrue(map.containsKey(null));
                assertEquals(!value, map.get(null));
            }
        }
    }

    private void checkMixed(Supplier<MutableObjectBooleanMap<String>> factory)
    {
        for (boolean valuesView : new boolean[]{false, true})
        {
            MutableObjectBooleanMap<String> map = factory.get();
            map.put(null, true);
            for (int i = 0; i < 32; i++) map.put("key" + i, (i & 1) == 0);
            MutableBooleanIterator iterator = iterator(map, valuesView);
            int seen = 0;
            int trueCount = 0;
            while (iterator.hasNext())
            {
                assertTrue(seen < 33, "iterator must terminate after every initial entry");
                if (iterator.next()) trueCount++;
                int oldSize = map.size();
                iterator.remove();
                assertEquals(oldSize - 1, map.size());
                seen++;
            }
            assertEquals(33, seen);
            assertEquals(17, trueCount);
            assertTrue(map.isEmpty());
            assertFalse(map.containsKey(null));
            for (int i = 0; i < 32; i++) assertFalse(map.containsKey("key" + i));
        }
    }
}

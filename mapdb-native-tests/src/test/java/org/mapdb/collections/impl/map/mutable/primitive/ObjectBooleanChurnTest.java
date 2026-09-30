package org.mapdb.collections.impl.map.mutable.primitive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapdb.collections.api.map.primitive.MutableObjectBooleanMap;
import org.mapdb.collections.api.block.HashingStrategy;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectBooleanChurnTest
{
    private static final HashingStrategy<Integer> HASHING = new HashingStrategy<>()
    {
        @Override public int computeHashCode(Integer key) { return key == null ? 0 : key.hashCode(); }
        @Override public boolean equals(Integer a, Integer b) { return java.util.Objects.equals(a, b); }
    };

    private static class MeasuredMap extends ObjectBooleanHashMap<Integer>
    {
        int capacity;
        int allocations;
        MeasuredMap() { super(1); }
        @Override protected void allocateTable(int size)
        {
            super.allocateTable(size);
            this.capacity = size;
            this.allocations++;
        }
    }

    private static class MeasuredStrategyMap extends ObjectBooleanHashMapWithHashingStrategy<Integer>
    {
        int capacity;
        int allocations;
        MeasuredStrategyMap(HashingStrategy<Integer> strategy) { super(strategy, 1); }
        @Override protected void allocateTable(int size)
        {
            super.allocateTable(size);
            this.capacity = size;
            this.allocations++;
        }
    }

    private static void exerciseDenseChurn(MutableObjectBooleanMap<Integer> map, int live)
    {
        map.put(null, false);
        for (int i = 0; i < live; i++) map.put(i, (i & 1) == 0);
        for (int i = 0; i < live * 8; i++)
        {
            map.removeKey(i);
            int inserted = i + live;
            map.put(inserted, (inserted & 1) == 0);
            assertFalse(map.containsKey(i));
            assertTrue(map.containsKey(inserted));
            assertEquals((inserted & 1) == 0, map.get(inserted));
            assertEquals(live + 1, map.size());
            assertTrue(map.containsKey(null));
            assertFalse(map.get(null));
        }
        for (int i = live * 8; i < live * 9; i++)
        {
            assertTrue(map.containsKey(i));
            assertEquals((i & 1) == 0, map.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 48, 63, 64, 65, 100})
    public void denseNormalChurnHasARebuildMargin(int live)
    {
        MeasuredMap map = new MeasuredMap();
        // Populate before measuring so ordinary initial growth is excluded.
        map.put(null, false);
        for (int i = 0; i < live; i++) map.put(i, (i & 1) == 0);
        int initialCapacity = map.capacity;
        int initialAllocations = map.allocations;
        exerciseDenseChurn(map, live);
        assertTrue(map.capacity <= initialCapacity * 2, "stable live size must have bounded capacity");
        assertTrue(map.allocations - initialAllocations <= 24,
                "dense churn must not rebuild per insertion: " + (map.allocations - initialAllocations));
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 48, 63, 64, 65, 100})
    public void denseStrategyChurnHasARebuildMargin(int live)
    {
        HashingStrategy<Integer> collisions = new HashingStrategy<>()
        {
            @Override public int computeHashCode(Integer key) { return 0; }
            @Override public boolean equals(Integer a, Integer b) { return java.util.Objects.equals(a, b); }
        };
        for (HashingStrategy<Integer> strategy : java.util.List.of(HASHING, collisions))
        {
            MeasuredStrategyMap map = new MeasuredStrategyMap(strategy);
            map.put(null, false);
            for (int i = 0; i < live; i++) map.put(i, (i & 1) == 0);
            int initialCapacity = map.capacity;
            int initialAllocations = map.allocations;
            exerciseDenseChurn(map, live);
            assertTrue(map.capacity <= initialCapacity * 2);
            assertTrue(map.allocations - initialAllocations <= 24,
                    "dense churn must not rebuild per insertion: " + (map.allocations - initialAllocations));
        }
    }

    @Test public void tombstoneOnlyChurnDoesNotGrowNormalMap()
    {
        MeasuredMap map = new MeasuredMap();
        int originalCapacity = map.capacity;
        for (int i = 0; i < 10000; i++)
        {
            map.put(i, (i & 1) == 0);
            assertTrue(map.containsKey(i));
            assertEquals((i & 1) == 0, map.get(i));
            map.removeKey(i);
            assertEquals(0, map.size());
            assertEquals(originalCapacity, map.capacity, "bounded live size must not grow the table");
        }
    }

    @Test public void tombstoneOnlyChurnDoesNotGrowStrategyMap()
    {
        MeasuredStrategyMap map = new MeasuredStrategyMap(HASHING);
        int originalCapacity = map.capacity;
        for (int i = 0; i < 10000; i++)
        {
            map.put(i, (i & 1) == 0);
            assertTrue(map.containsKey(i));
            assertEquals((i & 1) == 0, map.get(i));
            map.removeKey(i);
            assertEquals(0, map.size());
            assertEquals(originalCapacity, map.capacity, "bounded live size must not grow the table");
        }
    }

    private static void exerciseLiveGrowthAndNull(MutableObjectBooleanMap<Integer> map)
    {
        map.put(null, true);
        for (int i = 0; i < 200; i++) map.put(i, (i & 1) == 0);
        for (int i = 0; i < 200; i += 2) map.removeKey(i);
        for (int i = 200; i < 1000; i++)
        {
            map.put(i, false);
            map.removeKey(i);
        }
        assertEquals(101, map.size());
        assertTrue(map.containsKey(null));
        assertTrue(map.get(null));
        for (int i = 0; i < 200; i++)
        {
            assertEquals((i & 1) != 0, map.containsKey(i));
            if ((i & 1) != 0) assertFalse(map.get(i));
        }
        map.removeKey(null);
        assertFalse(map.containsKey(null));
        map.put(null, false);
        assertTrue(map.containsKey(null));
        assertFalse(map.get(null));
    }

    @Test public void realGrowthRetainsValuesAndNullSentinel()
    {
        MeasuredMap map = new MeasuredMap();
        exerciseLiveGrowthAndNull(map);
        assertEquals(512, map.capacity);
        MeasuredStrategyMap strategy = new MeasuredStrategyMap(HASHING);
        exerciseLiveGrowthAndNull(strategy);
        assertEquals(512, strategy.capacity);
    }

    @Test public void collisionStrategyRetainsRemainingFalseValues()
    {
        HashingStrategy<Integer> collisions = new HashingStrategy<>()
        {
            @Override public int computeHashCode(Integer key) { return 0; }
            @Override public boolean equals(Integer a, Integer b) { return java.util.Objects.equals(a, b); }
        };
        MeasuredStrategyMap map = new MeasuredStrategyMap(collisions);
        exerciseLiveGrowthAndNull(map);
        assertEquals(512, map.capacity);
    }
}

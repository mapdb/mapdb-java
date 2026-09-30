package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.impl.map.mutable.primitive.*;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectPrimitiveIteratorNullRemovalTest
{
    private static final HashingStrategy<String> STRING_STRATEGY = new HashingStrategy<>()
    {
        public int computeHashCode(String key) { return key == null ? 0 : 1; }
        public boolean equals(String left, String right) { return Objects.equals(left, right); }
    };

    private record Access(BooleanSupplier hasNext, Runnable next, Runnable remove,
                          IntSupplier size, BooleanSupplier containsNull, Runnable reuse) { }

    static Stream<Arguments> maps()
    {
        return Stream.of(
            Arguments.of("ObjectByteHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectByteHashMap<String>();
                for (String key : keys) map.put(key, (byte) 1);
                var iterator = view ? map.values().byteIterator() : map.byteIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, (byte) 1));
            }),
            Arguments.of("ObjectByteHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectByteHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, (byte) 1);
                var iterator = view ? map.values().byteIterator() : map.byteIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, (byte) 1));
            }),
            Arguments.of("ObjectCharHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectCharHashMap<String>();
                for (String key : keys) map.put(key, 'x');
                var iterator = view ? map.values().charIterator() : map.charIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 'x'));
            }),
            Arguments.of("ObjectCharHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectCharHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 'x');
                var iterator = view ? map.values().charIterator() : map.charIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 'x'));
            }),
            Arguments.of("ObjectDoubleHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectDoubleHashMap<String>();
                for (String key : keys) map.put(key, 1.0);
                var iterator = view ? map.values().doubleIterator() : map.doubleIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1.0));
            }),
            Arguments.of("ObjectDoubleHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectDoubleHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1.0);
                var iterator = view ? map.values().doubleIterator() : map.doubleIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1.0));
            }),
            Arguments.of("ObjectFloatHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectFloatHashMap<String>();
                for (String key : keys) map.put(key, 1.0f);
                var iterator = view ? map.values().floatIterator() : map.floatIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1.0f));
            }),
            Arguments.of("ObjectFloatHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectFloatHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1.0f);
                var iterator = view ? map.values().floatIterator() : map.floatIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1.0f));
            }),
            Arguments.of("ObjectIntHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectIntHashMap<String>();
                for (String key : keys) map.put(key, 1);
                var iterator = view ? map.values().intIterator() : map.intIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1));
            }),
            Arguments.of("ObjectIntHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectIntHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1);
                var iterator = view ? map.values().intIterator() : map.intIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1));
            }),
            Arguments.of("ObjectLongHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectLongHashMap<String>();
                for (String key : keys) map.put(key, 1L);
                var iterator = view ? map.values().longIterator() : map.longIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1L));
            }),
            Arguments.of("ObjectLongHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectLongHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1L);
                var iterator = view ? map.values().longIterator() : map.longIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, 1L));
            }),
            Arguments.of("ObjectShortHashMap", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectShortHashMap<String>();
                for (String key : keys) map.put(key, (short) 1);
                var iterator = view ? map.values().shortIterator() : map.shortIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, (short) 1));
            }),
            Arguments.of("ObjectShortHashMapWithHashingStrategy", (BiFunction<List<String>, Boolean, Access>) (keys, view) -> {
                var map = new ObjectShortHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, (short) 1);
                var iterator = view ? map.values().shortIterator() : map.shortIterator();
                return new Access(iterator::hasNext, iterator::next, iterator::remove,
                        map::size, () -> map.containsKey(null), () -> map.put(null, (short) 1));
            })
        );
    }

    @ParameterizedTest(name = "{0} null only")
    @MethodSource("maps")
    void nullOnly(String name, BiFunction<List<String>, Boolean, Access> factory)
    {
        for (boolean valuesView : new boolean[]{false, true})
        {
            Access a = factory.apply(Arrays.asList((String) null), valuesView);
            assertThrows(IllegalStateException.class, a.remove()::run);
            assertTrue(a.hasNext().getAsBoolean(), name);
            a.next().run();
            a.remove().run();
            assertEquals(0, a.size().getAsInt(), name);
            assertFalse(a.containsNull().getAsBoolean(), name);
            assertFalse(a.hasNext().getAsBoolean(), name);
            assertThrows(IllegalStateException.class, a.remove()::run);
            assertThrows(NoSuchElementException.class, a.next()::run);
            a.reuse().run();
            assertEquals(1, a.size().getAsInt(), name);
            assertTrue(a.containsNull().getAsBoolean(), name);
        }
    }

    @ParameterizedTest(name = "{0} mixed keys")
    @MethodSource("maps")
    void mixedKeys(String name, BiFunction<List<String>, Boolean, Access> factory)
    {
        var keys = new java.util.ArrayList<String>();
        keys.add(null);
        for (int i = 0; i < 32; i++) keys.add("key" + i);
        for (boolean valuesView : new boolean[]{false, true})
        {
            Access a = factory.apply(keys, valuesView);
            int seen = 0;
            while (a.hasNext().getAsBoolean())
            {
                assertTrue(seen < 33, name);
                a.next().run();
                int oldSize = a.size().getAsInt();
                a.remove().run();
                assertEquals(oldSize - 1, a.size().getAsInt(), name);
                seen++;
            }
            assertEquals(33, seen, name);
            assertEquals(0, a.size().getAsInt(), name);
            assertFalse(a.containsNull().getAsBoolean(), name);
        }
    }
}

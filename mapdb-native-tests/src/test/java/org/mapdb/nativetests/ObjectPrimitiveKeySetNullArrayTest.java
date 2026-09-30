package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.impl.map.mutable.primitive.*;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectPrimitiveKeySetNullArrayTest
{
    private static final HashingStrategy<String> STRING_STRATEGY = new HashingStrategy<>()
    {
        public int computeHashCode(String key) { return key == null ? 0 : 1; }
        public boolean equals(String left, String right) { return Objects.equals(left, right); }
    };

    static Stream<Arguments> maps()
    {
        return Stream.of(
            Arguments.of("ObjectBooleanHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectBooleanHashMap<String>();
                for (String key : keys) map.put(key, true);
                return map.keySet();
            }),
            Arguments.of("ObjectBooleanHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectBooleanHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, true);
                return map.keySet();
            }),
            Arguments.of("ObjectByteHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectByteHashMap<String>();
                for (String key : keys) map.put(key, (byte) 1);
                return map.keySet();
            }),
            Arguments.of("ObjectByteHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectByteHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, (byte) 1);
                return map.keySet();
            }),
            Arguments.of("ObjectCharHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectCharHashMap<String>();
                for (String key : keys) map.put(key, 'x');
                return map.keySet();
            }),
            Arguments.of("ObjectCharHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectCharHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 'x');
                return map.keySet();
            }),
            Arguments.of("ObjectDoubleHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectDoubleHashMap<String>();
                for (String key : keys) map.put(key, 1.0);
                return map.keySet();
            }),
            Arguments.of("ObjectDoubleHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectDoubleHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1.0);
                return map.keySet();
            }),
            Arguments.of("ObjectFloatHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectFloatHashMap<String>();
                for (String key : keys) map.put(key, 1.0f);
                return map.keySet();
            }),
            Arguments.of("ObjectFloatHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectFloatHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1.0f);
                return map.keySet();
            }),
            Arguments.of("ObjectIntHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectIntHashMap<String>();
                for (String key : keys) map.put(key, 1);
                return map.keySet();
            }),
            Arguments.of("ObjectIntHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectIntHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1);
                return map.keySet();
            }),
            Arguments.of("ObjectLongHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectLongHashMap<String>();
                for (String key : keys) map.put(key, 1L);
                return map.keySet();
            }),
            Arguments.of("ObjectLongHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectLongHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, 1L);
                return map.keySet();
            }),
            Arguments.of("ObjectShortHashMap", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectShortHashMap<String>();
                for (String key : keys) map.put(key, (short) 1);
                return map.keySet();
            }),
            Arguments.of("ObjectShortHashMapWithHashingStrategy", (Function<List<String>, Set<String>>) keys -> {
                var map = new ObjectShortHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                for (String key : keys) map.put(key, (short) 1);
                return map.keySet();
            })
        );
    }

    private List<List<String>> inputs()
    {
        return Arrays.asList(List.of(), Arrays.asList((String) null),
                Arrays.asList(null, "a", "b"), List.of("a", "b"));
    }

    @ParameterizedTest(name = "{0} untyped array")
    @MethodSource("maps")
    void untypedArrays(String name, Function<List<String>, Set<String>> factory)
    {
        for (List<String> input : inputs())
        {
            Set<String> keys = factory.apply(input);
            Object[] array = keys.toArray();
            assertEquals(input.size(), array.length, name);
            if (input.stream().anyMatch(Objects::isNull)) assertTrue(Arrays.asList(array).contains(null), name);
            assertEquals(new HashSet<>(input), new HashSet<>(Arrays.asList(array)), name);
            assertEquals(new HashSet<>(input), new HashSet<>(keys), name);
        }
    }

    @ParameterizedTest(name = "{0} typed array")
    @MethodSource("maps")
    void typedArrays(String name, Function<List<String>, Set<String>> factory)
    {
        for (List<String> input : inputs())
        {
            Set<String> keys = factory.apply(input);
            for (int capacity : new int[]{0, input.size(), input.size() + 2})
            {
                String[] buffer = new String[capacity];
                Arrays.fill(buffer, "untouched");
                String[] result = keys.toArray(buffer);
                assertEquals(Math.max(capacity, input.size()), result.length, name);
                if (capacity >= input.size()) assertSame(buffer, result, name);
                else assertNotSame(buffer, result, name);
                assertEquals(new HashSet<>(input),
                        new HashSet<>(Arrays.asList(Arrays.copyOf(result, input.size()))), name);
                if (capacity > input.size())
                {
                    assertNull(result[input.size()], name);
                    assertEquals("untouched", result[input.size() + 1], name);
                }
                assertEquals(new HashSet<>(input), new HashSet<>(keys), name);
            }
        }
    }
}

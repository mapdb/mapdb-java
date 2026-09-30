package org.mapdb.nativetests;

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

public class ObjectPrimitiveValuesNullRemovalTest
{
    private static final HashingStrategy<String> STRING_STRATEGY = new HashingStrategy<>()
    {
        public int computeHashCode(String key) { return key == null ? 0 : 1; }
        public boolean equals(String left, String right) { return Objects.equals(left, right); }
    };
    private record Access(BooleanSupplier remove, IntSupplier size,
                          BooleanSupplier containsNull, BooleanSupplier keptOther) { }
    static Stream<Arguments> maps()
    {
        return Stream.of(
            Arguments.of("ObjectByteHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectByteHashMap<String>();
                map.put(null, (byte) 1);
                if (mixed) map.put("keep", (byte) 2);
                return new Access(() -> bulk ? map.values().removeAll((byte) 1) : map.values().remove((byte) 1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == (byte) 2);
            }),
            Arguments.of("ObjectByteHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectByteHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, (byte) 1);
                if (mixed) map.put("keep", (byte) 2);
                return new Access(() -> bulk ? map.values().removeAll((byte) 1) : map.values().remove((byte) 1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == (byte) 2);
            }),
            Arguments.of("ObjectCharHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectCharHashMap<String>();
                map.put(null, 'x');
                if (mixed) map.put("keep", 'y');
                return new Access(() -> bulk ? map.values().removeAll('x') : map.values().remove('x'),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 'y');
            }),
            Arguments.of("ObjectCharHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectCharHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, 'x');
                if (mixed) map.put("keep", 'y');
                return new Access(() -> bulk ? map.values().removeAll('x') : map.values().remove('x'),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 'y');
            }),
            Arguments.of("ObjectDoubleHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectDoubleHashMap<String>();
                map.put(null, 1.0);
                if (mixed) map.put("keep", 2.0);
                return new Access(() -> bulk ? map.values().removeAll(1.0) : map.values().remove(1.0),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2.0);
            }),
            Arguments.of("ObjectDoubleHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectDoubleHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, 1.0);
                if (mixed) map.put("keep", 2.0);
                return new Access(() -> bulk ? map.values().removeAll(1.0) : map.values().remove(1.0),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2.0);
            }),
            Arguments.of("ObjectFloatHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectFloatHashMap<String>();
                map.put(null, 1.0f);
                if (mixed) map.put("keep", 2.0f);
                return new Access(() -> bulk ? map.values().removeAll(1.0f) : map.values().remove(1.0f),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2.0f);
            }),
            Arguments.of("ObjectFloatHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectFloatHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, 1.0f);
                if (mixed) map.put("keep", 2.0f);
                return new Access(() -> bulk ? map.values().removeAll(1.0f) : map.values().remove(1.0f),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2.0f);
            }),
            Arguments.of("ObjectIntHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectIntHashMap<String>();
                map.put(null, 1);
                if (mixed) map.put("keep", 2);
                return new Access(() -> bulk ? map.values().removeAll(1) : map.values().remove(1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2);
            }),
            Arguments.of("ObjectIntHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectIntHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, 1);
                if (mixed) map.put("keep", 2);
                return new Access(() -> bulk ? map.values().removeAll(1) : map.values().remove(1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2);
            }),
            Arguments.of("ObjectLongHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectLongHashMap<String>();
                map.put(null, 1L);
                if (mixed) map.put("keep", 2L);
                return new Access(() -> bulk ? map.values().removeAll(1L) : map.values().remove(1L),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2L);
            }),
            Arguments.of("ObjectLongHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectLongHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, 1L);
                if (mixed) map.put("keep", 2L);
                return new Access(() -> bulk ? map.values().removeAll(1L) : map.values().remove(1L),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == 2L);
            }),
            Arguments.of("ObjectShortHashMap", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectShortHashMap<String>();
                map.put(null, (short) 1);
                if (mixed) map.put("keep", (short) 2);
                return new Access(() -> bulk ? map.values().removeAll((short) 1) : map.values().remove((short) 1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == (short) 2);
            }),
            Arguments.of("ObjectShortHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (mixed, bulk) -> {
                var map = new ObjectShortHashMapWithHashingStrategy<String>(STRING_STRATEGY);
                map.put(null, (short) 1);
                if (mixed) map.put("keep", (short) 2);
                return new Access(() -> bulk ? map.values().removeAll((short) 1) : map.values().remove((short) 1),
                        map::size, () -> map.containsKey(null),
                        () -> map.containsKey("keep") && map.get("keep") == (short) 2);
            })
        );
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void removesMatchingNull(String name, BiFunction<Boolean, Boolean, Access> factory)
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            for (boolean bulk : new boolean[]{false, true})
            {
                Access a = factory.apply(mixed, bulk);
                assertTrue(a.remove().getAsBoolean(), name);
                assertEquals(mixed ? 1 : 0, a.size().getAsInt(), name);
                assertFalse(a.containsNull().getAsBoolean(), name);
                assertEquals(mixed, a.keptOther().getAsBoolean(), name);
                assertFalse(a.remove().getAsBoolean(), name);
                assertEquals(mixed ? 1 : 0, a.size().getAsInt(), name);
                assertEquals(mixed, a.keptOther().getAsBoolean(), name);
            }
        }
    }
}

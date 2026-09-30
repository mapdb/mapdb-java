package org.mapdb.nativetests;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.impl.map.mutable.primitive.*;
import static org.junit.jupiter.api.Assertions.*;

public class ObjectPrimitiveFlipNullDuplicateTest
{
    private static final class Key {
        public int hashCode() { return 0; }
    }
    private static final Key OTHER = new Key();
    private static final Key FIRST = new Key();
    private static final HashingStrategy<Key> STRATEGY = new HashingStrategy<>() {
        public int computeHashCode(Key key) { return 0; }
        public boolean equals(Key a, Key b) { return Objects.equals(a, b); }
    };
    private record Access(Runnable flip, BooleanSupplier uniqueResult, IntSupplier size,
                          BooleanSupplier sourceRetained) { }
    static Stream<Arguments> maps() {
        return Stream.of(
            Arguments.of("ObjectByteHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectByteHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, (byte) 1);
                map.put(OTHER, duplicate ? (byte) 1 : (byte) 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey((byte) 1)
                            && flipped.get((byte) 1) == first && flipped.get((byte) 2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == (byte) 1 && map.get(OTHER) == (duplicate ? (byte) 1 : (byte) 2));
            }),
            Arguments.of("ObjectByteHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectByteHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, (byte) 1);
                map.put(OTHER, duplicate ? (byte) 1 : (byte) 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey((byte) 1)
                            && flipped.get((byte) 1) == first && flipped.get((byte) 2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == (byte) 1 && map.get(OTHER) == (duplicate ? (byte) 1 : (byte) 2));
            }),
            Arguments.of("ObjectCharHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectCharHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, 'x');
                map.put(OTHER, duplicate ? 'x' : 'y');
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey('x')
                            && flipped.get('x') == first && flipped.get('y') == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 'x' && map.get(OTHER) == (duplicate ? 'x' : 'y'));
            }),
            Arguments.of("ObjectCharHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectCharHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, 'x');
                map.put(OTHER, duplicate ? 'x' : 'y');
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey('x')
                            && flipped.get('x') == first && flipped.get('y') == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 'x' && map.get(OTHER) == (duplicate ? 'x' : 'y'));
            }),
            Arguments.of("ObjectDoubleHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectDoubleHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1.0);
                map.put(OTHER, duplicate ? 1.0 : 2.0);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1.0)
                            && flipped.get(1.0) == first && flipped.get(2.0) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1.0 && map.get(OTHER) == (duplicate ? 1.0 : 2.0));
            }),
            Arguments.of("ObjectDoubleHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectDoubleHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1.0);
                map.put(OTHER, duplicate ? 1.0 : 2.0);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1.0)
                            && flipped.get(1.0) == first && flipped.get(2.0) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1.0 && map.get(OTHER) == (duplicate ? 1.0 : 2.0));
            }),
            Arguments.of("ObjectFloatHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectFloatHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1.0f);
                map.put(OTHER, duplicate ? 1.0f : 2.0f);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1.0f)
                            && flipped.get(1.0f) == first && flipped.get(2.0f) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1.0f && map.get(OTHER) == (duplicate ? 1.0f : 2.0f));
            }),
            Arguments.of("ObjectFloatHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectFloatHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1.0f);
                map.put(OTHER, duplicate ? 1.0f : 2.0f);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1.0f)
                            && flipped.get(1.0f) == first && flipped.get(2.0f) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1.0f && map.get(OTHER) == (duplicate ? 1.0f : 2.0f));
            }),
            Arguments.of("ObjectIntHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectIntHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1);
                map.put(OTHER, duplicate ? 1 : 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1)
                            && flipped.get(1) == first && flipped.get(2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1 && map.get(OTHER) == (duplicate ? 1 : 2));
            }),
            Arguments.of("ObjectIntHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectIntHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1);
                map.put(OTHER, duplicate ? 1 : 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1)
                            && flipped.get(1) == first && flipped.get(2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1 && map.get(OTHER) == (duplicate ? 1 : 2));
            }),
            Arguments.of("ObjectLongHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectLongHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1L);
                map.put(OTHER, duplicate ? 1L : 2L);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1L)
                            && flipped.get(1L) == first && flipped.get(2L) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1L && map.get(OTHER) == (duplicate ? 1L : 2L));
            }),
            Arguments.of("ObjectLongHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectLongHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, 1L);
                map.put(OTHER, duplicate ? 1L : 2L);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey(1L)
                            && flipped.get(1L) == first && flipped.get(2L) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == 1L && map.get(OTHER) == (duplicate ? 1L : 2L));
            }),
            Arguments.of("ObjectShortHashMap", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectShortHashMap<Key>();
                Key first = nullFirst ? null : FIRST;
                map.put(first, (short) 1);
                map.put(OTHER, duplicate ? (short) 1 : (short) 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey((short) 1)
                            && flipped.get((short) 1) == first && flipped.get((short) 2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == (short) 1 && map.get(OTHER) == (duplicate ? (short) 1 : (short) 2));
            }),
            Arguments.of("ObjectShortHashMapWithHashingStrategy", (BiFunction<Boolean, Boolean, Access>) (nullFirst, duplicate) -> {
                var map = new ObjectShortHashMapWithHashingStrategy<Key>(STRATEGY);
                Key first = nullFirst ? null : FIRST;
                map.put(first, (short) 1);
                map.put(OTHER, duplicate ? (short) 1 : (short) 2);
                // Both keys hash to zero; first inserted key is visited first.
                assertSame(first, map.keySet().iterator().next());
                return new Access(map::flipUniqueValues, () -> {
                    var flipped = map.flipUniqueValues();
                    return flipped.size() == 2 && flipped.containsKey((short) 1)
                            && flipped.get((short) 1) == first && flipped.get((short) 2) == OTHER;
                }, map::size, () -> map.containsKey(first) && map.containsKey(OTHER)
                        && map.get(first) == (short) 1 && map.get(OTHER) == (duplicate ? (short) 1 : (short) 2));
            }));
    }
    @ParameterizedTest(name="{0}: duplicate after null key")
    @MethodSource("maps")
    void rejectsDuplicateAfterNullKey(String name, BiFunction<Boolean, Boolean, Access> factory) {
        Access a = factory.apply(true, true);
        assertThrows(IllegalStateException.class, a.flip()::run);
        assertEquals(2, a.size().getAsInt());
        assertTrue(a.sourceRetained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: duplicate nonnull control")
    @MethodSource("maps")
    void rejectsDuplicateNonNullKeys(String name, BiFunction<Boolean, Boolean, Access> factory) {
        Access a = factory.apply(false, true);
        assertThrows(IllegalStateException.class, a.flip()::run);
        assertTrue(a.sourceRetained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: unique null value in flipped map")
    @MethodSource("maps")
    void retainsUniqueNullMapping(String name, BiFunction<Boolean, Boolean, Access> factory) {
        Access a = factory.apply(true, false);
        assertTrue(a.uniqueResult().getAsBoolean());
        assertEquals(2, a.size().getAsInt());
        assertTrue(a.sourceRetained().getAsBoolean());
    }
}

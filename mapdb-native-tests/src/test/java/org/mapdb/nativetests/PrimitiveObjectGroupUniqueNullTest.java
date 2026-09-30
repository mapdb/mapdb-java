package org.mapdb.nativetests;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.map.mutable.primitive.*;
import static org.junit.jupiter.api.Assertions.*;

public class PrimitiveObjectGroupUniqueNullTest {
    private record Access(RichIterable<String> source, BooleanSupplier retained) { }
    private record Factory(Function<String, Access> single, Supplier<Access> collision) { }
    static Stream<Arguments> maps() {
        return Stream.of(
            Arguments.of("Byte branch0", new Factory(value -> {
                var map = new ByteObjectHashMap<String>(); map.put((byte) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((byte) 0)
                        && Objects.equals(value, map.get((byte) 0)));
            }, () -> {
                var map = new ByteObjectHashMap<String>();
                map.put((byte) 0, null); map.put((byte) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((byte) 0)
                        && map.get((byte) 0) == null && "hit".equals(map.get((byte) 1)));
            })),
            Arguments.of("Byte branch1", new Factory(value -> {
                var map = new ByteObjectHashMap<String>(); map.put((byte) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((byte) 1)
                        && Objects.equals(value, map.get((byte) 1)));
            }, () -> {
                var map = new ByteObjectHashMap<String>();
                map.put((byte) 0, null); map.put((byte) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((byte) 0)
                        && map.get((byte) 0) == null && "hit".equals(map.get((byte) 1)));
            })),
            Arguments.of("Byte branch2", new Factory(value -> {
                var map = new ByteObjectHashMap<String>(); map.put((byte) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((byte) 2)
                        && Objects.equals(value, map.get((byte) 2)));
            }, () -> {
                var map = new ByteObjectHashMap<String>();
                map.put((byte) 0, null); map.put((byte) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((byte) 0)
                        && map.get((byte) 0) == null && "hit".equals(map.get((byte) 2)));
            })),
            Arguments.of("Char branch0", new Factory(value -> {
                var map = new CharObjectHashMap<String>(); map.put((char) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((char) 0)
                        && Objects.equals(value, map.get((char) 0)));
            }, () -> {
                var map = new CharObjectHashMap<String>();
                map.put((char) 0, null); map.put((char) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((char) 0)
                        && map.get((char) 0) == null && "hit".equals(map.get((char) 1)));
            })),
            Arguments.of("Char branch1", new Factory(value -> {
                var map = new CharObjectHashMap<String>(); map.put((char) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((char) 1)
                        && Objects.equals(value, map.get((char) 1)));
            }, () -> {
                var map = new CharObjectHashMap<String>();
                map.put((char) 0, null); map.put((char) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((char) 0)
                        && map.get((char) 0) == null && "hit".equals(map.get((char) 1)));
            })),
            Arguments.of("Char branch2", new Factory(value -> {
                var map = new CharObjectHashMap<String>(); map.put((char) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((char) 2)
                        && Objects.equals(value, map.get((char) 2)));
            }, () -> {
                var map = new CharObjectHashMap<String>();
                map.put((char) 0, null); map.put((char) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((char) 0)
                        && map.get((char) 0) == null && "hit".equals(map.get((char) 2)));
            })),
            Arguments.of("Double branch0", new Factory(value -> {
                var map = new DoubleObjectHashMap<String>(); map.put((double) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((double) 0)
                        && Objects.equals(value, map.get((double) 0)));
            }, () -> {
                var map = new DoubleObjectHashMap<String>();
                map.put((double) 0, null); map.put((double) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((double) 0)
                        && map.get((double) 0) == null && "hit".equals(map.get((double) 1)));
            })),
            Arguments.of("Double branch1", new Factory(value -> {
                var map = new DoubleObjectHashMap<String>(); map.put((double) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((double) 1)
                        && Objects.equals(value, map.get((double) 1)));
            }, () -> {
                var map = new DoubleObjectHashMap<String>();
                map.put((double) 0, null); map.put((double) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((double) 0)
                        && map.get((double) 0) == null && "hit".equals(map.get((double) 1)));
            })),
            Arguments.of("Double branch2", new Factory(value -> {
                var map = new DoubleObjectHashMap<String>(); map.put((double) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((double) 2)
                        && Objects.equals(value, map.get((double) 2)));
            }, () -> {
                var map = new DoubleObjectHashMap<String>();
                map.put((double) 0, null); map.put((double) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((double) 0)
                        && map.get((double) 0) == null && "hit".equals(map.get((double) 2)));
            })),
            Arguments.of("Float branch0", new Factory(value -> {
                var map = new FloatObjectHashMap<String>(); map.put((float) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((float) 0)
                        && Objects.equals(value, map.get((float) 0)));
            }, () -> {
                var map = new FloatObjectHashMap<String>();
                map.put((float) 0, null); map.put((float) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((float) 0)
                        && map.get((float) 0) == null && "hit".equals(map.get((float) 1)));
            })),
            Arguments.of("Float branch1", new Factory(value -> {
                var map = new FloatObjectHashMap<String>(); map.put((float) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((float) 1)
                        && Objects.equals(value, map.get((float) 1)));
            }, () -> {
                var map = new FloatObjectHashMap<String>();
                map.put((float) 0, null); map.put((float) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((float) 0)
                        && map.get((float) 0) == null && "hit".equals(map.get((float) 1)));
            })),
            Arguments.of("Float branch2", new Factory(value -> {
                var map = new FloatObjectHashMap<String>(); map.put((float) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((float) 2)
                        && Objects.equals(value, map.get((float) 2)));
            }, () -> {
                var map = new FloatObjectHashMap<String>();
                map.put((float) 0, null); map.put((float) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((float) 0)
                        && map.get((float) 0) == null && "hit".equals(map.get((float) 2)));
            })),
            Arguments.of("Int branch0", new Factory(value -> {
                var map = new IntObjectHashMap<String>(); map.put(0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey(0)
                        && Objects.equals(value, map.get(0)));
            }, () -> {
                var map = new IntObjectHashMap<String>();
                map.put(0, null); map.put(1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey(0)
                        && map.get(0) == null && "hit".equals(map.get(1)));
            })),
            Arguments.of("Int branch1", new Factory(value -> {
                var map = new IntObjectHashMap<String>(); map.put(1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey(1)
                        && Objects.equals(value, map.get(1)));
            }, () -> {
                var map = new IntObjectHashMap<String>();
                map.put(0, null); map.put(1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey(0)
                        && map.get(0) == null && "hit".equals(map.get(1)));
            })),
            Arguments.of("Int branch2", new Factory(value -> {
                var map = new IntObjectHashMap<String>(); map.put(2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey(2)
                        && Objects.equals(value, map.get(2)));
            }, () -> {
                var map = new IntObjectHashMap<String>();
                map.put(0, null); map.put(2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey(0)
                        && map.get(0) == null && "hit".equals(map.get(2)));
            })),
            Arguments.of("Long branch0", new Factory(value -> {
                var map = new LongObjectHashMap<String>(); map.put((long) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((long) 0)
                        && Objects.equals(value, map.get((long) 0)));
            }, () -> {
                var map = new LongObjectHashMap<String>();
                map.put((long) 0, null); map.put((long) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((long) 0)
                        && map.get((long) 0) == null && "hit".equals(map.get((long) 1)));
            })),
            Arguments.of("Long branch1", new Factory(value -> {
                var map = new LongObjectHashMap<String>(); map.put((long) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((long) 1)
                        && Objects.equals(value, map.get((long) 1)));
            }, () -> {
                var map = new LongObjectHashMap<String>();
                map.put((long) 0, null); map.put((long) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((long) 0)
                        && map.get((long) 0) == null && "hit".equals(map.get((long) 1)));
            })),
            Arguments.of("Long branch2", new Factory(value -> {
                var map = new LongObjectHashMap<String>(); map.put((long) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((long) 2)
                        && Objects.equals(value, map.get((long) 2)));
            }, () -> {
                var map = new LongObjectHashMap<String>();
                map.put((long) 0, null); map.put((long) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((long) 0)
                        && map.get((long) 0) == null && "hit".equals(map.get((long) 2)));
            })),
            Arguments.of("Short branch0", new Factory(value -> {
                var map = new ShortObjectHashMap<String>(); map.put((short) 0, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((short) 0)
                        && Objects.equals(value, map.get((short) 0)));
            }, () -> {
                var map = new ShortObjectHashMap<String>();
                map.put((short) 0, null); map.put((short) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((short) 0)
                        && map.get((short) 0) == null && "hit".equals(map.get((short) 1)));
            })),
            Arguments.of("Short branch1", new Factory(value -> {
                var map = new ShortObjectHashMap<String>(); map.put((short) 1, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((short) 1)
                        && Objects.equals(value, map.get((short) 1)));
            }, () -> {
                var map = new ShortObjectHashMap<String>();
                map.put((short) 0, null); map.put((short) 1, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((short) 0)
                        && map.get((short) 0) == null && "hit".equals(map.get((short) 1)));
            })),
            Arguments.of("Short branch2", new Factory(value -> {
                var map = new ShortObjectHashMap<String>(); map.put((short) 2, value);
                return new Access(map, () -> map.size() == 1 && map.containsKey((short) 2)
                        && Objects.equals(value, map.get((short) 2)));
            }, () -> {
                var map = new ShortObjectHashMap<String>();
                map.put((short) 0, null); map.put((short) 2, "hit");
                return new Access(map, () -> map.size() == 2 && map.containsKey((short) 0)
                        && map.get((short) 0) == null && "hit".equals(map.get((short) 2)));
            })));
    }
    @ParameterizedTest(name="{0}: null target duplicate") @MethodSource("maps")
    void rejectsExistingNullTarget(String name, Factory factory) { checkTarget(factory, null, "hit"); }
    @ParameterizedTest(name="{0}: nonnull target duplicate") @MethodSource("maps")
    void retainsNonNullTargetRejection(String name, Factory factory) { checkTarget(factory, "old", "hit"); }
    @ParameterizedTest(name="{0}: null source and target duplicate") @MethodSource("maps")
    void rejectsExistingNullWithNullSource(String name, Factory factory) { checkTarget(factory, null, null); }
    private static void checkTarget(Factory factory, String prior, String value) {
        Access a = factory.single().apply(value);
        var target = UnifiedMap.<String, String>newMap(); target.put("same", prior);
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> a.source().groupByUniqueKey(v -> {
            calls.incrementAndGet(); return "same";
        }, target));
        assertEquals("Key same already exists in map!", error.getMessage());
        assertEquals(2, calls.get()); assertTrue(target.containsKey("same"));
        assertEquals(value, target.get("same")); // Existing supplied-target overwrite is preserved.
        assertEquals(1, target.size()); assertTrue(a.retained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: unique null source") @MethodSource("maps")
    void retainsUniqueNullValue(String name, Factory factory) {
        Access a = factory.single().apply(null); var calls = new AtomicInteger();
        var result = a.source().groupByUniqueKey(v -> { calls.incrementAndGet(); return "same"; });
        assertEquals(1, result.size()); assertTrue(result.containsKey("same"));
        assertNull(result.get("same")); assertEquals(1, calls.get()); assertTrue(a.retained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: unrelated target null") @MethodSource("maps")
    void retainsUnrelatedTargetNull(String name, Factory factory) {
        Access a = factory.single().apply("hit"); var calls = new AtomicInteger();
        var target = UnifiedMap.<String, String>newMap(); target.put("other", null);
        assertSame(target, a.source().groupByUniqueKey(v -> { calls.incrementAndGet(); return "same"; }, target));
        assertEquals(2, target.size()); assertTrue(target.containsKey("other")); assertNull(target.get("other"));
        assertEquals("hit", target.get("same")); assertEquals(1, calls.get()); assertTrue(a.retained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: no-target null-first duplicate") @MethodSource("maps")
    void rejectsNullFirstCollision(String name, Factory factory) {
        Access a = factory.collision().get(); var calls = new AtomicInteger();
        assertNull(a.source().getFirst());
        assertThrows(IllegalStateException.class, () -> a.source().groupByUniqueKey(v -> {
            calls.incrementAndGet(); return "same";
        }));
        assertEquals(3, calls.get()); assertTrue(a.retained().getAsBoolean());
    }
    @ParameterizedTest(name="{0}: distinct null-first keys") @MethodSource("maps")
    void retainsDistinctGeneratedKeys(String name, Factory factory) {
        Access a = factory.collision().get(); var calls = new AtomicInteger();
        var result = a.source().groupByUniqueKey(v -> { calls.incrementAndGet(); return v == null ? "null" : "hit"; });
        assertEquals(2, result.size()); assertTrue(result.containsKey("null")); assertNull(result.get("null"));
        assertEquals("hit", result.get("hit")); assertEquals(2, calls.get()); assertTrue(a.retained().getAsBoolean());
    }
}

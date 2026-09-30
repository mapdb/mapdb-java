package org.mapdb.nativetests;

import java.util.function.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.impl.map.mutable.primitive.*;
import static org.junit.jupiter.api.Assertions.*;

public class PrimitiveObjectValuesNullScanTest {
    private record Access(Predicate<Object> remove, IntSupplier size, IntSupplier nullCount,
                          IntSupplier hitCount, Runnable duplicate, Runnable reuse) { }
    static Stream<Arguments> maps() {
        return Stream.of(
            Arguments.of("Byte sentinel null", (Supplier<Access>) () -> {
                var map = new ByteObjectHashMap<String>();
                map.put((byte) 0, null);
                map.put((byte) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((byte) 4, "hit"),
                        () -> map.put((byte) 5, "hit"));
            }),
            Arguments.of("Byte ordinary null", (Supplier<Access>) () -> {
                var map = new ByteObjectHashMap<String>();
                map.put((byte) 2, "hit");
                map.put((byte) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((byte) 4, "hit"),
                        () -> map.put((byte) 5, "hit"));
            }),
            Arguments.of("Char sentinel null", (Supplier<Access>) () -> {
                var map = new CharObjectHashMap<String>();
                map.put((char) 0, null);
                map.put((char) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((char) 4, "hit"),
                        () -> map.put((char) 5, "hit"));
            }),
            Arguments.of("Char ordinary null", (Supplier<Access>) () -> {
                var map = new CharObjectHashMap<String>();
                map.put((char) 2, "hit");
                map.put((char) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((char) 4, "hit"),
                        () -> map.put((char) 5, "hit"));
            }),
            Arguments.of("Double sentinel null", (Supplier<Access>) () -> {
                var map = new DoubleObjectHashMap<String>();
                map.put((double) 0, null);
                map.put((double) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((double) 4, "hit"),
                        () -> map.put((double) 5, "hit"));
            }),
            Arguments.of("Double ordinary null", (Supplier<Access>) () -> {
                var map = new DoubleObjectHashMap<String>();
                map.put((double) 2, "hit");
                map.put((double) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((double) 4, "hit"),
                        () -> map.put((double) 5, "hit"));
            }),
            Arguments.of("Float sentinel null", (Supplier<Access>) () -> {
                var map = new FloatObjectHashMap<String>();
                map.put((float) 0, null);
                map.put((float) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((float) 4, "hit"),
                        () -> map.put((float) 5, "hit"));
            }),
            Arguments.of("Float ordinary null", (Supplier<Access>) () -> {
                var map = new FloatObjectHashMap<String>();
                map.put((float) 2, "hit");
                map.put((float) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((float) 4, "hit"),
                        () -> map.put((float) 5, "hit"));
            }),
            Arguments.of("Int sentinel null", (Supplier<Access>) () -> {
                var map = new IntObjectHashMap<String>();
                map.put(0, null);
                map.put(1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put(4, "hit"),
                        () -> map.put(5, "hit"));
            }),
            Arguments.of("Int ordinary null", (Supplier<Access>) () -> {
                var map = new IntObjectHashMap<String>();
                map.put(2, "hit");
                map.put(3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put(4, "hit"),
                        () -> map.put(5, "hit"));
            }),
            Arguments.of("Long sentinel null", (Supplier<Access>) () -> {
                var map = new LongObjectHashMap<String>();
                map.put((long) 0, null);
                map.put((long) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((long) 4, "hit"),
                        () -> map.put((long) 5, "hit"));
            }),
            Arguments.of("Long ordinary null", (Supplier<Access>) () -> {
                var map = new LongObjectHashMap<String>();
                map.put((long) 2, "hit");
                map.put((long) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((long) 4, "hit"),
                        () -> map.put((long) 5, "hit"));
            }),
            Arguments.of("Short sentinel null", (Supplier<Access>) () -> {
                var map = new ShortObjectHashMap<String>();
                map.put((short) 0, null);
                map.put((short) 1, "hit");
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((short) 4, "hit"),
                        () -> map.put((short) 5, "hit"));
            }),
            Arguments.of("Short ordinary null", (Supplier<Access>) () -> {
                var map = new ShortObjectHashMap<String>();
                map.put((short) 2, "hit");
                map.put((short) 3, "hit");
                // Discover iteration order before assigning values; no hash-layout assumption.
                map.put(map.keySet().toArray()[0], null);
                assertNull(map.values().iterator().next());
                return new Access(map.values()::remove, map::size,
                        () -> (int) map.values().stream().filter(v -> v == null).count(),
                        () -> (int) map.values().stream().filter("hit"::equals).count(),
                        () -> map.put((short) 4, "hit"),
                        () -> map.put((short) 5, "hit"));
            }));
    }
    @ParameterizedTest(name="{0}: present after null") @MethodSource("maps")
    void removesPresentPastNull(String name, Supplier<Access> factory) {
        Access a = factory.get();
        assertTrue(a.remove().test(new String("hit")));
        assertEquals(1, a.size().getAsInt()); assertEquals(1, a.nullCount().getAsInt());
        assertEquals(0, a.hitCount().getAsInt());
        assertTrue(a.remove().test(null)); assertEquals(0, a.size().getAsInt());
        a.reuse().run(); assertTrue(a.remove().test("hit")); assertEquals(0, a.size().getAsInt());
    }
    @ParameterizedTest(name="{0}: absent after null") @MethodSource("maps")
    void leavesAbsentAndNullUntouched(String name, Supplier<Access> factory) {
        Access a = factory.get(); assertFalse(a.remove().test("absent"));
        assertEquals(2, a.size().getAsInt()); assertEquals(1, a.nullCount().getAsInt());
        assertEquals(1, a.hitCount().getAsInt());
    }
    @ParameterizedTest(name="{0}: null removal control") @MethodSource("maps")
    void removesNull(String name, Supplier<Access> factory) {
        Access a = factory.get(); assertTrue(a.remove().test(null));
        assertEquals(1, a.size().getAsInt()); assertEquals(0, a.nullCount().getAsInt());
        assertEquals(1, a.hitCount().getAsInt());
    }
    @ParameterizedTest(name="{0}: duplicate first-only control") @MethodSource("maps")
    void removesOneMatchingValue(String name, Supplier<Access> factory) {
        Access a = factory.get(); a.duplicate().run(); assertTrue(a.remove().test("hit"));
        assertEquals(2, a.size().getAsInt()); assertEquals(1, a.nullCount().getAsInt());
        assertEquals(1, a.hitCount().getAsInt());
    }
}

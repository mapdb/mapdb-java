package org.mapdb.nativetests;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMapUnsafe;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentComputeIfPresentNullTest {
    enum Route { ATOMIC, UNSAFE }
    enum Layout { DIRECT, COLLIDING, GROWN }
    record Key(int id, int hash) { @Override public int hashCode() { return this.hash; } }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> layouts() {
        return routes().flatMap(route -> Stream.of(Layout.values()).map(layout -> Arguments.of(route, layout)));
    }
    static Stream<Arguments> results() {
        return routes().flatMap(route -> Stream.of(Layout.values()).flatMap(layout ->
                Stream.<String>of(null, "new").map(result -> Arguments.of(route, layout, result))));
    }
    static Stream<Arguments> requiredFunctions() {
        return routes().flatMap(route -> Stream.of(false, true).map(present -> Arguments.of(route, present)));
    }
    static MutableMap<Key, String> map(Route route) {
        return route == Route.ATOMIC ? ConcurrentHashMap.newMap(8) : ConcurrentHashMapUnsafe.newMap(8);
    }
    static Key target() { return new Key(1, 7); }
    static MutableMap<Key, String> populated(Route route, Layout layout, boolean present, String value) {
        var source = map(route);
        if (layout != Layout.DIRECT) { source.put(new Key(2, 7), "retained"); source.put(new Key(3, 7), null); }
        if (layout == Layout.GROWN) for (int id = 10; id < 266; id++) source.put(new Key(id, id), "filler" + id);
        if (present) source.put(target(), value);
        return source;
    }
    @ParameterizedTest @MethodSource("results")
    void presentNullIsNotRemapped(Route route, Layout layout, String replacement) {
        var source = populated(route, layout, true, null); var expected = new HashMap<>(source); var calls = new AtomicInteger();
        var controlCalls = new AtomicInteger();
        assertNull(expected.computeIfPresent(target(), (key, value) -> { controlCalls.incrementAndGet(); return replacement; }));
        assertNull(source.computeIfPresent(target(), (key, value) -> { calls.incrementAndGet(); return replacement; }));
        assertEquals(0, controlCalls.get()); assertEquals(0, calls.get()); assertEquals(expected, source);
        assertTrue(source.containsKey(target())); assertNull(source.get(target()));
    }
    @ParameterizedTest @MethodSource("results")
    void nonNullValueRetainsReplacementAndDeletion(Route route, Layout layout, String replacement) {
        var source = populated(route, layout, true, "old"); var expected = new HashMap<>(source); var calls = new AtomicInteger();
        Key originalKey = source.keySet().stream().filter(target()::equals).findFirst().orElseThrow();
        String result = source.computeIfPresent(target(), (key, value) -> {
            calls.incrementAndGet(); assertEquals(target(), key); assertEquals("old", value); return replacement;
        });
        assertEquals(expected.computeIfPresent(target(), (key, value) -> replacement), result);
        assertEquals(1, calls.get()); assertEquals(expected, source);
        assertEquals(replacement != null, source.containsKey(target()));
        if (replacement != null) assertSame(originalKey, source.keySet().stream().filter(target()::equals).findFirst().orElseThrow());
    }
    @ParameterizedTest @MethodSource("results")
    void absentKeyDoesNotInvokeCallback(Route route, Layout layout, String replacement) {
        var source = populated(route, layout, false, null); var before = new HashMap<>(source); var calls = new AtomicInteger();
        assertNull(source.computeIfPresent(target(), (key, value) -> { calls.incrementAndGet(); return replacement; }));
        assertEquals(0, calls.get()); assertEquals(before, source); assertFalse(source.containsKey(target()));
    }
    @ParameterizedTest @MethodSource("layouts")
    void presentNullDoesNotInvokeThrowingCallback(Route route, Layout layout) {
        var source = populated(route, layout, true, null); var before = new HashMap<>(source);
        assertDoesNotThrow(() -> assertNull(source.computeIfPresent(target(), (key, value) -> {
            throw new IllegalArgumentException("callback must not run on a null mapping");
        })));
        assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("layouts")
    void nonNullCallbackExceptionLeavesMapUnchanged(Route route, Layout layout) {
        var source = populated(route, layout, true, "old"); var before = new HashMap<>(source);
        var failure = new IllegalArgumentException("expected callback failure"); var calls = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> source.computeIfPresent(target(), (key, value) -> {
            calls.incrementAndGet(); assertEquals("old", value); throw failure;
        })));
        assertEquals(1, calls.get()); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("requiredFunctions")
    void nullRemappingFunctionStillFailsBeforeLookup(Route route, boolean present) {
        var source = populated(route, Layout.DIRECT, present, null); var before = new HashMap<>(source);
        assertThrows(NullPointerException.class, () -> source.computeIfPresent(target(), null)); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("layouts")
    void ecGetIfAbsentPutStillDistinguishesPresence(Route route, Layout layout) {
        var source = populated(route, layout, true, null); var before = new HashMap<>(source); var calls = new AtomicInteger();
        assertNull(source.getIfAbsentPutWith(target(), parameter -> { calls.incrementAndGet(); return parameter; }, "new"));
        assertEquals(0, calls.get()); assertEquals(before, source); assertTrue(source.containsKey(target()));
    }
}

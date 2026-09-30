package org.mapdb.nativetests;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMapUnsafe;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentComputeIfAbsentNullTest {
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
    void presentNullEvaluatesOnceAndPreservesSizeAndStoredKey(Route route, Layout layout, String replacement) {
        var source = populated(route, layout, true, null); var expected = new HashMap<>(source); var calls = new AtomicInteger();
        var originalKey = source.keySet().stream().filter(target()::equals).findFirst().orElseThrow();
        Key query = target(); String result = source.computeIfAbsent(query, key -> {
            calls.incrementAndGet(); assertSame(query, key); return replacement;
        });
        assertEquals(expected.computeIfAbsent(query, key -> replacement), result); assertEquals(1, calls.get());
        assertEquals(expected, source); assertSame(originalKey, source.keySet().stream().filter(target()::equals).findFirst().orElseThrow());
        assertTrue(source.containsKey(query)); assertEquals(replacement, source.get(query));
    }
    @ParameterizedTest @MethodSource("results")
    void absentKeyRetainsNullAndNonNullComputation(Route route, Layout layout, String replacement) {
        var source = populated(route, layout, false, null); var expected = new HashMap<>(source); var calls = new AtomicInteger();
        assertEquals(expected.computeIfAbsent(target(), key -> replacement), source.computeIfAbsent(target(), key -> {
            calls.incrementAndGet(); assertEquals(target(), key); return replacement;
        }));
        assertEquals(1, calls.get()); assertEquals(expected, source); assertEquals(replacement != null, source.containsKey(target()));
    }
    @ParameterizedTest @MethodSource("layouts")
    void nonNullValueSkipsTheCallback(Route route, Layout layout) {
        var source = populated(route, layout, true, "old"); var before = new HashMap<>(source);
        assertEquals("old", source.computeIfAbsent(target(), key -> { throw new AssertionError("non-null mapping must not be computed"); }));
        assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("layouts")
    void presentNullCallbackFailureLeavesMapUnchanged(Route route, Layout layout) {
        var source = populated(route, layout, true, null); var before = new HashMap<>(source); var calls = new AtomicInteger();
        var failure = new IllegalArgumentException("expected failure");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> source.computeIfAbsent(target(), key -> {
            calls.incrementAndGet(); throw failure;
        })));
        assertEquals(1, calls.get()); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("layouts")
    void absentCallbackFailureLeavesMapUnchanged(Route route, Layout layout) {
        var source = populated(route, layout, false, null); var before = new HashMap<>(source); var calls = new AtomicInteger();
        var failure = new IllegalArgumentException("expected failure");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> source.computeIfAbsent(target(), key -> {
            calls.incrementAndGet(); throw failure;
        })));
        assertEquals(1, calls.get()); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("requiredFunctions")
    void nullFunctionStillFailsBeforeLookup(Route route, boolean present) {
        var source = populated(route, Layout.DIRECT, present, null); var before = new HashMap<>(source);
        assertThrows(NullPointerException.class, () -> source.computeIfAbsent(target(), null)); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("routes")
    void ecGetIfAbsentPutStillRetainsPresentNull(Route route) {
        var source = populated(route, Layout.DIRECT, true, null);
        assertNull(source.getIfAbsentPutWith(target(), parameter -> { throw new AssertionError("EC presence is unchanged"); }, "new"));
        assertTrue(source.containsKey(target())); assertNull(source.get(target())); assertEquals(1, source.size());
    }
    static final class Gate {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int meet() {
            int count = this.calls.incrementAndGet(); this.threads.add(Thread.currentThread().getName());
            if (count <= 2) try { this.barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError("both callbacks must observe the original null mappings", error); }
            return count;
        }
        void verify() { assertEquals(2, this.calls.get(), "computed results must be cached across the collision CAS retry"); assertEquals(2, this.threads.size()); }
    }
    @ParameterizedTest @MethodSource("routes")
    void competingComputationsOnNullEntryReturnTheWinningValue(Route route) throws Exception {
        var source = populated(route, Layout.DIRECT, true, null); var gate = new Gate();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> source.computeIfAbsent(target(), key -> "result" + gate.meet()));
            var second = executor.submit(() -> source.computeIfAbsent(target(), key -> "result" + gate.meet()));
            String a = first.get(10, TimeUnit.SECONDS); String b = second.get(10, TimeUnit.SECONDS); gate.verify();
            assertEquals(a, b); assertTrue(Set.of("result1", "result2").contains(a)); assertEquals(a, source.get(target()));
            assertEquals(1, source.size());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @ParameterizedTest @MethodSource("routes")
    void collidingNullEntriesRetryWithoutRecomputingOrChangingSize(Route route) throws Exception {
        var source = map(route); var firstKey = target(); var secondKey = new Key(2, 7); var retained = new Key(3, 7);
        source.put(retained, "retained"); source.put(firstKey, null); source.put(secondKey, null); var gate = new Gate();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> source.computeIfAbsent(target(), key -> { gate.meet(); return "first"; }));
            var second = executor.submit(() -> source.computeIfAbsent(new Key(2, 7), key -> { gate.meet(); return "second"; }));
            String a = first.get(10, TimeUnit.SECONDS); String b = second.get(10, TimeUnit.SECONDS); gate.verify();
            assertEquals("first", a); assertEquals("second", b); assertEquals(3, source.size());
            assertEquals("first", source.get(firstKey)); assertEquals("second", source.get(secondKey)); assertEquals("retained", source.get(retained));
            assertSame(firstKey, source.keySet().stream().filter(firstKey::equals).findFirst().orElseThrow());
            assertSame(secondKey, source.keySet().stream().filter(secondKey::equals).findFirst().orElseThrow());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @ParameterizedTest @MethodSource("routes")
    void concurrentNullResultsRetainTheExistingNullEntry(Route route) throws Exception {
        var source = populated(route, Layout.DIRECT, true, null); var gate = new Gate();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> source.computeIfAbsent(target(), key -> { gate.meet(); return null; }));
            var second = executor.submit(() -> source.computeIfAbsent(target(), key -> { gate.meet(); return null; }));
            assertNull(first.get(10, TimeUnit.SECONDS)); assertNull(second.get(10, TimeUnit.SECONDS)); gate.verify();
            assertEquals(1, source.size()); assertTrue(source.containsKey(target())); assertNull(source.get(target()));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}

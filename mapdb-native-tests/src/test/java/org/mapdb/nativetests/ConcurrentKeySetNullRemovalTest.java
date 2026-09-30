package org.mapdb.nativetests;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMapUnsafe;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentKeySetNullRemovalTest {
    enum Route { ATOMIC, UNSAFE }
    enum Layout { DIRECT, COLLIDING, GROWN }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> layouts() {
        return routes().flatMap(route -> Stream.of(Layout.values()).flatMap(layout ->
                Stream.<String>of(null, "value").map(value -> Arguments.of(route, layout, value))));
    }
    static Stream<Arguments> routeValues() {
        return routes().flatMap(route -> Stream.<String>of(null, "value").map(value -> Arguments.of(route, value)));
    }
    static ConcurrentMap<Key, String> map(Route route) {
        return route == Route.ATOMIC ? ConcurrentHashMap.newMap(8) : ConcurrentHashMapUnsafe.newMap(8);
    }
    static class Key {
        final int id;
        final int hash;
        final Gate gate;
        Key(int id, int hash) { this(id, hash, null); }
        Key(int id, int hash, Gate gate) { this.id = id; this.hash = hash; this.gate = gate; }
        @Override public int hashCode() { return this.hash; }
        @Override public boolean equals(Object other) {
            boolean equal = other instanceof Key key && this.id == key.id;
            if (equal && this.gate != null) this.gate.meet();
            return equal;
        }
    }
    static final class Gate {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicInteger remaining = new AtomicInteger(2);
        final AtomicInteger arrivals = new AtomicInteger();
        final Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean armed;
        void meet() {
            if (this.armed && this.remaining.getAndDecrement() > 0) {
                this.arrivals.incrementAndGet(); this.threads.add(Thread.currentThread().getName());
                try { this.barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError("both removers must read the old collision chain", error); }
            }
        }
        void verify() { assertEquals(2, this.arrivals.get()); assertEquals(2, this.threads.size()); }
    }
    static ConcurrentMap<Key, String> populated(Route route, Layout layout, String value) {
        var result = map(route);
        if (layout == Layout.GROWN) for (int id = 10; id < 266; id++) result.put(new Key(id, id), "filler" + id);
        if (layout != Layout.DIRECT) {
            result.put(new Key(2, 7), "left"); result.put(new Key(3, 7), null);
        }
        result.put(new Key(1, 7), value); return result;
    }
    @ParameterizedTest @MethodSource("layouts")
    void keyViewReportsActualRemoval(Route route, Layout layout, String value) {
        var source = populated(route, layout, value); int size = source.size();
        assertTrue(source.containsKey(new Key(1, 7))); assertEquals(value, source.get(new Key(1, 7)));
        boolean removed = source.keySet().remove(new Key(1, 7));
        assertFalse(source.containsKey(new Key(1, 7))); assertEquals(size - 1, source.size());
        assertTrue(removed, "removal must report true even when the previous mapped value is null");
        assertFalse(source.keySet().remove(new Key(1, 7))); assertEquals(size - 1, source.size());
        if (layout != Layout.DIRECT) {
            assertEquals("left", source.get(new Key(2, 7))); assertTrue(source.containsKey(new Key(3, 7)));
            assertNull(source.get(new Key(3, 7)));
        }
        if (layout == Layout.GROWN) for (int id = 10; id < 266; id++) assertEquals("filler" + id, source.get(new Key(id, id)));
    }
    @ParameterizedTest @MethodSource("layouts")
    void publicRemoveKeepsNullableReturnContract(Route route, Layout layout, String value) {
        var source = populated(route, layout, value); int size = source.size();
        assertEquals(value, source.remove(new Key(1, 7))); assertFalse(source.containsKey(new Key(1, 7)));
        assertEquals(size - 1, source.size()); assertNull(source.remove(new Key(1, 7)));
        if (layout != Layout.DIRECT) assertEquals("left", source.get(new Key(2, 7)));
    }
    @ParameterizedTest @MethodSource("routeValues")
    void conditionalRemoveKeepsValueMatching(Route route, String value) {
        var source = map(route); source.put(new Key(1, 7), value);
        assertFalse(source.remove(new Key(1, 7), "different")); assertEquals(1, source.size());
        assertTrue(source.remove(new Key(1, 7), value)); assertEquals(0, source.size());
        assertFalse(source.remove(new Key(1, 7), value));
    }
    @ParameterizedTest @MethodSource("routes")
    void removeAllReportsNullValuedKeyRemoval(Route route) {
        var source = map(route);
        source.put(new Key(1, 7), null); source.put(new Key(2, 7), null);
        source.put(new Key(3, 7), "retained"); source.put(new Key(4, 7), null);
        boolean removed = source.keySet().removeAll(List.of(new Key(1, 7), new Key(2, 7)));
        assertEquals(2, source.size()); assertFalse(source.containsKey(new Key(1, 7))); assertFalse(source.containsKey(new Key(2, 7)));
        assertTrue(removed); assertFalse(source.keySet().removeAll(List.of(new Key(1, 7), new Key(2, 7))));
        assertEquals("retained", source.get(new Key(3, 7))); assertTrue(source.containsKey(new Key(4, 7)));
    }
    @ParameterizedTest @MethodSource("routeValues")
    void exactlyOneCompetingRemoverReportsSuccess(Route route, String value) throws Exception {
        var gate = new Gate(); var source = map(route);
        source.put(new Key(2, 7), "retained"); source.put(new Key(1, 7, gate), value); gate.armed = true;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> source.keySet().remove(new Key(1, 7)));
            var second = executor.submit(() -> source.keySet().remove(new Key(1, 7)));
            boolean a = first.get(10, TimeUnit.SECONDS); boolean b = second.get(10, TimeUnit.SECONDS);
            gate.verify(); assertFalse(source.containsKey(new Key(1, 7))); assertEquals(1, source.size());
            assertEquals("retained", source.get(new Key(2, 7)));
            assertEquals(1, (a ? 1 : 0) + (b ? 1 : 0));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @ParameterizedTest @MethodSource("routeValues")
    void collisionChainCasRetryReportsBothRemovedEntries(Route route, String value) throws Exception {
        var gate = new Gate(); var source = map(route);
        source.put(new Key(3, 7), "retained");
        source.put(new Key(1, 7, gate), null); source.put(new Key(2, 7, gate), value); gate.armed = true;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> source.keySet().remove(new Key(1, 7)));
            var second = executor.submit(() -> source.keySet().remove(new Key(2, 7)));
            boolean a = first.get(10, TimeUnit.SECONDS); boolean b = second.get(10, TimeUnit.SECONDS);
            gate.verify(); assertFalse(source.containsKey(new Key(1, 7))); assertFalse(source.containsKey(new Key(2, 7)));
            assertEquals(1, source.size()); assertEquals("retained", source.get(new Key(3, 7)));
            assertTrue(a); assertTrue(b);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}

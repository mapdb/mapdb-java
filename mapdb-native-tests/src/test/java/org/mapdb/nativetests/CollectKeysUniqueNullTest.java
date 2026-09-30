package org.mapdb.nativetests;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.block.function.Function;
import static org.junit.jupiter.api.Assertions.*;

public class CollectKeysUniqueNullTest {
    enum Route {
        JAVA_TARGET, EC_TARGET;
        java.util.Map<String, String> target() {
            return this == JAVA_TARGET ? new java.util.HashMap<>() : UnifiedMap.newMap();
        }
        java.util.Map<String, String> group(List<String> source, Function<? super String, ? extends String> function) {
            return group(source, function, target());
        }
        java.util.Map<String, String> group(List<String> source, Function<? super String, ? extends String> function,
                java.util.Map<String, String> target) {
            var map = new java.util.LinkedHashMap<Integer, String>();
            for (int i = 0; i < source.size(); i++) map.put(i, source.get(i));
            var before = new java.util.LinkedHashMap<>(map);
            try { return org.mapdb.collections.impl.utility.MapIterate.collectKeysUnique(map, (key, value) -> function.valueOf(value), target); }
            finally { assertEquals(before, map); }
        }
    }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<org.junit.jupiter.params.provider.Arguments> routeKeys() {
        return routes().flatMap(route -> Stream.of(null, "same").map(key -> org.junit.jupiter.params.provider.Arguments.of(route, key)));
    }
    @ParameterizedTest @MethodSource("routes") void emptySourceLeavesTargetUntouched(Route route) {
        var calls = new AtomicInteger();
        var target = route.target(); target.put(null, null);
        assertSame(target, route.group(List.of(), v -> { calls.incrementAndGet(); return v; }, target));
        assertEquals(1, target.size()); assertTrue(target.containsKey(null)); assertNull(target.get(null));
        assertEquals(0, calls.get());
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void rejectsNullFirstSourceCollision(Route route, String key) {
        var source = Arrays.<String>asList(null, "hit");
        assertNull(source.get(0)); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> route.group(source, v -> {
            calls.incrementAndGet(); return key;
        }));
        assertEquals(2, calls.get()); assertEquals(2, source.size());
        assertTrue(source.contains(null)); assertTrue(source.contains("hit"));
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void rejectsExistingNullTarget(Route route, String key) { checkTarget(route, key, null, "hit"); }
    @ParameterizedTest @MethodSource("routeKeys")
    void rejectsNullSourceAgainstNullTarget(Route route, String key) { checkTarget(route, key, null, null); }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsNonNullTargetRejection(Route route, String key) { checkTarget(route, key, "old", "hit"); }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsNonNullTargetRejectionWithNullSource(Route route, String key) { checkTarget(route, key, "old", null); }
    private static void checkTarget(Route route, String key, String prior, String value) {
        var source = Arrays.<String>asList(value);
        var target = route.target(); target.put(key, prior);
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> route.group(source, v -> {
            calls.incrementAndGet(); return key;
        }, target));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(1, calls.get()); assertEquals(1, target.size());
        assertTrue(target.containsKey(key)); assertEquals(value, target.get(key));
        assertEquals(1, source.size()); assertTrue(source.contains(value));
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsUniqueNullSourceValue(Route route, String key) {
        var source = Arrays.<String>asList((String) null); var calls = new AtomicInteger();
        var result = route.group(source, v -> { calls.incrementAndGet(); return key; });
        assertEquals(1, result.size()); assertTrue(result.containsKey(key)); assertNull(result.get(key));
        assertEquals(1, calls.get()); assertTrue(source.contains(null));
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsNonNullSourceDuplicateRejection(Route route, String key) {
        var source = Arrays.asList("a", "b"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> route.group(source, v -> {
            calls.incrementAndGet(); return key;
        }));
        assertEquals(2, calls.get()); assertEquals(2, source.size());
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsUnrelatedNullTarget(Route route, String key) {
        var source = Arrays.asList("hit"); var calls = new AtomicInteger();
        var target = route.target(); target.put("other", null);
        assertSame(target, route.group(source, v -> { calls.incrementAndGet(); return key; }, target));
        assertEquals(2, target.size()); assertTrue(target.containsKey("other")); assertNull(target.get("other"));
        assertTrue(target.containsKey(key)); assertEquals("hit", target.get(key));
        assertEquals(1, calls.get()); assertTrue(source.contains("hit"));
    }
    @ParameterizedTest @MethodSource("routes") void retainsDistinctGeneratedNullKey(Route route) {
        var source = Arrays.<String>asList(null, "hit"); var calls = new AtomicInteger();
        var result = route.group(source, v -> { calls.incrementAndGet(); return v; });
        assertEquals(2, result.size()); assertTrue(result.containsKey(null)); assertNull(result.get(null));
        assertEquals("hit", result.get("hit")); assertEquals(2, calls.get()); assertEquals(2, source.size());
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> inheritedKeys() {
        return Stream.of(false, true).flatMap(immutable -> Stream.<String>of(null, "same")
                .map(key -> org.junit.jupiter.params.provider.Arguments.of(immutable, key)));
    }
    static org.mapdb.collections.api.map.MapIterable<String, String> inheritedSource(boolean immutable, String value1, String value2) {
        var map = UnifiedMap.<String, String>newWithKeysValues(null, value1, "a", value2);
        assertNull(map.keysView().getFirst());
        return immutable ? map.toImmutable() : map;
    }
    @ParameterizedTest @MethodSource("inheritedKeys")
    void inheritedClientsRejectNullFirstValue(boolean immutable, String key) {
        var source = inheritedSource(immutable, null, "hit"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return key; }));
        assertEquals(2, calls.get()); assertEquals(2, source.size()); assertTrue(source.containsKey(null));
        assertNull(source.get(null)); assertEquals("hit", source.get("a"));
    }
    @ParameterizedTest @MethodSource("inheritedKeys")
    void inheritedClientsRetainNonNullRejection(boolean immutable, String key) {
        var source = inheritedSource(immutable, "old", "hit"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return key; }));
        assertEquals(2, calls.get()); assertEquals(2, source.size()); assertEquals("old", source.get(null));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false, true})
    void inheritedClientsRetainDistinctNullKey(boolean immutable) {
        var source = inheritedSource(immutable, null, "hit"); var calls = new AtomicInteger();
        var result = source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return k; });
        assertEquals(2, result.size()); assertTrue(result.containsKey(null)); assertNull(result.get(null));
        assertEquals("hit", result.get("a")); assertEquals(2, calls.get()); assertEquals(source, result);
        if (immutable) assertInstanceOf(org.mapdb.collections.api.map.ImmutableMap.class, result);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false, true})
    void inheritedClientsRetainEmpty(boolean immutable) {
        org.mapdb.collections.api.map.MapIterable<String, String> source = immutable
                ? UnifiedMap.<String, String>newMap().toImmutable() : UnifiedMap.newMap();
        var calls = new AtomicInteger();
        assertTrue(source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return k; }).isEmpty());
        assertEquals(0, calls.get());
    }
}

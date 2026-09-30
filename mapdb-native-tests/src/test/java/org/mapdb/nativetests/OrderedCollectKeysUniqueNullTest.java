package org.mapdb.nativetests;

import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.factory.OrderedMaps;
import org.mapdb.collections.api.map.MapIterable;
import org.mapdb.collections.api.map.MutableOrderedMap;
import org.mapdb.collections.api.map.ImmutableOrderedMap;
import static org.junit.jupiter.api.Assertions.*;

public class OrderedCollectKeysUniqueNullTest {
    enum Route { MUTABLE, IMMUTABLE, UNMODIFIABLE }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> routeKeys() {
        return routes().flatMap(route -> Stream.<String>of(null, "same").map(key -> Arguments.of(route, key)));
    }
    static MapIterable<String, String> source(Route route, String first, String second) {
        var map = OrderedMaps.mutable.<String, String>empty(); map.put(null, first); map.put("b", second);
        return switch (route) {
            case MUTABLE -> map;
            case IMMUTABLE -> map.toImmutable();
            case UNMODIFIABLE -> map.asUnmodifiable();
        };
    }
    static void checkDuplicate(Route route, String key, String first, String second) {
        var source = source(route, first, second); var calls = new AtomicInteger();
        var keys = source.keysView().toList(); var values = source.valuesView().toList();
        var error = assertThrows(IllegalStateException.class, () -> source.collectKeysUnique((k, v) -> {
            calls.incrementAndGet(); return key;
        }));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(2, calls.get()); assertEquals(2, source.size());
        assertEquals(keys, source.keysView().toList()); assertEquals(values, source.valuesView().toList());
    }
    @ParameterizedTest @MethodSource("routeKeys")
    void rejectsNullFirstValue(Route route, String key) { checkDuplicate(route, key, null, "hit"); }
    @ParameterizedTest @MethodSource("routeKeys")
    void rejectsDuplicateNullValues(Route route, String key) { checkDuplicate(route, key, null, null); }
    @ParameterizedTest @MethodSource("routeKeys")
    void retainsNonNullValueDuplicateRejection(Route route, String key) { checkDuplicate(route, key, "old", "hit"); }

    @ParameterizedTest @MethodSource("routes")
    void rejectsDistinctEqualGeneratedKeys(Route route) {
        var source = source(route, null, "hit"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> source.collectKeysUnique((k, v) -> {
            calls.incrementAndGet(); return new String("same");
        }));
        assertEquals(2, calls.get()); assertEquals(2, source.size()); assertNull(source.get(null)); assertEquals("hit", source.get("b"));
    }
    @ParameterizedTest @MethodSource("routes")
    void retainsUniqueNullableResultAndOrder(Route route) {
        var source = source(route, null, "hit"); var calls = new AtomicInteger();
        var keys = source.keysView().toList(); var values = source.valuesView().toList();
        var result = source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return k; });
        assertEquals(2, calls.get()); assertEquals(2, result.size());
        assertEquals(keys, result.keysView().toList()); assertEquals(values, result.valuesView().toList());
        assertTrue(result.containsKey(null)); assertNull(result.get(null)); assertEquals("hit", result.get("b"));
        assertEquals(keys, source.keysView().toList()); assertEquals(values, source.valuesView().toList());
        if (route == Route.IMMUTABLE) assertInstanceOf(ImmutableOrderedMap.class, result);
        else assertInstanceOf(MutableOrderedMap.class, result);
    }
    @ParameterizedTest @MethodSource("routes")
    void retainsEmptySource(Route route) {
        var mutable = OrderedMaps.mutable.<String, String>empty();
        MapIterable<String, String> source = switch (route) {
            case MUTABLE -> mutable;
            case IMMUTABLE -> mutable.toImmutable();
            case UNMODIFIABLE -> mutable.asUnmodifiable();
        };
        var calls = new AtomicInteger();
        assertTrue(source.collectKeysUnique((k, v) -> { calls.incrementAndGet(); return k; }).isEmpty());
        assertEquals(0, calls.get()); assertTrue(source.isEmpty());
    }
}

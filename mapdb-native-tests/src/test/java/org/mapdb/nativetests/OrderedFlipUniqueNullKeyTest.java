package org.mapdb.nativetests;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.map.MapIterable;
import org.mapdb.collections.api.map.ImmutableOrderedMap;
import org.mapdb.collections.api.map.MutableOrderedMap;
import org.mapdb.collections.api.factory.OrderedMaps;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import static org.junit.jupiter.api.Assertions.*;

public class OrderedFlipUniqueNullKeyTest {
    enum Route { MUTABLE, UNMODIFIABLE, IMMUTABLE }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> routeValues() {
        return routes().flatMap(route -> Stream.<String>of(null, "same").map(value -> Arguments.of(route, value)));
    }
    static MapIterable<String, String> wrap(Route route, MutableOrderedMap<String, String> source) {
        return switch (route) {
            case MUTABLE -> source;
            case IMMUTABLE -> source.toImmutable();
            case UNMODIFIABLE -> source.asUnmodifiable();
        };
    }
    static MapIterable<String, String> source(Route route, String key1, String value1, String key2, String value2) {
        var source = OrderedMaps.mutable.<String, String>empty(); source.put(key1, value1); source.put(key2, value2);
        return wrap(route, source);
    }
    static MapIterable<String, String> flip(Route route, MapIterable<String, String> source) {
        var before = UnifiedMap.<String, String>newMap(); source.forEachKeyValue(before::put);
        var keys = source.keysView().toList(); var values = source.valuesView().toList();
        try {
            var result = source.flipUniqueValues();
            if (route == Route.IMMUTABLE) assertInstanceOf(ImmutableOrderedMap.class, result);
            return result;
        } finally { assertEquals(before, source); assertEquals(keys, source.keysView().toList()); assertEquals(values, source.valuesView().toList()); }
    }
    @ParameterizedTest @MethodSource("routeValues")
    void rejectsDuplicateValueAfterNullKey(Route route, String value) {
        var source = source(route, null, value, "a", value);
        assertNull(source.keysView().getFirst());
        var error = assertThrows(IllegalStateException.class, () -> flip(route, source));
        assertEquals("Duplicate value: " + value + " found at key: null and key: a", error.getMessage());
    }
    @ParameterizedTest @MethodSource("routeValues")
    void retainsNonNullKeyDuplicateRejection(Route route, String value) {
        var source = source(route, "a", value, "b", value);
        assertThrows(IllegalStateException.class, () -> flip(route, source));
    }
    @ParameterizedTest @MethodSource("routeValues")
    void retainsUniqueNullSourceKey(Route route, String value) {
        var source = source(route, null, value, "a", "other"); var result = flip(route, source);
        assertEquals(source.valuesView().toList(), result.keysView().toList());
        assertEquals(source.keysView().toList(), result.valuesView().toList());
        assertEquals(source.size(), result.size()); assertTrue(result.containsKey(value)); assertNull(result.get(value));
        assertEquals("a", result.get("other"));
        source.forEachKeyValue((key, originalValue) -> {
            assertTrue(result.containsKey(originalValue)); assertEquals(key, result.get(originalValue));
        });
    }
    @ParameterizedTest @MethodSource("routeValues")
    void retainsUniqueNullableValue(Route route, String key) {
        var source = source(route, key, null, "a", "other"); var result = flip(route, source);
        assertEquals(source.valuesView().toList(), result.keysView().toList());
        assertEquals(source.keysView().toList(), result.valuesView().toList());
        assertEquals(source.size(), result.size()); assertTrue(result.containsKey(null)); assertEquals(key, result.get(null));
        assertEquals("a", result.get("other"));
    }
    @ParameterizedTest @MethodSource("routes")
    void retainsEmptySource(Route route) {
        MapIterable<String, String> source = wrap(route, OrderedMaps.mutable.empty());
        assertTrue(flip(route, source).isEmpty()); assertTrue(source.isEmpty());
    }
}

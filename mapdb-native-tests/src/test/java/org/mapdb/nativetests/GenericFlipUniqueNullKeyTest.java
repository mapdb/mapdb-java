package org.mapdb.nativetests;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.map.MapIterable;
import org.mapdb.collections.api.map.ImmutableMap;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.utility.MapIterate;
import static org.junit.jupiter.api.Assertions.*;

public class GenericFlipUniqueNullKeyTest {
    enum Route { MUTABLE, STATIC, IMMUTABLE_HASH }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> routeValues() {
        return routes().flatMap(route -> Stream.<String>of(null, "same").map(value -> Arguments.of(route, value)));
    }
    static MapIterable<String, String> source(Route route, String key1, String value1, String key2, String value2) {
        var source = UnifiedMap.<String, String>newWithKeysValues(key1, value1, key2, value2);
        if (route == Route.IMMUTABLE_HASH) {
            for (int i = 0; i < 3; i++) source.put("filler-key-" + i, "filler-value-" + i);
            var immutable = source.toImmutable();
            assertEquals("ImmutableUnifiedMap", immutable.getClass().getSimpleName());
            return immutable;
        }
        return source;
    }
    static MapIterable<String, String> flip(Route route, MapIterable<String, String> source) {
        var before = UnifiedMap.<String, String>newMap(); source.forEachKeyValue(before::put);
        try {
            var result = route == Route.STATIC ? MapIterate.flipUniqueValues(source) : source.flipUniqueValues();
            if (route == Route.IMMUTABLE_HASH) assertInstanceOf(ImmutableMap.class, result);
            return result;
        } finally { assertEquals(before, source); }
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
        assertEquals(source.size(), result.size()); assertTrue(result.containsKey(value)); assertNull(result.get(value));
        assertEquals("a", result.get("other"));
        source.forEachKeyValue((key, originalValue) -> {
            assertTrue(result.containsKey(originalValue)); assertEquals(key, result.get(originalValue));
        });
    }
    @ParameterizedTest @MethodSource("routeValues")
    void retainsUniqueNullableValue(Route route, String key) {
        var source = source(route, key, null, "a", "other"); var result = flip(route, source);
        assertEquals(source.size(), result.size()); assertTrue(result.containsKey(null)); assertEquals(key, result.get(null));
        assertEquals("a", result.get("other"));
    }
    @ParameterizedTest @MethodSource("routes")
    void retainsEmptySource(Route route) {
        MapIterable<String, String> source = route == Route.IMMUTABLE_HASH
                ? UnifiedMap.<String, String>newMap().toImmutable() : UnifiedMap.newMap();
        assertTrue(flip(route, source).isEmpty()); assertTrue(source.isEmpty());
    }
}

package org.mapdb.nativetests;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.map.MutableMapIterable;
import org.mapdb.collections.impl.list.mutable.FastList;
import org.mapdb.collections.impl.utility.ArrayIterate;
import org.mapdb.collections.impl.utility.Iterate;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingSequentialNullTest {
    enum Route {
        FAST_LIST, ARRAY, RANDOM_ACCESS, ITERATOR;
        MutableMapIterable<String, String> group(List<String> source, Function<? super String, ? extends String> function) {
            return group(source, function, UnifiedMap.newMap());
        }
        MutableMapIterable<String, String> group(List<String> source, Function<? super String, ? extends String> function,
                MutableMapIterable<String, String> target) {
            List<String> actual = switch (this) {
                case FAST_LIST -> FastList.newList(source);
                case ITERATOR -> new LinkedList<>(source);
                default -> source;
            };
            var before = new java.util.ArrayList<>(actual);
            String[] array = source.toArray(new String[0]);
            String[] arrayBefore = array.clone();
            try {
                return switch (this) {
                    case FAST_LIST -> ((FastList<String>) actual).groupByUniqueKey(function, target);
                    case ARRAY -> ArrayIterate.groupByUniqueKey(array, function, target);
                    case RANDOM_ACCESS, ITERATOR -> Iterate.groupByUniqueKey(actual, function, target);
                };
            } finally {
                assertEquals(before, actual);
                assertArrayEquals(arrayBefore, array);
            }
        }
    }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<org.junit.jupiter.params.provider.Arguments> routeKeys() {
        return routes().flatMap(route -> Stream.of(null, "same").map(key -> org.junit.jupiter.params.provider.Arguments.of(route, key)));
    }
    @ParameterizedTest @MethodSource("routes") void emptySourceLeavesTargetUntouched(Route route) {
        var calls = new AtomicInteger();
        var target = UnifiedMap.<String, String>newMap(); target.put(null, null);
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
        var target = UnifiedMap.<String, String>newMap(); target.put(key, prior);
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
        var target = UnifiedMap.<String, String>newMap(); target.put("other", null);
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
}

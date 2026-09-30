package org.mapdb.nativetests;

import java.util.Collection;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.impl.block.factory.HashingStrategies;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.map.strategy.mutable.UnifiedMapWithHashingStrategy;
import static org.junit.jupiter.api.Assertions.*;

public class UnifiedMapValuesNullScanTest {
    enum Route { ORDINARY, STRATEGY }
    enum Layout { DIRECT, COLLIDING, GROWN }
    record Key(int id, int hash) { @Override public int hashCode() { return this.hash; } }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> layouts() {
        return routes().flatMap(route -> Stream.of(Layout.values()).map(layout -> Arguments.of(route, layout)));
    }
    static MutableMap<Object, Object> map(Route route) {
        return route == Route.ORDINARY ? UnifiedMap.newMap(8) :
                UnifiedMapWithHashingStrategy.newMap(HashingStrategies.nullSafeHashingStrategy(HashingStrategies.defaultStrategy()), 8);
    }
    static Key key(Layout layout, int id) { return new Key(id, layout == Layout.COLLIDING ? 0 : id); }
    static MutableMap<Object, Object> populated(Route route, Layout layout, Object firstValue, Object hit) {
        var result = map(route); result.put(null, firstValue); result.put(key(layout, 1), hit);
        if (layout == Layout.GROWN) for (int id = 2; id < 130; id++) result.put(key(layout, id), "filler" + id);
        assertSame(firstValue, result.values().iterator().next(), "source must expose the nullable entry before the query match");
        return result;
    }
    static void retainsFillers(MutableMap<Object, Object> source, Layout layout) {
        if (layout == Layout.GROWN) for (int id = 2; id < 130; id++) assertEquals("filler" + id, source.get(key(layout, id)));
    }
    @ParameterizedTest @MethodSource("layouts")
    void removesMatchAfterStoredNull(Route route, Layout layout) {
        var source = populated(route, layout, null, "hit"); Collection<Object> view = source.values(); int size = source.size();
        assertTrue(view.remove(new String("hit"))); assertEquals(size - 1, source.size()); assertEquals(source.size(), view.size());
        assertFalse(source.containsKey(key(layout, 1))); assertTrue(source.containsKey(null)); assertNull(source.get(null));
        retainsFillers(source, layout); source.put(key(layout, 1), "again"); assertTrue(view.contains("again"));
    }
    @ParameterizedTest @MethodSource("layouts")
    void absentNonNullQuerySkipsStoredNull(Route route, Layout layout) {
        var source = populated(route, layout, null, "hit"); var before = UnifiedMap.newMap(source);
        assertFalse(source.values().remove("absent")); assertEquals(before, source);
    }
    @ParameterizedTest @MethodSource("layouts")
    void existingNullRemovalRetainsOtherValues(Route route, Layout layout) {
        var source = populated(route, layout, null, "hit"); int size = source.size();
        assertTrue(source.values().remove(null)); assertFalse(source.containsKey(null)); assertEquals(size - 1, source.size());
        assertFalse(source.values().remove(null)); assertEquals("hit", source.get(key(layout, 1))); retainsFillers(source, layout);
    }
    @ParameterizedTest @MethodSource("layouts")
    void removesExactlyOneDuplicateAfterStoredNull(Route route, Layout layout) {
        var source = populated(route, layout, null, "hit"); source.put(key(layout, 131), new String("hit")); int size = source.size();
        assertEquals(2, source.valuesView().count("hit"::equals));
        assertTrue(source.values().remove("hit")); assertEquals(size - 1, source.size());
        assertEquals(1, source.valuesView().count("hit"::equals)); assertTrue(source.containsKey(null)); assertNull(source.get(null));
        assertTrue(source.values().remove("hit")); assertEquals(0, source.valuesView().count("hit"::equals));
        assertFalse(source.values().remove("hit")); assertTrue(source.containsKey(null)); retainsFillers(source, layout);
    }
    @ParameterizedTest @MethodSource("layouts")
    void nonNullScanRetainsExistingMatchingBehavior(Route route, Layout layout) {
        var source = populated(route, layout, "first", "hit"); int size = source.size();
        assertTrue(source.values().remove(new String("hit"))); assertEquals(size - 1, source.size());
        assertEquals("first", source.get(null)); assertFalse(source.containsKey(key(layout, 1)));
        assertFalse(source.values().remove("hit")); retainsFillers(source, layout);
    }
    @ParameterizedTest @MethodSource("routes")
    void emptyAndNullOnlyMapsDoNotMatchNonNullQuery(Route route) {
        var source = map(route); assertFalse(source.values().remove("absent"));
        source.put(null, null); assertFalse(source.values().remove("absent"));
        assertEquals(1, source.size()); assertTrue(source.containsKey(null)); assertNull(source.get(null));
    }
    @ParameterizedTest @MethodSource("routes")
    void identicalValueRetainsEqualsShortcut(Route route) {
        Object value = new Object() { @Override public boolean equals(Object other) { throw new AssertionError("identity must avoid equals"); } };
        var source = map(route); source.put(null, value);
        assertTrue(source.values().remove(value)); assertTrue(source.isEmpty());
    }
    @ParameterizedTest @MethodSource("routes")
    void retainsStoredValueComparisonDirection(Route route) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Object query = new Object() { @Override public boolean equals(Object other) { throw new AssertionError("query equals must not replace stored-value equals"); } };
        Object stored = new Object() { @Override public boolean equals(Object other) { calls.incrementAndGet(); return other == query; } };
        var source = map(route); source.put(null, stored);
        assertTrue(source.values().remove(query)); assertEquals(1, calls.get()); assertTrue(source.isEmpty());
    }
}

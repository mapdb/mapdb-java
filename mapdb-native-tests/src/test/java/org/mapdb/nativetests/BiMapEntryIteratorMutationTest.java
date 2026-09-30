package org.mapdb.nativetests;

import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.bimap.MutableBiMap;
import org.mapdb.collections.api.factory.BiMaps;
import static org.junit.jupiter.api.Assertions.*;

public class BiMapEntryIteratorMutationTest {
    enum Route { FORWARD, INVERSE }
    record Key(int id) { @Override public int hashCode() { return this.id; } }
    static Stream<Route> routes() { return Stream.of(Route.values()); }
    static Stream<Arguments> replacements() {
        return routes().flatMap(route -> Stream.<Object>of(null, new Key(1)).flatMap(key ->
                Stream.<String>of(null, "old").flatMap(prior -> Stream.<String>of(null, "new")
                        .map(next -> Arguments.of(route, key, prior, next)))));
    }
    static Stream<Arguments> repeated() {
        return routes().flatMap(route -> Stream.<Object>of(null, new Key(1)).flatMap(key ->
                Stream.<String>of(null, "last").map(value -> Arguments.of(route, key, value))));
    }
    static Stream<Arguments> routeValues() {
        return routes().flatMap(route -> Stream.<String>of(null, "old").map(value -> Arguments.of(route, value)));
    }
    static MutableBiMap<Object, Object> map(Route route) {
        MutableBiMap<Object, Object> base = BiMaps.mutable.empty();
        return route == Route.FORWARD ? base : base.inverse();
    }
    static void consistent(MutableBiMap<Object, Object> source) {
        assertEquals(source.size(), source.inverse().size());
        source.forEachKeyValue((key, value) -> {
            assertTrue(source.inverse().containsKey(value)); assertEquals(key, source.inverse().get(value));
        });
        source.inverse().forEachKeyValue((value, key) -> {
            assertTrue(source.containsKey(key)); assertEquals(value, source.get(key));
        });
        assertSame(source, source.inverse().inverse());
    }
    @ParameterizedTest @MethodSource("replacements")
    void entryReplacementThenRemovalClearsBothDirections(Route route, Object key, String prior, String replacement) {
        var source = map(route); source.put(key, prior); var iterator = source.entrySet().iterator(); var entry = iterator.next();
        assertEquals(prior, entry.setValue(replacement)); assertEquals(key, entry.getKey()); assertEquals(replacement, entry.getValue());
        assertEquals(replacement, source.get(key)); consistent(source);
        iterator.remove(); assertTrue(source.isEmpty()); assertTrue(source.inverse().isEmpty()); consistent(source);
        assertFalse(iterator.hasNext());
    }
    @ParameterizedTest @MethodSource("repeated")
    void repeatedReplacementsRemoveTheLatestValue(Route route, Object key, String last) {
        var source = map(route); source.put(key, null); var iterator = source.entrySet().iterator(); var entry = iterator.next();
        assertNull(entry.setValue("middle")); assertEquals("middle", entry.setValue(last));
        assertEquals(last, entry.getValue()); assertFalse(source.inverse().containsKey("middle")); consistent(source);
        iterator.remove(); assertTrue(source.isEmpty()); assertTrue(source.inverse().isEmpty()); consistent(source);
    }
    @ParameterizedTest @MethodSource("routes")
    void earlierEntryMutationDoesNotChangeCurrentCleanupTarget(Route route) {
        var source = map(route); var otherKey = new Key(1); source.put(null, "first"); source.put(otherKey, null);
        var iterator = source.entrySet().iterator(); var earlier = iterator.next(); assertNull(earlier.getKey());
        var current = iterator.next(); assertEquals(otherKey, current.getKey());
        assertEquals("first", earlier.setValue("changed-first")); assertNull(current.getValue());
        iterator.remove(); assertEquals(1, source.size()); assertEquals("changed-first", source.get(null));
        assertFalse(source.containsKey(otherKey)); assertFalse(source.inverse().containsKey(null)); consistent(source);
    }
    @ParameterizedTest @MethodSource("routes")
    void mutationsToEarlierAndCurrentEntriesRetainTheEarlierMapping(Route route) {
        var source = map(route); var otherKey = new Key(1); source.put(null, "first"); source.put(otherKey, null);
        var iterator = source.entrySet().iterator(); var earlier = iterator.next(); assertNull(earlier.getKey());
        var current = iterator.next(); assertEquals(otherKey, current.getKey());
        earlier.setValue("changed-first"); current.setValue("changed-second"); consistent(source);
        iterator.remove(); assertEquals(1, source.size()); assertEquals("changed-first", source.get(null));
        assertFalse(source.containsKey(otherKey)); assertFalse(source.inverse().containsKey("changed-second")); consistent(source);
    }
    @ParameterizedTest @MethodSource("routeValues")
    void failedDuplicateReplacementRetainsOriginalRemoval(Route route, String prior) {
        var source = map(route); var otherKey = new Key(1); source.put(null, prior); source.put(otherKey, "occupied");
        var iterator = source.entrySet().iterator(); var entry = iterator.next(); assertNull(entry.getKey());
        assertThrows(IllegalArgumentException.class, () -> entry.setValue("occupied"));
        assertEquals(prior, entry.getValue()); assertEquals(prior, source.get(null)); consistent(source);
        iterator.remove(); assertEquals(1, source.size()); assertEquals("occupied", source.get(otherKey));
        assertFalse(source.containsKey(null)); consistent(source);
    }
    @ParameterizedTest @MethodSource("routes")
    void nullableUnchangedEntryRetainsIteratorLifecycle(Route route) {
        var source = map(route); source.put(null, null); var iterator = source.entrySet().iterator();
        assertThrows(IllegalStateException.class, iterator::remove); consistent(source);
        var entry = iterator.next(); assertNull(entry.getKey()); assertNull(entry.getValue());
        iterator.remove(); assertThrows(IllegalStateException.class, iterator::remove);
        assertFalse(iterator.hasNext()); assertThrows(NoSuchElementException.class, iterator::next);
        assertTrue(source.isEmpty()); assertTrue(source.inverse().isEmpty()); consistent(source);
    }
}

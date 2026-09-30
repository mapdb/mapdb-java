package org.mapdb.nativetests;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingProcedureNullTest {
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void rejectsNullFirstSourceCollision(String key) {
        var source = UnifiedSet.<String>newSetWith(null, "hit");
        assertNull(source.getFirst()); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> source.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }));
        assertEquals(2, calls.get()); assertEquals(2, source.size());
        assertTrue(source.contains(null)); assertTrue(source.contains("hit"));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void rejectsExistingNullTarget(String key) { checkTarget(key, null, "hit"); }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void rejectsNullSourceAgainstNullTarget(String key) { checkTarget(key, null, null); }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsNonNullTargetRejection(String key) { checkTarget(key, "old", "hit"); }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsNonNullTargetRejectionWithNullSource(String key) { checkTarget(key, "old", null); }
    private static void checkTarget(String key, String prior, String value) {
        var source = UnifiedSet.<String>newSetWith(value);
        var target = UnifiedMap.<String, String>newMap(); target.put(key, prior);
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> source.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }, target));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(1, calls.get()); assertEquals(1, target.size());
        assertTrue(target.containsKey(key)); assertEquals(value, target.get(key));
        assertEquals(1, source.size()); assertTrue(source.contains(value));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsUniqueNullSourceValue(String key) {
        var source = UnifiedSet.<String>newSetWith((String) null); var calls = new AtomicInteger();
        var result = source.groupByUniqueKey(v -> { calls.incrementAndGet(); return key; });
        assertEquals(1, result.size()); assertTrue(result.containsKey(key)); assertNull(result.get(key));
        assertEquals(1, calls.get()); assertTrue(source.contains(null));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsNonNullSourceDuplicateRejection(String key) {
        var source = UnifiedSet.newSetWith("a", "b"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> source.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }));
        assertEquals(2, calls.get()); assertEquals(2, source.size());
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsUnrelatedNullTarget(String key) {
        var source = UnifiedSet.newSetWith("hit"); var calls = new AtomicInteger();
        var target = UnifiedMap.<String, String>newMap(); target.put("other", null);
        assertSame(target, source.groupByUniqueKey(v -> { calls.incrementAndGet(); return key; }, target));
        assertEquals(2, target.size()); assertTrue(target.containsKey("other")); assertNull(target.get("other"));
        assertTrue(target.containsKey(key)); assertEquals("hit", target.get(key));
        assertEquals(1, calls.get()); assertTrue(source.contains("hit"));
    }
    @Test void retainsDistinctGeneratedNullKey() {
        var source = UnifiedSet.<String>newSetWith(null, "hit"); var calls = new AtomicInteger();
        var result = source.groupByUniqueKey(v -> { calls.incrementAndGet(); return v; });
        assertEquals(2, result.size()); assertTrue(result.containsKey(null)); assertNull(result.get(null));
        assertEquals("hit", result.get("hit")); assertEquals(2, calls.get()); assertEquals(2, source.size());
    }
}

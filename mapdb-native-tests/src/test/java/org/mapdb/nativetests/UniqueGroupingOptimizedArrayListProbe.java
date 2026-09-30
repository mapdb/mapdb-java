package org.mapdb.nativetests;

import java.util.concurrent.atomic.AtomicInteger;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import java.util.ArrayList;
import java.util.Arrays;
import org.mapdb.collections.impl.utility.ArrayListIterate;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingOptimizedArrayListProbe {
    static int passed;
    static int failed;
    public static void main(String[] args) throws Exception {
        var field = ArrayListIterate.class.getDeclaredField("ELEMENT_DATA_FIELD");
        field.setAccessible(true);
        assertNotNull(field.get(null), "optimized route must have reflective backing-array access");
        for (String key : new String[]{null, "same"}) {
            check("null-first:" + key, () -> rejectsNullFirstSourceCollision(key));
            check("null-target:" + key, () -> rejectsExistingNullTarget(key));
            check("null-source-target:" + key, () -> rejectsNullSourceAgainstNullTarget(key));
            check("nonnull-target:" + key, () -> retainsNonNullTargetRejection(key));
            check("nonnull-target-null-source:" + key, () -> retainsNonNullTargetRejectionWithNullSource(key));
            check("unique-null:" + key, () -> retainsUniqueNullSourceValue(key));
            check("nonnull-duplicate:" + key, () -> retainsNonNullSourceDuplicateRejection(key));
            check("unrelated-null:" + key, () -> retainsUnrelatedNullTarget(key));
        }
        check("distinct-null-key", UniqueGroupingOptimizedArrayListProbe::retainsDistinctGeneratedNullKey);
        check("size100-fallback", () -> fallback(false));
        check("subclass101-fallback", () -> fallback(true));
        System.out.println("OPTIMIZED_GROUPING_RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) throw new AssertionError("optimized grouping semantic failures=" + failed);
        assertEquals(19, passed);
    }
    static void check(String name, Runnable test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (AssertionError e) { failed++; System.out.println("FAIL " + name + " " + e.getMessage()); }
    }
    static ArrayList<String> source(String... values) {
        var result = new ArrayList<>(Arrays.asList(values));
        for (int i = result.size(); i < 101; i++) result.add("filler-" + i);
        assertEquals(ArrayList.class, result.getClass()); assertEquals(101, result.size());
        return result;
    }
    static void fallback(boolean subclass) {
        ArrayList<String> list = subclass ? new ArrayList<String>() {} : new ArrayList<>();
        list.add(null); list.add("hit");
        int size = subclass ? 101 : 100;
        while (list.size() < size) list.add("filler-" + list.size());
        var before = new ArrayList<>(list); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ArrayListIterate.groupByUniqueKey(list, v -> {
            calls.incrementAndGet(); return "same";
        }));
        assertEquals(2, calls.get()); assertEquals(before, list);
    }
    static org.mapdb.collections.api.map.MutableMapIterable<String, String> group(ArrayList<String> list,
            org.mapdb.collections.api.block.function.Function<? super String, ? extends String> function) {
        return group(list, function, UnifiedMap.newMap());
    }
    static org.mapdb.collections.api.map.MutableMapIterable<String, String> group(ArrayList<String> list,
            org.mapdb.collections.api.block.function.Function<? super String, ? extends String> function,
            org.mapdb.collections.api.map.MutableMapIterable<String, String> target) {
        var before = new ArrayList<>(list);
        try { return ArrayListIterate.groupByUniqueKey(list, function, target); }
        finally { assertEquals(before, list); }
    }
    static void rejectsNullFirstSourceCollision(String key) {
        var source = source(null, "hit");
        assertNull(source.get(0)); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> group(source, v -> {
            calls.incrementAndGet(); return v != null && v.startsWith("filler-") ? v : key;
        }));
        assertEquals(2, calls.get()); assertEquals(101, source.size());
        assertTrue(source.contains(null)); assertTrue(source.contains("hit"));
    }
    static void rejectsExistingNullTarget(String key) { checkTarget(key, null, "hit"); }
    static void rejectsNullSourceAgainstNullTarget(String key) { checkTarget(key, null, null); }
    static void retainsNonNullTargetRejection(String key) { checkTarget(key, "old", "hit"); }
    static void retainsNonNullTargetRejectionWithNullSource(String key) { checkTarget(key, "old", null); }
    private static void checkTarget(String key, String prior, String value) {
        var source = source(value);
        var target = UnifiedMap.<String, String>newMap(); target.put(key, prior);
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> group(source, v -> {
            calls.incrementAndGet(); return v != null && v.startsWith("filler-") ? v : key;
        }, target));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(1, calls.get()); assertEquals(1, target.size());
        assertTrue(target.containsKey(key)); assertEquals(value, target.get(key));
        assertEquals(101, source.size()); assertTrue(source.contains(value));
    }
    static void retainsUniqueNullSourceValue(String key) {
        var source = source((String) null); var calls = new AtomicInteger();
        var result = group(source, v -> { calls.incrementAndGet(); return v != null && v.startsWith("filler-") ? v : key; });
        assertEquals(101, result.size()); assertTrue(result.containsKey(key)); assertNull(result.get(key));
        assertEquals(101, calls.get()); assertTrue(source.contains(null));
    }
    static void retainsNonNullSourceDuplicateRejection(String key) {
        var source = source("a", "b"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> group(source, v -> {
            calls.incrementAndGet(); return v != null && v.startsWith("filler-") ? v : key;
        }));
        assertEquals(2, calls.get()); assertEquals(101, source.size());
    }
    static void retainsUnrelatedNullTarget(String key) {
        var source = source("hit"); var calls = new AtomicInteger();
        var target = UnifiedMap.<String, String>newMap(); target.put("other", null);
        assertSame(target, group(source, v -> { calls.incrementAndGet(); return v != null && v.startsWith("filler-") ? v : key; }, target));
        assertEquals(102, target.size()); assertTrue(target.containsKey("other")); assertNull(target.get("other"));
        assertTrue(target.containsKey(key)); assertEquals("hit", target.get(key));
        assertEquals(101, calls.get()); assertTrue(source.contains("hit"));
    }
    static void retainsDistinctGeneratedNullKey() {
        var source = source(null, "hit"); var calls = new AtomicInteger();
        var result = group(source, v -> { calls.incrementAndGet(); return v; });
        assertEquals(101, result.size()); assertTrue(result.containsKey(null)); assertNull(result.get(null));
        assertEquals("hit", result.get("hit")); assertEquals(101, calls.get()); assertEquals(101, source.size());
    }
}

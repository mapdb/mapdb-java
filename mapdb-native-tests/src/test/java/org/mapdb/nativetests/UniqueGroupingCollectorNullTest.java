package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collector;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.impl.collector.Collectors2;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingCollectorNullTest {
    static Stream<Arguments> collisions() {
        return Stream.<String>of(null, "same").flatMap(key -> Stream.<String>of(null, "old")
                .flatMap(prior -> Stream.<String>of(null, "hit").map(value -> Arguments.of(key, prior, value))));
    }
    static Stream<Arguments> modes() {
        return Stream.of(false, true).flatMap(parallel -> Stream.<String>of(null, "same")
                .map(key -> Arguments.of(parallel, key)));
    }
    static Stream<String> keys() { return Stream.of(null, "same"); }
    static Stream<Boolean> parallelModes() { return Stream.of(false, true); }

    @ParameterizedTest @MethodSource("collisions")
    void accumulatorRejectsExistingMapping(String key, String prior, String value) {
        var calls = new AtomicInteger();
        checkAccumulator(Collectors2.groupByUniqueKey(v -> { calls.incrementAndGet(); return key; },
                UnifiedMap::<String, String>newMap), key, prior, value, calls);
    }
    private static <A> void checkAccumulator(Collector<String, A, UnifiedMap<String, String>> collector,
            String key, String prior, String value, AtomicInteger calls) {
        A container = collector.supplier().get();
        var map = collector.finisher().apply(container);
        map.put(key, prior);
        var error = assertThrows(IllegalStateException.class, () -> collector.accumulator().accept(container, value));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(1, calls.get());
        assertEquals(1, map.size()); assertTrue(map.containsKey(key)); assertEquals(value, map.get(key));
    }

    @ParameterizedTest @MethodSource("collisions")
    void combinerRejectsExistingMapping(String key, String prior, String value) {
        var calls = new AtomicInteger();
        checkCombiner(Collectors2.groupByUniqueKey(v -> { calls.incrementAndGet(); return key; },
                UnifiedMap::<String, String>newMap), key, prior, value, calls);
    }
    private static <A> void checkCombiner(Collector<String, A, UnifiedMap<String, String>> collector,
            String key, String prior, String value, AtomicInteger calls) {
        A left = collector.supplier().get(); A right = collector.supplier().get();
        var leftMap = collector.finisher().apply(left); var rightMap = collector.finisher().apply(right);
        leftMap.put(key, prior); rightMap.put(key, value);
        var error = assertThrows(IllegalStateException.class, () -> collector.combiner().apply(left, right));
        assertEquals("Key " + key + " already exists in map!", error.getMessage());
        assertEquals(0, calls.get());
        assertEquals(1, leftMap.size()); assertTrue(leftMap.containsKey(key)); assertEquals(value, leftMap.get(key));
        assertEquals(1, rightMap.size()); assertTrue(rightMap.containsKey(key)); assertEquals(value, rightMap.get(key));
    }

    @ParameterizedTest @MethodSource("modes")
    void streamRejectsNullFirstDuplicate(boolean parallel, String key) {
        var source = Arrays.<String>asList(null, "hit"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> stream(source, parallel).collect(Collectors2.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }, UnifiedMap::<String, String>newMap)));
        assertEquals(2, calls.get()); assertEquals(Arrays.asList(null, "hit"), source);
    }

    @ParameterizedTest @MethodSource("modes")
    void streamRetainsNonNullDuplicateRejection(boolean parallel, String key) {
        var source = List.of("a", "b"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> stream(source, parallel).collect(Collectors2.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }, UnifiedMap::<String, String>newMap)));
        assertEquals(2, calls.get()); assertEquals(List.of("a", "b"), source);
    }

    @ParameterizedTest @MethodSource("modes")
    void streamRetainsUniqueNullValue(boolean parallel, String key) {
        var source = Arrays.<String>asList((String) null); var calls = new AtomicInteger();
        var map = stream(source, parallel).collect(Collectors2.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return key;
        }, UnifiedMap::<String, String>newMap));
        assertEquals(1, map.size()); assertTrue(map.containsKey(key)); assertNull(map.get(key));
        assertEquals(1, calls.get()); assertEquals(Arrays.asList((String) null), source);
    }

    @ParameterizedTest @MethodSource("modes")
    void emptyStreamRetainsSupplierMapping(boolean parallel, String key) {
        var calls = new AtomicInteger();
        var map = stream(List.of(), parallel).collect(Collectors2.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return v;
        }, () -> { var target = UnifiedMap.<String, String>newMap(); target.put(key, null); return target; }));
        assertEquals(1, map.size()); assertTrue(map.containsKey(key)); assertNull(map.get(key));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @MethodSource("parallelModes")
    void streamRetainsDistinctNullKey(boolean parallel) {
        var calls = new AtomicInteger(); var source = Arrays.<String>asList(null, "hit");
        var map = stream(source, parallel).collect(Collectors2.groupByUniqueKey(v -> {
            calls.incrementAndGet(); return v;
        }, UnifiedMap::<String, String>newMap));
        assertEquals(2, map.size()); assertTrue(map.containsKey(null)); assertNull(map.get(null));
        assertEquals("hit", map.get("hit")); assertEquals(2, calls.get());
        assertEquals(Arrays.asList(null, "hit"), source);
    }

    @ParameterizedTest @MethodSource("keys")
    void combinerRetainsUnrelatedNullMapping(String key) {
        var calls = new AtomicInteger();
        checkDistinctCombine(Collectors2.groupByUniqueKey(v -> { calls.incrementAndGet(); return key; },
                UnifiedMap::<String, String>newMap), key, calls);
    }
    private static <A> void checkDistinctCombine(Collector<String, A, UnifiedMap<String, String>> collector,
            String key, AtomicInteger calls) {
        A left = collector.supplier().get(); A right = collector.supplier().get();
        var leftMap = collector.finisher().apply(left); var rightMap = collector.finisher().apply(right);
        leftMap.put("other", null); rightMap.put(key, "hit");
        assertSame(left, collector.combiner().apply(left, right));
        assertEquals(2, leftMap.size()); assertTrue(leftMap.containsKey("other")); assertNull(leftMap.get("other"));
        assertEquals("hit", leftMap.get(key)); assertEquals(1, rightMap.size()); assertEquals("hit", rightMap.get(key));
        assertEquals(0, calls.get());
    }
    private static Stream<String> stream(List<String> source, boolean parallel) {
        return parallel ? source.parallelStream() : source.stream();
    }
}

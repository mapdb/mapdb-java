package org.mapdb.nativetests;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class UniqueGroupingParallelNullTest {
    @Test void rejectsNullFirstDuplicateInOneWorker() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        var source = UnifiedSet.<String>newSetWith(null, "hit"); assertNull(source.getFirst());
        try {
            var parallel = source.asParallel(executor, 2); assertActualParallelRoute(parallel);
            var error = assertThrows(RuntimeException.class, () -> parallel
                    .groupByUniqueKey(v -> { calls.incrementAndGet(); return "same"; }));
            assertInstanceOf(IllegalStateException.class, rootCause(error));
            assertEquals("Key same already exists in map!", rootCause(error).getMessage());
            assertEquals(2, calls.get()); assertEquals(UnifiedSet.newSetWith(null, "hit"), source);
        } finally { close(executor); }
    }

    @Test void rejectsConcurrentNullDuplicates() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger(); var source = UnifiedSet.newSetWith("a", "b");
        var threads = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        try {
            var parallel = source.asParallel(executor, 1).collect(v -> (String) null).select(v -> true);
            assertActualParallelRoute(parallel);
            var error = assertThrows(RuntimeException.class, () -> parallel.groupByUniqueKey(v -> {
                calls.incrementAndGet(); threads.add(Thread.currentThread().getName());
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError("both grouping callbacks must reach barrier", e); }
                return "same";
            }));
            assertInstanceOf(IllegalStateException.class, rootCause(error));
            assertEquals("Key same already exists in map!", rootCause(error).getMessage());
            assertEquals(2, calls.get()); assertEquals(2, threads.size()); assertEquals(UnifiedSet.newSetWith("a", "b"), source);
        } finally { close(executor); }
    }

    @Test void preservesExistingNonNullDuplicateRejection() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        try {
            var error = assertThrows(RuntimeException.class, () -> UnifiedSet.newSetWith("a", "b").asParallel(executor, 2)
                    .groupByUniqueKey(v -> { calls.incrementAndGet(); return "same"; }));
            assertInstanceOf(IllegalStateException.class, rootCause(error)); assertEquals(2, calls.get());
        } finally { close(executor); }
    }

    @Test void returnsActualTypedNullValue() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        var source = UnifiedSet.<String>newSetWith((String) null);
        try {
            var map = source.asParallel(executor, 1).groupByUniqueKey(v -> { calls.incrementAndGet(); return "null"; });
            assertEquals(ConcurrentHashMap.class, map.getClass()); assertEquals(1, map.size());
            assertTrue(map.containsKey("null")); assertNull((Object) map.get("null"), "private marker must be decoded");
            String value = map.get("null"); assertNull(value); assertEquals(1, calls.get());
            assertEquals(UnifiedSet.newSetWith((String) null), source);
        } finally { close(executor); }
    }

    record CollisionKey(int id) {
        @Override public int hashCode() { return 7; }
    }
    static Stream<Arguments> layouts() {
        return Stream.of(1, 2, 4).flatMap(workers -> Stream.of(1, 16).map(batch -> Arguments.of(workers, batch)));
    }
    @ParameterizedTest @MethodSource("layouts")
    void retainsDistinctMixedNullResultsAcrossCollisionsAndResize(int workers, int batch) throws Exception {
        var executor = Executors.newFixedThreadPool(workers); var calls = new AtomicInteger();
        var source = UnifiedSet.<String>newSet();
        for (int i = 0; i < 64; i++) source.add("value-" + i);
        var before = UnifiedSet.newSet(source);
        var expected = Collections.synchronizedMap(new HashMap<CollisionKey, String>());
        try {
            var map = source.asParallel(executor, batch).collect(value -> Integer.parseInt(value.substring(6)) % 3 == 0 ? null : value)
                    .select(value -> true).groupByUniqueKey(value -> {
                var key = new CollisionKey(calls.getAndIncrement()); expected.put(key, value); return key;
            });
            assertEquals(ConcurrentHashMap.class, map.getClass()); assertEquals(64, map.size());
            assertEquals(64, calls.get()); assertEquals(64, expected.size());
            expected.forEach((key, value) -> {
                assertTrue(map.containsKey(key));
                assertEquals(value, (Object) map.get(key), "decoded value for " + key);
                String typed = map.get(key); assertEquals(value, typed);
            });
            assertEquals(before, source);
        } finally { close(executor); }
    }

    @Test void preservesNullGeneratedKeyRestriction() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        try {
            var error = assertThrows(RuntimeException.class, () -> UnifiedSet.newSetWith("hit").asParallel(executor, 1)
                    .groupByUniqueKey(v -> { calls.incrementAndGet(); return null; }));
            assertInstanceOf(NullPointerException.class, rootCause(error)); assertEquals(1, calls.get());
        } finally { close(executor); }
    }
    @Test void preservesWorkerCallbackFailureCause() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var failure = new IllegalArgumentException("callback marker");
        var calls = new AtomicInteger();
        try {
            var error = assertThrows(RuntimeException.class, () -> UnifiedSet.newSetWith("hit").asParallel(executor, 1)
                    .groupByUniqueKey(v -> { calls.incrementAndGet(); throw failure; }));
            assertSame(failure, rootCause(error)); assertEquals(1, calls.get());
        } finally { close(executor); }
    }
    @Test void preservesEmptySource() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        try {
            var map = UnifiedSet.<String>newSet().asParallel(executor, 1)
                    .groupByUniqueKey(v -> { calls.incrementAndGet(); return v; });
            assertEquals(ConcurrentHashMap.class, map.getClass()); assertTrue(map.isEmpty()); assertEquals(0, calls.get());
        } finally { close(executor); }
    }
    private static void assertActualParallelRoute(org.mapdb.collections.api.ParallelIterable<?> parallel) throws Exception {
        assertEquals(org.mapdb.collections.impl.lazy.parallel.AbstractParallelIterable.class,
                parallel.getClass().getMethod("groupByUniqueKey", org.mapdb.collections.api.block.function.Function.class)
                        .getDeclaringClass());
    }
    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }
    private static void close(ExecutorService executor) throws Exception {
        executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "grouping workers must terminate");
    }
}

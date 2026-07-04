// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.content.ContentAddress;
import org.mapdb.collections.impl.content.ContentAddressedStore;
import org.mapdb.collections.impl.digest.CollectionDigest;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "C2" content-addressing layer
 * ({@link ContentAddress} token round-trips + {@link ContentAddressedStore}
 * dedupe / memoisation), including the A2↔C2 tie: equal-content collections
 * (equal {@link CollectionDigest}) collapse to one stored instance / one compute.
 */
public class ContentAddressingTest
{
    private static ImmutableSortedMap<Integer, Integer> map(int[] k, int[] v)
    {
        return ImmutableSortedMap.fromSorted(k, v);
    }

    // ---- ContentAddress token --------------------------------------------

    @Test
    public void tokenFormatAndZeroPad()
    {
        assertEquals("mdbca1|0000000000000000", ContentAddress.of(0L).token());
        assertEquals("mdbca1|00000000000000ff", ContentAddress.of(0xFFL).token());
        // -1 is the all-ones u64 pattern.
        assertEquals("mdbca1|ffffffffffffffff", ContentAddress.of(-1L).token());
    }

    @Test
    public void tokenRoundTripsIncludingHighBit()
    {
        for (long v : new long[] {0L, 1L, 0xFFL, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789abcdefL})
        {
            ContentAddress a = ContentAddress.of(v);
            ContentAddress b = ContentAddress.parse(a.token());
            assertEquals(a, b);
            assertEquals(v, b.value());
        }
    }

    @Test
    public void parseRejectsMalformed()
    {
        assertThrows(IllegalArgumentException.class, () -> ContentAddress.parse("xxx|0000000000000000"));
        assertThrows(IllegalArgumentException.class, () -> ContentAddress.parse("mdbca1|00")); // too short
        assertThrows(IllegalArgumentException.class, () -> ContentAddress.parse("mdbca1|000000000000000g"));
        assertThrows(IllegalArgumentException.class, () -> ContentAddress.parse("mdbca1|00000000000000FF")); // uppercase
        assertThrows(IllegalArgumentException.class, () -> ContentAddress.parse(null));
    }

    @Test
    public void addressEquality()
    {
        assertEquals(ContentAddress.of(42L), ContentAddress.of(42L));
        assertEquals(ContentAddress.of(42L).hashCode(), ContentAddress.of(42L).hashCode());
        assertNotEquals(ContentAddress.of(42L), ContentAddress.of(43L));
    }

    // ---- Store: dedupe ----------------------------------------------------

    @Test
    public void internDedupesEqualAddresses()
    {
        ContentAddressedStore<String> store = new ContentAddressedStore<>();
        String first = store.intern(7L, "alpha");
        assertEquals("alpha", first);
        assertEquals(0, store.hits());
        assertEquals(1, store.misses());

        // A second value under the same address returns the FIRST instance.
        String second = store.intern(7L, new String("alpha")); // distinct instance on purpose
        assertSame(first, second);
        assertEquals(1, store.hits());
        assertEquals(1, store.misses());
        assertEquals(1, store.distinctCount());
    }

    @Test
    public void getAndContains()
    {
        ContentAddressedStore<String> store = new ContentAddressedStore<>();
        assertFalse(store.contains(1L));
        assertTrue(store.get(1L).isEmpty());
        store.intern(1L, "x");
        assertTrue(store.contains(1L));
        assertEquals("x", store.get(1L).get());
    }

    @Test
    public void internRejectsNull()
    {
        ContentAddressedStore<String> store = new ContentAddressedStore<>();
        assertThrows(NullPointerException.class, () -> store.intern(1L, null));
    }

    @Test
    public void internRejectsNullEvenOnHit()
    {
        // The null policy must be identical on hit and miss — a null candidate is
        // rejected even when the address is already populated.
        ContentAddressedStore<String> store = new ContentAddressedStore<>();
        store.intern(7L, "first");
        assertThrows(NullPointerException.class, () -> store.intern(7L, null));
    }

    @Test
    public void failedOperationDoesNotCountAsMissOrPopulate()
    {
        ContentAddressedStore<String> s1 = new ContentAddressedStore<>();
        assertThrows(NullPointerException.class, () -> s1.intern(1L, null));
        assertEquals(0, s1.misses());
        assertEquals(0, s1.distinctCount());

        ContentAddressedStore<String> s2 = new ContentAddressedStore<>();
        assertThrows(NullPointerException.class, () -> s2.computeIfAbsent(1L, a -> null));
        assertEquals(0, s2.misses());
        assertFalse(s2.contains(1L));

        ContentAddressedStore<String> s3 = new ContentAddressedStore<>();
        assertThrows(NullPointerException.class, () -> s3.memoize("x", str -> 1L, str -> null));
        assertEquals(0, s3.misses());
        assertFalse(s3.contains(1L));
    }

    // ---- Store: memoisation ----------------------------------------------

    @Test
    public void computeIfAbsentRunsOncePerAddress()
    {
        ContentAddressedStore<Long> store = new ContentAddressedStore<>();
        AtomicInteger calls = new AtomicInteger();
        Long a = store.computeIfAbsent(5L, addr -> {
            calls.incrementAndGet();
            return addr * 10;
        });
        Long b = store.computeIfAbsent(5L, addr -> {
            calls.incrementAndGet();
            return addr * 10;
        });
        assertEquals(50L, a);
        assertSame(a, b);
        assertEquals(1, calls.get());
        assertEquals(1, store.hits());
        assertEquals(1, store.misses());
    }

    // ---- A2 <-> C2 tie: content dedupe / stage memoisation ---------------

    @Test
    public void equalContentMapsMemoiseOnce()
    {
        ContentAddressedStore<Integer> store = new ContentAddressedStore<>();
        AtomicInteger computes = new AtomicInteger();
        // A "stage": sum the values of a map.
        java.util.function.Function<ImmutableSortedMap<Integer, Integer>, Integer> sumStage = m -> {
            computes.incrementAndGet();
            int s = 0;
            for (int v : m.values())
            {
                s += v;
            }
            return s;
        };

        ImmutableSortedMap<Integer, Integer> m1 = map(new int[] {1, 2, 3}, new int[] {10, 20, 30});
        // Same logical content, built independently.
        ImmutableSortedMap<Integer, Integer> m2 = map(new int[] {1, 2, 3}, new int[] {10, 20, 30});
        // Different content.
        ImmutableSortedMap<Integer, Integer> m3 = map(new int[] {1, 2, 3}, new int[] {10, 20, 99});

        int r1 = store.memoize(m1, CollectionDigest::ofSortedMap, sumStage);
        int r2 = store.memoize(m2, CollectionDigest::ofSortedMap, sumStage);
        int r3 = store.memoize(m3, CollectionDigest::ofSortedMap, sumStage);

        assertEquals(60, r1);
        assertEquals(60, r2);
        assertEquals(129, r3);
        // m1 and m2 share content -> computed once; m3 differs -> a second compute.
        assertEquals(2, computes.get());
        assertEquals(1, store.hits());   // m2 hit
        assertEquals(2, store.misses()); // m1, m3 miss
        assertEquals(2, store.distinctCount());
    }

    @Test
    public void dedupeEqualContentCollectionsToOneInstance()
    {
        ContentAddressedStore<ImmutableSortedMap<Integer, Integer>> store = new ContentAddressedStore<>();
        ImmutableSortedMap<Integer, Integer> m1 = map(new int[] {5, 6}, new int[] {50, 60});
        ImmutableSortedMap<Integer, Integer> m2 = map(new int[] {5, 6}, new int[] {50, 60});
        assertEquals(CollectionDigest.ofSortedMap(m1), CollectionDigest.ofSortedMap(m2));

        ImmutableSortedMap<Integer, Integer> a = store.intern(CollectionDigest.ofSortedMap(m1), m1);
        ImmutableSortedMap<Integer, Integer> b = store.intern(CollectionDigest.ofSortedMap(m2), m2);
        assertSame(m1, a);
        assertSame(m1, b); // m2 collapsed to the canonical m1 instance
        assertEquals(1, store.distinctCount());
    }
}

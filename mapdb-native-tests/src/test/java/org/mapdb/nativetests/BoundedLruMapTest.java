// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.bounded.BoundedLruMap;
import org.mapdb.collections.impl.bounded.EvictionCause;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native (port-specific) tests for the boxed {@link BoundedLruMap} carve-out
 * (spec {@code features/bounded-lru.md}). The shared cross-language JSON suite
 * ({@code 16-bounded-lru}) covers the observable eviction-log / LRU-order
 * oracle; these tests pin the contract details the suite leaves to a per-port
 * native obligation, plus the carve-out specifics:
 *
 * <ul>
 *   <li><b>Evict-before-insert timing</b> via a read-only probe callback (the
 *       victim is gone and the new key is not yet resident when the callback
 *       runs) — the spec demotes this to a native-test obligation because a pure
 *       recording callback cannot observe it.</li>
 *   <li><b>Unsigned-long</b> saturation, the {@code NEVER} sentinel, and
 *       inclusive expiry past {@code 2^63} (Java prints {@code long} signed, so
 *       a naive signed comparison would diverge).</li>
 *   <li>Capacity {@code 0}/{@code 1} edges, recency-refresh set, callback
 *       SIZE/EXPIRED ordering, snapshot independence, tie-free determinism.</li>
 * </ul>
 */
class BoundedLruMapTest
{
    /** A recording eviction callback: appends {@code [key, value, cause]} per invocation. */
    private static final class Recorder<K, V> implements BoundedLruMap.EvictionListener<K, V>
    {
        final List<Object[]> log = new ArrayList<>();

        @Override
        public void onEvict(K key, V value, EvictionCause cause)
        {
            this.log.add(new Object[] {key, value, cause});
        }

        void assertLog(Object[]... expected)
        {
            assertEquals(expected.length, this.log.size(), "eviction-log length");
            for (int i = 0; i < expected.length; i++)
            {
                assertEquals(expected[i][0], this.log.get(i)[0], "log[" + i + "].key");
                assertEquals(expected[i][1], this.log.get(i)[1], "log[" + i + "].value");
                assertEquals(expected[i][2], this.log.get(i)[2], "log[" + i + "].cause");
            }
        }
    }

    private static Object[] evt(int k, int v, EvictionCause c)
    {
        return new Object[] {k, v, c};
    }

    private static List<Integer> keys(BoundedLruMap<Integer, Integer> m)
    {
        return m.keys();
    }

    // ---- core SIZE eviction: victim is the LRU, contents in LRU order -----

    @Test
    void evictBasicVictimIsLru()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        assertEquals(Optional.empty(), m.put(1, 10));
        assertEquals(Optional.empty(), m.put(2, 20));
        assertEquals(Optional.empty(), m.put(3, 30)); // evicts 1 (LRU)
        assertEquals(List.of(2, 3), keys(m));
        assertEquals(List.of(20, 30), m.values());
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
        assertEquals(2, m.size());
        assertEquals(2, m.capacity());
    }

    @Test
    void getHitRefreshesRecency()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(Optional.of(10), m.get(1)); // 1 -> MRU, 2 is LRU
        m.put(3, 30); // evicts 2
        assertEquals(List.of(1, 3), keys(m));
        rec.assertLog(evt(2, 20, EvictionCause.SIZE));
    }

    @Test
    void getOrDefaultHitRefreshesMissDoesNot()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(10, m.getOrDefault(1, -1)); // hit: 1 -> MRU
        assertEquals(-1, m.getOrDefault(99, -1)); // miss: no insert, no refresh
        assertEquals(2, m.size());
        assertFalse(m.containsKey(99));
        m.put(3, 30); // evicts 2 (LRU, since the hit refreshed 1)
        assertEquals(List.of(1, 3), keys(m));
        rec.assertLog(evt(2, 20, EvictionCause.SIZE));
    }

    @Test
    void containsKeyDoesNotRefresh()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertTrue(m.containsKey(1)); // must NOT refresh 1
        m.put(3, 30); // evicts 1 (still LRU)
        assertEquals(List.of(2, 3), keys(m));
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
    }

    @Test
    void missDoesNotRefreshOrInsert()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(Optional.empty(), m.get(99));      // miss
        assertEquals(-1, m.getOrDefault(98, -1));        // miss: no insert
        assertEquals(2, m.size());
        m.put(3, 30); // 1 is still the LRU victim
        assertEquals(List.of(2, 3), keys(m));
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
    }

    // ---- update-at-capacity never evicts ---------------------------------

    @Test
    void updateAtCapacityNeverEvicts()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(Optional.of(10), m.put(1, 11)); // update: no evict, 1 -> MRU
        assertTrue(rec.log.isEmpty());
        assertEquals(List.of(2, 1), keys(m));
        m.put(3, 30); // now evicts 2
        assertEquals(List.of(1, 3), keys(m));
        rec.assertLog(evt(2, 20, EvictionCause.SIZE));
    }

    // ---- evict-before-insert timing (the native probe obligation) --------

    @Test
    void evictBeforeInsertTimingProbe()
    {
        // The callback inspects the map READ-ONLY while it runs: the victim must
        // already be gone and the new key must NOT yet be resident. A
        // LinkedHashMap removeEldestEntry (insert-then-evict) would fail this.
        List<Boolean> victimGone = new ArrayList<>();
        List<Boolean> newKeyAbsent = new ArrayList<>();
        List<Integer> sizeDuringCallback = new ArrayList<>();
        final BoundedLruMap<Integer, Integer>[] holder = new BoundedLruMap[1];
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder()
                        .maxSize(2)
                        .onEvict((k, v, cause) ->
                        {
                            // k is the victim (1); 3 is the incoming new key.
                            victimGone.add(!holder[0].containsKey(k));
                            newKeyAbsent.add(!holder[0].containsKey(3));
                            sizeDuringCallback.add(holder[0].size());
                        })
                        .build();
        holder[0] = m;
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30); // triggers SIZE eviction of 1
        assertEquals(List.of(true), victimGone, "victim must be gone when callback runs");
        assertEquals(List.of(true), newKeyAbsent, "new key must NOT be resident when callback runs");
        assertEquals(List.of(1), sizeDuringCallback, "size during callback = 1 (victim removed, new not yet inserted)");
        assertEquals(List.of(2, 3), keys(m));
    }

    // ---- capacity 0 / 1 / negative edges ---------------------------------

    @Test
    void capacityZeroDropsEverythingNoCallback()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(0).onEvict(rec).build();
        assertEquals(Optional.empty(), m.put(1, 10));
        assertEquals(Optional.empty(), m.put(2, 20));
        assertEquals(Optional.empty(), m.put(3, 30));
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertEquals(Optional.empty(), m.get(1));
        assertTrue(rec.log.isEmpty()); // nothing was ever resident to evict
        assertEquals(List.of(), keys(m));
    }

    @Test
    void capacityNegativeBehavesLikeZero()
    {
        // n <= 0 drops every insert (n == 0 path: maxSize >= 1 is never true).
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(-5).onEvict(rec).build();
        assertEquals(Optional.empty(), m.put(1, 10));
        assertEquals(Optional.empty(), m.put(2, 20));
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertTrue(rec.log.isEmpty());
    }

    @Test
    void capacityOneEvictsThenInsertsUpdateDoesNot()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(1).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20); // evicts 1
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
        assertEquals(List.of(2), keys(m));
        assertEquals(Optional.of(20), m.put(2, 21)); // update: no new log entry
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
        assertEquals(List.of(2), keys(m));
    }

    // ---- remove / clear fire no callback; remove-reinsert recency --------

    @Test
    void removeFiresNoCallback()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(3).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(Optional.of(20), m.remove(2));
        assertEquals(Optional.empty(), m.remove(99));
        assertTrue(rec.log.isEmpty());
        assertEquals(List.of(1), keys(m));
    }

    @Test
    void clearFiresNoCallback()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(3).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30);
        m.clear();
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertTrue(rec.log.isEmpty());
    }

    @Test
    void removeThenReinsertGetsFreshRecency()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(3).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30);
        m.remove(1);
        m.put(1, 11); // fresh insert: 1 becomes MRU, NOT stale
        m.put(4, 40); // evicts 2 (the new LRU), 1 survives as MRU-ish
        assertEquals(List.of(3, 1, 4), keys(m));
        rec.assertLog(evt(2, 20, EvictionCause.SIZE));
    }

    // ---- iteration is read-only snapshot, independent, no refresh/evict --

    @Test
    void snapshotIsIndependentAndDoesNotRefresh()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        List<Integer> snap = keys(m); // [1, 2] LRU order
        assertEquals(List.of(1, 2), snap);
        // Mutating the map does not change the already-returned snapshot.
        m.put(3, 30); // evicts 1 (iteration did not refresh it)
        assertEquals(List.of(1, 2), snap, "snapshot is an independent copy");
        assertEquals(List.of(2, 3), keys(m));
        rec.assertLog(evt(1, 10, EvictionCause.SIZE));
    }

    @Test
    void entriesSnapshotParallelToKeysAndValues()
    {
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(3).build();
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30);
        m.get(1); // LRU order -> [2, 3, 1]
        List<Integer> ks = m.keys();
        List<Integer> vs = m.values();
        List<Map.Entry<Integer, Integer>> es = m.entries();
        assertEquals(List.of(2, 3, 1), ks);
        assertEquals(List.of(20, 30, 10), vs);
        assertEquals(ks.size(), es.size());
        for (int i = 0; i < es.size(); i++)
        {
            assertEquals(ks.get(i), es.get(i).getKey(), "entry key parallel to keys");
            assertEquals(vs.get(i), es.get(i).getValue(), "entry value parallel to values");
        }
    }

    // ---- recency is per-op useSeq, NOT the now clock ---------------------

    @Test
    void recencyIsUseSeqNotNow()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).ttl(100).onEvict(rec).build();
        m.putAt(1, 10, 5);
        m.putAt(2, 20, 5); // both written at the SAME now=5
        m.get(1);          // useSeq refreshes 1 -> MRU; 2 is LRU
        m.putAt(3, 30, 5); // evicts 2 (recency is per-op, not now)
        assertEquals(List.of(1, 3), keys(m));
        rec.assertLog(evt(2, 20, EvictionCause.SIZE));
    }

    // ---- EXPIRED: inclusive, ascending expireAt then ascending lastUse ---

    @Test
    void expireBasicInclusiveAndOrdered()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(10).onEvict(rec).build();
        m.putAt(1, 10, 0); // expireAt = 10
        m.putAt(2, 20, 0); // expireAt = 10
        m.putAt(3, 30, 5); // expireAt = 15
        assertEquals(2, m.expireEntries(10)); // 1,2 expire (<=10 inclusive); 3 survives
        // ascending expireAt (both 10) then ascending lastUse (1 < 2)
        rec.assertLog(evt(1, 10, EvictionCause.EXPIRED), evt(2, 20, EvictionCause.EXPIRED));
        assertEquals(List.of(3), keys(m));
    }

    @Test
    void expireTiebreakByLastUse()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(10).onEvict(rec).build();
        m.putAt(1, 10, 0);
        m.putAt(2, 20, 0);
        m.putAt(3, 30, 0);
        m.putAt(4, 40, 0); // all share expireAt = 10
        m.get(3);
        m.get(1);
        m.get(4);
        m.get(2); // lastUse order now: 3 < 1 < 4 < 2
        assertEquals(4, m.expireEntries(10));
        rec.assertLog(
                evt(3, 30, EvictionCause.EXPIRED),
                evt(1, 10, EvictionCause.EXPIRED),
                evt(4, 40, EvictionCause.EXPIRED),
                evt(2, 20, EvictionCause.EXPIRED));
        assertTrue(m.isEmpty());
    }

    @Test
    void noImplicitExpiry()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(1).onEvict(rec).build();
        m.putAt(1, 10, 0); // expireAt = 1
        // A put/get at a much later logical time does NOT opportunistically expire 1.
        m.putAt(2, 20, 1000);
        assertEquals(Optional.of(10), m.get(1)); // still present
        assertTrue(rec.log.isEmpty());
        assertEquals(2, m.size());
    }

    @Test
    void ttlZeroBoundary()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(0).onEvict(rec).build();
        m.putAt(1, 10, 5); // expireAt = 5
        assertEquals(0, m.expireEntries(4)); // 5 > 4: nothing
        assertEquals(1, m.expireEntries(5)); // 5 <= 5 inclusive: 1 expires
        rec.assertLog(evt(1, 10, EvictionCause.EXPIRED));
    }

    @Test
    void noTtlExpiresNothing()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).onEvict(rec).build();
        m.put(1, 10);
        m.put(2, 20);
        assertEquals(0, m.expireEntries(-1L)); // now = huge unsigned: still nothing
        assertEquals(2, m.size());
        assertTrue(rec.log.isEmpty());
    }

    @Test
    void updateResetsExpireAtValueAtEvictionIsUpdated()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(10).onEvict(rec).build();
        m.putAt(1, 10, 0);  // expireAt = 10
        m.putAt(1, 11, 5);  // update: value 11, expireAt = 15, no SIZE eviction
        assertEquals(0, m.expireEntries(10)); // 15 > 10: survives
        assertEquals(1, m.expireEntries(15)); // 15 <= 15: expires, value-at-eviction = 11
        rec.assertLog(evt(1, 11, EvictionCause.EXPIRED));
    }

    // ---- unsigned-long saturation and the NEVER sentinel -----------------

    @Test
    void ttlSaturatesNeverExpires()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        // now + ttl overflows u64 -> saturates to NEVER (0xFFFF...FFFF), so the
        // entry is never expired by any in-range now (would wrap-then-expire with
        // signed/naive arithmetic).
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder()
                        .maxSize(10).ttl(-1L) /* u64 max */.onEvict(rec).build();
        m.putAt(1, 10, 100); // 100 + u64max saturates -> NEVER
        assertEquals(0, m.expireEntries(-2L)); // a very large in-range now: not expired
        assertEquals(0, m.expireEntries(1000));
        assertEquals(1, m.size());
        assertTrue(rec.log.isEmpty());
    }

    @Test
    void unsignedExpiryComparisonPast2Pow63()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        // expireAt just above Long.MAX_VALUE (negative as a signed long). A naive
        // signed compare (expireAt <= now) would treat it as already-expired.
        long ttl = Long.MIN_VALUE; // == 2^63 unsigned
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(10).ttl(ttl).onEvict(rec).build();
        m.putAt(1, 10, 0); // expireAt = 2^63 (Long.MIN_VALUE bits)
        // now = 5 (small positive). Unsigned: 2^63 > 5, so NOT expired.
        assertEquals(0, m.expireEntries(5));
        assertEquals(1, m.size());
        // now = 2^63 exactly: inclusive, expires.
        assertEquals(1, m.expireEntries(Long.MIN_VALUE));
        rec.assertLog(evt(1, 10, EvictionCause.EXPIRED));
    }

    @Test
    void expireThenSizeInteraction()
    {
        Recorder<Integer, Integer> rec = new Recorder<>();
        BoundedLruMap<Integer, Integer> m =
                BoundedLruMap.<Integer, Integer>builder().maxSize(2).ttl(10).onEvict(rec).build();
        m.putAt(1, 10, 0); // expireAt 10
        m.putAt(2, 20, 0); // expireAt 10
        assertEquals(2, m.expireEntries(10)); // both expire -> map empty, below capacity
        rec.log.clear();
        m.putAt(3, 30, 100); // map was empty: no SIZE eviction
        assertTrue(rec.log.isEmpty());
        assertEquals(List.of(3), keys(m));
    }

    // ---- no-callback configuration still works ---------------------------

    @Test
    void worksWithoutCallback()
    {
        BoundedLruMap<Integer, Integer> m = BoundedLruMap.withMaxSize(2);
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30); // evicts 1, no callback installed -> no crash
        assertEquals(List.of(2, 3), keys(m));
        assertEquals(2, m.capacity());
    }

    @Test
    void serializedCauseNames()
    {
        assertEquals("size", EvictionCause.SIZE.serializedName());
        assertEquals("expired", EvictionCause.EXPIRED.serializedName());
    }
}

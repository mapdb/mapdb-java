// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.SpaceSaving;
import org.mapdb.collections.impl.SpaceSaving.SSEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link SpaceSaving} (spec {@code features/count-min.md}),
 * mirroring the Rust reference port's {@code space_saving.rs} test module.
 * count/error are u64-in-long (unsigned bits): {@code -1L} is the saturating
 * ceiling {@code u64::MAX}. Eviction tie-break is by unsigned count then signed
 * i32 item; canonical order is count DESC (unsigned), signed item ASC.
 */
class SpaceSavingTest
{
    private static final long U64_MAX = -1L;

    private static List<SSEntry> set(int[]... triples)
    {
        SSEntry[] out = new SSEntry[triples.length];
        for (int i = 0; i < triples.length; i++)
        {
            out[i] = new SSEntry(triples[i][0], triples[i][1], triples[i][2]);
        }
        return Arrays.asList(out);
    }

    @Test
    void admitUnderCapacityNoEviction()
    {
        SpaceSaving s = SpaceSaving.withCapacity(3);
        s.addOne(7);
        s.addOne(7);
        s.addOne(-1);
        assertEquals(2, s.size());
        assertEquals(2L, s.count(7));
        assertEquals(0L, s.error(7));
        assertEquals(1L, s.count(-1));
        // 7 (count 2) before -1 (count 1); both error 0.
        assertEquals(set(new int[] {7, 2, 0}, new int[] {-1, 1, 0}), s.monitoredSet());
        assertEquals(set(new int[] {7, 2, 0}), s.topK(1));
    }

    @Test
    void evictMinTiebreakSmallerSignedItem()
    {
        // m=2: add(1),add(2) -> both count 1 (full); add(3) evicts the min-count
        // item; tie -> smallest signed item = 1 evicted. 3 admitted count=2 error=1.
        SpaceSaving s = SpaceSaving.withCapacity(2);
        s.addOne(1);
        s.addOne(2);
        s.addOne(3);
        assertEquals(set(new int[] {3, 2, 1}, new int[] {2, 1, 0}), s.monitoredSet());
        assertEquals(0L, s.count(1)); // evicted -> 0
        assertFalse(s.isMonitored(1));
        assertEquals(2L, s.count(3));
        assertEquals(1L, s.error(3));
    }

    @Test
    void evictTiebreakNegativeBeatsPositive()
    {
        // Monitored -5 (count 1) and 2 (count 1); add new -> -5 < 2 (SIGNED) so
        // -5 evicted (an unsigned comparison would evict 2).
        SpaceSaving s = SpaceSaving.withCapacity(2);
        s.addOne(-5);
        s.addOne(2);
        s.addOne(9);
        assertFalse(s.isMonitored(-5)); // smaller signed item evicted
        assertTrue(s.isMonitored(2));
        assertTrue(s.isMonitored(9));
        assertEquals(2L, s.count(9));
        assertEquals(1L, s.error(9));
    }

    @Test
    void alreadyMonitoredErrorNeverChanges()
    {
        SpaceSaving s = SpaceSaving.withCapacity(2);
        s.addOne(1);
        s.addOne(2);
        s.addOne(3); // evicts 1; 3 -> count 2, error 1
        assertEquals(1L, s.error(3));
        s.add(3, 100L); // re-add of a monitored item: error unchanged.
        assertEquals(102L, s.count(3));
        assertEquals(1L, s.error(3));
    }

    @Test
    void admittedWithRoomHasZeroError()
    {
        SpaceSaving s = SpaceSaving.withCapacity(5);
        s.add(7, 9L);
        assertEquals(0L, s.error(7));
        assertEquals(9L, s.count(7));
    }

    @Test
    void countZeroIsNoop()
    {
        SpaceSaving s = SpaceSaving.withCapacity(1);
        s.addOne(1);
        s.add(2, 0L); // must NOT evict 1.
        assertTrue(s.isMonitored(1));
        assertFalse(s.isMonitored(2));
        assertEquals(1, s.size());
    }

    @Test
    void emptySummary()
    {
        SpaceSaving s = SpaceSaving.withCapacity(3);
        assertEquals(0, s.size());
        assertEquals(3, s.capacity());
        assertTrue(s.monitoredSet().isEmpty());
        assertEquals(0L, s.count(7));
        assertEquals(0L, s.error(7));
        assertTrue(s.topK(3).isEmpty());
    }

    @Test
    void topKCanonicalOrderAndBounds()
    {
        SpaceSaving s = SpaceSaving.withCapacity(10);
        s.add(1, 5L);
        s.add(2, 3L);
        s.add(3, 3L); // tie at count 3 -> 2 before 3 (signed asc)
        s.add(4, 1L);
        List<SSEntry> full =
                set(new int[] {1, 5, 0}, new int[] {2, 3, 0}, new int[] {3, 3, 0}, new int[] {4, 1, 0});
        assertEquals(full, s.monitoredSet());
        assertEquals(set(new int[] {1, 5, 0}), s.topK(1));
        assertEquals(set(new int[] {1, 5, 0}, new int[] {2, 3, 0}), s.topK(2));
        assertEquals(full, s.topK(4));
        assertEquals(full, s.topK(99)); // k > size -> all, no padding
        assertTrue(s.topK(0).isEmpty());
    }

    @Test
    void countUnmonitoredIsZero()
    {
        SpaceSaving s = SpaceSaving.withCapacity(1);
        s.addOne(1);
        s.addOne(2); // evicts 1
        assertEquals(0L, s.count(1));
        assertFalse(s.isMonitored(1));
    }

    @Test
    void overflowSaturates()
    {
        SpaceSaving s = SpaceSaving.withCapacity(1);
        s.add(7, U64_MAX);
        s.add(7, U64_MAX);
        assertEquals(U64_MAX, s.count(7));
        assertEquals("18446744073709551615", Long.toUnsignedString(s.count(7)));
    }

    @Test
    void orderDependence()
    {
        // capacity 2. Sequence A: 1,1,2,3.
        SpaceSaving a = SpaceSaving.withCapacity(2);
        for (int it : new int[] {1, 1, 2, 3})
        {
            a.addOne(it);
        }
        // A: 1->1, 1->2, 2 admitted->1, 3 evicts min: 2(count1) vs 1(count2) ->
        // victim 2; 3 -> count2 error1. Set {1:2, 3:2e1}.
        assertEquals(set(new int[] {1, 2, 0}, new int[] {3, 2, 1}), a.monitoredSet());
    }

    @Test
    void errorFloorUnchangedAcrossEvictions()
    {
        SpaceSaving s = SpaceSaving.withCapacity(2);
        s.addOne(1); // {1:1}
        s.addOne(2); // {1:1, 2:1} full
        s.addOne(3); // evict 1 (tie, smallest signed): 3-> count2 error1
        assertEquals(1L, s.error(3));
        s.addOne(3); // monitored re-add: count3 error1 (UNCHANGED)
        assertEquals(3L, s.count(3));
        assertEquals(1L, s.error(3));
        s.addOne(4); // full {2:1, 3:3}; evict 2 (count1): 4-> count2 error1
        assertEquals(1L, s.error(4));
        assertEquals(2L, s.count(4));
        assertEquals(1L, s.error(3)); // 3 still unchanged
    }

    @Test
    void mZeroTraps()
    {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SpaceSaving.withCapacity(0));
        assertTrue(ex.getMessage().contains("capacity m must be non-zero"));
    }

    @Test
    void topKRejectsNegativeK()
    {
        SpaceSaving s = SpaceSaving.withCapacity(2);
        s.addOne(1);
        assertThrows(IllegalArgumentException.class, () -> s.topK(-1));
    }
}

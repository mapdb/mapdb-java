// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.navigable.NavigableTreeMap;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the boxed-tree NavigableMap carve-out
 * ({@link NavigableTreeMap}). These pin the per-port obligations the shared
 * scenario suite cannot reach: inclusive-vs-strict nav with absent ends,
 * poll-on-empty/single, {@code open(1,2)} = empty (membership = contains),
 * {@code removeRange} no-op = 0, descending order, i32 MIN/MAX + negatives, and
 * subMap snapshot independence WITH reverse-comparator preservation.
 */
public class NavigableTreeMapTest
{
    private static NavigableTreeMap<Integer, Integer> mapOf(int... keys)
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        for (int k : keys)
        {
            m.put(k, k * 10);
        }
        return m;
    }

    private static List<Integer> keysOf(List<Map.Entry<Integer, Integer>> entries)
    {
        return entries.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList());
    }

    @Test
    public void floorCeilingLowerHigherStrictness()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30);
        assertEquals(20, m.floorKey(25));
        assertEquals(30, m.ceilingKey(25));
        assertEquals(10, m.floorKey(10));   // inclusive
        assertNull(m.lowerKey(10));         // strict, nothing below
        assertNull(m.higherKey(30));        // strict, nothing above
        assertEquals(10, m.ceilingKey(5));
        assertEquals(20, m.lowerKey(25));
        assertEquals(30, m.higherKey(25));
        assertEquals(10, m.firstKey());
        assertEquals(30, m.lastKey());
        // entry forms carry value = key*10.
        assertEquals(20, m.floorEntry(25).get().getKey());
        assertEquals(200, m.floorEntry(25).get().getValue());
        assertEquals(30, m.ceilingEntry(25).get().getKey());
        assertEquals(10, m.firstEntry().get().getKey());
        assertEquals(300, m.lastEntry().get().getValue());
    }

    @Test
    public void navOnEmptyIsAllAbsent()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        assertNull(m.floorKey(5));
        assertNull(m.ceilingKey(5));
        assertNull(m.lowerKey(5));
        assertNull(m.higherKey(5));
        assertNull(m.firstKey());
        assertNull(m.lastKey());
        assertTrue(m.floorEntry(5).isEmpty());
        assertTrue(m.firstEntry().isEmpty());
        assertTrue(m.lastEntry().isEmpty());
    }

    @Test
    public void navSignedExtremes()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, m.floorKey(Integer.MIN_VALUE));
        assertNull(m.lowerKey(Integer.MIN_VALUE));
        assertEquals(0, m.higherKey(-1));
        assertEquals(Integer.MAX_VALUE, m.ceilingKey(Integer.MAX_VALUE));
        assertNull(m.higherKey(Integer.MAX_VALUE));
        assertEquals(List.of(Integer.MAX_VALUE, 1, 0, -1, Integer.MIN_VALUE), m.descendingKeys());
    }

    @Test
    public void pollFirstLastThenEmptyDoesNotTrap()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30);
        assertEquals(Map.entry(10, 100), m.pollFirstEntry().get());
        assertEquals(Map.entry(30, 300), m.pollLastEntry().get());
        assertEquals(1, m.size());
        assertEquals(Map.entry(20, 200), m.pollFirstEntry().get());
        // now empty: returns empty, does not trap.
        assertTrue(m.pollFirstEntry().isEmpty());
        assertTrue(m.pollLastEntry().isEmpty());
    }

    @Test
    public void pollSingleThenEmpty()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        m.put(7, 700);
        assertEquals(Map.entry(7, 700), m.pollFirstEntry().get());
        assertTrue(m.pollFirstEntry().isEmpty());
        assertTrue(m.isEmpty());
    }

    @Test
    public void rangeAscendingAndDescending()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        assertEquals(List.of(30, 40, 50, 60), m.rangeKeys(Range.closedOpen(30, 70)));
        assertEquals(List.of(60, 50, 40, 30), m.descendingRangeKeys(Range.closedOpen(30, 70)));
        assertEquals(List.of(40, 50, 60, 70), m.rangeKeys(Range.openClosed(30, 70)));
        assertEquals(List.of(80, 90, 100), m.rangeKeys(Range.atLeast(80)));
        assertEquals(List.of(30, 40), keysOf(m.rangeEntries(Range.closedOpen(30, 50))));
        assertEquals(List.of(100, 90, 80, 70, 60, 50, 40, 30, 20, 10), m.descendingKeys());
    }

    @Test
    public void rangeEntriesTolerateNullValues()
    {
        // The generic value surface allows null values; range-entry snapshots
        // must not reject them (Map.entry would NPE — use a null-tolerant entry).
        NavigableTreeMap<Integer, String> m = NavigableTreeMap.newMap();
        m.put(10, "a");
        m.put(20, null);
        m.put(30, "c");
        List<Map.Entry<Integer, String>> entries = m.rangeEntries(Range.closed(10, 30));
        assertEquals(3, entries.size());
        assertEquals(Integer.valueOf(20), entries.get(1).getKey());
        assertNull(entries.get(1).getValue());
        // descending forms too
        assertEquals(3, m.descendingRangeEntries(Range.closed(10, 30)).size());
        assertEquals(3, m.descendingEntries().size());
    }

    @Test
    public void rangeOpenNoIntegerIsEmptyNotCutEmpty()
    {
        // open(1,2) over Integer matches NOTHING (membership = contains) but the
        // range itself is not cut-empty.
        NavigableTreeMap<Integer, Integer> m = mapOf(1, 2);
        assertEquals(List.of(), m.rangeKeys(Range.open(1, 2)));
        assertEquals(List.of(), m.descendingRangeKeys(Range.open(1, 2)));
        assertFalse(Range.open(1, 2).isEmpty());
        assertEquals(0, m.removeRange(Range.open(1, 2)));
        assertEquals(2, m.size());
    }

    @Test
    public void removeRangeCountAndNoop()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        assertEquals(4, m.removeRange(Range.closedOpen(30, 70)));
        assertEquals(0, m.removeRange(Range.closedOpen(30, 70))); // no-op
        assertEquals(List.of(10, 20, 70, 80, 90, 100), m.rangeKeys(Range.all()));
        // removeRange kept the EC view in sync.
        assertEquals("[10, 20, 70, 80, 90, 100]", m.ecMap().keysView().makeString("[", ", ", "]"));
    }

    @Test
    public void subMapIndependence()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50);
        NavigableTreeMap<Integer, Integer> snap = m.subMap(Range.closed(20, 40));
        assertEquals(List.of(20, 30, 40), snap.rangeKeys(Range.all()));
        // Mutate snapshot -> original unchanged.
        snap.put(99, 990);
        snap.remove(20);
        assertTrue(m.containsKey(20));
        assertFalse(m.containsKey(99));
        // Mutate original -> snapshot unchanged.
        m.remove(30);
        assertTrue(snap.containsKey(30));
    }

    @Test
    public void subMapPreservesReverseComparator()
    {
        // subMap must keep the source ordering (reverse), not reset to natural.
        Comparator<Integer> reverse = Comparator.reverseOrder();
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap(reverse);
        for (int k : new int[]{10, 20, 30, 40, 50})
        {
            m.put(k, k * 10);
        }
        // Source iterates descending under the reverse comparator.
        assertEquals(List.of(50, 40, 30, 20, 10), m.rangeKeys(Range.all()));
        NavigableTreeMap<Integer, Integer> sub = m.subMap(Range.closedOpen(20, 50)); // {20,30,40}
        // The snapshot must also be reverse-ordered, proving the comparator carried.
        assertEquals(List.of(40, 30, 20), sub.rangeKeys(Range.all()));
        assertEquals(reverse, sub.comparator());
        // Under the reverse comparator floor(25) is the greatest element <= 25 in
        // the reverse order, i.e. the numerically-smallest >= 25 ... pin via firstKey.
        assertEquals(40, sub.firstKey());
        assertEquals(20, sub.lastKey());
    }

    /**
     * Float-keyed axis under the spec IEEE-754 totalOrder comparator, built by
     * incremental {@code put} (the bulk {@code fromSorted} float path is
     * covered by {@code NavigableFromSortedTest}). There is no
     * {@code TreeMap<f32, i32>} scenario at all, so the navigable map's float
     * comparator propagation and navigation are native-test-only — the
     * map-side counterpart of iso2 finding G1-F5.
     *
     * <p>NaN positions are asserted on raw bits: {@code Float.equals} treats
     * all NaNs as equal, so a defect swapping the two ends would survive a
     * plain {@code assertEquals}.
     */
    @Test
    public void floatTotalOrderKeysNavigationAndRankSelect()
    {
        Float negNan = Float.intBitsToFloat(0xFFC00000);
        Float posNan = Float.intBitsToFloat(0x7FC00000);
        NavigableTreeMap<Float, Integer> m = NavigableTreeMap.newMap(FloatTotalOrder.FLOAT_COMPARATOR);
        m.put(0.0f, 5);
        m.put(posNan, 8);
        m.put(Float.NEGATIVE_INFINITY, 2);
        m.put(-0.0f, 4);
        m.put(Float.POSITIVE_INFINITY, 7);
        m.put(negNan, 1);
        m.put(-1.0f, 3);
        m.put(1.0f, 6);

        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, m.comparator());
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, m.ecMap().comparator());
        assertEquals(8, m.size());
        assertEquals(List.of(negNan, Float.NEGATIVE_INFINITY, -1.0f, -0.0f, 0.0f,
                1.0f, Float.POSITIVE_INFINITY, posNan), m.rangeKeys(Range.all()));
        // signed zeros are distinct keys and do NOT overwrite each other
        assertEquals(Integer.valueOf(4), m.get(-0.0f));
        assertEquals(Integer.valueOf(5), m.get(0.0f));
        assertEquals(3, m.rank(-0.0f));
        assertEquals(4, m.rank(0.0f));
        assertEquals(Integer.valueOf(4), m.selectEntry(3).map(Map.Entry::getValue).orElse(null));
        assertEquals(Integer.valueOf(5), m.selectEntry(4).map(Map.Entry::getValue).orElse(null));
        // navigation follows the total order, not Float.compare
        assertEquals(Float.valueOf(-0.0f), m.lowerKey(0.0f));
        assertEquals(Float.valueOf(1.0f), m.higherKey(0.0f));
        assertEquals(Float.valueOf(Float.NEGATIVE_INFINITY), m.floorKey(-2.0f));
        assertEquals(Float.valueOf(-1.0f), m.ceilingKey(-2.0f));
        assertEquals(Float.valueOf(-0.0f), m.floorKey(-0.0f));
        assertEquals(Float.valueOf(0.0f), m.ceilingKey(0.0f));
        assertEquals(Float.floatToRawIntBits(negNan), Float.floatToRawIntBits(m.firstKey()));
        assertEquals(Float.floatToRawIntBits(posNan), Float.floatToRawIntBits(m.lastKey()));
        assertEquals(Float.floatToRawIntBits(negNan),
                Float.floatToRawIntBits(m.lowerKey(Float.NEGATIVE_INFINITY)));
        assertNull(m.lowerKey(negNan));
        assertNull(m.higherKey(posNan));
        // raw-bit order of the whole traversal, so NaN ends cannot be swapped
        List<Float> orderedKeys = m.rangeKeys(Range.all());
        assertEquals(Float.floatToRawIntBits(negNan), Float.floatToRawIntBits(orderedKeys.get(0)));
        assertEquals(Float.floatToRawIntBits(posNan),
                Float.floatToRawIntBits(orderedKeys.get(orderedKeys.size() - 1)));
        // the EC backing store holds the same keys after incremental puts
        assertEquals(m.size(), m.ecMap().size());
        assertEquals(orderedKeys, m.ecMap().keySet().stream().toList());
        // a materialized snapshot keeps the float comparator
        NavigableTreeMap<Float, Integer> sub = m.subMap(Range.closed(-0.0f, Float.POSITIVE_INFINITY));
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, sub.comparator());
        assertEquals(List.of(-0.0f, 0.0f, 1.0f, Float.POSITIVE_INFINITY),
                sub.rangeKeys(Range.all()));
    }

    /**
     * Two NaN keys of the same sign but different payloads stay distinct and
     * keep their payloads (and their own values) through both backing stores.
     * Asserted on raw bits, which is the only way to see it.
     */
    @Test
    public void floatTotalOrderKeepsDistinctSameSignNanKeyPayloads()
    {
        Float nanLow = Float.intBitsToFloat(0x7FC00000);
        Float nanHigh = Float.intBitsToFloat(0x7FC00001);
        NavigableTreeMap<Float, Integer> m = NavigableTreeMap.newMap(FloatTotalOrder.FLOAT_COMPARATOR);
        m.put(nanHigh, 2);
        m.put(nanLow, 1);
        m.put(1.0f, 0);
        assertEquals(3, m.size());
        List<Float> keys = m.rangeKeys(Range.all());
        assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(keys.get(0)));
        assertEquals(0x7FC00000, Float.floatToRawIntBits(keys.get(1)));
        assertEquals(0x7FC00001, Float.floatToRawIntBits(keys.get(2)));
        // distinct keys -> distinct values, no collapse
        assertEquals(Integer.valueOf(1), m.get(nanLow));
        assertEquals(Integer.valueOf(2), m.get(nanHigh));
        assertEquals(1, m.rank(nanLow));
        assertEquals(2, m.rank(nanHigh));
        assertEquals(3, m.ecMap().size());
    }
}

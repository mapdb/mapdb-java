// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.navigable.NavigableTreeMap;
import org.mapdb.collections.impl.navigable.NavigableTreeSet;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.tuple.Tuples;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the data-pump bulk builders on the boxed-tree navigation
 * wrappers: {@link NavigableTreeMap#fromSorted} / {@link NavigableTreeSet#fromSorted}
 * (spec {@code features/data-pump.md} + {@code style/java.md} navigation
 * carve-out).
 *
 * <p>These exist because the cross-language scenario
 * {@code 17-bulk-load/treemap_i32_from_sorted} asserts rank/select on a
 * <em>bulk-built</em> tree: before finding G1-F7 there was no bulk builder that
 * produced a rank/select-capable navigable map at all, so the validation runner
 * copied the pump result entry-by-entry into a put-by-put map and the scenario's
 * stated "bulk builder that omits the order-statistics augmentation" regression
 * guard was inert. The obligations pinned here are: bulk-built == put-by-put
 * (order statistics included), duplicate policy, empty input, unsorted
 * rejection, comparator preservation, and that both backing stores (EC tree +
 * navigation view) are live and in sync afterwards.
 */
public class NavigableFromSortedTest
{
    private static List<Pair<Integer, Integer>> pairs(int... keys)
    {
        List<Pair<Integer, Integer>> out = new ArrayList<>();
        for (int k : keys)
        {
            out.add(Tuples.pair(k, k * 10));
        }
        return out;
    }

    private static NavigableTreeMap<Integer, Integer> putByPut(List<Pair<Integer, Integer>> input)
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        for (Pair<Integer, Integer> p : input)
        {
            m.put(p.getOne(), p.getTwo());
        }
        return m;
    }

    // ---- map: the scenario's own data, bulk-built --------------------------

    @Test
    public void mapFromSortedMatchesScenarioRankSelect()
    {
        List<Pair<Integer, Integer>> input = Arrays.asList(
                Tuples.pair(-10, 100),
                Tuples.pair(0, 0),
                Tuples.pair(5, 50),
                Tuples.pair(20, 200),
                Tuples.pair(21, 210));
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(input);

        assertEquals(5, m.size());
        assertFalse(m.isEmpty());
        assertEquals(Integer.valueOf(-10), m.firstKey());
        assertEquals(Integer.valueOf(21), m.lastKey());
        assertEquals(Integer.valueOf(100), m.get(-10));
        assertEquals(Integer.valueOf(200), m.get(20));
        assertTrue(m.containsKey(5));
        assertFalse(m.containsKey(99));
        assertEquals(Arrays.asList(-10, 0, 5, 20, 21), m.rangeKeys(Range.all()));

        // Order statistics on the bulk-built tree (the scenario's guard).
        assertEquals(0, m.rank(-10));
        assertEquals(2, m.rank(5));
        assertEquals(4, m.rank(21));
        assertEquals(5, m.rank(22));
        assertEquals(Integer.valueOf(-10), m.selectKey(0).orElse(null));
        assertEquals(Integer.valueOf(5), m.selectKey(2).orElse(null));
        assertEquals(Integer.valueOf(21), m.selectKey(4).orElse(null));
        assertFalse(m.selectKey(5).isPresent());
        assertFalse(m.selectKey(-1).isPresent());
        assertEquals(Integer.valueOf(50), m.selectEntry(2).map(e -> e.getValue()).orElse(null));
    }

    @Test
    public void mapFromSortedEqualsPutByPutIncludingRankSelect()
    {
        Random rnd = new Random(20260904L);
        for (int trial = 0; trial < 40; trial++)
        {
            int n = rnd.nextInt(60);
            java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
            while (keys.size() < n)
            {
                keys.add(rnd.nextInt(2001) - 1000);
            }
            List<Pair<Integer, Integer>> input = new ArrayList<>();
            for (int k : keys)
            {
                input.add(Tuples.pair(k, k * 10));
            }
            NavigableTreeMap<Integer, Integer> bulk = NavigableTreeMap.fromSorted(input);
            NavigableTreeMap<Integer, Integer> ref = putByPut(input);

            assertEquals(ref.size(), bulk.size());
            assertEquals(ref.rangeKeys(Range.all()), bulk.rangeKeys(Range.all()));
            assertEquals(ref.descendingKeys(), bulk.descendingKeys());
            for (int probe = -1002; probe <= 1002; probe += 7)
            {
                assertEquals(ref.rank(probe), bulk.rank(probe), "rank " + probe);
                assertEquals(ref.floorKey(probe), bulk.floorKey(probe));
                assertEquals(ref.ceilingKey(probe), bulk.ceilingKey(probe));
                assertEquals(ref.lowerKey(probe), bulk.lowerKey(probe));
                assertEquals(ref.higherKey(probe), bulk.higherKey(probe));
            }
            for (int i = -1; i <= n; i++)
            {
                assertEquals(ref.selectKey(i), bulk.selectKey(i), "select " + i);
                assertEquals(ref.selectEntry(i).map(e -> e.getValue()),
                        bulk.selectEntry(i).map(e -> e.getValue()), "selectEntry value " + i);
            }
            // values, not only the key projection
            for (int k : keys)
            {
                assertEquals(ref.get(k), bulk.get(k), "get " + k);
            }
            assertEquals(entryPairs(ref.rangeEntries(Range.all())),
                    entryPairs(bulk.rangeEntries(Range.all())));
        }
    }

    private static List<String> entryPairs(List<java.util.Map.Entry<Integer, Integer>> entries)
    {
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : entries)
        {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    // ---- map: empty / duplicates / unsorted --------------------------------

    @Test
    public void mapFromSortedEmpty()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(new ArrayList<>());
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertNull(m.firstKey());
        assertNull(m.lastKey());
        assertEquals(0, m.rank(0));
        assertFalse(m.selectKey(0).isPresent());
        assertTrue(m.ecMap().isEmpty());
        // still usable afterwards
        m.put(7, 70);
        assertEquals(1, m.size());
        assertEquals(1, m.ecMap().size());
    }

    @Test
    public void mapFromSortedRejectsDuplicateUnderErrorPolicy()
    {
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> NavigableTreeMap.fromSorted(pairs(1, 2, 2, 3)));
    }

    @Test
    public void mapFromSortedIgnorePolicyKeepsFirst()
    {
        List<Pair<Integer, Integer>> input = Arrays.asList(
                Tuples.pair(1, 10),
                Tuples.pair(2, 20),
                Tuples.pair(2, 999),
                Tuples.pair(3, 30));
        NavigableTreeMap<Integer, Integer> m =
                NavigableTreeMap.fromSorted(null, input, Pump.DuplicatePolicy.IGNORE);
        assertEquals(3, m.size());
        assertEquals(Integer.valueOf(20), m.get(2));
        assertEquals(1, m.rank(2));
        assertEquals(Integer.valueOf(3), m.selectKey(2).orElse(null));
    }

    @Test
    public void mapFromSortedRejectsUnsortedInput()
    {
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeMap.fromSorted(pairs(1, 5, 3)));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeMap.fromSorted(pairs(3, 2, 1)));
    }

    // ---- map: comparator preservation + both stores live -------------------

    @Test
    public void mapFromSortedPreservesComparatorInBothStores()
    {
        Comparator<Integer> reverse = Comparator.reverseOrder();
        // ascending under the reverse comparator == descending naturally
        NavigableTreeMap<Integer, Integer> m =
                NavigableTreeMap.fromSorted(reverse, pairs(9, 5, 1), Pump.DuplicatePolicy.ERROR);
        assertEquals(reverse, m.comparator());
        assertEquals(reverse, m.ecMap().comparator());
        assertEquals(Arrays.asList(9, 5, 1), m.rangeKeys(Range.all()));
        assertEquals(Integer.valueOf(9), m.firstKey());
        assertEquals(Integer.valueOf(1), m.lastKey());
        // rank/select follow the map's own comparator
        assertEquals(0, m.rank(9));
        assertEquals(2, m.rank(1));
        assertEquals(Integer.valueOf(5), m.selectKey(1).orElse(null));
    }

    @Test
    public void mapFromSortedKeepsBackingStoresInSyncOnLaterMutation()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(pairs(1, 2, 3));
        assertEquals(3, m.ecMap().size());
        assertEquals(Integer.valueOf(20), m.ecMap().get(2));
        m.put(4, 40);
        m.remove(1);
        assertEquals(3, m.size());
        assertEquals(3, m.ecMap().size());
        assertEquals(Arrays.asList(2, 3, 4), m.rangeKeys(Range.all()));
        assertFalse(m.ecMap().containsKey(1));
        assertTrue(m.ecMap().containsKey(4));
        assertEquals(Integer.valueOf(3), m.selectKey(1).orElse(null));
    }

    // ---- float total-order comparator through the bulk builders ------------

    @Test
    public void mapFromSortedUnderFloatTotalOrder()
    {
        // Ascending under IEEE-754 totalOrder: -NaN < -Inf < -1 < -0.0 < +0.0 < 1 < +Inf < NaN.
        Float negNan = Float.intBitsToFloat(0xFFC00000);
        Float posNan = Float.intBitsToFloat(0x7FC00000);
        List<Pair<Float, Integer>> input = Arrays.asList(
                Tuples.pair(negNan, 1),
                Tuples.pair(Float.NEGATIVE_INFINITY, 2),
                Tuples.pair(-1.0f, 3),
                Tuples.pair(-0.0f, 4),
                Tuples.pair(0.0f, 5),
                Tuples.pair(1.0f, 6),
                Tuples.pair(Float.POSITIVE_INFINITY, 7),
                Tuples.pair(posNan, 8));
        NavigableTreeMap<Float, Integer> m = NavigableTreeMap.fromSorted(
                FloatTotalOrder.FLOAT_COMPARATOR, input, Pump.DuplicatePolicy.ERROR);

        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, m.comparator());
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, m.ecMap().comparator());
        assertEquals(8, m.size());
        // -0.0 and +0.0 are distinct keys under total order
        assertEquals(Integer.valueOf(4), m.get(-0.0f));
        assertEquals(Integer.valueOf(5), m.get(0.0f));
        assertEquals(0, m.rank(negNan));
        assertEquals(3, m.rank(-0.0f));
        assertEquals(4, m.rank(0.0f));
        assertEquals(7, m.rank(posNan));
        assertEquals(Integer.valueOf(4), m.selectEntry(3).map(e -> e.getValue()).orElse(null));
        assertEquals(Integer.valueOf(5), m.selectEntry(4).map(e -> e.getValue()).orElse(null));
        assertEquals(Float.valueOf(Float.NEGATIVE_INFINITY), m.selectKey(1).orElse(null));
        assertEquals(Float.valueOf(Float.POSITIVE_INFINITY), m.selectKey(6).orElse(null));

        // natural Float order would put NaN last and collapse the two zeros:
        // passing this input under natural order is out of order and must trap.
        assertThrows(Pump.PumpSourceNotSorted.class, () -> NavigableTreeMap.fromSorted(input));
    }

    @Test
    public void setFromSortedUnderFloatTotalOrder()
    {
        Float negNan = Float.intBitsToFloat(0xFFC00000);
        Float posNan = Float.intBitsToFloat(0x7FC00000);
        List<Float> input = Arrays.asList(
                negNan, Float.NEGATIVE_INFINITY, -0.0f, 0.0f, Float.POSITIVE_INFINITY, posNan);
        NavigableTreeSet<Float> s = NavigableTreeSet.fromSorted(
                FloatTotalOrder.FLOAT_COMPARATOR, input, Pump.DuplicatePolicy.ERROR);

        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, s.comparator());
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, s.ecSet().comparator());
        assertEquals(6, s.size());
        assertTrue(s.contains(-0.0f));
        assertTrue(s.contains(0.0f));
        assertEquals(2, s.rank(-0.0f));
        assertEquals(3, s.rank(0.0f));
        assertEquals(Float.valueOf(-0.0f), s.select(2).orElse(null));
        assertEquals(Float.valueOf(0.0f), s.select(3).orElse(null));
    }

    // ---- two-store lockstep across the full mutation surface ---------------

    @Test
    public void mapFromSortedStaysInSyncAcrossPollAndRemoveRangeAndClear()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(pairs(1, 2, 3, 4, 5, 6));
        assertEquals(Integer.valueOf(1), m.pollFirstEntry().map(e -> e.getKey()).orElse(null));
        assertEquals(Integer.valueOf(6), m.pollLastEntry().map(e -> e.getKey()).orElse(null));
        assertEquals(4, m.ecMap().size());
        assertFalse(m.ecMap().containsKey(1));
        assertFalse(m.ecMap().containsKey(6));
        assertEquals(1, m.removeRange(Range.closed(3, 3)));
        assertEquals(Arrays.asList(2, 4, 5), m.rangeKeys(Range.all()));
        assertEquals(3, m.ecMap().size());
        assertFalse(m.ecMap().containsKey(3));
        assertEquals(1, m.rank(4));
        m.clear();
        assertEquals(0, m.size());
        assertTrue(m.ecMap().isEmpty());
    }

    @Test
    public void setFromSortedStaysInSyncAcrossPollAndRemoveRangeAndClear()
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.fromSorted(Arrays.asList(1, 2, 3, 4, 5, 6));
        assertEquals(Integer.valueOf(1), s.pollFirst().orElse(null));
        assertEquals(Integer.valueOf(6), s.pollLast().orElse(null));
        assertEquals(4, s.ecSet().size());
        assertEquals(1, s.removeRange(Range.closed(3, 3)));
        assertEquals(Arrays.asList(2, 4, 5), s.rangeElements(Range.all()));
        assertEquals(3, s.ecSet().size());
        assertEquals(1, s.rank(4));
        s.clear();
        assertEquals(0, s.size());
        assertTrue(s.ecSet().isEmpty());
    }

    // ---- set analogues -----------------------------------------------------

    @Test
    public void setFromSortedRankSelectAndNavigation()
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.fromSorted(Arrays.asList(-10, 0, 5, 20, 21));
        assertEquals(5, s.size());
        assertEquals(Integer.valueOf(-10), s.first());
        assertEquals(Integer.valueOf(21), s.last());
        assertTrue(s.contains(5));
        assertFalse(s.contains(99));
        assertEquals(Arrays.asList(-10, 0, 5, 20, 21), s.rangeElements(Range.all()));
        assertEquals(0, s.rank(-10));
        assertEquals(2, s.rank(5));
        assertEquals(5, s.rank(22));
        assertEquals(Integer.valueOf(5), s.select(2).orElse(null));
        assertFalse(s.select(5).isPresent());
        assertEquals(Integer.valueOf(0), s.floor(4));
        assertEquals(Integer.valueOf(5), s.ceiling(4));
        assertEquals(5, s.ecSet().size());
    }

    @Test
    public void setFromSortedEmptyDuplicateUnsortedAndComparator()
    {
        NavigableTreeSet<Integer> empty = NavigableTreeSet.fromSorted(new ArrayList<Integer>());
        assertEquals(0, empty.size());
        assertEquals(0, empty.rank(0));
        assertFalse(empty.select(0).isPresent());

        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> NavigableTreeSet.fromSorted(Arrays.asList(1, 2, 2)));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeSet.fromSorted(Arrays.asList(1, 5, 3)));

        NavigableTreeSet<Integer> ignored =
                NavigableTreeSet.fromSorted(null, Arrays.asList(1, 2, 2, 3), Pump.DuplicatePolicy.IGNORE);
        assertEquals(3, ignored.size());
        assertEquals(Arrays.asList(1, 2, 3), ignored.rangeElements(Range.all()));
        assertEquals(1, ignored.rank(2));
        assertEquals(3, ignored.rank(4));
        assertEquals(Integer.valueOf(3), ignored.select(2).orElse(null));

        Comparator<Integer> reverse = Comparator.reverseOrder();
        NavigableTreeSet<Integer> rev =
                NavigableTreeSet.fromSorted(reverse, Arrays.asList(9, 5, 1), Pump.DuplicatePolicy.ERROR);
        assertEquals(reverse, rev.comparator());
        assertEquals(reverse, rev.ecSet().comparator());
        assertEquals(Arrays.asList(9, 5, 1), rev.rangeElements(Range.all()));
        assertEquals(Integer.valueOf(5), rev.select(1).orElse(null));
    }

    @Test
    public void setFromSortedEqualsAddByAdd()
    {
        Random rnd = new Random(4242L);
        for (int trial = 0; trial < 40; trial++)
        {
            int n = rnd.nextInt(60);
            java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
            while (keys.size() < n)
            {
                keys.add(rnd.nextInt(2001) - 1000);
            }
            List<Integer> input = new ArrayList<>(keys);
            NavigableTreeSet<Integer> bulk = NavigableTreeSet.fromSorted(input);
            NavigableTreeSet<Integer> ref = NavigableTreeSet.newSet();
            for (int k : input)
            {
                ref.add(k);
            }
            assertEquals(ref.size(), bulk.size());
            assertEquals(ref.rangeElements(Range.all()), bulk.rangeElements(Range.all()));
            assertEquals(ref.descending(), bulk.descending());
            for (int probe = -1002; probe <= 1002; probe += 7)
            {
                assertEquals(ref.rank(probe), bulk.rank(probe));
                assertEquals(ref.floor(probe), bulk.floor(probe));
                assertEquals(ref.higher(probe), bulk.higher(probe));
            }
            for (int i = -1; i <= n; i++)
            {
                assertEquals(ref.select(i), bulk.select(i));
            }
        }
    }
}

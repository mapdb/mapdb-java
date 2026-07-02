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
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link ImmutableSortedMap} (spec
 * {@code features/sorted-table-map.md}). Pins the per-port obligations the
 * shared scenario suite cannot reach cross-language: the construction traps
 * (unsorted / duplicate / length-mismatch -> {@link IllegalArgumentException}),
 * empty + single validity, snapshot independence from a mutated source array,
 * the {@code values()}/{@code entries()} key-order pairing with non-monotonic
 * values, the {@code select(rank(k)) == k} round-trip identity, and the
 * signed-i32 edges (nav / rank / select / range brackets at
 * {@code INT_MIN}/{@code INT_MAX} with no {@code v ± 1} overflow).
 */
public class ImmutableSortedMapTest
{
    private static final int MIN = Integer.MIN_VALUE;
    private static final int MAX = Integer.MAX_VALUE;

    // ---- construction traps ------------------------------------------------

    @Test
    public void unsortedInputTraps()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ImmutableSortedMap.fromSorted(new int[] {3, 1, 2}, new int[] {30, 10, 20}));
    }

    @Test
    public void duplicateKeyTraps()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ImmutableSortedMap.fromSorted(new int[] {1, 2, 2, 3}, new int[] {10, 20, 25, 30}));
    }

    @Test
    public void lengthMismatchTraps()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ImmutableSortedMap.fromSorted(new int[] {1, 2, 3}, new int[] {10, 20}));
    }

    @Test
    public void emptyAndSingleAreValid()
    {
        ImmutableSortedMap<Integer, Integer> empty = ImmutableSortedMap.fromSorted(new int[] {}, new int[] {});
        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
        assertTrue(empty.get(5).isEmpty());
        assertTrue(empty.firstKey().isEmpty());
        assertTrue(empty.lastKey().isEmpty());
        assertTrue(empty.floorKey(5).isEmpty());
        assertTrue(empty.ceilingKey(5).isEmpty());
        assertEquals(0, empty.rank(5));
        assertTrue(empty.selectKey(0).isEmpty());
        assertTrue(empty.rangeKeys(Range.all()).isEmpty());

        ImmutableSortedMap<Integer, Integer> single = ImmutableSortedMap.fromSorted(new int[] {7}, new int[] {700});
        assertEquals(1, single.size());
        assertEquals(Optional.of(700), single.get(7));
        assertEquals(Optional.of(7), single.floorKey(7));
        assertTrue(single.lowerKey(7).isEmpty());
        assertTrue(single.higherKey(7).isEmpty());
    }

    // ---- snapshot independence --------------------------------------------

    @Test
    public void snapshotIndependentFromSource()
    {
        int[] keys = {1, 2, 3};
        int[] values = {10, 20, 30};
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);
        // Mutate the caller's source arrays after construction.
        keys[0] = 999;
        values[1] = -1;
        assertEquals(Optional.of(10), map.get(1));
        assertEquals(Optional.of(20), map.get(2));
        assertFalse(map.containsKey(999));
        assertEquals(List.of(1, 2, 3), map.keys());
        assertEquals(List.of(10, 20, 30), map.values());
    }

    // ---- values()/entries() key-order pairing (non-monotonic values) ------

    @Test
    public void valuesAndEntriesPairInKeyOrder()
    {
        // keys ascending, values deliberately NOT monotonic.
        int[] keys = {10, 20, 30};
        int[] values = {300, 100, 200};
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);
        // values() is in key order, NOT sorted by value.
        assertEquals(List.of(300, 100, 200), map.values());
        // entries() pairs keys[i] with values[i].
        List<Map.Entry<Integer, Integer>> entries = map.entries();
        assertEquals(3, entries.size());
        assertEquals(Map.entry(10, 300), entries.get(0));
        assertEquals(Map.entry(20, 100), entries.get(1));
        assertEquals(Map.entry(30, 200), entries.get(2));
        // get respects the pairing.
        assertEquals(Optional.of(300), map.get(10));
        assertEquals(Optional.of(100), map.get(20));
        assertEquals(Optional.of(200), map.get(30));
    }

    // ---- select(rank(k)) == k round trip ----------------------------------

    @Test
    public void selectRankRoundTrip()
    {
        int[] keys = {-100, -1, 0, 7, 42, 9999};
        int[] values = {1, 2, 3, 4, 5, 6};
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);
        for (int k : keys)
        {
            int r = map.rank(k);
            assertEquals(Optional.of(k), map.selectKey(r), "select(rank(" + k + "))");
        }
        for (int i = 0; i < keys.length; i++)
        {
            Optional<Integer> sel = map.selectKey(i);
            assertTrue(sel.isPresent());
            assertEquals(i, map.rank(sel.get()), "rank(select(" + i + "))");
        }
        // out of range select -> empty, no trap.
        assertTrue(map.selectKey(keys.length).isEmpty());
        assertTrue(map.selectKey(-1).isEmpty());
    }

    // ---- signed-i32 edges: nav / rank / select ----------------------------

    @Test
    public void signedEdgesNavRankSelect()
    {
        int[] keys = {MIN, -1, 0, 1, MAX};
        int[] values = {1, 2, 3, 4, 5};
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);

        assertEquals(Optional.of(MIN), map.floorKey(MIN));
        assertTrue(map.lowerKey(MIN).isEmpty());
        assertEquals(Optional.of(0), map.higherKey(-1));
        assertEquals(Optional.of(MAX), map.ceilingKey(MAX));
        assertTrue(map.higherKey(MAX).isEmpty());
        // ceiling beyond the top.
        assertTrue(map.ceilingKey(MAX).isPresent());
        assertEquals(Optional.of(MIN), map.firstKey());
        assertEquals(Optional.of(MAX), map.lastKey());

        assertEquals(0, map.rank(MIN));
        assertEquals(2, map.rank(0));
        assertEquals(5, map.rank(MAX) + 1); // rank(MAX)=4; one past = size
        assertEquals(Optional.of(MIN), map.selectKey(0));
        assertEquals(Optional.of(MAX), map.selectKey(4));
        assertTrue(map.selectKey(5).isEmpty());
    }

    // ---- signed-i32 range brackets (no v ± 1 overflow) --------------------

    @Test
    public void signedEdgeRangeBrackets()
    {
        int[] keys = {MIN, -1, 0, 1, MAX};
        int[] values = {1, 2, 3, 4, 5};
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);

        // greater_than(MIN): open lower at INT_MIN excludes MIN. No v-1.
        assertEquals(List.of(-1, 0, 1, MAX), map.rangeKeys(Range.greaterThan(MIN)));
        // less_than(MAX): open upper at INT_MAX excludes MAX. No v+1.
        assertEquals(List.of(MIN, -1, 0, 1), map.rangeKeys(Range.lessThan(MAX)));
        // closed(MIN, MAX): the whole thing.
        assertEquals(List.of(MIN, -1, 0, 1, MAX), map.rangeKeys(Range.closed(MIN, MAX)));
        // singleton(MAX).
        assertEquals(List.of(MAX), map.rangeKeys(Range.singleton(MAX)));
        // atLeast(MIN): everything.
        assertEquals(5, map.rangeKeys(Range.atLeast(MIN)).size());
        // descending: lessThan(MAX) -> ascending [MIN,-1,0,1] -> desc [1,0,-1,MIN].
        assertEquals(List.of(1, 0, -1, MIN), map.descendingRangeKeys(Range.lessThan(MAX)));
    }

    // ---- range membership == contains (open(1,2) over i32 -> empty) -------

    @Test
    public void rangeMembershipIsContains()
    {
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(new int[] {1, 2}, new int[] {10, 20});
        assertTrue(map.rangeKeys(Range.open(1, 2)).isEmpty());
        assertEquals(0, map.rangeKeys(Range.open(1, 2)).size());
        // cut-empty range matches nothing.
        assertTrue(map.rangeKeys(Range.closedOpen(2, 2)).isEmpty());
    }

    // ---- randomized cross-check against TreeMap (the oracle) --------------

    @Test
    public void randomizedAgainstTreeMap()
    {
        Random rnd = new Random(0xBADC0FFEL);
        for (int trial = 0; trial < 50; trial++)
        {
            TreeMap<Integer, Integer> oracle = new TreeMap<>();
            int n = rnd.nextInt(200);
            for (int i = 0; i < n; i++)
            {
                oracle.put(rnd.nextInt(1000) - 500, rnd.nextInt());
            }
            List<Integer> ks = new ArrayList<>(oracle.keySet());
            int[] keys = ks.stream().mapToInt(Integer::intValue).toArray();
            int[] values = new int[keys.length];
            for (int i = 0; i < keys.length; i++)
            {
                values[i] = oracle.get(keys[i]);
            }
            ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);

            assertEquals(oracle.size(), map.size());
            for (int probe = -600; probe <= 600; probe += 7)
            {
                assertEquals(Optional.ofNullable(oracle.get(probe)), map.get(probe), "get " + probe);
                assertEquals(oracle.containsKey(probe), map.containsKey(probe), "contains " + probe);
                assertEquals(Optional.ofNullable(oracle.floorKey(probe)), map.floorKey(probe), "floor " + probe);
                assertEquals(Optional.ofNullable(oracle.ceilingKey(probe)), map.ceilingKey(probe), "ceiling " + probe);
                assertEquals(Optional.ofNullable(oracle.lowerKey(probe)), map.lowerKey(probe), "lower " + probe);
                assertEquals(Optional.ofNullable(oracle.higherKey(probe)), map.higherKey(probe), "higher " + probe);
                assertEquals(oracle.headMap(probe).size(), map.rank(probe), "rank " + probe);
            }
            // range cross-check.
            List<Integer> inRange = new ArrayList<>(oracle.subMap(-200, true, 200, false).keySet());
            assertEquals(inRange, map.rangeKeys(Range.closedOpen(-200, 200)));
        }
    }
}

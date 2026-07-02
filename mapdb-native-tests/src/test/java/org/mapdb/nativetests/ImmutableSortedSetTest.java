// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for {@link ImmutableSortedSet} (spec
 * {@code features/sorted-table-map.md}). Mirrors {@link ImmutableSortedMapTest}
 * for the element surface: construction traps, empty/single validity, snapshot
 * independence, {@code select(rank(k)) == k}, signed-i32 nav/rank/select +
 * range brackets at {@code INT_MIN}/{@code INT_MAX}, and a randomized
 * cross-check against {@link TreeSet}.
 */
public class ImmutableSortedSetTest
{
    private static final int MIN = Integer.MIN_VALUE;
    private static final int MAX = Integer.MAX_VALUE;

    @Test
    public void unsortedInputTraps()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ImmutableSortedSet.fromSorted(new int[] {3, 1, 2}));
    }

    @Test
    public void duplicateElementTraps()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ImmutableSortedSet.fromSorted(new int[] {1, 2, 2, 3}));
    }

    @Test
    public void emptyAndSingleAreValid()
    {
        ImmutableSortedSet<Integer> empty = ImmutableSortedSet.fromSorted(new int[] {});
        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
        assertFalse(empty.contains(5));
        assertTrue(empty.first().isEmpty());
        assertTrue(empty.floor(5).isEmpty());
        assertEquals(0, empty.rank(5));
        assertTrue(empty.select(0).isEmpty());
        assertTrue(empty.rangeElements(Range.all()).isEmpty());

        ImmutableSortedSet<Integer> single = ImmutableSortedSet.fromSorted(new int[] {7});
        assertEquals(1, single.size());
        assertTrue(single.contains(7));
        assertEquals(Optional.of(7), single.floor(7));
        assertTrue(single.lower(7).isEmpty());
        assertTrue(single.higher(7).isEmpty());
    }

    @Test
    public void snapshotIndependentFromSource()
    {
        int[] elems = {1, 2, 3};
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elems);
        elems[0] = 999;
        assertTrue(set.contains(1));
        assertFalse(set.contains(999));
        assertEquals(List.of(1, 2, 3), set.elements());
    }

    @Test
    public void selectRankRoundTrip()
    {
        int[] elems = {-100, -1, 0, 7, 42, 9999};
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elems);
        for (int e : elems)
        {
            int r = set.rank(e);
            assertEquals(Optional.of(e), set.select(r));
        }
        assertTrue(set.select(elems.length).isEmpty());
        assertTrue(set.select(-1).isEmpty());
    }

    @Test
    public void signedEdgesNavRankSelect()
    {
        int[] elems = {MIN, -1, 0, 1, MAX};
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elems);

        assertEquals(Optional.of(MIN), set.floor(MIN));
        assertTrue(set.lower(MIN).isEmpty());
        assertEquals(Optional.of(0), set.higher(-1));
        assertEquals(Optional.of(MAX), set.ceiling(MAX));
        assertTrue(set.higher(MAX).isEmpty());
        assertEquals(Optional.of(MIN), set.first());
        assertEquals(Optional.of(MAX), set.last());

        assertEquals(0, set.rank(MIN));
        assertEquals(2, set.rank(0));
        assertEquals(Optional.of(MIN), set.select(0));
        assertEquals(Optional.of(MAX), set.select(4));
        assertTrue(set.select(5).isEmpty());
    }

    @Test
    public void signedEdgeRangeBrackets()
    {
        int[] elems = {MIN, -1, 0, 1, MAX};
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elems);

        assertEquals(List.of(-1, 0, 1, MAX), set.rangeElements(Range.greaterThan(MIN)));
        assertEquals(List.of(MIN, -1, 0, 1), set.rangeElements(Range.lessThan(MAX)));
        assertEquals(List.of(MIN, -1, 0, 1, MAX), set.rangeElements(Range.closed(MIN, MAX)));
        assertEquals(List.of(MAX), set.rangeElements(Range.singleton(MAX)));
        assertEquals(List.of(1, 0, -1, MIN), set.descendingRangeElements(Range.lessThan(MAX)));
    }

    @Test
    public void rangeMembershipIsContains()
    {
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(new int[] {1, 2});
        assertTrue(set.rangeElements(Range.open(1, 2)).isEmpty());
        assertTrue(set.rangeElements(Range.closedOpen(2, 2)).isEmpty());
    }

    @Test
    public void randomizedAgainstTreeSet()
    {
        Random rnd = new Random(0x5EEDL);
        for (int trial = 0; trial < 50; trial++)
        {
            TreeSet<Integer> oracle = new TreeSet<>();
            int n = rnd.nextInt(200);
            for (int i = 0; i < n; i++)
            {
                oracle.add(rnd.nextInt(1000) - 500);
            }
            int[] elems = oracle.stream().mapToInt(Integer::intValue).toArray();
            ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elems);

            assertEquals(oracle.size(), set.size());
            for (int probe = -600; probe <= 600; probe += 7)
            {
                assertEquals(oracle.contains(probe), set.contains(probe));
                assertEquals(Optional.ofNullable(oracle.floor(probe)), set.floor(probe));
                assertEquals(Optional.ofNullable(oracle.ceiling(probe)), set.ceiling(probe));
                assertEquals(Optional.ofNullable(oracle.lower(probe)), set.lower(probe));
                assertEquals(Optional.ofNullable(oracle.higher(probe)), set.higher(probe));
                assertEquals(oracle.headSet(probe).size(), set.rank(probe));
            }
            List<Integer> inRange = new ArrayList<>(oracle.subSet(-200, true, 200, false));
            assertEquals(inRange, set.rangeElements(Range.closedOpen(-200, 200)));
        }
    }
}

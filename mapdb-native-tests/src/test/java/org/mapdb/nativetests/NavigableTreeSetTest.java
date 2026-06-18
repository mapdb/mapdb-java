// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.navigable.NavigableTreeSet;
import org.mapdb.collections.impl.range.Range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the boxed-tree NavigableSet carve-out
 * ({@link NavigableTreeSet}) — the element analogue of
 * {@link NavigableTreeMapTest}.
 */
public class NavigableTreeSetTest
{
    private static NavigableTreeSet<Integer> setOf(int... elems)
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet();
        for (int e : elems)
        {
            s.add(e);
        }
        return s;
    }

    @Test
    public void floorCeilingLowerHigherStrictness()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30);
        assertEquals(20, s.floor(25));
        assertEquals(30, s.ceiling(25));
        assertEquals(10, s.floor(10));
        assertNull(s.lower(10));
        assertNull(s.higher(30));
        assertEquals(10, s.ceiling(5));
        assertEquals(10, s.first());
        assertEquals(30, s.last());
    }

    @Test
    public void navOnEmptyIsAllAbsent()
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet();
        assertNull(s.floor(5));
        assertNull(s.ceiling(5));
        assertNull(s.lower(5));
        assertNull(s.higher(5));
        assertNull(s.first());
        assertNull(s.last());
    }

    @Test
    public void navSignedExtremes()
    {
        NavigableTreeSet<Integer> s = setOf(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, s.floor(Integer.MIN_VALUE));
        assertNull(s.lower(Integer.MIN_VALUE));
        assertEquals(0, s.higher(-1));
        assertEquals(Integer.MAX_VALUE, s.ceiling(Integer.MAX_VALUE));
        assertNull(s.higher(Integer.MAX_VALUE));
        assertEquals(List.of(Integer.MAX_VALUE, 1, 0, -1, Integer.MIN_VALUE), s.descending());
    }

    @Test
    public void pollFirstLastThenEmptyDoesNotTrap()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30);
        assertEquals(10, s.pollFirst().get());
        assertEquals(30, s.pollLast().get());
        assertEquals(20, s.pollFirst().get());
        assertTrue(s.pollFirst().isEmpty());
        assertTrue(s.pollLast().isEmpty());
    }

    @Test
    public void pollSingleThenEmpty()
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet();
        s.add(7);
        assertEquals(7, s.pollFirst().get());
        assertTrue(s.pollFirst().isEmpty());
        assertTrue(s.isEmpty());
    }

    @Test
    public void rangeAscendingAndDescending()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        assertEquals(List.of(30, 40, 50, 60), s.rangeElements(Range.closedOpen(30, 70)));
        assertEquals(List.of(60, 50, 40, 30), s.descendingRangeElements(Range.closedOpen(30, 70)));
        assertEquals(List.of(40, 50, 60, 70), s.rangeElements(Range.openClosed(30, 70)));
        assertEquals(List.of(80, 90, 100), s.rangeElements(Range.atLeast(80)));
        assertEquals(List.of(100, 90, 80, 70, 60, 50, 40, 30, 20, 10), s.descending());
    }

    @Test
    public void rangeOpenNoIntegerIsEmptyNotCutEmpty()
    {
        NavigableTreeSet<Integer> s = setOf(1, 2);
        assertEquals(List.of(), s.rangeElements(Range.open(1, 2)));
        assertFalse(Range.open(1, 2).isEmpty());
        assertEquals(0, s.removeRange(Range.open(1, 2)));
        assertEquals(2, s.size());
    }

    @Test
    public void removeRangeCountAndNoop()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        assertEquals(4, s.removeRange(Range.closedOpen(30, 70)));
        assertEquals(0, s.removeRange(Range.closedOpen(30, 70)));
        assertEquals(List.of(10, 20, 70, 80, 90, 100), s.rangeElements(Range.all()));
        assertEquals("[10, 20, 70, 80, 90, 100]", s.ecSet().makeString("[", ", ", "]"));
    }

    @Test
    public void subSetIndependence()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30, 40, 50);
        NavigableTreeSet<Integer> snap = s.subSet(Range.closed(20, 40));
        assertEquals(List.of(20, 30, 40), snap.rangeElements(Range.all()));
        snap.add(99);
        snap.remove(20);
        assertTrue(s.contains(20));
        assertFalse(s.contains(99));
        s.remove(30);
        assertTrue(snap.contains(30));
    }

    @Test
    public void subSetPreservesReverseComparator()
    {
        Comparator<Integer> reverse = Comparator.reverseOrder();
        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet(reverse);
        for (int k : new int[]{10, 20, 30, 40, 50})
        {
            s.add(k);
        }
        assertEquals(List.of(50, 40, 30, 20, 10), s.rangeElements(Range.all()));
        NavigableTreeSet<Integer> sub = s.subSet(Range.closedOpen(20, 50)); // {20,30,40}
        assertEquals(List.of(40, 30, 20), sub.rangeElements(Range.all()));
        assertEquals(reverse, sub.comparator());
        assertEquals(40, sub.first());
        assertEquals(20, sub.last());
    }
}

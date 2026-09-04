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
import org.mapdb.collections.impl.utility.FloatTotalOrder;

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

    /**
     * Float axis under the spec IEEE-754 totalOrder comparator, built by
     * incremental {@code add} (the bulk {@code fromSorted} float path is
     * covered by {@code NavigableFromSortedTest}). The shared scenario suite
     * cannot reach the wrapper at all here: its two {@code TreeSet<f32>}
     * scenarios drive EC {@code TreeSortedSet} (the wiring
     * {@code collections.md} names), so the navigable wrapper's float
     * comparator propagation and its point navigation / rank / select on the
     * float axis are native-test-only (iso2 finding G1-F5).
     *
     * <p>NaN positions are asserted on raw bits: {@code Float.equals} treats
     * all NaNs as equal, so a defect swapping the two ends would survive a
     * plain {@code assertEquals}.
     */
    @Test
    public void floatTotalOrderNavigationAndRankSelect()
    {
        Float negNan = Float.intBitsToFloat(0xFFC00000);
        Float posNan = Float.intBitsToFloat(0x7FC00000);
        NavigableTreeSet<Float> s = NavigableTreeSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR);
        // inserted out of order on purpose: the tree must impose total order
        for (Float f : new Float[]{0.0f, posNan, Float.NEGATIVE_INFINITY, -0.0f,
                Float.POSITIVE_INFINITY, negNan, -1.0f, 1.0f})
        {
            s.add(f);
        }
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, s.comparator());
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, s.ecSet().comparator());
        // -NaN < -Inf < -1 < -0.0 < +0.0 < 1 < +Inf < NaN
        assertEquals(8, s.size());
        assertEquals(List.of(negNan, Float.NEGATIVE_INFINITY, -1.0f, -0.0f,
                0.0f, 1.0f, Float.POSITIVE_INFINITY, posNan), s.rangeElements(Range.all()));
        assertEquals(Float.floatToRawIntBits(negNan), Float.floatToRawIntBits(s.first()));
        assertEquals(Float.floatToRawIntBits(posNan), Float.floatToRawIntBits(s.last()));
        // signed zeros stay distinct elements, and are ordered -0.0 before +0.0
        assertTrue(s.contains(-0.0f));
        assertTrue(s.contains(0.0f));
        assertEquals(3, s.rank(-0.0f));
        assertEquals(4, s.rank(0.0f));
        assertEquals(Float.valueOf(-0.0f), s.select(3).orElse(null));
        assertEquals(Float.valueOf(0.0f), s.select(4).orElse(null));
        // navigation follows the total order, not Float.compare
        assertEquals(Float.valueOf(-0.0f), s.lower(0.0f));
        assertEquals(Float.valueOf(1.0f), s.higher(0.0f));
        assertEquals(Float.valueOf(Float.NEGATIVE_INFINITY), s.floor(-2.0f));
        assertEquals(Float.valueOf(-1.0f), s.ceiling(-2.0f));
        assertEquals(Float.floatToRawIntBits(negNan),
                Float.floatToRawIntBits(s.lower(Float.NEGATIVE_INFINITY)));
        assertNull(s.lower(negNan));
        assertNull(s.higher(posNan));
        // raw-bit order of the whole traversal, so NaN ends cannot be swapped
        List<Float> ordered = s.rangeElements(Range.all());
        assertEquals(Float.floatToRawIntBits(negNan), Float.floatToRawIntBits(ordered.get(0)));
        assertEquals(Float.floatToRawIntBits(posNan),
                Float.floatToRawIntBits(ordered.get(ordered.size() - 1)));
        // the EC backing store holds the same elements after incremental adds
        assertEquals(s.size(), s.ecSet().size());
        assertEquals(ordered, s.ecSet().toList());
        // a materialized snapshot keeps the float comparator
        NavigableTreeSet<Float> sub = s.subSet(Range.closed(-0.0f, Float.POSITIVE_INFINITY));
        assertEquals(FloatTotalOrder.FLOAT_COMPARATOR, sub.comparator());
        assertEquals(List.of(-0.0f, 0.0f, 1.0f, Float.POSITIVE_INFINITY),
                sub.rangeElements(Range.all()));
    }

    /**
     * Two NaNs of the same sign but different payloads are distinct elements
     * and keep their payloads through both backing stores. {@code Float.equals}
     * cannot see this, so every assertion here is on raw bits.
     */
    @Test
    public void floatTotalOrderKeepsDistinctSameSignNanPayloads()
    {
        Float nanLow = Float.intBitsToFloat(0x7FC00000);
        Float nanHigh = Float.intBitsToFloat(0x7FC00001);
        NavigableTreeSet<Float> s = NavigableTreeSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR);
        s.add(nanHigh);
        s.add(nanLow);
        s.add(1.0f);
        assertEquals(3, s.size());
        List<Float> ordered = s.rangeElements(Range.all());
        assertEquals(3, ordered.size());
        assertEquals(Float.floatToRawIntBits(1.0f), Float.floatToRawIntBits(ordered.get(0)));
        // ascending payload order under totalOrder for positive NaNs
        assertEquals(0x7FC00000, Float.floatToRawIntBits(ordered.get(1)));
        assertEquals(0x7FC00001, Float.floatToRawIntBits(ordered.get(2)));
        assertEquals(1, s.rank(nanLow));
        assertEquals(2, s.rank(nanHigh));
        // and the EC backing store agrees, payloads intact
        assertEquals(3, s.ecSet().size());
        assertEquals(ordered, s.ecSet().toList());
    }
}

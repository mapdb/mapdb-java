// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.BoundType;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native (port-specific) tests for the boxed {@link RangeSet}
 * (spec/features/range-set-map.md), mirroring the Rust reference battery and
 * pinning the parity traps + the native-only obligations the shared JSON suite
 * cannot reach: complement involution over fixed sets, snapshot independence of
 * the {@code complement}/{@code subRangeSet} views, and the no-{@code +-1}
 * signed-extreme arithmetic.
 */
public class RangeSetTest
{
    private static RangeSet<Integer> rs(Range<Integer>... ranges)
    {
        RangeSet<Integer> s = new RangeSet<>();
        s.addAll(Arrays.asList(ranges));
        return s;
    }

    @Test
    public void coalesceOverlap()
    {
        // [1,5] + [3,9] overlap -> single [1,9].
        RangeSet<Integer> s = rs(Range.closed(1, 5), Range.closed(3, 9));
        assertEquals(List.of(Range.closed(1, 9)), s.asRanges());
        assertTrue(s.contains(4));
        assertFalse(s.contains(10));
        assertEquals(Optional.of(Range.closed(1, 9)), s.span());
    }

    @Test
    public void coalesceAbutCutTouch()
    {
        // [1,3) & [3,5) touch at Below(3) -> single [1,5). The OUTER (open)
        // upper cut survives, not [1,5].
        RangeSet<Integer> s = rs(Range.closedOpen(1, 3), Range.closedOpen(3, 5));
        assertEquals(List.of(Range.closedOpen(1, 5)), s.asRanges());
        assertTrue(s.contains(3));
        assertFalse(s.contains(5));
        assertEquals(BoundType.OPEN, s.asRanges().get(0).upperBoundType());
    }

    @Test
    public void openGapNoMerge()
    {
        // (1,3) & (3,5): value 3 is the gap -> TWO ranges.
        RangeSet<Integer> s = rs(Range.open(1, 3), Range.open(3, 5));
        assertEquals(List.of(Range.open(1, 3), Range.open(3, 5)), s.asRanges());
        assertFalse(s.contains(3));
    }

    @Test
    public void adjacentClosedNoIntegerAdjacencyMerge()
    {
        // [1,3] & [4,5]: cut model has no integer adjacency (Below(4) > Above(3)).
        RangeSet<Integer> s = rs(Range.closed(1, 3), Range.closed(4, 5));
        assertEquals(List.of(Range.closed(1, 3), Range.closed(4, 5)), s.asRanges());
    }

    /**
     * {@code RangeSet.add} is a single ascending pass that commits each stored
     * range's keep/absorb decision at visit time — a shape that is wrong for a
     * {@code RangeMap} (a value barrier can leave a connected neighbour pair
     * stored, so growing the merged span leftward can reconnect an entry the
     * pass already kept). It is nonetheless correct here, because a
     * {@code RangeSet}'s normal form is pairwise <strong>non-connected</strong>:
     * growing the merged span can never reconnect an already-visited range.
     * This pins that: an abutting run coalesces to the same single range
     * whichever end is added last, and a range that bridges a gap absorbs BOTH
     * sides.
     */
    @Test
    public void addCoalescesWholeRunFromEitherDirection()
    {
        // Chain to the LEFT of the last added range.
        RangeSet<Integer> left = rs(
                Range.closedOpen(1, 2), Range.closedOpen(2, 3), Range.closedOpen(3, 4));
        // Mirror: chain to the RIGHT of the last added range.
        RangeSet<Integer> right = rs(
                Range.closedOpen(2, 3), Range.closedOpen(3, 4), Range.closedOpen(1, 2));
        assertEquals(List.of(Range.closedOpen(1, 4)), left.asRanges());
        assertEquals(left.asRanges(), right.asRanges());

        // Bridging a gap between two already-separate ranges absorbs both.
        RangeSet<Integer> bridge = rs(Range.closedOpen(1, 3), Range.closedOpen(5, 7));
        assertEquals(2, bridge.asRanges().size());
        bridge.add(Range.closedOpen(3, 5));
        assertEquals(List.of(Range.closedOpen(1, 7)), bridge.asRanges());
    }

    /**
     * The non-connected normal form survives {@code remove}-derived states too:
     * {@code remove} only ever emits fragments separated by the (non-empty)
     * removed range, so re-adding that range re-joins them into exactly one.
     */
    @Test
    public void addRejoinsFragmentsLeftBehindByRemove()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(0, 10));
        s.remove(Range.closedOpen(3, 7));
        assertEquals(List.of(Range.closedOpen(0, 3), Range.closedOpen(7, 10)), s.asRanges());
        s.add(Range.closedOpen(3, 7));
        assertEquals(List.of(Range.closedOpen(0, 10)), s.asRanges());
    }

    @Test
    public void addEmptyIsNoop()
    {
        RangeSet<Integer> s = new RangeSet<>();
        s.add(Range.closedOpen(5, 5));
        assertTrue(s.isEmpty());
        s.add(Range.openClosed(5, 5));
        assertTrue(s.isEmpty());
    }

    @Test
    public void addOpenNoIntegerStores()
    {
        // open(1,2) is cut-non-empty -> stored, though it contains no Integer.
        RangeSet<Integer> s = rs(Range.open(1, 2));
        assertFalse(s.isEmpty());
        assertEquals(List.of(Range.open(1, 2)), s.asRanges());
        assertFalse(s.contains(1));
        assertFalse(s.contains(2));
    }

    @Test
    public void removeSplits()
    {
        RangeSet<Integer> s = rs(Range.closed(1, 9));
        s.remove(Range.closedOpen(4, 7));
        assertEquals(List.of(Range.closedOpen(1, 4), Range.closed(7, 9)), s.asRanges());
    }

    @Test
    public void removeEmptyIsNoop()
    {
        RangeSet<Integer> s = rs(Range.closed(1, 9));
        s.remove(Range.closedOpen(5, 5));
        assertEquals(List.of(Range.closed(1, 9)), s.asRanges());
    }

    @Test
    public void removeAbutmentDoesNotSplit()
    {
        // remove([5,9)) abuts [1,5) at Below(5) -> no change.
        RangeSet<Integer> s = rs(Range.closedOpen(1, 5));
        s.remove(Range.closedOpen(5, 9));
        assertEquals(List.of(Range.closedOpen(1, 5)), s.asRanges());
    }

    @Test
    public void containsAndRangeContaining()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(1, 5), Range.closed(8, 9));
        assertTrue(s.contains(3));
        assertFalse(s.contains(6));
        assertEquals(Optional.of(Range.closedOpen(1, 5)), s.rangeContaining(3));
        assertEquals(Optional.empty(), s.rangeContaining(6));
    }

    @Test
    public void enclosesSingleRange()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(1, 3), Range.closedOpen(5, 9));
        // No single stored range encloses [2,6).
        assertFalse(s.encloses(Range.closedOpen(2, 6)));
        assertTrue(s.encloses(Range.closedOpen(1, 2)));
        assertTrue(s.enclosesAll(List.of(Range.closedOpen(1, 2), Range.closedOpen(5, 8))));
        assertFalse(s.enclosesAll(List.of(Range.closedOpen(1, 2), Range.closedOpen(2, 6))));
    }

    @Test
    public void intersectsCutAlgebra()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(1, 3), Range.closedOpen(5, 9));
        // cut-non-empty overlap with both -> true.
        assertTrue(s.intersects(Range.closedOpen(2, 6)));
        // cut-empty query -> false.
        assertFalse(s.intersects(Range.closedOpen(5, 5)));
        // abutment -> false ([3,5) abuts [5,9) at Below(5)).
        RangeSet<Integer> s2 = rs(Range.closedOpen(5, 9));
        assertFalse(s2.intersects(Range.closedOpen(3, 5)));
    }

    @Test
    public void intersectsOpenCutNonEmptyNoInteger()
    {
        // intersects(open(1,2)) vs stored (1,2) is TRUE (cut-non-empty), even
        // though no Integer lies in it.
        RangeSet<Integer> s = rs(Range.open(1, 2));
        assertTrue(s.intersects(Range.open(1, 2)));
    }

    @Test
    public void complementBasic()
    {
        RangeSet<Integer> s = rs(Range.closed(1, 5));
        assertEquals(List.of(Range.lessThan(1), Range.greaterThan(5)), s.complement().asRanges());
    }

    @Test
    public void complementAllIsEmpty()
    {
        RangeSet<Integer> s = rs(Range.all());
        assertTrue(s.complement().isEmpty());
    }

    @Test
    public void complementEmptyIsAll()
    {
        RangeSet<Integer> s = new RangeSet<>();
        assertEquals(List.of(Range.<Integer>all()), s.complement().asRanges());
    }

    @Test
    public void complementUnboundedNoSpuriousGap()
    {
        // complement(lessThan(10)) = {[10,+inf)}, no leading (-inf,..) gap.
        RangeSet<Integer> s = rs(Range.lessThan(10));
        assertEquals(List.of(Range.atLeast(10)), s.complement().asRanges());
    }

    @Test
    public void complementInvolution()
    {
        List<List<Range<Integer>>> cases = List.of(
                List.of(Range.closed(1, 5)),
                List.of(Range.open(1, 3), Range.open(3, 5)),
                List.of(Range.lessThan(10)),
                List.of(Range.closed(Integer.MIN_VALUE, 0), Range.openClosed(0, Integer.MAX_VALUE)),
                List.<Range<Integer>>of(),
                List.of(Range.all()));
        for (List<Range<Integer>> ranges : cases)
        {
            RangeSet<Integer> s = new RangeSet<>();
            s.addAll(ranges);
            RangeSet<Integer> cc = s.complement().complement();
            assertEquals(s.asRanges(), cc.asRanges(), "involution failed for " + ranges);
        }
    }

    @Test
    public void subRangeSetClips()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(1, 5), Range.closed(8, 9));
        RangeSet<Integer> sub = s.subRangeSet(Range.closedOpen(3, 6));
        assertEquals(List.of(Range.closedOpen(3, 5)), sub.asRanges());
    }

    @Test
    public void subRangeSetIndependentSnapshot()
    {
        RangeSet<Integer> s = rs(Range.closedOpen(1, 5));
        RangeSet<Integer> sub = s.subRangeSet(Range.closedOpen(2, 4));
        sub.add(Range.closed(100, 200));
        // mutating the snapshot must not touch the parent.
        assertEquals(List.of(Range.closedOpen(1, 5)), s.asRanges());
        // and mutating the parent must not touch the snapshot.
        s.add(Range.closed(50, 60));
        assertEquals(List.of(Range.closedOpen(2, 4), Range.closed(100, 200)), sub.asRanges());
    }

    @Test
    public void complementIndependentSnapshot()
    {
        RangeSet<Integer> s = rs(Range.closed(1, 5));
        RangeSet<Integer> c = s.complement();
        s.add(Range.closed(10, 20));
        // the complement snapshot is unaffected by parent mutation.
        assertEquals(List.of(Range.lessThan(1), Range.greaterThan(5)), c.asRanges());
    }

    @Test
    public void signedExtremesNoPlusMinusOne()
    {
        RangeSet<Integer> s = new RangeSet<>();
        s.add(Range.closed(Integer.MIN_VALUE, 0));
        s.add(Range.openClosed(0, Integer.MAX_VALUE));
        // [MIN,0] and (0,MAX] abut at Above(0) -> coalesce to [MIN, MAX].
        assertEquals(List.of(Range.closed(Integer.MIN_VALUE, Integer.MAX_VALUE)), s.asRanges());
        assertTrue(s.contains(Integer.MIN_VALUE));
        assertTrue(s.contains(Integer.MAX_VALUE));
        assertEquals(Optional.of(Range.closed(Integer.MIN_VALUE, Integer.MAX_VALUE)), s.span());
        // [MIN, MAX] is NOT all(): its complement is the two flanking gaps,
        // computed in cut space, no overflow.
        assertEquals(
                List.of(Range.lessThan(Integer.MIN_VALUE), Range.greaterThan(Integer.MAX_VALUE)),
                s.complement().asRanges());

        // all() over the whole domain DOES complement to empty, no overflow.
        RangeSet<Integer> whole = new RangeSet<>();
        whole.add(Range.all());
        assertTrue(whole.complement().isEmpty());
    }

    @Test
    public void clearEmpties()
    {
        RangeSet<Integer> s = rs(Range.closed(1, 9));
        s.clear();
        assertTrue(s.isEmpty());
        assertEquals(List.of(), s.asRanges());
    }

    @Test
    public void normalFormAfterSequence()
    {
        // After an op sequence the invariant (non-empty, pairwise non-connected,
        // ascending by lower cut) must hold.
        RangeSet<Integer> s = new RangeSet<>();
        s.add(Range.closed(1, 5));
        s.add(Range.closedOpen(10, 12));
        s.add(Range.closedOpen(12, 15));
        s.add(Range.open(20, 25));
        s.add(Range.closed(4, 11));
        List<Range<Integer>> v = s.asRanges();
        for (int i = 0; i + 1 < v.size(); i++)
        {
            Range<Integer> a = v.get(i);
            Range<Integer> b = v.get(i + 1);
            assertTrue(a.lowerEndpoint() == null
                            || b.lowerEndpoint() == null
                            || a.lowerEndpoint() < b.lowerEndpoint()
                            || !a.equals(b),
                    "ascending by lower cut");
            assertFalse(a.isConnected(b), "pairwise non-connected");
        }
        for (Range<Integer> r : v)
        {
            assertFalse(r.isEmpty(), "non-empty");
        }
    }
}

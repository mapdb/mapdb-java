// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.BoundType;
import org.mapdb.collections.impl.range.Range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native boundary tests for the Bound/Range value model
 * (spec/features/bound-range.md), mirroring the Interval boundary tests. These
 * pin the cut-semantics edges the shared 10-range scenarios cannot reach
 * cross-language: the constructor traps (RESERVED expect_panic), structural
 * equality of distinct empties, and the cut-defined (not contains-defined)
 * encloses / intersection contract.
 */
public class RangeBoundaryTest
{
    @Test
    public void containsAcrossShapes()
    {
        Range<Integer> closed = Range.closed(10, 20);
        assertTrue(closed.contains(10));
        assertTrue(closed.contains(15));
        assertTrue(closed.contains(20));
        assertFalse(closed.contains(9));
        assertFalse(closed.contains(21));

        Range<Integer> open = Range.open(10, 20);
        assertFalse(open.contains(10));
        assertFalse(open.contains(20));
        assertTrue(open.contains(11));
        assertTrue(open.contains(19));

        Range<Integer> closedOpen = Range.closedOpen(10, 20);
        assertTrue(closedOpen.contains(10));
        assertFalse(closedOpen.contains(20));

        Range<Integer> openClosed = Range.openClosed(10, 20);
        assertFalse(openClosed.contains(10));
        assertTrue(openClosed.contains(20));
    }

    @Test
    public void containsUnbounded()
    {
        Range<Integer> all = Range.all();
        assertTrue(all.contains(Integer.MIN_VALUE));
        assertTrue(all.contains(0));
        assertTrue(all.contains(Integer.MAX_VALUE));

        assertTrue(Range.atLeast(10).contains(10));
        assertFalse(Range.atLeast(10).contains(9));
        assertTrue(Range.atLeast(10).contains(Integer.MAX_VALUE));

        assertFalse(Range.greaterThan(10).contains(10));
        assertTrue(Range.greaterThan(10).contains(11));

        assertTrue(Range.lessThan(5).contains(4));
        assertFalse(Range.lessThan(5).contains(5));

        assertTrue(Range.atMost(5).contains(5));
        assertFalse(Range.atMost(5).contains(6));
    }

    @Test
    public void isEmptyCutSemanticsNotDiscrete()
    {
        // open(1,2) has no integer member but is NOT cut-empty (no DiscreteDomain).
        Range<Integer> open = Range.open(1, 2);
        assertFalse(open.isEmpty());
        assertFalse(open.contains(1));
        assertFalse(open.contains(2));

        // Unbounded ranges are never empty.
        assertFalse(Range.all().isEmpty());
        assertFalse(Range.atLeast(0).isEmpty());
        assertFalse(Range.lessThan(0).isEmpty());

        // singleton is not empty.
        assertFalse(Range.singleton(5).isEmpty());
        assertTrue(Range.singleton(5).contains(5));
    }

    @Test
    public void distinctEmptiesArePreservedAndUnequal()
    {
        Range<Integer> co = Range.closedOpen(5, 5);
        Range<Integer> oc = Range.openClosed(5, 5);
        assertTrue(co.isEmpty());
        assertTrue(oc.isEmpty());
        // Both empty but DISTINCT — no cross-shape canonicalization.
        assertNotEquals(co, oc);
        assertFalse(co.contains(5));
        assertFalse(oc.contains(5));
        assertEquals(BoundType.CLOSED, co.lowerBoundType());
        assertEquals(BoundType.OPEN, co.upperBoundType());
        assertEquals(BoundType.OPEN, oc.lowerBoundType());
        assertEquals(BoundType.CLOSED, oc.upperBoundType());
        // Empties at different positions are unequal.
        assertNotEquals(co, Range.closedOpen(6, 6));
        // equal cuts -> equal range, consistent hashCode.
        assertEquals(co, Range.closedOpen(5, 5));
        assertEquals(co.hashCode(), Range.closedOpen(5, 5).hashCode());
    }

    @Test
    public void boundTypesAndEndpoints()
    {
        Range<Integer> r = Range.closedOpen(10, 20);
        assertEquals(BoundType.CLOSED, r.lowerBoundType());
        assertEquals(BoundType.OPEN, r.upperBoundType());
        assertEquals(Integer.valueOf(10), r.lowerEndpoint());
        assertEquals(Integer.valueOf(20), r.upperEndpoint());
        assertTrue(r.hasLowerBound());
        assertTrue(r.hasUpperBound());

        Range<Integer> all = Range.all();
        assertNull(all.lowerBoundType());
        assertNull(all.upperBoundType());
        assertNull(all.lowerEndpoint());
        assertNull(all.upperEndpoint());
        assertFalse(all.hasLowerBound());
        assertFalse(all.hasUpperBound());
    }

    @Test
    public void enclosesIsCutDefinedNotContainsDefined()
    {
        Range<Integer> big = Range.closed(10, 30);
        assertTrue(big.encloses(Range.closed(15, 25)));
        assertFalse(big.encloses(Range.closed(5, 25)));
        // [10,30] encloses empty@20.
        assertTrue(big.encloses(Range.closedOpen(20, 20)));

        // [1,5) encloses empty@5 (= [5,5)) though 5 is NOT in [1,5).
        Range<Integer> half = Range.closedOpen(1, 5);
        assertTrue(half.encloses(Range.closedOpen(5, 5)));
        assertFalse(half.contains(5));
    }

    @Test
    public void connectedOverlapIntersectionPresentNonEmpty()
    {
        Range<Integer> a = Range.closed(10, 20);
        Range<Integer> b = Range.closed(15, 25);
        assertTrue(a.isConnected(b));
        Optional<Range<Integer>> i = a.intersection(b);
        assertTrue(i.isPresent());
        assertFalse(i.get().isEmpty());
        assertEquals(Range.closed(15, 20), i.get());
    }

    @Test
    public void connectedAbutIntersectionPresentCutEmpty()
    {
        // [10,20) & [20,30) -> connected, present cut-empty at (Below20,Below20).
        Range<Integer> a = Range.closedOpen(10, 20);
        Range<Integer> b = Range.closedOpen(20, 30);
        assertTrue(a.isConnected(b));
        Optional<Range<Integer>> i = a.intersection(b);
        assertTrue(i.isPresent());
        assertTrue(i.get().isEmpty());
        assertEquals(Range.closedOpen(20, 20), i.get());
        assertEquals(BoundType.CLOSED, i.get().lowerBoundType());
        assertEquals(BoundType.OPEN, i.get().upperBoundType());

        // [10,20] & (20,30) abuts at (Above20,Above20) -> a DISTINCT empty.
        Range<Integer> oc = Range.closed(10, 20).intersection(Range.open(20, 30)).get();
        assertTrue(oc.isEmpty());
        assertEquals(Range.openClosed(20, 20), oc);
        assertEquals(BoundType.OPEN, oc.lowerBoundType());
        assertEquals(BoundType.CLOSED, oc.upperBoundType());
    }

    @Test
    public void intersectionNoneOnlyWhenDisconnected()
    {
        // Disjoint -> Optional.empty().
        Range<Integer> a = Range.closedOpen(10, 15);
        Range<Integer> b = Range.closedOpen(20, 25);
        assertFalse(a.isConnected(b));
        assertTrue(a.intersection(b).isEmpty());

        // Unbounded abut is CONNECTED (present empty), NOT none.
        Range<Integer> lt = Range.lessThan(5);
        Range<Integer> al = Range.atLeast(5);
        assertTrue(lt.isConnected(al));
        Optional<Range<Integer>> abut = lt.intersection(al);
        assertTrue(abut.isPresent());
        assertTrue(abut.get().isEmpty());
        assertEquals(Range.closedOpen(5, 5), abut.get());

        // lessThan(5) & greaterThan(5) -> DISCONNECTED (5 is the gap) -> none.
        Range<Integer> gt = Range.greaterThan(5);
        assertFalse(lt.isConnected(gt));
        assertTrue(lt.intersection(gt).isEmpty());
    }

    @Test
    public void span()
    {
        Range<Integer> s = Range.closed(10, 15).span(Range.closed(20, 25));
        assertEquals(Range.closed(10, 25), s);
        assertEquals(Integer.valueOf(10), s.lowerEndpoint());
        assertEquals(Integer.valueOf(25), s.upperEndpoint());

        Range<Integer> su = Range.atLeast(10).span(Range.closed(0, 5));
        assertEquals(Range.atLeast(0), su);
        assertEquals(Integer.valueOf(0), su.lowerEndpoint());
        assertNull(su.upperEndpoint());
        assertEquals(BoundType.CLOSED, su.lowerBoundType());
        assertNull(su.upperBoundType());
    }

    @Test
    public void badOrderConstructorsThrow()
    {
        // closed(5,1): lower > upper.
        assertThrows(IllegalArgumentException.class, () -> Range.closed(5, 1));
        // open(3,3) = (Above(3), Below(3)) is lower > upper.
        assertThrows(IllegalArgumentException.class, () -> Range.open(3, 3));
        // open(5,1) likewise.
        assertThrows(IllegalArgumentException.class, () -> Range.open(5, 1));
        // closedOpen(v,v) / openClosed(v,v) are VALID empties (must not throw).
        assertTrue(Range.closedOpen(7, 7).isEmpty());
        assertTrue(Range.openClosed(7, 7).isEmpty());
        // closed(v,v) is the singleton (valid, non-empty).
        assertFalse(Range.closed(7, 7).isEmpty());
    }

    @Test
    public void nonNullEndpointsRequired()
    {
        assertThrows(NullPointerException.class, () -> Range.closed(null, 5));
        assertThrows(NullPointerException.class, () -> Range.atLeast(null));
        assertThrows(NullPointerException.class, () -> Range.singleton(null));
        assertThrows(NullPointerException.class, () -> Range.all().contains(null));
    }
}

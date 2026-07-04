// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.Pump.DuplicatePolicy;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;
import org.mapdb.collections.impl.stream.SortedStream;
import org.mapdb.collections.impl.tuple.Tuples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "A1" sorted-stream algebra
 * ({@link SortedStream}). Exercises k-way merge, set union / intersection /
 * difference, distinct, filter, merge-join (including duplicate-key Cartesian
 * groups), the structure adapters (packed sorted map/set, unsigned Roaring,
 * range set), the pump terminal, ascending-order validation, and the
 * single-use guard.
 */
public class SortedStreamTest
{
    private static List<Integer> drain(SortedStream<Integer> s)
    {
        return new ArrayList<>(s.toList());
    }

    private static SortedStream<Integer> ints(Integer... vals)
    {
        return SortedStream.ofSorted(Arrays.asList(vals));
    }

    // ---- merge / union / intersect / difference ----

    @Test
    public void mergeKeepsAllElementsIncludingDuplicates()
    {
        List<Integer> out = drain(ints(1, 3, 3, 5).mergeWith(ints(2, 3, 6)));
        assertEquals(Arrays.asList(1, 2, 3, 3, 3, 5, 6), out);
    }

    @Test
    public void mergeOfEmptyIsIdentity()
    {
        assertEquals(Arrays.asList(1, 2, 3), drain(ints(1, 2, 3).mergeWith(ints())));
        assertEquals(Arrays.asList(1, 2, 3), drain(ints().mergeWith(ints(1, 2, 3))));
        assertEquals(List.of(), drain(ints().mergeWith(ints())));
    }

    @Test
    public void unionDedupsAcrossAndWithinOperands()
    {
        List<Integer> out = drain(ints(1, 3, 3, 5).union(ints(2, 3, 6, 6)));
        assertEquals(Arrays.asList(1, 2, 3, 5, 6), out);
    }

    @Test
    public void intersectEmitsCommonKeysOnce()
    {
        List<Integer> out = drain(ints(1, 2, 2, 3, 5, 8).intersect(ints(2, 3, 3, 4, 8, 9)));
        assertEquals(Arrays.asList(2, 3, 8), out);
    }

    @Test
    public void intersectDisjointIsEmpty()
    {
        assertEquals(List.of(), drain(ints(1, 3, 5).intersect(ints(2, 4, 6))));
    }

    @Test
    public void differenceKeepsReceiverOnlyKeys()
    {
        List<Integer> out = drain(ints(1, 2, 2, 3, 5, 8).difference(ints(2, 3, 3, 8)));
        assertEquals(Arrays.asList(1, 5), out);
    }

    @Test
    public void differenceWithEmptySubtrahendDedups()
    {
        assertEquals(Arrays.asList(1, 2, 3), drain(ints(1, 1, 2, 3, 3).difference(ints())));
    }

    @Test
    public void distinctCollapsesRuns()
    {
        assertEquals(Arrays.asList(1, 2, 3, 4), drain(ints(1, 1, 1, 2, 3, 3, 4).distinct()));
    }

    @Test
    public void filterPreservesOrder()
    {
        assertEquals(Arrays.asList(2, 4, 6), drain(ints(1, 2, 3, 4, 5, 6).filter(x -> x % 2 == 0)));
    }

    // ---- structure adapters ----

    @Test
    public void adaptsPackedSortedSetAndMap()
    {
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(Arrays.asList(10, 20, 30));
        assertEquals(Arrays.asList(10, 20, 30), drain(SortedStream.of(set)));

        ImmutableSortedMap<Integer, String> map = ImmutableSortedMap.fromSorted(
                Arrays.asList(1, 2, 3), Arrays.asList("a", "b", "c"));
        assertEquals(Arrays.asList(1, 2, 3), drain(SortedStream.keysOf(map)));

        List<String> vals = new ArrayList<>();
        SortedStream.entriesOf(map).forEach(e -> vals.add(e.getValue()));
        assertEquals(Arrays.asList("a", "b", "c"), vals);
    }

    @Test
    public void roaringStreamsInUnsignedOrderIncludingNegativeReinterpret()
    {
        RoaringU32 r = new RoaringU32();
        r.add(1);
        r.add(7);
        r.add(-1);          // u32 0xFFFFFFFF — must sort LAST
        r.add(0);
        List<Integer> out = drain(SortedStream.ofRoaring(r));
        assertEquals(Arrays.asList(0, 1, 7, -1), out);
    }

    @Test
    public void roaringStreamIntersectMatchesBitmapAnd()
    {
        RoaringU32 a = new RoaringU32();
        RoaringU32 b = new RoaringU32();
        for (int v : new int[] {0, 2, 4, 6, 8, -1})
        {
            a.add(v);
        }
        for (int v : new int[] {2, 3, 6, 7, -1})
        {
            b.add(v);
        }
        List<Integer> viaStream = drain(SortedStream.ofRoaring(a).intersect(SortedStream.ofRoaring(b)));

        int[] viaBitmap = a.and(b).toSortedArray();
        List<Integer> expected = new ArrayList<>();
        for (int v : viaBitmap)
        {
            expected.add(v);
        }
        assertEquals(expected, viaStream);
        assertEquals(Arrays.asList(2, 6, -1), viaStream);
    }

    @Test
    public void rangeSetStreamsRangesByLowerEndpoint()
    {
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.closedOpen(10, 20));
        rs.add(Range.closedOpen(0, 5));
        rs.add(Range.closedOpen(30, 40));
        List<Integer> lowers = new ArrayList<>();
        SortedStream.ofRanges(rs).forEach(r -> lowers.add(r.lowerEndpoint()));
        assertEquals(Arrays.asList(0, 10, 30), lowers);
    }

    // ---- merge-join ----

    @Test
    public void mergeJoinInnerEquiJoin()
    {
        ImmutableSortedMap<Integer, String> left = ImmutableSortedMap.fromSorted(
                Arrays.asList(1, 2, 4), Arrays.asList("Ann", "Bob", "Dan"));
        ImmutableSortedMap<Integer, String> right = ImmutableSortedMap.fromSorted(
                Arrays.asList(2, 3, 4), Arrays.asList("Eng", "Ops", "Sci"));

        SortedStream<Pair<Integer, String>> joined = SortedStream.mergeJoin(
                SortedStream.entriesOf(left), java.util.Map.Entry::getKey,
                SortedStream.entriesOf(right), java.util.Map.Entry::getKey,
                Comparator.<Integer>naturalOrder(),
                (l, r) -> Tuples.pair(l.getKey(), l.getValue() + "/" + r.getValue()),
                Comparator.comparing(Pair::getOne));

        List<String> rows = new ArrayList<>();
        joined.forEach(p -> rows.add(p.getOne() + "=" + p.getTwo()));
        assertEquals(Arrays.asList("2=Bob/Eng", "4=Dan/Sci"), rows);
    }

    @Test
    public void mergeJoinEmitsCartesianProductPerDuplicateKey()
    {
        // Two left rows and two right rows share key 1 -> 4 output rows.
        List<Pair<Integer, String>> leftRows = Arrays.asList(
                Tuples.pair(1, "L1a"), Tuples.pair(1, "L1b"), Tuples.pair(2, "L2"));
        List<Pair<Integer, String>> rightRows = Arrays.asList(
                Tuples.pair(1, "R1a"), Tuples.pair(1, "R1b"), Tuples.pair(3, "R3"));

        Comparator<Pair<Integer, String>> byKey = Comparator.comparing(Pair::getOne);
        SortedStream<Pair<Integer, String>> l = SortedStream.ofSorted(leftRows, byKey);
        SortedStream<Pair<Integer, String>> r = SortedStream.ofSorted(rightRows, byKey);

        SortedStream<String> joined = SortedStream.mergeJoin(
                l, Pair::getOne,
                r, Pair::getOne,
                Comparator.<Integer>naturalOrder(),
                (a, b) -> a.getTwo() + "+" + b.getTwo(),
                Comparator.naturalOrder());

        // Cartesian for key 1: L1a×{R1a,R1b}, L1b×{R1a,R1b}; key 2/3 no match.
        assertEquals(
                Arrays.asList("L1a+R1a", "L1a+R1b", "L1b+R1a", "L1b+R1b"),
                new ArrayList<>(joined.toList()));
    }

    // ---- pump terminal (compaction) ----

    @Test
    public void intoPumpBuildsSortedSet()
    {
        MutableSortedSet<Integer> set = ints(1, 3, 5).union(ints(2, 4, 6))
                .into(Pump.treeSortedSet(Comparator.<Integer>naturalOrder(), DuplicatePolicy.ERROR));
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6), new ArrayList<>(set));
    }

    @Test
    public void mergeToListMaterializesMultiset()
    {
        MutableList<Integer> merged = ints(1, 4, 7).mergeWith(ints(2, 3, 8)).toList();
        assertEquals(Arrays.asList(1, 2, 3, 4, 7, 8), new ArrayList<>(merged));
    }

    @Test
    public void intoPumpPropagatesDuplicateErrorFromMerge()
    {
        // mergeWith keeps duplicates; a strict (ERROR) sink must reject the dup
        // it receives, crossing the Sink poisoning path from the stream side.
        SortedStream<Integer> withDup = ints(1, 2, 3).mergeWith(ints(2, 4));
        assertThrows(RuntimeException.class, () ->
                withDup.into(Pump.treeSortedSet(Comparator.<Integer>naturalOrder(), DuplicatePolicy.ERROR)));
    }

    // ---- contract: validation + single-use ----

    @Test
    public void ofSortedValidatesAscendingOnConsumption()
    {
        SortedStream<Integer> bad = SortedStream.ofSorted(Arrays.asList(1, 5, 2));
        assertThrows(IllegalStateException.class, bad::toList);
    }

    @Test
    public void streamIsSingleUse()
    {
        SortedStream<Integer> s = ints(1, 2, 3);
        s.toList();
        assertThrows(IllegalStateException.class, s::toList);
    }

    @Test
    public void operatorConsumesOperands()
    {
        SortedStream<Integer> a = ints(1, 2, 3);
        SortedStream<Integer> b = ints(4, 5, 6);
        a.mergeWith(b);
        assertThrows(IllegalStateException.class, a::toList);
        assertThrows(IllegalStateException.class, b::toList);
    }

    @Test
    public void countTerminal()
    {
        assertTrue(ints(1, 2, 2, 3).union(ints(3, 4)).count() == 4);
    }

    // ---- tie stability + representative identity (needs distinguishable equals) ----

    @Test
    public void mergeIsStableAndSetOpsUseReceiverRepresentative()
    {
        Comparator<Pair<Integer, String>> byKey = Comparator.comparing(Pair::getOne);
        List<Pair<Integer, String>> a = Arrays.asList(Tuples.pair(1, "a"), Tuples.pair(2, "L"));
        List<Pair<Integer, String>> b = Arrays.asList(Tuples.pair(2, "R"), Tuples.pair(3, "c"));

        // mergeWith on a tie emits the RECEIVER's element first (L before R).
        List<String> merged = new ArrayList<>();
        SortedStream.ofSorted(a, byKey).mergeWith(SortedStream.ofSorted(b, byKey))
                .forEach(p -> merged.add(p.getTwo()));
        assertEquals(Arrays.asList("a", "L", "R", "c"), merged);

        // intersect emits the RECEIVER's representative (L, not R).
        Pair<Integer, String> only = SortedStream.ofSorted(a, byKey)
                .intersect(SortedStream.ofSorted(b, byKey)).toList().get(0);
        assertEquals("L", only.getTwo());
    }

    // ---- merge-join edge cases ----

    @Test
    public void mergeJoinEmptySideYieldsNothing()
    {
        Comparator<Integer> nat = Comparator.naturalOrder();
        assertEquals(List.of(), new ArrayList<>(SortedStream.mergeJoin(
                ints(), (Integer x) -> x, ints(1, 2, 3), (Integer x) -> x,
                nat, (l, r) -> l, nat).toList()));
        assertEquals(List.of(), new ArrayList<>(SortedStream.mergeJoin(
                ints(1, 2, 3), (Integer x) -> x, ints(), (Integer x) -> x,
                nat, (l, r) -> l, nat).toList()));
    }

    @Test
    public void mergeJoinAsymmetricGroupsAndInterleavedNonMatches()
    {
        // left keys 1,1,2,4,4,4 ; right keys 1,2,2,3,4 -> 2x1, 1x2, (3 skipped), 3x1
        List<Pair<Integer, String>> left = Arrays.asList(
                Tuples.pair(1, "la"), Tuples.pair(1, "lb"), Tuples.pair(2, "lc"),
                Tuples.pair(4, "ld"), Tuples.pair(4, "le"), Tuples.pair(4, "lf"));
        List<Pair<Integer, String>> right = Arrays.asList(
                Tuples.pair(1, "ra"), Tuples.pair(2, "rb"), Tuples.pair(2, "rc"),
                Tuples.pair(3, "rd"), Tuples.pair(4, "rf"));
        Comparator<Pair<Integer, String>> byKey = Comparator.comparing(Pair::getOne);

        SortedStream<Pair<Integer, String>> joined = SortedStream.mergeJoin(
                SortedStream.ofSorted(left, byKey), Pair::getOne,
                SortedStream.ofSorted(right, byKey), Pair::getOne,
                Comparator.<Integer>naturalOrder(),
                (l, r) -> Tuples.pair(l.getOne(), l.getTwo() + "-" + r.getTwo()),
                Comparator.comparing(Pair::getOne));

        List<String> rows = new ArrayList<>();
        joined.forEach(p -> rows.add(p.getTwo()));
        assertEquals(Arrays.asList(
                "la-ra", "lb-ra",        // key 1: 2x1
                "lc-rb", "lc-rc",        // key 2: 1x2 (consecutive matching group after key 1)
                "ld-rf", "le-rf", "lf-rf" // key 4: 3x1 (last group, after skipped right-only key 3)
        ), rows);
    }

    // ---- source-adapter edges ----

    @Test
    public void ofRoaringEmpty()
    {
        assertEquals(List.of(), drain(SortedStream.ofRoaring(new RoaringU32())));
    }

    @Test
    public void ofRangesUnboundedLowerSortsFirst()
    {
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.closedOpen(10, 20));
        rs.add(Range.lessThan(0));      // (-inf, 0): no lower bound
        List<Boolean> hasLower = new ArrayList<>();
        SortedStream.ofRanges(rs).forEach(r -> hasLower.add(r.hasLowerBound()));
        assertEquals(Arrays.asList(false, true), hasLower);
    }

    // ---- cursor contract: poisoning + iterator exhaustion ----

    @Test
    public void iteratorIsSingleUse()
    {
        SortedStream<Integer> s = ints(1, 2, 3);
        s.iterator();
        assertThrows(IllegalStateException.class, s::toList);
    }

    @Test
    public void exhaustedIteratorThrowsNoSuchElement()
    {
        Iterator<Integer> it = ints(1).iterator();
        it.next();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    public void operatorCursorPoisonsOnSourceError()
    {
        // A descending hand-sorted source fails validation mid-merge; the
        // operator cursor must poison (not resume over half-advanced operands).
        Iterator<Integer> it = SortedStream.ofSorted(Arrays.asList(3, 1))
                .mergeWith(ints(2)).iterator();
        assertThrows(IllegalStateException.class, () ->
        {
            while (it.hasNext())
            {
                it.next();
            }
        });
        assertFalse(it.hasNext());
    }
}

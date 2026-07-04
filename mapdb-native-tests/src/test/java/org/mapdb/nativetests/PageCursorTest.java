// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.paginate.PageCursor;
import org.mapdb.collections.impl.paginate.PageCursor.Direction;
import org.mapdb.collections.impl.paginate.PageCursor.Page;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "C1" pagination cursor
 * ({@link PageCursor}). Exercises full and bounded ascending / descending
 * scans, batch reassembly against the map's own {@code entries()} /
 * {@code descendingEntries()}, keyset seek ({@code startAfter}), the exhaustion
 * contract, {@code consumedRank}, the i32 wire-token round-trip across every
 * {@link Range} shape, cross-snapshot keyset stability, and the bad-input traps.
 */
public class PageCursorTest
{
    /** {@code {0:0, 2:20, 4:40, ..., 2(n-1):...}} — even keys, value = key*10. */
    private static ImmutableSortedMap<Integer, Integer> evenMap(int n)
    {
        int[] keys = new int[n];
        int[] vals = new int[n];
        for (int i = 0; i < n; i++)
        {
            keys[i] = 2 * i;
            vals[i] = 2 * i * 10;
        }
        return ImmutableSortedMap.fromSorted(keys, vals);
    }

    /** Drain a cursor fully, batch by batch, collecting emitted entries in order. */
    private static List<Map.Entry<Integer, Integer>> drain(
            PageCursor<Integer, Integer> start, ImmutableSortedMap<Integer, Integer> map, int batch)
    {
        List<Map.Entry<Integer, Integer>> out = new ArrayList<>();
        PageCursor<Integer, Integer> c = start;
        int guard = 0;
        while (true)
        {
            Page<Integer, Integer> p = c.page(map, batch);
            out.addAll(p.entries());
            if (!p.hasNext())
            {
                break;
            }
            c = p.next().orElseThrow();
            if (++guard > 100000)
            {
                throw new AssertionError("cursor did not terminate");
            }
        }
        return out;
    }

    private static List<Map.Entry<Integer, Integer>> reversed(List<Map.Entry<Integer, Integer>> in)
    {
        List<Map.Entry<Integer, Integer>> out = new ArrayList<>(in);
        java.util.Collections.reverse(out);
        return out;
    }

    // ---- full scans reassemble the map, at every batch size ----------------

    @Test
    public void ascFullScanReassemblesEntriesForAllBatchSizes()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(13);
        for (int batch = 1; batch <= 20; batch++)
        {
            List<Map.Entry<Integer, Integer>> got =
                    drain(PageCursor.start(Direction.ASC), map, batch);
            assertEquals(map.entries(), got, "batch=" + batch);
        }
    }

    @Test
    public void descFullScanReassemblesDescendingEntriesForAllBatchSizes()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(13);
        for (int batch = 1; batch <= 20; batch++)
        {
            List<Map.Entry<Integer, Integer>> got =
                    drain(PageCursor.start(Direction.DESC), map, batch);
            assertEquals(map.descendingEntries(), got, "batch=" + batch);
        }
    }

    // ---- bounded windows ---------------------------------------------------

    @Test
    public void boundedWindowMatchesRangeEntriesAsc()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(10); // keys 0,2,...,18
        Range<Integer> r = Range.closedOpen(4, 14); // keys 4,6,8,10,12
        List<Map.Entry<Integer, Integer>> want = map.rangeEntries(r);
        for (int batch = 1; batch <= 7; batch++)
        {
            assertEquals(want, drain(PageCursor.start(r, Direction.ASC), map, batch), "batch=" + batch);
        }
    }

    @Test
    public void boundedWindowMatchesDescendingRangeEntriesDesc()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(10);
        Range<Integer> r = Range.openClosed(4, 14); // keys 6,8,10,12,14
        List<Map.Entry<Integer, Integer>> want = map.descendingRangeEntries(r);
        for (int batch = 1; batch <= 7; batch++)
        {
            assertEquals(want, drain(PageCursor.start(r, Direction.DESC), map, batch), "batch=" + batch);
        }
    }

    @Test
    public void halfBoundedWindows()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(8); // 0..14
        assertEquals(map.rangeEntries(Range.atLeast(10)),
                drain(PageCursor.start(Range.atLeast(10), Direction.ASC), map, 3));
        assertEquals(map.rangeEntries(Range.lessThan(6)),
                drain(PageCursor.start(Range.lessThan(6), Direction.ASC), map, 3));
        assertEquals(map.rangeEntries(Range.greaterThan(9)),
                drain(PageCursor.start(Range.greaterThan(9), Direction.ASC), map, 3));
    }

    @Test
    public void emptyWindowYieldsNothing()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(10);
        // no even key in (5,6)
        Page<Integer, Integer> p = PageCursor.<Integer, Integer>start(Range.open(5, 6), Direction.ASC)
                .page(map, 10);
        assertTrue(p.entries().isEmpty());
        assertFalse(p.hasNext());
    }

    // ---- degenerate maps ---------------------------------------------------

    @Test
    public void emptyMapExhaustsImmediately()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(0);
        for (Direction d : Direction.values())
        {
            Page<Integer, Integer> p = PageCursor.<Integer, Integer>start(d).page(map, 5);
            assertTrue(p.entries().isEmpty());
            assertFalse(p.hasNext());
        }
    }

    @Test
    public void singletonMap()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(1); // {0:0}
        assertEquals(map.entries(), drain(PageCursor.start(Direction.ASC), map, 5));
        assertEquals(map.entries(), drain(PageCursor.start(Direction.DESC), map, 5));
        Page<Integer, Integer> p = PageCursor.<Integer, Integer>start(Direction.ASC).page(map, 5);
        assertEquals(1, p.entries().size());
        assertFalse(p.hasNext(), "single element in a full batch terminates cleanly");
    }

    // ---- exhaustion contract ----------------------------------------------

    @Test
    public void fullBatchThatExactlyExhaustsHasNoNext()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(6);
        Page<Integer, Integer> p = PageCursor.<Integer, Integer>start(Direction.ASC).page(map, 6);
        assertEquals(6, p.entries().size());
        assertFalse(p.hasNext());
    }

    @Test
    public void partialLastBatch()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(7);
        PageCursor<Integer, Integer> c = PageCursor.start(Direction.ASC);
        Page<Integer, Integer> p1 = c.page(map, 4);
        assertEquals(4, p1.entries().size());
        assertTrue(p1.hasNext());
        Page<Integer, Integer> p2 = p1.next().orElseThrow().page(map, 4);
        assertEquals(3, p2.entries().size());
        assertFalse(p2.hasNext());
    }

    // ---- seek (startAfter) -------------------------------------------------

    @Test
    public void startAfterPresentKeyAsc()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(6); // 0,2,4,6,8,10
        List<Map.Entry<Integer, Integer>> got =
                drain(PageCursor.startAfter(Range.all(), Direction.ASC, 4), map, 2);
        assertEquals(map.rangeEntries(Range.greaterThan(4)), got); // 6,8,10
    }

    @Test
    public void startAfterAbsentKeyAsc()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(6); // 0,2,4,6,8,10
        // 5 is absent; next strictly-greater key is 6.
        List<Map.Entry<Integer, Integer>> got =
                drain(PageCursor.startAfter(Range.all(), Direction.ASC, 5), map, 2);
        assertEquals(map.rangeEntries(Range.greaterThan(5)), got); // 6,8,10
    }

    @Test
    public void startAfterDesc()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(6); // 0,2,4,6,8,10
        List<Map.Entry<Integer, Integer>> got =
                drain(PageCursor.startAfter(Range.all(), Direction.DESC, 6), map, 2);
        // descending, strictly less than 6: 4,2,0
        assertEquals(reversed(map.rangeEntries(Range.lessThan(6))), got);
    }

    @Test
    public void startAfterPastEndExhausts()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(6);
        Page<Integer, Integer> p =
                PageCursor.<Integer, Integer>startAfter(Range.all(), Direction.ASC, 999).page(map, 5);
        assertTrue(p.entries().isEmpty());
        assertFalse(p.hasNext());
    }

    // ---- consumedRank ------------------------------------------------------

    @Test
    public void consumedRankProgresses()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(10);
        PageCursor<Integer, Integer> c = PageCursor.start(Direction.ASC);
        assertEquals(0, c.consumedRank(map));
        Page<Integer, Integer> p = c.page(map, 3);
        assertEquals(3, p.next().orElseThrow().consumedRank(map));
        p = p.next().orElseThrow().page(map, 3);
        assertEquals(6, p.next().orElseThrow().consumedRank(map));
    }

    @Test
    public void consumedRankDescAndBounded()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(10); // 0..18
        Range<Integer> r = Range.closed(4, 14); // 4,6,8,10,12,14 -> 6 in window
        PageCursor<Integer, Integer> c = PageCursor.start(r, Direction.DESC);
        assertEquals(0, c.consumedRank(map));
        Page<Integer, Integer> p = c.page(map, 2); // emits 14,12
        assertEquals(2, p.next().orElseThrow().consumedRank(map));
    }

    // ---- token round-trip --------------------------------------------------

    private static void assertTokenRoundTrips(PageCursor<Integer, Integer> c)
    {
        String tok = PageCursor.encodeI32(c);
        PageCursor<Integer, Integer> back = PageCursor.decodeI32(tok);
        assertEquals(c, back, tok);
        assertEquals(tok, PageCursor.encodeI32(back), "re-encode is stable");
    }

    @Test
    public void tokenRoundTripsEveryRangeShapeAndDirection()
    {
        List<Range<Integer>> shapes = List.of(
                Range.all(),
                Range.atLeast(-7),
                Range.greaterThan(3),
                Range.atMost(9),
                Range.lessThan(-2),
                Range.closed(-5, 5),
                Range.open(-5, 5),
                Range.openClosed(0, 100),
                Range.closedOpen(0, 100),
                Range.singleton(42));
        for (Range<Integer> r : shapes)
        {
            for (Direction d : Direction.values())
            {
                assertTokenRoundTrips(PageCursor.start(r, d));
                assertTokenRoundTrips(PageCursor.startAfter(r, d, -13));
                assertTokenRoundTrips(PageCursor.startAfter(r, d, Integer.MAX_VALUE));
                assertTokenRoundTrips(PageCursor.startAfter(r, d, Integer.MIN_VALUE));
            }
        }
    }

    @Test
    public void tokenResumesAnEquivalentScan()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(20);
        // scan first two batches directly, then serialise and continue via token
        PageCursor<Integer, Integer> c = PageCursor.start(Range.atLeast(4), Direction.ASC);
        List<Map.Entry<Integer, Integer>> got = new ArrayList<>();
        Page<Integer, Integer> p = c.page(map, 3);
        got.addAll(p.entries());
        String tok = PageCursor.encodeI32(p.next().orElseThrow());

        PageCursor<Integer, Integer> resumed = PageCursor.decodeI32(tok);
        got.addAll(drain(resumed, map, 3));

        assertEquals(map.rangeEntries(Range.atLeast(4)), got);
    }

    // ---- keyset stability across snapshots ---------------------------------

    @Test
    public void keysetResumeIsStableAcrossAnInsertBeforeTheCursor()
    {
        // snapshot A: 0,2,4,6,8 ; page one batch of 2 -> {0,2}, resume after 2
        ImmutableSortedMap<Integer, Integer> a = evenMap(5);
        Page<Integer, Integer> p = PageCursor.<Integer, Integer>start(Direction.ASC).page(a, 2);
        assertEquals(List.of(0, 2), keysOf(p.entries()));
        String tok = PageCursor.encodeI32(p.next().orElseThrow());

        // snapshot B inserts key 1 (before the resume point) — a raw offset would
        // now re-list key 2; keyset resumes strictly after key 2 regardless.
        ImmutableSortedMap<Integer, Integer> b = ImmutableSortedMap.fromSorted(
                new int[] {0, 1, 2, 4, 6, 8}, new int[] {0, 10, 20, 40, 60, 80});
        List<Map.Entry<Integer, Integer>> rest = drain(PageCursor.decodeI32(tok), b, 2);
        assertEquals(List.of(4, 6, 8), keysOf(rest));
    }

    private static List<Integer> keysOf(List<Map.Entry<Integer, Integer>> es)
    {
        List<Integer> out = new ArrayList<>(es.size());
        for (Map.Entry<Integer, Integer> e : es)
        {
            out.add(e.getKey());
        }
        return out;
    }

    // ---- bad input traps ---------------------------------------------------

    @Test
    public void badBatchSizeTraps()
    {
        ImmutableSortedMap<Integer, Integer> map = evenMap(3);
        PageCursor<Integer, Integer> c = PageCursor.start(Direction.ASC);
        assertThrows(IllegalArgumentException.class, () -> c.page(map, 0));
        assertThrows(IllegalArgumentException.class, () -> c.page(map, -1));
    }

    @Test
    public void malformedTokensTrap()
    {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("nope"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|x|*|*|*"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a|*|*"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a|z5|*|*"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a|c5|*|abc"));
        // empty bound fields are malformed, not a JVM StringIndexOutOfBounds (codex review)
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a||*|*"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a|*||*"));
        // bound-type char present but no integer payload
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decodeI32("mdbpc1|a|c|*|*"));
    }
}

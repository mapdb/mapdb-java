// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native (port-specific) tests for the boxed {@link RangeMap}
 * (spec/features/range-set-map.md), mirroring the Rust reference battery and
 * pinning the parity traps + the native-only obligations: put-split /
 * put-no-coalesce / putCoalescing equal-value both-sides, getEntry, snapshot
 * independence of {@code subRangeMap}, and no-{@code +-1} signed-extreme
 * arithmetic.
 */
public class RangeMapTest
{
    /** Render the entries as {@code (range, value)} pairs for assertion. */
    private static List<Object[]> entries(RangeMap<Integer, Integer> m)
    {
        List<Object[]> out = new ArrayList<>();
        for (RangeMap.Entry<Integer, Integer> e : m.asMapOfRanges())
        {
            out.add(new Object[] {e.getRange(), e.getValue()});
        }
        return out;
    }

    private static void assertEntries(RangeMap<Integer, Integer> m, Object... rangeThenValue)
    {
        List<Object[]> actual = entries(m);
        assertEquals(rangeThenValue.length / 2, actual.size(), "entry count");
        for (int i = 0; i < actual.size(); i++)
        {
            assertEquals(rangeThenValue[2 * i], actual.get(i)[0], "entry " + i + " range");
            assertEquals(rangeThenValue[2 * i + 1], actual.get(i)[1], "entry " + i + " value");
        }
    }

    @Test
    public void putBasic()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closed(8, 9), 200);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closed(8, 9), 200);
        assertEquals(Optional.of(100), m.get(3));
        assertEquals(Optional.empty(), m.get(6));
        assertEquals(Optional.of(200), m.get(8));
    }

    @Test
    public void putOverwriteClips()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(3, 9), 200);
        assertEntries(m, Range.closedOpen(1, 3), 100, Range.closedOpen(3, 9), 200);
        assertEquals(Optional.of(100), m.get(2));
        assertEquals(Optional.of(200), m.get(4));
        assertEquals(Optional.of(200), m.get(8));
    }

    @Test
    public void putSplitStraddle()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 9), 100);
        m.put(Range.closedOpen(3, 5), 200);
        assertEntries(m,
                Range.closedOpen(1, 3), 100,
                Range.closedOpen(3, 5), 200,
                Range.closedOpen(5, 9), 100);
        assertEquals(Optional.of(100), m.get(2));
        assertEquals(Optional.of(200), m.get(4));
        assertEquals(Optional.of(100), m.get(6));
    }

    @Test
    public void putDoesNotCoalesce()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(5, 9), 100);
        // TWO entries even though value equal and they abut.
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 100);
        assertEquals(Optional.of(100), m.get(5));
    }

    @Test
    public void putCoalescingEqualValueAbut()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.putCoalescing(Range.closedOpen(5, 9), 100);
        assertEntries(m, Range.closedOpen(1, 9), 100);
    }

    @Test
    public void putCoalescingDifferentValueNoMerge()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.putCoalescing(Range.closedOpen(5, 9), 200);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 200);
    }

    @Test
    public void putCoalescingBothSides()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(9, 12), 100);
        m.putCoalescing(Range.closedOpen(5, 9), 100);
        assertEntries(m, Range.closedOpen(1, 12), 100);
    }

    @Test
    public void removeSplits()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 9), 100);
        m.remove(Range.closedOpen(4, 7));
        assertEntries(m, Range.closedOpen(1, 4), 100, Range.closedOpen(7, 9), 100);
        assertEquals(Optional.empty(), m.get(5));
    }

    @Test
    public void getEntryLookup()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        Optional<RangeMap.Entry<Integer, Integer>> e = m.getEntry(3);
        assertTrue(e.isPresent());
        assertEquals(Range.closedOpen(1, 5), e.get().getRange());
        assertEquals(Integer.valueOf(100), e.get().getValue());
        assertEquals(Optional.empty(), m.getEntry(6));
    }

    @Test
    public void spanOverEntries()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closed(8, 9), 200);
        // span = [lower of first entry, upper of last entry] = [1, 9].
        assertEquals(Optional.of(Range.closed(1, 9)), m.span());
    }

    @Test
    public void emptyPutIsNoop()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(5, 5), 100);
        assertTrue(m.isEmpty());
        assertEntries(m);
    }

    @Test
    public void subRangeMapClipsSnapshot()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closed(8, 9), 200);
        RangeMap<Integer, Integer> sub = m.subRangeMap(Range.closedOpen(3, 6));
        assertEntries(sub, Range.closedOpen(3, 5), 100);
        // snapshot independence: mutate the parent, sub unchanged.
        m.put(Range.closed(3, 3), 999);
        assertEntries(sub, Range.closedOpen(3, 5), 100);
        // mutating the snapshot does not touch the parent.
        sub.put(Range.closed(50, 60), 7);
        assertEquals(Optional.empty(), m.get(55));
    }

    @Test
    public void signedExtremesNoPlusMinusOne()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(Integer.MIN_VALUE, 0), 1);
        m.put(Range.closed(0, Integer.MAX_VALUE), 2);
        assertEquals(Optional.of(1), m.get(Integer.MIN_VALUE));
        assertEquals(Optional.of(2), m.get(0));
        assertEquals(Optional.of(2), m.get(Integer.MAX_VALUE));
    }

    @Test
    public void clearEmpties()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 9), 100);
        m.clear();
        assertTrue(m.isEmpty());
        assertEntries(m);
    }

    @Test
    public void normalFormDisjointAfterSequence()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 10), 1);
        m.put(Range.closedOpen(3, 5), 2);
        m.put(Range.closedOpen(7, 20), 3);
        m.putCoalescing(Range.closedOpen(20, 25), 3);
        List<RangeMap.Entry<Integer, Integer>> v = m.asMapOfRanges();
        for (int i = 0; i + 1 < v.size(); i++)
        {
            Range<Integer> a = v.get(i).getRange();
            Range<Integer> b = v.get(i + 1).getRange();
            // ascending by lower cut.
            assertTrue(a.lowerEndpoint() < b.lowerEndpoint(), "ascending");
            // disjoint: no cut-non-empty intersection between entries.
            Optional<Range<Integer>> inter = a.intersection(b);
            assertTrue(inter.isEmpty() || inter.get().isEmpty(), "disjoint");
        }
        for (RangeMap.Entry<Integer, Integer> e : v)
        {
            assertFalse(e.getRange().isEmpty(), "non-empty");
        }
    }
}

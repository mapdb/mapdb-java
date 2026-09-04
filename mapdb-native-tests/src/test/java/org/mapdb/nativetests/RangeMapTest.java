// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native (port-specific) tests for the boxed {@link RangeMap}
 * (spec/features/range-set-map.md), mirroring the Rust reference battery and
 * pinning the parity traps + the native-only obligations: put-split /
 * put-coalesces / equal-value both-sides / value-barrier / order-independence,
 * getEntry, snapshot independence of {@code subRangeMap}, and no-{@code +-1}
 * signed-extreme arithmetic — plus a seeded put/remove sequence checked
 * against a dense oracle after every op.
 */
public class RangeMapTest
{
    /** Render the entries as {@code (range, value)} pairs for assertion. */
    private static List<Object[]> entries(RangeMap<Integer, ?> m)
    {
        List<Object[]> out = new ArrayList<>();
        for (RangeMap.Entry<Integer, ?> e : m.asMapOfRanges())
        {
            out.add(new Object[] {e.getRange(), e.getValue()});
        }
        return out;
    }

    private static void assertEntries(RangeMap<Integer, ?> m, Object... rangeThenValue)
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
    public void putCoalescesEqualValueAbut()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(5, 9), 100);
        // ONE entry: equal value and abutting, so plain put merges them.
        // Guava's TreeRangeMap leaves two here; this is the divergence.
        assertEntries(m, Range.closedOpen(1, 9), 100);
        assertEquals(Optional.of(100), m.get(5));
    }

    @Test
    public void putDifferentValueNoMerge()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(5, 9), 200);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 200);
    }

    @Test
    public void putCoalescesBothSides()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(9, 12), 100);
        m.put(Range.closedOpen(5, 9), 100);
        assertEntries(m, Range.closedOpen(1, 12), 100);
    }

    @Test
    public void putCoalescesChainAscendingOrder()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 2), 7);
        m.put(Range.closedOpen(2, 3), 7);
        // A chain never forms: the map is already [1,3) here.
        assertEntries(m, Range.closedOpen(1, 3), 7);
        m.put(Range.closedOpen(3, 4), 7);
        assertEntries(m, Range.closedOpen(1, 4), 7);
    }

    @Test
    public void putCoalescesChainOrderIndependent()
    {
        // Mirror of putCoalescesChainAscendingOrder: same three puts, inserted so
        // the existing entries lie to the RIGHT of the last one. Identical result.
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(2, 3), 7);
        m.put(Range.closedOpen(3, 4), 7);
        m.put(Range.closedOpen(1, 2), 7);
        assertEntries(m, Range.closedOpen(1, 4), 7);
    }

    @Test
    public void putDifferentValueIsAHardBarrier()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 2), 7);
        m.put(Range.closedOpen(2, 3), 8);
        m.put(Range.closedOpen(3, 4), 7);
        // The 8 entry is neither absorbed nor crossed, so the far [1,2) -> 7 is
        // unreachable even though both hold 7.
        assertEntries(m, Range.closedOpen(1, 2), 7,
                Range.closedOpen(2, 3), 8,
                Range.closedOpen(3, 4), 7);
    }

    @Test
    public void putSplitFragmentsDoNotRejoinAcrossTheInsert()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 9), 100);
        m.put(Range.closedOpen(3, 5), 200);
        // The two 100 fragments are separated by the 200 entry, so they are not
        // connected and must not be re-merged by the coalescing step.
        assertEntries(m, Range.closedOpen(1, 3), 100,
                Range.closedOpen(3, 5), 200,
                Range.closedOpen(5, 9), 100);
    }

    @Test
    public void normalFormHasNoConnectedEqualValuedPair()
    {
        // The global invariant that the old put/putCoalescing split could not
        // state: after every operation, no two connected entries hold an equal
        // value.
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 2), 7);
        m.put(Range.closedOpen(2, 3), 7);
        m.put(Range.closedOpen(3, 4), 8);
        m.put(Range.closedOpen(4, 5), 8);
        m.put(Range.closedOpen(5, 6), 7);
        List<RangeMap.Entry<Integer, Integer>> v = m.asMapOfRanges();
        for (int i = 0; i + 1 < v.size(); i++)
        {
            RangeMap.Entry<Integer, Integer> a = v.get(i);
            RangeMap.Entry<Integer, Integer> b = v.get(i + 1);
            assertFalse(a.getRange().isConnected(b.getRange())
                            && a.getValue().equals(b.getValue()),
                    "connected entries must not hold an equal value");
        }
        assertEntries(m, Range.closedOpen(1, 3), 7,
                Range.closedOpen(3, 5), 8,
                Range.closedOpen(5, 6), 7);
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
        m.put(Range.closedOpen(20, 25), 3);
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

    // ---- coalescing-put regression battery ---------------------------------
    // Salvaged from the pre-2026-07-31 putCoalescing outward-walk battery
    // (tag archive/putcoalescing-outward-walk), rewritten for the coalescing
    // put. The shapes that used to break an ascending single pass — a chain to
    // the LEFT of the insert, clip fragments that must rejoin, unbounded
    // sentinels — are now reachable only through put's own normal form, so
    // they pin the outward walk plus its cut-empty guard and value predicate.

    /**
     * Unbounded endpoints need no special-casing: the sentinel cuts coalesce
     * through {@code isConnected}/{@code span} like any other. Each put lands
     * next to an equal-valued neighbour and merges as it lands, so the two
     * chains reaching the infinities are single entries; the bridging put then
     * collapses them into a single {@code (-inf, +inf)} entry.
     */
    @Test
    public void putUnboundedChainsOnBothSidesCollapseToAll()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.lessThan(-5), 7);
        m.put(Range.closedOpen(-5, 0), 7);
        m.put(Range.closedOpen(5, 10), 7);
        m.put(Range.atLeast(10), 7);
        assertEntries(m, Range.lessThan(0), 7, Range.atLeast(5), 7);
        m.put(Range.closedOpen(0, 5), 7);
        assertEntries(m, Range.<Integer>all(), 7);
        assertEquals(Optional.of(7), m.get(Integer.MIN_VALUE));
        assertEquals(Optional.of(7), m.get(0));
        assertEquals(Optional.of(7), m.get(Integer.MAX_VALUE));
    }

    /**
     * Overlap: the inserted [6,11) clips [0,10)-&gt;7 down to the fragment [0,6)-&gt;7
     * and clips [10,12)-&gt;9 down to [11,12)-&gt;9. The equal-valued fragment must
     * rejoin the insert; the 9 entry must not.
     */
    @Test
    public void putRejoinsClippedFragmentAndChainBeyondIt()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(0, 10), 7);
        m.put(Range.closedOpen(10, 12), 9);
        m.put(Range.closedOpen(6, 11), 7);
        assertEntries(m,
                Range.closedOpen(0, 11), 7,
                Range.closedOpen(11, 12), 9);
        assertEquals(Optional.of(7), m.get(0));
        assertEquals(Optional.of(7), m.get(10));
        assertEquals(Optional.of(9), m.get(11));
    }

    /**
     * The inserted [6,14) straddles the equal-valued [0,20), so {@code clipOut}
     * emits BOTH fragments — [0,6) below and [14,20) starting exactly at the
     * inserted range's upper cut. Both must rejoin: one [0,20) entry, i.e. an
     * equal-valued put inside an entry is a no-op on the entry list. With
     * differently-valued flanks the straddled entry rejoins its own two
     * fragments and stops there.
     */
    @Test
    public void putRejoinsBothClipFragmentsOfAStraddledEqualEntry()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(0, 20), 7);
        m.put(Range.closedOpen(6, 14), 7);
        assertEntries(m, Range.closedOpen(0, 20), 7);

        RangeMap<Integer, Integer> flanked = new RangeMap<>();
        flanked.put(Range.closedOpen(0, 2), 1);
        flanked.put(Range.closedOpen(2, 18), 7);
        flanked.put(Range.closedOpen(18, 20), 1);
        flanked.put(Range.closedOpen(6, 14), 7);
        assertEntries(flanked,
                Range.closedOpen(0, 2), 1,
                Range.closedOpen(2, 18), 7,
                Range.closedOpen(18, 20), 1);
    }

    /**
     * A cut-empty {@code put} against a non-empty map is a no-op, and the check
     * happens BEFORE clipping: the empty [5,5) sits exactly on the abutment of
     * two entries, so a missing guard would clip (or, with an equal value,
     * splice) at that cut. Complements {@link #emptyPutIsNoop}, which only
     * covers the empty map.
     */
    @Test
    public void putCutEmptyRangeAtAnAbutmentIsNoop()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100);
        m.put(Range.closedOpen(5, 9), 200);
        m.put(Range.closedOpen(5, 5), 100);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 200);
        // The other cut-empty shape at the same endpoint — [5,5) is empty at
        // Below(5), (5,5] at Above(5) — is equally a no-op.
        m.put(Range.openClosed(5, 5), 200);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 200);
        // Also a no-op with a value nothing in the map holds.
        m.put(Range.closedOpen(3, 3), 999);
        assertEntries(m, Range.closedOpen(1, 5), 100, Range.closedOpen(5, 9), 200);
    }

    /**
     * The value predicate is {@link java.util.Objects#equals}, not reference
     * identity. For the spec's own {@code RangeMap<Integer, Integer>} that is
     * <strong>required</strong>, not a liberty: the JLS only guarantees boxing
     * identity inside the {@code -128..127} cache, so an identity predicate would
     * make coalescing above 127 depend on {@code -XX:AutoBoxCacheMax} and diverge
     * from the sibling ports' i32 value semantics.
     *
     * <p>The {@code String} half uses guaranteed-distinct instances, so it pins
     * value-equality without depending on any boxing-cache tuning.
     */
    @Test
    public void putUsesValueEqualityNotReferenceIdentity()
    {
        // Guaranteed-distinct-but-equal instances: identity would refuse to merge.
        RangeMap<Integer, String> s = new RangeMap<>();
        String a = "v";
        String b = new String("v");
        assertNotSame(a, b);
        s.put(Range.closedOpen(1, 5), a);
        s.put(Range.closedOpen(5, 9), b);
        assertEntries(s, Range.closedOpen(1, 9), "v");

        // The normative i32 universe: values above the JLS-guaranteed cache
        // range coalesce regardless of whether this JVM happens to cache them.
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.closedOpen(1, 5), 100_000);
        m.put(Range.closedOpen(5, 9), 100_000);
        assertEntries(m, Range.closedOpen(1, 9), 100_000);
    }

    /**
     * A cut-non-empty range that contains no {@code Integer} — {@code open(1, 2)}
     * — is still a stored entry and still a value barrier: it clips the
     * enclosing {@code all()} into the two flanks either side of its cuts, and
     * removing it leaves those flanks disconnected (the gap is cut-non-empty)
     * rather than re-coalesced. The dense oracle below cannot see this shape,
     * because no integer point ever lands in it; this pins it exactly.
     */
    @Test
    public void putNoIntegerRangeIsAStoredBarrier()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.all(), 1);
        m.put(Range.open(1, 2), 2);
        assertEntries(m,
                Range.atMost(1), 1,
                Range.open(1, 2), 2,
                Range.atLeast(2), 1);
        m.remove(Range.open(1, 2));
        assertEntries(m,
                Range.atMost(1), 1,
                Range.atLeast(2), 1);
    }

    /**
     * Removing an unbounded range from {@code all()} clips to the exact
     * boundary-flipped cut of the removed range: {@code lessThan(0)} (upper
     * {@code Below(0)}) leaves {@code atLeast(0)}, {@code atMost(0)} (upper
     * {@code Above(0)}) leaves {@code greaterThan(0)} — never a neighbouring
     * shape computed by {@code +-1} endpoint arithmetic.
     */
    @Test
    public void removeUnboundedClipsToExactSentinelCut()
    {
        RangeMap<Integer, Integer> m = new RangeMap<>();
        m.put(Range.all(), 1);
        m.remove(Range.lessThan(0));
        assertEntries(m, Range.atLeast(0), 1);

        RangeMap<Integer, Integer> n = new RangeMap<>();
        n.put(Range.all(), 1);
        n.remove(Range.atMost(0));
        assertEntries(n, Range.greaterThan(0), 1);
    }

    // ---- randomized cross-check against a dense oracle ---------------------

    /** Inclusive integer domain the oracle is dense over; endpoints draw from ENDPOINT_LIMIT. */
    private static final int DOMAIN_LIMIT = 12;
    private static final int ENDPOINT_LIMIT = 8;

    /**
     * Draw a random range whose endpoints come from {@code [-ENDPOINT_LIMIT,
     * ENDPOINT_LIMIT]}: the four bounded shapes plus the five unbounded forms,
     * so the sentinel cuts are exercised. Cut-empty draws ({@code [v,v)},
     * {@code (v,v]}) are let through — they must be no-ops. {@code open(v, v)}
     * is invalid, so an equal-endpoint open draw is widened to {@code (v, v+1)}.
     */
    private static Range<Integer> randomRange(Random rnd)
    {
        int a = rnd.nextInt(2 * ENDPOINT_LIMIT + 1) - ENDPOINT_LIMIT;
        int b = rnd.nextInt(2 * ENDPOINT_LIMIT + 1) - ENDPOINT_LIMIT;
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        switch (rnd.nextInt(9))
        {
            case 0:
                return Range.closed(lo, hi);
            case 1:
                return Range.open(lo, lo == hi ? hi + 1 : hi);
            case 2:
                return Range.closedOpen(lo, hi);
            case 3:
                return Range.openClosed(lo, hi);
            case 4:
                return Range.lessThan(a);
            case 5:
                return Range.atMost(a);
            case 6:
                return Range.greaterThan(a);
            case 7:
                return Range.atLeast(a);
            default:
                return Range.all();
        }
    }

    /** Whether the lower cut of {@code a} is strictly below that of {@code b}, via {@code span}. */
    private static boolean lowerCutStrictlyBelow(Range<Integer> a, Range<Integer> b)
    {
        Range<Integer> sp = a.span(b);
        boolean spanStartsAtA = Objects.equals(sp.lowerEndpoint(), a.lowerEndpoint())
                && sp.lowerBoundType() == a.lowerBoundType();
        boolean sameLower = Objects.equals(a.lowerEndpoint(), b.lowerEndpoint())
                && a.lowerBoundType() == b.lowerBoundType();
        return spanStartsAtA && !sameLower;
    }

    /**
     * The full RangeMap normal form over {@code asMapOfRanges}: ascending by
     * lower cut, every range cut-non-empty, consecutive ranges pairwise disjoint,
     * and no two connected consecutive entries holding an equal value.
     */
    private static void assertNormalForm(RangeMap<Integer, Integer> m, String at)
    {
        List<RangeMap.Entry<Integer, Integer>> v = m.asMapOfRanges();
        for (RangeMap.Entry<Integer, Integer> e : v)
        {
            assertFalse(e.getRange().isEmpty(), at + ": cut-empty entry " + e);
        }
        for (int i = 0; i + 1 < v.size(); i++)
        {
            Range<Integer> a = v.get(i).getRange();
            Range<Integer> b = v.get(i + 1).getRange();
            assertTrue(lowerCutStrictlyBelow(a, b), at + ": not ascending: " + a + ", " + b);
            Optional<Range<Integer>> inter = a.intersection(b);
            assertTrue(inter.isEmpty() || inter.get().isEmpty(), at + ": overlap: " + a + ", " + b);
            assertFalse(a.isConnected(b) && v.get(i).getValue().equals(v.get(i + 1).getValue()),
                    at + ": connected equal-valued entries " + v.get(i) + ", " + v.get(i + 1));
        }
    }

    /**
     * Seeded random {@code put}/{@code remove} sequence (~70/30) checked after
     * EVERY op against a naive dense oracle: one {@code Integer} slot per point
     * of {@code [-DOMAIN_LIMIT, DOMAIN_LIMIT]}, put writing the value at every
     * contained point, remove clearing it. Values come from {@code {1,2,3}} so
     * equal-value coalescing happens constantly. Per op: (a) {@code get} agrees
     * with the oracle at every point; (b) {@code getEntry} is present iff the
     * oracle is, its range contains the point and its value matches; (c) the
     * normal form holds; (d) re-densifying the entries reproduces the oracle.
     */
    @Test
    public void randomizedPutRemoveAgainstDenseOracle()
    {
        for (long seed : new long[] {0x5EEDL, 0xBADC0FFEL, 0xC0A1E5CEL})
        {
            Random rnd = new Random(seed);
            RangeMap<Integer, Integer> m = new RangeMap<>();
            Integer[] oracle = new Integer[2 * DOMAIN_LIMIT + 1];
            for (int op = 0; op < 400; op++)
            {
                Range<Integer> r = randomRange(rnd);
                boolean isPut = rnd.nextInt(10) < 7;
                Integer value = isPut ? 1 + rnd.nextInt(3) : null;
                if (isPut)
                {
                    m.put(r, value);
                }
                else
                {
                    m.remove(r);
                }
                for (int p = -DOMAIN_LIMIT; p <= DOMAIN_LIMIT; p++)
                {
                    if (r.contains(p))
                    {
                        oracle[p + DOMAIN_LIMIT] = value;
                    }
                }
                String at = "seed " + seed + " op " + op + " " + (isPut ? "put " : "remove ") + r
                        + (isPut ? " -> " + value : "");

                // (a) + (b): point lookups.
                for (int p = -DOMAIN_LIMIT; p <= DOMAIN_LIMIT; p++)
                {
                    Integer expected = oracle[p + DOMAIN_LIMIT];
                    assertEquals(Optional.ofNullable(expected), m.get(p), at + ": get(" + p + ")");
                    Optional<RangeMap.Entry<Integer, Integer>> e = m.getEntry(p);
                    assertEquals(expected != null, e.isPresent(), at + ": getEntry(" + p + ")");
                    if (e.isPresent())
                    {
                        assertTrue(e.get().getRange().contains(p), at + ": getEntry(" + p + ") range");
                        assertEquals(expected, e.get().getValue(), at + ": getEntry(" + p + ") value");
                    }
                }
                // (c): normal form.
                assertNormalForm(m, at);
                // (d): densify the entries and compare.
                Integer[] dense = new Integer[oracle.length];
                for (RangeMap.Entry<Integer, Integer> e : m.asMapOfRanges())
                {
                    for (int p = -DOMAIN_LIMIT; p <= DOMAIN_LIMIT; p++)
                    {
                        if (e.getRange().contains(p))
                        {
                            assertNull(dense[p + DOMAIN_LIMIT], at + ": point " + p + " covered twice");
                            dense[p + DOMAIN_LIMIT] = e.getValue();
                        }
                    }
                }
                assertArrayEquals(oracle, dense, at + ": densified entries");
            }
        }
    }
}

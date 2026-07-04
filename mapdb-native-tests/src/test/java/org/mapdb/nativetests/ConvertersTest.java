// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.convert.Converters;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.multimap.set.UnifiedSetMultimap;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeSet;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "A4" cross-structure converters
 * ({@link Converters}).
 */
public class ConvertersTest
{
    // ---- BitSet <-> RoaringU32 ----

    @Test
    public void bitSetRoundTripsThroughRoaring()
    {
        BitSet bits = new BitSet();
        bits.set(0);
        bits.set(5);
        bits.set(1000);
        bits.set(Integer.MAX_VALUE);

        RoaringU32 r = Converters.roaringFromBitSet(bits);
        assertArrayEquals(new int[] {0, 5, 1000, Integer.MAX_VALUE}, r.toSortedArray());

        BitSet back = Converters.bitSetFromRoaring(r);
        assertEquals(bits, back);
    }

    @Test
    public void bitSetFromRoaringRejectsHighHalfMembers()
    {
        RoaringU32 r = new RoaringU32();
        r.add(3);
        r.add(-1);          // u32 0xFFFFFFFF -> not a valid BitSet index
        assertThrows(IllegalArgumentException.class, () -> Converters.bitSetFromRoaring(r));
    }

    // ---- RangeSet<Integer> -> RoaringU32 ----

    @Test
    public void rangeSetMaterializesInclusiveAndExclusiveEndpoints()
    {
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.closed(1, 3));         // 1,2,3
        rs.add(Range.closedOpen(10, 13));   // 10,11,12
        rs.add(Range.openClosed(20, 22));   // 21,22

        RoaringU32 r = Converters.roaringFromRangeSet(rs);
        assertArrayEquals(new int[] {1, 2, 3, 10, 11, 12, 21, 22}, r.toSortedArray());
    }

    @Test
    public void rangeSetRejectsUnbounded()
    {
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.atLeast(5));
        assertThrows(IllegalArgumentException.class, () -> Converters.roaringFromRangeSet(rs));
    }

    @Test
    public void rangeSetHandlesMaxValueBoundaryWithoutOverflow()
    {
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.closed(Integer.MAX_VALUE - 2, Integer.MAX_VALUE));
        RoaringU32 r = Converters.roaringFromRangeSet(rs);
        assertArrayEquals(
                new int[] {Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE},
                r.toSortedArray());
    }

    @Test
    public void rangeSetNegativeEndpointsMaterializeInUnsignedOrder()
    {
        // The signed-range vs unsigned-Roaring seam: members {-2,-1,0,1} come
        // back in unsigned order (0, 1, then 2^32-2, 2^32-1).
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.closed(-2, 1));
        RoaringU32 r = Converters.roaringFromRangeSet(rs);
        assertArrayEquals(new int[] {0, 1, -2, -1}, r.toSortedArray());
    }

    @Test
    public void rangeSetOpenDiscreteEmptyRangeYieldsNothing()
    {
        // (1,2) stores but covers no integer; the OPEN adjustments give lo>hi.
        RangeSet<Integer> rs = new RangeSet<>();
        rs.add(Range.open(1, 2));
        assertTrue(Converters.roaringFromRangeSet(rs).isEmpty());
    }

    // ---- freeze / thaw ----

    @Test
    public void freezeThawRoundTrip()
    {
        MutableSortedMap<Integer, String> tree = TreeSortedMap.newMap();
        tree.put(3, "c");
        tree.put(1, "a");
        tree.put(2, "b");

        ImmutableSortedMap<Integer, String> frozen = Converters.freeze(tree);
        assertEquals(3, frozen.size());
        assertEquals("a", frozen.get(1).orElseThrow());
        assertEquals("c", frozen.lastEntry().orElseThrow().getValue());

        MutableSortedMap<Integer, String> thawed = Converters.thaw(frozen);
        assertEquals(tree, thawed);
        thawed.put(4, "d");     // proves it is mutable again
        assertEquals(4, thawed.size());
    }

    // ---- SetMultimap <-> map-of-sets ----

    @Test
    public void multimapToMapOfSetsAndBack()
    {
        MutableSetMultimap<String, Integer> mm = UnifiedSetMultimap.newMultimap();
        mm.put("a", 1);
        mm.put("a", 2);
        mm.put("b", 3);

        MutableMap<String, MutableSet<Integer>> mos = Converters.asMapOfSets(mm);
        assertEquals(UnifiedSet.newSetWith(1, 2), mos.get("a"));
        assertEquals(UnifiedSet.newSetWith(3), mos.get("b"));

        // Rebuild a set-multimap from a plain map-of-sets and check equality.
        Map<String, Set<Integer>> plain = new LinkedHashMap<>();
        plain.put("a", new java.util.HashSet<>(Arrays.asList(1, 2)));
        plain.put("b", new java.util.HashSet<>(Arrays.asList(3)));
        MutableSetMultimap<String, Integer> rebuilt = Converters.setMultimapFromMapOfSets(plain);
        assertEquals(mm, rebuilt);
    }

    @Test
    public void freezeThawEmpty()
    {
        MutableSortedMap<Integer, String> empty = TreeSortedMap.newMap();
        ImmutableSortedMap<Integer, String> frozen = Converters.freeze(empty);
        assertTrue(frozen.isEmpty());
        assertTrue(Converters.thaw(frozen).isEmpty());
    }

    @Test
    public void freezeRejectsNonNaturalOrder()
    {
        // A reverse-ordered tree map iterates descending; the packed form is
        // natural-order only, so fromSorted's ascending check must reject it.
        MutableSortedMap<Integer, String> rev = TreeSortedMap.newMap(Comparator.<Integer>reverseOrder());
        rev.put(1, "a");
        rev.put(2, "b");
        rev.put(3, "c");
        assertThrows(IllegalArgumentException.class, () -> Converters.freeze(rev));
    }

    @Test
    public void emptyConversionsAreEmpty()
    {
        assertTrue(Converters.roaringFromBitSet(new BitSet()).isEmpty());
        assertTrue(Converters.roaringFromRangeSet(new RangeSet<Integer>()).isEmpty());
        assertEquals(0, Converters.asMapOfSets(UnifiedSetMultimap.<String, Integer>newMultimap()).size());
    }
}

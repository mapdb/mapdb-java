// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.convert;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.multimap.set.SetMultimap;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.multimap.set.UnifiedSetMultimap;
import org.mapdb.collections.impl.range.BoundType;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeSet;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

/**
 * Archeology-2 "A4" — cross-structure converters.
 *
 * <p>Each method is trivial on its own; as a family they say <i>"these are
 * representations of the same data — pick the one that fits the phase."</i> A
 * dense integer set is a {@link BitSet} while you mutate it and a
 * {@link RoaringU32} once it is sparse or you need its serialized/query form; an
 * interval cover is a {@link RangeSet} while you reason about it and a
 * {@link RoaringU32} once you materialize its points; a sorted map is a mutable
 * tree while it changes and a packed {@link ImmutableSortedMap} once it is
 * frozen; associations are a {@link SetMultimap} or a plain map-of-sets
 * depending on which API you want.
 *
 * <p>These ride on the same comparator / bit-order contracts the rest of the
 * family uses; there is no new format or algorithm here.
 */
public final class Converters
{
    private Converters()
    {
    }

    // ------------------------------------------------------------------
    // BitSet <-> RoaringU32
    // ------------------------------------------------------------------

    /**
     * Materialize a {@link BitSet} as a {@link RoaringU32}. Set-bit indices
     * ({@code 0 .. Integer.MAX_VALUE}) become {@code u32} members, so the result
     * occupies the non-negative half of the {@code u32} space.
     */
    public static RoaringU32 roaringFromBitSet(BitSet bits)
    {
        RoaringU32 r = new RoaringU32();
        for (int i = bits.nextSetBit(0); i >= 0; i = (i == Integer.MAX_VALUE ? -1 : bits.nextSetBit(i + 1)))
        {
            r.add(i);
        }
        return r;
    }

    /**
     * Materialize a {@link RoaringU32} as a {@link BitSet}. A {@code BitSet} is
     * indexed by a non-negative {@code int}, so any member whose {@code u32}
     * value is {@code >= 2^31} (a negative {@code i32} reinterpret) cannot be
     * represented and throws {@link IllegalArgumentException}.
     */
    public static BitSet bitSetFromRoaring(RoaringU32 bitmap)
    {
        BitSet bits = new BitSet();
        for (int v : bitmap.toSortedArray())
        {
            if (v < 0)
            {
                throw new IllegalArgumentException(
                        "RoaringU32 member " + Integer.toUnsignedString(v)
                                + " exceeds Integer.MAX_VALUE; cannot index a BitSet");
            }
            bits.set(v);
        }
        return bits;
    }

    // ------------------------------------------------------------------
    // RangeSet<Integer> -> RoaringU32 (materialize the points)
    // ------------------------------------------------------------------

    /**
     * Materialize the integer members covered by a {@link RangeSet} into a
     * {@link RoaringU32}. Every canonical range must be bounded on both sides
     * (an unbounded range covers infinitely many points and throws
     * {@link IllegalArgumentException}). Endpoints honor their {@link BoundType}:
     * an {@code OPEN} endpoint is excluded, a {@code CLOSED} endpoint included.
     *
     * <p>Note this is a <i>membership</i> conversion: the resulting Roaring set
     * contains the same integers, but iterates them in unsigned order, which
     * differs from the {@code RangeSet}'s signed {@link Integer} order once
     * negative values are involved.
     */
    public static RoaringU32 roaringFromRangeSet(RangeSet<Integer> ranges)
    {
        RoaringU32 r = new RoaringU32();
        for (Range<Integer> range : ranges.asRanges())
        {
            if (!range.hasLowerBound() || !range.hasUpperBound())
            {
                throw new IllegalArgumentException(
                        "unbounded range " + range + " covers infinitely many points");
            }
            long lo = range.lowerEndpoint();
            if (range.lowerBoundType() == BoundType.OPEN)
            {
                lo++;
            }
            long hi = range.upperEndpoint();
            if (range.upperBoundType() == BoundType.OPEN)
            {
                hi--;
            }
            for (long v = lo; v <= hi; v++)
            {
                r.add((int) v);
            }
        }
        return r;
    }

    // ------------------------------------------------------------------
    // TreeSortedMap <-> ImmutableSortedMap (freeze / thaw)
    // ------------------------------------------------------------------

    /**
     * Freeze a mutable sorted map into the packed, pointerless
     * {@link ImmutableSortedMap}. The map must be ordered consistently with
     * natural key order (the packed form is natural-order only); an inconsistent
     * comparator surfaces as the {@code fromSorted} ascending check failing.
     */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> freeze(MutableSortedMap<K, V> map)
    {
        List<K> keys = new ArrayList<>(map.size());
        List<V> values = new ArrayList<>(map.size());
        map.forEachKeyValue((k, v) ->
        {
            keys.add(k);
            values.add(v);
        });
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    /**
     * Thaw a packed {@link ImmutableSortedMap} back into a mutable, natural-order
     * {@link TreeSortedMap} so it can be updated again.
     */
    public static <K extends Comparable<? super K>, V> MutableSortedMap<K, V> thaw(ImmutableSortedMap<K, V> map)
    {
        MutableSortedMap<K, V> out = TreeSortedMap.newMap();
        for (Map.Entry<K, V> e : map.entries())
        {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // SetMultimap <-> map-of-sets
    // ------------------------------------------------------------------

    /**
     * Snapshot a set-multimap as a plain {@code Map<K, Set<V>>} (one fresh set
     * per key). This is a <b>copy</b>, not a live view: mutating the returned
     * map or its sets does not write back to the multimap.
     */
    public static <K, V> MutableMap<K, MutableSet<V>> asMapOfSets(SetMultimap<K, V> multimap)
    {
        MutableMap<K, MutableSet<V>> out = UnifiedMap.newMap();
        multimap.forEachKeyMultiValues((k, values) ->
        {
            MutableSet<V> set = UnifiedSet.newSet();
            set.addAllIterable(values);
            out.put(k, set);
        });
        return out;
    }

    /**
     * Build a set-multimap from a map-of-sets (inverse of {@link #asMapOfSets}).
     *
     * <p>Note the round-trip is asymmetric for empty value sets: a key mapped to
     * an empty set produces no entries (a multimap has no notion of a key with
     * zero values), so {@code asMapOfSets(setMultimapFromMapOfSets(m))} drops any
     * such key.
     */
    public static <K, V> MutableSetMultimap<K, V> setMultimapFromMapOfSets(Map<K, ? extends Set<V>> map)
    {
        MutableSetMultimap<K, V> out = UnifiedSetMultimap.newMultimap();
        for (Map.Entry<K, ? extends Set<V>> e : map.entrySet())
        {
            out.putAll(e.getKey(), e.getValue());
        }
        return out;
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.sorted;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mapdb.collections.impl.range.Range;

/**
 * Compact immutable sorted map backed by packed parallel arrays
 * ({@code keys[i] -> values[i]}), queried by binary search (spec
 * {@code features/sorted-table-map.md}). Built once from strictly-ascending
 * input via {@link #fromSorted}; thereafter immutable. The on-heap analogue of
 * MapDB 3's {@code SortedTableMap} — we port the observable behaviour and the
 * packed-array + binary-search mechanism, not the off-heap {@code Volume}.
 *
 * <h2>Java carve-out (boxed; complexity relaxed)</h2>
 *
 * <p>mapdb-java is the boxed Eclipse-Collections fork ({@code style/java.md}),
 * which ships no primitive packed-array immutable sorted map, so this lands as a
 * <strong>boxed generic</strong> {@code ImmutableSortedMap<K, V>}
 * ({@code K extends Comparable<? super K>}), same posture as the boxed-tree,
 * boxed-{@code Range}, and NavigableMap carve-outs. The backing is packed
 * parallel {@code Object[]}-style lists (the compact spirit, even boxed); the
 * complexity guarantee is relaxed (boxing overhead) while observable results are
 * identical.
 *
 * <h2>Construction is the only way in</h2>
 *
 * <p>{@link #fromSorted(int[], int[])} (i32 suite) takes a strictly-ascending
 * snapshot. Construction <strong>traps</strong> ({@link IllegalArgumentException}
 * — the family's bad-input posture) unless every adjacent input pair satisfies
 * {@code keys[i-1] < keys[i]} strictly: out-of-order input traps, a duplicate key
 * traps (no last-wins / dedup), and a {@code keys}/{@code values} length mismatch
 * traps. Empty input is valid (builds an empty map); single-element input is
 * valid. The input is <strong>copied</strong>, so the built map is a snapshot
 * independent of the caller's source arrays.
 *
 * <h2>Immutable</h2>
 *
 * <p>No mutators are exposed. Absence is returned <strong>uniformly via
 * {@link Optional}</strong> for every absence-returning query
 * ({@link #get}, {@link #firstKey}/{@link #lastKey}, the point-nav and
 * {@code *Entry} forms, {@link #selectKey}/{@link #selectEntry}).
 *
 * @param <K> key type (totally-ordered, non-null)
 * @param <V> value type
 */
public final class ImmutableSortedMap<K extends Comparable<? super K>, V>
{
    private final List<K> keys;
    private final List<V> values;

    private ImmutableSortedMap(List<K> keys, List<V> values)
    {
        this.keys = keys;
        this.values = values;
    }

    /**
     * Build from <strong>strictly ascending</strong> parallel {@code int} arrays
     * (the i32 cross-language suite): {@code values[i]} is the value of
     * {@code keys[i]}. The input is copied (snapshot).
     *
     * @throws IllegalArgumentException if {@code keys.length != values.length},
     *         if the keys are not strictly ascending (out-of-order), or if any
     *         key is duplicated. No last-wins/dedup and no silent sort. Empty and
     *         single-element input are valid.
     */
    public static ImmutableSortedMap<Integer, Integer> fromSorted(int[] keys, int[] values)
    {
        if (keys.length != values.length)
        {
            throw new IllegalArgumentException(
                    "ImmutableSortedMap.fromSorted: keys/values length mismatch ("
                            + keys.length + " != " + values.length + ")");
        }
        List<Integer> ks = new ArrayList<>(keys.length);
        List<Integer> vs = new ArrayList<>(values.length);
        for (int i = 0; i < keys.length; i++)
        {
            ks.add(keys[i]);
            vs.add(values[i]);
        }
        assertStrictlyAscending(ks);
        return new ImmutableSortedMap<>(ks, vs);
    }

    /**
     * Build from a <strong>strictly ascending</strong> boxed source: {@code keys}
     * and {@code values} are parallel lists, {@code values.get(i)} the value of
     * {@code keys.get(i)}. The input is copied (snapshot).
     *
     * @throws IllegalArgumentException on length mismatch, out-of-order keys, or
     *         duplicate keys (same trap posture as {@link #fromSorted(int[], int[])}).
     */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> fromSorted(
            List<K> keys, List<V> values)
    {
        if (keys.size() != values.size())
        {
            throw new IllegalArgumentException(
                    "ImmutableSortedMap.fromSorted: keys/values length mismatch ("
                            + keys.size() + " != " + values.size() + ")");
        }
        List<K> ks = new ArrayList<>(keys);
        List<V> vs = new ArrayList<>(values);
        assertStrictlyAscending(ks);
        return new ImmutableSortedMap<>(ks, vs);
    }

    private static <K extends Comparable<? super K>> void assertStrictlyAscending(List<K> ks)
    {
        for (int i = 1; i < ks.size(); i++)
        {
            // strictly ascending: keys[i-1] < keys[i]; equal or greater traps.
            if (ks.get(i - 1).compareTo(ks.get(i)) >= 0)
            {
                throw new IllegalArgumentException(
                        "ImmutableSortedMap: input must be strictly ascending "
                                + "(no duplicate or out-of-order keys)");
            }
        }
    }

    /** Number of entries. */
    public int size()
    {
        return this.keys.size();
    }

    /** Whether the map is empty. */
    public boolean isEmpty()
    {
        return this.keys.isEmpty();
    }

    /**
     * Binary-search index of {@code key} if present, else {@code -(insertion
     * point) - 1} (the {@link Collections#binarySearch} convention). The
     * overflow-safe midpoint is internal to {@code binarySearch}.
     */
    private int search(K key)
    {
        return Collections.binarySearch(this.keys, key);
    }

    /** Lower-bound index: number of keys strictly less than {@code key}. */
    private int lowerBound(int searchResult)
    {
        return searchResult >= 0 ? searchResult : -(searchResult) - 1;
    }

    /** The value for {@code key}, or empty if absent. */
    public Optional<V> get(K key)
    {
        int i = this.search(key);
        return i >= 0 ? Optional.of(this.values.get(i)) : Optional.empty();
    }

    /** Whether {@code key} is present. */
    public boolean containsKey(K key)
    {
        return this.search(key) >= 0;
    }

    /** Minimum key, or empty. */
    public Optional<K> firstKey()
    {
        return this.keys.isEmpty() ? Optional.empty() : Optional.of(this.keys.get(0));
    }

    /** Maximum key, or empty. */
    public Optional<K> lastKey()
    {
        return this.keys.isEmpty() ? Optional.empty() : Optional.of(this.keys.get(this.keys.size() - 1));
    }

    /** Minimum {@code (key, value)} entry, or empty. */
    public Optional<Map.Entry<K, V>> firstEntry()
    {
        return this.keys.isEmpty() ? Optional.empty() : Optional.of(this.entryAt(0));
    }

    /** Maximum {@code (key, value)} entry, or empty. */
    public Optional<Map.Entry<K, V>> lastEntry()
    {
        return this.keys.isEmpty() ? Optional.empty() : Optional.of(this.entryAt(this.keys.size() - 1));
    }

    private Map.Entry<K, V> entryAt(int i)
    {
        return new AbstractMap.SimpleImmutableEntry<>(this.keys.get(i), this.values.get(i));
    }

    // ---- point navigation (NavigableMap surface, reused verbatim) ----------
    //
    // All resolve to a single binary search over the packed key array; the index
    // arithmetic never computes a k ± 1, so it is overflow-safe at the signed
    // extremes.

    /** Index of the greatest key {@code <= k}, or {@code -1}. */
    private int floorIndex(K k)
    {
        int s = this.search(k);
        if (s >= 0)
        {
            return s;
        }
        return -(s) - 1 - 1; // (insertion point) - 1
    }

    /** Index of the greatest key {@code < k} (strict), or {@code -1}. */
    private int lowerIndex(K k)
    {
        return this.lowerBound(this.search(k)) - 1;
    }

    /** Index of the least key {@code >= k}, or {@code size()} (out of range). */
    private int ceilingIndex(K k)
    {
        return this.lowerBound(this.search(k));
    }

    /** Index of the least key {@code > k} (strict), or {@code size()}. */
    private int higherIndex(K k)
    {
        int s = this.search(k);
        return s >= 0 ? s + 1 : -(s) - 1;
    }

    /** Greatest key {@code <= k}, or empty. */
    public Optional<K> floorKey(K k)
    {
        int i = this.floorIndex(k);
        return i >= 0 ? Optional.of(this.keys.get(i)) : Optional.empty();
    }

    /** Greatest entry whose key {@code <= k}, or empty. */
    public Optional<Map.Entry<K, V>> floorEntry(K k)
    {
        int i = this.floorIndex(k);
        return i >= 0 ? Optional.of(this.entryAt(i)) : Optional.empty();
    }

    /** Least key {@code >= k}, or empty. */
    public Optional<K> ceilingKey(K k)
    {
        int i = this.ceilingIndex(k);
        return i < this.keys.size() ? Optional.of(this.keys.get(i)) : Optional.empty();
    }

    /** Least entry whose key {@code >= k}, or empty. */
    public Optional<Map.Entry<K, V>> ceilingEntry(K k)
    {
        int i = this.ceilingIndex(k);
        return i < this.keys.size() ? Optional.of(this.entryAt(i)) : Optional.empty();
    }

    /** Greatest key {@code < k} (strict), or empty. */
    public Optional<K> lowerKey(K k)
    {
        int i = this.lowerIndex(k);
        return i >= 0 ? Optional.of(this.keys.get(i)) : Optional.empty();
    }

    /** Greatest entry whose key {@code < k} (strict), or empty. */
    public Optional<Map.Entry<K, V>> lowerEntry(K k)
    {
        int i = this.lowerIndex(k);
        return i >= 0 ? Optional.of(this.entryAt(i)) : Optional.empty();
    }

    /** Least key {@code > k} (strict), or empty. */
    public Optional<K> higherKey(K k)
    {
        int i = this.higherIndex(k);
        return i < this.keys.size() ? Optional.of(this.keys.get(i)) : Optional.empty();
    }

    /** Least entry whose key {@code > k} (strict), or empty. */
    public Optional<Map.Entry<K, V>> higherEntry(K k)
    {
        int i = this.higherIndex(k);
        return i < this.keys.size() ? Optional.of(this.entryAt(i)) : Optional.empty();
    }

    // ---- order statistics (rank / select) ---------------------------------
    //
    // On a flat ascending array rank IS the lower-bound binary-search index and
    // select(i) IS keys[i], so they are trivially consistent with iteration.

    /**
     * Number of keys <strong>strictly less than</strong> {@code key} — the
     * 0-based lower-bound index in {@code 0..=size()}. Defined for present and
     * absent keys.
     */
    public int rank(K key)
    {
        return this.lowerBound(this.search(key));
    }

    /**
     * The {@code i}-th smallest key (0-based), or empty when {@code i >= size()}
     * or {@code i < 0} (no trap). Round-trips with {@link #rank}.
     */
    public Optional<K> selectKey(int i)
    {
        return i >= 0 && i < this.keys.size() ? Optional.of(this.keys.get(i)) : Optional.empty();
    }

    /** The {@code i}-th smallest {@code (key, value)} entry (0-based), or empty. */
    public Optional<Map.Entry<K, V>> selectEntry(int i)
    {
        return i >= 0 && i < this.keys.size() ? Optional.of(this.entryAt(i)) : Optional.empty();
    }

    // ---- iteration (ascending) --------------------------------------------

    /** Keys in ascending order (materialized snapshot). */
    public List<K> keys()
    {
        return new ArrayList<>(this.keys);
    }

    /** Values in <strong>ascending-key order</strong> (paired with {@link #keys}), NOT sorted by value. */
    public List<V> values()
    {
        return new ArrayList<>(this.values);
    }

    /** {@code (key, value)} entries in ascending key order. */
    public List<Map.Entry<K, V>> entries()
    {
        List<Map.Entry<K, V>> out = new ArrayList<>(this.keys.size());
        for (int i = 0; i < this.keys.size(); i++)
        {
            out.add(this.entryAt(i));
        }
        return out;
    }

    // ---- iteration (descending) — required, not optional ------------------

    /** All keys, descending. */
    public List<K> descendingKeys()
    {
        List<K> out = new ArrayList<>(this.keys);
        Collections.reverse(out);
        return out;
    }

    /** All entries, descending. */
    public List<Map.Entry<K, V>> descendingEntries()
    {
        List<Map.Entry<K, V>> out = this.entries();
        Collections.reverse(out);
        return out;
    }

    // ---- range queries (consume Range<K>; membership == range.contains) ----
    //
    // The in-range entries form a CONTIGUOUS slice of the packed array; the
    // bracket is two binary searches from Range's CUT semantics, never v ± 1, so
    // open/closed bounds at INT_MIN/INT_MAX do not overflow.

    /** Keys whose key ∈ {@code range}, ascending. */
    public List<K> rangeKeys(Range<K> range)
    {
        int[] b = range.bracket(this.keys);
        return new ArrayList<>(this.keys.subList(b[0], b[1]));
    }

    /** {@code (key, value)} entries whose key ∈ {@code range}, ascending. */
    public List<Map.Entry<K, V>> rangeEntries(Range<K> range)
    {
        int[] b = range.bracket(this.keys);
        List<Map.Entry<K, V>> out = new ArrayList<>(b[1] - b[0]);
        for (int i = b[0]; i < b[1]; i++)
        {
            out.add(this.entryAt(i));
        }
        return out;
    }

    /** Keys whose key ∈ {@code range}, descending. */
    public List<K> descendingRangeKeys(Range<K> range)
    {
        List<K> out = this.rangeKeys(range);
        Collections.reverse(out);
        return out;
    }

    /** {@code (key, value)} entries whose key ∈ {@code range}, descending. */
    public List<Map.Entry<K, V>> descendingRangeEntries(Range<K> range)
    {
        List<Map.Entry<K, V>> out = this.rangeEntries(range);
        Collections.reverse(out);
        return out;
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.navigable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.range.Range;

/**
 * NavigableMap / NavigableSet operations on the mapdb sorted map surface
 * (spec {@code features/navigable-map.md}).
 *
 * <h2>Java carve-out (boxed sorted tree)</h2>
 *
 * <p>mapdb-java has no primitive tree types: sorted use routes through the boxed
 * Eclipse Collections {@link TreeSortedMap} (see {@code style/java.md}). EC's
 * sorted types do not implement the full {@link java.util.NavigableMap} surface,
 * so this wrapper adds the navigation methods on the mapdb wrapper layer,
 * delegating to a {@link java.util.TreeMap} navigation view keyed by the
 * <strong>same comparator</strong> as the source ({@code FloatTotalOrder} for
 * float keys). The wrapper owns a {@link java.util.TreeMap} as its store and
 * keeps the backing {@link TreeSortedMap} in sync on every mutation routed
 * through it.
 *
 * <h2>Two intentional divergences from {@code java.util.NavigableMap}</h2>
 *
 * <ol>
 *   <li><strong>No live mutable sub-views.</strong> {@link #subMap(Range)}
 *       returns a <em>new independent</em> (materialized) {@code NavigableTreeMap}
 *       — never a write-through view. The "delete a range" use case is the
 *       explicit {@link #removeRange(Range)}. {@code headMap}/{@code tailMap} are
 *       NOT provided with the JDK live-view contract.</li>
 *   <li><strong>Bounds are a {@link Range}</strong>, not Java's
 *       {@code (key, inclusive, …)} overloads or {@code null}-means-±∞.</li>
 * </ol>
 *
 * <p>Range membership is exactly {@link Range#contains(Comparable)}: e.g.
 * {@code open(1, 2)} over {@code Integer} matches no key yet is a valid,
 * non-cut-empty range. Discrete-domain emptiness is never inferred from cuts.
 *
 * <h2>Iteration / snapshot contract</h2>
 *
 * <p>Range / descending accessors return a materialized {@link List} snapshot
 * taken at call time; it is read-only and is never invalidated by later
 * mutation. {@link #subMap(Range)} returns an independent snapshot that
 * <strong>preserves the source comparator / ordering</strong> (reverse, custom,
 * or float total-order) — it does not reset to natural key order.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class NavigableTreeMap<K extends Comparable<? super K>, V>
{
    private final TreeMap<K, V> nav;
    private final MutableSortedMap<K, V> ecMap;

    private NavigableTreeMap(TreeMap<K, V> nav, MutableSortedMap<K, V> ecMap)
    {
        this.nav = nav;
        this.ecMap = ecMap;
    }

    /** A new, empty navigable map ordered by natural key order. */
    public static <K extends Comparable<? super K>, V> NavigableTreeMap<K, V> newMap()
    {
        return newMap(null);
    }

    /**
     * A new, empty navigable map ordered by {@code comparator} ({@code null} =
     * natural order). The same comparator backs both the navigation view and the
     * EC {@link TreeSortedMap}, so reverse/custom/float-total-order ordering is
     * shared.
     */
    public static <K extends Comparable<? super K>, V> NavigableTreeMap<K, V> newMap(Comparator<? super K> comparator)
    {
        TreeMap<K, V> nav = comparator == null ? new TreeMap<>() : new TreeMap<>(comparator);
        MutableSortedMap<K, V> ecMap = comparator == null
                ? TreeSortedMap.newMap()
                : TreeSortedMap.newMap(comparator);
        return new NavigableTreeMap<>(nav, ecMap);
    }

    /** The comparator ({@code null} for natural order). */
    public Comparator<? super K> comparator()
    {
        return this.nav.comparator();
    }

    /** A new empty map sharing this map's comparator (for materialized snapshots). */
    private NavigableTreeMap<K, V> emptyLike()
    {
        Comparator<? super K> cmp = this.nav.comparator();
        TreeMap<K, V> navOut = cmp == null ? new TreeMap<>() : new TreeMap<>(cmp);
        MutableSortedMap<K, V> ecOut = cmp == null
                ? TreeSortedMap.newMap()
                : TreeSortedMap.newMap(cmp);
        return new NavigableTreeMap<>(navOut, ecOut);
    }

    /** The backing EC sorted map (kept in sync with the navigation view). */
    public MutableSortedMap<K, V> ecMap()
    {
        return this.ecMap;
    }

    // ---- mutation (kept in sync across both stores) -----------------------

    /** Insert or replace; returns the previous value or {@code null}. */
    public V put(K key, V value)
    {
        this.ecMap.put(key, value);
        return this.nav.put(key, value);
    }

    /** Remove the entry for {@code key}; returns the previous value or {@code null}. */
    public V remove(K key)
    {
        this.ecMap.remove(key);
        return this.nav.remove(key);
    }

    /** Remove all entries. */
    public void clear()
    {
        this.ecMap.clear();
        this.nav.clear();
    }

    public int size()
    {
        return this.nav.size();
    }

    public boolean isEmpty()
    {
        return this.nav.isEmpty();
    }

    public boolean containsKey(K key)
    {
        return this.nav.containsKey(key);
    }

    public V get(K key)
    {
        return this.nav.get(key);
    }

    // ---- point navigation (pure queries) ----------------------------------

    /** Greatest key {@code <= k}, or {@code null}. */
    public K floorKey(K k)
    {
        return this.nav.floorKey(k);
    }

    /** Greatest entry whose key {@code <= k}, or empty. */
    public Optional<Map.Entry<K, V>> floorEntry(K k)
    {
        return Optional.ofNullable(this.nav.floorEntry(k));
    }

    /** Least key {@code >= k}, or {@code null}. */
    public K ceilingKey(K k)
    {
        return this.nav.ceilingKey(k);
    }

    /** Least entry whose key {@code >= k}, or empty. */
    public Optional<Map.Entry<K, V>> ceilingEntry(K k)
    {
        return Optional.ofNullable(this.nav.ceilingEntry(k));
    }

    /** Greatest key {@code < k} (strict), or {@code null}. */
    public K lowerKey(K k)
    {
        return this.nav.lowerKey(k);
    }

    /** Greatest entry whose key {@code < k} (strict), or empty. */
    public Optional<Map.Entry<K, V>> lowerEntry(K k)
    {
        return Optional.ofNullable(this.nav.lowerEntry(k));
    }

    /** Least key {@code > k} (strict), or {@code null}. */
    public K higherKey(K k)
    {
        return this.nav.higherKey(k);
    }

    /** Least entry whose key {@code > k} (strict), or empty. */
    public Optional<Map.Entry<K, V>> higherEntry(K k)
    {
        return Optional.ofNullable(this.nav.higherEntry(k));
    }

    /** Minimum key, or {@code null} when empty. */
    public K firstKey()
    {
        return this.nav.isEmpty() ? null : this.nav.firstKey();
    }

    /** Minimum entry, or empty. */
    public Optional<Map.Entry<K, V>> firstEntry()
    {
        return Optional.ofNullable(this.nav.firstEntry());
    }

    /** Maximum key, or {@code null} when empty. */
    public K lastKey()
    {
        return this.nav.isEmpty() ? null : this.nav.lastKey();
    }

    /** Maximum entry, or empty. */
    public Optional<Map.Entry<K, V>> lastEntry()
    {
        return Optional.ofNullable(this.nav.lastEntry());
    }

    // ---- poll (positional removal) ----------------------------------------

    /** Remove and return the minimum entry, or empty (does not trap on empty). */
    public Optional<Map.Entry<K, V>> pollFirstEntry()
    {
        Map.Entry<K, V> e = this.nav.pollFirstEntry();
        if (e == null)
        {
            return Optional.empty();
        }
        this.ecMap.remove(e.getKey());
        return Optional.of(e);
    }

    /** Remove and return the maximum entry, or empty (does not trap on empty). */
    public Optional<Map.Entry<K, V>> pollLastEntry()
    {
        Map.Entry<K, V> e = this.nav.pollLastEntry();
        if (e == null)
        {
            return Optional.empty();
        }
        this.ecMap.remove(e.getKey());
        return Optional.of(e);
    }

    // ---- order statistics (rank / select; spec features/rank-select.md) ----

    /**
     * The number of keys strictly less than {@code key} under this map's
     * comparator — the 0-based lower-bound index {@code key} occupies (if
     * present) or would occupy (if absent). Defined for present and absent keys
     * alike; the result is in {@code 0..=size()} ({@code size()} for any key
     * greater than the maximum). Pure query; never mutates.
     *
     * <p><strong>Java carve-out (complexity relaxed).</strong> Computed by an
     * ordered scan over the boxed tree — O(n) rather than the O(log n) the
     * subtree-size-augmented native ports give. Observable results are
     * identical (see {@code style/java.md}).
     */
    public int rank(K key)
    {
        Comparator<? super K> cmp = this.nav.comparator();
        int rank = 0;
        for (K k : this.nav.keySet())
        {
            int c = cmp == null ? k.compareTo(key) : cmp.compare(k, key);
            if (c < 0)
            {
                rank++;
            }
            else
            {
                break;
            }
        }
        return rank;
    }

    /**
     * The {@code i}-th smallest key (0-based), or empty when {@code i} is out of
     * range. {@code i >= size()} (including on an empty map) and {@code i < 0}
     * both return {@link Optional#empty()} and never trap. Round-trips with
     * {@link #rank}: {@code selectKey(rank(k))} is present and equals {@code k}
     * for any present {@code k}, and {@code rank(selectKey(i).get()) == i} for
     * every {@code 0 <= i < size()}.
     *
     * <p>Java carve-out: O(n) ordered scan (see {@link #rank}).
     */
    public Optional<K> selectKey(int i)
    {
        if (i < 0 || i >= this.nav.size())
        {
            return Optional.empty();
        }
        int idx = 0;
        for (K k : this.nav.keySet())
        {
            if (idx == i)
            {
                return Optional.of(k);
            }
            idx++;
        }
        return Optional.empty();
    }

    /**
     * The {@code i}-th smallest entry (0-based), or empty when {@code i} is out
     * of range (same index domain as {@link #selectKey}). Returns an immutable
     * snapshot entry.
     *
     * <p>Java carve-out: O(n) ordered scan (see {@link #rank}).
     */
    public Optional<Map.Entry<K, V>> selectEntry(int i)
    {
        if (i < 0 || i >= this.nav.size())
        {
            return Optional.empty();
        }
        int idx = 0;
        for (Map.Entry<K, V> e : this.nav.entrySet())
        {
            if (idx == i)
            {
                return Optional.of(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
            idx++;
        }
        return Optional.empty();
    }

    // ---- range slice & descending (consume Range<K>) ----------------------

    /** Keys in {@code range}, ascending (materialized snapshot). */
    public List<K> rangeKeys(Range<K> range)
    {
        List<K> out = new ArrayList<>();
        for (K k : this.nav.keySet())
        {
            if (range.contains(k))
            {
                out.add(k);
            }
        }
        return out;
    }

    /** Entries whose key ∈ {@code range}, ascending (materialized snapshot). */
    public List<Map.Entry<K, V>> rangeEntries(Range<K> range)
    {
        List<Map.Entry<K, V>> out = new ArrayList<>();
        for (Map.Entry<K, V> e : this.nav.entrySet())
        {
            if (range.contains(e.getKey()))
            {
                out.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** Keys in {@code range}, descending. */
    public List<K> descendingRangeKeys(Range<K> range)
    {
        List<K> out = new ArrayList<>();
        for (K k : this.nav.descendingKeySet())
        {
            if (range.contains(k))
            {
                out.add(k);
            }
        }
        return out;
    }

    /** Entries whose key ∈ {@code range}, descending. */
    public List<Map.Entry<K, V>> descendingRangeEntries(Range<K> range)
    {
        List<Map.Entry<K, V>> out = new ArrayList<>();
        for (Map.Entry<K, V> e : this.nav.descendingMap().entrySet())
        {
            if (range.contains(e.getKey()))
            {
                out.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** All keys, descending. */
    public List<K> descendingKeys()
    {
        return new ArrayList<>(this.nav.descendingKeySet());
    }

    /** All entries, descending. */
    public List<Map.Entry<K, V>> descendingEntries()
    {
        List<Map.Entry<K, V>> out = new ArrayList<>();
        for (Map.Entry<K, V> e : this.nav.descendingMap().entrySet())
        {
            out.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * A new independent map of the entries whose key ∈ {@code range}. Mutating
     * the snapshot never affects the original and vice versa. The snapshot
     * preserves the source comparator / ordering.
     */
    public NavigableTreeMap<K, V> subMap(Range<K> range)
    {
        NavigableTreeMap<K, V> out = this.emptyLike();
        for (Map.Entry<K, V> e : this.nav.entrySet())
        {
            if (range.contains(e.getKey()))
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Remove every entry whose key ∈ {@code range}; returns the count removed. */
    public int removeRange(Range<K> range)
    {
        List<K> victims = this.rangeKeys(range);
        for (K k : victims)
        {
            this.remove(k);
        }
        return victims.size();
    }
}

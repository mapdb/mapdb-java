// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.navigable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;

/**
 * NavigableSet operations on the mapdb sorted set surface (spec
 * {@code features/navigable-map.md}) — the element analogue of
 * {@link NavigableTreeMap} (no entry forms).
 *
 * <p>Like the map wrapper, this is the Java boxed-tree carve-out: navigation is
 * added on the mapdb wrapper layer, delegating to a {@link java.util.TreeSet}
 * navigation view keyed by the <strong>same comparator</strong> as the source
 * ({@code FloatTotalOrder} for float elements), and the backing EC
 * {@link TreeSortedSet} is kept in sync on every mutation routed through it.
 *
 * <p>Range membership is exactly {@link Range#contains(Comparable)};
 * {@link #subSet(Range)} returns an independent materialized snapshot that
 * preserves the source comparator / ordering (never resets to natural order);
 * {@link #pollFirst()}/{@link #pollLast()} do not trap on an empty set.
 *
 * @param <T> element type
 */
public final class NavigableTreeSet<T extends Comparable<? super T>>
{
    private final TreeSet<T> nav;
    private final MutableSortedSet<T> ecSet;

    private NavigableTreeSet(TreeSet<T> nav, MutableSortedSet<T> ecSet)
    {
        this.nav = nav;
        this.ecSet = ecSet;
    }

    /** A new, empty navigable set ordered by natural element order. */
    public static <T extends Comparable<? super T>> NavigableTreeSet<T> newSet()
    {
        return newSet(null);
    }

    /**
     * A new, empty navigable set ordered by {@code comparator} ({@code null} =
     * natural order). The same comparator backs both the navigation view and the
     * EC {@link TreeSortedSet}.
     */
    public static <T extends Comparable<? super T>> NavigableTreeSet<T> newSet(Comparator<? super T> comparator)
    {
        TreeSet<T> nav = comparator == null ? new TreeSet<>() : new TreeSet<>(comparator);
        MutableSortedSet<T> ecSet = comparator == null
                ? TreeSortedSet.newSet()
                : TreeSortedSet.newSet(comparator);
        return new NavigableTreeSet<>(nav, ecSet);
    }

    /**
     * Bulk build (data pump) from input already sorted ascending under natural
     * order, rejecting duplicates — the element analogue of
     * {@link NavigableTreeMap#fromSorted(Iterable)}.
     */
    public static <T extends Comparable<? super T>> NavigableTreeSet<T> fromSorted(Iterable<T> input)
    {
        return fromSorted(null, input, Pump.DuplicatePolicy.ERROR);
    }

    /**
     * Bulk build (data pump) from input already sorted ascending under
     * {@code comparator} ({@code null} = natural order).
     *
     * <p>The pump ({@link Pump#treeSortedSetFromSorted(Comparator, Iterable,
     * Pump.DuplicatePolicy)}) validates order and duplicates and builds the
     * backing EC {@link TreeSortedSet} in one pass; the navigation view is then
     * built from that sorted set through the JDK {@code TreeSet(SortedSet)}
     * bulk constructor (O(n) {@code buildFromSorted}), sharing the same
     * comparator. No element is added one-by-one.
     *
     * <p>Out-of-order input throws {@link Pump.PumpSourceNotSorted}; a duplicate
     * under {@link Pump.DuplicatePolicy#ERROR} throws
     * {@link Pump.PumpSourceDuplicate}.
     */
    public static <T extends Comparable<? super T>> NavigableTreeSet<T> fromSorted(
            Comparator<? super T> comparator, Iterable<T> input, Pump.DuplicatePolicy policy)
    {
        MutableSortedSet<T> ecSet = Pump.treeSortedSetFromSorted(comparator, input, policy);
        // TreeSet(SortedSet) adopts the source comparator and uses the O(n)
        // bottom-up build -- no per-element add/rebalance.
        TreeSet<T> nav = new TreeSet<>(ecSet);
        return new NavigableTreeSet<>(nav, ecSet);
    }

    /** The comparator ({@code null} for natural order). */
    public Comparator<? super T> comparator()
    {
        return this.nav.comparator();
    }

    /** A new empty set sharing this set's comparator (for materialized snapshots). */
    private NavigableTreeSet<T> emptyLike()
    {
        Comparator<? super T> cmp = this.nav.comparator();
        TreeSet<T> navOut = cmp == null ? new TreeSet<>() : new TreeSet<>(cmp);
        MutableSortedSet<T> ecOut = cmp == null
                ? TreeSortedSet.newSet()
                : TreeSortedSet.newSet(cmp);
        return new NavigableTreeSet<>(navOut, ecOut);
    }

    /** The backing EC sorted set (kept in sync with the navigation view). */
    public MutableSortedSet<T> ecSet()
    {
        return this.ecSet;
    }

    // ---- mutation (kept in sync across both stores) -----------------------

    /** Add {@code value}; returns {@code true} if newly inserted. */
    public boolean add(T value)
    {
        this.ecSet.add(value);
        return this.nav.add(value);
    }

    /** Remove {@code value}; returns {@code true} if it was present. */
    public boolean remove(T value)
    {
        this.ecSet.remove(value);
        return this.nav.remove(value);
    }

    /** Remove all elements. */
    public void clear()
    {
        this.ecSet.clear();
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

    public boolean contains(T value)
    {
        return this.nav.contains(value);
    }

    // ---- point navigation -------------------------------------------------

    /** Greatest element {@code <= x}, or {@code null}. */
    public T floor(T x)
    {
        return this.nav.floor(x);
    }

    /** Least element {@code >= x}, or {@code null}. */
    public T ceiling(T x)
    {
        return this.nav.ceiling(x);
    }

    /** Greatest element {@code < x} (strict), or {@code null}. */
    public T lower(T x)
    {
        return this.nav.lower(x);
    }

    /** Least element {@code > x} (strict), or {@code null}. */
    public T higher(T x)
    {
        return this.nav.higher(x);
    }

    /** Minimum element, or {@code null} when empty. */
    public T first()
    {
        return this.nav.isEmpty() ? null : this.nav.first();
    }

    /** Maximum element, or {@code null} when empty. */
    public T last()
    {
        return this.nav.isEmpty() ? null : this.nav.last();
    }

    // ---- poll -------------------------------------------------------------

    /** Remove and return the minimum element, or empty (does not trap on empty). */
    public Optional<T> pollFirst()
    {
        T v = this.nav.pollFirst();
        if (v == null)
        {
            return Optional.empty();
        }
        this.ecSet.remove(v);
        return Optional.of(v);
    }

    /** Remove and return the maximum element, or empty (does not trap on empty). */
    public Optional<T> pollLast()
    {
        T v = this.nav.pollLast();
        if (v == null)
        {
            return Optional.empty();
        }
        this.ecSet.remove(v);
        return Optional.of(v);
    }

    // ---- order statistics (rank / select; spec features/rank-select.md) ----

    /**
     * The number of elements strictly less than {@code x} under this set's
     * comparator — the 0-based lower-bound index {@code x} occupies (if present)
     * or would occupy (if absent). Defined for present and absent elements
     * alike; the result is in {@code 0..=size()} ({@code size()} for any element
     * greater than the maximum). Pure query; never mutates.
     *
     * <p><strong>Java carve-out (complexity relaxed).</strong> Computed by an
     * ordered scan over the boxed tree — O(n) rather than the O(log n) the
     * subtree-size-augmented native ports give. Observable results are
     * identical (see {@code style/java.md}).
     */
    public int rank(T x)
    {
        Comparator<? super T> cmp = this.nav.comparator();
        int rank = 0;
        for (T t : this.nav)
        {
            int c = cmp == null ? t.compareTo(x) : cmp.compare(t, x);
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
     * The {@code i}-th smallest element (0-based; the order-statistic
     * {@code select}, per the {@code select}-name reservation in
     * {@code features/rank-select.md}), or empty when {@code i} is out of range.
     * {@code i >= size()} (including on an empty set) and {@code i < 0} both
     * return {@link Optional#empty()} and never trap. Round-trips with
     * {@link #rank}: {@code select(rank(x))} is present and equals {@code x} for
     * any present {@code x}, and {@code rank(select(i).get()) == i} for every
     * {@code 0 <= i < size()}.
     *
     * <p>Java carve-out: O(n) ordered scan (see {@link #rank}).
     */
    public Optional<T> select(int i)
    {
        if (i < 0 || i >= this.nav.size())
        {
            return Optional.empty();
        }
        int idx = 0;
        for (T t : this.nav)
        {
            if (idx == i)
            {
                return Optional.of(t);
            }
            idx++;
        }
        return Optional.empty();
    }

    // ---- range slice & descending -----------------------------------------

    /** Elements in {@code range}, ascending (materialized snapshot). */
    public List<T> rangeElements(Range<T> range)
    {
        List<T> out = new ArrayList<>();
        for (T x : this.nav)
        {
            if (range.contains(x))
            {
                out.add(x);
            }
        }
        return out;
    }

    /** Elements in {@code range}, descending. */
    public List<T> descendingRangeElements(Range<T> range)
    {
        List<T> out = new ArrayList<>();
        for (T x : this.nav.descendingSet())
        {
            if (range.contains(x))
            {
                out.add(x);
            }
        }
        return out;
    }

    /** All elements, descending. */
    public List<T> descending()
    {
        return new ArrayList<>(this.nav.descendingSet());
    }

    /**
     * A new independent set of the elements ∈ {@code range}. Mutating the
     * snapshot never affects the original and vice versa; the snapshot preserves
     * the source comparator / ordering.
     */
    public NavigableTreeSet<T> subSet(Range<T> range)
    {
        NavigableTreeSet<T> out = this.emptyLike();
        for (T x : this.nav)
        {
            if (range.contains(x))
            {
                out.add(x);
            }
        }
        return out;
    }

    /** Remove every element ∈ {@code range}; returns the count removed. */
    public int removeRange(Range<T> range)
    {
        List<T> victims = this.rangeElements(range);
        for (T x : victims)
        {
            this.remove(x);
        }
        return victims.size();
    }
}

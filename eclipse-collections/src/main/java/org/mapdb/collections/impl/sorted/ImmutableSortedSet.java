// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.sorted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.mapdb.collections.impl.range.Range;

/**
 * Compact immutable sorted set backed by a single packed ascending array,
 * queried by binary search (spec {@code features/sorted-table-map.md}). The
 * element analogue of {@link ImmutableSortedMap}. Built once from
 * strictly-ascending input via {@link #fromSorted}; thereafter immutable.
 *
 * <p>Boxed carve-out and trap posture as for {@link ImmutableSortedMap}:
 * construction <strong>traps</strong> ({@link IllegalArgumentException}) unless
 * the input is strictly ascending (out-of-order or duplicate elements trap);
 * empty and single-element input are valid; the input is copied (snapshot).
 * Absence is returned uniformly via {@link Optional}.
 *
 * @param <T> element type (totally-ordered, non-null)
 */
public final class ImmutableSortedSet<T extends Comparable<? super T>>
{
    private final List<T> elems;

    private ImmutableSortedSet(List<T> elems)
    {
        this.elems = elems;
    }

    /**
     * Build from a <strong>strictly ascending</strong> {@code int} element array
     * (the i32 cross-language suite). The input is copied (snapshot).
     *
     * @throws IllegalArgumentException if the elements are not strictly ascending
     *         or contain a duplicate. Empty and single-element input are valid.
     */
    public static ImmutableSortedSet<Integer> fromSorted(int[] elements)
    {
        List<Integer> es = new ArrayList<>(elements.length);
        for (int e : elements)
        {
            es.add(e);
        }
        assertStrictlyAscending(es);
        return new ImmutableSortedSet<>(es);
    }

    /**
     * Build from a <strong>strictly ascending</strong> boxed element list (copied
     * — snapshot).
     *
     * @throws IllegalArgumentException on out-of-order or duplicate elements.
     */
    public static <T extends Comparable<? super T>> ImmutableSortedSet<T> fromSorted(List<T> elements)
    {
        List<T> es = new ArrayList<>(elements);
        assertStrictlyAscending(es);
        return new ImmutableSortedSet<>(es);
    }

    private static <T extends Comparable<? super T>> void assertStrictlyAscending(List<T> es)
    {
        for (int i = 1; i < es.size(); i++)
        {
            if (es.get(i - 1).compareTo(es.get(i)) >= 0)
            {
                throw new IllegalArgumentException(
                        "ImmutableSortedSet: input must be strictly ascending "
                                + "(no duplicate or out-of-order elements)");
            }
        }
    }

    /** Number of elements. */
    public int size()
    {
        return this.elems.size();
    }

    /** Whether the set is empty. */
    public boolean isEmpty()
    {
        return this.elems.isEmpty();
    }

    private int search(T elem)
    {
        return Collections.binarySearch(this.elems, elem);
    }

    private int lowerBound(int searchResult)
    {
        return searchResult >= 0 ? searchResult : -(searchResult) - 1;
    }

    /** Whether {@code elem} is present. */
    public boolean contains(T elem)
    {
        return this.search(elem) >= 0;
    }

    /** Minimum element, or empty. */
    public Optional<T> first()
    {
        return this.elems.isEmpty() ? Optional.empty() : Optional.of(this.elems.get(0));
    }

    /** Maximum element, or empty. */
    public Optional<T> last()
    {
        return this.elems.isEmpty() ? Optional.empty() : Optional.of(this.elems.get(this.elems.size() - 1));
    }

    /** Greatest element {@code <= k}, or empty. */
    public Optional<T> floor(T k)
    {
        int s = this.search(k);
        int i = s >= 0 ? s : -(s) - 1 - 1;
        return i >= 0 ? Optional.of(this.elems.get(i)) : Optional.empty();
    }

    /** Least element {@code >= k}, or empty. */
    public Optional<T> ceiling(T k)
    {
        int i = this.lowerBound(this.search(k));
        return i < this.elems.size() ? Optional.of(this.elems.get(i)) : Optional.empty();
    }

    /** Greatest element {@code < k} (strict), or empty. */
    public Optional<T> lower(T k)
    {
        int i = this.lowerBound(this.search(k)) - 1;
        return i >= 0 ? Optional.of(this.elems.get(i)) : Optional.empty();
    }

    /** Least element {@code > k} (strict), or empty. */
    public Optional<T> higher(T k)
    {
        int s = this.search(k);
        int i = s >= 0 ? s + 1 : -(s) - 1;
        return i < this.elems.size() ? Optional.of(this.elems.get(i)) : Optional.empty();
    }

    /** Number of elements <strong>strictly less than</strong> {@code elem} (lower-bound index). */
    public int rank(T elem)
    {
        return this.lowerBound(this.search(elem));
    }

    /** The {@code i}-th smallest element (0-based), or empty if {@code i >= size()} or {@code i < 0}. */
    public Optional<T> select(int i)
    {
        return i >= 0 && i < this.elems.size() ? Optional.of(this.elems.get(i)) : Optional.empty();
    }

    /** Elements in ascending order (materialized snapshot). */
    public List<T> elements()
    {
        return new ArrayList<>(this.elems);
    }

    /** All elements, descending. */
    public List<T> descendingElements()
    {
        List<T> out = new ArrayList<>(this.elems);
        Collections.reverse(out);
        return out;
    }

    /**
     * Elements ∈ {@code range}, ascending. Bracketed by two binary searches from
     * the range's cut semantics (overflow-safe at the signed extremes).
     */
    public List<T> rangeElements(Range<T> range)
    {
        int[] b = range.bracket(this.elems);
        return new ArrayList<>(this.elems.subList(b[0], b[1]));
    }

    /** Elements ∈ {@code range}, descending. */
    public List<T> descendingRangeElements(Range<T> range)
    {
        List<T> out = this.rangeElements(range);
        Collections.reverse(out);
        return out;
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.range;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A mutable piecewise mapping from disjoint non-empty {@link Range}s to values
 * (v1 ships the {@code Integer -> Integer} / i32&rarr;i32 specialisation).
 *
 * <p><strong>Unlike {@link RangeSet}, a {@code RangeMap} does NOT coalesce across
 * different values.</strong> {@link #put} is last-writer-wins: it clips/splits
 * every overlapping prior entry and inserts the new {@code (range, value)}, but
 * leaves adjacent equal-valued entries <strong>distinct</strong>.
 * {@link #putCoalescing} is the variant that merges connected neighbours holding
 * an <strong>equal</strong> value.
 *
 * <p>Every clip / split / merge / ordering decision reduces to the side-aware cut
 * comparisons of {@link Range}; there is <strong>no {@code +-1} endpoint
 * arithmetic</strong> (the {@code INT_MIN}/{@code INT_MAX} overflow trap).
 *
 * <h2>Java carve-out (boxed; observable behaviour identical)</h2>
 *
 * <p>Like {@link RangeSet} and the fork's other boxed carve-outs, this is a boxed
 * generic over {@code C extends Comparable<? super C>} and an arbitrary value
 * type {@code V}; the backing is a flat {@link ArrayList} in normal form (a tree
 * keyed by lower cut would give identical results). Absence-returning queries
 * ({@link #get}, {@link #getEntry}, {@link #span}) return {@link Optional}
 * <strong>uniformly</strong>. {@code Range} arguments are the boxed {@link Range};
 * the split/clip/merge is pure cut arithmetic, never {@code (value, inclusive)}
 * booleans or {@code +-1} math. Conformance basis: the {@code 15-range-set-map/}
 * cross-language scenarios plus the native test battery.
 *
 * @param <C> the totally-ordered, non-null endpoint type
 * @param <V> the value type
 */
public final class RangeMap<C extends Comparable<? super C>, V>
{
    /** Normal form: non-empty, pairwise disjoint, ascending by lower cut. */
    private final List<Entry<C, V>> entries;

    /** An empty range map. */
    public RangeMap()
    {
        this.entries = new ArrayList<>();
    }

    private RangeMap(List<Entry<C, V>> entries)
    {
        this.entries = entries;
    }

    /**
     * A {@code (range, value)} entry of a {@link RangeMap}.
     *
     * @param <C> the endpoint type
     * @param <V> the value type
     */
    public static final class Entry<C extends Comparable<? super C>, V>
    {
        private final Range<C> range;
        private final V value;

        Entry(Range<C> range, V value)
        {
            this.range = range;
            this.value = value;
        }

        /** The entry's range. */
        public Range<C> getRange()
        {
            return this.range;
        }

        /** The entry's value. */
        public V getValue()
        {
            return this.value;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof Entry<?, ?>))
            {
                return false;
            }
            Entry<?, ?> e = (Entry<?, ?>) o;
            return this.range.equals(e.range) && Objects.equals(this.value, e.value);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(this.range, this.value);
        }

        @Override
        public String toString()
        {
            return this.range + " -> " + this.value;
        }
    }

    /**
     * Assign {@code value} to <strong>every</strong> point of {@code range},
     * <strong>last-writer-wins</strong> over any prior overlap. Existing entries
     * are clipped to the parts outside {@code range} (a straddling entry
     * <strong>splits into two</strong>, both keeping the old value); the new
     * {@code (range, value)} is then inserted. A <strong>cut-empty</strong>
     * {@code range} is a <strong>no-op</strong>. {@code put} does
     * <strong>not</strong> coalesce — an adjacent equal value stays a distinct
     * entry.
     */
    public void put(Range<C> range, V value)
    {
        Objects.requireNonNull(range, "range");
        // Non-null values keep the Optional absence semantics of get/getEntry
        // unambiguous (a present mapping is never confused with no mapping),
        // matching the fork's non-null Range / tree-wrapper conventions.
        Objects.requireNonNull(value, "value");
        if (range.isEmpty())
        {
            return;
        }
        this.clipOut(range);
        this.insertEntry(range, value);
    }

    /**
     * Like {@link #put}, then <strong>merge</strong> the inserted entry with any
     * <strong>connected</strong> (overlapping <em>or</em> abutting) neighbour
     * whose value <strong>equals</strong> {@code value}, producing one entry
     * spanning the union. Neighbours with a different value are left untouched
     * (clipped by the {@code put} step as usual).
     */
    public void putCoalescing(Range<C> range, V value)
    {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(value, "value");
        if (range.isEmpty())
        {
            return;
        }
        this.clipOut(range);
        // Span over every connected entry with an EQUAL value, dropping them.
        Range<C> merged = range;
        List<Entry<C, V>> out = new ArrayList<>(this.entries.size() + 1);
        for (Entry<C, V> e : this.entries)
        {
            if (Objects.equals(e.value, value) && e.range.isConnected(merged))
            {
                merged = e.range.span(merged);
            }
            else
            {
                out.add(e);
            }
        }
        this.entries.clear();
        this.entries.addAll(out);
        this.insertEntry(merged, value);
    }

    /** The value mapped at {@code value}, or {@link Optional#empty()} if uncovered. */
    public Optional<V> get(C value)
    {
        Objects.requireNonNull(value, "value");
        for (Entry<C, V> e : this.entries)
        {
            if (e.range.contains(value))
            {
                return Optional.ofNullable(e.value);
            }
        }
        return Optional.empty();
    }

    /** The {@code (range, value)} entry covering {@code value}, or {@link Optional#empty()}. */
    public Optional<Entry<C, V>> getEntry(C value)
    {
        Objects.requireNonNull(value, "value");
        for (Entry<C, V> e : this.entries)
        {
            if (e.range.contains(value))
            {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Unmap {@code range}, <strong>splitting</strong> any entry straddling either
     * boundary (both fragments keep the old value). A cut-empty {@code range} is a
     * <strong>no-op</strong>.
     */
    public void remove(Range<C> range)
    {
        Objects.requireNonNull(range, "range");
        if (range.isEmpty())
        {
            return;
        }
        this.clipOut(range);
    }

    /**
     * The minimum range enclosing all entry ranges; {@link Optional#empty()} on
     * an empty map.
     */
    public Optional<Range<C>> span()
    {
        if (this.entries.isEmpty())
        {
            return Optional.empty();
        }
        Range<C> first = this.entries.get(0).range;
        Range<C> last = this.entries.get(this.entries.size() - 1).range;
        return Optional.of(Range.fromCutsInternal(first.lowerCut(), last.upperCut()));
    }

    /**
     * A <strong>new</strong> independent {@code RangeMap} restricted to
     * {@code view} (each entry range clipped to {@code view}, values preserved).
     * An independent snapshot: mutating it does not affect this map.
     */
    public RangeMap<C, V> subRangeMap(Range<C> view)
    {
        Objects.requireNonNull(view, "view");
        List<Entry<C, V>> out = new ArrayList<>();
        for (Entry<C, V> e : this.entries)
        {
            Optional<Range<C>> i = e.range.intersection(view);
            if (i.isPresent() && !i.get().isEmpty())
            {
                out.add(new Entry<>(i.get(), e.value));
            }
        }
        return new RangeMap<>(out);
    }

    /**
     * The canonical disjoint {@code (range, value)} entries,
     * <strong>ascending by lower cut</strong>, as a new independent list (a
     * defensive copy).
     */
    public List<Entry<C, V>> asMapOfRanges()
    {
        return new ArrayList<>(this.entries);
    }

    /** Whether the map has no entries. */
    public boolean isEmpty()
    {
        return this.entries.isEmpty();
    }

    /** Remove all entries. */
    public void clear()
    {
        this.entries.clear();
    }

    // ---- internals --------------------------------------------------------

    /**
     * Clip every entry to the parts <strong>outside</strong> {@code range} (the
     * {@code remove} / overlap-resolution split). A straddling entry becomes two
     * fragments; an entry fully inside {@code range} is dropped. Pure cut
     * arithmetic — the boundary cuts flip, never {@code +-1}. Abutment alone
     * (cut-empty intersection) leaves an entry untouched.
     */
    private void clipOut(Range<C> range)
    {
        List<Entry<C, V>> out = new ArrayList<>(this.entries.size() + 1);
        for (Entry<C, V> e : this.entries)
        {
            Range<C> r = e.range;
            Optional<Range<C>> i = r.intersection(range);
            if (i.isPresent() && !i.get().isEmpty())
            {
                // Left fragment below the removed range's lower cut.
                if (r.lowerCut().compareTo(range.lowerCut()) < 0)
                {
                    out.add(new Entry<>(Range.fromCutsInternal(r.lowerCut(), range.lowerCut()), e.value));
                }
                // Right fragment above the removed range's upper cut.
                if (range.upperCut().compareTo(r.upperCut()) < 0)
                {
                    out.add(new Entry<>(Range.fromCutsInternal(range.upperCut(), r.upperCut()), e.value));
                }
            }
            else
            {
                out.add(e);
            }
        }
        this.entries.clear();
        this.entries.addAll(out);
    }

    /**
     * Insert {@code (range, value)} at its ascending-by-lower-cut position.
     * Callers must have already cleared the overlap (via {@link #clipOut});
     * {@code range} is disjoint from every remaining entry.
     */
    private void insertEntry(Range<C> range, V value)
    {
        int pos = this.entries.size();
        for (int i = 0; i < this.entries.size(); i++)
        {
            if (this.entries.get(i).range.lowerCut().compareTo(range.lowerCut()) > 0)
            {
                pos = i;
                break;
            }
        }
        this.entries.add(pos, new Entry<>(range, value));
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RangeMap<?, ?>))
        {
            return false;
        }
        return this.entries.equals(((RangeMap<?, ?>) o).entries);
    }

    @Override
    public int hashCode()
    {
        return this.entries.hashCode();
    }

    @Override
    public String toString()
    {
        return this.entries.toString();
    }
}

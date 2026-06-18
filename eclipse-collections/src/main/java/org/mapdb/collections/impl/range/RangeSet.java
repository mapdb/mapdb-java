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
 * A mutable, auto-coalescing set of cut-regions over a totally-ordered,
 * non-null {@code C} (v1 ships the {@code Integer}/i32 universe of the
 * cross-language validation suite).
 *
 * <p>A {@code RangeSet} stores a collection of <strong>disjoint, non-empty,
 * pairwise non-connected</strong> {@link Range}s (the <em>normal form</em>). It
 * auto-coalesces on {@link #add}: two ranges merge iff they are
 * {@link Range#isConnected connected} — which is <strong>broader</strong> than
 * mere overlap, because an <em>abutment</em> (a cut-touch, e.g. {@code [1, 3)} &amp;
 * {@code [3, 5)}) is also connected. Every coalescing / split / complement /
 * ordering decision reduces to the side-aware cut comparisons of {@link Range};
 * there is no {@code (value, inclusive)} boolean reasoning and
 * <strong>no {@code +-1} endpoint arithmetic</strong> (the {@code INT_MIN}/
 * {@code INT_MAX} overflow trap).
 *
 * <h2>Cut-region, not integer-value-set</h2>
 *
 * <p>Because Phase 0 has no {@code DiscreteDomain}, a {@code RangeSet} models
 * <em>cut-regions</em>, not the set of {@code i32} values they happen to contain.
 * {@code add(open(1, 2))} over {@code Integer} produces a <strong>non-empty</strong>
 * set whose single stored range {@code (1, 2)} is cut-non-empty even though
 * {@link #contains} is false for every {@code Integer}. So {@code {}} and
 * {@code {(1, 2)}} are <strong>distinct</strong> RangeSets. Every set-level
 * predicate ({@link #isEmpty}, canonicality, {@link #complement},
 * {@link #intersects}, {@link #span}) is defined on the stored cut-regions; only
 * the point queries ({@link #contains} / {@link #rangeContaining}) ask about an
 * actual {@code C} value.
 *
 * <h2>Java carve-out (boxed; observable behaviour identical)</h2>
 *
 * <p>Eclipse Collections ships no primitive {@code RangeSet} (and no primitive
 * {@code Range}), so — like the fork's boxed-tree, boxed-{@code Range},
 * NavigableMap and ImmutableSortedMap carve-outs — {@code RangeSet} is a boxed
 * generic over {@code C extends Comparable<? super C>}. The backing here is a
 * flat {@link ArrayList} kept in normal form (a tree keyed by lower cut would
 * give identical results); only the per-port complexity guarantee is relaxed
 * (boxing overhead), never the observable behaviour. {@code Range} arguments are
 * the boxed {@link Range}; coalescing, splitting, complement and ordering reduce
 * to its cut comparisons, never to {@code (value, inclusive)} booleans or
 * {@code +-1} math. Absence-returning queries ({@link #rangeContaining},
 * {@link #span}) return {@link Optional} <strong>uniformly</strong> (never a mix
 * of {@code null} and {@code Optional}), matching the fork's tree-wrapper
 * convention. Conformance basis: the {@code 15-range-set-map/} cross-language
 * scenarios plus the native test battery.
 *
 * @param <C> the totally-ordered, non-null endpoint type
 */
public final class RangeSet<C extends Comparable<? super C>>
{
    /** Normal form: non-empty, pairwise non-connected, ascending by lower cut. */
    private final List<Range<C>> ranges;

    /** An empty range set. */
    public RangeSet()
    {
        this.ranges = new ArrayList<>();
    }

    private RangeSet(List<Range<C>> ranges)
    {
        this.ranges = ranges;
    }

    /**
     * Union {@code range} in, coalescing <strong>all connected</strong> stored
     * ranges. A <strong>cut-empty</strong> {@code range} (e.g.
     * {@code closedOpen(5, 5)}) is a <strong>no-op</strong>, decided by
     * {@link Range#isEmpty} (cut-empty), never by discrete cardinality —
     * {@code add(open(1, 2))} over {@code Integer} <strong>stores</strong> the
     * range. The merged range keeps the <strong>outer</strong> cuts of every
     * connected member (the cut min/max, no {@code +-1} math).
     */
    public void add(Range<C> range)
    {
        Objects.requireNonNull(range, "range");
        // Empty-range no-op (cut-empty), per the normative empty-range rule.
        if (range.isEmpty())
        {
            return;
        }
        // Merge range with every connected stored range, spanning all of them.
        // Connectivity (overlap OR abutment) is the coalescing predicate.
        Range<C> merged = range;
        List<Range<C>> out = new ArrayList<>(this.ranges.size() + 1);
        for (Range<C> r : this.ranges)
        {
            if (r.isConnected(merged))
            {
                merged = r.span(merged);
            }
            else
            {
                out.add(r);
            }
        }
        // Insert merged at its ascending-by-lower-cut position.
        int pos = out.size();
        for (int i = 0; i < out.size(); i++)
        {
            if (out.get(i).lowerCut().compareTo(merged.lowerCut()) > 0)
            {
                pos = i;
                break;
            }
        }
        out.add(pos, merged);
        this.ranges.clear();
        this.ranges.addAll(out);
    }

    /**
     * {@link #add} each range; the final normal form is order-independent.
     */
    public void addAll(Iterable<Range<C>> ranges)
    {
        Objects.requireNonNull(ranges, "ranges");
        for (Range<C> r : ranges)
        {
            this.add(r);
        }
    }

    /**
     * Subtract {@code range}, <strong>splitting</strong> any stored range
     * straddling either boundary. A cut-empty {@code range} is a
     * <strong>no-op</strong>. The split is pure cut arithmetic — the boundary
     * cuts flip ({@code remove([4, 7))} from {@code [1, 9]} leaves {@code [1, 4)}
     * and {@code [7, 9]}), never {@code +-1}. Abutment alone (cut-empty
     * intersection) does not split.
     */
    public void remove(Range<C> range)
    {
        Objects.requireNonNull(range, "range");
        if (range.isEmpty())
        {
            return;
        }
        List<Range<C>> out = new ArrayList<>(this.ranges.size() + 1);
        for (Range<C> r : this.ranges)
        {
            Optional<Range<C>> i = r.intersection(range);
            if (i.isPresent() && !i.get().isEmpty())
            {
                // Left fragment: r below the removed range's lower cut.
                if (r.lowerCut().compareTo(range.lowerCut()) < 0)
                {
                    out.add(Range.fromCutsInternal(r.lowerCut(), range.lowerCut()));
                }
                // Right fragment: r above the removed range's upper cut.
                if (range.upperCut().compareTo(r.upperCut()) < 0)
                {
                    out.add(Range.fromCutsInternal(range.upperCut(), r.upperCut()));
                }
            }
            else
            {
                out.add(r);
            }
        }
        this.ranges.clear();
        this.ranges.addAll(out);
    }

    /**
     * Whether {@code value} falls in some stored range. This is the
     * <strong>only</strong> integer-point predicate — {@code (1, 2)} correctly
     * contains no {@code Integer}.
     */
    public boolean contains(C value)
    {
        Objects.requireNonNull(value, "value");
        for (Range<C> r : this.ranges)
        {
            if (r.contains(value))
            {
                return true;
            }
        }
        return false;
    }

    /** The stored range containing {@code value}, or {@link Optional#empty()}. */
    public Optional<Range<C>> rangeContaining(C value)
    {
        Objects.requireNonNull(value, "value");
        for (Range<C> r : this.ranges)
        {
            if (r.contains(value))
            {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether some <strong>single</strong> stored range encloses {@code range}
     * (cut-defined {@link Range#encloses}). A set covering {@code {[1, 3), [5, 9)}}
     * does <strong>not</strong> enclose {@code [2, 6)} — no single stored range
     * does.
     */
    public boolean encloses(Range<C> range)
    {
        Objects.requireNonNull(range, "range");
        for (Range<C> r : this.ranges)
        {
            if (r.encloses(range))
            {
                return true;
            }
        }
        return false;
    }

    /** Whether {@link #encloses} holds for <strong>every</strong> argument. */
    public boolean enclosesAll(Iterable<Range<C>> ranges)
    {
        Objects.requireNonNull(ranges, "ranges");
        for (Range<C> r : ranges)
        {
            if (!this.encloses(r))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code range} has a <strong>cut-non-empty intersection</strong>
     * with some stored range — pure cut algebra. An <strong>abutment</strong> is
     * <em>not</em> an intersection ({@code intersects([3, 5))} against
     * {@code [5, 9)} is false); a cut-empty query never intersects; but a
     * discrete-empty-yet-cut-non-empty overlap <strong>does</strong> count
     * ({@code intersects(open(1, 2))} against stored {@code (1, 2)} is
     * <strong>true</strong>, though no {@code Integer} lies in it).
     */
    public boolean intersects(Range<C> range)
    {
        Objects.requireNonNull(range, "range");
        for (Range<C> r : this.ranges)
        {
            Optional<Range<C>> i = r.intersection(range);
            if (i.isPresent() && !i.get().isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The minimum enclosing range {@code [min lower cut, max upper cut]};
     * {@link Optional#empty()} on an empty set.
     */
    public Optional<Range<C>> span()
    {
        if (this.ranges.isEmpty())
        {
            return Optional.empty();
        }
        Range<C> first = this.ranges.get(0);
        Range<C> last = this.ranges.get(this.ranges.size() - 1);
        return Optional.of(Range.fromCutsInternal(first.lowerCut(), last.upperCut()));
    }

    /**
     * A <strong>new</strong> independent {@code RangeSet} of the cut-region
     * <strong>gaps</strong> between the stored ranges over the full
     * {@code (-inf, +inf)} domain. {@code complement(empty)} = {@code {all()}};
     * {@code complement({all()})} = {@code {}}; no spurious {@code +-inf} gap when
     * an end is already unbounded; the boundary side flips (closed&harr;open at
     * the same cut value). {@code complement(complement(S)) == S}.
     *
     * <p>This is a materialized snapshot (the Guava {@code complement()} live-view
     * divergence shared with {@code navigable-map.md} sub-views): mutating it does
     * not affect this set and vice versa.
     */
    public RangeSet<C> complement()
    {
        List<Range<C>> out = new ArrayList<>();
        // Walking cut: the lower cut of the next gap. Starts at -inf.
        Range.Cut<C> cursor = Range.<C>all().lowerCut();
        Range.Cut<C> aboveAll = Range.<C>all().upperCut();
        for (Range<C> r : this.ranges)
        {
            // Gap from cursor up to this range's lower cut, when non-empty.
            if (cursor.compareTo(r.lowerCut()) < 0)
            {
                out.add(Range.fromCutsInternal(cursor, r.lowerCut()));
            }
            // Next gap starts just past this range's upper cut.
            cursor = r.upperCut();
        }
        // Trailing gap from the last upper cut to +inf, when non-empty.
        if (cursor.compareTo(aboveAll) < 0)
        {
            out.add(Range.fromCutsInternal(cursor, aboveAll));
        }
        return new RangeSet<>(out);
    }

    /**
     * A <strong>new</strong> independent {@code RangeSet} = this set
     * <strong>intersected</strong> with {@code view} (the in-{@code view} slice,
     * each stored range clipped to {@code view}).
     * {@code subRangeSet([3, 6))} of {@code {[1, 5), [8, 9]}} = {@code {[3, 5)}}.
     * An independent snapshot: mutating it does not affect this set.
     */
    public RangeSet<C> subRangeSet(Range<C> view)
    {
        Objects.requireNonNull(view, "view");
        List<Range<C>> out = new ArrayList<>();
        for (Range<C> r : this.ranges)
        {
            Optional<Range<C>> i = r.intersection(view);
            if (i.isPresent() && !i.get().isEmpty())
            {
                out.add(i.get());
            }
        }
        // The stored ranges are ascending and disjoint, so their clipped images
        // stay ascending, disjoint and non-connected.
        return new RangeSet<>(out);
    }

    /**
     * The canonical disjoint ranges, <strong>ascending by lower cut</strong>, as
     * a new independent list (a defensive copy; mutating it does not affect this
     * set).
     */
    public List<Range<C>> asRanges()
    {
        return new ArrayList<>(this.ranges);
    }

    /**
     * Whether the set has <strong>no stored ranges</strong>. A cut-region
     * predicate — {@code {(1, 2)}} is <strong>not</strong> empty even though it
     * contains no {@code Integer}.
     */
    public boolean isEmpty()
    {
        return this.ranges.isEmpty();
    }

    /** Remove all ranges. */
    public void clear()
    {
        this.ranges.clear();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RangeSet<?>))
        {
            return false;
        }
        return this.ranges.equals(((RangeSet<?>) o).ranges);
    }

    @Override
    public int hashCode()
    {
        return this.ranges.hashCode();
    }

    @Override
    public String toString()
    {
        return this.ranges.toString();
    }
}

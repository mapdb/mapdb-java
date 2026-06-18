// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.range;

import java.util.Objects;
import java.util.Optional;

/**
 * Bound / Range value model — a pure in-memory value type describing a region
 * {@code [lo, hi)}, {@code (-inf, hi]}, {@code (lo, +inf)}, &hellip; with each
 * endpoint independently unbounded / open / closed.
 *
 * <p>This is <strong>not</strong> {@code Interval} (which materialises an
 * arithmetic progression and enumerates elements). A {@code Range} holds no
 * elements; it only describes a region ({@link #contains}) and supports the
 * open/unbounded endpoints {@code Interval} cannot.
 *
 * <p>The design follows Google Guava's {@code Range<C>} / {@code BoundType} /
 * {@code Cut}. The algebra ({@link #intersection}, {@link #span},
 * {@link #isConnected}, {@link #encloses}) is total and unambiguous because
 * endpoints are modelled as <em>cuts between values</em> rather than
 * {@code (value, inclusive)} pairs. See {@code spec/features/bound-range.md} for
 * the normative algorithms; every operation here reduces to a side-aware cut
 * comparison, never to a {@code (value, inclusive)} boolean.
 *
 * <h2>Side-aware cut ordering</h2>
 *
 * <p>{@code Unbounded} is contextual: as a lower cut it is {@code -inf}, as an
 * upper cut it is {@code +inf}. There is therefore no single context-free order
 * on one {@code Unbounded} value. We avoid that trap by splitting the unbounded
 * state into two distinct sentinels — {@code BelowAll} ({@code -inf}) and
 * {@code AboveAll} ({@code +inf}). With those two sentinels the four-variant
 * {@link Cut} has a single total order
 * ({@code BelowAll < Below(v) < Above(v) < AboveAll}, finite cuts breaking ties
 * by value then {@code Below < Above}), and the three spec comparators
 * ({@code compareLowerCuts}, {@code compareUpperCuts},
 * {@code compareLowerToUpper}) all collapse onto it. A lower cut never holds
 * {@code AboveAll}; an upper cut never holds {@code BelowAll}; that invariant is
 * established by the factories.
 *
 * <h2>Java carve-out (boxed)</h2>
 *
 * <p>Eclipse Collections ships no primitive {@code Range} type, so — like the
 * fork's boxed-tree carve-out — {@code Range} is a boxed generic over
 * {@code C extends Comparable<? super C>}. Endpoint values passed to the
 * factories <strong>must be non-null</strong>: unboundedness is expressed only
 * through the factory chosen ({@link #atLeast}/{@link #lessThan}/{@link #all}/
 * &hellip;), never by passing {@code null} — {@code null} is never conflated with
 * {@code Unbounded}. v1 ships the {@code i32} ({@code Integer}) specialisation of
 * the cross-language validation universe, but the type itself is generic over any
 * totally-ordered, non-null {@code Comparable}.
 *
 * @param <C> the totally-ordered, non-null endpoint type
 */
public final class Range<C extends Comparable<? super C>>
{
    private final Cut<C> lower;
    private final Cut<C> upper;

    private Range(Cut<C> lower, Cut<C> upper)
    {
        this.lower = lower;
        this.upper = upper;
    }

    /**
     * Construct from raw cuts after validating {@code lower <= upper}.
     *
     * @throws IllegalArgumentException if the cuts are out of order (a
     *         programming error, like {@code Interval.toReversed()} at the
     *         minimum step). {@code open(5, 1)} / {@code closed(5, 1)} /
     *         {@code open(v, v)} all trap here.
     */
    private static <C extends Comparable<? super C>> Range<C> fromCuts(Cut<C> lower, Cut<C> upper)
    {
        if (lower.compareTo(upper) > 0)
        {
            throw new IllegalArgumentException(
                    "Range: lower cut must not exceed upper cut (" + lower + " > " + upper + ")");
        }
        return new Range<>(lower, upper);
    }

    private static <C> C requireEndpoint(C value, String which)
    {
        return Objects.requireNonNull(value,
                which + " endpoint must be non-null; express unboundedness via the factory (atLeast/lessThan/all/...)");
    }

    // ---- factories (Guava-parity names) -----------------------------------

    /**
     * {@code (a, b)} — both endpoints open. Throws if {@code a >= b} (including
     * {@code open(v, v)}, which is empty-but-invalid-as-open).
     */
    public static <C extends Comparable<? super C>> Range<C> open(C a, C b)
    {
        return fromCuts(Cut.above(requireEndpoint(a, "lower")), Cut.below(requireEndpoint(b, "upper")));
    }

    /** {@code [a, b]} — both endpoints closed. Throws if {@code a > b}. */
    public static <C extends Comparable<? super C>> Range<C> closed(C a, C b)
    {
        return fromCuts(Cut.below(requireEndpoint(a, "lower")), Cut.above(requireEndpoint(b, "upper")));
    }

    /** {@code (a, b]}. Throws if {@code a > b}. */
    public static <C extends Comparable<? super C>> Range<C> openClosed(C a, C b)
    {
        return fromCuts(Cut.above(requireEndpoint(a, "lower")), Cut.above(requireEndpoint(b, "upper")));
    }

    /**
     * {@code [a, b)}. Throws if {@code a > b}. {@code closedOpen(v, v)} is the
     * valid empty range {@code (Below(v), Below(v))}.
     */
    public static <C extends Comparable<? super C>> Range<C> closedOpen(C a, C b)
    {
        return fromCuts(Cut.below(requireEndpoint(a, "lower")), Cut.below(requireEndpoint(b, "upper")));
    }

    /** {@code (a, +inf)}. */
    public static <C extends Comparable<? super C>> Range<C> greaterThan(C a)
    {
        return fromCuts(Cut.above(requireEndpoint(a, "lower")), Cut.aboveAll());
    }

    /** {@code [a, +inf)}. */
    public static <C extends Comparable<? super C>> Range<C> atLeast(C a)
    {
        return fromCuts(Cut.below(requireEndpoint(a, "lower")), Cut.aboveAll());
    }

    /** {@code (-inf, b)}. */
    public static <C extends Comparable<? super C>> Range<C> lessThan(C b)
    {
        return fromCuts(Cut.belowAll(), Cut.below(requireEndpoint(b, "upper")));
    }

    /** {@code (-inf, b]}. */
    public static <C extends Comparable<? super C>> Range<C> atMost(C b)
    {
        return fromCuts(Cut.belowAll(), Cut.above(requireEndpoint(b, "upper")));
    }

    /** {@code (-inf, +inf)}. */
    public static <C extends Comparable<? super C>> Range<C> all()
    {
        Cut<C> lower = Cut.belowAll();
        Cut<C> upper = Cut.aboveAll();
        return new Range<>(lower, upper);
    }

    /** {@code [v, v]}. */
    public static <C extends Comparable<? super C>> Range<C> singleton(C v)
    {
        return fromCuts(Cut.below(requireEndpoint(v, "value")), Cut.above(requireEndpoint(v, "value")));
    }

    // ---- queries ----------------------------------------------------------

    /** Whether {@code x} falls within the range (normative {@code contains}). */
    public boolean contains(C x)
    {
        Objects.requireNonNull(x, "contains argument must be non-null");
        return this.lower.lowerOk(x) && this.upper.upperOk(x);
    }

    /**
     * Cut-empty: {@code lowerCut == upperCut}. This is <strong>not</strong>
     * discrete cardinality — {@code open(1, 2)} over {@code Integer} is not empty
     * (no {@code DiscreteDomain} in Phase 0) even though no integer is contained.
     */
    public boolean isEmpty()
    {
        return this.lower.compareTo(this.upper) == 0;
    }

    /** The bound type of the lower endpoint; {@code null} when unbounded below. */
    public BoundType lowerBoundType()
    {
        return this.lower.boundTypeAsLower();
    }

    /** The bound type of the upper endpoint; {@code null} when unbounded above. */
    public BoundType upperBoundType()
    {
        return this.upper.boundTypeAsUpper();
    }

    /** The lower endpoint value; {@code null} when unbounded below. */
    public C lowerEndpoint()
    {
        return this.lower.endpoint();
    }

    /** The upper endpoint value; {@code null} when unbounded above. */
    public C upperEndpoint()
    {
        return this.upper.endpoint();
    }

    /** Whether the lower endpoint is finite. */
    public boolean hasLowerBound()
    {
        return this.lower.isFinite();
    }

    /** Whether the upper endpoint is finite. */
    public boolean hasUpperBound()
    {
        return this.upper.isFinite();
    }

    // ---- algebra (all via cut comparison) ---------------------------------

    /**
     * Cut-defined containment: {@code self.lower <= other.lower} and
     * {@code self.upper >= other.upper}. This is <strong>not</strong>
     * {@code forall value in other: contains(value)} — {@code [1, 5)} encloses
     * the empty {@code [5, 5)} though {@code 5} is not in {@code [1, 5)}.
     */
    public boolean encloses(Range<C> other)
    {
        Objects.requireNonNull(other, "other");
        return this.lower.compareTo(other.lower) <= 0 && this.upper.compareTo(other.upper) >= 0;
    }

    /**
     * Whether there is a (possibly empty) range enclosed by both. Cut-equal
     * endpoints count as connected (empty overlap).
     */
    public boolean isConnected(Range<C> other)
    {
        Objects.requireNonNull(other, "other");
        return this.lower.compareTo(other.upper) <= 0 && other.lower.compareTo(this.upper) <= 0;
    }

    /**
     * The overlap. {@link Optional#empty()} <strong>only</strong> when
     * disconnected; abutting operands return a <em>present</em> cut-empty range
     * at the touch point (None = disjoint, present-empty = abut).
     */
    public Optional<Range<C>> intersection(Range<C> other)
    {
        Objects.requireNonNull(other, "other");
        if (!this.isConnected(other))
        {
            return Optional.empty();
        }
        Cut<C> newLower = maxCut(this.lower, other.lower);
        Cut<C> newUpper = minCut(this.upper, other.upper);
        return Optional.of(new Range<>(newLower, newUpper));
    }

    /** The smallest range enclosing both. No cross-shape canonicalisation. */
    public Range<C> span(Range<C> other)
    {
        Objects.requireNonNull(other, "other");
        Cut<C> newLower = minCut(this.lower, other.lower);
        Cut<C> newUpper = maxCut(this.upper, other.upper);
        return new Range<>(newLower, newUpper);
    }

    private static <C extends Comparable<? super C>> Cut<C> maxCut(Cut<C> a, Cut<C> b)
    {
        return a.compareTo(b) < 0 ? b : a;
    }

    private static <C extends Comparable<? super C>> Cut<C> minCut(Cut<C> a, Cut<C> b)
    {
        return a.compareTo(b) > 0 ? b : a;
    }

    // ---- equality / hash / toString ---------------------------------------

    /**
     * Structural equality on the two cuts. {@code closedOpen(v, v)} and
     * {@code openClosed(v, v)} are unequal (distinct empties); empties at
     * different positions are unequal.
     */
    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof Range<?>))
        {
            return false;
        }
        Range<?> other = (Range<?>) o;
        return this.lower.equals(other.lower) && this.upper.equals(other.upper);
    }

    /** A hash over {@code (lowerCut, upperCut)}, consistent with {@link #equals}. */
    @Override
    public int hashCode()
    {
        return Objects.hash(this.lower, this.upper);
    }

    @Override
    public String toString()
    {
        return this.lower.toLowerString() + ", " + this.upper.toUpperString();
    }

    /**
     * A cut sits <em>between</em> values (Guava's {@code Cut}). The four-variant
     * form carries two distinct unbounded sentinels ({@code BelowAll} =
     * {@code -inf}, {@code AboveAll} = {@code +inf}) so the cut has a single,
     * total, context-free order — there is no lone {@code Unbounded} value with an
     * ambiguous position.
     *
     * <p>Total order: {@code BelowAll < Below(v) < Above(v) < AboveAll}. Finite
     * cuts at different values order by value; at the same value
     * {@code Below(v) < Above(v)}.
     */
    private abstract static class Cut<C extends Comparable<? super C>> implements Comparable<Cut<C>>
    {
        static <C extends Comparable<? super C>> Cut<C> belowAll()
        {
            return new BelowAll<>();
        }

        static <C extends Comparable<? super C>> Cut<C> aboveAll()
        {
            return new AboveAll<>();
        }

        static <C extends Comparable<? super C>> Cut<C> below(C value)
        {
            return new Below<>(value);
        }

        static <C extends Comparable<? super C>> Cut<C> above(C value)
        {
            return new Above<>(value);
        }

        /** Rank band so the sentinels order around the finite cuts. */
        abstract int rank();

        /** The lower-side {@code contains} predicate for this cut. */
        abstract boolean lowerOk(C x);

        /** The upper-side {@code contains} predicate for this cut. */
        abstract boolean upperOk(C x);

        /** {@link BoundType} when this cut is a lower endpoint; {@code null} if unbounded. */
        abstract BoundType boundTypeAsLower();

        /** {@link BoundType} when this cut is an upper endpoint; {@code null} if unbounded. */
        abstract BoundType boundTypeAsUpper();

        /** The endpoint value, or {@code null} for the sentinels. */
        abstract C endpoint();

        /** Whether this cut carries a finite endpoint value. */
        abstract boolean isFinite();

        abstract String toLowerString();

        abstract String toUpperString();

        @Override
        public final int compareTo(Cut<C> other)
        {
            int byRank = Integer.compare(this.rank(), other.rank());
            if (byRank != 0 || !this.isFinite())
            {
                // At least one is a sentinel, or they sit in different rank bands.
                return byRank;
            }
            // Both finite (Below/Above). Order by value, then Below(v) < Above(v).
            int byValue = this.endpoint().compareTo(other.endpoint());
            if (byValue != 0)
            {
                return byValue;
            }
            return Integer.compare(this.tieBreak(), other.tieBreak());
        }

        /** 0 for {@code Below}, 1 for {@code Above}; only consulted for finite cuts. */
        int tieBreak()
        {
            return 0;
        }

        private static final class BelowAll<C extends Comparable<? super C>> extends Cut<C>
        {
            @Override
            int rank()
            {
                return 0;
            }

            @Override
            boolean lowerOk(C x)
            {
                return true;
            }

            @Override
            boolean upperOk(C x)
            {
                // BelowAll is never an upper cut.
                return false;
            }

            @Override
            BoundType boundTypeAsLower()
            {
                return null;
            }

            @Override
            BoundType boundTypeAsUpper()
            {
                return null;
            }

            @Override
            C endpoint()
            {
                return null;
            }

            @Override
            boolean isFinite()
            {
                return false;
            }

            @Override
            String toLowerString()
            {
                return "(-∞";
            }

            @Override
            String toUpperString()
            {
                return "-∞)";
            }

            @Override
            public boolean equals(Object o)
            {
                return o instanceof BelowAll;
            }

            @Override
            public int hashCode()
            {
                return 1;
            }
        }

        private static final class AboveAll<C extends Comparable<? super C>> extends Cut<C>
        {
            @Override
            int rank()
            {
                return 2;
            }

            @Override
            boolean lowerOk(C x)
            {
                // AboveAll is never a lower cut.
                return false;
            }

            @Override
            boolean upperOk(C x)
            {
                return true;
            }

            @Override
            BoundType boundTypeAsLower()
            {
                return null;
            }

            @Override
            BoundType boundTypeAsUpper()
            {
                return null;
            }

            @Override
            C endpoint()
            {
                return null;
            }

            @Override
            boolean isFinite()
            {
                return false;
            }

            @Override
            String toLowerString()
            {
                return "(+∞";
            }

            @Override
            String toUpperString()
            {
                return "+∞)";
            }

            @Override
            public boolean equals(Object o)
            {
                return o instanceof AboveAll;
            }

            @Override
            public int hashCode()
            {
                return 2;
            }
        }

        private static final class Below<C extends Comparable<? super C>> extends Cut<C>
        {
            private final C value;

            Below(C value)
            {
                this.value = value;
            }

            @Override
            int rank()
            {
                return 1;
            }

            @Override
            int tieBreak()
            {
                return 0;
            }

            @Override
            boolean lowerOk(C x)
            {
                // closed lower [v : v <= x.
                return this.value.compareTo(x) <= 0;
            }

            @Override
            boolean upperOk(C x)
            {
                // open upper v) : x < v.
                return x.compareTo(this.value) < 0;
            }

            @Override
            BoundType boundTypeAsLower()
            {
                return BoundType.CLOSED;
            }

            @Override
            BoundType boundTypeAsUpper()
            {
                return BoundType.OPEN;
            }

            @Override
            C endpoint()
            {
                return this.value;
            }

            @Override
            boolean isFinite()
            {
                return true;
            }

            @Override
            String toLowerString()
            {
                return "[" + this.value;
            }

            @Override
            String toUpperString()
            {
                return this.value + ")";
            }

            @Override
            public boolean equals(Object o)
            {
                return o instanceof Below && this.value.equals(((Below<?>) o).value);
            }

            @Override
            public int hashCode()
            {
                return 31 * 3 + this.value.hashCode();
            }
        }

        private static final class Above<C extends Comparable<? super C>> extends Cut<C>
        {
            private final C value;

            Above(C value)
            {
                this.value = value;
            }

            @Override
            int rank()
            {
                return 1;
            }

            @Override
            int tieBreak()
            {
                return 1;
            }

            @Override
            boolean lowerOk(C x)
            {
                // open lower (v : v < x.
                return this.value.compareTo(x) < 0;
            }

            @Override
            boolean upperOk(C x)
            {
                // closed upper v] : x <= v.
                return x.compareTo(this.value) <= 0;
            }

            @Override
            BoundType boundTypeAsLower()
            {
                return BoundType.OPEN;
            }

            @Override
            BoundType boundTypeAsUpper()
            {
                return BoundType.CLOSED;
            }

            @Override
            C endpoint()
            {
                return this.value;
            }

            @Override
            boolean isFinite()
            {
                return true;
            }

            @Override
            String toLowerString()
            {
                return "(" + this.value;
            }

            @Override
            String toUpperString()
            {
                return this.value + "]";
            }

            @Override
            public boolean equals(Object o)
            {
                return o instanceof Above && this.value.equals(((Above<?>) o).value);
            }

            @Override
            public int hashCode()
            {
                return 31 * 5 + this.value.hashCode();
            }
        }
    }
}

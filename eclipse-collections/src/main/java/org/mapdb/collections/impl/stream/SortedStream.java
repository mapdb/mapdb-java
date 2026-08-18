/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.list.mutable.FastList;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.range.RangeSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;

/**
 * Archeology-2 "A1" — the sorted-stream algebra.
 *
 * <p>A {@code SortedStream<T>} is a <b>lazy, single-use</b> cursor that yields
 * elements in ascending order under a fixed {@link #comparator() comparator}.
 * It is the one primitive that turns the family's N ordered structures into a
 * dataflow framework: k-way merge, set union / intersection / difference, and
 * <b>merge-join</b> compose over <i>any</i> ordered source that can expose a
 * cursor — an {@link ImmutableSortedMap} or {@link ImmutableSortedSet} (the
 * packed "sorted table" forms), a {@link RoaringU32}, a {@link RangeSet}, a JDK
 * {@link java.util.SortedMap}/{@link java.util.SortedSet}, or any hand-sorted
 * {@link Iterable}. Terminals drain the stream into a {@link Pump.Sink} (so
 * {@code merge(a, b) -> pump -> ImmutableSortedMap} is compaction) or into a
 * plain list.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Elements are emitted in <b>ascending</b> order under {@code comparator}
 *       ({@code comparator.compare(prev, cur) <= 0}). Adjacent equal elements
 *       (a "run") are permitted; individual operators document how they treat
 *       them.</li>
 *   <li>A stream is <b>single-use</b>: each intermediate operator or terminal
 *       consumes it. Reusing a consumed stream throws
 *       {@link IllegalStateException}.</li>
 *   <li>Binary operators ({@link #mergeWith}, {@link #union}, {@link
 *       #intersect}, {@link #difference}) require both operands to share the
 *       <b>same comparator semantics</b>; the receiver's comparator is used and
 *       the operand's is assumed consistent (comparators cannot be compared for
 *       equality, so this is a caller obligation).</li>
 * </ul>
 *
 * <p>Everything here is pure in-memory glue over the existing comparator
 * contract — no new data-structure risk, no serialization, no mmap. It is the
 * substrate the later archeology-2 tiers (A3 bulk mutation, B external merge,
 * B4 changesets) compose over.
 *
 * @param <T> the element type
 */
public final class SortedStream<T>
{
    /** The unsigned-{@code int} comparator used by {@link #ofRoaring}. */
    public static final Comparator<Integer> UNSIGNED_INT = Integer::compareUnsigned;

    private static final long SAMPLE_SALT = 0x9E3779B97F4A7C15L;

    /**
     * A one-element look-ahead cursor over ascending elements. Custom ordered
     * sources implement this to plug into the algebra.
     *
     * @param <T> the element type
     */
    public interface Cursor<T>
    {
        /** Whether {@link #peek()} / {@link #next()} would return an element. */
        boolean hasNext();

        /**
         * The next element without consuming it.
         *
         * @throws NoSuchElementException if exhausted
         */
        T peek();

        /**
         * The next element, consuming it.
         *
         * @throws NoSuchElementException if exhausted
         */
        T next();
    }

    private final Comparator<? super T> comparator;
    private final Cursor<T> cursor;
    private boolean consumed;

    private SortedStream(Comparator<? super T> comparator, Cursor<T> cursor)
    {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
    }

    /** The ascending order this stream and its operators use. */
    public Comparator<? super T> comparator()
    {
        return this.comparator;
    }

    private Cursor<T> take()
    {
        if (this.consumed)
        {
            throw new IllegalStateException("SortedStream already consumed");
        }
        this.consumed = true;
        return this.cursor;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * Wrap a source cursor known to yield ascending elements under
     * {@code comparator}. No validation is performed; the caller owns the
     * ordering claim.
     */
    public static <T> SortedStream<T> of(Cursor<T> cursor, Comparator<? super T> comparator)
    {
        return new SortedStream<>(comparator, cursor);
    }

    /**
     * Wrap an {@link Iterable} whose iteration order is ascending under
     * {@code comparator}. The order claim is <b>validated</b> lazily as elements
     * are pulled: a descending step throws {@link IllegalStateException}. Use
     * this for hand-prepared input; the structure adapters below skip validation
     * because their sources are ordered by construction.
     */
    public static <T> SortedStream<T> ofSorted(Iterable<? extends T> ascending, Comparator<? super T> comparator)
    {
        Iterator<? extends T> it = ascending.iterator();
        return new SortedStream<>(comparator, validating(cursorOf(it), comparator));
    }

    /** {@link #ofSorted(Iterable, Comparator)} with natural ordering. */
    public static <T extends Comparable<? super T>> SortedStream<T> ofSorted(Iterable<? extends T> ascending)
    {
        return ofSorted(ascending, Comparator.<T>naturalOrder());
    }

    /** Stream the elements of a packed sorted set in ascending order. */
    public static <T extends Comparable<? super T>> SortedStream<T> of(ImmutableSortedSet<T> set)
    {
        return new SortedStream<>(Comparator.<T>naturalOrder(), cursorOf(set.elements().iterator()));
    }

    /** Stream the keys of a packed sorted map in ascending order. */
    public static <K extends Comparable<? super K>, V> SortedStream<K> keysOf(ImmutableSortedMap<K, V> map)
    {
        return new SortedStream<>(Comparator.<K>naturalOrder(), cursorOf(map.keys().iterator()));
    }

    /** Stream the entries of a packed sorted map in ascending key order. */
    public static <K extends Comparable<? super K>, V> SortedStream<Map.Entry<K, V>> entriesOf(ImmutableSortedMap<K, V> map)
    {
        Comparator<Map.Entry<K, V>> byKey = Comparator.comparing(Map.Entry::getKey);
        return new SortedStream<>(byKey, cursorOf(map.entries().iterator()));
    }

    /**
     * Stream a {@link RoaringU32} in <b>unsigned</b> ascending order (boxed as
     * {@code Integer} bit reinterprets, matching {@link RoaringU32#toSortedArray}
     * so {@code -1} sorts last). The comparator is {@link Integer#compareUnsigned}.
     */
    public static SortedStream<Integer> ofRoaring(RoaringU32 bitmap)
    {
        int[] values = bitmap.toSortedArray();
        Cursor<Integer> c = new Cursor<Integer>()
        {
            private int i;

            @Override
            public boolean hasNext()
            {
                return this.i < values.length;
            }

            @Override
            public Integer peek()
            {
                if (this.i >= values.length)
                {
                    throw new NoSuchElementException();
                }
                return values[this.i];
            }

            @Override
            public Integer next()
            {
                if (this.i >= values.length)
                {
                    throw new NoSuchElementException();
                }
                return values[this.i++];
            }
        };
        return new SortedStream<>(UNSIGNED_INT, c);
    }

    /**
     * Stream the disjoint ranges of a {@link RangeSet} in ascending order of
     * lower endpoint (a range with no lower bound sorts first). Because a
     * {@code RangeSet}'s canonical ranges are disjoint and coalesced, this order
     * is total <i>within one set</i>.
     *
     * <p><b>Binary-op hazard:</b> the comparator orders by lower endpoint only,
     * so two ranges sharing a lower endpoint (e.g. {@code [1,5)} and
     * {@code [1,9)}) compare equal. Combining {@code ofRanges} streams from
     * <i>different</i> range sets via {@link #intersect}/{@link #difference}
     * therefore treats such ranges as the same key; only combine range streams
     * when that is the intended semantics.
     */
    public static <C extends Comparable<? super C>> SortedStream<Range<C>> ofRanges(RangeSet<C> set)
    {
        List<Range<C>> ranges = set.asRanges();
        return new SortedStream<>(byLowerEndpoint(), cursorOf(ranges.iterator()));
    }

    // ------------------------------------------------------------------
    // Intermediate operators (lazy; each consumes the receiver)
    // ------------------------------------------------------------------

    /**
     * Binary merge (chain calls for k-way) preserving <b>all</b> elements
     * including duplicates. On a tie the receiver's element is emitted first (a
     * stable merge). This is the multiset union; use {@link #union} for set
     * semantics.
     */
    public SortedStream<T> mergeWith(SortedStream<T> other)
    {
        Cursor<T> a = this.take();
        Cursor<T> b = other.take();
        Comparator<? super T> cmp = this.comparator;
        Cursor<T> merged = new AbstractCursor<T>()
        {
            @Override
            protected T computeNext()
            {
                boolean ha = a.hasNext();
                boolean hb = b.hasNext();
                if (!ha && !hb)
                {
                    return endOfData();
                }
                if (!hb)
                {
                    return a.next();
                }
                if (!ha)
                {
                    return b.next();
                }
                return cmp.compare(a.peek(), b.peek()) <= 0 ? a.next() : b.next();
            }
        };
        return new SortedStream<>(cmp, merged);
    }

    /**
     * Set union: {@link #mergeWith} followed by {@link #distinct}. Runs of
     * comparator-equal elements (within either operand or spanning both)
     * collapse to a single representative — the first encountered.
     */
    public SortedStream<T> union(SortedStream<T> other)
    {
        return this.mergeWith(other).distinct();
    }

    /**
     * Set intersection: elements whose key is present in both operands, emitted
     * once each. Duplicate runs on either side collapse. When comparator-equal
     * elements differ by identity, the emitted representative is taken from the
     * <b>receiver</b> (matching {@link #union}, whose stable merge keeps the
     * receiver's element on a tie).
     */
    public SortedStream<T> intersect(SortedStream<T> other)
    {
        Cursor<T> a = this.take();
        Cursor<T> b = other.take();
        Comparator<? super T> cmp = this.comparator;
        Cursor<T> out = new AbstractCursor<T>()
        {
            @Override
            protected T computeNext()
            {
                while (a.hasNext() && b.hasNext())
                {
                    int c = cmp.compare(a.peek(), b.peek());
                    if (c < 0)
                    {
                        a.next();
                    }
                    else if (c > 0)
                    {
                        b.next();
                    }
                    else
                    {
                        T match = a.next();
                        skipEqual(a, match, cmp);
                        skipEqual(b, match, cmp);
                        return match;
                    }
                }
                return endOfData();
            }
        };
        return new SortedStream<>(cmp, out);
    }

    /**
     * Set difference: elements whose key is in the receiver but not in
     * {@code other}, emitted once each. Duplicate runs on the receiver side
     * collapse.
     */
    public SortedStream<T> difference(SortedStream<T> other)
    {
        Cursor<T> a = this.take();
        Cursor<T> b = other.take();
        Comparator<? super T> cmp = this.comparator;
        Cursor<T> out = new AbstractCursor<T>()
        {
            @Override
            protected T computeNext()
            {
                while (a.hasNext())
                {
                    if (!b.hasNext())
                    {
                        T only = a.next();
                        skipEqual(a, only, cmp);
                        return only;
                    }
                    int c = cmp.compare(a.peek(), b.peek());
                    if (c < 0)
                    {
                        T only = a.next();
                        skipEqual(a, only, cmp);
                        return only;
                    }
                    else if (c > 0)
                    {
                        b.next();
                    }
                    else
                    {
                        T drop = a.next();
                        skipEqual(a, drop, cmp);
                        skipEqual(b, drop, cmp);
                    }
                }
                return endOfData();
            }
        };
        return new SortedStream<>(cmp, out);
    }

    /** Collapse each run of comparator-equal elements to its first element. */
    public SortedStream<T> distinct()
    {
        Cursor<T> src = this.take();
        Comparator<? super T> cmp = this.comparator;
        Cursor<T> out = new AbstractCursor<T>()
        {
            private boolean has;
            private T last;

            @Override
            protected T computeNext()
            {
                while (src.hasNext())
                {
                    T v = src.next();
                    if (this.has && cmp.compare(this.last, v) == 0)
                    {
                        continue;
                    }
                    this.has = true;
                    this.last = v;
                    return v;
                }
                return endOfData();
            }
        };
        return new SortedStream<>(cmp, out);
    }

    /** Keep only elements matching {@code predicate}; order is preserved. */
    public SortedStream<T> filter(Predicate<? super T> predicate)
    {
        Cursor<T> src = this.take();
        Comparator<? super T> cmp = this.comparator;
        Cursor<T> out = new AbstractCursor<T>()
        {
            @Override
            protected T computeNext()
            {
                while (src.hasNext())
                {
                    T v = src.next();
                    if (predicate.test(v))
                    {
                        return v;
                    }
                }
                return endOfData();
            }
        };
        return new SortedStream<>(cmp, out);
    }

    /**
     * Return a deterministic, order-preserving Bernoulli sample of an integer
     * stream. The decision is a pure function of value and seed, so equal
     * values are sampled consistently across runs and input orderings.
     */
    public static SortedStream<Integer> sampleIntegers(
            SortedStream<Integer> stream, double probability, long seed)
    {
        return stream.filter(integerSampleGate(probability, seed));
    }

    /** Build the deterministic predicate used by {@link #sampleIntegers}. */
    public static Predicate<Integer> integerSampleGate(double probability, long seed)
    {
        if (Double.isNaN(probability) || probability < 0.0 || probability > 1.0)
        {
            throw new IllegalArgumentException("sample probability must be in [0,1], got " + probability);
        }
        if (probability == 0.0)
        {
            return value -> false;
        }
        if (probability == 1.0)
        {
            return value -> true;
        }
        return value ->
        {
            long hash = Hash.hash64(Hash.hash64Int32(value, SAMPLE_SALT), seed);
            double uniform = (hash >>> 11) * 0x1.0p-53;
            return uniform < probability;
        };
    }

    // ------------------------------------------------------------------
    // Merge-join — the relational primitive
    // ------------------------------------------------------------------

    /**
     * Sort-merge inner equi-join over two key-ordered streams. For every key
     * present in both inputs, emits {@code combiner(l, r)} for the full
     * Cartesian product of the left group and right group sharing that key
     * (standard sort-merge-join duplicate handling). Output is ascending by join
     * key; the caller supplies {@code outputComparator} consistent with that key
     * order so the result is itself a well-formed {@link SortedStream} (within a
     * key group all outputs compare equal, so the ascending contract holds).
     *
     * <p>Both input streams must be ordered by their join key under
     * {@code keyComparator}.
     *
     * @param left            left input, ordered by {@code leftKey}
     * @param leftKey         join-key extractor for the left side
     * @param right           right input, ordered by {@code rightKey}
     * @param rightKey        join-key extractor for the right side
     * @param keyComparator   order shared by both inputs' join keys
     * @param combiner        builds an output row from a matched (l, r) pair
     * @param outputComparator ascending order of the output rows, consistent
     *                         with {@code keyComparator} on the join key
     */
    public static <L, R, K, O> SortedStream<O> mergeJoin(
            SortedStream<L> left, Function<? super L, ? extends K> leftKey,
            SortedStream<R> right, Function<? super R, ? extends K> rightKey,
            Comparator<? super K> keyComparator,
            BiFunction<? super L, ? super R, ? extends O> combiner,
            Comparator<? super O> outputComparator)
    {
        Cursor<L> a = left.take();
        Cursor<R> b = right.take();
        Cursor<O> out = new AbstractCursor<O>()
        {
            private final List<L> leftGroup = new ArrayList<>();
            private final List<R> rightGroup = new ArrayList<>();
            private int li;
            private int ri;

            /** Emit one row of the buffered Cartesian product, advancing (li, ri). */
            private O emitNextProductRow()
            {
                O row = combiner.apply(this.leftGroup.get(this.li), this.rightGroup.get(this.ri));
                this.ri++;
                if (this.ri == this.rightGroup.size())
                {
                    this.ri = 0;
                    this.li++;
                }
                return row;
            }

            @Override
            protected O computeNext()
            {
                // Drain the current Cartesian product, right-inner then left-outer.
                if (this.li < this.leftGroup.size())
                {
                    return this.emitNextProductRow();
                }
                // Advance to the next matching key and buffer both groups.
                while (a.hasNext() && b.hasNext())
                {
                    int c = keyComparator.compare(leftKey.apply(a.peek()), rightKey.apply(b.peek()));
                    if (c < 0)
                    {
                        a.next();
                    }
                    else if (c > 0)
                    {
                        b.next();
                    }
                    else
                    {
                        K key = leftKey.apply(a.peek());
                        this.leftGroup.clear();
                        this.rightGroup.clear();
                        while (a.hasNext() && keyComparator.compare(leftKey.apply(a.peek()), key) == 0)
                        {
                            this.leftGroup.add(a.next());
                        }
                        while (b.hasNext() && keyComparator.compare(rightKey.apply(b.peek()), key) == 0)
                        {
                            this.rightGroup.add(b.next());
                        }
                        this.li = 0;
                        this.ri = 0;
                        return this.emitNextProductRow();
                    }
                }
                return endOfData();
            }
        };
        return new SortedStream<>(outputComparator, out);
    }

    // ------------------------------------------------------------------
    // Terminals (each consumes the stream)
    // ------------------------------------------------------------------

    /**
     * Feed every element, in order, into a {@link Pump.Sink} and return the
     * built collection. {@code merge(a, b).into(Pump.treeSortedSet(...))} is a
     * one-liner compaction.
     */
    public <R> R into(Pump.Sink<? super T, R> sink)
    {
        Cursor<T> src = this.take();
        while (src.hasNext())
        {
            sink.put(src.next());
        }
        return sink.create();
    }

    /** Collect the remaining elements into a fresh {@link MutableList}. */
    public MutableList<T> toList()
    {
        Cursor<T> src = this.take();
        MutableList<T> list = FastList.newList();
        while (src.hasNext())
        {
            list.add(src.next());
        }
        return list;
    }

    /** Count the remaining elements. */
    public long count()
    {
        Cursor<T> src = this.take();
        long n = 0;
        while (src.hasNext())
        {
            src.next();
            n++;
        }
        return n;
    }

    /** Apply {@code action} to each remaining element in order. */
    public void forEach(Consumer<? super T> action)
    {
        Cursor<T> src = this.take();
        while (src.hasNext())
        {
            action.accept(src.next());
        }
    }

    /** A one-shot {@link Iterator} view of the remaining elements. */
    public Iterator<T> iterator()
    {
        Cursor<T> src = this.take();
        return new Iterator<T>()
        {
            @Override
            public boolean hasNext()
            {
                return src.hasNext();
            }

            @Override
            public T next()
            {
                return src.next();
            }
        };
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static <T> void skipEqual(Cursor<T> c, T pivot, Comparator<? super T> cmp)
    {
        while (c.hasNext() && cmp.compare(c.peek(), pivot) == 0)
        {
            c.next();
        }
    }

    private static <T> Cursor<T> cursorOf(Iterator<? extends T> it)
    {
        return new BufferedCursor<>(it);
    }

    private static <T> Cursor<T> validating(Cursor<T> src, Comparator<? super T> cmp)
    {
        return new Cursor<T>()
        {
            private boolean has;
            private T last;

            private void check(T v)
            {
                if (this.has && cmp.compare(this.last, v) > 0)
                {
                    throw new IllegalStateException(
                            "source not ascending: " + this.last + " > " + v);
                }
                this.has = true;
                this.last = v;
            }

            @Override
            public boolean hasNext()
            {
                return src.hasNext();
            }

            @Override
            public T peek()
            {
                T v = src.peek();
                this.check(v);
                return v;
            }

            @Override
            public T next()
            {
                T v = src.next();
                this.check(v);
                return v;
            }
        };
    }

    private static <C extends Comparable<? super C>> Comparator<Range<C>> byLowerEndpoint()
    {
        return (x, y) ->
        {
            boolean xl = x.hasLowerBound();
            boolean yl = y.hasLowerBound();
            if (!xl || !yl)
            {
                return xl == yl ? 0 : (xl ? 1 : -1);
            }
            return x.lowerEndpoint().compareTo(y.lowerEndpoint());
        };
    }

    /** One-element look-ahead over a plain {@link Iterator}. */
    private static final class BufferedCursor<T> implements Cursor<T>
    {
        private final Iterator<? extends T> it;
        private boolean buffered;
        private T head;

        BufferedCursor(Iterator<? extends T> it)
        {
            this.it = it;
        }

        @Override
        public boolean hasNext()
        {
            return this.buffered || this.it.hasNext();
        }

        @Override
        public T peek()
        {
            if (!this.buffered)
            {
                this.head = this.it.next();
                this.buffered = true;
            }
            return this.head;
        }

        @Override
        public T next()
        {
            if (this.buffered)
            {
                this.buffered = false;
                T v = this.head;
                this.head = null;
                return v;
            }
            return this.it.next();
        }
    }

    /**
     * Guava-style compute-next cursor: subclasses implement {@link #computeNext}
     * and call {@link #endOfData} to signal exhaustion.
     */
    private abstract static class AbstractCursor<T> implements Cursor<T>
    {
        private enum State
        {
            READY, NOT_READY, DONE
        }

        private State state = State.NOT_READY;
        private T next;

        protected abstract T computeNext();

        protected final T endOfData()
        {
            this.state = State.DONE;
            return null;
        }

        @Override
        public final boolean hasNext()
        {
            switch (this.state)
            {
                case DONE:
                    return false;
                case READY:
                    return true;
                default:
                    return this.tryCompute();
            }
        }

        private boolean tryCompute()
        {
            T candidate;
            try
            {
                candidate = this.computeNext();
            }
            catch (RuntimeException e)
            {
                // Poison the cursor: a source/validation failure must not be
                // resumable over half-advanced operand cursors (mirrors the
                // Pump.Sink poisoning discipline).
                this.state = State.DONE;
                throw e;
            }
            if (this.state == State.DONE)
            {
                return false;
            }
            this.next = candidate;
            this.state = State.READY;
            return true;
        }

        @Override
        public final T peek()
        {
            if (!this.hasNext())
            {
                throw new NoSuchElementException();
            }
            return this.next;
        }

        @Override
        public final T next()
        {
            if (!this.hasNext())
            {
                throw new NoSuchElementException();
            }
            this.state = State.NOT_READY;
            T v = this.next;
            this.next = null;
            return v;
        }
    }
}

/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;

import org.mapdb.collections.api.bag.sorted.MutableSortedBag;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.multimap.list.MutableListMultimap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.bag.sorted.mutable.TreeBag;
import org.mapdb.collections.impl.list.mutable.FastList;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.multimap.list.FastListMultimap;
import org.mapdb.collections.impl.multimap.set.UnifiedSetMultimap;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.tuple.Tuples;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

/**
 * Data pump (bulk import): single-pass, insert-only construction of a fresh
 * collection from prepared input. Implements the mapdb spec data-pump feature
 * (see {@code spec/features/data-pump.md}) for the object sorted-tree and
 * multimap families. The primitive-hash families get generated
 * {@code bulkLoad}/{@code bulkLoadExact} factories on the collection classes
 * themselves; this hand-written class covers the object tree and multimap
 * builders plus the shared streaming {@link Sink}, duplicate policy, and error
 * model.
 *
 * <p><b>Tree carve-out.</b> mapdb-java has no primitive tree types; the object
 * trees ({@link TreeSortedMap}/{@link TreeSortedSet}/{@link TreeBag}) wrap
 * {@code java.util.TreeMap}/{@code TreeSet}, whose {@code TreeMap(SortedMap)} /
 * {@code TreeSet(SortedSet)} constructors already perform the bottom-up
 * perfectly-balanced O(n) red-black build (the private JDK
 * {@code buildFromSorted}). We do NOT reimplement that; the pump validates the
 * input's sortedness with the collection's own comparator (the IEEE-754
 * totalOrder {@link FloatTotalOrder} for float keys), then hands a sorted view
 * to the JDK constructor. This delegation is the documented Java carve-out.
 *
 * <p><b>Multimap.</b> The grouped builders consume input pre-sorted by key,
 * group contiguous equal-key runs, and build one value collection per key sized
 * once -- genuinely cheaper than an n&times;{@code put} loop.
 */
public final class Pump
{
    private Pump()
    {
    }

    /**
     * Duplicate policy for the pump (shared with the generated primitive-hash
     * {@code bulkLoad} factories). {@code ERROR} rejects a duplicate key/element;
     * {@code IGNORE} keeps the first and skips the rest. Bags are exempt --
     * duplicates increment the count and never error.
     */
    public enum DuplicatePolicy
    {
        ERROR,
        IGNORE
    }

    /**
     * A prepared change for {@link #applySorted}: either an upsert or a delete.
     * Keeping this value in {@code Pump} makes mutation of an existing packed
     * map another sorted-input pump mode rather than a parallel bulk API.
     */
    public static final class Change<K, V>
    {
        private final K key;
        private final V value;
        private final boolean delete;

        private Change(K key, V value, boolean delete)
        {
            this.key = Objects.requireNonNull(key, "key");
            this.value = value;
            this.delete = delete;
        }

        public static <K, V> Change<K, V> upsert(K key, V value)
        {
            return new Change<>(key, Objects.requireNonNull(value, "value"), false);
        }

        public static <K, V> Change<K, V> delete(K key)
        {
            return new Change<>(key, null, true);
        }

        public K key()
        {
            return this.key;
        }

        public boolean isDelete()
        {
            return this.delete;
        }

        public V value()
        {
            if (this.delete)
            {
                throw new IllegalStateException("delete change has no value");
            }
            return this.value;
        }

        @Override
        public String toString()
        {
            return this.delete ? ("delete(" + this.key + ")") : ("upsert(" + this.key + "=" + this.value + ")");
        }
    }

    /**
     * Apply strictly key-ascending changes to an immutable packed map in one
     * O(n + m) merge pass.
     */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> applySorted(
            ImmutableSortedMap<K, V> base, List<Change<K, V>> changes)
    {
        requireStrictlyAscendingChanges(changes);
        List<Map.Entry<K, V>> entries = base.entries();
        List<K> keys = new ArrayList<>(entries.size() + changes.size());
        List<V> values = new ArrayList<>(entries.size() + changes.size());
        int i = 0;
        int j = 0;
        while (i < entries.size() && j < changes.size())
        {
            Map.Entry<K, V> entry = entries.get(i);
            Change<K, V> change = changes.get(j);
            int comparison = entry.getKey().compareTo(change.key());
            if (comparison < 0)
            {
                emit(keys, values, entry.getKey(), entry.getValue());
                i++;
            }
            else if (comparison > 0)
            {
                if (!change.isDelete())
                {
                    emit(keys, values, change.key(), change.value());
                }
                j++;
            }
            else
            {
                if (!change.isDelete())
                {
                    emit(keys, values, change.key(), change.value());
                }
                i++;
                j++;
            }
        }
        while (i < entries.size())
        {
            Map.Entry<K, V> entry = entries.get(i++);
            emit(keys, values, entry.getKey(), entry.getValue());
        }
        while (j < changes.size())
        {
            Change<K, V> change = changes.get(j++);
            if (!change.isDelete())
            {
                emit(keys, values, change.key(), change.value());
            }
        }
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    /** Merge two key-disjoint packed maps in one O(n + m) pass. */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> mergeSortedDisjoint(
            ImmutableSortedMap<K, V> base, ImmutableSortedMap<K, V> addition)
    {
        List<Map.Entry<K, V>> left = base.entries();
        List<Map.Entry<K, V>> right = addition.entries();
        List<K> keys = new ArrayList<>(left.size() + right.size());
        List<V> values = new ArrayList<>(left.size() + right.size());
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size())
        {
            Map.Entry<K, V> a = left.get(i);
            Map.Entry<K, V> b = right.get(j);
            int comparison = a.getKey().compareTo(b.getKey());
            if (comparison < 0)
            {
                emit(keys, values, a.getKey(), a.getValue());
                i++;
            }
            else if (comparison > 0)
            {
                emit(keys, values, b.getKey(), b.getValue());
                j++;
            }
            else
            {
                throw new IllegalArgumentException(
                        "mergeSortedDisjoint: maps are not disjoint (shared key " + a.getKey() + ")");
            }
        }
        while (i < left.size())
        {
            Map.Entry<K, V> entry = left.get(i++);
            emit(keys, values, entry.getKey(), entry.getValue());
        }
        while (j < right.size())
        {
            Map.Entry<K, V> entry = right.get(j++);
            emit(keys, values, entry.getKey(), entry.getValue());
        }
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    /** Delete all packed-map entries in {@code range} during one rebuild pass. */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> rangeDelete(
            ImmutableSortedMap<K, V> base, Range<K> range)
    {
        List<K> keys = new ArrayList<>(base.size());
        List<V> values = new ArrayList<>(base.size());
        for (Map.Entry<K, V> entry : base.entries())
        {
            if (!range.contains(entry.getKey()))
            {
                emit(keys, values, entry.getKey(), entry.getValue());
            }
        }
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    private static <K, V> void emit(List<K> keys, List<V> values, K key, V value)
    {
        keys.add(key);
        values.add(value);
    }

    private static <K extends Comparable<? super K>, V> void requireStrictlyAscendingChanges(
            List<Change<K, V>> changes)
    {
        Objects.requireNonNull(changes, "changes");
        for (int i = 0; i < changes.size(); i++)
        {
            Objects.requireNonNull(changes.get(i), "change element");
            if (i > 0 && changes.get(i - 1).key().compareTo(changes.get(i).key()) >= 0)
            {
                throw new IllegalArgumentException(
                        "changes must be strictly ascending by key (no duplicate or out-of-order keys): "
                                + changes.get(i - 1).key() + " then " + changes.get(i).key());
            }
        }
    }

    /**
     * Thrown (unchecked) when a sorted pump receives input that is not ascending
     * under the collection's comparator. Mirrors the on-disk MapDB
     * {@code DBException.PumpSourceNotSorted}.
     */
    public static class PumpSourceNotSorted extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        public PumpSourceNotSorted()
        {
            super("Pump source is not sorted in ascending order");
        }

        public PumpSourceNotSorted(Object key)
        {
            super("Pump source is not sorted in ascending order at key: " + key);
        }
    }

    /**
     * Thrown (unchecked) when a pump with {@link DuplicatePolicy#ERROR} receives
     * a duplicate key/element. Mirrors the on-disk MapDB
     * {@code DBException.PumpSourceDuplicate}.
     */
    public static class PumpSourceDuplicate extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        public PumpSourceDuplicate()
        {
            super("Pump source contains a duplicate key");
        }

        public PumpSourceDuplicate(Object key)
        {
            super("Pump source contains a duplicate key: " + key);
        }
    }

    /**
     * Thrown (unchecked) when a bag pump would overflow a 32-bit count -- either a
     * single element's occurrence count or the bag's total {@code size} crosses
     * {@code Integer.MAX_VALUE}. The data-pump spec requires bag run counts to be
     * overflow-checked (bulk construction is exactly where huge runs are expected),
     * so the pump fails loudly instead of silently wrapping into a negative count.
     */
    public static class PumpSourceOverflow extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        public PumpSourceOverflow()
        {
            super("Pump bag count or size overflows Integer.MAX_VALUE");
        }

        public PumpSourceOverflow(Object key)
        {
            super("Pump bag count or size overflows Integer.MAX_VALUE at element: " + key);
        }
    }

    /**
     * Streaming bulk-import sink (mirrors the MapDB 3.x {@code Pump.Sink<E,R>}).
     * Append prepared elements with {@link #put}/{@link #putAll}, then call
     * {@link #create} once to obtain the built collection. A sink builds exactly
     * one fresh collection: after an error it is poisoned ({@code put}/
     * {@code create} fail) and {@code create} is once-only.
     *
     * @param <E> the element type accepted by {@code put}
     * @param <R> the built result type returned by {@code create}
     */
    public abstract static class Sink<E, R>
    {
        private boolean poisoned;
        private boolean created;

        /** Append one prepared element. */
        public final void put(E e)
        {
            this.checkUsable();
            try
            {
                this.doPut(e);
            }
            catch (RuntimeException ex)
            {
                this.poisoned = true;
                throw ex;
            }
        }

        /** Append all elements from the iterable. */
        public final void putAll(Iterable<? extends E> it)
        {
            this.putAll(it.iterator());
        }

        /** Append all elements from the iterator. */
        public final void putAll(Iterator<? extends E> it)
        {
            while (it.hasNext())
            {
                this.put(it.next());
            }
        }

        /** Finish the build and return the fresh collection. Once-only. */
        public final R create()
        {
            this.checkUsable();
            this.created = true;
            try
            {
                return this.doCreate();
            }
            catch (RuntimeException ex)
            {
                this.poisoned = true;
                throw ex;
            }
        }

        private void checkUsable()
        {
            if (this.poisoned)
            {
                throw new IllegalStateException("Sink is poisoned after an earlier error");
            }
            if (this.created)
            {
                throw new IllegalStateException("Sink.create() may be called only once");
            }
        }

        protected abstract void doPut(E e);

        protected abstract R doCreate();
    }

    // ------------------------------------------------------------------
    // Sorted (tree) builders -- delegate the O(n) build to the JDK.
    // ------------------------------------------------------------------

    /**
     * Streaming builder for a {@link MutableSortedMap}. Validates that keys arrive
     * strictly ascending under {@code comparator} (the collection's own
     * comparator), applies {@code policy} to equal keys, and on {@link
     * Sink#create()} feeds the validated sorted entries to the JDK
     * {@code TreeMap(SortedMap)} bulk constructor (O(n) {@code buildFromSorted}).
     *
     * @param comparator key order; {@code null} means natural ordering
     */
    public static <K, V> Sink<Pair<K, V>, MutableSortedMap<K, V>> treeSortedMap(
            Comparator<? super K> comparator, DuplicatePolicy policy)
    {
        return new Sink<Pair<K, V>, MutableSortedMap<K, V>>()
        {
            private final List<K> keys = new ArrayList<>();
            private final List<V> values = new ArrayList<>();

            @Override
            protected void doPut(Pair<K, V> e)
            {
                K key = e.getOne();
                if (!this.keys.isEmpty())
                {
                    int cmp = compare(comparator, this.keys.get(this.keys.size() - 1), key);
                    if (cmp > 0)
                    {
                        throw new PumpSourceNotSorted(key);
                    }
                    if (cmp == 0)
                    {
                        if (policy == DuplicatePolicy.ERROR)
                        {
                            throw new PumpSourceDuplicate(key);
                        }
                        return; // IGNORE: first wins
                    }
                }
                this.keys.add(key);
                this.values.add(e.getTwo());
            }

            @Override
            protected MutableSortedMap<K, V> doCreate()
            {
                SortedMap<K, V> sorted = new SortedEntriesMap<>(comparator, this.keys, this.values);
                return new TreeSortedMap<>(sorted);
            }
        };
    }

    /**
     * Streaming builder for a {@link MutableSortedSet}. Validates strictly
     * ascending input, applies {@code policy}, and delegates to the JDK
     * {@code TreeSet(SortedSet)} bulk constructor on {@link Sink#create()}.
     */
    public static <T> Sink<T, MutableSortedSet<T>> treeSortedSet(
            Comparator<? super T> comparator, DuplicatePolicy policy)
    {
        return new Sink<T, MutableSortedSet<T>>()
        {
            private final List<T> elements = new ArrayList<>();

            @Override
            protected void doPut(T e)
            {
                if (!this.elements.isEmpty())
                {
                    int cmp = compare(comparator, this.elements.get(this.elements.size() - 1), e);
                    if (cmp > 0)
                    {
                        throw new PumpSourceNotSorted(e);
                    }
                    if (cmp == 0)
                    {
                        if (policy == DuplicatePolicy.ERROR)
                        {
                            throw new PumpSourceDuplicate(e);
                        }
                        return;
                    }
                }
                this.elements.add(e);
            }

            @Override
            protected MutableSortedSet<T> doCreate()
            {
                SortedSet<T> sorted = new SortedElementsSet<>(comparator, this.elements);
                return new TreeSortedSet<>(sorted);
            }
        };
    }

    /**
     * Streaming builder for a {@link MutableSortedBag}. Bags are duplicate-exempt:
     * a run of equal keys becomes one element with {@code count = run length}, so
     * there is no {@link DuplicatePolicy}. Input must still be ascending (equal
     * keys are allowed as the counting case); strictly-decreasing input throws
     * {@link PumpSourceNotSorted}.
     */
    public static <T> Sink<T, MutableSortedBag<T>> treeBag(Comparator<? super T> comparator)
    {
        return new Sink<T, MutableSortedBag<T>>()
        {
            // Parallel sorted runs: distinct elements ascending + their counts.
            private final List<T> elements = new ArrayList<>();
            private final List<Counter> counts = new ArrayList<>();
            // Running total bag size, overflow-checked against Integer.MAX_VALUE.
            private long totalSize;

            @Override
            protected void doPut(T e)
            {
                if (!this.elements.isEmpty())
                {
                    int cmp = compare(comparator, this.elements.get(this.elements.size() - 1), e);
                    if (cmp > 0)
                    {
                        throw new PumpSourceNotSorted(e);
                    }
                    if (cmp == 0)
                    {
                        // Continue the current run: count the duplicate, checked.
                        Counter counter = this.counts.get(this.counts.size() - 1);
                        if (counter.getCount() == Integer.MAX_VALUE)
                        {
                            throw new PumpSourceOverflow(e);
                        }
                        counter.increment();
                        this.bumpTotal(e);
                        return;
                    }
                }
                // Start a new run for a strictly-greater element.
                this.elements.add(e);
                this.counts.add(new Counter(1));
                this.bumpTotal(e);
            }

            private void bumpTotal(T e)
            {
                this.totalSize++;
                if (this.totalSize > Integer.MAX_VALUE)
                {
                    throw new PumpSourceOverflow(e);
                }
            }

            @Override
            protected MutableSortedBag<T> doCreate()
            {
                // Build the underlying TreeSortedMap (element -> Counter) in one
                // O(n) pass via the JDK TreeMap(SortedMap) bulk constructor, then
                // wrap it as a TreeBag -- no per-element add()/rebalance.
                SortedMap<T, Counter> sorted = new SortedEntriesMap<>(comparator, this.elements, this.counts);
                MutableSortedMap<T, Counter> map = new TreeSortedMap<>(sorted);
                try
                {
                    return TreeBag.fromSortedCounts(map);
                }
                catch (ArithmeticException ex)
                {
                    throw new PumpSourceOverflow();
                }
            }
        };
    }

    /**
     * One-shot: build a {@link MutableSortedMap} from an already-sorted
     * {@code SortedMap}, delegating straight to the JDK {@code TreeMap(SortedMap)}
     * O(n) bulk constructor. The result orders by the source's comparator.
     */
    public static <K, V> MutableSortedMap<K, V> treeSortedMapFromSorted(SortedMap<K, ? extends V> presorted)
    {
        return new TreeSortedMap<>(presorted);
    }

    /**
     * One-shot: build a {@link MutableSortedMap} from input sorted ascending under
     * {@code comparator}. If {@code input} is already a {@code SortedMap} with an
     * equivalent comparator, the JDK {@code TreeMap(SortedMap)} fast path is used
     * directly; otherwise the entries are validated and built via the streaming
     * sink.
     */
    public static <K, V> MutableSortedMap<K, V> treeSortedMapFromSorted(
            Comparator<? super K> comparator, Iterable<Pair<K, V>> input, DuplicatePolicy policy)
    {
        if (input instanceof Map)
        {
            // not a Pair iterable in practice; guarded for clarity only
            throw new IllegalArgumentException("use treeSortedMapFromSorted(SortedMap) for map input");
        }
        Sink<Pair<K, V>, MutableSortedMap<K, V>> sink = treeSortedMap(comparator, policy);
        sink.putAll(input);
        return sink.create();
    }

    /**
     * Fast-path one-shot: if {@code sortedMap}'s comparator is equivalent to
     * {@code expectedComparator}, delegate straight to the JDK bulk constructor;
     * otherwise re-validate and rebuild under {@code expectedComparator}. Used to
     * be honest about the O(n) claim: it only holds when the source order already
     * matches the target order.
     */
    public static <K, V> MutableSortedMap<K, V> treeSortedMapFromSorted(
            Comparator<? super K> expectedComparator, SortedMap<K, ? extends V> sortedMap, DuplicatePolicy policy)
    {
        if (comparatorsEquivalent(expectedComparator, sortedMap.comparator()))
        {
            return new TreeSortedMap<>(sortedMap); // O(n) JDK buildFromSorted
        }
        Sink<Pair<K, V>, MutableSortedMap<K, V>> sink = treeSortedMap(expectedComparator, policy);
        for (Map.Entry<K, ? extends V> e : sortedMap.entrySet())
        {
            sink.put(Tuples.pair(e.getKey(), e.getValue()));
        }
        return sink.create();
    }

    /**
     * One-shot: build a {@link MutableSortedSet} from an already-sorted
     * {@code SortedSet}, delegating to the JDK {@code TreeSet(SortedSet)} O(n)
     * bulk constructor.
     */
    public static <T> MutableSortedSet<T> treeSortedSetFromSorted(SortedSet<T> presorted)
    {
        return new TreeSortedSet<>(presorted);
    }

    /**
     * One-shot: build a {@link MutableSortedSet} from input sorted ascending under
     * {@code comparator}, validating order/duplicates via the streaming sink.
     */
    public static <T> MutableSortedSet<T> treeSortedSetFromSorted(
            Comparator<? super T> comparator, Iterable<T> input, DuplicatePolicy policy)
    {
        Sink<T, MutableSortedSet<T>> sink = treeSortedSet(comparator, policy);
        sink.putAll(input);
        return sink.create();
    }

    // ------------------------------------------------------------------
    // Float-keyed tree builders -- wire FloatTotalOrder.
    // ------------------------------------------------------------------

    /** Streaming sorted-map builder for {@link Float} keys, ordered by IEEE-754 totalOrder. */
    public static <V> Sink<Pair<Float, V>, MutableSortedMap<Float, V>> floatTreeSortedMap(DuplicatePolicy policy)
    {
        return treeSortedMap(FloatTotalOrder.FLOAT_COMPARATOR, policy);
    }

    /** Streaming sorted-map builder for {@link Double} keys, ordered by IEEE-754 totalOrder. */
    public static <V> Sink<Pair<Double, V>, MutableSortedMap<Double, V>> doubleTreeSortedMap(DuplicatePolicy policy)
    {
        return treeSortedMap(FloatTotalOrder.DOUBLE_COMPARATOR, policy);
    }

    /** Streaming sorted-set builder for {@link Float} keys, ordered by IEEE-754 totalOrder. */
    public static Sink<Float, MutableSortedSet<Float>> floatTreeSortedSet(DuplicatePolicy policy)
    {
        return treeSortedSet(FloatTotalOrder.FLOAT_COMPARATOR, policy);
    }

    /** Streaming sorted-set builder for {@link Double} keys, ordered by IEEE-754 totalOrder. */
    public static Sink<Double, MutableSortedSet<Double>> doubleTreeSortedSet(DuplicatePolicy policy)
    {
        return treeSortedSet(FloatTotalOrder.DOUBLE_COMPARATOR, policy);
    }

    // ------------------------------------------------------------------
    // Multimap grouped builders -- one value collection per key, sized once.
    // ------------------------------------------------------------------

    /**
     * Build a {@link MutableListMultimap} from {@code (key, value)} pairs that are
     * already grouped by key (ascending under {@code keyComparator}; equal keys
     * are the grouping case). Each contiguous equal-key run becomes one value
     * list, sized to the run length and filled once -- cheaper than n&times;put.
     * Value order within each key is preserved. Out-of-order keys throw
     * {@link PumpSourceNotSorted}.
     */
    public static <K, V> MutableListMultimap<K, V> listMultimapFromSortedRuns(
            Comparator<? super K> keyComparator, Iterable<Pair<K, V>> sortedByKey)
    {
        FastListMultimap<K, V> result = new FastListMultimap<>();
        runByKey(keyComparator, sortedByKey, (key, run) -> result.putAll(key, run));
        return result;
    }

    /**
     * Build a {@link MutableSetMultimap} from {@code (key, value)} pairs grouped by
     * key. Each key's values are deduped (set semantics). Out-of-order keys throw
     * {@link PumpSourceNotSorted}.
     */
    public static <K, V> MutableSetMultimap<K, V> setMultimapFromSortedRuns(
            Comparator<? super K> keyComparator, Iterable<Pair<K, V>> sortedByKey)
    {
        UnifiedSetMultimap<K, V> result = new UnifiedSetMultimap<>();
        runByKey(keyComparator, sortedByKey, (key, run) ->
        {
            MutableSet<V> values = UnifiedSet.newSet(run.size());
            values.addAllIterable(run);
            result.putAll(key, values);
        });
        return result;
    }

    private interface RunConsumer<K, V>
    {
        void accept(K key, MutableList<V> run);
    }

    private static <K, V> void runByKey(
            Comparator<? super K> keyComparator, Iterable<Pair<K, V>> sortedByKey, RunConsumer<K, V> consumer)
    {
        boolean has = false;
        K runKey = null;
        MutableList<V> run = null;
        for (Pair<K, V> pair : sortedByKey)
        {
            K key = pair.getOne();
            if (has && compare(keyComparator, runKey, key) == 0)
            {
                requireComparatorConsistentWithEquals(runKey, key, "key comparator");
                run.add(pair.getTwo());
                continue;
            }
            if (has)
            {
                if (compare(keyComparator, runKey, key) > 0)
                {
                    throw new PumpSourceNotSorted(key);
                }
                consumer.accept(runKey, run);
            }
            runKey = key;
            run = FastList.newList();
            run.add(pair.getTwo());
            has = true;
        }
        if (has)
        {
            consumer.accept(runKey, run);
        }
    }

    // ------------------------------------------------------------------
    // Multimap source contracts -- explicit per the data-pump spec:
    //   fromSortedKeys      : grouped by key (equal keys contiguous), value
    //                         order within a key NOT validated.
    //   fromSortedKeyValues : sorted by key THEN value; value order within a
    //                         key is validated, and SetMultimap dedupes values.
    //   bulkLoad            : unsorted; hash/group accumulation (no sort claim).
    // A streaming Sink mirrors each sorted variant.
    // ------------------------------------------------------------------

    /**
     * Streaming list-multimap builder over input <b>grouped by key</b> (equal keys
     * contiguous, ascending under {@code keyComparator}). Value order within a key
     * is preserved but not validated. Out-of-order keys throw
     * {@link PumpSourceNotSorted}.
     */
    public static <K, V> Sink<Pair<K, V>, MutableListMultimap<K, V>> listMultimapSink(
            Comparator<? super K> keyComparator)
    {
        FastListMultimap<K, V> result = new FastListMultimap<>();
        return new MultimapRunSink<>(keyComparator, null, false,
                (key, run) -> result.putAll(key, run), () -> result);
    }

    /**
     * Streaming set-multimap builder over input grouped by key. Each key's values
     * are deduped (set semantics). Value order within a key is not validated.
     */
    public static <K, V> Sink<Pair<K, V>, MutableSetMultimap<K, V>> setMultimapSink(
            Comparator<? super K> keyComparator)
    {
        UnifiedSetMultimap<K, V> result = new UnifiedSetMultimap<>();
        return new MultimapRunSink<>(keyComparator, null, false,
                (key, run) ->
                {
                    MutableSet<V> values = UnifiedSet.newSet(run.size());
                    values.addAllIterable(run);
                    result.putAll(key, values);
                },
                () -> result);
    }

    /**
     * Streaming list-multimap builder over input <b>sorted by key then value</b>.
     * Value order within each key is validated against {@code valueComparator}
     * (descending values throw {@link PumpSourceNotSorted}); equal values are kept
     * (list semantics).
     */
    public static <K, V> Sink<Pair<K, V>, MutableListMultimap<K, V>> listMultimapKeyValueSink(
            Comparator<? super K> keyComparator, Comparator<? super V> valueComparator)
    {
        FastListMultimap<K, V> result = new FastListMultimap<>();
        return new MultimapRunSink<>(keyComparator, valueComparator, false,
                (key, run) -> result.putAll(key, run), () -> result);
    }

    /**
     * Streaming set-multimap builder over input sorted by key then value. Value
     * order within each key is validated against {@code valueComparator}; equal
     * values are deduped (set semantics).
     */
    public static <K, V> Sink<Pair<K, V>, MutableSetMultimap<K, V>> setMultimapKeyValueSink(
            Comparator<? super K> keyComparator, Comparator<? super V> valueComparator)
    {
        UnifiedSetMultimap<K, V> result = new UnifiedSetMultimap<>();
        return new MultimapRunSink<>(keyComparator, valueComparator, true,
                (key, run) ->
                {
                    MutableSet<V> values = UnifiedSet.newSet(run.size());
                    values.addAllIterable(run);
                    result.putAll(key, values);
                },
                () -> result);
    }

    /** One-shot list multimap from input grouped by key (alias of {@link #listMultimapFromSortedRuns}). */
    public static <K, V> MutableListMultimap<K, V> listMultimapFromSortedKeys(
            Comparator<? super K> keyComparator, Iterable<Pair<K, V>> groupedByKey)
    {
        Sink<Pair<K, V>, MutableListMultimap<K, V>> sink = listMultimapSink(keyComparator);
        sink.putAll(groupedByKey);
        return sink.create();
    }

    /** One-shot set multimap from input grouped by key (alias of {@link #setMultimapFromSortedRuns}). */
    public static <K, V> MutableSetMultimap<K, V> setMultimapFromSortedKeys(
            Comparator<? super K> keyComparator, Iterable<Pair<K, V>> groupedByKey)
    {
        Sink<Pair<K, V>, MutableSetMultimap<K, V>> sink = setMultimapSink(keyComparator);
        sink.putAll(groupedByKey);
        return sink.create();
    }

    /**
     * One-shot list multimap from input <b>sorted by key then value</b>; value
     * order within each key is validated against {@code valueComparator}.
     */
    public static <K, V> MutableListMultimap<K, V> listMultimapFromSortedKeyValues(
            Comparator<? super K> keyComparator, Comparator<? super V> valueComparator, Iterable<Pair<K, V>> sorted)
    {
        Sink<Pair<K, V>, MutableListMultimap<K, V>> sink = listMultimapKeyValueSink(keyComparator, valueComparator);
        sink.putAll(sorted);
        return sink.create();
    }

    /**
     * One-shot set multimap from input sorted by key then value; value order within
     * each key is validated against {@code valueComparator} and equal values are
     * deduped.
     */
    public static <K, V> MutableSetMultimap<K, V> setMultimapFromSortedKeyValues(
            Comparator<? super K> keyComparator, Comparator<? super V> valueComparator, Iterable<Pair<K, V>> sorted)
    {
        Sink<Pair<K, V>, MutableSetMultimap<K, V>> sink = setMultimapKeyValueSink(keyComparator, valueComparator);
        sink.putAll(sorted);
        return sink.create();
    }

    /**
     * Unsorted bulk-load into a list multimap: no order claim on the input. Values
     * are grouped per key via the multimap's own hash, preserving encounter order
     * within each key. Equivalent to an n&times;{@code put} loop but a single entry
     * point for the pump API.
     */
    public static <K, V> MutableListMultimap<K, V> listMultimapBulkLoad(Iterable<Pair<K, V>> unsorted)
    {
        FastListMultimap<K, V> result = new FastListMultimap<>();
        for (Pair<K, V> pair : unsorted)
        {
            result.put(pair.getOne(), pair.getTwo());
        }
        return result;
    }

    /**
     * Unsorted bulk-load into a set multimap: no order claim on the input. Values
     * are grouped per key and deduped (set semantics).
     */
    public static <K, V> MutableSetMultimap<K, V> setMultimapBulkLoad(Iterable<Pair<K, V>> unsorted)
    {
        UnifiedSetMultimap<K, V> result = new UnifiedSetMultimap<>();
        for (Pair<K, V> pair : unsorted)
        {
            result.put(pair.getOne(), pair.getTwo());
        }
        return result;
    }

    /**
     * Streaming run-grouping multimap sink. Buffers the contiguous equal-key run,
     * validating key order (and optionally value order within a key), and flushes
     * each completed run to the target multimap. The final run is flushed on
     * {@link Sink#create()}. Honours the {@link Sink} poison / once-only contract.
     */
    private static final class MultimapRunSink<K, V, R> extends Sink<Pair<K, V>, R>
    {
        private final Comparator<? super K> keyComparator;
        private final Comparator<? super V> valueComparator;
        private final boolean dedupeEqualValues;
        private final RunFlush<K, V> flush;
        private final java.util.function.Supplier<R> finisher;

        private boolean has;
        private K runKey;
        private V lastValue;
        private MutableList<V> run;

        MultimapRunSink(
                Comparator<? super K> keyComparator,
                Comparator<? super V> valueComparator,
                boolean dedupeEqualValues,
                RunFlush<K, V> flush,
                java.util.function.Supplier<R> finisher)
        {
            this.keyComparator = keyComparator;
            this.valueComparator = valueComparator;
            this.dedupeEqualValues = dedupeEqualValues;
            this.flush = flush;
            this.finisher = finisher;
        }

        @Override
        protected void doPut(Pair<K, V> pair)
        {
            K key = pair.getOne();
            V value = pair.getTwo();
            if (this.has)
            {
                int cmp = compare(this.keyComparator, this.runKey, key);
                if (cmp > 0)
                {
                    throw new PumpSourceNotSorted(key);
                }
                if (cmp == 0)
                {
                    requireComparatorConsistentWithEquals(this.runKey, key, "key comparator");
                    if (this.valueComparator != null)
                    {
                        int vcmp = this.valueComparator.compare(this.lastValue, value);
                        if (vcmp > 0)
                        {
                            throw new PumpSourceNotSorted(value);
                        }
                        if (vcmp == 0 && this.dedupeEqualValues)
                        {
                            requireComparatorConsistentWithEquals(this.lastValue, value, "value comparator");
                            return; // set-valued: drop the equal duplicate value
                        }
                    }
                    this.run.add(value);
                    this.lastValue = value;
                    return;
                }
                // new key: flush the completed run.
                this.flush.accept(this.runKey, this.run);
            }
            this.runKey = key;
            this.run = FastList.newList();
            this.run.add(value);
            this.lastValue = value;
            this.has = true;
        }

        @Override
        protected R doCreate()
        {
            if (this.has)
            {
                this.flush.accept(this.runKey, this.run);
            }
            return this.finisher.get();
        }
    }

    private interface RunFlush<K, V>
    {
        void accept(K key, MutableList<V> run);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> int compare(Comparator<? super T> comparator, T a, T b)
    {
        if (comparator != null)
        {
            return comparator.compare(a, b);
        }
        return ((Comparable<? super T>) a).compareTo(b);
    }

    private static <T> void requireComparatorConsistentWithEquals(T a, T b, String label)
    {
        if (!Objects.equals(a, b))
        {
            throw new IllegalArgumentException(label + " must be consistent with equals: " + a + " vs " + b);
        }
    }

    private static boolean comparatorsEquivalent(Comparator<?> a, Comparator<?> b)
    {
        if (a == null && b == null)
        {
            return true;
        }
        return Objects.equals(a, b);
    }

    /**
     * Read-only {@link SortedMap} view over validated, already-sorted parallel
     * key/value lists. Only {@code comparator()}, {@code size()},
     * {@code isEmpty()} and {@code entrySet().iterator()} are exercised by the JDK
     * {@code TreeMap(SortedMap)} constructor; the rest throw to make any misuse
     * loud. This is the adapter that gives the JDK its O(n) {@code buildFromSorted}
     * path without buffering into an intermediate red-black tree.
     */
    private static final class SortedEntriesMap<K, V> extends java.util.AbstractMap<K, V> implements SortedMap<K, V>
    {
        private final Comparator<? super K> comparator;
        private final List<K> keys;
        private final List<V> values;

        SortedEntriesMap(Comparator<? super K> comparator, List<K> keys, List<V> values)
        {
            this.comparator = comparator;
            this.keys = keys;
            this.values = values;
        }

        @Override
        public Comparator<? super K> comparator()
        {
            return this.comparator;
        }

        @Override
        public int size()
        {
            return this.keys.size();
        }

        @Override
        public boolean isEmpty()
        {
            return this.keys.isEmpty();
        }

        @Override
        public java.util.Set<Map.Entry<K, V>> entrySet()
        {
            return new AbstractSet<Map.Entry<K, V>>()
            {
                @Override
                public Iterator<Map.Entry<K, V>> iterator()
                {
                    return new Iterator<Map.Entry<K, V>>()
                    {
                        private int i;

                        @Override
                        public boolean hasNext()
                        {
                            return this.i < SortedEntriesMap.this.keys.size();
                        }

                        @Override
                        public Map.Entry<K, V> next()
                        {
                            if (!this.hasNext())
                            {
                                throw new NoSuchElementException();
                            }
                            int idx = this.i++;
                            return new java.util.AbstractMap.SimpleImmutableEntry<>(
                                    SortedEntriesMap.this.keys.get(idx),
                                    SortedEntriesMap.this.values.get(idx));
                        }
                    };
                }

                @Override
                public int size()
                {
                    return SortedEntriesMap.this.keys.size();
                }
            };
        }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedMap<K, V> headMap(K toKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedMap<K, V> tailMap(K fromKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public K firstKey()
        {
            if (this.keys.isEmpty())
            {
                throw new NoSuchElementException();
            }
            return this.keys.get(0);
        }

        @Override
        public K lastKey()
        {
            if (this.keys.isEmpty())
            {
                throw new NoSuchElementException();
            }
            return this.keys.get(this.keys.size() - 1);
        }
    }

    /**
     * Read-only {@link SortedSet} view over a validated, already-sorted element
     * list, for the JDK {@code TreeSet(SortedSet)} O(n) constructor. Only
     * {@code comparator()}, {@code size()}, and {@code iterator()} are needed.
     */
    private static final class SortedElementsSet<T> extends AbstractSet<T> implements SortedSet<T>
    {
        private final Comparator<? super T> comparator;
        private final List<T> elements;

        SortedElementsSet(Comparator<? super T> comparator, List<T> elements)
        {
            this.comparator = comparator;
            this.elements = elements;
        }

        @Override
        public Comparator<? super T> comparator()
        {
            return this.comparator;
        }

        @Override
        public Iterator<T> iterator()
        {
            return this.elements.iterator();
        }

        @Override
        public int size()
        {
            return this.elements.size();
        }

        @Override
        public boolean isEmpty()
        {
            return this.elements.isEmpty();
        }

        @Override
        public SortedSet<T> subSet(T fromElement, T toElement)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedSet<T> headSet(T toElement)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedSet<T> tailSet(T fromElement)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public T first()
        {
            if (this.elements.isEmpty())
            {
                throw new NoSuchElementException();
            }
            return this.elements.get(0);
        }

        @Override
        public T last()
        {
            if (this.elements.isEmpty())
            {
                throw new NoSuchElementException();
            }
            return this.elements.get(this.elements.size() - 1);
        }
    }
}

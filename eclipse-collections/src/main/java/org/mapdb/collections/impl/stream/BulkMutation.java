// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

/**
 * Archeology-2 "A3" — bulk mutation over the packed sorted map ("pump v2").
 *
 * <p>The data pump ([`data-pump.md`]) builds a <b>fresh</b> collection from
 * sorted input in one O(n) pass. It has no story for changing a collection that
 * already exists: today that means {@code m} individual {@code put}/{@code
 * remove} calls, each O(log n). When the changes are themselves sorted, this
 * class rebuilds the whole packed map in a single <b>O(n + m)</b> merge pass
 * instead — the same machinery, the same "prepared sorted input" discipline,
 * pointed at an existing base rather than the empty set.
 *
 * <p>The packed {@link ImmutableSortedMap} is immutable, so every operation
 * returns a <b>new</b> map; the base is never touched. This is exactly the
 * LSM-flavored motion the archeology-2 sketch describes — an immutable base plus
 * a sorted delta, merged and rebuilt — and it composes with the A1
 * {@link SortedStream} algebra (a delta is just another ordered source).
 *
 * <p>All operations are natural-order only (the packed form is), and validate
 * their sorted-input contract the way the pump does: out-of-order or duplicate
 * change keys are an error, never silently reordered.
 */
public final class BulkMutation
{
    private BulkMutation()
    {
    }

    /**
     * A single prepared change against a map key: either an <b>upsert</b>
     * (insert-or-overwrite with a value) or a <b>delete</b>.
     *
     * @param <K> key type
     * @param <V> value type
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

        /**
         * Insert {@code key} or overwrite its existing value with {@code value}.
         * The packed map has a non-null value contract (it reports absence with
         * {@code Optional}), so a null value is rejected here rather than
         * detonating on a later read.
         */
        public static <K, V> Change<K, V> upsert(K key, V value)
        {
            return new Change<>(key, Objects.requireNonNull(value, "value"), false);
        }

        /** Remove {@code key} if present (a no-op if absent). */
        public static <K, V> Change<K, V> delete(K key)
        {
            return new Change<>(key, null, true);
        }

        /** The affected key. */
        public K key()
        {
            return this.key;
        }

        /** Whether this change removes the key. */
        public boolean isDelete()
        {
            return this.delete;
        }

        /**
         * The upsert value.
         *
         * @throws IllegalStateException if this is a delete
         */
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
     * Apply a key-ascending, distinct-keyed list of {@link Change}s (upserts and
     * deletes) to {@code base}, returning a fresh packed map built in one
     * O(n + m) merge pass. An upsert of an existing key overwrites it; an upsert
     * of a new key inserts it; a delete removes a key (deleting an absent key is
     * a no-op).
     *
     * @throws IllegalArgumentException if {@code changes} is not strictly
     *         ascending by key (the pump's sorted-input contract)
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
            Map.Entry<K, V> e = entries.get(i);
            Change<K, V> c = changes.get(j);
            int cmp = e.getKey().compareTo(c.key());
            if (cmp < 0)
            {
                emit(keys, values, e.getKey(), e.getValue());
                i++;
            }
            else if (cmp > 0)
            {
                if (!c.isDelete())
                {
                    emit(keys, values, c.key(), c.value());
                }
                j++;
            }
            else
            {
                // Same key: the change wins (overwrite or remove).
                if (!c.isDelete())
                {
                    emit(keys, values, c.key(), c.value());
                }
                i++;
                j++;
            }
        }
        while (i < entries.size())
        {
            Map.Entry<K, V> e = entries.get(i++);
            emit(keys, values, e.getKey(), e.getValue());
        }
        while (j < changes.size())
        {
            Change<K, V> c = changes.get(j++);
            if (!c.isDelete())
            {
                emit(keys, values, c.key(), c.value());
            }
        }
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    /**
     * Merge a disjoint {@code addition} into {@code base} in one O(n + m) pass,
     * returning a fresh packed map. The two maps must share <b>no keys</b>; a
     * shared key is an error (use {@link #applySorted} when overwrites are
     * intended). This is the spec's reserved {@code merge_sorted_disjoint} —
     * the fast path for stitching together independently built runs.
     *
     * @throws IllegalArgumentException if the maps share a key
     */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> mergeSortedDisjoint(
            ImmutableSortedMap<K, V> base, ImmutableSortedMap<K, V> addition)
    {
        List<Map.Entry<K, V>> a = base.entries();
        List<Map.Entry<K, V>> b = addition.entries();
        List<K> keys = new ArrayList<>(a.size() + b.size());
        List<V> values = new ArrayList<>(a.size() + b.size());

        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size())
        {
            Map.Entry<K, V> ea = a.get(i);
            Map.Entry<K, V> eb = b.get(j);
            int cmp = ea.getKey().compareTo(eb.getKey());
            if (cmp < 0)
            {
                emit(keys, values, ea.getKey(), ea.getValue());
                i++;
            }
            else if (cmp > 0)
            {
                emit(keys, values, eb.getKey(), eb.getValue());
                j++;
            }
            else
            {
                throw new IllegalArgumentException(
                        "mergeSortedDisjoint: maps are not disjoint (shared key " + ea.getKey() + ")");
            }
        }
        while (i < a.size())
        {
            Map.Entry<K, V> ea = a.get(i++);
            emit(keys, values, ea.getKey(), ea.getValue());
        }
        while (j < b.size())
        {
            Map.Entry<K, V> eb = b.get(j++);
            emit(keys, values, eb.getKey(), eb.getValue());
        }
        return ImmutableSortedMap.fromSorted(keys, values);
    }

    /**
     * Delete every key contained in {@code range}, returning a fresh packed map
     * built in one O(n) linear pass (range-delete + rebuild compaction). Because
     * the base is sorted and a {@link Range} is convex, the removed keys form a
     * single contiguous block.
     */
    public static <K extends Comparable<? super K>, V> ImmutableSortedMap<K, V> rangeDelete(
            ImmutableSortedMap<K, V> base, Range<K> range)
    {
        List<Map.Entry<K, V>> entries = base.entries();
        List<K> keys = new ArrayList<>(entries.size());
        List<V> values = new ArrayList<>(entries.size());
        for (Map.Entry<K, V> e : entries)
        {
            if (!range.contains(e.getKey()))
            {
                emit(keys, values, e.getKey(), e.getValue());
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
}

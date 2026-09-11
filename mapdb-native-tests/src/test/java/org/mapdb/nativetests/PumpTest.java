/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.nativetests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.api.bag.sorted.MutableSortedBag;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.multimap.list.MutableListMultimap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Counter;
import org.mapdb.collections.impl.Fibonacci;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.bag.sorted.mutable.TreeBag;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.Pump.DuplicatePolicy;
import org.mapdb.collections.impl.list.mutable.primitive.DoubleArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.FloatArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.IntArrayList;
import org.mapdb.collections.impl.bag.mutable.primitive.IntHashBag;
import org.mapdb.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.mapdb.collections.impl.list.mutable.primitive.BooleanArrayList;
import org.mapdb.collections.impl.list.mutable.FastList;
import org.mapdb.collections.impl.map.mutable.primitive.FloatBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.FloatObjectHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.ObjectIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.mapdb.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.mapdb.collections.impl.set.mutable.primitive.IntHashSet;
import org.mapdb.collections.api.iterator.IntIterator;
import org.mapdb.collections.impl.tuple.Tuples;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native test battery for the data pump (bulk import) feature
 * ({@code spec/features/data-pump.md}). Covers, for the families mapdb-java
 * actually pumps:
 *
 * <ul>
 *   <li>hash {@code bulkLoad}/{@code bulkLoadExact} == an incremental put/add
 *       loop ({@code equals} + membership);
 *   <li>zero mid-load rehash at {@code n = 3*2^k} (3,6,12,24,48), asserted on
 *       the backing array length via reflection;
 *   <li>{@code bulkLoadExact} rejects a source larger than the declared size;
 *   <li>duplicate policy {@code ERROR}/{@code IGNORE}; bags count duplicates;
 *   <li>float/double carve-outs (raw-bit identity through the hash pump,
 *       IEEE-754 totalOrder through the tree pump);
 *   <li>tree pump delegation + identical iteration order;
 *   <li>{@code Sink} poison / double-create / empty input;
 *   <li>multimap grouped builders (list keeps order, set dedupes).
 * </ul>
 */
public class PumpTest
{
    // ------------------------------------------------------------------
    // Hash map: equivalence + sizing
    // ------------------------------------------------------------------

    @Test
    public void mapBulkLoadEqualsPutLoop()
    {
        int n = 1000;
        IntArrayList keys = new IntArrayList();
        IntArrayList values = new IntArrayList();
        IntIntHashMap putLoop = new IntIntHashMap();
        for (int i = 0; i < n; i++)
        {
            int k = i * 7 + 100;   // keep clear of the 0/1 sentinels
            keys.add(k);
            values.add(i);
            putLoop.put(k, i);
        }

        IntIntHashMap pumped = IntIntHashMap.bulkLoad(n, keys, values, DuplicatePolicy.ERROR);
        assertEquals(putLoop, pumped);
        assertEquals(putLoop.size(), pumped.size());
        for (int i = 0; i < n; i++)
        {
            int k = i * 7 + 100;
            assertTrue(pumped.containsKey(k));
            assertEquals(i, pumped.get(k));
        }
    }

    @Test
    public void mapBulkLoadExactZeroRehashAt3TimesPowerOfTwo() throws Exception
    {
        for (int k = 0; k <= 4; k++)
        {
            int n = 3 << k;  // 3, 6, 12, 24, 48
            IntArrayList keys = new IntArrayList();
            IntArrayList values = new IntArrayList();
            for (int i = 0; i < n; i++)
            {
                int key = 100 + i;   // all distinct, all clear of sentinels
                keys.add(key);
                values.add(i);
            }

            IntIntHashMap map = IntIntHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);

            int lengthAfter = keysValuesLength(map);
            int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);    // slots
            assertEquals(expectedCap * 2, lengthAfter,
                    "interleaved keysValues length wrong at n=" + n);
            // The table can hold n at the ~50% threshold with no rehash.
            assertTrue(n <= expectedCap / 2, "sizing does not clear 50% threshold at n=" + n);
            assertEquals(n, map.size());
            // capacity is exactly the pre-sized one => no rehash fired.
            assertEquals(expectedCap, lengthAfter / 2);
        }
    }

    @Test
    public void mapBulkLoadExactMatchesPreSizedPutLoopLayoutWithCollisions() throws Exception
    {
        int n = 20;
        int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
        IntArrayList keys = new IntArrayList();
        IntArrayList values = new IntArrayList();
        for (int i = 0; i < n; i++)
        {
            keys.add(7 + i * expectedCap);
            values.add(1000 + i);
        }

        IntIntHashMap pumped = IntIntHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);
        IntIntHashMap putLoop = new IntIntHashMap(n);
        for (int i = 0; i < n; i++)
        {
            putLoop.put(keys.get(i), values.get(i));
        }

        assertArrayEquals(keysValues(putLoop), keysValues(pumped));
    }

    @Test
    public void mapBulkLoadExactRejectsOversizedSource()
    {
        IntArrayList keys = IntArrayList.newListWith(10, 11, 12, 13);
        IntArrayList values = IntArrayList.newListWith(0, 1, 2, 3);
        assertThrows(IllegalArgumentException.class,
                () -> IntIntHashMap.bulkLoadExact(3, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void mapBulkLoadRejectsInterleavedArrayOverflowHint()
    {
        int tooLargeForInterleavedTable = (1 << 28) + 1;
        assertThrows(IllegalArgumentException.class,
                () -> IntIntHashMap.bulkLoad(
                        tooLargeForInterleavedTable,
                        new IntArrayList(),
                        new IntArrayList(),
                        DuplicatePolicy.ERROR));
    }

    @Test
    public void mapDuplicateError()
    {
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        IntArrayList values = IntArrayList.newListWith(0, 1, 2);
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void mapDuplicateIgnoreKeepsFirst()
    {
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        IntArrayList values = IntArrayList.newListWith(10, 11, 99);
        IntIntHashMap map = IntIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.IGNORE);
        assertEquals(2, map.size());
        assertEquals(10, map.get(5));   // first value wins, NOT 99
        assertEquals(11, map.get(6));
    }

    @Test
    public void mapMismatchedKeyValueLengthsThrow()
    {
        IntArrayList keys = IntArrayList.newListWith(1, 2, 3);
        IntArrayList valuesShort = IntArrayList.newListWith(1, 2);
        assertThrows(IllegalArgumentException.class,
                () -> IntIntHashMap.bulkLoad(3, keys, valuesShort, DuplicatePolicy.ERROR));
        IntArrayList valuesLong = IntArrayList.newListWith(1, 2, 3, 4);
        assertThrows(IllegalArgumentException.class,
                () -> IntIntHashMap.bulkLoad(3, keys, valuesLong, DuplicatePolicy.ERROR));
    }

    @Test
    public void mapEmptyBulkLoad()
    {
        IntIntHashMap map = IntIntHashMap.bulkLoad(0, new IntArrayList(), new IntArrayList(), DuplicatePolicy.ERROR);
        assertTrue(map.isEmpty());
        assertEquals(new IntIntHashMap(), map);
    }

    @Test
    public void mapSentinelKeysSurvivePump()
    {
        // 0 and 1 are EC's reserved sentinel keys; they must round-trip as data.
        IntArrayList keys = IntArrayList.newListWith(0, 1, 2);
        IntArrayList values = IntArrayList.newListWith(100, 101, 102);
        IntIntHashMap map = IntIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR);
        assertEquals(3, map.size());
        assertEquals(100, map.get(0));
        assertEquals(101, map.get(1));
        assertEquals(102, map.get(2));
    }

    // ------------------------------------------------------------------
    // Hash set
    // ------------------------------------------------------------------

    @Test
    public void setBulkLoadEqualsAddLoop()
    {
        int n = 500;
        IntArrayList elements = new IntArrayList();
        IntHashSet addLoop = new IntHashSet();
        for (int i = 0; i < n; i++)
        {
            int e = i * 3 + 50;
            elements.add(e);
            addLoop.add(e);
        }
        IntHashSet pumped = IntHashSet.bulkLoad(n, elements, DuplicatePolicy.ERROR);
        assertEquals(addLoop, pumped);
        assertEquals(n, pumped.size());
    }

    @Test
    public void setBulkLoadExactZeroRehashLargeValues() throws Exception
    {
        // Use values >= 32 so they all live in the table (not the side bitmask),
        // making the backing length predictable.
        for (int k = 0; k <= 4; k++)
        {
            int n = 3 << k;
            IntArrayList elements = new IntArrayList();
            for (int i = 0; i < n; i++)
            {
                elements.add(100 + i);
            }
            IntHashSet set = IntHashSet.bulkLoadExact(n, elements, DuplicatePolicy.ERROR);
            int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
            assertEquals(expectedCap, tableLength(set), "set table length wrong at n=" + n);
            assertEquals(n, set.size());
        }
    }

    @Test
    public void setSmallValuesUseBitmaskPath()
    {
        // 0..31 go to the side bitmask; pump must still produce the right set.
        IntArrayList elements = IntArrayList.newListWith(0, 1, 5, 31, 100, 200);
        IntHashSet pumped = IntHashSet.bulkLoad(6, elements, DuplicatePolicy.ERROR);
        IntHashSet expected = IntHashSet.newSetWith(0, 1, 5, 31, 100, 200);
        assertEquals(expected, pumped);
    }

    @Test
    public void setDuplicateError()
    {
        IntArrayList elements = IntArrayList.newListWith(40, 41, 40);
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntHashSet.bulkLoad(3, elements, DuplicatePolicy.ERROR));
    }

    @Test
    public void setDuplicateIgnore()
    {
        IntArrayList elements = IntArrayList.newListWith(40, 41, 40, 42);
        IntHashSet set = IntHashSet.bulkLoad(4, elements, DuplicatePolicy.IGNORE);
        assertEquals(IntHashSet.newSetWith(40, 41, 42), set);
    }

    // ------------------------------------------------------------------
    // Hash bag
    // ------------------------------------------------------------------

    @Test
    public void bagBulkLoadCountsDuplicates()
    {
        IntArrayList elements = IntArrayList.newListWith(7, 7, 7, 8, 9, 9);
        IntHashBag addLoop = new IntHashBag();
        addLoop.add(7);
        addLoop.add(7);
        addLoop.add(7);
        addLoop.add(8);
        addLoop.add(9);
        addLoop.add(9);

        IntHashBag pumped = IntHashBag.bulkLoad(3 /* distinct */, elements);
        assertEquals(addLoop, pumped);
        assertEquals(3, pumped.occurrencesOf(7));
        assertEquals(1, pumped.occurrencesOf(8));
        assertEquals(2, pumped.occurrencesOf(9));
        assertEquals(6, pumped.size());
        assertEquals(3, pumped.sizeDistinct());
    }

    @Test
    public void bagOvercountedExpectedDistinctStillCorrect()
    {
        IntArrayList elements = IntArrayList.newListWith(1, 2, 3);
        IntHashBag pumped = IntHashBag.bulkLoad(100, elements);
        assertEquals(3, pumped.size());
        assertEquals(3, pumped.sizeDistinct());
    }

    @Test
    public void bagBulkLoadRejectsInterleavedArrayOverflowHint()
    {
        int tooLargeForInterleavedTable = (1 << 28) + 1;
        assertThrows(IllegalArgumentException.class,
                () -> IntHashBag.bulkLoad(tooLargeForInterleavedTable, new IntArrayList()));
    }

    // ------------------------------------------------------------------
    // Bag overflow contract (data-pump spec: bag run counts are checked)
    // ------------------------------------------------------------------

    @Test
    public void hashBagBulkLoadPerElementCountOverflowThrows()
    {
        // One element repeated MAX_VALUE + 1 times: the per-element occurrence
        // count would wrap from Integer.MAX_VALUE to a negative value -> error.
        RepeatedIntIterable source = new RepeatedIntIterable(42, (long) Integer.MAX_VALUE + 1);
        assertThrows(Pump.PumpSourceOverflow.class, () -> IntHashBag.bulkLoad(1, source));
    }

    @Test
    public void hashBagBulkLoadAtExactlyMaxValueDoesNotThrow()
    {
        // Exactly MAX_VALUE occurrences is the boundary that must still succeed.
        RepeatedIntIterable source = new RepeatedIntIterable(42, Integer.MAX_VALUE);
        IntHashBag bag = IntHashBag.bulkLoad(1, source);
        assertEquals(Integer.MAX_VALUE, bag.occurrencesOf(42));
        assertEquals(Integer.MAX_VALUE, bag.size());
        assertEquals(1, bag.sizeDistinct());
    }

    @Test
    public void treeBagSinkCountsSortedRunsAndMatchesPutLoop()
    {
        // Ascending runs of equal keys -> count == run length, built via the JDK
        // TreeMap(SortedMap) bulk path (not per-element add()).
        Pump.Sink<Integer, MutableSortedBag<Integer>> sink = Pump.treeBag(null);
        sink.put(1);
        sink.put(1);
        sink.put(1);
        sink.put(2);
        sink.put(3);
        sink.put(3);
        MutableSortedBag<Integer> bag = sink.create();

        assertEquals(3, bag.occurrencesOf(1));
        assertEquals(1, bag.occurrencesOf(2));
        assertEquals(2, bag.occurrencesOf(3));
        assertEquals(6, bag.size());
        assertEquals(3, bag.sizeDistinct());

        TreeBag<Integer> ref = TreeBag.newBag();
        ref.add(1);
        ref.add(1);
        ref.add(1);
        ref.add(2);
        ref.add(3);
        ref.add(3);
        assertEquals(ref, bag);
        assertEquals(List.of(1, 1, 1, 2, 3, 3), bag.toList());
    }

    @Test
    public void treeBagSinkRejectsOutOfOrder()
    {
        Pump.Sink<Integer, MutableSortedBag<Integer>> sink = Pump.treeBag(null);
        sink.put(1);
        sink.put(2);
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(1));
    }

    @Test
    public void treeBagSinkPerRunCountOverflowThrows()
    {
        // Drive a single run past Integer.MAX_VALUE occurrences via the synthetic
        // iterable; the sink's per-run Counter must trip the overflow check.
        Pump.Sink<Integer, MutableSortedBag<Integer>> sink = Pump.treeBag(null);
        RepeatedIntIterable source = new RepeatedIntIterable(7, (long) Integer.MAX_VALUE + 1);
        IntIterator it = source.intIterator();
        assertThrows(Pump.PumpSourceOverflow.class, () ->
        {
            while (it.hasNext())
            {
                sink.put(it.next());
            }
        });
    }

    @Test
    public void treeBagFromSortedCountsTotalSizeOverflowThrows()
    {
        // Synthetic Counter path: two distinct keys whose counts together exceed
        // Integer.MAX_VALUE -> the total bag size overflows and must throw.
        TreeSortedMap<Integer, Counter> counts = new TreeSortedMap<>();
        counts.put(1, new Counter(Integer.MAX_VALUE));
        counts.put(2, new Counter(1));
        assertThrows(ArithmeticException.class, () -> TreeBag.fromSortedCounts(counts));
    }

    @Test
    public void treeBagFromSortedCountsBuildsValidBag()
    {
        TreeSortedMap<Integer, Counter> counts = new TreeSortedMap<>();
        counts.put(1, new Counter(3));
        counts.put(2, new Counter(2));
        TreeBag<Integer> bag = TreeBag.fromSortedCounts(counts);
        assertEquals(5, bag.size());
        assertEquals(3, bag.occurrencesOf(1));
        assertEquals(2, bag.occurrencesOf(2));
        assertEquals(List.of(1, 1, 1, 2, 2), bag.toList());
    }

    // ------------------------------------------------------------------
    // Float / double carve-outs (hash path: raw-bit identity)
    // ------------------------------------------------------------------

    @Test
    public void floatHashPumpPreservesRawBitIdentity()
    {
        float negZero = -0.0f;
        float posZero = 0.0f;
        float nan1 = Float.intBitsToFloat(0x7fc00000);
        float nan2 = Float.intBitsToFloat(0x7fc00001);   // distinct NaN payload
        FloatArrayList keys = FloatArrayList.newListWith(negZero, posZero, nan1, nan2, Float.POSITIVE_INFINITY);
        IntArrayList values = IntArrayList.newListWith(1, 2, 3, 4, 5);

        FloatIntHashMap map = FloatIntHashMap.bulkLoad(5, keys, values, DuplicatePolicy.ERROR);
        // -0.0 and +0.0 are distinct raw-bit keys.
        assertEquals(5, map.size());
        assertEquals(1, map.get(negZero));
        assertEquals(2, map.get(posZero));
        assertEquals(5, map.get(Float.POSITIVE_INFINITY));
        assertTrue(map.containsKey(nan1));
        assertTrue(map.containsKey(nan2));
    }

    @Test
    public void doubleHashSetPumpDistinguishesSignedZero()
    {
        DoubleArrayList elements = DoubleArrayList.newListWith(-0.0, 0.0, 1.0);
        DoubleHashSet set = DoubleHashSet.bulkLoad(3, elements, DuplicatePolicy.ERROR);
        assertEquals(3, set.size());
        assertTrue(set.contains(-0.0));
        assertTrue(set.contains(0.0));
    }

    // ------------------------------------------------------------------
    // Tree pump (delegate to JDK) + float totalOrder
    // ------------------------------------------------------------------

    @Test
    public void treeSortedMapFromSortedDelegatesAndKeepsOrder()
    {
        SortedMap<Integer, String> source = new TreeMap<>();
        for (int i = 0; i < 50; i++)
        {
            source.put(i, "v" + i);
        }
        MutableSortedMap<Integer, String> pumped = Pump.treeSortedMapFromSorted(source);
        assertEquals(new ArrayList<>(source.keySet()), pumped.keysView().toList());
        assertEquals(50, pumped.size());
        assertEquals("v0", pumped.get(0));
        assertEquals("v49", pumped.get(49));
    }

    @Test
    public void treeSortedMapSinkValidatesAndMatchesPutLoop()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        TreeMap<Integer, String> reference = new TreeMap<>();
        for (int i = 0; i < 20; i++)
        {
            sink.put(Tuples.pair(i, "x" + i));
            reference.put(i, "x" + i);
        }
        MutableSortedMap<Integer, String> built = sink.create();
        assertEquals(new ArrayList<>(reference.keySet()), built.keysView().toList());
        assertEquals(new ArrayList<>(reference.values()), built.toList());
    }

    @Test
    public void treeSortedMapSinkRejectsOutOfOrder()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        sink.put(Tuples.pair(5, "a"));
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(Tuples.pair(3, "b")));
    }

    @Test
    public void treeSortedMapSinkRejectsDuplicateUnderError()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        sink.put(Tuples.pair(5, "a"));
        assertThrows(Pump.PumpSourceDuplicate.class, () -> sink.put(Tuples.pair(5, "b")));
    }

    @Test
    public void treeSortedMapSinkIgnoreDuplicateKeepsFirst()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.IGNORE);
        sink.put(Tuples.pair(5, "first"));
        sink.put(Tuples.pair(5, "second"));
        sink.put(Tuples.pair(6, "x"));
        MutableSortedMap<Integer, String> built = sink.create();
        assertEquals(2, built.size());
        assertEquals("first", built.get(5));
    }

    @Test
    public void treeSortedMapOutOfOrderAtFirstMiddleLast()
    {
        // middle
        assertThrows(Pump.PumpSourceNotSorted.class, () ->
        {
            Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> s =
                    Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
            s.put(Tuples.pair(1, "a"));
            s.put(Tuples.pair(2, "b"));
            s.put(Tuples.pair(0, "c"));   // out of order in the middle
        });
        // last
        assertThrows(Pump.PumpSourceNotSorted.class, () ->
        {
            Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> s =
                    Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
            s.put(Tuples.pair(1, "a"));
            s.put(Tuples.pair(2, "b"));
            s.put(Tuples.pair(3, "c"));
            s.put(Tuples.pair(2, "d"));   // out of order at the end
        });
    }

    @Test
    public void treeSortedSetWithCustomComparatorKeepsOrder()
    {
        Comparator<Integer> reverse = Comparator.reverseOrder();
        List<Integer> descending = new ArrayList<>(List.of(50, 40, 30, 20, 10));
        Pump.Sink<Integer, MutableSortedSet<Integer>> sink = Pump.treeSortedSet(reverse, DuplicatePolicy.ERROR);
        sink.putAll(descending);
        MutableSortedSet<Integer> set = sink.create();
        // ascending under the reverse comparator == numerically descending
        assertEquals(descending, set.toList());
        assertEquals(reverse, set.comparator());
        // feeding ascending input to a reverse-comparator sink is "out of order"
        Pump.Sink<Integer, MutableSortedSet<Integer>> sink2 = Pump.treeSortedSet(reverse, DuplicatePolicy.ERROR);
        sink2.put(10);
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink2.put(20));
    }

    @Test
    public void floatTreeSortedSetUsesTotalOrder()
    {
        // Inserted ascending under FloatTotalOrder: -NaN < -Inf < -1 < -0 < +0 < 1 < +Inf < +NaN
        float negNan = Float.intBitsToFloat(0xffc00000);
        float posNan = Float.intBitsToFloat(0x7fc00000);
        List<Float> ascending = new ArrayList<>(List.of(
                negNan, Float.NEGATIVE_INFINITY, -1.0f, -0.0f, 0.0f, 1.0f, Float.POSITIVE_INFINITY, posNan));

        Pump.Sink<Float, MutableSortedSet<Float>> sink = Pump.floatTreeSortedSet(DuplicatePolicy.ERROR);
        sink.putAll(ascending);
        MutableSortedSet<Float> set = sink.create();

        List<Float> out = set.toList();
        assertEquals(ascending.size(), out.size());
        for (int i = 0; i < ascending.size(); i++)
        {
            assertEquals(Float.floatToRawIntBits(ascending.get(i)), Float.floatToRawIntBits(out.get(i)),
                    "totalOrder iteration order mismatch at " + i);
        }
        // sanity: a raw ascending check via the comparator
        for (int i = 1; i < out.size(); i++)
        {
            assertTrue(FloatTotalOrder.totalCompare(out.get(i - 1), out.get(i)) < 0);
        }
    }

    @Test
    public void floatTreeSortedSetRejectsOutOfTotalOrder()
    {
        Pump.Sink<Float, MutableSortedSet<Float>> sink = Pump.floatTreeSortedSet(DuplicatePolicy.ERROR);
        sink.put(1.0f);
        // -1.0 sorts before 1.0 in totalOrder => out of order
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(-1.0f));
    }

    // ------------------------------------------------------------------
    // Sink lifecycle
    // ------------------------------------------------------------------

    @Test
    public void sinkPoisonedAfterError()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        sink.put(Tuples.pair(5, "a"));
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(Tuples.pair(3, "b")));
        // poisoned: further put and create fail
        assertThrows(IllegalStateException.class, () -> sink.put(Tuples.pair(9, "c")));
        assertThrows(IllegalStateException.class, sink::create);
    }

    @Test
    public void sinkCreateOnceOnly()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        sink.put(Tuples.pair(1, "a"));
        sink.create();
        assertThrows(IllegalStateException.class, sink::create);
        assertThrows(IllegalStateException.class, () -> sink.put(Tuples.pair(2, "b")));
    }

    @Test
    public void sinkEmptyInput()
    {
        Pump.Sink<Pair<Integer, String>, MutableSortedMap<Integer, String>> sink =
                Pump.treeSortedMap(null, DuplicatePolicy.ERROR);
        MutableSortedMap<Integer, String> map = sink.create();
        assertTrue(map.isEmpty());
    }

    // ------------------------------------------------------------------
    // Multimap grouped builders
    // ------------------------------------------------------------------

    @Test
    public void listMultimapFromSortedRunsKeepsValueOrderAndDuplicates()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "a"), Tuples.pair(1, "b"), Tuples.pair(1, "a"),
                Tuples.pair(2, "c"),
                Tuples.pair(3, "d"), Tuples.pair(3, "e")));
        MutableListMultimap<Integer, String> mm = Pump.listMultimapFromSortedRuns(null, input);

        assertEquals(3, mm.keysView().size());
        assertEquals(List.of("a", "b", "a"), mm.get(1).toList());   // order + dup preserved
        assertEquals(List.of("c"), mm.get(2).toList());
        assertEquals(List.of("d", "e"), mm.get(3).toList());

        // equals an n*put loop
        FastListMultimapRef ref = new FastListMultimapRef();
        for (Pair<Integer, String> p : input)
        {
            ref.put(p.getOne(), p.getTwo());
        }
        assertEquals(ref.multimap, mm);
    }

    @Test
    public void setMultimapFromSortedRunsDedupesValues()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "a"), Tuples.pair(1, "b"), Tuples.pair(1, "a"),
                Tuples.pair(2, "c")));
        MutableSetMultimap<Integer, String> mm = Pump.setMultimapFromSortedRuns(null, input);
        assertEquals(2, mm.get(1).size());   // "a" deduped
        assertTrue(mm.get(1).contains("a"));
        assertTrue(mm.get(1).contains("b"));
        assertEquals(1, mm.get(2).size());
    }

    @Test
    public void multimapRejectsOutOfOrderKeys()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "a"), Tuples.pair(3, "b"), Tuples.pair(2, "c")));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> Pump.listMultimapFromSortedRuns(null, input));
    }

    // ------------------------------------------------------------------
    // Multimap source contracts: fromSortedKeys / fromSortedKeyValues / bulkLoad
    // ------------------------------------------------------------------

    @Test
    public void multimapFromSortedKeysGroupsByKey()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "b"), Tuples.pair(1, "a"),
                Tuples.pair(2, "c")));
        // fromSortedKeys does NOT validate value order: "b" then "a" is fine.
        MutableListMultimap<Integer, String> mm = Pump.listMultimapFromSortedKeys(null, input);
        assertEquals(List.of("b", "a"), mm.get(1).toList());
        assertEquals(List.of("c"), mm.get(2).toList());
    }

    @Test
    public void multimapFromSortedKeyValuesValidatesValueOrder()
    {
        // Values within key 1 are descending -> the key+value path must reject.
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "b"), Tuples.pair(1, "a")));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> Pump.listMultimapFromSortedKeyValues(null, Comparator.<String>naturalOrder(), input));
    }

    @Test
    public void multimapFromSortedKeyValuesAcceptsAscendingValues()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(1, "a"), Tuples.pair(1, "b"), Tuples.pair(1, "b"),
                Tuples.pair(2, "c")));
        MutableListMultimap<Integer, String> list =
                Pump.listMultimapFromSortedKeyValues(null, Comparator.<String>naturalOrder(), input);
        assertEquals(List.of("a", "b", "b"), list.get(1).toList());   // list keeps the equal "b"

        MutableSetMultimap<Integer, String> set =
                Pump.setMultimapFromSortedKeyValues(null, Comparator.<String>naturalOrder(), input);
        assertEquals(2, set.get(1).size());   // set dedupes the equal "b"
        assertTrue(set.get(1).contains("a"));
        assertTrue(set.get(1).contains("b"));
    }

    @Test
    public void multimapRejectsComparatorEqualButNonEqualKeys()
    {
        List<Pair<String, Integer>> input = List.of(
                Tuples.pair("A", 1),
                Tuples.pair("a", 2));

        assertThrows(IllegalArgumentException.class,
                () -> Pump.listMultimapFromSortedKeyValues(
                        String.CASE_INSENSITIVE_ORDER,
                        Comparator.<Integer>naturalOrder(),
                        input));
    }

    @Test
    public void setMultimapRejectsComparatorEqualButNonEqualDedupedValues()
    {
        List<Pair<Integer, String>> input = List.of(
                Tuples.pair(1, "A"),
                Tuples.pair(1, "a"));

        assertThrows(IllegalArgumentException.class,
                () -> Pump.setMultimapFromSortedKeyValues(
                        null,
                        String.CASE_INSENSITIVE_ORDER,
                        input));
    }

    @Test
    public void multimapSetKeyValueSinkValueOutOfOrderPoisonsSink()
    {
        Pump.Sink<Pair<Integer, String>, MutableSetMultimap<Integer, String>> sink =
                Pump.setMultimapKeyValueSink(null, Comparator.<String>naturalOrder());
        sink.put(Tuples.pair(1, "b"));
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(Tuples.pair(1, "a")));
        // poisoned: further put and create fail
        assertThrows(IllegalStateException.class, () -> sink.put(Tuples.pair(2, "z")));
        assertThrows(IllegalStateException.class, sink::create);
    }

    @Test
    public void multimapSinkDoubleCreateFails()
    {
        Pump.Sink<Pair<Integer, String>, MutableListMultimap<Integer, String>> sink =
                Pump.listMultimapSink(null);
        sink.put(Tuples.pair(1, "a"));
        sink.put(Tuples.pair(2, "b"));
        MutableListMultimap<Integer, String> mm = sink.create();
        assertEquals(List.of("a"), mm.get(1).toList());
        assertThrows(IllegalStateException.class, sink::create);
        assertThrows(IllegalStateException.class, () -> sink.put(Tuples.pair(3, "c")));
    }

    @Test
    public void multimapSinkRejectsOutOfOrderKeysAndPoisons()
    {
        Pump.Sink<Pair<Integer, String>, MutableListMultimap<Integer, String>> sink =
                Pump.listMultimapSink(null);
        sink.put(Tuples.pair(1, "a"));
        sink.put(Tuples.pair(3, "b"));
        assertThrows(Pump.PumpSourceNotSorted.class, () -> sink.put(Tuples.pair(2, "c")));
        assertThrows(IllegalStateException.class, sink::create);
    }

    @Test
    public void multimapBulkLoadUnsortedGroupsAndDedupes()
    {
        List<Pair<Integer, String>> input = new ArrayList<>(List.of(
                Tuples.pair(3, "c"), Tuples.pair(1, "a"), Tuples.pair(3, "c"),
                Tuples.pair(1, "b"), Tuples.pair(2, "x")));
        MutableListMultimap<Integer, String> list = Pump.listMultimapBulkLoad(input);
        assertEquals(List.of("a", "b"), list.get(1).toList());
        assertEquals(List.of("x"), list.get(2).toList());
        assertEquals(List.of("c", "c"), list.get(3).toList());   // list keeps dup

        MutableSetMultimap<Integer, String> set = Pump.setMultimapBulkLoad(input);
        assertEquals(1, set.get(3).size());   // set dedupes
    }

    // small helper to build a reference list-multimap via the normal put loop
    private static final class FastListMultimapRef
    {
        final org.mapdb.collections.impl.multimap.list.FastListMultimap<Integer, String> multimap =
                new org.mapdb.collections.impl.multimap.list.FastListMultimap<>();

        void put(Integer k, String v)
        {
            this.multimap.put(k, v);
        }
    }

    // ==================================================================
    // primitive -> object  (<name>ObjectHashMap)
    // ==================================================================

    @Test
    public void primObjBulkLoadEqualsPutLoop()
    {
        int n = 1000;
        IntArrayList keys = new IntArrayList();
        FastList<String> values = new FastList<>();
        IntObjectHashMap<String> putLoop = new IntObjectHashMap<>();
        for (int i = 0; i < n; i++)
        {
            int k = i * 7 + 100;   // keep clear of the 0/1 sentinels
            keys.add(k);
            values.add("v" + i);
            putLoop.put(k, "v" + i);
        }

        IntObjectHashMap<String> pumped = IntObjectHashMap.bulkLoad(n, keys, values, DuplicatePolicy.ERROR);
        assertEquals(putLoop, pumped);
        assertEquals(putLoop.size(), pumped.size());
        for (int i = 0; i < n; i++)
        {
            assertTrue(pumped.containsKey(i * 7 + 100));
            assertEquals("v" + i, pumped.get(i * 7 + 100));
        }
    }

    @Test
    public void primObjBulkLoadExactZeroRehashAt3TimesPowerOfTwo() throws Exception
    {
        for (int k = 0; k <= 4; k++)
        {
            int n = 3 << k;  // 3, 6, 12, 24, 48
            IntArrayList keys = new IntArrayList();
            FastList<String> values = new FastList<>();
            for (int i = 0; i < n; i++)
            {
                keys.add(100 + i);
                values.add("v" + i);
            }

            IntObjectHashMap<String> map = IntObjectHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);

            int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
            // The pre-sized table survived the whole load untouched. rehashAndGrow()
            // can only ever produce a STRICTLY larger table here (no removals => no
            // sentinels => newCapacity == nextPow2(2*(occupied+1)) > capacity at the
            // trigger point), so an unchanged capacity proves zero rehash fired.
            assertEquals(expectedCap, primitiveKeysLength(IntObjectHashMap.class, map),
                    "keys table length wrong at n=" + n);
            assertTrue(n <= expectedCap / 2, "sizing does not clear 50% threshold at n=" + n);
            assertEquals(n, map.size());
        }
    }

    @Test
    public void primObjBulkLoadExactMatchesPreSizedPutLoopLayoutWithCollisions() throws Exception
    {
        int n = 20;
        int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
        // IntObjectHashMap probes the raw key first (mask(element), the documented
        // first-probe carve-out), so k & (cap-1) picks the bucket.
        IntArrayList keys = keysSharingFirstProbe(n, expectedCap, false);
        FastList<String> values = new FastList<>();
        for (int i = 0; i < n; i++)
        {
            values.add("v" + i);
        }

        IntObjectHashMap<String> pumped = IntObjectHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);
        IntObjectHashMap<String> putLoop = new IntObjectHashMap<>(n);
        for (int i = 0; i < n; i++)
        {
            putLoop.put(keys.get(i), values.get(i));
        }

        // Identical slot-for-slot layout => the pump walked the same probe sequence
        // as an equally pre-sized put loop. (This is a layout-compatibility check;
        // the zero-rehash claim is carried by the capacity assertions in
        // primObjBulkLoadExactZeroRehashAt3TimesPowerOfTwo, not by this one.)
        assertArrayEquals(primitiveKeys(IntObjectHashMap.class, putLoop),
                primitiveKeys(IntObjectHashMap.class, pumped));
    }

    @Test
    public void primObjBulkLoadExactRejectsOversizedSource()
    {
        IntArrayList keys = IntArrayList.newListWith(10, 11, 12, 13);
        FastList<String> values = FastList.newListWith("a", "b", "c", "d");
        assertThrows(IllegalArgumentException.class,
                () -> IntObjectHashMap.bulkLoadExact(3, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjBulkLoadRejectsOverflowingSizeHint()
    {
        int tooLargeForTable = (1 << 29) + 1;   // 2 * n overflows the 2^30 table bound
        assertThrows(IllegalArgumentException.class,
                () -> IntObjectHashMap.bulkLoad(
                        tooLargeForTable, new IntArrayList(), new FastList<String>(), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntObjectHashMap.bulkLoad(
                        -1, new IntArrayList(), new FastList<String>(), DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjLargePracticalHintPreSizesToNextPowerOfTwo() throws Exception
    {
        // 1 << 29 is the largest ACCEPTED hint (the next value up is rejected --
        // see primObjBulkLoadRejectsOverflowingSizeHint); we do not allocate that
        // here. This only checks that a practically large hint pre-sizes to
        // exactly nextPow2(2n) and does so without consuming any input.
        int n = 1 << 16;
        IntObjectHashMap<String> map = IntObjectHashMap.bulkLoad(
                n, new IntArrayList(), new FastList<String>(), DuplicatePolicy.ERROR);
        assertEquals(1 << 17, primitiveKeysLength(IntObjectHashMap.class, map));
        assertTrue(map.isEmpty());
    }

    @Test
    public void allThreeFamiliesLoadExactlyAtTheHalfFullThreshold() throws Exception
    {
        // n == capacity / 2 is the tightest load the sizing promises: one more
        // element would trip the grow trigger. All three families must still come
        // out at the pre-sized capacity (i.e. no rehash fired).
        int n = 32;
        int expectedCap = 64;   // nextPow2(2 * 32)
        assertEquals(expectedCap, smallestPowerOfTwoGreaterThan(2 * n));

        IntArrayList intKeys = new IntArrayList();
        FastList<String> strValues = new FastList<>();
        FastList<String> strKeys = new FastList<>();
        IntArrayList intValues = new IntArrayList();
        BooleanArrayList boolValues = new BooleanArrayList();
        for (int i = 0; i < n; i++)
        {
            intKeys.add(100 + i);
            intValues.add(i);
            strKeys.add("k" + i);
            strValues.add("v" + i);
            boolValues.add((i & 1) == 0);
        }

        IntObjectHashMap<String> primObj =
                IntObjectHashMap.bulkLoadExact(n, intKeys, strValues, DuplicatePolicy.ERROR);
        assertEquals(n, primObj.size());
        assertEquals(expectedCap, primitiveKeysLength(IntObjectHashMap.class, primObj));

        ObjectIntHashMap<String> objPrim =
                ObjectIntHashMap.bulkLoadExact(n, strKeys, intValues, DuplicatePolicy.ERROR);
        assertEquals(n, objPrim.size());
        assertEquals(expectedCap, objectKeysLength(ObjectIntHashMap.class, objPrim));

        IntBooleanHashMap primBool =
                IntBooleanHashMap.bulkLoadExact(n, intKeys, boolValues, DuplicatePolicy.ERROR);
        assertEquals(n, primBool.size());
        assertEquals(expectedCap, primitiveKeysLength(IntBooleanHashMap.class, primBool));
    }

    @Test
    public void primObjDuplicateError()
    {
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        FastList<String> values = FastList.newListWith("a", "b", "c");
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntObjectHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjDuplicateIgnoreKeepsFirst()
    {
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        FastList<String> values = FastList.newListWith("first", "b", "later");
        IntObjectHashMap<String> map = IntObjectHashMap.bulkLoad(3, keys, values, DuplicatePolicy.IGNORE);
        assertEquals(2, map.size());
        assertEquals("first", map.get(5));
        assertEquals("b", map.get(6));
    }

    @Test
    public void primObjNullValuesAreSupportedAndDoNotConfuseDuplicateDetection()
    {
        // primitive->object maps permit null VALUES; duplicate detection keys off
        // containsKey, not get() != null, so a null first value still wins.
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        FastList<String> values = FastList.newListWith(null, "b", "later");
        IntObjectHashMap<String> map = IntObjectHashMap.bulkLoad(3, keys, values, DuplicatePolicy.IGNORE);
        assertEquals(2, map.size());
        assertTrue(map.containsKey(5));
        assertNull(map.get(5));

        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntObjectHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjMismatchedKeyValueLengthsThrow()
    {
        IntArrayList keys = IntArrayList.newListWith(1, 2, 3);
        assertThrows(IllegalArgumentException.class,
                () -> IntObjectHashMap.bulkLoad(3, keys, FastList.newListWith("a", "b"), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntObjectHashMap.bulkLoad(
                        3, keys, FastList.newListWith("a", "b", "c", "d"), DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjEmptyBulkLoad()
    {
        IntObjectHashMap<String> map = IntObjectHashMap.bulkLoad(
                0, new IntArrayList(), new FastList<String>(), DuplicatePolicy.ERROR);
        assertTrue(map.isEmpty());
        assertEquals(new IntObjectHashMap<String>(), map);

        IntObjectHashMap<String> exact = IntObjectHashMap.bulkLoadExact(
                0, new IntArrayList(), new FastList<String>(), DuplicatePolicy.ERROR);
        assertTrue(exact.isEmpty());
    }

    @Test
    public void primObjSentinelKeysSurvivePump()
    {
        // 0 and 1 are EC's reserved sentinel keys; they live in sentinelValues.
        IntArrayList keys = IntArrayList.newListWith(0, 1, 2);
        FastList<String> values = FastList.newListWith("zero", "one", "two");
        IntObjectHashMap<String> map = IntObjectHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR);
        assertEquals(3, map.size());
        assertEquals("zero", map.get(0));
        assertEquals("one", map.get(1));
        assertEquals("two", map.get(2));

        // and a duplicated sentinel key is still caught
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntObjectHashMap.bulkLoad(
                        3, IntArrayList.newListWith(0, 0), FastList.newListWith("a", "b"), DuplicatePolicy.ERROR));
    }

    @Test
    public void primObjFloatKeysPreserveRawBitIdentity()
    {
        float nan1 = Float.intBitsToFloat(0x7fc00000);
        float nan2 = Float.intBitsToFloat(0x7fc00001);   // distinct NaN payload
        FloatArrayList keys = FloatArrayList.newListWith(-0.0f, 0.0f, nan1, nan2, Float.POSITIVE_INFINITY);
        FastList<String> values = FastList.newListWith("nz", "pz", "n1", "n2", "inf");

        FloatObjectHashMap<String> map = FloatObjectHashMap.bulkLoad(5, keys, values, DuplicatePolicy.ERROR);
        assertEquals(5, map.size());
        assertEquals("nz", map.get(-0.0f));
        assertEquals("pz", map.get(0.0f));
        assertEquals("n1", map.get(nan1));
        assertEquals("n2", map.get(nan2));
        assertEquals("inf", map.get(Float.POSITIVE_INFINITY));
    }

    // ==================================================================
    // object -> primitive  (Object<name>HashMap)
    // ==================================================================

    @Test
    public void objPrimBulkLoadEqualsPutLoop()
    {
        int n = 1000;
        FastList<String> keys = new FastList<>();
        IntArrayList values = new IntArrayList();
        ObjectIntHashMap<String> putLoop = new ObjectIntHashMap<>();
        for (int i = 0; i < n; i++)
        {
            keys.add("k" + i);
            values.add(i);
            putLoop.put("k" + i, i);
        }

        ObjectIntHashMap<String> pumped = ObjectIntHashMap.bulkLoad(n, keys, values, DuplicatePolicy.ERROR);
        assertEquals(putLoop, pumped);
        assertEquals(putLoop.size(), pumped.size());
        for (int i = 0; i < n; i++)
        {
            assertTrue(pumped.containsKey("k" + i));
            assertEquals(i, pumped.get("k" + i));
        }
    }

    @Test
    public void objPrimBulkLoadExactZeroRehashAt3TimesPowerOfTwo() throws Exception
    {
        for (int k = 0; k <= 4; k++)
        {
            int n = 3 << k;
            FastList<String> keys = new FastList<>();
            IntArrayList values = new IntArrayList();
            for (int i = 0; i < n; i++)
            {
                keys.add("k" + i);
                values.add(i);
            }

            ObjectIntHashMap<String> map = ObjectIntHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);

            int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
            assertEquals(expectedCap, objectKeysLength(ObjectIntHashMap.class, map),
                    "keys table length wrong at n=" + n);
            assertTrue(n <= expectedCap / 2, "sizing does not clear 50% threshold at n=" + n);
            assertEquals(n, map.size());
        }
    }

    @Test
    public void objPrimBulkLoadExactMatchesPreSizedPutLoopLayout() throws Exception
    {
        int n = 20;
        FastList<CollidingKey> keys = new FastList<>();
        IntArrayList values = new IntArrayList();
        for (int i = 0; i < n; i++)
        {
            keys.add(new CollidingKey(i));   // all keys hash to the same bucket
            values.add(i);
        }

        ObjectIntHashMap<CollidingKey> pumped = ObjectIntHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);
        ObjectIntHashMap<CollidingKey> putLoop = new ObjectIntHashMap<>(n);
        for (int i = 0; i < n; i++)
        {
            putLoop.put(keys.get(i), values.get(i));
        }

        assertArrayEquals(objectKeys(ObjectIntHashMap.class, putLoop),
                objectKeys(ObjectIntHashMap.class, pumped));
    }

    @Test
    public void objPrimNullKeyIsSupported()
    {
        // Object-keyed maps store a null key via the NULL_KEY sentinel.
        FastList<String> keys = FastList.newListWith(null, "a");
        IntArrayList values = IntArrayList.newListWith(7, 8);
        ObjectIntHashMap<String> map = ObjectIntHashMap.bulkLoad(2, keys, values, DuplicatePolicy.ERROR);
        assertEquals(2, map.size());
        assertTrue(map.containsKey(null));
        assertEquals(7, map.get(null));
        assertEquals(8, map.get("a"));

        // a duplicated null key is caught like any other duplicate
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> ObjectIntHashMap.bulkLoad(
                        2, FastList.newListWith(null, null), IntArrayList.newListWith(1, 2), DuplicatePolicy.ERROR));
    }

    @Test
    public void objPrimDuplicateErrorAndIgnore()
    {
        FastList<String> keys = FastList.newListWith("a", "b", "a");
        IntArrayList values = IntArrayList.newListWith(10, 11, 99);
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> ObjectIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR));

        ObjectIntHashMap<String> map = ObjectIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.IGNORE);
        assertEquals(2, map.size());
        assertEquals(10, map.get("a"));   // first wins
        assertEquals(11, map.get("b"));
    }

    @Test
    public void objPrimEqualButNotIdenticalKeysAreDuplicates()
    {
        FastList<String> keys = FastList.newListWith("x", new String("x"));
        IntArrayList values = IntArrayList.newListWith(1, 2);
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> ObjectIntHashMap.bulkLoad(2, keys, values, DuplicatePolicy.ERROR));
    }

    @Test
    public void objPrimFloatKeysUseBoxedEqualsNotRawBits()
    {
        // Object-keyed maps compare with Float.equals: -0.0f != 0.0f and NaN
        // equals NaN. That is the boxed contract, NOT the primitive raw-bit one.
        FastList<Float> keys = FastList.newListWith(-0.0f, 0.0f, Float.NaN);
        IntArrayList values = IntArrayList.newListWith(1, 2, 3);
        ObjectIntHashMap<Float> map = ObjectIntHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR);
        assertEquals(3, map.size());
        assertEquals(1, map.get(-0.0f));
        assertEquals(2, map.get(0.0f));
        assertEquals(3, map.get(Float.NaN));

        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> ObjectIntHashMap.bulkLoad(
                        2, FastList.newListWith(Float.NaN, Float.NaN),
                        IntArrayList.newListWith(1, 2), DuplicatePolicy.ERROR));
    }

    @Test
    public void objPrimMismatchedLengthsAndOversizeAndBadHint()
    {
        FastList<String> keys = FastList.newListWith("a", "b", "c");
        assertThrows(IllegalArgumentException.class,
                () -> ObjectIntHashMap.bulkLoad(3, keys, IntArrayList.newListWith(1, 2), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectIntHashMap.bulkLoad(3, keys, IntArrayList.newListWith(1, 2, 3, 4), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectIntHashMap.bulkLoadExact(
                        2, keys, IntArrayList.newListWith(1, 2, 3), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectIntHashMap.bulkLoad(
                        (1 << 29) + 1, new FastList<String>(), new IntArrayList(), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectIntHashMap.bulkLoad(
                        -1, new FastList<String>(), new IntArrayList(), DuplicatePolicy.ERROR));
    }

    @Test
    public void objPrimEmptyBulkLoad()
    {
        ObjectIntHashMap<String> map = ObjectIntHashMap.bulkLoad(
                0, new FastList<String>(), new IntArrayList(), DuplicatePolicy.ERROR);
        assertTrue(map.isEmpty());
        assertEquals(new ObjectIntHashMap<String>(), map);
    }

    // ==================================================================
    // primitive -> boolean  (<name>BooleanHashMap)
    // ==================================================================

    @Test
    public void primBoolBulkLoadEqualsPutLoop()
    {
        int n = 1000;
        IntArrayList keys = new IntArrayList();
        BooleanArrayList values = new BooleanArrayList();
        IntBooleanHashMap putLoop = new IntBooleanHashMap();
        for (int i = 0; i < n; i++)
        {
            int k = i * 7 + 100;
            boolean v = (i % 3) == 0;
            keys.add(k);
            values.add(v);
            putLoop.put(k, v);
        }

        IntBooleanHashMap pumped = IntBooleanHashMap.bulkLoad(n, keys, values, DuplicatePolicy.ERROR);
        assertEquals(putLoop, pumped);
        assertEquals(putLoop.size(), pumped.size());
        for (int i = 0; i < n; i++)
        {
            assertEquals((i % 3) == 0, pumped.get(i * 7 + 100));
        }
    }

    @Test
    public void primBoolBothValuesRoundTrip()
    {
        IntArrayList keys = IntArrayList.newListWith(10, 11, 12);
        BooleanArrayList values = BooleanArrayList.newListWith(true, false, true);
        IntBooleanHashMap map = IntBooleanHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR);
        assertEquals(3, map.size());
        assertTrue(map.get(10));
        assertFalse(map.get(11));
        assertTrue(map.get(12));
        assertTrue(map.containsKey(11));   // present with value false
    }

    @Test
    public void primBoolBulkLoadExactZeroRehashAt3TimesPowerOfTwo() throws Exception
    {
        for (int k = 0; k <= 4; k++)
        {
            int n = 3 << k;
            IntArrayList keys = new IntArrayList();
            BooleanArrayList values = new BooleanArrayList();
            for (int i = 0; i < n; i++)
            {
                keys.add(100 + i);
                values.add((i & 1) == 0);
            }

            IntBooleanHashMap map = IntBooleanHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);

            int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
            assertEquals(expectedCap, primitiveKeysLength(IntBooleanHashMap.class, map),
                    "keys table length wrong at n=" + n);
            assertTrue(n <= expectedCap / 2, "sizing does not clear 50% threshold at n=" + n);
            assertEquals(n, map.size());
        }
    }

    @Test
    public void primBoolBulkLoadExactMatchesPreSizedPutLoopLayoutWithCollisions() throws Exception
    {
        int n = 20;
        int expectedCap = smallestPowerOfTwoGreaterThan(2 * n);
        // IntBooleanHashMap probes spreadAndMask(element) first, so the bucket is
        // chosen through the Fibonacci spread rather than the raw key.
        IntArrayList keys = keysSharingFirstProbe(n, expectedCap, true);
        BooleanArrayList values = new BooleanArrayList();
        for (int i = 0; i < n; i++)
        {
            values.add((i & 1) == 0);
        }

        IntBooleanHashMap pumped = IntBooleanHashMap.bulkLoadExact(n, keys, values, DuplicatePolicy.ERROR);
        IntBooleanHashMap putLoop = new IntBooleanHashMap(n);
        for (int i = 0; i < n; i++)
        {
            putLoop.put(keys.get(i), values.get(i));
        }

        // Layout-compatibility check; see the note on the primitive->object twin.
        assertArrayEquals(primitiveKeys(IntBooleanHashMap.class, putLoop),
                primitiveKeys(IntBooleanHashMap.class, pumped));
        assertEquals(putLoop, pumped);
    }

    @Test
    public void primBoolSentinelKeysSurvivePumpWithBothValues()
    {
        IntArrayList keys = IntArrayList.newListWith(0, 1, 2);
        BooleanArrayList values = BooleanArrayList.newListWith(false, true, false);
        IntBooleanHashMap map = IntBooleanHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR);
        assertEquals(3, map.size());
        assertTrue(map.containsKey(0));
        assertFalse(map.get(0));
        assertTrue(map.containsKey(1));
        assertTrue(map.get(1));
        assertFalse(map.get(2));

        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntBooleanHashMap.bulkLoad(
                        2, IntArrayList.newListWith(1, 1), BooleanArrayList.newListWith(false, true),
                        DuplicatePolicy.ERROR));
    }

    @Test
    public void primBoolDuplicateErrorAndIgnore()
    {
        IntArrayList keys = IntArrayList.newListWith(5, 6, 5);
        BooleanArrayList values = BooleanArrayList.newListWith(false, true, true);
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> IntBooleanHashMap.bulkLoad(3, keys, values, DuplicatePolicy.ERROR));

        IntBooleanHashMap map = IntBooleanHashMap.bulkLoad(3, keys, values, DuplicatePolicy.IGNORE);
        assertEquals(2, map.size());
        assertFalse(map.get(5));   // first value wins even though it is `false`
        assertTrue(map.get(6));
    }

    @Test
    public void primBoolMismatchedLengthsAndOversizeAndBadHint()
    {
        IntArrayList keys = IntArrayList.newListWith(1, 2, 3);
        assertThrows(IllegalArgumentException.class,
                () -> IntBooleanHashMap.bulkLoad(
                        3, keys, BooleanArrayList.newListWith(true, false), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntBooleanHashMap.bulkLoad(
                        3, keys, BooleanArrayList.newListWith(true, false, true, false), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntBooleanHashMap.bulkLoadExact(
                        2, keys, BooleanArrayList.newListWith(true, false, true), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntBooleanHashMap.bulkLoad(
                        (1 << 29) + 1, new IntArrayList(), new BooleanArrayList(), DuplicatePolicy.ERROR));
        assertThrows(IllegalArgumentException.class,
                () -> IntBooleanHashMap.bulkLoad(
                        -1, new IntArrayList(), new BooleanArrayList(), DuplicatePolicy.ERROR));
    }

    @Test
    public void primBoolEmptyBulkLoad()
    {
        IntBooleanHashMap map = IntBooleanHashMap.bulkLoad(
                0, new IntArrayList(), new BooleanArrayList(), DuplicatePolicy.ERROR);
        assertTrue(map.isEmpty());
        assertEquals(new IntBooleanHashMap(), map);
    }

    @Test
    public void primBoolFloatKeysPreserveRawBitIdentity()
    {
        float nan1 = Float.intBitsToFloat(0x7fc00000);
        float nan2 = Float.intBitsToFloat(0x7fc00001);
        FloatArrayList keys = FloatArrayList.newListWith(-0.0f, 0.0f, nan1, nan2);
        BooleanArrayList values = BooleanArrayList.newListWith(true, false, true, false);

        FloatBooleanHashMap map = FloatBooleanHashMap.bulkLoad(4, keys, values, DuplicatePolicy.ERROR);
        assertEquals(4, map.size());
        assertTrue(map.get(-0.0f));
        assertFalse(map.get(0.0f));
        assertTrue(map.get(nan1));
        assertFalse(map.get(nan2));
    }

    /**
     * {@code count} distinct int keys that all land on the SAME initial bucket of a
     * table of {@code capacity} slots, so every key after the first must probe.
     * The callers disagree about the first probe: {@code IntObjectHashMap} (like
     * {@code IntIntHashMap}) masks the raw key, while {@code IntBooleanHashMap}
     * masks the Fibonacci spread -- so the fixture mirrors whichever one is under
     * test ({@code spread}). Do not read this as a family-wide rule: the emitted
     * {@code Byte*} classes spread nothing in either family, and the other
     * object-valued classes mask an {@code int} cast of the key.
     */
    private static IntArrayList keysSharingFirstProbe(int count, int capacity, boolean spread)
    {
        IntArrayList keys = new IntArrayList();
        int bucket = -1;
        for (int k = 100; keys.size() < count; k++)
        {
            int slot = firstProbeSlot(k, capacity, spread);
            if (bucket < 0)
            {
                bucket = slot;
            }
            if (slot == bucket)
            {
                keys.add(k);
            }
        }
        for (int i = 0; i < keys.size(); i++)
        {
            assertEquals(bucket, firstProbeSlot(keys.get(i), capacity, spread),
                    "fixture key does not share the first probe slot");
        }
        return keys;
    }

    private static int firstProbeSlot(int key, int capacity, boolean spread)
    {
        return (spread ? Fibonacci.intSpreadOne(key) : key) & (capacity - 1);
    }

    /** Object key whose hash forces every instance into the same bucket. */
    private static final class CollidingKey
    {
        private final int id;

        CollidingKey(int id)
        {
            this.id = id;
        }

        @Override
        public int hashCode()
        {
            return 42;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof CollidingKey && ((CollidingKey) o).id == this.id;
        }
    }

    // ------------------------------------------------------------------
    // reflection / arithmetic helpers
    // ------------------------------------------------------------------

    private static int smallestPowerOfTwoGreaterThan(int n)
    {
        return n > 1 ? Integer.highestOneBit(n - 1) << 1 : 1;
    }

    private static int keysValuesLength(IntIntHashMap map) throws Exception
    {
        Field f = IntIntHashMap.class.getDeclaredField("keysValues");
        f.setAccessible(true);
        return ((int[]) f.get(map)).length;
    }

    private static int[] keysValues(IntIntHashMap map) throws Exception
    {
        Field f = IntIntHashMap.class.getDeclaredField("keysValues");
        f.setAccessible(true);
        return ((int[]) f.get(map)).clone();
    }

    private static int tableLength(IntHashSet set) throws Exception
    {
        Field f = IntHashSet.class.getDeclaredField("table");
        f.setAccessible(true);
        return ((int[]) f.get(set)).length;
    }

    private static int primitiveKeysLength(Class<?> cls, Object map) throws Exception
    {
        return java.lang.reflect.Array.getLength(rawKeys(cls, map));
    }

    private static int objectKeysLength(Class<?> cls, Object map) throws Exception
    {
        return ((Object[]) rawKeys(cls, map)).length;
    }

    private static int[] primitiveKeys(Class<?> cls, Object map) throws Exception
    {
        return ((int[]) rawKeys(cls, map)).clone();
    }

    private static Object[] objectKeys(Class<?> cls, Object map) throws Exception
    {
        return ((Object[]) rawKeys(cls, map)).clone();
    }

    private static Object rawKeys(Class<?> cls, Object map) throws Exception
    {
        Field f = cls.getDeclaredField("keys");
        f.setAccessible(true);
        return f.get(map);
    }
}

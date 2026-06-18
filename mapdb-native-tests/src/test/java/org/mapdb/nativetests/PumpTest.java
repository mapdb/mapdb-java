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

import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.multimap.list.MutableListMultimap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.Pump.DuplicatePolicy;
import org.mapdb.collections.impl.list.mutable.primitive.DoubleArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.FloatArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.IntArrayList;
import org.mapdb.collections.impl.bag.mutable.primitive.IntHashBag;
import org.mapdb.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.mapdb.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.mapdb.collections.impl.set.mutable.primitive.IntHashSet;
import org.mapdb.collections.impl.tuple.Tuples;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    public void mapBulkLoadExactRejectsOversizedSource()
    {
        IntArrayList keys = IntArrayList.newListWith(10, 11, 12, 13);
        IntArrayList values = IntArrayList.newListWith(0, 1, 2, 3);
        assertThrows(IllegalArgumentException.class,
                () -> IntIntHashMap.bulkLoadExact(3, keys, values, DuplicatePolicy.ERROR));
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

    private static int tableLength(IntHashSet set) throws Exception
    {
        Field f = IntHashSet.class.getDeclaredField("table");
        f.setAccessible(true);
        return ((int[]) f.get(set)).length;
    }
}

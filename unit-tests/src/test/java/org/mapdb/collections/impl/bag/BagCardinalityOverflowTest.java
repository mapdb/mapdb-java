/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.bag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.mapdb.collections.api.bag.Bag;
import org.mapdb.collections.api.bag.ImmutableBag;
import org.mapdb.collections.api.bag.MutableBag;
import org.mapdb.collections.api.bag.MutableBagIterable;
import org.mapdb.collections.api.bag.sorted.ImmutableSortedBag;
import org.mapdb.collections.api.factory.Bags;
import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.factory.SortedBags;
import org.mapdb.collections.impl.bag.immutable.ImmutableArrayBag;
import org.mapdb.collections.impl.bag.mutable.HashBag;
import org.mapdb.collections.impl.bag.mutable.MultiReaderHashBag;
import org.mapdb.collections.impl.bag.mutable.primitive.BooleanHashBag;
import org.mapdb.collections.impl.bag.sorted.mutable.TreeBag;
import org.mapdb.collections.impl.bag.strategy.mutable.HashBagWithHashingStrategy;
import org.mapdb.collections.impl.block.factory.HashingStrategies;
import org.mapdb.collections.impl.list.mutable.primitive.BooleanArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cardinality overflow (bags) -- spec algorithms.md: an add whose resulting
 * total size would exceed {@link Integer#MAX_VALUE} throws
 * {@link ArithmeticException} before any mutation and leaves the bag unchanged;
 * reaching exactly {@link Integer#MAX_VALUE} is allowed; a bulk add of known
 * size is checked as a whole. Counts are built with addOccurrences, so no test
 * allocates 2^31 elements.
 */
public class BagCardinalityOverflowTest
{
    private static final int MAX = Integer.MAX_VALUE;

    /**
     * Observable state: size, distinct size and every (item, count) entry in
     * iteration order. forEachWithOccurrences walks the entries, so this is the
     * bag's iteration without visiting 2^31 elements one by one.
     */
    private static <T> List<String> state(Bag<T> bag)
    {
        List<String> state = new ArrayList<>();
        state.add("size=" + bag.size());
        state.add("distinct=" + bag.sizeDistinct());
        long[] total = new long[1];
        bag.forEachWithOccurrences((each, count) ->
        {
            state.add(each + "=" + count);
            total[0] += count;
        });
        state.add("total=" + total[0]);
        return state;
    }

    private static void assertRefused(Bag<?> bag, List<String> expected, Runnable add)
    {
        assertThrows(ArithmeticException.class, add::run);
        assertEquals(expected, state(bag));
    }

    private static void checkMutableBag(Supplier<MutableBagIterable<String>> factory)
    {
        MutableBagIterable<String> bag = factory.get();
        assertEquals(MAX - 10, bag.addOccurrences("a", MAX - 10));
        assertEquals(5, bag.addOccurrences("b", 5));
        assertEquals(MAX - 5, bag.size());
        List<String> nearMax = state(bag);

        // single adds: existing value, new value, a count that alone overflows
        assertRefused(bag, nearMax, () -> bag.addOccurrences("a", 6));
        assertRefused(bag, nearMax, () -> bag.addOccurrences("c", 6));
        assertRefused(bag, nearMax, () -> bag.addOccurrences("c", MAX));
        assertFalse(bag.contains("c"));
        assertRefused(bag, nearMax, () -> bag.setOccurrences("b", 11));
        assertRefused(bag, nearMax, () -> bag.setOccurrences("c", 6));

        // bulk adds whose total is known: refused as a whole, nothing added
        MutableBag<String> six = HashBag.newBag();
        six.addOccurrences("c", 3);
        six.addOccurrences("d", 3);
        assertRefused(bag, nearMax, () -> bag.addAll(six));
        assertRefused(bag, nearMax, () -> bag.addAllIterable(six));
        assertRefused(bag, nearMax, () -> bag.withAll(six));
        assertRefused(bag, nearMax, () -> bag.addAll(Lists.mutable.with("c", "c", "c", "d", "d", "d")));
        assertRefused(bag, nearMax, () -> bag.addAllIterable(Lists.mutable.with("c", "d", "e", "f", "g", "h")));
        assertRefused(bag, nearMax, () -> bag.addAll(bag));
        assertFalse(bag.contains("c"));
        assertFalse(bag.contains("d"));

        // reaching exactly Integer.MAX_VALUE is allowed
        assertEquals(5, bag.addOccurrences("c", 5));
        assertEquals(MAX, bag.size());
        List<String> atMax = state(bag);
        assertRefused(bag, atMax, () -> bag.add("a"));
        assertRefused(bag, atMax, () -> bag.add("z"));
        assertRefused(bag, atMax, () -> bag.addOccurrences("c", 1));
        assertRefused(bag, atMax, () -> bag.addOccurrences("z", 1));
        assertRefused(bag, atMax, () -> bag.setOccurrences("b", 6));
        assertRefused(bag, atMax, () -> bag.addAll(Lists.mutable.with("z")));
        assertFalse(bag.contains("z"));
        assertEquals(MAX - 10, bag.addOccurrences("a", 0));
        assertFalse(bag.setOccurrences("a", MAX - 10));
        assertEquals(atMax, state(bag));

        // shrinking frees room again
        assertTrue(bag.setOccurrences("a", MAX - 11));
        assertTrue(bag.add("z"));
        assertEquals(MAX, bag.size());
        assertEquals(1, bag.occurrencesOf("z"));

        // an Iterable of unknown length is checked per element: the elements
        // before the overflowing one are added, then the add throws
        MutableBagIterable<String> lazyTarget = factory.get();
        lazyTarget.addOccurrences("a", MAX - 2);
        assertThrows(
                ArithmeticException.class,
                () -> lazyTarget.addAllIterable(Lists.mutable.with("x", "y", "z").asLazy()));
        assertEquals(MAX, lazyTarget.size());
        assertFalse(lazyTarget.contains("z"));
    }

    @Test
    public void hashBag()
    {
        checkMutableBag(HashBag::new);
    }

    @Test
    public void hashBagWithHashingStrategy()
    {
        checkMutableBag(() -> HashBagWithHashingStrategy.newBag(HashingStrategies.defaultStrategy()));
    }

    @Test
    public void treeBag()
    {
        checkMutableBag(TreeBag::new);
    }

    @Test
    public void multiReaderHashBag()
    {
        checkMutableBag(MultiReaderHashBag::newBag);
    }

    @Test
    public void synchronizedBags()
    {
        checkMutableBag(() -> HashBag.<String>newBag().asSynchronized());
        checkMutableBag(() -> TreeBag.<String>newBag().asSynchronized());
    }

    @Test
    public void hashBagCopyRefusesOversizedSourceBag()
    {
        MutableBag<String> source = HashBag.newBag();
        source.addOccurrences("a", MAX);
        MutableBag<String> target = HashBag.newBagWith("b");
        List<String> before = state(target);
        assertRefused(target, before, () -> target.addAll(source));
        assertEquals(source.size(), HashBag.newBag(source).size());
    }

    @Test
    public void immutableBagNewWith()
    {
        MutableBag<String> nearMax = HashBag.newBag();
        nearMax.addOccurrences("a", MAX - 1);
        ImmutableBag<String> array = Bags.immutable.withAll(nearMax);
        assertInstanceOf(ImmutableArrayBag.class, array);
        ImmutableBag<String> atMax = array.newWith("b");
        assertEquals(MAX, atMax.size());
        assertThrows(ArithmeticException.class, () -> atMax.newWith("a"));
        assertThrows(ArithmeticException.class, () -> atMax.newWith("c"));
        assertThrows(ArithmeticException.class, () -> atMax.newWithAll(Lists.mutable.with("c")));
        assertEquals(MAX, atMax.size());

        TreeBag<String> sortedNearMax = TreeBag.newBag();
        sortedNearMax.addOccurrences("a", MAX - 1);
        ImmutableSortedBag<String> sorted = SortedBags.immutable.withAll(sortedNearMax);
        assertEquals("ImmutableSortedBagImpl", sorted.getClass().getSimpleName());
        ImmutableSortedBag<String> sortedAtMax = sorted.newWith("a");
        assertEquals(MAX, sortedAtMax.size());
        assertThrows(ArithmeticException.class, () -> sortedAtMax.newWith("a"));
        assertThrows(ArithmeticException.class, () -> sortedAtMax.newWith("b"));
        assertThrows(ArithmeticException.class, () -> sortedAtMax.newWithAll(Lists.mutable.with("b")));
        assertEquals(MAX, sortedAtMax.size());
    }

    @Test
    public void booleanHashBag()
    {
        BooleanHashBag bag = new BooleanHashBag();
        bag.addOccurrences(true, MAX - 10);
        bag.addOccurrences(false, 5);
        assertEquals(MAX - 5, bag.size());
        String nearMax = bag.toStringOfItemToCount();

        assertThrows(ArithmeticException.class, () -> bag.addOccurrences(true, 6));
        assertThrows(ArithmeticException.class, () -> bag.addOccurrences(false, 6));
        assertThrows(ArithmeticException.class, () -> bag.addOccurrences(false, MAX));
        BooleanHashBag six = new BooleanHashBag();
        six.addOccurrences(true, 3);
        six.addOccurrences(false, 3);
        assertThrows(ArithmeticException.class, () -> bag.addAll(six));
        assertThrows(ArithmeticException.class, () -> bag.withAll(six));
        assertThrows(ArithmeticException.class, () -> bag.addAll(true, true, true, false, false, false));
        assertThrows(ArithmeticException.class, () -> bag.addAll(BooleanArrayList.newListWith(true, true, true, false, false, false)));
        assertThrows(ArithmeticException.class, () -> bag.addAll(bag));
        assertEquals(MAX - 5, bag.size());
        assertEquals(MAX - 10, bag.occurrencesOf(true));
        assertEquals(5, bag.occurrencesOf(false));
        assertEquals(nearMax, bag.toStringOfItemToCount());

        bag.addOccurrences(false, 5);
        assertEquals(MAX, bag.size());
        assertThrows(ArithmeticException.class, () -> bag.add(true));
        assertThrows(ArithmeticException.class, () -> bag.add(false));
        assertThrows(ArithmeticException.class, () -> bag.addOccurrences(true, 1));
        assertEquals(MAX, bag.size());
        assertEquals(MAX - 10, bag.occurrencesOf(true));
        assertEquals(10, bag.occurrencesOf(false));
        bag.addOccurrences(true, 0);
        assertSame(bag, bag.withAll(new BooleanHashBag()));
        assertEquals(MAX, bag.size());
    }
}

/*
 * Copyright (c) 2024 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.test.bag.mutable.sorted;

import org.mapdb.collections.api.bag.MutableBagIterable;
import org.mapdb.collections.api.factory.Bags;
import org.mapdb.collections.test.collection.mutable.MutableCollectionTestCase;
import org.junit.jupiter.api.Test;

import static org.mapdb.collections.test.IterableTestCase.assertIterablesEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public interface MutableBagIterableTestCase extends MutableCollectionTestCase
{
    @Override
    <T> MutableBagIterable<T> newWith(T... elements);

    @Test
    default void MutableBagIterable_addOccurrences()
    {
        MutableBagIterable<Integer> mutableBag = this.newWith(1, 2, 2, 3, 3, 3);
        assertEquals(4, mutableBag.addOccurrences(4, 4));
        assertIterablesEqual(Bags.immutable.with(1, 2, 2, 3, 3, 3, 4, 4, 4, 4), mutableBag);
        assertEquals(3, mutableBag.addOccurrences(1, 2));
        assertIterablesEqual(Bags.immutable.with(1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 4), mutableBag);

        assertThrows(
                IllegalArgumentException.class,
                () -> mutableBag.addOccurrences(4, -1));
        assertEquals(Bags.immutable.with(1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 4), mutableBag);

        // Cardinality overflow (spec algorithms.md): refused before mutation, not wrapped
        assertThrows(
                ArithmeticException.class,
                () -> mutableBag.addOccurrences(3, Integer.MAX_VALUE - 1));
        assertEquals(Bags.immutable.with(1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 4), mutableBag);
        assertEquals(12, mutableBag.size());

        // reaching exactly Integer.MAX_VALUE is allowed
        assertEquals(Integer.MAX_VALUE - 9, mutableBag.addOccurrences(3, Integer.MAX_VALUE - 12));
        assertEquals(Integer.MAX_VALUE, mutableBag.size());
        assertThrows(ArithmeticException.class, () -> mutableBag.addOccurrences(5, 1));
        assertEquals(Integer.MAX_VALUE, mutableBag.size());
        assertFalse(mutableBag.contains(5));
    }

    @Test
    default void MutableBagIterable_removeOccurrences()
    {
        MutableBagIterable<Integer> mutableBag = this.newWith(1, 2, 2, 3, 3, 3);
        assertFalse(mutableBag.removeOccurrences(4, 4));
        assertIterablesEqual(Bags.immutable.with(1, 2, 2, 3, 3, 3), mutableBag);
        assertTrue(mutableBag.removeOccurrences(1, 2));
        assertIterablesEqual(Bags.immutable.with(2, 2, 3, 3, 3), mutableBag);
        assertTrue(mutableBag.removeOccurrences(3, 2));
        assertIterablesEqual(Bags.immutable.with(2, 2, 3), mutableBag);
        assertTrue(mutableBag.removeOccurrences(2, 2));
        assertIterablesEqual(Bags.immutable.with(3), mutableBag);

        assertThrows(
                IllegalArgumentException.class,
                () -> mutableBag.removeOccurrences(4, -1));
    }
}

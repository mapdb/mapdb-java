/*
 * Copyright (c) 2024 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.bag.mutable;

import org.mapdb.collections.api.bag.MutableBag;
import org.mapdb.collections.api.collection.MutableCollection;
import org.mapdb.collections.api.factory.Sets;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.impl.collection.mutable.UnmodifiableMutableCollectionTestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiReaderHashBagAsUnmodifiableTest extends UnmodifiableMutableCollectionTestCase<Integer>
{
    @Override
    protected MutableCollection<Integer> getCollection()
    {
        return MultiReaderHashBag.newBagWith(2, 2).asUnmodifiable();
    }

    @Test
    public void selectUnique()
    {
        MutableBag<String> bag = MultiReaderHashBag.newBagWith("0", "1", "1", "1", "1", "2", "2", "2", "3", "3", "4", "5").asUnmodifiable();
        MutableSet<String> expected = Sets.mutable.with("0", "4", "5");
        MutableSet<String> actual = bag.selectUnique();
        assertEquals(expected, actual);
    }

    @Override
    @Test
    public void iteratorRemove()
    {
        // Not applicable
    }
}

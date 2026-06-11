/*
 * Copyright (c) 2022 The Bank of New York Mellon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.partition.set;

import org.mapdb.collections.api.factory.Sets;
import org.mapdb.collections.api.partition.set.PartitionImmutableSet;
import org.mapdb.collections.api.partition.set.PartitionMutableSet;
import org.mapdb.collections.api.set.MutableSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PartitionUnifiedSetTest
{
    @Test
    public void toImmutable()
    {
        PartitionMutableSet<Integer> partitionMutableSet = new PartitionUnifiedSet<>();
        MutableSet<Integer> selected = Sets.mutable.of(1, 2, 3);
        MutableSet<Integer> rejected = Sets.mutable.of(4, 5, 6);
        partitionMutableSet.getSelected().addAll(selected);
        partitionMutableSet.getRejected().addAll(rejected);
        PartitionImmutableSet<Integer> integerPartitionImmutableSet = partitionMutableSet.toImmutable();
        assertEquals(selected, integerPartitionImmutableSet.getSelected());
        assertEquals(rejected, integerPartitionImmutableSet.getRejected());
    }
}

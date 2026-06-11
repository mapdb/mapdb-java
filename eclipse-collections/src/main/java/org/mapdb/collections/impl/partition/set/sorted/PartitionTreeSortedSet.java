/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.partition.set.sorted;

import java.util.Comparator;

import org.mapdb.collections.api.partition.set.sorted.PartitionImmutableSortedSet;
import org.mapdb.collections.api.partition.set.sorted.PartitionMutableSortedSet;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;

public class PartitionTreeSortedSet<T> implements PartitionMutableSortedSet<T>
{
    private final MutableSortedSet<T> selected;
    private final MutableSortedSet<T> rejected;

    public PartitionTreeSortedSet(Comparator<? super T> comparator)
    {
        this.selected = TreeSortedSet.newSet(comparator);
        this.rejected = TreeSortedSet.newSet(comparator);
    }

    @Override
    public MutableSortedSet<T> getSelected()
    {
        return this.selected;
    }

    @Override
    public MutableSortedSet<T> getRejected()
    {
        return this.rejected;
    }

    @Override
    public PartitionImmutableSortedSet<T> toImmutable()
    {
        return new PartitionImmutableSortedSetImpl<>(this);
    }
}

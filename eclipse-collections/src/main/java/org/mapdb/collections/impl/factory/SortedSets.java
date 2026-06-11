/*
 * Copyright (c) 2021 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.factory;

import java.util.SortedSet;

import org.mapdb.collections.api.factory.set.sorted.ImmutableSortedSetFactory;
import org.mapdb.collections.api.factory.set.sorted.MutableSortedSetFactory;
import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.impl.set.sorted.immutable.ImmutableSortedSetFactoryImpl;
import org.mapdb.collections.impl.set.sorted.mutable.MutableSortedSetFactoryImpl;
import org.mapdb.collections.impl.set.sorted.mutable.SortedSetAdapter;

@SuppressWarnings("ConstantNamingConvention")
public final class SortedSets
{
    public static final ImmutableSortedSetFactory immutable = ImmutableSortedSetFactoryImpl.INSTANCE;
    public static final MutableSortedSetFactory mutable = MutableSortedSetFactoryImpl.INSTANCE;

    private SortedSets()
    {
        throw new AssertionError("Suppress default constructor for noninstantiability");
    }

    /**
     * @since 9.0.
     */
    public static <T> MutableSortedSet<T> adapt(SortedSet<T> list)
    {
        return SortedSetAdapter.adapt(list);
    }
}

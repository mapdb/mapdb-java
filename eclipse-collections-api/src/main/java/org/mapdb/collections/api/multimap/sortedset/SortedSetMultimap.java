/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.multimap.sortedset;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.function.Function2;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.multimap.bag.BagMultimap;
import org.mapdb.collections.api.multimap.list.ListMultimap;
import org.mapdb.collections.api.multimap.ordered.ReversibleIterableMultimap;
import org.mapdb.collections.api.multimap.ordered.SortedIterableMultimap;
import org.mapdb.collections.api.multimap.set.SetMultimap;
import org.mapdb.collections.api.set.sorted.SortedSetIterable;
import org.mapdb.collections.api.tuple.Pair;

/**
 * @since 1.0
 */
public interface SortedSetMultimap<K, V>
        extends SetMultimap<K, V>, SortedIterableMultimap<K, V>, ReversibleIterableMultimap<K, V>
{
    @Override
    SortedSetMultimap<K, V> newEmpty();

    @Override
    SortedSetIterable<V> get(K key);

    @Override
    MutableSortedSetMultimap<K, V> toMutable();

    @Override
    ImmutableSortedSetMultimap<K, V> toImmutable();

    @Override
    SortedSetMultimap<K, V> selectKeysValues(Predicate2<? super K, ? super V> predicate);

    @Override
    SortedSetMultimap<K, V> rejectKeysValues(Predicate2<? super K, ? super V> predicate);

    @Override
    SortedSetMultimap<K, V> selectKeysMultiValues(Predicate2<? super K, ? super RichIterable<V>> predicate);

    @Override
    SortedSetMultimap<K, V> rejectKeysMultiValues(Predicate2<? super K, ? super RichIterable<V>> predicate);

    @Override
    <K2, V2> BagMultimap<K2, V2> collectKeysValues(Function2<? super K, ? super V, Pair<K2, V2>> function);

    @Override
    <K2, V2> BagMultimap<K2, V2> collectKeyMultiValues(Function<? super K, ? extends K2> keyFunction, Function<? super V, ? extends V2> valueFunction);

    @Override
    <V2> ListMultimap<K, V2> collectValues(Function<? super V, ? extends V2> function);
}

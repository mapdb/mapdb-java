/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.multimap.ordered;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.ordered.ReversibleIterable;

public interface ReversibleIterableMultimap<K, V>
        extends OrderedIterableMultimap<K, V>
{
    @Override
    ReversibleIterableMultimap<K, V> newEmpty();

    @Override
    ReversibleIterable<V> get(K key);

    @Override
    ReversibleIterableMultimap<K, V> selectKeysValues(Predicate2<? super K, ? super V> predicate);

    @Override
    ReversibleIterableMultimap<K, V> rejectKeysValues(Predicate2<? super K, ? super V> predicate);

    @Override
    ReversibleIterableMultimap<K, V> selectKeysMultiValues(Predicate2<? super K, ? super RichIterable<V>> predicate);

    @Override
    ReversibleIterableMultimap<K, V> rejectKeysMultiValues(Predicate2<? super K, ? super RichIterable<V>> predicate);

    @Override
    <V2> ReversibleIterableMultimap<K, V2> collectValues(Function<? super V, ? extends V2> function);
}

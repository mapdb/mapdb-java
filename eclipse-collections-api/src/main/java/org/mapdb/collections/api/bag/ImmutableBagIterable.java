/*
 * Copyright (c) 2021 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.bag;

import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.function.primitive.ObjectIntToObjectFunction;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.block.predicate.primitive.IntPredicate;
import org.mapdb.collections.api.block.procedure.Procedure;
import org.mapdb.collections.api.collection.ImmutableCollection;
import org.mapdb.collections.api.map.MutableMapIterable;
import org.mapdb.collections.api.multimap.bag.ImmutableBagIterableMultimap;
import org.mapdb.collections.api.partition.bag.PartitionImmutableBagIterable;
import org.mapdb.collections.api.set.ImmutableSetIterable;
import org.mapdb.collections.api.tuple.Pair;

public interface ImmutableBagIterable<T> extends Bag<T>, ImmutableCollection<T>
{
    @Override
    ImmutableBagIterable<T> tap(Procedure<? super T> procedure);

    @Override
    ImmutableBagIterable<T> select(Predicate<? super T> predicate);

    @Override
    <P> ImmutableBagIterable<T> selectWith(Predicate2<? super T, ? super P> predicate, P parameter);

    @Override
    ImmutableBagIterable<T> reject(Predicate<? super T> predicate);

    @Override
    <P> ImmutableBagIterable<T> rejectWith(Predicate2<? super T, ? super P> predicate, P parameter);

    @Override
    PartitionImmutableBagIterable<T> partition(Predicate<? super T> predicate);

    @Override
    <P> PartitionImmutableBagIterable<T> partitionWith(Predicate2<? super T, ? super P> predicate, P parameter);

    @Override
    <S> ImmutableBagIterable<S> selectInstancesOf(Class<S> clazz);

    @Override
    <V> ImmutableBagIterableMultimap<V, T> groupBy(Function<? super T, ? extends V> function);

    @Override
    <V> ImmutableBagIterableMultimap<V, T> groupByEach(Function<? super T, ? extends Iterable<V>> function);

    @Override
    ImmutableSetIterable<Pair<T, Integer>> zipWithIndex();

    @Override
    ImmutableBagIterable<T> selectByOccurrences(IntPredicate predicate);

    /**
     * @since 9.2
     */
    @Override
    default ImmutableBagIterable<T> selectDuplicates()
    {
        return this.selectByOccurrences(occurrences -> occurrences > 1);
    }

    /**
     * @since 9.2
     */
    @Override
    ImmutableSetIterable<T> selectUnique();

    @Override
    MutableMapIterable<T, Integer> toMapOfItemToCount();

    @Override
    <V> ImmutableCollection<V> collectWithOccurrences(ObjectIntToObjectFunction<? super T, ? extends V> function);
}

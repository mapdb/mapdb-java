/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.bag;

import org.mapdb.collections.api.annotation.Beta;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.function.Function2;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.multimap.bag.UnsortedBagMultimap;
import org.mapdb.collections.api.set.ParallelUnsortedSetIterable;

/**
 * @since 5.0
 */
@Beta
public interface ParallelUnsortedBag<T> extends ParallelBag<T>
{
    @Override
    ParallelUnsortedSetIterable<T> asUnique();

    /**
     * Creates a parallel iterable for selecting elements from the current iterable.
     */
    @Override
    ParallelUnsortedBag<T> select(Predicate<? super T> predicate);

    @Override
    <P> ParallelUnsortedBag<T> selectWith(Predicate2<? super T, ? super P> predicate, P parameter);

    /**
     * Creates a parallel iterable for rejecting elements from the current iterable.
     */
    @Override
    ParallelUnsortedBag<T> reject(Predicate<? super T> predicate);

    @Override
    <P> ParallelUnsortedBag<T> rejectWith(Predicate2<? super T, ? super P> predicate, P parameter);

    @Override
    <S> ParallelUnsortedBag<S> selectInstancesOf(Class<S> clazz);

    /**
     * Creates a parallel iterable for collecting elements from the current iterable.
     */
    @Override
    <V> ParallelUnsortedBag<V> collect(Function<? super T, ? extends V> function);

    @Override
    <P, V> ParallelUnsortedBag<V> collectWith(Function2<? super T, ? super P, ? extends V> function, P parameter);

    /**
     * Creates a parallel iterable for selecting and collecting elements from the current iterable.
     */
    @Override
    <V> ParallelUnsortedBag<V> collectIf(Predicate<? super T> predicate, Function<? super T, ? extends V> function);

    /**
     * Creates a parallel flattening iterable for the current iterable.
     */
    @Override
    <V> ParallelUnsortedBag<V> flatCollect(Function<? super T, ? extends Iterable<V>> function);

    @Override
    <V> UnsortedBagMultimap<V, T> groupBy(Function<? super T, ? extends V> function);

    @Override
    <V> UnsortedBagMultimap<V, T> groupByEach(Function<? super T, ? extends Iterable<V>> function);
}

/*
 * Copyright (c) 2024 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.map.primitive;

import org.mapdb.collections.api.bag.MutableBag;
import org.mapdb.collections.api.bag.primitive.MutableBooleanBag;
import org.mapdb.collections.api.bag.primitive.MutableByteBag;
import org.mapdb.collections.api.bag.primitive.MutableCharBag;
import org.mapdb.collections.api.bag.primitive.MutableDoubleBag;
import org.mapdb.collections.api.bag.primitive.MutableFloatBag;
import org.mapdb.collections.api.bag.primitive.MutableIntBag;
import org.mapdb.collections.api.bag.primitive.MutableLongBag;
import org.mapdb.collections.api.bag.primitive.MutableShortBag;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.function.Function0;
import org.mapdb.collections.api.block.function.Function2;
import org.mapdb.collections.api.block.function.primitive.BooleanFunction;
import org.mapdb.collections.api.block.function.primitive.ByteFunction;
import org.mapdb.collections.api.block.function.primitive.CharFunction;
import org.mapdb.collections.api.block.function.primitive.DoubleFunction;
import org.mapdb.collections.api.block.function.primitive.FloatFunction;
import org.mapdb.collections.api.block.function.primitive.IntFunction;
import org.mapdb.collections.api.block.function.primitive.LongFunction;
import org.mapdb.collections.api.block.function.primitive.ShortFunction;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.block.procedure.Procedure2;
import org.mapdb.collections.api.factory.Maps;
import org.mapdb.collections.api.map.MutableMap;
import org.mapdb.collections.api.multimap.bag.MutableBagMultimap;
import org.mapdb.collections.api.ordered.OrderedIterable;
import org.mapdb.collections.api.partition.bag.PartitionMutableBag;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.api.tuple.Pair;

public interface MutablePrimitiveObjectMap<V> extends PrimitiveObjectMap<V>
{
    void clear();

    @Override
    <K, VV> MutableMap<K, VV> aggregateInPlaceBy(Function<? super V, ? extends K> groupBy, Function0<? extends VV> zeroValueFactory, Procedure2<? super VV, ? super V> mutatingAggregator);

    @Override
    default <K, VV> MutableMap<K, VV> aggregateBy(Function<? super V, ? extends K> groupBy, Function0<? extends VV> zeroValueFactory, Function2<? super VV, ? super V, ? extends VV> nonMutatingAggregator)
    {
        return this.aggregateBy(
                groupBy,
                zeroValueFactory,
                nonMutatingAggregator,
                Maps.mutable.empty());
    }

    @Override
    default <K> MutableMap<K, V> reduceBy(
            Function<? super V, ? extends K> groupBy,
            Function2<? super V, ? super V, ? extends V> reduceFunction)
    {
        return this.reduceBy(groupBy, reduceFunction, Maps.mutable.empty());
    }

    @Override
    <VV> MutableBagMultimap<VV, V> groupByEach(Function<? super V, ? extends Iterable<VV>> function);

    @Override
    <VV> MutableBagMultimap<VV, V> groupBy(Function<? super V, ? extends VV> function);

    @Override
    default <VV> MutableMap<VV, V> groupByUniqueKey(Function<? super V, ? extends VV> function)
    {
        return this.groupByUniqueKey(function, Maps.mutable.withInitialCapacity(this.size()));
    }

    @Override
    <VV> MutableBag<VV> collectIf(Predicate<? super V> predicate, Function<? super V, ? extends VV> function);

    @Override
    <VV> MutableBag<VV> collect(Function<? super V, ? extends VV> function);

    @Override
    MutableBooleanBag collectBoolean(BooleanFunction<? super V> booleanFunction);

    @Override
    MutableByteBag collectByte(ByteFunction<? super V> byteFunction);

    @Override
    MutableCharBag collectChar(CharFunction<? super V> charFunction);

    @Override
    MutableDoubleBag collectDouble(DoubleFunction<? super V> doubleFunction);

    @Override
    MutableFloatBag collectFloat(FloatFunction<? super V> floatFunction);

    @Override
    MutableIntBag collectInt(IntFunction<? super V> intFunction);

    @Override
    MutableLongBag collectLong(LongFunction<? super V> longFunction);

    @Override
    MutableShortBag collectShort(ShortFunction<? super V> shortFunction);

    @Override
    <P, VV> MutableBag<VV> collectWith(Function2<? super V, ? super P, ? extends VV> function, P parameter);

    @Override
    <VV> MutableBag<VV> flatCollect(Function<? super V, ? extends Iterable<VV>> function);

    /**
     * @since 9.2
     */
    @Override
    default <P, VV> MutableBag<VV> flatCollectWith(Function2<? super V, ? super P, ? extends Iterable<VV>> function, P parameter)
    {
        return this.flatCollect(each -> function.apply(each, parameter));
    }

    @Override
    <S> MutableBag<S> selectInstancesOf(Class<S> clazz);

    @Override
    MutableBag<V> select(Predicate<? super V> predicate);

    @Override
    <P> MutableBag<V> selectWith(Predicate2<? super V, ? super P> predicate, P parameter);

    @Override
    MutableBag<V> reject(Predicate<? super V> predicate);

    @Override
    <P> MutableBag<V> rejectWith(Predicate2<? super V, ? super P> predicate, P parameter);

    @Override
    PartitionMutableBag<V> partition(Predicate<? super V> predicate);

    @Override
    <P> PartitionMutableBag<V> partitionWith(Predicate2<? super V, ? super P> predicate, P parameter);

    /**
     * @deprecated in 7.0. Use {@link OrderedIterable#zip(Iterable)} instead.
     */
    @Override
    @Deprecated
    <S> MutableBag<Pair<V, S>> zip(Iterable<S> that);

    /**
     * @deprecated in 7.0. Use {@link OrderedIterable#zipWithIndex()} instead.
     */
    @Override
    @Deprecated
    MutableSet<Pair<V, Integer>> zipWithIndex();

    @Override
    <VV> MutableObjectLongMap<VV> sumByInt(Function<? super V, ? extends VV> groupBy, IntFunction<? super V> function);

    @Override
    <VV> MutableObjectDoubleMap<VV> sumByFloat(Function<? super V, ? extends VV> groupBy, FloatFunction<? super V> function);

    @Override
    <VV> MutableObjectLongMap<VV> sumByLong(Function<? super V, ? extends VV> groupBy, LongFunction<? super V> function);

    @Override
    <VV> MutableObjectDoubleMap<VV> sumByDouble(Function<? super V, ? extends VV> groupBy, DoubleFunction<? super V> function);
}

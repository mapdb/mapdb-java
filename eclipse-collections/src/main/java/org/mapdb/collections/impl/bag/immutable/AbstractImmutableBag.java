/*
 * Copyright (c) 2022 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.bag.immutable;

import java.util.Iterator;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.bag.ImmutableBag;
import org.mapdb.collections.api.bag.MutableBag;
import org.mapdb.collections.api.bag.primitive.ImmutableBooleanBag;
import org.mapdb.collections.api.bag.primitive.ImmutableByteBag;
import org.mapdb.collections.api.bag.primitive.ImmutableCharBag;
import org.mapdb.collections.api.bag.primitive.ImmutableDoubleBag;
import org.mapdb.collections.api.bag.primitive.ImmutableFloatBag;
import org.mapdb.collections.api.bag.primitive.ImmutableIntBag;
import org.mapdb.collections.api.bag.primitive.ImmutableLongBag;
import org.mapdb.collections.api.bag.primitive.ImmutableShortBag;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.function.Function2;
import org.mapdb.collections.api.block.function.primitive.BooleanFunction;
import org.mapdb.collections.api.block.function.primitive.ByteFunction;
import org.mapdb.collections.api.block.function.primitive.CharFunction;
import org.mapdb.collections.api.block.function.primitive.DoubleFunction;
import org.mapdb.collections.api.block.function.primitive.FloatFunction;
import org.mapdb.collections.api.block.function.primitive.IntFunction;
import org.mapdb.collections.api.block.function.primitive.LongFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectIntToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ShortFunction;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.predicate.Predicate2;
import org.mapdb.collections.api.block.procedure.Procedure;
import org.mapdb.collections.api.collection.MutableCollection;
import org.mapdb.collections.api.factory.Bags;
import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.factory.primitive.BooleanBags;
import org.mapdb.collections.api.factory.primitive.ByteBags;
import org.mapdb.collections.api.factory.primitive.CharBags;
import org.mapdb.collections.api.factory.primitive.DoubleBags;
import org.mapdb.collections.api.factory.primitive.FloatBags;
import org.mapdb.collections.api.factory.primitive.IntBags;
import org.mapdb.collections.api.factory.primitive.LongBags;
import org.mapdb.collections.api.factory.primitive.ShortBags;
import org.mapdb.collections.api.list.ImmutableList;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.map.ImmutableMap;
import org.mapdb.collections.api.partition.bag.PartitionImmutableBag;
import org.mapdb.collections.api.partition.bag.PartitionMutableBag;
import org.mapdb.collections.api.tuple.primitive.ObjectIntPair;
import org.mapdb.collections.impl.block.factory.Functions;
import org.mapdb.collections.impl.block.factory.Predicates;
import org.mapdb.collections.impl.map.mutable.UnifiedMap;
import org.mapdb.collections.impl.partition.bag.PartitionHashBag;

/**
 * @since 1.0
 */
public abstract class AbstractImmutableBag<T>
        extends AbstractImmutableBagIterable<T>
        implements ImmutableBag<T>
{
    @Override
    public ImmutableBag<T> newWithoutAll(Iterable<? extends T> elements)
    {
        return this.reject(Predicates.in(elements));
    }

    @Override
    public ImmutableBag<T> toImmutable()
    {
        return this;
    }

    @Override
    public ImmutableBag<T> tap(Procedure<? super T> procedure)
    {
        this.forEach(procedure);
        return this;
    }

    @Override
    public <P> ImmutableBag<T> selectWith(Predicate2<? super T, ? super P> predicate, P parameter)
    {
        return this.select(Predicates.bind(predicate, parameter));
    }

    @Override
    public <P> ImmutableBag<T> rejectWith(Predicate2<? super T, ? super P> predicate, P parameter)
    {
        return this.reject(Predicates.bind(predicate, parameter));
    }

    @Override
    public PartitionImmutableBag<T> partition(Predicate<? super T> predicate)
    {
        PartitionMutableBag<T> partitionMutableBag = new PartitionHashBag<>();
        this.forEachWithOccurrences((each, occurrences) -> {
            MutableBag<T> bucket = predicate.accept(each)
                    ? partitionMutableBag.getSelected()
                    : partitionMutableBag.getRejected();
            bucket.addOccurrences(each, occurrences);
        });
        return partitionMutableBag.toImmutable();
    }

    @Override
    public <P> PartitionImmutableBag<T> partitionWith(Predicate2<? super T, ? super P> predicate, P parameter)
    {
        PartitionMutableBag<T> partitionMutableBag = new PartitionHashBag<>();
        this.forEachWithOccurrences((each, occurrences) -> {
            MutableBag<T> bucket = predicate.accept(each, parameter)
                    ? partitionMutableBag.getSelected()
                    : partitionMutableBag.getRejected();
            bucket.addOccurrences(each, occurrences);
        });
        return partitionMutableBag.toImmutable();
    }

    /**
     * @since 9.0
     */
    @Override
    public <V> ImmutableBag<V> countBy(Function<? super T, ? extends V> function)
    {
        return this.collect(function);
    }

    /**
     * @since 9.0
     */
    @Override
    public <V, P> ImmutableBag<V> countByWith(Function2<? super T, ? super P, ? extends V> function, P parameter)
    {
        return this.collectWith(function, parameter);
    }

    /**
     * @since 10.0.0
     */
    @Override
    public <V> ImmutableBag<V> countByEach(Function<? super T, ? extends Iterable<V>> function)
    {
        return this.flatCollect(function);
    }

    @Override
    public <V> ImmutableBag<V> collectWithOccurrences(ObjectIntToObjectFunction<? super T, ? extends V> function)
    {
        return this.collectWithOccurrences(function, Bags.mutable.<V>empty()).toImmutable();
    }

    @Override
    public <P, V> ImmutableBag<V> collectWith(Function2<? super T, ? super P, ? extends V> function, P parameter)
    {
        return this.collect(Functions.bind(function, parameter));
    }

    @Override
    public ImmutableBooleanBag collectBoolean(BooleanFunction<? super T> booleanFunction)
    {
        return this.collectBoolean(booleanFunction, BooleanBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableByteBag collectByte(ByteFunction<? super T> byteFunction)
    {
        return this.collectByte(byteFunction, ByteBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableCharBag collectChar(CharFunction<? super T> charFunction)
    {
        return this.collectChar(charFunction, CharBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableDoubleBag collectDouble(DoubleFunction<? super T> doubleFunction)
    {
        return this.collectDouble(doubleFunction, DoubleBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableFloatBag collectFloat(FloatFunction<? super T> floatFunction)
    {
        return this.collectFloat(floatFunction, FloatBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableIntBag collectInt(IntFunction<? super T> intFunction)
    {
        return this.collectInt(intFunction, IntBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableLongBag collectLong(LongFunction<? super T> longFunction)
    {
        return this.collectLong(longFunction, LongBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableShortBag collectShort(ShortFunction<? super T> shortFunction)
    {
        return this.collectShort(shortFunction, ShortBags.mutable.empty()).toImmutable();
    }

    @Override
    public ImmutableList<ObjectIntPair<T>> topOccurrences(int n)
    {
        MutableList<ObjectIntPair<T>> result = this.occurrencesSortingBy(
                n,
                item -> -item.getTwo(),
                Lists.fixedSize.empty());
        return result.toImmutable();
    }

    @Override
    public ImmutableList<ObjectIntPair<T>> bottomOccurrences(int n)
    {
        MutableList<ObjectIntPair<T>> result = this.occurrencesSortingBy(
                n,
                ObjectIntPair::getTwo,
                Lists.fixedSize.empty());
        return result.toImmutable();
    }

    @Override
    public <V> ImmutableMap<V, T> groupByUniqueKey(Function<? super T, ? extends V> function)
    {
        return this.groupByUniqueKey(function, UnifiedMap.<V, T>newMap(this.size())).toImmutable();
    }

    @Override
    public RichIterable<RichIterable<T>> chunk(int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Size for groups must be positive but was: " + size);
        }

        Iterator<T> iterator = this.iterator();
        MutableList<RichIterable<T>> result = Lists.mutable.empty();
        while (iterator.hasNext())
        {
            MutableCollection<T> batch = Bags.mutable.empty();
            for (int i = 0; i < size && iterator.hasNext(); i++)
            {
                batch.add(iterator.next());
            }
            result.add(batch.toImmutable());
        }
        return result.toImmutable();
    }
}

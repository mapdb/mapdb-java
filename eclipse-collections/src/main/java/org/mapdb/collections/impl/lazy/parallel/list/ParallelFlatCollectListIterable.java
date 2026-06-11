/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.lazy.parallel.list;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.mapdb.collections.api.LazyIterable;
import org.mapdb.collections.api.annotation.Beta;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.procedure.Procedure;
import org.mapdb.collections.impl.lazy.parallel.AbstractParallelIterable;
import org.mapdb.collections.impl.lazy.parallel.OrderedBatch;
import org.mapdb.collections.impl.utility.Iterate;

@Beta
public class ParallelFlatCollectListIterable<T, V> extends AbstractParallelListIterable<V, ListBatch<V>>
{
    private final AbstractParallelIterable<T, ? extends OrderedBatch<T>> parallelIterable;
    private final Function<? super T, ? extends Iterable<V>> function;

    public ParallelFlatCollectListIterable(AbstractParallelIterable<T, ? extends OrderedBatch<T>> parallelIterable, Function<? super T, ? extends Iterable<V>> function)
    {
        this.parallelIterable = parallelIterable;
        this.function = function;
    }

    @Override
    public ExecutorService getExecutorService()
    {
        return this.parallelIterable.getExecutorService();
    }

    @Override
    public int getBatchSize()
    {
        return this.parallelIterable.getBatchSize();
    }

    @Override
    public LazyIterable<ListBatch<V>> split()
    {
        return this.parallelIterable.split().collect(batch -> batch.flatCollect(this.function));
    }

    @Override
    public void forEach(Procedure<? super V> procedure)
    {
        this.parallelIterable.forEach(each -> Iterate.forEach(this.function.valueOf(each), procedure));
    }

    @Override
    public V detect(Predicate<? super V> predicate)
    {
        // Some predicates are stateful, so they cannot be called more than once pre element,
        // that's why we use an AtomicReference to return the accepted element
        AtomicReference<V> result = new AtomicReference<>();
        this.parallelIterable.anySatisfy(each -> Iterate.anySatisfy(this.function.valueOf(each), each1 -> {
            if (predicate.accept(each1))
            {
                result.compareAndSet(null, each1);
                return true;
            }

            return false;
        }));

        return result.get();
    }

    @Override
    public boolean anySatisfy(Predicate<? super V> predicate)
    {
        return this.parallelIterable.anySatisfy(each -> Iterate.anySatisfy(this.function.valueOf(each), predicate));
    }

    @Override
    public boolean allSatisfy(Predicate<? super V> predicate)
    {
        return this.parallelIterable.allSatisfy(each -> Iterate.allSatisfy(this.function.valueOf(each), predicate));
    }
}

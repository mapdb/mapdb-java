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

import org.mapdb.collections.api.annotation.Beta;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.procedure.Procedure;
import org.mapdb.collections.impl.lazy.parallel.AbstractBatch;
import org.mapdb.collections.impl.lazy.parallel.Batch;
import org.mapdb.collections.impl.lazy.parallel.set.UnsortedSetBatch;
import org.mapdb.collections.impl.map.mutable.ConcurrentHashMap;
import org.mapdb.collections.impl.utility.Iterate;

@Beta
public class FlatCollectListBatch<T, V> extends AbstractBatch<V> implements ListBatch<V>
{
    private final Batch<T> batch;
    private final Function<? super T, ? extends Iterable<V>> function;

    public FlatCollectListBatch(Batch<T> batch, Function<? super T, ? extends Iterable<V>> function)
    {
        this.batch = batch;
        this.function = function;
    }

    @Override
    public void forEach(Procedure<? super V> procedure)
    {
        this.batch.forEach(each -> Iterate.forEach(this.function.valueOf(each), procedure));
    }

    @Override
    public ListBatch<V> select(Predicate<? super V> predicate)
    {
        return new SelectListBatch<>(this, predicate);
    }

    @Override
    public <VV> ListBatch<VV> collect(Function<? super V, ? extends VV> function)
    {
        return new CollectListBatch<>(this, function);
    }

    @Override
    public <VV> ListBatch<VV> flatCollect(Function<? super V, ? extends Iterable<VV>> function)
    {
        return new FlatCollectListBatch<>(this, function);
    }

    @Override
    public UnsortedSetBatch<V> distinct(ConcurrentHashMap<V, Boolean> distinct)
    {
        return new DistinctBatch<>(this, distinct);
    }
}

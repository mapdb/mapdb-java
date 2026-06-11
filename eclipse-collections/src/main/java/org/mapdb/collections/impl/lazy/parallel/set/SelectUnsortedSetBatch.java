/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.lazy.parallel.set;

import org.mapdb.collections.api.annotation.Beta;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.procedure.Procedure;
import org.mapdb.collections.impl.block.procedure.IfProcedure;
import org.mapdb.collections.impl.lazy.parallel.AbstractBatch;
import org.mapdb.collections.impl.lazy.parallel.bag.CollectUnsortedBagBatch;
import org.mapdb.collections.impl.lazy.parallel.bag.FlatCollectUnsortedBagBatch;
import org.mapdb.collections.impl.lazy.parallel.bag.UnsortedBagBatch;

@Beta
public class SelectUnsortedSetBatch<T> extends AbstractBatch<T> implements UnsortedSetBatch<T>
{
    private final UnsortedSetBatch<T> unsortedSetBatch;
    private final Predicate<? super T> predicate;

    public SelectUnsortedSetBatch(UnsortedSetBatch<T> unsortedSetBatch, Predicate<? super T> predicate)
    {
        this.unsortedSetBatch = unsortedSetBatch;
        this.predicate = predicate;
    }

    @Override
    public void forEach(Procedure<? super T> procedure)
    {
        this.unsortedSetBatch.forEach(new IfProcedure<>(this.predicate, procedure));
    }

    @Override
    public UnsortedSetBatch<T> select(Predicate<? super T> predicate)
    {
        return new SelectUnsortedSetBatch<>(this, predicate);
    }

    @Override
    public <V> UnsortedBagBatch<V> collect(Function<? super T, ? extends V> function)
    {
        return new CollectUnsortedBagBatch<>(this, function);
    }

    @Override
    public <V> UnsortedBagBatch<V> flatCollect(Function<? super T, ? extends Iterable<V>> function)
    {
        return new FlatCollectUnsortedBagBatch<>(this, function);
    }
}

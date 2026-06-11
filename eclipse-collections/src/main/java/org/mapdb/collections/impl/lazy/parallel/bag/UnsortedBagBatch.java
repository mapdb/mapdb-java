/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.lazy.parallel.bag;

import org.mapdb.collections.api.annotation.Beta;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.block.predicate.Predicate;
import org.mapdb.collections.api.block.procedure.primitive.ObjectIntProcedure;
import org.mapdb.collections.impl.lazy.parallel.Batch;

@Beta
public interface UnsortedBagBatch<T> extends Batch<T>
{
    void forEachWithOccurrences(ObjectIntProcedure<? super T> procedure);

    @Override
    UnsortedBagBatch<T> select(Predicate<? super T> predicate);

    @Override
    <V> UnsortedBagBatch<V> collect(Function<? super T, ? extends V> function);

    @Override
    <V> UnsortedBagBatch<V> flatCollect(Function<? super T, ? extends Iterable<V>> function);
}

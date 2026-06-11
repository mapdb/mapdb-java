/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.test.lazy;

import org.mapdb.collections.api.LazyIterable;
import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.impl.lazy.CompositeIterable;
import org.mapdb.collections.impl.utility.ArrayIterate;
import org.mapdb.collections.test.LazyNoIteratorTestCase;
import org.mapdb.collections.test.list.mutable.FastListNoIterator;

public class CompositeIterableTestNoIteratorTest implements LazyNoIteratorTestCase
{
    @Override
    public <T> LazyIterable<T> newWith(T... elements)
    {
        RichIterable<MutableList<T>> noIteratorLists = ArrayIterate.chunk(elements, 3).collect(chunk -> new FastListNoIterator<T>().withAll(chunk));
        Iterable<T>[] iterables = (Iterable<T>[]) noIteratorLists.toArray(new Iterable[]{});
        return CompositeIterable.with(iterables);
    }
}

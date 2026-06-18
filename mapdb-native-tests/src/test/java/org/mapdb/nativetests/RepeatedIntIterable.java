/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.nativetests;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.block.function.primitive.IntToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectIntToObjectFunction;
import org.mapdb.collections.api.block.predicate.primitive.IntPredicate;
import org.mapdb.collections.api.block.procedure.primitive.IntProcedure;
import org.mapdb.collections.api.iterator.IntIterator;
import org.mapdb.collections.impl.primitive.AbstractIntIterable;

/**
 * Synthetic {@code IntIterable} that yields a fixed {@code value} exactly
 * {@code count} times without materialising it. Used by the data-pump overflow
 * tests to drive a bag pump past {@code Integer.MAX_VALUE} occurrences (or total
 * size) cheaply -- the spec's "synthetic iterable path so you don't allocate
 * billions". Only {@link #intIterator()} is meaningfully implemented; every other
 * operation throws, because the bag pump consumes the iterator and nothing else.
 */
final class RepeatedIntIterable extends AbstractIntIterable
{
    private final int value;
    private final long count;

    RepeatedIntIterable(int value, long count)
    {
        this.value = value;
        this.count = count;
    }

    @Override
    public IntIterator intIterator()
    {
        return new IntIterator()
        {
            private long remaining = RepeatedIntIterable.this.count;

            @Override
            public boolean hasNext()
            {
                return this.remaining > 0;
            }

            @Override
            public int next()
            {
                this.remaining--;
                return RepeatedIntIterable.this.value;
            }
        };
    }

    @Override
    public int size()
    {
        return (int) Math.min(this.count, Integer.MAX_VALUE);
    }

    @Override
    public boolean isEmpty()
    {
        return this.count == 0;
    }

    @Override
    public boolean contains(int v)
    {
        return v == this.value && this.count > 0;
    }

    @Override
    public void each(IntProcedure procedure)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> RichIterable<V> collect(IntToObjectFunction<? extends V> function)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int detectIfNone(IntPredicate predicate, int ifNone)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int count(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean anySatisfy(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean allSatisfy(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean noneSatisfy(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.mapdb.collections.api.IntIterable select(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.mapdb.collections.api.IntIterable reject(IntPredicate predicate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int max()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int min()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long sum()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] toArray()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T injectInto(T injectedValue, ObjectIntToObjectFunction<? super T, ? extends T> function)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void appendString(Appendable appendable, String start, String separator, String end)
    {
        throw new UnsupportedOperationException();
    }
}

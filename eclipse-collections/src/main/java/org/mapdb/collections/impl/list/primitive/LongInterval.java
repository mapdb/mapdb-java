/*
 * Copyright (c) 2022 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.list.primitive;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.LongConsumer;

import org.mapdb.collections.api.LazyLongIterable;
import org.mapdb.collections.api.LongIterable;
import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.bag.primitive.MutableLongBag;
import org.mapdb.collections.api.block.function.primitive.LongToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectLongIntToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectLongToObjectFunction;
import org.mapdb.collections.api.block.predicate.primitive.LongPredicate;
import org.mapdb.collections.api.block.procedure.primitive.LongIntProcedure;
import org.mapdb.collections.api.block.procedure.primitive.LongLongProcedure;
import org.mapdb.collections.api.block.procedure.primitive.LongProcedure;
import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.factory.primitive.LongBags;
import org.mapdb.collections.api.factory.primitive.LongLists;
import org.mapdb.collections.api.factory.primitive.LongSets;
import org.mapdb.collections.api.iterator.LongIterator;
import org.mapdb.collections.api.list.ImmutableList;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.list.primitive.ImmutableLongList;
import org.mapdb.collections.api.list.primitive.LongList;
import org.mapdb.collections.api.list.primitive.MutableLongList;
import org.mapdb.collections.api.set.primitive.MutableLongSet;
import org.mapdb.collections.api.stack.primitive.MutableLongStack;
import org.mapdb.collections.api.tuple.primitive.LongLongPair;
import org.mapdb.collections.api.tuple.primitive.LongObjectPair;
import org.mapdb.collections.impl.factory.primitive.LongStacks;
import org.mapdb.collections.impl.lazy.primitive.CollectLongToObjectIterable;
import org.mapdb.collections.impl.lazy.primitive.LazyLongIterableAdapter;
import org.mapdb.collections.impl.lazy.primitive.ReverseLongIterable;
import org.mapdb.collections.impl.lazy.primitive.SelectLongIterable;
import org.mapdb.collections.impl.list.LongIntervalUtils;
import org.mapdb.collections.impl.tuple.primitive.PrimitiveTuples;
import org.mapdb.collections.impl.utility.Iterate;

/**
 * An LongInterval is a range of longs that may be iterated over using a step value.
 * Note that the size of the interval (the number of elements in the list it represents)
 * is limited by the maximum value of the integer index.
 */
public final class LongInterval
        implements ImmutableLongList, Serializable
{
    private static final long serialVersionUID = 1L;

    private final long from;
    private final long to;
    private final long step;
    private final int size;

    private LongInterval(long from, long to, long step)
    {
        this.from = from;
        this.to = to;
        this.step = step;

        this.size = LongIntervalUtils.intSize(this.from, this.to, this.step);
    }

    /**
     * This static {@code from} method allows LongInterval to act as a fluent builder for itself.
     * It works in conjunction with the instance methods {@link #to(long)} and {@link #by(long)}.
     * <p>
     * Usage Example:
     * <pre>
     * LongInterval interval1 = LongInterval.from(1).to(5);         // results in: 1, 2, 3, 4, 5.
     * LongInterval interval2 = LongInterval.from(1).to(10).by(2);  // results in: 1, 3, 5, 7, 9.
     * </pre>
     */
    public static LongInterval from(long newFrom)
    {
        return LongInterval.fromToBy(newFrom, newFrom, 1);
    }

    /**
     * This instance {@code to} method allows LongInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(long)} and instance method {@link #by(long)}.
     * <p>
     * Usage Example:
     * <pre>
     * LongInterval interval1 = LongInterval.from(1).to(5);         // results in: 1, 2, 3, 4, 5.
     * LongInterval interval2 = LongInterval.from(1).to(10).by(2);  // results in: 1, 3, 5, 7, 9.
     * </pre>
     */
    public LongInterval to(long newTo)
    {
        long adjustedStep = LongInterval.calculateAdjustedStep(this.from, newTo, this.step);
        return LongInterval.fromToBy(this.from, newTo, adjustedStep);
    }

    /**
     * This instance {@code by} method allows LongInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(long)} and instance method {@link #to(long)}.
     * <p>
     * Usage Example:
     * <pre>
     * LongInterval interval1 = LongInterval.from(1).to(5);         // results in: 1, 2, 3, 4, 5.
     * LongInterval interval2 = LongInterval.from(1).to(10).by(2);  // results in: 1, 3, 5, 7, 9.
     * </pre>
     */
    public LongInterval by(long newStep)
    {
        return LongInterval.fromToBy(this.from, this.to, newStep);
    }

    /**
     * Returns an LongInterval starting at zero.
     * <p>
     * Usage Example:
     * <pre>
     * LongInterval interval1 = LongInterval.zero().to(5);         // results in: 0, 1, 2, 3, 4, 5.
     * LongInterval interval2 = LongInterval.zero().to(10).by(2);  // results in: 0, 2, 4, 6, 8, 10.
     * </pre>
     */
    public static LongInterval zero()
    {
        return LongInterval.from(0);
    }

    /**
     * Returns an LongInterval starting from 1 to the specified count value with a step value of 1.
     */
    public static LongInterval oneTo(long count)
    {
        long adjustedStep = LongInterval.calculateAdjustedStep(1L, count, 1L);
        return LongInterval.oneToBy(count, adjustedStep);
    }

    /**
     * Returns an LongInterval starting from 1 to the specified count value with a step value of step.
     */
    public static LongInterval oneToBy(long count, long step)
    {
        return LongInterval.fromToBy(1, count, step);
    }

    /**
     * Returns an LongInterval starting from 0 to the specified count value with a step value of 1.
     */
    public static LongInterval zeroTo(long count)
    {
        long adjustedStep = LongInterval.calculateAdjustedStep(0L, count, 1L);
        return LongInterval.zeroToBy(count, adjustedStep);
    }

    /**
     * Returns an LongInterval starting from 0 to the specified count value with a step value of step.
     */
    public static LongInterval zeroToBy(long count, long step)
    {
        return LongInterval.fromToBy(0, count, step);
    }

    /**
     * Returns an LongInterval starting from the value from to the specified value to with a step value of 1.
     */
    public static LongInterval fromTo(long from, long to)
    {
        if (from <= to)
        {
            return LongInterval.fromToBy(from, to, 1);
        }
        return LongInterval.fromToBy(from, to, -1);
    }

    /**
     * Returns an LongInterval representing the even values from the value from to the value to.
     */
    public static LongInterval evensFromTo(long from, long to)
    {
        if (from % 2 != 0)
        {
            if (from < to)
            {
                from++;
            }
            else
            {
                from--;
            }
        }
        if (to % 2 != 0)
        {
            if (to > from)
            {
                to--;
            }
            else
            {
                to++;
            }
        }
        return LongInterval.fromToBy(from, to, to > from ? 2 : -2);
    }

    /**
     * Returns an LongInterval representing the odd values from the value from to the value to.
     */
    public static LongInterval oddsFromTo(long from, long to)
    {
        if (from % 2 == 0)
        {
            if (from < to)
            {
                from++;
            }
            else
            {
                from--;
            }
        }
        if (to % 2 == 0)
        {
            if (to > from)
            {
                to--;
            }
            else
            {
                to++;
            }
        }
        return LongInterval.fromToBy(from, to, to > from ? 2 : -2);
    }

    /**
     * Returns an LongInterval for the range of integers inclusively between from and to with the specified
     * stepBy value.
     */
    public static LongInterval fromToBy(long from, long to, long stepBy)
    {
        LongIntervalUtils.checkArguments(from, to, stepBy);
        return new LongInterval(from, to, stepBy);
    }

    /**
     * Returns true if the LongInterval contains all the specified long values.
     */
    @Override
    public boolean containsAll(long... values)
    {
        for (long value : values)
        {
            if (!this.contains(value))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean containsAll(LongIterable source)
    {
        for (LongIterator iterator = source.longIterator(); iterator.hasNext(); )
        {
            if (!this.contains(iterator.next()))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the LongInterval contains none of the specified long values.
     */
    public boolean containsNone(long... values)
    {
        for (long value : values)
        {
            if (this.contains(value))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the LongInterval contains the specified long value.
     */
    @Override
    public boolean contains(long value)
    {
        return LongIntervalUtils.contains(value, this.from, this.to, this.step);
    }

    @Override
    public void forEachWithIndex(LongIntProcedure procedure)
    {
        // Drive by index, not by post-increment boundary comparison: for a
        // full-domain long range `i += step` wraps and a signed `i <= to`/`i >= to`
        // test never terminates. The element count fits in an int (checkSize).
        for (int idx = 0; idx < this.size; idx++)
        {
            procedure.value(LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step), idx);
        }
    }

    @Deprecated(forRemoval = true)
    public void forEachWithLongIndex(LongLongProcedure procedure)
    {
        for (int idx = 0; idx < this.size; idx++)
        {
            procedure.value(LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step), idx);
        }
    }

    /**
     * @since 7.0.
     */
    @Override
    public void each(LongProcedure procedure)
    {
        for (int idx = 0; idx < this.size; idx++)
        {
            procedure.value(LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step));
        }
    }

    @Override
    public int count(LongPredicate predicate)
    {
        int count = 0;
        for (int i = 0; i < this.size(); i++)
        {
            if (predicate.accept(this.get(i)))
            {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean anySatisfy(LongPredicate predicate)
    {
        for (int i = 0; i < this.size(); i++)
        {
            if (predicate.accept(this.get(i)))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean allSatisfy(LongPredicate predicate)
    {
        for (int i = 0; i < this.size(); i++)
        {
            if (!predicate.accept(this.get(i)))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object otherList)
    {
        if (otherList == this)
        {
            return true;
        }
        if (!(otherList instanceof LongList))
        {
            return false;
        }
        LongList list = (LongList) otherList;
        if (this.size() != list.size())
        {
            return false;
        }
        if (this.from == this.to)
        {
            return this.from == list.get(0);
        }
        // Element-by-element, matching IntInterval's semantics. We deliberately
        // do NOT add a LongInterval-vs-LongInterval fast path comparing step:
        // two single-element intervals with the same element but different step
        // (e.g. fromToBy(1, 5, 10) and fromToBy(1, 100, 1000), both [1]) are
        // equal as lists, so a step comparison would wrongly report inequality.
        for (int idx = 0; idx < this.size; idx++)
        {
            if (LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step) != list.get(idx))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        int hashCode = 1;
        for (int idx = 0; idx < this.size; idx++)
        {
            long i = LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step);
            hashCode = 31 * hashCode + (int) (i ^ i >>> 32);
        }
        return hashCode;
    }

    /**
     * Returns a new LongInterval with the from and to values reversed and the step value negated.
     */
    @Override
    public LongInterval toReversed()
    {
        // Negating Long.MIN_VALUE silently overflows back to Long.MIN_VALUE
        // (it is unrepresentable as a positive long), which would build a wrong
        // interval. Per spec/algorithms.md "Reversed() panics at minimum step",
        // reject it explicitly rather than letting -this.step wrap.
        if (this.step == Long.MIN_VALUE)
        {
            throw new ArithmeticException("Cannot reverse a LongInterval with the minimum step value");
        }
        return LongInterval.fromToBy(this.to, this.from, -this.step);
    }

    /**
     * @since 6.0
     */
    @Override
    public ImmutableLongList distinct()
    {
        return this;
    }

    @Override
    public ImmutableLongList subList(int fromIndex, int toIndex)
    {
        throw new UnsupportedOperationException("subList not yet implemented!");
    }

    /**
     * Returns the size of the interval.
     */
    @Override
    public int size()
    {
        return this.size;
    }

    @Override
    public long dotProduct(LongList list)
    {
        if (this.size() != list.size())
        {
            throw new IllegalArgumentException("Lists used in dotProduct must be the same size");
        }
        long sum = 0L;
        for (int i = 0; i < this.size(); i++)
        {
            sum += this.get(i) * list.get(i);
        }
        return sum;
    }

    @Override
    public boolean isEmpty()
    {
        return this.size() == 0;
    }

    @Override
    public boolean notEmpty()
    {
        return !this.isEmpty();
    }

    @Override
    public String makeString()
    {
        return this.makeString(", ");
    }

    @Override
    public String makeString(String separator)
    {
        return this.makeString("", separator, "");
    }

    @Override
    public String makeString(String start, String separator, String end)
    {
        Appendable stringBuilder = new StringBuilder();
        this.appendString(stringBuilder, start, separator, end);
        return stringBuilder.toString();
    }

    @Override
    public void appendString(Appendable appendable)
    {
        this.appendString(appendable, ", ");
    }

    @Override
    public void appendString(Appendable appendable, String separator)
    {
        this.appendString(appendable, "", separator, "");
    }

    @Override
    public void appendString(
            Appendable appendable,
            String start,
            String separator,
            String end)
    {
        try
        {
            appendable.append(start);
            for (int i = 0; i < this.size(); i++)
            {
                if (i > 0)
                {
                    appendable.append(separator);
                }
                long value = this.get(i);
                appendable.append(String.valueOf(value));
            }
            appendable.append(end);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long[] toArray()
    {
        long[] result = new long[this.size()];
        this.forEachWithIndex((each, index) -> result[index] = each);
        return result;
    }

    @Override
    public long[] toArray(long[] result)
    {
        if (result.length < this.size())
        {
            result = new long[this.size()];
        }
        long[] finalBypass = result;
        this.forEachWithIndex((each, index) -> finalBypass[index] = each);
        return result;
    }

    @Override
    public <T> T injectInto(T injectedValue, ObjectLongToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        for (int idx = 0; idx < this.size; idx++)
        {
            result = function.valueOf(result, LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step));
        }
        return result;
    }

    @Override
    public <T> T injectIntoWithIndex(T injectedValue, ObjectLongIntToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        for (int idx = 0; idx < this.size; idx++)
        {
            result = function.valueOf(result, LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step), idx);
        }
        return result;
    }

    @Override
    public RichIterable<LongIterable> chunk(int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Size for groups must be positive but was: " + size);
        }
        MutableList<LongIterable> result = Lists.mutable.empty();
        if (this.notEmpty())
        {
            // Index-driven so the chunk boundaries cannot wrap at the full long
            // domain (a value-based `i += step` / `i <= to` walk wraps there).
            int idx = 0;
            while (idx < this.size)
            {
                MutableLongList batch = LongLists.mutable.empty();
                // idx + Math.min(size, this.size - idx) — never `idx + size`, which
                // overflows int once idx > 0 and the chunk size is large (the
                // remaining count this.size - idx is always a safe non-negative int).
                for (int batchEnd = idx + Math.min(size, this.size - idx); idx < batchEnd; idx++)
                {
                    batch.add(LongIntervalUtils.valueAtIndex(idx, this.from, this.to, this.step));
                }
                result.add(batch);
            }
        }
        return result;
    }

    @Override
    public String toString()
    {
        return this.makeString("[", ", ", "]");
    }

    @Override
    public LongIterator longIterator()
    {
        return new LongIntervalIterator();
    }

    @Override
    public long getFirst()
    {
        return this.from;
    }

    @Override
    public long getLast()
    {
        return LongIntervalUtils.valueAtIndex(this.size() - 1, this.from, this.to, this.step);
    }

    @Override
    public long get(int index)
    {
        this.checkBounds("index", index);
        return LongIntervalUtils.valueAtIndex(index, this.from, this.to, this.step);
    }

    private void checkBounds(String name, int index)
    {
        if (index < 0 || index >= this.size())
        {
            throw new IndexOutOfBoundsException(name + ": " + index + ' ' + this);
        }
    }

    @Override
    public int indexOf(long value)
    {
        return LongIntervalUtils.indexOf(value, this.from, this.to, this.step);
    }

    @Override
    public int lastIndexOf(long value)
    {
        return this.indexOf(value);
    }

    @Override
    public ImmutableLongList select(LongPredicate predicate)
    {
        return LongLists.mutable.withAll(new SelectLongIterable(this, predicate)).toImmutable();
    }

    @Override
    public ImmutableLongList reject(LongPredicate predicate)
    {
        return LongLists.mutable.withAll(new SelectLongIterable(this, value -> !predicate.accept(value))).toImmutable();
    }

    @Override
    public long detectIfNone(LongPredicate predicate, long ifNone)
    {
        return new SelectLongIterable(this, predicate).detectIfNone(predicate, ifNone);
    }

    @Override
    public <V> ImmutableList<V> collect(LongToObjectFunction<? extends V> function)
    {
        return new CollectLongToObjectIterable<V>(this, function).toList().toImmutable();
    }

    @Override
    public LazyLongIterable asReversed()
    {
        return ReverseLongIterable.adapt(this);
    }

    @Override
    public long sum()
    {
        if (this.size() == 1)
        {
            return this.getFirst();
        }

        int n = this.size();

        if (n % 2 == 0)
        {
            // Even n: (n / 2) is exact, so (n / 2) * (first + last) is the
            // arithmetic-series sum computed mod 2^64 (exact for substitutability
            // with LongArrayList even when the true sum overflows).
            return (long) (n / 2) * (this.getFirst() + this.getLast());
        }
        // Odd n: the sum equals n * (middle element). Halving first+last before
        // multiplying (the old code) does not commute with mod 2^64 and gives a
        // wrong result once the true sum overflows; multiplying by the middle
        // element is mod-2^64-exact.
        return (long) n * LongIntervalUtils.valueAtIndex(n / 2, this.from, this.to, this.step);
    }

    @Override
    public long max()
    {
        if (this.from >= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public long min()
    {
        if (this.from <= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public long minIfEmpty(long defaultValue)
    {
        return this.min();
    }

    @Override
    public long maxIfEmpty(long defaultValue)
    {
        return this.max();
    }

    @Override
    public double average()
    {
        // for an arithmetic sequence its median and its average are the same
        return this.median();
    }

    @Override
    public double median()
    {
        return ((double) this.getFirst() + (double) this.getLast()) / 2.0;
    }

    @Override
    public int binarySearch(long value)
    {
        return LongIntervalUtils.binarySearch(value, this.from, this.to, this.step);
    }

    @Override
    public long[] toSortedArray()
    {
        long[] array = this.toArray();
        Arrays.sort(array);
        return array;
    }

    @Override
    public MutableLongList toList()
    {
        return LongLists.mutable.withAll(this);
    }

    @Override
    public MutableLongList toSortedList()
    {
        return LongLists.mutable.withAll(this).sortThis();
    }

    @Override
    public MutableLongSet toSet()
    {
        return LongSets.mutable.withAll(this);
    }

    @Override
    public MutableLongBag toBag()
    {
        return LongBags.mutable.withAll(this);
    }

    @Override
    public LazyLongIterable asLazy()
    {
        return new LazyLongIterableAdapter(this);
    }

    @Override
    public ImmutableLongList toImmutable()
    {
        return this;
    }

    @Override
    public ImmutableLongList newWith(long element)
    {
        return LongLists.mutable.withAll(this).with(element).toImmutable();
    }

    @Override
    public ImmutableLongList newWithout(long element)
    {
        return LongLists.mutable.withAll(this).without(element).toImmutable();
    }

    @Override
    public ImmutableLongList newWithAll(LongIterable elements)
    {
        return LongLists.mutable.withAll(this).withAll(elements).toImmutable();
    }

    @Override
    public ImmutableLongList newWithoutAll(LongIterable elements)
    {
        return LongLists.mutable.withAll(this).withoutAll(elements).toImmutable();
    }

    @Override
    public ImmutableList<LongLongPair> zipLong(LongIterable iterable)
    {
        int size = this.size();
        int othersize = iterable.size();
        MutableList<LongLongPair> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        LongIterator iterator = this.longIterator();
        LongIterator otherIterator = iterable.longIterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public <T> ImmutableList<LongObjectPair<T>> zip(Iterable<T> iterable)
    {
        int size = this.size();
        int othersize = Iterate.sizeOf(iterable);
        MutableList<LongObjectPair<T>> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        LongIterator iterator = this.longIterator();
        Iterator<T> otherIterator = iterable.iterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public Spliterator.OfLong spliterator()
    {
        return new LongIntervalSpliterator(
                this.from, this.step, LongIntervalUtils.longSize(this.from, this.to, this.step));
    }

    @Override
    public MutableLongStack toStack()
    {
        return LongStacks.mutable.withAll(this);
    }

    private class LongIntervalIterator implements LongIterator
    {
        // Index-driven so the iterator cannot wrap past the end for a
        // full-domain interval (a value-based `current += step` walk with a
        // signed `current <= to`/`current >= to` test wraps and over-runs).
        private int index;

        @Override
        public boolean hasNext()
        {
            return this.index < LongInterval.this.size;
        }

        @Override
        public long next()
        {
            if (this.hasNext())
            {
                long result = LongIntervalUtils.valueAtIndex(
                        this.index, LongInterval.this.from, LongInterval.this.to, LongInterval.this.step);
                this.index++;
                return result;
            }
            throw new NoSuchElementException();
        }
    }

    private static final class LongIntervalSpliterator implements Spliterator.OfLong
    {
        // Index/remaining-count driven so production and splitting cannot wrap
        // or over-run at the full long domain: `current += step` plus a signed
        // `current <= to`/`current >= to` test wraps there. `current` is the
        // next value to emit and `remaining` is how many elements are still to
        // be produced (a genuine element count, which fits in a long).
        private long current;
        private long remaining;
        private final long step;
        private final boolean isAscending;

        private LongIntervalSpliterator(long current, long step, long remaining)
        {
            this.current = current;
            this.step = step;
            this.remaining = remaining;
            this.isAscending = step > 0L;
        }

        @Override
        public Comparator<? super Long> getComparator()
        {
            if (this.isAscending)
            {
                return Comparator.naturalOrder();
            }
            return Comparator.reverseOrder();
        }

        @Override
        public OfLong trySplit()
        {
            // Split by element count, not by a signed midpoint value comparison
            // (which can wrap). The left half covers the first `leftCount`
            // elements; this spliterator keeps the rest.
            long leftCount = this.remaining / 2L;
            if (leftCount == 0L)
            {
                return null;
            }
            long leftStart = this.current;
            // The new start value after the left half. `step * leftCount` may
            // overflow in isolation, but the two's-complement sum wraps back to
            // the correct in-range value (same contract as valueAtIndex).
            this.current += this.step * leftCount;
            this.remaining -= leftCount;
            return new LongIntervalSpliterator(leftStart, this.step, leftCount);
        }

        @Override
        public long estimateSize()
        {
            return this.remaining;
        }

        @Override
        public int characteristics()
        {
            return Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SORTED;
        }

        @Override
        public boolean tryAdvance(LongConsumer action)
        {
            if (this.remaining <= 0L)
            {
                return false;
            }
            action.accept(this.current);
            this.current += this.step;
            this.remaining--;
            return true;
        }

        @Override
        public void forEachRemaining(LongConsumer action)
        {
            while (this.remaining > 0L)
            {
                action.accept(this.current);
                this.current += this.step;
                this.remaining--;
            }
        }
    }

    private static long calculateAdjustedStep(long from, long to, long stepBy)
    {
        // Use a signed comparison rather than Long.signum(to - from): the
        // subtraction wraps for a full-domain direction (e.g. from MIN to MAX),
        // flipping the step sign and getting the interval wrongly rejected.
        // Multiplying by direction in {-1, 0, 1} cannot overflow stepBy.
        int direction = Long.compare(to, from);
        return direction == 0 ? stepBy : (long) direction * stepBy;
    }
}

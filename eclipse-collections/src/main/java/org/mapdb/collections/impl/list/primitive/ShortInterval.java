/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.list.primitive;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.mapdb.collections.api.ShortIterable;
import org.mapdb.collections.api.LazyShortIterable;
import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.bag.primitive.MutableShortBag;
import org.mapdb.collections.api.block.function.primitive.ShortToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectShortIntToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectShortToObjectFunction;
import org.mapdb.collections.api.block.predicate.primitive.ShortPredicate;
import org.mapdb.collections.api.block.procedure.primitive.ShortIntProcedure;
import org.mapdb.collections.api.block.procedure.primitive.ShortProcedure;
import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.factory.primitive.ShortBags;
import org.mapdb.collections.api.factory.primitive.ShortLists;
import org.mapdb.collections.api.factory.primitive.ShortSets;
import org.mapdb.collections.api.iterator.ShortIterator;
import org.mapdb.collections.api.list.ImmutableList;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.list.primitive.ShortList;
import org.mapdb.collections.api.list.primitive.ImmutableShortList;
import org.mapdb.collections.api.list.primitive.MutableShortList;
import org.mapdb.collections.api.set.primitive.MutableShortSet;
import org.mapdb.collections.api.stack.primitive.MutableShortStack;
import org.mapdb.collections.api.tuple.primitive.ShortShortPair;
import org.mapdb.collections.api.tuple.primitive.ShortObjectPair;
import org.mapdb.collections.impl.factory.primitive.ShortStacks;
import org.mapdb.collections.impl.lazy.primitive.CollectShortToObjectIterable;
import org.mapdb.collections.impl.lazy.primitive.LazyShortIterableAdapter;
import org.mapdb.collections.impl.lazy.primitive.ReverseShortIterable;
import org.mapdb.collections.impl.lazy.primitive.SelectShortIterable;
import org.mapdb.collections.impl.list.IntervalUtils;
import org.mapdb.collections.impl.tuple.primitive.PrimitiveTuples;
import org.mapdb.collections.impl.utility.Iterate;

/**
 * A ShortInterval is a range of shorts that may be iterated over using a step value.
 */
public final class ShortInterval
        implements ImmutableShortList, Serializable
{
    private static final long serialVersionUID = 1L;

    private final short from;
    private final short to;
    private final short step;
    private transient int size;

    private ShortInterval(short from, short to, short step)
    {
        this.from = from;
        this.to = to;
        this.step = step;
        this.size = IntervalUtils.intSize(this.from, this.to, this.step);
    }

    /**
     * This static {@code from} method allows ShortInterval to act as a fluent builder for itself.
     * It works in conjunction with the instance methods {@link #to(short)} and {@link #by(short)}.
     * <p>
     * Usage Example:
     * <pre>
     * ShortInterval interval1 = ShortInterval.from((short) 1).to((short) 5);                 // results in: 1, 2, 3, 4, 5.
     * ShortInterval interval2 = ShortInterval.from((short) 1).to((short) 10).by((short) 2);   // results in: 1, 3, 5, 7, 9.
     * </pre>
     */
    public static ShortInterval from(short newFrom)
    {
        return ShortInterval.fromToBy(newFrom, newFrom, (short) 1);
    }

    /**
     * This instance {@code to} method allows ShortInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(short)} and instance method {@link #by(short)}.
     */
    public ShortInterval to(short newTo)
    {
        short adjustedStep = ShortInterval.calculateAdjustedStep(this.from, newTo, this.step);
        return ShortInterval.fromToBy(this.from, newTo, adjustedStep);
    }

    /**
     * This instance {@code by} method allows ShortInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(short)} and instance method {@link #to(short)}.
     */
    public ShortInterval by(short newStep)
    {
        return ShortInterval.fromToBy(this.from, this.to, newStep);
    }

    /**
     * Returns a ShortInterval starting at zero.
     */
    public static ShortInterval zero()
    {
        return ShortInterval.from((short) 0);
    }

    /**
     * Returns a ShortInterval starting from 1 to the specified count value with a step value of 1.
     */
    public static ShortInterval oneTo(short count)
    {
        short adjustedStep = ShortInterval.calculateAdjustedStep((short) 1, count, (short) 1);
        return ShortInterval.oneToBy(count, adjustedStep);
    }

    /**
     * Returns a ShortInterval starting from 1 to the specified count value with a step value of step.
     */
    public static ShortInterval oneToBy(short count, short step)
    {
        return ShortInterval.fromToBy((short) 1, count, step);
    }

    /**
     * Returns a ShortInterval starting from 0 to the specified count value with a step value of 1.
     */
    public static ShortInterval zeroTo(short count)
    {
        short adjustedStep = ShortInterval.calculateAdjustedStep((short) 0, count, (short) 1);
        return ShortInterval.zeroToBy(count, adjustedStep);
    }

    /**
     * Returns a ShortInterval starting from 0 to the specified count value with a step value of step.
     */
    public static ShortInterval zeroToBy(short count, short step)
    {
        return ShortInterval.fromToBy((short) 0, count, step);
    }

    /**
     * Returns a ShortInterval starting from the value from to the specified value to with a step value of 1.
     */
    public static ShortInterval fromTo(short from, short to)
    {
        if (from <= to)
        {
            return ShortInterval.fromToBy(from, to, (short) 1);
        }
        return ShortInterval.fromToBy(from, to, (short) -1);
    }

    /**
     * Returns a ShortInterval representing the even values from the value from to the value to.
     */
    public static ShortInterval evensFromTo(short from, short to)
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
        return ShortInterval.fromToBy(from, to, to > from ? (short) 2 : (short) -2);
    }

    /**
     * Returns a ShortInterval representing the odd values from the value from to the value to.
     */
    public static ShortInterval oddsFromTo(short from, short to)
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
        return ShortInterval.fromToBy(from, to, to > from ? (short) 2 : (short) -2);
    }

    /**
     * Returns a ShortInterval for the range of shorts inclusively between from and to with the specified
     * stepBy value.
     */
    public static ShortInterval fromToBy(short from, short to, short stepBy)
    {
        IntervalUtils.checkArguments(from, to, stepBy);
        return new ShortInterval(from, to, stepBy);
    }

    /**
     * Returns true if the ShortInterval contains all the specified short values.
     */
    @Override
    public boolean containsAll(short... values)
    {
        for (short value : values)
        {
            if (!this.contains(value))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean containsAll(ShortIterable source)
    {
        for (ShortIterator iterator = source.shortIterator(); iterator.hasNext(); )
        {
            if (!this.contains(iterator.next()))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the ShortInterval contains the specified short value.
     */
    @Override
    public boolean contains(short value)
    {
        return IntervalUtils.contains(value, this.from, this.to, this.step);
    }

    @Override
    public void forEachWithIndex(ShortIntProcedure procedure)
    {
        int index = 0;
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                procedure.value((short) i, index++);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                procedure.value((short) i, index++);
            }
        }
    }

    private boolean goForward()
    {
        return this.from <= this.to && this.step > 0;
    }

    @Override
    public void each(ShortProcedure procedure)
    {
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                procedure.value((short) i);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                procedure.value((short) i);
            }
        }
    }

    @Override
    public int count(ShortPredicate predicate)
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
    public boolean anySatisfy(ShortPredicate predicate)
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
    public boolean allSatisfy(ShortPredicate predicate)
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
        if (!(otherList instanceof ShortList))
        {
            return false;
        }
        ShortList list = (ShortList) otherList;
        if (this.size() != list.size())
        {
            return false;
        }
        if (this.from == this.to)
        {
            return this.from == list.get(0);
        }
        if (this.from < this.to)
        {
            int listIndex = 0;
            for (long i = this.from; i <= this.to; i += this.step)
            {
                if ((short) i != list.get(listIndex++))
                {
                    return false;
                }
            }
        }
        else
        {
            int listIndex = 0;
            for (long i = this.from; i >= this.to; i += this.step)
            {
                if ((short) i != list.get(listIndex++))
                {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        int hashCode = 1;
        if (this.from == this.to)
        {
            hashCode = 31 + this.from;
        }
        else if (this.from < this.to)
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                hashCode = 31 * hashCode + (int) (short) i;
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                hashCode = 31 * hashCode + (int) (short) i;
            }
        }
        return hashCode;
    }

    /**
     * Returns a new ShortInterval with the from and to values reversed and the step value negated.
     */
    @Override
    public ShortInterval toReversed()
    {
        // A short step is promoted to int before negation, so -step never
        // overflows an int; but -Short.MIN_VALUE (32768) is not representable as
        // a short. Per spec/algorithms.md "Reversed() panics at minimum step",
        // reject it explicitly rather than narrowing the negation silently.
        if (this.step == Short.MIN_VALUE)
        {
            throw new ArithmeticException("Cannot reverse a ShortInterval with the minimum step value");
        }
        return ShortInterval.fromToBy(this.to, this.from, (short) -this.step);
    }

    @Override
    public ImmutableShortList distinct()
    {
        return this;
    }

    @Override
    public ImmutableShortList subList(int fromIndex, int toIndex)
    {
        return ShortInterval.fromToBy(this.get(fromIndex), this.get(toIndex - 1), this.step);
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
    public long dotProduct(ShortList list)
    {
        if (this.size() != list.size())
        {
            throw new IllegalArgumentException("Lists used in dotProduct must be the same size");
        }
        long sum = 0L;
        for (int i = 0; i < this.size(); i++)
        {
            sum += (long) this.get(i) * list.get(i);
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
                short value = this.get(i);
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
    public short[] toArray()
    {
        short[] result = new short[this.size()];
        this.forEachWithIndex((each, index) -> result[index] = each);
        return result;
    }

    @Override
    public short[] toArray(short[] result)
    {
        if (result.length < this.size())
        {
            result = new short[this.size()];
        }
        short[] finalBypass = result;
        this.forEachWithIndex((each, index) -> finalBypass[index] = each);
        return result;
    }

    @Override
    public <T> T injectInto(T injectedValue, ObjectShortToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                result = function.valueOf(result, (short) i);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                result = function.valueOf(result, (short) i);
            }
        }
        return result;
    }

    @Override
    public <T> T injectIntoWithIndex(T injectedValue, ObjectShortIntToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        int index = 0;

        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                result = function.valueOf(result, (short) i, index);
                index++;
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                result = function.valueOf(result, (short) i, index);
                index++;
            }
        }
        return result;
    }

    @Override
    public RichIterable<ShortIterable> chunk(int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Size for groups must be positive but was: " + size);
        }
        MutableList<ShortIterable> result = Lists.mutable.empty();
        if (this.notEmpty())
        {
            // Index-driven so a singleton (or any size <= chunkSize) interval
            // still yields one batch containing every element; the old
            // value-based `(lastUpdated + step) <= to` guard dropped the sole
            // element of a size-1 interval, diverging from LongInterval.chunk.
            int idx = 0;
            while (idx < this.size)
            {
                MutableShortList batch = ShortLists.mutable.empty();
                // idx + Math.min(size, this.size - idx) — never `idx + size`, which
                // overflows int once idx > 0 and the chunk size is large (the
                // remaining count this.size - idx is always a safe non-negative int).
                for (int batchEnd = idx + Math.min(size, this.size - idx); idx < batchEnd; idx++)
                {
                    batch.add((short) IntervalUtils.valueAtIndex(idx, this.from, this.to, this.step));
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
    public ShortIterator shortIterator()
    {
        return new ShortIntervalIterator();
    }

    @Override
    public short getFirst()
    {
        return this.from;
    }

    @Override
    public short getLast()
    {
        return (short) IntervalUtils.valueAtIndex(this.size() - 1, this.from, this.to, this.step);
    }

    @Override
    public short get(int index)
    {
        this.checkBounds("index", index);
        return (short) IntervalUtils.valueAtIndex(index, this.from, this.to, this.step);
    }

    private void checkBounds(String name, int index)
    {
        if (index < 0 || index >= this.size())
        {
            throw new IndexOutOfBoundsException(name + ": " + index + ' ' + this);
        }
    }

    @Override
    public int indexOf(short value)
    {
        return IntervalUtils.indexOf(value, this.from, this.to, this.step);
    }

    @Override
    public int lastIndexOf(short value)
    {
        return this.indexOf(value);
    }

    @Override
    public ImmutableShortList select(ShortPredicate predicate)
    {
        return ShortLists.mutable.withAll(new SelectShortIterable(this, predicate)).toImmutable();
    }

    @Override
    public ImmutableShortList reject(ShortPredicate predicate)
    {
        return ShortLists.mutable.withAll(new SelectShortIterable(this, value -> !predicate.accept(value))).toImmutable();
    }

    @Override
    public short detectIfNone(ShortPredicate predicate, short ifNone)
    {
        return new SelectShortIterable(this, predicate).detectIfNone(predicate, ifNone);
    }

    @Override
    public <V> ImmutableList<V> collect(ShortToObjectFunction<? extends V> function)
    {
        return new CollectShortToObjectIterable<V>(this, function).toList().toImmutable();
    }

    @Override
    public LazyShortIterable asReversed()
    {
        return ReverseShortIterable.adapt(this);
    }

    @Override
    public long sum()
    {
        long sum = 0L;
        for (int i = 0; i < this.size(); i++)
        {
            sum += this.get(i);
        }
        return sum;
    }

    @Override
    public short max()
    {
        if (this.from >= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public short min()
    {
        if (this.from <= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public short minIfEmpty(short defaultValue)
    {
        return this.min();
    }

    @Override
    public short maxIfEmpty(short defaultValue)
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
    public int binarySearch(short value)
    {
        return IntervalUtils.binarySearch(value, this.from, this.to, this.step);
    }

    @Override
    public short[] toSortedArray()
    {
        short[] array = this.toArray();
        Arrays.sort(array);
        return array;
    }

    @Override
    public MutableShortList toList()
    {
        return ShortLists.mutable.withAll(this);
    }

    @Override
    public MutableShortList toSortedList()
    {
        return ShortLists.mutable.withAll(this).sortThis();
    }

    @Override
    public MutableShortSet toSet()
    {
        return ShortSets.mutable.withAll(this);
    }

    @Override
    public MutableShortBag toBag()
    {
        return ShortBags.mutable.withAll(this);
    }

    @Override
    public LazyShortIterable asLazy()
    {
        return new LazyShortIterableAdapter(this);
    }

    @Override
    public ImmutableShortList toImmutable()
    {
        return this;
    }

    @Override
    public ImmutableShortList newWith(short element)
    {
        return ShortLists.mutable.withAll(this).with(element).toImmutable();
    }

    @Override
    public ImmutableShortList newWithout(short element)
    {
        return ShortLists.mutable.withAll(this).without(element).toImmutable();
    }

    @Override
    public ImmutableShortList newWithAll(ShortIterable elements)
    {
        return ShortLists.mutable.withAll(this).withAll(elements).toImmutable();
    }

    @Override
    public ImmutableShortList newWithoutAll(ShortIterable elements)
    {
        return ShortLists.mutable.withAll(this).withoutAll(elements).toImmutable();
    }

    @Override
    public ImmutableList<ShortShortPair> zipShort(ShortIterable iterable)
    {
        int size = this.size();
        int othersize = iterable.size();
        MutableList<ShortShortPair> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        ShortIterator iterator = this.shortIterator();
        ShortIterator otherIterator = iterable.shortIterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public <T> ImmutableList<ShortObjectPair<T>> zip(Iterable<T> iterable)
    {
        int size = this.size();
        int othersize = Iterate.sizeOf(iterable);
        MutableList<ShortObjectPair<T>> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        ShortIterator iterator = this.shortIterator();
        Iterator<T> otherIterator = iterable.iterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public MutableShortStack toStack()
    {
        return ShortStacks.mutable.withAll(this);
    }

    private static short calculateAdjustedStep(short from, short to, short stepBy)
    {
        int direction = Integer.signum(to - from);
        return direction == 0 ? stepBy : (short) (direction * stepBy);
    }

    private class ShortIntervalIterator implements ShortIterator
    {
        private long current = ShortInterval.this.from;

        @Override
        public boolean hasNext()
        {
            if (ShortInterval.this.from <= ShortInterval.this.to)
            {
                return this.current <= ShortInterval.this.to;
            }
            return this.current >= ShortInterval.this.to;
        }

        @Override
        public short next()
        {
            if (this.hasNext())
            {
                short result = (short) this.current;
                this.current += ShortInterval.this.step;
                return result;
            }
            throw new NoSuchElementException();
        }
    }

    private void readObject(ObjectInputStream ois)
            throws IOException, ClassNotFoundException
    {
        ois.defaultReadObject();
        this.size = IntervalUtils.intSize(this.from, this.to, this.step);
    }
}

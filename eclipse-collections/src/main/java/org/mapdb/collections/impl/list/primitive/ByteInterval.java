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

import org.mapdb.collections.api.ByteIterable;
import org.mapdb.collections.api.LazyByteIterable;
import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.bag.primitive.MutableByteBag;
import org.mapdb.collections.api.block.function.primitive.ByteToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectByteIntToObjectFunction;
import org.mapdb.collections.api.block.function.primitive.ObjectByteToObjectFunction;
import org.mapdb.collections.api.block.predicate.primitive.BytePredicate;
import org.mapdb.collections.api.block.procedure.primitive.ByteIntProcedure;
import org.mapdb.collections.api.block.procedure.primitive.ByteProcedure;
import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.factory.primitive.ByteBags;
import org.mapdb.collections.api.factory.primitive.ByteLists;
import org.mapdb.collections.api.factory.primitive.ByteSets;
import org.mapdb.collections.api.iterator.ByteIterator;
import org.mapdb.collections.api.list.ImmutableList;
import org.mapdb.collections.api.list.MutableList;
import org.mapdb.collections.api.list.primitive.ByteList;
import org.mapdb.collections.api.list.primitive.ImmutableByteList;
import org.mapdb.collections.api.list.primitive.MutableByteList;
import org.mapdb.collections.api.set.primitive.MutableByteSet;
import org.mapdb.collections.api.stack.primitive.MutableByteStack;
import org.mapdb.collections.api.tuple.primitive.ByteBytePair;
import org.mapdb.collections.api.tuple.primitive.ByteObjectPair;
import org.mapdb.collections.impl.factory.primitive.ByteStacks;
import org.mapdb.collections.impl.lazy.primitive.CollectByteToObjectIterable;
import org.mapdb.collections.impl.lazy.primitive.LazyByteIterableAdapter;
import org.mapdb.collections.impl.lazy.primitive.ReverseByteIterable;
import org.mapdb.collections.impl.lazy.primitive.SelectByteIterable;
import org.mapdb.collections.impl.list.IntervalUtils;
import org.mapdb.collections.impl.tuple.primitive.PrimitiveTuples;
import org.mapdb.collections.impl.utility.Iterate;

/**
 * A ByteInterval is a range of bytes that may be iterated over using a step value.
 */
public final class ByteInterval
        implements ImmutableByteList, Serializable
{
    private static final long serialVersionUID = 1L;

    private final byte from;
    private final byte to;
    private final byte step;
    private transient int size;

    private ByteInterval(byte from, byte to, byte step)
    {
        this.from = from;
        this.to = to;
        this.step = step;
        this.size = IntervalUtils.intSize(this.from, this.to, this.step);
    }

    /**
     * This static {@code from} method allows ByteInterval to act as a fluent builder for itself.
     * It works in conjunction with the instance methods {@link #to(byte)} and {@link #by(byte)}.
     * <p>
     * Usage Example:
     * <pre>
     * ByteInterval interval1 = ByteInterval.from((byte) 1).to((byte) 5);                 // results in: 1, 2, 3, 4, 5.
     * ByteInterval interval2 = ByteInterval.from((byte) 1).to((byte) 10).by((byte) 2);   // results in: 1, 3, 5, 7, 9.
     * </pre>
     */
    public static ByteInterval from(byte newFrom)
    {
        return ByteInterval.fromToBy(newFrom, newFrom, (byte) 1);
    }

    /**
     * This instance {@code to} method allows ByteInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(byte)} and instance method {@link #by(byte)}.
     */
    public ByteInterval to(byte newTo)
    {
        byte adjustedStep = ByteInterval.calculateAdjustedStep(this.from, newTo, this.step);
        return ByteInterval.fromToBy(this.from, newTo, adjustedStep);
    }

    /**
     * This instance {@code by} method allows ByteInterval to act as a fluent builder for itself.
     * It works in conjunction with the static method {@link #from(byte)} and instance method {@link #to(byte)}.
     */
    public ByteInterval by(byte newStep)
    {
        return ByteInterval.fromToBy(this.from, this.to, newStep);
    }

    /**
     * Returns a ByteInterval starting at zero.
     */
    public static ByteInterval zero()
    {
        return ByteInterval.from((byte) 0);
    }

    /**
     * Returns a ByteInterval starting from 1 to the specified count value with a step value of 1.
     */
    public static ByteInterval oneTo(byte count)
    {
        byte adjustedStep = ByteInterval.calculateAdjustedStep((byte) 1, count, (byte) 1);
        return ByteInterval.oneToBy(count, adjustedStep);
    }

    /**
     * Returns a ByteInterval starting from 1 to the specified count value with a step value of step.
     */
    public static ByteInterval oneToBy(byte count, byte step)
    {
        return ByteInterval.fromToBy((byte) 1, count, step);
    }

    /**
     * Returns a ByteInterval starting from 0 to the specified count value with a step value of 1.
     */
    public static ByteInterval zeroTo(byte count)
    {
        byte adjustedStep = ByteInterval.calculateAdjustedStep((byte) 0, count, (byte) 1);
        return ByteInterval.zeroToBy(count, adjustedStep);
    }

    /**
     * Returns a ByteInterval starting from 0 to the specified count value with a step value of step.
     */
    public static ByteInterval zeroToBy(byte count, byte step)
    {
        return ByteInterval.fromToBy((byte) 0, count, step);
    }

    /**
     * Returns a ByteInterval starting from the value from to the specified value to with a step value of 1.
     */
    public static ByteInterval fromTo(byte from, byte to)
    {
        if (from <= to)
        {
            return ByteInterval.fromToBy(from, to, (byte) 1);
        }
        return ByteInterval.fromToBy(from, to, (byte) -1);
    }

    /**
     * Returns a ByteInterval representing the even values from the value from to the value to.
     */
    public static ByteInterval evensFromTo(byte from, byte to)
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
        return ByteInterval.fromToBy(from, to, to > from ? (byte) 2 : (byte) -2);
    }

    /**
     * Returns a ByteInterval representing the odd values from the value from to the value to.
     */
    public static ByteInterval oddsFromTo(byte from, byte to)
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
        return ByteInterval.fromToBy(from, to, to > from ? (byte) 2 : (byte) -2);
    }

    /**
     * Returns a ByteInterval for the range of bytes inclusively between from and to with the specified
     * stepBy value.
     */
    public static ByteInterval fromToBy(byte from, byte to, byte stepBy)
    {
        IntervalUtils.checkArguments(from, to, stepBy);
        return new ByteInterval(from, to, stepBy);
    }

    /**
     * Returns true if the ByteInterval contains all the specified byte values.
     */
    @Override
    public boolean containsAll(byte... values)
    {
        for (byte value : values)
        {
            if (!this.contains(value))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean containsAll(ByteIterable source)
    {
        for (ByteIterator iterator = source.byteIterator(); iterator.hasNext(); )
        {
            if (!this.contains(iterator.next()))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the ByteInterval contains the specified byte value.
     */
    @Override
    public boolean contains(byte value)
    {
        return IntervalUtils.contains(value, this.from, this.to, this.step);
    }

    @Override
    public void forEachWithIndex(ByteIntProcedure procedure)
    {
        int index = 0;
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                procedure.value((byte) i, index++);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                procedure.value((byte) i, index++);
            }
        }
    }

    private boolean goForward()
    {
        return this.from <= this.to && this.step > 0;
    }

    @Override
    public void each(ByteProcedure procedure)
    {
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                procedure.value((byte) i);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                procedure.value((byte) i);
            }
        }
    }

    @Override
    public int count(BytePredicate predicate)
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
    public boolean anySatisfy(BytePredicate predicate)
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
    public boolean allSatisfy(BytePredicate predicate)
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
        if (!(otherList instanceof ByteList))
        {
            return false;
        }
        ByteList list = (ByteList) otherList;
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
                if ((byte) i != list.get(listIndex++))
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
                if ((byte) i != list.get(listIndex++))
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
                hashCode = 31 * hashCode + (int) (byte) i;
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                hashCode = 31 * hashCode + (int) (byte) i;
            }
        }
        return hashCode;
    }

    /**
     * Returns a new ByteInterval with the from and to values reversed and the step value negated.
     */
    @Override
    public ByteInterval toReversed()
    {
        // A byte step is promoted to int before negation, so -step never
        // overflows an int; but -Byte.MIN_VALUE (128) is not representable as a
        // byte. Per spec/algorithms.md "Reversed() panics at minimum step",
        // reject it explicitly rather than narrowing the negation silently.
        if (this.step == Byte.MIN_VALUE)
        {
            throw new ArithmeticException("Cannot reverse a ByteInterval with the minimum step value");
        }
        return ByteInterval.fromToBy(this.to, this.from, (byte) -this.step);
    }

    @Override
    public ImmutableByteList distinct()
    {
        return this;
    }

    @Override
    public ImmutableByteList subList(int fromIndex, int toIndex)
    {
        return ByteInterval.fromToBy(this.get(fromIndex), this.get(toIndex - 1), this.step);
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
    public long dotProduct(ByteList list)
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
                byte value = this.get(i);
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
    public byte[] toArray()
    {
        byte[] result = new byte[this.size()];
        this.forEachWithIndex((each, index) -> result[index] = each);
        return result;
    }

    @Override
    public byte[] toArray(byte[] result)
    {
        if (result.length < this.size())
        {
            result = new byte[this.size()];
        }
        byte[] finalBypass = result;
        this.forEachWithIndex((each, index) -> finalBypass[index] = each);
        return result;
    }

    @Override
    public <T> T injectInto(T injectedValue, ObjectByteToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                result = function.valueOf(result, (byte) i);
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                result = function.valueOf(result, (byte) i);
            }
        }
        return result;
    }

    @Override
    public <T> T injectIntoWithIndex(T injectedValue, ObjectByteIntToObjectFunction<? super T, ? extends T> function)
    {
        T result = injectedValue;
        int index = 0;

        if (this.goForward())
        {
            for (long i = this.from; i <= this.to; i += this.step)
            {
                result = function.valueOf(result, (byte) i, index);
                index++;
            }
        }
        else
        {
            for (long i = this.from; i >= this.to; i += this.step)
            {
                result = function.valueOf(result, (byte) i, index);
                index++;
            }
        }
        return result;
    }

    @Override
    public RichIterable<ByteIterable> chunk(int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Size for groups must be positive but was: " + size);
        }
        MutableList<ByteIterable> result = Lists.mutable.empty();
        if (this.notEmpty())
        {
            byte innerFrom = this.from;
            byte lastUpdated = this.from;
            if (this.from <= this.to)
            {
                while ((lastUpdated + this.step) <= this.to)
                {
                    MutableByteList batch = ByteLists.mutable.empty();
                    for (long i = innerFrom; i <= this.to && batch.size() < size; i += this.step)
                    {
                        batch.add((byte) i);
                        lastUpdated = (byte) i;
                    }
                    result.add(batch);
                    innerFrom = (byte) (lastUpdated + this.step);
                }
            }
            else
            {
                while ((lastUpdated + this.step) >= this.to)
                {
                    MutableByteList batch = ByteLists.mutable.empty();
                    for (long i = innerFrom; i >= this.to && batch.size() < size; i += this.step)
                    {
                        batch.add((byte) i);
                        lastUpdated = (byte) i;
                    }
                    result.add(batch);
                    innerFrom = (byte) (lastUpdated + this.step);
                }
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
    public ByteIterator byteIterator()
    {
        return new ByteIntervalIterator();
    }

    @Override
    public byte getFirst()
    {
        return this.from;
    }

    @Override
    public byte getLast()
    {
        return (byte) IntervalUtils.valueAtIndex(this.size() - 1, this.from, this.to, this.step);
    }

    @Override
    public byte get(int index)
    {
        this.checkBounds("index", index);
        return (byte) IntervalUtils.valueAtIndex(index, this.from, this.to, this.step);
    }

    private void checkBounds(String name, int index)
    {
        if (index < 0 || index >= this.size())
        {
            throw new IndexOutOfBoundsException(name + ": " + index + ' ' + this);
        }
    }

    @Override
    public int indexOf(byte value)
    {
        return IntervalUtils.indexOf(value, this.from, this.to, this.step);
    }

    @Override
    public int lastIndexOf(byte value)
    {
        return this.indexOf(value);
    }

    @Override
    public ImmutableByteList select(BytePredicate predicate)
    {
        return ByteLists.mutable.withAll(new SelectByteIterable(this, predicate)).toImmutable();
    }

    @Override
    public ImmutableByteList reject(BytePredicate predicate)
    {
        return ByteLists.mutable.withAll(new SelectByteIterable(this, value -> !predicate.accept(value))).toImmutable();
    }

    @Override
    public byte detectIfNone(BytePredicate predicate, byte ifNone)
    {
        return new SelectByteIterable(this, predicate).detectIfNone(predicate, ifNone);
    }

    @Override
    public <V> ImmutableList<V> collect(ByteToObjectFunction<? extends V> function)
    {
        return new CollectByteToObjectIterable<V>(this, function).toList().toImmutable();
    }

    @Override
    public LazyByteIterable asReversed()
    {
        return ReverseByteIterable.adapt(this);
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
    public byte max()
    {
        if (this.from >= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public byte min()
    {
        if (this.from <= this.to)
        {
            return this.getFirst();
        }
        return this.getLast();
    }

    @Override
    public byte minIfEmpty(byte defaultValue)
    {
        return this.min();
    }

    @Override
    public byte maxIfEmpty(byte defaultValue)
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
    public int binarySearch(byte value)
    {
        return IntervalUtils.binarySearch(value, this.from, this.to, this.step);
    }

    @Override
    public byte[] toSortedArray()
    {
        byte[] array = this.toArray();
        Arrays.sort(array);
        return array;
    }

    @Override
    public MutableByteList toList()
    {
        return ByteLists.mutable.withAll(this);
    }

    @Override
    public MutableByteList toSortedList()
    {
        return ByteLists.mutable.withAll(this).sortThis();
    }

    @Override
    public MutableByteSet toSet()
    {
        return ByteSets.mutable.withAll(this);
    }

    @Override
    public MutableByteBag toBag()
    {
        return ByteBags.mutable.withAll(this);
    }

    @Override
    public LazyByteIterable asLazy()
    {
        return new LazyByteIterableAdapter(this);
    }

    @Override
    public ImmutableByteList toImmutable()
    {
        return this;
    }

    @Override
    public ImmutableByteList newWith(byte element)
    {
        return ByteLists.mutable.withAll(this).with(element).toImmutable();
    }

    @Override
    public ImmutableByteList newWithout(byte element)
    {
        return ByteLists.mutable.withAll(this).without(element).toImmutable();
    }

    @Override
    public ImmutableByteList newWithAll(ByteIterable elements)
    {
        return ByteLists.mutable.withAll(this).withAll(elements).toImmutable();
    }

    @Override
    public ImmutableByteList newWithoutAll(ByteIterable elements)
    {
        return ByteLists.mutable.withAll(this).withoutAll(elements).toImmutable();
    }

    @Override
    public ImmutableList<ByteBytePair> zipByte(ByteIterable iterable)
    {
        int size = this.size();
        int othersize = iterable.size();
        MutableList<ByteBytePair> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        ByteIterator iterator = this.byteIterator();
        ByteIterator otherIterator = iterable.byteIterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public <T> ImmutableList<ByteObjectPair<T>> zip(Iterable<T> iterable)
    {
        int size = this.size();
        int othersize = Iterate.sizeOf(iterable);
        MutableList<ByteObjectPair<T>> target = Lists.mutable.withInitialCapacity(Math.min(size, othersize));
        ByteIterator iterator = this.byteIterator();
        Iterator<T> otherIterator = iterable.iterator();
        for (int i = 0; i < size && otherIterator.hasNext(); i++)
        {
            target.add(PrimitiveTuples.pair(iterator.next(), otherIterator.next()));
        }
        return target.toImmutable();
    }

    @Override
    public MutableByteStack toStack()
    {
        return ByteStacks.mutable.withAll(this);
    }

    private static byte calculateAdjustedStep(byte from, byte to, byte stepBy)
    {
        int direction = Integer.signum(to - from);
        return direction == 0 ? stepBy : (byte) (direction * stepBy);
    }

    private class ByteIntervalIterator implements ByteIterator
    {
        private long current = ByteInterval.this.from;

        @Override
        public boolean hasNext()
        {
            if (ByteInterval.this.from <= ByteInterval.this.to)
            {
                return this.current <= ByteInterval.this.to;
            }
            return this.current >= ByteInterval.this.to;
        }

        @Override
        public byte next()
        {
            if (this.hasNext())
            {
                byte result = (byte) this.current;
                this.current += ByteInterval.this.step;
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

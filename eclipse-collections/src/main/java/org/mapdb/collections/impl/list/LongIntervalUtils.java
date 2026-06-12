/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.list;

/**
 * Utility class for the boundary-correct arithmetic behind {@link
 * org.mapdb.collections.impl.list.primitive.LongInterval}.
 *
 * <p>Per spec/algorithms.md "Interval over signed integers", size/contains/get
 * must use wider-than-element arithmetic to avoid overflow at numeric bounds.
 * For an {@code int}-typed interval {@link IntervalUtils} can simply widen
 * everything to {@code long} (the uint64-equivalent). For a {@code long}-typed
 * interval there is no wider signed primitive, so the wider arithmetic is
 * <em>unsigned</em> 64-bit:
 *
 * <ul>
 *   <li>The distance between two longs is {@code to - from} computed in plain
 *       two's-complement, which is exactly the unsigned distance modulo 2^64
 *       (when the interval is ordered correctly, i.e. {@code from <= to} for a
 *       positive step), so {@link Long#divideUnsigned} / {@link
 *       Long#remainderUnsigned} give the true element count / on-grid test even
 *       for a range as wide as {@code [Long.MIN_VALUE, Long.MAX_VALUE]}.</li>
 *   <li>The magnitude of the step is {@code -step} as a bit pattern: for any
 *       non-zero {@code step} (including {@code Long.MIN_VALUE}, whose negation
 *       wraps back to itself) {@code -step} is the correct unsigned divisor,
 *       because the unsigned value of {@code 0x8000_0000_0000_0000} is exactly
 *       2^63, the true magnitude of {@code Long.MIN_VALUE}.</li>
 *   <li>{@code value - from} is likewise the unsigned offset of {@code value}
 *       from {@code from} along the ascending direction (and {@code from -
 *       value} for a descending interval).</li>
 *   <li>The "is within boundaries" test uses ordinary <em>signed</em>
 *       comparisons of {@code from}/{@code to}/{@code value} — these are real
 *       long values, not distances, so signed comparison is correct.</li>
 *   <li>{@code valueAtIndex} returns {@code from + step * index} computed in
 *       wrapping two's-complement: the true value is in range (it is {@code <=
 *       to} for a positive step), and although {@code step * index} may overflow
 *       a signed long in isolation, the sum {@code from + step * index} wraps
 *       back to the correct in-range value. We deliberately do NOT clamp with
 *       {@code Math.min(value, to)} the way {@link IntervalUtils} can, because a
 *       wrapped intermediate could be larger than {@code to} as a signed value
 *       and the clamp would misfire.</li>
 * </ul>
 */
public final class LongIntervalUtils
{
    private LongIntervalUtils()
    {
        throw new AssertionError("Suppress default constructor for noninstantiability");
    }

    public static void checkArguments(long from, long to, long stepBy)
    {
        LongIntervalUtils.checkStepBy(from, to, stepBy);
        LongIntervalUtils.checkSize(from, to, stepBy);
    }

    private static void checkStepBy(long from, long to, long stepBy)
    {
        if (stepBy == 0L)
        {
            throw new IllegalArgumentException("Cannot use a step by of 0");
        }
        if (from > to && stepBy > 0L || from < to && stepBy < 0L)
        {
            throw new IllegalArgumentException("Step by is incorrect for the range");
        }
    }

    private static void checkSize(long from, long to, long stepBy)
    {
        // unsignedCount = floor(unsignedDistance / |stepBy|), the number of
        // steps; the element count is unsignedCount + 1. Reject when the element
        // count cannot fit in an int (size() returns int). The largest allowed
        // element count is Integer.MAX_VALUE, i.e. a step count of
        // Integer.MAX_VALUE - 1; a step count of Integer.MAX_VALUE
        // (Integer.MAX_VALUE + 1 elements) is rejected.
        long unsignedCount = LongIntervalUtils.unsignedStepCount(from, to, stepBy);
        // size = unsignedCount + 1 must be <= Integer.MAX_VALUE, i.e.
        // unsignedCount <= Integer.MAX_VALUE - 1. unsignedCount is a genuine
        // unsigned value (it can exceed Long.MAX_VALUE as a bit pattern for
        // huge ranges), so compare via Long.compareUnsigned.
        if (Long.compareUnsigned(unsignedCount, (long) Integer.MAX_VALUE - 1L) > 0)
        {
            String rangeSize = Long.compareUnsigned(unsignedCount, Long.MAX_VALUE - 1L) >= 0
                    ? "more than " + Long.MAX_VALUE
                    : Long.toString(unsignedCount + 1L);
            throw new IllegalArgumentException("Range size: "
                    + rangeSize
                    + " exceeds max size() of "
                    + Integer.MAX_VALUE);
        }
    }

    /**
     * The unsigned number of steps from {@code from} to {@code to}; the element
     * count is this value plus one. Assumes {@link #checkStepBy} has accepted
     * the arguments (step non-zero and consistent in direction with the range).
     */
    private static long unsignedStepCount(long from, long to, long step)
    {
        if (step > 0L)
        {
            // from <= to: (to - from) is the unsigned distance.
            return Long.divideUnsigned(to - from, step);
        }
        // step < 0L and from >= to: (from - to) is the unsigned distance, and
        // (-step) is the unsigned magnitude of the step.
        return Long.divideUnsigned(from - to, -step);
    }

    public static int intSize(long from, long to, long step)
    {
        // checkSize guarantees this fits in an int.
        return (int) (LongIntervalUtils.unsignedStepCount(from, to, step) + 1L);
    }

    /**
     * The element count of {@code [from, to] by step}, as a {@code long}. Like
     * {@link #intSize} but without narrowing to {@code int}; used by the
     * spliterator's {@code estimateSize}. Boundary-correct: it uses the unsigned
     * step count rather than {@code (to - from) / step}, which overflows for a
     * wide-but-sparse range such as {@code [Long.MIN_VALUE, Long.MAX_VALUE]} by
     * {@code Long.MAX_VALUE}.
     */
    public static long longSize(long from, long to, long step)
    {
        return LongIntervalUtils.unsignedStepCount(from, to, step) + 1L;
    }

    public static boolean contains(long value, long from, long to, long step)
    {
        return LongIntervalUtils.isWithinBoundaries(value, from, to, step)
                && LongIntervalUtils.isOnGrid(value, from, step);
    }

    public static boolean isWithinBoundaries(long value, long from, long to, long step)
    {
        return step > 0L && from <= value && value <= to
                || step < 0L && to <= value && value <= from;
    }

    /**
     * True when {@code value} lies exactly on the step grid anchored at {@code
     * from}. {@code value - from} (ascending) or {@code from - value}
     * (descending) is the unsigned offset; it is divisible by the unsigned step
     * magnitude {@code -step} (descending) / {@code step} (ascending) iff the
     * unsigned remainder is zero.
     */
    private static boolean isOnGrid(long value, long from, long step)
    {
        if (step > 0L)
        {
            return Long.remainderUnsigned(value - from, step) == 0L;
        }
        return Long.remainderUnsigned(from - value, -step) == 0L;
    }

    public static int indexOf(long value, long from, long to, long step)
    {
        if (!LongIntervalUtils.isWithinBoundaries(value, from, to, step))
        {
            return -1;
        }
        if (!LongIntervalUtils.isOnGrid(value, from, step))
        {
            return -1;
        }
        // The index is the unsigned step count from `from` to `value`; it is
        // bounded by size() <= Integer.MAX_VALUE, so it fits in an int.
        if (step > 0L)
        {
            return (int) Long.divideUnsigned(value - from, step);
        }
        return (int) Long.divideUnsigned(from - value, -step);
    }

    public static long valueAtIndex(int index, long from, long to, long step)
    {
        if (index <= 0)
        {
            return from;
        }
        // The true value is in range; `step * index` may overflow in isolation
        // but the two's-complement sum wraps back to the correct in-range value.
        // No Math.min/Math.max clamp: a wrapped intermediate could compare
        // larger/smaller than `to` as a signed value and a clamp would misfire.
        return from + step * (long) index;
    }

    public static int binarySearch(long value, long from, long to, long step)
    {
        if (step > 0L && from > value || step < 0L && from < value)
        {
            return -1;
        }

        if (step > 0L && to < value || step < 0L && to > value)
        {
            return -1 - LongIntervalUtils.intSize(from, to, step);
        }

        // value is within [from, to] (inclusive of direction). The unsigned
        // offset / step magnitude gives the (insertion) index.
        if (step > 0L)
        {
            long offset = value - from;
            int index = (int) Long.divideUnsigned(offset, step);
            return Long.remainderUnsigned(offset, step) == 0L ? index : (index + 2) * -1;
        }
        long offset = from - value;
        long magnitude = -step;
        int index = (int) Long.divideUnsigned(offset, magnitude);
        return Long.remainderUnsigned(offset, magnitude) == 0L ? index : (index + 2) * -1;
    }
}

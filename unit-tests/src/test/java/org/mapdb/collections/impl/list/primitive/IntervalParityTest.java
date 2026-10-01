/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 */

package org.mapdb.collections.impl.list.primitive;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.list.Interval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * evensFromTo/oddsFromTo across every Interval width: the result holds exactly
 * the values of that parity in [from, to], in the from-to direction. A one-value
 * range of the other parity holds none and throws IllegalArgumentException
 * instead of returning an out-of-range neighbour or wrapping at the type bound.
 */
public class IntervalParityTest
{
    private interface Factory
    {
        long[] build(long from, long to, boolean even);
    }

    private static final Factory BYTE = (from, to, even) -> widen(even
            ? ByteInterval.evensFromTo((byte) from, (byte) to).toArray()
            : ByteInterval.oddsFromTo((byte) from, (byte) to).toArray());

    private static final Factory SHORT = (from, to, even) -> widen(even
            ? ShortInterval.evensFromTo((short) from, (short) to).toArray()
            : ShortInterval.oddsFromTo((short) from, (short) to).toArray());

    private static final Factory INT = (from, to, even) -> widen(even
            ? IntInterval.evensFromTo((int) from, (int) to).toArray()
            : IntInterval.oddsFromTo((int) from, (int) to).toArray());

    private static final Factory LONG = (from, to, even) -> even
            ? LongInterval.evensFromTo(from, to).toArray()
            : LongInterval.oddsFromTo(from, to).toArray();

    private static final Factory OBJECT = (from, to, even) -> widen(even
            ? Interval.evensFromTo((int) from, (int) to).toIntArray()
            : Interval.oddsFromTo((int) from, (int) to).toIntArray());

    @Test
    public void byteAllPairs()
    {
        for (long from = Byte.MIN_VALUE; from <= Byte.MAX_VALUE; from++)
        {
            for (long to = Byte.MIN_VALUE; to <= Byte.MAX_VALUE; to++)
            {
                check(BYTE, from, to);
            }
        }
    }

    @Test
    public void shortWindows()
    {
        checkWindows(SHORT, Short.MIN_VALUE, Short.MAX_VALUE, true);
    }

    @Test
    public void intWindows()
    {
        checkWindows(INT, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
    }

    @Test
    public void longWindows()
    {
        checkWindows(LONG, Long.MIN_VALUE, Long.MAX_VALUE, false);
    }

    @Test
    public void objectIntervalWindows()
    {
        checkWindows(OBJECT, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
    }

    @Test
    public void singletonsOfTheOtherParityThrow()
    {
        assertThrows(IllegalArgumentException.class, () -> IntInterval.evensFromTo(1, 1));
        assertThrows(IllegalArgumentException.class, () -> IntInterval.oddsFromTo(4, 4));
        assertThrows(IllegalArgumentException.class, () -> ByteInterval.oddsFromTo(Byte.MIN_VALUE, Byte.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ShortInterval.oddsFromTo(Short.MIN_VALUE, Short.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ByteInterval.evensFromTo(Byte.MAX_VALUE, Byte.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ShortInterval.evensFromTo(Short.MAX_VALUE, Short.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> LongInterval.oddsFromTo(Long.MIN_VALUE, Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Interval.evensFromTo(-3, -3));
    }

    /**
     * All pairs within three windows (both bounds and zero), plus, when
     * {@code crossWindows}, pairs spanning windows (full-width results).
     */
    private static void checkWindows(Factory factory, long min, long max, boolean crossWindows)
    {
        long[][] windows = {{min, min + 6}, {-6, 6}, {max - 6, max}};
        for (long[] a : windows)
        {
            for (long[] b : windows)
            {
                if (a != b && !crossWindows)
                {
                    continue;
                }
                // Offsets, not from <= a[1]: a window ending at Long.MAX_VALUE would overflow.
                for (long i = 0; i <= a[1] - a[0]; i++)
                {
                    for (long j = 0; j <= b[1] - b[0]; j++)
                    {
                        check(factory, a[0] + i, b[0] + j);
                    }
                }
            }
        }
    }

    private static void check(Factory factory, long from, long to)
    {
        for (boolean even : new boolean[]{true, false})
        {
            long[] expected = expected(from, to, even);
            if (expected.length == 0)
            {
                assertThrows(IllegalArgumentException.class, () -> factory.build(from, to, even),
                        () -> (even ? "evens" : "odds") + "FromTo(" + from + ", " + to + ")");
            }
            else
            {
                assertArrayEquals(expected, factory.build(from, to, even),
                        () -> (even ? "evens" : "odds") + "FromTo(" + from + ", " + to + ")");
            }
        }
    }

    /** Brute-force reference: every value of the parity in [from, to], walked from {@code from}. */
    private static long[] expected(long from, long to, boolean even)
    {
        long step = from <= to ? 1 : -1;
        long n = 0;
        for (long v = from; ; v += step)
        {
            if (Math.floorMod(v, 2) == (even ? 0 : 1))
            {
                n++;
            }
            if (v == to)
            {
                break;
            }
        }
        long[] result = new long[(int) n];
        int i = 0;
        for (long v = from; ; v += step)
        {
            if (Math.floorMod(v, 2) == (even ? 0 : 1))
            {
                result[i++] = v;
            }
            if (v == to)
            {
                break;
            }
        }
        return result;
    }

    private static long[] widen(byte[] values)
    {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++)
        {
            result[i] = values[i];
        }
        return result;
    }

    private static long[] widen(short[] values)
    {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++)
        {
            result[i] = values[i];
        }
        return result;
    }

    private static long[] widen(int[] values)
    {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++)
        {
            result[i] = values[i];
        }
        return result;
    }
}

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

import java.util.concurrent.atomic.AtomicInteger;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.ByteIterable;
import org.mapdb.collections.api.iterator.ByteIterator;
import org.mapdb.collections.impl.list.primitive.ByteInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ByteInterval boundary tests, per spec/algorithms.md "Interval over signed
 * integers". A byte-typed interval computes in long (the wide type), which
 * trivially cannot overflow for byte-magnitude operands, so size/contains/get
 * never wrap at numeric bounds. step 0 throws, and toReversed at the minimum
 * step throws explicitly because -Byte.MIN_VALUE is unrepresentable as a byte.
 */
public class ByteIntervalBoundaryTest
{
    @Test
    public void boundaryDoesNotWrap()
    {
        // A tight interval at the top of the byte domain.
        ByteInterval iv = ByteInterval.fromToBy((byte) 126, (byte) 127, (byte) 1);
        assertEquals(2, iv.size());
        assertEquals((byte) 126, iv.get(0));
        assertEquals((byte) 127, iv.get(1));
        assertTrue(iv.contains((byte) 126));
        assertTrue(iv.contains((byte) 127));
        assertFalse(iv.contains((byte) 125));
    }

    @Test
    public void fullByteRangeDoesNotWrap()
    {
        // [Byte.MIN_VALUE, Byte.MAX_VALUE] by Byte.MAX_VALUE.
        // distance 255 / step 127 == 2, so size 3.
        // values: -128, -128+127 == -1, -128+254 == 126.
        ByteInterval iv = ByteInterval.fromToBy(Byte.MIN_VALUE, Byte.MAX_VALUE, Byte.MAX_VALUE);
        assertEquals(3, iv.size());
        assertEquals(Byte.MIN_VALUE, iv.get(0));
        assertEquals((byte) -1, iv.get(1));
        assertEquals((byte) 126, iv.get(2));
        assertTrue(iv.contains(Byte.MIN_VALUE));
        assertTrue(iv.contains((byte) -1));
        assertTrue(iv.contains((byte) 126));
        // Byte.MAX_VALUE (127) is within [from, to] but NOT on the step grid.
        assertFalse(iv.contains(Byte.MAX_VALUE));
    }

    @Test
    public void negativeRangeContainsNoWrap()
    {
        ByteInterval iv = ByteInterval.fromToBy(Byte.MIN_VALUE, (byte) (Byte.MIN_VALUE + 10), (byte) 2);
        assertEquals(6, iv.size());
        assertEquals(Byte.MIN_VALUE, iv.get(0));
        assertEquals((byte) (Byte.MIN_VALUE + 10), iv.get(5));
        assertTrue(iv.contains((byte) (Byte.MIN_VALUE + 4)));
        assertFalse(iv.contains((byte) (Byte.MIN_VALUE + 5)));
    }

    @Test
    public void fromToByStepZeroThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> ByteInterval.fromToBy((byte) 0, (byte) 10, (byte) 0));
    }

    @Test
    public void toReversedMinimumStepThrows()
    {
        // Build a valid interval whose step is Byte.MIN_VALUE (size 2),
        // then reverse it: -step (128) is not representable as a byte and must
        // be rejected explicitly instead of narrowing back to Byte.MIN_VALUE.
        ByteInterval iv = ByteInterval.fromToBy((byte) 0, Byte.MIN_VALUE, Byte.MIN_VALUE);
        assertEquals(2, iv.size());
        ArithmeticException ex = assertThrows(ArithmeticException.class, iv::toReversed);
        assertTrue(ex.getMessage().toLowerCase().contains("minimum step"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    public void toReversedNormalStepWorks()
    {
        ByteInterval iv = ByteInterval.fromToBy((byte) 1, (byte) 10, (byte) 1);
        ByteInterval rev = iv.toReversed();
        assertEquals((byte) 10, rev.get(0));
        assertEquals((byte) 1, rev.get(rev.size() - 1));
        assertEquals(iv.size(), rev.size());
    }

    @Test
    @Timeout(10)
    public void fullDomainTraversalDoesNotWrapOrOverRun()
    {
        // The literal full byte domain [MIN, MAX] step 1 == 256 elements.
        ByteInterval iv = ByteInterval.fromToBy(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 1);
        assertEquals(256, iv.size());

        byte[] expected = new byte[256];
        for (int i = 0; i < 256; i++)
        {
            expected[i] = (byte) (Byte.MIN_VALUE + i);
        }

        byte[] array = iv.toArray();
        assertEquals(iv.size(), array.length);
        assertArrayEquals(expected, array);

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());

        byte[] seen = new byte[iv.size()];
        AtomicInteger fewiCount = new AtomicInteger();
        iv.forEachWithIndex((value, index) ->
        {
            seen[index] = value;
            fewiCount.incrementAndGet();
        });
        assertEquals(iv.size(), fewiCount.get());
        assertArrayEquals(expected, seen);

        ByteIterator iterator = iv.byteIterator();
        int iterCount = 0;
        while (iterator.hasNext())
        {
            assertEquals(expected[iterCount], iterator.next());
            iterCount++;
        }
        assertEquals(iv.size(), iterCount);
        assertFalse(iterator.hasNext());
    }

    @Test
    @Timeout(10)
    public void descendingFullDomainTraversalDoesNotWrapOrOverRun()
    {
        ByteInterval iv = ByteInterval.fromToBy(Byte.MAX_VALUE, Byte.MIN_VALUE, (byte) -1);
        assertEquals(256, iv.size());

        byte[] expected = new byte[256];
        for (int i = 0; i < 256; i++)
        {
            expected[i] = (byte) (Byte.MAX_VALUE - i);
        }
        assertArrayEquals(expected, iv.toArray());

        AtomicInteger eachCount = new AtomicInteger();
        iv.each(value -> eachCount.incrementAndGet());
        assertEquals(iv.size(), eachCount.get());
    }

    @Test
    public void chunkAtExtremePartitionsAllElements()
    {
        ByteInterval iv = ByteInterval.fromToBy((byte) (Byte.MAX_VALUE - 2), Byte.MAX_VALUE, (byte) 1);
        assertEquals(3, iv.size());
        RichIterable<ByteIterable> chunks = iv.chunk(2);
        assertEquals(2, chunks.size());
        assertArrayEquals(new byte[] {(byte) (Byte.MAX_VALUE - 2), (byte) (Byte.MAX_VALUE - 1)},
                chunks.getFirst().toArray());
        assertArrayEquals(new byte[] {Byte.MAX_VALUE}, chunks.getLast().toArray());
    }

    @Test
    public void chunkOfSingletonReturnsSingleBatch()
    {
        // Pre-fix this returns [] (element dropped); must be [[5]].
        RichIterable<ByteIterable> chunks = ByteInterval.fromToBy((byte) 5, (byte) 5, (byte) 1).chunk(2);
        assertEquals(1, chunks.size());
        assertArrayEquals(new byte[] {5}, chunks.getFirst().toArray());
    }

    @Test
    public void chunkSizeNotLessThanIntervalReturnsSingleBatch()
    {
        RichIterable<ByteIterable> chunks = ByteInterval.fromToBy((byte) 1, (byte) 3, (byte) 1).chunk(10);
        assertEquals(1, chunks.size());
        assertArrayEquals(new byte[] {1, 2, 3}, chunks.getFirst().toArray());
    }
}

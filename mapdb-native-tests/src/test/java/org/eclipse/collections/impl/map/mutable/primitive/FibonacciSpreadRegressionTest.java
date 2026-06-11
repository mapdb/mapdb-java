/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl.map.mutable.primitive;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 64-bit Fibonacci hash spread regression, per spec/style/java.md
 * "Native-test obligations" / spec/algorithms.md "Hash function". The
 * high-32-bit-only i64 key family {1, 2^32+1, 2*2^32+1, ...} differs only in
 * the high word; a weak (low-bit or 32-bit) hash collapses them into the same
 * bucket. The 64-bit Fibonacci spread (with the i64 high-word fold) must spread
 * them across distinct buckets, mirroring the Go/Zig native tests.
 *
 * <p>Placed in the EC package so it can call the package-private {@code probe}.
 */
public class FibonacciSpreadRegressionTest
{
    @Test
    public void highWordOnlyKeysSpreadAcrossBuckets()
    {
        // Insert the high-32-bit-only family so the map grows to a large table.
        LongIntHashMap map = new LongIntHashMap();
        int n = 64;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++)
        {
            keys[i] = (long) i << 32 | 1L;   // 1, 2^32+1, 2*2^32+1, ...
            map.put(keys[i], i);
        }
        assertEquals(n, map.size());

        // Every key must round-trip (distinct keys, none lost in probing).
        for (int i = 0; i < n; i++)
        {
            assertTrue(map.containsKey(keys[i]), "lost key index " + i);
            assertEquals(i, map.get(keys[i]));
        }

        // The initial probe bucket for these keys must not all collapse to the
        // same slot. A low-bit-only or 32-bit hash would map every key (all
        // share low 32 bits == 1) to the same bucket; Fibonacci must spread.
        Set<Integer> buckets = new HashSet<>();
        for (long key : keys)
        {
            buckets.add(map.probe(key));
        }
        // Demand a strong majority of distinct initial buckets. (Exact value is
        // implementation-defined, but a broken hash yields ~1.)
        assertTrue(buckets.size() >= n * 3 / 4,
                "Fibonacci spread too weak: " + buckets.size() + " distinct buckets for " + n + " keys");
    }

    @Test
    public void intHighEntropyKeysSpread()
    {
        // Keys that share low bits but differ in high bits, for the int path.
        IntIntHashMap map = new IntIntHashMap();
        int n = 64;
        int[] keys = new int[n];
        for (int i = 0; i < n; i++)
        {
            keys[i] = (i << 20) | 0x3;   // share low bits, differ high
            map.put(keys[i], i);
        }
        assertEquals(n, map.size());
        Set<Integer> buckets = new HashSet<>();
        for (int key : keys)
        {
            assertTrue(map.containsKey(key));
            buckets.add(map.probe(key));
        }
        assertTrue(buckets.size() >= n * 3 / 4,
                "int Fibonacci spread too weak: " + buckets.size() + " distinct buckets");
    }
}

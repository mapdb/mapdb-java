/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.map.mutable.primitive;

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
 * <p>The assertions measure {@code spreadAndMask} -- the production Fibonacci
 * spread+mask used at probeTwo/probeThree and across the whole primitive-set
 * family -- <b>directly</b>, NOT the slot a present key happens to land in.
 * Probing a present key returns its storage slot, which is distinct per key
 * under any hash, so it cannot detect a spread regression; this test must look
 * at the spread function itself.
 *
 * <p><b>First-probe carve-out.</b> For primitive-primitive maps the very first
 * probe index is {@code mask((int) element)} -- the raw low 32 bits, with no
 * Fibonacci (see spec/style/java.md). The contrast assertions below pin that
 * fact: the same adversarial family that Fibonacci scatters shares identical
 * low-32 bits, so the first probe alone would funnel them into one chain and
 * Fibonacci only engages on the subsequent probes. Asserting both the spread
 * (must scatter) and the low-32 collapse (must funnel) keeps this test honest:
 * a regression that dropped Fibonacci back to the raw-bit hash would flip the
 * first assertion red.
 *
 * <p>Placed in the impl package so it can call the package-private
 * {@code spreadAndMask}.
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

        // The Fibonacci spread index (spreadAndMask) must scatter the family.
        // These keys all share low 32 bits == 1, so a hash that ignored the high
        // word (or the raw-bit first-probe index) would map every one to the same
        // bucket; Fibonacci must not.
        Set<Integer> fibBuckets = new HashSet<>();
        Set<Long> rawLow32 = new HashSet<>();
        for (long key : keys)
        {
            fibBuckets.add(map.spreadAndMask(key));
            rawLow32.add(key & 0xFFFFFFFFL);
        }
        // Demand a strong majority of distinct spread buckets. (Exact value is
        // implementation-defined, but a broken hash yields ~1.)
        assertTrue(fibBuckets.size() >= n * 3 / 4,
                "Fibonacci spread too weak: " + fibBuckets.size() + " distinct buckets for " + n + " keys");
        // Carve-out guard: the first probe uses mask((int) element) == raw low 32
        // bits, which are identical for the whole family -> a single chain. This
        // both documents the first-probe behavior and proves the assertion above
        // is doing real work (Fibonacci, not the raw bits, produced the scatter).
        assertEquals(1, rawLow32.size(),
                "expected the high-word-only family to share its low 32 bits");
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
        for (int i = 0; i < n; i++)
        {
            assertTrue(map.containsKey(keys[i]), "lost key index " + i);
        }

        // Measure the production Fibonacci spread directly, not the storage slot.
        Set<Integer> fibBuckets = new HashSet<>();
        for (int key : keys)
        {
            fibBuckets.add(map.spreadAndMask(key));
        }
        assertTrue(fibBuckets.size() >= n * 3 / 4,
                "int Fibonacci spread too weak: " + fibBuckets.size() + " distinct buckets");
    }
}

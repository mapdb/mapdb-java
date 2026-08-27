/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl;

import java.util.Arrays;

/**
 * Fenwick tree / Binary Indexed Tree (prefix &amp; range sums). Java port of the
 * frozen {@code feat/fenwick} reference (see {@code spec/features/fenwick.md}).
 *
 * <p>A fixed-size index structure with O(log n) point-update and O(log n)
 * prefix/range sum over signed 32-bit element values accumulated in a
 * <b>wrapping</b> 64-bit accumulator.
 *
 * <p>Pinned invariants realized here:
 * <ul>
 *   <li><b>Indexing</b>: the public API is 0-based ({@code 0 .. n-1}); the BIT is
 *       classically 1-based internally ({@code internal = public + 1}). The
 *       1-based index is never observable. The backing array is length
 *       {@code n + 1} with slot {@code 0} unused.</li>
 *   <li><b>Ranges</b>: {@link #prefixSum(int)} is the INCLUSIVE prefix
 *       {@code [0..=i]}; {@link #rangeSum(int, int)} is the INCLUSIVE closed
 *       range {@code [lo..=hi]}; {@code total() == prefixSum(n-1)} (and {@code 0}
 *       for the empty tree).</li>
 *   <li><b>Accumulator</b>: each slot and every sum is a wrapping two's-complement
 *       {@code long}. Java {@code long} arithmetic already wraps, so no
 *       {@code addExact}/saturation is used. The per-element value widens to
 *       {@code long} and does NOT re-wrap at {@code int}, so {@link #get(int)}
 *       returns {@code long}.</li>
 *   <li><b>Out-of-range</b>: mutators ({@link #update(int, int)}/{@link #set(int, int)}),
 *       {@link #get(int)} and {@link #prefixSum(int)} throw
 *       {@link IndexOutOfBoundsException} on an out-of-domain index ({@code i < 0}
 *       or {@code i >= n}). {@link #rangeSum(int, int)} validates BOTH endpoints
 *       first (out-of-domain endpoint throws), THEN returns {@code 0} for an
 *       empty {@code lo > hi} range.</li>
 * </ul>
 */
public final class FenwickTree
{
    /** 1-based partial sums; {@code tree[0]} unused. Length is {@code n + 1}. */
    private final long[] tree;

    /** Public size {@code n} (number of valid 0-based indices). */
    private final int n;

    private FenwickTree(long[] tree, int n)
    {
        this.tree = tree;
        this.n = n;
    }

    /**
     * Construct an all-zero tree of size {@code n}. {@code withSize(0)} is a
     * valid empty tree ({@code total() == 0}, {@code isEmpty() == true}).
     *
     * @throws IllegalArgumentException if {@code n < 0} or {@code n == Integer.MAX_VALUE}
     *     ({@code n + 1} would overflow the backing {@code long[]} length).
     */
    public static FenwickTree withSize(int n)
    {
        if (n < 0 || n == Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "FenwickTree size must be in [0, Integer.MAX_VALUE): " + n);
        }
        return new FenwickTree(new long[n + 1], n);
    }

    /**
     * Build from an initial {@code int} array; the tree has
     * {@code size == values.length} and {@code get(i) == values[i]}. Uses the
     * O(n) in-place build, which produces the IDENTICAL tree as
     * {@code withSize(len)} then {@code update(i, values[i])}.
     */
    public static FenwickTree fromValues(int[] values)
    {
        int n = values.length;
        long[] tree = new long[n + 1];
        // Seed each 1-based slot with the (widened) element value.
        for (int i = 0; i < n; i++)
        {
            tree[i + 1] = values[i];
        }
        // O(n) in-place build: push each slot's running sum to its parent.
        // Over the 1-based array: parent = i + (i & -i).
        for (int i = 1; i <= n; i++)
        {
            int parent = i + lowbit(i);
            if (parent <= n)
            {
                tree[parent] += tree[i];
            }
        }
        return new FenwickTree(tree, n);
    }

    /** Number of valid 0-based indices. */
    public int size()
    {
        return n;
    }

    /** Alias for {@link #size()}. */
    public int length()
    {
        return n;
    }

    /** True iff the tree is empty ({@code n == 0}). */
    public boolean isEmpty()
    {
        return n == 0;
    }

    /**
     * Add {@code delta} ({@code int}, widened to {@code long}) to the value at
     * 0-based index {@code i}.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0} or {@code i >= n}.
     */
    public void update(int i, int delta)
    {
        addInternal(i, delta);
    }

    /**
     * Point-assign: make the value at {@code i} equal {@code value}.
     *
     * <p>Implemented as a Fenwick difference-add computed in wrapping
     * {@code long} ({@code delta = (long) value - get(i)}), NOT routed through
     * the {@code int} {@link #update(int, int)} signature -- so the internal
     * delta stays exact even when the current slot value already exceeds
     * {@code int}.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0} or {@code i >= n}.
     */
    public void set(int i, int value)
    {
        long delta = (long) value - get(i);
        addInternal(i, delta);
    }

    /**
     * The single logical value currently at 0-based index {@code i}, as
     * {@code long}. Equivalent to {@code rangeSum(i, i)}.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0} or {@code i >= n}.
     */
    public long get(int i)
    {
        checkIndex(i);
        // get(i) == prefixSum(i) - prefixSum(i-1); prefixSum(-1) := 0.
        if (i == 0)
        {
            return prefixSumInternal(0);
        }
        return prefixSumInternal(i) - prefixSumInternal(i - 1);
    }

    /**
     * Inclusive prefix sum {@code sum(values[0..=i])}, as wrapping {@code long}.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0} or {@code i >= n}.
     */
    public long prefixSum(int i)
    {
        checkIndex(i);
        return prefixSumInternal(i);
    }

    /**
     * Inclusive range sum {@code sum(values[lo..=hi])}, as wrapping {@code long}.
     *
     * <p>Validates BOTH endpoints first: {@code lo} and {@code hi} must be valid
     * public indices ({@code 0 .. n-1}). Only after both are valid, if
     * {@code lo > hi} the range is empty and returns {@code 0}.
     *
     * @throws IndexOutOfBoundsException if {@code lo} or {@code hi} is out of the
     *         {@code 0 .. n-1} domain. On the empty tree every call throws (no
     *         valid endpoint exists).
     */
    public long rangeSum(int lo, int hi)
    {
        checkIndex(lo);
        checkIndex(hi);
        // Both endpoints valid; an empty closed range (lo > hi) is a defined 0.
        if (lo > hi)
        {
            return 0L;
        }
        // rangeSum = prefixSum(hi) - prefixSum(lo-1); prefixSum(-1) := 0.
        long upper = prefixSumInternal(hi);
        long lower = (lo == 0) ? 0L : prefixSumInternal(lo - 1);
        return upper - lower;
    }

    /**
     * Grand total of all values, {@code == prefixSum(n-1)} for {@code n >= 1},
     * and {@code 0} for the empty tree.
     */
    public long total()
    {
        if (n == 0)
        {
            return 0L;
        }
        return prefixSumInternal(n - 1);
    }

    /**
     * The canonical 1-based BIT projection: a length-{@code n} {@code long} array
     * where element {@code j-1} (0-based in the returned array) is the partial
     * sum the tree stores for the 1-based index {@code j} -- i.e.
     * {@code tree[1 ..= n]}. This is the layout-independent secondary determinism
     * oracle.
     */
    public long[] canonicalTree()
    {
        return Arrays.copyOfRange(tree, 1, n + 1);
    }

    // ---- internals (1-based BIT navigation) -------------------------------

    /**
     * Test-only white-box analog of the Rust reference's {@code add_internal}:
     * add an arbitrary wrapping-{@code long} {@code delta} at 0-based index
     * {@code i}, so native tests can seed a slot near {@code Long.MAX_VALUE} (a
     * value unreachable through the i32 public {@code update}) and exercise the
     * two's-complement WRAP / post-wrap invertibility. NOT part of the public
     * API; the production paths use only i32 operands.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0} or {@code i >= n}.
     */
    void addLongForTesting(int i, long delta)
    {
        addInternal(i, delta);
    }

    /** Add a wrapping-{@code long} {@code delta} at 0-based index {@code i}. */
    private void addInternal(int i, long delta)
    {
        checkIndex(i);
        int j = i + 1; // public -> 1-based BIT
        while (j <= n)
        {
            tree[j] += delta;
            j += lowbit(j);
        }
    }

    /** Inclusive prefix sum for 0-based index {@code i} (caller guarantees in-range). */
    private long prefixSumInternal(int i)
    {
        long acc = 0L;
        int j = i + 1; // public -> 1-based BIT
        while (j > 0)
        {
            acc += tree[j];
            j -= lowbit(j);
        }
        return acc;
    }

    private void checkIndex(int i)
    {
        if (i < 0 || i >= n)
        {
            throw new IndexOutOfBoundsException(
                    "FenwickTree index " + i + " out of range 0.." + n);
        }
    }

    /** Low bit {@code j & -j} over a 1-based index ({@code j >= 1}). */
    private static int lowbit(int j)
    {
        return j & (-j);
    }
}

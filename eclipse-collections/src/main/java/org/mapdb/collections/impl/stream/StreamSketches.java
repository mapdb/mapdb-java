// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.stream;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.mapdb.collections.impl.CountMin;
import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.HyperLogLog;
import org.mapdb.collections.impl.SpaceSaving;

/**
 * Archeology-2 "C4" — the probabilistic sketches recast as <b>streaming
 * operators</b> over {@code i32} streams (the A1 {@link SortedStream} being the
 * canonical source).
 *
 * <p>The family already ships HyperLogLog, Space-Saving, and Count-Min as
 * standalone, cross-language-parity-tested structures. This class does no new
 * estimation work: it wires them as one-line pipeline stages — {@code stream |
 * approxDistinctCount()}, {@code stream | topK(100)}, {@code stream |
 * sample(p, seed)} — so a caller reaches for "how many distinct?", "what are the
 * heavy hitters?", or "give me a repeatable p-sample" without hand-managing a
 * sketch object.
 *
 * <p><b>Determinism.</b> The distinct-count and frequency operators depend only
 * on the multiset of inputs and inherit the sketches' cross-language byte
 * identity. {@code topK} additionally depends on input <i>order</i> (Space-Saving
 * evicts by arrival), so it is deterministic for a given stream — and a
 * {@link SortedStream} gives a canonical order. {@code sample} decides
 * membership by a seeded hash of the value through the same {@link Hash}
 * pipeline the sketches use, so it is order-independent and identical across
 * runs and ports.
 *
 * <p>Elements are {@code i32}, matching the sketches' surface and the Roaring /
 * int-keyed sources; the values are reinterpreted/zero-extended exactly as
 * {@link HyperLogLog#add(int)} does.
 */
public final class StreamSketches
{
    /** Default HyperLogLog precision (2^14 registers, ~0.8% standard error). */
    public static final int DEFAULT_HLL_PRECISION = 14;

    private StreamSketches()
    {
    }

    // ------------------------------------------------------------------
    // Approximate distinct count (HyperLogLog)
    // ------------------------------------------------------------------

    /** Estimate the number of distinct {@code i32} values in the stream (HLL). */
    public static long approxDistinctCount(Iterator<Integer> items, int precision)
    {
        Objects.requireNonNull(items, "items");
        HyperLogLog hll = HyperLogLog.withPrecision(precision);
        while (items.hasNext())
        {
            hll.add(items.next());
        }
        return Math.round(hll.estimate());
    }

    /** {@link #approxDistinctCount(Iterator, int)} at {@link #DEFAULT_HLL_PRECISION}. */
    public static long approxDistinctCount(Iterator<Integer> items)
    {
        return approxDistinctCount(items, DEFAULT_HLL_PRECISION);
    }

    /** Drain an A1 stream into an HLL distinct-count estimate. */
    public static long approxDistinctCount(SortedStream<Integer> stream, int precision)
    {
        return approxDistinctCount(stream.iterator(), precision);
    }

    /** Drain an A1 stream into an HLL distinct-count estimate at default precision. */
    public static long approxDistinctCount(SortedStream<Integer> stream)
    {
        return approxDistinctCount(stream.iterator(), DEFAULT_HLL_PRECISION);
    }

    // ------------------------------------------------------------------
    // Top-k heavy hitters (Space-Saving)
    // ------------------------------------------------------------------

    /**
     * The estimated {@code k} most frequent {@code i32} values, via a
     * Space-Saving summary monitoring at most {@code capacity} items. Fewer than
     * {@code k} entries are returned if fewer are monitored.
     *
     * @param capacity Space-Saving capacity (monitored-set bound); larger =
     *                 more accurate, more memory
     */
    public static List<SpaceSaving.SSEntry> topK(Iterator<Integer> items, int capacity, int k)
    {
        Objects.requireNonNull(items, "items");
        SpaceSaving ss = SpaceSaving.withCapacity(capacity);
        while (items.hasNext())
        {
            ss.addOne(items.next());
        }
        return ss.topK(k);
    }

    /** Drain an A1 stream into Space-Saving top-k. */
    public static List<SpaceSaving.SSEntry> topK(SortedStream<Integer> stream, int capacity, int k)
    {
        return topK(stream.iterator(), capacity, k);
    }

    // ------------------------------------------------------------------
    // Frequency sketch (Count-Min)
    // ------------------------------------------------------------------

    /**
     * Build a Count-Min sketch of {@code i32} value frequencies from the stream.
     * Query it with {@link CountMin#estimate(int)}.
     */
    public static CountMin frequencies(Iterator<Integer> items, int depth, int width)
    {
        Objects.requireNonNull(items, "items");
        CountMin cm = CountMin.withParams(depth, width);
        while (items.hasNext())
        {
            cm.addOne(items.next());
        }
        return cm;
    }

    /** Drain an A1 stream into a Count-Min frequency sketch. */
    public static CountMin frequencies(SortedStream<Integer> stream, int depth, int width)
    {
        return frequencies(stream.iterator(), depth, width);
    }

    // ------------------------------------------------------------------
    // Deterministic Bernoulli sampling
    // ------------------------------------------------------------------

    /**
     * A stateless, repeatable Bernoulli sample gate: an {@code i32} passes with
     * probability {@code p}, decided by a seeded hash of the value through the
     * shared {@link Hash} pipeline (not an RNG). Because the decision is a pure
     * function of {@code (value, seed)}, the same element always makes the same
     * cut, the sample is independent of stream order, and it is identical across
     * runs and language ports.
     *
     * <p>The kept fraction of a value {@code v} is {@code u(v) < p} where
     * {@code u(v) = (hash64Int32(v, seed) >>> 11) * 2^-53} lies in {@code [0, 1)}.
     *
     * @param p sampling probability in {@code [0, 1]}
     * @throws IllegalArgumentException if {@code p} is NaN or outside {@code [0, 1]}
     */
    public static Predicate<Integer> sampleGate(double p, long seed)
    {
        if (Double.isNaN(p) || p < 0.0 || p > 1.0)
        {
            throw new IllegalArgumentException("sample probability must be in [0,1], got " + p);
        }
        if (p == 0.0)
        {
            return v -> false;
        }
        if (p == 1.0)
        {
            return v -> true;
        }
        return v ->
        {
            long h = Hash.hash64Int32(v, seed);
            double u = (h >>> 11) * 0x1.0p-53;
            return u < p;
        };
    }

    /**
     * Order-preserving deterministic p-sample of an A1 stream: keeps each element
     * according to {@link #sampleGate(double, long)}. The result is still a
     * well-formed {@link SortedStream} (filtering preserves order).
     */
    public static SortedStream<Integer> sample(SortedStream<Integer> stream, double p, long seed)
    {
        return stream.filter(sampleGate(p, seed));
    }
}

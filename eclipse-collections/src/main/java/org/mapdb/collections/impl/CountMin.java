// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl;

/**
 * Count-Min Sketch — a {@code d x w} integer counter matrix giving a one-sided
 * <b>over</b>-estimate of an element's frequency, riding the deterministic hash
 * pipeline ({@link Hash}, spec {@code features/count-min.md}). The counter
 * matrix after a given add-sequence is the cross-language oracle: because the
 * {@code d} column indices are exactly
 * {@code Hash.positions(encodeI32(item), w, d)} — bit-identical across all five
 * ports — the entire matrix, every {@link #estimate}, and {@link #total} are
 * bit-identical too. <b>No floating point</b> appears in the deterministic
 * surface (the only float, {@link #optimal}, is native-test-only and never used
 * by the shared scenarios).
 *
 * <p><b>Java carve-out (boxed element edge, EXACT counters — no relaxation).</b>
 * mapdb-java's element API is boxed at the edge (per {@code style/java.md}), but
 * the CMS counter math is exact and deterministic — there is <b>no
 * precision/complexity relaxation</b> (identical posture to the Bloom and
 * hash-pipeline Java carve-outs). Counts are <b>unsigned 64-bit</b> carried in a
 * Java {@code long} (the bits are unsigned). Saturating add uses
 * {@link Long#compareUnsigned}; the projection emits {@link Long#toUnsignedString};
 * the estimate MIN uses {@link Long#compareUnsigned}. The constant {@code -1L}
 * is the bit pattern of {@code u64::MAX = 18446744073709551615}.
 *
 * <p>Pinned rulings (spec {@code features/count-min.md}):
 * <ul>
 *   <li><b>Row-hash derivation:</b> the column touched in row {@code r} is the
 *       {@code r}-th of {@code positions(encodeI32(item), m = w, k = d)} in
 *       derivation order ({@code c_r = (h1 + r*h2) mod w}). Repeated column
 *       numbers across rows touch <b>distinct</b> counters (one array per row)
 *       — NOT de-duplicated.</li>
 *   <li><b>{@code estimate} = MIN over the {@code d} rows</b> (never
 *       average/sum/median/row-0), unsigned. The empty MIN ({@code d == 0}) is
 *       {@code u64::MAX}.</li>
 *   <li><b>Overflow SATURATES at {@code u64::MAX}</b> (does NOT wrap) — a
 *       deliberate departure from the collections' wrapping contract, required
 *       by the no-under-estimate guarantee.</li>
 *   <li><b>{@code add(item, count)} increments by {@code count}</b> (plain CMS,
 *       no conservative update); {@code addOne} == {@code add(item, 1)}.</li>
 *   <li><b>Element encoding:</b> {@code i32} -&gt; reinterpret {@code u32} -&gt;
 *       4 LE bytes -&gt; the byte {@code positions} path (length fold applied),
 *       identical to Bloom.</li>
 * </ul>
 */
public final class CountMin
{
    /** {@code u64::MAX} as a {@code long} bit pattern (saturating ceiling). */
    private static final long U64_MAX = -1L;

    /** Euler's number {@code e}, used only by the native-only {@link #optimal}. */
    private static final double EULER_E = Math.E;

    private final int depth;
    private final int width;

    /**
     * Flat row-major matrix: counter {@code matrix[r*w + col]} is row {@code r},
     * column {@code col}. Length is exactly {@code d*w}. Each {@code long} holds
     * an unsigned 64-bit counter.
     */
    private final long[] matrix;

    /** Running sum of every {@code count} argument (the stream length N), saturating. */
    private long total;

    private CountMin(int depth, int width, long[] matrix)
    {
        this.depth = depth;
        this.width = width;
        this.matrix = matrix;
        this.total = 0L;
    }

    /**
     * Construct a {@code d x w} sketch with all counters zero. {@code d} is the
     * depth (rows / hash functions = the {@code k} argument to
     * {@link Hash#positions}); {@code w} is the width (columns per row = the
     * {@code m} argument to {@code positions}).
     *
     * <p>{@code w == 0} is invalid (a zero-column row holds nothing and every
     * modulo would divide by zero) and traps — identical to Bloom's {@code m = 0}
     * ruling. {@code d == 0} is legal and degenerate (an empty matrix;
     * {@link #estimate} returns {@code u64::MAX}). A {@code d*w} that overflows
     * an {@code int} array length is a native allocation limit and traps (the
     * shared suite never constructs such a sketch).
     *
     * @throws IllegalArgumentException if {@code w == 0}, {@code d < 0},
     *     {@code w < 0}, or {@code d*w} overflows an addressable array length
     */
    public static CountMin withParams(int d, int w)
    {
        if (w == 0)
        {
            throw new IllegalArgumentException("CountMin width w must be non-zero");
        }
        if (d < 0 || w < 0)
        {
            throw new IllegalArgumentException("CountMin d/w must be non-negative");
        }
        long len = (long) d * (long) w;
        if (len > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "CountMin d*w overflows an addressable array length (native allocation limit)");
        }
        return new CountMin(d, w, new long[(int) len]);
    }

    /**
     * Native-only convenience constructor sizing the sketch from a target
     * additive error {@code epsilon} (relative to the total) and failure
     * probability {@code delta} using the standard Count-Min formulas
     * {@code w = ceil(e/epsilon)}, {@code d = ceil(ln(1/delta))}, then
     * delegating to {@link #withParams}.
     *
     * <p><b>Float-quarantined: never used by the cross-language scenarios</b>
     * (the {@code ln}/{@code e}/{@code ceil} derivation can drift across libm
     * implementations). Native-tested against the pinned integer table.
     *
     * <p>Requires {@code 0 < epsilon < 1} and {@code 0 < delta < 1}; values
     * {@code <= 0}, {@code >= 1}, {@code NaN}, or {@code +/-Infinity} are invalid
     * and trap.
     *
     * @throws IllegalArgumentException on an out-of-range/non-finite parameter
     */
    public static CountMin optimal(double epsilon, double delta)
    {
        if (!(epsilon > 0.0 && epsilon < 1.0))
        {
            throw new IllegalArgumentException(
                    "CountMin.optimal requires 0 < epsilon < 1, got " + epsilon);
        }
        if (!(delta > 0.0 && delta < 1.0))
        {
            throw new IllegalArgumentException(
                    "CountMin.optimal requires 0 < delta < 1, got " + delta);
        }
        double w = Math.ceil(EULER_E / epsilon);
        double d = Math.ceil(Math.log(1.0 / delta));
        if (!(Double.isFinite(w) && Double.isFinite(d) && w >= 1.0 && d >= 1.0))
        {
            throw new IllegalArgumentException("CountMin.optimal produced a non-finite (d, w)");
        }
        // Java's saturating double→int cast would turn a huge finite w/d into
        // Integer.MAX_VALUE and either OOM or raise a misleading d*w overflow.
        if (w > Integer.MAX_VALUE || d > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "CountMin.optimal produced a (d, w) that does not fit in int: d="
                            + d + ", w=" + w);
        }
        return withParams((int) d, (int) w);
    }

    /** Returns {@code min(a + b, u64::MAX)} without wrapping (unsigned). */
    private static long saturatingAdd(long a, long b)
    {
        // a + b carries past u64::MAX iff a > u64::MAX - b (unsigned).
        if (Long.compareUnsigned(a, U64_MAX - b) > 0)
        {
            return U64_MAX;
        }
        return a + b;
    }

    /**
     * The {@code d} column indices for {@code item}, one per row, in derivation
     * order: {@code positions(encodeI32(item), m = w, k = d)}. {@code c_r} (the
     * {@code r}-th element) is the column touched in row {@code r}.
     */
    private int[] columns(int item)
    {
        // Element encoding: i32 -> reinterpret u32 -> 4 LE bytes -> byte
        // positions path (length fold applied), identical to Bloom.
        byte[] bytes = {
                (byte) item,
                (byte) (item >>> 8),
                (byte) (item >>> 16),
                (byte) (item >>> 24),
        };
        return Hash.positions(bytes, this.width, this.depth);
    }

    /**
     * Increment the {@code d} selected counters (one per row) by {@code count}
     * (unsigned), saturating at {@code u64::MAX}. {@code count = 0} is legal: a
     * no-op on the counters that still updates {@code total} (by 0). Plain CMS —
     * increments <b>all</b> {@code d} counters (no conservative update).
     */
    public void add(int item, long count)
    {
        int[] cols = columns(item);
        for (int r = 0; r < cols.length; r++)
        {
            int idx = r * this.width + cols[r];
            this.matrix[idx] = saturatingAdd(this.matrix[idx], count);
        }
        this.total = saturatingAdd(this.total, count);
    }

    /** Convenience for {@code add(item, 1)}; identical bits. */
    public void addOne(int item)
    {
        add(item, 1L);
    }

    /**
     * The frequency estimate for {@code item}: the <b>MIN</b> (unsigned) over the
     * {@code d} rows of the selected counter. Never under-estimates (within the
     * {@code u64} domain). For {@code d == 0} the MIN over zero rows is the
     * empty-min identity {@code u64::MAX}.
     */
    public long estimate(int item)
    {
        int[] cols = columns(item);
        long min = U64_MAX;
        for (int r = 0; r < cols.length; r++)
        {
            int idx = r * this.width + cols[r];
            if (Long.compareUnsigned(this.matrix[idx], min) < 0)
            {
                min = this.matrix[idx];
            }
        }
        return min;
    }

    /**
     * The running sum of every {@code count} argument ever added (the stream
     * length N), saturating at {@code u64::MAX}. Bits are unsigned.
     */
    public long total()
    {
        return this.total;
    }

    /** The depth {@code d} (number of rows / hash functions). */
    public int depth()
    {
        return this.depth;
    }

    /** The width {@code w} (number of columns per row). */
    public int width()
    {
        return this.width;
    }

    /**
     * The full counter matrix as {@code d*w} values, <b>row-major</b> (row 0
     * first, column 0 first within a row). Dense (all cells, including zeros).
     * Each value is an unsigned 64-bit counter in a {@code long}.
     */
    public long[] toCounters()
    {
        return this.matrix.clone();
    }
}

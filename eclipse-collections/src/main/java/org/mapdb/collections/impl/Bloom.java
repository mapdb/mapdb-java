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

/**
 * Bloom filter — approximate set membership on the deterministic hash pipeline
 * (see {@code spec/features/bloom.md}).
 *
 * <p>This is the Java port of the first end-user collection of the probabilistic
 * wave. It rides directly on the deterministic hash pipeline ({@link Hash}): it
 * uses {@link Hash#positions(byte[], int, int)} (Kirsch–Mitzenmacher
 * double-hashing) to pick {@code k} bit indices in an {@code m}-bit array.
 * Because {@code positions()} is <b>bit-identical across all five ports</b>, the
 * bit array after a given add-sequence is bit-identical too — that bit array is
 * the cross-language oracle.
 *
 * <p><b>Java carve-out (boxed element edge, EXACT bits — no relaxation).</b>
 * mapdb-java's element API is boxed at the edge per the existing boxed posture
 * ({@code style/java.md}), but the Bloom filter's computation is exact and
 * deterministic — there is <b>NO precision/complexity relaxation</b>. The
 * {@code i32} element is unboxed to a primitive {@code int}, encoded LE-4-bytes,
 * and run through the same exact {@link Hash#positions}; the bit array is a Java
 * {@code long[]}; {@code bitCount} uses {@link Long#bitCount}; {@code toBytes()}
 * emits the identical LSB-first ascending bytes. Java's output is bit-identical
 * to the other four ports.
 *
 * <h2>Element encoding (critical)</h2>
 * An {@code i32} element {@code v} is reinterpreted to {@code u32}, encoded to
 * <b>4 little-endian bytes</b>, and fed to the hash pipeline's <b>byte-input</b>
 * {@code positions(bytes, m, k)} path — the exact path the
 * {@code 12-hash-pipeline/positions_*} scenarios drive, which <b>folds in the
 * byte length</b>. This is NOT the scalar {@code hash32Int32} path: for
 * {@code v = 7} the byte-path input word is {@code 0x07 ^ 4 = 0x03}. Worked
 * example: {@code withParams(16, 4)} then {@code add(7)} lights bits
 * {@code {0, 2, 7, 9}} → {@code toBytes() = [0x85, 0x02]} → {@code "0x8502"},
 * {@code bitCount() == 4}.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>No false negative.</b> {@code add(v)} then {@code mightContain(v)} is
 *       always {@code true}.</li>
 *   <li><b>Idempotent / order-independent.</b> The bit array depends only on the
 *       <i>set</i> of added elements.</li>
 *   <li><b>Deterministic.</b> Identical {@code (m, k)} + add-sequence ⇒ identical
 *       bits on all five ports.</li>
 * </ul>
 *
 * <h2>Boxed-Java carve-out: the {@code k} bound</h2>
 * The spec's {@code with_params(m_bits: u32, k: u32)} carries both parameters in
 * the full {@code u32} domain. This Java port keeps <b>{@code m_bits} at the full
 * {@code u32}</b> ({@code 1 ..= 2^32-1}) — bit/word indexing is unsigned, so the
 * whole range is meaningful and representable. <b>{@code k} is bounded to
 * {@code 0 ..= Integer.MAX_VALUE}</b>, however, and a {@code k} above that is
 * <b>rejected</b> with an {@link IllegalArgumentException}. The reason is honest
 * and Java-specific: each element's positions are produced as a Java
 * {@code int[]} of length {@code k} ({@link Hash#positionsFromHashes}), and a
 * Java array cannot have {@code >= 2^31} elements — a {@code k >= 2^31} stored as
 * a signed {@code int} is negative and would throw {@code NegativeArraySizeException}
 * deep inside {@code add}/{@code mightContain}. A {@code k} that large is also
 * purely degenerate (it sets/tests billions of positions per element). The other
 * four ports tolerate such a {@code k} degenerately; Java's {@code int[]}-indexed
 * positions genuinely cannot represent it, so this port rejects it up front with a
 * clear message rather than crashing later. This is the <b>only</b> bound this
 * port adds beyond the spec; it never triggers for the validation scenarios
 * (whose {@code k} is small).
 */
public final class Bloom
{
    /** {@code ln(2)} in {@code f64} (used only by {@link #optimal}). */
    private static final double LN2 = 0.6931471805599453;

    /** The largest {@code u32} value, {@code 2^32 - 1}. */
    private static final long U32_MAX = 0xFFFF_FFFFL;

    /**
     * Number of bits in the array ({@code m}). The spec's domain is the full
     * {@code u32} {@code 1 ..= 2^32-1}; Java carries it as the <b>32-bit pattern
     * stored in a signed {@code int}</b> (a value {@code >= 2^31} is a negative
     * {@code int}). Every use treats it as <b>unsigned</b>
     * ({@link Integer#toUnsignedLong}, {@link Long#divideUnsigned}, {@code >>>},
     * {@link Integer#remainderUnsigned} inside {@link Hash}) — it is never
     * sign-extended.
     */
    private final int mBits;

    /**
     * Number of hash functions / positions set per element. Bounded to
     * {@code 0 ..= Integer.MAX_VALUE} (the boxed-Java carve-out: each element's
     * {@code k} positions are a Java {@code int[]}, which cannot hold {@code >=
     * 2^31} entries). Stored as a non-negative {@code int}; a {@code k} above the
     * bound is rejected by {@link #withParams}, so this is never a negative bit
     * pattern.
     */
    private final int k;

    /**
     * The bit array, {@code ceil(m / 64)} words; bit {@code i} lives in word
     * {@code i / 64} at bit position {@code i % 64} (LSB-first within a word).
     */
    private final long[] words;

    private Bloom(int mBits, int k, long[] words)
    {
        this.mBits = mBits;
        this.k = k;
        this.words = words;
    }

    /**
     * Canonical, fully-deterministic constructor: explicit bit count
     * {@code mBits} and hash count {@code k}. The filter starts empty (all bits
     * {@code 0}).
     *
     * <p>{@code mBits == 0} is <b>invalid</b> and traps with an
     * {@link IllegalArgumentException} (a 0-bit array can hold nothing and every
     * {@code positions} modulo would be by zero). {@code k == 0} is degenerate
     * but <b>legal</b> (see {@link #mightContain}).
     *
     * <p><b>{@code m_bits}: full {@code u32} domain (via {@code long}).</b> The
     * native ports carry {@code m} as {@code u32}; this Java port implements the
     * <b>same full {@code u32} domain</b> for {@code mBits}. It is accepted as a
     * {@code long} so a value {@code > Integer.MAX_VALUE} (the high bit of the
     * {@code u32}) is <b>not</b> rejected — it is validated against the
     * {@code u32} range and stored as the 32-bit pattern in a signed {@code int},
     * then interpreted unsigned everywhere (bit/word indexing uses {@code >>>} /
     * {@link Long#divideUnsigned} / {@link Integer#remainderUnsigned}, never a
     * sign-extend). Validation: {@code 1 <= mBits <= 2^32-1}.
     *
     * <p><b>{@code k}: bounded to {@code 0 ..= Integer.MAX_VALUE} (boxed-Java
     * carve-out).</b> Unlike {@code m}, {@code k} cannot span the full {@code u32}
     * in this port: each element's {@code k} positions are produced as a Java
     * {@code int[]} of length {@code k} ({@link Hash#positionsFromHashes}), which
     * cannot hold {@code >= 2^31} entries. A {@code k > Integer.MAX_VALUE} is
     * therefore <b>rejected</b> here with a clear {@link IllegalArgumentException}
     * (rather than being stored as a negative {@code int} and throwing a
     * {@code NegativeArraySizeException} later inside {@code add}/{@code
     * mightContain}). Such a {@code k} is degenerate and unrepresentable as a Java
     * {@code int[]}; see the class Javadoc carve-out. Validation:
     * {@code 0 <= k <= Integer.MAX_VALUE}. (The cross-language scenarios only
     * exercise small values, so this bound never triggers there.)
     *
     * @param mBits the number of bits in the array ({@code 1 ..= 2^32-1})
     * @param k the number of hash functions / positions set per element
     *        ({@code 0 ..= Integer.MAX_VALUE})
     * @return a fresh empty filter
     * @throws IllegalArgumentException if {@code mBits} is outside {@code 1 ..= 2^32-1}
     *         or {@code k} is outside {@code 0 ..= Integer.MAX_VALUE}
     */
    public static Bloom withParams(long mBits, long k)
    {
        if (mBits < 1L || mBits > U32_MAX)
        {
            throw new IllegalArgumentException(
                    "Bloom.withParams: mBits must be in 1..=4294967295 (u32), got " + mBits);
        }
        if (k < 0L || k > (long) Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "Bloom.withParams: k must be in 0..=2147483647 (Integer.MAX_VALUE) — "
                            + "Java's int[]-indexed positions cannot represent k >= 2^31; got " + k);
        }
        // ceil(mBits / 64) over the unsigned u32 domain. For the max u32 mBits
        // this is ~67.1M longs (~537 MB) — large but a representable Java array;
        // tests never allocate near the top of the range.
        int nWords = (int) ((mBits + 63L) / 64L);
        // Store the validated values as their 32-bit pattern in a signed int.
        return new Bloom((int) mBits, (int) k, new long[nWords]);
    }

    /**
     * Convenience constructor sizing the filter from an expected element count
     * {@code n} and a target false-positive probability {@code p}, using the
     * standard Bloom formulas:
     *
     * <pre>
     * m = ceil( -n * ln(p) / (ln 2)^2 )
     * k = max( 1, round( (m / n) * ln 2 ) )      # round-half-away-from-zero
     * </pre>
     *
     * then delegates to {@link #withParams}. This is <b>native-test-only</b>: it
     * never appears in the shared cross-language scenarios (the float derivation
     * could drift by a ULP across libms — quarantined to native tests against the
     * pinned integer table in {@code spec/features/bloom.md}).
     *
     * <p>Requires {@code n >= 1} and {@code 0 < p < 1}. {@code n == 0},
     * {@code p <= 0}, {@code p >= 1}, {@code NaN}, and {@code ±Infinity} are
     * invalid and trap (they would divide by zero, take {@code ln} of a
     * non-positive value, or yield a non-finite {@code m}).
     *
     * @param n the expected element count ({@code >= 1})
     * @param p the target false-positive probability ({@code 0 < p < 1})
     * @return a filter sized to {@code (m, k)}
     * @throws IllegalArgumentException on invalid {@code n}/{@code p}
     */
    public static Bloom optimal(long n, double p)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("Bloom.optimal: n must be >= 1");
        }
        if (!(Double.isFinite(p) && p > 0.0 && p < 1.0))
        {
            throw new IllegalArgumentException(
                    "Bloom.optimal: p must be finite and in (0, 1), got " + p);
        }
        double nf = (double) n;
        double mf = Math.ceil(-nf * Math.log(p) / (LN2 * LN2));
        if (!(Double.isFinite(mf) && mf >= 1.0 && mf <= (double) U32_MAX))
        {
            throw new IllegalArgumentException("Bloom.optimal: derived m out of range: " + mf);
        }
        long m = (long) mf;
        // Math.round is round-half-up; for the non-negative argument here that is
        // identical to round-half-away-from-zero. Clamp to >= 1.
        long kRound = Math.round(((double) m / nf) * LN2);
        long kk = Math.max(1L, kRound);
        return withParams(m, kk);
    }

    /**
     * The bit count {@code m} (the spec's {@code u32}) as a non-negative
     * {@code long}. {@code mBits} is stored as the {@code u32} bit pattern in a
     * signed {@code int}, so a value {@code >= 2^31} would render negative if
     * returned as an {@code int} (e.g. {@code withParams(2147483648L, 0)} would
     * yield {@code -2147483648}). Java has no unsigned {@code int}, so — exactly
     * like {@link #bitCount()} — this returns the unsigned value widened with
     * {@link Integer#toUnsignedLong}, keeping parity with the {@code u32} ports.
     * For the small validated values used in the scenarios the decimal is
     * unchanged.
     */
    public long mBits()
    {
        return Integer.toUnsignedLong(this.mBits);
    }

    /**
     * The hash count {@code k}. Bounded to {@code 0 ..= Integer.MAX_VALUE} (the
     * boxed-Java carve-out), so this is always a plain non-negative {@code int}.
     */
    public int k()
    {
        return this.k;
    }

    /**
     * Encode an {@code i32} element to the 4 little-endian bytes the hash
     * pipeline's {@code positions} byte path consumes: a two's-complement
     * reinterpret to {@code u32} (the {@code int} bits ARE that reinterpret —
     * NOT a sign-extend), least-significant byte first.
     */
    private static byte[] encodeI32(int v)
    {
        return new byte[] {
                (byte) v,
                (byte) (v >>> 8),
                (byte) (v >>> 16),
                (byte) (v >>> 24)
        };
    }

    /**
     * Add an {@code i32} element: set the {@code k} bits for {@code v}
     * (idempotent). With {@code k == 0} this sets no bits.
     */
    public void add(int v)
    {
        for (int p : Hash.positions(encodeI32(v), this.mBits, this.k))
        {
            setBit(p);
        }
    }

    /**
     * {@code mightContain} — the canonical name. Returns {@code false} ⇒
     * definitely absent; {@code true} ⇒ possibly present (may be a false
     * positive). <b>Never</b> returns {@code false} for an element that was added
     * (no false negative).
     *
     * <p>With {@code k == 0} the AND over zero positions is <b>vacuously
     * true</b>, so this returns {@code true} for every element (an
     * all-false-positive filter).
     */
    public boolean mightContain(int v)
    {
        for (int p : Hash.positions(encodeI32(v), this.mBits, this.k))
        {
            if (!getBit(p))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Idiomatic alias for {@link #mightContain} (what the JSON suite's
     * {@code contains_<v>} keys probe). The result is <b>approximate</b>
     * membership.
     */
    public boolean contains(int v)
    {
        return mightContain(v);
    }

    /**
     * {@code true} iff no bit is set (equivalently: nothing has been added, or
     * only {@code k == 0} adds). Equal to {@code bitCount() == 0}.
     */
    public boolean isEmpty()
    {
        for (long w : this.words)
        {
            if (w != 0L)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The number of set bits (popcount of the whole bit array). The zeroed tail
     * bits never contribute (no {@code positions} index reaches them).
     *
     * <p><b>Returns {@code long} for u32 parity.</b> The spec's {@code bit_count}
     * is a {@code u32} (the other four ports carry it as an unsigned 32-bit value,
     * which holds counts up to {@code 2^32-1}). Java has no unsigned {@code int},
     * so a count {@code >= 2^31} stored in a signed {@code int} would render
     * negative and break parity; this port returns the count as a non-negative
     * {@code long} instead. The value is accumulated as a {@code long} and capped
     * at the {@code u32} ceiling (impossible for {@code m <= 2^32-1}, so the cap
     * never truncates a real count). For every validation scenario the set count
     * is tiny, so the emitted decimal is unchanged for small {@code m}.
     */
    public long bitCount()
    {
        return longBitCount();
    }

    /** Population count of the bit array as a {@code long} (overflow-safe). */
    private long longBitCount()
    {
        long count = 0L;
        for (long w : this.words)
        {
            count += Long.bitCount(w);
        }
        // Saturate at the u32 ceiling; m <= 2^32-1 so this can never truncate a
        // real count, it only guards against an impossible overflow.
        return Math.min(count, U32_MAX);
    }

    /**
     * Bitwise OR of two filters with <b>identical {@code (m, k)}</b>, returning a
     * new filter. The result's membership is the union of the two filters'
     * membership (no false negatives lost).
     *
     * <p>Mismatched {@code (m, k)} traps with an {@link IllegalArgumentException}
     * (a filter built with different parameters has an incompatible bit array;
     * ORing them is meaningless).
     *
     * @param other the filter to union with
     * @return a new filter holding the bitwise OR
     * @throws IllegalArgumentException if {@code (m, k)} differ
     */
    public Bloom union(Bloom other)
    {
        if (this.mBits != other.mBits || this.k != other.k)
        {
            throw new IllegalArgumentException(
                    "Bloom.union: parameter mismatch (" + this.mBits + ", " + this.k
                            + ") vs (" + other.mBits + ", " + other.k + ")");
        }
        long[] out = new long[this.words.length];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = this.words[i] | other.words[i];
        }
        return new Bloom(this.mBits, this.k, out);
    }

    /**
     * The serialized bit array ({@code spec/features/bloom.md} §"Serialized
     * bit-array form"): length exactly {@code ceil(m / 8)} bytes; <b>LSB-first</b>
     * bit order within each byte (bit {@code i} ⇒
     * {@code byte[i / 8] |= 1 << (i % 8)}); ascending byte order;
     * <b>little-endian on every host</b>; unused tail bits {@code 0}.
     */
    public byte[] toBytes()
    {
        int nBytes = (int) ((Integer.toUnsignedLong(this.mBits) + 7L) / 8L);
        byte[] out = new byte[nBytes];
        for (int wi = 0; wi < this.words.length; wi++)
        {
            long w = this.words[wi];
            // Each word holds bits [wi*64 .. wi*64 + 64). Emit its 8 bytes
            // little-endian so bit (wi*64 + b*8 + j) lands at out[...] & (1<<j).
            for (int b = 0; b < 8; b++)
            {
                int outIdx = wi * 8 + b;
                if (outIdx < nBytes)
                {
                    out[outIdx] = (byte) (w >>> (8 * b));
                }
                // outIdx >= nBytes can only be a fully-zero tail byte (no
                // positions index reaches >= m), so dropping it is exact.
            }
        }
        return out;
    }

    /**
     * The sorted-ascending indices of the set bits — a human-legible alternate
     * oracle to {@link #toBytes} (drives the {@code set_bits} scenario
     * assertion).
     *
     * <p><b>Returns {@code long[]} of non-negative u32 indices for parity.</b> A
     * bit index lives in the {@code u32} domain {@code 0 ..= 2^32-1} (it can
     * exceed {@code 2^31} for a large {@code m}). The other ports carry indices as
     * unsigned 32-bit values; Java has no unsigned {@code int}, so each index is
     * returned as a non-negative {@code long} ({@code (wi * 64L + j)} is computed
     * in {@code long} arithmetic, never overflowing into a negative {@code int}).
     * For the validation scenarios (small {@code m}) the values are small and the
     * emitted decimals are unchanged.
     *
     * <p>The array length is sized from the overflow-safe {@code long} popcount; a
     * Java {@code long[]} cannot hold {@code >= 2^31} entries, so if the count ever
     * exceeded {@code Integer.MAX_VALUE} (impossible for the validated/scenario
     * sizes) it is unrepresentable as a Java array and we fail fast rather than
     * allocate a negative/overflowed array.
     */
    public long[] setBits()
    {
        long count = longBitCount();
        if (count > (long) Integer.MAX_VALUE)
        {
            throw new IllegalStateException(
                    "Bloom.setBits: " + count + " set bits exceed Integer.MAX_VALUE — "
                            + "not representable as a Java long[]");
        }
        long[] out = new long[(int) count];
        int n = 0;
        for (int wi = 0; wi < this.words.length; wi++)
        {
            long bits = this.words[wi];
            while (bits != 0L)
            {
                int j = Long.numberOfTrailingZeros(bits);
                // long arithmetic: a u32 index >= 2^31 stays non-negative.
                out[n++] = (long) wi * 64L + j;
                bits &= bits - 1L; // clear lowest set bit
            }
        }
        return out;
    }

    // ---- internal bit ops ------------------------------------------------

    private void setBit(int i)
    {
        // positions() returns an UNSIGNED u32 position in [0, m); for m >= 2^31 it
        // arrives as a negative int (the u32 bit pattern). The unsigned shift
        // (>>>) computes the word index correctly without sign-extension.
        this.words[i >>> 6] |= 1L << (i & 63);
    }

    private boolean getBit(int i)
    {
        return ((this.words[i >>> 6] >>> (i & 63)) & 1L) == 1L;
    }
}

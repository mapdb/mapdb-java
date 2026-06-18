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
 */
public final class Bloom
{
    /** {@code ln(2)} in {@code f64} (used only by {@link #optimal}). */
    private static final double LN2 = 0.6931471805599453;

    /** Number of bits in the array ({@code m}, carried as {@code int}). */
    private final int mBits;

    /** Number of hash functions / positions set per element. */
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
     * <p><b>Java subset.</b> The native ports carry {@code m}/{@code k} as
     * {@code u32}; the validated {@code m}/{@code k} are small (well under
     * {@code 2^31}), so Java carries them as a signed {@code int}. This method
     * therefore enforces the Java-representable subset explicitly:
     * {@code mBits} in {@code 1 ..= Integer.MAX_VALUE} and {@code k >= 0}. A
     * negative {@code mBits} (a {@code u32} with the high bit set) or a negative
     * {@code k} is outside that subset and traps rather than producing a
     * half-unsigned object.
     *
     * @param mBits the number of bits in the array ({@code 1 ..= 2^31-1})
     * @param k the number of hash functions / positions set per element ({@code >= 0})
     * @return a fresh empty filter
     * @throws IllegalArgumentException if {@code mBits < 1} or {@code k < 0}
     */
    public static Bloom withParams(int mBits, int k)
    {
        if (mBits < 1)
        {
            throw new IllegalArgumentException(
                    "Bloom.withParams: mBits must be in 1..Integer.MAX_VALUE, got " + mBits);
        }
        if (k < 0)
        {
            throw new IllegalArgumentException("Bloom.withParams: k must be >= 0, got " + k);
        }
        // ceil(mBits / 64) over the unsigned interpretation of mBits.
        int nWords = (int) ((Integer.toUnsignedLong(mBits) + 63L) / 64L);
        return new Bloom(mBits, k, new long[nWords]);
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
        if (!(Double.isFinite(mf) && mf >= 1.0 && mf <= (double) Integer.MAX_VALUE))
        {
            throw new IllegalArgumentException("Bloom.optimal: derived m out of range: " + mf);
        }
        int m = (int) mf;
        // Math.round is round-half-up; for the non-negative argument here that is
        // identical to round-half-away-from-zero. Clamp to >= 1.
        long kRound = Math.round((m / nf) * LN2);
        int kk = (int) Math.max(1L, kRound);
        return withParams(m, kk);
    }

    /** The bit count {@code m}. */
    public int mBits()
    {
        return this.mBits;
    }

    /** The hash count {@code k}. */
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
     */
    public int bitCount()
    {
        int count = 0;
        for (long w : this.words)
        {
            count += Long.bitCount(w);
        }
        return count;
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
     */
    public int[] setBits()
    {
        int[] out = new int[bitCount()];
        int n = 0;
        for (int wi = 0; wi < this.words.length; wi++)
        {
            long bits = this.words[wi];
            while (bits != 0L)
            {
                int j = Long.numberOfTrailingZeros(bits);
                out[n++] = wi * 64 + j;
                bits &= bits - 1L; // clear lowest set bit
            }
        }
        return out;
    }

    // ---- internal bit ops ------------------------------------------------

    private void setBit(int i)
    {
        int idx = i; // positions() always returns 0 <= i < m, so it fits an int.
        this.words[idx >>> 6] |= 1L << (idx & 63);
    }

    private boolean getBit(int i)
    {
        int idx = i;
        return ((this.words[idx >>> 6] >>> (idx & 63)) & 1L) == 1L;
    }
}

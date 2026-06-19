// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl;

import java.util.Arrays;

/**
 * HyperLogLog distinct-count cardinality sketch (see
 * {@code spec/features/hyperloglog.md}). Java port of the frozen Rust reference.
 *
 * <h2>Float-quarantine ruling (the heart of this feature)</h2>
 *
 * HyperLogLog has two observable surfaces:
 *
 * <ol>
 *   <li>The <b>integer register array</b> ({@code m = 2^p} bytes, each the max
 *       {@code rho} seen). This is <b>exact integer state</b>, a pure-integer
 *       function of {@code (p, add-sequence)} through the hash pipeline, and is
 *       the <b>cross-language oracle</b> — all five ports MUST produce the
 *       byte-identical array. The shared JSON scenarios assert ONLY this (via
 *       {@code register_hex}, {@code nonzero_registers}, {@code max_register},
 *       {@code register_at_N}).</li>
 *   <li>The <b>{@code double} estimate</b> ({@link #estimate()}). It is a
 *       function of {@code ln} / {@code 2^x} / division / summation and
 *       <b>cannot</b> be required to agree bit-for-bit across five libm
 *       implementations. It is specified precisely here and tested
 *       <b>natively</b> against a documented tolerance — it is <b>never</b> in
 *       the shared oracle. There is <b>no {@code estimate} assertion key</b>.</li>
 * </ol>
 *
 * <p>{@link #add(int)}, {@link #merge(HyperLogLog)}, and the register array use
 * <b>zero floating point</b> (only {@code hash64}, shifts, {@code max}, byte
 * packing); the float appears only inside {@link #estimate()}, a read-only
 * projection that never writes a register.
 *
 * <h2>Boxed / Optional carve-out</h2>
 *
 * The mapdb-java fork is a <b>boxed</b> Eclipse Collections fork, but this sketch
 * is a self-contained primitive: every public method returns or accepts a Java
 * <b>primitive</b> ({@code int}/{@code double}/{@code byte[]}) — there is no
 * boxed element type and no {@link java.util.Optional} return. {@link #add(int)}
 * takes the suite's v1 {@code i32} element directly (the one place an element
 * enters); the spec's "boxed element edge" (a {@code Comparable}/{@code Object}
 * element folded via the {@code bytes} hash path) is NOT part of v1 and is not
 * exposed here. Errors are signalled with {@link IllegalArgumentException}
 * (never a silent clamp / best-effort read), the Java idiom in place of the
 * reference's {@code Result}/{@code HllError}.
 */
public final class HyperLogLog
{
    /** Minimum legal precision ({@code m = 16}). */
    public static final int MIN_PRECISION = 4;

    /** Maximum legal precision ({@code m = 262144}); the v1 ceiling. */
    public static final int MAX_PRECISION = 18;

    /** The 4-byte ASCII magic that version-tags the serialized form ("HLL1"). */
    private static final byte[] MAGIC = {0x48, 0x4c, 0x4c, 0x31};

    private final int p;

    /** {@code m = 2^p} registers, each the max {@code rho} seen (0 = empty). */
    private final byte[] registers;

    private HyperLogLog(int p, byte[] registers)
    {
        this.p = p;
        this.registers = registers;
    }

    /**
     * Construct an empty sketch with {@code m = 2^p} zeroed registers.
     *
     * <p>{@code p} must be in {@code 4..=18}; otherwise
     * {@link IllegalArgumentException} (never a silent clamp — a clamp would let
     * two ports build differently-sized arrays from the same nominal {@code p}).
     *
     * @param p the precision ({@code log2(m)}), {@code 4 <= p <= 18}
     * @return a fresh all-zero sketch
     * @throws IllegalArgumentException if {@code p} is out of range
     */
    public static HyperLogLog withPrecision(int p)
    {
        if (p < MIN_PRECISION || p > MAX_PRECISION)
        {
            throw new IllegalArgumentException(
                    "precision " + p + " out of range " + MIN_PRECISION + ".." + MAX_PRECISION);
        }
        int m = 1 << p;
        return new HyperLogLog(p, new byte[m]);
    }

    /** The precision {@code p} ({@code log2(m)}). */
    public int precision()
    {
        return this.p;
    }

    /** The register count {@code m = 2^p}. */
    public int registerCount()
    {
        return this.registers.length;
    }

    /** The per-{@code p} maximum possible {@code rho} (and {@code fromBytes} byte ceiling): {@code 64 - p + 1}. */
    private static int rhoCeiling(int p)
    {
        return 64 - p + 1;
    }

    /**
     * Add an {@code i32} element. The item is encoded with the hash pipeline's
     * {@code i32} rule — reinterpret to {@code u32}, <b>zero-extend</b> to
     * {@code u64} ({@code item & 0xFFFFFFFFL}, NOT sign-extend) — then
     * {@code hash64(word, 0)}, then the {@code hll_split}, then
     * {@code register[idx] = max(register[idx], rho)}. <b>Pure integer, zero
     * floating point.</b>
     *
     * <p>The guard bit {@code 1L << (p - 1)} OR'd into the remainder pins an
     * all-zero remainder to {@code rho = 64 - p + 1} (its max) and guarantees
     * {@code numberOfLeadingZeros} is never called on {@code 0}. {@code add(0)}
     * exercises this: {@code hash64(0, 0) == 0}.
     *
     * @param item the {@code i32} element (reinterpreted, zero-extended)
     */
    public void add(int item)
    {
        // i32 -> u32 reinterpret -> zero-extend to u64 (high 32 bits always 0).
        long inputWord = item & 0xFFFFFFFFL;
        long x = Hash.hash64(inputWord, 0L);
        int idx = (int) (x >>> (64 - this.p));
        long w = (x << this.p) | (1L << (this.p - 1));
        int rho = Long.numberOfLeadingZeros(w) + 1;
        if (rho > (this.registers[idx] & 0xFF))
        {
            this.registers[idx] = (byte) rho;
        }
    }

    /**
     * The raw register array (the cross-language oracle bytes) as a defensive
     * copy. Each byte is an unsigned {@code rho} ({@code 0..=64 - p + 1}); read
     * with {@code & 0xFF} for the unsigned value.
     */
    public byte[] registers()
    {
        return this.registers.clone();
    }

    /** The count of registers {@code > 0} ({@code = m - V}, where {@code V} is the zero count). */
    public int nonzeroRegisters()
    {
        int count = 0;
        for (byte r : this.registers)
        {
            if (r != 0)
            {
                count++;
            }
        }
        return count;
    }

    /** The maximum register value (largest {@code rho} seen); {@code 0} for a fresh sketch. */
    public int maxRegister()
    {
        int max = 0;
        for (byte r : this.registers)
        {
            int v = r & 0xFF;
            if (v > max)
            {
                max = v;
            }
        }
        return max;
    }

    /**
     * Merge {@code other} into {@code this} by element-wise register <b>max</b>
     * (the union's register {@code j} is the max over both input sets). Requires
     * identical {@code p} (else {@link IllegalArgumentException}). Commutative,
     * associative, idempotent. <b>Pure integer, zero floating point.</b>
     *
     * @param other the sketch to merge in (must share this sketch's {@code p})
     * @throws IllegalArgumentException if the precisions differ
     */
    public void merge(HyperLogLog other)
    {
        if (this.p != other.p)
        {
            throw new IllegalArgumentException(
                    "merge precision mismatch: " + this.p + " != " + other.p);
        }
        for (int i = 0; i < this.registers.length; i++)
        {
            int b = other.registers[i] & 0xFF;
            if (b > (this.registers[i] & 0xFF))
            {
                this.registers[i] = (byte) b;
            }
        }
    }

    /**
     * Estimate the distinct cardinality (the <b>quarantined {@code double}</b>,
     * native-only and tolerance-tested — never in the shared oracle).
     *
     * <p>Original HyperLogLog estimator (Flajolet–Fusy–Gandouet–Meunier 2007)
     * with the small-range linear-counting correction and the <b>{@code 2^64}</b>
     * large-range correction (this HLL consumes a 64-bit {@code hash64}, so the
     * hash space is {@code 2^64}, NOT the 2007 paper's {@code 2^32}).
     *
     * <p><b>Edge note:</b> for an add/merge-reachable state this is always
     * finite. A synthetic state loaded via {@link #fromBytes(byte[])} with every
     * register at the absolute per-{@code p} ceiling ({@code 64 - p + 1}) is not
     * reachable from the v1 {@code i32} add surface; at small {@code p} its raw
     * {@code E} can exceed {@code 2^64}, making {@code ln(1 - E/2^64)} take
     * {@code ln(< 0) = NaN}. That state never occurs through {@code add}/
     * {@code merge} and never enters the shared integer oracle (the estimate is
     * quarantined), so it is a non-issue for the contract; documented here only
     * for callers that estimate arbitrary deserialized states.
     *
     * @return the estimated distinct cardinality
     */
    public double estimate()
    {
        double m = this.registers.length;
        double alpha = alphaM(this.p);

        // Z = sum 2^(-register[j]); register[j] == 0 contributes 2^0 = 1.
        // 1 << register[j] (<= 1 << 61) fits a long; compute the shift in integer.
        double z = 0.0;
        int v = 0;
        for (byte rb : this.registers)
        {
            int r = rb & 0xFF;
            if (r == 0)
            {
                v++;
            }
            z += 1.0 / (double) (1L << r);
        }
        double e = alpha * m * m / z;

        // Small-range (linear counting): E small AND there are empties (V > 0).
        if (e <= 2.5 * m && v > 0)
        {
            return m * Math.log(m / (double) v);
        }

        // Large-range correction near the HASH-SPACE ceiling (2^64, NOT 2^32).
        double two64 = 18446744073709551616.0; // 2^64, exactly representable.
        if (e > (1.0 / 30.0) * two64)
        {
            return -two64 * Math.log(1.0 - e / two64);
        }

        return e;
    }

    /**
     * The HLL bias constant {@code alpha_m}: pinned piecewise literals for small
     * {@code m}, closed form for {@code m >= 128}.
     */
    private static double alphaM(int p)
    {
        switch (p)
        {
            case 4:
                return 0.673;                                    // m = 16
            case 5:
                return 0.697;                                    // m = 32
            case 6:
                return 0.709;                                    // m = 64
            default:
                return 0.7213 / (1.0 + 1.079 / (double) (1L << p)); // m >= 128
        }
    }

    /**
     * Serialize to the v1 wire form: 5-byte header ("HLL1" + {@code p}) followed
     * by one byte per register in index order. Total length {@code 5 + 2^p}.
     */
    public byte[] toBytes()
    {
        byte[] out = new byte[5 + this.registers.length];
        out[0] = MAGIC[0];
        out[1] = MAGIC[1];
        out[2] = MAGIC[2];
        out[3] = MAGIC[3];
        out[4] = (byte) this.p;
        System.arraycopy(this.registers, 0, out, 5, this.registers.length);
        return out;
    }

    /**
     * Deserialize from the v1 wire form. Rejects (single MUST rule so no two
     * ports disagree on validity): too short ({@code < 5}), bad magic, {@code p}
     * out of range, length {@code != 5 + 2^p}, or any register byte (unsigned)
     * {@code > 64 - p + 1}. Any failure throws {@link IllegalArgumentException}
     * (never a best-effort partial read).
     *
     * @param bytes the serialized v1 form
     * @return the reconstructed sketch
     * @throws IllegalArgumentException on any validation failure
     */
    public static HyperLogLog fromBytes(byte[] bytes)
    {
        if (bytes.length < 5)
        {
            throw new IllegalArgumentException(
                    "serialized HLL too short: " + bytes.length + " bytes (need >= 5)");
        }
        if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] || bytes[2] != MAGIC[2] || bytes[3] != MAGIC[3])
        {
            throw new IllegalArgumentException("bad HLL magic (expected \"HLL1\")");
        }
        int p = bytes[4] & 0xFF;
        if (p < MIN_PRECISION || p > MAX_PRECISION)
        {
            throw new IllegalArgumentException(
                    "precision " + p + " out of range " + MIN_PRECISION + ".." + MAX_PRECISION);
        }
        int m = 1 << p;
        int expected = 5 + m;
        if (bytes.length != expected)
        {
            throw new IllegalArgumentException(
                    "HLL length mismatch: expected " + expected + ", got " + bytes.length);
        }
        int ceiling = rhoCeiling(p);
        byte[] registers = Arrays.copyOfRange(bytes, 5, bytes.length);
        for (int i = 0; i < registers.length; i++)
        {
            int r = registers[i] & 0xFF;
            if (r > ceiling)
            {
                throw new IllegalArgumentException(
                        "register[" + i + "] = " + r + " exceeds per-p ceiling " + ceiling);
            }
        }
        return new HyperLogLog(p, registers);
    }
}

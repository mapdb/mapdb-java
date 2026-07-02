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
 * Deterministic, byte-exact, cross-language hash pipeline (see
 * {@code spec/features/hash-pipeline.md}).
 *
 * <p>This is the Java port of a small, frozen primitive whose entire contract is
 * <b>bit-exactness across all five language ports</b>: every {@code (input, seed)}
 * produces the identical {@code hash32} / {@code hash64} / {@code positions} bits
 * in Rust, Go, TypeScript, Zig and Java. It is a separate, additive module — it
 * does <b>not</b> touch the collections' bucket hash ({@link Fibonacci},
 * {@code algorithms.md} &sect;"Hash function"), which keeps its native-hash
 * carve-out. This module has <b>no carve-out</b>: its output is exact and
 * bit-identical to the other four ports.
 *
 * <p><b>Java specifics</b> (recorded in {@code style/java.md} &sect;"Hash
 * pipeline"): Java has no unsigned types, so unsigned values are carried in
 * signed {@code int}/{@code long} two's-complement bit patterns. The only Java
 * specifics — none a precision/complexity relaxation — are:
 * <ul>
 *   <li>unsigned values carried in signed {@code int}/{@code long};</li>
 *   <li>{@code >>>} (logical right shift) wherever the spec requires a logical
 *       shift, never {@code >>};</li>
 *   <li>{@link Integer#remainderUnsigned} for the unsigned {@code combined % m}
 *       in {@link #positionsFromHashes}.</li>
 * </ul>
 * Java {@code int}/{@code long} multiply already wraps mod 2^32 / 2^64, so the
 * wrapping multiplies need no special handling.
 *
 * <ul>
 *   <li>{@link #hash32} — MurmurHash3 32-bit finalizer ({@code fmix32}) over the
 *       input word XOR'd with a 32-bit fold of the 64-bit seed.</li>
 *   <li>{@link #hash64} — MurmurHash3 64-bit finalizer ({@code fmix64},
 *       constants {@code 0xff51afd7ed558ccd} / {@code 0xc4ceb9fe1a85ec53},
 *       shifts {@code 33/33/33} — NOT the SplitMix64 generator's final mix) over
 *       the input word XOR'd with the seed.</li>
 *   <li>{@link #positions} — Kirsch–Mitzenmacher double hashing
 *       ({@code h1 + i*h2 mod m}), all 32-bit wrapping, unsigned modulo.</li>
 *   <li>{@link #hllSplit} — pre-stated {@code (register_index, leading_zero_run)}
 *       split for the later HyperLogLog feature.</li>
 * </ul>
 */
public final class Hash
{
    /** MurmurHash3 {@code fmix32} finalizer constant 1 (published value). */
    private static final int FMIX32_C1 = 0x85ebca6b;

    /** MurmurHash3 {@code fmix32} finalizer constant 2 (published value). */
    private static final int FMIX32_C2 = 0xc2b2ae35;

    /**
     * MurmurHash3 {@code fmix64} finalizer constant 1 (published value). NOTE:
     * the {@code fmix64} constants with three {@code 33}-bit shifts, NOT the
     * SplitMix64 <i>generator's</i> final mix ({@code 0xbf58476d1ce4e5b9} /
     * {@code 0x94d049bb133111eb}, shifts {@code 30/27/31}) — a different
     * function.
     */
    private static final long FMIX64_C1 = 0xff51afd7ed558ccdL;

    /** MurmurHash3 {@code fmix64} finalizer constant 2 (published value). */
    private static final long FMIX64_C2 = 0xc4ceb9fe1a85ec53L;

    /**
     * The fixed 32-bit salt for the second base hash of the double-hashing
     * position scheme (the 32-bit golden-ratio prime). Distinct from the 64-bit
     * collection Fibonacci constant {@code 0x9E3779B97F4A7C15}; carried as a
     * {@code long} seed (the seed parameter type) with its high 32 bits zero.
     */
    public static final long SALT2 = 0x9e3779b1L;

    private Hash()
    {
    }

    /**
     * 32-bit named hash: the MurmurHash3 {@code fmix32} finalizer applied to one
     * 32-bit lane derived from {@code word} and a 32-bit fold of the 64-bit
     * {@code seed}.
     *
     * <p>The seed is folded with {@code seed ^ (seed >>> 32)} so two seeds
     * differing only in their high 32 bits still produce different hashes. Seed
     * {@code 0} is an ordinary seed (XOR'd in; no special case). All shifts are
     * logical ({@code >>>}); the {@code int} multiplies wrap mod 2^32.
     *
     * @param word the 32-bit encoding of the input (bits, unsigned)
     * @param seed the 64-bit seed (bits, unsigned)
     * @return the {@code u32} hash as an {@code int} bit pattern
     */
    public static int hash32(int word, long seed)
    {
        // Fold the full 64-bit seed into one 32-bit lane (low XOR high).
        int seed32 = (int) (seed ^ (seed >>> 32));
        int h = word ^ seed32;
        h ^= h >>> 16;
        h *= FMIX32_C1;
        h ^= h >>> 13;
        h *= FMIX32_C2;
        h ^= h >>> 16;
        return h;
    }

    /**
     * 64-bit named hash: the MurmurHash3 {@code fmix64} finalizer applied to
     * {@code word ^ seed}. The seed is mixed in first as a 64-bit integer (no
     * endianness, no special case for seed {@code 0}). All shifts are logical
     * ({@code >>>}); the {@code long} multiplies wrap mod 2^64.
     *
     * @param word the 64-bit encoding of the input (bits, unsigned)
     * @param seed the 64-bit seed (bits, unsigned)
     * @return the {@code u64} hash as a {@code long} bit pattern
     */
    public static long hash64(long word, long seed)
    {
        long h = word ^ seed;
        h ^= h >>> 33;
        h *= FMIX64_C1;
        h ^= h >>> 33;
        h *= FMIX64_C2;
        h ^= h >>> 33;
        return h;
    }

    /** The high 32-bit lane of {@link #hash64} (pins the TS hi/lo lane split). */
    public static int hash64Hi(long word, long seed)
    {
        return (int) (hash64(word, seed) >>> 32);
    }

    /** The low 32-bit lane of {@link #hash64}. */
    public static int hash64Lo(long word, long seed)
    {
        return (int) hash64(word, seed);
    }

    // ---- Per-type input-word encoders ------------------------------------

    /**
     * Encode an {@code i32} element to the {@code hash32} input word: a
     * two's-complement bit reinterpret to {@code u32} (in Java the {@code int}
     * bits ARE that reinterpret — NOT a sign-extend).
     */
    public static int encodeI32Word32(int value)
    {
        return value;
    }

    /**
     * Encode an {@code i32} element to the {@code hash64} input word: reinterpret
     * to {@code u32} then <b>zero-extend</b> to {@code u64} ({@code value &
     * 0xFFFFFFFFL}), so the high 32 bits are always {@code 0}. NOT a sign-extend
     * — sign-extending {@code -1} to {@code 0xffffffffffffffff} is the classic
     * divergence.
     */
    public static long encodeI32Word64(int value)
    {
        return value & 0xFFFFFFFFL;
    }

    /**
     * Fold a raw byte array into the {@code hash32} input word: read 4 bytes at
     * a time as <b>little-endian</b> {@code u32} lanes, XOR-combine, zero-pad a
     * sub-lane tail to the LOW bytes, then XOR in {@code len(bytes) mod 2^32}.
     */
    public static int encodeBytesWord32(byte[] bytes)
    {
        int word = 0;
        int n = bytes.length;
        int full = n & ~3; // largest multiple of 4 <= n
        for (int i = 0; i < full; i += 4)
        {
            word ^= (bytes[i] & 0xFF)
                    | ((bytes[i + 1] & 0xFF) << 8)
                    | ((bytes[i + 2] & 0xFF) << 16)
                    | ((bytes[i + 3] & 0xFF) << 24);
        }
        if (full < n)
        {
            // Tail goes in the LOW bytes of its lane; remaining high bytes are 0.
            int lane = 0;
            int shift = 0;
            for (int i = full; i < n; i++)
            {
                lane |= (bytes[i] & 0xFF) << shift;
                shift += 8;
            }
            word ^= lane;
        }
        // Length reduced mod 2^32 before the XOR (n is already an int).
        return word ^ n;
    }

    /**
     * Fold a raw byte array into the {@code hash64} input word: read 8 bytes at
     * a time as <b>little-endian</b> {@code u64} lanes, XOR-combine, zero-pad a
     * sub-lane tail to the LOW bytes, then XOR in {@code len(bytes) mod 2^64}.
     */
    public static long encodeBytesWord64(byte[] bytes)
    {
        long word = 0L;
        int n = bytes.length;
        int full = n & ~7; // largest multiple of 8 <= n
        for (int i = 0; i < full; i += 8)
        {
            long lane = 0L;
            for (int b = 0; b < 8; b++)
            {
                lane |= (long) (bytes[i + b] & 0xFF) << (8 * b);
            }
            word ^= lane;
        }
        if (full < n)
        {
            long lane = 0L;
            int shift = 0;
            for (int i = full; i < n; i++)
            {
                lane |= (long) (bytes[i] & 0xFF) << shift;
                shift += 8;
            }
            word ^= lane;
        }
        // Length reduced mod 2^64 before the XOR (n widened to long).
        return word ^ (long) n;
    }

    /** {@code hash32} of an {@code i32} element (reinterpret encoding). */
    public static int hash32Int32(int value, long seed)
    {
        return hash32(encodeI32Word32(value), seed);
    }

    /** {@code hash32} of a raw byte array (little-endian fold encoding). */
    public static int hash32Bytes(byte[] bytes, long seed)
    {
        return hash32(encodeBytesWord32(bytes), seed);
    }

    /** {@code hash64} of an {@code i32} element (reinterpret + zero-extend). */
    public static long hash64Int32(int value, long seed)
    {
        return hash64(encodeI32Word64(value), seed);
    }

    /** {@code hash64} of a raw byte array (little-endian fold encoding). */
    public static long hash64Bytes(byte[] bytes, long seed)
    {
        return hash64(encodeBytesWord64(bytes), seed);
    }

    // ---- Derived positions (Kirsch–Mitzenmacher double hashing) ----------

    /**
     * Derive {@code k} array positions over a table of size {@code m} from two
     * base hashes {@code h1}/{@code h2}, combined linearly:
     * {@code p_i = (h1 + i*h2) mod m}, all 32-bit wrapping, <b>unsigned</b>
     * modulo. Returned in derivation order {@code p_0 … p_{k-1}}.
     *
     * <p>The {@code int} add/multiply wrap mod 2^32 natively;
     * {@link Integer#remainderUnsigned} supplies the unsigned {@code % m} (a
     * signed {@code %} is wrong when {@code combined}/{@code m} has the high bit
     * set). {@code m} and {@code k} are read as unsigned (bit pattern).
     */
    public static int[] positionsFromHashes(int h1, int h2, int m, int k)
    {
        int count = k; // k carried as unsigned; sketches use small k
        int[] out = new int[count];
        for (int i = 0; i < count; i++)
        {
            int combined = h1 + i * h2; // both terms wrap mod 2^32 natively
            out[i] = Integer.remainderUnsigned(combined, m);
        }
        return out;
    }

    /**
     * Derive {@code k} array positions for {@code input} over a table of size
     * {@code m} using Kirsch–Mitzenmacher double hashing.
     * {@code h1 = hash32(input, 0)}, {@code h2 = hash32(input, SALT2)}; then
     * {@link #positionsFromHashes}.
     */
    public static int[] positions(byte[] input, int m, int k)
    {
        int h1 = hash32Bytes(input, 0L);
        int h2 = hash32Bytes(input, SALT2);
        return positionsFromHashes(h1, h2, m, k);
    }

    // ---- HyperLogLog split (pre-stated for the HLL feature) --------------

    /**
     * Pre-stated HyperLogLog split: from a single 64-bit hash derive a
     * {@code (register_index, leading_zero_run)} pair. {@code p =
     * log2(number of registers)}, {@code 4 <= p <= 18}. Only {@code hash64(input,
     * 0)} is locked here; HLL itself is a separate later feature.
     *
     * <ul>
     *   <li>{@code idx} = the top {@code p} bits of the hash (register index);</li>
     *   <li>{@code rho} = {@code clz64(w) + 1}, the 1-based leading-zero run of
     *       the remaining bits shifted up with a guard bit set at position
     *       {@code p - 1}.</li>
     * </ul>
     *
     * @return a two-element array {@code {idx, rho}} (both unsigned in {@code int}
     *     bit patterns)
     */
    public static int[] hllSplit(byte[] input, int p)
    {
        long x = hash64Bytes(input, 0L);
        int idx = (int) (x >>> (64 - p));
        long w = (x << p) | (1L << (p - 1));
        int rho = Long.numberOfLeadingZeros(w) + 1;
        return new int[] {idx, rho};
    }
}

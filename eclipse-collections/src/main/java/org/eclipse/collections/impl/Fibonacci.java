/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.collections.impl;

/**
 * 64-bit Fibonacci (golden-ratio multiply) hash spread for the primitive hash
 * collections, replacing the former {@code SpreadFunctions} family.
 *
 * <p>Per {@code spec/algorithms.md} &sect;"Hash function": mapdb uses a 64-bit
 * Fibonacci hash with constant {@code 0x9E3779B97F4A7C15} for both small
 * primitives and pointer-sized inputs, because Java has no strong native
 * primitive hash (the spec's native-hash carve-out names only Rust's SipHash).
 * The old 32-bit Fibonacci constant {@code 0x9E3779B9} gave poor distribution
 * on int64 keys; the 64-bit form is required.
 *
 * <p><b>Construction.</b> Widen the key's raw bits to a 64-bit {@code long},
 * multiply by the golden-ratio constant, and take the high 32 bits of the
 * 128-bit-ish product (here folded down): the high bits of {@code key * C}
 * carry the avalanche. Callers then {@code mask} the low bits of that result,
 * which are exactly the well-mixed high bits of the product, so a power-of-two
 * table sees a good distribution.
 *
 * <p><b>i64 fold.</b> For 64-bit keys (and {@code double}, via raw bits) the
 * high word is folded into the low word ({@code h ^= h >>> 32}) before the
 * multiply, so both halves of a wide key influence the index — the spec's
 * i64-spread note.
 *
 * <p><b>Second sequence.</b> {@code spreadTwo} is an independent sequence used
 * for double hashing: the key bits are first XOR-folded with a distinct salt
 * ({@code 0x9E3779B97F4A7C15} rotated / the 32-bit golden ratio for narrow
 * keys) before the same multiply, giving an avalanche that does not correlate
 * with {@code spreadOne}. The probe step is forced odd ({@code | 1}) by the
 * caller, keeping it coprime to the power-of-two table length.
 *
 * <p><b>Float keys</b> are reinterpreted with {@code floatToRawIntBits} /
 * {@code doubleToRawLongBits} (NOT the canonicalizing {@code floatToIntBits}),
 * consistent with the raw-bit identity used in hash and equality.
 */
public final class Fibonacci
{
    /** 64-bit golden-ratio constant: floor(2^64 / phi), odd. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    /** A distinct salt for the second (independent) probe sequence. */
    private static final long GOLDEN2 = 0xC2B2AE3D27D4EB4FL;

    private Fibonacci()
    {
    }

    private static int spreadOne32(int code)
    {
        long h = (code & 0xFFFFFFFFL) * GOLDEN;
        return (int) (h >>> 32);
    }

    private static int spreadTwo32(int code)
    {
        long h = ((code & 0xFFFFFFFFL) ^ GOLDEN2) * GOLDEN;
        return (int) (h >>> 32);
    }

    private static long spreadOne64(long code)
    {
        long h = code ^ (code >>> 32);
        h *= GOLDEN;
        return h ^ (h >>> 32);
    }

    private static long spreadTwo64(long code)
    {
        long h = code ^ (code >>> 32) ^ GOLDEN2;
        h *= GOLDEN;
        return h ^ (h >>> 32);
    }

    public static long doubleSpreadOne(double element)
    {
        return Fibonacci.spreadOne64(Double.doubleToRawLongBits(element));
    }

    public static long doubleSpreadTwo(double element)
    {
        return Fibonacci.spreadTwo64(Double.doubleToRawLongBits(element));
    }

    public static long longSpreadOne(long element)
    {
        return Fibonacci.spreadOne64(element);
    }

    public static long longSpreadTwo(long element)
    {
        return Fibonacci.spreadTwo64(element);
    }

    public static int intSpreadOne(int element)
    {
        return Fibonacci.spreadOne32(element);
    }

    public static int intSpreadTwo(int element)
    {
        return Fibonacci.spreadTwo32(element);
    }

    public static int floatSpreadOne(float element)
    {
        return Fibonacci.spreadOne32(Float.floatToRawIntBits(element));
    }

    public static int floatSpreadTwo(float element)
    {
        return Fibonacci.spreadTwo32(Float.floatToRawIntBits(element));
    }

    public static int shortSpreadOne(short element)
    {
        return Fibonacci.spreadOne32(element);
    }

    public static int shortSpreadTwo(short element)
    {
        return Fibonacci.spreadTwo32(element);
    }

    public static int charSpreadOne(char element)
    {
        return Fibonacci.spreadOne32(element);
    }

    public static int charSpreadTwo(char element)
    {
        return Fibonacci.spreadTwo32(element);
    }
}

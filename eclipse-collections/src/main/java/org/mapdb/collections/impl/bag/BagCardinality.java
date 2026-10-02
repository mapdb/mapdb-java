/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.bag;

/**
 * Cardinality overflow guard shared by the object and primitive bags.
 * <p>
 * A bag's total size and every per-value occurrence count are {@code int}
 * cardinalities. An add whose resulting total size would exceed
 * {@link Integer#MAX_VALUE} is refused with {@link ArithmeticException} (the
 * {@link Math#addExact(int, int)} convention) before the bag is mutated.
 * Reaching exactly {@link Integer#MAX_VALUE} is allowed. A count never exceeds
 * the total size, so this one check also bounds every count.
 * <p>
 * Internal helper; not part of the public collections API.
 */
public final class BagCardinality
{
    private BagCardinality()
    {
        throw new AssertionError("Suppress default constructor for noninstantiability");
    }

    /**
     * Throws if adding {@code occurrences} (non-negative) to a bag of total
     * {@code size} would exceed {@link Integer#MAX_VALUE}. Overflow-safe: the
     * sum is never computed.
     */
    public static void checkAdd(int size, int occurrences)
    {
        if (occurrences > Integer.MAX_VALUE - size)
        {
            throw new ArithmeticException(
                    "Bag size would exceed Integer.MAX_VALUE: size " + size + " + " + occurrences + " occurrences");
        }
    }

    /**
     * Validates one occurrence count read from a serialized stream, the same
     * way {@code addOccurrences} validates its argument: a negative count
     * throws {@link IllegalArgumentException}.
     */
    public static void checkDeserializedCount(int count)
    {
        if (count < 0)
        {
            throw new IllegalArgumentException("Cannot add a negative number of occurrences: " + count);
        }
    }

    /**
     * Narrows a total summed in a {@code long} (for example over counts read
     * from a serialized stream) to the bag's {@code int} size, throwing
     * {@link ArithmeticException} if it exceeds {@link Integer#MAX_VALUE}.
     */
    public static int checkTotal(long total)
    {
        if (total > Integer.MAX_VALUE)
        {
            throw new ArithmeticException("Bag size would exceed Integer.MAX_VALUE: total " + total);
        }
        return (int) total;
    }
}

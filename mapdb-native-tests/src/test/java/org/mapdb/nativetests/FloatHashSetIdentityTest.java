/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.nativetests;

import org.mapdb.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.mapdb.collections.impl.set.mutable.primitive.FloatHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw-bit float identity for FloatHashSet / DoubleHashSet, per
 * spec/algorithms.md "NaN must hash and compare by bit pattern" and
 * "Tests required". These exercise the raw-bit identity change
 * (Float.floatToRawIntBits / Double.doubleToRawLongBits) in the templates.
 *
 * The distinct-NaN-payload tests FAIL on stock EC (Float.floatToIntBits
 * canonicalizes all NaN to 0x7FC00000, so the set collapses to size 1).
 */
public class FloatHashSetIdentityTest
{
    private static final float NAN_CANON = Float.intBitsToFloat(0x7FC00000);
    private static final float NAN_PAYLOAD = Float.intBitsToFloat(0x7FC00001);
    private static final float NAN_NEG = Float.intBitsToFloat(0xFFC00000);

    // ---- FloatHashSet ----

    @Test
    public void floatNaNKey_Findable()
    {
        FloatHashSet set = new FloatHashSet();
        set.add(NAN_CANON);
        assertTrue(set.contains(NAN_CANON));
        assertEquals(1, set.size());
    }

    @Test
    public void floatNaNKey_Remove()
    {
        FloatHashSet set = new FloatHashSet();
        set.add(NAN_CANON);
        assertTrue(set.remove(NAN_CANON));
        assertFalse(set.contains(NAN_CANON));
        assertEquals(0, set.size());
    }

    @Test
    public void floatNaNKey_Replaces()
    {
        // Adding the same NaN bit pattern twice dedupes to a single key.
        FloatHashSet set = new FloatHashSet();
        set.add(NAN_CANON);
        set.add(NAN_CANON);
        assertEquals(1, set.size());
        assertTrue(set.contains(NAN_CANON));
    }

    @Test
    public void floatNegativeZeroDistinct()
    {
        FloatHashSet set = new FloatHashSet();
        set.add(0.0f);
        set.add(-0.0f);
        assertEquals(2, set.size());
        assertTrue(set.contains(0.0f));
        assertTrue(set.contains(-0.0f));
    }

    @Test
    public void floatInfinityKeys()
    {
        FloatHashSet set = new FloatHashSet();
        set.add(Float.POSITIVE_INFINITY);
        set.add(Float.NEGATIVE_INFINITY);
        assertEquals(2, set.size());
        assertTrue(set.contains(Float.POSITIVE_INFINITY));
        assertTrue(set.contains(Float.NEGATIVE_INFINITY));
    }

    /**
     * Headline proof: distinct NaN payloads/signs are distinct keys.
     * FAILS on stock EC (collapses to size 1).
     */
    @Test
    public void floatDistinctNaNPayloadsAreDistinctKeys()
    {
        FloatHashSet set = new FloatHashSet();
        set.add(NAN_CANON);    // 0x7fc00000
        set.add(NAN_PAYLOAD);  // 0x7fc00001
        set.add(NAN_NEG);      // 0xffc00000
        assertEquals(3, set.size());
        assertTrue(set.contains(NAN_CANON));
        assertTrue(set.contains(NAN_PAYLOAD));
        assertTrue(set.contains(NAN_NEG));
    }

    // ---- DoubleHashSet ----

    private static final double DNAN_CANON = Double.longBitsToDouble(0x7FF8000000000000L);
    private static final double DNAN_PAYLOAD = Double.longBitsToDouble(0x7FF8000000000001L);
    private static final double DNAN_NEG = Double.longBitsToDouble(0xFFF8000000000000L);

    @Test
    public void doubleNaNKey_Findable()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(DNAN_CANON);
        assertTrue(set.contains(DNAN_CANON));
        assertEquals(1, set.size());
    }

    @Test
    public void doubleNaNKey_Replaces()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(DNAN_CANON);
        set.add(DNAN_CANON);
        assertEquals(1, set.size());
        assertTrue(set.contains(DNAN_CANON));
    }

    @Test
    public void doubleNaNKey_Remove()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(DNAN_CANON);
        assertTrue(set.remove(DNAN_CANON));
        assertFalse(set.contains(DNAN_CANON));
        assertEquals(0, set.size());
    }

    @Test
    public void doubleNegativeZeroDistinct()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(0.0d);
        set.add(-0.0d);
        assertEquals(2, set.size());
    }

    @Test
    public void doubleInfinityKeys()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(Double.POSITIVE_INFINITY);
        set.add(Double.NEGATIVE_INFINITY);
        assertEquals(2, set.size());
    }

    @Test
    public void doubleDistinctNaNPayloadsAreDistinctKeys()
    {
        DoubleHashSet set = new DoubleHashSet();
        set.add(DNAN_CANON);
        set.add(DNAN_PAYLOAD);
        set.add(DNAN_NEG);
        assertEquals(3, set.size());
    }
}

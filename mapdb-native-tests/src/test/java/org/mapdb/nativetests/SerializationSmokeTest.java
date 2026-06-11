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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.mapdb.collections.impl.list.mutable.primitive.IntArrayList;
import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.mapdb.collections.impl.set.mutable.primitive.FloatHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Within-version serialization smoke test, per spec/style/java.md
 * "Serialization -- best-effort, not a wire contract". Round-trips a few core
 * collections through ObjectOutputStream/ObjectInputStream and asserts equality.
 * Not a byte-exact / cross-version contract.
 */
public class SerializationSmokeTest
{
    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T original) throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos))
        {
            oos.writeObject(original);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray())))
        {
            return (T) ois.readObject();
        }
    }

    @Test
    public void intIntHashMapRoundTrip() throws Exception
    {
        IntIntHashMap map = new IntIntHashMap();
        map.put(0, 100);   // sentinel key
        map.put(1, 101);   // sentinel key
        map.put(42, 4242);
        map.put(-7, -77);
        IntIntHashMap copy = roundTrip(map);
        assertEquals(map, copy);
        assertEquals(map.size(), copy.size());
    }

    @Test
    public void floatHashSetRoundTrip() throws Exception
    {
        // NOTE: distinct NaN payloads/signs are NOT exercised here because EC's
        // FloatHashSet serializes via DataOutput.writeFloat, whose JDK contract
        // converts with Float.floatToIntBits and therefore CANONICALIZES every
        // NaN bit pattern to 0x7FC00000 at the stream boundary. NaN-payload
        // identity is an in-memory property (covered by FloatHashSetIdentityTest)
        // that cannot survive writeFloat; serialization is best-effort and not a
        // wire contract (spec/style/java.md). Signed zero DOES survive
        // (writeFloat preserves the sign bit), so it is asserted here.
        FloatHashSet set = new FloatHashSet();
        set.add(0.0f);
        set.add(-0.0f);
        set.add(1.0f);
        set.add(Float.intBitsToFloat(0x7FC00000));
        FloatHashSet copy = roundTrip(set);
        assertEquals(set, copy);
        assertEquals(set.size(), copy.size());
        // signed zero survives the round-trip distinctly
        assertTrue(copy.contains(0.0f));
        assertTrue(copy.contains(-0.0f));
    }

    @Test
    public void intArrayListRoundTrip() throws Exception
    {
        IntArrayList list = IntArrayList.newListWith(1, 2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE);
        IntArrayList copy = roundTrip(list);
        assertEquals(list, copy);
    }
}

package org.mapdb.nativetests;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.map.mutable.primitive.ByteBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.ShortBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.CharBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.LongBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.FloatBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.DoubleBooleanHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class PrimitiveBooleanValuesRemovalTest
{
    @Test public void floatingValuesRemovalPreservesRawKeyIdentity()
    {
        FloatBooleanHashMap floats = new FloatBooleanHashMap();
        DoubleBooleanHashMap doubles = new DoubleBooleanHashMap();
        for (int key = 2; key < 100; key++) { floats.put(key, false); doubles.put(key, false); }
        floats.put(0.0f, false); floats.put(-0.0f, true);
        floats.put(Float.intBitsToFloat(0x7fc00001), false);
        floats.put(Float.intBitsToFloat(0x7fc00002), true);
        doubles.put(0.0d, false); doubles.put(-0.0d, true);
        doubles.put(Double.longBitsToDouble(0x7ff8000000000001L), false);
        doubles.put(Double.longBitsToDouble(0x7ff8000000000002L), true);
        assertTrue(floats.values().remove(false)); assertTrue(doubles.values().remove(false));
        assertEquals(2, floats.size()); assertEquals(2, doubles.size());
        assertFalse(floats.containsKey(0.0f)); assertTrue(floats.containsKey(-0.0f));
        assertFalse(floats.containsKey(Float.intBitsToFloat(0x7fc00001)));
        assertTrue(floats.containsKey(Float.intBitsToFloat(0x7fc00002)));
        assertFalse(doubles.containsKey(0.0d)); assertTrue(doubles.containsKey(-0.0d));
        assertFalse(doubles.containsKey(Double.longBitsToDouble(0x7ff8000000000001L)));
        assertTrue(doubles.containsKey(Double.longBitsToDouble(0x7ff8000000000002L)));
    }

    @Test public void collidingValuesRemovalCrossesCompactionThreshold()
    {
        for (int count = 16; count <= 100; count++)
        {
            IntBooleanHashMap map = new IntBooleanHashMap();
            for (int key = 0; key < count; key++) map.put(2 + key * 65536, false);
            assertTrue(map.values().remove(false));
            assertTrue(map.isEmpty(), "count " + count + ": remaining " + map.size());
        }
    }

    @Test public void byteValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            ByteBooleanHashMap map = new ByteBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((byte) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((byte) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((byte) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((byte) 101));
        }
    }

    @Test public void shortValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            ShortBooleanHashMap map = new ShortBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((short) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((short) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((short) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((short) 101));
        }
    }

    @Test public void charValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            CharBooleanHashMap map = new CharBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((char) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((char) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((char) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((char) 101));
        }
    }

    @Test public void intValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            IntBooleanHashMap map = new IntBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((int) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((int) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((int) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((int) 101));
        }
    }

    @Test public void longValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            LongBooleanHashMap map = new LongBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((long) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((long) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((long) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((long) 101));
        }
    }

    @Test public void floatValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            FloatBooleanHashMap map = new FloatBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((float) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((float) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((float) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((float) 101));
        }
    }

    @Test public void doubleValuesRemovalVisitsAllMatchingKeys()
    {
        for (boolean mixed : new boolean[]{false, true})
        {
            DoubleBooleanHashMap map = new DoubleBooleanHashMap();
            for (int key = 0; key < 100; key++) map.put((double) key, mixed && key % 3 == 0);
            assertTrue(map.values().remove(false));
            assertFalse(map.containsValue(false));
            assertEquals(mixed ? 34 : 0, map.size());
            for (int key = 0; key < 100; key++)
                assertEquals(mixed && key % 3 == 0, map.containsKey((double) key), "key " + key);
            assertFalse(map.values().remove(false));
            if (mixed) assertTrue(map.values().removeAll(true));
            assertTrue(map.isEmpty());
            for (int key = 0; key < 100; key++) map.put((double) key, true);
            assertEquals(100, map.size());
            assertFalse(map.containsKey((double) 101));
        }
    }

}

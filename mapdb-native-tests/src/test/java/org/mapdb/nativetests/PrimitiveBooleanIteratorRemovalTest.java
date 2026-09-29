package org.mapdb.nativetests;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.iterator.MutableBooleanIterator;
import org.mapdb.collections.api.iterator.MutableIntIterator;
import org.mapdb.collections.api.iterator.MutableLongIterator;
import org.mapdb.collections.api.iterator.MutableFloatIterator;
import org.mapdb.collections.api.iterator.MutableDoubleIterator;
import org.mapdb.collections.impl.map.mutable.primitive.IntBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.LongBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.FloatBooleanHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.DoubleBooleanHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class PrimitiveBooleanIteratorRemovalTest
{
    @Test public void intKeyIteratorSelectiveRemovalVisitsOriginalKeys()
    {
        for (boolean sentinels : new boolean[]{false, true})
        {
            IntBooleanHashMap map = new IntBooleanHashMap();
            Set<Integer> expected = new HashSet<>();
            for (int k = sentinels ? 0 : 2; k < 18; k++) { map.put(k, (k & 1) == 0); expected.add(k); }
            Set<Integer> seen = new HashSet<>();
            MutableIntIterator it = map.keySet().intIterator();
            assertThrows(IllegalStateException.class, it::remove);
            int count = 0;
            while (it.hasNext())
            {
                int key = it.next();
                assertTrue(seen.add(key), "duplicate key " + key);
                if (count++ % 3 != 0)
                {
                    it.remove();
                    assertFalse(map.containsKey(key));
                    assertThrows(IllegalStateException.class, it::remove);
                }
            }
            assertEquals(expected, seen);
            for (int k = 0; k < 18; k++)
                if (map.containsKey(k)) assertEquals((k & 1) == 0, map.get(k));
        }
    }

    @Test public void longValuesIteratorCanDrainAcrossCompactionThreshold()
    {
        LongBooleanHashMap map = new LongBooleanHashMap();
        for (long k = 0; k < 66; k++) map.put(k, (k & 1) == 0);
        MutableBooleanIterator it = map.booleanIterator();
        int count = 0;
        while (it.hasNext()) { it.next(); it.remove(); count++; }
        assertEquals(66, count);
        assertTrue(map.isEmpty());
        assertFalse(map.containsKey(1000L));
        for (long k = 1000; k < 1066; k++) map.put(k, true);
        assertEquals(66, map.size());
        for (long k = 1000; k < 1066; k++) assertTrue(map.get(k));
        assertFalse(map.containsKey(-1000L));
    }

    @Test public void longKeyIteratorHandlesCollisionsAndFollowingChurn()
    {
        LongBooleanHashMap map = new LongBooleanHashMap();
        for (int round = 0; round < 20; round++)
        {
            Set<Long> expected = new HashSet<>();
            // Same low first-probe bits, spread probes must still find each key.
            for (long k = 0; k < 32; k++) { long key = (k << 32) | 2; expected.add(key); map.put(key, true); }
            Set<Long> seen = new HashSet<>();
            MutableLongIterator it = map.keySet().longIterator();
            while (it.hasNext()) { assertTrue(seen.add(it.next())); it.remove(); }
            assertEquals(expected, seen);
            assertTrue(map.isEmpty());
            assertFalse(map.containsKey(99L));
            for (long k = 1000; k < 1064; k++) { map.put(k + round * 128, false); map.removeKey(k + round * 128); }
            assertTrue(map.isEmpty());
        }
    }

    @Test public void insertingAfterIteratorDrainKeepsAnEmptyProbeSlot() throws Exception
    {
        IntBooleanHashMap map = new IntBooleanHashMap();
        for (int key = 2; key < 18; key++) map.put(key, true);
        MutableIntIterator it = map.keySet().intIterator();
        while (it.hasNext()) { it.next(); it.remove(); }
        java.lang.reflect.Field keys = IntBooleanHashMap.class.getDeclaredField("keys");
        keys.setAccessible(true);
        java.lang.reflect.Method spread = IntBooleanHashMap.class.getDeclaredMethod("spreadAndMask", int.class);
        spread.setAccessible(true);
        int[] drainedSlots = (int[]) keys.get(map);
        java.util.List<Integer> newKeys = new java.util.ArrayList<>();
        // Select fresh keys using the actual production spread: numeric-key
        // guesses do not target distinct first-probe slots in this family.
        for (int slot = 0; slot < drainedSlots.length; slot++)
        {
            if (drainedSlots[slot] != 0) continue;
            int candidate = 1000;
            while ((int) spread.invoke(map, candidate) != slot) candidate++;
            newKeys.add(candidate);
        }
        for (int key : newKeys) map.put(key, false);
        int[] slots = (int[]) keys.get(map);
        assertTrue(java.util.Arrays.stream(slots).anyMatch(key -> key == 0),
                "an absent probe needs an empty slot after deferred compaction");
        assertFalse(map.containsKey(100));
        for (int key : newKeys) assertTrue(map.containsKey(key));
    }

    @Test public void floatKeyRemovalPreservesRawIdentity()
    {
        FloatBooleanHashMap map = new FloatBooleanHashMap();
        Set<Integer> expected = new HashSet<>();
        for (int k = 0; k < 32; k++) { map.put(k, true); expected.add(Float.floatToRawIntBits(k)); }
        for (int bits : new int[]{0x80000000, 0x7fc00001, 0x7fc00002}) { map.put(Float.intBitsToFloat(bits), false); expected.add(bits); }
        Set<Integer> seen = new HashSet<>();
        MutableFloatIterator it = map.keySet().floatIterator();
        while (it.hasNext()) { assertTrue(seen.add(Float.floatToRawIntBits(it.next()))); it.remove(); }
        assertEquals(expected, seen);
        assertTrue(map.isEmpty());
        assertFalse(map.containsKey(Float.intBitsToFloat(0x7fc00003)));
        map.put(-0.0f, false); map.put(0.0f, true);
        assertEquals(2, map.size());
        assertFalse(map.get(-0.0f)); assertTrue(map.get(0.0f));
    }

    @Test public void doubleKeyRemovalPreservesRawIdentity()
    {
        DoubleBooleanHashMap map = new DoubleBooleanHashMap();
        Set<Long> expected = new HashSet<>();
        for (int k = 0; k < 32; k++) { map.put(k, true); expected.add(Double.doubleToRawLongBits(k)); }
        for (long bits : new long[]{0x8000000000000000L, 0x7ff8000000000001L, 0x7ff8000000000002L}) { map.put(Double.longBitsToDouble(bits), false); expected.add(bits); }
        Set<Long> seen = new HashSet<>();
        MutableDoubleIterator it = map.keySet().doubleIterator();
        while (it.hasNext()) { assertTrue(seen.add(Double.doubleToRawLongBits(it.next()))); it.remove(); }
        assertEquals(expected, seen);
        assertTrue(map.isEmpty());
        assertFalse(map.containsKey(Double.longBitsToDouble(0x7ff8000000000003L)));
    }
}

// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.Pump.Change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "A3" bulk mutation ({@link Pump}).
 */
public class BulkMutationTest
{
    private static ImmutableSortedMap<Integer, String> map(int[] keys, String[] values)
    {
        List<Integer> ks = new ArrayList<>();
        List<String> vs = new ArrayList<>();
        for (int i = 0; i < keys.length; i++)
        {
            ks.add(keys[i]);
            vs.add(values[i]);
        }
        return ImmutableSortedMap.fromSorted(ks, vs);
    }

    private static String dump(ImmutableSortedMap<Integer, String> m)
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : m.entries())
        {
            if (sb.length() > 0)
            {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---- applySorted ----

    @Test
    public void applySortedMixedUpsertsAndDeletes()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 3, 5, 7}, new String[] {"a", "c", "e", "g"});
        List<Change<Integer, String>> changes = Arrays.asList(
                Change.upsert(0, "z"),   // insert before all
                Change.delete(3),        // remove existing
                Change.upsert(5, "E"),   // overwrite existing
                Change.delete(6),        // delete absent -> no-op
                Change.upsert(9, "i"));  // append after all

        ImmutableSortedMap<Integer, String> out = Pump.applySorted(base, changes);
        assertEquals("0=z,1=a,5=E,7=g,9=i", dump(out));
        // base untouched (immutable)
        assertEquals("1=a,3=c,5=e,7=g", dump(base));
    }

    @Test
    public void applySortedEmptyChangesReturnsEquivalent()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2}, new String[] {"a", "b"});
        assertEquals("1=a,2=b", dump(Pump.applySorted(base, List.of())));
    }

    @Test
    public void applySortedOntoEmptyBase()
    {
        ImmutableSortedMap<Integer, String> empty = map(new int[] {}, new String[] {});
        List<Change<Integer, String>> changes = Arrays.asList(
                Change.upsert(2, "b"), Change.delete(5), Change.upsert(8, "h"));
        assertEquals("2=b,8=h", dump(Pump.applySorted(empty, changes)));
    }

    @Test
    public void applySortedDeleteAllYieldsEmpty()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        List<Change<Integer, String>> changes = Arrays.asList(
                Change.delete(1), Change.delete(2), Change.delete(3));
        assertTrue(Pump.applySorted(base, changes).isEmpty());
    }

    @Test
    public void applySortedRejectsOutOfOrderChanges()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1}, new String[] {"a"});
        List<Change<Integer, String>> bad = Arrays.asList(Change.upsert(5, "x"), Change.upsert(2, "y"));
        assertThrows(IllegalArgumentException.class, () -> Pump.applySorted(base, bad));
    }

    @Test
    public void applySortedRejectsDuplicateChangeKeys()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1}, new String[] {"a"});
        List<Change<Integer, String>> dup = Arrays.asList(Change.upsert(2, "x"), Change.delete(2));
        assertThrows(IllegalArgumentException.class, () -> Pump.applySorted(base, dup));
    }

    @Test
    public void applySortedOverwriteAndDeleteAtFirstAndLastKey()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 3, 5, 7}, new String[] {"a", "c", "e", "g"});
        // overwrite the first key, delete the last key
        assertEquals("1=A,3=c,5=e", dump(Pump.applySorted(base,
                Arrays.asList(Change.upsert(1, "A"), Change.delete(7)))));
        // delete the first key, overwrite the last key
        assertEquals("3=c,5=e,7=G", dump(Pump.applySorted(base,
                Arrays.asList(Change.delete(1), Change.upsert(7, "G")))));
    }

    @Test
    public void applySortedChangesEntirelyBelowBaseDrainsBaseTail()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {5, 7}, new String[] {"e", "g"});
        // all change keys sort below the base -> base tail must still be emitted
        assertEquals("1=z,5=e,7=g", dump(Pump.applySorted(base,
                Arrays.asList(Change.upsert(1, "z"), Change.delete(2)))));
    }

    @Test
    public void applySortedDeleteThenUpsertAdjacentKey()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2}, new String[] {"a", "b"});
        assertEquals("2=B", dump(Pump.applySorted(base,
                Arrays.asList(Change.delete(1), Change.upsert(2, "B")))));
    }

    @Test
    public void applySortedRejectsNullChangeElement()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1}, new String[] {"a"});
        List<Change<Integer, String>> withNull = Arrays.asList(Change.upsert(2, "x"), null);
        assertThrows(NullPointerException.class, () -> Pump.applySorted(base, withNull));
    }

    @Test
    public void upsertRejectsNullValue()
    {
        assertThrows(NullPointerException.class, () -> Change.upsert(1, null));
    }

    // ---- mergeSortedDisjoint ----

    @Test
    public void mergeSortedDisjointInterleaves()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 4, 7}, new String[] {"a", "d", "g"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {2, 5, 9}, new String[] {"b", "e", "i"});
        assertEquals("1=a,2=b,4=d,5=e,7=g,9=i", dump(Pump.mergeSortedDisjoint(a, b)));
    }

    @Test
    public void mergeSortedDisjointAppendsHigherRun()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {4, 5}, new String[] {"d", "e"});
        assertEquals("1=a,2=b,3=c,4=d,5=e", dump(Pump.mergeSortedDisjoint(a, b)));
    }

    @Test
    public void mergeSortedDisjointRejectsSharedKey()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 4}, new String[] {"a", "d"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {4, 9}, new String[] {"X", "i"});
        assertThrows(IllegalArgumentException.class, () -> Pump.mergeSortedDisjoint(a, b));
    }

    @Test
    public void mergeSortedDisjointWithEmpty()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 2}, new String[] {"a", "b"});
        ImmutableSortedMap<Integer, String> empty = map(new int[] {}, new String[] {});
        assertEquals("1=a,2=b", dump(Pump.mergeSortedDisjoint(a, empty)));
        assertEquals("1=a,2=b", dump(Pump.mergeSortedDisjoint(empty, a)));
        assertTrue(Pump.mergeSortedDisjoint(empty, empty).isEmpty());
    }

    @Test
    public void mergeSortedDisjointPrependsLowerRun()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {4, 5}, new String[] {"d", "e"});
        ImmutableSortedMap<Integer, String> lower = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        assertEquals("1=a,2=b,3=c,4=d,5=e", dump(Pump.mergeSortedDisjoint(base, lower)));
    }

    @Test
    public void mergeSortedDisjointDetectsCollisionAtFirstAndLastPosition()
    {
        // collision on the very first pair
        ImmutableSortedMap<Integer, String> a1 = map(new int[] {4, 8}, new String[] {"a", "b"});
        ImmutableSortedMap<Integer, String> b1 = map(new int[] {4, 9}, new String[] {"X", "i"});
        assertThrows(IllegalArgumentException.class, () -> Pump.mergeSortedDisjoint(a1, b1));
        // one map a strict prefix of the other's range, shared key last
        ImmutableSortedMap<Integer, String> a2 = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        ImmutableSortedMap<Integer, String> b2 = map(new int[] {3}, new String[] {"X"});
        assertThrows(IllegalArgumentException.class, () -> Pump.mergeSortedDisjoint(a2, b2));
    }

    // ---- rangeDelete ----

    @Test
    public void rangeDeleteCutsContiguousBlock()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 2, 3, 4, 5, 6}, new String[] {"a", "b", "c", "d", "e", "f"});
        // [3,5) removes 3,4 ; 5 stays (open upper)
        assertEquals("1=a,2=b,5=e,6=f", dump(Pump.rangeDelete(base, Range.closedOpen(3, 5))));
    }

    @Test
    public void rangeDeleteClosedRangeIncludesEndpoints()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 2, 3, 4, 5}, new String[] {"a", "b", "c", "d", "e"});
        assertEquals("1=a,5=e", dump(Pump.rangeDelete(base, Range.closed(2, 4))));
    }

    @Test
    public void rangeDeleteAllYieldsEmpty()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        assertTrue(Pump.rangeDelete(base, Range.all()).isEmpty());
    }

    @Test
    public void rangeDeleteDisjointRangeIsNoOp()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        assertEquals("1=a,2=b,3=c", dump(Pump.rangeDelete(base, Range.closed(10, 20))));
    }

    @Test
    public void rangeDeleteEmptyRangeIsNoOp()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        // closedOpen(2,2) is empty -> nothing removed
        assertEquals("1=a,2=b,3=c", dump(Pump.rangeDelete(base, Range.closedOpen(2, 2))));
    }

    @Test
    public void rangeDeleteHalfUnboundedCutsPrefixAndSuffix()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 2, 3, 4, 5}, new String[] {"a", "b", "c", "d", "e"});
        // atLeast(3): remove 3,4,5 (inclusive lower)
        assertEquals("1=a,2=b", dump(Pump.rangeDelete(base, Range.atLeast(3))));
        // lessThan(3): remove 1,2 (exclusive upper -> 3 survives)
        assertEquals("3=c,4=d,5=e", dump(Pump.rangeDelete(base, Range.lessThan(3))));
    }

    // ---- differential fuzz vs java.util.TreeMap ----

    @Test
    public void applySortedMatchesTreeMapReference()
    {
        Random rnd = new Random(20260704L);
        for (int trial = 0; trial < 200; trial++)
        {
            // Build a random base.
            TreeMap<Integer, String> ref = new TreeMap<>();
            List<Integer> bk = new ArrayList<>();
            List<String> bv = new ArrayList<>();
            for (int k = 0; k < 40; k++)
            {
                if (rnd.nextBoolean())
                {
                    ref.put(k, "v" + k);
                    bk.add(k);
                    bv.add("v" + k);
                }
            }
            ImmutableSortedMap<Integer, String> base = ImmutableSortedMap.fromSorted(bk, bv);

            // Build a random strictly-ascending change list and mirror it on the reference.
            List<Change<Integer, String>> changes = new ArrayList<>();
            for (int k = 0; k < 40; k++)
            {
                if (!rnd.nextBoolean())
                {
                    continue;
                }
                if (rnd.nextBoolean())
                {
                    String nv = "n" + trial + "_" + k;
                    changes.add(Change.upsert(k, nv));
                    ref.put(k, nv);
                }
                else
                {
                    changes.add(Change.delete(k));
                    ref.remove(k);
                }
            }

            ImmutableSortedMap<Integer, String> got = Pump.applySorted(base, changes);

            StringBuilder expected = new StringBuilder();
            for (Map.Entry<Integer, String> e : ref.entrySet())
            {
                if (expected.length() > 0)
                {
                    expected.append(',');
                }
                expected.append(e.getKey()).append('=').append(e.getValue());
            }
            assertEquals(expected.toString(), dump(got), "trial " + trial);
        }
    }

    // ---- Change value type ----

    @Test
    public void deleteChangeHasNoValue()
    {
        Change<Integer, String> d = Change.delete(1);
        assertTrue(d.isDelete());
        assertThrows(IllegalStateException.class, d::value);
    }
}

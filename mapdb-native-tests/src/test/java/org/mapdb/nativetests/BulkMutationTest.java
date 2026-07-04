// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.stream.BulkMutation;
import org.mapdb.collections.impl.stream.BulkMutation.Change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "A3" bulk mutation ({@link BulkMutation}).
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

        ImmutableSortedMap<Integer, String> out = BulkMutation.applySorted(base, changes);
        assertEquals("0=z,1=a,5=E,7=g,9=i", dump(out));
        // base untouched (immutable)
        assertEquals("1=a,3=c,5=e,7=g", dump(base));
    }

    @Test
    public void applySortedEmptyChangesReturnsEquivalent()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2}, new String[] {"a", "b"});
        assertEquals("1=a,2=b", dump(BulkMutation.applySorted(base, List.of())));
    }

    @Test
    public void applySortedOntoEmptyBase()
    {
        ImmutableSortedMap<Integer, String> empty = map(new int[] {}, new String[] {});
        List<Change<Integer, String>> changes = Arrays.asList(
                Change.upsert(2, "b"), Change.delete(5), Change.upsert(8, "h"));
        assertEquals("2=b,8=h", dump(BulkMutation.applySorted(empty, changes)));
    }

    @Test
    public void applySortedDeleteAllYieldsEmpty()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        List<Change<Integer, String>> changes = Arrays.asList(
                Change.delete(1), Change.delete(2), Change.delete(3));
        assertTrue(BulkMutation.applySorted(base, changes).isEmpty());
    }

    @Test
    public void applySortedRejectsOutOfOrderChanges()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1}, new String[] {"a"});
        List<Change<Integer, String>> bad = Arrays.asList(Change.upsert(5, "x"), Change.upsert(2, "y"));
        assertThrows(IllegalArgumentException.class, () -> BulkMutation.applySorted(base, bad));
    }

    @Test
    public void applySortedRejectsDuplicateChangeKeys()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1}, new String[] {"a"});
        List<Change<Integer, String>> dup = Arrays.asList(Change.upsert(2, "x"), Change.delete(2));
        assertThrows(IllegalArgumentException.class, () -> BulkMutation.applySorted(base, dup));
    }

    // ---- mergeSortedDisjoint ----

    @Test
    public void mergeSortedDisjointInterleaves()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 4, 7}, new String[] {"a", "d", "g"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {2, 5, 9}, new String[] {"b", "e", "i"});
        assertEquals("1=a,2=b,4=d,5=e,7=g,9=i", dump(BulkMutation.mergeSortedDisjoint(a, b)));
    }

    @Test
    public void mergeSortedDisjointAppendsHigherRun()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {4, 5}, new String[] {"d", "e"});
        assertEquals("1=a,2=b,3=c,4=d,5=e", dump(BulkMutation.mergeSortedDisjoint(a, b)));
    }

    @Test
    public void mergeSortedDisjointRejectsSharedKey()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 4}, new String[] {"a", "d"});
        ImmutableSortedMap<Integer, String> b = map(new int[] {4, 9}, new String[] {"X", "i"});
        assertThrows(IllegalArgumentException.class, () -> BulkMutation.mergeSortedDisjoint(a, b));
    }

    @Test
    public void mergeSortedDisjointWithEmpty()
    {
        ImmutableSortedMap<Integer, String> a = map(new int[] {1, 2}, new String[] {"a", "b"});
        ImmutableSortedMap<Integer, String> empty = map(new int[] {}, new String[] {});
        assertEquals("1=a,2=b", dump(BulkMutation.mergeSortedDisjoint(a, empty)));
        assertEquals("1=a,2=b", dump(BulkMutation.mergeSortedDisjoint(empty, a)));
    }

    // ---- rangeDelete ----

    @Test
    public void rangeDeleteCutsContiguousBlock()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 2, 3, 4, 5, 6}, new String[] {"a", "b", "c", "d", "e", "f"});
        // [3,5) removes 3,4 ; 5 stays (open upper)
        assertEquals("1=a,2=b,5=e,6=f", dump(BulkMutation.rangeDelete(base, Range.closedOpen(3, 5))));
    }

    @Test
    public void rangeDeleteClosedRangeIncludesEndpoints()
    {
        ImmutableSortedMap<Integer, String> base = map(
                new int[] {1, 2, 3, 4, 5}, new String[] {"a", "b", "c", "d", "e"});
        assertEquals("1=a,5=e", dump(BulkMutation.rangeDelete(base, Range.closed(2, 4))));
    }

    @Test
    public void rangeDeleteAllYieldsEmpty()
    {
        ImmutableSortedMap<Integer, String> base = map(new int[] {1, 2, 3}, new String[] {"a", "b", "c"});
        assertTrue(BulkMutation.rangeDelete(base, Range.all()).isEmpty());
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

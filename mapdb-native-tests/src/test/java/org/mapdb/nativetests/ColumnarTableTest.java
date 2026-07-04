// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.columnar.ColumnarTable;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "C5" columnar record adapter
 * ({@link ColumnarTable}). Covers construction validation + immutability, the
 * accessor surface, key-range filtering (via {@link Range#bracket}), column
 * projection, the A1 sort-merge {@link ColumnarTable#join}, and the sorted-family
 * bridges.
 */
public class ColumnarTableTest
{
    /** keys {10,20,30}, age=[1,2,3], score=[100,200,300]. */
    private static ColumnarTable sample()
    {
        return ColumnarTable.of(
                new int[] {10, 20, 30},
                new String[] {"age", "score"},
                new int[][] {{1, 2, 3}, {100, 200, 300}});
    }

    // ---- Construction / validation / immutability ------------------------

    @Test
    public void basicAccessors()
    {
        ColumnarTable t = sample();
        assertEquals(3, t.rowCount());
        assertFalse(t.isEmpty());
        assertEquals(2, t.valueColumnCount());
        assertEquals(List.of("age", "score"), t.columnNames());
        assertTrue(t.hasColumn("age"));
        assertFalse(t.hasColumn("nope"));
        assertEquals(20, t.keyAt(1));
        assertEquals(2, t.valueAt("age", 1));
        assertEquals(200, t.valueAt("score", 1));
        assertArrayEquals(new int[] {100, 200, 300}, t.column("score"));
        assertArrayEquals(new int[] {10, 20, 30}, t.keyColumn());
    }

    @Test
    public void rowOfKey()
    {
        ColumnarTable t = sample();
        assertEquals(0, t.rowOfKey(10));
        assertEquals(2, t.rowOfKey(30));
        assertEquals(-1, t.rowOfKey(15));
        assertEquals(-1, t.rowOfKey(999));
        assertEquals(-1, t.rowOfKey(-5));
    }

    @Test
    public void rejectsNonAscendingKeys()
    {
        assertThrows(IllegalArgumentException.class, () -> ColumnarTable.of(
                new int[] {10, 10}, new String[] {"c"}, new int[][] {{1, 2}}));
        assertThrows(IllegalArgumentException.class, () -> ColumnarTable.of(
                new int[] {20, 10}, new String[] {"c"}, new int[][] {{1, 2}}));
    }

    @Test
    public void rejectsLengthMismatchAndArityAndDupNames()
    {
        assertThrows(IllegalArgumentException.class, () -> ColumnarTable.of(
                new int[] {1, 2, 3}, new String[] {"c"}, new int[][] {{1, 2}}));
        assertThrows(IllegalArgumentException.class, () -> ColumnarTable.of(
                new int[] {1, 2}, new String[] {"a", "b"}, new int[][] {{1, 2}}));
        assertThrows(IllegalArgumentException.class, () -> ColumnarTable.of(
                new int[] {1, 2}, new String[] {"a", "a"}, new int[][] {{1, 2}, {3, 4}}));
    }

    @Test
    public void inputsAndOutputsAreCopied()
    {
        int[] keys = {1, 2, 3};
        int[] col = {7, 8, 9};
        ColumnarTable t = ColumnarTable.of(keys, new String[] {"c"}, new int[][] {col});
        keys[0] = 999;
        col[0] = 999;
        assertEquals(1, t.keyAt(0));
        assertEquals(7, t.valueAt("c", 0));
        // A returned column is a copy — mutating it must not affect the table.
        int[] got = t.column("c");
        got[0] = -1;
        assertEquals(7, t.valueAt("c", 0));
    }

    @Test
    public void emptyTable()
    {
        ColumnarTable t = ColumnarTable.of(new int[] {}, new String[] {"c"}, new int[][] {{}});
        assertTrue(t.isEmpty());
        assertEquals(0, t.rowCount());
        assertEquals(-1, t.rowOfKey(5));
    }

    @Test
    public void unknownColumnThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> sample().column("missing"));
        assertThrows(IllegalArgumentException.class, () -> sample().valueAt("missing", 0));
    }

    // ---- filterByKeyRange -------------------------------------------------

    @Test
    public void filterByKeyRangeClosed()
    {
        ColumnarTable t = sample();
        ColumnarTable f = t.filterByKeyRange(Range.closed(20, 30));
        assertArrayEquals(new int[] {20, 30}, f.keyColumn());
        assertArrayEquals(new int[] {2, 3}, f.column("age"));
        assertArrayEquals(new int[] {200, 300}, f.column("score"));
        assertEquals(List.of("age", "score"), f.columnNames());
    }

    @Test
    public void filterByKeyRangeOpenAndBoundaries()
    {
        ColumnarTable t = sample();
        // open(10,30) excludes both endpoints -> just {20}
        assertArrayEquals(new int[] {20}, t.filterByKeyRange(Range.open(10, 30)).keyColumn());
        // atLeast(20) -> {20,30}
        assertArrayEquals(new int[] {20, 30}, t.filterByKeyRange(Range.atLeast(20)).keyColumn());
        // lessThan(20) -> {10}
        assertArrayEquals(new int[] {10}, t.filterByKeyRange(Range.lessThan(20)).keyColumn());
        // all -> full
        assertArrayEquals(new int[] {10, 20, 30}, t.filterByKeyRange(Range.all()).keyColumn());
    }

    @Test
    public void filterByKeyRangeDisjointIsEmpty()
    {
        ColumnarTable t = sample();
        ColumnarTable f = t.filterByKeyRange(Range.closed(100, 200));
        assertTrue(f.isEmpty());
        assertEquals(List.of("age", "score"), f.columnNames());
    }

    // ---- project ----------------------------------------------------------

    @Test
    public void projectSubsetAndReorder()
    {
        ColumnarTable t = sample();
        ColumnarTable p = t.project("score");
        assertEquals(List.of("score"), p.columnNames());
        assertArrayEquals(new int[] {100, 200, 300}, p.column("score"));
        assertArrayEquals(new int[] {10, 20, 30}, p.keyColumn());

        ColumnarTable r = t.project("score", "age");
        assertEquals(List.of("score", "age"), r.columnNames());
    }

    @Test
    public void projectDuplicateOrUnknownThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> sample().project("age", "age"));
        assertThrows(IllegalArgumentException.class, () -> sample().project("ghost"));
    }

    // ---- join -------------------------------------------------------------

    @Test
    public void innerJoinMatchesKeysAndConcatenatesColumns()
    {
        ColumnarTable left = ColumnarTable.of(
                new int[] {1, 2, 3, 5}, new String[] {"l"}, new int[][] {{10, 20, 30, 50}});
        ColumnarTable right = ColumnarTable.of(
                new int[] {2, 3, 4, 5}, new String[] {"r"}, new int[][] {{200, 300, 400, 500}});
        ColumnarTable j = left.join(right);
        // common keys {2,3,5}
        assertArrayEquals(new int[] {2, 3, 5}, j.keyColumn());
        assertEquals(List.of("l", "r"), j.columnNames());
        assertArrayEquals(new int[] {20, 30, 50}, j.column("l"));
        assertArrayEquals(new int[] {200, 300, 500}, j.column("r"));
    }

    @Test
    public void joinNoOverlapIsEmpty()
    {
        ColumnarTable left = ColumnarTable.of(new int[] {1, 2}, new String[] {"l"}, new int[][] {{1, 2}});
        ColumnarTable right = ColumnarTable.of(new int[] {8, 9}, new String[] {"r"}, new int[][] {{8, 9}});
        ColumnarTable j = left.join(right);
        assertTrue(j.isEmpty());
        assertEquals(List.of("l", "r"), j.columnNames());
    }

    @Test
    public void joinNameCollisionThrows()
    {
        ColumnarTable a = ColumnarTable.of(new int[] {1}, new String[] {"x"}, new int[][] {{1}});
        ColumnarTable b = ColumnarTable.of(new int[] {1}, new String[] {"x"}, new int[][] {{2}});
        assertThrows(IllegalArgumentException.class, () -> a.join(b));
    }

    @Test
    public void joinWithNegativeKeysStaysSignedAscending()
    {
        ColumnarTable left = ColumnarTable.of(
                new int[] {-5, -1, 3}, new String[] {"l"}, new int[][] {{1, 2, 3}});
        ColumnarTable right = ColumnarTable.of(
                new int[] {-1, 0, 3}, new String[] {"r"}, new int[][] {{9, 8, 7}});
        ColumnarTable j = left.join(right);
        assertArrayEquals(new int[] {-1, 3}, j.keyColumn());
        assertArrayEquals(new int[] {2, 3}, j.column("l"));
        assertArrayEquals(new int[] {9, 7}, j.column("r"));
    }

    @Test
    public void multiColumnJoinResult()
    {
        ColumnarTable left = ColumnarTable.of(
                new int[] {1, 2, 3}, new String[] {"a", "b"}, new int[][] {{11, 22, 33}, {1, 1, 1}});
        ColumnarTable right = ColumnarTable.of(
                new int[] {2, 3}, new String[] {"c"}, new int[][] {{222, 333}});
        ColumnarTable j = left.join(right);
        assertArrayEquals(new int[] {2, 3}, j.keyColumn());
        assertEquals(List.of("a", "b", "c"), j.columnNames());
        assertArrayEquals(new int[] {22, 33}, j.column("a"));
        assertArrayEquals(new int[] {1, 1}, j.column("b"));
        assertArrayEquals(new int[] {222, 333}, j.column("c"));
    }

    // ---- bridges to the sorted family ------------------------------------

    @Test
    public void keyColumnAsSetBridge()
    {
        ImmutableSortedSet<Integer> s = sample().keyColumnAsSet();
        assertEquals(3, s.size());
        assertTrue(s.contains(20));
        assertFalse(s.contains(15));
        assertEquals(Arrays.asList(10, 20, 30), s.elements());
    }

    @Test
    public void columnAsMapBridge()
    {
        ImmutableSortedMap<Integer, Integer> m = sample().columnAsMap("score");
        assertEquals(3, m.size());
        assertEquals(200, m.get(20).get());
        assertEquals(300, m.get(30).get());
        assertTrue(m.get(15).isEmpty());
    }

    // ---- Content address / digest (C2 via A2) ----------------------------

    @Test
    public void digestDeterministicAndEqualForEqualTables()
    {
        assertEquals(sample().digest(), sample().digest());
    }

    @Test
    public void digestSensitiveToKeysValuesNamesOrder()
    {
        long base = sample().digest();
        // Different value.
        assertNotEquals(base, ColumnarTable.of(
                new int[] {10, 20, 30}, new String[] {"age", "score"},
                new int[][] {{1, 2, 3}, {100, 200, 999}}).digest());
        // Different key.
        assertNotEquals(base, ColumnarTable.of(
                new int[] {10, 20, 31}, new String[] {"age", "score"},
                new int[][] {{1, 2, 3}, {100, 200, 300}}).digest());
        // Renamed column (same values) — names are part of the identity.
        assertNotEquals(base, ColumnarTable.of(
                new int[] {10, 20, 30}, new String[] {"age", "points"},
                new int[][] {{1, 2, 3}, {100, 200, 300}}).digest());
        // Reordered columns.
        assertNotEquals(base, sample().project("score", "age").digest());
    }

    @Test
    public void digestOfEmptyTablesDifferByColumns()
    {
        long a = ColumnarTable.of(new int[] {}, new String[] {"x"}, new int[][] {{}}).digest();
        long b = ColumnarTable.of(new int[] {}, new String[] {"y"}, new int[][] {{}}).digest();
        assertNotEquals(a, b);
    }
}

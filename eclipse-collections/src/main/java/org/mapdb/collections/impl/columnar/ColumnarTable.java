// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.columnar;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;
import org.mapdb.collections.impl.stream.SortedStream;

/**
 * Archeology-2 "C5" — a thin <b>columnar record adapter</b>: a "struct of
 * parallel primitive collections" view over {@code i32} data. A table is a single
 * <b>key column</b> (strictly-ascending, unique {@code i32}s — the sort/​join/​
 * filter axis) plus zero or more named <b>value columns</b>, each a parallel
 * {@code int[]} carried <b>by position</b>. It gives the family a DataFrame-shaped
 * API — {@link #filterByKeyRange}, {@link #project}, {@link #join} — <b>without</b>
 * a query engine: there is deliberately no expression language and no optimizer
 * (that is the line where scope explodes; see {@code 00-README}). Every relational
 * operation is expressed over the key column via the accepted primitives —
 * {@link Range} bracketing and the A1 sort-merge {@link SortedStream#mergeJoin} —
 * so the "the family is already columnar; just name it" thesis is realised in code.
 *
 * <h2>Immutable, position-aligned</h2>
 *
 * <p>The table is immutable: it defensively copies its inputs and every operation
 * returns a fresh table. Row {@code i} is the tuple
 * {@code (keyAt(i), column(c0)[i], column(c1)[i], …)}; the key column and all value
 * columns share the same length and index space. Because keys are unique and
 * ascending, the key column <b>is</b> an {@link ImmutableSortedSet} and each
 * {@code (key, value-column)} pair <b>is</b> an {@link ImmutableSortedMap} — the
 * adapter exposes both bridges ({@link #keyColumnAsSet} / {@link #columnAsMap}),
 * making "these are representations of the same data" concrete.
 *
 * <h2>Join</h2>
 *
 * <p>{@link #join} is an inner equi-join on the two tables' key columns, run
 * through A1's sort-merge join. Value columns are concatenated (left then right)
 * and carried by position; the two tables' value-column names must be disjoint
 * (project/​rename first if not). Since keys are unique per table the join is 1:1
 * per matched key, and the result's key column is again a strictly-ascending
 * unique subset — a well-formed table.
 */
public final class ColumnarTable
{
    private final int[] keys;               // strictly ascending, unique
    private final String[] columnNames;     // value-column names, in order
    private final int[][] columns;          // columns[c][row]; each length == keys.length

    private ColumnarTable(int[] keys, String[] columnNames, int[][] columns)
    {
        this.keys = keys;
        this.columnNames = columnNames;
        this.columns = columns;
    }

    /**
     * Build a table from a strictly-ascending unique key column and named value
     * columns (each the same length as the keys). Inputs are validated and copied.
     *
     * @param keys        strictly-ascending, unique {@code i32} key column
     * @param columnNames value-column names (unique, non-null)
     * @param columns     one {@code int[]} per name, each {@code keys.length} long
     * @throws IllegalArgumentException on a length mismatch, a duplicate/​non-ascending
     *     key, or a duplicate column name
     */
    public static ColumnarTable of(int[] keys, String[] columnNames, int[][] columns)
    {
        if (columnNames.length != columns.length)
        {
            throw new IllegalArgumentException(
                    "names/columns arity mismatch: " + columnNames.length + " vs " + columns.length);
        }
        for (int i = 1; i < keys.length; i++)
        {
            if (keys[i] <= keys[i - 1])
            {
                throw new IllegalArgumentException(
                        "key column must be strictly ascending (unique) at index " + i);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String name : columnNames)
        {
            if (name == null)
            {
                throw new IllegalArgumentException("column name must not be null");
            }
            if (!seen.add(name))
            {
                throw new IllegalArgumentException("duplicate column name: " + name);
            }
        }
        int[][] copied = new int[columns.length][];
        for (int c = 0; c < columns.length; c++)
        {
            if (columns[c].length != keys.length)
            {
                throw new IllegalArgumentException(
                        "column '" + columnNames[c] + "' length " + columns[c].length
                                + " != key column length " + keys.length);
            }
            copied[c] = columns[c].clone();
        }
        return new ColumnarTable(keys.clone(), columnNames.clone(), copied);
    }

    /** Number of rows. */
    public int rowCount()
    {
        return this.keys.length;
    }

    /** Whether the table has no rows. */
    public boolean isEmpty()
    {
        return this.keys.length == 0;
    }

    /** Number of value columns (excludes the key column). */
    public int valueColumnCount()
    {
        return this.columnNames.length;
    }

    /** Value-column names, in order. */
    public List<String> columnNames()
    {
        return new ArrayList<>(Arrays.asList(this.columnNames));
    }

    /** Whether a value column with this name exists. */
    public boolean hasColumn(String name)
    {
        return this.indexOfColumn(name) >= 0;
    }

    /** The key at the given row (0-based). */
    public int keyAt(int row)
    {
        return this.keys[row];
    }

    /** The key column as a fresh {@code int[]} (ascending). */
    public int[] keyColumn()
    {
        return this.keys.clone();
    }

    /** A named value column as a fresh {@code int[]} (row-aligned with the keys). */
    public int[] column(String name)
    {
        int c = this.requireColumn(name);
        return this.columns[c].clone();
    }

    /** The value of {@code name} at the given row. */
    public int valueAt(String name, int row)
    {
        return this.columns[this.requireColumn(name)][row];
    }

    /**
     * The 0-based row index of {@code key}, or {@code -1} if absent (binary search
     * over the ascending key column).
     */
    public int rowOfKey(int key)
    {
        int lo = 0;
        int hi = this.keys.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int k = this.keys[mid];
            if (k < key)
            {
                lo = mid + 1;
            }
            else if (k > key)
            {
                hi = mid - 1;
            }
            else
            {
                return mid;
            }
        }
        return -1;
    }

    // ---- Relational operations (all via the accepted primitives) ---------

    /**
     * Rows whose key falls in {@code range}, as a new table with the same columns.
     * The window is computed by {@link Range#bracket} over the key column (the
     * accepted bound model), and every value column is sliced by position — no
     * per-column scan.
     */
    public ColumnarTable filterByKeyRange(Range<Integer> range)
    {
        int[] b = range.bracket(this.keyList());
        int start = b[0];
        int end = b[1];
        int n = end - start;
        int[] outKeys = Arrays.copyOfRange(this.keys, start, end);
        int[][] outCols = new int[this.columns.length][];
        for (int c = 0; c < this.columns.length; c++)
        {
            outCols[c] = Arrays.copyOfRange(this.columns[c], start, end);
        }
        assert outKeys.length == n;
        return new ColumnarTable(outKeys, this.columnNames.clone(), outCols);
    }

    /**
     * A new table with the same key column but only the named value columns, in
     * the requested order. Names may repeat only if distinct requests — duplicates
     * in {@code names} are rejected (a table cannot hold two same-named columns).
     */
    public ColumnarTable project(String... names)
    {
        Set<String> seen = new LinkedHashSet<>();
        int[][] outCols = new int[names.length][];
        String[] outNames = new String[names.length];
        for (int i = 0; i < names.length; i++)
        {
            if (!seen.add(names[i]))
            {
                throw new IllegalArgumentException("duplicate projected column: " + names[i]);
            }
            int c = this.requireColumn(names[i]);
            outNames[i] = names[i];
            outCols[i] = this.columns[c].clone();
        }
        return new ColumnarTable(this.keys.clone(), outNames, outCols);
    }

    /**
     * Inner equi-join with {@code other} on the two key columns, run through A1's
     * sort-merge {@link SortedStream#mergeJoin}. The result's key column is the
     * matched keys (ascending, unique); its value columns are this table's followed
     * by {@code other}'s, carried by position. The two tables' value-column names
     * must be disjoint.
     *
     * @throws IllegalArgumentException if any value-column name is shared
     */
    public ColumnarTable join(ColumnarTable other)
    {
        for (String name : other.columnNames)
        {
            if (this.hasColumn(name))
            {
                throw new IllegalArgumentException(
                        "join column-name collision: '" + name + "' (project/rename first)");
            }
        }

        // Row-index streams ordered by their key column (indices are ascending
        // under the by-key comparator because the keys are). Join key is the
        // signed i32 in natural order — the order both key columns are sorted in.
        SortedStream<Integer> leftRows =
                SortedStream.ofSorted(indices(this.keys.length), Comparator.comparingInt(i -> this.keys[i]));
        SortedStream<Integer> rightRows =
                SortedStream.ofSorted(indices(other.keys.length), Comparator.comparingInt(i -> other.keys[i]));

        SortedStream<int[]> matched = SortedStream.mergeJoin(
                leftRows, i -> this.keys[i],
                rightRows, i -> other.keys[i],
                Comparator.<Integer>naturalOrder(),
                (li, ri) -> new int[] {li, ri},
                Comparator.comparingInt(pair -> this.keys[pair[0]]));

        List<int[]> pairs = new ArrayList<>();
        matched.forEach(pairs::add);

        int m = pairs.size();
        int lc = this.columns.length;
        int rc = other.columns.length;
        int[] outKeys = new int[m];
        int[][] outCols = new int[lc + rc][m];
        for (int r = 0; r < m; r++)
        {
            int li = pairs.get(r)[0];
            int ri = pairs.get(r)[1];
            outKeys[r] = this.keys[li];
            for (int c = 0; c < lc; c++)
            {
                outCols[c][r] = this.columns[c][li];
            }
            for (int c = 0; c < rc; c++)
            {
                outCols[lc + c][r] = other.columns[c][ri];
            }
        }
        String[] outNames = new String[lc + rc];
        System.arraycopy(this.columnNames, 0, outNames, 0, lc);
        System.arraycopy(other.columnNames, 0, outNames, lc, rc);
        return new ColumnarTable(outKeys, outNames, outCols);
    }

    // ---- Bridges to the sorted family ------------------------------------

    /** The key column as an {@link ImmutableSortedSet} (same members, same order). */
    public ImmutableSortedSet<Integer> keyColumnAsSet()
    {
        return ImmutableSortedSet.fromSorted(this.keys.clone());
    }

    /**
     * A {@code key → value} {@link ImmutableSortedMap} projecting a single value
     * column — the map <i>is</i> that one column of the table.
     */
    public ImmutableSortedMap<Integer, Integer> columnAsMap(String name)
    {
        int c = this.requireColumn(name);
        return ImmutableSortedMap.fromSorted(this.keys.clone(), this.columns[c].clone());
    }

    // ---- internals --------------------------------------------------------

    private int indexOfColumn(String name)
    {
        for (int c = 0; c < this.columnNames.length; c++)
        {
            if (this.columnNames[c].equals(name))
            {
                return c;
            }
        }
        return -1;
    }

    private int requireColumn(String name)
    {
        int c = this.indexOfColumn(name);
        if (c < 0)
        {
            throw new IllegalArgumentException("no such column: " + name);
        }
        return c;
    }

    /** A boxed, read-only {@code List<Integer>} view of the key column for {@link Range#bracket}. */
    private List<Integer> keyList()
    {
        return new AbstractList<Integer>()
        {
            @Override
            public Integer get(int index)
            {
                return ColumnarTable.this.keys[index];
            }

            @Override
            public int size()
            {
                return ColumnarTable.this.keys.length;
            }
        };
    }

    /** {@code [0, 1, …, n-1]} as a boxed list (ascending row indices). */
    private static List<Integer> indices(int n)
    {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
        {
            out.add(i);
        }
        return out;
    }
}

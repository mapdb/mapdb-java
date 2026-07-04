// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.paginate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.mapdb.collections.impl.range.BoundType;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;

/**
 * Archeology-2 "C1" — a resumable, serialisable <b>pagination cursor</b> over an
 * {@link ImmutableSortedMap}. A cursor is the value triple
 * {@code (bound, direction, after)}: a fixed query window ({@link Range}), a
 * fixed travel {@link Direction}, and the <i>exclusive resume point</i> (the last
 * key already yielded, or absent for a fresh scan). {@link #page} walks the map's
 * existing order-statistics surface ({@code rank} / {@code selectEntry} /
 * {@code higherKey} / {@code lowerKey}) to hand back one bounded batch plus the
 * next cursor, and {@link #encodeI32} / {@link #decodeI32} render the cursor as a
 * compact, language-neutral token.
 *
 * <h2>Keyset, not offset — and why it is stable "forever"</h2>
 *
 * <p>Resumption is <b>keyset</b> (a.k.a. seek): the token stores the last key
 * emitted, and the next page starts at the first key strictly beyond it
 * ({@code higherKey} ascending, {@code lowerKey} descending). It is deliberately
 * <i>not</i> offset-by-count pagination. Against the immutable snapshot the token
 * was minted from, both are exact; keyset additionally degrades gracefully if the
 * token is ever replayed against a <i>different</i> snapshot of the same logical
 * data (an insert or delete before the resume point does not silently skip or
 * repeat rows the way a raw offset would). Since the backing map is immutable, a
 * token is valid against its snapshot indefinitely — the classic
 * language-neutral "next-page token" over a frozen table.
 *
 * <h2>Exact and self-contained</h2>
 *
 * <p>No approximation: every in-window entry is returned exactly once, in
 * {@code direction} order, across successive pages. The cursor computes its
 * window {@code [start, end)} from the {@link Range} using only the map's public
 * navigation ({@code ceilingKey} / {@code higherKey} + {@code rank}, all
 * O(log n)), so it neither materialises the whole key list per page nor modifies
 * {@code ImmutableSortedMap}. Resolution of a resume key is likewise O(log n).
 *
 * <h2>Java carve-out (boxed; i32 token)</h2>
 *
 * <p>The paging engine is generic over any totally-ordered, non-null
 * {@code K extends Comparable<? super K>}, matching the boxed posture of
 * {@link ImmutableSortedMap} and {@link Range}. The <b>wire token</b>, however,
 * ships the {@code i32} ({@code Integer}-key) specialisation of the
 * cross-language validation universe: {@link #encodeI32} / {@link #decodeI32}.
 * Values are never part of the token (a cursor is a <i>position</i>, resolved
 * against the map at {@code page} time), so decoding is value-type agnostic.
 *
 * @param <K> key type (totally-ordered, non-null)
 * @param <V> value type
 */
public final class PageCursor<K extends Comparable<? super K>, V>
{
    /** Travel direction of a scan: ascending or descending key order. */
    public enum Direction
    {
        /** Smallest in-window key first. */
        ASC,
        /** Largest in-window key first. */
        DESC
    }

    private final Range<K> bound;
    private final Direction direction;
    /** Exclusive resume point (last key yielded); {@code null} == fresh scan. */
    private final K after;

    private PageCursor(Range<K> bound, Direction direction, K after)
    {
        this.bound = bound;
        this.direction = direction;
        this.after = after;
    }

    // ---- factories --------------------------------------------------------

    /** A fresh cursor over the whole map in {@code direction} order. */
    public static <K extends Comparable<? super K>, V> PageCursor<K, V> start(Direction direction)
    {
        return start(Range.<K>all(), direction);
    }

    /** A fresh cursor over the {@code bound} window in {@code direction} order. */
    public static <K extends Comparable<? super K>, V> PageCursor<K, V> start(
            Range<K> bound, Direction direction)
    {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(direction, "direction");
        return new PageCursor<>(bound, direction, null);
    }

    /**
     * A cursor positioned <i>after</i> {@code key} (exclusive) — a seek. The next
     * page begins at the first in-window key strictly beyond {@code key} in
     * {@code direction} order. {@code key} need not be present in any particular
     * map; it is resolved by {@code higherKey} / {@code lowerKey} at page time.
     */
    public static <K extends Comparable<? super K>, V> PageCursor<K, V> startAfter(
            Range<K> bound, Direction direction, K key)
    {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(key, "key");
        return new PageCursor<>(bound, direction, key);
    }

    // ---- accessors --------------------------------------------------------

    /** The fixed query window. */
    public Range<K> bound()
    {
        return this.bound;
    }

    /** The fixed travel direction. */
    public Direction direction()
    {
        return this.direction;
    }

    /**
     * The exclusive resume point — the last key already yielded — or empty for a
     * fresh scan that has not yet returned anything.
     */
    public Optional<K> after()
    {
        return Optional.ofNullable(this.after);
    }

    // ---- window resolution (public nav only; O(log n)) --------------------

    /** First absolute index of the {@code bound} window (inclusive). */
    private int windowStart(ImmutableSortedMap<K, V> map)
    {
        if (!this.bound.hasLowerBound())
        {
            return 0;
        }
        K lo = this.bound.lowerEndpoint();
        Optional<K> k = this.bound.lowerBoundType() == BoundType.CLOSED
                ? map.ceilingKey(lo)   // include lo: first key >= lo
                : map.higherKey(lo);   // exclude lo: first key >  lo
        return k.map(map::rank).orElse(map.size());
    }

    /** One-past-last absolute index of the {@code bound} window (exclusive). */
    private int windowEnd(ImmutableSortedMap<K, V> map)
    {
        if (!this.bound.hasUpperBound())
        {
            return map.size();
        }
        K hi = this.bound.upperEndpoint();
        Optional<K> k = this.bound.upperBoundType() == BoundType.CLOSED
                ? map.higherKey(hi)    // include hi: one past last <= hi is first key > hi
                : map.ceilingKey(hi);  // exclude hi: first key >= hi
        return k.map(map::rank).orElse(map.size());
    }

    /**
     * Absolute index of the first entry this cursor would emit against {@code map}
     * within {@code [start, end)}, or a value outside the window (in the ASC
     * sense: {@code >= end}; DESC sense: {@code < start}) when the scan is already
     * exhausted. Derived purely from the resume key via {@code higherKey} /
     * {@code lowerKey} + {@code rank}.
     */
    private int resumeIndex(ImmutableSortedMap<K, V> map, int start, int end)
    {
        if (this.direction == Direction.ASC)
        {
            if (this.after == null)
            {
                return start;
            }
            // first key strictly > after; clamp up into the window's low edge.
            int idx = map.higherKey(this.after).map(map::rank).orElse(map.size());
            return Math.max(idx, start);
        }
        // DESC
        if (this.after == null)
        {
            return end - 1;
        }
        // greatest key strictly < after; clamp down into the window's high edge.
        int idx = map.lowerKey(this.after).map(map::rank).orElse(-1);
        return Math.min(idx, end - 1);
    }

    // ---- paging -----------------------------------------------------------

    /**
     * The count of in-window entries this cursor has already consumed (the
     * cursor's <b>rank</b> within its window) against {@code map} — 0 for a fresh
     * scan. Computed from {@code rank} / {@code select}, so it is the exact number
     * of entries a caller has already seen before this cursor's next page.
     */
    public int consumedRank(ImmutableSortedMap<K, V> map)
    {
        Objects.requireNonNull(map, "map");
        int start = this.windowStart(map);
        int end = this.windowEnd(map);
        if (start > end)
        {
            return 0;
        }
        int resume = this.resumeIndex(map, start, end);
        if (this.direction == Direction.ASC)
        {
            return Math.min(resume, end) - start;
        }
        return (end - 1) - Math.max(resume, start - 1);
    }

    /**
     * Emit the next batch and the cursor to resume after it.
     *
     * <p>Returns up to {@code maxBatch} in-window entries in {@code direction}
     * order, starting immediately past this cursor's resume point. The returned
     * {@link Page#next()} is present iff at least one further in-window entry
     * remains — so a caller loops until {@code next()} is empty, and a full-batch
     * page that exactly exhausts the window still terminates cleanly (empty next).
     *
     * @throws IllegalArgumentException if {@code maxBatch < 1}
     */
    public Page<K, V> page(ImmutableSortedMap<K, V> map, int maxBatch)
    {
        Objects.requireNonNull(map, "map");
        if (maxBatch < 1)
        {
            throw new IllegalArgumentException("maxBatch must be >= 1 (was " + maxBatch + ")");
        }
        int start = this.windowStart(map);
        int end = this.windowEnd(map);
        if (start > end)
        {
            start = end; // disjoint / empty window
        }
        int resume = this.resumeIndex(map, start, end);

        List<Map.Entry<K, V>> entries = new ArrayList<>(Math.min(maxBatch, Math.max(0, end - start)));
        int last = -1;
        if (this.direction == Direction.ASC)
        {
            for (int i = Math.max(resume, start); i < end && entries.size() < maxBatch; i++)
            {
                entries.add(map.selectEntry(i).orElseThrow());
                last = i;
            }
            boolean more = last >= 0 && (last + 1) < end;
            return new Page<>(entries, more ? this.advanced(entries) : null);
        }
        // DESC
        for (int i = Math.min(resume, end - 1); i >= start && entries.size() < maxBatch; i--)
        {
            entries.add(map.selectEntry(i).orElseThrow());
            last = i;
        }
        boolean more = last >= 0 && (last - 1) >= start;
        return new Page<>(entries, more ? this.advanced(entries) : null);
    }

    /** A cursor whose resume point is the last key of the just-emitted batch. */
    private PageCursor<K, V> advanced(List<Map.Entry<K, V>> emitted)
    {
        K lastKey = emitted.get(emitted.size() - 1).getKey();
        return new PageCursor<>(this.bound, this.direction, lastKey);
    }

    // ---- i32 wire token ---------------------------------------------------
    //
    // Grammar (deterministic, ASCII, '|'-delimited — '|' never occurs in a
    // decimal i32, whose only non-digit is a leading '-'):
    //
    //   token  := "mdbpc1" '|' dir '|' lower '|' upper '|' after
    //   dir    := 'a' | 'd'
    //   lower  := '*'                       ; unbounded below
    //           | 'c' int                   ; closed  [v
    //           | 'o' int                   ; open    (v
    //   upper  := '*'                       ; unbounded above
    //           | 'c' int                   ; closed  v]
    //           | 'o' int                   ; open    v)
    //   after  := '*'                       ; fresh (no resume point)
    //           | int                       ; last key yielded (exclusive)

    private static final String TOKEN_PREFIX = "mdbpc1";

    /**
     * Encode an {@code i32}-keyed cursor as a compact, language-neutral token.
     * The token carries {@code (bound, direction, after)} and round-trips through
     * {@link #decodeI32}; it never carries values.
     */
    public static String encodeI32(PageCursor<Integer, ?> cursor)
    {
        Objects.requireNonNull(cursor, "cursor");
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
        sb.append('|').append(cursor.direction == Direction.ASC ? 'a' : 'd');
        sb.append('|').append(encodeLower(cursor.bound));
        sb.append('|').append(encodeUpper(cursor.bound));
        sb.append('|').append(cursor.after == null ? "*" : Integer.toString(cursor.after));
        return sb.toString();
    }

    private static String encodeLower(Range<Integer> bound)
    {
        if (!bound.hasLowerBound())
        {
            return "*";
        }
        char t = bound.lowerBoundType() == BoundType.CLOSED ? 'c' : 'o';
        return t + Integer.toString(bound.lowerEndpoint());
    }

    private static String encodeUpper(Range<Integer> bound)
    {
        if (!bound.hasUpperBound())
        {
            return "*";
        }
        char t = bound.upperBoundType() == BoundType.CLOSED ? 'c' : 'o';
        return t + Integer.toString(bound.upperEndpoint());
    }

    /**
     * Decode a token produced by {@link #encodeI32}. The value type is
     * unconstrained (a cursor is a position); the caller binds {@code V} at the
     * use site.
     *
     * @throws IllegalArgumentException if {@code token} is malformed or not a
     *         {@code mdbpc1} token
     */
    public static <V> PageCursor<Integer, V> decodeI32(String token)
    {
        Objects.requireNonNull(token, "token");
        String[] f = token.split("\\|", -1);
        if (f.length != 5 || !TOKEN_PREFIX.equals(f[0]))
        {
            throw new IllegalArgumentException("not a mdbpc1 pagination token: " + token);
        }
        Direction dir = parseDirection(f[1], token);
        Range<Integer> bound = parseBound(f[2], f[3], token);
        Integer after = "*".equals(f[4]) ? null : parseInt(f[4], "after", token);
        return new PageCursor<>(bound, dir, after);
    }

    private static Direction parseDirection(String s, String token)
    {
        if ("a".equals(s))
        {
            return Direction.ASC;
        }
        if ("d".equals(s))
        {
            return Direction.DESC;
        }
        throw new IllegalArgumentException("bad direction '" + s + "' in token: " + token);
    }

    private static Range<Integer> parseBound(String lo, String hi, String token)
    {
        boolean loUnb = "*".equals(lo);
        boolean hiUnb = "*".equals(hi);
        BoundType loT = loUnb ? null : parseBoundType(lo, "lower", token);
        BoundType hiT = hiUnb ? null : parseBoundType(hi, "upper", token);
        Integer loV = loUnb ? null : parseInt(lo.substring(1), "lower endpoint", token);
        Integer hiV = hiUnb ? null : parseInt(hi.substring(1), "upper endpoint", token);

        if (loUnb && hiUnb)
        {
            return Range.all();
        }
        if (loUnb)
        {
            return hiT == BoundType.CLOSED ? Range.atMost(hiV) : Range.lessThan(hiV);
        }
        if (hiUnb)
        {
            return loT == BoundType.CLOSED ? Range.atLeast(loV) : Range.greaterThan(loV);
        }
        if (loT == BoundType.CLOSED)
        {
            return hiT == BoundType.CLOSED ? Range.closed(loV, hiV) : Range.closedOpen(loV, hiV);
        }
        return hiT == BoundType.CLOSED ? Range.openClosed(loV, hiV) : Range.open(loV, hiV);
    }

    private static BoundType parseBoundType(String field, String which, String token)
    {
        if (field.isEmpty())
        {
            throw new IllegalArgumentException("empty " + which + " bound in token: " + token);
        }
        char c = field.charAt(0);
        if (c == 'c')
        {
            return BoundType.CLOSED;
        }
        if (c == 'o')
        {
            return BoundType.OPEN;
        }
        throw new IllegalArgumentException("bad " + which + " bound '" + field + "' in token: " + token);
    }

    private static int parseInt(String s, String which, String token)
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("bad " + which + " '" + s + "' in token: " + token, e);
        }
    }

    // ---- equality / hash / toString ---------------------------------------

    /** Structural equality on {@code (bound, direction, after)}. */
    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof PageCursor<?, ?>))
        {
            return false;
        }
        PageCursor<?, ?> other = (PageCursor<?, ?>) o;
        return this.direction == other.direction
                && this.bound.equals(other.bound)
                && Objects.equals(this.after, other.after);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(this.bound, this.direction, this.after);
    }

    @Override
    public String toString()
    {
        return "PageCursor{bound=" + this.bound + ", direction=" + this.direction
                + ", after=" + (this.after == null ? "<start>" : this.after) + '}';
    }

    /**
     * One page of a paginated scan: the batch of entries (in cursor direction
     * order) plus the cursor to resume after them. {@link #next()} is empty when
     * the scan is exhausted.
     *
     * @param <K> key type
     * @param <V> value type
     */
    public static final class Page<K extends Comparable<? super K>, V>
    {
        private final List<Map.Entry<K, V>> entries;
        private final PageCursor<K, V> next;

        private Page(List<Map.Entry<K, V>> entries, PageCursor<K, V> next)
        {
            this.entries = entries;
            this.next = next;
        }

        /** The batch of entries, in the cursor's travel direction. */
        public List<Map.Entry<K, V>> entries()
        {
            return this.entries;
        }

        /** The cursor to resume after this page, or empty when the scan is exhausted. */
        public Optional<PageCursor<K, V>> next()
        {
            return Optional.ofNullable(this.next);
        }

        /** Whether a further page remains. */
        public boolean hasNext()
        {
            return this.next != null;
        }

        @Override
        public String toString()
        {
            return "Page{entries=" + this.entries.size() + ", hasNext=" + this.hasNext() + '}';
        }
    }
}

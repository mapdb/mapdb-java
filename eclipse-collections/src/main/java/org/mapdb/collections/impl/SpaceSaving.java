// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Space-Saving — a bounded heavy-hitters / top-k summary tracking at most
 * {@code m} monitored {@code (item, count, error)} triples with a deterministic
 * eviction rule (spec {@code features/count-min.md}). Unlike the Count-Min
 * Sketch, Space-Saving is <b>order-DEPENDENT</b> (eviction depends on which item
 * is the current min when the set is full, which depends on add order). For an
 * identical capacity {@code m} and an identical add-sequence <b>in the same
 * order</b>, the monitored set in canonical order is bit-identical across all
 * five ports. <b>No floating point</b> appears in any asserted value.
 *
 * <p><b>Java carve-out (boxed element edge, EXACT counters — no relaxation).</b>
 * The monitored set is keyed by a boxed {@link Integer} item (per
 * {@code style/java.md}), but counts/errors are <b>unsigned 64-bit</b> carried
 * in a Java {@code long} (bits unsigned). Saturating add uses
 * {@link Long#compareUnsigned}; the eviction min uses {@link Long#compareUnsigned}
 * on the count then a signed {@code Integer} compare on the item; the projection
 * emits {@link Long#toUnsignedString}. The constant {@code -1L} is the bit
 * pattern of {@code u64::MAX = 18446744073709551615}. <b>No precision
 * relaxation.</b>
 *
 * <p>Pinned rulings:
 * <ul>
 *   <li><b>Eviction tie-break:</b> the victim minimizes
 *       {@code (count, signed-i32 item)} — smallest count (unsigned), then
 *       smallest <b>signed</b> i32 item ({@code INT_MIN} &lt; … &lt; -1 &lt; 0
 *       &lt; 1). {@code error} is NOT part of the tie-break.</li>
 *   <li><b>Error accounting:</b> a displaced new item gets
 *       {@code count = evicted_count + count} (saturating),
 *       {@code error = evicted_count}; an already-monitored item's {@code error}
 *       NEVER changes; a freshly-admitted (room) item has {@code error = 0}.</li>
 *   <li><b>Saturating add</b> at {@code u64::MAX} (does NOT wrap).</li>
 *   <li><b>Canonical order:</b> {@code count} DESCENDING (unsigned), then signed
 *       {@code item} ASCENDING ({@code error} rides along but never decides
 *       order). {@code topK(k)} is the first {@code k} of this order;
 *       {@code topK(size())} == {@code monitoredSet()}.</li>
 *   <li><b>{@code count = 0} add is a no-op</b> (no admit, increment, or
 *       eviction).</li>
 * </ul>
 */
public final class SpaceSaving
{
    /** {@code u64::MAX} as a {@code long} bit pattern (saturating ceiling). */
    private static final long U64_MAX = -1L;

    private final int capacity;

    /** item -&gt; {@code [count, error]} (both unsigned 64-bit in {@code long}). */
    private final Map<Integer, long[]> monitored;

    private SpaceSaving(int capacity)
    {
        this.capacity = capacity;
        this.monitored = new HashMap<>();
    }

    /**
     * Construct an empty summary monitoring at most {@code m} items.
     *
     * <p>{@code m == 0} is invalid (a zero-capacity summary can monitor nothing;
     * every {@code add} would have to evict from an empty set) and traps.
     *
     * @throws IllegalArgumentException if {@code m <= 0}
     */
    public static SpaceSaving withCapacity(int m)
    {
        if (m == 0)
        {
            throw new IllegalArgumentException("SpaceSaving capacity m must be non-zero");
        }
        if (m < 0)
        {
            throw new IllegalArgumentException("SpaceSaving capacity m must be non-negative");
        }
        return new SpaceSaving(m);
    }

    /** Returns {@code min(a + b, u64::MAX)} without wrapping (unsigned). */
    private static long saturatingAdd(long a, long b)
    {
        if (Long.compareUnsigned(a, U64_MAX - b) > 0)
        {
            return U64_MAX;
        }
        return a + b;
    }

    /**
     * Add {@code item} with weight {@code count} (unsigned 64-bit bits).
     *
     * <ul>
     *   <li>{@code count = 0} is a no-op (no admit, increment, or eviction).</li>
     *   <li>If {@code item} is already monitored: its {@code count} grows
     *       (saturating); its {@code error} is unchanged.</li>
     *   <li>If there is room ({@code size < m}): admit with {@code error = 0}.</li>
     *   <li>If full: evict the {@code (count, signed item)}-min victim; the new
     *       item takes {@code count = evicted_count + count} (saturating) and
     *       {@code error = evicted_count}.</li>
     * </ul>
     */
    public void add(int item, long count)
    {
        if (count == 0L)
        {
            return; // zero-weight add changes nothing.
        }
        long[] e = this.monitored.get(item);
        if (e != null)
        {
            e[0] = saturatingAdd(e[0], count);
            // error (e[1]) unchanged for an already-monitored item.
            return;
        }
        if (this.monitored.size() < this.capacity)
        {
            this.monitored.put(item, new long[] {count, 0L});
            return;
        }
        // Full + unmonitored item: evict the (count, signed item)-min victim.
        int victim = argminVictim();
        long evictedCount = this.monitored.remove(victim)[0];
        this.monitored.put(item, new long[] {saturatingAdd(evictedCount, count), evictedCount});
    }

    /** Convenience for {@code add(item, 1)}. */
    public void addOne(int item)
    {
        add(item, 1L);
    }

    /**
     * The monitored item minimizing {@code (count, signed item)}: smallest count
     * (unsigned), then smallest signed i32 item on a count tie. Items are
     * distinct, so the victim is unique. Caller guarantees the set is non-empty.
     */
    private int argminVictim()
    {
        int victim = 0;
        long victimCount = 0L;
        boolean first = true;
        for (Map.Entry<Integer, long[]> en : this.monitored.entrySet())
        {
            int item = en.getKey();
            long c = en.getValue()[0];
            if (first)
            {
                victim = item;
                victimCount = c;
                first = false;
                continue;
            }
            int cmp = Long.compareUnsigned(c, victimCount);
            if (cmp < 0 || (cmp == 0 && item < victim))
            {
                victim = item;
                victimCount = c;
            }
        }
        return victim;
    }

    /** The monitored {@code count} for {@code item}, or {@code 0} if not monitored. */
    public long count(int item)
    {
        long[] e = this.monitored.get(item);
        return e == null ? 0L : e[0];
    }

    /** The monitored {@code error} for {@code item}, or {@code 0} if not monitored. */
    public long error(int item)
    {
        long[] e = this.monitored.get(item);
        return e == null ? 0L : e[1];
    }

    /** Whether {@code item} is currently monitored. */
    public boolean isMonitored(int item)
    {
        return this.monitored.containsKey(item);
    }

    /** The number of currently monitored items ({@code <= m}). */
    public int size()
    {
        return this.monitored.size();
    }

    /** The capacity {@code m}. */
    public int capacity()
    {
        return this.capacity;
    }

    /**
     * The entire monitored set as {@code (item, count, error)} triples in
     * canonical order: {@code count} DESCENDING (unsigned), then signed
     * {@code item} ASCENDING.
     */
    public List<SSEntry> monitoredSet()
    {
        List<SSEntry> out = new ArrayList<>(this.monitored.size());
        for (Map.Entry<Integer, long[]> en : this.monitored.entrySet())
        {
            long[] v = en.getValue();
            out.add(new SSEntry(en.getKey(), v[0], v[1]));
        }
        // count DESC (unsigned), then signed item ASC.
        out.sort((a, b) ->
        {
            int cmp = Long.compareUnsigned(b.count, a.count);
            if (cmp != 0)
            {
                return cmp;
            }
            return Integer.compare(a.item, b.item);
        });
        return out;
    }

    /**
     * The {@code k} highest-{@code count} monitored items in canonical order (the
     * first {@code k} of {@link #monitoredSet}). {@code k > size()} returns all
     * monitored items (no padding); {@code k = 0} returns the empty list.
     *
     * @throws IllegalArgumentException if {@code k < 0}
     */
    public List<SSEntry> topK(int k)
    {
        if (k < 0)
        {
            throw new IllegalArgumentException("SpaceSaving.topK requires k >= 0, got " + k);
        }
        List<SSEntry> all = monitoredSet();
        if (k >= all.size())
        {
            return all;
        }
        return new ArrayList<>(all.subList(0, k));
    }

    /**
     * A monitored {@code (item, count, error)} triple. {@code count}/{@code error}
     * are unsigned 64-bit bit patterns carried in {@code long}.
     */
    public static final class SSEntry
    {
        /** The signed i32 item. */
        public final int item;
        /** The monitored count (unsigned 64-bit bits). */
        public final long count;
        /** The monitored error (unsigned 64-bit bits). */
        public final long error;

        public SSEntry(int item, long count, long error)
        {
            this.item = item;
            this.count = count;
            this.error = error;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof SSEntry))
            {
                return false;
            }
            SSEntry e = (SSEntry) o;
            return this.item == e.item && this.count == e.count && this.error == e.error;
        }

        @Override
        public int hashCode()
        {
            int h = item;
            h = 31 * h + Long.hashCode(count);
            h = 31 * h + Long.hashCode(error);
            return h;
        }

        @Override
        public String toString()
        {
            return "[" + item + "," + Long.toUnsignedString(count)
                    + "," + Long.toUnsignedString(error) + "]";
        }
    }
}

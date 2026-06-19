// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.bounded;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded LRU map (max-size v1) — spec {@code features/bounded-lru.md}.
 *
 * <p>A fixed-capacity {@code BoundedLruMap<K, V>} that evicts its
 * <b>least-recently-used</b> entry when a new-key insert would exceed
 * {@code maxSize}. The recency order is a single global LRU order from
 * least-recently-used (the eviction victim) to most-recently-used. Eviction is
 * <b>inline</b> (synchronous on the triggering {@code put}), <b>lazy</b>
 * (triggered only by a new-key insert exceeding capacity, or by
 * {@link #expireEntries(long)}), and <b>deterministic</b> (the victim is exactly
 * the minimum-{@code lastUse} entry; the model is tie-free by construction).
 *
 * <h2>Boxed bounded-LRU carve-out (mapdb-java)</h2>
 *
 * <p>mapdb-java ships no primitive bounded-LRU type and Eclipse Collections has
 * no {@code maximumSize} map, so this is a <b>wrapper-layer boxed type</b> over a
 * {@code LinkedHashMap<K, Node<K>>} in <b>access-order mode</b>
 * ({@code new LinkedHashMap<>(16, 0.75f, true)}). The carve-out, relative to the
 * native ({@code i32}/{@code i32}) ports, is purely representational — the
 * <b>observable behavior is identical</b>:
 *
 * <ul>
 *   <li><b>Boxed keys/values.</b> Keys and values are {@code Object} (the suite
 *       exercises {@code Integer}/{@code Integer}); the native ports use
 *       {@code i32}/{@code i32}. Identity and ordering are the boxed
 *       {@code equals}/{@code hashCode}.</li>
 *   <li><b>Absence is uniform {@link Optional}.</b> {@link #get}/{@link #put}/
 *       {@link #remove} return {@code Optional<V>} (the EC-fork absence shape),
 *       matching the native ports' {@code Option}/comma-ok/{@code undefined}/
 *       {@code ?V}.</li>
 *   <li><b>Access-order is used ONLY for the recency ordering</b> (which entry is
 *       eldest = LRU), never for the eviction trigger. {@code SIZE} eviction is an
 *       explicit <b>pre-insertion</b> step (evict the eldest, fire the callback,
 *       <i>then</i> insert) — we do <b>NOT</b> use {@code removeEldestEntry}, whose
 *       JDK insert-then-evict timing violates the spec's evict-before-insert
 *       contract (and for {@code n == 0} would momentarily store and self-evict
 *       the new key).</li>
 *   <li><b>Unsigned logical-time ticks in a {@code long}.</b> {@code now} / {@code ttl}
 *       / {@code expireAt} are unsigned 64-bit logical ticks carried in a signed
 *       Java {@code long}; all comparisons use {@link Long#compareUnsigned}.
 *       {@code expireAt = saturatingUnsignedAdd(now, ttl)} saturates at
 *       {@code 0xFFFF…FFFF} (the {@code "never"} sentinel) instead of wrapping.
 *       This is the same unsigned-in-signed discipline the hash pipeline uses.</li>
 * </ul>
 *
 * <h2>Logical time / expiry (no wall clock)</h2>
 *
 * <p>There is <b>no real clock</b>. {@code ttl} is an after-write TTL; every write
 * sets {@code expireAt = saturatingUnsignedAdd(now, ttl)}. {@link #expireEntries}
 * removes every entry with {@code expireAt <= now} (unsigned, <b>inclusive</b>),
 * firing the callback with cause {@link EvictionCause#EXPIRED} in ascending
 * {@code expireAt}, then ascending {@code lastUse} (LRU) order. There is <b>no
 * implicit expiry</b>: a {@code put}/{@code get} never opportunistically expires.
 * Plain {@link #put(Object, Object)} is defined as {@code putAt(k, v, 0)}.
 *
 * <p>Iteration ({@link #keys}/{@link #values}/{@link #entries}) is a read-only,
 * point-in-time snapshot in <b>LRU order</b> (least-recently-used first); it
 * never refreshes recency and never evicts.
 *
 * <p><b>Recency-refresh set is exactly</b> {@code {put (insert or update),
 * get-hit, getOrDefault-hit}} — nothing else. {@code containsKey}, iteration,
 * misses, {@code remove}, {@code size}/{@code isEmpty}, {@code expireEntries} do
 * not refresh recency. The callback fires <b>only</b> for {@code SIZE} and
 * {@code EXPIRED}, once per evicted entry, synchronously, with the value at the
 * moment of eviction; never on {@code put}-update / {@code remove} / {@code clear}
 * / any read.
 *
 * <p>v1 is single-threaded / single-writer (not thread-safe). Mutating the map
 * from inside the eviction callback is undefined behavior.
 *
 * <p><b>Null keys/values are unsupported.</b> The cross-language suite only
 * exercises non-null boxed {@code Integer} keys/values. {@code null} values are
 * not distinguishable from absence through the {@link Optional} return shape
 * (a present {@code null} value would read back as {@link Optional#empty()}), and
 * {@link #entries()} rejects {@code null} via {@link Map#entry}; callers MUST NOT
 * store {@code null} keys or values.
 *
 * @param <K> the (boxed) key type
 * @param <V> the (boxed) value type
 */
public final class BoundedLruMap<K, V>
{
    /** Unsigned 64-bit "never expires" sentinel ({@code 0xFFFF…FFFF}). */
    private static final long NEVER = -1L;

    /** One LRU node: the value plus its logical expiry tick. */
    private static final class Node<V>
    {
        V value;
        /** Unsigned logical expiry tick; {@link #NEVER} means never expires. */
        long expireAt;

        Node(V value, long expireAt)
        {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    /**
     * The eviction callback: invoked with {@code (key, value-at-eviction, cause)}
     * for causes {@link EvictionCause#SIZE} and {@link EvictionCause#EXPIRED} only.
     */
    @FunctionalInterface
    public interface EvictionListener<K, V>
    {
        void onEvict(K key, V value, EvictionCause cause);
    }

    /**
     * Key -&gt; node, in <b>access order</b>: iteration runs eldest (LRU) first,
     * youngest (MRU) last. {@code get}/{@code put} on this map count as accesses
     * (matching recency rules 1+2); {@code containsKey} does NOT (matching our
     * non-refresh rule). Used <b>only</b> for the recency ordering — never for the
     * eviction trigger (see class doc).
     */
    private final LinkedHashMap<K, Node<V>> map =
            new LinkedHashMap<>(16, 0.75f, true);

    /** Capacity {@code n} ({@code 0} ⇒ permanently empty; every insert drops). Negative inputs are clamped to {@code 0}. */
    private final int maxSize;
    /** After-write TTL in unsigned logical ticks, or {@code null} for a pure max-size map. */
    private final Long ttl;
    /** Optional recording/eviction callback, or {@code null}. */
    private final EvictionListener<K, V> onEvict;

    private BoundedLruMap(int maxSize, Long ttl, EvictionListener<K, V> onEvict)
    {
        // A negative capacity is meaningless; clamp it to 0 (drop-everything), so
        // capacity() never reports a negative value and the map stays within the
        // spec's non-negative capacity domain. Clamp-to-0 (not throw) matches the
        // Go port's cross-port convention; observable behavior is identical to n==0.
        this.maxSize = Math.max(maxSize, 0);
        this.ttl = ttl;
        this.onEvict = onEvict;
    }

    /** A pure max-size LRU map of capacity {@code n} (no TTL, no callback). */
    public static <K, V> BoundedLruMap<K, V> withMaxSize(int n)
    {
        return new Builder<K, V>().maxSize(n).build();
    }

    /** Start building a map; {@code maxSize} defaults to {@code 0} (drop-everything). */
    public static <K, V> Builder<K, V> builder()
    {
        return new Builder<>();
    }

    /**
     * Builder for {@link BoundedLruMap}. {@code maxSize} is the capacity;
     * {@code ttl} and {@code onEvict} are optional.
     */
    public static final class Builder<K, V>
    {
        private int maxSize;
        private Long ttl;
        private EvictionListener<K, V> onEvict;

        /** Set the capacity {@code n} (the maximum number of resident entries). */
        public Builder<K, V> maxSize(int n)
        {
            this.maxSize = n;
            return this;
        }

        /** Set the after-write TTL (unsigned logical ticks). {@code expireAt = saturating(now + ttl)}. */
        public Builder<K, V> ttl(long ttl)
        {
            this.ttl = ttl;
            return this;
        }

        /** Install the eviction callback (causes {@code SIZE} and {@code EXPIRED} only). */
        public Builder<K, V> onEvict(EvictionListener<K, V> cb)
        {
            this.onEvict = cb;
            return this;
        }

        /** Build the map. */
        public BoundedLruMap<K, V> build()
        {
            return new BoundedLruMap<>(maxSize, ttl, onEvict);
        }
    }

    // --- time -------------------------------------------------------------

    /** Unsigned saturating add: {@code a + b} clamped at {@code 0xFFFF…FFFF} ({@link #NEVER}). */
    private static long saturatingUnsignedAdd(long a, long b)
    {
        long sum = a + b;
        // Unsigned overflow ⇔ the sum wrapped below either operand.
        if (Long.compareUnsigned(sum, a) < 0)
        {
            return NEVER;
        }
        return sum;
    }

    private long writeExpireAt(long now)
    {
        return ttl == null ? NEVER : saturatingUnsignedAdd(now, ttl);
    }

    // --- map surface ------------------------------------------------------

    /** {@code put(k, v)} == {@code putAt(k, v, 0)} (no hidden clock). */
    public Optional<V> put(K key, V value)
    {
        return putAt(key, value, 0L);
    }

    /**
     * Insert-or-update with a logical write tick. Refreshes recency of {@code key};
     * a new-key insert at capacity evicts the LRU entry first (evict-before-insert).
     * Returns the previous value, or {@link Optional#empty()}.
     */
    public Optional<V> putAt(K key, V value, long now)
    {
        long expireAt = writeExpireAt(now);

        Node<V> existing = map.get(key); // access-order: refreshes recency of an existing key
        if (existing != null)
        {
            // Update: value replaced, expiry reset, recency refreshed; NO eviction.
            V old = existing.value;
            existing.value = value;
            existing.expireAt = expireAt;
            return Optional.ofNullable(old);
        }

        // Genuine insertion of a new key.
        if (maxSize <= 0)
        {
            // Capacity 0 (or non-positive): the entry is dropped, never resident,
            // no victim, no callback (spec "n <= 0 drop").
            return Optional.empty();
        }

        // Evict-before-insert: at most ONE SIZE eviction when maxSize >= 1
        // (one insert raises size by one). Done as an EXPLICIT pre-insertion step
        // (NOT removeEldestEntry) so the victim is gone and its callback has fired
        // before the new entry is observable.
        if (map.size() >= maxSize)
        {
            Map.Entry<K, Node<V>> eldest = map.entrySet().iterator().next(); // LRU end
            K victimKey = eldest.getKey();
            Node<V> victim = eldest.getValue();
            map.remove(victimKey);
            fireEvict(victimKey, victim.value, EvictionCause.SIZE);
        }

        map.put(key, new Node<>(value, expireAt));
        return Optional.empty();
    }

    /** Lookup. On a hit refreshes recency; on a miss does nothing. */
    public Optional<V> get(K key)
    {
        Node<V> node = map.get(key); // access-order: a hit refreshes recency
        return node == null ? Optional.empty() : Optional.ofNullable(node.value);
    }

    /**
     * A {@code get} that returns {@code defaultValue} on a miss. A hit refreshes
     * recency exactly like {@link #get}; a miss does NOT refresh recency and does
     * NOT insert {@code defaultValue}.
     */
    public V getOrDefault(K key, V defaultValue)
    {
        Node<V> node = map.get(key); // access-order: a hit refreshes recency
        return node == null ? defaultValue : node.value;
    }

    /** Membership test. Does NOT refresh recency (uses {@code containsKey}, not {@code get}) and never evicts. */
    public boolean containsKey(K key)
    {
        return map.containsKey(key); // LinkedHashMap.containsKey does NOT count as an access
    }

    /**
     * Delete {@code key}. Does not evict and does NOT invoke the eviction callback
     * (manual removal is not an eviction). Returns the removed value, or empty.
     */
    public Optional<V> remove(K key)
    {
        Node<V> node = map.remove(key);
        return node == null ? Optional.empty() : Optional.ofNullable(node.value);
    }

    /** Current entry count ({@code 0 ..= maxSize}). */
    public int size()
    {
        return map.size();
    }

    /** Whether the map is empty. */
    public boolean isEmpty()
    {
        return map.isEmpty();
    }

    /** The configured capacity {@code n}. */
    public int capacity()
    {
        return maxSize;
    }

    /**
     * Remove all entries. Does NOT invoke the eviction callback for the cleared
     * entries (bulk manual removal is not eviction).
     */
    public void clear()
    {
        map.clear();
    }

    /**
     * Logical-time expiry pass: remove every entry with {@code expireAt <= now}
     * (unsigned, inclusive), firing the callback with cause
     * {@link EvictionCause#EXPIRED} in ascending {@code expireAt}, then ascending
     * {@code lastUse} (LRU) order. Returns the count removed. The only time-driven
     * eviction; surviving entries' recency is unchanged. A no-TTL map expires
     * nothing for any {@code now}.
     */
    public int expireEntries(long now)
    {
        if (ttl == null)
        {
            return 0;
        }
        // Collect victims in LRU order (the LinkedHashMap access-order iteration is
        // ascending lastUse), then STABLE-sort by unsigned expireAt so equal-
        // expireAt entries keep that ascending-lastUse order. NEVER never expires.
        List<Map.Entry<K, Node<V>>> victims = new ArrayList<>();
        for (Map.Entry<K, Node<V>> e : map.entrySet())
        {
            long expireAt = e.getValue().expireAt;
            if (expireAt != NEVER && Long.compareUnsigned(expireAt, now) <= 0)
            {
                victims.add(e);
            }
        }
        victims.sort(Comparator.comparing(e -> e.getValue().expireAt, Long::compareUnsigned));
        for (Map.Entry<K, Node<V>> e : victims)
        {
            K key = e.getKey();
            Node<V> node = e.getValue();
            map.remove(key);
            fireEvict(key, node.value, EvictionCause.EXPIRED);
        }
        return victims.size();
    }

    private void fireEvict(K key, V value, EvictionCause cause)
    {
        if (onEvict != null)
        {
            onEvict.onEvict(key, value, cause);
        }
    }

    // --- iteration (LRU order, read-only snapshots) -----------------------

    /** All keys in LRU order (least-recently-used first). Read-only snapshot: no refresh, no evict. */
    public List<K> keys()
    {
        return new ArrayList<>(map.keySet());
    }

    /** All values in LRU order, parallel to {@link #keys}. Read-only snapshot. */
    public List<V> values()
    {
        List<V> out = new ArrayList<>(map.size());
        for (Node<V> n : map.values())
        {
            out.add(n.value);
        }
        return out;
    }

    /** All {@code [key, value]} entries in LRU order. Read-only snapshot. */
    public List<Map.Entry<K, V>> entries()
    {
        List<Map.Entry<K, V>> out = new ArrayList<>(map.size());
        for (Map.Entry<K, Node<V>> e : map.entrySet())
        {
            out.add(Map.entry(e.getKey(), e.getValue().value));
        }
        return out;
    }
}

// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/**
 * Archeology-2 "C2" — a <b>content-addressed store</b>: an in-memory map from an
 * {@code A2} collection digest (a 64-bit {@link ContentAddress}) to a value,
 * providing the three uses the {@code 00-README} names for content-addressing:
 *
 * <ul>
 *   <li><b>Dedupe</b> — {@link #intern} collapses logically-equal collections
 *       (equal digest) to a single stored instance.</li>
 *   <li><b>Memoise a pipeline stage</b> — {@link #memoize} keys a computed output
 *       by the digest of its input ("this input digest → this output"), so an
 *       unchanged input reuses the cached output.</li>
 *   <li><b>Incremental rebuild</b> — because the key is content (not identity or
 *       timestamp), re-running a stage over data that did not change is a cache
 *       hit; only changed inputs recompute, à la a build system.</li>
 * </ul>
 *
 * <p>The store is keyed by the raw 64-bit digest ({@code long}) that
 * {@code CollectionDigest.of*} returns — no boxing churn — while
 * {@link ContentAddress} supplies the portable string token when an identity must
 * cross a process or file boundary. {@link #hits} / {@link #misses} count cache
 * outcomes.
 *
 * <p><b>Determinism:</b> the backing map is a {@link HashMap}, but the store is
 * only ever queried by address (get/​put); it is never iterated for an
 * observable result, so behaviour is deterministic and port-independent. Values
 * are assumed to be genuine functions of their address (the caller's contract).
 * Not thread-safe.
 *
 * @param <V> the stored value type (e.g. a collection, or a pipeline-stage output)
 */
public final class ContentAddressedStore<V>
{
    private final Map<Long, V> byAddress = new HashMap<>();
    private long hits;
    private long misses;

    /** Whether a value is stored under this address. */
    public boolean contains(long address)
    {
        return this.byAddress.containsKey(address);
    }

    /** The value stored under this address, if any. Does not affect hit/​miss counts. */
    public Optional<V> get(long address)
    {
        return Optional.ofNullable(this.byAddress.get(address));
    }

    /** Number of distinct addresses stored. */
    public int distinctCount()
    {
        return this.byAddress.size();
    }

    /** Cache hits recorded by {@link #intern} / {@link #computeIfAbsent} / {@link #memoize}. */
    public long hits()
    {
        return this.hits;
    }

    /** Cache misses recorded by {@link #intern} / {@link #computeIfAbsent} / {@link #memoize}. */
    public long misses()
    {
        return this.misses;
    }

    /**
     * Dedupe: store {@code value} under {@code address} if absent and return it (a
     * miss); if a value is already present, return the <b>existing</b> instance
     * and discard {@code value} (a hit). Two logically-equal collections
     * interned under their equal digests therefore collapse to one instance.
     *
     * @return the canonical stored instance for this address
     */
    public V intern(long address, V value)
    {
        // Validate before the lookup so the null policy is identical on hit and
        // miss (a null candidate is always rejected, never silently deduped away).
        Objects.requireNonNull(value, "value");
        V existing = this.byAddress.get(address);
        if (existing != null)
        {
            this.hits++;
            return existing;
        }
        this.byAddress.put(address, value);
        this.misses++;
        return value;
    }

    /**
     * Memoise by a pre-computed address: return the cached value if present (a
     * hit), else run {@code compute} on the address, store, and return it (a miss).
     * {@code compute} must not return {@code null}.
     */
    public V computeIfAbsent(long address, LongFunction<? extends V> compute)
    {
        V existing = this.byAddress.get(address);
        if (existing != null)
        {
            this.hits++;
            return existing;
        }
        // Compute (and null-check) before recording the miss, so a failed compute
        // leaves the counters and the cache untouched.
        V produced = Objects.requireNonNull(compute.apply(address), "compute result");
        this.byAddress.put(address, produced);
        this.misses++;
        return produced;
    }

    /**
     * Full pipeline-stage memoisation: digest {@code input} with {@code digestFn},
     * then return the cached output for that digest or compute it once. Re-invoking
     * with an input of equal content (equal digest) is a hit — the essence of
     * content-addressed incremental rebuild.
     *
     * @param input    the stage input
     * @param digestFn maps the input to its {@code A2} content digest
     *     (e.g. {@code CollectionDigest::ofSortedMap})
     * @param compute  the pure stage, run only on a miss
     * @param <I>      the input type
     */
    public <I> V memoize(I input, ToLongFunction<? super I> digestFn, Function<? super I, ? extends V> compute)
    {
        long address = digestFn.applyAsLong(input);
        V existing = this.byAddress.get(address);
        if (existing != null)
        {
            this.hits++;
            return existing;
        }
        // Compute (and null-check) before recording the miss, so a failed compute
        // leaves the counters and the cache untouched.
        V produced = Objects.requireNonNull(compute.apply(input), "compute result");
        this.byAddress.put(address, produced);
        this.misses++;
        return produced;
    }
}

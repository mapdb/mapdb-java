// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.bounded;

/**
 * Why an entry left a {@link BoundedLruMap} — the eviction-callback cause.
 *
 * <p>Only {@link #SIZE} and {@link #EXPIRED} exist in v1 (spec
 * {@code features/bounded-lru.md} §"Cause enumeration"). A {@code put} that
 * updates an existing key, a {@code remove}, and a {@code clear} are <b>not</b>
 * evictions and never invoke the callback; the Caffeine causes {@code REPLACED},
 * {@code EXPLICIT}, {@code COLLECTED} are deliberately absent so a port mirroring
 * {@code RemovalCause} does not fire the callback on those paths.
 */
public enum EvictionCause
{
    /** Evicted because a new-key insert exceeded {@code maximumSize} (the LRU victim). */
    SIZE,
    /** Removed by {@code expireEntries(now)} because its logical expiry tick had passed. */
    EXPIRED;

    /** The lower-case serialized name used by the cross-language suite ({@code "size"}/{@code "expired"}). */
    public String serializedName()
    {
        return this == SIZE ? "size" : "expired";
    }
}

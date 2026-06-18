// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.range;

/**
 * The kind of a finite endpoint of a {@link Range}: {@link #OPEN} (exclusive) or
 * {@link #CLOSED} (inclusive). Mirrors Google Guava's {@code BoundType} and the
 * other mapdb ports' {@code BoundType} (Rust/TS/Zig {@code Open}/{@code Closed},
 * Go {@code BoundOpen}/{@code BoundClosed}).
 */
public enum BoundType
{
    /** An exclusive endpoint. */
    OPEN,
    /** An inclusive endpoint. */
    CLOSED
}

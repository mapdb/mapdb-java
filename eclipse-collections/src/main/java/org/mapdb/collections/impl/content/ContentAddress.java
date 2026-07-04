// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.content;

/**
 * Archeology-2 "C2" — a <b>content address</b>: an {@code A2} collection digest
 * (see {@code org.mapdb.collections.impl.digest.CollectionDigest}) reinterpreted
 * as a stable, language-neutral <b>identity</b> for a collection. Two logically
 * equal collections have equal addresses; the address is the key under which a
 * content-addressed store dedupes them and memoises pipeline stages ("this input
 * digest → this output").
 *
 * <p>The address wraps the 64-bit digest and renders it as a compact ASCII
 * <b>token</b> {@code mdbca1|<16-hex>} — the {@code i32} bit pattern of the
 * {@code u64} digest, big-endian, zero-padded to 16 hex chars. The token grammar
 * mirrors the C1 pagination token ({@code mdbpc1|…}) and is a forever cross-port
 * contract: any port must emit and parse these exact bytes.
 */
public final class ContentAddress
{
    /** Token prefix + separator; a version tag so the grammar can evolve. */
    private static final String PREFIX = "mdbca1|";

    private final long value;

    private ContentAddress(long value)
    {
        this.value = value;
    }

    /** Wrap a raw 64-bit digest (as returned by {@code CollectionDigest.of*}). */
    public static ContentAddress of(long digest)
    {
        return new ContentAddress(digest);
    }

    /** The raw 64-bit digest bit pattern. */
    public long value()
    {
        return this.value;
    }

    /** The portable ASCII token {@code mdbca1|<16-hex>}. */
    public String token()
    {
        // Fixed 16 lowercase hex chars, zero-padded — byte-stable across ports.
        String hex = Long.toHexString(this.value);
        StringBuilder sb = new StringBuilder(PREFIX.length() + 16);
        sb.append(PREFIX);
        for (int i = hex.length(); i < 16; i++)
        {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }

    /**
     * Parse a {@code mdbca1|<16-hex>} token back into an address.
     *
     * @throws IllegalArgumentException if the prefix is wrong or the payload is
     *     not exactly 16 hex digits
     */
    public static ContentAddress parse(String token)
    {
        if (token == null || !token.startsWith(PREFIX))
        {
            throw new IllegalArgumentException("not a content-address token: " + token);
        }
        String hex = token.substring(PREFIX.length());
        if (hex.length() != 16)
        {
            throw new IllegalArgumentException("content-address payload must be 16 hex chars: " + token);
        }
        for (int i = 0; i < hex.length(); i++)
        {
            char ch = hex.charAt(i);
            boolean isHex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!isHex)
            {
                throw new IllegalArgumentException("non-hex (or non-lowercase) char in token: " + token);
            }
        }
        // parseUnsignedLong handles the high-bit-set (>= 0x8000...) case a signed
        // parse would reject.
        return new ContentAddress(Long.parseUnsignedLong(hex, 16));
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ContentAddress && ((ContentAddress) o).value == this.value;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(this.value);
    }

    @Override
    public String toString()
    {
        return this.token();
    }
}

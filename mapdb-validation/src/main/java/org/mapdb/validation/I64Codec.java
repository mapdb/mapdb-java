// Copyright (c) 2026 Jan Kotek.
// Internal validation tooling for the Eclipse Collections fork (mapdb-java).
package org.mapdb.validation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wide-integer (i64) operand encoding, implementing
 * cross-language-validation/README.md §"Wide-integer (i64) operand encoding".
 *
 * <p>An i64 key is a decimal string (small keys may also be bare JSON numbers);
 * it is parsed straight to a Java {@code long} with {@link Long#parseLong},
 * never narrowed through a double. The full signed range is supported,
 * including negatives.
 */
final class I64Codec {

    private I64Codec() {
    }

    /** Parse an i64 operand from a JSON node (decimal string or bare number). */
    static long parseOperand(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("null i64 operand");
        }
        if (node.isTextual()) {
            return Long.parseLong(node.asText());
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            // A floating JSON number for an i64 key would already have lost
            // precision above 2^53; reject rather than silently narrow.
            throw new IllegalArgumentException("i64 key must be a decimal string or integer, got " + node);
        }
        throw new IllegalArgumentException("expected i64 key, got " + node);
    }

    /** Parse an i64 from an assertion-key suffix (decimal, may be negative). */
    static long parseSuffix(String s) {
        return Long.parseLong(s);
    }
}

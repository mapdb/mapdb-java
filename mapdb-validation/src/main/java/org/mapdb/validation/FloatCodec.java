// Copyright (c) 2026 Jan Kotek.
// Derived-context module for the Eclipse Collections fork (mapdb-java).
// Internal validation tooling — not published.
package org.mapdb.validation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * f32 operand encoding + canonical f32 serialization, implementing
 * cross-language-validation/README.md §"Float operand encoding".
 *
 * <p>A float operand is one of three forms:
 * <ul>
 *   <li>a JSON number ({@code 2.0}) — the exact value at f32 width;</li>
 *   <li>a human-label string ({@code "NaN"}, {@code "-0.0"}, {@code "Infinity"}, …)
 *       or a bare decimal string ({@code "2.0"}) or a {@code 0x..} bits string;</li>
 *   <li>a bits-escape object {@code {"bits":"0x........"}} — exactly 8 hex
 *       digits reinterpreted via {@link Float#intBitsToFloat}.</li>
 * </ul>
 *
 * <p>Canonical serialization renders NaN (any sign/payload) and {@code ±0.0}
 * as the lower-case {@code 0x}-prefixed 8-hex-digit raw bit pattern,
 * {@code ±Infinity} as labels, integral finite floats as {@code N.0}, and
 * other finite floats shortest-round-trip via {@link Float#toString}.
 */
final class FloatCodec {

    static final int CANONICAL_POS_NAN = 0x7FC00000;
    static final int CANONICAL_NEG_NAN = 0xFFC00000;

    private FloatCodec() {
    }

    /** Parse a float operand JSON node (number, string label, or bits-escape object). */
    static float parseOperand(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("null float operand");
        }
        if (node.isTextual()) {
            return parseLabel(node.asText());
        }
        if (node.isObject()) {
            JsonNode bits = node.get("bits");
            if (bits != null && bits.isTextual()) {
                return Float.intBitsToFloat(parseBits(bits.asText()));
            }
            throw new IllegalArgumentException("expected {\"bits\":\"0x..\"} float object, got " + node);
        }
        if (node.isNumber()) {
            // Read at f32 width: the operand is an f32, never a widened f64.
            return (float) node.asDouble();
        }
        throw new IllegalArgumentException("expected f32 operand, got " + node);
    }

    /**
     * Parse a human-label / decimal / hex-bits float string. Used both for
     * string operands and for assertion-key suffixes (get_-NaN, contains_0.0,
     * contains_0x7fc00001).
     */
    static float parseLabel(String s) {
        switch (s) {
            case "NaN":
            case "+NaN":
                return Float.intBitsToFloat(CANONICAL_POS_NAN);
            case "-NaN":
                return Float.intBitsToFloat(CANONICAL_NEG_NAN);
            case "Infinity":
            case "+Infinity":
                return Float.POSITIVE_INFINITY;
            case "-Infinity":
                return Float.NEGATIVE_INFINITY;
            case "0.0":
            case "+0.0":
                return 0.0f;
            case "-0.0":
                return -0.0f;
            case "pos_zero":
                return 0.0f;
            case "neg_zero":
                return -0.0f;
            default:
                if (s.startsWith("0x") || s.startsWith("0X")) {
                    return Float.intBitsToFloat(parseBits(s));
                }
                return Float.parseFloat(s);
        }
    }

    /** Parse an exact 32-bit IEEE-754 pattern from a 0x-prefixed, 8-hex-digit string. */
    static int parseBits(String hex) {
        String body;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            body = hex.substring(2);
        } else {
            throw new IllegalArgumentException("f32 bits literal must start with 0x: " + hex);
        }
        if (body.length() != 8) {
            throw new IllegalArgumentException("f32 bits literal must be 8 hex digits: " + hex);
        }
        return (int) Long.parseLong(body, 16);
    }

    /**
     * Canonical, bit-faithful serialization of an f32 value. NaN (any
     * sign/payload) and {@code ±0.0} render as their 0x-hex bit pattern;
     * {@code ±Infinity} render as labels; integral finite floats as {@code N.0};
     * other finite floats shortest-round-trip via {@link Float#toString}.
     */
    static String format(float v) {
        if (Float.isNaN(v) || v == 0.0f) {
            return String.format("0x%08x", Float.floatToRawIntBits(v));
        }
        if (v == Float.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (v == Float.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e16f) {
            // Integer-valued finite float -> "N.0" (matches Java/Go rendering).
            return ((long) v) + ".0";
        }
        // Shortest-round-trip at f32 width (Float.toString on modern JDKs).
        return Float.toString(v);
    }

    /** True iff the string is a bare NaN label used for loose-NaN scalar matching. */
    static boolean isNanLabel(String s) {
        return "NaN".equals(s) || "+NaN".equals(s) || "-NaN".equals(s);
    }
}

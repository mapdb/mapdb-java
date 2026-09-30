package org.mapdb.nativetests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapdb.collections.api.factory.Maps;
import org.mapdb.collections.api.map.ImmutableMap;
import static org.junit.jupiter.api.Assertions.*;

public class ImmutableMapSerializationNullDuplicateTest {
    private static final class ResolvingKey implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String resolved;
        ResolvingKey(String resolved) { this.resolved = resolved; }
        private Object readResolve() { return this.resolved; }
    }
    static Stream<Arguments> values() {
        return Stream.<String>of(null, "same").flatMap(key -> Stream.<String>of(null, "old")
                .flatMap(prior -> Stream.<String>of(null, "hit").map(value -> Arguments.of(key, prior, value))));
    }
    @ParameterizedTest @MethodSource("values")
    void rejectsDuplicateResolvedKeysIncludingNullValues(String key, String prior, String value) throws Exception {
        var first = new ResolvingKey(key); var second = new ResolvingKey(key);
        var source = Maps.immutable.<Object, String>with(first, prior, second, value);
        assertEquals("ImmutableDoubletonMap", source.getClass().getSimpleName());
        byte[] bytes = encode(source);
        assertThrows(IllegalStateException.class, () -> decode(bytes));
        assertEquals(2, source.size()); assertTrue(source.containsKey(first)); assertTrue(source.containsKey(second));
        assertEquals(prior, source.get(first)); assertEquals(value, source.get(second));
    }

    @ParameterizedTest @MethodSource("values")
    void retainsDistinctResolvedKeysAndNullValues(String key, String prior, String value) throws Exception {
        var first = new ResolvingKey(key); var second = new ResolvingKey("right");
        var source = Maps.immutable.<Object, String>with(first, prior, second, value);
        Object restored = decode(encode(source));
        assertInstanceOf(ImmutableMap.class, restored);
        var result = (ImmutableMap<?, ?>) restored;
        assertEquals(2, result.size()); assertTrue(result.containsKey(key)); assertTrue(result.containsKey("right"));
        assertEquals(prior, result.get(key)); assertEquals(value, result.get("right"));
        assertEquals(2, source.size()); assertEquals(prior, source.get(first)); assertEquals(value, source.get(second));
    }

    @Test void retainsOrdinaryNullableRoundTrip() throws Exception {
        var source = Maps.immutable.<String, String>with(null, null, "right", "hit");
        Object restored = decode(encode(source));
        assertInstanceOf(ImmutableMap.class, restored); assertEquals(source, restored);
        assertEquals(2, source.size()); assertTrue(source.containsKey(null)); assertNull(source.get(null));
    }
    private static byte[] encode(Object source) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(source); }
        byte[] encoded = bytes.toByteArray();
        assertTrue(new String(encoded, StandardCharsets.ISO_8859_1)
                .contains("org.mapdb.collections.impl.map.immutable.ImmutableMapSerializationProxy"),
                "actual immutable map serialization proxy must be encoded");
        return encoded;
    }
    private static Object decode(byte[] bytes) throws Exception {
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { return input.readObject(); }
    }
}

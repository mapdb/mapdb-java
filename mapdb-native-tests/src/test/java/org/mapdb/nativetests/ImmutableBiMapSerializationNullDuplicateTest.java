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
import org.mapdb.collections.api.factory.BiMaps;
import org.mapdb.collections.api.bimap.ImmutableBiMap;
import static org.junit.jupiter.api.Assertions.*;

public class ImmutableBiMapSerializationNullDuplicateTest {
    private static final class ResolvingKey implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String resolved;
        private final int hash;
        ResolvingKey(String resolved, int hash) { this.resolved = resolved; this.hash = hash; }
        @Override public int hashCode() { return this.hash; }
        private Object readResolve() { return this.resolved; }
    }
    static Stream<Arguments> values() {
        return Stream.<String>of(null, "same").flatMap(key -> Stream.<String>of(null, "old")
                .flatMap(prior -> Stream.of("hit").map(value -> Arguments.of(key, prior, value))));
    }
    @ParameterizedTest @MethodSource("values")
    void rejectsDuplicateResolvedKeysIncludingNullValues(String key, String prior, String value) throws Exception {
        var first = new ResolvingKey(key, 0); var second = new ResolvingKey(key, 1);
        var source = BiMaps.immutable.<Object, String>with(first, prior, second, value);
        assertSame(first, source.keysView().getFirst(), "null-valued first entry must be encoded first");
        byte[] bytes = encode(source);
        assertThrows(IllegalStateException.class, () -> decode(bytes));
        assertEquals(2, source.size()); assertTrue(source.containsKey(first)); assertTrue(source.containsKey(second));
        assertEquals(prior, source.get(first)); assertEquals(value, source.get(second));
    }

    @ParameterizedTest @MethodSource("values")
    void retainsDistinctResolvedKeysAndNullValues(String key, String prior, String value) throws Exception {
        var first = new ResolvingKey(key, 0); var second = new ResolvingKey("right", 1);
        var source = BiMaps.immutable.<Object, String>with(first, prior, second, value);
        Object restored = decode(encode(source));
        assertInstanceOf(ImmutableBiMap.class, restored);
        var result = (ImmutableBiMap<?, ?>) restored;
        assertEquals(2, result.size()); assertTrue(result.containsKey(key)); assertTrue(result.containsKey("right"));
        assertEquals(prior, result.get(key)); assertEquals(value, result.get("right"));
        assertTrue(result.inverse().containsKey(prior)); assertEquals(key, result.inverse().get(prior));
        assertEquals("right", result.inverse().get(value));
        assertEquals(2, source.size()); assertEquals(prior, source.get(first)); assertEquals(value, source.get(second));
    }

    @Test void retainsOrdinaryNullableRoundTrip() throws Exception {
        var source = BiMaps.immutable.<String, String>with(null, null, "right", "hit");
        Object restored = decode(encode(source));
        assertInstanceOf(ImmutableBiMap.class, restored); assertEquals(source, restored);
        var result = (ImmutableBiMap<?, ?>) restored;
        assertTrue(result.inverse().containsKey(null)); assertNull(result.inverse().get(null));
        assertEquals("right", result.inverse().get("hit"));
        assertEquals(2, source.size()); assertTrue(source.containsKey(null)); assertNull(source.get(null));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings={"same"})
    void retainsExistingResolvedValueCollisionRejection(String value) throws Exception {
        var source = BiMaps.immutable.<String, Object>with("left", new ResolvingKey(value, 0), "right", new ResolvingKey(value, 1));
        byte[] bytes = encode(source);
        assertThrows(IllegalArgumentException.class, () -> decode(bytes));
        assertEquals(2, source.size()); assertEquals(2, source.inverse().size());
    }
    static Stream<Arguments> simultaneousCollisions() {
        return Stream.<String>of(null, "same").flatMap(key ->
                Stream.of(Arguments.of(key, null, "collision"),
                        Arguments.of(key, "old", "collision"),
                        Arguments.of(key, "old", null)));
    }
    @ParameterizedTest @MethodSource("simultaneousCollisions")
    void retainsValueCollisionPrecedenceForSimultaneousResolvedKeyCollision(
            String key, String prior, String collision) throws Exception {
        var first = new ResolvingKey(key, 0);
        var second = new ResolvingKey("other-key", 1);
        var third = new ResolvingKey(key, 2);
        var resolvedValue = new ResolvingKey(collision, 42);
        var source = BiMaps.immutable.<Object, Object>with(
                first, prior, second, collision, third, resolvedValue);
        assertIterableEquals(java.util.List.of(first, second, third), source.keysView(),
                "the encoded third entry must collide with both earlier decoded entries");
        assertNotEquals(prior, collision);
        assertNotEquals(collision, resolvedValue);
        assertNotEquals(prior, resolvedValue);
        try {
            byte[] bytes = encode(source);
            var error = assertThrows(IllegalArgumentException.class, () -> decode(bytes));
            assertEquals("Value " + collision + " already exists in map!", error.getMessage());
        } finally {
            assertEquals(3, source.size()); assertEquals(3, source.inverse().size());
            assertEquals(prior, source.get(first)); assertEquals(collision, source.get(second));
            assertSame(resolvedValue, source.get(third));
            assertSame(first, source.inverse().get(prior));
            assertSame(second, source.inverse().get(collision));
            assertSame(third, source.inverse().get(resolvedValue));
        }
    }
    private static byte[] encode(Object source) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(source); }
        byte[] encoded = bytes.toByteArray();
        assertTrue(new String(encoded, StandardCharsets.ISO_8859_1)
                .contains("org.mapdb.collections.impl.bimap.immutable.ImmutableBiMapSerializationProxy"),
                "actual immutable map serialization proxy must be encoded");
        return encoded;
    }
    private static Object decode(byte[] bytes) throws Exception {
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { return input.readObject(); }
    }
}

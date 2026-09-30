package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapdb.collections.api.factory.Maps;
import org.mapdb.collections.api.map.ImmutableMap;
import static org.junit.jupiter.api.Assertions.*;

public class SmallImmutableFlipUniqueTest {
    static Stream<Arguments> duplicates() {
        List<Arguments> cases = new ArrayList<>();
        for (int size : new int[]{3, 4})
            for (int first = 0; first < size; first++)
                for (int second = first + 1; second < size; second++)
                    for (String value : new String[]{null, "same"})
                        for (boolean nullKey : new boolean[]{false, true})
                            cases.add(Arguments.of(size, first, second, value, nullKey));
        return cases.stream();
    }
    static Stream<Arguments> uniqueCases() {
        List<Arguments> cases = new ArrayList<>();
        for (int size : new int[]{3, 4})
            for (int nullValue = -1; nullValue < size; nullValue++)
                for (boolean nullKey : new boolean[]{false, true}) cases.add(Arguments.of(size, nullValue, nullKey));
        return cases.stream();
    }
    static ImmutableMap<String, String> source(String[] keys, String[] values) {
        var source = keys.length == 3
                ? Maps.immutable.with(keys[0], values[0], keys[1], values[1], keys[2], values[2])
                : Maps.immutable.with(keys[0], values[0], keys[1], values[1], keys[2], values[2], keys[3], values[3]);
        assertEquals(keys.length == 3 ? "ImmutableTripletonMap" : "ImmutableQuadrupletonMap", source.getClass().getSimpleName());
        return source;
    }
    @ParameterizedTest @MethodSource("duplicates")
    void rejectsEveryDuplicatePair(int size, int first, int second, String value, boolean nullKey) {
        String[] keys = new String[size]; String[] values = new String[size];
        for (int i = 0; i < size; i++) { keys[i] = "key-" + i; values[i] = "value-" + i; }
        if (nullKey) keys[first] = null;
        values[first] = value; values[second] = value;
        var source = source(keys, values);
        var error = assertThrows(IllegalStateException.class, source::flipUniqueValues);
        assertEquals("Duplicate value: " + value + " found at key: " + keys[first] + " and key: " + keys[second], error.getMessage());
        assertEquals(size, source.size());
        for (int i = 0; i < size; i++) { assertTrue(source.containsKey(keys[i])); assertEquals(values[i], source.get(keys[i])); }
    }
    @ParameterizedTest @MethodSource("uniqueCases")
    void retainsValidNullableMapsAndIteration(int size, int nullValue, boolean nullKey) {
        String[] keys = new String[size]; String[] values = new String[size];
        for (int i = 0; i < size; i++) { keys[i] = "key-" + i; values[i] = i == nullValue ? null : "value-" + i; }
        if (nullKey) keys[0] = null;
        var source = source(keys, values); var result = source.flipUniqueValues();
        assertEquals(source.getClass(), result.getClass()); assertEquals(size, result.size());
        assertEquals(source.valuesView().toList(), result.keysView().toList());
        assertEquals(source.keysView().toList(), result.valuesView().toList());
        for (int i = 0; i < size; i++) {
            assertTrue(result.containsKey(values[i])); assertEquals(keys[i], result.get(values[i]));
            assertTrue(source.containsKey(keys[i])); assertEquals(values[i], source.get(keys[i]));
        }
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"same"})
    void retainsExistingDoubletonDuplicateRejection(String value) {
        var source = Maps.immutable.<String, String>with(null, value, "other", value);
        assertEquals("ImmutableDoubletonMap", source.getClass().getSimpleName());
        assertThrows(IllegalStateException.class, source::flipUniqueValues); assertEquals(2, source.size());
    }
}

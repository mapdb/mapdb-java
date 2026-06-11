// Copyright (c) 2026 Jan Kotek.
// Internal cross-language validation runner for the Eclipse Collections fork
// (mapdb-java). Routes scenarios through STOCK, UNRENAMED Eclipse Collections
// (org.mapdb.collections) production collections and emits a red/green list.
package org.mapdb.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.impl.bag.mutable.primitive.IntHashBag;
import org.mapdb.collections.impl.list.mutable.primitive.FloatArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.IntArrayList;
import org.mapdb.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.multimap.list.FastListMultimap;
import org.mapdb.collections.impl.multimap.set.UnifiedSetMultimap;
import org.mapdb.collections.impl.set.mutable.primitive.FloatHashSet;
import org.mapdb.collections.impl.set.mutable.primitive.IntHashSet;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;
import org.mapdb.collections.impl.utility.FloatTotalOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads cross-language validation scenarios and checks every assertion key
 * against stock Eclipse Collections. Output is the canonical per-line
 * {@code key: value} format plus a PASS/FAIL line per scenario and a summary.
 * Exit code is non-zero if any scenario fails or cannot be run.
 */
public final class ValidationRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UNKNOWN = "UNKNOWN_ASSERTION";

    /** f32 rendering mode for an assertion value, mirroring the Rust runner. */
    private enum FloatMode {
        NONE,      // integer collections: i32/bool/null arrays and scalars
        F32_KEYED, // f32 map/set: scalar size/get/contains are i32/bool; float arrays quoted
        F32_LIST   // f32 list: scalar sum/min/max are floats; sorted array unquoted
    }

    private boolean anyFail = false;
    private int scenariosRun = 0;

    // Per-dir tallies for the summary table.
    private final Map<String, int[]> dirStats = new java.util.TreeMap<>(); // dir -> [pass, fail, skipUnsupported]

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ValidationRunner <scenarios-root-or-file>");
            System.exit(2);
        }
        ValidationRunner runner = new ValidationRunner();
        int code = runner.run(Path.of(args[0]));
        System.exit(code);
    }

    private int run(Path root) throws IOException {
        List<Path> files;
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".json"))
                        .sorted()
                        .collect(Collectors.toList());
            }
        } else {
            files = List.of(root);
        }
        if (files.isEmpty()) {
            System.err.println("No *.json scenarios found under " + root);
            return 2;
        }
        for (Path f : files) {
            runScenario(root, f);
        }
        printSummary();
        return anyFail ? 1 : 0;
    }

    private String dirOf(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return ".";
        }
        if (Files.isDirectory(root)) {
            return parent.getFileName().toString();
        }
        return parent.getFileName().toString();
    }

    private void tally(String dir, int idx) {
        dirStats.computeIfAbsent(dir, k -> new int[3])[idx]++;
    }

    private void runScenario(Path root, Path file) {
        String dir = dirOf(root, file);
        JsonNode scenario;
        try {
            scenario = MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            System.out.println("=== scenario: " + file + " ===");
            System.out.println("FAIL " + file + " : could not parse JSON: " + e.getMessage());
            anyFail = true;
            tally(dir, 1);
            return;
        }
        String name = scenario.path("name").asText(file.getFileName().toString());
        String collection = scenario.path("collection").asText("");
        System.out.println("=== scenario: " + name + " ===");
        scenariosRun++;

        ScenarioResult result = new ScenarioResult(name);
        try {
            dispatch(collection, scenario, result);
        } catch (UnsupportedCollectionException e) {
            // EC ships no production surface for this type -> RED (counts as fail).
            System.out.println("UNSUPPORTED: " + e.getMessage());
            System.out.println("FAIL " + name + " : unsupported collection type in stock EC: " + collection);
            anyFail = true;
            tally(dir, 2);
            return;
        } catch (RuntimeException e) {
            System.out.println("ERROR: " + e);
            System.out.println("FAIL " + name + " : runner error: " + e.getMessage());
            anyFail = true;
            tally(dir, 1);
            return;
        }

        if (result.failed) {
            System.out.println("FAIL " + name);
            anyFail = true;
            tally(dir, 1);
        } else {
            System.out.println("PASS " + name);
            tally(dir, 0);
        }
    }

    /** Per-scenario evaluation context: prints + compares each assertion. */
    private final class ScenarioResult {
        final String name;
        boolean failed = false;

        ScenarioResult(String name) {
            this.name = name;
        }

        /**
         * Emit a computed assertion. Unknown keys are skipped silently (no print,
         * no fail). Otherwise print {@code key: value} and compare to expected.
         */
        void emit(String key, String computed, JsonNode expected, FloatMode mode) {
            if (computed == null) {
                return; // unknown assertion key -> skip
            }
            System.out.println(key + ": " + computed);
            String expectedStr = renderExpected(expected, key, mode);
            if (!computed.equals(expectedStr) && !looseNanMatch(expected, mode, computed)) {
                System.out.println("FAIL " + name + " " + key + ": expected=" + expectedStr + " got=" + computed);
                failed = true;
            }
        }
    }

    private static final class UnsupportedCollectionException extends RuntimeException {
        UnsupportedCollectionException(String msg) {
            super(msg);
        }
    }

    private void dispatch(String collection, JsonNode scenario, ScenarioResult r) {
        switch (collection) {
            case "HashMap<i32, i32>":
                runIntIntMap(scenario, r);
                break;
            case "HashMap<i64, i32>":
                runI64Map(scenario, r);
                break;
            case "ListMultimap<i64, i32>":
                runI64ListMultimap(scenario, r);
                break;
            case "SetMultimap<i64, i32>":
                runI64SetMultimap(scenario, r);
                break;
            case "ArrayList<i32>":
                runIntList(scenario, r);
                break;
            case "HashSet<i32>":
                runIntSet(scenario, r);
                break;
            case "HashBag<i32>":
                runIntBag(scenario, r);
                break;
            case "TreeSet<i32>":
                runIntTreeSet(scenario, r);
                break;
            case "TreeMap<i32, i32>":
                runIntTreeMap(scenario, r);
                break;
            case "HashMap<f32, i32>":
                runF32Map(scenario, r);
                break;
            case "HashSet<f32>":
                runF32Set(scenario, r);
                break;
            case "TreeSet<f32>":
                runF32TreeSet(scenario, r);
                break;
            case "ArrayList<f32>":
                runF32List(scenario, r);
                break;
            default:
                throw new UnsupportedCollectionException("no stock-EC production type for: " + collection);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static Iterable<Map.Entry<String, JsonNode>> assertions(JsonNode scenario) {
        JsonNode a = scenario.get("assertions");
        if (a == null || !a.isObject()) {
            return List.of();
        }
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        a.fields().forEachRemaining(out::add);
        return out;
    }

    private static boolean skipKey(String key) {
        // comment is a doc string; expect_panic is RESERVED/unused -> treat as skip.
        return key.equals("comment") || key.equals("expect_panic");
    }

    private static String formatIntArray(int[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String formatIntList(List<Integer> v) {
        return "[" + v.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    // ---- HashMap<i32, i32> ------------------------------------------------

    private void runIntIntMap(JsonNode scenario, ScenarioResult r) {
        IntIntHashMap map = new IntIntHashMap();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put":
                    map.put(op.get("key").asInt(), op.get("value").asInt());
                    break;
                case "remove":
                    map.removeKey(op.get("key").asInt());
                    break;
                case "addToValue":
                    map.addToValue(op.get("key").asInt(), op.get("delta").asInt());
                    break;
                case "clear":
                    map.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown hashmap op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntIntMap(key, map), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalIntIntMap(String key, IntIntHashMap map) {
        switch (key) {
            case "size":
                return String.valueOf(map.size());
            case "is_empty":
                return String.valueOf(map.isEmpty());
            case "sorted_keys":
                return formatIntArray(sortedAsc(map.keySet().toArray()));
            case "sorted_values":
                return formatIntArray(sortedAsc(map.values().toArray()));
            case "min": {
                int[] ks = sortedAsc(map.keySet().toArray());
                return ks.length == 0 ? "null" : String.valueOf(ks[0]);
            }
            case "max": {
                int[] ks = sortedAsc(map.keySet().toArray());
                return ks.length == 0 ? "null" : String.valueOf(ks[ks.length - 1]);
            }
            default:
                if (key.startsWith("get_")) {
                    int k = Integer.parseInt(key.substring(4));
                    return map.containsKey(k) ? String.valueOf(map.get(k)) : "null";
                }
                if (key.startsWith("contains_")) {
                    int k = Integer.parseInt(key.substring(9));
                    return String.valueOf(map.containsKey(k));
                }
                return null;
        }
    }

    private static int[] sortedAsc(int[] a) {
        Arrays.sort(a);
        return a;
    }

    // ---- HashMap<i64, i32> ------------------------------------------------

    private void runI64Map(JsonNode scenario, ScenarioResult r) {
        LongIntHashMap map = new LongIntHashMap();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put":
                    map.put(I64Codec.parseOperand(op.get("key")), op.get("value").asInt());
                    break;
                case "remove":
                    map.removeKey(I64Codec.parseOperand(op.get("key")));
                    break;
                case "clear":
                    map.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown i64-hashmap op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalI64Map(key, map), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalI64Map(String key, LongIntHashMap map) {
        switch (key) {
            case "size":
                return String.valueOf(map.size());
            case "is_empty":
                return String.valueOf(map.isEmpty());
            case "sorted_keys": {
                long[] ks = map.keySet().toArray();
                Arrays.sort(ks);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < ks.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(ks[i]).append('"');
                }
                return sb.append(']').toString();
            }
            default:
                if (key.startsWith("get_")) {
                    long k = I64Codec.parseSuffix(key.substring(4));
                    return map.containsKey(k) ? String.valueOf(map.get(k)) : "null";
                }
                if (key.startsWith("contains_")) {
                    long k = I64Codec.parseSuffix(key.substring(9));
                    return String.valueOf(map.containsKey(k));
                }
                return null;
        }
    }

    // ---- {List,Set}Multimap<i64, i32> -------------------------------------

    private void runI64ListMultimap(JsonNode scenario, ScenarioResult r) {
        FastListMultimap<Long, Integer> mm = new FastListMultimap<>();
        applyMultimapOps(scenario, mm::put, k -> mm.removeAll(k));
        evalMultimap(scenario, r,
                mm.keysView().size(),
                () -> sortedI64Keys(mm.keysView()),
                k -> sortedIntValues(mm.get(k)),
                k -> mm.containsKey(k));
    }

    private void runI64SetMultimap(JsonNode scenario, ScenarioResult r) {
        UnifiedSetMultimap<Long, Integer> mm = new UnifiedSetMultimap<>();
        applyMultimapOps(scenario, mm::put, k -> mm.removeAll(k));
        evalMultimap(scenario, r,
                mm.keysView().size(),
                () -> sortedI64Keys(mm.keysView()),
                k -> sortedIntValues(mm.get(k)),
                k -> mm.containsKey(k));
    }

    private interface MultimapPut {
        void put(Long k, Integer v);
    }

    private interface MultimapRemoveAll {
        void removeAll(Long k);
    }

    private void applyMultimapOps(JsonNode scenario, MultimapPut put, MultimapRemoveAll removeAll) {
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put":
                    put.put(I64Codec.parseOperand(op.get("key")), op.get("value").asInt());
                    break;
                case "removeAll":
                    removeAll.removeAll(I64Codec.parseOperand(op.get("key")));
                    break;
                default:
                    throw new IllegalArgumentException("unknown i64-multimap op: " + op.path("op").asText());
            }
        }
    }

    private static String sortedI64Keys(Iterable<Long> keys) {
        List<Long> ks = new ArrayList<>();
        for (Long k : keys) {
            ks.add(k);
        }
        ks.sort(Comparator.naturalOrder());
        return "[" + ks.stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(",")) + "]";
    }

    private static String sortedIntValues(Iterable<Integer> vals) {
        List<Integer> vs = new ArrayList<>();
        for (Integer v : vals) {
            vs.add(v);
        }
        vs.sort(Comparator.naturalOrder());
        return formatIntList(vs);
    }

    private interface KeyValues {
        String values(Long k);
    }

    private interface KeyContains {
        boolean contains(Long k);
    }

    private void evalMultimap(JsonNode scenario, ScenarioResult r,
                              int distinctKeyCount,
                              java.util.function.Supplier<String> sortedKeys,
                              KeyValues values, KeyContains contains) {
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            if (key.equals("distinct_key_count")) {
                computed = String.valueOf(distinctKeyCount);
            } else if (key.equals("sorted_keys")) {
                computed = sortedKeys.get();
            } else if (key.startsWith("get_")) {
                computed = values.values(I64Codec.parseSuffix(key.substring(4)));
            } else if (key.startsWith("contains_key_")) {
                computed = String.valueOf(contains.contains(I64Codec.parseSuffix(key.substring(13))));
            } else {
                computed = null;
            }
            r.emit(key, computed, e.getValue(), FloatMode.NONE);
        }
    }

    // ---- ArrayList<i32> ---------------------------------------------------

    private void runIntList(JsonNode scenario, ScenarioResult r) {
        IntArrayList list = new IntArrayList();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    list.add(op.get("value").asInt());
                    break;
                case "add_at":
                    list.addAtIndex(op.get("index").asInt(), op.get("value").asInt());
                    break;
                case "remove":
                    list.remove(op.get("value").asInt());
                    break;
                case "clear":
                    list.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown arraylist op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntList(key, list), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalIntList(String key, IntArrayList list) {
        switch (key) {
            case "size":
                return String.valueOf(list.size());
            case "is_empty":
                return String.valueOf(list.isEmpty());
            case "sum":
                // EC IntList.sum() widens to a long accumulator (contract parity).
                return String.valueOf(list.sum());
            case "min":
                return list.isEmpty() ? "null" : String.valueOf(list.min());
            case "max":
                return list.isEmpty() ? "null" : String.valueOf(list.max());
            case "to_sorted_array":
                return formatIntArray(sortedAsc(list.toArray()));
            case "inject_into_sum":
                // injectInto with + accumulates in the i32 seed and wraps.
                return String.valueOf(list.injectInto(0, (a, v) -> a + v));
            case "inject_into_product":
                return String.valueOf(list.injectInto(1, (a, v) -> a * v));
            case "count_even":
                return String.valueOf(list.count(v -> v % 2 == 0));
            case "count_odd":
                return String.valueOf(list.count(v -> v % 2 != 0));
            case "any_satisfy_even":
                return String.valueOf(list.anySatisfy(v -> v % 2 == 0));
            case "all_satisfy_even":
                return String.valueOf(list.allSatisfy(v -> v % 2 == 0));
            case "none_satisfy_odd":
                return String.valueOf(list.noneSatisfy(v -> v % 2 != 0));
            default:
                String r2 = evalIntListPredicate(key, list);
                return r2;
        }
    }

    private String evalIntListPredicate(String key, IntArrayList list) {
        if (key.startsWith("get_at_")) {
            int idx = Integer.parseInt(key.substring(7));
            return idx >= 0 && idx < list.size() ? String.valueOf(list.get(idx)) : "null";
        }
        if (key.startsWith("contains_")) {
            int v = Integer.parseInt(key.substring(9));
            return String.valueOf(list.contains(v));
        }
        if (key.startsWith("select_gt_")) {
            int t = Integer.parseInt(key.substring(10));
            return formatIntArray(sortedAsc(list.select(v -> v > t).toArray()));
        }
        if (key.startsWith("reject_gt_")) {
            int t = Integer.parseInt(key.substring(10));
            return formatIntArray(sortedAsc(list.reject(v -> v > t).toArray()));
        }
        if (key.startsWith("detect_gt_")) {
            int t = Integer.parseInt(key.substring(10));
            // detectIfNone returns a sentinel; use anySatisfy + manual scan for null.
            for (int i = 0; i < list.size(); i++) {
                int v = list.get(i);
                if (v > t) {
                    return String.valueOf(v);
                }
            }
            return "null";
        }
        if (key.startsWith("count_gt_")) {
            int t = Integer.parseInt(key.substring(9));
            return String.valueOf(list.count(v -> v > t));
        }
        if (key.startsWith("count_lt_")) {
            int t = Integer.parseInt(key.substring(9));
            return String.valueOf(list.count(v -> v < t));
        }
        if (key.startsWith("any_satisfy_gt_")) {
            int t = Integer.parseInt(key.substring(15));
            return String.valueOf(list.anySatisfy(v -> v > t));
        }
        if (key.startsWith("all_satisfy_gt_")) {
            int t = Integer.parseInt(key.substring(15));
            return String.valueOf(list.allSatisfy(v -> v > t));
        }
        if (key.startsWith("none_satisfy_gt_")) {
            int t = Integer.parseInt(key.substring(16));
            return String.valueOf(list.noneSatisfy(v -> v > t));
        }
        if (key.startsWith("none_satisfy_lt_")) {
            int t = Integer.parseInt(key.substring(16));
            return String.valueOf(list.noneSatisfy(v -> v < t));
        }
        return null;
    }

    // ---- HashSet<i32> -----------------------------------------------------

    private void runIntSet(JsonNode scenario, ScenarioResult r) {
        IntHashSet set = buildIntSet(scenario.path("operations"));
        IntHashSet other = scenario.has("other")
                ? buildIntSet(scenario.path("other").path("operations"))
                : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntSet(key, set, other), e.getValue(), FloatMode.NONE);
        }
    }

    private IntHashSet buildIntSet(JsonNode ops) {
        IntHashSet set = new IntHashSet();
        for (JsonNode op : ops) {
            switch (op.path("op").asText()) {
                case "add":
                    set.add(op.get("value").asInt());
                    break;
                case "remove":
                    set.remove(op.get("value").asInt());
                    break;
                case "clear":
                    set.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown hashset op: " + op.path("op").asText());
            }
        }
        return set;
    }

    private String evalIntSet(String key, IntHashSet set, IntHashSet other) {
        switch (key) {
            case "size":
                return String.valueOf(set.size());
            case "is_empty":
                return String.valueOf(set.isEmpty());
            case "to_sorted_array":
                return formatIntArray(sortedAsc(set.toArray()));
            default:
                break;
        }
        if (key.startsWith("contains_")) {
            return String.valueOf(set.contains(Integer.parseInt(key.substring(9))));
        }
        if (other == null) {
            return null;
        }
        List<Integer> a = toIntList(set);
        List<Integer> b = toIntList(other);
        switch (key) {
            case "other_size":
                return String.valueOf(other.size());
            case "union_sorted":
                return formatIntList(sortedDistinct(concat(a, b)));
            case "union_size":
                return String.valueOf(sortedDistinct(concat(a, b)).size());
            case "intersect_sorted":
                return formatIntList(sortedDistinct(a.stream().filter(other::contains).collect(Collectors.toList())));
            case "intersect_size":
                return String.valueOf((int) a.stream().filter(other::contains).count());
            case "difference_sorted":
                return formatIntList(sortedDistinct(a.stream().filter(x -> !other.contains(x)).collect(Collectors.toList())));
            case "difference_size":
                return String.valueOf((int) a.stream().filter(x -> !other.contains(x)).count());
            case "symmetric_difference_sorted":
                return formatIntList(symmetricDifference(a, b, set, other));
            case "symmetric_difference_size":
                return String.valueOf(symmetricDifference(a, b, set, other).size());
            default:
                return null;
        }
    }

    private static List<Integer> toIntList(IntHashSet set) {
        List<Integer> out = new ArrayList<>();
        set.forEach((int v) -> out.add(v));
        return out;
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<Integer> sortedDistinct(List<Integer> in) {
        return in.stream().distinct().sorted().collect(Collectors.toList());
    }

    private static List<Integer> symmetricDifference(List<Integer> a, List<Integer> b,
                                                     IntHashSet setA, IntHashSet setB) {
        List<Integer> out = new ArrayList<>();
        for (Integer x : a) {
            if (!setB.contains(x)) {
                out.add(x);
            }
        }
        for (Integer x : b) {
            if (!setA.contains(x)) {
                out.add(x);
            }
        }
        return sortedDistinct(out);
    }

    // ---- HashBag<i32> -----------------------------------------------------

    private void runIntBag(JsonNode scenario, ScenarioResult r) {
        IntHashBag bag = new IntHashBag();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    bag.add(op.get("value").asInt());
                    break;
                case "remove":
                    bag.remove(op.get("value").asInt());
                    break;
                case "clear":
                    bag.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown hashbag op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntBag(key, bag), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalIntBag(String key, IntHashBag bag) {
        switch (key) {
            case "size":
                return String.valueOf(bag.size());
            case "size_distinct":
                return String.valueOf(bag.sizeDistinct());
            case "is_empty":
                return String.valueOf(bag.isEmpty());
            case "sorted_distinct":
                return formatIntArray(sortedAsc(bag.toSet().toArray()));
            case "to_sorted_array":
                return formatIntArray(sortedAsc(bag.toArray()));
            default:
                if (key.startsWith("occurrences_")) {
                    return String.valueOf(bag.occurrencesOf(Integer.parseInt(key.substring(12))));
                }
                if (key.startsWith("contains_")) {
                    return String.valueOf(bag.contains(Integer.parseInt(key.substring(9))));
                }
                return null;
        }
    }

    // ---- TreeSet<i32> (object TreeSortedSet<Integer>) ---------------------

    private void runIntTreeSet(JsonNode scenario, ScenarioResult r) {
        MutableSortedSet<Integer> set = TreeSortedSet.newSet();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    set.add(op.get("value").asInt());
                    break;
                case "remove":
                    set.remove(op.get("value").asInt());
                    break;
                case "clear":
                    set.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown treeset op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(set.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(set.isEmpty());
                    break;
                case "min":
                    computed = set.isEmpty() ? "null" : String.valueOf(set.getFirst());
                    break;
                case "max":
                    computed = set.isEmpty() ? "null" : String.valueOf(set.getLast());
                    break;
                case "to_sorted_array":
                    computed = "[" + set.collect(String::valueOf).makeString(",") + "]";
                    break;
                default:
                    computed = key.startsWith("contains_")
                            ? String.valueOf(set.contains(Integer.parseInt(key.substring(9))))
                            : null;
            }
            r.emit(key, computed, e.getValue(), FloatMode.NONE);
        }
    }

    // ---- TreeMap<i32, i32> (object TreeSortedMap<Integer,Integer>) --------

    private void runIntTreeMap(JsonNode scenario, ScenarioResult r) {
        MutableSortedMap<Integer, Integer> map = TreeSortedMap.newMap();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put":
                    map.put(op.get("key").asInt(), op.get("value").asInt());
                    break;
                case "remove":
                    map.remove(op.get("key").asInt());
                    break;
                case "clear":
                    map.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown treemap op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(map.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(map.isEmpty());
                    break;
                case "min":
                    computed = map.isEmpty() ? "null" : String.valueOf(map.keySet().getFirst());
                    break;
                case "max":
                    computed = map.isEmpty() ? "null" : String.valueOf(map.keySet().getLast());
                    break;
                case "sorted_keys":
                    // keysView() preserves the tree's key order; keySet().collect()
                    // would route through an unordered UnifiedSet and scramble it.
                    computed = "[" + map.keysView().collect(String::valueOf).makeString(",") + "]";
                    break;
                case "sorted_values":
                    // Values in key-ascending order (TreeSortedMap iterates by key).
                    computed = "[" + map.valuesView().collect(String::valueOf).makeString(",") + "]";
                    break;
                default:
                    if (key.startsWith("get_")) {
                        Integer v = map.get(Integer.parseInt(key.substring(4)));
                        computed = v == null ? "null" : String.valueOf(v);
                    } else if (key.startsWith("contains_")) {
                        computed = String.valueOf(map.containsKey(Integer.parseInt(key.substring(9))));
                    } else {
                        computed = null;
                    }
            }
            r.emit(key, computed, e.getValue(), FloatMode.NONE);
        }
    }

    // ---- HashMap<f32, i32> ------------------------------------------------

    private void runF32Map(JsonNode scenario, ScenarioResult r) {
        FloatIntHashMap map = new FloatIntHashMap();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put":
                    map.put(FloatCodec.parseOperand(op.get("key")), op.get("value").asInt());
                    break;
                case "remove":
                    map.removeKey(FloatCodec.parseOperand(op.get("key")));
                    break;
                case "clear":
                    map.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown f32-hashmap op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(map.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(map.isEmpty());
                    break;
                case "sorted_keys": {
                    float[] ks = map.keySet().toArray();
                    Float[] boxed = new Float[ks.length];
                    for (int i = 0; i < ks.length; i++) {
                        boxed[i] = ks[i];
                    }
                    Arrays.sort(boxed, Float::compare);
                    computed = "[" + Arrays.stream(boxed)
                            .map(f -> "\"" + FloatCodec.format(f) + "\"")
                            .collect(Collectors.joining(",")) + "]";
                    break;
                }
                default:
                    if (key.startsWith("get_")) {
                        float k = FloatCodec.parseLabel(key.substring(4));
                        computed = map.containsKey(k) ? String.valueOf(map.get(k)) : "null";
                    } else if (key.startsWith("contains_")) {
                        float k = FloatCodec.parseLabel(key.substring(9));
                        computed = String.valueOf(map.containsKey(k));
                    } else {
                        computed = null;
                    }
            }
            r.emit(key, computed, e.getValue(), FloatMode.F32_KEYED);
        }
    }

    // ---- HashSet<f32> -----------------------------------------------------

    private void runF32Set(JsonNode scenario, ScenarioResult r) {
        FloatHashSet set = new FloatHashSet();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    set.add(FloatCodec.parseOperand(op.get("value")));
                    break;
                case "remove":
                    set.remove(FloatCodec.parseOperand(op.get("value")));
                    break;
                case "clear":
                    set.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown f32-hashset op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(set.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(set.isEmpty());
                    break;
                case "sorted_values":
                case "to_sorted_array": {
                    float[] vs = set.toArray();
                    Float[] boxed = new Float[vs.length];
                    for (int i = 0; i < vs.length; i++) {
                        boxed[i] = vs[i];
                    }
                    Arrays.sort(boxed, Float::compare);
                    computed = "[" + Arrays.stream(boxed)
                            .map(f -> "\"" + FloatCodec.format(f) + "\"")
                            .collect(Collectors.joining(",")) + "]";
                    break;
                }
                default:
                    computed = key.startsWith("contains_")
                            ? String.valueOf(set.contains(FloatCodec.parseLabel(key.substring(9))))
                            : null;
            }
            r.emit(key, computed, e.getValue(), FloatMode.F32_KEYED);
        }
    }

    // ---- TreeSet<f32> (object TreeSortedSet<Float>, Float::compare) -------

    private void runF32TreeSet(JsonNode scenario, ScenarioResult r) {
        // mapdb-java fallback type (no primitive sorted set yet), but ordered by
        // the IEEE 754 totalOrder comparator FloatTotalOrder.FLOAT_COMPARATOR
        // (sign-flip construction, Rust total_cmp equivalent) rather than
        // Float::compare. The portable subset asserted by the shared suite
        // (signed-zero split, +NaN at top) is unchanged by construction; the
        // non-portable parts (-NaN below -Inf, distinct NaN payloads) are
        // covered by native tests, not the shared suite.
        MutableSortedSet<Float> set = TreeSortedSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR);
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    set.add(FloatCodec.parseOperand(op.get("value")));
                    break;
                case "remove":
                    set.remove(FloatCodec.parseOperand(op.get("value")));
                    break;
                case "clear":
                    set.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown f32-treeset op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(set.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(set.isEmpty());
                    break;
                case "min":
                    computed = set.isEmpty() ? "null" : FloatCodec.format(set.getFirst());
                    break;
                case "max":
                    computed = set.isEmpty() ? "null" : FloatCodec.format(set.getLast());
                    break;
                case "sorted":
                case "sorted_values":
                case "to_sorted_array": {
                    // In-order traversal straight from the tree (NOT runner-sorted).
                    List<String> parts = new ArrayList<>();
                    for (Float f : set) {
                        parts.add("\"" + FloatCodec.format(f) + "\"");
                    }
                    computed = "[" + String.join(",", parts) + "]";
                    break;
                }
                default:
                    computed = key.startsWith("contains_")
                            ? String.valueOf(set.contains(FloatCodec.parseLabel(key.substring(9))))
                            : null;
            }
            r.emit(key, computed, e.getValue(), FloatMode.F32_KEYED);
        }
    }

    // ---- ArrayList<f32> ---------------------------------------------------

    private void runF32List(JsonNode scenario, ScenarioResult r) {
        FloatArrayList list = new FloatArrayList();
        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "add":
                    list.add(FloatCodec.parseOperand(op.get("value")));
                    break;
                case "clear":
                    list.clear();
                    break;
                default:
                    throw new IllegalArgumentException("unknown f32-arraylist op: " + op.path("op").asText());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            String computed;
            switch (key) {
                case "size":
                    computed = String.valueOf(list.size());
                    break;
                case "is_empty":
                    computed = String.valueOf(list.isEmpty());
                    break;
                case "sum": {
                    // Per-add f32 left-fold (IEEE arithmetic, not widened).
                    float s = list.injectInto(0.0f, (acc, v) -> acc + v);
                    computed = FloatCodec.format(s);
                    break;
                }
                case "min":
                    computed = list.isEmpty() ? "null" : FloatCodec.format(list.min());
                    break;
                case "max":
                    computed = list.isEmpty() ? "null" : FloatCodec.format(list.max());
                    break;
                case "sorted":
                case "to_sorted_array": {
                    // Sort a COPY via EC's float sort (Float::compare order).
                    float[] arr = list.toArray();
                    Float[] boxed = new Float[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        boxed[i] = arr[i];
                    }
                    Arrays.sort(boxed, Float::compare);
                    computed = "[" + Arrays.stream(boxed)
                            .map(FloatCodec::format)
                            .collect(Collectors.joining(",")) + "]";
                    break;
                }
                default:
                    computed = null;
            }
            r.emit(key, computed, e.getValue(), FloatMode.F32_LIST);
        }
    }

    // ---- expected rendering + loose NaN -----------------------------------

    private String renderExpected(JsonNode v, String key, FloatMode mode) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isBoolean()) {
            return String.valueOf(v.asBoolean());
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode el : v) {
                switch (mode) {
                    case NONE:
                        // i64 sorted_keys arrays are quoted decimal STRINGS in the
                        // JSON; integer arrays are bare numbers. Preserve quoting so
                        // the rendered form matches the runner's quoted i64 output.
                        if (el.isNull()) {
                            parts.add("null");
                        } else if (el.isTextual()) {
                            parts.add("\"" + el.asText() + "\"");
                        } else {
                            parts.add(el.asText());
                        }
                        break;
                    case F32_KEYED:
                        parts.add("\"" + FloatCodec.format(elementToF32(el)) + "\"");
                        break;
                    case F32_LIST:
                        parts.add(FloatCodec.format(elementToF32(el)));
                        break;
                    default:
                        parts.add(el.asText());
                }
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (v.isNumber()) {
            boolean f32Scalar = (mode == FloatMode.F32_LIST && !key.equals("size"))
                    || (mode == FloatMode.F32_KEYED && (key.equals("min") || key.equals("max")));
            if (f32Scalar) {
                return FloatCodec.format((float) v.asDouble());
            }
            // i32 scalar, or i32 map value under F32_KEYED.
            if (v.isIntegralNumber()) {
                return v.asText();
            }
            return v.asText();
        }
        if (v.isTextual()) {
            String s = v.asText();
            if (mode == FloatMode.NONE) {
                // e.g. i64 sorted_keys array handled above; bare string scalar.
                return s;
            }
            // Float label scalar (e.g. sum: "NaN", max: "NaN", min: "-2.0").
            return FloatCodec.format(FloatCodec.parseLabel(s));
        }
        if (v.isObject() && mode != FloatMode.NONE) {
            // bits-escape float scalar (e.g. sum: {"bits":"0xffc00000"}).
            return FloatCodec.format(FloatCodec.parseOperand(v));
        }
        return v.toString();
    }

    private float elementToF32(JsonNode el) {
        if (el.isTextual()) {
            return FloatCodec.parseLabel(el.asText());
        }
        if (el.isNumber()) {
            return (float) el.asDouble();
        }
        if (el.isObject()) {
            return FloatCodec.parseOperand(el);
        }
        throw new IllegalArgumentException("unexpected float array element: " + el);
    }

    /**
     * Loose-NaN scalar match: a bare NaN LABEL expected scalar matches any NaN
     * the runner computed (canonical "0x........" form). Does not apply to
     * {"bits"} operands or to array elements.
     */
    private boolean looseNanMatch(JsonNode expected, FloatMode mode, String computed) {
        if (mode == FloatMode.NONE) {
            return false;
        }
        if (expected == null || !expected.isTextual()) {
            return false;
        }
        if (!FloatCodec.isNanLabel(expected.asText())) {
            return false;
        }
        if (!(computed.startsWith("0x") || computed.startsWith("0X"))) {
            return false;
        }
        try {
            int bits = (int) Long.parseLong(computed.substring(2), 16);
            return Float.isNaN(Float.intBitsToFloat(bits));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ---- summary ----------------------------------------------------------

    private void printSummary() {
        System.out.println();
        System.out.println("================ SUMMARY ================");
        System.out.printf("%-22s %6s %6s %6s%n", "dir", "PASS", "FAIL", "(unsup)");
        int tp = 0;
        int tf = 0;
        int tu = 0;
        for (Map.Entry<String, int[]> e : dirStats.entrySet()) {
            int[] s = e.getValue();
            int fail = s[1] + s[2];
            System.out.printf("%-22s %6d %6d %6d%n", e.getKey(), s[0], fail, s[2]);
            tp += s[0];
            tf += fail;
            tu += s[2];
        }
        System.out.println("-----------------------------------------");
        System.out.printf("%-22s %6d %6d %6d%n", "TOTAL", tp, tf, tu);
        System.out.println("(unsup = scenarios that could not run: no stock-EC type; counted as FAIL)");
        System.out.println("scenarios run: " + scenariosRun + ", result: " + (anyFail ? "RED (failures present)" : "GREEN"));
    }
}

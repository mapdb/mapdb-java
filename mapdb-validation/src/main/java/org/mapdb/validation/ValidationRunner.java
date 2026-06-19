// Copyright (c) 2026 Jan Kotek.
// Internal cross-language validation runner for mapdb-java (the renamed
// Eclipse Collections fork). Routes scenarios through the fork's production
// collections under org.mapdb.collections and emits a red/green list.
package org.mapdb.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.impl.bag.mutable.primitive.IntHashBag;
import org.mapdb.collections.impl.list.mutable.primitive.FloatArrayList;
import org.mapdb.collections.impl.list.mutable.primitive.IntArrayList;
import org.mapdb.collections.impl.map.mutable.primitive.FloatIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.mapdb.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.mapdb.collections.impl.multimap.list.FastListMultimap;
import org.mapdb.collections.impl.multimap.set.UnifiedSetMultimap;
import org.mapdb.collections.impl.set.mutable.primitive.FloatHashSet;
import org.mapdb.collections.impl.set.mutable.primitive.IntHashSet;
import org.mapdb.collections.impl.navigable.NavigableTreeMap;
import org.mapdb.collections.impl.navigable.NavigableTreeSet;
import org.mapdb.collections.impl.range.BoundType;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;
import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.RoaringU32;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads cross-language validation scenarios and checks every assertion key
 * against the mapdb-java production collections. Output is the canonical per-line
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
    private int skippedAssertions = 0;

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
        } catch (ScenarioSkipException e) {
            // Malformed/forward-compat scenario -> SKIP (neither PASS nor FAIL).
            System.out.println("SKIP " + name + " : " + e.getMessage());
            scenariosRun--;
            return;
        } catch (RuntimeException e) {
            System.out.println("ERROR: " + e);
            System.out.println("FAIL " + name + " : runner error: " + e.getMessage());
            anyFail = true;
            tally(dir, 1);
            return;
        }

        // Vacuous-pass guard: a scenario that evaluated ZERO real assertions is
        // flagged a failure, even if nothing explicitly failed. This is what lets
        // emit() forward-compat-SKIP an unknown assertion key without failing the
        // scenario: a scenario with at least one KNOWN assertion still passes
        // (its unknown keys merely skip), but a scenario whose assertions are ALL
        // unknown (or absent) evaluates nothing and is caught here rather than
        // passing vacuously.
        if (!result.failed && result.evaluated == 0) {
            System.out.println("FAIL " + name
                    + " : no assertions evaluated (all unknown/absent -> vacuous pass guard)");
            result.failed = true;
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
        /** Real assertions actually evaluated (evaluator returned a value). */
        int evaluated = 0;
        /** Unknown assertion keys skipped (evaluator returned the null sentinel). */
        int unknownSkipped = 0;

        ScenarioResult(String name) {
            this.name = name;
        }

        /**
         * Emit a computed assertion. A key the runner has no evaluator for
         * (evaluator returns the {@code null} "unknown key" sentinel) is reported
         * as SKIP <b>without failing the scenario</b> — the cross-language README
         * requires an unknown ops/assertion key to be forward-compat-SKIPPED, so a
         * future key never breaks an older runner. (The vacuous-pass hole that
         * once let the {@code product} assertion slip through is closed instead by
         * the zero-evaluated-assertions guard in {@link #runScenario}: a scenario
         * that evaluates NO real assertion is flagged a failure there, so an
         * unknown key can be skipped without also turning every all-unknown
         * scenario green.) A known key is printed and compared to expected.
         */
        void emit(String key, String computed, JsonNode expected, FloatMode mode) {
            if (computed == null) {
                System.out.println("SKIP " + name + " " + key + ": unknown assertion key (forward-compat skip)");
                skippedAssertions++;
                unknownSkipped++;
                return;
            }
            evaluated++;
            System.out.println(key + ": " + computed);
            String expectedStr = renderExpected(expected, key, mode);
            if (!computed.equals(expectedStr) && !looseNanMatch(expected, mode, computed)) {
                System.out.println("FAIL " + name + " " + key + ": expected=" + expectedStr + " got=" + computed);
                failed = true;
            }
        }
    }

    /**
     * A malformed scenario the runner declines to evaluate (per the
     * cross-language README forward-compat / authoring rules): e.g. a
     * sorted-table scenario with zero or multiple {@code from_sorted} ops. The
     * scenario is SKIPPED (neither PASS nor FAIL), not silently mis-applied.
     */
    private static final class ScenarioSkipException extends RuntimeException {
        ScenarioSkipException(String msg) {
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
            case "Range<i32>":
                runRange(scenario, r);
                break;
            case "ImmutableSortedMap<i32, i32>":
                runImmutableSortedMap(scenario, r);
                break;
            case "ImmutableSortedSet<i32>":
                runImmutableSortedSet(scenario, r);
                break;
            case "HashPipeline":
                runHashPipeline(scenario, r);
                break;
            case "RoaringU32":
                runRoaringU32(scenario, r);
                break;
            default:
                // Forward-compat: a collection kind this runner does not yet
                // understand is SKIPPED (neither PASS nor FAIL), per the
                // cross-language-validation README. Adding a future kind never
                // breaks an older runner.
                throw new ScenarioSkipException("unknown collection kind (forward-compat skip): " + collection);
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

    /** Like {@link #formatIntList} but renders {@code null} elements as {@code null}. */
    private static String formatNullableIntList(List<Integer> v) {
        return "[" + v.stream().map(x -> x == null ? "null" : String.valueOf(x))
                .collect(Collectors.joining(",")) + "]";
    }

    private static final Pattern NAV_KEY = Pattern.compile("^(floor|ceiling|lower|higher)_(-?\\d+)$");

    /**
     * Parse the signed base-10 i32 suffix of a point-nav assertion key
     * ({@code floor_<k>}/{@code ceiling_<k>}/{@code lower_<k>}/{@code higher_<k>}),
     * including a leading {@code -} and the full i32 range. Returns {@code null}
     * when the key is not a nav key.
     */
    private static Integer parseNavKey(String key) {
        Matcher m = NAV_KEY.matcher(key);
        return m.matches() ? Integer.parseInt(m.group(2)) : null;
    }

    // Order-statistics keys (spec features/rank-select.md). Matched by EXACT
    // regex so they never collide with the functional select_<pred> keys
    // (select_gt_N, select_even): rank_<k> takes a signed i32 suffix, select_<i>
    // a NON-NEGATIVE decimal index (leading '+' rejected by the pattern).
    private static final Pattern RANK_KEY = Pattern.compile("^rank_(-?[0-9]+)$");
    private static final Pattern SELECT_KEY = Pattern.compile("^select_([0-9]+)$");

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
            case "inject_into_wrapping_product":
            case "product":
                // Wrapping i32 product: the seed-1 accumulator multiplies and wraps
                // (mirrors Rust/Go/TS runners). Drives 06-overflow/i32_multiply_overflow.
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

    // ---- TreeSet<i32> (NavigableTreeSet<Integer> over the boxed tree) -----

    private void runIntTreeSet(JsonNode scenario, ScenarioResult r) {
        NavigableTreeSet<Integer> set = NavigableTreeSet.newSet();
        // Operation result logs: poll values and removeRange counts in
        // execution order (cross-language-observable per the harness).
        List<Integer> pollFirstKeys = new ArrayList<>();
        List<Integer> pollLastKeys = new ArrayList<>();
        List<Integer> removeRangeCounts = new ArrayList<>();
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
                case "poll_first":
                    pollFirstKeys.add(set.pollFirst().orElse(null));
                    break;
                case "poll_last":
                    pollLastKeys.add(set.pollLast().orElse(null));
                    break;
                case "remove_range":
                    removeRangeCounts.add(set.removeRange(buildRangeFromNode(op.get("range"))));
                    break;
                default:
                    // Unknown op -> SKIP (forward-compat).
                    break;
            }
        }
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntTreeSet(key, set, query, pollFirstKeys, pollLastKeys, removeRangeCounts),
                    e.getValue(), FloatMode.NONE);
        }
    }

    private String evalIntTreeSet(String key, NavigableTreeSet<Integer> set, Range<Integer> query,
                                  List<Integer> pollFirstKeys, List<Integer> pollLastKeys,
                                  List<Integer> removeRangeCounts) {
        switch (key) {
            case "size":
                return String.valueOf(set.size());
            case "is_empty":
                return String.valueOf(set.isEmpty());
            case "min":
            case "first":
                return optIntStr(set.first());
            case "max":
            case "last":
                return optIntStr(set.last());
            case "to_sorted_array":
                return formatIntList(set.rangeElements(Range.all()));
            case "descending_elements":
                return formatIntList(set.descending());
            case "range_elements":
                return query == null ? null : formatIntList(set.rangeElements(query));
            case "range_elements_desc":
                return query == null ? null : formatIntList(set.descendingRangeElements(query));
            case "range_size":
                return query == null ? null : String.valueOf(set.rangeElements(query).size());
            case "poll_first_keys":
                return formatNullableIntList(pollFirstKeys);
            case "poll_last_keys":
                return formatNullableIntList(pollLastKeys);
            case "remove_range_counts":
                return formatIntList(removeRangeCounts);
            default:
                break;
        }
        if (key.startsWith("contains_")) {
            return String.valueOf(set.contains(Integer.parseInt(key.substring(9))));
        }
        Matcher rank = RANK_KEY.matcher(key);
        if (rank.matches()) {
            return String.valueOf(set.rank(Integer.parseInt(rank.group(1))));
        }
        Matcher sel = SELECT_KEY.matcher(key);
        if (sel.matches()) {
            return optIntStr(set.select(Integer.parseInt(sel.group(1))).orElse(null));
        }
        Integer navArg = parseNavKey(key);
        if (navArg != null) {
            return evalSetNav(key, set, navArg);
        }
        return null;
    }

    private String evalSetNav(String key, NavigableTreeSet<Integer> set, int k) {
        if (key.startsWith("floor_")) {
            return optIntStr(set.floor(k));
        }
        if (key.startsWith("ceiling_")) {
            return optIntStr(set.ceiling(k));
        }
        if (key.startsWith("lower_")) {
            return optIntStr(set.lower(k));
        }
        if (key.startsWith("higher_")) {
            return optIntStr(set.higher(k));
        }
        return null;
    }

    // ---- TreeMap<i32, i32> (NavigableTreeMap<Integer,Integer> over the boxed tree)

    private void runIntTreeMap(JsonNode scenario, ScenarioResult r) {
        NavigableTreeMap<Integer, Integer> map = NavigableTreeMap.newMap();
        List<Integer> pollFirstKeys = new ArrayList<>();
        List<Integer> pollFirstValues = new ArrayList<>();
        List<Integer> pollLastKeys = new ArrayList<>();
        List<Integer> pollLastValues = new ArrayList<>();
        List<Integer> removeRangeCounts = new ArrayList<>();
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
                case "poll_first": {
                    Optional<Map.Entry<Integer, Integer>> e = map.pollFirstEntry();
                    pollFirstKeys.add(e.map(Map.Entry::getKey).orElse(null));
                    pollFirstValues.add(e.map(Map.Entry::getValue).orElse(null));
                    break;
                }
                case "poll_last": {
                    Optional<Map.Entry<Integer, Integer>> e = map.pollLastEntry();
                    pollLastKeys.add(e.map(Map.Entry::getKey).orElse(null));
                    pollLastValues.add(e.map(Map.Entry::getValue).orElse(null));
                    break;
                }
                case "remove_range":
                    removeRangeCounts.add(map.removeRange(buildRangeFromNode(op.get("range"))));
                    break;
                default:
                    // Unknown op -> SKIP (forward-compat).
                    break;
            }
        }
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalIntTreeMap(key, map, query,
                            pollFirstKeys, pollFirstValues, pollLastKeys, pollLastValues, removeRangeCounts),
                    e.getValue(), FloatMode.NONE);
        }
    }

    private String evalIntTreeMap(String key, NavigableTreeMap<Integer, Integer> map, Range<Integer> query,
                                  List<Integer> pollFirstKeys, List<Integer> pollFirstValues,
                                  List<Integer> pollLastKeys, List<Integer> pollLastValues,
                                  List<Integer> removeRangeCounts) {
        switch (key) {
            case "size":
                return String.valueOf(map.size());
            case "is_empty":
                return String.valueOf(map.isEmpty());
            case "min":
                return optIntStr(map.firstKey());
            case "max":
                return optIntStr(map.lastKey());
            case "first_key":
                return optIntStr(map.firstKey());
            case "last_key":
                return optIntStr(map.lastKey());
            case "sorted_keys":
                return formatIntList(map.rangeKeys(Range.all()));
            case "sorted_values":
                return formatIntList(rangeValuesAsc(map));
            case "descending_keys":
                return formatIntList(map.descendingKeys());
            case "range_keys":
                return query == null ? null : formatIntList(map.rangeKeys(query));
            case "range_keys_desc":
                return query == null ? null : formatIntList(map.descendingRangeKeys(query));
            case "range_size":
                return query == null ? null : String.valueOf(map.rangeKeys(query).size());
            case "poll_first_keys":
                return formatNullableIntList(pollFirstKeys);
            case "poll_last_keys":
                return formatNullableIntList(pollLastKeys);
            case "poll_first_values":
                return formatNullableIntList(pollFirstValues);
            case "poll_last_values":
                return formatNullableIntList(pollLastValues);
            case "remove_range_counts":
                return formatIntList(removeRangeCounts);
            default:
                break;
        }
        if (key.startsWith("get_")) {
            Integer v = map.get(Integer.parseInt(key.substring(4)));
            return v == null ? "null" : String.valueOf(v);
        }
        if (key.startsWith("contains_")) {
            return String.valueOf(map.containsKey(Integer.parseInt(key.substring(9))));
        }
        Matcher rank = RANK_KEY.matcher(key);
        if (rank.matches()) {
            return String.valueOf(map.rank(Integer.parseInt(rank.group(1))));
        }
        Matcher sel = SELECT_KEY.matcher(key);
        if (sel.matches()) {
            return optIntStr(map.selectKey(Integer.parseInt(sel.group(1))).orElse(null));
        }
        Integer navArg = parseNavKey(key);
        if (navArg != null) {
            return evalMapNav(key, map, navArg);
        }
        return null;
    }

    private static List<Integer> rangeValuesAsc(NavigableTreeMap<Integer, Integer> map) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : map.rangeEntries(Range.all())) {
            out.add(e.getValue());
        }
        return out;
    }

    private String evalMapNav(String key, NavigableTreeMap<Integer, Integer> map, int k) {
        if (key.startsWith("floor_")) {
            return optIntStr(map.floorKey(k));
        }
        if (key.startsWith("ceiling_")) {
            return optIntStr(map.ceilingKey(k));
        }
        if (key.startsWith("lower_")) {
            return optIntStr(map.lowerKey(k));
        }
        if (key.startsWith("higher_")) {
            return optIntStr(map.higherKey(k));
        }
        return null;
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

    // ---- Range<i32> (boxed Range<Integer>) --------------------------------

    private static final Pattern CONTAINS_N = Pattern.compile("^contains_(-?\\d+)$");

    /**
     * The Bound/Range value model (spec/features/bound-range.md). Exactly ONE
     * constructor op builds the range under test; an optional {@code "other"}
     * block (same single-builder shape) supplies the second range for the
     * binary ops. The i32 universe is boxed as {@code Range<Integer>}.
     */
    private void runRange(JsonNode scenario, ScenarioResult r) {
        Range<Integer> range = buildRange(scenario.path("operations"));
        Range<Integer> other = scenario.has("other")
                ? buildRange(scenario.path("other").path("operations"))
                : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalRange(key, range, other), e.getValue(), FloatMode.NONE);
        }
    }

    private Range<Integer> buildRange(JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.size() != 1) {
            throw new IllegalArgumentException(
                    "Range<i32> scenario must have exactly one constructor op");
        }
        return buildRangeFromNode(ops.get(0));
    }

    /**
     * Build a {@code Range<Integer>} from a single builder-op node (the
     * {@code 10-range} builder-op shape), reused by the navigable-map
     * {@code query} block and the {@code remove_range} op's {@code range} field.
     */
    private Range<Integer> buildRangeFromNode(JsonNode op) {
        String name = op.path("op").asText();
        switch (name) {
            case "closed":
                return Range.closed(op.get("lower").asInt(), op.get("upper").asInt());
            case "open":
                return Range.open(op.get("lower").asInt(), op.get("upper").asInt());
            case "closed_open":
                return Range.closedOpen(op.get("lower").asInt(), op.get("upper").asInt());
            case "open_closed":
                return Range.openClosed(op.get("lower").asInt(), op.get("upper").asInt());
            case "at_least":
                return Range.atLeast(op.get("lower").asInt());
            case "greater_than":
                return Range.greaterThan(op.get("lower").asInt());
            case "at_most":
                return Range.atMost(op.get("upper").asInt());
            case "less_than":
                return Range.lessThan(op.get("upper").asInt());
            case "all":
                return Range.all();
            case "singleton":
                return Range.singleton(op.get("value").asInt());
            default:
                throw new IllegalArgumentException("unknown range op: " + name);
        }
    }

    private static String boundTypeStr(BoundType bt) {
        if (bt == BoundType.OPEN) {
            return "open";
        }
        if (bt == BoundType.CLOSED) {
            return "closed";
        }
        return "null";
    }

    private static String optIntStr(Integer v) {
        return v == null ? "null" : String.valueOf(v);
    }

    /**
     * Evaluate a single Range assertion key. Returns {@code null} for an
     * unrecognised key, which the shared {@code emit} reports as SKIP and fails
     * the scenario (no silent vacuous pass). Binary-op keys require an
     * {@code other} range; when it is absent they fall through to {@code null}.
     */
    private String evalRange(String key, Range<Integer> range, Range<Integer> other) {
        switch (key) {
            case "is_empty":
                return String.valueOf(range.isEmpty());
            case "has_lower_bound":
                return String.valueOf(range.hasLowerBound());
            case "has_upper_bound":
                return String.valueOf(range.hasUpperBound());
            case "lower_bound_type":
                return boundTypeStr(range.lowerBoundType());
            case "upper_bound_type":
                return boundTypeStr(range.upperBoundType());
            case "lower_endpoint":
                return optIntStr(range.lowerEndpoint());
            case "upper_endpoint":
                return optIntStr(range.upperEndpoint());
            default:
                break;
        }
        Matcher m = CONTAINS_N.matcher(key);
        if (m.matches()) {
            return String.valueOf(range.contains(Integer.parseInt(m.group(1))));
        }
        // Binary-op keys below require "other".
        if (other == null) {
            return null;
        }
        switch (key) {
            case "encloses_other":
                return String.valueOf(range.encloses(other));
            case "is_connected_other":
                return String.valueOf(range.isConnected(other));
            case "span_lower":
                return optIntStr(range.span(other).lowerEndpoint());
            case "span_upper":
                return optIntStr(range.span(other).upperEndpoint());
            case "span_lower_type":
                return boundTypeStr(range.span(other).lowerBoundType());
            case "span_upper_type":
                return boundTypeStr(range.span(other).upperBoundType());
            default:
                break;
        }
        // Intersection: empty Optional = disjoint; present (possibly cut-empty)
        // = abut/overlap. The disjoint-case fallback (spec §"Disjoint-case
        // fallback") is exactly what these null/false branches emit.
        Optional<Range<Integer>> inter = range.intersection(other);
        switch (key) {
            case "intersection_is_none":
                return String.valueOf(inter.isEmpty());
            case "intersection_is_empty":
                return String.valueOf(inter.isPresent() && inter.get().isEmpty());
            case "intersection_lower":
                return optIntStr(inter.map(Range::lowerEndpoint).orElse(null));
            case "intersection_upper":
                return optIntStr(inter.map(Range::upperEndpoint).orElse(null));
            case "intersection_lower_type":
                return boundTypeStr(inter.map(Range::lowerBoundType).orElse(null));
            case "intersection_upper_type":
                return boundTypeStr(inter.map(Range::upperBoundType).orElse(null));
            case "intersection_has_lower_bound":
                return String.valueOf(inter.isPresent() && inter.get().hasLowerBound());
            case "intersection_has_upper_bound":
                return String.valueOf(inter.isPresent() && inter.get().hasUpperBound());
            default:
                return null;
        }
    }

    // ---- ImmutableSortedMap<i32, i32> / ImmutableSortedSet<i32> -----------
    //
    // The compact immutable sorted table (spec features/sorted-table-map.md).
    // Built by EXACTLY ONE from_sorted op (zero or multiple -> SKIP, per the
    // cross-language authoring rules). Reuses the structural/lookup, navigable
    // (nav/range), and rank/select assertion keys.

    /**
     * Extract the single {@code from_sorted} op node from a sorted-table
     * scenario. SKIPs (does not fail) when there is not exactly one
     * {@code from_sorted} op (the README's malformed-scenario rule).
     */
    private static JsonNode requireSingleFromSorted(JsonNode scenario) {
        JsonNode ops = scenario.path("operations");
        JsonNode found = null;
        int count = 0;
        if (ops.isArray()) {
            for (JsonNode op : ops) {
                if ("from_sorted".equals(op.path("op").asText())) {
                    count++;
                    found = op;
                }
            }
        }
        if (count != 1) {
            throw new ScenarioSkipException(
                    "sorted-table scenario must have exactly one from_sorted op (found " + count + ")");
        }
        return found;
    }

    private static int[] intArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new int[0];
        }
        int[] out = new int[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = node.get(i).asInt();
        }
        return out;
    }

    private void runImmutableSortedMap(JsonNode scenario, ScenarioResult r) {
        JsonNode op = requireSingleFromSorted(scenario);
        int[] keys = intArray(op.get("keys"));
        int[] values = intArray(op.get("values"));
        ImmutableSortedMap<Integer, Integer> map = ImmutableSortedMap.fromSorted(keys, values);
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalImmutableSortedMap(key, map, query), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalImmutableSortedMap(String key, ImmutableSortedMap<Integer, Integer> map,
                                          Range<Integer> query) {
        switch (key) {
            case "size":
                return String.valueOf(map.size());
            case "is_empty":
                return String.valueOf(map.isEmpty());
            case "min":
            case "first_key":
                return optIntStr(map.firstKey().orElse(null));
            case "max":
            case "last_key":
                return optIntStr(map.lastKey().orElse(null));
            case "sorted_keys":
                return formatIntList(map.keys());
            case "sorted_values":
                // The harness's sorted_values is "all values, sorted ascending"
                // (README) -- the value multiset, NOT the key-order values()
                // pairing (which is a native-test obligation). Sort a copy.
                return formatIntArray(sortedAsc(map.values().stream().mapToInt(Integer::intValue).toArray()));
            case "descending_keys":
                return formatIntList(map.descendingKeys());
            case "range_keys":
                return query == null ? null : formatIntList(map.rangeKeys(query));
            case "range_keys_desc":
                return query == null ? null : formatIntList(map.descendingRangeKeys(query));
            case "range_size":
                return query == null ? null : String.valueOf(map.rangeKeys(query).size());
            default:
                break;
        }
        if (key.startsWith("get_")) {
            return optIntStr(map.get(Integer.parseInt(key.substring(4))).orElse(null));
        }
        if (key.startsWith("contains_")) {
            return String.valueOf(map.containsKey(Integer.parseInt(key.substring(9))));
        }
        Matcher rank = RANK_KEY.matcher(key);
        if (rank.matches()) {
            return String.valueOf(map.rank(Integer.parseInt(rank.group(1))));
        }
        Matcher sel = SELECT_KEY.matcher(key);
        if (sel.matches()) {
            return optIntStr(map.selectKey(Integer.parseInt(sel.group(1))).orElse(null));
        }
        Integer navArg = parseNavKey(key);
        if (navArg != null) {
            if (key.startsWith("floor_")) {
                return optIntStr(map.floorKey(navArg).orElse(null));
            }
            if (key.startsWith("ceiling_")) {
                return optIntStr(map.ceilingKey(navArg).orElse(null));
            }
            if (key.startsWith("lower_")) {
                return optIntStr(map.lowerKey(navArg).orElse(null));
            }
            if (key.startsWith("higher_")) {
                return optIntStr(map.higherKey(navArg).orElse(null));
            }
        }
        return null;
    }

    private void runImmutableSortedSet(JsonNode scenario, ScenarioResult r) {
        JsonNode op = requireSingleFromSorted(scenario);
        int[] elements = intArray(op.get("elements"));
        ImmutableSortedSet<Integer> set = ImmutableSortedSet.fromSorted(elements);
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            r.emit(key, evalImmutableSortedSet(key, set, query), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalImmutableSortedSet(String key, ImmutableSortedSet<Integer> set,
                                          Range<Integer> query) {
        switch (key) {
            case "size":
                return String.valueOf(set.size());
            case "is_empty":
                return String.valueOf(set.isEmpty());
            case "min":
            case "first":
                return optIntStr(set.first().orElse(null));
            case "max":
            case "last":
                return optIntStr(set.last().orElse(null));
            case "to_sorted_array":
                return formatIntList(set.elements());
            case "descending_elements":
                return formatIntList(set.descendingElements());
            case "range_elements":
                return query == null ? null : formatIntList(set.rangeElements(query));
            case "range_elements_desc":
                return query == null ? null : formatIntList(set.descendingRangeElements(query));
            case "range_size":
                return query == null ? null : String.valueOf(set.rangeElements(query).size());
            default:
                break;
        }
        if (key.startsWith("contains_")) {
            return String.valueOf(set.contains(Integer.parseInt(key.substring(9))));
        }
        Matcher rank = RANK_KEY.matcher(key);
        if (rank.matches()) {
            return String.valueOf(set.rank(Integer.parseInt(rank.group(1))));
        }
        Matcher sel = SELECT_KEY.matcher(key);
        if (sel.matches()) {
            return optIntStr(set.select(Integer.parseInt(sel.group(1))).orElse(null));
        }
        Integer navArg = parseNavKey(key);
        if (navArg != null) {
            if (key.startsWith("floor_")) {
                return optIntStr(set.floor(navArg).orElse(null));
            }
            if (key.startsWith("ceiling_")) {
                return optIntStr(set.ceiling(navArg).orElse(null));
            }
            if (key.startsWith("lower_")) {
                return optIntStr(set.lower(navArg).orElse(null));
            }
            if (key.startsWith("higher_")) {
                return optIntStr(set.higher(navArg).orElse(null));
            }
        }
        return null;
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
        System.out.println("(unsup column retained for layout; unknown collection kinds now SKIP per forward-compat)");
        System.out.println("unknown assertion keys skipped (forward-compat; a scenario with ZERO real "
                + "assertions still fails): " + skippedAssertions);
        System.out.println("scenarios run: " + scenariosRun + ", result: " + (anyFail ? "RED (failures present)" : "GREEN"));
    }

    // ---- HashPipeline (spec/features/hash-pipeline.md) --------------------
    //
    // A stateless probe (NOT a stored collection): exactly ONE hash op carries
    // the input + seed under test; the assertions read the deterministic hash
    // output. Hashes are emitted as fixed-width 0x-prefixed lowercase hex (8
    // digits for a u32, 16 for a u64) so a 64-bit hash survives the JSON 2^53
    // ceiling and every port's consensus diff is byte-identical. `positions` is
    // an int[] emitted in DERIVATION order (NOT sorted).

    /** Parse a 0x-prefixed hex `word` operand to a long (full u64 range). */
    private static long parseHexWord(JsonNode op)
    {
        String s = op.path("word").asText();
        if (!s.startsWith("0x") && !s.startsWith("0X"))
        {
            throw new ScenarioSkipException("hash-pipeline word must start with 0x: " + s);
        }
        // parseUnsignedLong accepts the full 64-bit range (never via double).
        return Long.parseUnsignedLong(s.substring(2), 16);
    }

    /**
     * Parse a `seed` operand: a DECIMAL STRING parsed straight to u64 via
     * {@link Long#parseUnsignedLong} (never narrowed through a double). A bare
     * JSON integer is also accepted for small seeds.
     */
    private static long parseSeed(JsonNode op)
    {
        JsonNode s = op.path("seed");
        if (s.isTextual())
        {
            return Long.parseUnsignedLong(s.asText());
        }
        if (s.isIntegralNumber())
        {
            return s.asLong();
        }
        throw new ScenarioSkipException("hash-pipeline seed must be a decimal string or integer");
    }

    /** Parse a 0x-prefixed hex `bytes` operand to a byte[]. */
    private static byte[] parseHexBytes(JsonNode op)
    {
        String s = op.path("bytes").asText();
        if (!s.startsWith("0x") && !s.startsWith("0X"))
        {
            throw new ScenarioSkipException("hash-pipeline bytes must start with 0x: " + s);
        }
        String body = s.substring(2);
        if ((body.length() & 1) != 0)
        {
            throw new ScenarioSkipException("hash-pipeline bytes must have an even hex-digit count: " + s);
        }
        byte[] out = new byte[body.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(body.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** The single hash op of a scenario (zero or multiple ops -> SKIP). */
    private static JsonNode requireSingleHashOp(JsonNode scenario)
    {
        JsonNode ops = scenario.path("operations");
        if (!ops.isArray() || ops.size() != 1)
        {
            throw new ScenarioSkipException(
                    "hash-pipeline scenario must have exactly one op (forward-compat): got "
                            + (ops.isArray() ? ops.size() : 0));
        }
        return ops.get(0);
    }

    private static String hex32(int h)
    {
        return String.format("0x%08x", h & 0xFFFFFFFFL);
    }

    private static String hex64(long h)
    {
        return "0x" + String.format("%016x", h);
    }

    private void runHashPipeline(JsonNode scenario, ScenarioResult r)
    {
        JsonNode op = requireSingleHashOp(scenario);
        String opName = op.path("op").asText();

        // A hash probe over a single op: compute the four possible widths lazily.
        int h32;
        long h64;
        int[] positions;
        switch (opName)
        {
            case "hash_word32":
            {
                long raw = parseHexWord(op);
                if (Long.compareUnsigned(raw, 0xFFFFFFFFL) > 0)
                {
                    throw new ScenarioSkipException("hash_word32 word exceeds 32 bits: " + op.path("word").asText());
                }
                long seed = parseSeed(op);
                h32 = Hash.hash32((int) raw, seed);
                h64 = 0L;
                positions = null;
                break;
            }
            case "hash_word64":
            {
                long word = parseHexWord(op);
                long seed = parseSeed(op);
                h64 = Hash.hash64(word, seed);
                h32 = 0;
                positions = null;
                break;
            }
            case "hash_i32":
            {
                int value = op.path("value").asInt();
                long seed = parseSeed(op);
                h32 = Hash.hash32Int32(value, seed);
                h64 = Hash.hash64Int32(value, seed);
                positions = null;
                break;
            }
            case "hash_bytes":
            {
                byte[] bytes = parseHexBytes(op);
                long seed = parseSeed(op);
                h32 = Hash.hash32Bytes(bytes, seed);
                h64 = Hash.hash64Bytes(bytes, seed);
                positions = null;
                break;
            }
            case "positions":
            {
                int value = op.path("value").asInt();
                int m = op.path("m").asInt();
                int k = op.path("k").asInt();
                // The i32 element drives positions via its little-endian 4-byte
                // form (the byte path the sketches use); no op-level seed (the
                // scheme fixes the internal seeds 0 and SALT2).
                byte[] bytes = new byte[] {
                        (byte) value,
                        (byte) (value >>> 8),
                        (byte) (value >>> 16),
                        (byte) (value >>> 24)
                };
                positions = Hash.positions(bytes, m, k);
                h32 = 0;
                h64 = 0L;
                break;
            }
            default:
                throw new ScenarioSkipException("unknown hash-pipeline op (forward-compat skip): " + opName);
        }

        boolean hash32Op = opName.equals("hash_word32") || opName.equals("hash_i32") || opName.equals("hash_bytes");
        boolean hash64Op = opName.equals("hash_word64") || opName.equals("hash_i32") || opName.equals("hash_bytes");
        boolean positionsOp = opName.equals("positions");

        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            String computed;
            switch (key)
            {
                case "hash32":
                    computed = hash32Op ? hex32(h32) : null;
                    break;
                case "hash64":
                    computed = hash64Op ? hex64(h64) : null;
                    break;
                case "hash64_hi":
                    computed = hash64Op ? hex32((int) (h64 >>> 32)) : null;
                    break;
                case "hash64_lo":
                    computed = hash64Op ? hex32((int) h64) : null;
                    break;
                case "positions":
                    computed = positionsOp ? formatIntArray(positions) : null;
                    break;
                default:
                    // Forward-compat: an assertion key this runner does not yet
                    // understand is SKIPPED (per the cross-language README), not
                    // a vacuous fail. Matches the unknown-op / unknown-kind SKIPs.
                    throw new ScenarioSkipException(
                            "unknown hash-pipeline assertion key (forward-compat skip): " + key);
            }
            r.emit(key, computed, e.getValue(), FloatMode.NONE);
        }
    }

    // ---- RoaringU32 (spec/features/roaring-u32.md) ------------------------
    //
    // A sparse, compressed u32 set. Values are i32 reinterpreted to u32 (NOT
    // sign-extended); ordering/min/max/serialized order are UNSIGNED u32
    // ascending. serialized_hex (+ the four set-algebra hex keys) is the byte
    // oracle, emitted as a lowercase 0x-prefixed hex string. container_types is
    // a string[] ("array"/"bitmap"). to_sorted_array is UNSIGNED-ascending,
    // emitted as i32. Malformed scenarios (reversed range, mixed deserialize,
    // bad-hex without 0x) SKIP.

    private static final Pattern ROARING_CONTAINS = Pattern.compile("^contains_(-?\\d+)$");

    private void runRoaringU32(JsonNode scenario, ScenarioResult r)
    {
        RoaringU32 set = buildRoaring(scenario.path("operations"));
        RoaringU32 other = scenario.has("other")
                ? buildRoaring(scenario.path("other").path("operations"))
                : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            r.emit(key, evalRoaring(key, set, other), e.getValue(), FloatMode.NONE);
        }
    }

    /**
     * Build a {@link RoaringU32} from a scenario op list. A {@code deserialize}
     * op must be the ONLY op (mixing with add/remove SKIPs); a reversed
     * {@code add_range}/{@code remove_range} (unsigned {@code from > to}) SKIPs;
     * a {@code deserialize} hex without a {@code 0x} prefix SKIPs.
     */
    private RoaringU32 buildRoaring(JsonNode ops)
    {
        // A lone deserialize op is read-back; mixing it with anything SKIPs.
        for (JsonNode op : ops)
        {
            if (op.path("op").asText().equals("deserialize"))
            {
                if (ops.size() != 1)
                {
                    throw new ScenarioSkipException(
                            "RoaringU32 deserialize op must be the only op");
                }
                String s = op.path("bytes").asText();
                if (!s.startsWith("0x") && !s.startsWith("0X"))
                {
                    throw new ScenarioSkipException(
                            "RoaringU32 deserialize bytes must start with 0x: " + s);
                }
                String body = s.substring(2);
                if ((body.length() & 1) != 0)
                {
                    throw new ScenarioSkipException(
                            "RoaringU32 deserialize bytes must have an even hex-digit count");
                }
                byte[] bytes = new byte[body.length() / 2];
                for (int i = 0; i < bytes.length; i++)
                {
                    bytes[i] = (byte) Integer.parseInt(body.substring(2 * i, 2 * i + 2), 16);
                }
                return RoaringU32.deserialize(bytes);
            }
        }
        RoaringU32 set = new RoaringU32();
        for (JsonNode op : ops)
        {
            switch (op.path("op").asText())
            {
                case "add":
                    set.add(op.get("value").asInt());
                    break;
                case "remove":
                    set.remove(op.get("value").asInt());
                    break;
                case "clear":
                    set.clear();
                    break;
                case "add_range":
                    roaringRange(set, op, true);
                    break;
                case "remove_range":
                    roaringRange(set, op, false);
                    break;
                default:
                    throw new ScenarioSkipException(
                            "unknown RoaringU32 op (forward-compat skip): " + op.path("op").asText());
            }
        }
        return set;
    }

    /**
     * Apply an inclusive {@code add_range}/{@code remove_range} over UNSIGNED
     * reinterpreted u32 endpoints. A reversed range (unsigned {@code from > to})
     * SKIPs the whole scenario (authoring rule, no wrap-around).
     */
    private void roaringRange(RoaringU32 set, JsonNode op, boolean add)
    {
        int from = op.get("from").asInt();
        int to = op.get("to").asInt();
        if (Integer.compareUnsigned(from, to) > 0)
        {
            throw new ScenarioSkipException("RoaringU32 reversed range (unsigned from > to)");
        }
        // Inclusive unsigned loop; `to` may be 0xFFFFFFFF so iterate then break.
        int v = from;
        while (true)
        {
            if (add)
            {
                set.add(v);
            }
            else
            {
                set.remove(v);
            }
            if (v == to)
            {
                break;
            }
            v++;
        }
    }

    private String evalRoaring(String key, RoaringU32 set, RoaringU32 other)
    {
        switch (key)
        {
            case "cardinality":
                return String.valueOf(set.cardinality());
            case "is_empty":
                return String.valueOf(set.isEmpty());
            case "chunk_count":
                return String.valueOf(set.chunkCount());
            case "container_types":
                return formatStringArray(set.containerTypes());
            case "to_sorted_array":
                return formatIntArray(set.toSortedArray());
            case "min":
                return set.min().isPresent() ? String.valueOf(set.min().getAsInt()) : "null";
            case "max":
                return set.max().isPresent() ? String.valueOf(set.max().getAsInt()) : "null";
            case "serialized_hex":
                return hexBytes(set.serialize());
            case "serialized_len":
                return String.valueOf(set.serialize().length);
            default:
                break;
        }
        Matcher cm = ROARING_CONTAINS.matcher(key);
        if (cm.matches())
        {
            return String.valueOf(set.contains(Integer.parseInt(cm.group(1))));
        }
        if (other == null)
        {
            return null;
        }
        switch (key)
        {
            case "union_serialized_hex":
                return hexBytes(set.or(other).serialize());
            case "union_cardinality":
                return String.valueOf(set.or(other).cardinality());
            case "intersect_serialized_hex":
                return hexBytes(set.and(other).serialize());
            case "intersect_cardinality":
                return String.valueOf(set.and(other).cardinality());
            case "and_not_serialized_hex":
                return hexBytes(set.andNot(other).serialize());
            case "and_not_cardinality":
                return String.valueOf(set.andNot(other).cardinality());
            case "xor_serialized_hex":
                return hexBytes(set.xor(other).serialize());
            case "xor_cardinality":
                return String.valueOf(set.xor(other).cardinality());
            default:
                return null;
        }
    }

    /** Lowercase 0x-prefixed hex of a byte image (the byte oracle format). */
    private static String hexBytes(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("0x");
        for (byte b : bytes)
        {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Render a string[] as a quoted JSON-style array (matches renderExpected). */
    private static String formatStringArray(String[] v)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append('"').append(v[i]).append('"');
        }
        return sb.append(']').toString();
    }
}

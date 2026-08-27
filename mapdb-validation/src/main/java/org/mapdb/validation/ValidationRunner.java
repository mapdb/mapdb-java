// Copyright (c) 2026 Jan Kotek.
// Internal cross-language validation runner for mapdb-java (the renamed
// Eclipse Collections fork). Routes scenarios through the fork's production
// collections under org.mapdb.collections and emits a red/green list.
package org.mapdb.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mapdb.collections.api.set.sorted.MutableSortedSet;
import org.mapdb.collections.api.multimap.list.MutableListMultimap;
import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
import org.mapdb.collections.api.map.sorted.MutableSortedMap;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.bounded.BoundedLruMap;
import org.mapdb.collections.impl.bounded.EvictionCause;
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
import org.mapdb.collections.impl.range.RangeMap;
import org.mapdb.collections.impl.range.RangeSet;
import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;
import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;
import org.mapdb.collections.impl.FenwickTree;
import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.Bloom;
import org.mapdb.collections.impl.HyperLogLog;
import org.mapdb.collections.impl.CountMin;
import org.mapdb.collections.impl.SpaceSaving;
import org.mapdb.collections.impl.tuple.Tuples;
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

        /**
         * Emit a range-object / range-object-array assertion (the RangeSet/
         * RangeMap {@code as_ranges} / {@code complement_ranges} /
         * {@code sub_range_set_ranges} / {@code as_map_of_ranges} /
         * {@code sub_range_map_entries} / {@code range_containing_<v>} /
         * {@code get_entry_<v>} keys). The standard {@link #emit}/
         * {@link #renderExpected} path is bypassed because the expected value is a
         * nested object (or array of objects): the runner builds {@code computed}
         * by hand in the fixed key order
         * ({@code lower, lower_type, upper, upper_type[, value]}) and compares it
         * against the COMPACT canonicalisation of the expected JSON
         * ({@link JsonNode#toString()}, which preserves source key order and
         * emits no whitespace) — the byte-for-byte oracle the Rust runner achieves
         * via serde_json's {@code to_string()}. A {@code null} computed (unknown
         * evaluator) is reported as SKIP <b>without failing the scenario</b>,
         * like {@link #emit}: an unknown key is forward-compat-skipped, and the
         * zero-evaluated-assertions guard in {@link #runScenario} still catches a
         * scenario that evaluates NO real assertion (so a JSON-only scenario whose
         * sole key is unknown does not pass vacuously).
         */
        void emitJson(String key, String computed, JsonNode expected) {
            if (computed == null) {
                System.out.println("SKIP " + name + " " + key + ": unknown assertion key (forward-compat skip)");
                skippedAssertions++;
                unknownSkipped++;
                return;
            }
            evaluated++;
            System.out.println(key + ": " + computed);
            // expected.toString() is Jackson's compact form (no spaces, source
            // key order), matching the hand-built computed string.
            String expectedStr = expected == null || expected.isNull() ? "null" : expected.toString();
            if (!computed.equals(expectedStr)) {
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
            case "RangeSet<i32>":
                runRangeSet(scenario, r);
                break;
            case "RangeMap<i32, i32>":
                runRangeMap(scenario, r);
                break;
            case "BoundedLruMap<i32, i32>":
                runBoundedLru(scenario, r);
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
            case "Bloom":
                runBloom(scenario, r);
                break;
            case "HyperLogLog":
                runHyperLogLog(scenario, r);
                break;
            case "CountMin":
                runCountMin(scenario, r);
                break;
            case "SpaceSaving":
                runSpaceSaving(scenario, r);
                break;
            case "FenwickTree":
                runFenwick(scenario, r);
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

    private static String formatLongArray(long[] v) {
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
        IntIntHashMap map;
        if ("bulkLoadExact".equals(scenario.path("construction").asText())) {
            IntArrayList keys = new IntArrayList();
            IntArrayList values = new IntArrayList();
            for (JsonNode op : scenario.path("operations")) {
                keys.add(op.get("key").asInt());
                values.add(op.get("value").asInt());
            }
            map = IntIntHashMap.bulkLoadExact(keys.size(), keys, values, Pump.DuplicatePolicy.ERROR);
        }
        else {
            map = new IntIntHashMap();
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
        MutableListMultimap<Long, Integer> mm;
        if ("fromSortedKeyValues".equals(scenario.path("construction").asText())) {
            mm = Pump.listMultimapFromSortedKeyValues(null, Comparator.<Integer>naturalOrder(), i64Pairs(scenario));
        }
        else {
            mm = new FastListMultimap<>();
            applyMultimapOps(scenario, mm::put, k -> mm.removeAll(k));
        }
        evalMultimap(scenario, r,
                mm.keysView().size(),
                () -> sortedI64Keys(mm.keysView()),
                k -> sortedIntValues(mm.get(k)),
                k -> mm.containsKey(k));
    }

    private void runI64SetMultimap(JsonNode scenario, ScenarioResult r) {
        MutableSetMultimap<Long, Integer> mm;
        if ("fromSortedKeyValues".equals(scenario.path("construction").asText())) {
            mm = Pump.setMultimapFromSortedKeyValues(null, Comparator.<Integer>naturalOrder(), i64Pairs(scenario));
        }
        else {
            mm = new UnifiedSetMultimap<>();
            applyMultimapOps(scenario, mm::put, k -> mm.removeAll(k));
        }
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

    private static List<Pair<Long, Integer>> i64Pairs(JsonNode scenario) {
        List<Pair<Long, Integer>> pairs = new ArrayList<>();
        for (JsonNode op : scenario.path("operations")) {
            pairs.add(Tuples.pair(I64Codec.parseOperand(op.get("key")), op.get("value").asInt()));
        }
        return pairs;
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
        if ("fromSorted".equals(scenario.path("construction").asText())) {
            List<Pair<Integer, Integer>> pairs = new ArrayList<>();
            for (JsonNode op : scenario.path("operations")) {
                pairs.add(Tuples.pair(op.get("key").asInt(), op.get("value").asInt()));
            }
            MutableSortedMap<Integer, Integer> pumped =
                    Pump.treeSortedMapFromSorted(null, pairs, Pump.DuplicatePolicy.ERROR);
            for (Map.Entry<Integer, Integer> entry : pumped.entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        else {
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

    // ---- RangeSet<i32> / RangeMap<i32, i32> -------------------------------
    //
    // The auto-coalescing RangeSet / piecewise RangeMap (spec/features/
    // range-set-map.md). Routed through the PRODUCTION boxed RangeSet<Integer>
    // / RangeMap<Integer, Integer> (the boxed Java carve-out).
    //
    // A RangeSet/RangeMap is a STATEFUL structure built by a sequence of
    // mutating ops, each carrying a range-builder object (the 10-range op
    // shape):
    //   RangeSet: {"op":"add","range":{...}} / {"op":"remove_range","range":{...}}
    //             / {"op":"clear"}
    //   RangeMap: {"op":"put","range":{...},"value":<i32>}
    //             / {"op":"remove_range","range":{...}} / {"op":"clear"}
    // An optional top-level "query" (same builder shape) supplies the range for
    // encloses_query / intersects_query / sub_range_set_ranges /
    // sub_range_map_entries. Unknown ops/keys/kinds SKIP (forward-compat).
    //
    // The as_ranges / complement_ranges / sub_range_set_ranges /
    // as_map_of_ranges / sub_range_map_entries arrays are EXPLICIT-ORDER
    // (ascending by lower cut) and the range objects are compared by compact
    // canonical JSON (see ScenarioResult.emitJson) — never sorted.

    /**
     * Parse a signed base-10 i32 suffix ({@code -} allowed, {@code +} rejected)
     * from a {@code <prefix><N>} key — the {@code contains_<v>} / {@code get_<v>}
     * / {@code range_containing_<v>} / {@code get_entry_<v>} convention. Returns
     * {@code null} when the key does not match (so the caller falls through).
     */
    private static Integer signedI32Suffix(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            return null;
        }
        String rest = key.substring(prefix.length());
        // Reject a leading '+' and any non-digit body (mirrors the Rust/Go
        // runners): rest is an optional '-' then base-10 digits only.
        if (!rest.matches("-?\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Serialise a {@code Range<Integer>} as the fixed-shape assertion object
     * {@code {"lower":..,"lower_type":..,"upper":..,"upper_type":..}} — endpoints
     * are the i32 value or {@code null} when unbounded, {@code *_type} is
     * {@code "open"}/{@code "closed"}/{@code null}. Key order matches the scenario
     * JSON so the compact comparison agrees byte-for-byte.
     */
    private static String rangeObjStr(Range<Integer> r) {
        return "{\"lower\":" + optIntJson(r.lowerEndpoint())
                + ",\"lower_type\":" + boundTypeJson(r.lowerBoundType())
                + ",\"upper\":" + optIntJson(r.upperEndpoint())
                + ",\"upper_type\":" + boundTypeJson(r.upperBoundType())
                + "}";
    }

    /**
     * Serialise a {@code (range, value)} RangeMap entry: the range object plus a
     * trailing {@code "value":<i32>}.
     */
    private static String entryObjStr(RangeMap.Entry<Integer, Integer> e) {
        Range<Integer> r = e.getRange();
        return "{\"lower\":" + optIntJson(r.lowerEndpoint())
                + ",\"lower_type\":" + boundTypeJson(r.lowerBoundType())
                + ",\"upper\":" + optIntJson(r.upperEndpoint())
                + ",\"upper_type\":" + boundTypeJson(r.upperBoundType())
                + ",\"value\":" + e.getValue()
                + "}";
    }

    private static String rangeArrayStr(List<Range<Integer>> ranges) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rangeObjStr(ranges.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String entryArrayStr(List<RangeMap.Entry<Integer, Integer>> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entryObjStr(entries.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String optIntJson(Integer v) {
        return v == null ? "null" : String.valueOf(v);
    }

    private static String boundTypeJson(BoundType bt) {
        if (bt == BoundType.OPEN) {
            return "\"open\"";
        }
        if (bt == BoundType.CLOSED) {
            return "\"closed\"";
        }
        return "null";
    }

    private void runRangeSet(JsonNode scenario, ScenarioResult r) {
        RangeSet<Integer> set = new RangeSet<>();
        JsonNode ops = scenario.path("operations");
        if (ops.isArray()) {
            for (JsonNode op : ops) {
                switch (op.path("op").asText()) {
                    case "add":
                        set.add(buildRangeFromNode(op.get("range")));
                        break;
                    case "remove_range":
                        set.remove(buildRangeFromNode(op.get("range")));
                        break;
                    case "clear":
                        set.clear();
                        break;
                    default:
                        throw new ScenarioSkipException(
                                "unknown RangeSet op (forward-compat skip): " + op.path("op").asText());
                }
            }
        }
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            JsonNode expected = e.getValue();
            // Object / array-of-object keys: compared via compact canonical JSON.
            switch (key) {
                case "as_ranges":
                    r.emitJson(key, rangeArrayStr(set.asRanges()), expected);
                    continue;
                case "complement_ranges":
                    r.emitJson(key, rangeArrayStr(set.complement().asRanges()), expected);
                    continue;
                case "sub_range_set_ranges":
                    if (query == null) {
                        r.emitJson(key, null, expected);
                    } else {
                        r.emitJson(key, rangeArrayStr(set.subRangeSet(query).asRanges()), expected);
                    }
                    continue;
                default:
                    break;
            }
            Integer rc = signedI32Suffix(key, "range_containing_");
            if (rc != null) {
                Optional<Range<Integer>> rng = set.rangeContaining(rc);
                r.emitJson(key, rng.map(ValidationRunner::rangeObjStr).orElse("null"), expected);
                continue;
            }
            // Scalar / bool keys: the standard emit path.
            r.emit(key, evalRangeSet(key, set, query), expected, FloatMode.NONE);
        }
    }

    /**
     * Evaluate a scalar/bool RangeSet assertion key. Returns {@code null} for an
     * unrecognised key (a loud SKIP that fails the scenario). Object-shaped keys
     * are handled in {@link #runRangeSet} via {@code emitJson}.
     */
    private String evalRangeSet(String key, RangeSet<Integer> set, Range<Integer> query) {
        switch (key) {
            case "is_empty":
                return String.valueOf(set.isEmpty());
            case "span_lower":
                return optIntStr(set.span().map(Range::lowerEndpoint).orElse(null));
            case "span_upper":
                return optIntStr(set.span().map(Range::upperEndpoint).orElse(null));
            case "span_lower_type":
                return boundTypeStr(set.span().map(Range::lowerBoundType).orElse(null));
            case "span_upper_type":
                return boundTypeStr(set.span().map(Range::upperBoundType).orElse(null));
            case "encloses_query":
                return query == null ? null : String.valueOf(set.encloses(query));
            case "intersects_query":
                return query == null ? null : String.valueOf(set.intersects(query));
            default:
                break;
        }
        Integer cv = signedI32Suffix(key, "contains_");
        if (cv != null) {
            return String.valueOf(set.contains(cv));
        }
        return null;
    }

    private void runRangeMap(JsonNode scenario, ScenarioResult r) {
        RangeMap<Integer, Integer> map = new RangeMap<>();
        JsonNode ops = scenario.path("operations");
        if (ops.isArray()) {
            for (JsonNode op : ops) {
                switch (op.path("op").asText()) {
                    case "put":
                        map.put(buildRangeFromNode(op.get("range")), op.get("value").asInt());
                        break;
                    case "remove_range":
                        map.remove(buildRangeFromNode(op.get("range")));
                        break;
                    case "clear":
                        map.clear();
                        break;
                    default:
                        throw new ScenarioSkipException(
                                "unknown RangeMap op (forward-compat skip): " + op.path("op").asText());
                }
            }
        }
        Range<Integer> query = scenario.has("query") ? buildRangeFromNode(scenario.get("query")) : null;
        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            JsonNode expected = e.getValue();
            switch (key) {
                case "as_map_of_ranges":
                    r.emitJson(key, entryArrayStr(map.asMapOfRanges()), expected);
                    continue;
                case "sub_range_map_entries":
                    if (query == null) {
                        r.emitJson(key, null, expected);
                    } else {
                        r.emitJson(key, entryArrayStr(map.subRangeMap(query).asMapOfRanges()), expected);
                    }
                    continue;
                default:
                    break;
            }
            Integer ge = signedI32Suffix(key, "get_entry_");
            if (ge != null) {
                Optional<RangeMap.Entry<Integer, Integer>> entry = map.getEntry(ge);
                r.emitJson(key, entry.map(ValidationRunner::entryObjStr).orElse("null"), expected);
                continue;
            }
            r.emit(key, evalRangeMap(key, map), expected, FloatMode.NONE);
        }
    }

    /**
     * Evaluate a scalar RangeMap assertion key. {@code get_<v>} returns the
     * mapped i32 or {@code null}. Object-shaped keys ({@code get_entry_<v>},
     * {@code as_map_of_ranges}, {@code sub_range_map_entries}) are handled in
     * {@link #runRangeMap}.
     */
    private String evalRangeMap(String key, RangeMap<Integer, Integer> map) {
        switch (key) {
            case "is_empty":
                return String.valueOf(map.isEmpty());
            case "span_lower":
                return optIntStr(map.span().map(Range::lowerEndpoint).orElse(null));
            case "span_upper":
                return optIntStr(map.span().map(Range::upperEndpoint).orElse(null));
            case "span_lower_type":
                return boundTypeStr(map.span().map(Range::lowerBoundType).orElse(null));
            case "span_upper_type":
                return boundTypeStr(map.span().map(Range::upperBoundType).orElse(null));
            default:
                break;
        }
        // get_entry_<v> is handled in runRangeMap; here only get_<v>.
        Integer gv = signedI32Suffix(key, "get_");
        if (gv != null && !key.startsWith("get_entry_")) {
            return optIntStr(map.get(gv).orElse(null));
        }
        return null;
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
                            // Fenwick `tree` is an i64 decimal-string array in JSON;
                            // the runner emits a bare-decimal array. Unquote to match.
                            if ("tree".equals(key))
                            {
                                parts.add(el.asText());
                            }
                            else
                            {
                                parts.add("\"" + el.asText() + "\"");
                            }
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

    // ---- HyperLogLog (spec/features/hyperloglog.md) -----------------------
    //
    // A stored cardinality sketch. The cross-language oracle is the INTEGER
    // register array (register_hex / nonzero_registers / max_register /
    // register_at_N) — NEVER the float estimate (float-quarantine Rule Q1; there
    // is deliberately NO `estimate` assertion key). Exactly one builder op,
    // first: either with_precision(p) (then zero or more add/merge) OR a single
    // from_bytes. Zero/two builders or an add before the builder => malformed =>
    // SKIP. A merge consumes the scenario's `other` HyperLogLog. Unknown
    // ops/keys/kinds SKIP (forward-compat).

    /**
     * Build a HyperLogLog from an op list (used for the primary and the `other`
     * block). A malformed op list (not starting with exactly one builder, an
     * add/merge before the builder, an out-of-range with_precision, or a bad
     * from_bytes) raises {@link ScenarioSkipException} -> the scenario SKIPs.
     */
    private HyperLogLog buildHll(JsonNode operations, JsonNode other)
    {
        if (!operations.isArray() || operations.size() == 0)
        {
            throw new ScenarioSkipException("HyperLogLog scenario must have at least one op (forward-compat)");
        }
        JsonNode first = operations.get(0);
        String firstOp = first.path("op").asText();
        HyperLogLog hll;
        try
        {
            switch (firstOp)
            {
                case "with_precision":
                    hll = HyperLogLog.withPrecision(first.path("p").asInt());
                    break;
                case "from_bytes":
                    // from_bytes is the SOLE op when present (full state replacement).
                    if (operations.size() != 1)
                    {
                        throw new ScenarioSkipException("from_bytes must be the only op (forward-compat)");
                    }
                    hll = HyperLogLog.fromBytes(parseHexBytes(first));
                    break;
                default:
                    throw new ScenarioSkipException(
                            "HyperLogLog first op must be a builder (forward-compat): " + firstOp);
            }
        }
        catch (IllegalArgumentException e)
        {
            // Out-of-range p / bad from_bytes => the harness cannot build the
            // probe => SKIP. Native tests pin the error path itself.
            throw new ScenarioSkipException("HyperLogLog builder error (forward-compat skip): " + e.getMessage());
        }
        for (int i = 1; i < operations.size(); i++)
        {
            JsonNode op = operations.get(i);
            switch (op.path("op").asText())
            {
                case "add":
                    hll.add(op.get("value").asInt());
                    break;
                case "merge":
                {
                    if (other == null)
                    {
                        throw new ScenarioSkipException("HyperLogLog merge with no `other` block (forward-compat)");
                    }
                    HyperLogLog otherHll = buildHll(other.path("operations"), null);
                    try
                    {
                        hll.merge(otherHll);
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw new ScenarioSkipException("HyperLogLog merge error (forward-compat skip): " + e.getMessage());
                    }
                    break;
                }
                default:
                    throw new ScenarioSkipException(
                            "unknown HyperLogLog op (forward-compat skip): " + op.path("op").asText());
            }
        }
        return hll;
    }

    private void runHyperLogLog(JsonNode scenario, ScenarioResult r)
    {
        JsonNode other = scenario.has("other") ? scenario.get("other") : null;
        HyperLogLog hll = buildHll(scenario.path("operations"), other);
        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            // Unknown HLL assertion key (incl. out-of-range register_at_N) ->
            // evalHll returns null; emit() prints the visible forward-compat SKIP
            // line and does NOT increment `evaluated` (so an all-unknown scenario
            // is still caught by the vacuous-pass guard). A KNOWN key increments
            // `evaluated` and is compared. This mirrors the emit() convention used
            // by every other runner in this file (do not short-circuit on null).
            r.emit(key, evalHll(key, hll), e.getValue(), FloatMode.NONE);
        }
    }

    /**
     * Evaluate a single HyperLogLog assertion key. The PRIMARY oracle is
     * register_hex (the full serialized form as a lowercase 0x-prefixed hex
     * string; registers are unsigned bytes). NO `estimate` key (float-quarantine
     * Q1). Returns {@code null} for an unknown key or an out-of-range
     * register_at_N suffix, which the caller skips silently (forward-compat).
     */
    private String evalHll(String key, HyperLogLog hll)
    {
        switch (key)
        {
            case "register_hex":
            {
                byte[] bytes = hll.toBytes();
                StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
                sb.append("0x");
                for (byte b : bytes)
                {
                    sb.append(String.format("%02x", b & 0xFF));
                }
                return sb.toString();
            }
            case "nonzero_registers":
                return String.valueOf(hll.nonzeroRegisters());
            case "max_register":
                return String.valueOf(hll.maxRegister());
            default:
                break;
        }
        if (key.startsWith("register_at_"))
        {
            // Parse the index suffix as an unsigned i32/u32 with range-check;
            // out-of-range or non-numeric -> unknown -> skip (return null).
            String suffix = key.substring("register_at_".length());
            long n;
            try
            {
                n = Long.parseLong(suffix);
            }
            catch (NumberFormatException ex)
            {
                return null;
            }
            if (n < 0 || n >= hll.registerCount())
            {
                return null;
            }
            return String.valueOf(hll.registers()[(int) n] & 0xFF);
        }
        return null;
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

    // ---- Bloom (spec/features/bloom.md) -----------------------------------
    //
    // A new collection kind: "collection": "Bloom". Exactly ONE with_params op
    // builds the filter (zero/multiple -> SKIP, the from_sorted/HashPipeline
    // rule); subsequent ops are `add`. A union scenario carries a second filter
    // in the top-level "other" block. Bytes are emitted as a lower-case
    // 0x-prefixed hex string (byte 0 first, bytes formatted unsigned); set_bits
    // are sorted ascending. contains_<v>/union_contains_<v> parse a signed i32
    // suffix. Unknown ops/keys/kinds SKIP (forward-compat); m=0/range/union
    // mismatch guard to SKIP.

    private static final Pattern BLOOM_CONTAINS = Pattern.compile("^contains_(-?[0-9]+)$");
    private static final Pattern BLOOM_UNION_CONTAINS = Pattern.compile("^union_contains_(-?[0-9]+)$");

    /**
     * Strictly read an integral JSON field as a signed i32. A missing,
     * non-integral, or out-of-i32-range value is malformed for the Java port and
     * SKIPs (rather than silently coercing via {@link JsonNode#asInt()}).
     */
    private static int bloomI32Field(JsonNode op, String field)
    {
        JsonNode n = op.get(field);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt())
        {
            throw new ScenarioSkipException(
                    "Bloom " + field + " must be an i32 integer (forward-compat skip)");
        }
        return n.asInt();
    }

    /**
     * Strictly read an integral JSON field as a {@code u32} parameter ({@code m}
     * or {@code k}): the spec's {@code with_params(m_bits: u32, k: u32)} domain is
     * the full {@code 0 ..= 2^32-1}, so the field is read as a {@code long} and
     * validated against that range (NOT narrowed to a signed {@code int}, which
     * would SKIP a legitimate value {@code > Integer.MAX_VALUE}). A missing,
     * non-integral, or out-of-{@code u32}-range value is malformed and SKIPs.
     */
    private static long bloomU32Field(JsonNode op, String field)
    {
        JsonNode n = op.get(field);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong())
        {
            throw new ScenarioSkipException(
                    "Bloom " + field + " must be an integer (forward-compat skip)");
        }
        long v = n.asLong();
        if (v < 0L || v > 0xFFFF_FFFFL)
        {
            throw new ScenarioSkipException(
                    "Bloom " + field + " out of u32 range (forward-compat skip): " + v);
        }
        return v;
    }

    /** Strictly parse a signed-i32 assertion-key suffix, SKIPping if out of range. */
    private static int bloomI32Suffix(String suffix)
    {
        try
        {
            return Integer.parseInt(suffix);
        }
        catch (NumberFormatException ex)
        {
            throw new ScenarioSkipException(
                    "Bloom contains suffix out of i32 range (forward-compat skip): " + suffix);
        }
    }

    /**
     * Build a Bloom filter from an operations array: exactly one {@code
     * with_params} op (else malformed -> SKIP), then {@code add} ops. Unknown
     * ops SKIP (forward-compat). Guards {@code m = 0} / out-of-range -> SKIP.
     */
    private Bloom buildBloom(JsonNode ops)
    {
        if (ops == null || !ops.isArray())
        {
            throw new ScenarioSkipException("Bloom scenario must have an operations array");
        }
        int withParamsCount = 0;
        for (JsonNode op : ops)
        {
            if (op.path("op").asText().equals("with_params"))
            {
                withParamsCount++;
            }
        }
        if (withParamsCount != 1)
        {
            throw new ScenarioSkipException(
                    "Bloom scenario must have exactly one with_params op (forward-compat): got "
                            + withParamsCount);
        }
        Bloom bloom = null;
        for (JsonNode op : ops)
        {
            String name = op.path("op").asText();
            switch (name)
            {
                case "with_params":
                {
                    // m/k are u32 (the spec's with_params domain); parse as long
                    // so a value > Integer.MAX_VALUE is accepted, not SKIPped.
                    long m = bloomU32Field(op, "m");
                    long k = bloomU32Field(op, "k");
                    try
                    {
                        // m=0/negative or negative k -> withParams throws; guard
                        // it to SKIP (malformed for the Java subset) rather than
                        // letting it FAIL the scenario.
                        bloom = Bloom.withParams(m, k);
                    }
                    catch (IllegalArgumentException ex)
                    {
                        throw new ScenarioSkipException("Bloom with_params invalid: " + ex.getMessage());
                    }
                    break;
                }
                case "add":
                    if (bloom == null)
                    {
                        throw new ScenarioSkipException("Bloom add before with_params");
                    }
                    bloom.add(bloomI32Field(op, "value"));
                    break;
                default:
                    // Unknown op -> SKIP (forward-compat).
                    throw new ScenarioSkipException("unknown Bloom op (forward-compat skip): " + name);
            }
        }
        return bloom;
    }

    private void runBloom(JsonNode scenario, ScenarioResult r)
    {
        Bloom bloom = buildBloom(scenario.path("operations"));
        Bloom other = scenario.has("other")
                ? buildBloom(scenario.path("other").path("operations"))
                : null;
        Bloom union = null;
        if (other != null)
        {
            try
            {
                union = bloom.union(other);
            }
            catch (IllegalArgumentException ex)
            {
                // Mismatched (m, k) -> guard to SKIP (the shared suite only
                // asserts a matching union; a mismatch is malformed here).
                throw new ScenarioSkipException("Bloom union param mismatch: " + ex.getMessage());
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            r.emit(key, evalBloom(key, bloom, union), e.getValue(), FloatMode.NONE);
        }
    }

    /**
     * Render a byte array as a lower-case {@code 0x}-prefixed hex string, byte 0
     * first, two hex digits per byte (formatted unsigned).
     */
    private static String bloomHex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private String evalBloom(String key, Bloom bloom, Bloom union)
    {
        switch (key)
        {
            case "m_bits":
                // mBits() now returns the unsigned u32 as a non-negative long
                // (Integer.toUnsignedLong applied inside); emit it directly so a
                // (hypothetical) large-u32 scenario matches.
                return String.valueOf(bloom.mBits());
            case "k":
                return String.valueOf(Integer.toUnsignedLong(bloom.k()));
            case "bit_count":
                // bitCount() is a long (u32-correct, non-negative); decimal.
                return String.valueOf(bloom.bitCount());
            case "is_empty":
                return String.valueOf(bloom.isEmpty());
            case "set_bits":
                // setBits() are non-negative long u32 indices; decimal each.
                return formatLongArray(bloom.setBits());
            case "bytes":
                return bloomHex(bloom.toBytes());
            default:
                break;
        }
        Matcher cm = BLOOM_CONTAINS.matcher(key);
        if (cm.matches())
        {
            return String.valueOf(bloom.mightContain(bloomI32Suffix(cm.group(1))));
        }
        // Union keys require "other"; absent -> null (SKIP via emit).
        if (union == null)
        {
            return null;
        }
        switch (key)
        {
            case "union_bit_count":
                return String.valueOf(union.bitCount());
            case "union_set_bits":
                return formatLongArray(union.setBits());
            case "union_bytes":
                return bloomHex(union.toBytes());
            default:
                break;
        }
        Matcher um = BLOOM_UNION_CONTAINS.matcher(key);
        if (um.matches())
        {
            return String.valueOf(union.mightContain(bloomI32Suffix(um.group(1))));
        }
        return null;
    }

    // ---- CountMin (spec/features/count-min.md) ----------------------------
    //
    // A d x w integer counter matrix riding the hash pipeline. Counts are u64
    // carried in Java long (unsigned bits), serialized via Long.toUnsignedString
    // (a saturated counter is u64::MAX = 18446744073709551615 > 2^53). Exactly
    // ONE with_params op builds the sketch (no optimal() in scenarios — the
    // float trap is native-only); subsequent ops are add. `counters` is an
    // explicit-order (row-major) array of decimal strings; estimate_<v>/total
    // are derived decimal-string cross-checks.

    private static final Pattern ESTIMATE_KEY = Pattern.compile("^estimate_(-?[0-9]+)$");
    private static final Pattern COUNT_KEY = Pattern.compile("^count_(-?[0-9]+)$");
    private static final Pattern ERROR_KEY = Pattern.compile("^error_(-?[0-9]+)$");
    private static final Pattern TOP_K_KEY = Pattern.compile("^top_k_([0-9]+)$");

    /**
     * Parse a u64 {@code count} field (decimal string; omitted -&gt; 1). A value
     * that does not parse as {@code 0 ..= u64::MAX} (negative, non-numeric, or
     * exceeding {@code u64::MAX}) is malformed -&gt; SKIP (the same wide-integer
     * discipline as the i64-key suite; parsed straight to u64, never via a
     * double). Shared by CountMin and SpaceSaving.
     */
    private static long parseU64Count(JsonNode op)
    {
        JsonNode c = op.get("count");
        if (c == null || c.isNull())
        {
            return 1L; // add_one shape
        }
        try
        {
            if (c.isTextual())
            {
                return Long.parseUnsignedLong(c.asText());
            }
            if (c.isIntegralNumber())
            {
                // A bare JSON integer: parse the DECIMAL TEXT straight to u64
                // (never via asLong(), which truncates a node outside the signed
                // long range). parseUnsignedLong rejects negative and >u64::MAX.
                return Long.parseUnsignedLong(c.asText());
            }
        }
        catch (NumberFormatException ex)
        {
            throw new ScenarioSkipException("malformed u64 count (negative / >u64::MAX / non-numeric): " + c);
        }
        throw new ScenarioSkipException("count must be a decimal string or non-negative integer: " + c);
    }

    /**
     * Parse the signed i32 suffix of an assertion key (e.g. {@code estimate_<v>},
     * {@code count_<v>}, {@code error_<v>}) with a full i32 range-check. An
     * out-of-range suffix is treated as an unknown key (SKIP via {@code null}),
     * NOT wrapped.
     */
    private static Integer parseI32Suffix(String s)
    {
        try
        {
            long v = Long.parseLong(s);
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
            {
                return null; // out of i32 range -> unknown key
            }
            return (int) v;
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    private void runCountMin(JsonNode scenario, ScenarioResult r)
    {
        JsonNode ops = scenario.path("operations");
        // Exactly one with_params op builds the sketch (zero or multiple -> SKIP).
        JsonNode params = null;
        int paramCount = 0;
        if (ops.isArray())
        {
            for (JsonNode op : ops)
            {
                if ("with_params".equals(op.path("op").asText()))
                {
                    paramCount++;
                    params = op;
                }
            }
        }
        if (paramCount != 1)
        {
            throw new ScenarioSkipException(
                    "CountMin scenario must have exactly one with_params op (found " + paramCount + ")");
        }
        CountMin cms = CountMin.withParams(params.get("d").asInt(), params.get("w").asInt());
        for (JsonNode op : ops)
        {
            String opName = op.path("op").asText();
            switch (opName)
            {
                case "with_params":
                    break;
                case "add":
                    cms.add(op.get("value").asInt(), parseU64Count(op));
                    break;
                default:
                    // Unknown op -> SKIP (forward-compat).
                    throw new ScenarioSkipException("unknown CountMin op (forward-compat skip): " + opName);
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            r.emit(key, evalCountMin(key, cms), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalCountMin(String key, CountMin cms)
    {
        switch (key)
        {
            case "depth":
                return String.valueOf(cms.depth());
            case "width":
                return String.valueOf(cms.width());
            case "total":
                return Long.toUnsignedString(cms.total());
            case "counters":
                return formatU64Array(cms.toCounters());
            default:
                break;
        }
        Matcher est = ESTIMATE_KEY.matcher(key);
        if (est.matches())
        {
            Integer v = parseI32Suffix(est.group(1));
            // Out-of-i32-range suffix -> unknown key -> SKIP (NOT wrap, NOT fail).
            if (v == null)
            {
                throw new ScenarioSkipException("estimate suffix out of i32 range (unknown key skip): " + key);
            }
            return Long.toUnsignedString(cms.estimate(v));
        }
        // Unknown assertion key -> SKIP (forward-compat).
        throw new ScenarioSkipException("unknown CountMin assertion key (forward-compat skip): " + key);
    }

    /** A u64[] as a JSON array of quoted decimal strings (Long.toUnsignedString). */
    private static String formatU64Array(long[] v)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append('"').append(Long.toUnsignedString(v[i])).append('"');
        }
        return sb.append(']').toString();
    }

    // ---- SpaceSaving (spec/features/count-min.md) -------------------------
    //
    // A bounded heavy-hitters / top-k summary. count/error are u64 in long
    // (unsigned), serialized via Long.toUnsignedString. monitored_set / top_k
    // are NESTED arrays of [item, "count", "error"] triples in CANONICAL order
    // (count DESC unsigned, signed item ASC) — an explicit-order projection, NOT
    // runner-sorted. Exactly ONE with_capacity op builds the summary; adds are
    // applied IN LISTED ORDER (Space-Saving is order-dependent).

    private void runSpaceSaving(JsonNode scenario, ScenarioResult r)
    {
        JsonNode ops = scenario.path("operations");
        JsonNode cap = null;
        int capCount = 0;
        if (ops.isArray())
        {
            for (JsonNode op : ops)
            {
                if ("with_capacity".equals(op.path("op").asText()))
                {
                    capCount++;
                    cap = op;
                }
            }
        }
        if (capCount != 1)
        {
            throw new ScenarioSkipException(
                    "SpaceSaving scenario must have exactly one with_capacity op (found " + capCount + ")");
        }
        SpaceSaving ss = SpaceSaving.withCapacity(cap.get("m").asInt());
        for (JsonNode op : ops)
        {
            String opName = op.path("op").asText();
            switch (opName)
            {
                case "with_capacity":
                    break;
                case "add":
                    // Adds are applied in array order (the order is contractual).
                    ss.add(op.get("value").asInt(), parseU64Count(op));
                    break;
                default:
                    throw new ScenarioSkipException("unknown SpaceSaving op (forward-compat skip): " + opName);
            }
        }
        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            // monitored_set / top_k_<k> are nested-array projections; compare the
            // rendered nested form directly (renderExpected handles only flat
            // arrays). Everything else routes through the standard emit.
            if (key.equals("monitored_set"))
            {
                emitNestedTriples(r, key, formatTriples(ss.monitoredSet()), e.getValue());
                continue;
            }
            Matcher tk = TOP_K_KEY.matcher(key);
            if (tk.matches())
            {
                emitNestedTriples(r, key, formatTriples(ss.topK(Integer.parseInt(tk.group(1)))), e.getValue());
                continue;
            }
            r.emit(key, evalSpaceSaving(key, ss), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalSpaceSaving(String key, SpaceSaving ss)
    {
        switch (key)
        {
            case "size":
                return String.valueOf(ss.size());
            case "capacity":
                return String.valueOf(ss.capacity());
            default:
                break;
        }
        Matcher cnt = COUNT_KEY.matcher(key);
        if (cnt.matches())
        {
            Integer v = parseI32Suffix(cnt.group(1));
            // Out-of-i32-range suffix -> unknown key -> SKIP (NOT wrap, NOT fail).
            if (v == null)
            {
                throw new ScenarioSkipException("count suffix out of i32 range (unknown key skip): " + key);
            }
            return Long.toUnsignedString(ss.count(v));
        }
        Matcher err = ERROR_KEY.matcher(key);
        if (err.matches())
        {
            Integer v = parseI32Suffix(err.group(1));
            if (v == null)
            {
                throw new ScenarioSkipException("error suffix out of i32 range (unknown key skip): " + key);
            }
            return Long.toUnsignedString(ss.error(v));
        }
        // Unknown assertion key -> SKIP (forward-compat).
        throw new ScenarioSkipException("unknown SpaceSaving assertion key (forward-compat skip): " + key);
    }

    /**
     * Render a list of {@code (item, count, error)} triples as a nested JSON
     * array {@code [[item,"count","error"],…]} (item a bare int; count/error
     * quoted u64 decimal strings), in the list's canonical order.
     */
    private static String formatTriples(List<SpaceSaving.SSEntry> entries)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            SpaceSaving.SSEntry en = entries.get(i);
            sb.append('[').append(en.item)
                    .append(",\"").append(Long.toUnsignedString(en.count)).append('"')
                    .append(",\"").append(Long.toUnsignedString(en.error)).append('"')
                    .append(']');
        }
        return sb.append(']').toString();
    }

    /**
     * Render the expected nested-triples JSON array and compare to the computed
     * form, printing and failing through the same channels as
     * {@link ScenarioResult#emit}. The expected JSON elements are inner arrays
     * {@code [item, "count", "error"]} (item bare int, count/error quoted
     * strings).
     */
    private void emitNestedTriples(ScenarioResult r, String key, String computed, JsonNode expected)
    {
        System.out.println(key + ": " + computed);
        String expectedStr = renderNestedTriples(expected);
        if (!computed.equals(expectedStr))
        {
            System.out.println("FAIL " + r.name + " " + key + ": expected=" + expectedStr + " got=" + computed);
            r.failed = true;
        }
    }

    private static String renderNestedTriples(JsonNode expected)
    {
        if (expected == null || !expected.isArray())
        {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean firstOuter = true;
        for (JsonNode triple : expected)
        {
            if (!firstOuter)
            {
                sb.append(',');
            }
            firstOuter = false;
            sb.append('[');
            // [item, "count", "error"]: item bare int; count/error quoted strings.
            int item = triple.get(0).asInt();
            String count = triple.get(1).asText();
            String error = triple.get(2).asText();
            sb.append(item).append(",\"").append(count).append("\",\"").append(error).append('"').append(']');
        }
        return sb.append(']').toString();
    }

    // ---- FenwickTree (spec/features/fenwick.md) ---------------------------
    //
    // A fixed-size int-element / long-accumulator Binary Indexed Tree.
    // Construction is EXACTLY ONE op (`with_size` or `from_values`) FIRST, then
    // any number of `update`/`set` point ops (all in-range; out-of-range traps
    // are native-test-only). Sum-returning assertions (`total`, `get_<i>`,
    // `prefix_sum_<i>`, `range_sum_<lo>_<hi>`, and each `tree` element) are i64
    // and wire-encoded as DECIMAL STRINGS (Long.toString -- signed). The `tree`
    // assertion is the canonical 1-based BIT array in 1-based index order (an
    // explicit-order key, NOT sorted), each element a BARE signed decimal (the
    // rust/go/ts/zig reference wire form `[v1,v2,...]`). renderExpected unquotes
    // textual `tree` elements so they match that bare-decimal form.
    // Unknown ops / kinds / assertion keys SKIP (forward-compat).

    private static final Pattern FENWICK_GET_KEY = Pattern.compile("^get_([0-9]+)$");
    private static final Pattern FENWICK_PREFIX_KEY = Pattern.compile("^prefix_sum_([0-9]+)$");
    private static final Pattern FENWICK_RANGE_KEY = Pattern.compile("^range_sum_([0-9]+)_([0-9]+)$");

    private void runFenwick(JsonNode scenario, ScenarioResult r)
    {
        JsonNode operations = scenario.path("operations");
        if (!operations.isArray() || operations.size() == 0)
        {
            throw new ScenarioSkipException(
                    "fenwick scenario must begin with a construction op (forward-compat skip)");
        }

        JsonNode firstOp = operations.get(0);
        String first = firstOp.path("op").asText("");
        FenwickTree tree;
        switch (first)
        {
            case "with_size":
            {
                long requested = I64Codec.parseOperand(firstOp.path("n"));
                if (requested < 0 || requested > Integer.MAX_VALUE)
                {
                    throw new ScenarioSkipException(
                            "fenwick with_size out-of-range n (malformed): " + requested);
                }
                tree = FenwickTree.withSize((int) requested);
                break;
            }
            case "from_values":
            {
                JsonNode vals = firstOp.path("values");
                if (!vals.isArray())
                {
                    throw new ScenarioSkipException("fenwick from_values needs a values array");
                }
                int[] arr = new int[vals.size()];
                for (int i = 0; i < arr.length; i++)
                {
                    arr[i] = vals.get(i).asInt();
                }
                tree = FenwickTree.fromValues(arr);
                break;
            }
            default:
                throw new ScenarioSkipException(
                        "fenwick first op must be with_size/from_values (forward-compat skip): " + first);
        }

        for (int k = 1; k < operations.size(); k++)
        {
            JsonNode op = operations.get(k);
            String opName = op.path("op").asText("");
            switch (opName)
            {
                case "update":
                    tree.update(op.path("index").asInt(), op.path("delta").asInt());
                    break;
                case "set":
                    tree.set(op.path("index").asInt(), op.path("value").asInt());
                    break;
                case "with_size":
                case "from_values":
                    throw new ScenarioSkipException(
                            "fenwick has a non-first construction op (malformed)");
                default:
                    throw new ScenarioSkipException(
                            "unknown fenwick op (forward-compat skip): " + opName);
            }
        }

        for (Map.Entry<String, JsonNode> e : assertions(scenario))
        {
            String key = e.getKey();
            if (skipKey(key))
            {
                continue;
            }
            r.emit(key, evalFenwick(key, tree), e.getValue(), FloatMode.NONE);
        }
    }

    private String evalFenwick(String key, FenwickTree tree)
    {
        switch (key)
        {
            case "size":
                return String.valueOf(tree.size());
            case "is_empty":
                return String.valueOf(tree.isEmpty());
            case "total":
                return Long.toString(tree.total());
            case "tree":
            {
                long[] canon = tree.canonicalTree();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < canon.length; i++)
                {
                    if (i > 0)
                    {
                        sb.append(',');
                    }
                    sb.append(Long.toString(canon[i]));
                }
                return sb.append(']').toString();
            }
            default:
        }
        Matcher m = FENWICK_GET_KEY.matcher(key);
        if (m.matches())
        {
            return Long.toString(tree.get(parseFenwickIndex(key, m.group(1))));
        }
        m = FENWICK_PREFIX_KEY.matcher(key);
        if (m.matches())
        {
            return Long.toString(tree.prefixSum(parseFenwickIndex(key, m.group(1))));
        }
        m = FENWICK_RANGE_KEY.matcher(key);
        if (m.matches())
        {
            int lo = parseFenwickIndex(key, m.group(1));
            int hi = parseFenwickIndex(key, m.group(2));
            return Long.toString(tree.rangeSum(lo, hi));
        }
        throw new ScenarioSkipException(
                "unknown fenwick assertion key (forward-compat skip): " + key);
    }

    private static int parseFenwickIndex(String key, String s)
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException ex)
        {
            throw new ScenarioSkipException(
                    "fenwick assertion index out of i32 range (forward-compat skip): " + key);
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

    private RoaringU32 buildRoaring(JsonNode ops)
    {
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

    private void roaringRange(RoaringU32 set, JsonNode op, boolean add)
    {
        int from = op.get("from").asInt();
        int to = op.get("to").asInt();
        if (Integer.compareUnsigned(from, to) > 0)
        {
            throw new ScenarioSkipException("RoaringU32 reversed range (unsigned from > to)");
        }
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

    // ---- BoundedLruMap<i32, i32> (spec/features/bounded-lru.md) -----------

    private static long parseTick(JsonNode v) {
        if (v.isTextual()) {
            return Long.parseUnsignedLong(v.asText());
        }
        if (v.isIntegralNumber()) {
            return Long.parseUnsignedLong(v.asText());
        }
        throw new ScenarioSkipException("bounded-lru tick must be a decimal string or integer");
    }

    private static final class LruLog {
        final List<Integer> putResults = new ArrayList<>();
        final List<Integer> getResults = new ArrayList<>();
        final List<Integer> getOrDefaultResults = new ArrayList<>();
        final List<Boolean> containsResults = new ArrayList<>();
        final List<Integer> removeResults = new ArrayList<>();
        final List<Integer> expiredCounts = new ArrayList<>();
        final List<List<Integer>> snapshotKeysLog = new ArrayList<>();
        final List<List<Integer>> snapshotValuesLog = new ArrayList<>();
        final List<List<int[]>> snapshotEntriesLog = new ArrayList<>();
    }

    private void runBoundedLru(JsonNode scenario, ScenarioResult r) {
        JsonNode maxSizeNode = scenario.get("max_size");
        if (maxSizeNode == null || !maxSizeNode.isInt() && !maxSizeNode.canConvertToInt()) {
            throw new ScenarioSkipException("BoundedLruMap scenario needs a non-negative max_size");
        }
        int maxSize = maxSizeNode.asInt();
        if (maxSize < 0) {
            throw new ScenarioSkipException("BoundedLruMap scenario needs a non-negative max_size");
        }

        JsonNode ttlNode = scenario.get("ttl");
        Long ttl = (ttlNode == null || ttlNode.isNull()) ? null : parseTick(ttlNode);

        List<int[]> evictLog = new ArrayList<>();
        BoundedLruMap.Builder<Integer, Integer> builder =
                BoundedLruMap.<Integer, Integer>builder().maxSize(maxSize);
        if (ttl != null) {
            builder = builder.ttl(ttl);
        }
        BoundedLruMap<Integer, Integer> map = builder
                .onEvict((k, v, cause) ->
                        evictLog.add(new int[] {k, v, cause == EvictionCause.EXPIRED ? 1 : 0}))
                .build();

        LruLog log = new LruLog();

        for (JsonNode op : scenario.path("operations")) {
            switch (op.path("op").asText()) {
                case "put": {
                    int k = op.path("key").asInt();
                    int v = op.path("value").asInt();
                    JsonNode nowNode = op.get("now");
                    Optional<Integer> prev = (nowNode != null && !nowNode.isNull())
                            ? map.putAt(k, v, parseTick(nowNode))
                            : map.put(k, v);
                    log.putResults.add(prev.orElse(null));
                    break;
                }
                case "put_at": {
                    int k = op.path("key").asInt();
                    int v = op.path("value").asInt();
                    long now = parseTick(op.path("now"));
                    log.putResults.add(map.putAt(k, v, now).orElse(null));
                    break;
                }
                case "get": {
                    int k = op.path("key").asInt();
                    log.getResults.add(map.get(k).orElse(null));
                    break;
                }
                case "get_or_default": {
                    int k = op.path("key").asInt();
                    int d = op.path("default").asInt();
                    log.getOrDefaultResults.add(map.getOrDefault(k, d));
                    break;
                }
                case "contains_key": {
                    int k = op.path("key").asInt();
                    log.containsResults.add(map.containsKey(k));
                    break;
                }
                case "remove": {
                    int k = op.path("key").asInt();
                    log.removeResults.add(map.remove(k).orElse(null));
                    break;
                }
                case "clear":
                    map.clear();
                    break;
                case "expire_entries": {
                    long now = parseTick(op.path("now"));
                    log.expiredCounts.add(map.expireEntries(now));
                    break;
                }
                case "snapshot_keys":
                    log.snapshotKeysLog.add(new ArrayList<>(map.keys()));
                    break;
                case "snapshot_values":
                    log.snapshotValuesLog.add(new ArrayList<>(map.values()));
                    break;
                case "snapshot_entries": {
                    List<int[]> pairs = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> en : map.entries()) {
                        pairs.add(new int[] {en.getKey(), en.getValue()});
                    }
                    log.snapshotEntriesLog.add(pairs);
                    break;
                }
                default:
                    break;
            }
        }

        for (Map.Entry<String, JsonNode> e : assertions(scenario)) {
            String key = e.getKey();
            if (skipKey(key)) {
                continue;
            }
            evalLruAssertion(key, e.getValue(), map, log, evictLog, r);
        }
    }

    private void evalLruAssertion(String key, JsonNode expected,
            BoundedLruMap<Integer, Integer> map, LruLog log, List<int[]> evictLog,
            ScenarioResult r) {
        switch (key) {
            case "size":
                r.emit(key, String.valueOf(map.size()), expected, FloatMode.NONE);
                return;
            case "is_empty":
                r.emit(key, String.valueOf(map.isEmpty()), expected, FloatMode.NONE);
                return;
            case "lru_order_keys":
                r.emit(key, formatIntList(map.keys()), expected, FloatMode.NONE);
                return;
            case "lru_order_values":
                r.emit(key, formatIntList(map.values()), expected, FloatMode.NONE);
                return;
            case "eviction_log":
                r.emitJson(key, formatEvictionLog(evictLog), expected);
                return;
            case "put_results":
                r.emit(key, formatNullableIntList(log.putResults), expected, FloatMode.NONE);
                return;
            case "get_results":
                r.emit(key, formatNullableIntList(log.getResults), expected, FloatMode.NONE);
                return;
            case "get_or_default_results":
                r.emit(key, formatIntList(log.getOrDefaultResults), expected, FloatMode.NONE);
                return;
            case "contains_results":
                r.emit(key, formatBoolList(log.containsResults), expected, FloatMode.NONE);
                return;
            case "remove_results":
                r.emit(key, formatNullableIntList(log.removeResults), expected, FloatMode.NONE);
                return;
            case "expired_counts":
                r.emit(key, formatIntList(log.expiredCounts), expected, FloatMode.NONE);
                return;
            case "snapshot_keys_log":
                r.emitJson(key, formatArrayOfIntLists(log.snapshotKeysLog), expected);
                return;
            case "snapshot_values_log":
                r.emitJson(key, formatArrayOfIntLists(log.snapshotValuesLog), expected);
                return;
            case "snapshot_entries_log":
                r.emitJson(key, formatArrayOfPairArrays(log.snapshotEntriesLog), expected);
                return;
            default:
                break;
        }
        if (key.startsWith("get_")) {
            int k = Integer.parseInt(key.substring(4));
            Integer found = null;
            for (Map.Entry<Integer, Integer> en : map.entries()) {
                if (en.getKey() == k) {
                    found = en.getValue();
                    break;
                }
            }
            r.emit(key, found == null ? "null" : String.valueOf(found), expected, FloatMode.NONE);
            return;
        }
        if (key.startsWith("contains_")) {
            int k = Integer.parseInt(key.substring(9));
            r.emit(key, String.valueOf(map.containsKey(k)), expected, FloatMode.NONE);
            return;
        }
        throw new ScenarioSkipException(
                "unknown bounded-lru assertion key (forward-compat skip): " + key);
    }

    private static String formatBoolList(List<Boolean> v) {
        return "[" + v.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private static String formatEvictionLog(List<int[]> evictLog) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < evictLog.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            int[] t = evictLog.get(i);
            sb.append('[').append(t[0]).append(',').append(t[1]).append(',')
              .append(t[2] == 1 ? "\"expired\"" : "\"size\"").append(']');
        }
        return sb.append(']').toString();
    }

    private static String formatArrayOfIntLists(List<List<Integer>> v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatIntList(v.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String formatArrayOfPairArrays(List<List<int[]>> v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            List<int[]> inner = v.get(i);
            sb.append('[');
            for (int j = 0; j < inner.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                int[] p = inner.get(j);
                sb.append('[').append(p[0]).append(',').append(p[1]).append(']');
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }
}

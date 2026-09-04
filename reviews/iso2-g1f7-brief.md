# CONSTRAINT — READ FIRST

This is a **read-only review**. Do NOT modify, create, or delete ANY file
anywhere on this machine except your single output file
`/tmp/iso2-g1f7-review2.md`. In particular do NOT touch
`/home/play2/mapdb/mapdb-collection-spec` (the spec repo), and do NOT add
scenarios, edit READMEs, or run any build/test that writes into a repo.
Read the code, reason, and write your findings to the output file only.
Propose changes as text in the review; never apply them.

# Brief: review fix for iso2 finding G1-F7 (mapdb-java)

You are reviewing a small change in the repo `/home/play2/mapdb/mapdb-java`
(Java fork of Eclipse Collections, packages `org.mapdb.collections.*`).
Write your answer to `/tmp/iso2-g1f7-review2.md` and to no other file.

## Background

A 298-scenario cross-language conformance suite lives at
`/home/play2/mapdb/mapdb-collection-spec/cross-language-validation`
(scenarios/, validate.sh, and a guard `check-runners.sh` driven by
`runners.json`). Rule: a runner MUST obtain every assertion value by calling
the production method the assertion names -- no std/builtin oracle, no
"compute it another way and copy the answer in".

Finding G1-F7: the Java runner
`mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java`,
function `runIntTreeMap`, in its `fromSorted` construction branch, called
`Pump.treeSortedMapFromSorted(...)` and then copied the result entry-by-entry
into a freshly constructed `NavigableTreeMap` via one-by-one `put`. So scenario
`scenarios/17-bulk-load/treemap_i32_from_sorted.json` -- whose comment says
"order statistics after a fromSorted bulk load must match one-by-one inserts.
Regression guard: a bulk builder that omits the subtree-size augmentation
leaves rank/select broken" -- was in fact asserting on one-by-one inserts. The
bulk builder was never the object under test. Go/Rust/TS/Zig runners assert on
the pumped tree itself; Go found a real TreeMap.fromSorted bug that way.

## What the pump actually builds (investigated)

- `org.mapdb.collections.impl.Pump.treeSortedMapFromSorted(comparator,
  Iterable<Pair<K,V>>, DuplicatePolicy)` returns a `MutableSortedMap` --
  concretely an EC `TreeSortedMap`, which wraps a `java.util.TreeMap`. The pump
  validates strict ascending order under the given comparator (throws
  `Pump.PumpSourceNotSorted`), applies the duplicate policy (`ERROR` ->
  `Pump.PumpSourceDuplicate`; `IGNORE` -> first of an equal run wins), and then
  hands a `SortedMap` view to the JDK `TreeMap(SortedMap)` constructor, which
  does the O(n) bottom-up `buildFromSorted`. This delegation is the documented
  Java "tree carve-out" (spec/features/data-pump.md; spec/style/java.md).
- BUT: the rank/select + navigation surface the scenario asserts on lives on a
  DIFFERENT type, `org.mapdb.collections.impl.navigable.NavigableTreeMap`
  (spec/style/java.md "NavigableMap / NavigableSet -- boxed-tree navigation
  carve-out"). It owns a `java.util.TreeMap` navigation view plus the EC
  `TreeSortedMap`, kept in sync. Its `rank`/`selectKey`/`selectEntry` are an
  O(n) ordered scan -- Java has NO subtree-size augmentation at all (documented
  complexity relaxation in spec/style/java.md; results are identical).
- So before this change there was NO bulk builder in production that yields a
  rank/select-capable navigable map. That is the real finding behind G1-F7: the
  runner's copy-out was papering over a missing production API.

## The fix

1. New production API `NavigableTreeMap.fromSorted(Iterable<Pair<K,V>>)` and
   `NavigableTreeMap.fromSorted(Comparator, Iterable<Pair<K,V>>,
   Pump.DuplicatePolicy)`:

   ```java
   MutableSortedMap<K, V> ecMap = Pump.treeSortedMapFromSorted(comparator, input, policy);
   // TreeMap(SortedMap) adopts the source comparator and uses the O(n)
   // bottom-up buildFromSorted -- no per-entry put/rebalance.
   TreeMap<K, V> nav = new TreeMap<>(ecMap);
   return new NavigableTreeMap<>(nav, ecMap);
   ```

   (`MutableSortedMap extends java.util.SortedMap`, so both the pump result and
   the navigation view go through the JDK bulk constructors and share one
   comparator; the pumped map instance itself becomes the wrapper's backing EC
   store -- it is not copied.)

2. Symmetric `NavigableTreeSet.fromSorted(...)` over
   `Pump.treeSortedSetFromSorted` + `new TreeSet<>(ecSet)`
   (`MutableSortedSet extends NavigableSet`). The runner's `runIntTreeSet` has
   NO fromSorted branch today (no such scenario), so this half is API symmetry,
   not a runner fix.

3. Runner `runIntTreeMap` now does, in the fromSorted branch:
   `map = NavigableTreeMap.fromSorted(null, pairs, Pump.DuplicatePolicy.ERROR);`
   and the incremental branch initialises `map = NavigableTreeMap.newMap()`.
   The unused `MutableSortedMap` import was removed. No entry is copied.

4. New native tests `mapdb-native-tests/src/test/java/org/mapdb/nativetests/
   NavigableFromSortedTest.java` (11 tests): the scenario's own data with all
   its rank/select assertions on the bulk-built map; randomized bulk-vs-
   put-by-put equivalence (40 trials, n<=59: size, sorted keys, descending
   keys, rank over a probe grid, floor/ceiling/lower/higher, selectKey for
   i in [-1, n]); empty input; duplicate under ERROR -> PumpSourceDuplicate;
   IGNORE keeps first; unsorted input -> PumpSourceNotSorted; comparator
   preservation in BOTH stores (reverse comparator); both backing stores live
   and in sync under later put/remove; set analogues incl. add-by-add
   equivalence.

## Results

- `mvn clean install -DskipTests -Dcheckstyle.skip=true -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils -am` -- BUILD SUCCESS
- `mvn -f mapdb-native-tests/pom.xml test` -- Tests run: 615, Failures: 0, Errors: 0 (was 604 before; +11 new)
- `mvn -f mapdb-validation/pom.xml clean package` -- BUILD SUCCESS
- BEFORE: `check-runners.sh` reported
  `FAIL java: 25 ok / 1 bad -- TreeMap<i32, i32> | java | FORBIDDEN oracle in runIntTreeMap: fromSorted result copied entry-by-entry into a freshly constructed NavigableTreeMap`
- AFTER: `./check-runners.sh --root /home/play2/mapdb` -> PASS java 26 checked (all five ports PASS).
- AFTER: `./validate.sh --skip-go --skip-rust --skip-ts --skip-zig` -> Scenarios: 306, Pass: 306, Fail: 0; java 306 pass / 0 fail. (The suite grew from 298 to 306 while this work was in flight, from a concurrent workstream; the three new `17-bulk-load/treemap_i32_from_sorted_range_{closed,half_open,open}.json` scenarios combine `fromSorted` with a range query and therefore exercise exactly the bulk-built map this change introduces -- they pass.) (No production bug was uncovered by driving the pumped tree -- expected, since Java's rank/select is an ordered scan over the tree, so there is no separate augmentation to get out of step.)

## The exact diff

diff --git a/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeMap.java b/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeMap.java
index 9a8fc145..c356dc23 100644
--- a/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeMap.java
+++ b/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeMap.java
@@ -15,6 +15,8 @@ import java.util.Optional;
 import java.util.TreeMap;
 
 import org.mapdb.collections.api.map.sorted.MutableSortedMap;
+import org.mapdb.collections.api.tuple.Pair;
+import org.mapdb.collections.impl.Pump;
 import org.mapdb.collections.impl.map.sorted.mutable.TreeSortedMap;
 import org.mapdb.collections.impl.range.Range;
 
@@ -93,6 +95,49 @@ public final class NavigableTreeMap<K extends Comparable<? super K>, V>
         return new NavigableTreeMap<>(nav, ecMap);
     }
 
+    /**
+     * Bulk build (data pump) from input already sorted ascending by key under
+     * natural order, rejecting duplicates — see {@code features/data-pump.md}.
+     * Equivalent to {@link #fromSorted(Comparator, Iterable, Pump.DuplicatePolicy)}
+     * with a {@code null} comparator and {@link Pump.DuplicatePolicy#ERROR}.
+     */
+    public static <K extends Comparable<? super K>, V> NavigableTreeMap<K, V> fromSorted(
+            Iterable<Pair<K, V>> input)
+    {
+        return fromSorted(null, input, Pump.DuplicatePolicy.ERROR);
+    }
+
+    /**
+     * Bulk build (data pump) from input already sorted ascending by key under
+     * {@code comparator} ({@code null} = natural order).
+     *
+     * <p>The pump ({@link Pump#treeSortedMapFromSorted(Comparator, Iterable,
+     * Pump.DuplicatePolicy)}) validates order and duplicates and builds the
+     * backing EC {@link TreeSortedMap} in one pass; the navigation view is then
+     * built from that sorted map through the JDK {@code TreeMap(SortedMap)}
+     * bulk constructor (O(n) {@code buildFromSorted}), sharing the same
+     * comparator. No entry is inserted one-by-one.
+     *
+     * <p>Out-of-order input throws {@link Pump.PumpSourceNotSorted}; a duplicate
+     * key under {@link Pump.DuplicatePolicy#ERROR} throws
+     * {@link Pump.PumpSourceDuplicate} ({@link Pump.DuplicatePolicy#IGNORE}
+     * keeps the first of an equal run). Nothing half-built is returned.
+     *
+     * <p>Order statistics ({@link #rank} / {@link #selectKey} /
+     * {@link #selectEntry}) on the result are exactly those of the same entries
+     * inserted one-by-one — the Java carve-out computes them by ordered scan,
+     * so there is no separate subtree-size augmentation to keep in step.
+     */
+    public static <K extends Comparable<? super K>, V> NavigableTreeMap<K, V> fromSorted(
+            Comparator<? super K> comparator, Iterable<Pair<K, V>> input, Pump.DuplicatePolicy policy)
+    {
+        MutableSortedMap<K, V> ecMap = Pump.treeSortedMapFromSorted(comparator, input, policy);
+        // TreeMap(SortedMap) adopts the source comparator and uses the O(n)
+        // bottom-up buildFromSorted -- no per-entry put/rebalance.
+        TreeMap<K, V> nav = new TreeMap<>(ecMap);
+        return new NavigableTreeMap<>(nav, ecMap);
+    }
+
     /** The comparator ({@code null} for natural order). */
     public Comparator<? super K> comparator()
     {
diff --git a/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeSet.java b/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeSet.java
index 3037140a..ab585b81 100644
--- a/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeSet.java
+++ b/eclipse-collections/src/main/java/org/mapdb/collections/impl/navigable/NavigableTreeSet.java
@@ -13,6 +13,7 @@ import java.util.Optional;
 import java.util.TreeSet;
 
 import org.mapdb.collections.api.set.sorted.MutableSortedSet;
+import org.mapdb.collections.impl.Pump;
 import org.mapdb.collections.impl.range.Range;
 import org.mapdb.collections.impl.set.sorted.mutable.TreeSortedSet;
 
@@ -65,6 +66,41 @@ public final class NavigableTreeSet<T extends Comparable<? super T>>
         return new NavigableTreeSet<>(nav, ecSet);
     }
 
+    /**
+     * Bulk build (data pump) from input already sorted ascending under natural
+     * order, rejecting duplicates — the element analogue of
+     * {@link NavigableTreeMap#fromSorted(Iterable)}.
+     */
+    public static <T extends Comparable<? super T>> NavigableTreeSet<T> fromSorted(Iterable<T> input)
+    {
+        return fromSorted(null, input, Pump.DuplicatePolicy.ERROR);
+    }
+
+    /**
+     * Bulk build (data pump) from input already sorted ascending under
+     * {@code comparator} ({@code null} = natural order).
+     *
+     * <p>The pump ({@link Pump#treeSortedSetFromSorted(Comparator, Iterable,
+     * Pump.DuplicatePolicy)}) validates order and duplicates and builds the
+     * backing EC {@link TreeSortedSet} in one pass; the navigation view is then
+     * built from that sorted set through the JDK {@code TreeSet(SortedSet)}
+     * bulk constructor (O(n) {@code buildFromSorted}), sharing the same
+     * comparator. No element is added one-by-one.
+     *
+     * <p>Out-of-order input throws {@link Pump.PumpSourceNotSorted}; a duplicate
+     * under {@link Pump.DuplicatePolicy#ERROR} throws
+     * {@link Pump.PumpSourceDuplicate}.
+     */
+    public static <T extends Comparable<? super T>> NavigableTreeSet<T> fromSorted(
+            Comparator<? super T> comparator, Iterable<T> input, Pump.DuplicatePolicy policy)
+    {
+        MutableSortedSet<T> ecSet = Pump.treeSortedSetFromSorted(comparator, input, policy);
+        // TreeSet(SortedSet) adopts the source comparator and uses the O(n)
+        // bottom-up build -- no per-element add/rebalance.
+        TreeSet<T> nav = new TreeSet<>(ecSet);
+        return new NavigableTreeSet<>(nav, ecSet);
+    }
+
     /** The comparator ({@code null} for natural order). */
     public Comparator<? super T> comparator()
     {
diff --git a/mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java b/mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java
index 0a1858fa..950faa78 100644
--- a/mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java
+++ b/mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java
@@ -10,7 +10,6 @@ import com.fasterxml.jackson.databind.ObjectMapper;
 import org.mapdb.collections.api.set.sorted.MutableSortedSet;
 import org.mapdb.collections.api.multimap.list.MutableListMultimap;
 import org.mapdb.collections.api.multimap.set.MutableSetMultimap;
-import org.mapdb.collections.api.map.sorted.MutableSortedMap;
 import org.mapdb.collections.api.tuple.Pair;
 import org.mapdb.collections.impl.Pump;
 import org.mapdb.collections.impl.bounded.BoundedLruMap;
@@ -1095,24 +1094,23 @@ public final class ValidationRunner {
     // ---- TreeMap<i32, i32> (NavigableTreeMap<Integer,Integer> over the boxed tree)
 
     private void runIntTreeMap(JsonNode scenario, ScenarioResult r) {
-        NavigableTreeMap<Integer, Integer> map = NavigableTreeMap.newMap();
+        NavigableTreeMap<Integer, Integer> map;
         List<Integer> pollFirstKeys = new ArrayList<>();
         List<Integer> pollFirstValues = new ArrayList<>();
         List<Integer> pollLastKeys = new ArrayList<>();
         List<Integer> pollLastValues = new ArrayList<>();
         List<Integer> removeRangeCounts = new ArrayList<>();
         if ("fromSorted".equals(scenario.path("construction").asText())) {
+            // The bulk-built map IS the object under test: no entry is copied
+            // out of the pump result into a put-by-put map (finding G1-F7).
             List<Pair<Integer, Integer>> pairs = new ArrayList<>();
             for (JsonNode op : scenario.path("operations")) {
                 pairs.add(Tuples.pair(op.get("key").asInt(), op.get("value").asInt()));
             }
-            MutableSortedMap<Integer, Integer> pumped =
-                    Pump.treeSortedMapFromSorted(null, pairs, Pump.DuplicatePolicy.ERROR);
-            for (Map.Entry<Integer, Integer> entry : pumped.entrySet()) {
-                map.put(entry.getKey(), entry.getValue());
-            }
+            map = NavigableTreeMap.fromSorted(null, pairs, Pump.DuplicatePolicy.ERROR);
         }
         else {
+            map = NavigableTreeMap.newMap();
             for (JsonNode op : scenario.path("operations")) {
                 switch (op.path("op").asText()) {
                     case "put":

### New file: mapdb-native-tests/src/test/java/org/mapdb/nativetests/NavigableFromSortedTest.java
```java
// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.api.tuple.Pair;
import org.mapdb.collections.impl.Pump;
import org.mapdb.collections.impl.navigable.NavigableTreeMap;
import org.mapdb.collections.impl.navigable.NavigableTreeSet;
import org.mapdb.collections.impl.range.Range;
import org.mapdb.collections.impl.tuple.Tuples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the data-pump bulk builders on the boxed-tree navigation
 * wrappers: {@link NavigableTreeMap#fromSorted} / {@link NavigableTreeSet#fromSorted}
 * (spec {@code features/data-pump.md} + {@code style/java.md} navigation
 * carve-out).
 *
 * <p>These exist because the cross-language scenario
 * {@code 17-bulk-load/treemap_i32_from_sorted} asserts rank/select on a
 * <em>bulk-built</em> tree: before finding G1-F7 there was no bulk builder that
 * produced a rank/select-capable navigable map at all, so the validation runner
 * copied the pump result entry-by-entry into a put-by-put map and the scenario's
 * stated "bulk builder that omits the order-statistics augmentation" regression
 * guard was inert. The obligations pinned here are: bulk-built == put-by-put
 * (order statistics included), duplicate policy, empty input, unsorted
 * rejection, comparator preservation, and that both backing stores (EC tree +
 * navigation view) are live and in sync afterwards.
 */
public class NavigableFromSortedTest
{
    private static List<Pair<Integer, Integer>> pairs(int... keys)
    {
        List<Pair<Integer, Integer>> out = new ArrayList<>();
        for (int k : keys)
        {
            out.add(Tuples.pair(k, k * 10));
        }
        return out;
    }

    private static NavigableTreeMap<Integer, Integer> putByPut(List<Pair<Integer, Integer>> input)
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        for (Pair<Integer, Integer> p : input)
        {
            m.put(p.getOne(), p.getTwo());
        }
        return m;
    }

    // ---- map: the scenario's own data, bulk-built --------------------------

    @Test
    public void mapFromSortedMatchesScenarioRankSelect()
    {
        List<Pair<Integer, Integer>> input = Arrays.asList(
                Tuples.pair(-10, 100),
                Tuples.pair(0, 0),
                Tuples.pair(5, 50),
                Tuples.pair(20, 200),
                Tuples.pair(21, 210));
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(input);

        assertEquals(5, m.size());
        assertFalse(m.isEmpty());
        assertEquals(Integer.valueOf(-10), m.firstKey());
        assertEquals(Integer.valueOf(21), m.lastKey());
        assertEquals(Integer.valueOf(100), m.get(-10));
        assertEquals(Integer.valueOf(200), m.get(20));
        assertTrue(m.containsKey(5));
        assertFalse(m.containsKey(99));
        assertEquals(Arrays.asList(-10, 0, 5, 20, 21), m.rangeKeys(Range.all()));

        // Order statistics on the bulk-built tree (the scenario's guard).
        assertEquals(0, m.rank(-10));
        assertEquals(2, m.rank(5));
        assertEquals(4, m.rank(21));
        assertEquals(5, m.rank(22));
        assertEquals(Integer.valueOf(-10), m.selectKey(0).orElse(null));
        assertEquals(Integer.valueOf(5), m.selectKey(2).orElse(null));
        assertEquals(Integer.valueOf(21), m.selectKey(4).orElse(null));
        assertFalse(m.selectKey(5).isPresent());
        assertFalse(m.selectKey(-1).isPresent());
        assertEquals(Integer.valueOf(50), m.selectEntry(2).map(e -> e.getValue()).orElse(null));
    }

    @Test
    public void mapFromSortedEqualsPutByPutIncludingRankSelect()
    {
        Random rnd = new Random(20260904L);
        for (int trial = 0; trial < 40; trial++)
        {
            int n = rnd.nextInt(60);
            java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
            while (keys.size() < n)
            {
                keys.add(rnd.nextInt(2001) - 1000);
            }
            List<Pair<Integer, Integer>> input = new ArrayList<>();
            for (int k : keys)
            {
                input.add(Tuples.pair(k, k * 10));
            }
            NavigableTreeMap<Integer, Integer> bulk = NavigableTreeMap.fromSorted(input);
            NavigableTreeMap<Integer, Integer> ref = putByPut(input);

            assertEquals(ref.size(), bulk.size());
            assertEquals(ref.rangeKeys(Range.all()), bulk.rangeKeys(Range.all()));
            assertEquals(ref.descendingKeys(), bulk.descendingKeys());
            for (int probe = -1002; probe <= 1002; probe += 7)
            {
                assertEquals(ref.rank(probe), bulk.rank(probe), "rank " + probe);
                assertEquals(ref.floorKey(probe), bulk.floorKey(probe));
                assertEquals(ref.ceilingKey(probe), bulk.ceilingKey(probe));
                assertEquals(ref.lowerKey(probe), bulk.lowerKey(probe));
                assertEquals(ref.higherKey(probe), bulk.higherKey(probe));
            }
            for (int i = -1; i <= n; i++)
            {
                assertEquals(ref.selectKey(i), bulk.selectKey(i), "select " + i);
            }
        }
    }

    // ---- map: empty / duplicates / unsorted --------------------------------

    @Test
    public void mapFromSortedEmpty()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(new ArrayList<>());
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertNull(m.firstKey());
        assertNull(m.lastKey());
        assertEquals(0, m.rank(0));
        assertFalse(m.selectKey(0).isPresent());
        assertTrue(m.ecMap().isEmpty());
        // still usable afterwards
        m.put(7, 70);
        assertEquals(1, m.size());
        assertEquals(1, m.ecMap().size());
    }

    @Test
    public void mapFromSortedRejectsDuplicateUnderErrorPolicy()
    {
        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> NavigableTreeMap.fromSorted(pairs(1, 2, 2, 3)));
    }

    @Test
    public void mapFromSortedIgnorePolicyKeepsFirst()
    {
        List<Pair<Integer, Integer>> input = Arrays.asList(
                Tuples.pair(1, 10),
                Tuples.pair(2, 20),
                Tuples.pair(2, 999),
                Tuples.pair(3, 30));
        NavigableTreeMap<Integer, Integer> m =
                NavigableTreeMap.fromSorted(null, input, Pump.DuplicatePolicy.IGNORE);
        assertEquals(3, m.size());
        assertEquals(Integer.valueOf(20), m.get(2));
        assertEquals(1, m.rank(2));
        assertEquals(Integer.valueOf(3), m.selectKey(2).orElse(null));
    }

    @Test
    public void mapFromSortedRejectsUnsortedInput()
    {
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeMap.fromSorted(pairs(1, 5, 3)));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeMap.fromSorted(pairs(3, 2, 1)));
    }

    // ---- map: comparator preservation + both stores live -------------------

    @Test
    public void mapFromSortedPreservesComparatorInBothStores()
    {
        Comparator<Integer> reverse = Comparator.reverseOrder();
        // ascending under the reverse comparator == descending naturally
        NavigableTreeMap<Integer, Integer> m =
                NavigableTreeMap.fromSorted(reverse, pairs(9, 5, 1), Pump.DuplicatePolicy.ERROR);
        assertEquals(reverse, m.comparator());
        assertEquals(reverse, m.ecMap().comparator());
        assertEquals(Arrays.asList(9, 5, 1), m.rangeKeys(Range.all()));
        assertEquals(Integer.valueOf(9), m.firstKey());
        assertEquals(Integer.valueOf(1), m.lastKey());
        // rank/select follow the map's own comparator
        assertEquals(0, m.rank(9));
        assertEquals(2, m.rank(1));
        assertEquals(Integer.valueOf(5), m.selectKey(1).orElse(null));
    }

    @Test
    public void mapFromSortedKeepsBackingStoresInSyncOnLaterMutation()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.fromSorted(pairs(1, 2, 3));
        assertEquals(3, m.ecMap().size());
        assertEquals(Integer.valueOf(20), m.ecMap().get(2));
        m.put(4, 40);
        m.remove(1);
        assertEquals(3, m.size());
        assertEquals(3, m.ecMap().size());
        assertEquals(Arrays.asList(2, 3, 4), m.rangeKeys(Range.all()));
        assertFalse(m.ecMap().containsKey(1));
        assertTrue(m.ecMap().containsKey(4));
        assertEquals(Integer.valueOf(3), m.selectKey(1).orElse(null));
    }

    // ---- set analogues -----------------------------------------------------

    @Test
    public void setFromSortedRankSelectAndNavigation()
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.fromSorted(Arrays.asList(-10, 0, 5, 20, 21));
        assertEquals(5, s.size());
        assertEquals(Integer.valueOf(-10), s.first());
        assertEquals(Integer.valueOf(21), s.last());
        assertTrue(s.contains(5));
        assertFalse(s.contains(99));
        assertEquals(Arrays.asList(-10, 0, 5, 20, 21), s.rangeElements(Range.all()));
        assertEquals(0, s.rank(-10));
        assertEquals(2, s.rank(5));
        assertEquals(5, s.rank(22));
        assertEquals(Integer.valueOf(5), s.select(2).orElse(null));
        assertFalse(s.select(5).isPresent());
        assertEquals(Integer.valueOf(0), s.floor(4));
        assertEquals(Integer.valueOf(5), s.ceiling(4));
        assertEquals(5, s.ecSet().size());
    }

    @Test
    public void setFromSortedEmptyDuplicateUnsortedAndComparator()
    {
        NavigableTreeSet<Integer> empty = NavigableTreeSet.fromSorted(new ArrayList<Integer>());
        assertEquals(0, empty.size());
        assertEquals(0, empty.rank(0));
        assertFalse(empty.select(0).isPresent());

        assertThrows(Pump.PumpSourceDuplicate.class,
                () -> NavigableTreeSet.fromSorted(Arrays.asList(1, 2, 2)));
        assertThrows(Pump.PumpSourceNotSorted.class,
                () -> NavigableTreeSet.fromSorted(Arrays.asList(1, 5, 3)));

        NavigableTreeSet<Integer> ignored =
                NavigableTreeSet.fromSorted(null, Arrays.asList(1, 2, 2, 3), Pump.DuplicatePolicy.IGNORE);
        assertEquals(3, ignored.size());

        Comparator<Integer> reverse = Comparator.reverseOrder();
        NavigableTreeSet<Integer> rev =
                NavigableTreeSet.fromSorted(reverse, Arrays.asList(9, 5, 1), Pump.DuplicatePolicy.ERROR);
        assertEquals(reverse, rev.comparator());
        assertEquals(reverse, rev.ecSet().comparator());
        assertEquals(Arrays.asList(9, 5, 1), rev.rangeElements(Range.all()));
        assertEquals(Integer.valueOf(5), rev.select(1).orElse(null));
    }

    @Test
    public void setFromSortedEqualsAddByAdd()
    {
        Random rnd = new Random(4242L);
        for (int trial = 0; trial < 40; trial++)
        {
            int n = rnd.nextInt(60);
            java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
            while (keys.size() < n)
            {
                keys.add(rnd.nextInt(2001) - 1000);
            }
            List<Integer> input = new ArrayList<>(keys);
            NavigableTreeSet<Integer> bulk = NavigableTreeSet.fromSorted(input);
            NavigableTreeSet<Integer> ref = NavigableTreeSet.newSet();
            for (int k : input)
            {
                ref.add(k);
            }
            assertEquals(ref.size(), bulk.size());
            assertEquals(ref.rangeElements(Range.all()), bulk.rangeElements(Range.all()));
            assertEquals(ref.descending(), bulk.descending());
            for (int probe = -1002; probe <= 1002; probe += 7)
            {
                assertEquals(ref.rank(probe), bulk.rank(probe));
                assertEquals(ref.floor(probe), bulk.floor(probe));
                assertEquals(ref.higher(probe), bulk.higher(probe));
            }
            for (int i = -1; i <= n; i++)
            {
                assertEquals(ref.select(i), bulk.select(i));
            }
        }
    }
}
```

## Questions to answer explicitly

1. Does ANY runner path still bypass the production bulk builder? Check
   `runIntTreeMap` and `runIntTreeSet` in
   `mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java`,
   and also the other bulk-load scenarios' runner paths
   (`scenarios/17-bulk-load/hashmap_i32_bulk_load_exact.json`,
   `list_multimap_i64_from_sorted_key_values.json`,
   `set_multimap_i64_from_sorted_key_values.json`) -- are those driven by
   production bulk APIs or by a copy-out of the same shape as G1-F7?
2. Is the bulk-built structure genuinely the object under test, i.e. is the
   pumped `MutableSortedMap` really the wrapper's backing store and is the
   navigation `TreeMap` really built by the O(n) `TreeMap(SortedMap)`
   constructor rather than n puts? Any way the comparator, the two stores, or
   the value identity could diverge?
3. "Are subtree sizes verified after bulk build?" -- Java has no subtree-size
   augmentation (ordered-scan rank/select, documented carve-out). Is the
   randomized bulk-vs-put-by-put equivalence test the right Java analogue of
   the other ports' augmentation invariant, or is something stronger needed?
4. Are the tests sufficient? What obligation is missing (float/total-order
   comparator keys? very large n? `IGNORE` policy interaction with rank?
   poisoning/half-built results? null keys/values?).
5. The spec-side manifest cell for `TreeMap<i32, i32>` / java in
   `runners.json` uses `forbidden: "pumped\\.entrySet\\(\\)"`. That pattern is
   now vacuously satisfied (the identifier is gone). Is it still a useful
   tripwire, or should it be replaced with something that catches the SHAPE of
   the regression (e.g. a `put(` call inside the fromSorted branch) regardless
   of variable naming? I may NOT edit the spec repo, only report.
6. Anything about style consistency with the rest of the codebase (javadoc,
   EC brace/format conventions, checkstyle) that would be flagged in review?

Be concrete and cite file/line where you can. If you find nothing material,
say so plainly.

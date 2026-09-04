# Review: iso2 G1-F7 (mapdb-java)

## Verdict

No blocking functional finding in the Java change. The `TreeMap<i32, i32>` runner now tests the product of a production `NavigableTreeMap.fromSorted` factory, and both of that wrapper's internal trees are built through JDK sorted-source bulk paths. I would approve the code change, with a required spec-side follow-up to strengthen and correct the runner manifest guard. The native tests are sufficient for G1-F7 itself, though two focused additions would improve coverage of the newly public generic API.

## Findings

### P1/P2: none in the implementation

I found no correctness or construction-path regression in the changed production/runner code.

### P2: the manifest tripwire is now vacuous and its note is stale

`runners.json:906` forbids only the exact old spelling `pumped.entrySet()`. It would not catch `built.entrySet()`, `result.entrySet()`, or another copy-out spelling. Worse, the note at `runners.json:908` still says the Java runner re-inserts the Pump result, which is no longer true.

The most useful guard with the current line-oriented, whole-method checker is a positive `pattern` requiring the actual result assignment:

```json
"pattern": "map\\s*=\\s*NavigableTreeMap\\.fromSorted\\s*\\("
```

That is materially stronger than the current generic `symbol: "NavigableTreeMap"`, because the latter is also satisfied by the incremental branch and type declaration. A companion forbidden pattern for the obsolete architecture would also help:

```json
"forbidden": "Pump\\.treeSortedMapFromSorted\\s*\\("
```

The positive assignment is the important part: it ties the production bulk result to `map`, which is the object passed to the assertion evaluator. A generic `put(` prohibition cannot be used against the entire `runIntTreeMap` scope because the legitimate incremental branch contains `map.put(...)` at `ValidationRunner.java:1117`. Truly detecting arbitrary copy-out shape within only the `fromSorted` branch would require either a branch-aware/AST-aware check or extracting bulk construction into a separately scoped helper.

The same weakness exists for the other current bulk-load cells: the Java manifest entries require only `IntIntHashMap` (`runners.json:337`), `FastListMultimap` (`:640`), and `UnifiedSetMultimap` (`:833`), all of which are present in their incremental branches. Consider positive patterns requiring `IntIntHashMap.bulkLoadExact(`, `Pump.listMultimapFromSortedKeyValues(`, and `Pump.setMultimapFromSortedKeyValues(` respectively.

### P3: add float-total-order and value-preservation coverage

The tests adequately cover the reported i32 failure, but the newly public factories are generic and their Javadocs/class contract specifically advertise custom/float comparators. The reverse-integer test proves comparator propagation in an ordinary case; it does not exercise `FloatTotalOrder`'s difficult equivalence/order cases (signed zero and distinct/sign-varying NaNs). A map and/or set factory test using `FloatTotalOrder.FLOAT_COMPARATOR`, including rank/select, would pin the Java data-pump requirement at `spec/features/data-pump.md:36-40,163-164` through the new wrapper rather than only through `PumpTest`.

Also, `mapFromSortedEqualsPutByPutIncludingRankSelect` (`NavigableFromSortedTest.java:104-140`) compares keys/navigation but not all values or `selectEntry`. The fixed scenario checks two `get` values and one selected value, so gross value loss is covered, but a randomized `rangeEntries`/`get`/`selectEntry` comparison would make the test name's full-map equivalence claim accurate. A small `assertSame` test with mutable value objects could additionally pin that the two stores retain the same value references rather than cloning/reconstructing values.

These are coverage improvements, not reasons to reject G1-F7.

## Explicit answers

### 1. Do any current runner bulk paths still bypass production bulk APIs?

No, for the bulk-load scenarios currently present:

- `runIntTreeMap` assigns `NavigableTreeMap.fromSorted(...)` directly to the `map` later passed to `evalIntTreeMap` (`ValidationRunner.java:1096-1110,1146-1152`). There is no post-pump entry loop.
- The hash-map scenario assigns `IntIntHashMap.bulkLoadExact(...)` directly to `map` (`:442-452`).
- The list-multimap scenario assigns `Pump.listMultimapFromSortedKeyValues(...)` directly to `mm` (`:580-584`).
- The set-multimap scenario does the same with `Pump.setMultimapFromSortedKeyValues(...)` (`:596-600`).

The multimap Pump implementations do populate their fresh concrete result internally by run/group operations (`Pump.java:818-843,867-885`), but that is the production bulk algorithm, not a runner-side copy-out after construction.

`runIntTreeSet` currently has no `fromSorted` construction branch: it always creates `NavigableTreeSet.newSet()` and applies operations with `add` (`ValidationRunner.java:984-1015`). There is no TreeSet `fromSorted` scenario today, so this does not bypass an existing bulk scenario. It is nevertheless a latent integration gap: if such a scenario is added now, it will silently exercise add-by-add construction despite the new production factory. Either add the branch along with the first scenario or make unsupported construction modes fail rather than fall through.

### 2. Is the bulk-built structure genuinely the object under test?

Yes, with one precision about the two-store design.

`Pump.treeSortedMapFromSorted` consumes and validates the pair iterable into parallel lists, presents them as a `SortedMap`, and constructs the EC `TreeSortedMap` from that sorted source (`Pump.java:432-469`). `TreeSortedMap(SortedMap)` delegates to `new TreeMap<>(map)` (`TreeSortedMap.java:68-70`), so the EC backing tree takes the JDK O(n) `buildFromSorted` path.

`NavigableTreeMap.fromSorted` then constructs the navigation tree with `new TreeMap<>(ecMap)` and passes the exact `ecMap` reference into the wrapper (`NavigableTreeMap.java:131-138`; fields/constructor at `:68-75`). Thus:

- the Pump result really is retained as `this.ecMap`;
- the navigation tree is another O(n) sorted-source build, not n calls to `put`;
- `TreeMap(SortedMap)` adopts the source comparator, so `nav.comparator()` and `ecMap.comparator()` start with the same comparator reference;
- keys and values are copied as object references, so initial value identity is preserved.

The set path is analogous (`NavigableTreeSet.java:94-101`). `TreeSet(SortedSet)` calls its optimized sorted-set `addAll` path when source and destination comparators are equal; here they are the same comparator (or both null), so it bulk-builds its underlying `TreeMap` rather than doing ordinary adds.

Strictly, wrapper queries—including `rank` and `select`—read `nav`, not `ecMap` (`NavigableTreeMap.java:187-205,321-390`; set `:243-289`). That is still the production object returned by the new bulk factory, and Pump content/order defects flow into it through the sorted copy. It does mean the scenario alone cannot detect an `ecMap`-only divergence; the native checks of `ecMap` are therefore useful.

There is one pre-existing escape hatch: `ecMap()`/`ecSet()` return mutable backing collections (`NavigableTreeMap.java:158-162`; `NavigableTreeSet.java:121-125`). A caller can mutate those objects directly and desynchronize `nav`. Wrapper-routed `put/remove/clear/poll/removeRange` keep the stores synchronized, and the new factory does not worsen this, but claims that the stores can never diverge should be qualified as “for mutations routed through the wrapper.” If absolute lockstep is intended, exposing a mutable backing store is incompatible with it.

### 3. Are subtree sizes verified after bulk build?

There are no subtree sizes to verify in Java. `rank` and `select` scan the navigation tree in order (`NavigableTreeMap.java:321-390`; `NavigableTreeSet.java:243-289`), matching the documented O(n) carve-out in `spec/style/java.md:324-335`.

The randomized bulk-vs-put-by-put semantic equivalence test is therefore the right Java analogue. A structural augmentation invariant would be fictitious. The strongest useful extension would be randomized mutations after bulk construction, checking both wrapper results and `ecMap`/`ecSet` after every operation; that would test the actual Java-specific two-store invariant. It is optional here because the mutation methods are shared with already-tested ordinary construction, and the new test already exercises post-build map `put/remove`.

One documentation discrepancy is worth cleaning up separately: `spec/style/java.md:326-329` says rank/select scan the boxed EC tree, but the implementation scans `nav`. Observable behavior and complexity are unchanged, but the description should name the navigation `TreeMap` (or simply “boxed tree”).

### 4. Are the tests sufficient, and what is missing?

They are sufficient for G1-F7: scenario data is tested on the factory result; random i32 bulk-vs-incremental rank/select/navigation equivalence is covered; empty, duplicate/error, ignore-first, unsorted input, reverse comparator propagation, and post-build mutation are covered; set symmetry is covered.

Recommended additions, in priority order:

1. Float-total-order construction through `NavigableTreeMap.fromSorted`/`NavigableTreeSet.fromSorted`, including `-0.0`, `+0.0`, infinities and multiple raw NaNs, plus rank/select and both comparator accessors.
2. Randomized map value/entry equivalence (`get`, `rangeEntries`, and `selectEntry`), not only key projections.
3. For set `IGNORE`, assert the resulting order statistics, as the map test already does (`NavigableFromSortedTest.java:168-182` versus set `:264-266`).
4. If two-store lockstep deserves direct coverage, include `clear`, `pollFirst`/`pollLast`, and `removeRange` after bulk construction, or a randomized mutation trace.

A very large-n correctness test is not necessary; it would not reliably prove complexity, while the constructor call sites establish use of the JDK bulk paths. Sink poisoning is already a `Pump.Sink` obligation (`Pump.java:351-417`) and is not exposed by these one-shot wrapper factories. A failed one-shot returns no object, so there is no half-built result to inspect. Null-key/value tests should be added only if the wrapper API explicitly promises them; the cross-language data-pump contract does not. Null values are supported by the underlying maps, but are orthogonal to G1-F7.

### 5. Is the existing manifest `forbidden` useful?

Not meaningfully. It is now a historical exact-string check and is trivially evaded by renaming. Replace it with the positive assignment pattern above, update the stale note, and preferably forbid direct `Pump.treeSortedMapFromSorted(` in `runIntTreeMap`. For a truly shape-based guarantee, enhance the checker or isolate the bulk branch in its own scoped helper; whole-method grep cannot distinguish legitimate incremental `put` calls from forbidden bulk-branch copy-out.

### 6. Style/checkstyle consistency

The changed production files follow the existing EC brace layout, import grouping, and Javadoc style. The new test follows neighboring native-test conventions. `git diff --check` reports no whitespace errors, and the changed lines do not introduce over-120-character lines. I see no likely checkstyle blocker.

The test class's long historical explanation is more verbose than necessary but consistent with this repository's spec-linked native tests and not a review issue. The only concrete documentation cleanup I found is the pre-existing EC-tree-versus-navigation-tree wording noted above; the new factory Javadocs themselves accurately describe the construction and error policy.

---

## Follow-up actions taken (by the implementing agent, after this review)

Applied in the same commit:

- `NavigableFromSortedTest`: float IEEE-754 totalOrder construction through
  BOTH new factories (signed zeros, signed NaN payloads, infinities; rank /
  select / `selectEntry` values / comparator on both stores; plus the
  "same input under natural order must trap" check) — review item 4.1.
- `NavigableFromSortedTest`: randomized map equivalence now compares values
  (`get` for every key, `rangeEntries` key=value projection, `selectEntry`
  values), not only key projections — review item 4.2.
- `NavigableFromSortedTest`: the set `IGNORE` case now asserts the resulting
  order statistics, like the map case — review item 4.3.
- `NavigableFromSortedTest`: two-store lockstep after bulk build now covers
  `pollFirst`/`pollLast`/`removeRange`/`clear` for map and set — review
  item 4.4.
- `ValidationRunner.runIntTreeSet`: added the `fromSorted` construction branch
  driving `NavigableTreeSet.fromSorted`, closing the latent integration gap the
  review names in answer 1 (a future TreeSet fromSorted scenario would
  otherwise have silently run add-by-add — exactly the G1-F7 shape). The
  assertion-emitting tail was extracted to `emitIntTreeSet` so both branches
  share it. No scenario exercises this today.

Native tests: 604 -> 619. `check-runners.sh` PASS (all five ports);
`validate.sh --skip-go --skip-rust --skip-ts --skip-zig` 306/306 pass.

NOT applied here (spec repo is out of this task's scope — reported upward
instead):

- Strengthening the `runners.json` Java cells for `TreeMap<i32, i32>`
  (positive `pattern` `map\s*=\s*NavigableTreeMap\.fromSorted\s*\(`, drop or
  replace the now-vacuous `forbidden: pumped\.entrySet\(\)`, fix the stale
  note) and the analogous positive patterns for the hash-map / list-multimap /
  set-multimap bulk cells.
- The `spec/style/java.md:326-329` wording that says rank/select scan the EC
  tree when the implementation scans the navigation `TreeMap` (observably
  identical; wording only).
- The `ecMap()`/`ecSet()` mutable-backing-store escape hatch, which is
  pre-existing and already on the iso2 REAL WORK list.

---

# Round 2 — iso2 G1-F5 (`TreeSet<f32>` drives `TreeSortedSet`, not `NavigableTreeSet`)

Brief: `reviews/iso2-g1f5-brief.md`. Codex output verbatim below; it ran
source-review only (no builds, no repo writes).

## Disposition reached before the review

The type switch was NOT made. Documented reason, cited at the call site:

- `spec/collections.md` ("TreeSet, TreeMap, TreeBag — ordered", mapdb-java
  note) states verbatim that "the validation runner wires
  `TreeSortedSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR)` for the
  `TreeSet<f32>` scenarios".
- `runners.json`'s Java cell for `TreeSet<f32>` pins `symbol:
  "TreeSortedSet"` with a matching carve-out note.
- The two `TreeSet<f32>` scenarios assert only size / min / max / contains /
  to_sorted_array — nothing navigational — so every assertion value already
  comes from the production sorted set's own methods under the spec
  comparator. This is an inconsistency, not an oracle violation.
- Switching unilaterally would contradict the normative note AND turn
  `check-runners.sh` red for java; both edits belong to the spec repo, which
  this task must not touch. Satisfying the pinned symbol by naming
  `TreeSortedSet` in a comment would be gaming the guard, which the manifest
  forbids outright.

Scan for other f32/i32 production-type divergences: none. The other pairs are
`FloatArrayList`/`IntArrayList`, `FloatHashSet`/`IntHashSet`,
`FloatIntHashMap`/`IntIntHashMap`. `TreeSet<f32>` vs `TreeSet<i32>` is the only
one — codex independently confirmed this, assertion-side helpers included.

## Codex review (verbatim)

# Second opinion: iso2 G1-F5

## Findings

1. **The disposition is sound, but the new runner comment misdescribes the implementation.** `NavigableTreeSet` does not operate “over the same boxed EC tree.” It owns two separate stores: a direct JDK `TreeSet` and an EC `TreeSortedSet` (which itself wraps another JDK `TreeSet`), and synchronizes mutations between them (`NavigableTreeSet.java:40-46, 60-66, 129-140`; the map is analogous). Replace that sentence with something like: “Both are production classes using the same comparator semantics; `NavigableTreeSet` owns a separate navigation store and `TreeSortedSet` backing store and keeps them synchronized.” This does not change the no-oracle-violation conclusion: `runF32TreeSet` directly queries the production `TreeSortedSet` for every asserted value.

2. **The new tests do not fully pin their claimed NaN ordering.** Assertions such as `assertEquals(negNan, s.first())`, `assertEquals(posNan, s.last())`, and expected `List<Float>` comparisons (`NavigableTreeSetTest.java:184-200`, map lines 235-248) use `Float.equals`, under which all NaNs compare equal. A defect that swapped negative and positive NaNs at the two ends could therefore pass those particular assertions. Compare `Float.floatToRawIntBits(...)` at the NaN positions/navigation results, as `FloatTotalOrderTest.floatSortMatchesTotalOrder` already does. Also consider inserting two same-sign NaN payloads and proving that both survive as distinct set elements/map keys; the comparator test covers payload ordering, but these wrapper tests do not cover payload identity through the two stores.

3. **The “no float-comparator coverage at all before” premise is overstated.** Existing `NavigableFromSortedTest.mapFromSortedUnderFloatTotalOrder` and `setFromSortedUnderFloatTotalOrder` (`NavigableFromSortedTest.java:251-307`) already test comparator identity on both stores, signed-zero distinction, and float rank/select. The new tests still add useful coverage of the ordinary `newSet`/`newMap` plus incremental-add path, point navigation, and comparator preservation in a materialized snapshot. Describe that incremental coverage rather than implying all float navigable coverage is new. The new map test also omits `-1/+1` and does not call `floorKey`/`ceilingKey`, contrary to the broad summary in the brief; the set test covers those cases.

## Answers to the questions

1. **Yes.** For the two actual `TreeSet<f32>` scenarios, this is a runner/type inconsistency, not a conformance or oracle violation. They perform only `add`, and the asserted size/min/max/contains/in-order traversal all come directly from the production EC `TreeSortedSet` configured with `FloatTotalOrder.FLOAT_COMPARATOR`. There is no runner-side reference collection or runner-side sort on this path.

2. **Deferring the switch is the stronger call.** The spec explicitly says the Java validation runner wires `TreeSortedSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR)`, and `runners.json` pins that production symbol. A Java-only switch would knowingly contradict both sources and make the guard fail. The proper change is coordinated: update the normative note and manifest cell, then switch the runner in the same change set. Merely breaking the manifest and reporting it would leave the repository intentionally inconsistent, with no scenario-level behavioral benefit today.

3. **The tests are useful but partly redundant and slightly incomplete as claims of exact float-total-order coverage.** They newly cover the incremental constructor/mutation path and point navigation. Add raw-bit NaN assertions and, ideally, distinct same-sign NaN payloads plus an assertion that the EC backing store contains the same float keys/elements after incremental insertion. No current v1 conformance obligation is blocked: `features/navigable-map.md:67-71` explicitly defers float Range/navigation with the rest of the float matrix. Comparator-preserving snapshots are still worth testing, but the chosen `[-0.0,+Inf]` bounds intentionally avoid the fact that Java `Range<Float>` itself uses natural `Float.compare`, not the tree comparator.

4. **No other production-type divergence was found in the requested paths.** The pairs are `FloatArrayList`/`IntArrayList`, `FloatHashSet`/`IntHashSet`, and `FloatIntHashMap`/`IntIntHashMap`; only the tree-set pair differs. The f32 hash/list assertion-side `Arrays.sort` calls canonicalize unordered/list output just as the i32 helpers do; they do not replace the production collection under test. The f32 tree-set output is correctly a direct in-order traversal.

5. Besides findings 1-3, the adjacent pre-existing heading at `ValidationRunner.java:1377` still says `Float::compare`, contradicting the actual `FloatTotalOrder.FLOAT_COMPARATOR`; it should be corrected when this area is next edited. Otherwise the new tests are coherent and the decision note reaches the right conclusion.

Per the brief, I performed source review only and did not run builds, tests, Maven, or validation scripts.

## Actions taken on this round's findings

- Finding 1 (comment misdescribed the implementation): corrected — the note
  now says the two are production types with the same comparator semantics,
  and that `NavigableTreeSet` owns a JDK `TreeSet` navigation store plus an EC
  `TreeSortedSet` backing store kept in sync, whereas this path drives the EC
  set directly. Codex is right; "over the same boxed EC tree" was wrong.
- Finding 2 (NaN assertions were `Float.equals`, which cannot see payloads or
  a swapped pair of ends): all NaN positions in both new tests now assert on
  `Float.floatToRawIntBits`, including first/last, `lower` past -Inf, and the
  first/last elements of the full in-order traversal. Added
  `floatTotalOrderKeepsDistinctSameSignNanPayloads` (set) and
  `floatTotalOrderKeepsDistinctSameSignNanKeyPayloads` (map): two positive
  NaNs with different payloads stay distinct, keep ascending payload order,
  keep their own values (map), and survive in the EC backing store.
- Finding 3 (premise overstated; map test thinner than described): test
  javadocs now say they cover the incremental `add`/`put` path and point to
  `NavigableFromSortedTest` for the bulk float path. The map test gained
  `-1.0f`/`1.0f` keys and `floorKey`/`ceilingKey` assertions, so it matches
  the set test's breadth; both now also assert the EC backing store holds the
  same keys/elements after incremental mutation.
- Finding 5 (pre-existing wrong section heading): the `runF32TreeSet` heading
  said `Float::compare`; corrected to `FloatTotalOrder`.
- Not acted on: codex's note that Java `Range<Float>` uses natural
  `Float.compare` rather than the tree comparator. That is a real pre-existing
  question about `Range` on the float axis, out of this task's scope, and
  `spec/features/navigable-map.md` defers float Range/navigation with the rest
  of the float matrix. Reported upward instead.

Gates after these changes: native tests 621 -> 623 (604 at the start of this
workstream); `check-runners.sh --root /home/play2/mapdb` PASS for all five
ports; `validate.sh --skip-go --skip-rust --skip-ts --skip-zig` 306/306 pass,
0 fail.

## Still needs a spec-repo decision (not done here)

To make `TreeSet<f32>` drive `NavigableTreeSet` like `TreeSet<i32>`, three
edits must land together: the `collections.md` sentence naming the wiring, the
`runners.json` Java cell (`symbol` -> `NavigableTreeSet`, drop the carve-out
note), and the runner. Until then the runner stays as the spec describes it.

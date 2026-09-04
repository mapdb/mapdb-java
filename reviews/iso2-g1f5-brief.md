# CONSTRAINT — READ FIRST

READ-ONLY review. Do NOT modify, create, or delete ANY file anywhere except
your single output file `/tmp/iso2-g1f5-review.md`. Do NOT touch
`/home/play2/mapdb/mapdb-collection-spec` (the spec repo) or
`/home/play2/mapdb/mapdb-java`. Do NOT run any build, test, or maven command
(another agent is building that checkout concurrently). Read sources, reason,
and write your findings to the output file only. Propose changes as text.

# Brief: iso2 finding G1-F5 (mapdb-java) — short second opinion

Repo: `/home/play2/mapdb/mapdb-java`. Spec: `/home/play2/mapdb/mapdb-collection-spec`.

## The finding

iso2 G1-F5: "Java `TreeSet<f32>` uses `TreeSortedSet` (a `java.util.TreeSet`
wrapper) while `TreeSet<i32>` uses `NavigableTreeSet`." The coordinator asked
me to make the f32 runner drive the same production navigable type as i32
(with the float total-order comparator), UNLESS there is a documented reason
not to — in which case cite it and leave a note.

## What I found

1. `spec/collections.md` (section "TreeSet, TreeMap, TreeBag — ordered",
   mapdb-java note, lines ~152-160) states verbatim: "the validation runner
   wires `TreeSortedSet.newSet(FloatTotalOrder.FLOAT_COMPARATOR)` for the
   `TreeSet<f32>` scenarios."
2. `cross-language-validation/runners.json`, Java cell for `TreeSet<f32>`:
   `"symbol": "TreeSortedSet"` with note "Java's f32 sorted set is
   TreeSortedSet (a java.util.TreeSet wrapper), NOT the NavigableTreeSet used
   for i32; documented carve-out at the call site."
3. The two `TreeSet<f32>` scenarios
   (`scenarios/05-float-edge-cases/treeset_f32_{neg_zero_distinct,total_order}.json`)
   use ops add only and assert only
   `size` / `min` / `max` / `contains_*` / `to_sorted_array` — nothing
   navigational (no floor/ceiling/lower/higher/rank/select/descending/range).
4. `NavigableTreeSet` internally OWNS an EC `TreeSortedSet` plus a synced
   `java.util.TreeSet` navigation view; both are production types over the
   same boxed EC tree. So the f32 runner is not using a std/hand-rolled
   oracle: every assertion value comes from the production sorted set's own
   methods, ordered by the spec `FloatTotalOrder.FLOAT_COMPARATOR`.
5. Scanning every Java cell in `runners.json`, the f32/i32 type pairs are:
   ArrayList `FloatArrayList`/`IntArrayList`, HashMap
   `FloatIntHashMap`/`IntIntHashMap`, HashSet `FloatHashSet`/`IntHashSet` —
   all consistent. `TreeSet<f32>` vs `TreeSet<i32>` is the ONLY divergence.

## What I did (and did not do)

I did NOT switch the runner to `NavigableTreeSet`, because:
- it would contradict the explicit `collections.md` sentence above, and
- it would turn `check-runners.sh` RED for java (the cell pins the literal
  symbol `TreeSortedSet`), and the fix for both lives in the spec repo, which
  this task must not edit. Making the check green by mentioning
  `TreeSortedSet` in a comment would be gaming the guard, which the manifest
  explicitly forbids ("NEVER relax an entry to make the check green").

Instead I:
- expanded the comment at the `runF32TreeSet` call site
  (`mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java`)
  to cite `collections.md` and the runners.json cell, state that this is an
  inconsistency and not an oracle violation (with the reason), and name the
  coverage that the shared suite therefore never reaches;
- closed that actual coverage gap with native tests, since the navigable
  wrappers had NO float-comparator coverage at all before:
  `NavigableTreeSetTest.floatTotalOrderNavigationAndRankSelect` and
  `NavigableTreeMapTest.floatTotalOrderKeysNavigationAndRankSelect` — total
  order over -NaN / -Inf / -1 / -0.0 / +0.0 / 1 / +Inf / +NaN inserted out of
  order, comparator identity on BOTH backing stores, signed zeros distinct
  (and, for the map, not overwriting each other), rank/select/selectEntry
  across the zero split, lower/higher/floor/ceiling following total order
  rather than `Float.compare`, null at the ends, and comparator preservation
  through a materialized `subSet`/`subMap` snapshot.

Native tests went 619 -> 621. `check-runners.sh --root /home/play2/mapdb`:
PASS java 26 checked (all five ports PASS). `validate.sh` java: 306 scenarios.

## Questions

1. Is my reading right that driving `TreeSortedSet` for `TreeSet<f32>` is an
   inconsistency rather than a conformance/oracle violation, given what those
   two scenarios actually assert?
2. Is deferring the type switch to a coordinated spec-repo change the right
   call, or should the runner have been switched anyway and the manifest
   breakage reported as the finding? Argue the stronger case.
3. Do the two new native tests actually pin what the shared suite loses, or
   is there a specific float/navigable obligation still uncovered?
4. Did I miss any other place where a Java f32 path drives a different
   production type than its i32 counterpart? Check
   `mapdb-validation/src/main/java/org/mapdb/validation/ValidationRunner.java`
   (`runF32TreeSet`, `runF32Set`, `runF32Map`, `runF32List` versus their i32
   counterparts) — including assertion-side helpers, not just construction.
5. Anything wrong or overstated in the new comment or the new tests?

Be brief and concrete. If nothing material, say so plainly.

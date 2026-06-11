# mapdb-validation — cross-language conformance runner

Internal, unpublished Maven module. It runs the
`mapdb-collection-spec/cross-language-validation` scenarios (every `*.json`
under the scenarios root) against **stock, unrenamed Eclipse Collections**
(still `org.mapdb.collections`, version `14.0.0-SNAPSHOT` installed in the
local `~/.m2`) and prints a red/green conformance list.

This module is deliberately **NOT** in the root pom `<modules>`, so the
documented build-unblock command (`../BUILD-MAPDB.md`) is unaffected.

## Prerequisites

1. Eclipse Collections artifacts installed locally per `../BUILD-MAPDB.md`:

   ```
   cd ..
   mvn install -DskipTests -Dcheckstyle.skip=true \
     -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils \
     -am
   ```

2. JDK 25 / Maven 3.9.16 (release target 17). Jackson is pulled from Maven Central.

## Build

```
mvn -f mapdb-validation/pom.xml clean package
```

Produces a self-contained fat jar at
`mapdb-validation/target/mapdb-validation.jar`.

## Run

Pass the scenarios root directory as the single argument. The runner discovers
every `*.json` under it recursively.

```
java -jar mapdb-validation/target/mapdb-validation.jar \
  ../mapdb-collection-spec/cross-language-validation/scenarios
```

Or with an explicit absolute path:

```
java -jar mapdb-validation/target/mapdb-validation.jar \
  /home/play1/mapdb/mapdb-collection-spec/cross-language-validation/scenarios
```

Output: one `key: value` line per assertion, a `PASS`/`FAIL <name>` line per
scenario (with `expected=`/`got=` on each failing assertion), then a per-dir
and grand-total summary. **Exit code is non-zero if any scenario FAILs** (and
also if any scenario could not be run because its collection type is
unsupported by stock EC).

A single scenario can also be run by passing its file path.

## Type mapping (stock EC ⇒ scenario type)

Scenarios name conceptual primitive collections. Where stock EC ships a
matching **production primitive** type it is used directly; where it does not,
the closest **production object** surface is used and the decision recorded
here. **No collection logic is reimplemented in the runner** — every assertion
is computed from the real EC collection. Sorting an assertion's *output array*
for deterministic comparison is allowed by the contract and is not
re-implementing collection logic.

| Scenario type            | EC type used                                  | Notes |
|--------------------------|-----------------------------------------------|-------|
| `HashMap<i32, i32>`      | `IntIntHashMap`                               | primitive |
| `ArrayList<i32>`         | `IntArrayList`                                | primitive |
| `HashSet<i32>`           | `IntHashSet`                                  | primitive |
| `HashBag<i32>`           | `IntHashBag`                                  | primitive |
| `ArrayStack<i32>`        | `IntArrayStack`                               | primitive (no scenario currently uses it) |
| `HashMap<i64, i32>`      | `LongIntHashMap`                              | primitive |
| `HashMap<f32, i32>`      | `FloatIntHashMap`                             | primitive |
| `HashSet<f32>`           | `FloatHashSet`                                | primitive |
| `ArrayList<f32>`         | `FloatArrayList`                              | primitive |
| `TreeSet<i32>`           | `TreeSortedSet<Integer>` (natural order)      | **object fallback** — EC ships no primitive sorted set |
| `TreeMap<i32, i32>`      | `TreeSortedMap<Integer,Integer>` (natural)    | **object fallback** — EC ships no primitive sorted map |
| `TreeSet<f32>`           | `TreeSortedSet<Float>` with `Float::compare`  | **object fallback** — EC ships no primitive sorted set AND no IEEE total-order float comparator. `Float.compare` is the natural stock surface; it collapses `-0.0`/`+0.0` and treats every NaN equal, so these scenarios are expected RED. |
| `ListMultimap<i64, i32>` | `FastListMultimap<Long,Integer>`              | **object fallback** — EC ships no primitive multimap |
| `SetMultimap<i64, i32>`  | `UnifiedSetMultimap<Long,Integer>`            | **object fallback** — EC ships no primitive multimap |

### Why the object fallbacks are an honest red/green probe

Stock EC simply has no primitive `Tree*` or primitive multimap surface, so the
closest production EC type is an object sorted/multimap. For `TreeSet<f32>` the
critical contract requirement — IEEE 754 totalOrder by bit pattern, with
`-0.0 < +0.0` distinct and `+NaN` at the top — is **not** something stock EC
provides; `Float.compare` (the obvious stock comparator) collapses signed zero
and mis-orders NaN. We use `Float.compare` rather than inventing a total-order
comparator precisely so the resulting failures are recorded as RED entries
(stock EC gaps), not papered over.

## Float operand encoding (per the contract)

The runner implements the contract's float operand layer:

* JSON number ⇒ exact value (read at f32 width via `(float)`).
* Human label string ⇒ `NaN`/`+NaN` (`0x7FC00000`), `-NaN` (`0xFFC00000`),
  `Infinity`/`+Infinity`, `-Infinity`, `0.0`/`+0.0`, `-0.0`, or any decimal
  parsed with `Float.parseFloat`.
* `{"bits":"0x........"}` ⇒ exactly 8 hex digits ⇒ `Float.intBitsToFloat`.

Canonical float **serialization**: NaN (any sign/payload) and `±0.0` render as
their lower-case `0x`-prefixed 8-hex-digit bit pattern via
`Float.floatToRawIntBits`; `±Infinity` render as the labels; integral finite
floats render as `N.0`; other finite floats render shortest-round-trip via
`Float.toString` (shortest-round-trip at float width on modern JDKs).

**Loose-NaN scalar match**: when an assertion's *scalar* expected value is a
bare NaN label (`"NaN"`/`"+NaN"`/`"-NaN"`), the check passes against any NaN
bit pattern. This does **not** apply to `{"bits"}` operands (always exact) nor
to float **array** elements (always exact / positional).

## i64 wide-integer encoding

i64 keys are decimal strings (small keys may be bare JSON numbers), parsed with
`Long.parseLong` (full signed range incl. negatives), never via a double. The
`get_<k>` / `contains_<k>` / `contains_key_<k>` suffix is parsed as an i64.
`sorted_keys` for i64 maps/multimaps serializes each key as a quoted decimal
string sorted numerically ascending.

## Unknown assertion keys

Unknown assertion keys are **skipped** (not printed, not failed), per the
contract, so new assertion types do not break this runner. `comment` keys are
also skipped. `expect_panic` is RESERVED and unused by any scenario; the runner
treats it as unknown (skip).

## Contract-ambiguity decisions (matched to the Rust runner)

* **`get_N` on a primitive map** distinguishes absent (`null`) from present-but-zero
  by calling `containsKey` first; EC primitive maps return `0` for an absent key,
  so a naive `get` would be wrong. (Not strictly an ambiguity, but a stock-EC trap.)
* **List `sum`** uses EC `IntList.sum()` which returns `long` — the contract's
  widened-accumulator rule. `inject_into_sum` accumulates in i32 and wraps.
* **`inject_into_product`** initial seed `1`, i32 wrapping multiply.
* **f32 list `sum`** is a per-add f32 left-fold (IEEE arithmetic, not widened),
  matching the Rust runner and the contract note.
* **f32 list `min`/`max`** are taken from EC `FloatArrayList.min()/max()`, i.e.
  whatever stock EC's float comparison does (expected RED where total-order is
  required).
* **Bag `to_sorted_array`** flattens with duplicates, sorted ascending.
* **Unknown keys / `comment` / `expect_panic`** ⇒ skip.

# mapdb-collections

`mapdb-collections` is the **Java port of the mapdb primitive-collections
family**, governed by the
[mapdb-collection-spec](https://github.com/mapdb/mapdb-collection-spec). It is a
**fork of [Eclipse Collections](https://github.com/eclipse-collections/eclipse-collections)**:
Eclipse Collections is the vehicle (a mature, generated primitive-collections
framework), and spec conformance is the goal.

The mapdb collection family has ports in Rust, Go, TypeScript, Zig, and now
Java. A single contract — the spec — is the master; conformance is proven by the
shared cross-language validation scenarios plus the port's native tests.

## What changed vs. upstream Eclipse Collections

The behavioral fork is small and targeted at the spec's conformance core. Every
change lands in the StringTemplate (`.stg`) generator templates or shared code,
so it covers all generated primitive types uniformly:

- **Raw-bit float and double identity.** Hashing and equality use
  `Float.floatToRawIntBits` / `Double.doubleToRawLongBits` (not the
  canonicalizing `floatToIntBits`). Distinct NaN bit patterns are distinct keys;
  `-0.0` and `+0.0` are distinct. Covers keys, values, tuples, bags, sets, maps,
  sorted collections, and `containsValue`.
- **IEEE 754 totalOrder** for float/double ordering in sorted collections:
  sign-flipped bit comparison, so `-0.0 < +0.0` and `+NaN` sorts at the top.
- **Default hash spread → 64-bit Fibonacci** (`0x9E3779B97F4A7C15`). The
  `SpreadFunctions` family was deleted. (Java has no strong native primitive
  hash; the spec's native-hash carve-out names only Rust's SipHash.)
- **Overflow-safe `IntInterval` arithmetic** (uint64-style size/contains/get,
  reversed-at-minimum-step guard).

The full list of v1 decisions, carve-outs, and spec debt is documented in the
spec repo under `spec/style/java.md`.

Everything else Eclipse Collections ships (the broad object-collection API,
parallel iteration, etc.) is **inherited surface with no conformance promise** —
supported core is the spec inventory.

## Coordinates

```xml
<dependency>
    <groupId>org.mapdb</groupId>
    <artifactId>mapdb-collections-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.mapdb</groupId>
    <artifactId>mapdb-collections</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Published artifacts (v1): `mapdb-collections-api`, `mapdb-collections`,
`mapdb-collections-forkjoin`. Java packages are under `org.mapdb.collections.*`.
JARs carry an `Automatic-Module-Name` (`org.mapdb.collections.api` / `.impl` /
`.forkjoin`); there is no `module-info` in v1.

## Build

Requirements: JDK 17+ (release target 17; tested on JDK 25), Maven 3.9.x.

Sources for the primitive types are **generated** from `.stg` templates on every
build (not checked in), so a clean build always regenerates them.

Build and install the core modules:

```
mvn clean install -DskipTests -Dcheckstyle.skip=true \
  -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils \
  -am
```

(The module *directories* keep their upstream `eclipse-collections-*` names in
v1; the Maven artifactIds are `mapdb-collections-*`.)

### Conformance: cross-language validation

The internal, unpublished `mapdb-validation` module runs the spec's
cross-language scenarios (298 scenarios) against the built collections. The
scenarios live in the sibling `mapdb-collection-spec` repo:

```
mvn -f mapdb-validation/pom.xml clean package
java -jar mapdb-validation/target/mapdb-validation.jar \
  ../mapdb-collection-spec/cross-language-validation/scenarios
```

### Native (port-specific) tests

The internal, unpublished `mapdb-native-tests` module runs the port's
native-test obligations (raw-bit float identity, IEEE totalOrder, sentinel
boundaries, Fibonacci hash spread, serialization smoke, IntInterval boundaries):

```
mvn -f mapdb-native-tests/pom.xml test
```

## License

Forked from Eclipse Collections, which is licensed under the Eclipse Public
License v1.0 and the Eclipse Distribution License v1.0. All upstream copyright
notices are retained in source-file headers as required by the EPL. See
[`LICENSE-EPL-1.0.txt`](LICENSE-EPL-1.0.txt),
[`LICENSE-EDL-1.0.txt`](LICENSE-EDL-1.0.txt), and [`NOTICE.md`](NOTICE.md).

SPDX-License-Identifier: EPL-1.0 OR BSD-3-Clause

## Links

- Spec / contract: https://github.com/mapdb/mapdb-collection-spec
- This fork: https://github.com/mapdb/mapdb-java
- Upstream Eclipse Collections: https://github.com/eclipse-collections/eclipse-collections

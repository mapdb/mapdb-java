# Building mapdb-collections

This document records the exact, repeatable commands that build and verify
`mapdb-collections` (the Eclipse Collections fork) on this machine, after the
mechanical trim/rename phase.

Packages are `org.mapdb.collections.*`; Maven coordinates are `org.mapdb` /
`mapdb-collections-*`; the version is `1.0.0-SNAPSHOT`. The module *directory*
names are still the upstream `eclipse-collections-*` (a deferred v1 cosmetic;
the artifactIds are decoupled from the directory names).

## Prerequisites

- JDK 17+ (release target 17). Tested on OpenJDK 25.0.3; the compiler plugin
  forks `javac` with `release 17`. CI uses temurin 17.
- Maven 3.9.16.
- Network access to Maven Central. First run downloads the full
  dependency/plugin set; a warm-repo clean reactor build runs in well under a
  minute on this machine.

## 1. Build + install the core modules

```
mvn clean install -DskipTests -Dcheckstyle.skip=true \
  -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils \
  -am
```

Reactor produced (via `-am`):

1. mapdb-collections-parent (pom)
2. mapdb-collections-code-generator
3. mapdb-collections-code-generator-maven-plugin
4. mapdb-collections-api
5. mapdb-collections (impl)
6. mapdb-collections-testutils
7. mapdb-collections-forkjoin

The api/impl/forkjoin jars carry an `Automatic-Module-Name`
(`org.mapdb.collections.api` / `.impl` / `.forkjoin`); there is no `module-info`
in v1.

### Why each flag

- `-pl <list> -am` — build only the published core plus its build
  prerequisites (the generator and its Maven plugin), skipping the heavy
  `unit-tests*` modules. A plain `mvn clean install` (whole reactor) also works
  and additionally compiles the unit-test modules.
- `-DskipTests` — compile test sources but do not run the huge unit suite.
- `-Dcheckstyle.skip=true` — checkstyle is style policy, off by default for a
  build/conformance run.

### What is OFF by default (and stays that way)

- **maven-enforcer-plugin** — id-only profile (`-Pmaven-enforcer-plugin`), no
  `<activation>`. Its `requireJavaVersion` was relaxed from exactly `17` to
  `[17,)`, so it now passes on JDK 17+ (it is only switched on in CI logic if
  desired). Off for normal builds.
- **maven-javadoc-plugin** aggregate, **spotless** (also globally
  `spotless.check.skip=true`), **jacoco**, **errorprone**,
  **maven-dependency-plugin** analyze — all gated behind profiles or
  `performRelease`.
- OSGi/bnd machinery was removed entirely (no `bnd.bnd`, no bnd profile).

## 2. Code generation (StringTemplate)

Generation runs every build; the generated `.java` sources are a build product,
not checked in. The `mapdb-collections-code-generator-maven-plugin`
`generate-sources` goal expands the `.stg` templates into
`*/target/generated-sources/java/org/mapdb/collections/...`. A clean build is
therefore inherently a clean-generate.

## 3. Cross-language conformance validation

The standalone, unpublished `mapdb-validation/` module (not in the root
`<modules>`) runs the cross-language scenarios. The scenarios live in the
sibling `mapdb-collection-spec` repo.

```
mvn -f mapdb-validation/pom.xml clean package
java -jar mapdb-validation/target/mapdb-validation.jar \
  ../mapdb-collection-spec/cross-language-validation/scenarios
```

Expected: `scenarios run: 57, result: GREEN` (exit code 0).

## 4. Native (port-specific) tests

The standalone, unpublished `mapdb-native-tests/` module (also not in the root
`<modules>`) runs the spec-required tests the shared scenario suite does not
exercise (raw-bit float identity, IEEE totalOrder, sentinel boundaries, 64-bit
Fibonacci hash spread, capacity/resize threshold, serialization smoke,
IntInterval boundaries). It depends
on the locally-installed core SNAPSHOTs, so run step 1 first, then:

```
mvn -f mapdb-native-tests/pom.xml test
```

Expected: `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
The distinct-NaN-payload tests are the headline proof of the raw-bit float
identity change.

## Expected non-fatal warnings on JDK 25

- `sun.misc.Unsafe is internal proprietary API ...` in `ConcurrentHashMapUnsafe`.
- `[removal] SecurityManager / AccessController` deprecations in
  `CollectionsThreadFactory` and `ConcurrentHashMapUnsafe`.

These are warnings only; compilation is green. They are inherited upstream code.

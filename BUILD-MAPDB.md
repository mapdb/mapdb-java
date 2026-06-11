# Building mapdb-java (build-unblock phase)

This document records the exact, repeatable command that produces a green
build of the core modules of the Eclipse Collections fork on this machine.

It is the output of the **build-unblock trim** phase only (see
`../todo/java/plan.md`). It is NOT the real trim/rename phase: there are
**zero** package or artifact renames, and **zero** behavioral changes to
library code. Everything is still `org.eclipse.collections`.

## Prerequisites

- JDK 25 (tested: OpenJDK 25.0.3). The build targets
  `maven.compiler.release=17`, which JDK 25 compiles fine. The compiler
  plugin forks `javac` with `release 17`.
- Maven 3.9.16.
- Network access to Maven Central (and, transitively, `repo.eclipse.org`
  cbi-releases plugin repo declared in the root pom — not actually needed
  for the core build, but it is listed). First run downloads the full
  dependency/plugin set; expect a few minutes. With a warm local repo a
  full `clean install` of the reactor runs in ~30-45 s on this machine
  (measured: 29.9 s and 43.3 s on two back-to-back clean runs).

## The command

```
mvn install -DskipTests -Dcheckstyle.skip=true \
  -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils \
  -am
```

For a guaranteed-clean run prepend `clean`:

```
mvn clean install -DskipTests -Dcheckstyle.skip=true \
  -pl eclipse-collections-api,eclipse-collections,eclipse-collections-forkjoin,eclipse-collections-testutils \
  -am
```

### Reactor this produces (via `-am`, "also make" upstream deps)

1. eclipse-collections-parent (pom)
2. eclipse-collections-code-generator
3. eclipse-collections-code-generator-maven-plugin
4. eclipse-collections-api
5. eclipse-collections (impl)
6. eclipse-collections-testutils
7. eclipse-collections-forkjoin

These are exactly the goal modules. The code generator and its Maven
plugin are pulled in automatically as build prerequisites of api/impl.

## Why each flag

- `-pl <list> -am` — select only the goal modules plus their upstream
  dependencies. This **avoids** the modules that would distort or break a
  clean validation build without touching the root `<modules>` list:
  `unit-tests`, `unit-tests-thread-safety`, `serialization-tests`,
  `jcstress-tests`, `unit-tests-java8`, `test-coverage`, `p2-site` (all in
  the default `<modules>` list), and the Scala/JMH modules (which live in
  the `all` / release profiles, not the default reactor). No pom surgery
  was required to exclude any of them.
- `-DskipTests` — compiles test sources but does not *run* the (huge) unit
  suite. Test sources for the core modules still compile (testutils and
  forkjoin test-compile during this build).
- `-Dcheckstyle.skip=true` — the `maven-checkstyle-plugin` is bound in the
  root build and would run `checkstyle:check` against the generated +
  hand-written sources. Checkstyle is style policy, irrelevant to a
  validation build, and is a known friction point on newer toolchains.
  Skipping it keeps the build focused on compile-green.

## What is deliberately NOT activated (and stays that way)

- **maven-enforcer-plugin** — lives in an id-only profile
  (`-Pmaven-enforcer-plugin`) with **no** `<activation>`, so it is OFF by
  default. Its `requireJavaVersion` rule pins **exactly 17** and would FAIL
  on JDK 25. Do not pass `-Pmaven-enforcer-plugin` on this machine. (It is
  only switched on in CI on a JDK-17 runner.)
- **bnd-maven-plugin / OSGi** — only in the `bnd-maven-plugin` profile,
  activated by the `performRelease` property. Off for normal builds.
- **maven-javadoc-plugin** aggregate / **spotless** / **jacoco** /
  **errorprone** / **maven-dependency-plugin** analyze — all gated behind
  profiles or `performRelease`; none run in the command above.
- **spotless** is additionally globally disabled via
  `spotless.check.skip=true` in the root pom properties.

## Code generation (StringTemplate)

Generation is real and runs every build (sources are a build product, not
checked in). The `eclipse-collections-code-generator-maven-plugin`
`generate-sources` goal expands the `.stg` templates:

- `eclipse-collections-api/target/generated-sources/java` — ~1354 `.java`
  files generated, ~1595 sources compiled.
- `eclipse-collections/target/generated-sources/java` — 1478 `.java` files
  generated, ~2139 sources compiled.

## Expected non-fatal warnings on JDK 25

- `sun.misc.Unsafe is internal proprietary API ...` in
  `ConcurrentHashMapUnsafe.java` (impl).
- `[removal] SecurityManager / getSecurityManager() / AccessController`
  deprecations in `CollectionsThreadFactory.java` and
  `ConcurrentHashMapUnsafe.java`.

These are warnings only; compilation is green. They are upstream code and
will need attention eventually but are out of scope for build-unblock.

## Modules / pom edits in this phase

**None.** This is a flags-only solution. The root pom `<modules>` list and
every module pom are unchanged from upstream. No directories were deleted.

## Cross-language validation runner

The standalone, unpublished `mapdb-validation/` module (not in the root
`<modules>`) runs the cross-language conformance scenarios against this stock
EC build; see [`mapdb-validation/README.md`](mapdb-validation/README.md) for
build and run commands.

## Native (port-specific) tests

The standalone, unpublished `mapdb-native-tests/` module (also NOT in the root
`<modules>`) runs the port's native-test obligations (raw-bit float identity,
IEEE totalOrder, sentinel boundaries, 64-bit Fibonacci hash spread,
serialization smoke, IntInterval boundaries) — the spec-required tests that the
shared cross-language scenario suite does not exercise.

It depends on the locally-installed `eclipse-collections-api` and
`eclipse-collections` SNAPSHOT artifacts, so run the core install command above
first, then:

```
mvn -f mapdb-native-tests/pom.xml test
```

Expected: `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
The distinct-NaN-payload tests are the headline proof of the raw-bit float
identity change — they FAIL on stock (unmodified) Eclipse Collections, which
canonicalizes every NaN to `0x7FC00000` via `Float.floatToIntBits`.

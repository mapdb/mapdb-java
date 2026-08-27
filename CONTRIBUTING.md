Contributing to mapdb-java
==========================

`mapdb-java` (the `org.mapdb.collections.*` Java port) is a fork of
[Eclipse Collections](https://github.com/eclipse-collections/eclipse-collections),
maintained by Jan Kotek as one of the language ports governed by the
[mapdb collection spec](https://github.com/mapdb/mapdb-collection-spec). It is
**not** an Eclipse Foundation project; there is no Eclipse Contributor
Agreement and no `collections-dev@eclipse.org` involvement.

Issues
------

Search the [issue tracker](https://github.com/mapdb/mapdb-java/issues) for a
relevant issue or open a new one.

The contract
------------

Observable behaviour is defined by the cross-language spec, not by this repo in
isolation. Before changing behaviour, read
[`BUILD-MAPDB.md`](BUILD-MAPDB.md) and the spec's
[`style/java.md`](https://github.com/mapdb/mapdb-collection-spec/blob/main/spec/style/java.md),
which records the fork's carve-outs (raw-bit float identity, 64-bit Fibonacci
hash, IntInterval-only Interval, object-fallback trees/multimaps). A change that
alters observable behaviour must come with a matching update to the
cross-language validation scenarios in the spec repo.

Building
--------

- Java 17+ (release target 17), Maven 3.9.6+.
- The build performs StringTemplate code generation to create the primitive
  collections; generated sources are a build product and are not checked in, so
  run the full build once before opening your IDE:

```bash
mvn clean install -DskipTests=true
```

`BUILD-MAPDB.md` documents the exact build, the cross-language validator, and
the native test suite. All three are run in CI
([`.github/workflows/build.yml`](.github/workflows/build.yml)) and must stay
green:

- `mvn clean install` of the core plus the inherited unit-test suites,
- the `mapdb-validation` runner (298 cross-language scenarios),
- the `mapdb-native-tests` suite (the port-specific battery).

Coding style
------------

Match the surrounding code. The inherited Eclipse Collections checkstyle config
is present but **off by default** (style policy, not a build gate); you can run
it locally for a style pass. Avoid whitespace-only changes on unrelated lines so
`git blame` stays accurate.

Commits and pull requests
-------------------------

- [Use the imperative mood][imperative-mood] ("Fix bug", "Add feature").
- Reference the GitHub issue when relevant.
- Squash to a small number of clean commits and rebase on the base branch before
  opening a pull request; no merge commits.
- Make sure CI is green. A maintainer will review and merge.

Thanks for contributing!

[imperative-mood]: https://github.com/git/git/blob/master/Documentation/SubmittingPatches

# ADR-003: Gradle multi-module build with Kotlin DSL

Status: **Superseded by [ADR-009](009-bazel-build.md)** (2026-08-24).
Originally accepted 2026-08.

> The build system is now Bazel. The content below is preserved unchanged as
> the historical reasoning. Its best idea — conventions in exactly one place —
> was not superseded at all. ADR-009 first moved it from `build-logic/`
> convention plugins to `tools/bbv.bzl`; ADR-010 later replaced those target
> generators with package-local explicit rules and values-only
> `tools/java_test_settings.bzl` policy.

## Context

The codebase is deliberately split into fifteen modules with one-way
dependencies (capture never depends on UI, storage never depends on
enrichment, and so on) so architectural boundaries are compiler-enforced.
The build system must make that cheap, keep builds reproducible, and later
drive native packaging.

## Decision

Gradle (wrapper-pinned, currently 9.7.1) with the Kotlin DSL and an included
`build-logic` build providing convention plugins:

- `bbv.java-common` — toolchain (Java 25, ADR-008), UTF-8, `-parameters`,
  JUnit Jupiter + AssertJ test wiring, slf4j-api, reproducible archives
  (no timestamps, stable file order), dependency locking on all
  configurations.
- `bbv.java-library` / `bbv.java-application` — thin roles on top of common.

Third-party versions live only in `gradle/libs.versions.toml`. Dependency
locking is enabled; lockfiles are regenerated with
`./gradlew resolveAndLockAll --write-locks --no-configuration-cache`.
Parallel builds, build caching, and the configuration cache are on in
`gradle.properties`. `jpackage`-based distribution tasks will be added to
`bbv.java-application` when packaging work starts (Phase 9-ish), not before.

## Consequences

- Every new module is ~5 lines of build script; all policy edits happen once
  in `build-logic`.
- Cross-module reaching (`subprojects {}` mutation, ad hoc `allprojects`)
  is forbidden — conventions are the only shared-configuration channel.
- Locking means adding a dependency is a deliberate two-step (catalog entry +
  lockfile regeneration), which is intended friction.
- Kotlin DSL costs a little first-configuration time, repaid by type-checked
  build scripts and the configuration cache.

## Revisit when

Gradle's module count or configuration time becomes a measured bottleneck, or
`jpackage` integration demands restructuring `bbv.java-application`.

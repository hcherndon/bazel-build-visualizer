# ADR-010: Package-local native Bazel targets

Status: **accepted** (2026-08-28).

Amends [ADR-009](009-bazel-build.md). Bazel 9.2.0, bzlmod, the Java 25
toolchain, locked dependencies, test safety fences and protobuf generation
remain unchanged. This ADR replaces only ADR-009's module-wide convention
macros and source layout.

## Context

The first Bazel cutover deliberately mirrored the old Gradle modules: one
source library and one generated test suite per module. That made parity easy,
but it hides dozens of independent Java packages behind one compilation
action. A small change therefore invalidates much more work than its imports
require, and the `ui-swing` BUILD file describes its test packages through a
large dictionary consumed by a custom macro. The resulting labels do not match
the source tree a contributor is editing.

The repository now has enough source and tests for those coarse boundaries to
matter. Bazel can cache and schedule package-sized compilation actions only if
the BUILD graph exposes them.

## Decision

- Put a `BUILD.bazel` beside every directory containing Java source. An
  acyclic production Java package gets one `java_library` whose direct
  dependencies match its imports.
- Use native `java_library`, `java_binary`, `alias`, `filegroup` and
  `test_suite` rules directly. Use contrib_rules_jvm's public
  `java_junit5_test` rule directly for JUnit 5. BUILD files do not generate
  targets from dictionaries, loops or owned rule macros.
- Compile tests once per Java package and keep one explicit test runner per
  test class. This preserves per-class execution and reporting while limiting
  test-source invalidation to the package that changed.
- Keep small module-root aggregate libraries and test suites as compatibility
  and discovery entry points. Production and test targets inside the
  repository depend on package-local labels, not those aggregates.
- `tools/java_test_settings.bzl` contains policy values such as the
  native-access flag, JUnit dependencies and real-Bazel tags. It does not wrap
  or create rules.
- Break Java-package dependency cycles when they prevent honest Bazel package
  boundaries. Display value formatting moves out of Events so UI session
  services do not depend back on their consumer. The high-level process
  launcher moves out of the runner's low-level process package.
- Move the `bazel-runner` source tree to `runner`. Bazel reserves top-level
  `bazel-*` paths in its execroot, and the old name required an owned
  source-relocation rule. The old `//bazel-runner` package remains as aliases
  for established entry labels; no compile action reads source from it.
- Retain the native protobuf `genrule`, JMH annotation-processor setup and
  packaging shell rules. They perform work Bazel still needs and are not
  convention wrappers.

## Consequences

- Package libraries and package test libraries can compile, cache and schedule
  independently. A package's BUILD file states the dependencies of the code
  next to it.
- BUILD files are more numerous and more explicit. That repetition is chosen:
  reviewing a native rule is cheaper than mentally expanding a repository
  macro, and build behavior no longer depends on a custom target generator.
- Module-root aggregate targets remain convenient but are intentionally not
  the dependency path for repository code. They may rebuild as broad facades;
  package-local consumers do not pay that cost.
- Moving a class between Java packages now normally requires moving its BUILD
  ownership and updating explicit labels. That is useful friction: the Java
  dependency change and the build dependency change are reviewed together.
- The change improves graph granularity structurally. No wall-clock speedup is
  claimed until clean and incremental builds are measured under a documented
  method.

## Revisit when

- Native Java dependency inference becomes reliable enough to keep explicit
  BUILD files current without introducing a second build description.
- A measured package library is too small and analysis/action overhead exceeds
  its cache or parallelism benefit. Merge only that boundary, with the
  measurement recorded.
- Java modules (`module-info.java`) replace the unnamed classpath. Package
  visibility and native-access policy then need a new decision.

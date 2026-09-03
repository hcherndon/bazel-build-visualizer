# ADR-009: Bazel as the build system

Status: **accepted** (2026-08-24; amended 2026-08-25 and 2026-08-28; drafted
2026-08-22 as proposed).
Supersedes [ADR-003](003-gradle.md), which is marked accordingly. Accepted
with the migration itself: the version pins were re-verified against the
Bazel Central Registry on the acceptance date and all resolved unchanged,
and the plan was revised for two days of drift on `main` (five new catalog
artifacts, a new module edge, jpackage/notarize tasks now in scope, the
`bazel-sweep` hazard tag, and a sixth spike binary — details in the plan's
revision notes). Scope, mechanics, sequencing, and the parity gate live in
[docs/bazel-migration-plan.md](../bazel-migration-plan.md); this ADR is the
decision and its costs.

## Context

ADR-003 chose Gradle, and none of its "revisit when" triggers has fired:
module count is not a bottleneck, configuration time is not measured pain,
and jpackage work has not started. This supersession is argued on different
ground, and says so plainly rather than pretending a trigger fired.

The product is a Bazel build visualizer. Its own build is the one
non-trivial Bazel workspace this project could exercise daily at zero
marginal cost — and today it cannot, because the repo builds with Gradle.
Every Phase 2 capability (launch, instrumentation plan, loopback BES
capture, cancellation) can be pointed at the repository that implements it
the day the repository is a Bazel workspace. That standing self-fixture —
real, evolving, maintained by construction — is worth more to this project
than to almost any other Java repo, and it is the reason this ADR exists.

Secondary context that makes the move cheap *now* rather than merely
possible: Phase 2 just closed green and audited, giving a complete suite to
diff against; the codebase only grows from here (the ADR-008 timing
argument, reused deliberately); Bazel 9 is bzlmod-only, so a migration
writes one dependency universe with no legacy mode; protobuf ≥ 33.4 ships
prebuilt protoc, so this pure-Java repo needs no C++ toolchain; and
rules_java provides remote JDK 25 toolchains matching the ADR-008 baseline.

## Decision

Bazel **9.2.0** (current stable LTS), pinned in `.bazelversion` and run via
**Bazelisk**, which is the only thing a contributor installs — bazelisk
fetches Bazel, Bazel fetches the JDK. Specifically:

- **Toolchain:** `--java_language_version=25`,
  `--java_runtime_version=remotejdk_25` (rules_java 9.8.0). Hermetic by
  default; the foojay resolver's job disappears rather than migrates. The
  ADR-008 constraints ride along unchanged: preview features stay
  forbidden, and `--enable-native-access=ALL-UNNAMED` moves into the shared
  macros as the single written occurrence. ADR-010 later moved that value to
  `tools/java_test_settings.bzl` while removing the rule wrappers.
- **Conventions:** the build-logic convention plugins become `tools/bbv.bzl`
  macros (`bbv_java_library`, `bbv_java_test_suite`, `bbv_java_binary`)
  with the same contract: a module's BUILD file stays ~5 lines, policy
  edits happen once. Gradle's `api`/`implementation` split maps to
  `exports`/`deps`, which Bazel's strict-deps checking enforces harder than
  Gradle could. **Superseded for the current source tree by
  [ADR-010](010-package-local-bazel-targets.md):** the parity-era module-wide
  macros are replaced by explicit package-local native rules. The policy
  remains centralized only where it is a value rather than a rule generator.
- **Dependencies:** rules_jvm_external 7.1; the version catalog transplants
  verbatim into one `maven.install`; `maven_install.json` +
  `MODULE.bazel.lock` are committed and replace the 17 Gradle lockfiles.
  The deliberate-friction contract survives: adding a dependency is a
  MODULE.bazel edit plus an explicit repin.
- **Protobuf/gRPC:** one genrule runs the *same sha256-pinned binaries
  Gradle resolved* (protoc 4.36.0, protoc-gen-grpc-java 1.83.1
  maven-central exes) directly over the vendored sources (vendoring and
  `update-protos.sh` unchanged); no `proto_library` is declared — with the
  pinned exes doing all codegen it would only add a second, unused
  compilation path — and the runtime classpath stays the audited maven
  jars. The BCR `grpc-java` module is deliberately **not** used: it trails
  at 1.82.0, substitutes source-built targets for the locked jars, and
  pours ~30 unchosen artifacts into the maven hub. Generated-source parity
  with Gradle was byte-diffed at cutover, not asserted.
- **Tests:** contrib_rules_jvm's JUnit-Platform runner, one test target per
  class for parallelism and per-class reporting. A module compiles its test
  sources into one shared library by default because test classes legitimately
  share helpers. A large module may declare coherent functional source groups;
  each group then compiles once, exposes a named scoped suite, and carries only
  its own test binaries' cache keys. Execution remains per class in both forms.
  This 2026-08-25 amendment narrows invalidation without pretending every test
  class is independently compilable. ADR-010 supersedes that target shape:
  tests compile once per Java package, with an explicit runner per class. The
  seven real-Bazel test classes (six whole classes plus `RealBazelCliRunTest`,
  split out of the mixed `CliRunTest`) run un-sandboxed with inherited
  environment and uncached results. CI excludes them **visibly**, as the
  `--config=ci` tag filter in `.bazelrc`, and they run on developer
  machines — the guard against "silently skipped and green", the precise
  failure mode `BazelBinary` exists to prevent, is that any skip is loud
  (`BazelBinary.whyUnavailable()`) and any exclusion is written in the
  config rather than shaped by the environment.
- **Cutover, not coexistence:** Gradle remains the build of record until
  the parity checklist passes, then is deleted whole in one change. Two
  supported builds would be two places for the build to lie about itself.

## Consequences

- **The tool can capture its own build.** `bbv run -- build //...` in this
  repo becomes the standing end-to-end fixture, and dogfooding is a
  routine, not an event.
- Contributor prerequisites drop to bazelisk alone — no JVM, no wrapper
  bootstrap. CI loses its setup-java step.
- Test iteration improves structurally: target-level caching means an edit
  reruns the classes it can affect, not every module's suite. Functional source
  groups keep a test-only edit from invalidating unrelated test binaries in a
  large module while preserving package-local shared fixtures.
- **Packaging gets more expensive — and the bill is already due.** When
  this ADR was drafted, jpackage was future Phase 9 work; by acceptance the
  Gradle `jpackage`/`notarize` tasks existed (landed 2026-08-23), so the
  migration ports them immediately as owned `bazel run` targets over the
  deploy jar rather than deferring the cost. The signing/notarization
  env-gating (`BBV_MAC_SIGNING_IDENTITY` / `BBV_MAC_NOTARY_PROFILE`)
  carries over unchanged.
- The IDE story changes: JetBrains' Bazel plugin plus a committed
  `.bazelproject` replaces Gradle import. Watched as the top
  contributor-experience risk during the module-by-module step, while
  Gradle import still exists as the escape hatch.
- At cutover, ~100 lines of owned Starlark (macros + codegen genrules)
  replaced two maintained Gradle plugins. ADR-010 removes the Java rule
  generators; the pinned protobuf codegen remains.
- Benchmarks now *compile* in `bazel build //...` (rot protection);
  running them stays manual. The `printRuntimeCp` helper dies with no
  consumer. The "-D properties don't reach forked JVMs" finding retires,
  replaced by the java_binary stub's `--jvm_flag=`.
- Convenience symlinks are ignored by **anchored** names
  (`/bazel-bin`, …) — a blanket `bazel-*` would hide the tracked
  `bazel-runner/` compatibility package from `git status`, the same anchoring
  lesson the .gitignore already records for `build/`. ADR-010 moves its source
  to `runner/` to avoid Bazel's reserved execroot prefix.
- ADR-003 stays in the tree marked superseded, reasoning intact. Its best
  idea — conventions in exactly one place — is not superseded at all; it
  just changed language.

## Revisit when

- **BCR grpc-java tracks current releases** — readopt the question of
  idiomatic `java_grpc_library` (its shaded-netty blocker has already
  fallen; the version-authority and provenance objections are what remain).
- **Packaging grows beyond jpackage app images** — the port above covers
  what the Gradle tasks did (app-image/dmg/pkg, env-gated signing and
  notarization); anything more (Windows installers, Linux packages,
  update channels) is new work, priced against the owned-script approach
  rather than a Gradle plugin.
- **Remote execution or a second build platform ever appears** — the
  codegen exes are selected per target platform on the assumption
  host = exec = target, which is true for this desktop repo and must be
  revisited the day it is not.

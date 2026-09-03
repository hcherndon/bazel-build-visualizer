# Gradle → Bazel migration: scope and plan

Status: **accepted and executed** — [ADR-009](adr/009-bazel-build.md) was
accepted 2026-08-24 and the migration carried out with it;
[ADR-003](adr/003-gradle.md) is marked superseded. Written 2026-08-22;
every version pin below was verified against its registry on that date and
re-verified on 2026-08-24 (all resolved unchanged). Main had moved in the
two days between drafting and execution; the drift is corrected in place
below and summarized here:

This is the historical cutover record. [ADR-010](adr/010-package-local-bazel-targets.md)
later replaced its module-wide macro and source-layout choices with
package-local native targets; the executed evidence below is not rewritten.

- **Five catalog additions the draft predates** (all now in the
  `maven.install` list): `com.fifesoft:rsyntaxtextarea:3.6.1`,
  `com.fifesoft:autocomplete:3.3.3`,
  `com.github.vertical-blank:sql-formatter:2.0.5` (the Query card's editor
  stack, user-approved 2026-08-24), `io.airlift:aircompressor:2.0.3` and
  `com.google.code.gson:2.14.0` (enrichment's execution-log and profile
  parsers).
- **A new module edge**: `storage-sqlite` now depends on `analysis-core`
  (aggregate persistence meets the aggregate types there), so step 4's
  dependency order changes — see §6.
- **jpackage/notarize are in scope after all**: the Gradle `jpackage` and
  `notarize` tasks landed 2026-08-23, so §10's "Phase 9 work" deferral is
  false and the tasks are ported (as `bazel run` scripts over the deploy
  jar, signing/notarization still env-gated).
- **The `bazel-sweep` hazard tag postdates the draft**:
  `BazelVersionMatrixTest` sweeps four Bazel versions and is excluded from
  the default Gradle build by tag; its Bazel translation is in §4.3.
- **The proposed `.bazelrc` lacked the OUTER server's memory cap** — the
  fixture rc caps the child servers the tests spawn, but the workspace's
  own server was uncapped. Bazel sizes its server JVM from machine RAM,
  and several uncapped servers alive at once have crashed a development
  machine at over 120 GB resident. The shipped `.bazelrc` opens with
  `startup --host_jvm_args=-Xmx4g` / `startup --max_idle_secs=300`.
- **Open question 5 resolved**: the real-Bazel surface is now six whole
  classes plus a mixed `CliRunTest` (3 of its 8 methods tagged); the
  mixed class is split rather than given inherited-env dispensation.
- **benchmarks has six spike binaries** (`EntityScaleSpike` is new), not
  five.

## 1. Why, and why now

The product is a Bazel build visualizer that cannot today capture its own
build, because its own build is Gradle. Migrating makes the repository its
own first fixture: `bbv run -- build //...` against this workspace exercises
the Phase 2 capture path on a real, non-trivial, multi-language-free Bazel
build every day, for free. That is the strategic reason, and it is honestly
not one of ADR-003's "revisit when" triggers — Gradle has no measured
bottleneck here. ADR-009 supersedes on strategy, and says so.

The timing argument is the same one ADR-008 used for Java 25: **the codebase
only grows.** Phase 2 just closed with a green, audited baseline — 15 modules,
~1,700 lines of build logic, one CI workflow, and a complete test suite to
diff against. Phase 3 onward adds the normalization pipeline, enrichment,
and the heavy views on top of whatever build system is underneath. Every
phase deferred makes the same migration strictly larger.

Two facts external to this repo make now workable at all:

- **Bazel 9 is bzlmod-only** (WORKSPACE support removed outright), so a
  greenfield migration writes exactly one dependency universe with no legacy
  mode to also support.
- **Protobuf ≥ 33.4 ships a prebuilt protoc** behind
  `--@protobuf//bazel/toolchains:prefer_prebuilt_protoc`, so a pure-Java
  repository no longer buys a C++ toolchain the first time it declares a
  `proto_library`.

## 2. Version pins

| What | Pin | Why this one |
|---|---|---|
| Bazel | **9.2.0** via `.bazelversion` | Latest stable LTS release (2026-07-13; the 8.8.0rc2 of 2026-08-18 is an 8.x-line backport RC). Same version the vendored protos are pinned to and the newest version the ground-truth experiments ran against — the build tool and the protocol schema move together. |
| Bazelisk | ≥ 1.29.0, not pinned | The installer, not a dependency: contributors get it from Homebrew/npm/GitHub releases; GitHub runners preinstall 1.29.0. `.bazelversion` is what pins reality. |
| rules_java | 9.8.0 | Latest in BCR; ships `remotejdk25_*` toolchains for the ADR-008 baseline. |
| rules_jvm_external | 7.1 | Latest in BCR; maven hub + `maven_install.json` locking. |
| protobuf (BCR module) | 36.0 | Exactly matches `protobuf-java` 4.36.0 already in the catalog — rule definitions, prebuilt protoc, and the runtime jar stay one version. |
| contrib_rules_jvm | 0.34.0 | Latest in BCR; supplies the JUnit Platform test runner and `java_test_suite`. Known-good with JUnit Platform 6.x. |
| protoc (codegen binary) | 4.36.0, sha256-pinned maven-central exe | Same binary Gradle resolves today (`com.google.protobuf:protoc`). See §4.1. |
| protoc-gen-grpc-java | 1.83.1, sha256-pinned maven-central exe | Same binary Gradle resolves today. See §4.1 — this is what keeps gRPC at 1.83.1 instead of downgrading to the BCR module's 1.82.0. |
| grpc-java (BCR module) | **not used** | Deliberate. See §4.1. |

Contributor install story (README replacement text): install bazelisk
(`brew install bazelisk`), clone, `bazel test //...`. Nothing else — not
even a JVM: bazelisk fetches Bazel 9.2.0 per `.bazelversion`, and rules_java
fetches the remote JDK 25. That is strictly stronger than today's
"a JVM able to run the Gradle wrapper".

## 3. Inventory: everything the Gradle build does, and its Bazel carrier

The convention plugins and per-module scripts were read line by line for
this table. Anything not listed here was checked and found to be free
(plain `java`/`java-library` behavior with no configuration).

| Current behavior (where) | Bazel carrier |
|---|---|
| Java 25 toolchain, auto-provisioned via foojay (`bbv.java-common`, ADR-008) | `.bazelrc`: `--java_language_version=25 --java_runtime_version=remotejdk_25` (rules_java remote Temurin). Local Corretto is ignored — hermetic by default, no resolver plugin needed. |
| Preview features forbidden (ADR-008) | Nothing to carry: no `--enable-preview` anywhere. The ADR-008 caveat that incubator imports fail only at runtime is unchanged by the build system. |
| UTF-8 + `-parameters` on every compile | `--javacopt=-parameters` (encoding is UTF-8 by default on JDK 18+; state it anyway in the macro for greppability). Set in the shared macro, not per target. |
| `--enable-native-access=ALL-UNNAMED` on **every forked JVM** — tests, `:app:run`, start scripts, spikes, JMH (load-bearing for sqlite-jdbc + FlatLaf; ADR-008) | `jvm_flags` in the `bbv_java_test_suite` / `bbv_java_binary` macros (§4.4) — single source of truth, same as today's `extra["bbvNativeAccessArg"]`. |
| Test tasks: JUnit Platform, 2 GiB heap, failed/skipped logging | contrib_rules_jvm JUnit5 runner (`java_test_suite(runner = "junit5")`), `jvm_flags = ["-Xmx2g", nativeaccess]`. Per-test-class targets replace one JVM per module — see §5 wins. |
| JUnit Jupiter 6.1.3 + AssertJ + launcher wired into every module | Macro adds `@maven//:org_junit_jupiter_junit_jupiter`, assertj, launcher to every suite. One new artifact: `junit-platform-reporting` (the runner's XML writer needs it — verify at step 0). |
| Headless AWT pinned for `app` and `ui-swing` tests (GraphicsEnvironment caches first answer) | `jvm_flags = ["-Djava.awt.headless=true"]` on those two suites. Per-class JVMs actually shrink the ordering hazard the Gradle comment worries about. |
| slf4j-api injected into every module | Macro default dep. Kept explicit in the macro so the BUILD files stay 5-liners like the Gradle scripts were. |
| Reproducible archives (ADR-003) | Free: Bazel jars are deterministic (fixed timestamps, stable order) by construction. Verified at step 8 by double-build hash compare. |
| Dependency locking on all configurations (ADR-003); 16 `gradle.lockfile`s; catalog in `libs.versions.toml` | One `maven.install` in MODULE.bazel listing the same GAVs (the catalog transplanted, comments included) + committed `maven_install.json` (sha256 per artifact) + committed `MODULE.bazel.lock`. Deliberate-friction contract preserved: adding a dep = MODULE.bazel edit + `REPIN=1 bazel run @maven//:pin`. |
| protobuf codegen: protoc 4.36.0 + grpc plugin 1.83.1 over vendored protos; descriptor set **with imports** (`proto/build.gradle.kts`) | §4.1. `proto_library` targets (+ descriptor sets via protobuf's rules) and genrule-driven java/grpc srcjars from the identical pinned binaries. Descriptor-set-with-imports has **no consumer in the tree today** (verified by grep) — carried anyway because the Gradle build promises it. |
| `application` plugin: `bbv` launcher name, macOS dock JVM args, `-D` forwarding into `:app:run` (`app/build.gradle.kts`) | `java_binary(name = "bbv")`. macOS args via `select({"@platforms//os:macos": ...})` — an improvement: Gradle keys on the *build* host OS, Bazel on the target platform. `-D` forwarding pattern is replaced wholesale: `bazel run //app:bbv -- --jvm_flag=-Dbbv.smoke=true …` (the java_binary stub accepts leading `--jvm_flag=` args). Memory finding "Gradle `-D` properties do not reach forked JVMs" retires with the build system. |
| `installDist` start scripts (README workflow) | `bazel run //app:bbv -- …` day to day; `bazel build //app:bbv_deploy.jar` + `java --enable-native-access=ALL-UNNAMED -jar` for an installable single file. README section rewritten at step 8. |
| 6 spike `JavaExec` tasks (EntityScaleSpike joined after drafting), 4 GiB heap, `-D` forwarding, offscreen args (`benchmarks/build.gradle.kts`) | 6 `java_binary` targets from one list in the benchmarks BUILD (same loop, Starlark instead of Kotlin). `bazel run //benchmarks:table_spike -- --offscreen`. |
| JMH via me.champeau.jmh plugin, detached from `check` | No plugin: `java_library(srcs = glob(src/jmh/java))` + `java_plugin` on `jmh-generator-annprocess` + `java_binary(main_class = "org.openjdk.jmh.Main")`. Two new maven artifacts: `org.openjdk.jmh:jmh-core:1.37`, `:jmh-generator-annprocess:1.37` (today the plugin supplies them). Behavior change, accepted: `bazel build //...` now *compiles* benchmarks (rot protection); running stays manual (`bazel run //benchmarks:jmh -- <filter>`). |
| `printRuntimeCp` helper task (test-support) | Dropped — grep found no consumer. `bazel run` / deploy jars are the replacement if the need returns. Noted in ADR-009. |
| `resolveAndLockAll` task | `REPIN=1 bazel run @maven//:pin`. |
| Real-Bazel tests (6 whole classes plus 3 of `CliRunTest`'s 8 methods, `@Tag("real-bazel")`, self-skipping via `BazelBinary.find()`) | §4.3 — the one place Bazel's sandbox actively fights the suite. The mixed `CliRunTest` is split into `CliRunTest` (plain) and `RealBazelCliRunTest`. |
| The four-version Bazel sweep (`BazelVersionMatrixTest`, `@Tag("bazel-sweep")`, excluded from the default build; run via `-Pbbv.bazelSweep=true`) | §4.3 — a dedicated target tagged `["bazel-sweep", "manual", …]`: `manual` keeps it out of every `//...` wildcard, and the rc default `test --test_tag_filters=-bazel-sweep` is the second fence. Run it deliberately, never casually — it starts four Bazel servers and has crashed machines. |
| Gradle perf tuning: parallel, build cache, config cache (gradle.properties, docs/performance.md) | Bazel-native: parallel and incremental by default; `--disk_cache` in `.bazelrc` for cross-clean reuse. Config-cache equivalent is Skyframe — nothing to configure. performance.md's build-tuning section rewritten with measured Bazel figures at step 10, not promises. |
| CI: setup-java + setup-gradle, `./gradlew check`, macOS 14 + Ubuntu, test-report upload (.github/workflows/ci.yml) | §4.5. Preinstalled bazelisk honors `.bazelversion`; `bazel test //...`; cache keyed on the two lockfiles; upload `bazel-testlogs` on failure. setup-java disappears entirely. |
| `.gitignore` build outputs | Add `/bazel-bin`, `/bazel-out`, `/bazel-testlogs`, `/bazel-bazel-bep-viewer` — **explicitly anchored, never `bazel-*`**, which would swallow the tracked `bazel-runner/` module. The .gitignore's own header comment records this exact class of mistake for `build/`; the new entries get the matching comment. |
| `group`/`version` coordinates | Dropped — nothing is published to Maven. Version string lives where it's consumed (app metadata) when Phase 9 needs one. |

## 4. The four design decisions

### 4.1 Protobuf + gRPC codegen: pinned binaries, all-maven classpath (not the grpc-java module)

The idiomatic route — `bazel_dep` on `grpc-java`, `java_proto_library` +
`java_grpc_library` — was investigated and **rejected for now**, for reasons
worth recording precisely because they will decay:

- BCR `grpc-java` is at **1.82.0**; this repo locks **1.83.1**. The module
  `maven.override`s every `io.grpc:*` coordinate (including
  `grpc-netty-shaded` → `@io_grpc_grpc_java//netty:shaded_maven`, so the
  shading guarantee would survive) onto **source-built targets at its own
  version**. Adopting it means the build tool, not the catalog, chooses the
  gRPC version — backwards, by this repo's rules.
- Runtime classes would come from source builds rather than the
  checksummed maven jars the lockfile audits. The classpath would no longer
  be the thing that was reviewed.
- Its `maven.install` contributes ~30 artifacts into the shared `maven` hub
  (netty, guava, okhttp…), so `maven_install.json` grows a universe nobody
  here chose — against the "every third-party addition is deliberate"
  contract.

**Decision:** reproduce the Gradle pipeline exactly, under Bazel:

- One codegen genrule runs protoc directly over the vendored sources with
  `-I proto/src/main/proto`, so the repo-relative imports resolve unchanged
  — the vendoring layout and `update-protos.sh` survive untouched. No
  `proto_library` is declared (executed refinement of the draft: with the
  pinned exes doing all codegen, `proto_library` would add a second,
  unused compilation path and the prebuilt-protoc flag it needed).
- The genrule runs the **same two binaries Gradle downloads today** —
  `com.google.protobuf:protoc:4.36.0` and
  `io.grpc:protoc-gen-grpc-java:1.83.1` maven-central `.exe` artifacts —
  fetched per-platform via `http_file` (`use_repo_rule`) with sha256 pins
  (osx-aarch_64 and linux-x86_64, the two platforms that exist here;
  more pins the day another appears), selected by platform. protoc writes
  `.srcjar` outputs natively (jar-suffixed output paths), plus the
  descriptor set with `--include_imports` — the Gradle artifact, bit for
  bit.
- One `java_library` per generated srcjar pair, `deps`/`exports` on
  `@maven//:com_google_protobuf_protobuf_java`, grpc-api/stub/protobuf —
  the identical 1.83.1/4.36.0 maven jars on the classpath as today.

Parity is then *provable*, not argued: same protoc, same plugin, same
inputs ⇒ diff the generated sources against Gradle's
`proto/build/generated/` at step 3 and require zero delta.

Caveat, stated honestly: genrule tools select on the target platform, which
equals the exec platform for this single-host desktop build; if remote
execution ever appears, the exes get promoted into a real toolchain.
**Revisit trigger** (in ADR-009): when BCR grpc-java tracks current releases,
reconsider the idiomatic route — the shaded-netty blocker already fell.

### 4.2 Tests: contrib_rules_jvm JUnit-Platform runner, one target per class

The draft proposed `java_test_suite(runner = "junit5")` from
contrib_rules_jvm — one `java_test` per test class, each compiling its own
source file. **Executed refinement:** this codebase's test classes
legitimately use each other's helpers (`BfsTest` builds graphs with
`CsrGraphTest.stream`), so per-class compilation does not build. What
shipped (`bbv_java_test_suite` in `tools/bbv.bzl`) compiles all of a
module's test sources once into a shared testonly `java_library` and runs
one runtime-only `java_junit5_test` per class over it. Invalidation is
therefore per module — exactly Gradle's granularity, no worse — while
execution, reporting, flake isolation and parallelism stay per class. The
runner is contrib_rules_jvm's JUnit Platform runner against the repo's own
JUnit **6.1.3** artifacts from `@maven`, verified working (XML included);
the draft's fallback — an ~80-line owned launcher — stayed unwritten.

`assumeTrue`-skips keep working unchanged (they surface as skipped in the
XML, same as under Gradle).

### 4.3 The real-Bazel test classes: inherited environment, no sandbox, never silently weakened

The real-Bazel surface at execution time is seven classes across three
modules — `RealBazelCaptureTest`, `RealBazelBesTest`, `RealBazelGraphTest`,
`RealBazelNormalizationTest`, `RealBazelEnrichmentTest` (capture-bes),
`RealBazelCapabilityTest` (bazel-runner), and `RealBazelCliRunTest` (app,
split out of the mixed `CliRunTest` — resolving open question 5). They find
a host Bazel via `BBV_TEST_BAZEL` / a dev bazelisk / `PATH`, and spawn
Bazel servers that are *designed* to escape the process tree (PPID 1 —
memory finding 9). Under default `bazel test` they would all silently skip:
scrubbed env means no `PATH` bazelisk, and the sandbox would block the
server's `output_user_root` and bazelisk's download cache anyway.

Silent skipping is exactly what `BazelBinary`'s contract forbids CI to
drift into. So these classes are excluded from the suites and declared
as dedicated per-class test targets:

```python
tags = ["real-bazel", "no-sandbox", "external", "requires-network"],
env_inherit = ["PATH", "HOME", "BBV_TEST_BAZEL", "USE_BAZEL_VERSION"],
size = "large",
```

`no-sandbox` lets the child server live; `env_inherit` lets discovery see
the host; `external` disables result caching — correct, because the result
depends on host state Bazel cannot fingerprint. On a bare machine they
still skip with `BazelBinary.whyUnavailable()`'s message, same as today; on
CI (bazelisk preinstalled) they run, same as today. The fixture's
`hermeticStartupOptions()` already isolates the child builds' rc files and
output roots — that work transfers unchanged.

The four-version sweep, `BazelVersionMatrixTest`, is a separate hazard
class and gets a harder fence. Under Gradle it is excluded by tag and runs
only via `-Pbbv.bazelSweep=true`; under Bazel it is its own target,

```python
tags = ["bazel-sweep", "no-sandbox", "external", "manual"],
```

where `manual` keeps it out of every `//...` wildcard entirely and the rc
default `test --test_tag_filters=-bazel-sweep` is the second fence, so even
naming a wildcard with an explicit filter cannot pick it up by accident.
It downloads and starts four Bazel server versions; run deliberately, one
version at a time, never as part of anything routine.

Deliberately **not** adopted: `rules_bazel_integration_test`. It solves
version-matrix management this suite already solves itself via
`USE_BAZEL_VERSION`, and would insert a framework between the tests and the
thing they test. Reconsider only if child-workspace management outgrows
`BazelWorkspaceFixture`. Also note the recursion is mundane: the outer
Bazel just runs a JVM; the inner Bazel is an ordinary subprocess of it.

### 4.4 Conventions become macros: `tools/bbv.bzl`

ADR-003's real asset is "every module is ~5 lines; policy lives in one
place." That transfers as a small Starlark file:

- `bbv_java_library(name, deps, …)` — wraps `java_library`; adds slf4j,
  `-parameters`, resources glob.
- `bbv_java_test_suite(name, deps, jvm_flags = [], …)` — one shared
  testonly `java_library` over the module's test sources plus one
  runtime-only `java_junit5_test` per class (§4.2's executed refinement);
  adds JUnit 6 + AssertJ + launcher/reporting deps, `-Xmx2g`, the
  native-access flag, headless AWT where asked.
- `bbv_real_bazel_test(…)` — the dedicated per-class shape for the
  hazard-tagged classes of §4.3 (grew out of execution; the draft had the
  suites only).
- `bbv_java_binary(…)` — wraps `java_binary`; adds the native-access flag.

The native-access string is written **once**, in this file, replacing
`extra["bbvNativeAccessArg"]` — same single-source-of-truth discipline,
same greppability. ADR-003's ban on cross-module mutation
(`subprojects {}`) becomes structural: Bazel has no equivalent to forbid.

A representative leaf BUILD file after migration (`storage-sqlite/BUILD.bazel`):

```python
load("//tools:bbv.bzl", "bbv_java_library", "bbv_java_test_suite")

bbv_java_library(
    name = "storage-sqlite",
    srcs = glob(["src/main/java/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = ["//core-model", "@maven//:org_xerial_sqlite_jdbc"],
    exports = ["//core-model", "@maven//:org_xerial_sqlite_jdbc"],  # was: api(...)
)

bbv_java_test_suite(
    name = "tests",
    srcs = glob(["src/test/java/**/*.java"]),
    deps = [":storage-sqlite"],
)
```

Gradle's `api` vs `implementation` maps to `exports` vs plain `deps` — the
distinction survives, and Bazel enforces it *harder* (strict deps: compiling
against a transitive dep that isn't exported is an error, with a suggested
fix). The carefully-argued `api`/`implementation` comments in the module
build files migrate as comments on `exports`.

### 4.5 Toolchain, CI, and caches

`.bazelversion`:

```
9.2.0
```

`.bazelrc` (shape; the committed file is the authority):

```
# MACHINE SAFETY, first and not optional: the OUTER server's memory cap.
# Bazel sizes its server JVM from machine RAM; this suite also spawns CHILD
# servers (capped by the fixture rc at -Xmx1g/15s), and several uncapped
# servers alive at once have crashed a development machine at over 120 GB
# resident. The draft of this plan lacked these two lines.
startup --host_jvm_args=-Xmx4g
startup --max_idle_secs=300

# ADR-008: Java 25 LTS, hermetic remote JDK on every machine and in CI.
build --java_language_version=25
build --java_runtime_version=remotejdk_25

# bbv.java-common parity: -parameters and explicit UTF-8 on every compile.
build --javacopt=-parameters
build --javacopt=-encoding
build --javacopt=UTF-8

# Bazel's default Java toolchain runs Error Prone; the Gradle build ran
# plain javac and linters are out of scope (§10). Disabled wholesale so the
# parity gate diffs builds, not linters.
build --javacopt=-XepDisableAllChecks

test --test_output=errors
# Fence two of three for the sweep (the target's own "manual" tag is the
# first); real-bazel stays included by default, like `./gradlew build`.
test --test_tag_filters=-bazel-sweep
# 2 GiB test JVMs times unbounded parallelism is the 120 GB shape again.
test --local_test_jobs=4
# A child Bazel derives its output root from TEST_TMPDIR, whose default
# location is inside the execroot — which a Bazel 9 child then mistakes for
# its own main repo and refuses to start ("repo contents cache is inside
# the main repo"). A neutral tmp root keeps the real-bazel children honest.
test --test_tmpdir=/tmp/bbv-test-tmp

# CI adds: --config=ci. CI's exclusion of real-bazel is visible here, not a
# silent environment-shaped skip.
build:ci --repository_cache=~/.cache/bazel-repo
test:ci --test_tag_filters=-bazel-sweep,-real-bazel
test:ci --test_env=CI=true
```

(`--@protobuf//bazel/toolchains:prefer_prebuilt_protoc` from the draft was
dropped: the codegen runs the pinned protoc exes directly via genrule, no
`proto_library` is declared, so the protobuf module contributes only its
runtime jars and no C++ action can exist to prevent. `--disk_cache` was
also dropped from the default config — Bazel's own output base already
survives between invocations, and a shared disk cache is a tuning decision
to make with measurements, not ahead of them.)

CI workflow replacement (shape, not final YAML): checkout →
`actions/cache` on the repository cache + disk cache, keyed on
`hashFiles('.bazelversion', 'MODULE.bazel.lock', 'maven_install.json')` →
`bazel test --config=ci //...` (preinstalled bazelisk reads
`.bazelversion`, immunizing against the runner-image Bazel-default drift
GitHub had in January) → on failure, upload `bazel-testlogs/**` (the
`test.xml`/`test.log` tree replaces `**/build/reports/tests`). setup-java
and setup-gradle steps are deleted; matrix stays macOS 14 + Ubuntu.

MODULE.bazel (skeleton — the maven list is `libs.versions.toml`
transplanted verbatim, review comments and all):

```python
module(name = "bbv")

bazel_dep(name = "rules_java", version = "9.8.0")
bazel_dep(name = "rules_jvm_external", version = "7.1")
bazel_dep(name = "protobuf", version = "36.0")
bazel_dep(name = "contrib_rules_jvm", version = "0.34.0")
bazel_dep(name = "platforms", version = "1.1.0")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "io.airlift:aircompressor:2.0.3",               # post-draft catalog addition
        "org.assertj:assertj-core:3.27.3",
        "com.fifesoft:autocomplete:3.3.3",              # post-draft catalog addition
        "com.formdev:flatlaf:3.7.2",
        "com.google.code.gson:gson:2.14.0",             # post-draft catalog addition
        "io.grpc:grpc-api:1.83.1",
        "io.grpc:grpc-netty-shaded:1.83.1",
        "io.grpc:grpc-protobuf:1.83.1",
        "io.grpc:grpc-stub:1.83.1",
        "org.junit.jupiter:junit-jupiter:6.1.3",
        "org.junit.platform:junit-platform-launcher:6.1.3",
        "org.junit.platform:junit-platform-reporting:6.1.3",  # test-runner XML; new
        "ch.qos.logback:logback-classic:1.6.3",
        "com.google.protobuf:protobuf-java:4.36.0",
        "com.google.protobuf:protobuf-java-util:4.36.0",
        "com.fifesoft:rsyntaxtextarea:3.6.1",           # post-draft catalog addition
        "org.slf4j:slf4j-api:2.0.18",
        "com.github.vertical-blank:sql-formatter:2.0.5",  # post-draft catalog addition
        "org.xerial:sqlite-jdbc:3.53.2.1",
        "org.openjdk.jmh:jmh-core:1.37",                # was plugin-supplied
        "org.openjdk.jmh:jmh-generator-annprocess:1.37",
    ],
    lock_file = "//:maven_install.json",
    fail_if_repin_required = True,
)
use_repo(maven, "maven")

# Codegen binaries: the exact exes the Gradle protobuf plugin resolves today.
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(
    name = "protoc_gen_grpc_java_osx_aarch64",
    url = "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.83.1/protoc-gen-grpc-java-1.83.1-osx-aarch_64.exe",
    sha256 = "<filled at step 2>",
    executable = True,
)
# … x3 more platforms, x4 for com.google.protobuf:protoc:4.36.0 …
```

Both lockfiles (`MODULE.bazel.lock`, `maven_install.json`) are committed —
they are the successors of the 16 Gradle lockfiles and carry the same
contract.

IDE: the JetBrains Bazel plugin (the maintained successor to IJwB) with a
checked-in `.bazelproject`. This is the one contributor-facing regression
risk called out to watch: Swing dev-loop ergonomics under the Bazel plugin
are good but different, and `.idea/` Gradle import stops working the day
Gradle is deleted.

## 5. What is genuinely won and lost

Won: self-hosting (the tool captures its own build — a standing, honest
Phase 2+ fixture); per-test-class caching and parallelism; a no-prereq
contributor setup (bazelisk only); target-level dependency enforcement
(strict deps make `api`-leak bugs compile errors); one dependency universe
instead of catalog + 17 lockfiles; deterministic outputs by construction;
CI without setup-java.

Lost or riskier, stated plainly: Gradle's `application`/`installDist`
convenience (deploy jar + `bazel run` replace it; Phase 9's jpackage work
becomes bespoke genrules — ADR-003 explicitly deferred jpackage to the
application plugin, and ADR-009 must re-own that cost); IntelliJ import
maturity; the me.champeau.jmh plugin's conveniences (JMH runs become a
plain `java_binary` invocation); Gradle's toolchain auto-detection of a
local Corretto (deliberately dropped for hermeticity); and roughly a week
of focused work that produces no product progress. The grpc/protobuf
codegen also becomes ~100 lines of owned Starlark instead of a maintained
plugin — mitigated by it being a frozen, parity-checked transliteration.

## 6. Migration sequence

Each step lands independently and leaves `main` green (Gradle stays the
build of record until step 9 — the two builds coexist on the migration
branch only long enough to diff them; they are never both "supported").

| # | Step | Exit criterion |
|---|---|---|
| 0 | **Feasibility spike** (throwaway branch): MODULE.bazel skeleton; compile `core-model` at language level 25 on remotejdk_25; run one JUnit 6.1.3 class via contrib_rules_jvm incl. XML output; genrule-generate the BEP java + grpc srcjars and compile them | `bazel test //core-model/...` green; generated grpc stub compiles against maven 1.83.1 jars; open questions from §7 answered with evidence |
| 1 | **ADR-009 accepted**; this plan revised with spike findings | ADR merged with status accepted; ADR-003 marked superseded |
| 2 | Scaffolding on the real branch: `.bazelversion`, `.bazelrc`, MODULE.bazel + both lockfiles, exe sha256 pins, `tools/bbv.bzl`, `.gitignore` entries | `bazel build @maven//…` resolves; lockfiles committed; `bazel mod tidy` clean |
| 3 | `proto/` BUILD: proto_libraries, codegen genrules, descriptor set | Generated sources diff **empty** vs Gradle's `proto/build/generated/`; descriptor set parses and contains imports |
| 4 | Module BUILD files in dependency order (corrected 2026-08-24 for the new storage-sqlite → analysis-core edge): core-model → graph-core, session-format → analysis-core → storage-sqlite → proto (independent) → bep-codec, test-support → bazel-runner → capture-file → enrichment → capture-bes → ui-swing → app, benchmarks | After each: that module's `bazel test` green. After all: test-class inventory (names + counts per class, from XML) identical to Gradle's, real-Bazel classes excepted |
| 5 | Binaries: `//app:bbv` (+ deploy jar, macOS select), 6 spike binaries, JMH binary | `bazel run //app:bbv -- import/inspect/run` behaves identically on a fixture session; smoke run via `--jvm_flag=-Dbbv.smoke=true` passes; each spike `--offscreen` PASSes; `bazel run //benchmarks:jmh -- -l` lists both benchmarks |
| 6 | Real-Bazel test targets (§4.3) | On this machine: all seven run and pass under `bazel test --test_tag_filters=real-bazel`; with `PATH` stripped they skip with the correct message |
| 7 | CI cutover on the branch: new workflow, both OSes, caching | Green on macOS + Ubuntu. (Executed revision: CI runs `bazelisk test //... --config=ci`, which excludes real-bazel — the exclusion is a named line in `.bazelrc`, visible rather than environment-shaped, and the seven classes run on developer machines per step 6. The draft's "assert non-skipped in the workflow" retired with that decision.) |
| 8 | Parity signoff (checklist §8) + docs sweep: README, troubleshooting.md, performance.md, implementation-status.md; historical docs (phase2-audit, ground-truth, product-plan) get pointer notes only, never rewrites — ADR-008 set that precedent | Checklist all green, recorded at the bottom of this document |
| 9 | **Gradle removal**: all `*.gradle.kts`, `build-logic/`, `gradle/`, `gradlew*`, `gradle.properties`, 17 lockfiles, `.gradle`/`.kotlin` ignores | `git grep -il gradle -- ':!docs' ':!*.md'` empty; `bazel test //...` green from a fresh clone on a machine with only bazelisk |
| 10 | Dogfood + measure: `bbv run -- build //...` on this repo; Gradle-vs-Bazel clean/incremental/no-op/CI timings into performance.md (including where Bazel is *slower*); update agent memory (`./gradlew check` → `bazel test //...`) | A committed session capture of this repo building itself; performance.md updated with measured, dated figures |

Estimate: **4–6 focused days**. Drivers: step 0 (half day to a day — it
retires nearly all technical risk), step 4 (1–2 days; 15 modules but the
macro makes each mechanical), steps 7–8 (a day; CI iteration latency).
The step-4 module order above is the dependency order, so work can pause
indefinitely at any row with everything before it green.

## 7. Open questions step 0 must answer (none block accepting the ADR)

1. contrib_rules_jvm 0.34.0 runner against JUnit **6.1.3** exactly: does its
   XML listener need `junit-platform-reporting`, and is skipped-via-assume
   reported as skipped? (Fallback runner in §4.2.)
2. protoc's `.srcjar`-suffix output path support when driven with
   `--grpc-java_out` — confirm the plugin writes into the jar as protoc
   does. (Fallback: genrule writes a dir, `zipper` packs it.)
3. Sandboxed sqlite-jdbc/FlatLaf native extraction on macOS seatbelt
   (both extract to `java.io.tmpdir` = `TEST_TMPDIR`, expected fine —
   confirm, since storage-sqlite tests are the load-bearing case).
4. `--@protobuf//bazel/toolchains:prefer_prebuilt_protoc` actually prevents
   every C++ action: `bazel aquery 'mnemonic("CppCompile", deps(//...))'`
   must be empty. This is a standing invariant, checked in CI thereafter.
5. Whether `CliRunTest` is purely real-Bazel or mixed; if mixed, split the
   file rather than give the whole class inherited-env dispensation.
   **Resolved 2026-08-24: mixed — 5 plain tests, 3 `@Tag("real-bazel")`
   methods. Split into `CliRunTest` and `RealBazelCliRunTest`, every
   method preserved.**

## 8. Parity signoff checklist (step 8 gate)

Ticked 2026-08-25 against the evidence in §11's signoff record; a box is
ticked only for something that actually ran, and the two items that did not
run say so instead.

- [x] Test inventory: identical class list and per-class test counts,
      Gradle XML vs `bazel-testlogs` XML — 216 classes, 1852 tests,
      0 failures, 0 skipped on both sides (`tools/test_inventory.py` holds
      the counting logic; record in §11.1).
- [x] Generated proto/grpc sources: byte-identical to Gradle's (step 3
      diff re-run at signoff; sha256s in §11.2).
- [x] Runtime classpath: GAV set in `maven_install.json` ≡ union of
      Gradle lockfiles, zero version mismatches; every delta is a
      codegen/generator tool or a planned addition, enumerated in §11.3.
- [x] `bbv import` / `inspect` / `run` / smoke exercised from `bazel run`
      and the smoke path additionally from the deploy jar under
      `--illegal-native-access=deny` (the ADR-008 enforcement rehearsal;
      scope and exit codes in §11.4).
- [x] All seven real-Bazel test classes ran (not skipped) on this machine
      (§11.5). CI excludes real-bazel visibly via `--config=ci`; the
      classes run on developer machines instead.
- [x] JMH lists both benchmarks (§11.6). **Spike offscreen PASS verdicts:
      not executed at signoff** — deferred; the six spike binaries compile
      in `bazel build //...` (rot protection) and their verdicts measure
      machine performance, not the build system (performance.md's
      build-system note). Follow-up: run each `--offscreen` once and
      record.
- [x] Double clean build ⇒ identical `bbv_deploy.jar` sha256 (§11.7).
- [x] No protobuf/gRPC/C++-toolchain CppCompile actions in `deps(//...)`;
      the aquery's single hit is Bazel's own `@bazel_tools` launcher tool,
      stated verbatim in §11.8.
- [ ] Fresh-clone build on a bazelisk-only environment (no JVM installed):
      **not executed — deferred.** Needs a second workspace and a full
      re-download to prove anything; the committed lockfiles and hermetic
      toolchain make CI's ubuntu runner (bazelisk + no project JVM setup)
      the honest first execution of this check.
- [x] `git status` clean after full build (§11.9; the convenience symlinks
      and nothing else, all ignored — the workspace-name symlink entry in
      `.gitignore` matches the canonical clone directory name).

## 9. Risk register

| Risk | L | I | Mitigation |
|---|---|---|---|
| JUnit 6 runner incompatibility in contrib_rules_jvm | med | low | Step 0; owned fallback runner is small and this repo's style |
| Real-Bazel tests behave differently under `bazel test` (env, orphan servers accumulating across runs) | med | med | §4.3 tags; step 6 runs the suite five times back-to-back and asserts no server pile-up (`ps` sweep, the fixture's own shutdown path) |
| macOS sandbox vs native-lib extraction | low | high | Step 0 item 3; worst case `no-sandbox` on storage/ui suites, recorded as a known cost |
| grpc codegen genrule subtly diverges from plugin behavior | low | high | Byte-diff gate at steps 3 and 8 makes divergence impossible to miss |
| CI cache misconfiguration makes Bazel CI *slower* than Gradle's | med | med | Step 7 records cold/warm times before cutover; Gradle workflow isn't deleted until step 9 |
| IntelliJ workflow regression annoys daily development | med | med | `.bazelproject` committed and validated during step 4, while Gradle import still exists as escape hatch |
| BCR/maven outage during migration | low | low | Everything sha256-pinned; `--repository_cache` shared |

## 10. Explicitly out of scope

- Remote caching/execution, RBE — single-user desktop repo; `--disk_cache`
  only.
- ~~jpackage/macOS packaging under Bazel — Phase 9 work~~ **No longer out
  of scope** (corrected 2026-08-24): the Gradle `jpackage`/`notarize`
  tasks landed 2026-08-23, so the migration ports them — `bazel run`
  script targets over the `//app:bbv` deploy jar, preserving the app
  name, the version-stamping rule (jpackage refuses versions starting
  with 0, so 0.x is stamped 1.0.0 and announced), the `.bviz` file
  association, and the `BBV_MAC_SIGNING_IDENTITY` /
  `BBV_MAC_NOTARY_PROFILE` env gates. See docs/packaging.md.
- Splitting modules into finer-grained targets — the 15-module structure
  transfers 1:1; finer targets are a later, separate decision.
- Adopting `java_proto_library`/`java_grpc_library` — revisit per §4.1.
- Error Prone / NullAway / formatters — not in the Gradle build; adding
  them is orthogonal and easier after (Bazel java toolchains carry Error
  Prone natively) but not this change.

## 11. Signoff record (2026-08-25)

Recorded per step 8's exit criterion. Machine: the macOS arm64 development
machine; Bazel 9.2.0 via bazelisk 1.29.0; local JDK 25.0.1 for the
deploy-jar rehearsal and jpackage. Commits: parity gates ran at cutover
(commit `7421515`); the deny rehearsal, double-clean determinism check,
CppCompile aquery and GAV comparison ran the following day from the same
tree.

1. **Test inventory.** `./gradlew build --max-workers=3` → BUILD
   SUCCESSFUL; Gradle XML totals 216 classes / 1852 tests / 0 failures /
   0 skipped. `bazel test //... --test_tag_filters=-bazel-sweep` →
   "Executed 7 out of 216 tests: 216 tests pass" (the 7 are the
   `external`-tagged real-Bazel targets, which never cache); testlogs XML
   totals 216 targets / 1852 tests / 0 failures / 0 skipped. Identical
   inventory. Counting logic: `tools/test_inventory.py`.
2. **Proto parity.** `diff -r` of the extracted srcjars against Gradle's
   generated trees: no difference beyond protoc's own jar manifest.
   sha256 over the java trees (both sides):
   `ae6c0b89653de586defcd4dab579e4fa751f6a22a4def5d8ccf27e5d76e5b768`;
   grpc trees:
   `03313a46c29894c1152bc253bd4836dbc5e43bbe0aa86fd98dd3fa003697a515`;
   descriptor set byte-identical:
   `f31bf690c020d40969b60ea6cdef3aae48936cee242827ecb83844411130fc87`.
3. **Dependency universe.** Union of the 15 module `gradle.lockfile`s (from
   git history) vs `maven_install.json`: zero version mismatches. Only in
   Gradle: `com.google.protobuf:protoc` and `io.grpc:protoc-gen-grpc-java`
   (same versions, now sha256-pinned `http_file` exes rather than maven
   artifacts), `org.junit:junit-bom` (a BOM, no jar), and the
   me.champeau.jmh plugin's bytecode-generator stack
   (`jmh-generator-asm`/`-bytecode`/`-reflection`, `org.ow2.asm:asm`) —
   replaced by annotation processing. Only in Bazel:
   `junit-platform-reporting` (planned; runner XML),
   `jmh-generator-annprocess` (planned), and its transitive
   `open-test-reporting-tooling-spi`.
4. **Native-access rehearsal.** Smoke run (window opens, FlatLaf loads its
   native library, exits after two seconds) under
   `--illegal-native-access=deny`: via
   `bazel run //app:bbv -- --jvm_flag=--illegal-native-access=deny
   --jvm_flag=-Dbbv.smoke=true` → exit 0, and via
   `java --illegal-native-access=deny --enable-native-access=ALL-UNNAMED
   -Dbbv.smoke=true -jar bazel-bin/app/bbv_deploy.jar` → exit 0.
   `import --help` verified via `bazel run`. `import`/`inspect`/`run`
   behavior is otherwise covered in-process by the app suite
   (CliImportTest, CliInspectTest, RealBazelCliRunTest and siblings) under
   `bazel test`; no side-by-side fixture-session comparison of the two
   launch paths was performed beyond the smoke path.
5. **Real-Bazel classes.** All seven ran and PASSED on this machine under
   `bazel test`: RealBazelCapabilityTest (19.2s), RealBazelBesTest (29.2s),
   RealBazelCaptureTest (55.3s), RealBazelEnrichmentTest (51.6s),
   RealBazelGraphTest (15.0s), RealBazelNormalizationTest (99.1s),
   RealBazelCliRunTest (24.6s). None skipped. The PATH-stripped skip path
   was not separately exercised at signoff.
6. **JMH.** `bazel run //benchmarks:jmh -- -l` lists
   `SqliteInsertBench.insertRows`, `SyntheticGeneratorBench.actionAtRandom`
   and `.actionAtSequential`. Spike offscreen verdicts: not executed
   (checklist note).
7. **Determinism.** `bazel clean` + `bazel build //app:bbv_deploy.jar`,
   twice: both builds produced sha256
   `6d9d7cacf4d8e34f53d21060771e69f645c0e32bd4d61d5c97d22c60a19550b6`
   (matching the pre-clean artifact).
8. **CppCompile.** `bazel aquery 'mnemonic("CppCompile", deps(//...))'`
   returns exactly one action: `@bazel_tools//src/tools/launcher:
   launcher_maker` (`Compiling src/tools/launcher/launcher_maker.cc [for
   tool]`, exec configuration) — Bazel's own java_binary launcher tooling,
   present regardless of this repository's rules. Zero actions from
   protobuf, gRPC, or any repo target; the prebuilt-protoc goal this
   invariant was written for holds.
9. **Clean tree.** `git status --porcelain` empty after the full build and
   test cycle, with only the ignored convenience symlinks on disk.

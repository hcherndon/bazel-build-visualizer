# Gradle → Bazel migration: scope and plan

Status: **proposed** — this document is the scoping deliverable; nothing in it
has been implemented. The build-system decision itself is ADR territory:
[ADR-003](adr/003-gradle.md) fixed Gradle, so the migration may not begin
until [ADR-009](adr/009-bazel-build.md) (drafted alongside this plan, status
proposed) is accepted. Written 2026-08-22; every version pin below was
verified against its registry on that date.

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
| 5 spike `JavaExec` tasks, 4 GiB heap, `-D` forwarding, offscreen args (`benchmarks/build.gradle.kts`) | 5 `java_binary` targets from one list in the benchmarks BUILD (same loop, Starlark instead of Kotlin). `bazel run //benchmarks:table_spike -- --offscreen`. |
| JMH via me.champeau.jmh plugin, detached from `check` | No plugin: `java_library(srcs = glob(src/jmh/java))` + `java_plugin` on `jmh-generator-annprocess` + `java_binary(main_class = "org.openjdk.jmh.Main")`. Two new maven artifacts: `org.openjdk.jmh:jmh-core:1.37`, `:jmh-generator-annprocess:1.37` (today the plugin supplies them). Behavior change, accepted: `bazel build //...` now *compiles* benchmarks (rot protection); running stays manual (`bazel run //benchmarks:jmh -- <filter>`). |
| `printRuntimeCp` helper task (test-support) | Dropped — grep found no consumer. `bazel run` / deploy jars are the replacement if the need returns. Noted in ADR-009. |
| `resolveAndLockAll` task | `REPIN=1 bazel run @maven//:pin`. |
| Real-Bazel tests (4 classes, `@Tag("real-bazel")`, self-skipping via `BazelBinary.find()`) | §4.3 — the one place Bazel's sandbox actively fights the suite. |
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

- `proto_library` targets over the vendored sources (protobuf 36.0 rules,
  `--@protobuf//bazel/toolchains:prefer_prebuilt_protoc` so no C++ is ever
  compiled). `strip_import_prefix` makes the two upstream-mirroring roots
  (`proto/src/main/proto`) resolve unchanged — the vendoring layout and
  `update-protos.sh` survive untouched.
- Codegen genrules run the **same two binaries Gradle downloads today** —
  `com.google.protobuf:protoc:4.36.0` and
  `io.grpc:protoc-gen-grpc-java:1.83.1` maven-central `.exe` artifacts —
  fetched per-platform via `http_file` (`use_repo_rule`) with sha256 pins
  (osx-aarch_64, osx-x86_64, linux-x86_64, linux-aarch_64), selected by
  platform. protoc writes `.srcjar` outputs natively (jar-suffixed output
  paths), plus the descriptor set with `--include_imports` — the Gradle
  artifact, bit for bit.
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

`java_test_suite(runner = "junit5")` from contrib_rules_jvm generates one
`java_test` per test class, running the repo's own JUnit **6.1.3** artifacts
from `@maven` (the runner is version-agnostic over the Platform launcher
API; 0.31.1+ is documented working against Platform 6.x — step 0 verifies
6.1.3 and the XML the runner writes). Fallback if it disappoints: an
~80-line launcher in `test-support` driving `junit-platform-launcher`
directly with Bazel's XML contract — this repo would not mind owning that.

What per-class targets buy, concretely: Bazel caches at test-target
granularity, so a `storage-sqlite` edit reruns that module's classes and
nothing else — today `check` reruns every module's whole suite JVM.
`assumeTrue`-skips keep working unchanged (they surface as skipped in the
XML, same as under Gradle).

### 4.3 The four real-Bazel test classes: inherited environment, no sandbox, never silently weakened

`RealBazelCaptureTest`, `RealBazelBesTest`, `RealBazelCapabilityTest`, and
`CliRunTest` find a host Bazel via `BBV_TEST_BAZEL` / a dev bazelisk /
`PATH`, sweep `USE_BAZEL_VERSION` across 6.5.0→9.2.0, and spawn Bazel
servers that are *designed* to escape the process tree (PPID 1 — memory
finding 9). Under default `bazel test` they would all silently skip:
scrubbed env means no `PATH` bazelisk, and the sandbox would block the
server's `output_user_root` and bazelisk's download cache anyway.

Silent skipping is exactly what `BazelBinary`'s contract forbids CI to
drift into. So these four classes are excluded from the suites and declared
as dedicated `java_junit5_test` targets:

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
- `bbv_java_test_suite(name, deps, jvm_flags = [], …)` — wraps
  `java_test_suite`; adds JUnit 6 + AssertJ + launcher deps, `-Xmx2g`, the
  native-access flag, `runner = "junit5"`.
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

`.bazelrc` (complete initial contents):

```
# ADR-008: Java 25 LTS, hermetic remote JDK on every machine and in CI.
build --java_language_version=25
build --java_runtime_version=remotejdk_25

# Never compile protoc or any C++: prebuilt protoc (protobuf >= 33.4).
common --@protobuf//bazel/toolchains:prefer_prebuilt_protoc

test --test_output=errors

# Survive `bazel clean` and share across worktrees.
build --disk_cache=~/.cache/bbv-bazel-disk

# CI adds: --config=ci  (nothing host-specific may live outside it)
build:ci --disk_cache= --repository_cache=~/.cache/bazel-repo
test:ci --test_env=CI=true
```

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
        "org.assertj:assertj-core:3.27.3",
        "com.formdev:flatlaf:3.7.2",
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
        "org.slf4j:slf4j-api:2.0.18",
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
| 4 | Module BUILD files in dependency order: core-model → graph-core, session-format, storage-sqlite, bep-codec → test-support → capture-file, bazel-runner → capture-bes, enrichment, analysis-core → ui-swing → app, benchmarks | After each: that module's `bazel test` green. After all: test-class inventory (names + counts per class, from XML) identical to Gradle's, real-Bazel classes excepted |
| 5 | Binaries: `//app:bbv` (+ deploy jar, macOS select), 5 spike binaries, JMH binary | `bazel run //app:bbv -- import/inspect/run` behaves identically on a fixture session; smoke run via `--jvm_flag=-Dbbv.smoke=true` passes; each spike `--offscreen` PASSes; `bazel run //benchmarks:jmh -- -l` lists both benchmarks |
| 6 | Real-Bazel test targets (§4.3) | On this machine: all four run and pass under `bazel test --test_tag_filters=real-bazel`; with `PATH` stripped they skip with the correct message |
| 7 | CI cutover on the branch: new workflow, both OSes, caching | Green on macOS + Ubuntu; real-Bazel tests *ran* (assert non-skipped in the workflow — CI silently losing them is the failure mode `BazelBinary` was built to prevent); cold/warm CI times recorded |
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

## 8. Parity signoff checklist (step 8 gate)

- [ ] Test inventory: identical class list and per-class test counts,
      Gradle XML vs `bazel-testlogs` XML (scripted diff, committed under
      `tools/`).
- [ ] Generated proto/grpc sources: byte-identical to Gradle's (step 3
      diff re-run at signoff).
- [ ] Runtime classpath: GAV set in `maven_install.json` ≡ union of
      Gradle lockfiles (known deltas only: +jmh ×2, +junit-platform-reporting).
- [ ] `bbv import` / `inspect` / `run` / smoke behave identically from
      `bazel run` and from the deploy jar (native-access flag present —
      run once under `--illegal-native-access=deny` to prove it, the
      ADR-008 enforcement rehearsal).
- [ ] All four real-Bazel classes ran (not skipped) on both CI OSes.
- [ ] Spikes: all four offscreen PASS verdicts; JMH lists benchmarks.
- [ ] Double clean build ⇒ identical `bbv_deploy.jar` sha256.
- [ ] No CppCompile actions anywhere in `deps(//...)`.
- [ ] Fresh-clone build on a bazelisk-only environment (no JVM installed)
      succeeds.
- [ ] `git status` clean after full build (no stray outputs; the four
      symlinks and nothing else, all ignored).

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
- jpackage/macOS packaging under Bazel — Phase 9 work, as it was under
  ADR-003. This migration only changes *whose* problem it is (ours, as
  genrules, instead of the application plugin's).
- Splitting modules into finer-grained targets — the 15-module structure
  transfers 1:1; finer targets are a later, separate decision.
- Adopting `java_proto_library`/`java_grpc_library` — revisit per §4.1.
- Error Prone / NullAway / formatters — not in the Gradle build; adding
  them is orthogonal and easier after (Bazel java toolchains carry Error
  Prone natively) but not this change.

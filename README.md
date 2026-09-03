# Bazel Build Visualizer

> **Status: v1 complete, with post-v1 local/SSH Workspaces available.** The
> app launches Bazel for you, shows exactly what it will run and why before it
> runs it, captures the event stream through an embedded Build Event Service
> bound to loopback, and turns it into targets, actions, tests, artifacts and
> failures you can page, filter and sort. Every row leads back to the bytes
> Bazel sent, and every value the build did not report reads as unknown rather
> than as zero. Cancel a build and the partial session is still there to open.
> Verified against real Bazel 6.5, 7.6, 8.4 and 9.2 — including the places
> where those four disagree, which are written down in
> [docs/bep-content.md](docs/bep-content.md). Offline import of binary and JSON
> BEP files (Phase 1) still works the same way.
>
> **Phase 4 adds the execution log and the trace profile.** Builds it launches
> now record where every subprocess ran, whether it was a cache hit, and where
> its time went; the profile contributes the build's phases, Bazel's own
> critical path, and a resource timeline. Attempts are attached to actions by
> output path and to tests by label, and where an attempt cannot be attached
> the session says which of the five situations that is rather than leaving a
> blank. What those two sources actually contain — and the four places the
> supported Bazel versions disagree about them — is in
> [docs/exec-log-and-profile.md](docs/exec-log-and-profile.md).
>
> **Starlark CPU profiling identifies expensive rule and macro code.** The
> Performance and Full presets retain Bazel's gzip pprof output, normalize its
> samples without materializing the whole profile, and expose summary, hot
> function/file, caller/callee, and bounded flame views. The same tables and
> resolved views are available to the Query page. Sampled CPU remains distinct
> from elapsed time and from what Bazel waited for. See
> [docs/starlark-profiling.md](docs/starlark-profiling.md).
>
> **Phase 5 adds the dependency graph.** `aquery` and `cquery` are planned from
> the build's own command line, run after it finishes, and imported. The
> Both query scopes are populated from the exact top-level labels the completed build
> reported, so wildcard-only/manual targets are not accidentally added by
> either query command's pattern expansion. An incomplete BEP cannot certify
> that set, and a no-target fallback to requested patterns is recorded as wider. The imported data supplies the
> declared action graph, the configured-target graph, producer-to-consumer
> edges and persisted forward and reverse CSR indexes. The Graph card shows
> what an action depends on, what depends on it, and whether two actions are
> connected — with a source selector that says whether the graph was confirmed
> to be this build's, because a dependency tree from a query that analysed a
> different configuration looks exactly like a correct one. The Configurations
> card also joins those checksums back to the build, pages their effective
> options, and compares any selected pair without treating older Bazel versions'
> missing option payload as an empty configuration. What is actually in
> those protos, across four Bazel versions, is in
> [docs/aquery-and-cquery.md](docs/aquery-and-cquery.md). See
> **Phase 6 adds the timeline and critical-path analysis.** A custom Java2D view that draws aggregate
> density when a build is too big to show span by span and exact spans when it
> is not, with lanes grouped by runner, mnemonic, package or cache result, a
> hover readout of everything a time bin knows, and a range you can drag out to
> filter the actions table. The wheel or a two-finger trackpad gesture scrolls
> through lanes; Control/Command + wheel zooms around the pointer, and macOS
> also supports a native pinch. It follows a running build until you pan or
> zoom its time axis — vertical lane scrolling does not stop follow mode, and a
> live update never moves a time viewport a person put where it is. The
> Critical Path page keeps Bazel's profile-reported schedule separate from the
> visualizer's dependency-only lower bound, shows the exact ordered components
> of each, and connects identified dependency steps back to Actions and Graph.
> 1M spans index in 0.17 s and draw at 0.9 ms a frame. See
> [docs/implementation-status.md](docs/implementation-status.md).
>
> **Post-v1 Workspaces put local and SSH repositories in independent windows.**
> Startup restores the Workspace windows left open, or shows recent Workspaces
> when there is nothing to restore. Each saved entry names one repository, its
> Bazel executable and either this computer or an SSH destination; several
> entries can point at different repositories on the same machine. Each window
> owns its repository tools, Terminal, capture and SSH connection. Different
> canonical repositories may capture in parallel, while a process-wide lease
> prevents two windows from capturing the same repository at once. The desktop
> BES remains bound to `127.0.0.1`, remote capture files return to the local
> managed session before import, and opening recorded data never reconnects by
> itself. See [ADR-011](docs/adr/011-ssh-remote-workspaces.md) and
> [ADR-012](docs/adr/012-multiple-workspace-windows.md).

A local-first desktop application for capturing, exploring, and understanding
Bazel builds. It ingests the Build Event Protocol — from builds it launches
on this computer or an explicitly selected SSH host, from live streams it
attaches to, or from files you already have — journals everything
raw before interpreting it, and turns the result into fast, honest views:
a 50-million-row action table that scrolls at 60 fps, a full-build timeline,
critical-path analysis, and dependency graphs that are actually the graph they
claim to be. The UI,
managed capture and analysis remain on the desktop; remote execution happens
only after the user explicitly selects an SSH Workspace. Build capture then
runs its separate preflight over that selected connection. The app adds no
build flags without telling you and never shows a number it cannot define.

## Building

Prerequisites: [bazelisk](https://github.com/bazelbuild/bazelisk)
(`brew install bazelisk`). Nothing else — not even a JVM: bazelisk fetches
Bazel 9.2.0 per `.bazelversion`, and the build fetches the remote JDK 25
toolchain ([ADR-008](docs/adr/008-java-25.md) via
[ADR-009](docs/adr/009-bazel-build.md)).

```
bazel test //...
```

UI tests also have package suites for quick iteration, while
`//ui-swing:tests` remains the complete UI gate. For example:

```
bazel test //ui-swing:tests-capture
bazel test //ui-swing:tests-events
bazel test //ui-swing:tests-timeline
```

Each Java package compiles to its own test library, so changing a capture test
does not invalidate the graph, event, or timeline test libraries. Every test
class also has a direct package-local runner, such as
`//ui-swing/src/test/java/com/holtherndon/bazelviz/ui/capture:LauncherPanelTest`.

The four-version Bazel sweep (`BazelVersionMatrixTest`) is excluded from
every default run and must never be run casually — it starts four Bazel
servers and has crashed a development machine. See the notes in
`capture-bes/BUILD.bazel` before touching it.

## Running the app

```
bazel run //app:app
```

(`bazel run //app:app -- --jvm_flag=-Dbbv.smoke=true` opens the window and
exits after two seconds; used by scripted verification. `bbv.appdir` rides the
same way, as a `--jvm_flag=-D<name>=<value>` argument before the program's own.)

Open **Settings › Preferences…** and choose the **Theme** tab to select a
persistent appearance. The bundled theme IDs are `light`, `dark`,
`intellij-light`, `darcula`, `macos-light`, and `macos-dark`.
`--jvm_flag=-Dbbv.theme=<id>` overrides the saved choice for one process
without replacing it.

Graphical launches write a bounded rolling log at
`logs/application.log` below the application-support directory. Choose
**Diagnostics › Log Detail** to switch between Error, Warn, Info, Debug and
Trace; Info is the saved first-run default. **Diagnostics** can also open the
current log in a modeless viewer or reveal it in the system file browser.
`--jvm_flag=-Dbbv.log.level=<error|warn|info|debug|trace>` overrides the saved
choice for one process. Headless commands remain console-only and default to
Warn so their stdout stays machine-readable.

The graphical app restores the Workspace windows that were open when it last
closed. Otherwise it opens on **Workspaces**, with recent entries newest first.
Create a named Workspace for one local or SSH repository, or open a recent one.
Choosing it opens a separate native window; choosing the same profile again
focuses that existing window. **Workspaces** in the menu recalls the manager or
creates and edits saved profiles. Closing the last Workspace window shows the
manager again. Opening or importing historical capture data never selects a
Workspace and never reconnects to a host recorded in that data.

The **Discovery** tab in **Settings › Preferences…** can hold one optional
local script. A non-empty script starts with a shebang; that shebang both
selects its interpreter and drives syntax highlighting in the editor. It runs
directly on this computer, off the Swing event thread, once at app startup and
whenever **Run Discovery Now** is used. Its stdout accepts:

```
local|workspace name|/my/workspace
ssh|remote workspace name|builder-host|/remote/workspace
```

Each run replaces the previous **Discovered** rows. Those rows are never saved
in `workspaces.properties` and cannot be edited or removed individually. The
SSH field is an OpenSSH destination or configured `Host` alias. Put a custom
port, jump host, identity, and other SSH connection options in the user's SSH
configuration.

## Importing a BEP file from the command line

```
bazel run //app:app -- import path/to/build.bep
bazel run //app:app -- inspect <session-dir> --events 20
```

For an installable single file, build the deploy jar and run it with the
native-access grant the launcher would have added:

```
bazel build //app:app_deploy.jar
java --enable-native-access=ALL-UNNAMED -jar bazel-bin/app/app_deploy.jar import path/to/build.bep
```

For a graphical launch from that jar on macOS, add the JDK package export used
by the Bazel and packaged launchers so the Timeline can receive native trackpad
pinch events:

```
java --enable-native-access=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  -jar bazel-bin/app/app_deploy.jar
```

Without that optional export the Timeline still supports its zoom buttons and
Control/Command + wheel; only native pinch is unavailable.

`bbv import --help` documents the options and the exit-code contract: 0 for a
clean import, 1 when the source was truncated or corrupt and everything before
the damage was imported, 3 when the import failed outright.

## Launching a build from the command line

```
bazel run //app:app -- run -- build //...
```

The Bazel command goes after `--`, so its options are never confused with
this tool's. `bbv run` prints the instrumentation plan — the command you
typed, the command that will run, and one line for every difference — then
launches, forwards Bazel's own output to stderr, and writes a summary to
stdout. `--dry-run` plans without launching; `--json` makes the summary
machine-readable.

The exit code describes the capture, not the build: a failing build whose
events were all captured exits 0, because the session it produced is exactly
what you asked for. The build's own result is in the summary.

If your command already names a `--bes_backend`, `bbv run` stops and asks,
because redirecting a team's build results away from their own backend is not
a decision a tool should make.

The desktop UI runs a command in the selected Workspace. For an SSH workspace,
its form records a name, OpenSSH destination or configured Host alias, optional
port, absolute remote working directory and remote Bazel executable. Choosing
that workspace is the explicit action that opens its private OpenSSH
connection. Capture borrows the same connection and adds only its temporary
reverse-BES forward and staging files. Authentication, host keys, jump hosts
and credentials remain in the user's OpenSSH configuration or agent; the
application stores no password or private key.

**Terminal** is available for both local and SSH workspaces and opens the
selected workspace's login shell automatically when its tab is visited. It
stays alive across navigation and closes when its workspace is closed or
replaced.

## Running the Phase 0 spikes

Every spike prints a PASS/FAIL verdict against its budget and, in
`--offscreen` mode, exits nonzero when it fails. What each one measures
differs; only two of them are frame-rate spikes:

| Spike | `--offscreen` behaviour | Reports |
|---|---|---|
| `table_spike` | headless; builds the model, no window | page-fetch latency percentiles, cache stats, JTable pixel geometry |
| `timeline_spike` | paints 300 frames to a `BufferedImage` | LOD build time, frame-time percentiles |
| `graph_spike` | paints 300 frames to a `BufferedImage` | CSR build/traversal rates, retained bytes, frame-time percentiles |
| `sql_paging_spike` | console-only; the flag is accepted and ignored | insert throughput, OFFSET vs keyset vs point-lookup latency |

The timeline and graph spikes open an interactive window when run without
`--offscreen`; the table spike opens one too, while the SQL spike is always
console-only. Targets and measured results:
[docs/performance.md](docs/performance.md).

```
bazel run //benchmarks:table_spike      -- --offscreen
bazel run //benchmarks:timeline_spike   -- --offscreen
bazel run //benchmarks:graph_spike      -- --offscreen
bazel run //benchmarks:sql_paging_spike -- --rows=2000000
```

(`bes_throughput_spike` and `entity_scale_spike` run the same way. JMH:
`bazel run //benchmarks:jmh -- -l` lists the benchmarks; a bare run
executes them — never in CI.)

## Modules

| Module | Responsibility |
|---|---|
| `core-model` | shared domain types: ids, session state machine |
| `proto` | compiled Bazel protobuf definitions (BEP, spawn log, …) |
| `bep-codec` | BEP framing/decoding (binary and JSON) |
| `runner` | local/SSH command execution and local/SSH+SFTP filesystem access (`//bazel-runner` keeps compatibility aliases) |
| `capture-bes` | loopback gRPC Build Event Service endpoint and local/SSH live-capture coordination |
| `capture-file` | importing existing BEP files |
| `session-format` | on-disk session directory layout, journals, manifests |
| `storage-sqlite` | explicit-SQL persistence: per-session DB + catalog DB |
| `enrichment` | execution-log / timing-profile correlation onto sessions |
| `graph-core` | memory-mapped CSR graph indexes and traversal |
| `analysis-core` | critical path, parallelism, cache analysis |
| `ui-swing` | Swing/FlatLaf shell and custom-painted heavy views |
| `app` | entry point and composition root |
| `test-support` | deterministic synthetic data generators (Tier 1-3) |
| `benchmarks` | Phase 0 spikes and JMH benchmarks (not shipped) |

Java packages use native Bazel rules beside their source. Shared test and JVM
policy values live in `tools/java_test_settings.bzl`; it does not create rules
or targets. See [ADR-010](docs/adr/010-package-local-bazel-targets.md), which
amends [ADR-009](docs/adr/009-bazel-build.md) (the Bazel migration that
superseded [ADR-003](docs/adr/003-gradle.md)). Packaging:
`bazel run //app:jpackage` builds the macOS app image ([docs/packaging.md](docs/packaging.md)).

## Documentation

- [docs/architecture.md](docs/architecture.md) — module map, the six graph
  representations, session state machine, pipeline, threading model
- [docs/adr/](docs/adr/) — ADR-001 … ADR-011, the fixed decisions
  (ADR-008 supersedes ADR-002; ADR-009 supersedes ADR-003; ADR-010 amends
  ADR-009's build-target layout; ADR-011 defines SSH remote workspaces)
- [docs/performance.md](docs/performance.md) — benchmark tiers, Phase 0
  targets and measurements
- [docs/implementation-status.md](docs/implementation-status.md) — what
  exists today, phase by phase
- Design references: [capture-sources](docs/capture-sources.md) ·
  [session-format](docs/session-format.md) ·
  [database-schema](docs/database-schema.md) ·
  [graph-model](docs/graph-model.md) ·
  [metric-definitions](docs/metric-definitions.md) ·
  [bazel-compatibility](docs/bazel-compatibility.md) ·
  [exec-log-and-profile](docs/exec-log-and-profile.md) ·
  [starlark-profiling](docs/starlark-profiling.md) ·
  [phase4-contracts](docs/phase4-contracts.md) ·
  [aquery-and-cquery](docs/aquery-and-cquery.md) ·
  [phase5-contracts](docs/phase5-contracts.md) ·
  [instrumentation-planner](docs/instrumentation-planner.md) ·
  [privacy](docs/privacy.md) ·
  [troubleshooting](docs/troubleshooting.md)

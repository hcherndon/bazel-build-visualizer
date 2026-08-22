# Bazel Build Visualizer

> **Status: Phase 2 complete — launching and capturing live builds.** The
> app launches Bazel for you, shows exactly what it will run and why before it
> runs it, and captures the event stream through an embedded Build Event
> Service bound to loopback. Cancel a build and the partial session is still
> there to open. Verified against real Bazel 6.5, 7.6, 8.4 and 9.2. Offline
> import of binary and JSON BEP files (Phase 1) still works the same way.
> Targets, actions and tests are not normalized yet — that is Phase 3. See
> [docs/implementation-status.md](docs/implementation-status.md).

A local desktop application for capturing, exploring, and understanding
Bazel builds. It ingests the Build Event Protocol — from builds it launches,
live streams it attaches to, or files you already have — journals everything
raw before interpreting it, and turns the result into fast, honest views:
a 50-million-row action table that scrolls at 60 fps, a full-build timeline,
and dependency graphs that are actually the graph they claim to be. It runs
entirely on your machine, adds no build flags without telling you, and never
shows a number it cannot define.

## Building

Prerequisites: none beyond a JVM able to run the Gradle wrapper — the Java
25 toolchain ([ADR-008](docs/adr/008-java-25.md)) is auto-provisioned on
first build.

```
./gradlew check
```

## Running the app

```
./gradlew :app:run
```

(`-Dbbv.smoke=true` opens the window and exits after two seconds; used by
scripted verification.)

## Importing a BEP file from the command line

```
./gradlew :app:installDist
app/build/install/bbv/bin/bbv import path/to/build.bep
app/build/install/bbv/bin/bbv inspect <session-dir> --events 20
```

`bbv import --help` documents the options and the exit-code contract: 0 for a
clean import, 1 when the source was truncated or corrupt and everything before
the damage was imported, 3 when the import failed outright.

## Launching a build from the command line

```
app/build/install/bbv/bin/bbv run -- build //...
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

## Running the Phase 0 spikes

Every spike prints a PASS/FAIL verdict against its budget and, in
`--offscreen` mode, exits nonzero when it fails. What each one measures
differs; only two of them are frame-rate spikes:

| Spike | `--offscreen` behaviour | Reports |
|---|---|---|
| `runTableSpike` | headless; builds the model, no window | page-fetch latency percentiles, cache stats, JTable pixel geometry |
| `runTimelineSpike` | paints 300 frames to a `BufferedImage` | LOD build time, frame-time percentiles |
| `runGraphSpike` | paints 300 frames to a `BufferedImage` | CSR build/traversal rates, retained bytes, frame-time percentiles |
| `runSqlPagingSpike` | console-only; the flag is accepted and ignored | insert throughput, OFFSET vs keyset vs point-lookup latency |

The timeline and graph spikes open an interactive window when run without
`--offscreen`; the table spike opens one too, while the SQL spike is always
console-only. Targets and measured results:
[docs/performance.md](docs/performance.md).

```
./gradlew :benchmarks:runTableSpike     --args="--offscreen"
./gradlew :benchmarks:runTimelineSpike  --args="--offscreen"
./gradlew :benchmarks:runGraphSpike     --args="--offscreen"
./gradlew :benchmarks:runSqlPagingSpike --args="--rows=2000000"
```

## Modules

| Module | Responsibility |
|---|---|
| `core-model` | shared domain types: ids, session state machine |
| `proto` | compiled Bazel protobuf definitions (BEP, spawn log, …) |
| `bep-codec` | BEP framing/decoding (binary and JSON) |
| `bazel-runner` | launching and supervising Bazel processes |
| `capture-bes` | loopback gRPC Build Event Service endpoint for live capture |
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

Build conventions live in `build-logic/` (see
[ADR-003](docs/adr/003-gradle.md)).

## Documentation

- [docs/architecture.md](docs/architecture.md) — module map, the six graph
  representations, session state machine, pipeline, threading model
- [docs/adr/](docs/adr/) — ADR-001 … ADR-008, the fixed decisions
  (ADR-008 supersedes ADR-002)
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
  [instrumentation-planner](docs/instrumentation-planner.md) ·
  [privacy](docs/privacy.md) ·
  [troubleshooting](docs/troubleshooting.md)

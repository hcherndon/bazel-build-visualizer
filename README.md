# Bazel Build Visualizer

> **Status: Phase 0 — foundations and risk retirement.** The window shell
> launches, synthetic data generators exist, and the rendering/storage
> spikes are being proven at scale. Nothing here captures a real build yet.
> See [docs/implementation-status.md](docs/implementation-status.md).

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
21 toolchain is auto-provisioned on first build.

```
./gradlew check
```

## Running the app

```
./gradlew :app:run
```

(`-Dbbv.smoke=true` opens the window and exits after two seconds; used by
scripted verification.)

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
- [docs/adr/](docs/adr/) — ADR-001 … ADR-007, the fixed decisions
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

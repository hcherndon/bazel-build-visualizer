# Architecture

This document describes the system as built in this repository. Decisions
with rationale live in [docs/adr/](adr/); this file is the map.

## Module map

Fifteen Bazel packages sharing the `tools/bbv.bzl` convention macros
(ADR-009; each was a Gradle module until 2026-08-24). Dependencies point
strictly downward toward `core-model`; the UI never appears on a
capture/storage/analysis classpath and vice versa, and Bazel's strict
dependency checking enforces the arrows compile-time hard.

| Module | Responsibility |
|---|---|
| `core-model` | Pure domain types shared by everything: identifiers (`SessionId`), the session lifecycle state machine (`SessionState`), the normalized event/action/target value types, the provenance wrapper (`Measured`), and the redaction engine (`core.redact`) plus the CSV and JSON escaping every export shares (`core.text`). No I/O, no framework dependencies beyond slf4j. |
| `proto` | Compiled Bazel protobuf definitions (BEP, command line, spawn/execution log, failure details). Nothing hand-written lives here; it exists so generated code has one home and one version policy. |
| `bep-codec` | Framing and decoding of Build Event Protocol payloads: length-delimited binary streams and JSON file variants, turning journal bytes into typed events. Owns "what did Bazel say", not "what does it mean". |
| `bazel-runner` | Launches and supervises Bazel processes: command assembly from the instrumentation plan (ADR-007), process lifecycle, exit-status interpretation, workspace preflight checks. |
| `capture-bes` | gRPC Build Event Service endpoint (loopback-only, see docs/privacy.md) that live builds stream into; appends received events to the raw journal (ADR-004). |
| `capture-file` | Ingestion of already-written BEP files (binary or JSON) into the same raw journal format, so file import and live capture converge immediately. Also the reverse: `capture.file.export` streams the journal back out as a length-delimited binary BEP file, which is the module that already has both the journal reader and the decoders. |
| `session-format` | The managed session directory layout (docs/session-format.md): what files a session contains, journal file format, manifest read/write, integrity checks, and the portable `.bviz` archive (`format.portable`) that carries one to another machine. The only module that knows paths inside a session. |
| `storage-sqlite` | Explicit-SQL persistence (ADR-006): per-session database and app catalog database (ADR-005), schema DDL and migrations, paged query APIs that return primitive arrays, streamed CSV/JSON table exports, and the redaction column inventory that `SessionRedactionTest` checks against the schema. Depends on `analysis-core`, following plan 6.1's split: the aggregate and metric types are analysis-core's, the streaming that fills them is this module's. |
| `enrichment` | Post-build enrichers that add data the BEP stream lacks (execution log correlation, timing profile merge, external metadata), each re-runnable against the journal. |
| `graph-core` | Memory-mapped CSR graph index format (ADR-006): builders that stream edges into on-disk CSR files, and read-side traversal primitives over mapped buffers. |
| `analysis-core` | Algorithms and metrics over the indexes: critical path, graph extraction, clustering, layout, quantile sketches, the concurrency sweep, the metric catalog and the finding rules. Depends on `core-model` and `graph-core` only — **not** on `storage-sqlite`, which is what keeps every formula testable without a database and callable from either side. A caller supplies the arrays; this module supplies the answers. |
| `ui-swing` | All Swing code: window shell, FlatLaf theming, and the custom-painted heavy views (virtualized table, timeline, graph canvas). Talks to services only through background executors (see threading model). |
| `app` | Entry point and composition root: wires modules together, owns `main`, logging config, and (later) jpackage packaging. |
| `test-support` | Test and benchmark fixtures, notably the deterministic synthetic data generators (`SyntheticActionGenerator`, `SyntheticEdges`, `SyntheticScale`) with O(1) random access so Tier 3 scale never requires materialized fixtures. |
| `benchmarks` | Phase 0 architectural spikes (table/timeline/graph/SQL-paging, each with `--offscreen`) and JMH microbenchmarks. Never shipped. |
| `build-logic` (included build) | Convention plugins `bbv.java-common` / `bbv.java-library` / `bbv.java-application`: toolchain pin, test wiring, reproducible archives, dependency locking (ADR-003). |

## The six graph representations

Bazel builds involve several *different* graphs. Conflating them is the
classic failure mode of build visualizers, so these labels are load-bearing
and must be used exactly — in code, UI, and docs:

1. **BEP event graph** — the DAG of build *events* linked by
   `BuildEventId` children references. Ordering/announcement structure of
   the protocol stream itself. Not a build dependency graph.
2. **Target graph** — targets and their declared dependencies as written in
   BUILD files, before configuration. What `bazel query` shows.
3. **Configured-target graph** — targets *after* configuration: one node
   per (target, configuration) pair. Splits and transitions mean one target
   can appear several times. What `bazel cquery` shows.
4. **Declared action graph** — every action Bazel *would* run for the
   build, with artifact-mediated producer/consumer edges. What `bazel
   aquery` shows; exists even for fully cached builds.
5. **Observed execution graph** — the subset of actions that actually
   executed (or were confirmed cache hits) in *this* invocation, with what
   was observed about each: timing, runner, cache state.
6. **Temporal view** — the observed executions laid out on the wall clock:
   spans, concurrency lanes, the critical path as a time-ordered chain.
   Same nodes as (5), different structure — time, not dependency.

UI surfaces, metrics, and APIs must say which graph they are over. "The
graph" is never an acceptable label.

Two navigation cards read these graphs (since the 2026-08-24 Graph/Tree
split): **Tree** (`TreeView`) browses dependencies and reverse dependencies
one level at a time and finds paths, at any graph size; **Graph**
(`GraphExplorerView`) draws bounded extracts on the canvas with selectable
node weights. Each card carries its own graph-source selector naming which of
the representations above is on screen; see `docs/graph-model.md` for the
weight definitions and their budgets.

## Session state machine

Mirrors `core-model`'s `SessionState` exactly (that enum is authoritative;
update both together):

```
NEW -> PREFLIGHT -> CAPTURING -> BUILD_FINISHED -> ENRICHING -> INDEXING -> READY
```

Alternative transitions:

- `PREFLIGHT -> FAILED_TO_START` (bad workspace, bazel missing, port taken)
- `CAPTURING -> CANCELLED | INCOMPLETE | CORRUPT_PARTIAL`
- `BUILD_FINISHED -> INDEXING` (enrichment skipped/vetoed)
- `ENRICHING -> INCOMPLETE | CORRUPT_PARTIAL`
- `INDEXING -> READY_WITH_WARNINGS | INCOMPLETE | CORRUPT_PARTIAL`

Terminal states: `READY`, `READY_WITH_WARNINGS`, `FAILED_TO_START`,
`CANCELLED`, `INCOMPLETE`, `CORRUPT_PARTIAL`. The last three describe the
*capture outcome*, not openability — a session with any journaled data is
inspectable (ADR-004).

## Raw-first ingestion pipeline

Stages, in order, per ADR-004; each derived stage is re-runnable from the
journal:

1. **Receive** — bytes arrive from the BES gRPC stream (`capture-bes`) or a
   BEP file (`capture-file`).
2. **Journal** — append verbatim payload + arrival metadata to the
   append-only raw journal (`session-format`). Durability point; source of
   truth. Nothing before this stage interprets content.
3. **Decode** — `bep-codec` turns journal records into typed events;
   unknown/undecodable records are counted and preserved, never dropped.
4. **Normalize** — decoded events become rows in the session SQLite
   database (`storage-sqlite`): actions, targets, files, problems.
5. **Enrich** — `enrichment` correlates auxiliary sources (execution log,
   timing profile) onto the normalized rows; every enriched value carries
   its source.
6. **Index** — `graph-core` writes the CSR dependency indexes and temporal
   indexes as mmap files; SQLite gets its secondary indexes.
7. **Ready** — the UI opens read-only views over SQLite + mmap indexes.

Decode/normalize proceed incrementally during capture for live views;
stages 5-6 run after `BUILD_FINISHED`.

## Threading model

Rules first (project-wide, non-negotiable):

- **The EDT does no I/O, no parsing, no SQL, no graph layout — ever.** The
  EDT paints from already-prepared primitive-array snapshots and handles
  input.
- Unavailable values are represented as *unavailable* (sentinel/Optional at
  the edge), never as zero.
- Data crosses onto the EDT only as immutable snapshots via
  `SwingUtilities.invokeLater`; background work never touches Swing
  components.

Executor inventory (plan section 19) — each a named, owned executor; ad hoc
thread creation is forbidden:

The plan (section 19.2) fixes the *set* of executors; the names below are the
implementation's names for them and may be refined, but the lanes may not be
merged — the point of the split is that a slow journal write can never stall
a UI query, and that SQLite has exactly one writer.

| Executor (plan 19.2) | Type | Work |
|---|---|---|
| EDT | Swing | painting, input, snapshot swap-in only |
| Orchestration / blocking I/O | virtual-thread-per-task | Bazel process supervision, stdout/stderr pumps, enrichment command execution, general blocking I/O |
| Journal writer | single dedicated platform thread | raw journal appends; nothing else shares it, backpressure is explicit |
| Protobuf parser pool | bounded platform pool | BEP decode; bounded so decode can never outrun persistence |
| SQLite writer | single thread | every write to the session database, one writer connection |
| SQLite read pool | small bounded pool | short read transactions serving queries; cancellable |
| CPU analysis pool | bounded platform pool sized to cores | metrics, aggregation, critical path, CSR/temporal index construction |
| Graph layout pool | bounded platform pool | layout and rasterization prep for the graph canvas; cancellable |
| Export/import pool | bounded platform pool | archive read/write, BEP export, CSV/JSON export |

gRPC's own `grpc-netty-shaded` event loops sit in front of this: they perform
BES wire I/O only and hand bytes straight to the journal writer, per the
"gRPC callback requirements" in plan section 9.3.

Blocking work goes on virtual threads unless it is a dedicated-resource lane
(journal file, SQLite connection, render prep), which get named platform
pools with explicit bounds. Queues between stages are bounded; overflow is
backpressure, never unbounded buffering (bounded-memory streaming rule).

# Implementation status

Last updated: 2026-08-21. This file states what exists in the tree, not what
is planned to exist. Update it in the same change that lands the work.

## Phases

Phase scopes are those of `docs/product-plan.md` section 24 and must not be
renumbered or re-scoped here.

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository and architectural spikes | **Complete** — all exit criteria met (see below) |
| 1 | Session, journal, and offline BEP import | **Complete** — all five exit criteria verified end to end (see below) |
| 2 | Bazel launcher and embedded BES | Not started |
| 3 | Core target, action, test, and artifact normalization | Not started |
| 4 | Execution-log and profile enrichment | Not started |
| 5 | `aquery`, `cquery`, and graph construction | Not started |
| 6 | Timeline | Not started |
| 7 | Graph visualization | Not started |
| 8 | Metrics and findings | Not started |
| 9 | Session export, redaction, and macOS packaging | Not started |
| 10 | Scale hardening and compatibility release gate | Not started |

## Phase 0 checklist (as of 2026-08-21)

| Task | Status |
|---|---|
| Repo + Gradle multi-module skeleton (15 modules + build-logic, wrapper 9.7.1) | Done |
| Convention plugins (`bbv.java-common` / `-library` / `-application`), reproducible archives, dependency locking wiring | Done |
| Java 25 toolchain auto-provisioning (foojay resolver) | Done — baseline raised from 21 to 25 on 2026-08-21, see below |
| Logging (slf4j everywhere, logback in `app`, uncaught-handler in `Main`) | Done |
| FlatLaf window shell (`app` `Main`, `ui-swing` `MainWindow`/`Themes`, smoke mode) | Done |
| Synthetic data generators (`test-support`: `SyntheticActionGenerator`, `SyntheticEdges`, Tier 1-3 scales, O(1) access) | Done |
| Bazel BEP/BES proto vendoring + Java/gRPC codegen in `proto` | Done — Bazel 9.2.0 and googleapis pinned, provenance in `proto/PROTO_SOURCES.md`, wire round-trip covered by `BepProtoSmokeTest` |
| Rendering spikes: table / timeline / graph (`:benchmarks:runTableSpike` etc., `--offscreen` mode) | Done — all pass, results in docs/performance.md |
| SQLite paging spike (`:benchmarks:runSqlPagingSpike`) | Done — 1.68M rows/s insert; keyset vs OFFSET finding recorded |
| JMH microbenchmark harness in `benchmarks` | Done — `SyntheticGeneratorBench`, `SqliteInsertBench` compile; not attached to `check` (run `./gradlew :benchmarks:jmh`) |
| ADRs 001-008 | Done — ADR-008 supersedes ADR-002 |
| CI (GitHub Actions, macOS 14 + Ubuntu, `check`) | Done |
| Approved plan committed as `docs/product-plan.md` | Done |
| Dependency lockfiles written | Done — `./gradlew resolveAndLockAll --write-locks --no-configuration-cache` regenerates them |
| Phase 0 benchmark results recorded in docs/performance.md | Done |

## Java 25 upgrade (2026-08-21)

The language baseline moved from Java 21 LTS to Java 25 LTS.
[ADR-008](adr/008-java-25.md) records the decision and supersedes ADR-002;
ADR-002 remains in the tree, marked superseded, with its reasoning intact.
The move was made now precisely because Phase 0 is the cheapest point to
change a baseline — there is almost no product code to re-verify.

What landed:

- `bbv.java-common` pins `JavaLanguageVersion.of(25)`; the foojay resolver
  provisions the toolchain, so no preinstalled JDK is required.
- Every forked JVM (tests, `:app:run`, the spikes, the JMH benchmark JVMs,
  packaged start scripts) gets `--enable-native-access=ALL-UNNAMED`. Without it, Java 25 warns that
  FlatLaf and sqlite-jdbc call `System::load` from the unnamed module; a
  future JDK will block those calls outright. Reader-facing writeup in
  docs/troubleshooting.md.
- Preview features remain forbidden — unchanged from ADR-002, and restated in
  ADR-008 because a baseline bump is exactly when someone would assume
  otherwise. In particular Structured Concurrency is still preview in 25 and
  is not used.

No source file changed for the upgrade; it is a toolchain change.

Benchmark numbers are not restated here. `docs/performance.md` is the
authority on every measured figure and on which JDK each one was taken under;
read the JDK attribution there before comparing figures across the upgrade.

## Phase 0 audit

Phase 0 was reviewed by an adversarial audit (five lenses — EDT discipline,
memory/scale invariants, algorithmic correctness, storage and build config,
documentation truthfulness — with independent refuters per finding and a
completeness critic). 14 candidates, 11 refuted, and these fixed:

- **`.gitignore` silently excluded five vendored protos**, including
  `build_event_stream.proto`, because a bare `build/` matches at any depth
  and the Bazel proto paths contain a `build` component. A fresh clone could
  not compile `:proto`. Every lens missed it; the completeness critic found
  it by diffing the tree against the lens scopes. Ignore patterns are now
  anchored to module roots.
- **`BatchedInsert` committed failed batches.** Restoring auto-commit in
  `close()` commits the open transaction, so an aborted batch became durable
  while `rowCount()` reported zero. Now rolls back before rethrowing.
- **`PagedTableModel` swallowed fetch failures**, rendering them identically
  to still-loading cells and retrying forever. Failures are now visible,
  counted, and not auto-retried.
- **`TimelineLodIndex` capped bins by count, not bytes**, permitting a ~2.9 GB
  pyramid against the 4 GB heap objective. Cap is now byte-derived.
- **Spike exit codes were inconsistent**, so two of four reported a budget
  breach as a green build.

The last two code fixes were formally refuted as unreachable in Phase 0 —
correct today, since nothing yet wires a `RowSource` that can throw. Both
refutations conceded the analysis itself; the fixes were kept because Phase 3
wires exactly such a source, and inheriting a known latent defect costs more
than fixing it now.

## Phase 0 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Application window launches on macOS | Met — `./gradlew :app:run` with `-Dbbv.smoke=true` |
| Synthetic paged table remains responsive | Met — cached access max 23.6 µs, uncached fetch p99 120 µs at 50M rows |
| Aggregate timeline can pan and zoom without loading individual spans | Met — LOD-only painting, p95 0.79 ms at Tier 3 |
| Chosen Swing approach meets initial spike thresholds, or an ADR documents a revised rendering strategy | Met — all four spikes pass with >25x margin; ADR-001 stands, no revision needed |
| `./gradlew check` succeeds | Met |

The one structural limit the spikes surfaced: `JScrollPane` int pixel geometry
overflows near 107.4M rows at `rowHeight` 20. The 50M-row target fits; the
custom logical scrollbar of plan 17.5 becomes mandatory beyond that. See
docs/performance.md.

## Phase 1 exit criteria (plan section 24)

Verified end to end against the real `bbv` CLI on generated BEP files, not
only by unit tests. Commands and observed results:

| Criterion | Status |
|---|---|
| Complete and truncated BEP files import | Met — a 2,000-event binary file and its 300,000-event counterpart both import completely; the 2,000-event file cut mid-record at 40% imports its 771-event prefix, is marked `TRUNCATED` (not corrupt), reports damage at byte 229,050 of 229,433, and exits 1. The JSON encoding of the same stream imports to the same 2,000 events. |
| Restart resumes interrupted indexing | Met — SIGINT at 145,000 of 300,000 events leaves the session resumable; `--resume` continues at source byte 43,970,432 without re-reading, reaching exactly 300,000 with zero duplicate `(stream_id, sequence)` rows. |
| Original source is preserved | Met — the SHA-256 and size are recorded before anything is decoded, and the file is copied to `raw/imported-source.bep`, so the session never depends on the original. |
| Event counts and offsets are reproducible | Met — three independent imports of one file produced identical sequence, event type, id hash, raw segment/offset/length and decode status for all 2,000 rows, and an identical SHA-256 over the journaled payload bytes. |
| No full file is loaded into memory | Met — an 85 MB file imports completely under `-Xmx64m`, with a peak buffer of 5.9 MB. The buffer is bounded by the largest single record, not by file size. |

**Resumed sessions equal clean ones.** The interrupted-then-resumed session
and a clean import of the same file agree on every persisted column and hash
identically over their journaled payloads. Two columns are excluded from that
comparison on purpose: `bep_events.id` is insertion order, and `receive_micros`
is a wall-clock observation that legitimately differs between two runs.

A resumed session ends `READY_WITH_WARNINGS` where a clean one ends `READY`,
and its manifest permanently records that it was cancelled and resumed. That
is the honest outcome, not a defect to paper over.

### Interpretations worth knowing

- **A cancelled import stays in `CAPTURING`, a non-terminal state.**
  `INCOMPLETE` is terminal in the state machine, so marking a cancelled import
  there would make it permanently unresumable and contradict exit criterion 2.
  Plan 21.1 finds recoverable work precisely by looking for non-terminal
  states, so this is consistent with it.
- **A flipped payload byte yields `CORRUPT_PARTIAL`, not a partial import.**
  The length framing survives, so every record is still read and journaled;
  what fails is decoding the damaged one. All events are stored, the
  undecodable bytes are preserved verbatim, and the session says it is corrupt.
- **`checkpoints/import-source.json`** is a sidecar beside the frozen
  `import.ckpt`. The frozen checkpoint records a journal position, and a source
  byte offset is not derivable from the journal for JSON, where the file
  contains whitespace between objects that the journaled records do not.
- **Ctrl-C exits 130, not the documented 4.** A JVM killed by SIGINT exits with
  128 plus the signal number and a shutdown hook cannot change that; `bbv
  import --help` says so. The session is left resumable either way.

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
| 2 | Bazel launcher and embedded BES | **Complete** — all six exit criteria verified against real Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0, audited, and remediated (see below) |
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
| Chosen Swing approach meets initial spike thresholds, or an ADR documents a revised rendering strategy | Met — all four spikes pass; the three rendering spikes clear their 33 ms frame budget by >25x, and SQL paging clears the 100 ms cached-page budget by ~4x. ADR-001 stands, no revision needed |
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

## Phase 1 audit

Reviewed by an adversarial audit (five lenses — journal/recovery, importer and
its exit-criteria claims, parsers/codec, storage and UI, documentation
honesty — with three independent refuters per finding and a completeness
critic). 19 candidates, 5 refuted. Fixed:

- **A resumed import could store every record after the seam twice under a
  shifted sequence.** The next ordinal was seeded from the database high-water
  mark while the already-journaled records were counted from the journal. After
  a crash in which SQLite committed rows whose frames were still in the
  writer's staging buffer, the two disagree. The journal is the authority on
  frame identity, so the database is now reconciled to it before anything is
  appended, and the discarded rows are reported.
- **A capped diagnostic destroyed the import.** `flushDiagnostics` inserted the
  summary row into the map it was iterating, so the first capped code threw
  `ConcurrentModificationException` at finalization. The cap exists to surface
  a limit; failing the whole import instead was the worst possible outcome.
- **Losing segments from the front of a journal was invisible**, and recovery
  reported the mutilated journal as complete. The gap scan anchored at the
  lowest surviving index rather than at 0, and a test asserted that behavior
  with a rationale that is false for this format — every journal starts at
  segment 0 and nothing deletes one, so a run starting higher is proof of loss.
- **Recovery never converged on an empty trailing segment.** A zero-length
  final segment — which a crash between creating a segment and forcing its
  header produces, and which recovery's own repair also produces — was reported
  as truncated forever, claiming data loss that had not happened.
- **A dead process's session lock could become unbreakable.** Host identity came
  from the `HOSTNAME` environment variable, which is a per-process value: a
  cron job, a CI runner and a desktop launch on one machine can each see a
  different string, and a lock that appears to be held on another host is
  deliberately not breakable. The OS hostname is now primary.
- **Journal replay duplicated diagnostics.** A resumed import re-emitted a
  per-record diagnostic for every replayed frame. Anchored diagnostics now
  identify themselves by where they happened and are idempotent; unanchored
  import-level notes can still legitimately repeat.
- **The JSON parser's reported peak memory understated the truth by a whole
  record**, because the copy delivered to the caller coexists with the
  accumulator and was not counted. That figure is the instrumentation exit
  criterion 5 rests on, so it now counts both.
- **The CLI called itself `bbv` but the build produced `app`**, so no usage line
  it printed could be pasted into a shell.

Documentation corrections: the frame contract's source-kind values were all off
by one against the code; the README still announced Phase 0; the schema page
named a database path that never existed; performance objective 12 was listed
unmeasurable although Phase 1 measures it; and the session layout omitted two
files the importer writes.

Known gaps the critic identified and this phase does not close:
`session-format`'s `format/session` package, `app/cli`, and `test-support`'s
fixtures had no review lens of their own, and `test-support`'s
announced-missing-child fixture still has no test exercising it.

## Phase 2 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Executable/workspace detection | Done — `BazelExecutableResolver` (`--version`, not `version`: the client answers it in ~15 ms without touching the server), `WorkspaceDetector` (filesystem walk, four markers) |
| Command model | Done — `BazelCommand` + `CommandLineParser`, grammar measured against real binaries |
| Capability detection | Done — `bazel help flags-as-proto` from outside the workspace, help-text scrape as fallback, cached by executable + version + startup options |
| Instrumentation planner | Done — `InstrumentationPlanner`; mechanism complete, catalog covers the Phase 2 flags |
| gRPC BES server | Done — `BesServer`, loopback-only by construction, raw-byte marshaller |
| Sequence tracking and acknowledgements | Done — `BesStreamTracker`; acks follow the journal append and never pass a gap |
| Process launch | Done — `BazelLauncher`, direct argv, two pump threads |
| Capture stdout/stderr | Done — `ConsoleCapture` writes `raw/stdout.log` and `raw/stderr.log` verbatim |
| Cancellation | Done — SIGINT/SIGTERM/SIGKILL ladder, with a one-second hold before the first signal |
| Correlate invocation and stream identifiers | Done — `BesStreamKey` is `(buildId, invocationId, component)`; `event_streams.stream_key` |
| Persist effective command and injected flags | Done — manifest fields plus `instrumentation-plan.json` |
| Binary-file fallback for BES conflicts | Done — plan 8.5 option 2, `--build_event_binary_file` into the session |
| UI: launcher, plan dialog, live status, console, stop controls | Done |
| CLI: `bbv run` | Done — `--dry-run`, `--json`, `--replace-bes`/`--keep-bes` |
| Real-Bazel ground truth recorded | Done — `docs/bazel-ground-truth.md`, five experiments, 69 findings |
| Capture throughput measured | Done — `:benchmarks:runBesThroughputSpike`; see docs/performance.md |

## Phase 2 exit criteria (plan section 24)

Every row was checked against real Bazel binaries provisioned by Bazelisk, not
against a fake. `RealBazelBesTest`, `RealBazelCaptureTest` and
`RealBazelCapabilityTest` are tagged `real-bazel` and skip — never weaken —
when the machine has none.

| Criterion | Status |
|---|---|
| A real Bazel 6–9 fixture build can be launched | Met — 6.5.0, 7.6.1, 8.4.1 and 9.2.0 each launch a generated genrule workspace and complete. Capability detection is asserted separately on all four, including the `FlagInfo` field-population differences between them. |
| Events arrive through the embedded BES | Met — every version delivers a full stream to the loopback server; the envelopes unwrap to decodable `BuildEvent`s, the first is `BuildStarted` and the last is `component_stream_finished`. |
| No accepted event is silently dropped | Met — `CaptureSummary.isComplete()` is the arithmetic `received == journaled` and `journaled == normalized + stream-control`, checked on every capture. A 200,000-event burst under active backpressure satisfies it. `RawEventSink.submit` has no return value a caller could ignore: it accepts or it throws. |
| Duplicate sequences are idempotent | Met — a retransmitted sequence is acknowledged again and journaled once, verified over a real socket. A stream replayed on a *new connection* is also recognised, because trackers are keyed by `StreamId` for the life of the server rather than by connection. |
| A cancelled build creates an inspectable partial session | Met — a build cancelled six seconds in finalizes as `CANCELLED` with its journal, its rows and a `CAPTURE_CANCELLED` diagnostic, and opens. |
| Existing BES conflicts require an explicit decision | Met — a command carrying its own `--bes_backend` refuses to launch, offers plan 8.5's three resolutions with the cost of each, and creates nothing. |

### Interpretations worth knowing

**The build's outcome and the capture's outcome are separate.** A failed build
with a complete stream finalizes as `READY`: it is a good session about a bad
build, which is the most useful thing this tool produces. `bbv run`'s exit
code describes the capture for the same reason — a script that captures
failing builds on purpose is the normal case.

**Exit code 38 is not a build failure.** Bazel reports it when the event
upload fails, whatever the build did, so it masks the real result.
`CaptureResult.buildOutcomeKnown()` says when the exit code can be believed.

**Objective 1 is not met, and the gap is not ours.** The capture path
sustains 86,000 events/sec end to end without loss, against a target of
100,000. Replacing the whole pipeline with a sink that acknowledges and stores
nothing runs at the same speed, so the journal and the indexer are free at
this scale and the cost is the gRPC acknowledgement round trip. See
docs/performance.md.

**What is deliberately not attempted.** Coalescing acknowledgements would
close the throughput gap, and is not tried: an acknowledgement with a wrong
sequence number kills the user's Bazel server on 6.5.0 and 9.2.0, and no
experiment has established that Bazel accepts one acknowledgement covering a
run of sequences.

## Phase 2 audit

Eight lenses over the Phase 2 surface, three independent refuters per finding,
and a completeness critic whose job was to audit what no lens owned. 193
agents, 61 candidates, 32 confirmed. `docs/phase2-audit.md` is the full report,
including the refuted candidates and the dissents that changed a fix's
location rather than its verdict.

**Four defects made the tool say untrue things.**

A capture that received nothing reported itself complete. Every clause of
`isComplete()` is vacuously true at zero, so a build that died during option
parsing — or one whose events went to somebody else's backend — produced a
session claiming a COMPLETE capture source with no events in it, finalized
READY, exit 0. A CI job would have archived it as good.

Ctrl-C before the process existed was acknowledged and then discarded. The
whole of preflight ran with nothing to signal, so the tool printed "asking
Bazel to stop" and then launched the build the user had just cancelled.
Measured: SIGINT at t=3s produced a completed build reporting success.

The keep-your-own-backend resolution wrote a BEP file that nothing read. The
dialog promises "This application reads a local copy instead"; Bazel wrote
32,933 bytes into the session and the session reported zero events.

A `--bes_backend` set in a `.bazelrc` was invisible, so plan 8.5's mandatory
conflict never fired and the team's backend silently missed the invocation.

**Two could leave a session unfinalized with its lock on disk**: `force()`
throws on a failed journal and that unchecked exception escaped the cleanup
block, and `finish()` blocked forever handing a sentinel to a journal thread
that had already died.

**One test asserted the behaviour of the bug it was meant to catch** — the
second time in this project. The out-of-order bound counted pipeline depth
rather than disorder, and the unit test pinned the wrong quantity; with one
message in flight the two are indistinguishable, so it took a 200,000-event
burst to expose it.

**The completeness critic again found what no lens looked at**, including a
claim in `docs/performance.md` that this phase had written: an "accepted"
throughput column that divided the server's event count by the client's send
duration and reported 869k–1.3M events/sec for a path that cannot have
accepted more than its flow-control window. It read as though the transport
were fast and this application's storage slow. Both halves were false, and the
column is gone.

Also fixed: the parser could swallow the Bazel command as an unknown startup
option's value (found by the audit, and hit immediately when the fixture
started passing `--nohome_rc`); `BazelBinary` silently skipped the entire
real-Bazel suite when its override was mistyped, hiding the evidence every
exit criterion rests on; the fixture's empty `.bazelrc` did not make a run
hermetic and its comment claimed it did; exit 38 was reported as a failed
build on both surfaces; and the instrumentation dialog said which flags could
be turned off while offering no way to turn one off.

**Deliberately not fixed, and recorded instead** in `docs/phase2-contracts.md`
§10: rc detection covers the `common` and `build` sections and not
command-specific ones; `--json` omits the plan's warnings; the veto is in the
dialog and not in the CLI; and objective 1 is missed for reasons outside this
application's code.

# Implementation status

Last updated: 2026-08-23. This file states what exists in the tree, not what
is planned to exist. Update it in the same change that lands the work.

## Phases

Phase scopes are those of `docs/product-plan.md` section 24 and must not be
renumbered or re-scoped here.

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository and architectural spikes | **Complete** — all exit criteria met (see below) |
| 1 | Session, journal, and offline BEP import | **Complete** — all five exit criteria verified end to end (see below) |
| 2 | Bazel launcher and embedded BES | **Complete** — all six exit criteria verified against real Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0, audited, and remediated (see below) |
| 3 | Core target, action, test, and artifact normalization | **Complete** — all five exit criteria met and verified against real Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0 (see below) |
| 4 | Execution-log and profile enrichment | **Complete** — all five exit criteria met, audited and remediated (see below) |
| 5 | `aquery`, `cquery`, and graph construction | **Complete** — five of six exit criteria met, one partial with a stated reason (see below) |
| 6 | Timeline | **Complete** — all five exit criteria met, one task partial with a stated reason (see below) |
| 7 | Graph visualization | **Complete** — all six exit criteria met and proved by test (see below) |
| 8 | Metrics and findings | **Complete** — all five exit criteria met and proved by test (see below) |
| 9 | Session export, redaction, and macOS packaging | **Complete** — all five exit criteria met and proved by test (see below) |
| 10 | Scale hardening and compatibility release gate | **Complete** — six of seven exit criteria met and measured, one partial with a stated reason (see below) |

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

## Phase 3 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Normalize configurations | Done — keyed on the opaque BEP id alone; referenced-but-undeclared ids (`system`, and `none` when it appears) get a row marked `declared = 0` rather than being dropped |
| Normalize targets | Done — two tables, because `TargetConfiguredId` carries no configuration and a row must not wait for `TargetComplete` |
| Normalize named file/depset structures | Done — stored as a DAG with two edge kinds; a set referenced but never defined is recorded and reportable rather than dropped |
| Normalize logical actions | Done — identity is `id.actionCompleted.primaryOutput`; label nullable; a repeated primary output is counted and surfaced, never merged |
| Normalize action timestamps and status | Done — `ActionTiming` classifies the four unavailability cases; the Bazel 8.4.x zero-length span is recorded unknown |
| Normalize outputs and logs | Done — output groups with their `incomplete` flag, tree artifacts kept out of the file roll-up, test logs merged across their two sources |
| Normalize tests and summaries | Done — verdict from `testSummary.overallStatus` only; every attempt kept |
| Build string/path dictionaries | Done — `labels` and `mnemonics` interned; artifact paths are the artifact table |
| Implement incremental overview aggregates | **Partial** — the overview is one consistent read in one transaction, on a timer, not an incrementally maintained aggregate. See the interpretations below. |
| Add source completeness | Done — `build_invocation.saw_last_message`, `configurations.declared`, depsets with no defining event, and the output-group `incomplete` flag; each is surfaced |
| UI: overview, actions table, targets tree, tests table, failures table, shared inspector | Done |
| BEP content ground truth recorded | Done — `docs/bep-content.md`, five experiments across four Bazel versions, 91 findings, five unresolved contradictions stated as such |
| Scale measured | Done — `:benchmarks:runEntityScaleSpike`; 112,000 actions/s normalized, flat 0.3–0.7 ms pages under every sort at a million actions, ordering verified against a reference query. See docs/performance.md |

## Phase 3 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Successful and failed builds produce coherent action/target/test records | Met — `RealBazelNormalizationTest` runs four real builds (success, failure under `--keep_going`, a test run with retries, an analysis abort) plus the same build across 6.5.0, 7.6.1, 8.4.1 and 9.2.0. Every run asserts `PRAGMA foreign_key_check` is empty, so "nothing was dropped to protect referential tidiness" is checked rather than claimed. |
| Action table supports paging, filtering, and sorting | Met — keyset paging under six sorts in both directions, with `ActionQueriesTest` walking every page of every sort and asserting each row is visited exactly once. `ActionsViewWiringTest` drives the toolbar over an imported session and checks the table and the status line agree. No path uses `OFFSET`. |
| Live updates are coalesced | Met — the overview attaches to the running capture as soon as its session directory exists, and re-reads one whole snapshot in one transaction on a timer rather than reacting to rows. `OverviewPanelTest` advances the underlying numbers a few thousand times in 400 ms against a 40 ms interval and observes about ten reads: reads follow the clock, not the data. `RealBazelNormalizationTest` reads a session while the build writing it is still running, which is the part the audit found had never been exercised. |
| Unknown values are visibly unknown | Met — `Measured` and the `Optional`-typed row records carry absence through the query layer; `Inspection.Field` carries the absence *and* its reason, and the shared inspector renders that as the word "unknown" followed by why. A field that claims both a value and a reason is refused by the constructor. |
| Event-to-domain provenance is inspectable | Met — every normalized row carries `bep_event_id`; the inspector offers the source event from any of the five views; `EntityViewsWiringTest` follows an action row to its event, to its journal location, to bytes that equal the ones the stream contained. |

### Interpretations worth knowing

**"Incremental overview aggregates" are a timer, not an incremental maintainer.**
Plan 24 lists incremental aggregates as a Phase 3 task. What exists is a set
of counting queries re-run on a two-second timer, which delivers the exit
criterion the task exists for — coalesced live updates — with one read per
interval and every number on screen taken from the same read. Maintaining
running totals in the writer would be faster and would be optimizing a number
nobody has measured; `OverviewQueries` is where that change goes when one is.

**There is no `action_outputs` table and no `action_attempts` table.** The BEP
names neither: `ActionExecuted` carries a `primary_output` File with a uri and
nothing else, and a retried action appears once reporting its final result.
Empty tables would have read as "these actions produced nothing" and "nothing
was retried". Both arrive in Phase 4 with the execution log.

**`DECLARED_ACTIONS` is not populated.** Phase 3 fills `BEP_EVENTS`,
`TARGETS`, `CONFIGURED_TARGETS` and `OBSERVED_EXECUTION`. The declared action
graph needs `aquery`, which is Phase 5, and the actions view does not imply
that what it shows is every action there is.

**The actions table can be legitimately near-empty.** Bazel publishes an event
for a successful action only under `--build_event_publish_all_actions`, so an
imported BEP captured without it holds failures and little else. The view says
so — worded about the options rather than about the rows, because what is
known is what Bazel was asked to publish.

**Every sort pages at the same cost, and that took two rewrites.** Measured at
a million actions: 0.30–0.67 ms a page under all six sorts, at the head of the
table and at the tail alike. The first two versions did not: sorting on a null
flag cost 18.8 ms at the tail, and a single row-value predicate cost 8.2 ms —
both the shape of `OFFSET`, reached from different directions.
`docs/performance.md` has the figures and what each mistake looked like. What
still differs is the one-off anchor scan when a sort is picked: 52–61 ms for
the four that order by a column of `actions`, 242–363 ms for the two that
order by dictionary text, and it runs off the EDT with the previous rows still
on screen.

**Unknowns sort first ascending, last descending.** SQL's own convention,
adopted because it is the only one an index can seek. Sorting them to one end
regardless of direction reads better and costs a full scan and a sort per page.

**Two numbers for the same thing, twice.** The overview shows this session's
counts beside Bazel's, and a test's elapsed time beside Bazel's reported
duration. In both cases the pair legitimately disagrees — `actionsExecuted`
excludes cache hits, and `totalRunDuration` excludes failed retries and
understated real wall time by 13x on a measured six-attempt test — so each is
labelled rather than reconciled into a figure true of neither.

### Verified from clean

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`,
so nothing came out of the build cache or a stale output directory:
**BUILD SUCCESSFUL in 2m 21s, 72 actionable tasks all executed, 907 tests
across 111 classes, 0 failures, 0 errors, 0 skipped.**

Nothing carries `@Disabled` and no Gradle configuration excludes a test, so the
907 are the whole suite. But *0 skipped* is a fact about this machine, not a
property of the suite: the tests that drive a real Bazel are guarded by
`assumeTrue(bazel.isPresent())`, and `RealBazelCapabilityTest` additionally
requires each pinned version to actually resolve. They ran here because Bazel
6.5.0, 7.6.1, 8.4.1 and 9.2.0 are all installed. On a machine without them the
count stays 907 and the skipped count rises, which is the honest reading of a
green run elsewhere. Two further guards are environmental rather than about
Bazel: `JournalBoundedMemoryTest` needs per-thread allocation accounting from
the JVM, and one `SessionLockTest` case needs its second process to still be
alive.

## Phase 3 audit

Eight lenses, three independent refuters per finding, and a completeness critic
whose job was to audit what no lens owned. 171 agents, 62 distinct candidates,
20 confirmed and 8 more from the critic. `docs/phase3-audit.md` is the full
report, including the 42 that did not survive and why.

**Eight defects made the tool say untrue things.** Every session claimed its
capture was truncated, because the completeness flag the phase's own "add source
completeness" task exists to provide was declared, selected, rendered — and
never written. A test's "elapsed across attempts" was Bazel's summary window,
which excludes failed retries and understated real wall time by 13x in
measurement, while three comments and a UI label said it was computed from the
attempts. An aborted target existed only in the abort log, so a build that
failed during analysis — which produces nothing else — showed no targets at all.
A test that failed to build reached the tests view not at all. "N actions" named
a total the source cannot support, because a cache hit publishes no event.
Empty `targetMetrics` became a confident zero. An interrupted build was reported
as failed. The Exit column showed Bazel's constant 1 as the process's code.

**Six lost work or leaked.** Live-captured sessions had no indexes at all,
because the only caller of the finalize step was the import path. Resuming an
import never migrated the schema. The read path never checked it either.
Closing the window leaked five views and the session. The entity buffer was
bounded in events, which bounds nothing. Truncation evidence was computed and
discarded.

**One was a security defect.** Swing renders any string beginning with
`<html>` as a live document and fetches its remote images, so a string in a
session file could make this application open a network connection — which plan
22.1 says it never does. Verified before and after the fix.

**Two were found by re-reading rather than by the audit**, and both are the same
kind of mistake. The actions view closed the reader its own page source needed,
so every page fetch failed and the table showed the error placeholder — and its
test passed, because the assertion excluded only the *loading* placeholder. And
keyset paging cost the same as `OFFSET`, twice over: sorting on a null flag cost
18.8 ms at the tail of a 200,000-row table, and its obvious replacement planned
three different ways at three depths. Neither was visible from the code and
neither had a measurement; `:benchmarks:runEntityScaleSpike` exists because of
it.

**The completeness critic again found what no lens looked at**, for the fourth
phase running — including two defects it verified by running Bazel itself rather
than by reading, and the fact that the "live overview" was never attached to a
running capture at all. The card showed "No session is open." for the whole
build and populated only when it ended, so the phase's own UI deliverable was
not delivered and its exit criterion rested on a unit test's fake reader.

## Phase 4 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Detect supported execution-log format | Done — by capability, and by sniffing the file's first bytes on import; the compact format is a zstd frame and the binary one is not (S4) |
| Plan and capture execution-log output | Done — one format per invocation, because Bazel 7+ rejects two; the user's own execution-log flag is left alone rather than fought with |
| Parse it streaming | Done — nothing accumulates but a bounded command buffer; paths are passed by id so the parser never holds them |
| Create action attempts | Done — one row per spawn, not per action: a test produces two and most actions produce none |
| Correlate attempts with actions | Done — by output path for ordinary actions, by label for tests, and the reason is stored either way |
| Parse JSON trace profile | Done — Gson pull parser; kept and skipped events are both counted |
| Normalize build phases and selected spans | Done — phases read from the file rather than a constant, ends derived and flagged as derived |
| Import final build metrics | Already done in Phase 3 — `BuildMetricsReported` writes `build_metrics`; a second path would have duplicated it |
| Calculate correlation diagnostics | Done — five `AttemptCorrelation` values, counted in the coverage panel, with the two that need attention called out |
| Add optional filesystem stat enrichment | Done — fills holes only, counts absent files rather than writing zero, refuses paths that escape the output tree |
| UI: attempt inspector, timing breakdown, runner/cache columns, phase overview, data-coverage panel, enrichment task status | Done — all six; the coverage panel shares the Overview card because plan 17.1 fixes the sidebar at eleven entries |
| Execution-log and profile ground truth recorded | Done — `docs/exec-log-and-profile.md`, four Bazel versions, with a "not measured" section so nothing there is mistaken for a finding |

## Phase 4 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Detailed metrics appear without replacing source-specific values | Met — Phase 4 writes no column any earlier phase wrote. An attempt's `start_micros` sits beside the action's and neither replaces the other; `ActionInspectionTest` and `RealBazelEnrichmentTest` both assert the action's own timing columns are untouched by enrichment. The actions table's Runner column comes from the attempt and the Duration column still comes from the BEP. |
| Ambiguous correlations remain visible | Met — `action_attempts.correlation` records which of five situations produced a null `action_id`, and `correlation_note` says it in words. The coverage panel counts the two that need attention and states that their own measurements are still correct; the attempt inspector shows the reason on every attempt. A test's two spawns are both kept and neither is chosen. |
| Enrichment failure does not invalidate BEP | Met — structurally, not by care: the importers write only to tables schema v4 added and run in their own transactions. `ExecutionLogImporterTest` and `ProfileImporterTest` each feed a broken file to a session with BEP data and assert the action rows are unchanged, nothing landed, and the task row explains what the user lost in the words plan 21.4 asks for. A failed profile import leaves a succeeded execution-log import alone. |
| Profile and execution-log imports are resumable where practical | **Partial, and stated as such.** Neither is resumable. Both run in one transaction that either lands whole or rolls back, and re-running is the recovery. See the interpretation below. |

### Interpretations worth knowing

**"Resumable where practical" was read as "not practical here", deliberately.**
Phase 1's BEP import is resumable because the source is tens of gigabytes,
arrives over minutes, and is the thing the session is made of. An execution log
and a profile are neither: they are written by Bazel at the end of the build,
are read in seconds, and are enrichment — losing one costs the user the
enrichment and nothing else. So both import inside a single transaction and a
failure rolls the whole thing back, which is simpler than a resume point and
has the property that matters more: a half-imported execution log never exists.
`enrichment_tasks.resume_offset` is where a resume point goes if a real profile
ever makes one worth having; it is currently always null, which the column's
comment says.

**An execution log from another build is refused; a profile from another build
is not.** The log's timings would attach to the wrong actions, and that is
worse than not having them. A profile's phases and counters are about the
machine and the invocation as a whole, and a user comparing two builds is doing
something reasonable — so it imports with `build_id_matches = 0` recorded, and
the panel says so.

**The execution log covers about a third of the actions, always.** Measured 4
spawns against 13 published actions. The other actions run inside the Bazel
server and never start a subprocess, so no attempt record exists for them and
none ever will. Every place that shows the ratio says why.

**Bazel 6.5.0 attempts have a length and no position.** That version never
emits a spawn start under any flag setting, so its attempts cannot be drawn on
a timeline. `start_unknown_reason` carries the sentence, and
`RealBazelEnrichmentTest` asserts every 6.5.0 attempt has one.

**Nothing joins Bazel's critical path to the actions table.** Its components
name themselves with a progress message and nothing else. ADR-009 wants Bazel's
answer kept as Bazel's regardless, and the visualizer's own dependency critical
path is a Phase 6 computation over a graph Phase 5 has not built yet.

## Phase 4 audit

Done by hand rather than by a fleet: a systematic self-review against the
defect classes Phase 3's audit found, each turned into a check that can be run
again rather than remembered. Six findings, all fixed — three dead columns, one
method built and never wired, one unmeasured number in a comment, and one
unbounded JDBC batch. Two more came from running the code against real Bazel
and could not have been found any other way: a paging benchmark that measured
an empty table and passed, and capability detection probing a different Bazel
than the build ran. `docs/phase4-audit.md` is the full report.

### Verified from clean (Phase 4)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 35s, 76 tasks all executed, 1,002 tests across 121
classes, 0 failures, 0 errors, 0 skipped** — up from 907 at the end of Phase 3.

The same caveat as then applies and is worth repeating: *0 skipped* is a fact
about this machine. The real-Bazel tests are guarded by
`assumeTrue(bazel.isPresent())`, and Phase 4 added `RealBazelEnrichmentTest`,
whose four-version sweep needs 6.5.0, 7.6.1, 8.4.1 and 9.2.0 all installed. On
a machine without them the count stays 1,002 and the skipped count rises.

One warning is expected and is not a failure: `io.airlift.compress.zstd`
calls `sun.misc.Unsafe::objectFieldOffset`, which Java 25 warns about
terminally. See ADR-008 for the exposure and the exit.

## Phase 5 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Generate auxiliary command plans | Done — `AuxiliaryQueryPlanner`, plan 8.6's ten rules; options fall into three groups, not two (carried, rejected-and-named, instrumentation-left-behind-and-not-counted-as-a-loss) |
| Capture protobuf query outputs | Done — run after the build, stdout redirected to a file because it is binary and `Subprocess.run` decodes UTF-8 |
| Import action graph | Done — one sub-message at a time from a `CodedInputStream`; the container is never materialised |
| Import configured-target graph | Done — same streaming treatment; edges reach labels that are not nodes, because a rule's inputs include its source files |
| Correlate graph actions | Done — by reconstructed primary-output path; unmatched rows on both sides are expected and kept |
| Preserve depset DAG | Done — `graph_depsets` and its two edge tables, never flattened |
| Derive artifact producer/consumer edges | Done — plan 13.1 as one statement; source artifacts excluded by the join, pairs deduplicated by the group-by |
| Implement external edge sorting | Done, by delegation — SQLite's external merge sort, and the code says so rather than claiming a fresh one |
| Build forward/reverse CSR indexes | Done — `CsrFile` with magic, version, counts, CRC32C and atomic rename; reverse derived from forward so they cannot disagree |
| Add graph completeness diagnostics | Done — on `graph_sources`, not in a second table; see the audit for why the second table was removed |
| UI: dependency and reverse-dependency trees, selected-action neighbourhood, path-between-nodes, graph-source selector | Done — the Graph card, which therefore arrives in Phase 5 rather than 7 |
| aquery/cquery ground truth recorded | Done — `docs/aquery-and-cquery.md`, four versions, twelve findings, with a "not measured" section |

## Phase 5 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Direct action dependencies and reverse dependencies are queryable | Met — `ActionEdgeAndIndexTest` derives edges from a real `aquery` graph and walks the genrule chain in both directions: `gen_a -> gen_b -> gen_slow` forward, and nothing behind `gen_a` because it is at the head. The Graph card reaches both from a selected action. |
| Large graph construction is bounded-memory | **Met in construction, unmeasured at scale.** No step holds the edge set: the file is read one sub-message at a time, staging is SQLite temp tables that spill to disk, the derivation never leaves the database, and the CSR build streams a query twice. What has not been done is running it on a five-million-action graph — the fixture is six targets. See the interpretation below. |
| Configuration mismatches are visible | Met — `ConfigurationMatch` is a checked set comparison, not a judgement, and `EXACT` is the only state that permits a graph to be called the build's. A mismatched graph is imported, labelled in the selector, warned about above the trees, and still shown, because its actions are real. |
| Forward and reverse indexes are consistent | Met — the reverse index is `CsrBuilder.reverse` of the forward one rather than a second query, so disagreement is impossible rather than unlikely; the test still asserts every forward edge appears reversed. A file whose header disagrees with its registry row is refused. |
| A failed auxiliary query leaves the rest of the session usable | Met — the two graphs are independent `graph_sources` rows; a failed import writes no graph rows and leaves the executed actions untouched, which `ActionGraphImporterTest` asserts by counting them before and after. |

### Interpretations worth knowing

**"Bounded memory" is a property of the construction, not a measurement.**
Every step is streaming or delegated to SQLite, and the code says which. But the
largest graph this has run on is sixteen actions. Phase 0's spikes measured the
CSR structures at five million nodes; the import path in front of them has not
been measured at that size, and the honest statement is that it is built not to
hold the graph rather than that it has been shown not to.

**The external sort is SQLite's.** Plan 13.1 asks for bounded buffers, sorted
runs, a merge and a dedup. A `GROUP BY` SQLite cannot satisfy from an index is
exactly that, with far more testing behind it than a fresh implementation would
have. Delegating is recorded in the code as a decision, not presented as an
implementation.

**Bazel's own graph and this session's executions are different populations.**
Measured: `aquery` declares the `TestRunner` action of every test that a `build`
invocation never runs, and the event stream reports a `stable-status.txt` action
`aquery` never declares. Neither count is "the build's actions", and the UI says
which is which.

**A configured-target edge names a label, not a configured target.** The proto
*can* say more — `Rule.configured_rule_input` carries a dependency's label with
its configuration checksum — and Bazel fills it zero times on all four versions,
with and without `--proto:include_configurations`. So the limit is Bazel's, not
the schema's, and the table has no column because there is nothing to put in it.

**The Graph card arrives in Phase 5.** Plan 24 gives Phase 5 the trees, the
neighbourhood, the path search and the source selector; Phase 7 is the rendered
canvas with layouts and semantic zoom, which will read the same indexes.

## Phase 5 audit

Six findings, all fixed: a whole table declared and never written, a column only
ever set to NULL, a column plan 12.4 needs that was never written, a
configuration check that made its own success unreachable, binary query output
about to be round-tripped through a UTF-8 String, and two bugs the CSR work
surfaced. `docs/phase5-audit.md` is the full report, including what the
validation sweep cost and why it was cut to one Bazel version.

### Verified from clean (Phase 5)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 50s, 76 tasks all executed, 1,090 tests across 131
classes, 0 failures, 0 errors, 0 skipped** — up from 1,002 at the end of
Phase 4.

*0 skipped* remains a fact about this machine rather than the suite: the
real-Bazel tests are assumption-guarded, and this machine has 6.5.0, 7.6.1,
8.4.1 and 9.2.0 installed. Phase 5's own end-to-end test needs only whichever
Bazel is on the path.

The suite's memory cost is now bounded rather than machine-sized. Bazel picks a
server heap from the machine's RAM, so on a large machine four version servers
plus parallel test JVMs took a development machine past 120 GB. The fixture rc
caps each server at 1 GB with `max_idle_secs=15`; four genrules need no more,
and every existing version sweep still passes.

## Phase 6 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Build timeline LOD index | Done — Phase 0's pyramid plus the rest of plan 14.3's per-bin fields; 54 bytes a level-0 bin instead of 32, and both benchmark tiers keep full millisecond resolution |
| Implement custom Swing timeline | Done — `TimelineView`, density at broad zoom and exact spans at close, header and lane labels as their own components |
| Add action and attempt lanes | Done — `SessionSpanSource` streams both; tests are not streamed separately because their window is their attempts' and drawing both would double-count the density |
| Add grouping and sorting | Done — plan 14.2's eight groupings and five sorts, with honest fallbacks for what a session cannot supply |
| Add live retroactive insertion | Done — attached to a running capture, coalesced to two seconds, viewport never touched |
| Add selection synchronization | Done — both directions, and neither moves the other's viewport |
| Add time-range filtering | Done — a dragged range narrows the actions table, matching overlap rather than containment |
| Add critical-path overlay | **Partial** — the path is computed (`analysis-core`) and the overlay colour is defined; the view does not yet draw it. See below. |

## Phase 6 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Broad views use aggregate bins | Met — above `SpanWindow.MAX_SPANS` in view the canvas paints the pyramid's bins, one column per pixel. The status line names which mode is in use, because a density plot and a span plot answer different questions. |
| Close views show exact spans | Met — below that threshold the exact spans are fetched into a `SpanWindow` and drawn individually. A range holding more than the cap says how many it left out rather than drawing a stripe that looks complete. |
| Tier 2 remains interactive | Met — measured. 1M spans: index builds in 0.170 s, frames at p95 0.91 ms against a 33 ms budget. Tier 3's 5M spans build in 1.124 s and draw at p95 0.78 ms. Live rebuilds are coalesced to two seconds so a running capture cannot starve the frame budget. |
| No SQLite access occurs during painting | Met, and enforced — `TimelinePaintIsolationTest` fails if any painting class gains a field that can reach a database, and separately asserts the controller still has one so the test cannot pass by the split collapsing. |
| Live updates do not reset viewport or selection | Met — `TimelineViewport.withWall` is the only path a live update takes and returns a navigated viewport untouched, selection and dragged range included. Ten tests, including a build growing ninety-fold without moving the view a pixel. |

### Interpretations worth knowing

**The critical-path overlay is computed but not drawn.** `CriticalPath` produces
the path, the slack and the makespan, with 13 tests; `TimelineColours` defines
the overlay colour. What is missing is the join from graph node indices to
timeline spans, which needs the graph and the timeline to agree on identity —
the graph is keyed by `declared_actions.node_index` and the timeline by
`actions.id`. That join is a Phase 7 concern where the graph view needs it
anyway, and shipping the overlay against a guessed correspondence would draw a
confident line through the wrong actions.

**A lane's total duration is not its elapsed time.** Overlapping spans are
counted twice, deliberately: "where did the time go" is a question about work,
not wall clock. Both numbers are on the lane.

**The category summary is narrower than the plan asks.** Plan 14.3 says "top
mnemonics or category summary". A per-bin histogram is unaffordable at millions
of bins, and the two-word vote that fits cannot prove a majority in one
streaming pass. What it can prove for free is that a bin holds exactly one
category, so that is what is reported and a mixed bin reports nothing. Real
builds spend long stretches on one kind of work, so it fires often.

**The timeline is up to two seconds behind a running build.** Rebuilding the
pyramid costs 170 ms at Tier 2 and capture progress arrives many times a second.
Coalescing is what keeps the frame budget; the lag is the price and is stated
rather than hidden.

**Tests are not a separate span kind.** Plan 14.1 lists them, and their window
is their attempts', which are already drawn. Drawing both would count the same
work twice in the density, and a viewer cannot tell a doubled bin from a busy
one.

## Phase 6 audit

Four findings, all fixed: four per-bin aggregates computed and read by nobody, a
time-range filter with nothing to filter, a comment describing a majority check
the code did not make, and a live refresh that would have rebuilt the pyramid on
every progress tick. `docs/phase6-audit.md` is the full report, including the
check that became a test.

### Verified from clean (Phase 6)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 51s, 79 tasks all executed, 1,145 tests across 137
classes, 0 failures, 0 errors, 0 skipped** — up from 1,090 at the end of
Phase 5. `analysis-core` has its first 13.

*0 skipped* is still a fact about this machine, which has all four pinned Bazel
versions installed. Phase 6 added no test that needs Bazel at all: the timeline
is measured with the synthetic generator, which costs no server and is why
these figures could be taken at Tier 3 without going near the memory ceiling
that made the suite unrunnable.

## Phase 7 checklist (as of 2026-08-22)

| Task | Status |
|---|---|
| Implement graph extraction API | Done — `GraphExtract`: dependencies, reverse dependencies, neighbourhood, path and whole-graph, each bounded and each carrying the totals it drew from |
| Implement clustering | Done — `GraphClustering` by package, target or mnemonic; every node in exactly one group and every edge counted, with `clusteredNodes`/`clusteredEdges` exposed so the arithmetic is checkable |
| Implement layered, radial and critical-path layouts | Done — plus grid for cluster summaries. All four are O(V+E), deterministic, and cancellable |
| Implement spatial index | Done — `GraphSpatialIndex`, a uniform grid (plan 13.6's "or equivalent"); 20,000 hit tests over 50,000 nodes in 2 ms |
| Implement custom Java2D graph canvas | Done — `GraphCanvas`, painting from a prepared model with no route to a database or to a layout function |
| Add semantic zoom | Done — plan 13.6's far, medium and near bands, with label thresholds and an edge budget at far zoom that reports itself |
| Add limit estimation and warnings | Done — `LimitEstimate` before a drawing is attempted, and a bar offering all three of plan 13.6's actions plus grouping |
| Cache layouts | Done — keyed by the whole request record, so query *and* settings both count; bounded LRU |
| Add export of visible and complete filtered graphs | Done — `GraphExport`, DOT and CSV, streamed and written through a temporary file, each carrying its own provenance |

## Phase 7 exit criteria (plan section 24)

Every criterion has a test in `Phase7ExitCriteriaTest`, run against a real
session database rather than a stub, because five of the six are statements
about what a user sees.

| Criterion | Status |
|---|---|
| Small subgraphs render in full detail | Met — a five-node neighbourhood draws every node, every edge between them, and a label on each; the fit lands in the near band and no warning is shown. |
| Large graphs automatically aggregate | Met — a whole-build request that will not fit switches itself to cluster mode rather than returning a blank canvas with an explanation. The grouping accounts for every action and every dependency, which the test asserts by summing the boxes. |
| Exact totals remain visible | Met — every rendering carries a sentence naming the graph's totals whether or not anything was omitted, because a view of five actions from a build of sixty is otherwise indistinguishable from a build with five actions. |
| Raising limits is explicit | Met — nothing raises itself. The limit stays where it was until "Draw it anyway" is pressed, and pressing it returns to the view that was asked for rather than leaving the user in the grouped one. |
| Layout is cancellable | Met — all four layouts, given an already-raised flag over a 100,000-node graph, return a placement of nothing rather than a partial one. A superseded request is cancelled and the last one submitted is what appears. |
| Panning and selection remain responsive | Met — measured at the plan's own limits. 50,000 nodes and 200,000 edges: extract, layout, index and label in 14 ms; a fitted frame in 35 ms; a near-zoom frame, where every visible edge and label is drawn, in 4 ms; 20,000 hit tests in 2 ms. |

### Interpretations worth knowing

**Phase 6's critical-path overlay is still not drawn on the timeline.** Phase 7
supplies both halves of the join it was waiting for —
`GraphQueries.durationsByNodeIndex` weights the path and `actionIdsByNodeIndex`
maps a node back to an executed action — and the graph canvas draws paths.

*Corrected after Phase 8:* this section originally predicted the timeline
overlay would land with Phase 8's metrics work. It did not. Phase 8 kept the two
critical paths distinct, computed the derived one for the first time in
production, and drew the chain on the **graph** canvas, which already had path
rendering. The timeline overlay remains an unshipped Phase 6 deliverable rather
than a Phase 8 omission, and it is recorded here as one so the prediction does
not stand as a claim.

**The far zoom band stops drawing individual edges above 30,000 of them.** Plan
13.6's far band asks for aggregate edge thickness rather than individual edges,
and there is a measured reason: 200,000 hairlines took 366 ms a frame and
resolved to a grey smear. The omission is transient, reverses on zoom, and is
named on screen by `hiddenDetail()` — a blank area that looked edgeless would be
a claim about the build, and a false one.

**A uniform grid, not a quadtree.** Plan 13.6 says "quadtree or equivalent". The
layouts place points on or near a lattice, so a quadtree's recursive subdivision
buys nothing over a grid that is two counting passes and three int arrays.

**Path modes cannot be selected, only arrived at.** `PATH` and `CRITICAL_PATH`
appear in the mode list so a found path can be shown as the current mode, but
picking one does nothing: a path needs two endpoints that only a search
supplies, and a mode a user could select but never satisfy would be a dead
control.

**Clustering refuses rather than truncating, twice over.** A graph with more
groups than the cluster limit returns nothing with the exact count, exactly as
`GraphExtract.whole` does for nodes. The answer to "too many packages" is to
group by mnemonic, which is always a small set, and the message says so.

## Phase 7 audit

Sixteen findings, all fixed. Eleven from counting call sites — including three
promises the code had made and not kept: a method whose error message pointed at
a `submitPath` that did not exist, a path extraction that nothing could reach so
no path could ever be drawn, and the node-to-action join committed as closing
Phase 6's gap while wired to nothing. Two from measuring: a fitted frame at the
plan's own limits took 366 ms, and the first layered layout was O(V·E) in the
worst case. One from writing the exit-criteria test, which found that every
graph's first view was fitted to a one-pixel window. And two in the docs: the
summary table above claimed Phases 4, 5 and 6 were "Not started" while the same
file documented their completion, and `docs/architecture.md` described an
`analysis-core` dependency on `storage-sqlite` that has never existed.

`docs/phase7-audit.md` is the full report.

### Verified from clean (Phase 7)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 47s, 79 tasks all executed, 1,281 tests across 150
classes, 0 failures, 0 errors, 0 skipped** — up from 1,145 at the end of
Phase 6.

*0 skipped* is still a fact about this machine, which has all four pinned Bazel
versions installed. Phase 7, like Phase 6, added no test that needs Bazel:
nothing in graph drawing depends on Bazel's behaviour, so rule 18 does not
apply. The scale figures come from the synthetic generator, which costs no
server — the reason they could be taken at the plan's full 50,000-node limit
without going near the memory ceiling that made the suite unrunnable earlier.

## Phase 8 checklist (as of 2026-08-23)

| Task | Status |
|---|---|
| Implement action/invocation metrics | Done — `ActionMetrics` for plan 15.1 and `InvocationMetrics` for plan 15.2, every optional value a `Measured` or an `Optional` so a view cannot render it without deciding what to say when it is missing |
| Implement quantile sketches | Done — `QuantileSketch`, a fixed-layout integer histogram: bounded memory per group, mergeable so a live capture folds new actions into a total already drawn, and exact under merge because no floating point decides where a value goes |
| Implement concurrency sweep | Done — `ConcurrencySweep`, an exact event-point walk rather than a binned count, with half-open spans so a handover is not an overlap |
| Implement dependency critical path and slack | Done — `CriticalPath` (Phase 6) gains per-node accessors and is wired for the first time: `CriticalPaths` holds it beside Bazel's, the chain finding reports slack, and the graph canvas draws it |
| Implement findings engine | Done — all thirteen of plan 16.1's rules, over a bounded candidate set collected during the scan |
| Add evidence and caveats | Done — structurally: a `Finding` with no evidence cannot be constructed, and `FindingLanguage` refuses the phrasing plan 16.2 names |
| Add dashboard navigation | Done — a Findings card, overview cards that navigate, and finding links that carry a `Kind` enum rather than a filter string |

## Phase 8 exit criteria (plan section 24)

Every criterion has a test in `Phase8ExitCriteriaTest`, run against a real
session database with a real CSR index over a real action graph — the smallest
session in which both critical paths exist at once, which the second criterion
is about.

| Criterion | Status |
|---|---|
| Every displayed metric reports source and completeness | Met — `MetricSeries` carries source, completeness and the count that did not report, and `describe()` is the sentence a view must print beside the number. The test walks every series in every aggregate, every coverage entry, and every number inside every finding. |
| Bazel-reported and derived critical paths remain distinct | Met — and structurally, not editorially. `CriticalPaths` holds both and offers no accessor called `criticalPath`, no best-available fallback and no merge; the test asserts by reflection that no such accessor exists, and that the screen shows two rows rather than one. |
| Findings link to supporting records | Met — a finding with no evidence throws at construction. The test resolves every action-kind evidence id back to a row in the session, so an id that named nothing would fail. |
| Findings avoid unsupported causal language | Met — `FindingLanguage` refuses the phrases plan 16.2 lists and requires the sentence a reader acts on to hedge. "Root cause" is permitted only when the caller declares it holds a structured failure record. The test scans every finding the real session produces for the banned phrasing. |
| Formulas have deterministic unit tests | Met — `QuantileSketchTest` (merge is commutative and associative; every reported interval contains the true quantile), `ConcurrencySweepTest` (agrees with a brute-force count; order-independent), `FindingRulesTest` (each rule fires on its own shape and not on others). And end to end: collecting the same session twice gives equal aggregates, equal findings and equal counts. |

### Interpretations worth knowing

**The duration source is measured, not assumed.** Two sources report how long
an action took and they disagree about more than the number: the build event
stream publishes no action timestamps on Bazel 6.5.0 or 7.6.1, publishes
`endTime == startTime` for every action on 8.4.1, and omits a third of them on
9.2.0, while the execution log times only the actions that spawned a
subprocess. `bestDurationSource()` counts what each covers in *this* session and
picks the larger. An action reporting the same start and end is untimed rather
than a duration of zero — while its span still reaches the sweep, which reports
it as instantaneous rather than losing it.

**The two critical paths are meant to disagree.** Bazel's includes scheduling
and machine limits; the derived one is what the dependency graph alone implies.
`schedulingGapMicros()` is the difference, deliberately signed, and a build
where the two are close was limited by its dependencies while one where Bazel's
is much longer was limited by something else. They also cannot be joined:
Bazel's components identify themselves by a progress message and nothing else.

**A finding is a candidate, not a diagnosis.** Every rule reports a measurement
and a threshold and stops. That is what one build on one machine supports: the
queue-dominated rule can say queue time was most of an attempt and cannot say
what the queue was, which is what plan 16.1's own text for that rule says too.

**Two rules are grounded rather than guessed.** Cache-miss concentration refuses
to fire without cache-state coverage, because an action with no execution-log
record is not a miss. Non-cacheable concentration reads Bazel's own `cacheable`
and `remotable` declarations rather than deciding what a runner string like
`darwin-sandbox` implies — `spawn.proto` constrains that field to nothing and
says it varies under the dynamic strategy.

**The timeline's critical-path overlay is still not drawn, and Phase 8 is not
where it went.** The Phase 7 notes predicted it would land here; that prediction
was wrong and is corrected above. What Phase 8 delivered instead is the chain
drawn on the *graph* canvas, which already had path rendering and needed only
the node indices. The timeline overlay remains an unshipped Phase 6 deliverable.

**Findings run once per session, not on a timer.** The overview refreshes every
two seconds because its numbers are counts over indexed tables. A collection
scans every action, sweeps every span and runs thirteen rules — 898 ms at
250,000 actions — so it runs when a session opens and when the user asks again,
and one collection feeds both the findings card and the overview's new cards.

## Phase 8 audit

Thirty-five findings, all fixed. Twenty of them were one defect wearing twenty
faces: plan 15.2's invocation metrics and plan 15.3's aggregate distributions
were computed on every collection and displayed nowhere — the same "work wired
to nothing" the last two audits each found several of, at a larger scale. Six
were genuinely dead API and were deleted. Two were plan requirements the rules
had missed, found by asking what each unread accessor was *for*: plan 16.1
requires a chain finding to show slack and graph coverage, and it showed
neither. One was a link whose words promised to draw the dependency chain and
whose handler opened the graph and stopped, which is the Phase 7 finding
recurring in a new place and is fixed the same way — by making the link carry an
enum a destination must handle to compile.

`docs/phase8-audit.md` is the full report.

### Verified from clean (Phase 8)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 55s, 79 tasks all executed, 1,369 tests across 159
classes, 0 failures, 0 errors, 0 skipped** — up from 1,281 at the end of
Phase 7.

*0 skipped* remains a fact about this machine, which has all four pinned Bazel
versions installed. Phase 8, like Phases 6 and 7, added no test that needs
Bazel: nothing in the metric catalog depends on Bazel's behaviour at run time,
only on what four measured versions were already recorded as reporting, so
rule 18 does not apply. Both scale measurements use synthetic data — which is
why the formulas could be measured at the plan's full five-million Tier 3
ceiling without going near the memory that made the suite unrunnable earlier.

## Phase 9 checklist (as of 2026-08-23)

| Task | Status |
|---|---|
| Implement portable `.bviz` | Done — Zip64 out through a temporary file, checksums verified before the rename; a reader that validates end to end before extraction writes a byte |
| Implement binary BEP export | Done — `BepStreamExport`, from the raw journal, preserving Bazel's own bytes for a live capture and saying so when it cannot |
| Implement CSV/JSON graph and table exports | Done — five tables in two formats, streamed row by row; the graph's DOT and CSV export landed in Phase 7 and now shares the RFC 4180 quoting |
| Implement redacted export | Done — pseudonyms rather than a mask, paths mapped rather than deleted, and the report shown before anything is written |
| Add recent-session library | Done — plan 10.6's catalog, a two-table index that a rescan rebuilds from the directories |
| Add retention and cleanup | Done — a plan is shown, the sweep takes the plan rather than the policy, and a pinned session is never a candidate |
| Add macOS file associations | Done — `.bviz` only, declared through jpackage and verified in the built `Info.plist` |
| Add macOS app menu and open-file handlers | Done — About, Open File and Quit; Preferences deliberately not installed until there is a settings screen |
| Build Apple Silicon and Intel packages | Partial with a stated reason — jpackage does not cross-compile, so this is one task run on two machines; `docs/packaging.md` says so rather than a build naming one package after both |
| Add signing/notarization hooks without embedding credentials | Done — both read the environment, and `notarize` refuses without a keychain profile name rather than prompting for an Apple ID it should never see |

## Phase 9 exit criteria (plan section 24)

Every criterion has a test in `Phase9ExitCriteriaTest`, against real archives, a
real catalog and a real database.

| Criterion | Status |
|---|---|
| Sessions survive application restart and relocation | Met — restart is the catalog file; relocation matches by session UUID, which is the one thing a move does not change, and the user's pin survives it. The application rescans once per launch, which the audit found it was not doing. |
| Portable archives validate before opening | Met — `validate` decompresses every entry and discards the bytes; `extract` runs the same checks again while writing. A zip-slip entry is refused with nothing on disk, and the test asserts the escaped file is not there. |
| File associations open the app | Met structurally and verified by hand — the descriptor declares the extension the writer uses, the built `Info.plist` carries it as a `CFBundleDocumentTypes` entry and an exported UTI, the classifier routes it, and `MainWindow.openPath` is the public route the desktop handler calls. Finder actually doing it needs an installed bundle. |
| Export does not require loading the entire session into memory | Met and measured — a 200,000-row CSV of tens of megabytes grows the heap by less than half the file's size. Nothing in the export path builds a list, a value tree or a string of the whole result. |
| Redaction tests pass | Met — `RedactorTest`, `SessionRedactionTest` and the exit test's own end-to-end check that a token and an account name do not reach an exported file. |

### Interpretations worth knowing

**A redacted archive cannot carry the raw capture, and the reader refuses one
that claims to.** The raw journal is the original bytes, secrets included, so
exporting it beside a redacted database would undo the redaction completely. An
archive is either redacted or complete; opening a redacted one says what that
costs — its enrichments cannot be re-run and its database cannot be rebuilt.

**Zip-slip is closed by allow-list first, path arithmetic second.** A session
archive holds a known set of files in a known shape, so anything else is refused
before any resolution happens. That is also how "never load native code from a
session archive" is really implemented: a `.dylib` is not rejected by refusing
to load it, it is rejected by never reaching disk.

**Entry names must be letters, digits, dot, dash and underscore.** Every file a
session contains is named that way, so the restriction costs nothing and closes
what path checks do not — a control character that rewrites a terminal, a
right-to-left override, a Unicode form that normalises differently on macOS than
the form that was checked.

**The redaction inventory is a test, not a document.** 35 columns rewritten, 83
named as deliberately left alone, and every `TEXT` column in the schema must be
in one list or the other. `strings.value` is the one that matters most: it is
the interned dictionary and holds whatever the stream put in it, so redacting
the obviously-sensitive columns while leaving it alone would leak the same data
through the side door.

**One deviation from plan 10.4, stated.** "Store already compressed large files
without recompressing" is implemented as `NO_COMPRESSION` rather than a
literally `STORED` entry, because a stored entry needs its size and CRC before
the first byte is written and that means reading every large file twice. The
rule's purpose — no compression CPU on incompressible data — is met in one pass.

## Phase 9 audit

Fifty-eight members with no production caller; five of them were features wired
to nothing, including `rescan`, without which the relocation exit criterion was
true of the code and false of the application. Four capabilities the plan names
were reachable only from a test. The column check found a bug in the audit
method itself: schema v3 renamed two columns with `ALTER TABLE RENAME COLUMN`,
which every previous phase's `CREATE TABLE`-only grep could not see. And
`docs/privacy.md` claimed the UI masked sensitive fields when nothing did.

`docs/phase9-audit.md` is the full report.

### Verified from clean (Phase 9)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 57s, 79 tasks all executed, 1,474 tests across 168
classes, 0 failures, 0 errors, 0 skipped** — up from 1,369 at the end of
Phase 8.

Phase 9 added no test that needs Bazel. Nothing in archiving, redaction,
cataloguing or packaging depends on Bazel's behaviour, so rule 18 does not
apply — with one exception that runs on every build: the BEP export's tests
build real BES envelopes and real `BuildEvent` messages from the synthetic
stream and compare the exported bytes to what went in, which is the property
that matters and needs no Bazel server to check.

The packaging was verified by running it: `./gradlew :app:jpackage` produces an
image whose `Info.plist` declares the `.bviz` association and whose launcher
runs the CLI. That is not part of `build` — jpackage is slow and platform-bound
— so it is recorded here rather than gated on.

## Phase 10 checklist (as of 2026-08-23)

| Task | Status |
|---|---|
| Run all benchmark tiers | Done — Tier 3 raw capture (50,000,000 events), Tier 3 indexed session (5,000,000 actions), Tier 3 timeline, Tier 2 graph. Figures in `docs/performance.md`. |
| Profile heap allocation | Done — 0.48 GB resident capturing 50,000,000 events; 0.96 GB with the larger page cache. Objective 9's budget is 4 GB. |
| Remove per-event/per-edge retained objects | Done — and now enforced: `BoundedMemoryTest` asserts the CSR graph is two primitive arrays and that nothing streaming the build retains a domain object |
| Tune SQLite and queues | Done — three pragmas, measured before and after at two scales, with the unflattering result published |
| Verify Bazel 6–9 fixtures | Done — `BazelVersionMatrixTest`, one real instrumented build per version, all four complete with nothing lost |
| Test incomplete and corrupt sessions | Done — `DamagedSessionTest`, seven cases end to end |
| Perform privacy review | Done — `docs/security-review.md`, plan 22.1 and 22.2 clause by clause |
| Perform archive/parser security review | Done — same document, plan 22.3 and 22.4, with the one partial stated |
| Document known version limitations | Done — `docs/bazel-compatibility.md`, including the action-timing table that differs on every version |
| Write user guide and troubleshooting guide | Done — `docs/user-guide.md`, and `docs/troubleshooting.md` extended to cover sessions |

## Phase 10 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Tier 3 raw capture succeeds without data loss | **Met** — 50,000,000 events sent, acknowledged, journaled (11.8 GB across 42 segments) and indexed. `received == journaled` and `journaled == normalized + stream-control`. 0.48 GB resident. |
| Tier 3 indexed session can be reopened and queried | **Met** — 5,000,000 actions in a 1.5 GB database, closed and reopened from cold: overview in **9.6 ms** against a five-second objective, first page in **0.6 ms** against five hundred milliseconds. |
| Aggregate timeline and graph remain usable | **Met** — timeline p95 **0.89 ms** at 5,000,000 spans; graph p95 **1.29 ms** at 1,000,000 nodes and 20,000,000 edges. The budget is 33 ms. |
| Every limit is explicit | **Met** — `docs/limits.md` enumerates every bound, what happens when it is reached and whether it can be moved, and `LimitsDocTest` checks the page against the constants so it cannot drift. |
| Bazel compatibility matrix is published | **Met** — `docs/bazel-compatibility.md`'s release matrix, produced by a test that prints the row it asserts. All four versions capture completely. |
| No known routine path blocks the EDT | **Met** — `EdtDisciplineTest` walks every compiled Swing component and asserts that one which can reach a database owns a thread; three paint-isolation tests assert the painted views can reach neither a connection nor an executor. |
| Release candidate packages launch on supported macOS architectures | **Partial, with a stated reason** — the Apple Silicon package was built and launched: the bundle declares the `.bviz` association, the launcher runs the CLI, and a smoke launch created the application-support directories and exited cleanly. **Intel is unverified**: jpackage does not cross-compile, so it needs an Intel machine. |

### Interpretations worth knowing

**The capture rate falls five-fold with table size, and that is published rather
than averaged.** 79,359/s at 200,000 events, 43,803/s at three million,
16,157/s at fifty million. The cause is the SQLite page cache, not index
maintenance; the pragmas that fix it at three million (+18%) barely help at
fifty million (+4%), because 128 MB of cache covers none of a six-gigabyte
b-tree. What this means for a real build is smaller than it sounds: a Tier 3
build emits its events over minutes, and sixteen thousand a second is a million
a minute.

**Objective 1 is still not met**, and its gap is now better understood. 79.4k/s
against a 100k/s target, and the shortfall is gRPC's per-message acknowledgement
— replacing the whole pipeline with a sink that stores nothing produces the same
rate. Closing it means coalescing acknowledgements, which cannot be attempted
without an experiment against all four Bazel versions first: an acknowledgement
with the wrong sequence number kills the user's Bazel server on 6.5.0 and 9.2.0.

**The Bazel sweep is excluded from `./gradlew build`.** Four servers is four
downloads and several minutes, and Bazel sizes its server JVM from the machine's
RAM. The fixture caps each at `-Xmx1g` and the sweep runs one at a time; it is
still opt-in behind `-Pbbv.bazelSweep=true`. A single-version end-to-end capture
stays in the default suite.

## Definition of done for v1 (plan section 25)

Walked item by item. Twenty-nine of the plan's thirty-one items are met; the two
that are not are named.

| Group | Status |
|---|---|
| **Invocation** (6 items) | Met. The executable selector was the one gap and was added in this phase. |
| **Capture** (6 items) | Met. Embedded BES, file fallback, binary and JSON import, raw preservation, interrupted recovery, and disclosure of missing sequences — the last verified on all four Bazel versions. |
| **Analysis** (6 items) | Met. |
| **Visualization** (5 items) | **4 of 5.** "Every display limit is visible and configurable" — every limit is visible and stated, and only the graph's node and edge limits are raisable from the UI. There is no settings screen. |
| **Persistence** (5 items) | Met. |
| **Performance** (4 items) | Met, with objective 1's burst target as the stated shortfall. |
| **Quality** (5 items) | **4 of 5.** "Packaging works on Apple Silicon and Intel macOS" — Apple Silicon built and launched; Intel unverified because jpackage does not cross-compile. |

Both shortfalls are the same kind: a thing that exists and is not reachable from
where the plan wanted it. Neither is a defect in what was built.

## Phase 10 audit

Zero findings from the call-site sweep — the first phase with none, and what a
hardening phase should look like. The findings came from elsewhere: the
benchmark's own client could not reach Tier 3 and had been flattering the
published capture numbers; the Tier 3 slowdown was the page cache and the fix
helps far less at Tier 3 than at Tier 2; a structural rule fired on correct code
and had to be made sharper; three documents had drifted; and plan section 25
named an executable selector that was not there.

`docs/phase10-audit.md` is the full report.

### Verified from clean (Phase 10)

`rm -rf build */build build-logic/build && ./gradlew build --no-build-cache`:
**BUILD SUCCESSFUL in 2m 54s, 79 tasks all executed, 1,489 tests across 172
classes, 0 failures, 0 errors, 0 skipped** — up from 1,474 at the end of
Phase 9.

*0 skipped* remains a fact about this machine, which has all four pinned Bazel
versions installed. The four-version sweep is **not** in that count: it is
tagged `bazel-sweep` and excluded from `build`. It was run once for this phase
and all four versions passed; re-run it with `-Pbbv.bazelSweep=true`.

The measurements behind the exit criteria are not part of `build` either — a
fifty-million-event capture takes fifty minutes and writes 17 GB. They are run
deliberately, and every figure in `docs/performance.md` names the command that
produced it.

---

# v1 is complete

All ten phases are done. Plan section 25's definition of done is met in
twenty-nine of thirty-one items, with both shortfalls named above and neither a
defect in what was built.

What ships: a local, single-user macOS application that launches or imports a
Bazel build, captures it raw-first, normalizes it into a queryable session,
enriches it from the execution log, the trace profile, `aquery` and `cquery`,
and shows it as an overview, a timeline, an action table, dependency trees, a
graph canvas, tests, errors, events, a build pane and evidence-backed findings —
at five million actions, without loading the build into memory, and without
claiming a number it does not have.

Plan section 28's deferred roadmap starts here.

## After v1

Two navigation changes from use, landed 2026-08-23. Neither changed a query, a
table or a column; both are renames and one layout.

- **Console and Capture are one Build pane.** `NavEntry.BUILD` replaces
  `CONSOLE` and `CAPTURE`. `MainWindow.buildBuildCard()` puts `CapturePanel` at
  `BorderLayout.NORTH` as a header strip over `ConsoleView` at `CENTER`.
  Following one build no longer means switching cards, and `captureStarted` no
  longer moves the user, because there is nowhere to move them to. Left
  navigation is ten entries where plan 17.1 lists eleven.
- **The Failures view is the Errors view.** `NavEntry.FAILURES` is `ERRORS`,
  `ui/failures/` is `ui/errors/`, `FailuresView`/`FailureInspection` are
  `ErrorsView`/`ErrorInspection`, and `FailureQueries`/`FailureRow` are
  `ErrorQueries`/`ErrorRow`. The view has always listed `Kind.OUTPUT` rows —
  whatever Bazel wrote to stderr — and a compiler warning on the way to a
  successful action is not a failure. **Java identifiers only:** no table,
  column or SQL literal changed and no migration was added, so every session
  already on disk still opens.

`./gradlew build`: **BUILD SUCCESSFUL, 1,490 tests across 172 classes, 0
failures, 0 errors, 0 skipped** — one more test than Phase 10's 1,489, which is
the `NavEntryTest` case pinning the merged entry.

## Post-v1 fixes

**2026-08-23 — the Overview tab no longer overflows the window.** Two
compounding causes in `OverviewPanel`: the tile rows used a fixed
`GridLayout(0, 4, …)`, which sizes every column to its widest cell's preferred
width regardless of window width, so one tile with a long note set all four
columns that wide; and the scroll pane's content was a plain `JPanel`, which
is not `Scrollable`, so the scroll pane honoured that oversized preferred
width instead of narrowing the content to fit. `CoverageView` — shown in the
same tab, stacked below the overview in a `JSplitPane` — had the identical
missing-`Scrollable` weakness.

Fixed with two new reusable pieces in `ui/theme`: `WrapLayout` (a
`FlowLayout` that reflows a row's cells to the available width instead of
demanding one unbroken row, replacing the tile grid's `GridLayout`) and
`ScrollableViewport` (a `JPanel` implementing `Scrollable`, so a
`JScrollPane`'s view tracks the viewport's width instead of overflowing it —
now the view in both `OverviewPanel` and `CoverageView`). Because forcing the
content narrower exposed a second problem — several of `CoverageView`'s
explanatory notes measure over 800px wide at the default font and would have
been silently clipped once actually confined to the window's width — its
notes now render through a third new helper, `WrappingLabel`, a
non-editable `JTextArea` styled to look like a label, which wraps instead of
using `<html>` (deliberately unavailable here; see `PlainText`'s javadoc for
why HTML rendering is off).

Not touched: the `GridBagLayout` name/value rows inside `OverviewPanel`'s own
detail sections, and `CoverageView`'s paired name/value rows, are unchanged
and still do not wrap. Their content today is short enough not to be at
practical risk, but an exceptionally long single value in either could still
be clipped at the narrowest supported window width — a smaller instance of
the same class of problem, left for whoever hits it. `ui/metrics/FindingsView`
has the same latent `GridLayout`/non-`Scrollable` shape and was not touched:
it is a different tab and was not reported.

- **Cancellation is one ladder, and it always ends the client** (2026-08-23).
  `CaptureCoordinator.cancel` started a fresh thread running a full escalation
  ladder on every click, so a user who pressed Cancel, then Terminate, then
  Force Kill — which is what a user does when the first click appears to do
  nothing — had three ladders racing, each timing its own grace period against
  one process. Signal delivery is now serialized inside
  `BazelLauncher.BazelProcess`: the first request runs the ladder, later ones
  deliver their harsher rung immediately, and no rung is ever sent twice or
  after a harsher one. An interrupted ladder now force-kills rather than walking
  away from a client that was demonstrably ignoring its signal, and a client
  still alive when the capture finalizes is force-stopped instead of orphaned —
  either would leave the workspace's command lock held, which is what makes a
  later `bazel clean` hang. Escalation past what the user asked for is recorded
  in the session's warnings rather than applied silently (rule 12).
  `--bes_timeout=60s` is now injected on the keep-your-own-backend path too; it
  used to be injected only alongside the embedded backend, leaving the one plan
  that keeps a foreign Build Event Service running on Bazel's wait-for-ever
  default. Signal delivery became testable through the package-private
  `StopSignals` seam: `CancellationLadderTest` asserts the exact sequence of
  rungs under concurrent stops, `RealSubprocessLadderTest` proves the same
  ladder against a real process that ignores `SIGINT` and `SIGTERM` without
  starting a Bazel server, and `RealBazelCaptureTest` proves the workspace lock
  is free after three stops in a row. See `docs/troubleshooting.md`, "A build
  will not stop, or the next Bazel command hangs".

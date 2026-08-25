# Implementation status

Last updated: 2026-08-24. This file states what exists in the tree, not what
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
graph canvas, tests, errors, events, a console pane and evidence-backed findings —
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

- **The timeline keeps its own clock, and its axis never labels a negative
  time** (2026-08-23). Two reports, one view. The timeline appeared not to
  render during a build because `TimelineController` had no clock of its own:
  `refreshLive()` ran only when `MainWindow.CaptureListener.captureProgress`
  called it, and that fires only when the BES stream emits, so on a quiet build
  the view simply stopped updating while `OverviewPanel` — which has always run
  its own `scheduleWithFixedDelay` — kept going. The controller now runs a
  daemon ticker of its own at the same 2 s cadence, sharing the existing
  wall-clock throttle so a timer tick and a progress tick landing together do
  the work once; it is shut down in `closeSession`, which `openSession` calls
  first, so reopening a session cannot leak a second one.
  The axis could show negative seconds because pan and zoom were unclamped and
  `Header.paintComponent` printed `microsAtX(x) - wallStartMicros` with no
  lower bound. Pan and zoom are now bounded by the clamp that already existed
  in `TimelineCanvas` and was reachable only from a benchmark, and — because a
  bounded negative is still negative — `TimelineView.ticksFor` omits any tick
  whose time precedes the wall start entirely, mark and label together. The
  label is deliberately *not* clamped to `0.00s`: that would paint one label at
  several positions and assert a viewport position the user is not at, which is
  a worse failure than the one being fixed.

- **The Events tab populates during a build, not just after one** (2026-08-23).
  `EventsView.openSession` was reachable from exactly one place,
  `MainWindow.installSession`, which only ran after `captureFinished` — so the
  table showed nothing at all while a capture was running, even though
  `EventWriter` commits on a wall-clock timer regardless of pending rows and a
  second reader connection against the live database already saw those rows,
  which is exactly what `attachLiveOverview` already relied on for the
  overview and the timeline. `MainWindow.CaptureListener.attachLiveOverview`
  now opens the Events tab over the same live `SqliteSessionSource` those two
  already use, once, the same way.
  `EventRowSource.rowCount()` is captured once at `open`, `EventRowIndex`
  decides `DENSE` vs `SPARSE_ANCHORS` from three queries at open and never
  re-queries, and `PagedTableModel` stores its row count in a
  `private final int` with no growth API — by design, so `getRowCount()` stays
  a non-blocking EDT field read. Rather than giving any of the three a mutable,
  concurrently-read count, `EventsView` now rebuilds and swaps, the same trade
  `TimelineController` (see above) already makes for the timeline: a row
  source is rebuilt off the page executor and the finished table model is
  swapped in on the EDT, only when the row count or row-index mode actually
  changed. `EventsView` runs its own daemon ticker at the same 2 s cadence for
  the same reason the timeline's does — a live view driven only by BES
  progress ticks stalls on a quiet build — shut down in `closeSession`, which
  `openSession` calls first. `EventRowSource` now exposes its backing reader
  (package-private) so a refresh reuses the same connection rather than
  opening a new one every tick, which would otherwise accumulate one reader
  per tick in `SqliteSessionSource`'s reader list for the life of a long
  build.
  `JTable.setModel` clears the selection unconditionally, so the swap
  explicitly reads back the selected row and the scroll viewport's pixel
  position beforehand and reapplies both afterwards — safe because BEP events
  are only ever appended, never reordered or deleted, so a row index or a
  pixel offset valid before the swap still names the same event and the same
  place in the table after it. The status line now states a live session's
  count as "at least N events (still capturing)" rather than a bare number
  (rule 11: a growing count is a lower bound, not a total, and must say so).
  `EventsViewLiveRefreshTest` proves the view's own timer grows the table with
  no external call driving it, proves selection and scroll survive a swap, and
  proves the lower-bound wording appears for a live session and not for a
  finished one.
- **The graph tab works on real sessions, over both graphs** (2026-08-23).
  Four defects and three absences, one change set. The defects: (1)
  `ActionEdgeDeriver.deriveAll` and `GraphIndexBuilder.build` had no production
  caller — the capture imported aquery/cquery output and stopped, so every real
  session opened the graph tab to "no action graph" while the whole Phase 5/7
  stack passed its tests. Edge derivation and CSR index building now run in
  `CaptureCoordinator`'s quiet finalization step, and a failure degrades to a
  named warning rather than failing the capture. (2) The "Depends on" tree and
  the rooted canvas modes walked the forward (producer-to-consumer) index under
  the name "dependencies", so they showed the things that *depend on* a node;
  the direction binding is fixed in `GraphExtract`/`GraphLayoutService`/
  `GraphView` and pinned by tests in all three. (3) The root rows of both trees
  never loaded their children — JTree expands a fresh root itself, so the
  lazy-load expansion listener never fired for it. (4) Clicking a canvas node
  moved only the trees; the canvas kept drawing the old root's neighbourhood.
  Double-click and a "Focus here" context menu now recentre the drawing and
  re-root the trees together.
  The absences: the configured-target label graph — imported since Phase 5 and
  read by nothing — has a CSR index (`graph_indexes` kind `CONFIGURED_TARGETS`)
  and `GraphQueries` accessors keyed on `GraphKind`; the graph-source selector
  is now a control rather than a caption, switching search, trees, path finder
  and canvas between the action graph and the label graph, with the sentence
  under it stating what a node means in each (rule 13); and the node limit is a
  spinner beside the depth control (`MIN_NODE_LIMIT`/`MAX_NODE_LIMIT` in
  `docs/limits.md`) instead of only the over-limit bar's doubling button.
  `GraphQueries.DEFAULT_NODE_BUDGET`, documented and enforced nowhere, is
  removed. The storage graph package gained its own test classes (37 tests) and
  `RealBazelGraphTest` now proves, on a real captured build, that the loaded
  indexes answer label searches and bounded traversals over both graphs.
- **The launch dialog no longer claims aquery/cquery go uncaptured**
  (2026-08-24). `InstrumentationPlanner.reportUnimplemented` put
  `DataSource.AQUERY` / `DataSource.CQUERY` into the source-availability map
  as `UNAVAILABLE`, reason "this version of the application does not capture
  it yet," and warned "The Full Graph Diagnostics preset asks for aquery,
  cquery, which this version does not capture yet" whenever a preset
  requested `Capability.AQUERY_PROTO_OUTPUT` / `CQUERY_PROTO_OUTPUT`. That was
  a Phase 2 placeholder (commit d8dcf89) that outlived the phase which made it
  true: Phase 5 (commit 99610e1) landed `CaptureCoordinator
  .queryGraphsQuietly`, which runs `bazel aquery` and `bazel cquery` from
  every live capture's `finally` block and imports both through
  `ActionGraphImporter` / `ConfiguredTargetImporter` — unconditionally, never
  consulting the availability map or the chosen preset. Nobody deleted the
  stub when the capability shipped, so the dialog went on warning users away
  from a graph the application was already producing (rule 11: never say a
  thing is unavailable when it is not; rule 13: do not claim a source is
  missing when it was captured).
  `reportUnimplemented` existed solely to make this false claim — no other
  `DataSource` was reported through it — so it is deleted outright, along
  with an orphaned javadoc block above `addExecutionLog` that described the
  same stub. It is replaced by
  `recordAuxiliaryQueryAvailability`, which marks both sources `PLANNED`
  unconditionally, for every preset, not only the ones naming those
  capabilities: since `queryGraphsQuietly` does not check the preset either,
  gating the availability entry on `requestedCapabilities()` (as the old
  method did, and as `addProfile`/`addExecutionLog` correctly do for sources
  that *are* flag-gated) would leave the map silently `UNKNOWN` for presets
  like Live Essentials that never name `AQUERY_PROTO_OUTPUT` /
  `CQUERY_PROTO_OUTPUT`, even though both queries run anyway. `PLANNED` was
  chosen over inventing a new verdict because it already means exactly this
  elsewhere in the same file: a source captured after the build rather than
  during it (`DataSource.PROFILE`, `DataSource.EXECUTION_LOG`).
  `InstrumentationPlannerTest` no longer pins the false warning in place;
  `auxiliaryQueryGraphsAreDeclaredPlanned` asserts the Full Graph Diagnostics
  preset — the exact preset named in the stale warning — launches with both
  sources `PLANNED` and no "does not capture" warning, and
  `auxiliaryQueryGraphsArePlannedRegardlessOfPreset` pins the unconditional
  behavior against Live Essentials.

- **A session can be asked a question nobody built a view for** (2026-08-24).
  Perfetto's query page is the one thing it does that nothing here replaced —
  SQL over the captured data — and it is unreachable on a build large enough to
  matter because Perfetto is a web application. This is a desktop application
  over a local SQLite file, so the size that stops Perfetto is not a constraint:
  the same `PagedTableModel` the actions and events tables use carries a result
  of any size, a page at a time, unmodified. A new **Query** card
  (`NavEntry.QUERY`, eleventh and last, in no phase of plan 17.1) holds a SQL
  editor, a schema tree read from `sqlite_master` and `PRAGMA table_info` at run
  time — the 59 tables across five schema versions had no reachable description
  at all before this — and a paged result grid.
  Nothing typed there can **write** to the session, and that part is a
  guarantee: `SessionDatabase.newQueryConnection()` opens with
  `SQLITE_OPEN_READONLY`, the flag is fixed at open time, and no SQL reaches
  it. In front of it sit two refusals that are not guarantees and are described
  as such. `PRAGMA query_only = ON` is connection state, and connection state
  is reachable from SQL — `EXPLAIN PRAGMA query_only = OFF` clears it, because
  SQLite applies flag pragmas in `sqlite3Pragma()` at *prepare* time and
  `EXPLAIN` does not suppress that. So `ReadOnlySql` treats `EXPLAIN` as
  **transparent**, classifying whatever it was put in front of (repeatedly, so
  `EXPLAIN EXPLAIN PRAGMA …` is covered), and `AdHocQueries` re-reads
  `query_only` before *every* execution rather than once at construction — a
  one-time assertion about a value a later statement can change is not a
  refusal. `ReadOnlySql` also refuses text that is not a single statement,
  which neither of the others would have done: `Statement.execute` runs every
  statement in a semicolon-joined string, so `SELECT 1; DROP TABLE actions` is
  refused before any of it is sent. The pragma allowlist earns its keep beyond
  tidiness — `sqlite3_soft_heap_limit64` is process-global, so
  `PRAGMA soft_heap_limit = 1` typed into a text box would degrade the writer
  connection ingesting a build, which neither the row cap nor the deadline
  touches. `AdHocQueries` refuses a connection that can write rather than
  trusting one, which is what makes the distinction between
  `newReadConnection()` (a convention) and `newQueryConnection()` (a fact)
  load-bearing.
  Paging is `SELECT * FROM (…) LIMIT ? OFFSET ?`, the one place in this
  codebase that uses OFFSET, because keyset paging needs a sort key and a
  unique tiebreaker that an arbitrary user query does not expose — its
  `ORDER BY` may be over an expression or absent. The row count is an exact
  `SELECT COUNT(*) FROM (…)` rather than a plan estimate: `PagedTableModel`
  reads its count on the EDT and never re-asks, and an estimate in a status
  line is a guess presented as a total (rule 11). A SQL NULL reaches the grid
  as `null` and is drawn as an italic, dimmed `NULL` — distinguishable from the
  four-letter string "NULL", from an empty string and from 0, which Swing's
  default renderer draws identically. A BLOB states its exact byte length
  instead of rendering as `[B@6d06d69c`. A result over the cap states the cap,
  the exact number of matching rows, and offers a button that raises the cap to
  the full match and runs again (rule 12).
  Cancel is `sqlite3_interrupt` and is called straight from the EDT because it
  cannot block; a 60 s deadline fires the same interrupt for a window nobody is
  watching, and both report themselves as *stopped* rather than as failed. JDBC
  `setQueryTimeout` is deliberately unused — in sqlite-jdbc 3.53.2.1 it only
  sets the busy timeout and does nothing to a statement that is running.
  The status line does **not** yet distinguish a live session: the row count is
  a `SELECT COUNT(*)` snapshot taken when the query was described, and against
  a capture still in progress that is a lower bound the panel reports as a
  plain number. `EventsView` learned to write "at least N events (still
  capturing)" for this reason and this card has not; the gap is stated in
  `QueryRowSource`'s javadoc rather than papered over.
  `EdtDisciplineTest` now counts `QueryReader` among the field types that mean
  a component can block, so the new card is held to the same rule as every
  other. `AdHocQueriesTest` proves the refusal at run time by attempting an
  INSERT, an UPDATE and a DROP on the query connection and asserting SQLite
  rejects all three with the rows unchanged; `QueryViewWiringTest` proves the
  same through the card, over a real imported session, and proves that Cancel
  returns in milliseconds and leaves the connection usable.
  `AdHocQueriesTest.theWriteGuaranteeOutlivesQueryOnly` is the uncomfortable
  one and is kept deliberately: it goes *round* the statement filter, clears
  `query_only` through `EXPLAIN`, and then asserts that a `CREATE TABLE` and an
  `INSERT` are still refused and the rows are unchanged — the guarantee
  outliving the refusal — before asserting that the per-execution guard notices,
  says so, and puts `query_only` back.

- **Cross-view navigation is one vocabulary, not a pile of callbacks**
  (2026-08-24). The navigation half of the plan's `SelectionService` (section
  7), as `ui/nav`: `EntityRef` (a sealed value — target label, action id, or
  event id) and `EntityActions` (context-menu builder, button strip, and
  inspector region over one command vocabulary, dispatching into a single
  handler). `MainWindow.navigate` is that handler and the only place a command
  becomes a card switch, replacing the per-view `onShowSourceEvent` /
  `onShowInGraph` / `onShowOnTimeline` `LongConsumer` wiring for the views
  that adopted it (Actions, Events); Targets/Tests/Errors keep the old setter
  until they adopt. `OPEN_IN_GRAPH` points at today's Graph card; the planned
  Graph/Tree split re-points that one switch arm and wires `OPEN_IN_TREE` —
  which, with `SHOW_EVENTS_FOR_LABEL` (no events-by-label read path exists
  yet), is in the vocabulary but deliberately **not** in the wired set, so
  nothing offers it: absent commands are honestly absent, never disabled
  stubs. The Timeline's planned inline inspector adopts by calling
  `buttonStripFor` — no facility change needed.
  The Events tab parses the target label **at render time** — an explicit
  decision over any schema or capture-path change. Where the payload is
  decoded (the inspector), the label comes structurally from the proto via
  the new `EventIdDisplay.label(BuildEventId)`; where only the stored
  sentence exists (table rows, JSON records), `EventIdDisplay.labelOfDisplay`
  inverts the display grammar next to the renderer that defines it, refuses
  anything that does not look like a label (absent markers, an
  `ActionCompleted` output path in the label slot, a label cut by the display
  cap), and is round-trip-tested against the structured accessor for every
  label-carrying id kind. A labelled row offers "Open target" and "Show
  actions for this target" in a right-click menu and in the inspector; an
  event with no parseable label offers nothing.
  `EntityReader.targetsByLabel` now exposes the `TargetQueries.byLabel` that
  already existed, and `TargetsView.revealLabel` gives the tree random access
  by label — look up the row off the EDT, expand its package (which triggers
  the lazy child load), select the target when the load lands, and say so in
  the status line when the session never declared the label. The Actions tab
  adopts fully: rows and the shared inspector carry (action, label, event)
  refs, the bespoke Dependencies / On-timeline buttons stay in place but
  dispatch through the facility, and "show actions for this target" lands as
  `filterToLabel` — substring semantics, because `ActionFilter.labelContains`
  is what the query layer offers, made visible as a chip naming the label
  that clears the filter when clicked, so the narrowed table can never pass
  for the whole build. `InspectorPanel`'s legacy source-event button yields
  to the strip whenever the strip offers the same jump, so no action appears
  twice.

- **The timeline places spans by identity, stacks them honestly, and knows
  what "live" means** (2026-08-24). Four connected fixes in one overhaul.
  *Placement:* every `SpanWindow` span now carries the lane key its grouping
  assigns it — the same value `TimelineController.lanes()` puts in
  `Lane.key` — and `paintSpans` joins span to lane by that key. The old
  `i % lanes` placement made a span's row a function of its position in the
  fetch, so every pan and zoom (each of which refetches the window) shuffled
  the whole plot vertically; `TimelineSpanPlacementTest` drives the real
  mouse listeners and requires every span to stay put. A span whose key is in
  no current lane (transient while a regroup's rebuild lands — the controller
  now rebuilds the model, not just the window, when the grouping changes)
  draws in an extra row below the lanes with its exact count on the status
  line. *Stacking:* overlapping same-lane spans stack top-to-bottom by start
  time (`SpanStacking`, pure and order-independent), bounded at
  `SpanStacking.MAX_SUB_ROWS` (6, in docs/limits.md); overflow draws into the
  last sub-row — never dropped — and the status line states the exact count.
  *Live:* while a capture is live and follow-live is on, the right edge is
  the wall clock (a 250 ms EDT timer re-fits a *following* viewport;
  `TimelineViewport.withWall` still refuses to move a navigated one), and a
  labelled band above the lanes shows **in-flight targets** — configured, not
  yet completed — as blue spans growing from their configuration's BEP
  receive time. Target-level by explicit decision: BEP has no action-start
  event (`ActionOutcome` documents there is no way to spell RUNNING), so
  TargetConfigured→TargetCompleted are the only live signals, and the band
  says "targets" and "BEP receive time" so nobody reads it as running
  actions. No schema change was needed: `targets.bep_event_id` /
  `configured_targets.bep_event_id` already point at the events, and
  `bep_events.receive_micros` already holds the times. A target with no
  receive timestamp is counted in the band label and drawn nowhere.
  `TimelineColours` gained green: completed success is `SUCCESS`, failure
  stays `FAILED` red, and `IN_FLIGHT` blue is reserved for the band — a
  finished action can no longer wear the colour of one still running.
  *Click:* a clicked segment opens an inline inspector on the Timeline card
  (previously the click selected a row in the Actions tab without switching
  to it — visibly nothing). The inspector shows what is already in hand
  immediately, the controller fetches the action's details off the EDT
  (`EntityReader.action`), absent facts are absent lines (an unknown
  duration is its worded reason, never "0.000 s"), and the actions strip is
  the shared `EntityActions` facility — same vocabulary, same
  `MainWindow.navigate` switch, with "show on timeline" omitted because the
  segment is already there. *Defaults:* group-by Mnemonic and sort-by Name
  are explicit selections (`TimelineDefaultsTest`); they used to be
  `values()[0]` accidents — Runner and first-activity — and Runner needs an
  execution log most sessions lack. Follow-live stays default-on.

- **The Query card grew into a suite: tabs, saved queries, saved views, a real
  editor** (2026-08-24). Four things the first cut shipped without, and one
  bug it shipped with.
  The **starter SQL was invalid two ways** — it named a `mnemonic` column
  (`actions` has interning FKs `mnemonic_id`/`label_id`, resolved through
  `mnemonics`/`labels`) and a `duration_micros` column that does not exist
  (durations are `end_micros - start_micros`, either end nullable). The first
  thing a user ran errored on the schema the card exists to teach. The
  replacement joins `mnemonics`, groups on `mnemonics.value`, and is honest
  about NULL: `timed_actions` counts the rows whose difference is non-NULL,
  which is exactly the set `SUM` covers, so untimed actions are counted beside
  the total rather than folded silently into it.
  `QueryViewWiringTest.theStarterQueryRunsAsShipped` runs the literal constant
  against a real imported session, so the starter can never regress to
  fiction again.
  **Tabs** (`QueryTab`, up to `QueryView.MAX_TABS` = 16, docs/limits.md): each
  tab owns its editor, results grid, row source, and its own `QueryReader`
  from `SessionSource.openQueryReader()` on its own single-thread executor —
  the per-view connection pattern the events/actions tables use, and the
  reason two tabs' queries run genuinely concurrently instead of queuing on
  one connection (`twoTabsRunConcurrently` proves it with a slow recursive CTE
  on one tab and a fast count on the other). Tabs rename and close; the last
  one refuses to close with a reason; session close mirrors the old
  `closeSession()` discipline per tab (interrupt, then shut down, then close,
  on a daemon thread).
  **`CREATE TEMP VIEW <name> AS <tabular>`** is now the one non-read the
  statement filter admits, as a genuinely new `ReadOnlySql.Shape.DEFINE` —
  it cannot be wrapped in `SELECT * FROM (…)`, so it could not ride on
  TABULAR. The head is parsed token by token (CREATE, TEMP/TEMPORARY, VIEW,
  one name — bare, `"quoted"`, `` `backquoted` `` or `[bracketed]` — then AS),
  and the body is re-checked by the same rules as a standalone statement.
  Every other CREATE stays banned by name: TABLE, non-temp VIEW, INDEX,
  TRIGGER, VIRTUAL TABLE, and the TEMP spellings of TABLE and TRIGGER, which
  can hold data or run statements where a view cannot. The temp schema is a
  different database from the session file, writable even though main is
  opened `SQLITE_OPEN_READONLY` — but `query_only` refuses connection-wide,
  so `AdHocQueries` lifts that one flag for exactly the DROP-and-CREATE pair
  and restores it in a `finally`; `aTempViewCannotBeWrittenThrough` re-proves
  the open-mode guarantee at the precise moment the flag is down (main-schema
  CREATE TABLE/VIEW/INDEX/TRIGGER/VIRTUAL TABLE all refused `readonly`), and
  a shadowing temp view is shown to be local, harmless, and described as
  itself in the schema tree (schema-qualified `table_info`;
  `temp.sqlite_master` is listed alongside main's).
  A prior review found the **quoted-pragma refusal worked only by side
  effect**: `PRAGMA "query_only" = table_info` is legal SQLite where the
  quotes name the pragma and the bare word is its *value*, and the old check
  — reading the name out of the skeleton, where quoted identifiers are
  blanked — judged that statement by its value. A value matching an
  allowlisted name would have cleared `query_only` through `EXPLAIN`. Names
  are now read from the original text and a quote or bracket where the name
  should be is refused by rule (`quotedPragmaNamesAreRefusedExplicitly`), in
  every spelling and through `EXPLAIN`.
  **Saved queries and saved views** (`QueryLibrary`): plain `.sql` files plus
  a small JSON `index.json` under `settings/queries/` and `settings/views/` —
  human-editable on purpose, index rebuilt from the files when missing,
  index entries that point outside their directory ignored. Saved views store
  name + SELECT body and are **replayed** onto every tab's connection at open
  and on every library change (`QueryReader.applyTempViews`, drop-what-I-made
  semantics so a rename does not leave its old name behind); a broken saved
  view is reported and skipped, never fatal. Two examples ship on first use —
  `actions_with_labels` and `mnemonic_totals`, the interning joins everyone
  writes first — and deleting them is respected, not reseeded.
  **The editor** is now RSyntaxTextArea with SQL highlighting and line
  numbers, AutoComplete fed from the connection's own schema (tables, views,
  temp views, columns — so completion tracks the selected tab), and a Format
  button behind vertical-blank's sql-formatter. All three are BSD-3-Clause/
  MIT, reviewed in `gradle/libs.versions.toml`'s header, user-approved, and
  lockfiles regenerated. rsyntaxtextarea is pinned to 3.6.1 — the version
  autocomplete 3.3.3 declares — rather than the fresh 4.x major.

- **The Graph card split into Graph and Tree, and the Graph card gained
  weights** (2026-08-24). `NavEntry.GRAPH` was the trees with the canvas
  hidden behind an embedded "Canvas" sub-tab; now `NavEntry.TREE` ("Tree",
  arrival phase 5, `TreeView` — the renamed `GraphView`) is the JTree
  deps/rdeps browsing, search and path-between-nodes, and `NavEntry.GRAPH`
  ("Graph", arrival phase 7, the new `GraphExplorerView`) hosts the canvas
  machinery (`GraphCanvasPanel` and friends, unchanged in place). Twelve nav
  entries; each card keeps its own graph-source selector. The
  `EntityActions` split-wiring debt is paid: `OPEN_IN_GRAPH` re-pointed at
  the new Graph card, `OPEN_IN_TREE` wired at the Tree card and added to
  `MainWindow`'s wired set, leaving `SHOW_EVENTS_FOR_LABEL` the only
  deliberately unwired command. The overview's derived-critical-path tile
  still names `NavEntry.GRAPH` — the chain is a canvas drawing, so the split
  moved its destination's content, not its link.
  **Weights** (`GraphWeight`, computed off-EDT by `GraphWeights` via
  `GraphLayoutService.weights`): immediate deps/rdeps (CSR degree, O(1)),
  transitive deps/rdeps (exact over the drawn subgraph under
  `SUBGRAPH_TRANSITIVE_WORK_BUDGET`, plus a budgeted whole-graph BFS for the
  selected node only, rendered "≥N (budget reached)" when
  `GLOBAL_TRANSITIVE_NODE_BUDGET` trips — a full transitive closure stays
  forbidden), output size (`GraphQueries.outputSizesByNodeIndex`, the
  `primary_output_id` → `artifacts.size_bytes` join; unsized outputs are
  unknown, never zero), and inputs (presented as the immediate dependency
  count and labelled as exactly that — no distinct raw-input count exists in
  the session). The weight drives node radius, edge thickness and the colour
  ramp only; positions stay with the LAYERED/RADIAL/LINEAR/GRID layouts (no
  force-directed layout, unchanged rule), the layout cache key is untouched,
  and re-selecting a weight restyles via `GraphCanvas.restyle` without
  re-fitting the camera or dropping the selection. Unknown weights draw grey
  at base size. Both budgets are in `docs/limits.md`; the split and the
  weight definitions are in `docs/graph-model.md`. New tests:
  `GraphWeightsTest` (exact subgraph counts on a diamond, budget trips,
  lower-bound wording), `GraphWeightEncodingTest` (radius/colour/thickness
  mapping, unknown-is-not-zero), `GraphExplorerViewTest` (the new card's
  selector and honest absences), weight cases in `GraphCanvasPanelTest`, a
  size-join case in `GraphQueriesTest`, and the renamed
  `TreeViewSourceTest`; `NavEntryTest` pins the twelve entries.

- **The Targets card navigates, and a target now resolves to a graph node
  exactly** (2026-08-24). `TargetsView` grows a header toolbar over the shared
  `EntityActions` facility for the selected target: Open in tree, Open in
  graph, Show actions for this target, Show events for this target, Show
  source event. A toolbar rather than the row menu the table cards use,
  because the Targets tree's selection is often a package and a fixed strip of
  buttons can say *why* a jump is unavailable where a menu can only leave the
  item out. Every button is either live or disabled with the reason in its
  tooltip — nothing selected ("Select a target in the tree first — a package
  row is not a target"), a row with no `bep_event_id`, a command the window
  does not wire, or no facility installed at all. Each button sits in a
  wrapper panel carrying the same tooltip, because a disabled Swing component
  receives no mouse events and would otherwise be a dead control whose
  explanation cannot be read. `EntityActions.isWired` is the new (and only
  new) facility method the toolbar needs: menus stay honest by omission,
  a permanent toolbar by explanation. The card's *inspector* still reaches
  the Events card through the older `onShowSourceEvent` setter — a target's
  `Inspection` carries no refs yet, so that half of the adoption is not done.
  **Show events ships disabled, and the gap is this:** there is still no
  events-by-label read path. `bep_event_ids.display` is stored and
  `EventIdDisplay.labelOfDisplay` inverts that grammar, so a `display LIKE`
  filter is *conceivable* — but it is substring-on-a-rendered-sentence, not a
  label match, and the Events card's paging is keyset-anchored over a
  contiguous id range (`EventRowIndex`), so a filtered table needs a filtered
  count and a filtered anchor index before it can show a row. Rather than
  ship approximate semantics behind an exact-sounding button, the button is
  present, off, and names the missing path. `SHOW_EVENTS_FOR_LABEL` therefore
  stays out of `MainWindow.wiredCommands()` — still the one deliberately
  unwired command.
  **Exact label lookup:** `GraphQueries.nodeForLabel(GraphKind, String)`
  matches `labels.value` by equality (the column is UNIQUE, so it is indexed
  as well as exact) and answers in the asked-for graph's own numbering —
  the sorted label universe for the configured-target graph, the lowest
  `node_index` the label owns for the action graph, so repeated jumps land in
  the same place. `TreeView.showLabel` and `GraphExplorerView.showLabel` are
  the entry points, trying the label graph first (a target *is* a node there)
  and the action graph second, selecting the matching source before showing
  the node so the index means what it meant when it was looked up, and saying
  so when neither graph carries the label. `EntityActions.Command.appliesTo`
  widens `OPEN_IN_TREE` / `OPEN_IN_GRAPH` to target labels — the widening its
  own comment anticipated — and `MainWindow.navigate`'s two graph arms take
  either identity. This closes the substring hole those jumps would otherwise
  have inherited from the Find fields: `//app:server` no longer risks landing
  on `//app:server_lib`. New tests: `TargetsViewWiringTest` (toolbar order,
  every disabled state and its reason, arming on selection, and each button
  dispatching its own command with the selected target's own ref),
  `nodeForLabel` cases in `GraphQueriesTest` (exact match, a label that is a
  proper prefix of another, absence, stability across a label's several
  actions, and the two graphs' different numbering for one label), a wiring
  case in `EntityActionsTest`, and a target-label case in
  `MainWindowNavWiringTest`.

- **The Actions tab's label-filter chip, once shown, could not be removed**
  (2026-08-24). `ActionsView.filterToLabel` and `clearLabelFilter` toggled
  `labelChip.setVisible(...)` without the `revalidate()`/`repaint()` pairing
  every other dynamic-visibility site in the module already carries
  (`InspectorPanel`, `EventInspectorPanel`, `TimelineView`, `QueryTab`).
  `JComponent.setVisible(true)` revalidates the chip *itself* as a side
  effect, but that is not the fix: the chip's own toolbar — laid out while
  the chip was invisible — was never told to redo its `FlowLayout`, so
  nothing made room for the chip and it inherited stale, usually zero,
  bounds. No pixels, no click target, no way to clear a filter arriving from
  another view's "show actions for this target". Fixed by holding the
  toolbar as a field and pairing both `setVisible` calls with
  `toolbar.revalidate()`/`toolbar.repaint()`. **Why the existing test missed
  it:** `labelFilterNarrowsVisiblyAndClears` never realized the component
  tree and cleared the filter through the package-private
  `clearLabelFilterForTest()` rather than a click — `isVisible()` was
  already true under the bug, so nothing it checked could tell a chip with
  real bounds from one sitting at (0,0,0,0). Strengthened rather than
  patched: the test now calls `addNotify()` + `setSize` + `validate()` to
  realize a genuinely displayable (if unshown — headless forbids any real
  `Window`, `new JFrame()` throws `HeadlessException` even without
  `setVisible(true)`) component tree, installs a recording `RepaintManager`
  to confirm the *toolbar itself* — not merely the chip, which revalidates
  on its own per `JComponent.setVisible(true)`'s own side effect — is asked
  to re-lay-out on both the show and the hide, asserts the chip's bounds are
  genuinely non-zero once that request is honored, and clears the filter
  with a real `labelChip.doClick()` rather than the bypass method (now
  removed). Confirmed to fail on each half of the reverted fix before
  landing it.

- **The Errors card reads a console row's stderr instead of calling it
  unknown** (2026-08-24). An `ErrorRow.Kind.OUTPUT` row is a progress event
  that wrote to the console, and for the most common failure a build has —
  a syntax error, thirteen events, zero structured diagnostics — its
  `progress.stderr` is the *only* copy of the compiler's own words
  (docs/bep-content.md finding X2, rule 48). The card listed those rows,
  showed their byte count, and rendered the em-dash "unknown" over text that
  was on disk the whole time. Now selecting one reads it.
  **The read.** `ErrorQueries.PROGRESS_WITH_STDERR` already selected
  `raw_segment`/`raw_offset`/`raw_length` and threw them away;
  `ProgressRef.rawLocation()` hands them over as the `RawLocation` they
  describe, and `ErrorRow` grew an `Optional<RawLocation>` — present only for
  the kind whose text is not in the row, absent for the three kinds whose
  message column *is* the message. `ErrorsView` now opens a `SessionReader`
  beside its `EntityReader`, on the same single thread (a JDBC connection is
  not thread-safe, and a `SessionReader` is bound to the thread that opened
  it), and a selection fetches exactly one payload on that thread —
  `EventInspectorModel`'s shape, generation counter included, because `JTable`
  fires selection events far more often than a user changes their mind and a
  superseded read is dropped rather than rendered late.
  **The decode.** `RawPayloadRenderer.console(RawPayload)` is the structural
  accessor, beside `Rendered.targetLabel` and for the same reason: the card
  needs the stderr string, not a sentence containing it, and reading it out of
  the rendered protobuf text would be parsing a display format. It unwraps a
  BES envelope, re-decodes a JSON record, and returns a `Console` whose
  `absence` is populated exactly when the two strings are not this record's
  own answer.
  **Four states, never conflated.** "Reading it back from the journal…", the
  text itself, "this event decoded and carried no console text", and the
  reason the bytes could not be reached are four different facts and each says
  which it is. A redacted session keeps its database and drops its `raw/`
  directory, so its console rows name frames that are gone: that is a stated
  absence in the inspector, never a modal dialog, because a session working as
  intended must not look broken. A journal that will not *open* is caught the
  same way and kept as a sentence — it costs the console rows their text, and
  refusing to open the card over it would hide the failures that read fine.
  The Message **column** stays size-only — bulk text lives in the journal, not
  in a table cell (ADR-004) — but it now
  reads `text in journal — select to view` rather than an em dash, because the
  message is not unknown, it is elsewhere. stderr and stdout render a line per
  field (the inspector draws a field's value in one label, so a multi-line
  diagnostic in one field would come out as one unreadable run), capped at 400
  lines with the withheld count stated.
  New tests: `ErrorInspectionTest` (each of the four states, the journal
  pointer, the disclosed line cap, and non-OUTPUT rows unchanged),
  `ErrorsViewTest` (the stderr on screen after a selection, one payload read,
  zero reads on the EDT, the redacted session's honest absence, a journal that
  will not open leaving the card intact, no read at all for a row that carries
  its own text, and both readers released on close),
  `ErrorQueriesTest` (the journal address survives to the caller, only stderr
  events are listed, the limit holds), and console cases in
  `RawPayloadRendererTest` (binary, BES-wrapped, JSON, a non-progress event,
  and bytes that will not decode).

- **The Events tab follows a live build's tail, and auto-pauses when a user
  scrolls away from it** (2026-08-24). `EventsView` gains a "Follow tail"
  checkbox, checked by default, next to the status line — the events analogue
  of `TimelineView`'s "Follow live" and `ConsoleView`'s "Follow output".
  `swapRows`, the read-before/reapply seam a live refresh already uses to
  carry the selection, scroll position, and column widths across each
  rebuild-and-swap of the table model, now reads the checkbox too: checked, it
  scrolls to the table's last row instead of restoring the captured position
  (`table.scrollRectToVisible(table.getCellRect(rowCount - 1, 0, true))`, the
  same call `ActionsView.selectAction` already makes, chosen over a raw
  `setViewPosition` because it accounts for the row's real height rather than
  assuming one); unchecked, the old restore behaviour is unchanged.
  **Auto-pause is the hybrid this task adds over either precedent:** any
  vertical-viewport movement the view did not itself make is the user's, and
  is read against the table's real content height (`rows × getRowHeight()`,
  a field computation `JTable` can answer with no layout pass) with a
  two-row tolerance. Scrolling away from the bottom by hand unchecks the box
  so the next tick cannot yank the user back to what they were reading;
  scrolling back down to the bottom re-checks it; checking it by hand jumps
  to the tail immediately. A single `adjustingViewportProgrammatically` flag,
  set around every scroll the view makes to itself — the tail-jump, the
  position restore, and the two `JTable.setModel` calls (`buildViews` and
  `closeSession`) that could otherwise move a *realized* window's viewport
  as a side effect of layout — keeps those from being misread as the user
  scrolling. New tests in `EventsViewLiveRefreshTest`: default-on with no
  session even open, a refresh scrolling to the tail while following (and
  not pausing itself), auto-pause on a scroll away from the bottom, resume on
  a scroll back to it, and `refreshPreservesSelectionAndScroll` now also
  asserts the paused state holds across the swap it already exercised. A
  default-checked assertion was added to `EventsViewWiringTest`'s real-session
  wiring test.

- **The Graph card's canvas grew up: distinct action labels, decluttered
  text, label-aware Fit, node dragging, direction arrows, and a real Find**
  (2026-08-24). Six polish features, all in `ui/graph` plus one storage read.
  **Distinct labels:** `GraphQueries.displayLabelsByNodeIndex()` composes
  "Mnemonic — output basename" per action-graph node (mnemonic through
  `declared_actions.mnemonic_id`, basename from the `primary_output_id`
  artifact's path), because naming every action by its owning target's label
  made all of a target's actions read as the same string. Absent pieces
  degrade honestly — mnemonic alone, then target label, then basename, then
  null so the canvas keeps saying "(name not recorded)" — and the grammar is
  one public static, `composeDisplayLabel`, which the Find dropdown and
  Browse tree reuse for the parenthesised distinct half of their rows: a
  dropdown row reads `//pkg:t1  (Javac — t1.o)`, a Browse leaf the same with
  the package stripped (the package is its group). The panel takes the array
  via `attachActionDisplayLabels`, separate from the target labels, which the
  complete export keeps for its `label` column; the label graph keeps target
  labels, a label being the node there. **Declutter:** within a zoom band the
  canvas never paints text over text — candidates are ordered by a
  deterministic priority (selected, hovered, higher weight, lower node index)
  and a label that would overlap an already-painted one is skipped and
  counted (`declutteredLabelCount()`); a selected label always paints because
  nothing outranks it. **Label-aware Fit:** `GraphTransform.fit` gains a
  screen-space reservation overload, and `fitToView` reserves the widest
  label visible at the resulting band — capped at
  `GraphCanvas.MAX_LABEL_FIT_FRACTION` of the window and clamped so the
  reservation can never drop the view into a coarser band where the labels it
  reserved for would stop painting; both caps surface through
  `hiddenDetail()`. **Dragging:** a press on a node arms a node drag (a press
  on empty canvas still pans — the same branch point that used to pan on any
  miss); offsets live in a canvas-only world-space overlay, never in the
  shared `GraphLayout.Result`, `GraphSpatialIndex` or cached `Rendered`. Hit
  testing filters the index (`GraphSpatialIndex.nearest` gains an
  `IntPredicate` overload) and tests dragged nodes at their displaced
  coordinates; marquee selection does the same; edges follow. Offsets survive
  `restyle()` (a weight change shares positions by design), reset on any
  `setModel` (every new query, focus, source, layout kind and refresh arrives
  there), and "Reset positions" — a toolbar button beside Fit and a context
  menu item beside "Focus here" — clears them explicitly. **Arrows:** edges
  are stored producer→consumer, and at the near band each drawn edge gets an
  arrowhead at its consumer end, pulled back to the node's rim; coarser bands
  and mid-drag frames draw none, so `GraphCanvasScaleTest`'s 200 ms budget
  holds unchanged. **Find:** the Graph card's Find field is now an
  as-you-type dropdown of up to `FIND_LIMIT` matches, queried on the card's
  worker (never the EDT), each entry landing on its exact `node_index`
  instead of the old silent first-substring-match; one row past the limit is
  fetched so "only the first 12" is a fact, not a guess. The search matches
  every part a search row shows — label, mnemonic and primary-output path in
  the action graphs, label and rule class in the label graph — so typing
  "Javac" over a graph drawn full of "Javac — …" cannot be answered with a
  false "nothing matches". The visible export's node CSV is headed
  `id,name,duration_micros` because it carries these drawn names; the
  complete export keeps `id,label,…` over real target labels, and
  `GraphExportTest` pins which file carries which. A Browse… toggle
  opens `GraphNodeBrowser` — the schema browser's filter-tree pattern rebuilt
  for graph nodes, package-grouped, at most `BROWSE_LIMIT` entries with the
  graph's total named at the root and the truncation stated in the summary.
  New named limits documented in `docs/limits.md` (`FIND_LIMIT` 12,
  `BROWSE_LIMIT` 500, `MAX_LABEL_FIT_FRACTION` 0.5) and the semantics in
  `docs/graph-model.md`. New tests: display-label composition and degradation
  (`GraphQueriesTest`), declutter determinism, selection outranking, medium
  band scope, fit-with-labels bounds and the stated cap, drag
  survive-restyle/reset-on-relayout/reset action, pan-on-empty-press, arrow
  gating and tip geometry (`GraphCanvasTest`), reservation fit maths
  (`GraphTransformTest`), filtered nearest (`GraphSpatialIndexTest`), display
  labels reaching the model and the target-label fallback
  (`GraphCanvasPanelTest`), and find dropdown exact landing, no-match
  honesty, truncation, browse listing/landing/filtering
  (`GraphExplorerViewTest`).

- **The Findings pane no longer overflows the window, and scrolling it is no
  longer extremely slow** (2026-08-24). The exact latent shape the Overview
  fix's changelog entry named and left untouched: `FindingsView`'s `summary`
  panel and each `section()` (invocation metrics, the per-mnemonic table) used
  a fixed `GridLayout(0, 2, …)`, sizing every row in a column to that column's
  single widest cell; and both the header/summary/catalog stack (`top`) and
  the finding-detail pane (`detail`) were plain `BoxLayout` `JPanel`s handed
  bare to a `JScrollPane`, so neither was `Scrollable` and neither told the
  scroll pane to track the viewport's width. Findings volume compounds this —
  `FindingRules` emits several findings per mnemonic group across three
  rules, each with a title, evidence and links built from this build's own
  (potentially long) strings — and none of the three scroll panes (`top`,
  the finding list, `detail`) had a wheel unit increment set, so the platform
  default of one pixel per notch made scrolling any of them feel frozen.

  Fixed the same way as the Overview tab: `top` and `detail` are now
  `ui.theme.ScrollableViewport`s, so their enclosing `JScrollPane`s track the
  viewport's width and never grow a horizontal scrollbar, however wide a
  single row's value or a finding's evidence/link button text wants to be —
  confirmed for both by embedding the real scroll pane at a narrow width and
  asserting no horizontal scrollbar appears, even with a finding fixture
  carrying a deliberately long evidence label and link description. `summary`
  and `section()` moved from `GridLayout(0, 2, …)` to the `GridBagLayout`
  two-column name/value shape `OverviewPanel.section` already uses, so one
  long row no longer sets every row in its column to the same width. Inside
  `detail`, a finding's title and each metric's `name: value` line — both of
  which can be full sentences built from this build's own numbers, not just
  short labels — now render through the existing `wrapped()` `JTextArea`
  helper (extended with a bold variant) instead of a plain, non-wrapping
  `JLabel`, so long text wraps instead of being silently clipped (rule 12).
  `getVerticalScrollBar().setUnitIncrement(16)` is now set on all three
  scroll panes. Because an evidence/link `JButton`'s text cannot wrap, a
  narrow window still ellipsis-clips it — so, mirroring the existing
  `PlainText.tooltip(...)` use on `FindingRenderer`'s list cells, both
  buttons now carry their full, untruncated text as a tooltip, keeping a
  clipped label reachable by hover instead of unreadable. New tests in
  `FindingsViewTest`: the summary/catalog grids and the detail pane each
  track the viewport's width instead of overflowing it, every scroll pane
  uses the fast wheel increment rather than the 1-pixel-per-notch default,
  and the evidence and link buttons carry their full text as a tooltip.

- **The timeline's lanes are sized by their content and the plot scrolls**
  (2026-08-24). Lane height used to be `canvas.getHeight() / lanes` with a
  three-pixel floor, and `boundsOfSpan` divided that again by the lane's
  stacking depth (up to `SpanStacking.MAX_SUB_ROWS`, 6). On any real session
  that produced one- and two-pixel sub-rows — marks too thin to see and too
  thin to click, and thinner the more concurrency the build actually had,
  which is exactly backwards. The in-flight band above them already drew
  fixed-height rows, so one plot used two rules. Now there is one:
  `TimelineView.SUB_ROW_HEIGHT` (18, in docs/limits.md, checked by
  `LimitsDocTest`) is the height of every lane sub-row *and* every band row,
  a lane is `SpanStacking.depthOf(key)` of those tall — **depth-scaled**, so
  only lanes with real overlap grow and a quiet lane pays nothing for a busy
  neighbour's concurrency — and the row layout (`relayoutLanes`, rebuilt
  whenever the lanes or the stacking change) is a function of the data alone,
  never of the window. `MAX_SUB_ROWS` is unchanged at 6 and the overflow
  count is still stated exactly; what changed is that the budget now buys
  height rather than rationing it.
  The total height goes to a `JScrollPane`: the canvas is the view and
  reports it as its preferred size, `laneLabels` is the pane's
  `rowHeaderView` so labels and rows scroll in lockstep by construction
  rather than by arithmetic, and the time axis stays outside the pane,
  pinned — behind a strut of the label column's width, which also fixes the
  axis having been offset by that width all along. Vertically the policy is
  as-needed with a 16-pixel unit; horizontally there is **no** scrolling
  ever: the canvas is `Scrollable` with `getScrollableTracksViewportWidth()`
  true and the policy `HORIZONTAL_SCROLLBAR_NEVER`, because horizontal
  position belongs to the pan/zoom transform and two mechanisms for one axis
  would fight. When the lanes do not fill the window the canvas tracks the
  viewport's height instead, so the aggregate density plot keeps the full
  height it drew in before. The wheel over the plot still means zoom and only
  zoom — the event is consumed in the canvas handler *and* the pane's own
  wheel scrolling is off, because a gesture that zoomed and scrolled at once
  would be unusable. Scroll state is the viewport's, not the
  `TimelineViewport` record's (which stays immutable and time-only): the
  position is read before and reapplied after every model swap — the
  `EventsView` live-refresh pattern — clamped to what the new plot is
  actually tall enough to show, so a live rebuild neither returns a reading
  user to the top nor parks them past the end; and the reveal paths
  (`MainWindow.revealOnTimeline`, the inline inspector's own click) scroll
  the selected span's lane into view, vertically only. New tests in
  `TimelineVerticalSpaceTest`: a depth-1 lane against a depth-6 one, every
  one of the six sub-rows separately hittable, preferred height reported as
  band-plus-lanes with the row header agreeing, position preserved across a
  live rebuild and clamped when a regroup shortens the plot, reveal
  scrolling the lane fully into view, and wheel-zooms-without-scrolling. The
  existing coordinate-driven timeline tests
  (`TimelineSpanPlacementTest`, `TimelineViewTest`, `TimelineLiveBandTest`,
  `TimelineInspectorTest`) pass unchanged: their clicks were already inside
  the first sub-row, which is now taller rather than shorter.

- **Table headers grew up: 3-state sort where honest, a column menu
  everywhere, and column state that survives restarts** (2026-08-24). New
  shared machinery in `ui/table`: `TableHeaderInteractions` (a
  `MouseAdapter`-on-`getTableHeader()` installer mirroring
  `EntityActions.installRowMenu`'s shape — the header is a distinct component
  from the body, so the two menus never collide) drives a left-click cycle of
  ascending → descending → default order against a small per-view `Adapter`
  (is this column sortable; apply this sort or the default; why not, for the
  headers that cannot), plus a right-click menu with per-column visibility
  checkboxes (the last visible column cannot be hidden) and an explicit Sort
  submenu — or, on views whose order is fixed, a disabled item naming why.
  `ColumnState`/`ColumnStateStore` generalize `EventsView`'s private
  read-before-reapply width preservation into one value — widths, visibility,
  order, sort — reapplied after every `setModel` swap and persisted as
  per-view JSON under `settings/columns/<view>.json` (the `QueryLibrary`
  pattern: load on attach, save debounced, both on a shared I/O thread, a
  corrupt or absent file degrading silently to defaults). Hidden ≠ dropped:
  a hidden column leaves the `TableColumnModel` only, keeps its remembered
  width and position, and the table model never changes shape. **Sort scope
  is deliberately pragmatic.** Actions: headers drive the *existing* backend
  `ActionSort` orderings (Target→LABEL, Mnemonic→MNEMONIC, Outcome→OUTCOME,
  Duration→DURATION), and the toolbar combo and headers are one synced state
  — a header click updates the combo and reloads, a combo change moves the
  indicator, the third click is ARRIVAL ascending, and toolbar-only orderings
  (start time) persist by enum name with no indicator; Exit/Runner/Cached/
  Output headers explain that no index orders actions by them at
  five-million-action scale. Never a client `RowSorter` on `PagedTableModel`.
  Errors: a `TableRowSorter` over the in-memory model — legitimate there
  because every loaded row is in memory — behind the same cycle, with every
  column `setSortable(false)` so the L&F's own two-state toggle stays out,
  the third state being the deliberate kind-priority load order, and
  selection converting view→model so a sorted click inspects the row on
  screen. Events/Tests/Query stay unsorted by design and their header
  tooltips say exactly why (chronological identity per `EventRowIndex`;
  `TestRowSource`'s documented worst-first order; result order belonging to
  the user's SQL — add ORDER BY). Wiring: each view gains
  `attachColumnState(Path)` and `MainWindow` hands all five the settings
  directory beside `queryView.attachLibrary` (six lines, the unavoidable
  wiring); `QueryView` forwards to every tab, future tabs included, all
  sharing one `query.json` matched by column name. No new limit constants —
  the save debounce is a cadence like the live-refresh interval, not a bound
  on data — so `docs/limits.md` is unchanged. New tests: the cycle, adapter
  honesty, hidden-column width memory across swaps, order restore,
  last-column protection, menu content, tooltip text, persistence round-trip/
  corrupt-file/no-op-save (`TableHeaderInteractionsTest`,
  `ColumnStateStoreTest`), header↔combo sync in both directions with the
  reloads they trigger and unsortable-header honesty on a real imported
  session (`ActionsViewHeaderSortTest`), sorter attach/cycle/detach and
  sorted-selection correctness (`ErrorsViewSortTest`), hidden columns
  surviving a live refresh and the chronological tooltip
  (`EventsViewColumnStateTest`), and the fixed-order explanations on Tests
  and Query (`TestsViewColumnStateTest`, `QueryTabColumnStateTest`);
  `EventsViewLiveRefreshTest`'s width-preservation test now passes through
  the shared store unchanged.

- **One inspector header, and the actions stopped falling off the edge**
  (2026-08-24). Three inspectors hand-rolled the same header and shared the
  same failure: a non-wrapping `EntityActions.buttonStripFor` strip whose
  preferred width grew with the number of offers, sitting in a
  `BorderLayout.EAST` slot that `BorderLayout` hands its full preferred width
  whatever the container's own width is. Dragged narrow, the strip walked left
  off its own panel — "the toolbar is entirely hidden unless the pane is
  pulled all the way to the side". New `ui/inspect/InspectorHeader`: a bold
  title line, a disabled-colour subtitle line, and **one** `…` overflow button
  that opens `EntityActions.popupFor` for the inspection's refs. A button of
  fixed width cannot outgrow its slot, and a menu is a window, so it is
  bounded by the screen rather than by the pane. What gives way instead is the
  *text*: the two lines sit in a `GridLayout` inside `BorderLayout`'s CENTER —
  both of which hand a child exactly the width available, the condition a
  `JLabel` needs to ellipsize, where `BoxLayout` would floor each label at its
  own preferred width and let it overhang — and each carries the whole string
  in a `PlainText.tooltip`. `getMinimumSize` is overridden to the edge's width
  plus room for an ellipsis rather than the title's natural width, so an
  ancestor honouring it is not an ancestor that refuses to narrow.
  **Honest absence, unchanged in kind:** the button exists only when
  `offersFor` returns something — no facility, no refs, or no wired command of
  the refs' kind means no button, never an ellipsis over an empty rectangle.
  `addTrailing` lets a host put its own control on the same protected edge.
  Adopted by all three: `InspectorPanel` (Actions/Targets/Tests/Errors) drops
  its EAST strip and its private title/subtitle labels, and the legacy
  "Show source event" button becomes a trailing control that still yields when
  the menu offers the same jump — the old `stripShowsSource` check is now
  `header.offers(SHOW_SOURCE_EVENT)`; `EventInspectorPanel` drops its
  `BorderLayout.SOUTH` label-actions region, its headline becomes the title
  and the inspected event's target label becomes both the subtitle and the
  refs the menu acts on (no label → neither, as before); `TimelineView`'s
  inline inspector drops `inspectorTitle`/`inspectorActions`, its Close rides
  the header's edge via `addTrailing`, and `SpanDetails`'s first line — which
  the controller already words as the segment's one-line identity, "Action 7
  (Javac) — SUCCESS" — becomes the subtitle with the rest as the body, so
  `SpanDetails` needed no new field. The `installEntityActions` wiring is
  untouched: `MainWindow` still installs the facility per view and each view
  forwards it, with its own omissions (`REVEAL_ACTION` in Actions,
  `SHOW_ON_TIMELINE` in the Timeline). New `InspectorHeaderTest` measures the
  layout promise rather than describing it — laid out at 160 px with a 78-
  character label, the button keeps its full preferred width and stays inside
  the header's bounds while the title shrinks below its own, the minimum width
  stays under 200 px, the menu equals `popupFor`'s list including omissions,
  and the button is absent in each of the three ways it can have nothing
  behind it. `InspectorPanelTest`, `EventsViewEntityActionsTest` and
  `TimelineInspectorTest` now assert against the menu instead of a strip.

- **The timeline's lane labels stopped being a dead zone for the wheel, and
  its vertical scrollbar stopped being invisible** (2026-08-24).
  `TimelineView`'s `plotScroll` carries `setWheelScrollingEnabled(false)`
  pane-wide so the canvas's own `MouseWheelListener` can own "wheel means
  zoom" without the scroll pane's default handling fighting it — correct for
  the canvas, but `LaneLabels` (the row header) had no wheel listener of its
  own, so wheeling over the lane names did nothing at all. `LaneLabels` now
  gets a constructor-installed `MouseWheelListener` that scrolls the shared
  viewport by `getPreciseWheelRotation()` times `VERTICAL_SCROLL_UNIT` — the
  same unit its `Scrollable#getScrollableUnitIncrement` already reports —
  through the existing clamping `scrollTo` helper, so a trackpad's fractional
  notches feel the same here as they do over the plot, and the canvas's own
  wheel path is untouched: it still zooms, and only zooms. Separately,
  `VERTICAL_SCROLLBAR_AS_NEEDED` plus FlatLaf's thin, low-contrast default
  thumb meant the bar appeared and disappeared with the plot's height and was
  easy to miss even shown, so a scrollable plot too often looked exactly like
  a non-scrollable one. The policy is now `VERTICAL_SCROLLBAR_ALWAYS`, and a
  new `TimelineView.styleVerticalScrollbar` applies FlatLaf's per-component
  `"FlatLaf.style"` client property to `plotScroll`'s vertical scrollbar only
  — a wider, higher-contrast thumb via the `width`/`thumbArc`/`thumb`/
  `hoverThumbColor` style keys, computed from `FlatLaf.isLafDark()` so it
  reads against either theme. Scoped to this one scroll bar: `ui/theme/
  Themes.java`'s global defaults are unchanged, and any look-and-feel other
  than FlatLaf simply ignores the client property. No new limit constant —
  `VERTICAL_SCROLL_UNIT` is reused rather than duplicated, and the scrollbar's
  width/arc are styling, not a documented limit, so `docs/limits.md` is
  unchanged. `TimelineVerticalSpaceTest`'s scrollbar-policy assertion now
  expects `VERTICAL_SCROLLBAR_ALWAYS`; its wheel-consumption assertions for
  the canvas (`isWheelScrollingEnabled()` still false, the canvas gesture
  still zooms without scrolling) are untouched. New
  `wheelOverLaneLabelsScrollsTheSharedViewport` dispatches a precise wheel
  event straight at `LaneLabels`' listener the same way the existing canvas
  test does, and asserts both halves: the shared viewport moved by the
  expected pixel delta, and the plot's zoom transform did not change.

- **The build system is Bazel 9.2.0** (2026-08-24, ADR-009 accepted; Gradle
  removed). One `bazel test //...` replaces `./gradlew build`: fifteen
  modules as `BUILD.bazel` packages over `tools/bbv.bzl` convention macros
  (the successor of `build-logic/`'s three plugins — native-access grant,
  2 GiB test heaps, headless AWT, UTF-8 + `-parameters`, each written once),
  one `MODULE.bazel` dependency universe locked in `maven_install.json`
  (every catalog artifact transplanted, plus `junit-platform-reporting` for
  the runner's XML and the two JMH jars the Gradle plugin used to supply),
  and protobuf/gRPC codegen by the same sha256-pinned protoc 4.36.0 /
  protoc-gen-grpc-java 1.83.1 binaries Gradle resolved — generated sources
  and the descriptor set verified byte-identical before cutover. Tests run
  one `java_test` per class through contrib_rules_jvm's JUnit Platform
  runner; the seven real-Bazel classes (CliRunTest's three tagged methods
  split into `RealBazelCliRunTest`) are dedicated un-sandboxed targets with
  inherited `PATH`/`HOME`/`BBV_TEST_BAZEL`/`USE_BAZEL_VERSION`, and
  `BazelVersionMatrixTest` sits behind two fences (`manual` tag + the rc's
  `-bazel-sweep` filter). The outer server is capped in `.bazelrc`
  (`-Xmx4g`, 300 s idle) for the same reason the fixture caps its children.
  jpackage/notarize became `bazel run //app:jpackage` / `//app:notarize`
  over the deploy jar, env-gated exactly as before; CI runs
  `bazelisk test //... --config=ci` on both OSes with the real-bazel
  exclusion visible in the config, uploading `bazel-testlogs` on failure.
  Layout-coupled tests were reworked, not weakened: `EdtDisciplineTest`
  scans the module's own code source (floor raised to the measured 40
  components), `LimitsDocTest` and `Phase9ExitCriteriaTest` resolve their
  documents through runfiles. One Bazel-specific workaround stands:
  the execroot symlink forest refuses top-level directories named
  `bazel-*`, so the `bazel-runner` module's compile inputs are byte-exact
  in-process copies relocated under `bazel-out` (`tools/relocate.bzl`) —
  name, label and layout unchanged. Post-merge fix (2026-08-25): a root
  `.bazelignore` shields `//...` traversal from the git worktrees under
  `.claude/`, whose BUILD files and bazel-* symlinks otherwise load as
  packages of this workspace and break the build at loading.

- **Launching a build now starts in Console, in a form that explains itself**
  (2026-08-25). `NavEntry.BUILD` and its `build` card id remain stable, but the
  visible title is **Console** and it is first in the sidebar. The launcher no
  longer consumes `MainWindow`'s frame-wide `BorderLayout.NORTH`; the Console
  card reads top-to-bottom as `LauncherPanel`, live `CapturePanel`, then the
  growing `ConsoleView`. The compact launcher has explicit accessible labels
  for Workspace, Bazel executable, Capture detail and Bazel command (without
  bazel), plus named **Choose workspace…** and **Choose Bazel…** buttons. Its
  combo offers exactly Live Essentials, Performance Diagnostics (recommended
  and selected by default), and Full Graph Diagnostics; an always-visible
  explanation follows the selection and Full Graph renders a bold warning
  naming disk, CPU and indexing cost. `CapturePreset.CUSTOM` stays compatible
  with stored/model code but is deliberately absent until there is an
  individual-source editor. The separate `InstrumentationPlanDialog` and every
  ADR-007 preflight choice are unchanged.

  `LauncherStateStore` keeps the four values and `LauncherHistory` under the
  existing `settings/` directory. A dedicated `bbv-launcher-settings` thread
  performs every load/save, its store refuses EDT access, and missing or
  corrupt state falls back to defaults without blocking launch. History is
  exactly 50 unique commands, newest first; a duplicate is promoted, Up/Down
  recalls while leaving the field editable, and the bound is printed below
  the field. Ctrl+Space completion reuses the existing autocomplete library
  over a fixed, test-pinned set of common Bazel subcommands. Focused headless
  coverage is in `LauncherPanelTest`, `LauncherHistoryTest`,
  `LauncherStateStoreTest`, and the updated `NavEntryTest`; no dependency or
  build-file change was needed.

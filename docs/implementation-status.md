# Implementation status

Last updated: 2026-08-22. This file states what exists in the tree, not what
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

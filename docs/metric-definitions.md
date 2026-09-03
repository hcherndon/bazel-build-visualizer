# Metric definitions

Every metric surfaced in the UI must have an entry here before it ships.
A number without a definition is a bug. Unavailable inputs make a metric
*unavailable or partial* — never silently zero (project rule). Entries
follow this template:

> **Name** — Definition · Units · Source · Formula · Completeness · Caveats

The full catalog grows with Phases 4-8. Three worked examples fix the format,
and the Phase 3 catalog below covers every number the Overview, Actions, Tests
and Errors views put on screen.

---

**Logical wall duration**

- *Definition:* elapsed wall-clock time of one action from observed start to
  observed completion, as a single span.
- *Units:* microseconds (displayed adaptively).
- *Source:* BEP action events; refined by execution log spawn timings when
  that enrichment ran.
- *Formula:* `end_micros - start_micros` per action.
- *Completeness:* only actions with both timestamps observed; the metric
  reports the covered fraction (e.g. "timing for 92% of executed actions").
- *Caveats:* includes queuing/scheduling inside the span for some runner
  types; cache hits have near-zero durations that must not be averaged
  together with executed actions unless explicitly labeled.

**Parallelism factor**

- *Definition:* average number of actions executing concurrently over an
  interval.
- *Units:* dimensionless (actions).
- *Source:* temporal index over observed execution spans.
- *Formula:* `sum(overlap of each action span with interval) / interval
  length`.
- *Completeness:* undefined over intervals where timing coverage is partial;
  reported only for the covered subset, with coverage stated.
- *Caveats:* this is *observed tool-side* concurrency, not `--jobs`; remote
  execution can legitimately exceed local core count.

**Known input bytes**

- *Definition:* total size of an action's input artifacts whose sizes were
  observed.
- *Units:* bytes.
- *Source:* BEP file metadata and execution log entries.
- *Formula:* sum of observed input file sizes; the count of inputs with
  *unknown* size is carried alongside.
- *Completeness:* the "known" prefix is load-bearing — the metric always
  displays with its unknown-count (e.g. "1.2 GiB known, 14 inputs
  unknown"); it is never presented as "input bytes".
- *Caveats:* tree artifacts and unresolved symlinks may report expanded or
  unexpanded sizes depending on Bazel version capability; comparison across
  sessions requires matching completeness.

---

## Starlark CPU profile

**Sampled Starlark CPU**

- *Definition:* sum of the selected `CPU` sample values across all pprof sample
  records and Starlark threads.
- *Units:* microseconds (displayed adaptively).
- *Source:* managed `--starlark_cpu_profile` gzip pprof.
- *Completeness:* available only after a validated successful import. A valid
  zero-sample profile is measured zero; no or failed profile is unavailable.
- *Caveats:* statistical CPU, not elapsed time. It excludes blocked time and
  runnable time not scheduled on a CPU; the profiler reports no dropped count.

**Starlark self CPU**

- *Definition:* selected sample values whose leaf frame resolves to a function
  or source file.
- *Units:* microseconds and percentage of Sampled Starlark CPU.
- *Formula:* sum each sample value once for its leaf frame.
- *Completeness:* function and file coverage are reported separately as exact
  attributed and unattributed CPU/sample-record partitions. An empty function
  name or filename is unattributed, not a zero-cost symbol.
- *Caveats:* a pprof record can aggregate multiple sampling ticks, so its row
  count is not CPU time.

**Starlark cumulative CPU**

- *Definition:* selected sample values for stacks containing a function or
  source file.
- *Units:* microseconds and percentage of Sampled Starlark CPU.
- *Formula:* add the sample value once to every distinct function and non-empty
  source file represented by physical or inline frames in the sample.
  Recursive occurrences remain separate in the physical call-context tree but
  are de-duplicated in flat function/file totals.
- *Caveats:* cumulative values overlap by design and must not be summed across
  functions. The physical call tree uses the first function for each location;
  its separate context coverage becomes partial for inline, missing, or unnamed
  symbols. Source lines are navigation hints, not line-level attribution.

**Average sampled Starlark CPU cores**

- *Definition:* sampled Starlark CPU divided by the pprof duration.
- *Units:* cores (dimensionless).
- *Completeness:* unavailable if either value is absent or duration is zero.
- *Caveats:* can exceed one because all Starlark threads contribute.

---

## Phase 3 catalog

Every number in the Overview, Actions, Tests and Errors views. Where two
entries look like the same quantity, they are not, and the pair is the point.

### Counted by this session

**Configured targets**

- *Definition:* rows in `configured_targets` — one per (target, configuration)
  that Bazel reported completing or aborting.
- *Units:* targets.
- *Source:* BEP `TargetComplete` and `aborted` events.
- *Formula:* `COUNT(*) FROM configured_targets`.
- *Completeness:* complete for the events received. A label built for both the
  target and the exec platform counts twice, deliberately: they have different
  actions and different outputs.
- *Caveats:* not the number of targets in the build. A target configured and
  never completed has no row here; see the next entry.

**Targets configured but never completed**

- *Definition:* labels analysis reported and execution never finished.
- *Units:* targets.
- *Source:* BEP `TargetConfigured` with no matching `TargetComplete`.
- *Formula:* `COUNT(*) FROM targets t WHERE NOT EXISTS (SELECT 1 FROM
  configured_targets ct WHERE ct.target_id = t.id)`.
- *Completeness:* complete for the events received.
- *Caveats:* this is the entire content of a build interrupted during analysis
  — one measured interrupt produced six configured and zero completed. A view
  that counted only completions would report such a build as empty.

**Targets named by an abort**

- *Definition:* `aborted` events, whatever id kind they rode.
- *Units:* events, not distinct targets.
- *Source:* BEP `aborted` on `targetConfigured`, `targetCompleted`,
  `unconfiguredLabel` and `configuredLabel` ids.
- *Formula:* `COUNT(*) FROM aborted_events`.
- *Completeness:* complete only if ingest ran past `buildFinished`, since these
  arrive after it. `saw_last_message` says whether it did.
- *Caveats:* scales with target count, not failure count — 12,000 measured from
  a single interrupt during analysis. Under `--nokeep_going` most of these are
  statements about a sibling's failure rather than about the target named.

**Actions observed**

- *Definition:* rows in `actions` — one per distinct primary output.
- *Units:* actions.
- *Source:* BEP `ActionExecuted` events.
- *Formula:* `COUNT(*) FROM actions`.
- *Completeness:* **flag-dependent.** Bazel publishes an event for a successful
  action only under `--build_event_publish_all_actions`. Without it this counts
  failures and little else, and the view says so.
- *Caveats:* an absent row means "not executed this invocation" — a cache hit —
  never "failed". Rows with mnemonic `TestRunner` are test executions and also
  appear in the tests view; counting both double-counts them.

**Artifacts**

- *Definition:* distinct exec-root-relative paths seen in any file set or
  directory output.
- *Units:* artifacts.
- *Source:* BEP `NamedSetOfFiles` and `TargetComplete.directoryOutput`.
- *Formula:* `COUNT(*) FROM artifacts`.
- *Completeness:* complete for the file sets received; a set referenced but
  never defined leaves its files uncounted, and that condition is reported
  separately.
- *Caveats:* a tree artifact is one row here and its expanded children are more;
  summing a directory's size with its children's double-counts, which is why
  tree rows carry no size.

### Reported by Bazel

These come from `BuildMetrics` and are shown under Bazel's name, never merged
with the counts above.

**Actions created / actions executed / action cache hits**

- *Definition:* Bazel's own counters for the invocation.
- *Units:* actions.
- *Source:* BEP `BuildMetrics.ActionSummary`.
- *Formula:* read verbatim.
- *Completeness:* present whenever `buildMetrics` arrived, which was every
  measured run including one interrupted by SIGINT.
- *Caveats:* **never presented as a ratio.** `actionsExecuted` excludes cache
  hits, `actionsCreated` was measured *smaller* than `actionsExecuted`, and the
  top-level `actionsCreated` does not equal the sum of the per-mnemonic ones.
  Any hit "rate" built from these is a number describing nothing.

**Work by action type**

- *Definition:* actions created and executed, per mnemonic.
- *Units:* actions.
- *Source:* BEP `BuildMetrics.ActionSummary.ActionData`.
- *Formula:* read verbatim.
- *Completeness:* **complete only on Bazel 8.4.1 and later.** On 6.5.0 and 7.6.1
  a mnemonic whose actions were all cache hits is absent from the breakdown
  entirely, so the chart under-reports without any indication in the data.
- *Caveats:* executed excludes cache hits, so the per-mnemonic totals do not sum
  to the actions the build performed.

**Elapsed time**

- *Definition:* what the user watched: `buildFinished` minus `buildStarted`.
- *Units:* microseconds (displayed adaptively).
- *Source:* BEP `BuildStarted.startTime` and `BuildFinished.finishTime`.
- *Formula:* `finished_micros - started_micros`.
- *Completeness:* unavailable when either event is missing — an interrupted
  capture has no finish.
- *Caveats:* differs from Bazel's own `wallTimeInMs` by up to a second in
  measurement. Both are shown; neither is averaged with the other, and
  `cpuTimeInMs` can legitimately exceed either.

**Analysis phase / execution phase / critical path**

- *Definition:* Bazel's phase timings.
- *Units:* milliseconds, except critical path which arrives as a duration.
- *Source:* BEP `BuildMetrics.TimingMetrics`.
- *Formula:* read verbatim.
- *Completeness:* `executionPhaseTimeInMs` is absent on Bazel 6.5.0;
  `criticalPathTime` exists only on 9.2.0. Both display as unavailable there.
- *Caveats:* a zero on the wire is indistinguishable from an absent field for
  these, so a genuinely instantaneous phase reads as unavailable. That is the
  conservative direction: claiming a phase took no time is the stronger
  falsehood.

### Per action

**Action duration**

- *Definition:* elapsed time of one action, from Bazel's reported start to its
  reported end.
- *Units:* microseconds (displayed adaptively).
- *Source:* BEP `ActionExecuted.startTime` / `endTime`.
- *Formula:* `end_micros - start_micros`, and only when the pair is trustworthy.
- *Completeness:* **unavailable on most builds.** Bazel 6.5.0 and 7.6.1 publish
  no action timestamps at all; 8.4.1 publishes `endTime == startTime` for every
  action, a five-second sleep included; on 9.2.0 roughly a third of action
  events carry none. The reason is stored per row and shown in the inspector.
- *Caveats:* aggregates exclude unknowns rather than counting them as zero, and
  a duration sort places unknowns at one end rather than treating them as
  instantaneous. Refined by execution-log spawn timings in Phase 4, which is
  where action timing becomes generally available.

**Action exit code**

- *Definition:* the code the process actually returned.
- *Units:* dimensionless.
- *Source:* BEP `failureDetail.spawn.spawnExitCode`.
- *Formula:* read verbatim; falls back to `ActionExecuted.exitCode` only when
  absent.
- *Completeness:* present for spawn failures that reported one.
- *Caveats:* **not `ActionExecuted.exitCode`.** That field was measured as 1 for
  every failure on all four versions, regardless of what the command returned.
  Both are stored; the view labels Bazel's as a status code.

### Per test

**Test verdict**

- *Definition:* the test target's overall result.
- *Units:* enumerated status.
- *Source:* BEP `TestSummary.overallStatus`, and nothing else.
- *Formula:* read verbatim.
- *Completeness:* absent when no summary arrived — a test that failed to build
  produces no test rows at all, and that state is shown as such.
- *Caveats:* **never derived from `targetCompleted.success`**, which was measured
  `true` for a test that failed. `FLAKY` and `TIMEOUT`-as-overall exist only at
  this level; an attempt carries a narrower vocabulary.

**Attempts**

- *Definition:* how many attempt records the test produced, and how many of
  them did not pass.
- *Units:* attempts.
- *Source:* BEP `TestResult` events, counted.
- *Formula:* `COUNT(*) FROM test_attempts WHERE test_id = ?`, and the same with
  `status <> 'PASSED'`.
- *Completeness:* complete for the events received.
- *Caveats:* **not Bazel's `attemptCount`**, which is the maximum attempts any
  (run, shard) needed and equals the run count for a healthy multi-run test.
  Labelling that field "retries" reports retries that did not happen.

**Test elapsed time**

- *Definition:* last stop minus first start across every attempt.
- *Units:* microseconds.
- *Source:* BEP `TestResult` attempt timestamps.
- *Formula:* `last_stop_micros - first_start_micros`.
- *Completeness:* unavailable when no attempt reported both ends.
- *Caveats:* a cached attempt replays timestamps from before the build started —
  measured 2.1 s earlier — so a sum of durations over cached tests reports
  seconds of testing for a build that took milliseconds. Shown beside Bazel's
  own `totalRunDuration`, which excludes failed retries and understated real
  wall time by 13x on a measured six-attempt test.

### Errors

**Failed actions / failed targets / targets not built**

- *Definition:* three separate counts, never summed into one "failures" figure.
- *Units:* rows.
- *Source:* `actions.outcome`, `configured_targets.outcome`, `aborted_events`.
- *Formula:* three `COUNT(*)` queries.
- *Completeness:* the third depends on ingest running past `buildFinished`.
- *Caveats:* they do not scale together. The first two are bounded by what broke;
  the third by the size of the build. Presenting a total would make one broken
  target in a large workspace look like a catastrophe.

## Phase 4 catalog

### Spawn total time
- *Definition:* `SpawnMetrics.total_time`, the wall time Bazel measured for one
  subprocess, from the execution log.
- *Source:* execution log. **Not** the action's BEP duration, which measures a
  different span and is kept separately under its own name (ADR-009).
- *Unavailable when:* the record carries no metrics. On Bazel 6.5.0 without
  `--experimental_execution_log_spawn_metrics` the duration is in the legacy
  `walltime` field instead, and is recovered from there.

### Spawn start
- *Definition:* `SpawnMetrics.start_time`, the instant a subprocess began.
- *Unavailable when:* the Bazel is 6.5.0, on any flag setting. Such an attempt
  has a length and no position and must not be drawn on a timeline;
  `start_unknown_reason` carries the sentence.

### Timing breakdown
- *Definition:* the separately-measured components of a spawn's elapsed time —
  parse, queue, setup, network, execution, fetch, process outputs, upload,
  retries.
- *Note:* these do not sum to the total, because the total is measured
  independently. The difference is shown as "unaccounted" rather than folded
  into execution only when every component is available. An action-level sum
  is available only when every attempt reported that component; summing a
  known subset would make partial work look complete.
- *Unavailable when:* the spawn ran locally, in which case queue, upload,
  fetch and network were never measured. They render as absent, never as zero.

### Runner
- *Definition:* the string Bazel put in `SpawnExec.runner` — `darwin-sandbox`,
  `worker`, `remote`, `disk cache hit`, `remote cache hit`.
- *Note:* free text, never parsed into an enum. `spawn.proto` constrains it to
  nothing and says it varies under the dynamic strategy.
- *Unavailable when:* no execution log, or the action never spawned a
  subprocess. On the actions table an action with several spawns shows the
  count instead: two spawns under different runners give the action none.

### Actions with attempt data
- *Definition:* `COUNT(DISTINCT action_id)` over `action_attempts`.
- *Note:* legitimately far below the action count — measured 4 against 13.
  Most actions run inside the Bazel server and never start a subprocess. Every
  place showing the ratio says so.

### Build phase duration
- *Definition:* the gap between one phase marker and the next.
- *Source:* trace profile. **Derived, not measured:** the profile records when
  each phase began and never when it ended. `build_phases.end_is_derived`
  records that, and the last phase has no end at all.

### Bazel's critical path
- *Definition:* the `critical path component` spans Bazel wrote into the
  profile, in order.
- *Note:* kept as Bazel's own answer and never joined to the actions table —
  its entries identify themselves with a progress message and nothing else.
  The visualizer's own dependency critical path is a separate Phase 6 metric
  and ADR-009 requires both to survive separately. The summary reads an exact
  component count and aggregate duration; descriptions stay in SQLite and are
  loaded by ordinal in bounded pages.
- *Trust:* profile components are read only from a currently successful import
  whose metadata confirms the BEP build id. A failed retry, missing identity,
  or mismatched build withholds retained components. The independent
  BuildMetrics total remains available when valid. A profile fallback total
  requires every component duration to be present and nonnegative, and exact
  addition must not overflow.

### Profile span absolute time
- *Definition:* a span's trace timestamp plus the profile's anchor.
- *Uncertainty:* zero on Bazel 8.4.1+, **±1 second on 6.5.0 and 7.6.1**, where
  the anchor is floored to the whole second. Any view placing a profile span
  against an execution-log timing on those versions must surface that;
  `ProfileAnchor.precisionCaveat()` is the wording.

### Output size from disk
- *Definition:* `Files.size` of an output that no source reported a size for.
- *Note:* fills holes only. A size Bazel reported is a different measurement,
  taken at execution time, of a file that may since have been rebuilt.
- *Unavailable when:* the file is not on this machine — the normal answer for
  an artifact that only ever existed on a remote executor. Counted as absent,
  never written as zero.

## Phase 6 catalog

### Active actions in a bin
- *Definition:* how many spans overlap a time bin, from the LOD pyramid.
- *Note:* an estimate at coarse zoom in the sense that the bin is wide, not in
  the sense that the count is approximate — it is exact for the bin it
  describes.

### Total overlapping duration in a bin
- *Definition:* the span-microseconds falling inside the bin, summed.
- *Note:* not the bin's width times its active count, and not a lane's elapsed
  time. Two actions overlapping for the whole bin contribute twice its width.

### Cache hits and misses in a bin
- *Definition:* spans **starting** in the bin that the execution log reported a
  cache result for, split by result.
- *Note:* start-attributed, not spread. Spreading would let one long cached
  action outweigh a hundred short ones in every bin it touched.
- *Unavailable when:* no execution log. Reported as zero *known*, which the
  view renders as unknown — never as a miss.

### Local and remote in a bin
- *Definition:* as above, split by whether the runner was a remote one.
- *Unavailable when:* no execution log, same distinction.

### Input bytes in a bin
- *Definition:* the input bytes of spans starting in the bin.
- *Note:* **a lower bound.** Spans reporting no byte count contribute nothing
  rather than a guess, so this is "at least" and never "exactly".

### Uniform category of a bin
- *Definition:* the mnemonic of every span in the bin, when they are all the
  same.
- *Note:* deliberately not "the most common". A per-bin histogram is
  unaffordable at millions of bins, and the two-word vote that fits cannot
  prove a majority in one streaming pass. A mixed bin reports nothing.

### Lane total duration
- *Definition:* the sum of the lane's span durations.
- *Note:* overlapping spans are counted twice, on purpose. It answers "how much
  work" and not "how long"; the lane's elapsed time is a separate number.

### Visualizer-computed dependency critical path
- *Definition:* the longest weighted path through the action graph, weighting
  each action by its measured duration.
- *Source:* the dependency graph plus whichever duration source was used, which
  the result states.
- *Execution-log weight:* the shortest recorded spawn duration correlated to
  each action. Dynamic attempts can race in parallel, so the aggregate
  **Subprocess time** sum is work rather than elapsed gating time and is never
  used as a path weight. Choosing the shortest attempt is conservative: it may
  understate an action, but it cannot double-count concurrent attempts and
  preserves the path's lower-bound meaning.
- **Not Bazel's critical path.** Bazel writes its own into the profile and
  Phase 5 stores it untouched. The two legitimately disagree: Bazel's includes
  scheduling and machine limits, this one is what the dependencies alone imply.
  ADR-009 keeps both.
- *Partial when:* any action has no measured duration. Those count as
  instantaneous, so the answer is a lower bound and says so.
- *Unavailable when:* the aquery import failed, its configurations are not an
  exact match, its target scope is not exact completed-BEP labels, structural
  completeness was not recorded, or any artifact path or depset reference was
  unresolved. Missing targets or references can remove nodes or edges, so
  computing a shorter path and calling it complete would be unsupported.
- *Comparison rule:* Bazel's total minus this total is shown only when every
  graph node was timed. With a partial path, missing action durations are part
  of the numeric difference, so calling that value scheduling or wait time
  would be unsupported.
- *Undefined when:* the graph has a cycle. A producer-to-consumer action graph
  cannot have one, so a cycle means the graph is wrong rather than the build,
  and the result names every action Kahn's algorithm could not order. That set
  includes both cycle members and actions downstream of a cycle; it is not
  presented as exact cycle membership.

### Slack
- *Definition:* how much later an action could have started without delaying
  the build, from the backward pass.
- *Note:* zero for every action on any equally longest critical branch. The UI
  selects one deterministic chain for its table and graph, so zero slack alone
  does not imply membership in that selected chain.

### In-flight targets (timeline live band)
- *Definition:* targets a running capture has seen configured
  (`TargetConfigured`) with no completion (`TargetCompleted`) or abort yet,
  drawn as spans growing from their configuration to the current wall clock.
- *Units:* targets; span positions in microseconds.
- *Source:* `targets.outcome = 'CONFIGURED'` with no completed/failed/aborted
  `configured_targets` row, positioned by `bep_events.receive_micros` of the
  target's configuration event.
- *Formula:* span start = receive time of the `TargetConfigured` event; span
  end = now, until completion removes the target from the band.
- *Completeness:* only while the capture is live; the band does not exist for
  a finished or imported session. A configured target whose event has no
  receive timestamp is counted in the band's label and drawn nowhere — absent,
  never zero-length.
- *Caveats:* **target-level, not action-level, and labelled as such.** BEP has
  no action-start event (`ActionExecuted` fires once, at completion), so
  "running actions" is not a fact the stream can supply and this band never
  claims it. Positions are **BEP receive times** — when the viewer received
  the event, not when Bazel did the work — which is the only live signal
  target events carry; they are comparable to the wall clock the band grows
  toward, but not a measurement of analysis or execution time.

## Phase 8 catalog

Plan 15's metrics catalog, plan 16's findings, and the two questions every entry
here has to answer before it may appear on a screen: where the number came from,
and how much of the build it covers.

### The duration source

- *Definition:* which measurement fed every duration in a collection — the
  build event stream's action start and end, or the execution log's per-spawn
  total time.
- *Formula:* `bestDurationSource()` counts, in this session, the actions with a
  usable BEP pair (`end > start`, both present) against actions for which every
  correlated attempt in the current successful execution-log import has a
  nonnegative total, and picks the larger. Ties go to the execution log.
- *Why it is not a constant:* the event stream publishes no action timestamps on
  Bazel 6.5.0 or 7.6.1, publishes `endTime == startTime` for every action on
  8.4.1, and omits roughly a third of them on 9.2.0; the execution log times
  only the actions that spawned a subprocess, which is a minority. Choosing from
  the Bazel version would be wrong for a session captured with different flags.
- *Caveats:* the two sources measure different spans and are named differently
  on screen for that reason — **Action wall duration** from the event stream,
  **Subprocess time** from the log. The second is a sum over an action's
  attempts, so an action raced by the dynamic strategy consumed more of it than
  it held the wall clock for. Subprocess timing components are not attached to
  an ActionMetrics row whose selected duration is a BEP wall span; doing so
  would mix the numerator and denominator of every fraction.

### Quantile (median, p90, p95, p99)

- *Definition:* the value at a rank in a group's distribution.
- *Source:* a fixed-layout integer histogram over the group's observations.
- *Formula:* rank is `ceil(q × count)`, clamped to at least one; the answer is
  the exact integer range of the bucket that rank falls in.
- *Reported as an interval, not a number.* A histogram does not know where
  inside a bucket its values fell, and a midpoint would be a derived guess with
  the same face as a measurement. The interval is never wider than about 1.6% of
  its own value. `p0` and `p100` come back as single values because the minimum
  and maximum are tracked exactly.
- *Unavailable when:* the group has no observations — in which case the minimum,
  maximum, sum and mean are all absent rather than zero, and the count is the
  one number that says why.

### Peak concurrency

- *Definition:* the largest number of actions observed running at one instant.
- *Source:* an exact sweep over every observed span's endpoints.
- *Formula:* spans are half-open, `[start, end)`, and every end at an instant is
  applied before every start at it — so a chain of back-to-back actions reports
  one, not briefly two.
- *Caveats:* **a span of zero length is never running.** Bazel 8.4.1 reports
  every action with `endTime == startTime`, so a sweep of its event-stream
  timings finds nothing running at any instant. That is true of the timings and
  false of the build, and `instantaneousSpans` is how the answer says so.
- *Unavailable when:* nothing had a usable span. Reported as unknown, never as a
  peak of zero.

### Average concurrency, parallelism factor

- *Definition:* summed span time divided by the wall-clock interval swept.
- *Note:* plan 15.2 and plan 15.4 ask for the same quantity under two names, and
  both names exist in the API because a reader who found only one would
  reasonably conclude the other was missing.
- *Caveats:* **an aggregate concurrency indicator, not CPU utilization.** A build
  of remote actions can report far more than the machine has cores, and a build
  of one long local action reports one whatever it did to the CPU. A second
  figure, average *while busy*, divides by the time something was running, so a
  build with one long stall does not lower its own baseline.

### Time with zero active actions, time with low concurrency

- *Definition:* microseconds inside the swept window at concurrency zero, and at
  concurrency below a threshold.
- *Note:* over the observed spans, not over Bazel's execution phase. A caller
  that knows the phase boundaries can compare the two; one that does not should
  not be told that it does.
- *Threshold:* the low-parallelism windows use this session's own typical
  concurrency — the average while busy — divided by two, and ignore stretches
  shorter than 500 ms.

### Slack

- *Definition:* how much later an action could have started without making the
  build longer.
- *Source:* the backward pass of the derived dependency schedule.
- *Note:* zero for every action on any equally longest derived branch. The
  selected chain is tie-broken deterministically and tracked separately, so a
  zero-slack action on an alternate equal branch is not labeled as a member of
  the displayed chain.
- *Unavailable when:* there is no imported action graph, or the action is not in
  it. An action that ran without being declared by analysis has no slack rather
  than slack of zero.

### Start concurrency, completion concurrency

- *Definition:* actions running when one action started, and running immediately
  before it finished.
- *Formula:* two binary searches over the sorted endpoints. "Immediately before"
  is one microsecond before the end, because spans are half-open and an action's
  own end instant is a moment at which it is no longer running.
- *Unavailable when:* the session's duration source did not place the action on
  the clock.

### Cache state, per action

- *Definition:* hit, miss, or **not reported**.
- *Formula:* an action is a miss if any of its attempts reported one, a hit if
  they all did, and not reported if none of them said.
- *Caveats:* **not reported is not a miss.** Most actions run inside the Bazel
  server and never spawn a subprocess, so no execution-log record exists for
  them. Every rate is over the actions that reported, and every place showing a
  rate shows the count it was taken over. Plan 16.1 requires sufficient
  cache-state coverage before a cache finding may be raised at all, and the rule
  refuses below half.

### Runner, per action

- *Definition:* the runner string Bazel wrote, verbatim.
- *Formula:* an action's runner is the one its attempts share. An action whose
  spawns ran under different runners, or whose spawns did not all report one,
  has **no** runner rather than whichever name sorted first.
- *Caveats:* free text, never parsed into a category. `spawn.proto` constrains
  the field to nothing and says it varies under the dynamic strategy, so
  "local", "remote" and "worker" are readings of the data and not the data.

### Declared uncacheable, declared not remotable

- *Definition:* counts of actions whose spawns Bazel marked `cacheable = 0` or
  `remotable = 0`.
- *Source:* execution log, Bazel's own declaration, read verbatim.
- *Why it exists:* it is the grounded answer to plan 16.1's "non-cacheable or
  local-only concentration", which would otherwise require guessing what a
  runner name implies.

### Coverage figures

- *Definition:* what share of the build each source describes — timing, runner,
  cache state, input size, output size, action-graph correlation,
  action-graph structural completeness, target graph, and BEP/execution-log
  correlation.
- *Action-graph correlation:* declared actions matched to executed actions.
  Cache hits legitimately lower this ratio, so it does not measure graph
  completeness.
- *Action-graph completeness:* an all-or-nothing claim over the declared graph.
  It is complete only when every artifact path and depset reference resolved;
  otherwise it is unavailable for dependency-derived claims and names the
  exact unresolved counts.
- *Note:* every incomplete figure carries the reason. "No aquery output was
  imported" and "an action the graph declares and the build served from cache
  never executed" are different problems with different fixes, and a bare
  percentage cannot tell them apart.
- *Caveats:* a source that produced nothing is reported as **unavailable** rather
  than as zero per cent, because zero of zero divides by nothing and reads as
  complete.

### Findings

- *Definition:* an evidence-backed optimization candidate (plan section 16),
  carrying a title, severity, confidence, evidence, metric values, the threshold
  used, why it may matter, caveats, a suggested next investigation, and links.
- *Severity is not confidence.* Severity is how much of the build the finding is
  about; confidence is how well the data supports it. A large effect measured
  through a source that covered a third of the actions is high severity and low
  confidence, and one combined number could not express that.
- *Caveats:* every finding is a correlation over one build that ran once, on one
  machine, under one set of flags. The rules report a measurement and a
  threshold and stop. `FindingLanguage` refuses "will improve", "definitely",
  "is caused by" and their relatives at construction, and permits "root cause"
  only when the finding rests on a structured failure record.

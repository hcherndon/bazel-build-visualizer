# Metric definitions

Every metric surfaced in the UI must have an entry here before it ships.
A number without a definition is a bug. Unavailable inputs make a metric
*unavailable or partial* — never silently zero (project rule). Entries
follow this template:

> **Name** — Definition · Units · Source · Formula · Completeness · Caveats

The full catalog grows with Phases 4-8. Three worked examples fix the format,
and the Phase 3 catalog below covers every number the Overview, Actions, Tests
and Failures views put on screen.

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

## Phase 3 catalog

Every number in the Overview, Actions, Tests and Failures views. Where two
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

### Failures

**Failed actions / failed targets / targets not built**

- *Definition:* three separate counts, never summed into one "failures" figure.
- *Units:* rows.
- *Source:* `actions.outcome`, `configured_targets.outcome`, `aborted_events`.
- *Formula:* three `COUNT(*)` queries.
- *Completeness:* the third depends on ingest running past `buildFinished`.
- *Caveats:* they do not scale together. The first two are bounded by what broke;
  the third by the size of the build. Presenting a total would make one broken
  target in a large workspace look like a catastrophe.

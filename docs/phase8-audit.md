# Phase 8 audit

The same three checks the earlier phases turned into greps, plus the one Phase 7
added. Every finding below is fixed.

1. **Call sites of every new public method.** The highest-yield check for the
   third phase running: 4 findings in Phase 6, 11 in Phase 7, 35 here.
2. **Declared columns against every INSERT.** No new DDL this phase, so the
   check ran the other way: every column the metric queries *read* was checked
   against the schema and against what writes it.
3. **Read the prose.** Phase 7 added this after finding a status document that
   called three finished phases "Not started".
4. **Run the thing, and measure it.** Two scale tests rather than an estimate.

---

## 1. Thirty-five public members with no production caller

The sweep listed 35 public methods and record components that nothing outside
their own file called. They fall into three groups, and only the first is the
kind of finding that gets deleted.

### Dead API, deleted

| Member | Why it went |
|---|---|
| `ConcurrencySweep.Spans.sweptCount()`, `timedCount()`, `untimedCount()` | Three accessors on the accumulator restating what `Result` already reports. Three more places for the same numbers to disagree. |
| `QuantileSketch.Distribution.bucketCount()` and `bucketCount(int)` | Nothing read either. `equals` uses the array directly. |
| `Coverage.uncovered()` | `describe()` already says "covered of total". |
| `SessionMetrics.startConcurrency(…)`, `completionConcurrency(…)` | A second definition of "immediately before completion" for a number `MetricQueries` already attaches to each candidate while it has the spans in hand. |
| `MetricsService.last()`, `setThresholds(…)` | A cached result nobody read, and a setter that could have made a finding's printed threshold disagree with the finding beside it. Thresholds became a constructor parameter. |
| `QuantileSketch.SUB_BUCKETS` (demoted), `MetricQueries.durationName` (demoted) | Public with no caller outside the package or the file. |

### Computed and never displayed — the real finding

The other 20 were record components of `InvocationMetrics`, `GroupAggregate`
and `ActionMetrics`: **plan 15.2's invocation metrics and plan 15.3's aggregate
distributions were being computed on every collection and shown nowhere.** The
Findings card had a coverage banner and a critical-path pair and nothing else.

That is the same defect Phase 6 found four of and Phase 7 found three of, at a
larger scale: work done, committed, and wired to nothing. The fix was to display
them — an *Invocation metrics* section (wall time, phase durations with their
derived-end caveat, action and attempt counts, cache split with its hit rate
over the actions that reported, runner counts verbatim, byte totals with their
unknown counts, test totals, event counts split into undecodable and
not-attempted, ingestion lag, correlation rate, and the full concurrency
summary) and a *By mnemonic* section (count, observed work, min/median/p95/max/
mean, cache split including the not-reported count, Bazel's uncacheable
declarations, input bytes).

### Two plan requirements the rules were missing

Found by asking what each unread accessor was *for*:

- **`ActionMetrics.slackMicros` was never shown.** Plan 16.1's long-critical-chain
  rule says to show "Path length, Top contributors, Serial sections, Slack,
  Whether graph coverage is complete". The rule showed the first two. It now
  reports each contributor's slack — zero for everything on the chain, by
  construction, which is what distinguishes "on the chain" from "merely slow" —
  and carries action-graph coverage as a metric with its own provenance.
- **`directDependencies` and `onDerivedCriticalPath` were never shown.** Both
  now appear in the high-fan-out finding, and start and completion concurrency
  in the queue-dominated one, which is where plan 15.1's per-action concurrency
  metrics actually answer a question.

### A link that promised something the code did not do

The long-critical-chain finding offered "Draw the dependency chain" and the
window's handler, given a graph link with no focused id, called `showCard(GRAPH)`
and stopped. It opened the graph and drew nothing.

This is precisely the Phase 7 finding — an error message naming a `submitPath`
that did not exist — recurring in a new place, and the fix is the same shape:
make it structural. `Finding.Link` carries a `Kind` enum rather than a free-text
filter string, so a destination that cannot honour a link fails to compile
instead of silently ignoring it. `Kind.DERIVED_CRITICAL_PATH` now sends the
chain's own node indices — from the collection that weighted it, not a second
computation with a different weighting — to `GraphView.showCriticalPath`, which
draws it on the Phase 7 canvas.

---

## 2. Columns read against columns written

No new DDL this phase, so the check ran in the read direction: every column
`MetricQueries` selects, against the schema and against the writer that fills
it.

Clean. All sixteen `action_attempts` columns the metrics read — including
`cacheable` and `remotable`, which the non-cacheable-concentration rule depends
on — are written by `AttemptWriter`. Every table the queries name exists.

The check that mattered here is a different one, and it is why
`bestDurationSource()` exists: a column can be present, written, and still
carry nothing usable. Bazel 6.5.0 and 7.6.1 publish no action timestamps at
all; 8.4.1 publishes `endTime == startTime` for every action. A metric layer
that trusted the schema would report a build of instantaneous actions. So the
source is chosen by counting what each one covers in *this* session.

---

## 3. Two documents that had drifted

- **`docs/architecture.md`** described `analysis-core` as "critical path, graph
  extraction, clustering, layout" and `storage-sqlite` without the new edge
  between them. Phase 8 adds the metric catalog, the sketches, the sweep and the
  finding rules to the first, and makes the second depend on it — following plan
  6.1, which puts metrics and aggregation in `analysis-core` and query
  implementations in `storage-sqlite`. The direction that matters is unchanged
  and still load-bearing: `analysis-core` depends on `core-model` and
  `graph-core` alone, which is what lets every formula in it be tested without a
  database.
- **`docs/implementation-status.md`** predicted, at the end of Phase 7, that the
  timeline's critical-path overlay "belongs with Phase 8's metrics work". It did
  not get drawn, and that prediction is now corrected rather than left standing.
  What Phase 8 delivered instead is the chain drawn on the **graph** canvas,
  which already had path rendering from Phase 7 and needed only the node
  indices. The timeline overlay remains a Phase 6 deliverable that has not
  shipped; it is recorded as such rather than as a Phase 8 omission.

---

## 4. What running it found

Nothing broken, which is worth stating because the previous two phases each
found a real bug this way. What the measurement did settle is a design question
that was open: whether the collection could sit on the overview's two-second
refresh. It cannot — 898 ms for 250,000 actions, and the scan is the part that
scales — so it runs once per session on its own thread and the overview's cards
keep coming from the indexed counts. See `docs/performance.md`.

The formulas measured at the plan's full Tier 3 ceiling rather than a tenth of
it, because neither needs a Bazel server: 13 ms to sketch five million
durations, 110 ms to sweep five million spans. The arithmetic is not the cost.

---

## Kept after checking

Two public constants have no caller outside their own file and stay public:
`MetricQueries.DEFAULT_GROUP_LIMIT` and `DEFAULT_CANDIDATE_LIMIT`. They
parameterise the public `Request` record, and a caller wanting a different limit
needs something to build from. They are named here so the next audit does not
have to rediscover the reason.

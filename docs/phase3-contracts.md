# Phase 3 shared contracts

Binding source: `docs/product-plan.md` sections 2.1, 10.7, 11.1–11.4, 13,
24 (Phase 3).

Measured source: `docs/bep-content.md` — what Bazel 6.5.0, 7.6.1, 8.4.1 and
9.2.0 actually emit. Where a contract below looks arbitrary, the finding id in
brackets is the measurement that forced it.

**These contracts exist in code.** Read the source; this page explains it.

| Contract | Implemented in |
|---|---|
| Provenance wrapper for any presented value | `core-model` `core.measure.Measured` |
| Byte totals that state their shortfall | `core-model` `core.measure.ByteTotal` |
| Which graph a view is showing | `core-model` `core.graph.GraphKind` |
| Action / target / test outcomes | `core-model` `core.domain.ActionOutcome`, `TargetOutcome`, `TestOutcome` |
| Entity identity | `core-model` `core.id.*` |
| Normalized tables | `storage-sqlite` `storage.schema.SchemaV2` |

## 1. Identity

| Entity | Key | Why not the obvious alternative |
|---|---|---|
| Action | `id.actionCompleted.primaryOutput` | `(label, configuration)` collides: one test target emitted six actions [A1] |
| Target | `(label, aspect)` | `TargetConfiguredId` carries no configuration, so a configuration-qualified key cannot be populated when the target is configured |
| Configured target | `(target, configuration)` | — |
| Configuration | the opaque BEP id, per stream | two configuration events were byte-identical with different ids [C2]; the mnemonic changed spelling across versions [C1] |
| Named set | `(stream, id)` | ids are dense decimals reshuffled between runs of the same build [F2] |
| Artifact | `pathPrefix` joined with `name` | `name` alone collides across target and exec configurations; `uri` embeds a machine-specific absolute path [F4, F5] |
| Test attempt | `(test, run, shard, attempt)` | all three are 1-based and always present [TS1] |

Identity is read from the **event id**, never from the payload. The payload's
`primaryOutput` is absent on every failed action, and on Bazel 9.2.0 absent from
a successful `RunfilesTree` action too, so its presence is not even a proxy for
success [A2, A3]. A configuration read from the payload can disagree with the
one in the id [C5]; the id wins and the normalizer never asserts they match.

A duplicate primary output is surfaced as a diagnostic, never upserted. The
uniqueness is measured, not guaranteed; if it breaks, the user needs to be told
rather than shown one action where two ran.

## 2. Absence has three meanings

Bazel emits protobuf-JSON, which omits a field at its default. Three different
facts produce the same absent key, and collapsing them is how a tool comes to
state things that are not true (plan 11.4, rule 11).

| Kind | Example | Stored as |
|---|---|---|
| proto3 default — the value is known | `completed.success` absent means the target failed; `isTool` absent means false | the default, in a NOT NULL column |
| this Bazel version cannot report it | `action.startTime` on 6.5.0 and 7.6.1 | NULL, disambiguated by `build_invocation.build_tool_version` |
| the flag was not passed | successful actions without `--build_event_publish_all_actions` | NULL, disambiguated by `build_invocation.publishes_all_actions` |

The compound case: `executionInfo.exitCode` is NULL for the second reason on
6.5.0/7.6.1 and for the first on 8.4.1/9.2.0. Same NULL, opposite meanings,
resolvable only against the version.

One measured defect gets its own handling: Bazel 8.4.1 reports
`endTime == startTime` for **every** action, a five-second sleep included [A5].
Both timestamps are stored as given and `duration_unknown_reason` records why
the duration is not derivable. Duration aggregates exclude unknowns; they never
count them as zero.

## 3. Dispatch

Read the event **type** from the id key, which is stable, and the **payload**
by its own key, which is different [E1]. `aborted` rides four id kinds —
`targetConfigured`, `targetCompleted`, `unconfiguredLabel`, `configuredLabel` —
and which one carries an analysis failure changed between 6.5.0 and 7.6.1, so
the failed-target set is the union across all of them keyed on the label inside
the id [E2, finding 47].

int64 fields arrive as JSON **strings**; `criticalPathTime`, `testTimeout`,
`testAttemptDuration` and `timingBreakdown[].time` arrive as protobuf Duration
text [E3]. The `*Millis` spellings are preferred: both spellings are emitted on
all four versions and disagreed in zero of 148 checks [E4].

## 4. Stream ordering

Measured across 43 streams and four versions, under `--jobs=64`, `--keep_going`
failures, SIGINT and SIGKILL:

- A `NamedSetOfFiles` is always published before any reference to it — 1,829
  references, zero forward references [O1]. Children always precede parents, so
  stream order is a valid reverse-topological order and per-set roll-ups can be
  computed incrementally.
- `configuration` precedes any target event referencing it [O2].
- `configured` precedes `completed`/`aborted` for the same label [O3].
- `aborted` events arrive **after** `buildFinished` [O5]. Ingest runs to
  `lastMessage: true`, never stopping at `buildFinished`, or the entire failed
  and skipped target list is lost.

The normalizer is a single streaming pass that takes the first of these as its
fast path and treats a forward reference as a surfaced warning rather than a
dropped row. The guarantee was measured on the JSON file transport; nothing has
measured it over gRPC BES, and the two experiments disagreed on whether it may
be relied upon at all (Contradiction 4). A warning satisfies both positions.

## 5. Paging and sort order

Every paged query is keyset, under every sort, in both directions. See
`docs/performance.md` for the two ways that was got wrong first and what each
cost.

Two consequences a reader of the code should know before changing it:

- **Unknowns sort first ascending and last descending.** That is SQL's own
  convention. Putting them at one end regardless of direction reads better and
  costs a full scan and a temporary b-tree per page, because `col IS NULL` is an
  expression no ordinary index supplies.
- **A page is composed from ranges, not expressed as one predicate.** The rest
  of the anchor's value group, then everything past it, then the unknowns. Each
  is a range SQLite can seek; the single-predicate forms that select the same
  rows are not, and their cost grows with the table.

## 6. Graphs, and what may be claimed about them

`GraphKind` names the six graphs and each view states which one it shows
(plan 2.1, rule 13). Two of them — `BEP_EVENTS` and `TEMPORAL` — carry no
dependency semantics at all, and `impliesDependency()` returns false for them so
no view can present an announcement edge as a build dependency.

Phase 3 populates `BEP_EVENTS`, `TARGETS`, `CONFIGURED_TARGETS` and
`OBSERVED_EXECUTION`. It does **not** populate `DECLARED_ACTIONS` — that needs
aquery, which is Phase 5 — and the actions view says so rather than implying the
actions it shows are all the actions there are.

## 7. What Phase 3 does not do

- **Action inputs.** The BEP does not report them. They arrive in Phase 4 from
  the execution log, and the table arrives with them; an empty one now would
  read as "these actions had no inputs".
- **Attempt-level execution detail.** A retried action appears once in the
  stream and reports its final result [A9]. Runner, cache result and queue time
  come from the execution log in Phase 4.
- **Cache-hit actions.** An absent action row means "not executed this
  invocation", never "failed" or "missing" [A8]. The capture path always passes
  `--build_event_publish_all_actions`; an imported BEP often will not have, and
  the view explains its empty state instead of implying nothing ran.
- **Critical path.** Bazel reports one only on 9.2.0, and deriving one from
  action timings is impossible on 6.5.0, 7.6.1 and 8.4.1, where the timings are
  absent or zero-length [A4, A5].
- **Cross-run identity.** Every key here is scoped to one invocation. Comparing
  builds is Phase 10 and needs content-based identity — path plus digest — not
  these ids [F2].
- **Heap and memory tiles.** The heap numbers require `--memory_profile` on all
  four versions [M2]. Only `garbageMetrics` is reliably present, and it is not a
  heap size.

## 8. Numbers the UI must not compute

Each of these was measured producing a specific wrong answer.

| Do not | Because |
|---|---|
| derive test pass/fail from `targetCompleted.success` | it is `true` for a test that failed [TS5] |
| show a cache hit *rate* | `actionsExecuted` excludes cache hits and `actionsCreated` can be smaller than `actionsExecuted` [M3, M5]; the two are shown as separate numbers |
| label `attemptCount` as retries | it equals `runCount` for a healthy multi-run test [TS1] |
| use `testSummary.totalRunDuration` as a timeline bound | it excludes failed retries and understated real wall time by 13× on a six-attempt test [TS2]; stored separately as Bazel's figure |
| sum test attempt durations as "time spent testing" | a cached attempt replays timestamps from ~2.1 s before the build started [TS3] |
| count `TestRunner` actions in action aggregates | test executions appear in both the action stream and the test stream [TS7] |
| sum `directoryOutput` alongside expanded children | Bazel reports a tree artifact twice [§7 tree artifacts] |
| average or cross-check the two build durations | `wallTimeInMs` is Bazel's internal span and disagrees with the terminal-visible elapsed time by up to a second [M7]; `cpuTimeInMs` can exceed it |
| classify failures from `exitCode.name` | a missing dependency exits `PARSING_FAILURE` and a syntax error exits `BUILD_FAILURE` [X1] |
| regex a failure message | the wording changes between versions [A6] |

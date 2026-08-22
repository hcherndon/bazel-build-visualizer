# Phase 4 shared contracts

What every Phase 4 component may assume about the others. Each rule exists
because a measurement in `docs/exec-log-and-profile.md` says the obvious
alternative is wrong; finding ids in parentheses point there.

Phase 3's contracts (`docs/phase3-contracts.md`) are unchanged and still apply.
This document adds only what a second and third source make necessary.

---

## 1. The unit of the execution log is a spawn, not an action

A row in `action_attempts` is one execution of one subprocess. It is not an
action, not an attempt *at* an action in the retry sense, and not in
one-to-one correspondence with anything in the BEP.

Three measured facts force this:

- Two thirds of BEP actions produce no spawn at all — 4 spawns against 13
  `actionCompleted` events — because they run inside the Bazel server (K1).
- One test produces exactly two spawns sharing a label and mnemonic, and the
  second is XML generation which exits 0 even when the test failed (K3).
- A spawn can produce no resolvable output, so nothing ties it to an action
  (K2).

So: **no component may assume an action has exactly one attempt, at least one
attempt, or at most one attempt.** Any of the three is false somewhere.

## 2. Correlation is recorded, never inferred from a null

`action_attempts.action_id` being null carries no meaning on its own. Four
different situations produce it, and they are not the same fact:

| `correlation` | Means | Is it a problem? |
|---|---|---|
| `MATCHED_BY_OUTPUT` | a produced output equalled an action's primary output | no |
| `MATCHED_BY_TEST_LABEL` | a test spawn tied to a test by label | no |
| `NO_ACTION_EXPECTED` | ran in-process, or is the XML spawn after a test | no, and common |
| `AMBIGUOUS` | more than one action matched equally well | yes, and shown |
| `UNMATCHED` | nothing matched and something should have | yes, and shown |

Plan 24 makes "ambiguous correlations remain visible" an exit criterion. A
column that only says "attached or not" cannot deliver it, so the reason is
stored beside the result and the UI reads the reason.

**No component may collapse these five into a boolean.**

## 3. There are two correlation paths and they are not variants of one

**Ordinary actions correlate by output path.** `primary_output` is the BEP's
action identity (finding A1), so a produced output equal to it is an identity
match, not a heuristic. Measured 4 of 4 on 7.6.1, 8.4.1 and 9.2.0.

**Tests correlate by label and attempt ordering.** They have to: on 7.6.1 the
spawn that ran the test has no resolvable output at all, and on 8.4.1+ its only
one is a `test.outputs` directory that BEP never names as any action's primary
output (K2).

**Nothing correlates by `(label, mnemonic)`.** That pair matches both of a
test's two spawns, and taking either the first or the last reports a failing
test as passing half the time (K3). The two are told apart by their outputs.

## 4. Data-source precedence: add columns, never overwrite them

ADR-009 and plan 12.3. Phase 4 writes no column that Phase 3 wrote. Where the
two sources describe the same quantity, both survive under names saying whose
they are — the pattern schema v3 already established for
`tests.bazel_first_start_micros`.

| Quantity | BEP says | Execution log says | Profile says | Shown as |
|---|---|---|---|---|
| when an action ran | `actions.start_micros` | `action_attempts.start_micros` | `profile_spans.start_micros` | all three, labelled |
| how long it took | `actions.end − start` | `total_micros` | `duration_micros` | all three, labelled |
| where it ran | — | `runner` | — | the execution log's |
| cache hit | — | `cache_hit` | — | the execution log's |
| critical path | — | — | `bazel_critical_path` | Bazel's, unjoined |

The inspector shows every source that has a value and names each one. It does
not pick a winner and it does not average.

## 5. Time has three clocks and two of them are uncertain

- **BEP timestamps** are epoch microseconds, and on 6.5.0 and 7.6.1 action
  timestamps do not exist at all (finding A4).
- **Execution-log `start_micros`** is epoch microseconds from 7.6.1 on. Bazel
  6.5.0 never emits it under any flag, so a 6.5.0 attempt has a length and no
  position (S2). Such an attempt must be drawn as a duration, never placed on a
  timeline.
- **Profile `ts`** is relative to `Initialize command`, is legitimately
  negative for `Launch Blaze` (P3), and becomes absolute only through
  `ProfileAnchor`.

`ProfileAnchor` carries its own uncertainty because on 6.5.0 and 7.6.1 the
anchor is floored to the whole second (P1). **A component that places a profile
span against an execution-log attempt on those versions must surface the ±1 s
window.** `ProfileAnchor.precisionCaveat()` returns the words for it; a caller
that ignores it is making a claim the data does not support.

## 6. Enrichment failure is contained by construction

Plan 21.4. Each task in `enrichment_tasks` is independent, and:

- No enrichment task writes to any table Phase 3 owns.
- A failed task leaves `SUCCEEDED` rows from other tasks untouched.
- A partly-succeeded task keeps the rows it wrote and records `PARTIAL` with
  how far it got, because those rows are real.
- Nothing about a task's failure makes the BEP data less trustworthy, and no
  code path may make it so.

The consequence for readers: **the absence of attempt data is never an error
state.** It is a normal condition with several causes, and the coverage panel
distinguishes them.

## 7. An empty execution log is not an empty result

A fully cached rebuild produces a log with an invocation header and zero spawns
(S3). That is a complete, correct log of a build in which every action hit the
action cache.

Distinguishing it from a truncated or absent log requires the invocation
header, which only the compact format has, and which Bazel 6.5.0 has no compact
format to provide (X1, V3). So:

- On 7+ with a compact log: "0 attempts, every action was cached" is a
  statement the data supports.
- On 6.5.0, or with a binary or JSON log: it is not. The session records that
  the distinction could not be made and the UI says so.

## 8. Verify the file belongs to the session, and say when you cannot

- A profile is checked by `otherData.build_id` against the BEP's
  `started.uuid`. Measured 4 of 4 (V1).
- A compact execution log is checked by `Invocation.id` the same way. Measured
  9 of 9 (V2).
- A binary or JSON execution log **cannot be checked at all** — there is no
  header — and is labelled unverified rather than assumed correct (V3).

`ExecLogFormat.unverifiableReason()` is the single place that wording lives.

## 9. Parsing rules that are not optional

1. Decompress zstd before parsing a compact log, and stream it (S4).
2. Resolve `output_id` against `File`, `Directory` **and** `UnresolvedSymlink`
   entries. Assuming `File` loses every tree artifact, which on 8.4.1+ is the
   only resolvable output a test spawn has (S5).
3. Keep `InputSet` as a DAG. Never flatten it into per-attempt input rows
   (plan 10.7, S5).
4. On Bazel 6.5.0, read `progress_message` from field 9 and `walltime` from
   field 17 — both `reserved` in the vendored proto. Parsed with the modern
   descriptor alone, a 6.5.0 log yields attempts with no timing and no error
   (S1).
5. Never let a 6.5.0 attempt's missing start read as absent-by-nature. It is
   version-cannot-report, and `start_unknown_reason` says so.

## 10. What Phase 4 does not do

- **It does not build the action graph.** Declared inputs come from `aquery`,
  which is Phase 5. `input_sets` holds the *actual* inputs of executed spawns,
  which is a different set and a smaller one.
- **It does not join Bazel's critical path to actions.** Its only identifier is
  a progress message Bazel is free to reword (P5).
- **It does not compute a dependency critical path.** That needs the action
  graph and is Phase 6.
- **It does not fill `DECLARED_ACTIONS`.** Still Phase 5.
- **It does not make the profile a timeline.** Phase 6 owns the timeline; Phase
  4 owns the rows it will read.

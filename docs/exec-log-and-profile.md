# The Execution Log and the JSON Trace Profile: What Is Actually In Them

Ground truth for Phase 4, measured against real Bazel 6.5.0, 7.6.1, 8.4.1 and
9.2.0 on 2026-08-22. Every claim below is something a probe produced, not
something the documentation says. Where the documentation and the bytes
disagree, the bytes are recorded and the disagreement is named.

Companion to `docs/bep-content.md`, which does the same for the BEP stream.
Where the two sources describe the same thing, ADR-009 governs: both values are
kept, under names that say whose they are.

## Legend

| Mark | Meaning |
|---|---|
| ✅ | present and populated |
| — | field absent from the wire |
| ✗ | the flag or format does not exist on that version |
| ⚠ | present but means something other than its name says |

The probe workspace is four genrules (one slow, one large) plus two
`simple_test` rules, one passing and one failing. Artifacts were produced with
`--noslim_profile --experimental_profile_include_target_label
--experimental_profile_include_primary_output
--build_event_publish_all_actions`, one execution-log format per invocation.

---

## 1. Which formats exist, and which can be combined

### X1 — The compact execution log does not exist on Bazel 6.5.0

| Flag | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|
| `--execution_log_binary_file` | ✅ | ✅ | ✅ | ✅ |
| `--execution_log_json_file` | ✅ | ✅ | ✅ | ✅ |
| `--execution_log_compact_file` | ✗ | ✅ | ✅ | ✅ |
| `--experimental_execution_log_file` | ✅ | ✗ | ✗ | ✗ |
| `--experimental_execution_log_spawn_metrics` | ✅ | ✗ | ✗ | ✗ |
| `--record_full_profiler_data` | ✗ | ✅ | ✅ | ✅ |

Measured by parsing `bazel help build` on each version. The name
`--experimental_execution_log_compact_file` — which is what the vendored
`spawn.proto` comment still refers to — exists on **none** of the four. The
flag graduated out of `experimental_` before 7.6.1 and the proto comment was
not updated. Do not detect the compact format by the experimental spelling.

The two 6.5.0-only flags are the reason S2 has an exception. Note that
`--experimental_execution_log_file` is *not* an early compact format; it is the
pre-split spelling of the binary/json pair.

### X2 — On Bazel 7+ the three formats are mutually exclusive; on 6.5.0 they are not

Passing binary and json together on 8.4.1:

```
ERROR: Must specify at most one of --execution_log_binary_file,
       --execution_log_json_file and --execution_log_compact_file
```

Exit code 2, before analysis, nothing captured. The identical command on 6.5.0
succeeds and writes **both** files. So the planner must choose exactly one
format per invocation on 7+, and the choice is not recoverable after the fact —
there is no second log to fall back to.

### X3 — `--slim_profile` defaults to `true` on all four versions, and slimming destroys per-action data

| | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|
| action events, `--noslim_profile` | 22 | 26 | 26 | 30 |
| action events, `--slim_profile` | 2 | 2 | 2 | 2 |
| total events, unslimmed | 469 | 767 | 1066 | 1613 |
| total events, slimmed | 313 | 300 | 314 | 311 |

`--[no]slim_profile (a boolean; default: "true")` on every version. A profile
captured without explicitly passing `--noslim_profile` therefore has **no
usable per-action timing** — the whole point of importing it. This is not a
large-build heuristic that happens to spare small builds: it fired on a
six-target workspace.

`--slim_profile` also removes `package creation`, `Fetching repository`,
`Conflict checking` and `gc notification` entirely, and cuts `bazel module
processing` from 576 events to 3 on 9.2.0.

---

## 2. What a spawn record contains

### S1 — Bazel 6.5.0's `SpawnExec` uses field numbers that the current `spawn.proto` marks `reserved`

Wire-level field numbers in the first `SpawnExec` of a 6.5.0 binary log:

| Field | 6.5.0 holds | Current `spawn.proto` says |
|---:|---|---|
| 9 | `progress_message` (`"Executing genrule //pkg:gen_a"`) | `reserved 9` |
| 17 | `walltime`, a `Duration` (25.8 ms) | `reserved 17` |
| 13 | *(absent)* | `cache_hit` |
| 19 | *(absent)* | `digest` |
| 20 | *(absent)* | `metrics` |

Parsing a 6.5.0 execution log with the vendored proto therefore yields a spawn
with **no timing at all** and no progress message: both land in unknown fields
and are dropped. Nothing errors. The result is a table of attempts whose
durations are all unknown, which reads exactly like a build whose durations
Bazel declined to report.

This is the single largest version hazard in Phase 4. A 6.5.0 log needs the two
legacy fields declared explicitly, or its timings must be marked
version-cannot-report rather than silently absent.

### S2 — Only 7.6.1+ gives an attempt an absolute start; 6.5.0 can give durations, behind an off-by-default flag

| `SpawnMetrics` subfield | 6.5.0 | 6.5.0 `+spawn_metrics` | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|:-:|
| `total_time` (1) | ✗ | ✅ | ✅ | ✅ | ✅ |
| `execution_wall_time` (8) | ✗ | ✅ | ✅ | ✅ | ✅ |
| `start_time` (20) | ✗ | ✗ | ✅ | ✅ | ✅ |
| `measured_memory_peak_bytes` (21) | ✗ | ✗ | — | — | ✅ |

`start_time` is a `Timestamp`, so from 7.6.1 an attempt has a real absolute
window — `start_time` and `start_time + total_time` — and can be placed on the
same timeline as BEP action timestamps.

6.5.0 is two cases, not one. By default it emits no `metrics` submessage at all
and its only duration is the legacy `walltime` at field 17 (S1). Passing
`--experimental_execution_log_spawn_metrics` — which exists **only** on 6.5.0
and defaults to false — adds a `metrics` submessage carrying `total_time` and
`execution_wall_time` in their modern positions. Verified by running the same
build twice: field 20 absent without the flag, present with subfields `[1, 8]`
with it.

Even so, **6.5.0 never emits `start_time`**. An attempt captured on 6.5.0 has a
length and no position, on any flag setting. So 6.5.0 attempts cannot be placed
on a timeline or aligned with profile spans, and must be shown as durations
only.

The boundary is 6.5→7, **not** binary-versus-compact: the 7.6.1 *binary* log
carries `metrics` with `start_time` just as the compact one does.

### S3 — A fully cached rebuild produces a log with an invocation header and zero spawns

Re-running an already-built target:

| | 6.5.0 (binary) | 7.6.1 | 8.4.1 | 9.2.0 (compact) |
|---|---:|---:|---:|---:|
| file size | 0 bytes | 66 | 66 | 66 |
| spawn entries | 0 | 0 | 0 | 0 |

The compact log's 66 bytes decompress to 57 and hold exactly one entry: the
`Invocation` header. `spawn.proto` states the reason — "spawns whose owning
action hits the persistent action cache are never reported at all."

So an empty execution log has two distinct causes that must not be conflated:
every action hit the action cache (a complete, correct log), or the log was
never written / was truncated. On 7+ the invocation header distinguishes them.
On 6.5.0 the file is zero bytes in both cases and they are **indistinguishable**.

### S4 — The compact log is zstd-compressed; the binary log is not

Compact logs begin `28 b5 2f fd` — the zstd frame magic — on 7.6.1, 8.4.1 and
9.2.0. Binary logs begin directly with a protobuf length varint. The compact
format needs a zstd decoder before a single record can be read; this is a
dependency Phase 4 adds, not an option.

Compression is why the compact log is worth it. Same build, 9.2.0:

| Format | Bytes | Relative |
|---|---:|---:|
| compact (zstd) | 789 | 1.0x |
| binary | 2,754 | 3.5x |
| json | 7,055 | 8.9x |

### S5 — The compact log's entries reference each other by id, and an id is not always a `File`

`ExecLogEntry` carries `id = 1` and a oneof of nine types. A `Spawn`'s outputs
are `output_id` references resolved against earlier entries. Those entries are
`File` (3), `Directory` (4) **or** `UnresolvedSymlink` (5) — all three of which
declare `path = 1`.

Entry kinds actually emitted by the test-run probe:

| | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---:|---:|---:|
| `File` | 13 | 13 | 13 |
| `Directory` | 0 | 2 | 2 |

A resolver that indexes only `File` loses every tree-artifact output — which on
8.4.1 and 9.2.0 is the *only* resolvable output the test spawn has (see K2).
The first version of this probe made exactly that mistake and printed
`<unresolved 14>`; the number is what caught it.

`InputSet` (6) is a DAG with `transitive_set_ids`, the same shape as BEP's
`NamedSetOfFiles`. Plan 10.7's rule applies unchanged: do not eagerly flatten it
into duplicated input rows.

---

## 3. Correlating spawns with BEP actions

### K1 — The execution log covers a strict, small subset of BEP actions

Same build, `--build_event_publish_all_actions` on:

| | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---:|---:|---:|
| BEP `actionCompleted` events | 13 | 13 | 15 |
| execution-log spawns | 4 | 4 | 4 |

The other nine to eleven actions — writing `.sh` files, runfiles manifests,
`stable-status.txt`, symlinks — complete inside the Bazel server without ever
spawning a subprocess, so no spawn record exists for them. This is the opposite
of the intuition that the execution log is the more detailed source: it is more
detailed **about the actions it covers**, and it covers roughly a third of them.

The data-coverage panel must say "4 of 13 actions have attempt data, because
the other 9 ran in-process", not "9 actions are missing".

### K2 — Output-path correlation succeeds for ordinary actions and fails for tests

Matching resolved spawn output paths against BEP `id.actionCompleted.primaryOutput`:

| Spawn kind | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|
| `Genrule` (4 per build) | 4/4 matched | 4/4 | 4/4 |
| `TestRunner`, main spawn | **0 resolvable outputs** | 1 `Directory` | 1 `Directory` |
| `TestRunner`, XML spawn | `test.xml` | `test.xml` | `test.xml` |

For an ordinary action, `primary_output` is an exact join key and it works.

For a test it does not. On 7.6.1 the test's main spawn has **no** resolvable
output at all — all ten of its declared outputs are `invalid_output_path`
entries, meaning declared-but-not-produced. On 8.4.1 and 9.2.0 its one
resolvable output is the `test.outputs` *directory*, which BEP does not list as
any action's primary output. So the spawn that actually ran the test can be
joined to a BEP action on no version.

Test spawns must therefore be correlated to **tests** — by `target_label` plus
attempt ordering — and not to actions. That is a second correlation path, not a
special case of the first.

### K3 — One `(label, mnemonic)` maps to two spawns for every test

Every test in the probe produced exactly two `TestRunner` spawns on all three
versions:

```
TestRunner //pkg:fail_test  exit=1  status='NON_ZERO_EXIT'  outputs=[test.outputs/]
TestRunner //pkg:fail_test  exit=0  status=''               outputs=[test.xml]
```

The second is not a retry and not a second attempt: it is the XML-generation
spawn, which succeeds even when the test it describes failed. A correlator
keying on `(label, mnemonic)` will find two rows and, if it takes either the
first or the last, will report a failing test as passing half the time.

They are told apart by their outputs, not by their order or their status.

### K4 — The same build correlates differently across versions

The 7.6.1 → 8.4.1 change in K2 is not cosmetic. On 7.6.1 `test.outputs` appears
in the spawn's `invalid_output_path` list (10 entries); on 8.4.1+ it is a
resolved `Directory` output (9 invalid entries). Identical workspace, identical
command. Any correlation-quality metric must be reported per version, and a
session captured on one version cannot have its correlation rate compared with
one captured on another.

---

## 4. The JSON trace profile

### P1 — `profile_finish_ts` holds the **start**, truncated to the second ⚠

| Version | `otherData` key | Anchor − BEP `buildStarted` | Anchor − BEP `buildFinished` |
|---|---|---:|---:|
| 6.5.0 | `profile_finish_ts` | −895 ms | −2,078 ms |
| 7.6.1 | `profile_finish_ts` | −756 ms | −2,229 ms |
| 8.4.1 | `profile_start_ts` | +20 ms | −1,461 ms |
| 9.2.0 | `profile_start_ts` | +22 ms | −1,377 ms |

On 6.5.0 and 7.6.1 the field named `profile_finish_ts` sits *before* the build
started and roughly 2.1 seconds before it finished, while the trace itself spans
1.2–1.5 s. It cannot be the finish. It is the start, floored to a whole second —
both values end in `000`, and the gap to `buildStarted` is exactly the
sub-second remainder.

Two consequences. Reading the field by its name places every span on those
versions about one build-length too late. And because it is floored to the
second, absolute alignment between profile spans and execution-log
`start_time` on 6.5.0/7.6.1 carries up to **1,000 ms** of error — so spans from
those versions must not be drawn against attempt timings at millisecond
precision, or shown as if they were.

On 8.4.1+ the key is renamed *and* the value gains millisecond precision,
matching `otherData.date` exactly.

### P2 — The phase set is not stable, so a phase overview cannot hard-code it

| 6.5.0 (7 markers) | 7.6.1 / 8.4.1 / 9.2.0 (5 markers) |
|---|---|
| Launch Blaze | Launch Blaze |
| Initialize command | Initialize command |
| Evaluate target patterns | Evaluate target patterns |
| Load and analyze dependencies | Load, analyze dependencies and build artifacts |
| Prepare for build | |
| Build artifacts | |
| Complete build | Complete build |

Three separate 6.5.0 phases were merged into one from 7.6.1 on. A UI that
renders a fixed list of phases will show three permanently empty rows on 7+, or
silently drop the merged phase on 6.

Phase markers are **instant** events (`"ph": "i"`) with no duration — except
`Launch Blaze`, which is `"X"` and starts at a negative `ts`. A phase's extent
is the gap to the next marker, which the importer computes; the profile does not
state it.

### P3 — Trace time is relative to "Initialize command", and starts negative

`ts: 0` is `Initialize command`. `Launch Blaze` runs from `ts: -17000` to
`-20000` µs depending on version. An importer that treats `ts` as unsigned, or
that assumes the minimum `ts` is zero, misplaces the launch span.

### P4 — Per-action attribution is available on all four versions, on `action processing` events only

```json
{"cat":"action processing","name":"Writing script pkg/fail_test.sh","ph":"X",
 "ts":99215,"dur":1559,"pid":1,
 "out":"bazel-out/darwin_arm64-fastbuild/bin/pkg/fail_test.sh",
 "args":{"target":"//pkg:fail_test","mnemonic":"FileWrite"},"tid":661}
```

`out` is the primary output path — the same join key BEP uses (finding A1 in
`docs/bep-content.md`) — and it is present on 6.5.0 too. The sibling category
`complete action execution` carries `name: "actuallyCompleteAction"` and **no**
`out` and **no** `args` on any version, so it cannot be attributed to anything.

`args.target` is present but **empty** on 7.6.1 for the workspace-status action
and populated on 9.2.0 (`@@bazel_tools//tools:internal_platform`). Prefer `out`.

Both fields require the flags: `out` needs
`--experimental_profile_include_primary_output`, `args.target` needs
`--experimental_profile_include_target_label`. Both default to `false`.

### P5 — Bazel's own critical path is in the profile, keyed only by progress message

```json
{"cat":"critical path component","name":"action 'Executing genrule //pkg:gen_a'",
 "ph":"X","ts":276238,"dur":36440,"pid":1,"args":{"tid":43},"tid":0}
```

This is the ADR-009 "Bazel-reported critical path" source. Its only identifier
is a human-readable progress message wrapped in `action '…'`. There is no
label, no output path, no mnemonic. Correlating it back to an action means
parsing that string, which is a presentation string Bazel is free to reword.

Recommendation for Phase 4: store the critical-path components as Bazel wrote
them, display them as Bazel's own answer, and do **not** join them to the action
table. The visualizer's own dependency critical path is a separate Phase 6
computation and ADR-009 requires both to survive separately anyway.

Note `tid: 0` with `args.tid: 43`: the components are drawn on a synthetic
thread and name the real one in `args`.

### P6 — Ten counter series carry the resource timeline

`ph: "C"` events, on 9.2.0: `action count`, `action count (local)`,
`CPU usage (Bazel)`, `CPU usage (total)`, `Memory usage (Bazel)`,
`Memory usage (total)`, `Network Down usage (total)`,
`Network Up usage (total)`, `System load average`, `Total worker memory usage`.

Thread names arrive as `ph: "M"` metadata events (`thread_name`,
`thread_sort_index`) — 109 of each on 9.2.0. A timeline that labels rows by
`tid` alone will show bare integers.

---

## 5. Provenance: does this file belong to this session?

### V1 — The profile's `build_id` equals BEP's `started.uuid` on all four versions

4 of 4 exact matches. `otherData.build_id` is a reliable check that a profile
belongs to the invocation a session captured.

### V2 — The compact log's `Invocation.id` equals BEP's `started.uuid`, on 7+ only

9 of 9 exact matches across 7.6.1, 8.4.1 and 9.2.0 over build, cached and test
runs. The header is the first entry, so the check costs one record.

### V3 — The binary and JSON logs carry no invocation identity at all

There is no header record: the file is a bare sequence of `SpawnExec`. A binary
log from an unrelated build is indistinguishable from the right one. Since
6.5.0 has no compact format (X1), **no execution log captured on 6.5.0 can be
verified as belonging to its session.** Imported 6.5.0 logs must be labelled
unverified rather than assumed correct.

---

## 6. Requirements this imposes on Phase 4

1. Detect the execution-log format by capability, not by version number, and
   never by the `experimental_` spelling (X1).
2. Choose exactly one execution-log format per invocation on 7+; record which
   (X2).
3. Always pass `--noslim_profile`; a profile without it has no per-action
   timing (X3).
4. Always pass `--experimental_profile_include_primary_output`; without it
   profile spans cannot be attributed (P4).
5. Declare 6.5.0's legacy `progress_message` (9) and `walltime` (17) explicitly,
   or mark 6.5.0 attempt timings version-cannot-report. Never let them read as
   absent (S1).
5a. Pass `--experimental_execution_log_spawn_metrics` on 6.5.0, and still record
   6.5.0 attempt starts as version-cannot-report — the flag adds durations, not
   positions (S2).
6. Decompress zstd before parsing a compact log; stream, do not buffer (S4).
7. Resolve `output_id` against `File`, `Directory` **and** `UnresolvedSymlink`
   entries (S5).
8. Preserve `InputSet` as a DAG; do not flatten (S5).
9. Distinguish "no spawns because everything was cached" from "no spawns because
   the log is absent or truncated" using the invocation header; on 6.5.0 record
   that the distinction cannot be made (S3, V3).
10. Correlate ordinary actions by output path, tests by label plus attempt
    ordering, and never by `(label, mnemonic)` alone (K2, K3).
11. Report coverage as "N of M actions have attempt data, because the rest ran
    in-process", never as missing data (K1).
12. Anchor profile spans with `profile_start_ts` on 8.4.1+ and with
    `profile_finish_ts` **read as a start** on 6.5.0/7.6.1, carrying a ±1 s
    uncertainty on those versions (P1).
13. Derive phase extents from the gaps between instant markers, and take the
    phase set from the file rather than from a constant (P2, P3).
14. Store Bazel's critical path as Bazel's, unjoined (P5, ADR-009).
15. Verify a profile by `build_id` and a compact log by `Invocation.id`; label a
    binary or JSON log unverifiable (V1, V2, V3).

---

## 7. Not yet measured

Stated so that nothing below is mistaken for a finding.

- Remote execution and remote caching. Every probe ran locally, so `cache_hit`
  was false in every record, and `"disk cache hit"` / `"remote cache hit"`
  runner values were never observed. `Digest` (field 16) was present but its
  behaviour under a remote cache is unmeasured.
- Dynamic execution, where `spawn.proto` says `runner` is whichever branch won.
- Genuine test retries. `--flaky_test_attempts` was not exercised, so the
  relationship between multiple real attempts and the two-spawn pattern of K3 is
  unknown.
- Worker strategies, and the `Total worker memory usage` counter series.
- A profile large enough for Bazel to slim it despite `--noslim_profile`, if
  such a threshold exists.
- Behaviour when the build is cancelled mid-flight: whether the execution log is
  left truncated, and whether the compact log's zstd frame is closed.
- `--execution_log_sort`, present on all four versions and never exercised.

---

## 8. The Starlark CPU pprof

Measured on 2026-09-02 with a real Starlark-heavy fixture under Bazel 6.5.0,
7.6.1, 8.4.1, and 9.2.0. Each invocation used
`--starlark_cpu_profile=<file>` and its own output user root. The four gzip
files were decoded against the upstream pprof schema and then imported through
the production streaming importer.

### C1 — the wire format and units are stable across all four versions

Every file is a gzip-compressed pprof `Profile` with exactly one sample type:
`CPU` measured in `microseconds`. `period_type` repeats those strings and the
period is 10,000 µs. Observed sample values are positive multiples of that
period. The importer still validates these facts rather than branching on a
Bazel version.

### C2 — stacks are leaf-first and symbols carry a definition start line

`Sample.location_id[0]` is the leaf, as required by pprof. Bazel emitted one
function per location in these probes, with a name, system name, filename, and
definition start line. Location line values reflect the first sampled frame
line and Bazel's encoder warns that sampled line attribution is unreliable.
The application therefore reverses stacks only for root-to-leaf drawing,
aggregates by function/file, and never claims a line heat map.

### C3 — CPU samples have no wall-clock placement or stable cross-build id

No sample timestamp or thread id was present. `time_nanos` identifies the
profile start and `duration_nanos` its monotonic duration, but cannot place
individual stacks on the Timeline. Function and location ids are derived from
process-local object identity and cannot be compared across sessions. CPU is
summed across all Starlark threads; it can exceed wall duration and excludes
blocked or unscheduled runnable time.

### C4 — the file carries no invocation identity or loss counter

Unlike the JSON trace profile, the Starlark pprof contains no build UUID,
Bazel version, or command line. A profile written into the managed directory by
the approved invocation has capture provenance; a future manual attachment
must be labelled unverified. The format also reports no dropped signal/sample
count, so the application cannot turn sampling loss into a numeric coverage
claim. A valid file with zero samples remains distinct from no file or a failed
import.

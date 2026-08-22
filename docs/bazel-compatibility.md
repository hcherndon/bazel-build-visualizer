# Bazel compatibility

The tool targets Bazel 6 through 9. Message shapes, flag names, and
auxiliary-output formats drift across that range, and forks/release
candidates make version strings unreliable.

Policy: **observed capability over version comparison.** The tool never
gates behavior on parsing `bazel version` output. Instead it probes: does
this binary accept this flag, does the stream contain this message field, did
the execution log arrive in the expected format? Each probe result becomes a
recorded capability on the session, and the instrumentation planner
(docs/instrumentation-planner.md) and decoders branch on capabilities only. A
Bazel we have never seen gets a degraded but honest experience — unknown
messages are journaled and counted (ADR-004), never dropped — rather than a
wrong guess based on a version threshold.

Version strings are still *recorded* (they are useful diagnostics and
display data); they are just never used as a behavior switch.

**Everything below was measured**, on real 6.5.0, 7.6.1, 8.4.1 and 9.2.0
binaries on macOS arm64 during Phase 2. `docs/bazel-ground-truth.md` is the
full record, including the commands, the output, the contradictions the
experiments could not resolve, and what they could not settle at all. Read it
before changing anything here.

## How capabilities are detected

`bazel help flags-as-proto` — a single line of base64 holding a
`bazel_flags.FlagCollection`, giving an exact per-command flag table. It works
on every targeted version. The fallback, when it fails, is scraping
`bazel help <command> --long`, which can establish that a flag exists and
nothing else.

Three details are load-bearing and each cost an experiment to learn:

- **The probe runs outside any workspace.** The output is byte-identical
  inside one, but inside one the command contacts the Bazel server: it takes
  the server's command lock, so the probe blocks for the whole of a build the
  user has running, and if its startup options differ from theirs at all it
  *restarts their server and discards their analysis cache*. Reaching for
  `--ignore_all_rc_files` to make the probe deterministic is exactly what
  guarantees that difference. Outside a workspace it runs in batch mode: no
  server, no lock, ~0.7 s.
- **Bazelisk must be pinned.** Outside a workspace it has no `.bazelversion`
  to read and will happily probe a different Bazel than the build will use, so
  the resolved version is passed back through `USE_BAZEL_VERSION`.
- **stdout must not be merged with stderr.** The base64 is on stdout, a batch
  mode notice and JVM warnings on stderr, and merging them corrupts the
  payload. It is one line of 470–584 KB with no trailing newline.

A failed probe yields `UNKNOWN` for every capability, never `UNSUPPORTED`. One
unrecognized flag in a user's `.bazelrc` makes the probe exit 2 with empty
stdout on every version.

## Measured flag matrix

| Capability | Flag | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|:-:|:-:|:-:|:-:|
| BES backend | `--bes_backend` | yes | yes | yes | yes |
| BES lifecycle events | `--bes_lifecycle_events` | yes | yes | yes | yes |
| BES timeout | `--bes_timeout` | yes | yes | yes | yes |
| BES upload mode | `--bes_upload_mode` | yes | yes | yes | yes |
| Binary BEP file | `--build_event_binary_file` | yes | yes | yes | yes |
| JSON BEP file | `--build_event_json_file` | yes | yes | yes | yes |
| Publish all actions | `--build_event_publish_all_actions` | yes | yes | yes | yes |
| BEP file upload mode | `--build_event_binary_file_upload_mode` | **no** | yes | yes | yes |
| Compact execution log | `--execution_log_compact_file` | **no** | yes | yes | yes |
| Binary execution log | `--execution_log_binary_file` | yes | yes | yes | yes |
| JSON trace profile | `--generate_json_trace_profile` | yes | yes | yes | yes |
| Announce profile path | `--experimental_announce_profile_path` | yes | yes | **no** | **no** |
| Query output format | `--output` (aquery/cquery) | yes | yes | yes | yes |

`FlagInfo`'s own fields arrived over time, and they are proto2 optionals, so a
getter cannot tell an absent field from an empty value:

| `FlagInfo` field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|
| 1–9 (name, commands, `allows_multiple`, tags…) | yes | yes | yes | yes |
| 10 `requires_value` | **absent** | yes | yes | yes |
| 11–16 (`default_value`, `enum_values`, …) | **absent** | **absent** | yes | yes |

`--output`'s `enum_values` is empty on **every** version, so the flag dump
cannot say whether `--output=proto` is accepted. Verified by execution
instead: `proto` is accepted by aquery, cquery and query on all four;
`streamed_proto` is rejected by aquery on 6.5.0.

## Command-line grammar

- Startup options and command options are a hard partition, and misplacement
  is fatal both ways with two different error shapes (server-side
  `ERROR: X :: Unrecognized option`, client-side `[FATAL …] Unknown startup
  option`).
- The last occurrence of a single-valued flag wins, silently — which is what
  makes appending a safe override. Accumulating flags (`--build_metadata`,
  `--copt`, `--bes_header`) keep every occurrence instead.
  `bazel canonicalize-flags --for_command=<cmd> --` is a scriptable oracle for
  which is which, per version, with no build required.
- Flags and target patterns interleave freely, and values may be attached
  (`--flag=v`) or separate (`--flag v`).
- **After a `--`, everything is a target pattern.** A flag placed there is not
  rejected: its leading dash is read as the exclusion marker, the build fails
  during target resolution with exit 1, and *no event stream is written at
  all*. Instrumentation must be spliced before any user `--`.
- Workspace markers are `MODULE.bazel`, `REPO.bazel`, `WORKSPACE.bazel` and
  `WORKSPACE`, each independently sufficient on all four versions — including
  a `WORKSPACE`-only directory on 9.2. `WORKSPACE.bzlmod` is **not** a marker.
- `bazel info workspace` reports the root, but relative target patterns
  resolve against the process working directory. The launcher records both and
  rewrites neither.

## BES behaviour the embedded server depends on

- An acknowledgement carrying the **wrong** `sequence_number` kills the Bazel
  server outright on 6.5.0 and 9.2.0 (fatal internal error, exit 37), and
  fails gracefully on 7.6.1 and 8.4.1. The experiment's own stack trace looks
  race-shaped, so the safe assumption is that it can crash any version.
  `BesServerTest` pins the exact echo.
- A server that stops acknowledging makes Bazel wait **indefinitely** — no
  error, no retry, no give-up, workspace lock held. `--bes_timeout` defaults
  to `0s`, meaning no timeout, so the planner injects an explicit one.
- Closing the stream with status OK while acknowledgements are outstanding
  fails fast and cleanly (~1.2 s, exit 45). That is the correct way for the
  server to give up; stalling is not.
- Exit code 38 means the upload failed and **masks the build's own result**.
- Bazel's uploader outlives its client and replays the stream from sequence 1
  on a new connection, with the same `StreamId`.

## Process model and cancellation

- The Bazel server is **not** a descendant of the client. It runs with
  `PPID 1` in its own session from the first millisecond, so killing the
  client's process tree reaps nothing — which is the intended outcome, since
  the server is shared with every other terminal using that output base.
- Bazelisk `exec`s the real Bazel, so the spawned PID is the client.
- SIGINT and SIGTERM both exit 8 with a complete event stream; the client
  exits within ~25 ms, so escalation timers below a second are dead code.
- SIGKILL does not stop the build: the server runs on for ~2.5 s, cancels the
  build itself, finishes the stream, and holds the command lock throughout —
  reporting a PID that is already dead.
- A signal delivered in the client's first few tens of milliseconds is a race
  that can be lost entirely, can produce exit 130 with no stream at all, and
  on Bazel 9 can kill the server. The launcher holds the first signal until
  the client is a second old.

## Event content the normalizer depends on

A second measurement pass (five experiments, same four versions, 2026-08-22)
covered what is *inside* the stream rather than how it is transported. The full
record — field matrices per entity, the evidence for each claim, five
unresolved contradictions and a list of what was never provoked — is
`docs/bep-content.md`. The version-dependent parts:

| Behaviour | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| Action `startTime` / `endTime` | absent | absent | present but `endTime == startTime` for every action | present, usable |
| `importantOutput` on `TargetComplete` | present | present | absent by default | absent by default |
| Synthetic tags appended to `completed.tag` | no | yes | yes | yes |
| Analysis failure's `aborted` rides | `targetCompleted` | `targetConfigured` | `targetConfigured` | `targetConfigured` |
| `configured` payload for an analysis-failed target | present | absent | absent | absent |
| `buildMetrics` network / Skyframe counters | absent | absent | present | present |
| `timingMetrics.executionPhaseTimeInMs` | absent | present | present | present |
| `timingMetrics.criticalPathTime` | absent | absent | absent | present |
| Fully-cached mnemonic in `actionData` | dropped | dropped | present | present |
| `testResult` `executionInfo.exitCode` | absent | absent | proto3-default when 0 | proto3-default when 0 |
| Carrier of `lastMessage: true` | `buildToolLogs` | `buildToolLogs` | changed | changed |
| `--build_event_max_named_set_of_file_entries` default | `-1` | `-1` | 5000 | 5000 |

Version-independent, and each one a way to be wrong on all four:

- `id.actionCompleted.primaryOutput` is unique across a stream and present on
  failures; the payload's `primaryOutput` is absent on every failure and on some
  successes. `(label, configuration)` collides heavily.
- `action.exitCode` is `1` for every failure; the process's real code is
  `failureDetail.spawn.spawnExitCode`.
- `targetCompleted.success` is `true` for a test that failed. Test verdicts come
  from `testSummary.overallStatus` only.
- `aborted` events arrive **after** `buildFinished`. An ingest that stops there
  loses the entire failed and skipped target list.
- A `NamedSetOfFiles` was always published before any reference to it: zero
  forward references in 1,829 references across 43 streams. Measured on the JSON
  file transport only, so the normalizer treats it as a fast path and surfaces a
  violation rather than assuming it cannot happen.
- Named-set ids are dense decimals reshuffled between runs of the same build.
- Configuration id `system` is referenced on every build and never declared.
- Two configuration events can be byte-identical with different ids.
- Both the `*Millis` and the Timestamp/Duration spellings are emitted on all
  four versions and never disagreed in 148 checks.

## Enrichment flags (Phase 4)

Measured on 2026-08-22 by parsing `bazel help build` on each version, and
confirmed by running builds with them.

| Flag | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|
| `--execution_log_binary_file` | yes | yes | yes | yes |
| `--execution_log_json_file` | yes | yes | yes | yes |
| `--execution_log_compact_file` | **no** | yes | yes | yes |
| `--experimental_execution_log_file` | yes | no | no | no |
| `--experimental_execution_log_spawn_metrics` | yes | no | no | no |
| `--profile`, `--generate_json_trace_profile`, `--[no]slim_profile` | yes | yes | yes | yes |
| `--experimental_profile_include_primary_output` | yes | yes | yes | yes |
| `--record_full_profiler_data` | no | yes | yes | yes |

Four behaviours the planner depends on:

**The three execution-log formats are mutually exclusive from Bazel 7.** Naming
two is a command-line error that fails the build before analysis. On 6.5.0 the
same command succeeds and writes both files.

**`--slim_profile` defaults to `true` on every version**, and slimming cuts
per-action events from 22–30 down to 2. A profile captured without
`--noslim_profile` has none of what the importer reads it for.

**Bazel 6.5.0 writes `progress_message` at field 9 and `walltime` at field 17
of `SpawnExec`,** both of which the current `spawn.proto` marks `reserved`.
Parsed with the modern descriptor alone it yields attempts with no timing and
no error.

**The profile's absolute anchor changed name and meaning between 7.6.1 and
8.4.1.** `profile_finish_ts` on 6.5.0 and 7.6.1 holds the *start*, floored to
the second; `profile_start_ts` on 8.4.1+ holds it exactly.

Full measurements, including the correlation behaviour and what was not
measured, are in `docs/exec-log-and-profile.md`.

## What is not covered

Everything above was measured on macOS arm64 only. Linux and Windows are
unverified, and each report's own "Unverified" section lists what the
experiments could not settle — including the exact 7.x release that added
`FlagInfo` fields 11–16, whether `help flags-as-proto` exists before 6.5, and
whether the named-set ordering guarantee holds over gRPC BES as it does over the
JSON file.

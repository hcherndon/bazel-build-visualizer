<!--
  Generated from five experiment runs against real Bazel 6.5.0, 7.6.1, 8.4.1
  and 9.2.0 binaries during Phase 2, on macOS 26 arm64. It is evidence, not
  design: every claim here is followed by the command that produced it, and
  the "Unverified" section at the end is as load-bearing as the rest.

  docs/bazel-compatibility.md is the summary the code is written against;
  this is the record of how those conclusions were reached.
-->

# Bazel Integration Ground Truth — Merged Report

All results below come from five experiment runs against real Bazel binaries driven by `bazelisk` (`USE_BAZEL_VERSION=<v> ~/.cache/bbv-dev/bin/bazelisk ...`) on **macOS 25.6.0, arm64**, 2026-08-21/22. Nothing here was tested on Linux or Windows.

## 0. Coverage matrix

Not every topic was exercised on every version. Do not read "all four" into a row that says otherwise.

| Topic area | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| BES failure modes / ACK protocol | yes | yes | yes | yes |
| Cancellation & process model | partial (steady-state + lock only) | yes | partial (steady-state + lock only) | yes |
| Command-line grammar (`--`, precedence, `run`) | no | yes | no | yes |
| Workspace markers | yes | yes | yes | yes |
| `help flags-as-proto` probe | yes | yes | yes | yes |
| BEP content / `--build_event_publish_all_actions` | yes | yes | **not tested at all** | yes |

---

## 1. Exit codes (cross-cutting)

Every experiment produced exit codes; this is the merged table. **Symbolic names were never confirmed** — no experiment read Bazel's `ExitCode` enum. Record numbers and message text, not guessed names (see Unverified §9.1).

| Code | Observed meaning | Where produced | Versions |
|---|---|---|---|
| 0 | Success — including a successful BES upload, and including a build that ran to completion after a cancel signal was silently lost, and including all `--bes_upload_mode=nowait_for_upload_complete` / `fully_async` failures | §4, §5.3, §6.5 | all four |
| 1 | Build/action failure with no BES attached; also target-pattern parse failure after a `--` | §5.6, §2.4 | all four (1 for actions); 7.6.1/9.2.0 (`--` case) |
| 2 | Option parsing error: unrecognized option, misplaced startup/command option, invalid enum value, not in a workspace, bad `.bazelrc` line | §2.1, §2.2, §3.1, §4.6 | all four |
| 4 | `bazel test` with no test targets ("No test targets were found, yet testing was requested") — observed incidentally | §2.5 | 7.6.1/9.2.0 |
| 8 | Interrupted — SIGINT or SIGTERM handled by the client | §6.2 | all four |
| 9 | Command lock held, with `--noblock_for_lock` | §6.4 | all four |
| 37 | `FATAL: bazel crashed due to an internal error` / `Server terminated abruptly` — wrong ACK sequence number (6.5.0, 9.2.0) and early SIGINT on 9.2.0 | §5.1, §6.5 | 6.5.0, 9.2.0 |
| 38 | BES upload failed or timed out (transport-level) | §5.1, §5.2 | all four |
| 45 | BES protocol violation — server closed the stream with status OK before all ACKs; also wrong ACK on 7.6.1 | §5.1 | all four (early-close); 7.6.1 (wrong ACK) |
| 130 | SIGINT delivered before the client installed its handler — **no BEP file created at all** | §6.5 | 7.6.1, 9.2.0 |
| 137 | SIGKILL (test harness cap on a hung invocation) | §5.1 | all four |
| 143 | **Inferred, never observed.** The cancellation write-up asserts "exit 130 (and 143 for SIGTERM)"; only 130 was measured. | — | — |

---

## 2. Command-line grammar and argv construction

> Grammar findings in this section were measured on **7.6.1 and 9.2.0 only** unless noted. See Unverified §9.3.

### 2.1 Startup segment vs command segment is a hard partition

`--output_base`, `--max_idle_secs`, `--host_jvm_args`, `--bazelrc`, `--noblock_for_lock` are all **startup** options: rejected after the verb, accepted together before it.

```
$ USE_BAZEL_VERSION=9.2.0 bazelisk --output_base=<OB> build --output_base=/tmp/zzz //:gen_a
ERROR: --output_base=/tmp/zzz :: Unrecognized option: --output_base=/tmp/zzz
exit=2
$ bazelisk --output_base=<OB> --max_idle_secs=100 --host_jvm_args=-Xmx2g --bazelrc=/dev/null --noblock_for_lock info release
release 9.2.0   exit=0
```

The `Unrecognized option` line is byte-identical between 7.6.1 and 9.2.0. `--noblock_for_lock` as a startup option is independently corroborated on 6.5.0 and 8.4.1 by the lock-release probes (§6.4), which is the only cross-version evidence for the startup/command split outside 7.6.1/9.2.0.

### 2.2 Two distinct error shapes, both exit 2

A **command** option in startup position is rejected by the C++ client before the server is consulted, with `FATAL`, not `ERROR`:

```
$ USE_BAZEL_VERSION=7.6.1 bazelisk --output_base=<OB> --keep_going build //:gen_a
[FATAL 08:24:40.588 src/main/cpp/blaze.cc:1105] Unknown startup option: '--keep_going'.
$ USE_BAZEL_VERSION=9.2.0 bazelisk --output_base=<OB> --keep_going build //:gen_a
[FATAL 08:24:40.602 src/main/cpp/blaze.cc:1255] Unknown startup option: '--keep_going'.
```

| Shape | Producer | Stable anchor | Varies by version |
|---|---|---|---|
| `ERROR: X :: Unrecognized option: X` | server-side option parser | whole line (byte-identical 7.6.1 vs 9.2.0) | — |
| `[FATAL <ts> src/main/cpp/blaze.cc:<N>] Unknown startup option: '<X>'.` | C++ client | substring `Unknown startup option: '` | timestamp; source line 1105 (7.6.1) vs 1255 (9.2.0) |

### 2.3 Last occurrence wins — but only for single-valued flags

```
$ bazelisk build --build_event_json_file=<D>/bepA.json --build_event_json_file=<D>/bepB.json //:gen_a
exit=0; bepA exists: NO ; bepB exists: YES size=43327 (7.6.1) / 57774 (9.2.0)   # no WARNING emitted
$ bazelisk canonicalize-flags --for_command=build -- --build_event_json_file=/tmp/A.json --build_event_json_file=/tmp/B.json
--build_event_json_file=/tmp/B.json
$ bazelisk canonicalize-flags --for_command=build -- --keep_going --nokeep_going
--keep_going=0
```

Accumulating (`allowMultiple`) flags keep **every** occurrence:

```
$ bazelisk canonicalize-flags --for_command=build -- --build_metadata=k=1 --build_metadata=k=2
--build_metadata=k=1
--build_metadata=k=2
$ bazelisk canonicalize-flags --for_command=build -- --bes_header=a=1 --bes_header=b=2
--bes_header=a=1
--bes_header=b=2
```

`bazel canonicalize-flags --for_command=<cmd> -- <flags>` is a scriptable oracle for accumulate-vs-override, per flag per version, with no build required.

| Flag | Behaviour |
|---|---|
| `--build_event_json_file`, `--bes_lifecycle_events`, `--build_event_publish_all_actions`, `--keep_going` | single-valued, last wins |
| `--build_metadata`, `--copt`, `--bes_header` | accumulates (duplicate keys are kept verbatim; dedup is the consumer's problem) |

### 2.4 `--` is a permanent mode switch, not a separator you can ignore

A bare trailing `--` on `build` is provably a no-op:

```
$ bazelisk build //:gen_a --
exit=0 ; INFO: Found 1 target... ; INFO: Build completed successfully, 1 total action
$ bazelisk build --
exit=0 ; WARNING: Usage: bazel build <options> <targets>. ; INFO: Found 0 targets...   # same as omitting it
```

Anything after it is parsed as a target pattern, and a flag is silently reinterpreted as a **negative** pattern:

```
$ bazelisk build //:gen_a -- --build_event_json_file=<D>/bepC.json
exit=1
WARNING: Target pattern parsing failed.
ERROR: Skipping '-build_event_json_file=<D>/bepC.json': no such target '//:-build_event_json_file=...'
bepC written: NO
$ bazelisk build -- -- //:gen_a       # second -- becomes the pattern "-"
exit=1 ; ERROR: Skipping '-': no such target '//:-'
$ bazelisk build -- //... -//:gen_b   # real exclusions still fine
exit=0, Found 3 targets
```

Note the exit code is **1** here (target resolution), not 2 — a "flag problem?" check keyed on exit 2 misses this entirely, and the BEP file is never written, so the failure surfaces as a dead invocation with no event stream.

### 2.5 Flags and targets interleave freely; values accept the space form

```
$ bazelisk build //:gen_a --keep_going //:gen_b            exit=0 ; Found 2 targets
$ bazelisk build //:gen_a --build_event_json_file=<D>/bepD.json //:gen_b
exit=0 ; bepD: YES 43283 bytes (7.6.1) / 53007 bytes (9.2.0)
```

`--build_event_json_file <path>` (space-separated) also produced the file on both versions.

### 2.6 `bazel run` passthrough

```
$ bazelisk run //:tool -- --flag plain --keep_going
ARGC=3 / ARG1=[--flag] / ARG2=[plain] / ARG3=[--keep_going]
PWD=<output_base>/execroot/_main/bazel-out/darwin_arm64-fastbuild/bin/tool_run.sh.runfiles/_main
$ bazelisk run //:tool --flag        ERROR: --flag :: Unrecognized option: --flag
$ bazelisk run //:tool -- -- --x     ARGC=2 / ARG1=[--] / ARG2=[--x]
$ bazelisk run //:tool --keep_going -- --flag    ARGC=1 / ARG1=[--flag]
$ bazelisk run //:tool --            ARGC=0 exit=0
```

Only the **first** `--` is a separator. The tool's cwd is its runfiles directory, not the user's cwd.

### 2.7 Fixture constraint: native `sh_binary` is gone in 9.2.0

```
$ USE_BAZEL_VERSION=7.6.1 bazelisk build //...   exit=0
$ USE_BAZEL_VERSION=9.2.0 bazelisk build //...   exit=1
ERROR: <ws>/BUILD.bazel:13:1: name 'sh_binary' is not defined (did you mean 'cc_binary'?)
```

A ~12-line Starlark rule returning `DefaultInfo(executable=...)` builds cleanly on both and is what §2.6 was measured against.

---

## 3. Workspace discovery, cwd, and target patterns

### 3.1 Marker files — identical set on all four versions

Seven sibling dirs, each with one marker, no marker in any ancestor; `bazel info workspace` in each:

| Marker file | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| `MODULE.bazel` | root | root | root | root |
| `WORKSPACE` | root | root | root | root |
| `WORKSPACE.bazel` | root | root | root | root |
| `REPO.bazel` | root | root | root | root |
| `WORKSPACE.bzlmod` | exit 2 | exit 2 | exit 2 | exit 2 |
| (none) | exit 2 | exit 2 | exit 2 | exit 2 |

A `WORKSPACE`-only directory still **builds** on 9.2.0 (`exit=0, INFO: Build completed successfully, 2 total actions`, verified on 6.5.0/7.6.1/9.2.0 for all four markers).

Only the error wording changed:

```
6.5.0 / 7.6.1: ERROR: The 'info' command is only supported from within a workspace (below a directory having a WORKSPACE file).
8.4.1 / 9.2.0: ERROR: The 'info' command is only supported from within a workspace (below a directory having a MODULE.bazel file).
```

Stable substring across all four: `is only supported from within a workspace`. The verb name is interpolated (`'build'` vs `'info'`).

### 3.2 `bazel info workspace` is the root; relative patterns resolve against cwd

```
cwd = <D>/ws/sub
$ bazelisk info workspace        -> <D>/ws
$ bazelisk info | grep -E '^(workspace|execution_root|output_base):'
execution_root: <OB>/execroot/_main ; output_base: <OB> ; workspace: <D>/ws    # no key reports cwd
$ bazelisk query :all   -> //sub:gen_sub
$ bazelisk build ...    -> exit=0, Found 1 target      # the sub package, not the 4 at root
$ bazelisk build gen_sub -> exit=0, Found 1 target
```

### 3.3 The cwd is recoverable from the BEP, but not from `started`

Build run from `<D>/ws/sub` with `--build_event_json_file`:

```
started.workspaceDirectory              = <D>/ws            # the ROOT
id.pattern                              = {'pattern': [':all']}   # literal, unresolved
structuredCommandLine[original].residual = [':all']
unstructuredCommandLine.args contains    '--client_cwd=<D>/ws/sub'
structuredCommandLine 'original', section 'command options':
    optionName=client_cwd  optionValue=<D>/ws/sub  combinedForm=--client_cwd=<D>/ws/sub
```

Present on both 7.6.1 and 9.2.0. The `canonical` command line differs substantially between versions (9.2.0 injects four `--flag_alias=...@@rules_python+//` entries) and `started.optionsDescription` is polluted with them on 9.2.0 — do not diff canonical command lines across versions and do not treat `optionsDescription` as a record of what the user typed.

---

## 4. Capability probing via `bazel help flags-as-proto`

### 4.1 Works everywhere, including outside a workspace

```
$ USE_BAZEL_VERSION=<v> bazelisk help flags-as-proto        # in ws and in noworkspace
$ cmp nows-<v>.stdout ws-<v>.stdout   -> IDENTICAL for 6.5.0, 7.6.1, 8.4.1, 9.2.0
```

Outside a workspace it runs in **batch mode** and starts no server (`ps -eo pid,command | grep 'workspace_directory=.*gt-flags-proto'` returned 0 processes after cleanup). Batch-mode warning wording follows the same 6/7 vs 8/9 split as §3.1.

### 4.2 Output is one line of base64, no newline anywhere

```
$ wc -l < stdout                                     -> 0    (every version, both contexts)
$ LC_ALL=C tr -d 'A-Za-z0-9+/=' < stdout | wc -c     -> 0
last byte: 6.5.0 = 46 ('F'); 7.6.1/8.4.1/9.2.0 = 3d ('=')
base64.b64decode(raw, validate=True)                 -> succeeds on all four
```

Sizes: 470612→352959 (6.5.0), 509000→381748 (7.6.1), 573992→430493 (8.4.1), 583516→437636 (9.2.0) bytes.

### 4.3 stdout and stderr must stay separate

Everything non-base64 goes to stderr and would corrupt a merged stream: `Starting local Bazel server (8.4.1) and connecting to it...`, the batch-mode `WARNING:`, `OpenJDK 64-Bit Server VM warning: Options -Xverify:none and -noverify were deprecated...`, `INFO: Reading rc options for 'help' from ...`, `WARNING: Running Bazel server needs to be killed...`, `Another command (pid=...) is running...`.

### 4.4 Schema is sound; field population is version-tiered

Scanner: `/Users/holtherndon/.claude/jobs/82be5deb/tmp/gt-flags-proto/scan.py` (no `protoc`, no python protobuf on the machine).

```
$ python3 scan.py out/ws-<v>.stdout <v>
6.5.0: flag_infos=992  top-level {(1,2): 992}  schema violations: 0
7.6.1: flag_infos=994  8.4.1: flag_infos=1044  9.2.0: flag_infos=1079   (0 violations, 0 duplicate names)
```

| FlagInfo field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| 1–4, 6, 7, 9 (name, has_negative_flag, documentation, commands, allows_multiple, effect_tags, documentation_category) | present (992/992) | present | present | present |
| 5 | 7/992 | sparse | sparse | sparse |
| 8 (metadata_tags) | 249/992 | sparse | sparse | sparse |
| 10 `requires_value` | **absent (0)** | 994/994 | 1044 | 1079 |
| 11 `old_name` | **absent** | **absent** | 66 | 65 |
| 12 `deprecation_warning` | **absent** | **absent** | 22 | 20 |
| 13 `default_value` | **absent** | **absent** | 816/1044 | 850/1079 |
| 14 `option_expansions` | **absent** | **absent** | 19 | 19 |
| 15 `type_converter` | **absent** | **absent** | 1044 | 1079 |
| 16 `enum_values` | **absent** | **absent** | 56 | 56 |

Fields 7/8/9 are **LEN-delimited strings** carrying enum constant names, not proto enums:

```
flag=bes_backend   f7 wt=LEN 'AFFECTS_OUTPUTS'   f9 wt=LEN 'LOGGING'
flag=output        f7 wt=LEN 'TERMINAL_OUTPUT'   f9 wt=LEN 'QUERY'
```

Declaring them as typed enums would be a wire-type mismatch.

### 4.5 What the dump can and cannot answer

All six BES/BEP flags carry `build` in `commands` on all four versions: `bes_backend`, `bes_lifecycle_events`, `bes_upload_mode`, `build_event_binary_file`, `build_event_json_file`, `build_event_publish_all_actions` (plus `bes_results_url`, `bes_timeout`, `build_event_text_file`, `build_event_binary_file_path_conversion`). `has_negative_flag=1` for `bes_lifecycle_events` and `build_event_publish_all_actions`; `=0` for `bes_backend`, `build_event_binary_file`; `allows_multiple=0` for all six on all versions.

The `commands` list itself is not stable: 6.5.0 has 22, 7.6.1/8.4.1 add `vendor` (23), 9.2.0 has 21 and drops `analyze-profile` and `sync`.

**The dump cannot answer `--output=proto`.** Exactly one FlagInfo named `output` per version, `commands=['aquery','config','cquery','mod','query']`, `enum_values(16)=[]` on **every** version including 8.4.1/9.2.0 where 56 other flags populate it (Bazel models it as `type_converter='String' default_value='text'`). Values live only in free text (field 3), and that text describes aquery only while `commands` is the union across five commands. By contrast real enums do populate: `bes_upload_mode conv=BesUploadMode enum_values=['WAIT_FOR_UPLOAD_COMPLETE','NOWAIT_FOR_UPLOAD_COMPLETE','FULLY_ASYNC']`.

Execution probes were needed instead:

```
$ USE_BAZEL_VERSION=<v> bazelisk aquery --output=proto //:empty        exit=0 on all four
$ ... --output=streamed_proto //:empty
  7.6.1/8.4.1/9.2.0 exit=0 ; 6.5.0 exit=2
  ERROR: Invalid output format 'streamed_proto'. Valid values are: proto, textproto, jsonproto, text, summary
$ bazelisk cquery --output=bogusfmt //:empty
  6.5.0: Valid values are: label_kind, label, transitions, proto, textproto, jsonproto, build, graph, starlark, files
  9.2.0: Valid values are: label_kind, label, transitions, proto, streamed_proto, textproto, jsonproto, build, graph, starlark, files
$ bazelisk query --output=bogusfmt
  6.5.0 and 9.2.0: ... proto, streamed_jsonproto, streamed_proto
```

`streamed_proto` rollout was **per command**, not global: already on `query` at 6.5.0, absent from `aquery` at 6.5.0, added to `cquery` between 6.5.0 and 9.2.0.

### 4.6 Three hazards of probing inside the user's workspace

**(a) A hostile rc kills the probe.** `.bazelrc` containing `common --another_bogus_flag=1`:

```
$ bazelisk help flags-as-proto      exit=2  stdout_bytes=0   (6.5.0, 8.4.1, 9.2.0)
ERROR: --another_bogus_flag=1 :: Unrecognized option: --another_bogus_flag=1
$ bazelisk --ignore_all_rc_files help flags-as-proto
exit=0 on all four, and cmp -> IDENTICAL to clean-workspace output
```

(`build --this_flag_does_not_exist` was harmless — `help` does not inherit `build` options; only `common` reaches it.) The dump is provably rc-independent, so nothing is lost by probing outside the workspace.

**(b) `--ignore_all_rc_files` inside a workspace destroys the user's warm server.** `.bazelrc` with only `startup --host_jvm_args=-Dprobe=1`:

```
8.4.1, probe WITH --ignore_all_rc_files:
WARNING: Running Bazel server needs to be killed, because the following startup options are different:
  - Only in old server: --host_jvm_args=-Dprobe=1 -Dprobe=1
  - Only in new server:
Starting local Bazel server (8.4.1) and connecting to it...
...and the user's very next normal command kills the probe's server, in the other direction.
```

6.5.0/7.6.1 emit one terser line; 8.4.1/9.2.0 itemize. Confirmed 6.5.0, 8.4.1, 9.2.0.

**(c) An in-workspace probe blocks on the command lock.** With a 25 s build in flight on 8.4.1:

```
in-workspace probe : exit=0 elapsed=20.48s bytes=573992
                     stderr: Another command (pid=71986) is running. Waiting for it to complete on the server (server_pid=71991)...
non-workspace probe: exit=0 elapsed=0.88s  bytes=573992  (cmp -> IDENTICAL to reference output)
```

### 4.7 Cost

| Mode | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| Batch (non-workspace), 5 runs | 0.69/0.68/0.68/0.66/0.68 s | 0.69/0.67/0.68/0.66/0.66 s | 0.74/0.69/0.70/0.71/0.85 s | 0.71/0.68/0.69/0.67/0.68 s |
| Server, cold / warm | 0.89 / ~0.06 s | 1.45 / ~0.07 s | 1.57 / ~0.07 s | 0.80 / ~0.07 s |

The server mode leaves a JVM alive with `--max_idle_secs=10800` (3 hours), visible in the spawned `bazel(ws)` process's `ps` line.

### 4.8 Two value-encoding traps

`default_value` can be present-but-empty vs entirely absent — on 9.2.0 `bes_backend` has `f13 wt=LEN ''` while `config` has no field 13 at all. And `enum_values` are UPPERCASE while `default_value` is lowercase (`enum_values=['WAIT_FOR_UPLOAD_COMPLETE',...] default='wait_for_upload_complete'`), though the CLI accepts either:

```
$ USE_BAZEL_VERSION=8.4.1 bazelisk canonicalize-flags -- --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE   exit=0
$ ... --bes_upload_mode=wait_for_upload_complete                                                       exit=0
```

Startup-only options are emitted with `commands=['startup']` exactly (`output_base`, `batch`, `host_jvm_args`, `max_idle_secs` on all four; `expand_configs_in_place` exists on 6.5.0/7.6.1, gone by 8.4.1).

---

## 5. BES: transport, failure modes, and the embedded server contract

### 5.1 Failure-mode matrix

All rows: trivial workspace, build itself succeeds in ~0.05 s, default flags unless stated.

| Server behaviour | Exit | Wall | User-visible | Versions |
|---|---|---|---|---|
| Nothing listening (dead port) | 38 | 16.00–16.03 s | `ERROR: The Build Event Protocol upload failed: All 4 retry attempts failed. UNAVAILABLE: Connection refused UNAVAILABLE: Connection refused` | identical on all four |
| TCP accepts, never speaks HTTP/2 | 38 | 90.90–90.93 s | `... All 4 retry attempts failed. DEADLINE_EXCEEDED: ... deadline exceeded after 14.999440541s. [closed=[], open=[[buffered_nanos=15002397500, waiting_for_connection]]]` | all four; 8.4.1/9.2.0 add `CallOptions deadline exceeded after 14.999883666s. Name resolution delay 0.000000000 seconds.` |
| Real gRPC, drains stream, **never ACKs** | **hang** (137 at harness cap) | >150 s, >240 s, >400 s observed | nothing on stderr after `INFO: Build completed successfully`; on a TTY only `Waiting for build events upload: Build Event Service 28s` | all four hang |
| ACKs first 5, then stalls | **hang** (137 at cap) | 93.36 s at a 90 s cap | same | 7.6.1 measured; same code path as above |
| ACKs 5 then closes stream OK | 45 | 1.16–1.23 s | `ERROR: ... Server closed stream with status OK but not all ACKs have been received (ackQueue=27) FAILED_PRECONDITION:` | all four (ackQueue=27 on 6.5.0/7.6.1, 29 on 8.4.1/9.2.0) |
| ACKs with `sequence_number=0` | **37 (crash)** on 6.5.0, 9.2.0; 45 on 7.6.1; 38 on 8.4.1 | — | see below | deterministic 3/3 per version |
| Scheme-less `--bes_backend=127.0.0.1:41333` | 38 | 16.15 s | `... UNAVAILABLE: not an SSL/TLS record: 000018040000000000000400400000000500400000000600004000fe03...` | 7.6.1 measured; help text identical on all four |

Commands:

```
$ cd <scratch>/ws-7.6.1 && USE_BAZEL_VERSION=7.6.1 ~/.cache/bbv-dev/bin/bazelisk build //... --bes_backend=grpc://127.0.0.1:41111   # dead port
$ python3 blackhole.py 41222 hold   # accept, read, never write
$ USE_BAZEL_VERSION=7.6.1 bazelisk build //... --bes_backend=grpc://127.0.0.1:41222
$ USE_BAZEL_VERSION=7.6.1 bazelisk build //... --bes_backend=grpc://127.0.0.1:41444   # grpcio 1.83.0, drains and never yields
$ USE_BAZEL_VERSION=<v> bazelisk build //... --bes_backend=grpc://127.0.0.1:41777      # acks with seq 0
```

The blackhole log proves **one TCP connection per invocation**, reused by all 4 gRPC retries, never past the preface:

```
2026-08-22T08:24:15.251659 ACCEPT ('127.0.0.1', 65256)
2026-08-22T08:24:15.252062 RECV ... b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n\x00\x00\x18\x04\x00...'
(4 ACCEPT lines total, one per version run)
```

The wrong-ACK crash on 9.2.0:

```
FATAL: bazel crashed due to an internal error. Printing stack trace:
java.lang.IllegalStateException: Stream was terminated by error, no further calls are allowed
	at com.google.devtools.build.lib.buildeventservice.client.BuildEventServiceGrpcClient$BESGrpcStreamContext.sendOverStream(BuildEventServiceGrpcClient.java:158)
Server terminated abruptly (error code: 14, error message: 'recvmsg:Connection reset by peer', log file: '.../822adffb.../server/jvm.out')
```

6.5.0 is the same exception at `BuildEventServiceGrpcClient.java:166`. 7.6.1/8.4.1 instead print `ERROR: The Build Event Protocol upload failed: Not retrying publishBuildEvents: status='Status{code=FAILED_PRECONDITION, description=Expected ACK with seqNum=1 but received ACK with seqNum=0, cause=null}'`.

### 5.2 `--bes_timeout` is the only bound, and it is off by default

```
$ USE_BAZEL_VERSION=<v> bazelisk help build --long | grep -A6 '^  --bes_timeout'
--bes_timeout (An immutable length of time.; default: "0s")
...The default value is '0' which means that there is no timeout.      # identical on all four
```

| Scenario | `--bes_timeout` | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|---|
| blackhole | 5s | 6.55 s | 6.22 s | 6.28 s | 6.21 s |
| blackhole | 30s | — | 30.09 s | — | — |
| dead port | 3s | — | 4.19 s | — | — |
| never-ACKing gRPC | 20s | 21.19 s | 21.27 s | 21.29 s | 21.22 s |

All exit 38 with `ERROR: The Build Event Protocol upload timed out. com.google.common.util.concurrent.TimeoutFuture$TimeoutFutureException: Timed out: NonCancellationPropagatingFuture@...[status=PENDING, ...]`. Wall = requested timeout + ~1.2 s of build/startup.

### 5.3 `--bes_upload_mode` — the escape valve

Default is `wait_for_upload_complete` on all four (help text identical on 7.6.1/8.4.1/9.2.0). **6.5.0 does not list the flag in `bazel help build --long` at all** (`grep '^  --bes'` returns only backend/header/instance_name/keywords/oom_finish_upload_timeout/outerr_buffer_size/outerr_chunk_size/proxy/results_url/timeout) yet accepts it and behaves the same.

Against the never-ACKing server, two consecutive invocations A then B:

| Version | mode | A | B |
|---|---|---|---|
| 6.5.0 | nowait | exit 0, 1.29 s | exit 0, 5.13 s + WARNING `...5.011 seconds...` |
| 7.6.1 | nowait | exit 0, 1.22 s | exit 0, 5.12 s + `WARNING: The background upload of the Build Event Protocol for the previous invocation failed to complete in 5.009 seconds. Cancelling and starting a new invocation...` |
| 8.4.1 | nowait | exit 0, 1.24 s | exit 0, 5.13 s + `...5.014 seconds...` |
| 9.2.0 | nowait | exit 0, 1.21 s | exit 0, 5.11 s + `...5.007 seconds...` |
| 7.6.1 | fully_async | exit 0, 1.21 s | exit 0, **0.09 s** (events accepted, just not acked) |
| 7.6.1 | fully_async vs dead port / blackhole | exit 0, 1.21 / 1.25 s | exit 0, 5.09 / 5.12 s + same WARNING |

Invalid values are rejected at parse time, byte-identically on all four:

```
$ USE_BAZEL_VERSION=<v> bazelisk build //... --bes_upload_mode=bogus
ERROR: While parsing option --bes_upload_mode=bogus: Not a valid Mode for uploading to the Build Event Service: 'bogus' (should be wait_for_upload_complete, nowait_for_upload_complete or fully_async)
exit=2
```

### 5.4 Wire sequence and ACK encoding

Server log from a working acking run on 7.6.1:

```
PublishLifecycleEvent 105 bytes -> Empty
PublishLifecycleEvent 145 bytes -> Empty
PublishBuildToolEventStream OPENED
req#1 seq=1 bytes=537 top=[(4, 495), (5, 18), (5, 17)] inner=[(1, 78), (2, 1), (3, 410)]
req#2 seq=2 ... inner=[(1, 78), (2, 2), (3, 77)]
request_iterator EXHAUSTED after 32 requests (client half-closed)
PublishLifecycleEvent 108 bytes -> Empty
PublishLifecycleEvent 109 bytes -> Empty
```

The sequence number lives at `PublishBuildToolEventStreamRequest` **field 4** → `OrderedBuildEvent` **field 2**. Reading it from field 1 always yielded 0 and triggered `FAILED_PRECONDITION: Expected ACK with seqNum=1 but received ACK with seqNum=0`; switching to field 4 gave `EXIT=0 WALL=0.08s`. ACK message = field 1: raw StreamId bytes echoed back verbatim, field 2: varint sequence_number — accepted by all four. Request counts: 32 on 6.5.0/7.6.1, 34 on 8.4.1/9.2.0 for the same 3-genrule workspace.

Note the **two trailing `PublishLifecycleEvent` unary calls after the stream closes**.

### 5.5 `--bes_backend` alone is sufficient

```
$ bazelisk build //... --bes_backend=grpc://127.0.0.1:41333        # working acking server
VERSION=7.6.1 EXIT=0 WALL=1.23s, INFO: Build completed successfully — no complaint about instance name or project id
$ ... --bes_results_url=http://localhost:9999/invocation/
EXIT=0, stderr gains twice (start and end):
INFO: Streaming build results to: http://localhost:9999/invocation/d6cda428-bcf3-4bcd-bf76-4a2d319a14c2
```

Help text on all four: `Supported schemes are grpc and grpcs (grpc with TLS enabled). If no scheme is provided, Bazel assumes grpcs.` The UUID is concatenated directly onto whatever string is passed, so `--bes_results_url` must end in a separator.

### 5.6 A BES failure masks the build's exit code

Genrule with `cmd = exit 1`:

```
$ bazelisk build //failpkg:f                                          EXIT=1 on all four
   ERROR: ... Executing genrule //failpkg:f failed: (Exit 1) / ERROR: Build did NOT complete successfully
$ bazelisk build //failpkg:f --bes_backend=grpc://127.0.0.1:41111     EXIT=38 on all four, BOTH errors present
```

### 5.7 A BES stall wedges the workspace beyond the invocation

After SIGKILLing the client of a hung no-ACK run, the next invocation in that workspace printed only:

```
Another command (pid=80793) is running. Waiting for it to complete on the server (server_pid=66406)...
```

and never built (all four versions — this is what invalidated a first attempt at the `--bes_timeout` experiment). When the never-acking python server was killed, the **orphaned server-side upload reconnected to a freshly started server on the same port**: three `PublishBuildToolEventStream OPENED` within 1 ms of the new server coming up at 08:55:57, replaying 32–34 requests each.

On a hung 7.6.1 run, two SIGTERMs printed `Bazel caught terminate signal; cancelling pending invocation.` but the process was still alive at `ps -p 74101 -o etime` → `06:54`; a SIGINT ended it (`GONE after SIGINT`). **This contradicts §6.2 — see §8.1.**

---

## 6. Process model, signals, and cancellation

> Measured **without** a BES backend attached. See §8.2.

### 6.1 The server is not a descendant of the client

```
$ ps -ax -o pid,ppid,pgid,stat,command    # during a 7.6.1 build
--- client ---            74321 74308 74321 SN   .../sha256/45cca.../bin/bazel
--- children of client -- (empty)
--- server -------------  74318     1 74318 RNs  bazel(ws-7.6.1)
--- action wrappers -----  74343 74318 74318 .../process-wrapper --timeout=0 --kill_delay=15
                           74350 74318 74318 .../process-wrapper --timeout=0 --kill_delay=15
```

Identical on 9.2.0 (`66951 1 66951 SNs`, actions `71372 66951`), 6.5.0 (`78192 1`, actions `78337 78192`), 8.4.1 (`78198 1`, actions `78402 78198`).

Cold start opens no window: polling every 20 ms from spawn with no server running, the server is already at PPID 1 at the first sample — `t+ 0.04s children_of_client=[] ['server 75175 ppid=1']`, unchanged for 25 s (7.6.1 and 9.2.0).

Bazelisk `exec()`s the real binary, so the spawned PID **is** the client: the launch command was `bazelisk ... build //:all_out` but `ps -p $!` reports `/Users/holtherndon/Library/Caches/bazelisk/downloads/sha256/45cca.../bin/bazel`, and `pgrep -P 74321` is empty.

Process groups: under job control the client's PGID is its own PID; without it (`nojobctl.sh`, no `set -m`) `client_pid=81307 pgid=81302 parent_shell_pgid=81302` — the client shares the launcher's group, so `kill(-pgid)` would signal the runner itself. SIGINT still worked in that configuration (`sent SIGINT rc=0 / RESULT: client exited / final_exit=8`), so no job-control setup is required.

### 6.2 Signal behaviour, mid-build (t=14 s)

| Signal | Exit | Drain | Banner | BEP |
|---|---|---|---|---|
| SIGINT, 7.6.1 | 8 | 0.027 s | `Bazel caught interrupt signal; cancelling pending invocation.` | `size=24096 records=21 truncation=NONE`, BuildFinished=True, last_message=True |
| SIGINT, 9.2.0 | 8 | 0.063 s | same | `size=33209 records=25 truncation=NONE` |
| SIGINT, 6.5.0 | 8 | — | **`Bazel caught interrupt signal; shutting down.`** (misleading — the server did not shut down) | `size=24261 records=21 truncation=NONE` |
| SIGINT, 8.4.1 | 8 | — | `...cancelling pending invocation.` | `size=29190 records=47 truncation=NONE` |
| SIGTERM, 7.6.1 | 8 | 0.027 s | `Bazel caught terminate signal; cancelling pending invocation.` | `size=24064 records=21 truncation=NONE` |
| SIGTERM, 9.2.0 | 8 | 0.031 s | same | `size=32516 records=23 truncation=NONE` |
| SIGTERM, 8.4.1 | 8 | — | same | `size=24857 records=23` |
| SIGTERM, 6.5.0 | 8 | — | **`Bazel caught terminate signal; shutting down.`** | `size=24191 records=21` |
| SIGKILL, 7.6.1 | 137 | ~2.5 s server-side | — | grows from 22336 to 24323 bytes **after** the client dies |
| SIGKILL, 9.2.0 | 137 | ~2.1 s | — | 28839 → 31691 bytes |

BEP scan tool: `/Users/holtherndon/.claude/jobs/82be5deb/tmp/gt-cancellation/beplist.py` (schema-free length-delimited record walker). Interrupted BEP record #16 strings: `['INTERRUPTED', 'build interrupted']`; record #19: `'Multiple abort reasons reported: [USER_INTERRUPTED, INCOMPLETE]'`.

**Escalation ladders are dead code on the happy path.** `ladder.sh 7.6.1` with spec `INT@0,TERM@0.15,KILL@0.4`:

```
sent SIGINT at t+0.000s
SIGTERM at t+0.155s: process already gone
SIGKILL at t+0.404s: process already gone
exit_code=8
```

A second SIGINT 13 ms later also missed.

### 6.3 BEP flush timing

`flushcheck.sh` busy-waits on `ps -p <client>` at 5 ms resolution:

```
7.6.1: sent SIGINT at t+0.000s / client gone at t+0.022s
       AT_CLIENT_EXIT (+0ms): bytes=24067 records=21 BuildFinished=True last_message=True truncation=NONE
       t+250ms: bytes=24067 ... / t+5s: bytes=24067 ... / wait_exit=8
9.2.0: client gone at t+0.023s / AT_CLIENT_EXIT: bytes=31526 records=23 BuildFinished=True last_message=True
```

After SIGINT/SIGTERM the file is final at client exit — no settle delay. After SIGKILL it is not (`run_kill2.sh`, 100 ms polling, times relative to the kill):

```
7.6.1 (client_wait_status=137):
t+  0.05s  action_processes=2  bep_bytes=22336  server_alive=True
t+  2.52s  action_processes=0  bep_bytes=24323  server_alive=True
t+ 20.12s  FINAL action_processes=0 bep_bytes=24323 server_alive=True
server-side command.log: ERROR: build interrupted / INFO: Elapsed time: 16.566s   (client killed at 14.1 s)
```

The server reaps the action processes on its own; SIGKILL only costs the ability to know when the file is final.

### 6.4 Lock release

`lockrelease.sh` — SIGKILL at t=14 s, then `bazel --noblock_for_lock info server_pid` in a loop:

```
7.6.1 (dead client pid 78473):
t+0.06s rc=9 out=Another command (pid=78473) is running. Exiting immediately.|
t+0.66s rc=9 / t+1.28s rc=9 / t+1.88s rc=9 / t+2.49s rc=0 out=75175
9.2.0: rc=9 until t+2.5s rc=0 out=76478 ; 6.5.0: rc=9 until t+2.42s ; 8.4.1: rc=9 until t+2.43s
After SIGINT instead: t+0.07s rc=0 on all four (7.6.1 out=75175, 9.2.0 out=76478, 8.4.1 out=78198, 6.5.0 out=78192)
```

The holder PID in the lock message can be a **dead process**. The unchanged `server_pid` across probes confirms the server survives a client SIGKILL.

A second concurrent invocation blocks with a stable, parseable message:

```
7.6.1: Another command (pid=72751) is running. Waiting for it to complete on the server (server_pid=66930)...
       SIGINT to that lock-blocked client -> B1_exit=8
       --noblock_for_lock -> B2_noblock_exit=9 B2_wall=0.03, "Another command (pid=72751) is running. Exiting immediately."
9.2.0: byte-identical shape (pids 72889 / 66951)
$ bazel help startup_options -> --[no]block_for_lock (a boolean; default: "true")   # 7.6.1 and 9.2.0
```

### 6.5 The early-signal race — the first ~5–50 ms (warm) / ~0.5 s (cold) are unreliable

`early9.sh`, 4 runs per delay, warm server, SIGINT:

| Version | delay | outcomes |
|---|---|---|
| 9.2.0 | 0.005 s | 4/4 `exit=130 bep=NOFILE` |
| 9.2.0 | 0.02 s | `exit=37 bep=152`, `exit=130 NOFILE`, **`exit=0 bep=34251` (signal lost, full successful build)**, `exit=130 NOFILE` |
| 9.2.0 | 0.05 s | 4/4 `exit=8`, complete BEP |
| 7.6.1 | 0.005 s | 4/4 `exit=130 bep=NOFILE` |
| 7.6.1 | 0.02 s | 3× `exit=8` (tiny BEP) + 1× `exit=130 NOFILE` |
| 7.6.1 | 0.05 s | 4/4 `exit=8` |

The 9.2.0 exit-37 run's full stderr:

```
Bazel caught interrupt signal; cancelling pending invocation.
ERROR: could not acquire lock on repo contents cache
Server terminated abruptly (error code: 14, error message: 'recvmsg:Connection reset by peer', log file: '/private/var/tmp/bbvgt9/3675cf8a945a4b96f6b4ae2a853fcbff/server/jvm.out')
```

The next invocation reported `server_pids_before=[]` — the server was gone. The repo contents cache is a Bazel 8/9 feature; not reproduced on 7.6.1.

Cold start (7.6.1, `coldint.sh`): 0.05 s → `exit=130`, no BEP; 0.3 s → 130, no BEP; 0.5 s → 130, no BEP; 0.7 s → `exit=8`, BEP present but `BuildFinished=False`; 1.0 s+ → `exit=8` complete. Every cold case left a fresh orphan server (`servers_after=[79423]`, `ps -p 79423 -o ppid=` → 1) which the next invocation reused.

### 6.6 Interrupt during loading/analysis: a valid BEP with **no** BuildFinished

```
7.6.1, SIGINT at t=0.05 s (warm), exit 8, stderr: ERROR: command interrupted while computing main repo mapping
size=1011 records=8 truncation=NONE (ends on record boundary)
SUMMARY: BuildFinished(field14)=False last_message(field20=1)=True truncated=False
final record #7 len=11 fields=[(1,2,4),(3,2,0),(20,0,1)]   # empty Progress with last_message set
```

Reproduced with `ERROR: command interrupted while syncing package loading` (size=977) and cold at 0.7 s (`size=973 records=8`). On 9.2.0 the exit-37 case gave `size=152 records=2`, BuildStarted + one Progress carrying `ERROR: could not acquire lock on repo contents cache`, `last_message=1`, `BuildFinished=False`.

### 6.7 `bazel shutdown` after a force-kill

```
--- probe: --noblock_for_lock info server_pid ---   74318 / noblock_info_rc=0
--- bazel shutdown ---                              shutdown_rc=0 shutdown_seconds=1.03
server_pid 74318 after shutdown:                    (empty = gone)
```

9.2.0: `shutdown_rc=0 shutdown_seconds=1.04`. Final cleanup: rc=0 on all four, then `pgrep -f 'output_base=/private/var/tmp/bbvgt'` returned nothing. It must be issued **after** the ~2.5 s lock window, and it destroys the shared warm server.

---

## 7. BEP content and parsing semantics

> Measured on **6.5.0, 7.6.1, 9.2.0 only** — 8.4.1 was never exercised for BEP content. Workspace: 20 genrules in `//pkg` (g00–g04 leaf, g05–g19 chained) plus `//fail:boom` whose cmd is `echo 'about to fail' >&2 ; exit 3`. Raw captures: `/Users/holtherndon/.claude/jobs/82be5deb/tmp/gt-bep-content/out-{6.5.0,7.6.1,9.2.0}/`.

### 7.1 Successful actions are invisible without `--build_event_publish_all_actions`

```
$ USE_BAZEL_VERSION=$V bazelisk clean && bazelisk build //pkg/... --build_event_json_file=out-$V/A.json
=== 6.5.0 / 7.6.1 / 9.2.0 A.json (NO flag) ===
  action payload events in stream: 0
  buildMetrics.actionSummary.actionsExecuted: 21
  per-mnemonic: [('Genrule', '20'), ('BazelWorkspaceStatusAction', '1')]
```

With the flag, exactly +21 action, +22 progress, +1 configuration = **+44 events**:

| Version | no flag | with flag |
|---|---|---|
| 6.5.0 | 68452 B / 99 events | 91182 B / 143 events (+22730 B, ×1.332) |
| 7.6.1 | 68720 B / 99 events | 91200 B / 143 events (+22480 B, ×1.327) |
| 9.2.0 | 77135 B / 101 events | 101962 B / 145 events (+24827 B, ×1.322) |

Per published action: 1082 B (6.5.0), 1070 B (7.6.1), 1182 B (9.2.0). **The action payload embeds the full `commandLine` array** — here `["/bin/bash","-c","source external/bazel_tools/tools/genrule/genrule-setup.sh; echo 'about to fail' >&2 ; exit 3"]`, ~110 chars. A javac or C++ command line is commonly 10–100× longer, so the +33 % figure does not generalize.

The 21st action is label-less on 6.5.0/7.6.1:

```
{"id":{"actionCompleted":{"primaryOutput":"bazel-out/stable-status.txt","configuration":{"id":"system"}}},
 "action":{"type":"BazelWorkspaceStatusAction","success":true, ...}}   # keys: configuration, primaryOutput, success, type
```

On 9.2.0 it additionally carries `label "@@bazel_tools//tools:internal_platform"`.

**Failed actions are always published**, flag or not: the failing build with no event flags emitted exactly 1 action event (`out-6.5.0/C.json` 73 events / action 1; 7.6.1 same; 9.2.0 71 events / action 1).

**Incremental rebuilds publish almost nothing**, even with the flag:

| Version | E1 (after clean) | E2 (no change) |
|---|---|---|
| 6.5.0 | 143 events, 21 action, actionsExecuted=21 | 103 events, **1** action, actionsExecuted=1 |
| 7.6.1 | 143 / 21 / 21 | 103 / 1 / 1 |
| 9.2.0 | 145 / 21 / 21 | 105 / 1 / 1 |

The survivor is always `BazelWorkspaceStatusAction, success=true`.

Flag spelling is stable: `$ bazelisk help build | grep -A3 build_event_publish_all_actions` → `--[no]build_event_publish_all_actions (a boolean; default: "false")` on 6.5.0, 7.6.1, 9.2.0.

### 7.2 Stream boundaries — `lastMessage` is the only reliable terminator

First event is always `{"id":{"started":{}}, "children":[...], "started":{...}}` with children `[progress, unstructuredCommandLine, structuredCommandLine×3, buildMetadata, optionsParsed, workspaceStatus, pattern, buildFinished]`.

| Version | Last payload | Tail order |
|---|---|---|
| 6.5.0 | `buildToolLogs` | 96 finished, 97 buildMetrics, 98 progress, 99 buildToolLogs(lastMessage) |
| 7.6.1 | `buildToolLogs` | same |
| 9.2.0 | **`buildMetrics`** | 98 finished, 99 progress, 100 buildToolLogs, 101 buildMetrics(lastMessage) |

Stable over 6 repeated clean+build runs per version (`rep-$V/r1..r6.json`): 6/6 each way. All 12 A/B/C/D files: `lastMessage_occurrences=1`, always the final line.

### 7.3 Ordering guarantees that do not hold

**Announce-before-arrive fails in ~17 % of streams** (4 of 24 files, all three versions):

```
rep-9.2.0/r3.json  late=1  [(81, '{"progress": {"opaqueCount": 19}}', 'firstAnnouncedLine=82')]
out-6.5.0/A.json   late=1  [(37, '{"progress": {"opaqueCount": 5}}',  'firstAnnouncedLine=38')]
out-7.6.1/A.json   late=1  [(37, ... same ...)]
out-6.5.0/B.json   late=2  [(40, ...), (115, '{"progress": {"opaqueCount": 36}}', 'firstAnnouncedLine=116')]
files=24  files_with_violation=4
```

The inversion in `out-6.5.0/A.json` lines 36–39: the progress chain is written in file order 3, 5, 4, 6 — progress/5 precedes the progress/4 that announces it. Not tied to `--build_event_publish_all_actions` (seen with and without). Normal long-distance announcement still works (7.6.1 line 33 announces `targetCompleted //pkg:g19`, which arrives at line 92).

**A child can be announced by more than one parent, after it already arrived.** In every failing capture on all three versions, the failed action's id is announced twice:

```
46 id={"progress":{"opaqueCount":6}} children=[..., {"actionCompleted":{...//fail:boom...}}]
47 id={"actionCompleted":{...//fail:boom...}} payload=action        <-- arrives
54 id={"targetCompleted":{"label":"//fail:boom",...}} payload=completed
       children=[{"actionCompleted":{...//fail:boom...}}]           <-- announced AGAIN, 7 lines later
```

0 announced-but-never-arrived ids in every C.json and D.json across all three versions.

### 7.4 Failure is carried by four payloads under four field names

```
$ python3 fail.py out-7.6.1/C.json           # build //fail:boom //pkg/... , exit 1
line 50 payload=action    /action/exitCode = 1
                          /action/failureDetail/spawn/code = NON_ZERO_EXIT
                          /action/failureDetail/spawn/spawnExitCode = 3
                          /action/stderr = {"name":"stderr","uri":"file:///...bazel-out/_tmp/actions/stderr-3"}
line 54 payload=completed /completed/failureDetail/spawn/code = NON_ZERO_EXIT ; spawnExitCode = 3
line 55 payload=finished  /finished/exitCode/name = BUILD_FAILURE ; /finished/exitCode/code = 1 ; /finished/failureDetail/...
lines 58-72 payload=aborted (under targetCompleted ids)  /aborted/reason = INCOMPLETE
```

Aborted census: 6.5.0 `{('INCOMPLETE', has_description=False): 15}`, 7.6.1 same 15, 9.2.0 17 — all under `targetCompleted` ids.

**`action.exitCode` is 1 even though the spawn exited 3.** Bazel's own console says `Executing genrule //fail:boom failed: (Exit 3)`. The real code is only in `failureDetail.spawn.spawnExitCode`. Identical on all three versions.

Only the human-readable `message` differs: 6.5.0 `"from target //fail:boom"`, 7.6.1 `"from target"`, 9.2.0 `"from genrule rule target //fail:boom"` — do not parse it.

### 7.5 Proto3 zero-value omission

```
SUCCESS finished payload (7.6.1 A.json):
{"overallSuccess": true, "finishTimeMillis":"...", "exitCode": {"name": "SUCCESS"}, ...}   # exitCode.code ABSENT
FAILURE finished payload (7.6.1 C.json):
{"finishTimeMillis":..., "exitCode": {"name":"BUILD_FAILURE","code":1}, ..., "failureDetail":{...}}  # overallSuccess ABSENT
failing action keys:    ['exitCode','stderr','label','configuration','type','commandLine','failureDetail']   # no 'success'
succeeding action keys: ['commandLine','configuration','label','primaryOutput','success','type']
failing completed payload: {"failureDetail": {...}}   # nothing else
```

Identical on all three. `if (json.has("success") && !json.get("success"))` classifies every failure as "unknown".

### 7.6 Version-gated content

| Feature | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| `action.startTime` / `action.endTime` (RFC3339) | absent | absent | **unknown, not tested** | present (`"startTime": "2026-08-22T13:24:58.346653Z"`, `"endTime": "...364653Z"`) |
| `convenienceSymlinksIdentified` payload | absent | absent | **unknown, not tested** | present, 1 per build |
| Last payload in stream | buildToolLogs | buildToolLogs | **unknown** | buildMetrics |
| Workspace-status action label | none | none | **unknown** | `@@bazel_tools//tools:internal_platform` |

Genrule action field sets in B.json: 6.5.0 and 7.6.1 both `20 × ['commandLine','configuration','label','primaryOutput','success','type']`; 9.2.0 `20 × [... 'endTime', ... 'startTime', ...]`. Everything else in the payload vocabulary (aborted, action, buildMetadata, buildMetrics, buildToolLogs, completed, configuration, configured, expanded, finished, namedSetOfFiles, optionsParsed, progress, started, structuredCommandLine, unstructuredCommandLine, workspaceInfo, workspaceStatus) is identical across the three tested versions.

On 6.5.0/7.6.1 the only timing available is `buildMetrics.actionSummary.actionData[].firstStartedMs/lastEndedMs` (per-mnemonic aggregate) and the critical-path text in `buildToolLogs`.

### 7.7 Id kind and payload name disagree

| id kind | payload name(s) observed |
|---|---|
| `actionCompleted` | `action` |
| `buildFinished` | `finished` |
| `namedSet` | `namedSetOfFiles` |
| `pattern` | `expanded` |
| `workspace` | `workspaceInfo` |
| `targetCompleted` | **`completed` or `aborted`** |
| `targetConfigured` | `configured` |
| buildMetadata, buildMetrics, buildToolLogs, configuration, convenienceSymlinksIdentified, optionsParsed, progress, started, structuredCommandLine, unstructuredCommandLine, workspaceStatus | same name |

Identical mapping on all three versions.

### 7.8 Configuration joins are nullable in two directions

```
out-7.6.1/A.json  configuration ids = [8e4ab22e...(274 bytes), 096dcc84...(274 bytes)]
out-7.6.1/B.json  configuration ids = [8e4ab22e..., "none"(payload len 2 == "{}"), 096dcc84...]
workspace-status action: id says configuration "system"; payload says configuration "none"
No configuration event with id "system" exists in any capture.
```

Same on 6.5.0 and 9.2.0. So resolving via the id misses, and resolving via the payload lands on a configuration with no mnemonic, no platformName, no cpu.

### 7.9 Failing-build event counts are not reproducible

Same command, same workspace, same version, C = no flag, D = with flag:

| Version | C events / aborted / action | D events / aborted / action | D bytes vs C |
|---|---|---|---|
| 6.5.0 | 73 / 15 / 1 | 87 / 15 / 7 | 64408 vs 58360 |
| 7.6.1 | 73 / 15 / 1 | 71 / 19 / 3 | **56905 vs 58375 — smaller despite the flag** |
| 9.2.0 | 71 / 17 / 1 | 71 / 20 / 3 | 71112 vs 70542 |

The flag was identical between 6.5.0-D (7 actions, 15 aborted) and 7.6.1-D (3 actions, 19 aborted); only scheduling differed.

---

## 8. Contradictions and unresolved tensions

**8.1 SIGTERM: equivalent to SIGINT, or ineffective?** The cancellation experiment concludes "SIGTERM behaves identically to SIGINT — same exit code 8, same complete BEP" (§6.2, measured on all four at t=14 s of a build). The BES experiment reports the opposite on a BES-stalled invocation: "two SIGTERMs produced `Bazel caught terminate signal; cancelling pending invocation.` but the process was still alive at `ps -p 74101 -o etime` → `06:54`; a SIGINT ended it" (§5.7, 7.6.1). The plausible reconciliation is that the two probes hit different phases — SIGTERM during execution vs SIGTERM during the post-build BES drain — but neither experiment tested the other's condition, so this is unresolved. **Both findings stand.**

**8.2 After a client SIGKILL: does the server wind down in ~2.5 s, or keep going indefinitely?** §6.4 measures the lock released at t+2.42–2.50 s on all four, with the server cancelling the build and finishing the BEP. §5.7 reports that after SIGKILLing a BES-stalled client, "the server-side command keeps running and keeps retrying, so the next bazel command in that workspace blocks" — observed on all four, with the next invocation printing only the `Waiting for it to complete` line and never building. The cancellation runs had **no BES backend attached**; that is the likely discriminator, but it was not isolated. **Both findings stand**; the safe reading is that the ~2.5 s figure is a floor that applies only without BES.

**8.3 "Append instrumentation flags at the end" is asserted and refuted within the same experiment.** §2.5 concludes instrumentation flags "may be appended after the user's target patterns … which is the simplest safe splice point," while §2.4 proves that appending after a user-supplied `--` makes the flag a bogus negative target pattern, exit 1, **and no BEP file is written**. These are compatible only under the unstated precondition "no `--` in the user's argv." Treated as a contradiction because the first is stated unconditionally.

**8.4 The wrong-ACK crash: version difference or timing race?** §5.1 reports it as a version split (crash on 6.5.0/9.2.0, graceful on 7.6.1/8.4.1), deterministic 3/3 per version. The same experiment's own note observes the stack trace (`Stream was terminated by error, no further calls are allowed` inside `sendOverStream`) "looks race-shaped," so a larger build could flip 7.6.1/8.4.1 into crashing. The determinism evidence and the race hypothesis are in direct tension; the safe assumption is that a wrong ACK may crash the Bazel server on **any** version.

**8.5 6.5.0's interrupt banner is factually wrong.** 6.5.0 prints `Bazel caught interrupt signal; shutting down.` yet §6.4 shows the server surviving with an unchanged `server_pid`. Recorded here so nobody reconciles it by believing the banner.

---

## 9. Unverified

Nothing below was settled by any experiment. Merged and deduplicated across all five.

**9.1 Exit-code identity**
- Official symbolic names for exit codes 37, 38 and 45 — only the numbers and accompanying message text were observed; Bazel's `ExitCode` enum was never read. Do not encode guessed names.
- Exit code 143 for an unhandled SIGTERM is asserted by inference from the observed 130; it was never produced.

**9.2 BES mechanics**
- Whether the wrong-ACK crash split is a genuine version difference or a stable-looking race (see §8.4).
- Where the ~15 s per-attempt gRPC deadline and the 4-attempt retry count come from, and whether any flag controls them. Nothing in `bazel help build --long` obviously does, but the flag space was not exhaustively searched.
- Whether `--bes_timeout` affects the 5 s next-invocation wait imposed by `nowait_for_upload_complete` / `fully_async`. The 5 s cap was measured only at the default `--bes_timeout=0s`.
- Whether `--bes_lifecycle_events` changes observable BES traffic. Precedence for it was established only through `canonicalize-flags`, never against a live backend.
- `grpcs://` against a server presenting a real TLS certificate. Only the scheme-less "assumes grpcs and fails" path was observed.
- Behaviour when the BES server dies or stalls in the **middle** of a long build. Every build completed in ~0.05 s, so all failure modes were exercised in the post-build drain phase only.
- Whether `--bes_oom_finish_upload_timeout` (default `10m` on all four) interacts with any of these paths. Never exercised.
- Behaviour under `--keep_going`, and BES behaviour for `bazel test` rather than `bazel build`.

**9.3 Command-line grammar**
- Whether 6.5.0 and 8.4.1 share the 7.6.1/9.2.0 grammar (startup/command split, last-wins, `--` residue mode, interleaving). Those two versions were exercised only for workspace markers and `--noblock_for_lock` placement.
- Whether last-wins holds across **different sources** (`.bazelrc` vs command line, multiple rc files, `--config` expansions). All precedence tests used a single command line; rc precedence is a separate mechanism and must be tested before "append always wins" is relied on.
- `--` handling for commands other than `build` and `run` (`test`, `query`, `cquery`, `coverage`). `bazel test --keep_going //:gen_a` was run only far enough to observe exit 4.
- Whether native `sh_binary` works on 8.4.1, and whether `--incompatible_autoload_externally` restores it on 9.2.0.

**9.4 Workspace discovery**
- Marker file that is a directory, a broken symlink, or unreadable — only zero-byte regular files were tested.
- Which marker wins when several exist at **different depths** (e.g. `MODULE.bazel` at the repo root and `WORKSPACE` in a subdirectory) — i.e. whether discovery stops at the first or the highest marker.

**9.5 Cancellation and process model**
- Whether 9.2.0 still writes a server-side `command.log`. `tail /private/var/tmp/bbvgt9/<output_base>/command.log` → "No such file or directory" on 9.2.0 while the same path had content on 7.6.1, whose server command line contained `--write_command_log`; 9.2.0's was not captured. Do not rely on `command.log` as an observation channel on Bazel 9 without checking.
- Root cause of the 9.2.0 exit-37 server death. `/private/var/tmp/bbvgt9/<output_base>/server/jvm.out` was never read; whether it is a crash, an intentional abort, or specific to a server that has never served a command is unknown.
- Exact width of the early-signal race window per version. Sampled at 0.005/0.02/0.05 s warm and 0.05/0.3/0.5/0.7/1.0/1.5/2.0/3.0 s cold, 4 runs per point. Machine- and cache-speed dependent; not a portable constant.
- What `kill(-pgid)` actually does. PGIDs were recorded and the conclusion drawn from them; the signal was never sent.
- Whether killing the server directly (SIGTERM/SIGKILL to the server PID) leaves recoverable state. Not tested — it would have disturbed shared output bases.

**9.6 BEP content**
- **8.4.1 was not exercised at all.** Every "all three versions" claim in §7 covers 6.5.0, 7.6.1 and 9.2.0 only. The introduction points of `action.startTime`/`endTime`, of `convenienceSymlinksIdentified`, and of the buildToolLogs→buildMetrics terminator swap are therefore unknown — all could have landed in 8.x.
- Whether an action that is a **cache hit** (local action cache or `--disk_cache`) produces an `action` event with the flag. The incremental run proved up-to-date actions produce nothing, but that is Skyframe short-circuiting before execution, a different code path. No disk cache was configured.
- Whether `--build_event_publish_all_actions` behaves the same over gRPC BES as over `--build_event_json_file`. Only the JSON file sink was exercised; BES could filter or batch differently.
- Whether the announce-before-arrive inversion also occurs over BES streaming, or is an artefact of the JSON file writer's flushing. All four observed cases involved `progress` events, which is consistent with a flush interleaving that was never isolated.
- Behaviour with **test targets**: no `testResult`, `testSummary`, or test-related aborted reasons were ever observed, and nothing says how the publish-all-actions flag interacts with test actions.
- Aborted reasons other than `INCOMPLETE` from the execution phase. *Partially settled elsewhere:* §6.2 observed `USER_INTERRUPTED` (and `Multiple abort reasons reported: [USER_INTERRUPTED, INCOMPLETE]`) in the cancellation experiment. `ANALYSIS_FAILURE`, `LOADING_FAILURE` and `SKIPPED` remain unobserved.
- Multi-configuration, host/exec-split and aspect behaviour. `buildMetrics` reported `actionsCreatedNotIncludingAspects=20` vs `actionsCreated=21`, hinting at aspect accounting that was never exercised.
- Per-action byte cost on realistic command lines (see §7.1); the ~1.07–1.18 KB figure cannot be extrapolated.

**9.7 Capability probe**
- Which minor release in `(7.6.1, 8.4.1]` introduced FlagInfo fields 11–16. Bracketed, not bisected.
- Whether `bazel help flags-as-proto` exists at all before 6.5.0. The failure mode for an unsupported subtopic (exit code, stderr text) was never observed, so the pre-6.5 fallback path is unvalidated.
- Whether the probe survives a hostile **system** (`/etc/bazel.bazelrc`) or **user** (`~/.bazelrc`) rc. Neither file exists on this machine; only the workspace-level `.bazelrc` failure was reproduced. `--ignore_all_rc_files` covers all three by construction but was demonstrated only for the workspace file.
- Whether the in-workspace lock-blocking (20.48 s, 8.4.1) is identical on the other three versions, and whether any client-side timeout exists.
- Whether any Bazel version populates `enum_values` for the `output` flag. Empty on all four; the `type_converter='String'` reading suggests it never will, but that is an inference.

**9.8 Platform**
- Everything in this report is macOS 25.6.0, arm64. Linux and Windows were not tested anywhere. Three specific risks: (a) the ~16 s connection-refused backoff could differ; (b) PPID-1 reparenting is POSIX daemonization and Windows has no equivalent process model; (c) **the "no newline anywhere in the flags-as-proto base64" result is the single most load-bearing parser assumption** and could differ if the Windows client writes CRLF or wraps the output.

---

## 10. What the implementation must do

Argv construction
1. Partition the flag table into a **startup segment** (before the verb) and a **command segment** (after it), and mark `--output_base`, `--max_idle_secs`, `--host_jvm_args`, `--bazelrc`, `--noblock_for_lock` as startup. [§2.1]
2. Recognize **both** misplacement error shapes as "flag in the wrong segment," anchoring on `Unrecognized option: ` and on `Unknown startup option: '` respectively — never on the whole FATAL line, whose source line number varies by version. [§2.2]
3. Add an `accumulates: bool` column to the flag table. Append-to-override is valid only for single-valued flags; `--build_metadata`, `--copt` and `--bes_header` add rather than replace. Use `bazel canonicalize-flags --for_command=<cmd> -- <flags>` as the per-version oracle. [§2.3]
4. Scan the user's argv for a `--` and **splice instrumentation flags in before it**, never append past it; emit at most one `--`; allow only target patterns after it. A flag placed after `--` produces exit 1 at target-pattern parsing with **no BEP file written**, so a "flag problem?" check keyed on exit 2 will miss it entirely. [§2.4, §8.3]
5. Surface the override when instrumentation shadows a user's `--build_event_json_file` — Bazel emits no warning about the shadowed occurrence. [§2.3]
6. Parse user-supplied command lines without assuming flags-then-targets order, and handle the space-separated value form (`--flag value`), not just `--flag=value`. [§2.5]
7. For `bazel run`, emit exactly one `--`, put all Bazel flags before it, and pass user arguments through unmodified even when they contain `--`. [§2.6]

Workspace and cwd
8. Discover the workspace root by walking upward for any of `{MODULE.bazel, WORKSPACE, WORKSPACE.bazel, REPO.bazel}` — one predicate, no version gate. Do **not** treat `WORKSPACE.bzlmod` as a marker. [§3.1]
9. Detect "not in a workspace" by exit code 2 plus the substring `is only supported from within a workspace`; never by the WORKSPACE-vs-MODULE.bazel wording, which changed at 8.x, and never by the full sentence, which interpolates the verb. [§3.1]
10. Capture the process cwd separately from the workspace root and launch Bazel with the user's original cwd; never rewrite `:all`, `...` or bare target names to root-relative form. [§3.2]
11. In a BEP-only consumer, resolve relative target patterns from `--client_cwd` in the `structuredCommandLine` `original` section (`optionName == 'client_cwd'`), not from `started.workspaceDirectory` and not from `started.optionsDescription`. [§3.3]

Capability probing
12. Run `bazel help flags-as-proto` from a **scratch directory outside any workspace**. That single choice avoids the rc-file failure, the warm-server kill, and the command-lock stall, and produces byte-identical output. [§4.1, §4.6]
13. Read stdout as raw bytes to EOF and base64-decode the whole thing. No `readLine()`, no per-line trim, no assumption of a trailing newline, no max-line-length reader — the payload is a single 470–584 KB line. [§4.2]
14. Never set `redirectErrorStream(true)` or `2>&1`; buffer stderr separately. [§4.3]
15. Parse FlagInfo with proto2 open semantics and `hasField` checks; declare fields 7/8/9 as strings, not enums; tolerate a future field 17+. Never read `default_value`, `type_converter` or `enum_values` on 6.5.0 or 7.6.1 — they are absent, not empty. [§4.4, §4.8]
16. Do not derive query/aquery/cquery output formats from `enum_values`; use a per-(version, command) table built from execution probes. Emit `--output=proto` (lowercase); never `--output=streamed_proto` to `aquery` on 6.x. [§4.5]
17. Give the probe an explicit timeout and treat non-zero exit with empty stdout as **inconclusive**, never as "flag unsupported." Cache the parsed result keyed by resolved bazel binary path + `bazel --version`; budget ~0.7 s and ~600 KB per binary. [§4.6, §4.7]
18. Filter out any FlagInfo whose `commands == ['startup']` when computing build-command capabilities, and compare user values against `enum_values` case-insensitively. [§4.8]
19. Do not index into or diff the whole `commands` list — `vendor` appears at 7.6.1, `analyze-profile` and `sync` disappear at 9.2.0. Membership-test for `'build'` instead. [§4.5]

Embedded BES server
20. **ACK every `PublishBuildToolEventStreamRequest`, including after the client half-closes.** Not acking, or acking only the first N, hangs the user's build indefinitely on all four versions with nothing on stderr but a progress spinner. [§5.1]
21. Emit ACKs from a path that cannot block on downstream storage or parsing — ack first, persist afterwards. A back-pressure design that stops acking while a queue is full hangs the build rather than slowing it. [§5.1]
22. Echo the sequence number from `PublishBuildToolEventStreamRequest` field 4 → `OrderedBuildEvent` field 2, and echo the StreamId bytes verbatim (no semantic reconstruction). Add a dedicated regression test for ACK sequence numbers on **6.5.0 and 9.2.0**, where a wrong number killed the Bazel server outright and destroyed the analysis cache — and treat "may crash" as the assumption on all versions. [§5.1, §5.4, §8.4]
23. Build the server's give-up path around **closing the gRPC stream** (~1 s, exit 45, one clear error) rather than stalling, and ensure any internal exception in the stream handler terminates the RPC instead of leaving it open. [§5.1]
24. Bind the socket and complete the gRPC/HTTP-2 handshake as one step — never bind early and initialize gRPC later. A socket that accepts but does not finish the preface costs the user ~91 s and a red ERROR, from a hard-coded ~15 s per-attempt deadline × 4 attempts that no known flag controls. [§5.1]
25. Guarantee the server is listening before the bazel process starts; a refused connection costs ~16 s of retry backoff and turns a green build into exit 38. [§5.1]
26. Keep the unary `PublishLifecycleEvent` endpoint alive until the invocation is fully done — two more lifecycle calls arrive **after** the stream closes. [§5.4]
27. Handle a replayed stream on a new connection idempotently: an orphaned server-side uploader reconnected to a freshly started server on the same port and replayed all 32–34 requests. [§5.7]

Runner: flags and exit-code classification
28. Always pass an explicit `--bes_timeout` (e.g. 30s–60s). The default is `0s`, meaning no timeout, and it is the only reliable bound on a BES stall — it converts every failure mode into a bounded single-line ERROR. [§5.2]
29. Emit `--bes_backend=grpc://127.0.0.1:<port>` with the `grpc://` prefix mandatory; a missing scheme silently attempts TLS and burns 16 s. If `--bes_results_url` is used, make it end in a separator — the invocation UUID is concatenated directly. [§5.5]
30. Emit `--build_event_publish_all_actions` by default, unconditionally, with no version gate or capability probe. Without it a green build has **zero** action events. [§7.1]
31. Keep `--bes_upload_mode=nowait_for_upload_complete` as an opt-in safety valve, not a default: it caps the damage at a 5 s wait plus a WARNING at the start of the next build, but makes BES failures invisible (exit 0 even when nothing uploaded). It can be passed uniformly across 6.5–9.2 even though 6.5.0 does not document it. [§5.3]
32. Never infer build success or failure from the process exit code while BES is attached — a broken backend turns an action failure's exit 1 into exit 38. Read the outcome from the BEP (`BuildFinished` / `TargetComplete`) and classify 38 as "BES transport problem, outcome unknown from exit code." [§5.6]
33. Classify exit 9 as "lock held," not build failure; put `--noblock_for_lock` **before** the subcommand. [§6.4]
34. Classify exit 130 with **no BEP file** as a legitimate cancellation — the runner must not require the BEP path to exist. Classify exit 37 plus `Server terminated abruptly` as "cancelled, server lost" and expect the next invocation to cold-start. [§6.5]
35. Record exit codes and message text in the compatibility table, never guessed enum names. [§1, §9.1]

Runner: cancellation
36. Signal the specific client PID; do not walk descendants (the client has none), do not signal the process group (the client may share the launcher's), and never reap the Bazel server. [§6.1]
37. Send SIGINT, then poll process liveness; escalate only after seconds, not milliseconds — the client is gone in 13–63 ms and a 150 ms SIGTERM/400 ms SIGKILL both land on a dead process. Do not tune the ladder as if SIGTERM were stronger than SIGINT, and do not rely on a second SIGINT. [§6.2]
38. Never cancel within the first ~1 s of spawning; if the user asks earlier, **delay** the signal. Below that window the outcome is a race between exit 130 with no BEP, exit 8, exit 0 with the signal silently lost, and (on 9.2.0) exit 37 with the server dead. [§6.5]
39. Reconcile "cancel requested" against "build succeeded" — exit 0 after a cancel request is a real observed outcome. [§6.5]
40. Detect cancellation by exit code 8 plus the BEP's `BuildFinished` exit-code name `INTERRUPTED` / abort reason `USER_INTERRUPTED`; never by string-matching the banner, whose wording differs on 6.5.0 (`shutting down.` vs `cancelling pending invocation.`). [§6.2, §8.5]
41. After SIGINT or SIGTERM, read the BEP immediately at client exit — no settle delay, no growth polling. After a SIGKILL, do **not**: wait for `lastMessage`/`BuildFinished` or for the lock to release first. Prefer SIGINT; SIGKILL never speeds up stopping the actions, it only removes your ability to know when the file is final. [§6.3]
42. Never restart a build immediately after force-killing a client: allow ≥3 s or poll `bazel --noblock_for_lock info` until it returns 0. Never use the PID printed in the lock message for liveness or as a kill target — it can already be dead. [§6.4]
43. Treat `bazel shutdown` as an explicit user action or last-resort recovery only, issued after the lock window, never as an automatic ladder rung — it destroys the shared warm server. [§6.7]
44. Assume a BES stall can outlive the invocation and wedge the workspace regardless of the cancellation ladder, and reconcile the two conflicting force-kill models (§8.2) with a real measurement before relying on the ~2.5 s figure with BES attached.

BEP ingestion
45. Detect end-of-stream **only** by `lastMessage == true`. Never hard-code "the stream ends with buildToolLogs" — 9.2.0 ends with `buildMetrics`. [§7.2]
46. Treat "stream ended cleanly" and "build reported a result" as separate conditions. A missing `BuildFinished` with `lastMessage == true` means "cancelled before the build started," not corruption; a session model requiring a terminal `BuildFinished` will hang or error on every early cancellation. [§6.6, §7.2]
47. Buffer orphan events and re-parent them when the announcement arrives — roughly 1 in 6 real captures violates announce-before-arrive. Never derive timing or causality from announcement order. [§7.3]
48. Treat `children` as a set-union with idempotent placeholder creation, deduplicated by serialized `BuildEventId` — a failed action's id is announced by a `progress` event and re-announced by `targetCompleted` after it already arrived. [§7.3]
49. Dispatch payloads via an explicit id-kind → payload-kind table, and for `targetCompleted` dispatch on the payload actually present (`completed` **or** `aborted`). [§7.4, §7.7]
50. Read failure from four carriers — `action`, `completed`, `finished`, `aborted` — keyed on the **presence** of `failureDetail` (and `aborted.reason`), classifying via the `failureDetail.spawn.code` enum. Never regex the `message`, whose text differs on all three versions. [§7.4]
51. Display `failureDetail.spawn.spawnExitCode` as "exit code," not `action.exitCode`, which is 1 even for a spawn that exited 3 and contradicts Bazel's own console output. [§7.4]
52. Deserialize with default `false`/`0` and treat missing as failure: `action.success`, `completed.success`, `finished.overallSuccess` and `finished.exitCode.code` are omitted at their zero values. [§7.5]
53. Skip-and-count unknown payload kinds rather than failing — 9.2.0 already emits `convenienceSymlinksIdentified`, which 6.5.0/7.6.1 never do, and an exhaustive switch will break on the next release. [§7.6]
54. Make configuration lookup a nullable join, never an assert: the `system` configuration id is never published, and the `none` configuration has an empty payload. Render internal actions' config column blank/"n/a" and never reject an event whose configuration id has no match. [§7.8]
55. Tolerate an `action` event with no `label` (workspace-status and other internal actions). [§7.1]
56. Version-gate per-action timing: `startTime`/`endTime` exist only on 9.2.0 of the tested set. Hide the duration column on older versions rather than rendering zeros; fall back to `buildMetrics.actionSummary.actionData[].firstStartedMs/lastEndedMs` and the `buildToolLogs` critical path. [§7.6]
57. Record in each session whether `--build_event_publish_all_actions` was present, and have the UI say "action data not captured" rather than "no actions ran." Derive success ratios from `buildMetrics.actionSummary`, never by counting action events — failures are always published, successes only under the flag. [§7.1]
58. Report incremental builds as "1 of 21 actions executed (20 up to date)" from `buildMetrics.actionSummary`; a near-empty action view on a warm build is correct, not a capture bug. Benchmarks must `bazel clean` between runs. [§7.1]
59. Budget storage per executed action (~1 KB **plus the full command line**, which dominates the row and is 10–100× larger on real compile actions) and consider interning or compressing `commandLine`. Assume ~2 stream events per published action, so line counts grow faster than action counts. [§7.1]
60. Write no golden-file test that asserts exact event counts or byte sizes for a failing build, and none that assumes a with-flag capture is larger than a without-flag one. Assert invariants instead (failed target has `failureDetail`; unbuilt targets have `aborted.reason == INCOMPLETE`; `finished.exitCode.name == BUILD_FAILURE`), and consider `--keep_going` in failure fixtures to make the target set deterministic. [§7.9]

Test fixtures and coverage
61. Avoid native `sh_binary` in any cross-version fixture; use a small Starlark rule returning `DefaultInfo(executable=...)`. [§2.7]
62. Fill the two largest coverage holes before writing a compatibility table: **8.4.1 BEP content** (§9.6) and **6.5.0 / 8.4.1 command-line grammar** (§9.3). Every "all versions" claim in §7 and §2 currently excludes them.

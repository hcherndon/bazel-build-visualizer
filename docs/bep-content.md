# The Bazel Build Event Protocol: What Is Actually In It

**Audience:** the team about to design the database schema and the BEP normalizer.
**Basis:** five independent measurement experiments (`action-identity`, `targets-configs`, `depsets`, `tests`, `aggregates-and-failure`), all run on macOS 25.6.0 / darwin_arm64 against Bazel **6.5.0, 7.6.1, 8.4.1, 9.2.0** via `bazelisk`, capturing `--build_event_json_file` only.
**Date of capture:** 2026-08-22.

Every claim below carries the command that produced it and the JSON it produced. Claims that could not be reconciled across experiments are in [§10 Contradictions](#10-contradictions-unresolved) rather than silently arbitrated. Everything not measured is in [§12 Unverified](#12-unverified).

---

## Legend for every version matrix in this document

| Symbol | Meaning | What the normalizer stores |
|---|---|---|
| `Y` | Field present and populated | the value |
| `—` | **Field absent from the JSON on this version.** Bazel cannot emit it. | `NULL` / unknown. **Never 0, never `""`, never `false`.** |
| `cond` | Emitted only under a stated condition (usually proto3 default-omission) | the proto3 default (`0`/`false`), because the value is *known* |
| `flag` | Emitted only when a named CLI flag is passed | `NULL` + record the flag state |
| `BROKEN` | Present but carrying a wrong value | `NULL` + a defect marker |

The distinction between `—` and `cond` is the whole ballgame for this project's "unavailable data is never stored as zero" rule. See [§2](#2-three-kinds-of-absence-and-they-are-not-interchangeable).

---

## 1. Cross-cutting encoding rules

These apply to every entity and are the source of most normalizer bugs.

### E1 — The event id key and the payload key do not match

**Evidence** (`aggregates`, full event dump, all four versions):

```json
{"children": [...], "finished": {"exitCode": {"name": "SUCCESS"}}, "id": {"buildFinished": {}}}
```

Sequence listing printed `idKey=buildFinished payloadKey=['finished']`, `idKey=pattern payloadKey=['expanded']`, `idKey=targetConfigured payloadKey=['configured']`, `idKey=actionCompleted payloadKey=['action']`, `idKey=workspace payloadKey=['workspaceInfo']`.

Required mapping (identical on 6.5.0 / 7.6.1 / 8.4.1 / 9.2.0):

| id key | payload key(s) observed |
|---|---|
| `buildStarted` | `started` |
| `pattern` | `expanded`, `aborted` |
| `configuration` | `configuration` |
| `targetConfigured` | `configured`, `aborted` |
| `targetCompleted` | `completed`, `aborted` |
| `unconfiguredLabel` | `aborted` |
| `configuredLabel` | `aborted` |
| `actionCompleted` | `action` |
| `namedSet` | `namedSetOfFiles` |
| `testResult` | `testResult` |
| `testSummary` | `testSummary` |
| `buildFinished` | `finished` |
| `buildMetrics` | `buildMetrics` |
| `buildToolLogs` | `buildToolLogs` |
| `workspace` | `workspaceInfo` |
| `progress` | `progress` |

### E2 — Dispatch on the payload key, never the id key

`aborted` rides on **three** different id types. From `targets-configs/payloads.py`, (id key, payload key) pairs:

```
build-nokeep   9.2.0  {('targetConfigured','configured'): 13, ('targetCompleted','completed'): 12, ('targetCompleted','aborted'): 1}
abort-analysis 9.2.0  {('targetConfigured','configured'): 3, ('targetCompleted','completed'): 3, ('targetConfigured','aborted'): 1, ('unconfiguredLabel','aborted'): 1}
abort-analysis 6.5.0  {('targetConfigured','configured'): 4, ('targetCompleted','aborted'): 1, ('targetCompleted','completed'): 3, ('unconfiguredLabel','aborted'): 1}
```

`aggregates` adds a fourth: an analysis failure produced an `aborted` keyed by `{"configuredLabel": {"label": "//nosuchpkg:nosuchtarget", "configuration": {...}}}`.

The `targets-configs` author records making exactly this mistake mid-investigation: *"A dispatcher keyed on `id.targetCompleted` would create a target row with success=NULL and no outputs for every aborted target… it inverted the answer to the TargetConfigured/TargetComplete parity question."*

### E3 — int64 fields are JSON **strings**; some others are JSON ints

Verified by `tests/analyze.py types` and by inspection across all experiments.

| Encoding | Fields |
|---|---|
| JSON string holding an integer | `File.length`, `testTimeoutSeconds`, `testAttemptStartMillisEpoch`, `testAttemptDurationMillis`, `firstStartTimeMillis`, `lastStopTimeMillis`, `totalRunDurationMillis`, `startTimeMillis`, `finishTimeMillis`, `serverPid`, all `*InMs` timing metrics, `garbageCollected` |
| JSON int | `testResult` id `run` / `shard` / `attempt`, `totalRunCount`, `runCount`, `shardCount`, `attemptCount`, `totalNumCached`, `action.exitCode`, `spawnExitCode`, `exitCode.code`, `actionCacheStatistics.hits/misses` |
| RFC3339 timestamp string | `startTime`, `finishTime`, `testAttemptStart`, `action.startTime`, `action.endTime` |
| protobuf Duration string | `testTimeout` (`"300s"`), `testAttemptDuration` (`"0.277s"`), `timingBreakdown[].time`, **`criticalPathTime` (`"1.037770417s"`)** |

`criticalPathTime` is the trap: it sits inside `timingMetrics` beside six `*InMs` integer-strings and is the only Duration there.

```json
9.2.0: {"actionsExecutionStartInMs": "92", "analysisPhaseTimeInMs": "9", "cpuTimeInMs": "408",
        "criticalPathTime": "1.037770417s", "executionPhaseTimeInMs": "1041", "wallTimeInMs": "1189"}
```

### E4 — Both the deprecated `*Millis` spelling and the Timestamp/Duration spelling are emitted, simultaneously, on all four versions

The premise that newer Bazel dropped the millis fields is **refuted** for 6.5–9.2.

`tests/fields.py testResult`:

```
field                            6.5.0    7.6.1    8.4.1    9.2.0
testAttemptDuration                YES      YES      YES      YES
testAttemptDurationMillis          YES      YES      YES      YES
testAttemptStart                   YES      YES      YES      YES
testAttemptStartMillisEpoch        YES      YES      YES      YES
```

Same for `testSummary.firstStartTime`/`firstStartTimeMillis`, `lastStopTime`/`lastStopTimeMillis`, `totalRunDuration`/`totalRunDurationMillis`, and for `started.startTime`/`startTimeMillis`, `finished.finishTime`/`finishTimeMillis`. `tests/analyze.py consistency` parsed both spellings on 37 testResult events per version: **0 mismatches on all four**.

```json
"testAttemptDuration": "0.277s", "testAttemptDurationMillis": "277",
"testAttemptStart": "2026-08-22T16:55:43.804Z", "testAttemptStartMillisEpoch": "1787417743804"
```

---

## 2. Three kinds of absence, and they are not interchangeable

Every `—` cell in this document is one of these. The normalizer needs to distinguish all three, because the project rule forbids collapsing kinds 2 and 3 into zero.

1. **proto3 default omission** — the value is known and is the type default. `action.success` absent ⇒ the action failed. `completed.success` absent ⇒ the target failed. `isTool` absent ⇒ false. `cachedLocally` absent ⇒ false. `finished.exitCode.code` absent ⇒ 0. `executionInfo.exitCode` absent **on 8.4.1/9.2.0** ⇒ 0. → store the default.
2. **Version cannot report it** — `action.startTime` on 6.5.0/7.6.1; `executionInfo.exitCode` on 6.5.0/7.6.1; `timingMetrics.executionPhaseTimeInMs` on 6.5.0. → store NULL and record the Bazel version on the session row so the UI can say "not reported by this Bazel".
3. **Flag not passed / build state** — `importantOutput` on 8.x/9.x, `memoryMetrics` heap fields without `--memory_profile`, action events without `--build_event_publish_all_actions`, `configuration` id `"none"` without that same flag. → store NULL **and record the flag state**, readable from the `structuredCommandLine` events.

Note the compound case that matters most: `executionInfo.exitCode` is NULL for reason 2 on 6.5.0/7.6.1 and NULL for reason 1 on 8.4.1/9.2.0. Same NULL, two meanings, resolvable only with the version.

---

## 3. ORDERING guarantees

This is the section the normalizer's structure hangs on. Measured by comparing line indices in the newline-delimited JSON.

### O1 — NamedSetOfFiles is ALWAYS defined before it is referenced. Zero forward references.

**This is the answer to the load-bearing question.** From `depsets`, deep-scanning every `fileSets` array in every event payload of every stream:

> 43 streams, 1,335 NamedSetOfFiles definitions, **1,829 references, 0 forward references, 0 undefined references, 0 child-defined-after-parent**, on all four versions.

Consolidated table (columns: events, sets, refs, forward-refs, undefined-refs, child-after-parent):

```
bep-6.5.0-run1.json       297 49  72 0 0 0
bep-7.6.1-run1.json       297 49  72 0 0 0
bep-8.4.1-run1.json       299 49  72 0 0 0
bep-9.2.0-run1.json       299 49  72 0 0 0
bep-7.6.1-fail.json       291 61 103 0 0 0   (--keep_going, failing genrule, --jobs=64)
bep-9.2.0-fail.json       297 61 103 0 0 0
bep-7.6.1-interrupt.json  407 90 113 0 0 0   (SIGINT to the process group ~9s in; 13 aborted events)
bep-9.2.0-interrupt.json  409 90 113 0 0 0
bep-9.2.0-kill.json       390 90 113 0 0 0   (SIGKILL of client+server mid-build; no buildFinished)
bep-9.2.0-par.json        351 62 101 0 0 0   (clean build, --jobs=64)
... 43 streams total, all zeros
```

Two stronger sub-facts fall out:

- **Child sets are defined before parent sets.** So the stream order is itself a valid reverse-topological order of the DAG — per-set roll-ups (file counts, byte totals) can be computed incrementally as sets arrive, with no second pass.
- The `children` announcement path agrees: only `progress` events announce `namedSet` children (49 of them in `bep-7.6.1-run1.json`), and *"announced AFTER already defined: 0"* — even the announcement precedes the definition.

Only two payload positions ever reference a named set: `completed.outputGroup[].fileSets` and `namedSetOfFiles.fileSets`. `actionCompleted` never references one, even with `--build_event_publish_all_actions`.

> ⚠️ The `targets-configs` experiment reaches the **opposite operational conclusion** on this exact question, from absence of evidence rather than contrary evidence. See [Contradiction 4](#contradiction-4).

### O2 — `configuration` always precedes any target event referencing it. Zero violations.

`targets-configs/final.py`, 26 file/version combinations:

```
6.5.0  configured-before-completed violations=0 ; config-before-target violations=0 ; completed refs undeclared cfg=[]
7.6.1  same
8.4.1  same
9.2.0  same
```

**Caveat:** this checked `targetCompleted` references only. `action-identity` found that **action** events reference a configuration id (`"system"`) that no `configuration` event ever declares — see [C4](#c4-system-configuration).

### O3 — `configured` always precedes `completed`/`aborted` for the same label. Zero violations, zero orphans.

```
build-keepgoing 6.5.0/7.6.1/8.4.1/9.2.0  violations=0 orphans=[]
build-nokeep    6.5.0/7.6.1/8.4.1/9.2.0  violations=0 orphans=[]
test-keepgoing  6.5.0/7.6.1/8.4.1/9.2.0  violations=0 orphans=[]
abort-analysis  6.5.0/7.6.1/8.4.1/9.2.0  violations=0 orphans=[]
abort-nokeep    6.5.0/7.6.1/8.4.1/9.2.0  violations=0 orphans=[]
interrupt       6.5.0/9.2.0              violations=0 orphans=[]
```

### O4 — `targetCompleted` always precedes `testSummary`; `testResult` never follows its own `testSummary`

`tests/order.py` over every capture: `targetCompleted vs testSummary ordering: {'targetCompleted before testSummary': 58}`, `ordering violations (testResult emitted after its testSummary): NONE`. Additionally each attempt announces the next as a child: linkage counts `{(1,2): 20, (2,3): 16}`.

### O5 — `aborted` events arrive AFTER `buildFinished`, on all four versions

SIGINT stream tails:

```
6.5.0 / 7.6.1: 21:buildFinished -> 22:buildMetrics -> 24:ABORTED -> 25:ABORTED -> 26:buildToolLogs
8.4.1 / 9.2.0: 23:buildFinished -> 25:ABORTED -> 26:ABORTED -> 27:buildToolLogs -> 28:buildMetrics
```

A consumer that treats `buildFinished` as end-of-stream loses the entire list of failed and skipped targets.

### O6 — The last event, and the carrier of `lastMessage: true`, changed between 7.6.1 and 8.4.1

Scan of 43 streams — tuple is (version, events carrying `lastMessage`, actual final event, count of flagged events):

```
('6.5.0', ('buildToolLogs',), 'buildToolLogs', 1) x 11
('7.6.1', ('buildToolLogs',), 'buildToolLogs', 1) x 10
('8.4.1', ('buildMetrics',),  'buildMetrics',  1) x 10
('9.2.0', ('buildMetrics',),  'buildMetrics',  1) x 12
```

Exactly one event per stream carries the flag and it is always physically last, across success, no-op, cold-cache, three failure modes, `--keep_going` variants and SIGINT.

### O7 — Anti-guarantees (things ordering does **not** give you)

- **Named-set ids are not stable across runs**, not even a warm no-op rerun of an unmodified workspace. `depsets/cmp.py bep-7.6.1-run1.json bep-7.6.1-run2.json`: *"same id set: True; ids present in both but with DIFFERENT content: 49"*, and *"targetCompleted fileSet id per label differs: 49 of 50 labels"* (`//pkg:fg0` `('23',)` → `('8',)`).
- **Publication order is not id order.** run1 emitted ids `0,1,2,4,3,5,…,33,32,35,34,…`.
- **A defined set may never be referenced** — observed only in abnormally-terminated streams (2 orphans in the `--build_event_max_named_set_of_file_entries=2` crash streams). Legal; not a parse error.
- **An undefined reference was never seen in 1,829 references.** Treat one as evidence of truncation/corruption and surface it, do not swallow it.

---

## 4. Entity: Configurations

### Field matrix — payload key `configuration`

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.configuration.id` | Y | Y | Y | Y | opaque string; **not always a hash** — literal `"none"` and `"system"` occur |
| `mnemonic` | Y | Y | Y | Y | absent on the `"none"` config; exec form is version-unstable |
| `platformName` | Y | Y | Y | Y | absent on the `"none"` config |
| `cpu` | Y | Y | Y | Y | absent on the `"none"` config |
| `makeVariable` (map) | Y | Y | Y | Y | 4 keys in a toolchain-free workspace; `TARGET_CPU` **value** differs on 9.2.0 |
| `isTool` | cond | cond | cond | cond | emitted only when `true`; absence ⇒ target config |

**Command** (`targets-configs`):

```
USE_BAZEL_VERSION=<v> ~/.cache/bbv-dev/bin/bazelisk --nohome_rc --nosystem_rc \
  build //... --keep_going --build_event_json_file=bep/build-keepgoing-<v>.json \
  --build_event_publish_all_actions
```

Target config (9.2.0):

```json
{"configuration": {"cpu": "darwin_arm64",
  "makeVariable": {"BINDIR": "bazel-out/darwin_arm64-fastbuild/bin", "COMPILATION_MODE": "fastbuild",
                   "GENDIR": "bazel-out/darwin_arm64-fastbuild/bin", "TARGET_CPU": "aarch64"},
  "mnemonic": "darwin_arm64-fastbuild", "platformName": "darwin_arm64"},
 "id": {"configuration": {"id": "2d8934052f1445fdec9fefac5a616f1fb9d9dea67b8c1b3f6e1572370634272c"}}}
```

Exec config (9.2.0), the only one carrying `isTool`:

```json
{"configuration": {"cpu": "darwin_arm64", "isTool": true,
  "makeVariable": {"BINDIR": "bazel-out/darwin_arm64-opt-exec/bin", "COMPILATION_MODE": "opt", ...},
  "mnemonic": "darwin_arm64-opt-exec", "platformName": "darwin_arm64"},
 "id": {"configuration": {"id": "77e8473c8c1c4b4ef1825b4b7ffc50dc3ee606e358c10cba8c77f21ec5fe49d9"}}}
```

### C1 — `mnemonic` is version-unstable and must never be parsed

```
6.5.0  exec mnemonic: darwin_arm64-opt-exec-2B5CBBC6
7.6.1  exec mnemonic: darwin_arm64-opt-exec-ST-d57f47055a04
8.4.1  exec mnemonic: darwin_arm64-opt-exec-ST-d57f47055a04
9.2.0  exec mnemonic: darwin_arm64-opt-exec
```

`makeVariable.TARGET_CPU` also changed value: `darwin_arm64` on 6.5.0/7.6.1/8.4.1, `aarch64` on 9.2.0. Use `isTool` for exec-ness and `makeVariable.COMPILATION_MODE` for the mode.

### C2 — Two configurations can have byte-identical payloads and different ids

A **trivial single-target build emits two `configuration` events**, not one, on all four versions. Cause isolated: test-configuration trimming.

```
6.5.0 trivial  #cfg=2 none=False mnemonics=['darwin_arm64-fastbuild', 'darwin_arm64-fastbuild']
7.6.1 / 8.4.1 / 9.2.0 trivial  #cfg=2
notrim 9.2.0  #cfg=1 ids=['91a890ab'] targets={'//pkg:gen_a':'91a890ab','//pkg:pass_test':'91a890ab'}
trim   9.2.0  #cfg=2 ids=['1a589d14','2d893405'] targets={'//pkg:gen_a':'2d893405','//pkg:pass_test':'1a589d14'}
```

The two 6.5.0 payloads, identical apart from the id:

```json
{"id":{"configuration":{"id":"9cd96869affcbadf499d664d349aab0a56d17a75de5bc5fa99e4d5d7a601840c"}},"configuration":{"mnemonic":"darwin_arm64-fastbuild","platformName":"darwin_arm64","cpu":"darwin_arm64","makeVariable":{"COMPILATION_MODE":"fastbuild","TARGET_CPU":"darwin_arm64","GENDIR":"bazel-out/darwin_arm64-fastbuild/bin","BINDIR":"bazel-out/darwin_arm64-fastbuild/bin"}}}
{"id":{"configuration":{"id":"3b270167ad09e1b14e1cecd3ecac79b255a5a5eb6162dc1c3e64c83ef54484da"}},"configuration":{"mnemonic":"darwin_arm64-fastbuild","platformName":"darwin_arm64","cpu":"darwin_arm64","makeVariable":{"COMPILATION_MODE":"fastbuild","TARGET_CPU":"darwin_arm64","GENDIR":"bazel-out/darwin_arm64-fastbuild/bin","BINDIR":"bazel-out/darwin_arm64-fastbuild/bin"}}}
```

They genuinely partition the actions (9.2.0 r1): `2d893405…` → 20 actions (non-test targets), `1a589d14…` → 10 actions (all `//pkg:flaky_test`), `77e8473c…` → 1 action (`//pkg:shared` in the exec config), `system` → 1 action.

### C3 — The `"none"` configuration exists, is empty, and is flag-gated

```
build //... --keep_going                          -> 6.5.0 #cfg=2 none=False ; 9.2.0 #cfg=2 none=False
build //pkg:gen_a --build_event_publish_all_actions -> 6.5.0 #cfg=3 none=True  ; 9.2.0 #cfg=3 none=True
```

Payload, all four versions: `{"configuration": {}, "id": {"configuration": {"id": "none"}}}`. It is announced as a child of a `progress` event and referenced by **zero** `targetCompleted` events.

<a id="c4-system-configuration"></a>

### C4 — The `"system"` configuration id is referenced but never declared

`action-identity`, comparing ids declared by `configuration` events against ids referenced by action events, over all runs per version:

```
6.5.0: declared=7 ; referenced-but-undeclared=['system']
7.6.1: declared=7 ; referenced-but-undeclared=['system']
8.4.1: declared=7 ; referenced-but-undeclared=['system']
9.2.0: declared=7 ; referenced-but-undeclared=['system']
```

A literal FK from `action.configuration_id` to `configuration.id` fails on **every single build**.

### C5 — The id and the payload disagree on the same event

For `BazelWorkspaceStatusAction`, `id.actionCompleted.configuration.id` is `system` while `action.configuration.id` is `none`:

```
--- 6.5.0 : 1 mismatches   id.cfg='system'  payload.cfg='none'  po=bazel-out/stable-status.txt
--- 7.6.1 : 1 mismatches   (identical)
--- 8.4.1 : 1 mismatches   (identical)
--- 9.2.0 : 1 mismatches   (identical)
```

By contrast `id.actionCompleted.label` and `action.label` **never** diverged, across r1-fastbuild, r3-fail and r4-flaky on all four versions.

### C6 — The set of configuration events is not a function of the build graph

`targets-configs` initially read a missing exec configuration as a version difference and then disproved it: `bazelisk clean` + `build //pkg:uses_mytool --build_event_publish_all_actions`, twice per version, gave `#cfg=4 isTool=1 #actionEvents=3` on **all four**. The earlier 7.6.1 incremental run showed `#cfg=3 isTool=0` purely because the exec action was cached and its configuration therefore never announced.

---

## 5. Entity: Targets

### Field matrix — `targetConfigured` id + `configured` payload

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.targetConfigured.label` | Y | Y | Y | Y | **no configuration in this id** |
| `id.targetConfigured.aspect` | ? | ? | ? | ? | never observed — no aspects in any workspace |
| `configured.targetKind` | Y | Y | Y | Y | always `"<rule class> rule"` |
| `configured.testSize` | cond | cond | cond | cond | test targets only; absent ⇒ not a test |
| `configured.tag` | cond | cond | cond | cond | **user tags only**, sorted; absent when empty |
| `configured` payload for an analysis-failed target | Y | — | — | — | see T5 |

Full event (9.2.0):

```json
{"children": [{"targetCompleted": {"configuration": {"id": "1a589d14ca3886895c1228db75ec6c30d0c253d2c9f4c3070e5f3535de94c607"}, "label": "//pkg:pass_test"}}],
 "configured": {"tag": ["test-tag-alpha"], "targetKind": "starlark_test rule", "testSize": "SMALL"},
 "id": {"targetConfigured": {"label": "//pkg:pass_test"}}}
```

`cfgmatrix.py` on all four versions: `targetConfigured <absent> x13` (no `configuration` key in the id at all) vs `targetCompleted <hash> x11 / x2`.

### Field matrix — `targetCompleted` id + `completed` payload

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.targetCompleted.label` | Y | Y | Y | Y | |
| `id.targetCompleted.configuration.id` | Y | Y | Y | Y | this is where the label→config binding lives |
| `completed.success` | cond | cond | cond | cond | **`true` only; absent ⇒ failed** |
| `completed.outputGroup[].name` | Y | Y | Y | Y | |
| `completed.outputGroup[].fileSets[]` | Y | Y | Y | Y | repeated; NamedSetOfFiles id refs |
| `completed.outputGroup[].incomplete` | ⚠ | Y | ⚠ | Y | **contested — see [Contradiction 1](#contradiction-1)** |
| `completed.importantOutput` | Y | Y | flag | flag | `--legacy_important_outputs` default flipped |
| `completed.directoryOutput` | Y | Y | Y | Y | never suppressed |
| `completed.tag` | ⚠ | Y | Y | Y | 7.6.1+ appends synthetic tags — **see [Contradiction 3](#contradiction-3)** |
| `completed.testTimeout` | cond | cond | cond | cond | **only under the `test` command** |
| `completed.testTimeoutSeconds` | cond | cond | cond | cond | ditto |
| `completed.failureDetail` | cond | cond | cond | cond | on failure |

### T1 — `importantOutput` is gone by default on Bazel 8+

Measured independently by three experiments; the strongest single-workspace census (`depsets`, same command each version):

```
6.5.0 targetCompleted: 50  with importantOutput: 50  total entries: 220
7.6.1 targetCompleted: 50  with importantOutput: 50  total entries: 220
8.4.1 targetCompleted: 50  with importantOutput: 0   total entries: 0
9.2.0 targetCompleted: 50  with importantOutput: 0   total entries: 0
```

Root cause, from `bazelisk --nohome_rc --nosystem_rc help build | grep legacy_important_outputs`:

```
6.5.0: --[no]legacy_important_outputs (a boolean; default: "true")
7.6.1: --[no]legacy_important_outputs (a boolean; default: "true")
8.4.1: --[no]legacy_important_outputs (a boolean; default: "false")
9.2.0: --[no]legacy_important_outputs (a boolean; default: "false")
```

Confirmed bidirectional by forcing the flag: `build //pkg:gen_a --legacy_important_outputs` on 8.4.1 and 9.2.0 gives `keys: ['importantOutput','outputGroup','success']`; `--nolegacy_important_outputs` on 6.5.0 and 7.6.1 gives `keys: ['outputGroup','success']`.

**Consequence:** an importer that reads outputs from `importantOutput` produces **zero outputs for every target on Bazel 8 and 9**. Depset resolution is mandatory, not an optimization.

### T2 — Output resolution is always indirect

`targets-configs/final.py`, aggregated over five build shapes per version:

```
6.5.0  outputGroup entry keys : {'name': 36, 'fileSets': 36}
7.6.1  outputGroup entry keys : {'name': 36, 'fileSets': 36}
8.4.1  outputGroup entry keys : {'name': 36, 'fileSets': 36}
9.2.0  outputGroup entry keys : {'name': 36, 'fileSets': 36}
```

Files are never inline in an output group. Multi-group example (`build //pkg:multi --output_groups=+extra_group`, all four versions):

```json
"outputGroup": [{"fileSets": [{"id": "0"}], "name": "default"},
                {"fileSets": [{"id": "1"}], "name": "extra_group"}]
```

### T3 — On failure, `success` is absent, and the payload may contain nothing else

```json
{"children": [{"actionCompleted": {"configuration": {"id": "2d8934052f14…"}, "label": "//pkg:gen_fail", "primaryOutput": "bazel-out/darwin_arm64-fastbuild/bin/pkg/fail.txt"}}],
 "completed": {"failureDetail": {
    "message": "bash failed: error executing Genrule command (from genrule rule target //pkg:gen_fail) /bin/bash -c 'source external/bazel_tools/tools/genrule/genrule-setup.sh; echo '\\''about to fail'\\'' >&2 ; exit 1'\n\nUse --sandbox_debug to see verbose messages from the sandbox and retain the sandbox build root for debugging",
    "spawn": {"code": "NON_ZERO_EXIT", "spawnExitCode": 1}}},
 "id": {"targetCompleted": {"configuration": {"id": "2d8934052f14…"}, "label": "//pkg:gen_fail"}}}
```

`completed keys: ['failureDetail']` on 6.5.0, 7.6.1, 8.4.1 and 9.2.0. No `success`, no `outputGroup`, no `importantOutput`.

### T4 — There is NOT a TargetComplete for every TargetConfigured

Columns: #`configured` / #`completed` / #`aborted` on a `targetCompleted` id / #`aborted` on a `targetConfigured` id.

| Shape | Versions | cfg'd | compl'd | abort@Completed | abort@Configured |
|---|---|---|---|---|---|
| `build --keep_going` | all four | 13 | 13 | 0 | 0 |
| `build` (nokeep) | all four | 13 | 12 | 1 | 0 |
| `test --keep_going` | all four | 13 | 13 | 0 | 0 |
| analysis failure | 6.5.0 | 4 | 3 | 1 | 0 |
| analysis failure | 7.6.1/8.4.1/9.2.0 | **3** | 3 | 0 | 1 |
| analysis failure (nokeep) | 6.5.0 | 4 | **0** | 4 | 0 |
| analysis failure (nokeep) | 7.6.1/8.4.1/9.2.0 | 3 | 3 | 0 | 1 |
| SIGINT during execution | 6.5.0, 9.2.0 | 6 | **0** | 6 | 0 |

In the interrupt case **six targets were configured and zero completed**. A normalizer that creates the target row only on TargetComplete drops every target in that build.

### T5 — On 7.6.1+ an analysis-failed target has *no* `configured` payload at all

Workspace with `//broken:analysis_fail` depending on a nonexistent target; `build //pkg:all //broken:all --keep_going`.

6.5.0 emits a `configured` payload **and** an abort on the `targetCompleted` id:

```json
{"aborted": {"reason": "ANALYSIS_FAILURE"}, "children": [{"unconfiguredLabel": {"label": "//pkg:does_not_exist"}}], "id": {"targetCompleted": {"configuration": {"id": "3b270167…"}, "label": "//broken:analysis_fail"}}}
```

7.6.1/8.4.1/9.2.0 emit no `configured` payload and abort on the `targetConfigured` id:

```json
{"aborted": {"reason": "ANALYSIS_FAILURE"}, "children": [{"unconfiguredLabel": {"label": "//pkg:does_not_exist"}}], "id": {"targetConfigured": {"label": "//broken:analysis_fail"}}}
```

The target *is* in the build — the `pattern` event announces it as a child — but nothing carries its rule kind. Corroborated independently by `aggregates` with a different workspace (`//faildep:needs_missing`): aggregate over all its streams gave `('ANALYSIS_FAILURE','targetCompleted') 1` (the 6.5.0 run) vs `('ANALYSIS_FAILURE','targetConfigured') 3`.

### T6 — `targetKind`, `testSize`, `tag` shapes

```
"configured": {"targetKind": "genrule rule"}                                            // //pkg:gen_a
"configured": {"targetKind": "filegroup rule"}                                          // //pkg:group
"configured": {"targetKind": "multi_out rule"}                                          // Starlark rule
"configured": {"tag": ["manual-ish","my-custom-tag","no-remote"], "targetKind": "genrule rule"}
"configured": {"targetKind": "starlark_test rule", "testSize": "MEDIUM"}                // no user tags -> no `tag` key
"configured": {"tag": ["test-tag-alpha"], "targetKind": "starlark_test rule", "testSize": "SMALL"}
```

Byte-identical `configured` payloads on all four versions for every target in the workspace.

### T7 — `completed.tag` ≠ `configured.tag` on 7.6.1+

`targets-configs/tags.py`:

```
-- 6.5.0
   //pkg:pass_test   configured.tag=['test-tag-alpha']  completed.tag=['test-tag-alpha']
-- 7.6.1 / 8.4.1 / 9.2.0 (identical to each other)
   //pkg:fail_test   configured.tag=None                completed.tag=['medium','moderate','noflaky','nolocal']
   //pkg:pass_test   configured.tag=['test-tag-alpha']  completed.tag=['test-tag-alpha','small','short','noflaky','nolocal']
   //pkg:gen_tagged  configured.tag=[...user tags...]   completed.tag=[...same user tags...]
```

`//pkg:fail_test` has **no** `tags` attribute in the BUILD file yet gets four tags on `completed` on 7.6.1+. Non-test targets are unaffected. A single merged tag column produces a silent cross-version inconsistency in any tag facet.

---

## 6. Entity: Actions

### Field matrix — `actionCompleted` id + `action` payload

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.actionCompleted.primaryOutput` | Y | Y | Y | Y | 354/354 events; **the identity column** |
| `id.actionCompleted.label` | cond | cond | cond | Y | absent for `BazelWorkspaceStatusAction` on 6/7/8 |
| `id.actionCompleted.configuration.id` | Y | Y | Y | Y | may be undeclared (`system`) |
| `action.success` | cond | cond | cond | cond | `true` only; absent ⇒ failed |
| `action.primaryOutput` (payload `File`) | cond | cond | cond | cond | **absent on all failures**; also absent on some 9.2.0 successes |
| `action.configuration.id` | Y | Y | Y | Y | may disagree with the id (C5) |
| `action.type` (mnemonic) | Y | Y | Y | Y | free text; vocabulary grows by version |
| `action.commandLine` | cond | cond | cond | cond | spawn-running actions only |
| `action.startTime` / `endTime` | **—** | **—** | BROKEN | Y | see A4 / A5 |
| `action.exitCode` | cond | cond | cond | cond | always `1`; **not the process exit code** |
| `action.failureDetail.spawn.spawnExitCode` | Y | Y | Y | Y | the real exit code |
| `action.failureDetail.spawn.code` | Y | Y | Y | Y | e.g. `NON_ZERO_EXIT` |
| `action.stderr` | cond | cond | cond | cond | a `File` **URI**, not inline text |
| `action.stdout` | — | — | — | — | never observed in any of 16 streams |
| `action.strategyDetails` | — | — | — | — | **0 occurrences in 16 streams** |

**Command:**

```
USE_BAZEL_VERSION=$V ~/.cache/bbv-dev/bin/bazelisk --nohome_rc --nosystem_rc \
  build //pkg:all --build_event_json_file=... --build_event_publish_all_actions
```

Census over ALL runs per version:

```
6.5.0: 81 action events; primaryOutput missing-or-empty=0, label present-but-empty=0, configuration.id missing-or-empty=0
7.6.1: 87 action events; ... =0
8.4.1: 90 action events; ... =0
9.2.0: 96 action events; ... =0
```

### A1 — `primaryOutput` alone is the identity; `(label, configuration)` is not

```
6.5.0  r1-fastbuild n=28 | primaryOutput: distinct=28 dupkeys=0 | (label,cfg): distinct=24 dupkeys=2 dupevents=4
7.6.1  r1-fastbuild n=30 | primaryOutput: distinct=30 dupkeys=0 | (label,cfg): distinct=24 dupkeys=2 dupevents=6
8.4.1  r1-fastbuild n=30 | primaryOutput: distinct=30 dupkeys=0 | (label,cfg): distinct=24 dupkeys=2 dupevents=6
9.2.0  r1-fastbuild n=32 | primaryOutput: distinct=32 dupkeys=0 | (label,cfg): distinct=24 dupkeys=2 dupevents=8
9.2.0  r4-flaky     n=13 | primaryOutput: distinct=13 dupkeys=0 | (label,cfg): distinct=3  dupkeys=2 dupevents=10
```

Zero duplicate `primaryOutput` values in any of the 16 streams. A single test target legitimately emits `FileWrite`, `SourceSymlinkManifest`, `RepoMappingManifest`, `SymlinkTree`, `RunfilesTree` and `TestRunner`.

`primaryOutput` is configuration-qualified by construction — its second path segment is the configuration mnemonic:

```json
{"id":{"actionCompleted":{"primaryOutput":"bazel-out/darwin_arm64-fastbuild/bin/pkg/shared.txt","label":"//pkg:shared","configuration":{"id":"2d8934052f14…"}}}}
{"id":{"actionCompleted":{"primaryOutput":"bazel-out/darwin_arm64-opt-exec/bin/pkg/shared.txt","label":"//pkg:shared","configuration":{"id":"77e8473c8c1c…"}}}}
```

The flip side: only 2 of ~30 primaryOutputs survive a `-c opt` change (`r1 po=32 r2 po=32 overlap=2` on 9.2.0), so it is useless as a build-over-build key.

### A2 — The id carries `primaryOutput` even when the action FAILED; the payload does not

The proto's "only provided for successful actions" applies to the payload `File`, not the identity string.

```
6.5.0: success+payloadPO=79 success-noPO=0 fail+payloadPO=0 fail-noPO=2
7.6.1: success+payloadPO=85 success-noPO=0 fail+payloadPO=0 fail-noPO=2
8.4.1: success+payloadPO=88 success-noPO=0 fail+payloadPO=0 fail-noPO=2
9.2.0: success+payloadPO=88 success-noPO=6 fail+payloadPO=0 fail-noPO=2
```

```json
{"id":{"actionCompleted":{"primaryOutput":"bazel-out/darwin_arm64-fastbuild/bin/fail/boom.txt","label":"//fail:boom","configuration":{"id":"2d8934052f14…"}}},
 "action":{"exitCode":1,"stderr":{...},"label":"//fail:boom","type":"Genrule","commandLine":[...],
   "failureDetail":{...},"startTime":"2026-08-22T16:56:30.851870Z","endTime":"2026-08-22T16:56:30.868870Z"}}
```

Key off the payload File and **every failed action loses its identity** — precisely the rows a build-failure viewer needs.

### A3 — Payload `primaryOutput` absence is not a success proxy (9.2.0)

```
--- 6.5.0 : 0 such events        (success == true and 'primaryOutput' not in action)
--- 7.6.1 : 0 such events
--- 8.4.1 : 0 such events
--- 9.2.0 : 2 such events
{"id":{"actionCompleted":{"primaryOutput":"bazel-out/darwin_arm64-fastbuild/bin/pkg/flaky_test.sh.runfiles","label":"//pkg:flaky_test",...}},"action":{"success":true,...,"type":"RunfilesTree"}}
```

`RunfilesTree` is new in 9.2.0; `RepoMappingManifest` is new in 7.6.1+. The mnemonic vocabulary grows between versions.

### A4 — Action timestamps do not exist on 6.5.0 or 7.6.1 at all

Field census of the `action` payload, r1-fastbuild:

```
6.5.0: {'success':28,'label':27,'primaryOutput':28,'configuration':28,'type':28,'commandLine':21}   <- no startTime/endTime
7.6.1: {'success':30,'primaryOutput':30,'configuration':30,'type':30,'label':29,'commandLine':21}   <- no startTime/endTime
8.4.1: {... 'commandLine':21,'startTime':21,'endTime':21}
9.2.0: {... 'commandLine':21,'startTime':21,'endTime':21}
```

Per-run coverage:

```
6.5.0 r1: 0/28   6.5.0 r3-fail: 0/16
7.6.1 r1: 0/30   7.6.1 r3-fail: 0/16
8.4.1 r1: 21/30  8.4.1 r3-fail: 15/16
9.2.0 r1: 21/32  9.2.0 r3-fail: 15/16
```

Even on 8/9 they appear only for spawn-executing mnemonics. By mnemonic (9.2.0 r1): `Genrule n=18 startTime=18`, `AlphaMnemonic/BetaMnemonic/MultiOut` 1 each; `FileWrite`, `SymlinkTree`, `SourceSymlinkManifest`, `RepoMappingManifest`, `RunfilesTree`, `BazelWorkspaceStatusAction` all `startTime=0`. The 21 timestamped actions are exactly the 21 with a `commandLine` — one "was a spawn" boolean explains both.

### A5 — Bazel 8.4.1 reports `endTime == startTime` for every action. Durations are all zero.

```
8.4.1 r1: 21/30 carry startTime; 21 of those have startTime==endTime
8.4.1 r3: 15/16 carry startTime; 15 of those have startTime==endTime
9.2.0 r1: 21/32 carry startTime;  0 of those have startTime==endTime
9.2.0 r3: 15/16 carry startTime;  0 of those have startTime==endTime
```

Control experiment — `genrule(name="sleep2", cmd="sleep 2; …")` and `sleep5`:

```
=== 8.4.1
   //slow:sleep2 start= 2026-08-22T16:59:01.212417Z end= 2026-08-22T16:59:01.212417Z
   //slow:sleep5 start= 2026-08-22T16:59:01.212407Z end= 2026-08-22T16:59:01.212407Z
=== 9.2.0
   //slow:sleep2 start= 2026-08-22T16:59:07.485344Z end= 2026-08-22T16:59:09.507344Z
   //slow:sleep5 start= 2026-08-22T16:59:07.485345Z end= 2026-08-22T16:59:12.506345Z
```

9.2.0 yields 2.022 s and 5.021 s; 8.4.1 yields 0.000 s for both. Also on 8.4.1 a `TestRunner` action for a test that took ~314 ms over two attempts reports `"startTime":"2026-08-22T16:56:28.099370Z","endTime":"2026-08-22T16:56:28.099370Z"` — while that same test's `testResult` events on 8.4.1 report correct per-attempt durations. The defect is confined to `ActionExecuted` timing.

### A6 — `action.exitCode` is always 1; the real code is `failureDetail.spawn.spawnExitCode`

Two genrules failing with distinct codes (`exit 7`, `exit 3`):

```json
"action": {"exitCode": 1, ..., "failureDetail": {"message": "bash failed: error executing Genrule command (from genrule rule target //fail:boom) /bin/bash -c '… exit 7'\n\nUse --sandbox_debug …", "spawn": {"code": "NON_ZERO_EXIT", "spawnExitCode": 7}}}
```

```
6.5.0: exitCode values={1: 2}   7.6.1: {1: 2}   8.4.1: {1: 2}   9.2.0: {1: 2}
```

`spawnExitCode` correctly reported 7 and 3 on every version. Independently reproduced by `aggregates` with `exit 3` → `exitCode: 1`, `spawnExitCode: 3` on all four.

Only the prose differs by version: *"error executing command"* (6.5.0), *"error executing Genrule command"* (7.6.1/8.4.1), *"error executing Genrule command (from genrule rule target …)"* (9.2.0). **Never parse it.**

### A7 — `commandLine` yes, `strategyDetails` never

By mnemonic, r1-fastbuild (9.2.0; identical for shared mnemonics on the other three):

```
AlphaMnemonic n=1 commandLine=1     BazelWorkspaceStatusAction n=1 commandLine=0
BetaMnemonic  n=1 commandLine=1     FileWrite                  n=2 commandLine=0
Genrule       n=18 commandLine=18   RepoMappingManifest        n=2 commandLine=0
MultiOut      n=1 commandLine=1     RunfilesTree               n=2 commandLine=0
                                    SourceSymlinkManifest      n=2 commandLine=0
                                    SymlinkTree                n=2 commandLine=0
```

```json
"commandLine":["/bin/bash","-c","source external/bazel_tools/tools/genrule/genrule-setup.sh; echo local > bazel-out/darwin_arm64-fastbuild/bin/pkg/local_gen.txt"]
```

Literal substring scan for `strategyDetails` across all 16 BEP files: **0 in every one**, on all four versions, including local, sandboxed and test actions. The nearest strategy signal is `testResult.executionInfo.strategy` (`"darwin-sandbox"`), which exists only for tests.

### A8 — Action events are emitted only for actions that execute; the flag gates only successes

```
7.6.1 r6-cold    events=51 action_events=13
7.6.1 r7-warm    events=27 action_events=1
7.6.1 r8-noflag  events=23 action_events=0
9.2.0 r6-cold    events=53 action_events=13
9.2.0 r7-warm    events=29 action_events=1
9.2.0 r8-noflag  events=25 action_events=0
```

But a **failing** build emits its failed actions with no flag at all (`build //fail:all --keep_going`, 9.2.0): `action events without --build_event_publish_all_actions on a FAILING build: 2`.

Absence of an action row means "not executed this invocation" (cache hit) — never "failed" and never "missing".

### A9 — A retried action appears once; retries live only on `testResult`

`test //pkg:flaky_test //pkg:pass_test --flaky_test_attempts=3`, test scripted to fail once then pass:

```
6.5.0: TestRunner action events for //pkg:flaky_test = 1 ; testResult attempts = [1, 2]
7.6.1: = 1 ; [1, 2]      8.4.1: = 1 ; [1, 2]      9.2.0: = 1 ; [1, 2]
```

The one action event reports `"success": true` despite the failed attempt. `--runs_per_test=3` (9.2.0) likewise did not duplicate: `9 action events, 9 distinct primaryOutput, dups=[]`, disambiguated by path (`run_1_of_3/test.log`, …).

### A10 — Labels can be canonical `@@repo//…`

```
6.5.0: 25 distinct labels; non-"//"-prefixed: []
7.6.1: 25 distinct labels; non-"//"-prefixed: []
8.4.1: 27 distinct labels; non-"//"-prefixed: []
9.2.0: 28 distinct labels; non-"//"-prefixed: ['@@bazel_tools//tools:internal_platform']
```

Main-repo targets were `//pkg:leaf0` on every version — never `@@//pkg:leaf0` — even though 6.5.0 ran in WORKSPACE mode (`execroot/p3`) and 7.6.1+ under bzlmod (`execroot/_main`). This workspace had no external-repo build actions, so the 1-in-28 rate is a floor, not a typical rate.

### A11 — `stderr` is a URI into a directory that gets deleted

```json
"stderr":{"name":"stderr","uri":"file:///private/var/tmp/_bazel_holtherndon/fe9889ea…/execroot/p3/bazel-out/_tmp/actions/stderr-3"}
```

Present only when the action actually wrote to stderr — the sibling failure `//fail:boom_after_write` (which wrote nothing) has no `stderr` key (`r3-fail: 16 action events, 2 failures, 'stderr': 1`). The text is **not** in the BEP. For an archived `.json`, the only surviving copy of the failing command is `failureDetail.message`.

---

## 7. Entity: File sets and artifacts

### Field matrix — `namedSet` id + `namedSetOfFiles` payload

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.namedSet.id` | Y | Y | Y | Y | dense decimal **string**, `0..N-1`, per-invocation |
| `namedSetOfFiles.files[]` | cond | cond | cond | cond | may be empty (pure-nesting set) |
| `namedSetOfFiles.fileSets[]` | cond | cond | cond | cond | may be empty (leaf set) |

### `File` message shape — differs by where the File appears

| Field | in `namedSetOfFiles.files` | in `completed.importantOutput` | in `completed.directoryOutput` | in `action.primaryOutput` | in `testResult.testActionOutput` | in `testSummary.passed/failed` |
|---|---|---|---|---|---|---|
| `name` | Y | Y | Y (the dir) | — | Y (always `"test.log"`/`"test.xml"`) | **—** |
| `uri` | Y | Y | **—** | Y | Y | Y |
| `pathPrefix` | Y (generated) / **—** (source) | Y | Y | — | — | — |
| `digest` | Y | Y | Y (**tree** digest) | — | — | — |
| `length` | Y (string int64) | Y | **—** | — | — | — |

**There is no single File shape.** A normalizer that requires `name`, or requires `uri`, or requires `length`, will drop rows.

Generated file, verified against disk:

```json
{"name":"pkg/many5.txt","uri":"file:///…/ob-9/execroot/_main/bazel-out/darwin_arm64-fastbuild/bin/pkg/many5.txt","pathPrefix":["bazel-out","darwin_arm64-fastbuild","bin"],"digest":"b4452f9736c522e055b1f98d64f349a1bf56a156a7c3f9f7a0539f51e5518565","length":"6"}
```

> file exists, size 6, contents `b'many5\n'`, `hashlib.sha256` = `b4452f97…` — exact match.

Source file — **no `pathPrefix` key at all**, and the uri points into the source tree:

```json
{"name":"pkg2/src.txt","uri":"file:///…/p3-depsets/ws/pkg2/src.txt","digest":"e5d53b3ad5222924d553116e1e5a18dabd3b17f04ca0ddd74297a0bc3586234f","length":"13"}
```

Tree artifact (`declare_directory`), 9.2.0 — reported **twice**, once as a directory and once as expanded children:

```json
"directoryOutput": [{"digest":"211028f30c253881adae2254619f98781d5a88ddef61eb927d665568215db2e5","name":"pkg/treedir.d","pathPrefix":["bazel-out","darwin_arm64-fastbuild","bin"]}]
```

while the referenced set contains `pkg/treedir.d/one.txt` (digest `2c8b08da…`, length `"4"`) and `pkg/treedir.d/two.txt`. Key-shape survey: `directoryOutput shapes : {"dirout:['digest','name','pathPrefix']": 3}` on all four — never `uri`, never `length`. Naively summing `directoryOutput` plus expanded files double-counts.

### F1 — The named-set graph is a real DAG with sharing and depth

```
namedSetOfFiles events: 49 dup-id defs: {}
total File entries across all sets: 76
max nesting depth: 9  deepest ids: ['48']
total references: 72
ref contexts: Counter({'.completed.outputGroup[].fileSets': 50, '.namedSetOfFiles.fileSets': 22})
shared sets (referenced by >1 parent): count=18 [('36',4), ('25',3), ('40',3), ('45',3), ('31',2), ...]
max in-degree: 4
```

Identical (49 sets / depth 9 / 18 shared / in-degree 4) on all four versions. A `--keep_going` build of two packages reached 61 sets and depth 10; an interrupted build reached 90 sets and 113 references. Sharing is cross-target: `//pkg:gen0` and `//pkg:fg0` both name set id `"23"`.

A set can be both: pure nesting —

```json
{"id":{"namedSet":{"id":"32"}},"namedSetOfFiles":{"fileSets":[{"id":"33"},{"id":"25"}]}}
```

— and mixed (Starlark `depset(direct=…, transitive=…)`), where **10 of 19 sets had both `files` and `fileSets`**:

```json
{"id":"4","files":[{"name":"pkg2/m0.direct","uri":"…","pathPrefix":["bazel-out","darwin_arm64-fastbuild","bin"],"digest":"6a93d091…","length":"13"}],"fileSets":[{"id":"5"}]}
```

Nested Starlark case, verified identical on 6.5.0 and 9.2.0, showing set `2` reachable from both `1` and `4`:

```
namedSet id=2 keys=['files']            files=['l1_0.txt','l1_1.txt','l1_2.txt'] fileSets=[]
namedSet id=3 keys=['files']            files=['l2_0.txt','l2_1.txt','l2_2.txt'] fileSets=[]
namedSet id=1 keys=['fileSets','files'] files=['mid1_own.txt']  fileSets=['2','3']
namedSet id=4 keys=['fileSets','files'] files=['mid2_own.txt']  fileSets=['2']
namedSet id=0 keys=['fileSets','files'] files=['top_own.txt']   fileSets=['1','4']
completed //nest:top [{"name":"default","fileSets":[{"id":"0"}]}]
```

### F2 — Ids are dense, per-stream, and **reshuffled between runs**

```
bep-7.6.1-run1.json      n=49 dense 0..n-1: True  min/max: 0 48  identical-content-different-id: 0
bep-9.2.0-interrupt.json n=90 dense 0..n-1: True  min/max: 0 89  identical-content-different-id: 0
bep-9.2.0-par.json       n=62 dense 0..n-1: True  min/max: 0 61  identical-content-different-id: 0
```

Across all 43 streams (1,335 definitions) `dup-id defs: {}` every time. But comparing run1 to a warm no-op run2 of the *identical* workspace: **49 of 49 ids differ in content**, and after `bazel clean`, 48 of 49. Corroborated by `targets-configs`: in one invocation set `"1"` held `multi.extra1.txt + multi.extra2.txt`; in another it was `//pkg:multi`'s default group.

### F3 — `--build_event_max_named_set_of_file_entries` reshapes the graph; the default changed at Bazel 8

Sweep on one 30-file depset, identical on 7.6.1 and 9.2.0:

```
max=0  rc=0  sets=1  maxfiles=30 maxsubsets=0  (no chunking)
max=1  rc=0  sets=1  maxfiles=30 maxsubsets=0  (no chunking — <=1 silently means unlimited)
max=2  rc=37 sets=22 maxfiles=2  maxsubsets=2  (CRASH)
max=3  rc=0  sets=15 maxfiles=3  maxsubsets=3  depth=4, total file entries=30
max=4  rc=0  sets=11 maxfiles=4  maxsubsets=4  depth=3, total file entries=30
max=10 rc=0  sets=4  maxfiles=10 maxsubsets=3  depth=2, total file entries=30
```

The `max=2` crash, identical exit 37 on all four versions:

```
FATAL: bazel crashed due to an internal error.
java.lang.AssertionError: 1
  at com.google.devtools.build.lib.runtime.NamedArtifactGroup.expandSet(NamedArtifactGroup.java:175)
  at com.google.devtools.build.lib.runtime.BuildEventStreamer.maybeReportArtifactSet(BuildEventStreamer.java:493)
```

Defaults from `help build`: **`-1` (unlimited) on 6.5.0 and 7.6.1; `5000` on 8.4.1 and 9.2.0.** Real large-repo captures from Bazel 8+ *will* contain synthetic chunk sets corresponding to no Starlark depset. Chunking changes only the graph shape — total File entries stayed at 76 in the full-workspace comparison at `max=5` (49 sets → 56).

### F4 — `name` alone is not a unique file identity

A Starlark rule depending on `//pkg:gen0` both normally and via `cfg="exec"`:

```
9.2.0 DUPLICATE name across roots: pkg/out0_a.txt [('bazel-out','darwin_arm64-fastbuild','bin'), ('bazel-out','darwin_arm64-opt-exec','bin')]
9.2.0 DUPLICATE name across roots: pkg/out0_b.txt [same two prefixes]
9.2.0 distinct names: 2   (4 File entries, 2 distinct names)
```

### F5 — `uri` is not portable and must never be a key

Same file, same digest `4643971e132a2e3b91b1d156dd701a5ef608d2f48086de8423ea57ac88cdfabd`:

```
6.5.0 file:///private/var/tmp/_bazel_holtherndon/61e80cad…/execroot/bepws/bazel-out/darwin_arm64-fastbuild/bin/pkg/multi.main.txt
7.6.1 file:///private/var/tmp/_bazel_holtherndon/61e80cad…/execroot/_main/bazel-out/darwin_arm64-fastbuild/bin/pkg/multi.main.txt
9.2.0 file:///Users/holtherndon/Library/Caches/bazel/_bazel_holtherndon/61e80cad…/execroot/_main/bazel-out/darwin_arm64-fastbuild/bin/pkg/multi.main.txt
```

In all three, `name` is `pkg/multi.main.txt` and `pathPrefix` is `["bazel-out","darwin_arm64-fastbuild","bin"]`. The execroot segment is the `workspace()` name on 6.5.0 and `_main` under bzlmod on 7.6.1+; the output base root moved to `~/Library/Caches/bazel` on 9.2.0.

### F6 — Cached rebuilds do not shrink the depset graph

7.6.1 warm rerun: total events 297 → 221 (action events vanished) but `namedSetOfFiles` stayed at **49 with 72 references**. Same count on 6.5.0, 8.4.1 and 9.2.0. Ingest cost per invocation is dominated by a fixed depset payload, not by executed work.

---

## 8. Entity: Tests

### Field matrix — `testResult`

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `id.testResult.label` | Y | Y | Y | Y | |
| `id.testResult.configuration.id` | Y | Y | Y | Y | |
| `id.testResult.run` | Y | Y | Y | Y | **always present, always ≥1** |
| `id.testResult.shard` | Y | Y | Y | Y | always present, always ≥1 |
| `id.testResult.attempt` | Y | Y | Y | Y | always present, always ≥1 |
| `testResult.status` | Y | Y | Y | Y | `PASSED`/`FAILED`/`TIMEOUT` observed; **never `FLAKY`** |
| `testResult.statusDetails` | — | — | — | — | 0/158 events |
| `testResult.cachedLocally` | cond | cond | cond | cond | `true` only |
| `testResult.testAttemptStart(MillisEpoch)` | Y | Y | Y | Y | both spellings |
| `testResult.testAttemptDuration(Millis)` | Y | Y | Y | Y | both spellings |
| `testResult.testActionOutput[]` | Y | Y | Y | Y | `test.log` + `test.xml`, `{name, uri}` |
| `executionInfo.strategy` | Y | Y | Y | Y | `"darwin-sandbox"` locally |
| `executionInfo.timingBreakdown` | Y | Y | Y | Y | 8 fixed children |
| `executionInfo.exitCode` | **—** | **—** | cond | cond | added between 7.6.1 and 8.4.1 |
| `executionInfo.cachedRemotely` | ? | ? | ? | ? | never observed |

**The only real drift in the testResult payload is `exitCode`:**

```
executionInfo.exitCode               -        -      YES      YES
executionInfo.strategy             YES      YES      YES      YES
executionInfo.timingBreakdown      YES      YES      YES      YES

executionInfo key union:
6.5.0: ['strategy', 'timingBreakdown']        8.4.1: ['exitCode', 'strategy', 'timingBreakdown']
7.6.1: ['strategy', 'timingBreakdown']        9.2.0: ['exitCode', 'strategy', 'timingBreakdown']
```

```
9.2.0  //t:fail_test  run 1 shard 1 att 1  FAILED  exit=1
7.6.1  //t:fail_test  run 1 shard 1 att 1  FAILED  exit=None
9.2.0  //t:always_fail_flaky_attr_test     FAILED  exit=3     (script does `exit 3`)
9.2.0  F-timeout: status='TIMEOUT' exitCode=142
7.6.1  F-timeout: status='TIMEOUT' exitCode=None
```

`exit_code` NULL therefore means "Bazel too old" on 6/7 and "exit code was 0" on 8/9 — the version must gate the rendering.

Composite id, `out-9.2.0/B-shardrun.json`:

```json
{"id": {"testResult": {"attempt": 1, "configuration": {"id": "8d0dc878722abdb5999ab38453510fb8c3c3811a392fb47b3c7b3c266b88b4ea"}, "label": "//t:sharded_test", "run": 2, "shard": 3}}}
```

158 testResult events scanned across all four versions: `id fields missing/zero: NONE`, zero duplicate `(run, shard, attempt)` keys.

### Field matrix — `testSummary`

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `overallStatus` | Y | Y | Y | Y | `PASSED`/`FAILED`/`FLAKY`/`TIMEOUT` — **different domain from `testResult.status`** |
| `totalRunCount` | Y | Y | Y | Y | == number of testResult events |
| `runCount` | Y | Y | Y | Y | distinct `run` ids |
| `shardCount` | cond | cond | cond | cond | **absent for unsharded tests** |
| `attemptCount` | Y | Y | Y | Y | max attempts on any one shard — **not total retries** |
| `totalNumCached` | cond | cond | cond | cond | |
| `firstStartTime(Millis)` | Y | Y | Y | Y | excludes failed retries |
| `lastStopTime(Millis)` | Y | Y | Y | Y | |
| `totalRunDuration(Millis)` | Y | Y | Y | Y | excludes failed retries |
| `passed[]` / `failed[]` | cond | cond | cond | cond | Files with **`uri` only** |

### TS1 — Counter semantics, verified with zero violations (144 checks × 4 versions)

```
[C-full.json] //t:sharded_test
   testResult events=6  distinct runs=2 shards=3  max attempts in one shard=2
   summary: totalRunCount=6 runCount=2 shardCount=3 attemptCount=2 overallStatus=PASSED
     totalRunCount == #testResult events?      True
     attemptCount == max attempts per shard?   True
     runCount == distinct run ids?             True

[C-full.json] //t:fail_test
   testResult events=6  distinct runs=2 shards=1  max attempts in one shard=6
   summary: totalRunCount=6 runCount=2 shardCount=None attemptCount=6
```

`//t:sharded_test` has `attemptCount=2` even though **every** attempt number in its ids is 1 — `attemptCount` counts test actions per shard, not retry depth.

**Six events arise two different ways** — 3 shards × 2 runs × 1 attempt, or 1 shard × 2 runs × 3 attempts. Only the id triple disambiguates:

```
//t:sharded_test (6 events)   run/shard/att: (1,1,1)(1,2,1)(1,3,1)(2,1,1)(2,2,1)(2,3,1) all PASSED
//t:fail_test    (6 events)   run 1: att 1,2,3 FAILED; run 2: att 1,2,3 FAILED
```

`--flaky_test_attempts` is a **global** cap applied to all tests, not only `flaky = True` ones — `//t:fail_test` has no flaky attribute yet got 3 attempts.

### TS2 — `testSummary` timing EXCLUDES failed retries

Two hypotheses proved with 144 checks, 0 violations, all four versions:

```
H1: testSummary.totalRunDurationMillis == sum of LAST attempt duration per (run,shard)
H2: testSummary.firstStartTimeMillis   == min start of LAST attempt per (run,shard)
```

The resulting drift:

```
DRIFT 7.6.1 A-flaky.json //t:flaky_test: summary.firstStartTime is 243ms LATER than earliest attempt start
DRIFT 7.6.1 C-full.json  //t:fail_test:  579ms LATER
DRIFT 8.4.1 C-full.json  //t:always_fail_flaky_attr_test: 747ms LATER
DRIFT 9.2.0 A-flaky.json //t:flaky_test: 415ms LATER

//t:flaky_test   totalRunDurationMillis=43   sum(all attempt durs)=246   durations=[203, 43]
//t:fail_test    totalRunDurationMillis=84   sum(all attempt durs)=1121  durations=[483,483,36,35,44,40]
```

A Gantt bar built from summary bounds visually clips the failed attempts that are the entire reason the user is looking. The two numbers disagree by **13×** on a 6-attempt test.

### TS3 — Cached tests replay timestamps from BEFORE the build started

```
CACHED 6.5.0 D-cached.json //t:slow_test: buildStarted=1787417763758 testAttemptStart=1787417761653 -> 2105ms BEFORE buildStarted
CACHED 7.6.1 D-cached.json //t:pass_test: 2098ms BEFORE buildStarted
CACHED 8.4.1 D-cached.json //t:pass_test: 2106ms BEFORE buildStarted
CACHED 9.2.0 D-cached.json //t:slow_test: 2104ms BEFORE buildStarted
```

Byte-identical replay against the preceding uncached run:

```
D-warm1 : firstStartTimeMillis=1787417728465 totalRunDurationMillis=37 totalNumCached=None cachedLocally=[None]
D-cached: firstStartTimeMillis=1787417728465 totalRunDurationMillis=37 totalNumCached=1    cachedLocally=[True]
```

The cached `//t:slow_test` still reports `totalRunDurationMillis: 2044` inside a build whose own `INFO: Elapsed time: 0.058s`.

### TS4 — `FLAKY` is a summary-only status; attempts carry their own truth

```json
{"id":{"testResult":{"attempt":1,...,"run":1,"shard":1}},
 "testResult":{"status":"FAILED","testAttemptDurationMillis":"277",
   "testActionOutput":[{"name":"test.log","uri":"file://…/flaky_test/test_attempts/attempt_1.log"}]}}
{"id":{"testResult":{"attempt":2,...,"run":1,"shard":1}},
 "testResult":{"status":"PASSED","testAttemptDurationMillis":"40",
   "testActionOutput":[{"name":"test.log","uri":"file://…/flaky_test/test.log"}]}}
```

```
//t:flaky_test: {"attemptCount": 2, "overallStatus": "FLAKY", "runCount": 1, "totalRunCount": 2, "totalRunDurationMillis": "40"}
    passed[1] failed[1]
      passed: t/flaky_test/test.log
      failed: flaky_test/test_attempts/attempt_1.log

testResult.status:         PASSED, FAILED, TIMEOUT
testSummary.overallStatus: PASSED, FAILED, FLAKY, TIMEOUT
```

Both logs are named literally `"test.log"` in `testActionOutput[].name`; only the **uri path** distinguishes a retry log from a final log.

`testSummary.failed[]` is not a list of distinct failures — it mixes final and per-attempt logs (6 entries for 2 runs × 3 attempts):

```
//t:fail_test failed[6]:
   fail_test/run_2_of_2/test.log
   run_2_of_2/test_attempts/attempt_1.log
   run_2_of_2/test_attempts/attempt_2.log
   fail_test/run_1_of_2/test.log
   run_1_of_2/test_attempts/attempt_1.log
   run_1_of_2/test_attempts/attempt_2.log
```

### TS5 — `targetCompleted.success` is TRUE for a test that FAILED

```
targetCompleted //t2:big_output_test  success=True keys=['outputGroup','success','tag','testTimeout','testTimeoutSeconds']
   (its testResult status='FAILED', its testSummary overallStatus='FAILED')
```

`success` means "the target built", never "the test passed". TargetComplete payload keys for **test** targets, per version:

```
6.5.0: ['importantOutput','outputGroup','success','testTimeout','testTimeoutSeconds']
7.6.1: ['importantOutput','outputGroup','success','tag','testTimeout','testTimeoutSeconds']
8.4.1: ['outputGroup','success','tag','testTimeout','testTimeoutSeconds']
9.2.0: ['outputGroup','success','tag','testTimeout','testTimeoutSeconds']
```

`testTimeout` is a Duration string (`"300s"`, `"5s"`) and `testTimeoutSeconds` an int64-as-string (`"300"`, `"5"`) — both emitted, same value.

### TS6 — A test target that fails to build produces no test rows at all

`bazel test //t2:unbuildable_test //t2:ok_test --keep_going`, 9.2.0:

```
targetCompleted //t2:unbuildable_test  success=None keys=['failureDetail','outputGroup','tag']
  failureDetail.message="bash failed: error executing Genrule command (from genrule rule target //t2:broken_gen) … exit 7"
targetCompleted //t2:ok_test           success=True  keys=['outputGroup','success','tag','testTimeout','testTimeoutSeconds']
testSummary //t2:ok_test: {…"overallStatus": "PASSED"…}     <- only ok_test gets a summary
```

No `testResult`, no `testSummary` for the unbuildable test. The normalizer needs an explicit "test target that never ran" state or these disappear from the UI instead of showing red.

### TS7 — Test executions double-count against the actions table

```
6.5.0 C-full.json  [WITH --build_event_publish_all_actions]  testResult=22  TestRunner actionCompleted=14  distinct (label,run,shard)=14  match=True
7.6.1 C-full.json  [WITH flag]     22 / 14 / 14  match=True
8.4.1 C-full.json  [WITH flag]     22 / 14 / 14  match=True
9.2.0 C-full.json  [WITH flag]     22 / 14 / 14  match=True
7.6.1 D-cached.json [WITHOUT flag]  testResult= 2  TestRunner actionCompleted= 0
7.6.1 E-cachedruns  [WITHOUT flag]  testResult= 3  TestRunner actionCompleted= 0
```

`attempt_N.log in actionCompleted primaryOutput? False` on every file. The action id has no run/shard/attempt — the only discriminator is the path:

```json
{"primaryOutput": "bazel-out/darwin_arm64-fastbuild/testlogs/t/sharded_test/shard_2_of_3_run_2_of_2/test.log", "label": "//t:sharded_test", "configuration": {"id": "b3fcd488…"}}
```

Mnemonic census (7.6.1 C-full): `{'BazelWorkspaceStatusAction': 1, 'FileWrite': 4, 'SourceSymlinkManifest': 4, 'RepoMappingManifest': 4, 'SymlinkTree': 4, 'TestRunner': 14}`.

`timingBreakdown` children, identical on all four versions, safe to model as fixed columns:

```
['executionWallTime','fetchTime','networkTime','parseTime','processOutputsTime','queueTime','setupTime','uploadTime']
each: {"name": "...", "time": "0.203s"}, under a totalTime parent
```

---

## 9. Entity: Build-level metrics, `started`, `finished`, and termination

### M1 — `buildMetrics` is always emitted, exactly once

```
buildMetrics events per stream (version,count)->streams:
{('6.5.0', 1): 11, ('7.6.1', 1): 10, ('8.4.1', 1): 10, ('9.2.0', 1): 12}
```

Across 43 streams covering success, no-op rebuild, cold-server/warm-cache, action failure, analysis failure, BUILD syntax error, downstream-of-failure with and without `--keep_going`, and SIGINT. **Presence is guaranteed; contents are not.**

### Sub-message availability matrix

| `buildMetrics` member | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 | Note |
|---|---|---|---|---|---|
| `actionSummary` | Y | Y | Y | Y | |
| `artifactMetrics` | Y | Y | Y | Y | |
| `buildGraphMetrics` | Y | Y | Y | Y | |
| `cumulativeMetrics` | Y | Y | Y | Y | |
| `memoryMetrics` | Y | Y | Y | Y | garbage only by default |
| `packageMetrics` | Y | Y | Y | Y | `{}` on early failure |
| `targetMetrics` | Y | Y | Y | Y | `{}` on early failure |
| `timingMetrics` | Y | Y | Y | Y | |
| `workerPoolMetrics` | **—** | Y | Y | Y | always `{}` in these runs |
| `networkMetrics` | **—** | **—** | Y | Y | |
| `dynamicExecutionMetrics` | **—** | **—** | Y | Y | always `{}` |
| `remoteAnalysisCacheStatistics` | **—** | **—** | **—** | Y | always `{}` |
| `buildGraphMetrics.builtValues/evaluatedValues/dirtiedValues/changedValues/cleanedValues` | **—** | **—** | Y | Y | |
| `actionSummary.actionData[].actionsCreated` | **—** | **—** | Y | Y | see M4 |
| `actionSummary.actionData[].systemTime/userTime` | **—** | Y | Y | Y | |
| `actionSummary.runnerCount[].execKind` | **—** | Y | Y | Y | |

Top-level key sets: 6.5.0 has 8 sub-messages, 7.6.1 has 9, 8.4.1 has 11, 9.2.0 has 12.

### `timingMetrics` matrix

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| `wallTimeInMs` | Y | Y | Y | Y |
| `cpuTimeInMs` | Y | Y | Y | Y |
| `analysisPhaseTimeInMs` | Y | Y | Y | Y |
| `executionPhaseTimeInMs` | **—** | Y | Y | Y |
| `actionsExecutionStartInMs` | **—** | **—** | Y | Y |
| `criticalPathTime` | **—** | **—** | **—** | Y (Duration string) |

Measured with a genuinely long execution phase (6 genrules each `sleep 1`) so that no field could be omitted for being zero:

```
6.5.0 {"analysisPhaseTimeInMs":"11","cpuTimeInMs":"447","wallTimeInMs":"1164"}
7.6.1 {"analysisPhaseTimeInMs":"4","cpuTimeInMs":"394","executionPhaseTimeInMs":"1048","wallTimeInMs":"1200"}
8.4.1 {"actionsExecutionStartInMs":"111","analysisPhaseTimeInMs":"6","cpuTimeInMs":"452","executionPhaseTimeInMs":"1041","wallTimeInMs":"1206"}
9.2.0 {"actionsExecutionStartInMs":"92","analysisPhaseTimeInMs":"9","cpuTimeInMs":"408","criticalPathTime":"1.037770417s","executionPhaseTimeInMs":"1041","wallTimeInMs":"1189"}
```

**An "analysis vs execution" phase breakdown is impossible for 6.5.0.**

### M2 — `memoryMetrics` heap numbers are flag-gated on all four versions

Default build, 9.2.0: `"memoryMetrics": {"garbageMetrics": [{"garbageCollected": "22080", "type": "CodeHeap 'non-profiled nmethods'"}, ...]}` and nothing else.

Same invocation plus `--memory_profile=out/<v>/memprof.txt`:

```
6.5.0 {"peakPostGcHeapSize":"108834992","peakPostGcTenuredSpaceHeapSize":"27276408","usedHeapSizePostBuild":"27276408"}
9.2.0 {"peakPostGcHeapSize":"116092472","peakPostGcTenuredSpaceHeapSize":"24341064","usedHeapSizePostBuild":"24341064"}
```

Flag-gated, not version-gated. Do not synthesize a heap number from `garbageMetrics`.

### M3 — "Actions executed" excludes cache hits

Cold server + warm on-disk action cache (`bazelisk shutdown`, then rebuild). All four versions:

```
actionsCreated(top): 8   actionsExecuted(top): 1   acStats.misses: 1  hits: 7
artifactMetrics.outputArtifactsFromActionCache: {"sizeInBytes": "72", "count": 7}
Bazel stderr: INFO: Build completed successfully, 1 total action
```

### M4 — On 6.5.0/7.6.1 a fully-cached mnemonic disappears from `actionData` entirely

Same cold-server/warm-cache run:

```
6.5.0 / 7.6.1: actionData: [{"mnemonic":"BazelWorkspaceStatusAction","actionsExecuted":"1"}]
               (Genrule, FileWrite and CustomThing vanished)
8.4.1 / 9.2.0: [{"mnemonic":"BazelWorkspaceStatusAction","actionsExecuted":"1","actionsCreated":"1"},
                {"mnemonic":"FileWrite","actionsCreated":"2"},
                {"mnemonic":"CustomThing","actionsCreated":"2"},
                {"mnemonic":"Genrule","actionsCreated":"3"}]
```

A "work by mnemonic" chart is only complete on 8.4.1+; on 6/7 it silently shows only the cache misses.

### M5 — `actionsCreated` can be smaller than `actionsExecuted`

Action-failure run on a warm server: 6.5.0 and 9.2.0 both reported `actionsExecuted=3 actionsCreated=2`. The inverse also occurs — in a fully-cached no-op rebuild on 9.2.0, top-level `actionsCreated` is **absent** (zero) while the `actionData[]` entries sum to 8 created. Never render "X of Y actions executed" from these two; never assume the top-level equals the sum of the per-mnemonic values.

### M6 — On loading/analysis failure the counts are genuinely not in the stream

```
9.2.0 fail_dep:    targetMetrics={} packageMetrics={} timing={"cpuTimeInMs":"119","criticalPathTime":"0.001700958s","wallTimeInMs":"33"}
6.5.0 fail_syntax: targetMetrics={} packageMetrics={} timing={"cpuTimeInMs":"68","wallTimeInMs":"20"}
```

The interrupt-during-analysis run that emitted **12,000** `targetConfigured` aborts still reported `targetMetrics= {}`.

### M7 — `wallTimeInMs` ≠ `finishTimeMillis − startTimeMillis`

```
9.2.0 success: start 1787417730431, finish 1787417733979, delta 3548 ms, wallTimeInMs 2857  (gap 691 ms)
8.4.1 success: delta 4024, wallTimeInMs 3109                                                 (gap 915 ms)
9.2.0 heavy  : delta 1158, wallTimeInMs 1189                                                 (wallTime EXCEEDS delta by 31 ms)
```

`buildToolLogs` publishes the client-visible number separately, and it matches the millis delta exactly: `name='elapsed time' contents='3.548000'` (9.2.0), `'4.024000'` (8.4.1), `'2.367000'` (7.6.1), `'0.291000'` (6.5.0). Also `cpuTimeInMs` can exceed `wallTimeInMs` under parallelism (9.2.0 interrupt-during-analysis: 6211 vs 594).

### `started` / `finished` matrix

| Field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---|---|---|---|
| `started.uuid`, `buildToolVersion`, `command`, `workingDirectory`, `workspaceDirectory`, `serverPid`, `optionsDescription`, `startTime`, `startTimeMillis` | Y | Y | Y | Y |
| `started.host`, `started.user` | **—** | **—** | **—** | Y |
| `finished.finishTime`, `finishTimeMillis` | Y | Y | Y | Y |
| `finished.exitCode.name` | Y | Y | Y | Y |
| `finished.exitCode.code` | cond | cond | cond | cond (absent on SUCCESS) |
| `finished.overallSuccess` | cond | cond | cond | cond (**`true` only, never `false`**) |
| `finished.failureDetail` | cond | cond | cond | cond |
| `finished.anomalyReport` | **—** | **—** | **—** | **—** (0 of 43 streams) |

`overallSuccess` scan across 43 streams:

```
{('6.5.0','True'):5, ('6.5.0','<ABSENT>'):6, ('7.6.1','True'):4, ('7.6.1','<ABSENT>'):6,
 ('8.4.1','True'):4, ('8.4.1','<ABSENT>'):6, ('9.2.0','True'):5, ('9.2.0','<ABSENT>'):7}
```

The literal `false` never appears. `exitCode` pairs seen: `('SUCCESS','<ABSENT>') 16`, `('BUILD_FAILURE',1) 16`, `('PARSING_FAILURE',1) 4`, `('INTERRUPTED',8) 4`.

`workingDirectory` and `workspaceDirectory` are genuinely different — running from `ws-9.2.0/good/` gave `workingDirectory = …/ws-9.2.0/good`, `workspaceDirectory = …/ws-9.2.0`. Path-relativization must key off the workspace directory.

`optionsDescription` on 9.2.0 was polluted with four `--flag_alias` entries Bazel injected itself; it is not a faithful record of the user's command line. Use `structuredCommandLine`.

### X1 — `exitCode.name` is NOT a failure taxonomy

| Failure kind | `aborted.reason` | `finished.exitCode.name` | `failureDetail` category |
|---|---|---|---|
| Action failed (genrule `exit 3`) | *(no aborted event at all)* | `BUILD_FAILURE` (code 1) | `spawn.code=NON_ZERO_EXIT`, `spawnExitCode=3` |
| Missing dependency | `ANALYSIS_FAILURE` | **`PARSING_FAILURE`** (code 1) | `packageLoading.code=BUILD_FILE_MISSING` |
| BUILD syntax error | `LOADING_FAILURE` | **`BUILD_FAILURE`** (code 1) | `packageLoading.code=TARGET_MISSING` |
| SIGINT | `INCOMPLETE` | `INTERRUPTED` (code 8) | `interrupted.code=INTERRUPTED` |
| Skipped sibling (nokeep) | `INCOMPLETE` | `BUILD_FAILURE` | — |

A missing dep is `PARSING_FAILURE` and a syntax error is `BUILD_FAILURE` — the opposite of intuition, identically on all four versions. Classify from `aborted.reason` plus the `failureDetail` oneof category key.

### X2 — The syntax error text exists ONLY in `progress.stderr`

BUILD file with missing commas, `build //failsyntax:oops`, all four versions produced exactly one aborted:

```json
{"id": {"pattern": {"pattern": ["//failsyntax:oops"]}},
 "aborted": {"reason": "LOADING_FAILURE", "description": "no such target '//failsyntax:oops': target 'oops' not declared in package 'failsyntax' defined by …/failsyntax/BUILD.bazel"}}
```

The actual diagnostic — `ERROR: …/failsyntax/BUILD.bazel:3:5: syntax error at 'outs': expected ,` — was found **only inside a `progress` event's `stderr` string**. The stream had 13 events total, zero `targetConfigured`, zero `targetCompleted`. Discarding progress events as noise destroys the only copy of the compiler/parser diagnostics; file:line:column is available only as unparsed prose.

### X3 — Interrupted targets say `INCOMPLETE`, not `USER_INTERRUPTED`

```json
{"reason": "INCOMPLETE", "description": "Multiple abort reasons reported: [USER_INTERRUPTED, INCOMPLETE]"}
```

Four separate interrupt experiments (SIGINT during execution; `--nobuild //many/...` over 12,000 targets interrupted at 0.30 s, 0.45 s, 0.60 s) never produced a bare `USER_INTERRUPTED` reason: all 12,000 aborts came back `{'INCOMPLETE': 12000}`. Aggregate over all 43 streams: only `ANALYSIS_FAILURE`, `INCOMPLETE`, `LOADING_FAILURE` ever appeared as a `reason`. Cancellation is detectable **only** from `finished.exitCode.name == "INTERRUPTED"` (code 8) — and `INCOMPLETE` is also the reason used for targets skipped because a sibling failed.

### X4 — An interrupted build is a complete stream, not a truncated one

Backgrounded `build //slow:slow_1 //slow:slow_2` (60 s sleeps), `kill -INT` after 8 s. Client exit 8 on all four:

```json
{"exitCode": {"code": 8, "name": "INTERRUPTED"}, "failureDetail": {"interrupted": {"code": "INTERRUPTED"}, "message": "build interrupted"}, "finishTime": …, "finishTimeMillis": …}
```

`buildMetrics` arrived fully populated (`actionsExecuted=3`; 9.2.0 timing `wallTimeInMs 8010`, `criticalPathTime "7.976713125s"`).

Even a **SIGKILL** of client+server mid-build left a well-formed stream: `bep-9.2.0-kill.json`, 390 lines, every line valid JSON, trailing newline present, 0 dangling named-set references — but no `buildFinished` and no `lastMessage`.

### X5 — Abort volume is unbounded

400 packages × 30 genrules, warm server, `build --nobuild //many/...` interrupted after 0.30 s on 9.2.0:

```
total events: 12017
top kinds: [('targetConfigured', 12000), ('structuredCommandLine', 3), ('progress', 3), ('started', 1), ...]
aborted reasons: {'INCOMPLETE': 12000}
aborted id keys: Counter({'targetConfigured': 12000})
```

Repeated at 0.45 s and 0.60 s with the same result. Abort rows scale with **target** count, not failure count.

### X6 — `--keep_going` changes whether siblings abort at all

`build //failaction:downstream //failaction:unrelated_slow`:

- default (`--nokeep_going`), all four: `unrelated_slow` gets an aborted event; `downstream` gets a `targetCompleted` with `success` absent.
- `--keep_going`, all four: **no aborted events at all**; `unrelated_slow` completes with `success=True`; `downstream` completes with `success` absent.
- both variants: `finished.exitCode={"name":"BUILD_FAILURE","code":1}`, `overallSuccess` absent.

### X7 — `actionCacheStatistics` and `runnerCount` shapes

```
9.2.0 success missDetails: [{}, {"reason":"DIFFERENT_DEPS"}, {"reason":"DIFFERENT_ENVIRONMENT"},
  {"reason":"DIFFERENT_FILES"}, {"reason":"CORRUPTED_CACHE_ENTRY"}, {"count":7,"reason":"NOT_CACHED"},
  {"count":1,"reason":"UNCONDITIONAL_EXECUTION"}, {"reason":"DIGEST_MISMATCH"}]
```

Note the leading `{}` with neither reason nor count (defaulted enum-zero row), and that the 6.5.0 array lacks `DIGEST_MISMATCH` (7 entries vs 9.2.0's 8). `hits`/`loadTimeInMs`/`saveTimeInMs` are **value-dependent, not version-gated** — they vanish when zero on every version.

```
9.2.0 runnerCount: [{"count":8,"name":"total"},{"count":3,"name":"internal"},{"count":5,"execKind":"Local","name":"darwin-sandbox"}]
6.5.0 runnerCount: [{"count":8,"name":"total"},{"count":3,"name":"internal"},{"count":5,"name":"darwin-sandbox"}]
```

`runnerCount` contains a synthetic `"total"` row — summing the array double-counts.

`buildToolLogs.log[]`, same four names on all four versions:

```
name='elapsed time'      contents='3.548000'                         <- the only machine-readable one
name='critical path'     contents='Critical Path: 0.06s, Remote (0.00% of the time): [parse: 0.00%, queue…'
name='process stats'     contents='8 processes: 3 internal, 5 darwin-sandbox.'
name='command.profile.gz' uri=file:///Users/…/command-5cb70660-….profile.gz
```

---

## 10. Contradictions (unresolved)

These are places where two experiments produced findings that cannot both be stated as written. Each is presented with both sides and a resolution *hypothesis* that has **not** been tested. Do not adopt either side as fact.

<a id="contradiction-1"></a>

### Contradiction 1 — `outputGroup.incomplete`

| Side | Experiment | Evidence |
|---|---|---|
| Never occurs | `targets-configs` | Census over five build shapes × four versions: `outputGroup entry keys : {'name': 36, 'fileSets': 36}` — *"36 entries each, zero occurrences of any other key — no `inlineFiles`, no `incomplete`."* Filed under Unverified: *"`outputGroup.incomplete` was never observed (zero occurrences across all shapes and versions)."* |
| Does occur | `depsets` | `//pkg2:m_boom` (depends on a genrule that exits 3), `--keep_going`: **9.2.0** `"completed":{"outputGroup":[{"name":"default","fileSets":[{"id":"58"}],"incomplete":true}],"failureDetail":{…}}`; **7.6.1** the same with `{"id":"60"}`. |

Untested hypothesis: the `targets-configs` build shapes never produced a target whose own output group was *partially* materialized, so the flag had no occasion to appear. **Either way the column must exist**, and byte/count roll-ups must persist it or they silently under-report on failed builds with no indication.

### Contradiction 2 — the `aborted` payload for a skipped sibling under `--nokeep_going`

| Side | Experiment | Evidence |
|---|---|---|
| Carries `reason: INCOMPLETE` | `aggregates` | All four versions: `ABORTED@targetCompleted reason=INCOMPLETE desc='' id={"label": "//failaction:unrelated_slow", …}` |
| Is the empty object | `tests` | 7.6.1 and 9.2.0, dumped verbatim: `{"aborted": {}, "id": {"targetCompleted": {"configuration": {"id": "ec0dd…"}, "label": "//t2:ok_test"}}}`, and `aborted payload shapes seen: {'{}': 2}` |

A third data point from `targets-configs` shows the same field is genuinely optional and inconsistent across shapes: `abort-analysis` on all four gave `aborted={}` on an `unconfiguredLabel` id, while `abort-nokeep` on all four gave `{"reason":"INCOMPLETE"}` on that same id type.

Consequence regardless of resolution: `reason` is **nullable** and must map to an explicit `UNKNOWN`, never be defaulted to `INCOMPLETE`.

<a id="contradiction-3"></a>

### Contradiction 3 — `completed.tag` on 6.5.0

| Side | Experiment | Evidence |
|---|---|---|
| `tag` added after 6.5.0 | `tests` | TargetComplete payload keys for test targets: `6.5.0: ['importantOutput','outputGroup','success','testTimeout','testTimeoutSeconds']` vs `7.6.1: [… ,'tag', …]`. Concluded *"`tag` ADDED after 6.5.0"*. |
| `tag` present on 6.5.0 | `targets-configs` | `tags.py`: `-- 6.5.0  //pkg:pass_test  configured.tag=['test-tag-alpha']  completed.tag=['test-tag-alpha']` |

Untested hypothesis: 6.5.0 emits only *user* tags; the `tests` workspace's targets had no `tags` attribute, so on 6.5.0 the list was empty and proto3-omitted, while 7.6.1+ injects synthetic `small`/`short`/`noflaky`/`nolocal` making it non-empty. If so the version difference is "synthetic tags added in 7.6.1", not "field added in 7.6.1" — and a normalizer built on the `tests` write-up would wrongly record 6.5.0 as unable to report tags. **Not resolved by measurement.**

<a id="contradiction-4"></a>

### Contradiction 4 — is single-pass NamedSetOfFiles resolution safe?

| Side | Experiment | Evidence |
|---|---|---|
| Yes, proven | `depsets` | 43 streams, 1,335 definitions, **1,829 references, 0 forward references, 0 undefined references, 0 child-after-parent**, four versions, including `--jobs=64`, `--keep_going` failures, SIGINT and SIGKILL. Plus: children always precede parents, so stream order is a valid reverse-topological order. |
| No, must buffer | `targets-configs` | *"This does NOT extend to NamedSetOfFiles — I did not test whether a NamedSetOfFiles event always precedes the TargetComplete referencing it, and BEP does not guarantee it, so file resolution still needs buffering or a second pass."* Also filed under its Unverified list. |

This is evidence versus absence of evidence, but the two operational recommendations are incompatible and the cautious side has a real basis: **neither experiment tested the gRPC BES transport**, and the JSON file carries no sequence numbers at all. The design in §11 satisfies both positions rather than choosing between them.

### Contradiction 5 — is `testTimeout` a reliable "this is a test target" discriminator?

| Side | Experiment | Evidence |
|---|---|---|
| Yes, on all four versions | `tests` | *"Presence of `testTimeout` on a `targetCompleted` is a reliable 'this target is a test' discriminator across all 4 versions."* All its runs used `bazel test`. |
| No — absent under `bazel build` | `targets-configs` | `fields.py`: both fields appear only in the `test-keepgoing` column (count 2, one per test target); *"The same script over the `build-keepgoing` and `build-nokeep` shapes printed no rows at all, i.e. neither field appears under `bazel build`."* |

Both are self-consistent within their command scope; the discriminator is command-dependent. `configured.testSize` (emitted under both commands) is the candidate that survives both write-ups — but that has not been cross-checked against `bazel build` of a test target in either experiment.

### Independently corroborated (raises confidence rather than lowering it)

- The `--legacy_important_outputs` default flip at Bazel 8 — found separately by `targets-configs`, `depsets` and `tests`, on three different workspaces.
- `action.exitCode == 1` vs `failureDetail.spawn.spawnExitCode` — found separately by `action-identity` (`exit 7`/`exit 3`) and `aggregates` (`exit 3`).
- The analysis-failure abort id-key drift (6.5.0 `targetCompleted` → 7.6.1+ `targetConfigured`) — found separately by `targets-configs` and `aggregates` on different workspaces.
- The exec-config mnemonic instability across versions — found separately by `targets-configs`, `action-identity` and `depsets`.
- `system`/`none` configuration weirdness — found by `action-identity` and `targets-configs`.

---

## 11. What the schema and normalizer must do

Each item cites the finding it derives from.

1. **Record the Bazel version and the capture flag set on the session row**, read from `started.buildToolVersion` and the `structuredCommandLine` events. Every NULL in this schema is ambiguous without them. → [§2], [M1], [A8], [T1]
2. **Never coalesce a missing value to zero.** Implement three distinct absence handlings: proto3 default (store the default), version-cannot-report (store NULL + version), flag-not-passed (store NULL + flag state). → [§2]
3. **Dispatch on the payload key, never the id key**, using the explicit id-key → payload-key map. Derive the stored event-type enum from the *id* key (stable) but read the payload by its own key. → [E1], [E2]
4. **Parse int64 fields from JSON strings**, and parse `criticalPathTime`, `testTimeout`, `testAttemptDuration` and `timingBreakdown[].time` as Duration text, not as numbers. A `timingMetrics` reader that treats every field as int64 throws on 9.2.0. → [E3]
5. **Prefer the `*Millis` spellings** for all timestamps and durations; both spellings exist on all four versions and never disagree. Keep a Timestamp/Duration reader as insurance for Bazel > 9.2, but do not build a fallback *chain* that assumes one is missing. → [E4]
6. **Key configurations on `(invocation_id, configuration_id)` and nothing else.** Never dedupe or join on `mnemonic`/`cpu`/`platformName`/`makeVariable` — two byte-identical payloads with different ids are normal in every build. → [C2]
7. **All configuration payload columns nullable; `configuration_id` a free string.** `"none"` has an entirely empty payload, `"none"` and `"system"` are legal ids, and a hex/length validator would reject them. → [C3], [C4]
8. **No FK from `action.configuration_id` to `configuration.id`** — or synthesize placeholder configuration rows for undeclared ids first. `system` is dangling on every build on every version. → [C4]
9. **Read a configuration from the *id*, not the payload, and never assert they are equal** — the normalizer would throw on every build. Label may be read from either. → [C5]
10. **`is_tool` defaults to false when absent** (proto3), and is the only reliable exec-vs-target discriminator. Never derive exec-ness from the mnemonic string. → [C1]
11. **Do not treat the configuration count, or a missing exec configuration, as an anomaly.** It varies run-to-run with cache state. → [C6]
12. **Create the target row on `targetConfigured` (or on an `aborted` riding either target id), with a tri-state status** (configured / completed / aborted). Never wait for TargetComplete: in the interrupt case six targets were configured and zero completed. Rows must be creatable with `target_kind` NULL for 7.6.1+ analysis failures. → [T4], [T5], [E2]
13. **`completed.success` is NOT NULL, supplied by the parser as `success is True`.** Absence means failed, never "not yet finished". Same rule for `action.success` and `finished.overallSuccess` — the literal `false` is dead code in all three. → [T3], [A2], [X-overallSuccess]
14. **Resolve target outputs through `outputGroup[].fileSets[] → NamedSetOfFiles` as the primary and only required path.** `importantOutput` is an optional enrichment that is empty on Bazel 8 and 9 by default, and on 6/7 it duplicates data reachable through the file sets — dedupe it or skip it, or byte totals double-count. → [T1], [T2]
15. **A `target_output_group` table** (target, group name, root set id, `incomplete` flag) — not a single outputs pointer per target. Persist `incomplete`; allow zero output groups on a failed target. → [T2], [Contradiction 1]
16. **Store both tag lists separately, or record which event a tag came from.** 7.6.1+ injects synthetic size/timeout/flaky/local tags into `completed.tag` that the user never wrote. → [T7], [Contradiction 3]
17. **The action identity column is `id.actionCompleted.primaryOutput`, scoped to one invocation.** `(label, configuration)` collides heavily and must be stored as attributes. Read identity from the id, never from `action.primaryOutput` — that File is absent on every failure. `label` must be NULLABLE (absent for `BazelWorkspaceStatusAction` on 6/7/8); absent ≠ empty string. → [A1], [A2]
18. **Derive action success solely from `action.success`.** The presence of `action.primaryOutput` is not a proxy — 9.2.0's `RunfilesTree` succeeds without it. `action.type` must be free text, not an enum: the mnemonic vocabulary grows between versions. → [A3]
19. **`start_time`/`end_time` NULLABLE, plus a per-version rule: on Bazel 8.x, if `endTime == startTime`, record the duration as unknown.** Bazel 6 and 7 have no action timing at all; on 8/9 roughly a third of action events lack it; 8.4.1's timestamps are present but zero-length for 100% of actions including a 5-second sleep. Any duration aggregate must exclude unknowns rather than count them as zero, and a critical-path view must render "timing unavailable". Use `BuildMetrics`/test `timingBreakdown` for timing on 6.5.0/7.6.1/8.4.1. → [A4], [A5]
20. **Feed the UI's "exit code" from `failureDetail.spawn.spawnExitCode`, not `action.exitCode`.** Store `action.exitCode` separately as the Bazel-status field, `failureDetail.spawn.code` as the failure category, and `failureDetail.message` verbatim as the human text. Never regex the message — its wording changes on every version. → [A6], [X1]
21. **Store `command_line` as a JSON argv array; do not model `strategyDetails` at all.** It is unpopulated in stock local Bazel (0 occurrences, 16 streams, four versions). A single "was a spawn" boolean explains both `commandLine` presence and `startTime` presence on 8/9. → [A7]
22. **Absence of an action row means "not executed this invocation" (cache hit), never "failed" or "missing".** The capture path must always pass `--build_event_publish_all_actions`; user-uploaded BEPs will often have zero successful action events and need a graceful empty state that explains why. → [A8]
23. **Model attempts on a separate `test_attempt` table keyed `(label, configuration_id, run, shard, attempt)`, all NOT NULL.** A retried action appears once in the action stream and reports the final success — the action row must not claim "succeeded on the first try". Surface a duplicate `primaryOutput` as an anomaly, never upsert it silently. → [A9], [TS1]
24. **The label column must accept `@@repo//pkg:target`.** No regex anchored on `^//`; package grouping must handle the repo prefix. → [A10]
25. **`stderr` is a nullable URI into `bazel-out/_tmp/actions/` whose file will not exist later.** Capture it at ingest time or fall back to `failureDetail.message`, which is the only place the failing command and the sandbox hint appear together. `stdout` was never emitted. → [A11]
26. **Store the named-set graph as edges — `(session_id, set_id) → file` and `(session_id, set_id) → child_set_id`** — with a memoized iterative traversal and a visited set. Do not materialize per-target flattened file lists: sets are shared (in-degree up to 4 observed, depth up to 10), and expansion without memoization double-counts files and bytes. A set can have both `files` and `fileSets` non-empty (10 of 19 sets in a Starlark build). → [F1], [O1]
27. **The file-set primary key is `(session_id, named_set_id)`.** Ids are dense per-stream decimal strings that are reshuffled between runs of the same build; a global unique index cross-links one build's outputs into another's on the second ingest. Cross-run identity must come from content (path + digest), never from the id. → [F2], [O7]
28. **Implement file resolution as a single streaming pass with a deferred/tolerant FK, and fail loudly on a forward reference.** This satisfies both sides of the ordering dispute: it takes the measured guarantee (0 forward references in 1,829) as the fast path, and a forward reference — which would indicate a real Bazel change, a truncated capture, or BES-transport reordering — becomes a surfaced warning instead of a silently dropped row. Per-set roll-ups may be computed incrementally, since children always precede parents. → [O1], [Contradiction 4]
29. **A defined-but-unreferenced named set is legal, not a parse error**; an undefined reference is strong evidence of truncation and must be surfaced. → [O7]
30. **One `File` table with almost every column nullable** — or separate tables per position. `pathPrefix` is absent for source files, `uri`+`length` absent for `directoryOutput`, `name` absent in `testSummary.passed/failed`, and `action.primaryOutput` carries **only** `uri`. Identity is `(pathPrefix, name)` — the joined execroot-relative path — never `name` alone (it collides across target/exec configs) and never `uri` (machine- and version-dependent). → [F4], [F5], §7 File-shape table
31. **Decide explicitly whether a tree artifact counts as one entry or N**, and do not sum `directoryOutput` plus its expanded children. The directory digest is a tree digest and is not comparable to file digests. → §7 tree artifacts
32. **Expect synthetic chunk sets in real Bazel 8+ captures** (`--build_event_max_named_set_of_file_entries` defaults to 5000 there, `-1` on 6/7) and do not present them to users as meaningful nodes. Never emit `=2` from capture tooling — it crashes Bazel on all four versions. Treat `<= 1` as "unlimited". → [F3]
33. **Budget ingest per invocation, not per executed action.** A fully-cached no-op rebuild republishes the entire named-set graph (49 sets, 72 references, unchanged). → [F6]
34. **`test_attempt.status` and `test_target.overall_status` are different enum domains** — `FLAKY` and `TIMEOUT`-as-overall exist only on the summary. Do not share a lookup table; derive flakiness as "same `(run, shard)` has both a FAILED and a PASSED attempt", or trust `overallStatus`. → [TS4]
35. **Compute test target timeline bounds from the attempts, not from `testSummary`.** Store `testSummary.totalRunDuration` as a separate `bazel_reported_duration` column: it excludes failed retries and understated real wall time by 13× on a 6-attempt test, and `firstStartTime` lands 218–747 ms after the earliest attempt. → [TS2]
36. **`shard_count` NULLABLE, absent meaning "not sharded"; `attempt_count` is not a retry count.** Never coalesce `shardCount` to 0 (phantom zero-shard tests in a histogram) and never label `attemptCount` as retries — it equals `runCount` for a healthy multi-run test. → [TS1]
37. **Clamp or flag cached test attempts in any timeline.** Attempt timestamps legitimately predate `buildStarted` by ~2.1 s, and a "time spent testing" metric that sums durations will report 2 seconds for a 58 ms build. `cached_locally` and `total_num_cached` default to false/0 when absent (proto3), not unknown. → [TS3]
38. **Never derive test pass/fail from `targetCompleted.success`** — it is `true` for a test that failed. Join to `testSummary.overallStatus`. Store `testTimeoutSeconds` (parse the string): it is the only place the effective timeout appears and it is what makes `TIMEOUT` interpretable. → [TS5]
39. **Give test targets that never ran an explicit state**, derived from `targetCompleted`-with-`failureDetail` and from `aborted` events; there is no testResult or testSummary for them. → [TS6]
40. **Exclude or mark `type='TestRunner'` rows in the actions table** so action counts and execution-time aggregates do not double-count tests. Do not attempt to join an action row back to a test attempt by parsing `shard_N_of_M_run_X_of_Y` out of the path — key tests off `testResult` only. → [TS7]
41. **Make every `buildMetrics` sub-message and scalar optional**, and render "not reported by Bazel 6.x" rather than a zeroed panel for `networkMetrics`, `dynamicExecutionMetrics`, Skyframe counts, `executionPhaseTimeInMs`, `criticalPathTime` and `runnerCount[].execKind`. → [M1], §9 matrices
42. **Present executed and cached as two separate numbers, never a ratio.** `actionsExecuted` excludes cache hits, `actionsCreated` can be *smaller* than `actionsExecuted`, and the top-level `actionsCreated` does not equal the sum of `actionData[].actionsCreated`. A "work by mnemonic" chart is only complete on 8.4.1+. → [M3], [M4], [M5]
43. **`packages_loaded` / `targets_configured` columns nullable, never `DEFAULT 0`**, and no derived percentages from them: they are `{}` on any early failure. → [M6]
44. **Commit to one definition of "build duration" and label it.** `finishTimeMillis − startTimeMillis` is what the user saw in their terminal (and matches `buildToolLogs` `elapsed time`); `wallTimeInMs` is Bazel's internal span and disagrees by up to ~1 s. Do not average or cross-check them; `cpuTimeInMs` can exceed `wallTimeInMs`. → [M7]
45. **Do not show a heap/memory tile by default** — those fields require `--memory_profile` on all four versions. Only `garbageMetrics` is reliably present, and it is not a heap size. → [M2]
46. **Classify failures from `aborted.reason` plus the `failureDetail` oneof category key, never from `exitCode.name`.** A missing dep exits `PARSING_FAILURE` and a syntax error exits `BUILD_FAILURE`. Store `aborted.description` verbatim alongside the reason; map an absent reason to an explicit `UNKNOWN`; tolerate reason values not observed here. → [X1], [X3], [Contradiction 2]
47. **Build the failed-target set by unioning `aborted` events across ALL id key types**, keying on the label inside the id. Scanning only `targetCompleted` finds nothing on 7.6.1+; scanning only `targetConfigured` finds nothing on 6.5.0. → [T5], [E2]
48. **Retain and surface `progress.stderr`.** It is the only copy of compiler/parser diagnostics; a syntax-error build produces 13 events, zero of them structured diagnostics. → [X2]
49. **Give "Interrupted" its own status, separate from "Failed"**, driven by `finished.exitCode.name`/code 8 — because the per-target reason reads `INCOMPLETE`, the same value used for siblings skipped under `--nokeep_going`. The target tally needs three states: succeeded, failed, not-attempted. → [X3], [X4], [X6]
50. **Detect stream completeness from the `lastMessage: true` flag only.** "Saw `buildToolLogs`" and "saw `buildMetrics`" each declare premature completion on half the versions. Ingest must run past `buildFinished` to `lastMessage`, or the entire failed/skipped target list is lost. → [O5], [O6]
51. **Handle six-figure abort volumes without loading them into memory, and summarize rather than list** ("12,000 targets not built"). Abort rows scale with target count, not failure count. → [X5]
52. **Skip the leading `{}` entry in `missDetails[]`** (a defaulted enum-zero row) rather than storing a null-reason row; tolerate unknown future reason strings. Exclude the synthetic `"total"` row from `runnerCount[]` or store it distinctly. Do not infer "this version cannot report cache hits" from a single stream — absence means zero. → [X7]
53. **Do not design any UI element around `finished.anomalyReport`, `action.strategyDetails`, `action.stdout`, or `testResult.statusDetails`.** All four were absent from every stream in every experiment. Keep the columns nullable and do not treat a NULL there as a parse defect. → [X-anomaly], [A7], [A11], §12

---

## 12. Unverified

Nothing below was measured. It is listed so that no one mistakes it for a covered case.

### Transport and encoding
- **The binary BEP (`--build_event_binary_file`) was never captured.** All five experiments used `--build_event_json_file` only. Field presence should be identical since it is the same message, but proto3 default-omission behaves differently in binary vs JSON for scalar fields, so **the "absent success means false" rule must be re-confirmed against the binary stream** before the normalizer relies on it there.
- **The gRPC BES transport was never exercised.** Every ordering guarantee in [§3] — including the load-bearing NamedSetOfFiles-before-reference result — is from a local JSON file. The JSON file carries no sequence numbers at all, and how BES `sequence_number` relates to file line order, whether `lastMessage` placement changes, and whether events can arrive reordered are all open.
- Whether a hard kill can produce a **torn/partial JSON line**. One SIGKILL sample (390 lines) parsed cleanly and ended with a newline; one sample is not proof.

### Execution environment
- **No remote execution and no remote cache, ever.** Every run was local `darwin-sandbox` or `local`. Consequently: whether `strategyDetails` becomes populated; whether remotely-executed actions carry different timestamp semantics; whether `File.uri` becomes a `bytestream://` URL under `--remote_download_minimal`; whether `digest`/`length` stay populated; whether `executionInfo.cachedRemotely` appears and how it interacts with `cachedLocally`; whether `artifactMetrics` distinguishes remote from local cache hits; all remote cache hit/miss counters.
- **All measurements are darwin/arm64 (macOS 25.6.0, Apple Silicon). Linux is unverified** — in particular the 8.4.1 zero-duration defect could plausibly be platform-specific, and mnemonic sets may differ.
- **Whether the 8.4.1 `endTime == startTime` defect is present in other 8.x patch releases** (8.0–8.3, 8.5+). Only 8.4.1 was tested; write the normalizer rule defensively for all of 8.x.
- **Bazel > 9.2.0 was not tested.** The coexistence of `*Millis` and Timestamp/Duration spellings may not survive a future release.
- No worker strategies, so `workerPoolMetrics` was always `{}`. No dynamic execution, so `dynamicExecutionMetrics` was always `{}`. No remote analysis cache, so `remoteAnalysisCacheStatistics` was always `{}`.
- `--digest_function` other than the default SHA-256 (e.g. blake3) was never used.

### Graph shapes never built
- **Aspects were never exercised anywhere.** The `aspect` field of `TargetConfiguredId`/`TargetCompletedId` was never observed non-empty; how it is rendered and whether it participates in the target key is unknown. Aspect actions are a plausible place for `(label, configuration)` reuse, and the `actionsCreated` vs `actionsCreatedNotIncludingAspects` pair was never exercised with a nonzero difference. **Any schema treating `(label, configuration)` as unique should be checked against an aspect-bearing build first.**
- **No C++/Java/Python toolchains.** All workspaces were deliberately toolchain-free, so `CppCompile`/`Javac` payload fields, larger `makeVariable` maps, and toolchain-derived configurations are unmeasured.
- **`--experimental_multi_cpu`, `--platforms`, and explicit Starlark configuration transitions.** The only duplicate-configuration case came from test-config trimming, where both payloads were identical, so **two TargetComplete events for the same label with genuinely different configurations were never observed** and the two-row assumption is unconfirmed.
- **Test sharding beyond `shard_count = 3`, and `--runs_per_test` on 6.5.0/7.6.1/8.4.1** (verified only on 9.2.0 by `action-identity`; `tests` verified the 3×2 cross product on all four).
- **Build sizes were tiny** (28–32 action events; 49–90 named sets; largest named-set id observed 89). `primaryOutput` uniqueness at real scale — many external repos, multiple platforms, aspects — is extrapolated, not demonstrated. Whether named-set ids can exceed 2^31 is unknown.
- **No depset naturally exceeded 5000 entries**, so default-triggered chunking on Bazel 8/9 was never observed — only chunking forced with a small explicit flag value.
- **No action with zero outputs**, so whether `id.actionCompleted.primaryOutput` can ever be genuinely empty is unknown. It was non-empty in 354 of 354 events.
- `bazel coverage` and the `baseline.lcov` output group were never run; whether coverage introduces additional named-set references or a new referencing event type is unknown. Only `TargetComplete` and `NamedSetOfFiles` were ever seen referencing a named set, but not every event type was exercised.
- **`File.symlinkTargetPath` was never exercised.**

### Values and conditions never provoked
- **`aborted.reason` values other than `ANALYSIS_FAILURE`, `LOADING_FAILURE`, `INCOMPLETE`.** In particular a bare `USER_INTERRUPTED` could not be elicited in four separate interrupt experiments; `TIME_OUT`, `OUT_OF_MEMORY`, `REMOTE_ENVIRONMENT_FAILURE`, `SKIPPED`, `NO_ANALYZE`, `NO_BUILD` were never produced.
- **`exitCode.name` values other than `SUCCESS`, `BUILD_FAILURE`, `PARSING_FAILURE`, `INTERRUPTED`.** `COMMAND_LINE_ERROR`, `TESTS_FAILED` and OOM were not produced.
- **`TestStatus` values `NO_STATUS`, `FAILED_TO_BUILD`, `REMOTE_FAILURE`, `INCOMPLETE`** never appeared; a test target that failed to build produced **no** testResult at all rather than a `FAILED_TO_BUILD` one.
- **`testResult.statusDetails`** — absent on all 158 events across all four versions, including FAILED, TIMEOUT (exit 142) and cached results. Presumably remote/system-level only.
- **`testActionOutput` with inline `contents`** instead of a `uri` was never observed; whether Bazel ever inlines small test logs, and under which flag, is unknown.
- **`testProgress` events** never appeared in any capture.
- **`finished.anomalyReport`** — never present in 43 streams across four versions and ten outcome shapes.
- **`outputGroup.incomplete`** — see [Contradiction 1]; one experiment observed it, one did not, and the condition that sets it is not established.
- **Whether `started.host`/`user` are genuinely new in Bazel 9 or merely flag-gated off on 8.4.1.** Only observed absent on 6/7/8 and present on 9.2.0 under one identical invocation; no search was made for an enabling flag.
- **Whether `criticalPathTime` arrived in 9.0 or in a late 8.x patch.** Only "8.4.1 does not emit it, 9.2.0 does" was established.
- **Whether `directoryOutput` has a suppressing flag** analogous to `--legacy_important_outputs`. It was present by default on all four; no search was made.
- **Whether other synthetic configuration ids besides `system` can appear and dangle.**
- **`memoryMetrics` under real memory pressure or an actual OOM.**
- The `test` and `coverage` commands were not run by the `aggregates-and-failure` experiment, so its metrics findings are `build`-only; conversely `tests` ran only `test`. Cross-command differences beyond `testTimeout` ([Contradiction 5]) are unmeasured.
- **Multi-configuration aggregation for build-level metrics** — every metrics run used a single configuration.
- The `TIMEOUT` status, the build-failure-of-a-test-target case, and the empty `aborted: {}` payload were exercised on **7.6.1 and 9.2.0 only**; 6.5.0 and 8.4.1 are assumed, not verified, to behave the same.

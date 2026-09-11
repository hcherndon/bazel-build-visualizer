# Hermeticity and build reproducibility: proposed design

Status: accepted for staged implementation in
[ADR-014](adr/014-reproducibility-audits.md). Researched 2026-09-11 against the
source tree, official Bazel documentation, and a small Bazel 9.2.0 fixture.
The research below is retained as design context. The ADR takes precedence;
see the [user guide](hermeticity.md) and [implementation status](implementation-status.md)
for the initial controlled protocol and deferred features.

## Recommendation

Add a **Check reproducibility** run mode and a **Hermeticity** analysis page.
Run the same `build` twice with freshly cleaned action outputs, preserve both
captures, and compare recorded action recipes, inputs, and outputs. Design the
comparison engine for reuse by later cache, configuration, and host comparisons.

Do not call an unchanged pair "hermetic." Two executions can expose a defect,
but cannot prove independence from every undeclared file, clock, network service,
or host setting. Bazel's [hermeticity guide](https://bazel.build/basics/hermeticity)
also recommends isolation and environment-variation checks. Its
[sandboxing guide](https://bazel.build/docs/sandboxing) explains that ordinary
sandboxing does not hide the entire host filesystem.

Use three separate questions rather than one ambiguous pass/fail:

| Check | Question | Initial scope |
|---|---|---|
| Repeat-build audit | Did independently executed actions produce the same outputs for the same recorded recipe and inputs? | First release of this feature |
| Cache-reuse audit | Did unchanged cacheable work reuse the expected cache, and what changed its identity? | Follow-up using the same comparison engine |
| Environment-variation audit | Does changing an execution environment expose hidden dependencies? | Follow-up: paths, workers, hosts, sandbox/container and repository-rule checks |

Initial recommendation: `build` on the selected local or SSH workspace machine.
SSH transport is supported; a separate Bazel remote-execution cluster is a
different concern. Whether cluster execution is required immediately remains
an open product choice. Never silently switch a remote-only build to local.
`test` needs separate test-cache, sharding and retry semantics; `run` can execute
arbitrary application side effects. Neither belongs in the initial audit mode.

## What the real fixture showed

On 2026-09-11, a disposable workspace outside this repository ran four builds
with one private output base, a 1 GiB server cap and two jobs. Each build was
preceded by synchronous `clean`. User/system/workspace rc files were disabled
for this controlled experiment only. The server was shut down afterward.
No clean ran against the application's or user's normal output base.

The BUILD file contained three native genrules:

```starlark
genrule(name = "stable", outs = ["stable.txt"], cmd = "echo stable > $@")
genrule(name = "random", outs = ["random.txt"], cmd = "/usr/bin/uuidgen > $@")
genrule(name = "downstream", srcs = [":random"], outs = ["downstream.txt"],
        cmd = "cat $(location :random) > $@")
```

Runs A/B used `--disk_cache= --remote_cache= --remote_executor=`. Runs C/D
used the same initially empty private disk cache. All four used Bazel 9.2.0,
`--execution_log_json_file=<outside-output-base>/<run>.json`,
`--lockfile_mode=off`, and `--symlink_prefix=/`. JSON was a research aid;
production capture should continue preferring compact logs.

| Observation | A versus B, actual execution | C versus D, disk-cache reuse |
|---|---|---|
| Stable action | Same recorded argv, environment, inputs and output; different metrics | Same output; D was cached |
| Random action | Same argv, environment and recorded inputs; different output digest | Same output; D was cached |
| Downstream action | Same argv/environment; changed generated input and output digests | Same output; D was cached |
| Action cache digest | Absent on these cache-disabled spawns | Present with the disk cache enabled |

Platform properties were not emitted in these fixture logs; their absence is
not independent evidence that platforms are equal. All three A/B spawns used
`darwin-sandbox`; all three D spawns reported
`disk cache hit`. Thus neither bytewise log equality nor `clean` alone is a
valid reproducibility test. This fixture did not exercise Linux, SSH, workers,
remote execution, tree outputs or older Bazel versions. It is evidence for
these specific cases, not the complete compatibility gate.

## Safe run protocol

Use one app-owned output base on the workspace machine, at the **same path**
for both builds. Two different bases change a variable and should be a separate
path-sensitivity test. Keep logs and comparison evidence outside that base.

```text
Lock workspace → preflight → review → snapshot context
               → clean private base → build A → preserve A
               → clean private base → build B → preserve B
               → validate coverage → compare → report
```

1. Review the entire sequence before launch: machine, executable, targets,
   effective flags, clean scope, expected rebuild cost, cache policy and evidence
   retention. Honor ADR-007: explicit conflicts require approval, not silently
   appended overrides. Required evidence can be vetoed, but the audit then
   becomes unavailable/inconclusive rather than pretending to pass.
2. Acquire the existing canonical workspace capture lease before preflight probes
   that address workspace/private Bazel state; hold it for the whole operation.
   No clean or build starts before review. An app lease cannot prevent builds or
   edits from external terminals; say so.
3. Allocate and validate an owned output base. Confirm Bazel's resolved
   `output_base` before **every** clean, including when the executable is a
   wrapper. Reject unexpected paths, symlink escapes and conflicting startup
   options. Never delete or clean the normal workspace output base by default.
4. Prevent convenience-symlink updates and clean-time removal of the user's
   normal links. The fixture validated `--symlink_prefix=/` for 9.2.0; the
   production choice needs capability/version tests. Bazel's
   [build options](https://github.com/bazelbuild/bazel/blob/9.2.0/src/main/java/com/google/devtools/build/lib/buildtool/BuildRequestOptions.java)
   document its special behavior and newer convenience-link controls.
5. Capture source/context before A, between runs, and after B: repository state
   including dirty/untracked files, relevant rc/lock/config files, resolved Bazel
   version/executable, effective argv, execution policy and action input evidence.
   Do not require a clean Git checkout. A commit hash alone is insufficient;
   record inaccessible paths and snapshot limits. Pre/post checks cannot detect
   every transient edit, and source mutations must never be silently reverted.
6. For the initial local-execution protocol, disable disk and remote action-cache
   reads/writes through an explicitly reviewed plan, not cache deletion. Reject
   an unresolved remote-execution or remote-only strategy conflict. Preserve
   repository download caches; this does not audit repository fetching. Verify
   actual runner/cache state afterward rather than trusting flag names alone.
   For cluster support, separately validate remote cache bypass, server-side
   result publication and dynamic/fallback behavior. A client upload flag is not
   proof that a remote executor will avoid publishing results to a shared cache.
7. Use synchronous clean. Do not default to `clean --expunge` or asynchronous
   deletion. [Bazel's clean implementation](https://github.com/bazelbuild/bazel/blob/9.2.0/src/main/java/com/google/devtools/build/lib/runtime/commands/CleanCommand.java)
   distinguishes ordinary output/cache cleanup from deleting the entire output
   base and shutting down its server. Worker/server-reset policy must be explicit
   and fixture-tested; ordinary clean is not a general cold-environment guarantee.
8. Preserve A's raw logs, source hashes and required evidence completely before
   cleaning for B. On SSH, transfer success is a prerequisite for advancing.
   Recheck context before starting B; report mutation instead of assuming sameness.
9. Keep auxiliary aquery/cquery activity outside the measured A/B sequence by
   default. Existing capture finalization always runs them, so add an explicit
   auxiliary policy. Start with execution-log dependency evidence; query-derived
   configuration/source links must name their separately captured provenance.
10. Cancellation, failed clean, failed build, lost SSH command outcome or missing
    evidence stops subsequent destructive/execution steps. Preserve partial
    captures and the step log. Never auto-replay a build or clean after reconnect.
    On restart, open an interrupted audit; resuming requires a new review.

Give one coordinator ownership of the private server/output-base lifecycle,
with at most one audit server per workspace and the existing memory caps. After
the client is known to have stopped, shut down using the same verified startup
and output-base context. Clean up only that explicitly validated app-owned base;
raw sessions and audit evidence must remain outside the cleanup target. Persist
cleanup failures and orphan/interrupted ownership so crashes do not silently
leave servers and caches accumulating. If SSH leaves the command outcome unknown,
keep the base and report cleanup pending: do not replay or signal a possibly
running process through a replacement transport. Recovery needs reconciliation
and review before cleanup resumes.

Retain both ordinary sessions and identify them as A/B in the audit. Source
session retention/deletion must respect audit references and active readers.
Diagnostic files may contain secrets: compare exact raw data only through the
existing trusted-data boundary. Keep sensitive values in trusted raw sources or
an explicitly reviewed private comparison store, not ordinary queryable tables;
publish masked diagnostics/equality outcomes. Mask all UI and export paths,
including Query and ordinary table exports, and classify every new field under
the existing redaction/export rules. Treat already-redacted or withheld values
as unknown. Do not publish ordinary hashes of secret values as a supposed
redaction mechanism.

## Comparison model and findings

Compare semantics, not log bytes. Timing, invocation UUIDs, entry IDs and
scheduling order are context, not reproducibility failures. Keep exact ordered
arguments and path strings; do **not** strip arbitrary absolute paths, timestamps
inside inputs, configuration directory names or environment differences.
Those can be the cache-breaking defect itself.

Match unique action groups by output paths/sets plus label and mnemonic, with
configuration evidence as corroboration when available. Missing configuration
metadata stays explicit; it must neither borrow another session's configuration
nor exclude every otherwise unambiguously matched spawn. A unique shared output
can be a secondary anchor when an output set changes. Never match on action
digest, argv or arrival order: they are either compared values or unstable
identities. Preserve all
duplicate observations/retries; ambiguous pairing stays ambiguous. Sessions with
different configurations, platforms or expanded target sets remain inspectable
but are not presented as an unchanged-context experiment.

For each matched action, compare separately:

- Recipe: ordered argv, environment, execution-platform properties, relevant
  timeout/cacheability policy and declared outputs. Sort keyed collections only
  where semantics permit; duplicate/conflicting entries remain explicit.
- Inputs: tool membership and semantic path/content manifests, including tree
  members, symlink targets and resolved runfiles. Digest identity includes the
  algorithm and size. Missing digests are unknown unless a tested format rule
  establishes an empty file.
- Outputs: declaration/production state, kind, paths, tree contents, symlinks
  and content digests. A missing output is distinct from an empty output.
- Context: actual execution versus cache hit, outcome, action cache digest when
  supplied, timing, runner, and original evidence location.

The [Bazel 9.2 execution-log schema](https://github.com/bazelbuild/bazel/blob/9.2.0/src/main/protobuf/spawn.proto)
is important here: compact IDs and input-set structure are not canonical;
runfiles require ordered overlay rules; the action digest is not always supplied.
Do not manufacture a digest and label it Bazel's action key. Our own versioned
fingerprints are only comparison aids.

| Classification | Meaning and next action |
|---|---|
| Output divergence with equal recorded inputs | Both actions executed successfully; complete recorded recipe/inputs match but outputs differ. Strong evidence of nonreproducible execution, not proof of its hidden cause. |
| Recipe or input drift | Show the exact argv, environment, platform, tool or file change that can affect cache identity, even when outputs happen to match. |
| Downstream propagation | A changed generated input is linked to a changed producer output. Prioritize the earliest supported divergence instead of blaming every consumer independently. |
| Cache identity/policy issue | Explain observed key changes or non-cacheable policy; equal visible fields with a changed key remain an unexplained identity difference. |
| Added/removed action or output | Show structural changes, with matching confidence and source/configuration differences. |
| Inconclusive | Cached, absent, failed, ambiguous, redacted, unsupported or incomplete observations do not establish repeat execution equivalence. |
| No differences observed | Limited to successfully compared observations in these runs, with an explicit coverage denominator; never "100% hermetic." |

Difference severity and evidence coverage are independent. A partial audit can
find a real problem without becoming a complete audit. Avoid one overall green
badge hiding uncaptured/non-spawn work. Execution logs are not syscall traces;
unchanged logged inputs do not prove that there were no undeclared inputs.

## Data and service changes

The raw execution logs are the recovery source. Existing normalized tables
cannot support this reliably without new observations:

| Current code | Gap relevant to this feature |
|---|---|
| `CompactExecLogParser`, `BinaryExecLogParser` | Drop argv/platform; binary drops inputs/tools. Compact drops directory members, symlink targets and runfiles, and labels produced references as files. |
| `AttemptWriter` / schema v4 `artifacts` | Digests are interned by path with first-non-null retention, not preserved per spawn observation; digest algorithm and parts of invocation context are lost. |
| Existing environment rows | Withheld values are unknown, not proof of equality. |
| Existing BEP/action correlation | Some spawns cannot be joined; a target/mnemonic pair is not a unique spawn identity. Comparison must not depend on that join succeeding. |

Add a versioned, loss-aware execution observation index beside current navigation
data: log source/provenance, file/tree/symlink observations, ordered DAG/runfiles
references, argv/env/platform entries, spawn observations and exact output refs.
Retain unknown fields and missing references as coverage evidence. Old sessions
can be explicitly reindexed from their preserved logs; never rewrite raw files
or silently migrate a historical analysis into an apparently complete one.

Use disk-backed set traversal, external sorting and streaming merge comparison.
Preserve DAG sharing rather than duplicating every transitive input for every
spawn. Different DAG structures may denote the same manifest. Cache versioned
semantic fingerprints, and compute detailed differences lazily. All stages need
explicit byte/record/fan-out/work/disk limits, cancellation and atomic publication;
limits produce named incomplete results rather than dropped records. The current
[auxiliary-ingestion release blocker](implementation-status.md#remaining-release-blockers)
must be resolved or its bounded mechanisms incorporated before shipping this.

Keep one session per invocation (ADR-005). Proposed audit storage is a separate
manifest plus derived comparison SQLite database referencing the two immutable
sources. Only small audit metadata belongs in the catalog. Use read-only session
readers/ATTACH or streaming merge; never copy event-level comparison data into the
catalog. A proposed ADR should define comparison ownership, retention, reindexing
and export before implementation. Ordinary `.bviz` is still a single session;
paired export requires an explicit format/version decision, not an improvised ZIP.

Concrete reuse points:

- `runner`: new pure run-mode/audit plan, reusing capability discovery,
  instrumentation disclosures, `CommandExecutor` and `ExecutionFileSystem`.
- `capture-bes`: audit coordinator composed from ordinary `CaptureCoordinator`
  runs plus recorded clean steps; explicit auxiliary policy and one cancellation
  owner. Do not recursively activate the UI's Run action.
- `core-model` / `analysis-core`: comparison identities, coverage, classification
  and deterministic algorithms, independent of Swing and SQLite.
- `storage-sqlite` / `enrichment`: bounded observation ingestion, indexes, paged
  comparison queries and source-reference lifecycle.
- `ui-swing`: `ComparisonSource`/reader service, shared filters, tables, inspectors,
  graph components and source actions. Hold `CaptureLeaseRegistry` ownership
  through both runs and `SessionMutationCoordinator` leases during comparison.

## User interface

Keep this separate from capture-detail presets in Console: **Build mode:
Normal / Check reproducibility**. Review groups the two builds, cleanup scope,
cache/execution policy, context checks and evidence retention. Its live stepper
selects A/B/clean logs without mixing them into one transcript.

Add one **Hermeticity** navigation page with three internal tabs:

1. **Summary** — run pair, step status, context comparability, divergence counts,
   coverage and first supported divergent producers. Scrollable, full-width,
   compact shared toolbar. Show "No differences observed in N compared actions;
   M not verified" rather than "Passed: hermetic."
2. **Action differences** — paged/filterable table by finding, target, mnemonic,
   changed field, cache state, confidence and dependency impact. Reuse the shared
   All/Any/regex filter builder. A wide before/after inspector has Recipe, Inputs,
   Outputs and Evidence tabs, with next/previous difference and copy controls.
3. **Coverage & runs** — commands, source checks, execution/cache counts, missing
   records/fields, excluded non-spawn actions, limits, failed steps and original
   evidence. Open either ordinary session in its normal views.

Context actions: Reveal action in A/B, View configuration, Open BUILD file,
copy path, and Show affected chain in Graph. Never navigate to a guessed action
when correlation is ambiguous. A graph starts at the supported divergence and
keeps downstream changes distinct from independent divergence candidates.

MVP output comparison is **digest/manifest comparison**, not arbitrary artifact
content diffing. A's output files disappear at the second clean unless snapshotted
beforehand. Optional content capture needs reviewed byte limits and retention;
opening a current B file must never masquerade as opening historical A content.

## Delivery and acceptance gates

1. **Contracts and fixtures.** Approve protocol/scope and comparison ADR. Extend
   the tiny fixture into package-local real-Bazel tests. Set measurable resource
   limits and privacy/retention requirements before broad UI work.
2. **Evidence indexing.** Preserve missing execution-log fields, correct output
   kinds and per-observation digests, handle supported runfiles variants, and
   close required ingestion bounds. Validate compact and binary formats; don't
   claim full comparison coverage for a version whose adapter is incomplete.
3. **Offline comparison.** Compare two preserved sessions with deterministic
   classification, paged differences and coverage. This makes the engine useful
   before automatic clean/build orchestration and tests it without rerunning
   expensive user builds. No commands from an imported artifact are executed.
4. **Managed audit and pages.** Add reviewed local/SSH orchestration, output-base
   guards, sequential evidence preservation, cancellation and the three views.
5. **Broader diagnostics.** Add independent cache-reuse, worker/path/host variation,
   explicit cluster execution and repository-rule checks. Bazel's
   [workspace-rule log](https://bazel.build/remote/workspace) records selected
   repository operations, but cached setup may be absent; verify modern Bzlmod
   coverage rather than treating it as a comprehensive trace.

Required tests include stable and random outputs; source mutation; argv/env/tool/
platform drift; tree member changes; symlink and empty-file semantics; runfiles
collision ordering; equivalent input sets with different IDs/DAG shape;
multiple configurations; duplicate spawns/retries; cache masking; unavailable
digests; non-spawn/zero-spawn builds; malformed/truncated logs; invalid pairing;
reindex/reopen/delete/export behavior, including Query redaction; cancellation at
every step; server shutdown, orphan recovery and cleanup refusal under unknown
SSH outcomes; remote transfer and reconnect failures; path-escape/incorrect-output-base
refusal; narrow-screen layout; and bounded memory/disk/work behavior on large
synthetic logs.

Probe supported Bazel versions one at a time under repository memory caps. The
four-build research fixture is not a substitute for that gate. Do not run the
hazard-tagged version sweep casually. Bazel's own
[execution-log parser](https://github.com/bazelbuild/bazel/blob/9.2.0/src/tools/execlog/README.md)
and [cache-debugging guidance](https://bazel.build/remote/cache-remote) provide
useful independent cross-checks for reconstruction and diff fixtures; no new
runtime dependency or external parser process is proposed.

Implementation is deliberately not estimated as a small UI change: observation
fidelity and safe repeated execution are the largest parts. Phase 2/3 should be
reviewed and tested independently before the automated mode is enabled.

# ADR-014: Paired reproducibility audits with private comparison data

Status: accepted (2026-09-11), following approval of
[the feature plan](../hermeticity-plan.md). Implementation is staged; see
[implementation status](../implementation-status.md) for shipped behavior.

## Context

Repeated builds can expose unstable outputs and changes that prevent cache
reuse. Bytewise execution-log comparison is insufficient: scheduling and timing
vary, compact record IDs are not canonical, and clean builds can still reuse a
disk or remote action cache. Existing normalized action tables also discard
parts of the evidence and intern digests by path rather than observation.

## Decision

### Separate the experiment from the verdict

Add a Check reproducibility run mode, independent of capture-detail presets.
Its initial execution scope is `build` on a selected local or SSH workspace
machine. Remote-execution clusters, test-cache semantics and environment
variation are later protocols, not silently approximated by this one.

UI clarification (2026-09-15): the Console's single **Build mode** dropdown
offers **Hermeticity diagnostic** beside the normal capture presets. This is a
shortcut to the separate reviewed A/B workflow, not a new instrumentation
`CapturePreset`. After approval, both builds and their linked comparison run
automatically. The diagnostic selection is transient and does not replace
persisted capture-detail preferences; opening a Workspace does not silently
restore a two-build diagnostic selection.

The result compares recorded commands, environments, platform properties,
input manifests and output observations. It distinguishes independent output
divergence from changed generated inputs, recipe drift, cache reuse, ambiguous
matches and unavailable evidence. Matching does not require BEP correlation;
configuration metadata corroborates an otherwise unique match when available.
No result claims that an unchanged pair proves hermeticity.

### Keep ordinary sessions independent

ADR-005 remains intact: A and B are two ordinary managed sessions, never two
invocations mixed into one session database. The audit owns a small durable
operation record referencing its sources and recording steps, failures and
private-server cleanup state. Event/action-level comparison data never enters
the catalog. In-process session leases protect sources during comparison;
durable audit references must be reconciled before source retention can delete
them. Missing sources make an audit incomplete, never silently substitute a
different capture.

Comparison indexes are versioned, private and rebuildable from raw execution
logs. Initial offline comparison may use an explicitly owned disposable
directory and bounded snapshots of user-selected files. Managed sessions are
not rewritten to open a comparison. Work uses SQLite with file-backed scratch,
streaming ingestion and paged reads rather than a whole-log object graph.
Record, source/expanded byte, collection, work and disk limits must produce
explicit refusals or incomplete coverage.

The private index may retain exact sensitive values for comparison, protected
like the trusted raw files. It is not attached to ordinary Query connections,
included in table export, or added to `.bviz`. Its service API returns masked
diagnostics and equality outcomes, not secret fingerprints. A redacted source
cannot regain missing information through comparison. Every new field requires
an explicit privacy classification.

The existing single-session `.bviz` format does not change. Compound audit
export, private-index export and portable audit import are deferred until their
own format/redaction contract is reviewed. Opening captured data cannot execute
commands or reconnect to a host.

### Make repeated execution a reviewed operation

The existing workspace capture lease covers preflight, review, both builds and
cleanup. One coordinator owns cancellation and at most one private audit Bazel
server per workspace. Both builds use the same verified app-owned output base;
normal output bases and workspace convenience links are protected. Each clean
requires validation of Bazel's resolved output base. No arbitrary directory
from an imported file is a cleanup target.

Before execution, review both builds, clean scope, effective commands, cache
policy, cost and evidence retention. ADR-007 conflicts remain explicit. A veto
of required evidence or isolation makes this protocol unavailable, not a normal
build mislabelled as an audit. Repository download-cache reuse is outside the
initial experiment. Actual spawn cache/runner evidence determines comparison
coverage even after approved cache-bypass flags.

The first managed protocol is explicitly rc-free (`--ignore_all_rc_files`),
not a claim to reproduce the user's ordinary rc-configured invocation. Users
must opt into that difference; unresolved command-line configuration/strategy
conflicts are refused. As a narrow exception to the general capability-only
policy, automatic destructive audit steps additionally require the tested
Bazel 9.2.0 or Bazel 7.4.x protocol. Required capabilities and resolved-path checks still apply;
a matching version alone is never sufficient. Ordinary capture and offline
comparison retain their independent format/capability policies.

Review UX clarification (2026-09-16): one final review is the consent boundary.
Its **Run both builds** action explicitly approves the displayed rc-free,
uncached experiment; no preliminary confirmation, acknowledgement checkbox or
separate approval of each capture is required. Setup blockers appear first and
disable execution, not the ability to inspect or correct settings. Turning off
required execution logs is reversible through an explicit **Enable execution
logs** action, which replans both captures without overriding other choices.
Capability and evidence checks still apply after that action.

Compatibility extension (2026-09-16): Bazel 7.4 compact execution logs do not
include an invocation ID. Managed 7.4 audits may instead bind evidence through
the app-controlled capture: distinct per-run output paths in private staging,
absence checked before dispatch, the exact reviewed log flag, successful known
process completion and complete BES capture, successful preservation, and a
recorded checksum. This is capture-bound evidence, not an independently
verified embedded invocation identity. The review, results and reopened audit
must disclose that distinction. An embedded ID, when present, must still match;
9.2.0 continues to require it. Binary logs, stale files, ambiguous paths,
unknown SSH outcomes and failed transfers do not qualify for this fallback.
Compatibility fixtures must exercise the 7.4 clean/build protocol before it is
advertised as verified; unmeasured hosts and patch releases remain explicit.

Preserve A's evidence outside the private output base before cleaning for B;
SSH transfer failure stops the sequence. Source/configuration changes between
runs are reported without reverting them. Auxiliary queries do not run between
A and B unless a later explicitly reviewed protocol requires them.

Failure or cancellation stops subsequent clean/build steps and preserves partial
captures. Unknown SSH outcomes never authorize command replay or signalling
old process identifiers through a replacement connection. Private-server shutdown
and cleanup run only after client termination is known, with exact owned-path
validation. Cleanup failures and crash/orphan ownership remain visible for
reconciliation; raw sessions are outside the cleanup target.

### Keep UI behind service contracts

Hermeticity has Summary, Action differences, and Coverage & runs views, using
shared toolbars, filters and inspectors. Blocking capture, ingestion, comparison
and cleanup stay off the EDT. The UI consumes paged service records, not SQLite
connections. Historic output digest comparison does not imply historic file
contents are retained or that a current path still contains A's bytes.

## Consequences

- Evidence indexing and offline comparison must be tested before enabling
  automatic clean/build orchestration.
- The new bounded comparison importer does not by itself resolve the existing
  auxiliary-ingestion release blocker; normal capture paths retain their own
  documented limitations until separately hardened.
- Unsupported log variants and non-spawn work remain visible coverage gaps.
- No new runtime dependency is necessary: the existing protobuf, zstd, SQLite
  and Swing facilities are sufficient.

## Revisit when

Remote-execution clusters, cache-reuse experiments, host variation, durable
comparison-index reuse or portable audit sharing are added.

# Architecture

This document describes the system as built in this repository. Decisions
with rationale live in [docs/adr/](adr/); this file is the map.

## Module map

Fifteen logical modules contain package-local Bazel targets beside their Java
source (ADR-010, amending ADR-009; each logical module was a Gradle module
until 2026-08-24). BUILD files call native and contrib_rules_jvm rules
directly. Dependencies point strictly downward toward `core-model`; the UI
never appears on a capture/storage/analysis classpath and vice versa, and
Bazel's strict dependency checking enforces the arrows at compile time.
`tools/java_test_settings.bzl` holds shared values for JUnit dependencies,
test JVM flags and the real-Bazel safety tags; it wraps no rule and generates
no target.

| Module | Responsibility |
|---|---|
| `core-model` | Pure domain types shared by everything: identifiers (`SessionId`), the session lifecycle state machine (`SessionState`), the normalized event/action/target value types, the provenance wrapper (`Measured`), and the redaction engine (`core.redact`) plus the CSV and JSON escaping every export shares (`core.text`). No I/O, no framework dependencies beyond slf4j. |
| `proto` | Compiled Bazel protobuf definitions (BEP, command line, spawn/execution log, failure details). Nothing hand-written lives here; it exists so generated code has one home and one version policy. |
| `bep-codec` | Framing and decoding of Build Event Protocol payloads: length-delimited binary streams and JSON file variants, turning journal bytes into typed events. Owns "what did Bazel say", not "what does it mean". |
| `runner` | Owns the execution boundary (ADR-011): local and SSH `CommandExecutor` implementations, local and SSH/SFTP-backed `ExecutionFileSystem` implementations, command assembly from the instrumentation plan (ADR-007), process lifecycle, exit-status interpretation and workspace preflight. The old `//bazel-runner` package contains compatibility aliases only; the source tree moved because Bazel reserves top-level `bazel-*` paths in its execroot. |
| `capture-bes` | gRPC Build Event Service endpoint (always bound to desktop loopback; see docs/privacy.md) that live builds stream into; appends received events to the raw journal (ADR-004). `CaptureCoordinator` borrows the selected workspace's local or SSH execution session for one capture and owns only capture-scoped resources. |
| `capture-file` | Ingestion of already-written BEP files (binary or JSON) into the same raw journal format, so file import and live capture converge immediately. Also the reverse: `capture.file.export` streams the journal back out as a length-delimited binary BEP file, which is the module that already has both the journal reader and the decoders. |
| `session-format` | The managed session directory layout (docs/session-format.md): what files a session contains, journal file format, manifest read/write, integrity checks, and the portable `.bviz` archive (`format.portable`) that carries one to another machine. The only module that knows paths inside a session. |
| `storage-sqlite` | Explicit-SQL persistence (ADR-006): per-session database and app catalog database (ADR-005), schema DDL and migrations, paged query APIs that return primitive arrays, streamed CSV/JSON table exports, and the redaction column inventory that `SessionRedactionTest` checks against the schema. Depends on `analysis-core`, following plan 6.1's split: the aggregate and metric types are analysis-core's, the streaming that fills them is this module's. |
| `enrichment` | Post-build enrichers that add data the BEP stream lacks (execution log correlation, timing profile merge, external metadata), each re-runnable against the journal. |
| `graph-core` | Memory-mapped CSR graph index format (ADR-006): builders that stream edges into on-disk CSR files, and read-side traversal primitives over mapped buffers. |
| `analysis-core` | Algorithms and metrics over the indexes: critical path, graph extraction, clustering, layout, quantile sketches, the concurrency sweep, the metric catalog and the finding rules. Depends on `core-model` and `graph-core` only — **not** on `storage-sqlite`, which is what keeps every formula testable without a database and callable from either side. A caller supplies the arrays; this module supplies the answers. |
| `ui-swing` | All Swing code: window shell, FlatLaf theming, and the custom-painted heavy views (virtualized table, timeline, graph canvas). Talks to services only through background executors (see threading model). |
| `app` | Entry point and composition root: wires modules together, owns `main`, the Logback rolling-file backend and jpackage packaging. The UI sees only the backend-neutral logging runtime interface. |
| `test-support` | Test and benchmark fixtures, notably the deterministic synthetic data generators (`SyntheticActionGenerator`, `SyntheticEdges`, `SyntheticScale`) with O(1) random access so Tier 3 scale never requires materialized fixtures. |
| `benchmarks` | Phase 0 architectural spikes (table/timeline/graph/SQL-paging, each with `--offscreen`) and JMH microbenchmarks. Never shipped. |

## The six graph representations

Bazel builds involve several *different* graphs. Conflating them is the
classic failure mode of build visualizers, so these labels are load-bearing
and must be used exactly — in code, UI, and docs:

1. **BEP event graph** — the DAG of build *events* linked by
   `BuildEventId` children references. Ordering/announcement structure of
   the protocol stream itself. Not a build dependency graph.
2. **Target graph** — targets and their declared dependencies as written in
   BUILD files, before configuration. What `bazel query` shows.
3. **Configured-target graph** — targets *after* configuration: one node
   per (target, configuration) pair. Splits and transitions mean one target
   can appear several times. What `bazel cquery` shows.
4. **Declared action graph** — every action Bazel *would* run for the
   build, with artifact-mediated producer/consumer edges. What `bazel
   aquery` shows; exists even for fully cached builds.
5. **Observed execution graph** — the subset of actions that actually
   executed (or were confirmed cache hits) in *this* invocation, with what
   was observed about each: timing, runner, cache state.
6. **Temporal view** — the observed executions laid out on the wall clock:
   spans, concurrency lanes, the critical path as a time-ordered chain.
   Same nodes as (5), different structure — time, not dependency.

UI surfaces, metrics, and APIs must say which graph they are over. "The
graph" is never an acceptable label.

Two navigation cards read these graphs (since the 2026-08-24 Graph/Tree
split): **Tree** (`TreeView`) browses dependencies and reverse dependencies
one level at a time and finds paths, at any graph size; **Graph**
(`GraphExplorerView`) draws bounded extracts on the canvas with selectable
node weights. Each card carries its own graph-source selector naming which of
the representations above is on screen; see `docs/graph-model.md` for the
weight definitions and their budgets.

## Session state machine

Mirrors `core-model`'s `SessionState` exactly (that enum is authoritative;
update both together):

```
NEW -> PREFLIGHT -> CAPTURING -> BUILD_FINISHED -> ENRICHING -> INDEXING -> READY
```

Alternative transitions:

- `PREFLIGHT -> FAILED_TO_START` (bad workspace, bazel missing, port taken)
- `CAPTURING -> CANCELLED | INCOMPLETE | CORRUPT_PARTIAL`
- `BUILD_FINISHED -> INDEXING` (enrichment skipped/vetoed)
- `ENRICHING -> INCOMPLETE | CORRUPT_PARTIAL`
- `INDEXING -> READY_WITH_WARNINGS | INCOMPLETE | CORRUPT_PARTIAL`

Terminal states: `READY`, `READY_WITH_WARNINGS`, `FAILED_TO_START`,
`CANCELLED`, `INCOMPLETE`, `CORRUPT_PARTIAL`. The last three describe the
*capture outcome*, not openability — a session with any journaled data is
inspectable (ADR-004).

## Raw-first ingestion pipeline

Stages, in order, per ADR-004; each derived stage is re-runnable from the
journal:

1. **Receive** — bytes arrive from the BES gRPC stream (`capture-bes`) or a
   BEP file (`capture-file`).
2. **Journal** — append verbatim payload + arrival metadata to the
   append-only raw journal (`session-format`). Durability point; source of
   truth. Nothing before this stage interprets content.
3. **Decode** — `bep-codec` turns journal records into typed events;
   unknown/undecodable records are counted and preserved, never dropped.
4. **Normalize** — decoded events become rows in the session SQLite
   database (`storage-sqlite`): actions, targets, files, problems.
5. **Enrich** — `enrichment` correlates auxiliary sources (execution log,
   timing profile) onto the normalized rows; every enriched value carries
   its source.
6. **Index** — `graph-core` writes the CSR dependency indexes and temporal
   indexes as mmap files; SQLite gets its secondary indexes.
7. **Ready** — the UI opens read-only views over SQLite + mmap indexes.

Decode/normalize proceed incrementally during capture for live views;
stages 5-6 run after `BUILD_FINISHED`.

## Execution boundary and SSH workspaces

The startup screen and **Workspaces** menu manage user-level execution
workspaces separately from captured build sessions. `WorkspaceProfile` gives
each one a stable ID and user label and records one working directory, Bazel
executable, and either this computer or one SSH destination. Several profiles
can name different repositories on the same machine. `WorkspaceStore` keeps
them newest-first in bounded, atomically replaced
`settings/workspaces.properties`; legacy launcher-local and saved-SSH values
have a one-time migration path.

Workspace discovery is a separate configuration path. One optional script is
persisted at `settings/workspace-discovery`; discovered `WorkspaceProfile`
values exist only in memory and never enter `WorkspaceStore`. Every boot or
manual invocation replaces the whole discovered snapshot. Saved and discovered
profiles are merged only for display, with stable-ID collisions resolved in
favour of saved profiles. The UI marks discovered rows and disables their Edit
and Remove actions.

The discovery script runs directly on the desktop according to its shebang,
using the blocking-I/O executor rather than the EDT or a selected execution
session. Its bounded stdout parser accepts only the local/SSH row protocol and
returns immutable profiles plus bounded diagnostics. The shebang also drives
syntax highlighting in Preferences; it is not inferred from a filename.

Choosing a workspace creates one execution-scoped command runner and one
filesystem. Local execution uses `LocalCommandExecutor` and
`LocalExecutionFileSystem`. SSH selection opens one private system-OpenSSH
control master and derives command, terminal and SFTP channels from it. Remote
paths are `ExecutionPath` values owned by that filesystem; a remote Linux path
is never passed to desktop `Files` as if it were local. Closing or replacing
the workspace closes its tools and SSH connection. Opening/importing a
captured session does none of those things: provenance is descriptive, and
analysis can be opened without a live workspace.

ADR-012 adds a process-level application controller above those execution
sessions. The Workspace manager owns the saved/discovered profile snapshots,
Preferences, theme, logging and desktop handlers. Its registry maps each stable
profile ID to at most one ordinary `MainWindow`; opening an existing ID focuses
that window. Every managed window owns one execution connection, repository
browser, terminal, capture controller, selected analysis session, file-editor
manager and worker set. It cannot replace another window's execution context.

The ordered set of open IDs and safe normal-window geometry is atomically
persisted in `settings/workspace-window-state.properties`. Saved profiles can
restore immediately. A discovered ID is resolved only against the fresh startup
discovery snapshot, so its profile remains ephemeral. Restoration opens
Console, leaves the command draft blank and restores no captured session.
Saved-profile command history and table/query-result layout live below
`settings/workspace-windows/<sha256-profile-id>/`; discovered profiles do not
create that state. Removing a durably saved profile removes its private state,
and startup orphan cleanup runs only after a trustworthy profile-store load.
The global query library and captured-session catalog serialize their file and
database access across windows; catalog reconciliation runs once through the
manager. A process-owned `SessionMutationCoordinator` also leases every open
session UUID. Retention locks each candidate and rechecks active leases, pin
state and catalog location at execution time, while portable archive adoption
uses the same UUID lock and a unique staging directory. Thus a stale cleanup
plan cannot delete a session another window opened, and two imports cannot
share or remove each other's partial extraction. Desktop Open File routes to
the focused Workspace window, or to the manager's workspace-less analysis
shell when the manager owns focus. Desktop Quit closes all windows
asynchronously before the controller releases global resources.

A window that has accepted close is immediately removed from command and file
routing but remains in the restore snapshot until its asynchronous resource
teardown completes. Repository and editor workers finish before the local or
SSH execution context closes. The import/archive/export/catalog worker lane is
given bounded cooperative and interrupt/reap waits; an operation that ignores
thread interruption is reported and may outlive the bound only on a daemon
thread.

Before preflight, a process-global `CaptureLeaseRegistry` keys local work by
the canonical real repository path and SSH work by its connection authority
plus canonical remote repository path. It admits parallel captures for
different keys and reports the existing owner for a duplicate. Completion,
failure, cancellation and window shutdown all release the lease idempotently;
Bazel's own workspace/output-base locking remains authoritative outside this
process.

A live capture borrows the selected runner and filesystem. For SSH, it reuses
the already-selected control connection and adds only its reverse forward,
staging files and capture channels. It does not create an independently owned
connection whose lifetime ends with the capture.

The embedded BES still listens only on desktop `127.0.0.1`. Before the review
dialog can approve a remote launch, OpenSSH allocates a port on remote
`127.0.0.1` and reverse-forwards it to the desktop listener. Bazel receives
that remote loopback URI. It writes the execution log, profile and any BEP
fallback into a unique mode-0700 directory under remote `/tmp`; after the
primary command, SFTP copies each planned file into the managed session's
local `raw/` directory before import. Aquery and cquery use non-TTY channels so
their protobuf bytes can stream directly into bounded local files. Their
`aquery.query` and `cquery.query` scope files travel in the other direction
through SFTP.

The primary remote Bazel client uses a forced TTY so interrupts reach its
foreground process group. That necessarily merges its remote stdout and
stderr; the review dialog states this before launch. Capability probes,
metadata helpers and binary query streams remain non-TTY. Cancellation targets
the reported remote process group and does not treat a dead desktop SSH client
as proof that the remote Bazel client stopped.

The selected execution supplies two workspace tools. **Browse Repository**
lazily reads one directory at a time and opens files through the shared
bounded, conflict-aware editor. Exact Bazel convenience symlinks at the
repository root can be expanded explicitly; ordinary and nested links remain
leaves, and target resolution stays on the browser worker. **Terminal** embeds
JediTerm over a local Pty4J PTY. For a local workspace the PTY contains its
login shell; for SSH it contains the system OpenSSH client, which relays
resize events to the remote PTY while the control master still owns transport
and authentication. Navigating to the Terminal card opens its shell
automatically and idempotently. Changing tabs does not close it; closing or
replacing the workspace does. Terminal channel
and PTY lifecycle tasks run on the window-owned virtual-thread blocking-I/O
executor and its named, single-virtual-thread timer. The overridden JediTerm
executor manager creates no private pools. A manifest records `LOCAL` or `SSH`
provenance for display; opening historical session data never creates a
connection, starts a terminal or executes its recorded command.

## Threading model

Rules first (project-wide, non-negotiable):

- **The EDT does no I/O, no parsing, no SQL, no graph layout — ever.** The
  EDT paints from already-prepared primitive-array snapshots and handles
  input.
- Unavailable values are represented as *unavailable* (sentinel/Optional at
  the edge), never as zero.
- Data crosses onto the EDT only as immutable snapshots via
  `SwingUtilities.invokeLater`; background work never touches Swing
  components.

Executor inventory (plan section 19) — each a named, owned executor; ad hoc
thread creation is forbidden:

The plan (section 19.2) fixes the *set* of executors; the names below are the
implementation's names for them and may be refined, but the lanes may not be
merged — the point of the split is that a slow journal write can never stall
a UI query, and that SQLite has exactly one writer.

| Executor (plan 19.2) | Type | Work |
|---|---|---|
| EDT | Swing | painting, input, snapshot swap-in only |
| Orchestration / blocking I/O | virtual-thread-per-task | Local/SSH Bazel process supervision, workspace discovery, stdout/stderr pumps, SFTP and filesystem calls, enrichment command execution, general blocking I/O |
| Journal writer | single dedicated platform thread | raw journal appends; nothing else shares it, backpressure is explicit |
| Protobuf parser pool | bounded platform pool | BEP decode; bounded so decode can never outrun persistence |
| SQLite writer | single thread | every write to the session database, one writer connection |
| SQLite read pool | small bounded pool | short read transactions serving queries; cancellable |
| CPU analysis pool | bounded platform pool sized to cores | metrics, aggregation, critical path, CSR/temporal index construction |
| Graph layout pool | bounded platform pool | layout and rasterization prep for the graph canvas; cancellable |
| Export/import pool | bounded platform pool | archive read/write, BEP export, CSV/JSON export |
| Application log writer | one daemon platform thread, bounded queue | rolling diagnostic-file writes; callers never block and overflow has an exact visible count |

gRPC's own `grpc-netty-shaded` event loops sit in front of this: they perform
BES wire I/O only and hand bytes straight to the journal writer, per the
"gRPC callback requirements" in plan section 9.3.

Graphical startup reads the small appearance, logging, workspace, open-window and discovery
script settings files off the EDT. The composition root attaches the bounded
rolling-file logger, supplies immutable recent workspaces to the window, then
installs the selected FlatLaf theme on the EDT before constructing the first
window. It starts configured discovery on the blocking-I/O executor; discovery
results cross back as one immutable replacement snapshot. This avoids EDT I/O
and a light-window flash when a dark theme was saved. Runtime theme,
logging-level, workspace-list and discovery-script changes happen in memory on
the EDT; their coalesced or serialized atomic settings writes use the existing
orchestration / blocking-I/O executor. Window disposal and desktop quit
completion wait asynchronously for the newest queued preference writes before
disposing the last frame and stopping that executor; the EDT never waits.

Logging is summary-based: Info records lifecycle and operation outcomes, Debug
records stage decisions, and Trace records bounded periodic progress rather
than every event, row, graph edge or terminal byte. The `app` module attaches
the file destination only for graphical startup. Headless subcommands retain a
stderr-only Logback destination so stdout remains a stable result channel.
Debug and Trace apply to the application's own logger namespace; dependency
internals remain at Warn so a diagnostic run does not turn into raw SQLite or
transport-library tracing. One operating-system lease owns the rolling
destination until its writer actually closes, preventing concurrent app
instances from racing a rollover. Embedded control characters are escaped so
each event, including a stack trace, remains one physical log record.

Blocking work goes on virtual threads unless it is a dedicated-resource lane
(journal file, SQLite connection, render prep), which get named platform
pools with explicit bounds. Queues between stages are bounded; overflow is
backpressure, never unbounded buffering (bounded-memory streaming rule).

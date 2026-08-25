# Bazel Build Visualizer

## Detailed implementation plan for an agentic coding agent

**Status:** Approved planning baseline
**Primary platform:** macOS, Apple Silicon first
**Portability target:** Windows and Linux-compatible architecture
**Bazel compatibility:** Bazel 6 through Bazel 9 using capability detection
**Product model:** Local, single-user desktop application
**UI technology:** Java Swing with custom Java2D visualization
**Java baseline:** Java 21 LTS *(superseded — the baseline is now Java 25 LTS
per [ADR-008](adr/008-java-25.md); this line and the ADR-002 section below are
preserved as the historical approved text)*
**Working title:** Bazel Build Visualizer
**Intended implementer:** Agentic coding system such as Claude Code or Codex

> This file is the authoritative baseline for the repository. It is reproduced
> verbatim from the approved plan. Any change to a fixed architectural decision
> requires a new or amended ADR under `docs/adr/` **before** the code changes
> (see section 26, rule 4). Docs elsewhere in `docs/` elaborate on this plan;
> where they disagree with it, this file wins.

---

# 1. Product definition

## 1.1 Problem

Bazel exposes extensive build information, but it is spread across protocol events, logs, profiles, query results, console output, and command-specific tools. It is difficult for developers to answer practical questions such as:

- What was Bazel doing during a slow period?
- Which actions were on a critical dependency chain?
- Which actions had the largest inputs or outputs?
- Which targets generated the most work?
- Which actions were cache misses?
- Was time spent executing, waiting, uploading, downloading, or scheduling?
- Which dependency caused a selected action to run?
- What depends on an expensive action?
- Why did a target or test fail?
- Did one large action block many downstream actions?
- Did the build have insufficient parallelism?
- Did thousands of tiny actions create excessive scheduling overhead?
- How much of a slow build was caused by the build graph versus the execution environment?

The application must make these questions answerable without requiring the user to understand BEP protobufs, execution logs, trace profiles, or Bazel query syntax.

## 1.2 Product vision

Create a desktop application that can:

1. Launch a Bazel command from a selected workspace.
2. Transparently instrument the command.
3. Receive Build Event Service and Build Event Protocol data live.
4. Show completed actions and build progress as events arrive.
5. Enrich the capture with execution logs, profiles, `aquery`, and `cquery`.
6. Normalize all sources into one coherent session model.
7. Visualize the invocation chronologically, hierarchically, and graphically.
8. Identify credible build-performance optimization candidates.
9. Preserve the original evidence and clearly distinguish observed, inferred, and unavailable data.
10. Save, reopen, inspect, and export large captures.

## 1.3 Goals

### Required goals

- Launch `build`, `test`, `run`, `coverage`, and other Bazel commands.
- Allow workspace, Bazel executable, command, targets, arguments, environment, and capture settings to be configured.
- Display the original and effective command.
- Explain every automatically added flag.
- Receive live events through an embedded loopback BES.
- Import binary and JSON BEP files.
- Export a canonical binary BEP stream.
- Save and reopen complete visualizer sessions.
- Scale to several million actions and at least 50 million raw events.
- Preserve all raw data in complete-capture mode.
- Keep the Swing Event Dispatch Thread responsive during ingestion and analysis.
- Show every imposed display, traversal, indexing, or layout limit to the user.
- Support exact inspection of any action even when aggregate rendering is required.
- Compute the widest practical set of action, target, test, artifact, graph, and invocation metrics.
- Represent missing data as unknown, never as zero.
- Explain metric provenance and confidence.

### Strong secondary goals

- Offer useful diagnostics automatically.
- Support interrupted, cancelled, malformed, and partially written captures.
- Make imported sessions useful even if they contain only BEP.
- Allow graph and table data to be exported.
- Support future build-to-build comparison.
- Keep the application architecture portable to Windows and Linux.

## 1.4 Non-goals for v1

- Hosted multi-user build observability.
- Implementing a general remote execution service.
- Replacing Bazel's remote cache or remote executor.
- Replacing terminal output for interactive programs launched by `bazel run`.
- Authenticating arbitrary internet-facing BES clients.
- Rendering millions of individually labeled graph nodes at once.
- Downloading remote artifacts automatically.
- A public plugin SDK.
- IDE integration.
- Automated source-code modification.
- Claiming that a diagnostic finding is definitively causal without evidence.

"All actions are available" must mean that every captured action is searchable, inspectable, exportable, and eligible for analysis. It must not imply that several million full-detail nodes can be legibly painted on one screen.

---

# 2. Research-grounded constraints

The official BEP documentation describes BEP as the mechanism through which third-party programs can inspect a Bazel invocation. The protocol should therefore be treated as the primary live invocation stream, but not as the only performance data source.

The Bazel command reference defines `aquery` as an action-graph query. Consequently, the application must not treat the BEP parent/child event relationships as equivalent to the action dependency graph.

The official BEP glossary describes `BuildMetrics` as an end-of-command event containing counters and gauges. Build-wide metrics that depend on this event will become available during finalization rather than continuously.

Bazel's performance guidance uses execution logs and other profiling outputs to diagnose build behavior. Deep capture must combine BEP with these auxiliary sources instead of pretending BEP contains every execution statistic.

The checked-in Bazel protobuf definitions are the wire-contract source for event decoding. Pin the imported definitions, retain unknown fields and raw payloads, and test against real outputs from every supported Bazel major version.

Official BEP examples should be converted into parser fixtures in addition to locally generated Bazel 6–9 fixtures.

## 2.1 Important consequences

### BEP event graph is not the action graph

Maintain distinct representations for:

1. **BEP event graph**
   - Parent events announce child event identifiers.
   - Useful for protocol completeness, lifecycle navigation, and debugging.

2. **Target graph**
   - Unconfigured target relationships.
   - Primarily from `query`.

3. **Configured-target graph**
   - Configuration-aware target relationships.
   - Primarily from `cquery`.

4. **Declared action graph**
   - Actions, declared inputs, outputs, and artifact producer/consumer relationships.
   - Primarily from `aquery`.

5. **Observed execution graph**
   - Actions and attempts that actually executed or were checked against caches.
   - Derived from execution logs and observed artifacts.

6. **Temporal execution view**
   - Action overlap over time.
   - Derived from recorded timestamps.
   - Temporal overlap must not be presented as dependency.

Every UI label must name the graph explicitly.

### Live action display is completion-oriented

The standard implementation must not promise that every running action will be announced at its start.

The safe behavior is:

- Receive an action event when Bazel publishes it.
- Record its supplied start and end timestamps when available.
- Insert its timeline span retroactively.
- Describe it as "received" or "completed," not "started," unless a supported source explicitly reports a start notification.
- Show currently running actions only when a source reliably exposes that state.
- Never infer that an action is currently running merely because its event identifier was announced.

### Enrichment is asynchronous

The session moves through these states:

```text
NEW
  -> PREFLIGHT
  -> CAPTURING
  -> BUILD_FINISHED
  -> ENRICHING
  -> INDEXING
  -> READY
```

Alternative terminal states:

```text
FAILED_TO_START
CANCELLED
INCOMPLETE
CORRUPT_PARTIAL
READY_WITH_WARNINGS
```

The user may inspect the session while enrichment and indexing continue.

---

# 3. Fixed architectural decisions

## ADR-001: Use Swing rather than SWT

Choose Swing for v1.

Rationale:

- The difficult views require custom rendering regardless of widget toolkit.
- Swing offers direct Java2D integration for the timeline and graph canvas.
- A bundled Java runtime avoids SWT platform-fragment management.
- Swing supports a model-driven table without creating a component for every row.
- macOS packaging is straightforward through `jpackage`.
- The UI can use FlatLaf while retaining custom rendering.
- The application remains mostly pure Java.

This is not a claim that Swing intrinsically renders more data than SWT. Performance will come from disk-backed models, aggregation, viewport culling, asynchronous queries, and bounded layout.

Reconsider this decision only if the Phase 0 macOS benchmark fails the stated responsiveness targets after the data model and painting paths have been optimized.

## ADR-002: Use Java 21 LTS

> **Superseded as of 2026-08-21 by [docs/adr/008-java-25.md](adr/008-java-25.md).**
> The language baseline is now Java 25 LTS; the "do not require preview
> features" constraint below is unchanged and still binding. The plan text
> that follows is preserved verbatim as the historical baseline.

Use Java 21 language and runtime features.

Permitted features include:

- Records
- Sealed interfaces
- Pattern matching available without preview flags
- Virtual threads for blocking orchestration and I/O
- `java.awt.desktop` macOS integration
- `ProcessHandle`

Do not require preview features.

## ADR-003: Use Gradle Kotlin DSL

Use a Gradle multi-module project with:

- Gradle Wrapper committed
- Dependency locking
- Reproducible archives
- Java toolchains
- Protobuf and gRPC generation
- JUnit test suites
- JMH performance benchmarks
- `jpackage` distribution tasks

Do not require Bazel to build the visualizer itself. The application must still use real Bazel workspaces for integration tests.

## ADR-004: Use raw-first capture

The network receive path must preserve the original payload before expensive normalization.

Raw data is the recovery and forward-compatibility source of truth.

## ADR-005: Use one SQLite database per session

Maintain:

- A small application catalog database.
- One database per build session.
- Segmented raw journals outside SQLite.
- Memory-mapped graph indexes outside SQLite where appropriate.

Do not place all sessions in one enormous database.

## ADR-006: Do not use an ORM

Use explicit SQL, prepared statements, batched transactions, and schema migrations.

An ORM would add object allocation, hide query behavior, and make bulk-ingestion tuning harder.

## ADR-007: Do not hold the complete build as Java objects

Short-lived decoded protobuf objects are acceptable.

Persistent state must live in:

- Raw journal segments
- SQLite tables
- Primitive arrays
- Memory-mapped graph files
- Bounded caches

## ADR-008: Make instrumentation transparent

No capture flag may be silently inserted, removed, or replaced.

The user must be able to inspect:

- Original command
- Added flags
- Replaced conflicting flags
- Auxiliary commands
- Data each item enables
- Expected relative overhead
- Destination files
- Unsupported capabilities
- Any behavior inherited from Bazel configuration files that can be identified

## ADR-009: Preserve source-specific values

Do not collapse conflicting measurements into one unexplained number.

For example, retain separately:

- BEP action start/end
- Execution-log attempt start/end
- Profile span start/end
- Bazel-reported critical path
- Visualizer-computed dependency critical path

The UI may choose a preferred display value, but must expose all source values.

---

# 4. Capture sources and presets

## 4.1 Source matrix

| Source | Availability | Primary purpose | Main limitations |
|---|---:|---|---|
| Embedded BES/BEP | Live | Lifecycle, progress, targets, tests, action completion, failures, files, command metadata | Not a complete action/target dependency graph |
| Binary BEP file | Live-tail or import | Local fallback, archival, interoperability | Same semantic limitations as BEP |
| JSON BEP file | Live-tail or import | Human-readable interoperability | Larger and slower to parse |
| Compact execution log | Usually post-build | Attempts, runner/cache information, inputs, outputs, detailed timings | Version-sensitive and potentially large |
| JSON trace profile | Usually post-build | Phase timing, thread/activity timeline, profile events | Potentially very large |
| `aquery` protobuf | Post-build by default | Declared action graph and artifact relationships | Additional analysis cost; options must match |
| `cquery` output | Post-build by default | Configured-target relationships | Additional analysis cost |
| `query` output | Optional | Unconfigured target graph | Does not represent configured actions |
| Stdout/stderr | Live | Human-readable progress and errors | Ordering between streams is approximate |
| Filesystem stat enrichment | Optional post-build | Local output sizes when absent elsewhere | Can add I/O and cannot cover remote-only artifacts |

## 4.2 Capture presets

### Preset A: Live Essentials

Use when minimal perturbation is preferred.

Capture:

- Embedded BES or binary-file fallback
- Complete raw BEP
- Structured command metadata
- Console output
- All action events when the detected Bazel version supports the required flag

Do not automatically run:

- Profile capture
- Execution log capture
- `aquery`
- `cquery`

Expected result:

- Useful lifecycle, target, test, failure, and chronological action views
- Limited dependency, cache, and detailed timing analysis

### Preset B: Performance Diagnostics

Recommended default.

Capture:

- Everything in Live Essentials
- Compact execution log when supported
- JSON trace profile
- Final `BuildMetrics`
- Post-build `aquery`
- Optional filesystem output-size enrichment

Expected result:

- Detailed action timing
- Cache and runner attribution
- Actual input/output metadata
- Declared action dependency graph
- Critical-path and bottleneck analysis

### Preset C: Full Graph Diagnostics

Capture:

- Everything in Performance Diagnostics
- `cquery`
- Optional `query`
- Complete event indexing
- Complete action-edge materialization
- Execution-graph information if the detected Bazel version exposes a supported source

This preset must display a prominent disk, CPU, and indexing-cost warning.

### Preset D: Custom

Allow every source and limit to be individually configured.

## 4.3 Instrumentation plan UI

Before launch, show a panel containing:

### Original command

The exact argv entered by the user.

### Effective command

The final argv that will be passed to `ProcessBuilder`.

### Added items

For every added flag, show:

- Flag and value
- Reason
- Capture source enabled
- Relative overhead: low, medium, or high
- Whether it writes a local file
- Whether the file may contain sensitive information
- Capability status for the selected Bazel binary
- Whether the user can disable it

### Auxiliary commands

Show commands scheduled after the primary invocation, including:

- Purpose
- When they run
- Which primary-command options will be carried forward
- Estimated relative cost
- Output path
- Whether failure is fatal or non-fatal

### Conflicts

Examples:

- User supplied another BES backend.
- User supplied a BEP output path.
- User disabled action publication.
- An injected flag is unsupported.
- A destination already exists.
- The command is not compatible with action enrichment.
- A user option would be overridden by a later command-line option.

Do not launch until mandatory conflicts are resolved.

---

# 5. User workflows

## 5.1 Launch a build

1. Open the application.
2. Select or enter a working directory.
3. Detect the workspace root.
4. Select Bazel, Bazelisk, or an explicit executable.
5. Enter a complete command such as `test //...`.
6. Choose a capture preset.
7. Optionally configure:
   - Startup options
   - Environment variables
   - `.bazelrc` behavior
   - Disk budget
   - Graph/indexing limits
   - Redaction
8. Run preflight validation.
9. Review the instrumentation plan.
10. Start the invocation.
11. Navigate live views while the command runs.
12. Cancel or terminate if necessary.
13. Continue inspecting while enrichment completes.
14. Save, export, or retain the session in the local library.

## 5.2 Open an existing capture

Supported input types:

- Binary BEP stream
- JSON BEP stream
- Portable `.bviz` session
- Managed session directory
- Growing binary BEP file in tail mode

Import workflow:

1. Detect the format by content, not only extension.
2. Hash and record the source file.
3. Preserve the original file or a byte-identical copy.
4. Create an import journal and session database.
5. Begin streaming parsing.
6. Make partial results available immediately.
7. Show parse progress, throughput, event count, and warnings.
8. Permit cancellation.
9. Retain a resumable checkpoint.
10. Mark truncated final messages as incomplete rather than discarding the whole session.

## 5.3 Inspect a slow action

1. Select an action from any view.
2. Open the shared inspector.
3. Display:
   - Identity
   - Target owner
   - Configuration
   - Timing
   - Attempts
   - Cache result
   - Runner
   - Inputs
   - Outputs
   - Dependencies
   - Reverse dependencies
   - Command
   - Environment
   - Raw source records
   - Metric provenance
4. Navigate to:
   - Its timeline span
   - Dependency neighborhood
   - Reverse-dependency neighborhood
   - Critical-path position
   - Target
   - Related tests
5. Export the selected record if needed.

## 5.4 Understand a massive build

The application must:

- Keep all actions queryable.
- Start in aggregate mode.
- Show total versus currently rendered counts.
- Allow arbitrary filters.
- Allow exact table inspection.
- Use semantic zoom for graphs and timelines.
- Warn before expensive exact traversals.
- Permit limits to be raised.
- Permit full edge/action data to be exported without rendering all entities.

No view may silently sample or truncate.

---

# 6. Project structure

Use the following initial Gradle modules:

```text
bazel-build-visualizer/
├── app/
├── ui-swing/
├── core-model/
├── bazel-runner/
├── capture-bes/
├── capture-file/
├── bep-codec/
├── storage-sqlite/
├── enrichment/
├── analysis-core/
├── graph-core/
├── session-format/
├── test-support/
├── benchmarks/
├── proto/
├── docs/
├── build-logic/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── gradlew
```

## 6.1 Module responsibilities

### `app`

- Main method
- Dependency composition
- Application lifecycle
- Global exception handling
- macOS desktop integration
- Logging initialization
- Configuration-directory discovery
- Single-instance behavior if implemented
- Opening files passed by the operating system

### `ui-swing`

- Main window
- Launcher
- Session navigation
- Actions table
- Timeline
- Graph canvas
- Trees
- Inspector
- Console
- Dialogs
- Settings
- Theme
- UI-specific models
- Selection coordination

### `core-model`

- Stable domain identifiers
- Entity records
- Session state
- Data-source provenance
- Completeness and confidence types
- Application events
- Query/filter AST
- Service interfaces that do not depend on Swing or SQLite

### `bazel-runner`

- Executable discovery
- Bazel/Bazelisk version detection
- Capability detection
- Command parsing
- Instrumentation planning
- Process creation
- Environment management
- Output capture
- Graceful and forced cancellation
- Auxiliary command planning

### `capture-bes`

- Embedded gRPC server
- BES lifecycle publication handling
- Build-tool event stream handling
- Sequence validation
- Acknowledgements
- Stream correlation
- Backpressure
- Raw-journal handoff

### `capture-file`

- Binary BEP parser
- Growing-file parser
- JSON BEP parser
- Format detection
- Import checkpoints
- Truncation handling

### `bep-codec`

- Generated protobuf types
- BEP event decoding
- Event identifier canonicalization
- Unknown-field preservation
- Raw protobuf rendering
- Translation into normalization commands

### `storage-sqlite`

- Catalog database
- Per-session database
- Migrations
- Batched writer
- Read connection pool
- Query implementations
- Raw event index
- Aggregate persistence
- Session recovery

### `enrichment`

- Execution-log import
- Profile import
- `aquery` import
- `cquery` import
- Optional `query` import
- Filesystem metadata enrichment
- Cross-source correlation

### `analysis-core`

- Metrics
- Aggregation
- Percentiles
- Diagnostic findings
- Critical-path calculations
- Concurrency calculations
- Data-quality reports

### `graph-core`

- Edge generation
- External edge sorting
- CSR index building
- Forward and reverse traversal
- Topological ordering
- Cycle diagnostics
- Clustering
- Layout
- Spatial index
- Timeline level-of-detail index

### `session-format`

- Manifest
- Managed session layout
- Portable archive import/export
- BEP export
- CSV/JSON/GraphML export
- Redacted export

### `test-support`

- Synthetic BEP generator
- Fake BES client
- Tiny Bazel workspaces
- Database fixtures
- Fake clocks
- Large synthetic graph generator

### `benchmarks`

- JMH benchmarks
- End-to-end capture benchmarks
- SQLite benchmarks
- Graph construction benchmarks
- Rendering benchmarks
- Import benchmarks

### `proto`

Vendor pinned copies of required Bazel and BES protobuf definitions with:

- Source tag or commit recorded
- License notices
- Generated-code package checks
- Descriptor set generation
- A script or task for controlled schema updates

---

# 7. Core application services

Define clear interfaces before implementing adapters.

## 7.1 Primary services

### `SessionManager`

Responsibilities:

- Create sessions
- Open sessions
- Finalize sessions
- Recover interrupted sessions
- Delete managed sessions
- Export sessions
- Publish session-state changes

### `BazelInvocationService`

Responsibilities:

- Preflight
- Produce instrumentation plans
- Start processes
- Track invocation state
- Capture stdout/stderr
- Cancel and terminate processes
- Launch enrichment commands
- Record the exact effective command

### `BazelCapabilityDetector`

Return a `BazelCapabilities` value containing detected support for:

- BES backend
- Binary BEP output
- JSON BEP output
- Publishing all actions
- Execution-log variants
- Profile output
- `aquery` protobuf output
- `cquery` output modes
- Relevant build metric fields
- Action timestamp fields
- Optional execution-graph logging

Detection rules:

1. Determine actual Bazel version.
2. Inspect help output for the selected executable.
3. Cache by resolved executable, version, and relevant startup context.
4. Prefer observed capability to hard-coded version comparisons.
5. Keep a compatibility table for known flag aliases.
6. Never inject a flag merely because the version number suggests it exists.

### `InstrumentationPlanner`

Input:

- Original argv
- Working directory
- Executable
- Environment
- Capture preset
- Capabilities
- Existing explicit flags
- User settings
- Session paths
- Local BES endpoint

Output:

- Original argv
- Effective argv
- Added flags
- Replaced flags
- Warnings
- Errors
- Auxiliary commands
- Expected output files
- Source availability plan
- Relative overhead indicators

### `CaptureCoordinator`

Coordinates:

- BES server readiness
- File-tail capture
- Process launch
- Console capture
- Raw journal
- Normalization
- Session state
- Finalization
- Enrichment

### `NormalizationPipeline`

Consumes raw event envelopes and performs:

1. Minimal validation.
2. Event type detection.
3. Domain command generation.
4. Batched database writes.
5. Incremental aggregate updates.
6. UI delta publication.
7. Checkpoint persistence.

### `SessionQueryService`

Provides asynchronous, cancellable operations for:

- Action pages
- Target pages
- Tests
- Events
- Metrics
- Details
- Search
- Graph neighborhoods
- Timeline ranges
- Counts and estimates

### `SelectionService`

Maintains cross-view selection by stable domain identity.

A selection in one view must update all other open views without those views querying each other directly.

---

# 8. Bazel process and command handling

## 8.1 Command representation

Represent a launch as structured data:

- Executable path
- Startup arguments
- Bazel command
- Command arguments
- Target residue
- Arguments after `--`
- Working directory
- Environment overrides
- Inheritance mode
- Capture preset
- Shell mode

Default to direct argv execution through `ProcessBuilder`.

Do not execute through a shell by default.

Provide explicit shell mode for users who require shell expansion, wrappers, pipes, or substitutions. Shell mode must carry a security warning and show the exact shell command.

## 8.2 Workspace detection

Detection order:

1. User-selected directory.
2. Search ancestors for recognized workspace markers.
3. Ask Bazel for workspace information when safe.
4. Permit operation from a subdirectory.
5. Allow explicit override.

Record both:

- Process working directory
- Detected workspace root

## 8.3 Executable detection

Candidates:

- Explicit user path
- `bazelisk` on `PATH`
- `bazel` on `PATH`
- Previously used executable
- Workspace-specific preference

Record:

- Entered path
- Resolved path
- Version output
- Bazelisk-resolved Bazel version when discoverable
- File hash where practical

## 8.4 Flag injection

The planner may add supported equivalents of:

- Local BES backend
- Complete action publication
- Compact execution log
- JSON trace profile
- Binary BEP fallback
- Other metrics-supporting flags discovered in the compatibility matrix

Rules:

- User-supplied explicit options take precedence unless the user confirms replacement.
- Later-option precedence must be made visible.
- All generated paths must be session-local and absolute.
- Existing files must not be overwritten without explicit policy.
- Flags must be passed as argv elements.
- Paths must not be manually shell-quoted in direct execution mode.
- Sensitive flag values must be redacted in UI copies where configured.

## 8.5 Existing BES backend

If an explicit or detected upstream BES backend conflicts with the local embedded backend, offer:

1. Replace it with the visualizer's local backend.
2. Preserve it and capture through a local binary BEP file.
3. Cancel and edit the command.

Do not implement a forwarding BES relay in v1.

Record the selected conflict resolution in the manifest.

## 8.6 Auxiliary query construction

Run enrichment after the primary build by default so it does not delay or contend with the measured execution.

For `aquery` and `cquery`:

1. Reuse the same Bazel executable.
2. Reuse startup options.
3. Reuse the workspace and environment.
4. Extract applicable targets.
5. Retain configuration-affecting options supported by the auxiliary command.
6. Exclude command-specific options that the auxiliary command rejects.
7. Show the resulting command before or during the primary invocation.
8. Capture stdout/stderr independently.
9. Treat failure as non-fatal to the main session.
10. Explain when the graph may not exactly match because options could not be reproduced.

Never claim an exact graph match unless the configuration equivalence has been verified.

## 8.7 Cancellation

Expose:

- **Cancel build:** graceful interrupt request.
- **Terminate:** process termination.
- **Force kill:** process and descendants.

On macOS and other POSIX systems:

1. Attempt interrupt semantics through the platform process controller.
2. Wait for a configured grace period.
3. Send termination.
4. Force-kill remaining descendants if requested.

Always:

- Continue draining output briefly.
- Finalize available raw data.
- Mark the invocation cancelled or incomplete.
- Permit enrichment only when its source files are valid.

---

# 9. BES server and ingestion pipeline

## 9.1 Network binding

- Bind only to loopback by default.
- Ask the operating system for an available port.
- Start and confirm readiness before launching Bazel.
- Record the actual endpoint.
- Do not expose the service on LAN interfaces in v1.
- Generate a unique capture token internally even if the Bazel endpoint cannot transmit it; use invocation and stream IDs for correlation.

## 9.2 Stream handling

Maintain state per BES stream:

- Stream identifier
- Invocation identifier
- Build identifier
- Highest received sequence
- Highest contiguous sequence
- Highest acknowledged sequence
- Duplicate count
- Gap state
- First/last timestamps
- Completion state
- Error state

Behavior:

- Accept duplicate retransmissions idempotently.
- Do not normalize the same sequence twice.
- Detect sequence gaps.
- Buffer only a bounded number of out-of-order events.
- Withhold acknowledgements when persistence cannot keep up.
- Never drop accepted events silently.
- Surface backlog and stalled-stream warnings.

## 9.3 Raw-first pipeline

Pipeline stages:

```text
gRPC callback
  -> bounded receive queue
  -> journal writer
  -> acknowledgement coordinator
  -> protobuf decoder
  -> normalization queue
  -> SQLite batch writer
  -> aggregate updater
  -> throttled UI notifications
```

### gRPC callback requirements

The callback must:

- Perform minimal validation.
- Avoid database access.
- Avoid Swing access.
- Avoid expensive protobuf traversal.
- Enqueue or apply backpressure.
- Return quickly.

### Journal writer

The journal writer must:

- Append frames sequentially.
- Add a checksum.
- Rotate segments at a configurable size.
- Persist checkpoints.
- Group flushes.
- Force all data on graceful finalization.
- Recover to the last valid frame after a crash.

Default balanced durability:

- Acknowledge only after the frame has been appended to the journal channel.
- Flush to the operating system periodically.
- Do not perform an `fsync` for every event.
- Offer a stricter durability setting for users willing to accept overhead.

### Normalization batching

Initial tunable defaults:

- Up to 5,000 normalization commands per transaction.
- Flush at least every 100 milliseconds during live capture.
- UI aggregate publication no faster than four times per second.
- Separate CPU parsing pool from the single SQLite writer.

Tune using benchmarks rather than assumptions.

## 9.4 Backpressure

All pipeline queues must be bounded.

When pressure rises:

1. Reduce UI update frequency.
2. Pause nonessential enrichment.
3. Increase database batch size within configured limits.
4. Withhold BES acknowledgement if necessary.
5. Show a capture-lag indicator.

Never resolve overload by silently discarding action, target, test, or failure events.

If the user selected an explicit reduced event-indexing mode, raw events must still be preserved.

## 9.5 File-tail fallback

For a growing binary BEP file:

- Track the last complete frame offset.
- Decode variable-length prefixes incrementally.
- Wait when the final payload is incomplete.
- Detect truncation or file replacement.
- Resume from checkpoints.
- Finalize only after process exit and a final read.

For JSON:

- Support the actual Bazel stream representation identified by format detection.
- Use streaming parsing.
- Enforce configurable per-record size limits.
- Preserve the raw source even when unknown JSON fields cannot be normalized.

---

# 10. Session storage design

## 10.1 Application directories

On macOS, use an application-support directory containing:

```text
catalog/
managed-sessions/
import-cache/
temporary-captures/
settings/
logs/
```

Do not place large sessions in preferences storage.

## 10.2 Managed session layout

```text
session-<uuid>/
├── manifest.json
├── session.sqlite
├── raw/
│   ├── bes-000001.journal
│   ├── bes-000002.journal
│   ├── stdout.log
│   ├── stderr.log
│   ├── execution-log.bin
│   ├── profile.json
│   ├── aquery.pb
│   ├── cquery.pb
│   └── imported-source.bep
├── indexes/
│   ├── action-forward.csr
│   ├── action-reverse.csr
│   ├── target-forward.csr
│   ├── target-reverse.csr
│   ├── timeline-lod.dat
│   └── graph-clusters.dat
├── exports/
├── checkpoints/
└── locks/
```

Only create files that are relevant to the session.

## 10.3 Manifest

The manifest must include:

- Format version
- Application version
- Session UUID
- Creation and finalization timestamps
- Session state
- Working directory
- Workspace root
- Bazel executable information
- Bazel version
- Original command
- Effective command
- Environment capture policy
- Capture preset
- Injected flags
- Auxiliary commands
- Source files
- Source hashes
- Completeness per source
- Redaction state
- Event/action counts
- Index versions
- Database schema version
- Known warnings
- Whether the session contains absolute paths or environment values

## 10.4 Portable `.bviz` format

A portable session is a Zip64 archive with a `.bviz` extension.

Rules:

- Include the manifest at the archive root.
- Protect against zip-slip paths.
- Enforce decompression-size limits.
- Store already compressed large files without recompressing.
- Export through a temporary file, then atomically rename.
- Verify checksums before declaring success.
- Estimate required temporary disk space before export.
- Permit redacted export.
- Do not require portable export for normal persistence; managed session directories remain the efficient local format.

## 10.5 Binary BEP export

Provide a streaming export that:

1. Reads BEP payloads in canonical stream order from the raw journal.
2. Writes the expected length-delimited binary stream.
3. Excludes BES lifecycle envelopes that are not BEP `BuildEvent` payloads.
4. Preserves original serialized payload bytes where possible.
5. Reports any missing sequence ranges.
6. Allows partial export from incomplete sessions.

## 10.6 Catalog database

Catalog fields:

- Session UUID
- Display name
- Workspace
- Command summary
- Start/end time
- Status
- Bazel version
- Action/event counts
- Total size
- Last opened
- Pinned status
- Managed directory
- Thumbnail/summary metrics
- Warning count

The catalog must not contain the full build data.

## 10.7 Session database schema

Use 64-bit integer surrogate IDs inside each session database.

Core tables:

### Session and streams

- `session_info`
- `capture_sources`
- `event_streams`
- `schema_metadata`
- `import_diagnostics`

### BEP events

- `bep_events`
- `bep_event_ids`
- `bep_event_edges`
- `bep_announced_missing`
- `raw_event_locations`

Important event columns:

- Sequence
- Stream
- Event ID hash
- Event type
- Event timestamp
- Receive timestamp
- Raw segment
- Raw offset
- Raw length
- Decode status
- Parent/child counts

### Dictionaries

- `strings`
- `paths`
- `labels`
- `mnemonics`
- `platforms`
- `runners`

Use dictionary tables for repeated values.

### Configurations and targets

- `configurations`
- `targets`
- `target_edges`
- `target_outputs`
- `target_status_history`

### Actions and attempts

- `actions`
- `action_attempts`
- `action_status_history`
- `action_logs`
- `action_commands`
- `action_environment`

Separate logical actions from execution attempts.

### Artifacts and dependency sets

- `artifacts`
- `artifact_digests`
- `depsets`
- `depset_children`
- `depset_artifacts`
- `action_input_roots`
- `action_outputs`
- `action_edges`

Do not eagerly flatten every depset into duplicated action-input rows.

### Tests

- `tests`
- `test_attempts`
- `test_outputs`
- `test_summaries`

### Profiles and metrics

- `profile_spans`
- `build_metrics`
- `metric_values`
- `aggregate_metrics`
- `quantile_sketches`

### Diagnostics and user data

- `findings`
- `annotations`
- `saved_filters`
- `bookmarks`

## 10.8 Required indexes

At minimum:

- Action start time
- Action end time
- Action duration
- Action mnemonic
- Action owner target
- Action status
- Attempt runner
- Attempt cache status
- Attempt duration
- Artifact path
- Artifact producer
- Action-edge source
- Action-edge destination
- Target-edge source
- Target-edge destination
- Event sequence
- Event type and sequence
- Test label/status

Create full-text indexes only for selected fields such as:

- Labels
- Mnemonics
- Output paths
- Failure messages

Indexing complete command lines must be opt-in because of size and privacy.

## 10.9 SQLite operation

Use:

- WAL mode during active capture
- One writer connection
- Multiple short-lived or pooled read connections
- Prepared statements
- Batched writes
- Explicit transactions
- Bounded statement caches
- Short read transactions
- Cancellable long-running queries
- `ANALYZE` after major finalization stages

Database tuning must be configurable and benchmarked.

---

# 11. Domain model

## 11.1 Stable identifiers

Define strongly typed identifiers:

- `SessionId`
- `InvocationId`
- `StreamId`
- `BepEventId`
- `ConfigurationId`
- `TargetId`
- `ActionId`
- `ActionAttemptId`
- `ArtifactId`
- `DepsetId`
- `TestId`
- `ProfileSpanId`

Do not expose database row IDs as cross-session identities.

## 11.2 Action versus attempt

A logical action may have:

- No execution attempt
- One execution attempt
- Multiple attempts
- Local and remote competing attempts
- Failed attempts followed by success
- Cache lookup without execution
- Retry behavior

The action table holds logical identity and aggregate outcome.

The attempt table holds:

- Runner
- Host or worker where available
- Cache result
- Start/end
- Queue time
- Setup time
- Execution time
- Network time
- Upload/download time
- Exit code
- Attempt status
- Attempt-specific command or environment
- Source record

## 11.3 Artifact semantics

Track:

- Path
- Workspace-relative path when possible
- Artifact type
- Digest
- Known byte size
- Producer action
- Whether source/generated
- Whether local/remote/inline
- Symlink information
- Tree/directory status
- Data source

Do not automatically read artifact contents.

For byte totals, retain:

- Known bytes
- Unknown artifact count
- Unique artifact count
- Duplicate-reference count

## 11.4 Missing data

Represent every optional metric using:

- Present value
- Source
- Confidence/completeness
- Optional warning

Never convert unavailable data to zero.

Examples:

- `inputBytes = unknown`
- `knownInputBytes = 4.2 GiB`
- `unknownInputCount = 137`

## 11.5 Provenance

Use a source bitmask or normalized source table:

- BEP
- BES envelope
- Execution log
- Profile
- `aquery`
- `cquery`
- `query`
- Filesystem
- Derived
- User annotation

The inspector must display provenance.

---

# 12. Cross-source correlation

## 12.1 Correlation priorities

Prefer, in order:

1. Exact action key where available in both sources.
2. Exact primary-output identity.
3. Complete output-set match.
4. Owner target and configuration match.
5. Mnemonic match.
6. Command digest match.
7. Temporal compatibility.
8. Unique candidate after all available constraints.

## 12.2 Suggested scoring model

Initial correlation score:

| Evidence | Score |
|---|---:|
| Exact action key | 100 |
| Exact primary output | 40 |
| Exact output-set digest | 30 |
| Exact owner target | 20 |
| Exact configuration checksum | 15 |
| Exact mnemonic | 10 |
| Exact command digest | 10 |
| Compatible timing | 5 |
| Conflicting owner target | -50 |
| Conflicting primary output | -80 |
| Conflicting mnemonic | -20 |

Rules:

- A unique exact action-key match is definitive.
- Without an action key, require a score of at least 70 for automatic attachment.
- A tie is ambiguous.
- Ambiguous records remain unattached and visible in diagnostics.
- Persist the score, evidence, and candidate count.
- Add real fixtures before adjusting scoring.

## 12.3 Data-source precedence

Do not overwrite lower-priority source fields. Store them separately.

Preferred presentation:

- Logical action start/end: BEP when clearly action-level.
- Attempt timings: execution log.
- Declared inputs: `aquery`.
- Actual inputs: execution log.
- Build phases: profile and `BuildMetrics`.
- Bazel critical path: Bazel source.
- Dependency critical path: derived action graph.
- Target dependencies: `cquery` or `query`.
- Action dependencies: artifact producer/consumer derivation.

## 12.4 Configuration mismatch

If auxiliary graph commands cannot reproduce the primary invocation's configuration:

- Mark the graph `PARTIAL_OR_MISMATCHED`.
- List omitted or changed options.
- Do not silently attach uncertain graph data.
- Permit the user to inspect raw query output.
- Allow manual rerun with edited options.

---

# 13. Graph construction and algorithms

## 13.1 Action-edge derivation

For each action:

1. Resolve declared or actual input artifacts.
2. Find each artifact's producing action.
3. Emit a direct producer-to-consumer edge.
4. Exclude source artifacts with no producer.
5. Deduplicate repeated producer/consumer pairs.
6. Optionally retain artifact-level edge details.
7. Record whether the edge came from declared or observed inputs.

Use external sorting for very large edge sets:

1. Emit edge pairs into bounded in-memory buffers.
2. Sort and write temporary runs.
3. Merge runs.
4. Deduplicate.
5. Write SQLite edge rows and CSR files.

## 13.2 CSR indexes

Create forward and reverse compressed-sparse-row indexes.

Files contain:

- Header and format version
- Node count
- Edge count
- Offset array
- Edge target array
- Optional edge metadata references
- Checksum

Use:

- Primitive numeric arrays
- Memory mapping
- Segmenting when mappings become too large
- Atomic build-and-rename
- Versioned index files

Do not instantiate a Java object for every node and edge.

## 13.3 Required algorithms

Implement:

- Forward breadth-first traversal
- Reverse breadth-first traversal
- Depth-limited neighborhood extraction
- Node-budget-limited traversal
- Shortest unweighted path
- Topological ordering
- Strongly connected component detection for diagnostics
- Weighted longest path on a DAG
- Critical-path reconstruction
- Earliest start/finish
- Latest start/finish
- Slack
- Direct fan-in/fan-out
- Exact transitive counts for selected nodes within a configurable budget
- Cached approximate or bounded counts for global summaries
- Package/target/mnemonic clustering

Never compute or store a complete transitive closure.

## 13.4 Derived dependency critical path

For each node `v` with duration weight `w(v)`:

```text
earliestStart(v)  = max(earliestFinish(p)) for predecessors p
earliestFinish(v) = earliestStart(v) + w(v)
```

The path ending at the node with maximum earliest finish is the derived dependency critical path.

Backward pass:

```text
latestFinish(v) = min(latestStart(s)) for successors s
latestStart(v)  = latestFinish(v) - w(v)
slack(v)        = latestStart(v) - earliestStart(v)
```

Caveats:

- Run only on a complete acyclic graph.
- If cycles exist, identify them and compute on a condensed component graph.
- Label the result "Visualizer-computed dependency critical path."
- Do not present it as Bazel's own critical path.
- Explain whether action or attempt duration was used.
- Mark results partial when graph or timing coverage is incomplete.

## 13.5 Graph display modes

Provide:

1. **Package overview**
2. **Target overview**
3. **Mnemonic overview**
4. **Selected action neighborhood**
5. **Dependencies**
6. **Reverse dependencies**
7. **Path between two nodes**
8. **Critical path**
9. **Filtered raw subgraph**
10. **Whole-build density/cluster view**
11. **BEP event graph**

## 13.6 Large-graph rendering

Use semantic zoom:

### Far zoom

- Package/target/mnemonic clusters
- Aggregate node size
- Aggregate edge thickness
- Heat colors
- No individual labels

### Medium zoom

- Expanded subclusters
- Selected high-impact actions
- Limited edge bundling
- Labels for significant entities

### Near zoom

- Individual actions
- Exact edges
- Full labels
- Hit testing
- Hover details

Rendering implementation:

- World-coordinate model using doubles
- Viewport transform
- Quadtree or equivalent spatial index
- Viewport culling
- Label thresholds
- Cached background tiles where beneficial
- Separate layout and paint stages
- Layout cancellation
- Cached layouts keyed by graph query and settings

Default detailed-layout limits should be configurable, with an initial default around:

- 50,000 nodes
- 200,000 edges

Above the limit:

- Switch to cluster/density mode.
- Show exact total counts.
- Offer "raise limit," "export," and "refine filter."
- Never claim the omitted nodes do not exist.

## 13.7 Layout strategies

- Layered DAG layout for dependency subgraphs
- Radial layout for selected-node neighborhoods
- Linear layout for critical paths
- Grid or matrix layout for dense cluster summaries
- Force-directed layout only for small graphs
- Deterministic stable positioning where possible

Do not run force-directed layout on an unbounded action graph.

---

# 14. Timeline design

## 14.1 Timeline entities

Support spans for:

- Logical actions
- Execution attempts
- Tests
- Bazel phases
- Profile events
- Remote transfer activity where available
- User bookmarks

## 14.2 Lanes

Group by:

- Runner
- Worker/thread
- Mnemonic
- Target/package
- Execution platform
- Cache result
- Test shard/run
- User-selected field

Allow sorting by:

- First activity
- Total duration
- Critical-path contribution
- Action count
- Name

## 14.3 Level-of-detail pyramid

Build multiple time resolutions.

For each time bin, store:

- Action count
- Active-action estimate
- Total overlapping duration
- Maximum overlap
- Cache-hit/miss counts
- Local/remote counts
- Failure count
- Input/output bytes where known
- Top mnemonics or category summary

At broad zoom levels, draw aggregated density.

At close zoom levels, query and draw individual spans.

## 14.4 Live insertion

When an action arrives after completion:

- Insert its span based on supplied timestamps.
- Update affected aggregate bins.
- Animate or highlight newly received data without moving the user's viewport.
- Do not auto-scroll if the user has navigated into history.
- Offer a "Follow live" toggle.

## 14.5 Timeline interactions

- Scroll/pan
- Trackpad zoom
- Drag zoom range
- Fit invocation
- Fit selection
- Jump to next slow action
- Jump to next failure
- Select action
- Open context menu
- Filter selected time range
- Compare concurrency with critical path
- Copy timestamps and duration

---

# 15. Metrics catalog

## 15.1 Action metrics

| Metric | Source or formula | Notes |
|---|---|---|
| Logical wall duration | Action end minus start | Keep source-specific variants |
| Attempt duration | Attempt end minus start | One row per attempt |
| Queue duration | Execution log | Unknown when absent |
| Setup duration | Execution log | Source-specific |
| Execution duration | Execution log | Distinguish process time from logical wall time |
| Network duration | Execution log | When reported |
| Upload duration | Execution log | When reported |
| Download/fetch duration | Execution log | When reported |
| Retry count | Attempt count minus one | Explain competing attempts separately |
| Input file count | Expanded actual or declared inputs | Report which definition |
| Known input bytes | Sum of known unique input sizes | Report unknown count |
| Output file count | Outputs | Include tree-artifact caveat |
| Known output bytes | Sum of known output sizes | Report unknown count |
| Direct dependencies | Incoming action edges | Exact when graph complete |
| Direct reverse dependencies | Outgoing action edges | Exact when graph complete |
| Transitive dependencies | Bounded exact or approximate | Always label method |
| Transitive reverse dependencies | Bounded exact or approximate | Always label method |
| Fan-in | Direct producer count | Deduplicated |
| Fan-out | Direct consumer count | Deduplicated |
| Critical-path membership | Bazel-reported or derived | Keep variants separate |
| Critical-path contribution | Node weight on selected path | Not equivalent to summed wall impact |
| Slack | Derived graph schedule | Requires complete timing and graph |
| Cache state | Execution log/BEP | Include unknown |
| Runner | Execution log/BEP where available | Local, remote, worker, sandbox, other |
| Exit status | BEP/execution log | Preserve structured failure |
| Target owner | BEP/aquery | Configuration-aware |
| Action key | `aquery`/execution log | Sensitive diagnostic identifier |
| Command size | Argument count and encoded length | Avoid displaying secret values by default |
| Environment size | Variable count and encoded length | Values may be redacted |
| Start concurrency | Timeline sweep | Number of active actions at start |
| Completion concurrency | Timeline sweep | Number active immediately before completion |
| Data completeness | Derived | Percent or categorical coverage |

## 15.2 Invocation metrics

- Total wall time
- Time to first event
- Load phase duration
- Analysis phase duration
- Execution phase duration
- Finalization duration
- Total logical actions
- Total attempts
- Successful/failed/cancelled action counts
- Cache hit, miss, and unknown counts
- Local/remote/worker action counts
- Total known input/output bytes
- Upload/download bytes and time where available
- Peak observed concurrency
- Average observed concurrency
- Parallelism factor
- Bazel-reported critical path
- Derived dependency critical path
- Time with zero active actions during execution phase
- Time with low concurrency
- Test totals and flaky-test count
- Event ingestion lag
- Raw event count
- Unresolved/corrupt event count
- Correlation success rate
- Graph completeness
- Timing completeness
- Size completeness

## 15.3 Aggregate dimensions

Aggregate by:

- Mnemonic
- Target
- Package
- Rule class
- Configuration
- Execution platform
- Runner
- Cache state
- Status
- Test suite
- Time window

For duration and size distributions, provide:

- Count
- Sum
- Minimum
- Maximum
- Mean
- Median
- p90
- p95
- p99

Use mergeable quantile sketches for global live aggregates and exact SQL calculations for bounded selected result sets.

## 15.4 Parallelism factor

Display:

```text
sum of selected action durations / selected wall-clock interval
```

Explain that this is an aggregate concurrency indicator, not CPU utilization.

## 15.5 Data coverage

Every dashboard must show coverage, for example:

```text
Timing coverage:        99.8% of actions
Runner coverage:        87.4% of actions
Input-size coverage:    72.1% of input artifacts
Action-graph coverage:  complete
Target-graph coverage:  unavailable
Correlation confidence: 96.3% definitive, 2.8% probable, 0.9% unresolved
```

---

# 16. Diagnostic findings

Findings are evidence-backed optimization candidates.

Each finding must contain:

- Title
- Severity
- Confidence
- Evidence
- Affected actions/targets
- Metric values
- Threshold used
- Why it may matter
- Caveats
- Suggested next investigation
- Links to relevant views

## 16.1 Initial finding rules

### Long critical chain

Detect a long derived or Bazel-reported critical path.

Show:

- Path length
- Top contributors
- Serial sections
- Slack
- Whether graph coverage is complete

### High-fan-out action

Detect an action with many direct or transitive consumers.

Explain that optimizing or caching it may unblock substantial downstream work.

### High input-volume action

Detect actions with large known unique inputs.

Show unknown-size coverage so incomplete totals are not misleading.

### High output-volume action

Detect expensive output production or transfer candidates.

### Many tiny actions

Detect a large count of short actions whose aggregate overhead is material.

Group by mnemonic, target, and runner.

### Low-parallelism window

Detect periods during the execution phase with significantly fewer active actions than the session's typical concurrency.

Link to blocking dependency chains.

### Queue-dominated action

Detect actions where queue time is a large fraction of total attempt time.

Do not assume the cause is Bazel rather than remote infrastructure.

### Transfer-dominated action

Detect upload/download/network time dominating execution.

### Cache-miss concentration

Detect groups with a high miss rate.

Require sufficient cache-state coverage.

### Non-cacheable or local-only concentration

Identify mnemonics or targets that prevent expected cache/remote behavior.

### Repeated attempts

Identify retries, local/remote races, or repeated failures.

### Slow tail

Identify a small number of late actions that extend completion.

### Graph mismatch

Warn when the action graph cannot be reliably correlated with observed execution.

## 16.2 Finding language

Use:

- "Candidate"
- "Associated with"
- "May indicate"
- "Investigate"

Avoid:

- "Root cause" unless explicit failure data proves it
- "Will improve build time"
- "Definitely caused by"

---

# 17. Swing UI specification

## 17.1 Main window

### Top launcher bar

- Workspace selector
- Bazel executable selector
- Command editor
- Capture-preset selector
- Run/cancel control
- Session state
- Capture-lag indicator

### Left navigation

- Overview
- Timeline
- Actions
- Targets
- Graph
- Tests
- Failures
- Events
- Console
- Capture
- Findings

> **As built (2026-08-23):** ten entries, not these eleven. **Console** and
> **Capture** are one **Build** entry — the capture status is a header strip
> above the console, because following one build meant reading both — and
> **Failures** is named **Errors**, because the view also lists Bazel's console
> diagnostics and a warning on stderr is not a failure. Sections 17.10 and 17.12
> below are the plan text for those views and are unchanged; only the names and
> the card count moved.
>
> **As built (2026-08-24):** twelve entries. **Query** was added after v1 (SQL
> over the captured data; in no phase of this plan), and **Graph** split into
> **Graph** — the Phase 7 rendered canvas, now with selectable node weights —
> and **Tree** — the Phase 5 dependency trees, search and path-between-nodes.
> `NavEntry`'s javadoc and `docs/graph-model.md` carry the reasons.
>
> **As built (2026-08-25):** the launcher moved from a frame-wide bar into the
> top of that merged card, followed by capture status and console output. The
> stable `NavEntry.BUILD`/`build` identifiers remain, but the visible entry is
> **Console** and comes first. Its labelled four-row form persists workspace,
> Bazel executable, capture detail, the editable command and 50-entry unique
> command history under `settings/`, with disk I/O off the EDT. The three
> visible capture choices explain their scope and cost; Custom remains a model
> value but is not offered without an individual-source editor. ADR-007's
> effective-command dialog remains a separate required review.

### Right inspector

Dockable and resizable.

Tabs depend on selection:

- Summary
- Timing
- Inputs
- Outputs
- Dependencies
- Reverse dependencies
- Attempts
- Command
- Environment
- Logs
- Raw
- Provenance

### Bottom status bar

- Session state
- Build state
- Event count
- Action count
- Ingestion rate
- Normalization backlog
- Database size
- Raw size
- Warnings
- Current filter

## 17.2 Start screen

Show:

- New invocation
- Open BEP/session
- Recent sessions
- Pinned sessions
- Workspace grouping
- Session sizes
- Cleanup controls

## 17.3 Overview

Cards:

- Build outcome
- Wall time
- Action count
- Cache summary
- Critical path
- Peak concurrency
- Input/output bytes
- Test summary
- Data completeness

Charts:

- Phase timeline
- Active actions over time
- Duration by mnemonic
- Cache result by mnemonic
- Longest actions
- Critical-path contributors
- Known bytes by mnemonic
- Findings

Every card must navigate to a filtered detailed view.

## 17.4 Actions view

Use a paged, database-backed `TableModel`.

Default columns:

- Start
- End
- Duration
- Mnemonic
- Owner target
- Primary output
- Status
- Cache result
- Runner
- Queue time
- Execution time
- Input count
- Input bytes
- Output count
- Output bytes
- Direct deps
- Direct reverse deps
- Critical
- Slack
- Attempts

Features:

- Column chooser
- Reordering
- Saved layouts
- SQL-backed sorting
- Structured filtering
- Copy cells/rows
- Export current result
- Pin action
- Open subgraph
- Reveal in timeline
- Compare attempts

Do not use `TableRowSorter` over the complete result set.

## 17.5 Virtual table behavior

- Fetch rows in windows around the viewport.
- Cache a bounded number of windows.
- Cancel obsolete fetches.
- Represent row count as exact or estimated.
- Preserve selection by stable `ActionId`, not row number.
- Avoid blocking `getValueAt`.
- Return cached placeholders while a page loads.
- Trigger narrow row updates when data arrives.
- Test JTable geometry with 50 million logical rows.
- If Swing viewport integer geometry becomes a practical limit, replace only the scrolling shell with a custom logical scrollbar while retaining reusable cell rendering.

## 17.6 Trees

Trees include:

- Package/target tree
- Target dependency tree
- Action dependency tree
- Reverse-dependency tree
- BEP event tree

Requirements:

- Lazy children
- Child-count query
- Paging for nodes with many children
- Cycle guard
- "Load next" synthetic nodes where needed
- Path breadcrumbs
- Depth and node budgets
- Clear partial-result indicators

## 17.7 Graph canvas

Input behavior:

- Pan
- Zoom around pointer
- Box select
- Multi-select
- Fit
- Navigate history
- Expand dependencies
- Expand reverse dependencies
- Collapse cluster
- Change layout
- Copy label
- Export visible graph

Rendering:

- Antialias selectively
- Disable expensive detail while actively panning
- Draw labels only above scale thresholds
- Use spatial hit testing
- Never perform layout on the EDT
- Never query SQLite from `paintComponent`

## 17.8 Timeline

- Custom Java2D component
- Independent header and lane labels
- Synchronized horizontal viewport
- Density at broad zoom
- Individual spans at close zoom
- Critical-path overlay
- Cache and runner color modes
- Selection synchronization
- Follow-live mode
- Time-range filter creation

## 17.9 Tests view

Show:

- Test label
- Status
- Cached state
- Total duration
- Runs
- Shards
- Attempts
- Flaky status
- Failed attempts
- Output/log links
- Owner target
- Timeline navigation

## 17.10 Failures view

> Shipped as the **Errors** view; see the note under 17.1's left navigation.

Combine:

- Failed actions
- Failed targets
- Failed tests
- Aborted events
- Structured failure details
- Relevant console excerpts
- Candidate causal chain

Clearly distinguish explicit structured cause from inferred relationships.

## 17.11 Events view

Modes:

- Chronological table
- BEP event graph
- Event-ID lookup
- Missing-announced-child report
- Duplicate report
- Raw protobuf inspector

Render full protobuf text only for the selected event.

## 17.12 Console

> Shipped as the body of the **Build** pane, under the capture-status header;
> see the note under 17.1's left navigation.

- Separate stdout and stderr channels
- Combined timestamped view
- Search
- Pause visual updates without stopping capture
- Follow mode
- ANSI handling
- Maximum in-memory text window
- Complete output retained on disk
- Jump from failure records to matching output ranges

## 17.13 Capture view

Show:

- Original/effective command
- Added flags
- BES endpoint
- Streams
- Sequence progress
- Source files
- Queue depth
- Ingestion rate
- Journal flush state
- Normalization progress
- Enrichment tasks
- Correlation progress
- Graph-index progress
- Warnings and errors

---

# 18. Filter and search language

Implement a small typed expression language.

Examples:

```text
mnemonic:CppCompile
duration > 2s
input_bytes >= 100MiB
cache:miss AND runner:remote
target:"//app/..."
critical:true
status:failed OR attempts > 1
start >= 30s AND start < 60s
rdeps > 1000
```

Support:

- `AND`
- `OR`
- `NOT`
- Parentheses
- Equality and inequality
- Contains/prefix matching
- Duration units
- Byte units
- Boolean fields
- Enum aliases
- Quoted strings

Implementation:

1. Tokenize.
2. Parse into an AST.
3. Type-check field/operator compatibility.
4. Compile to parameterized SQL.
5. Keep graph predicates as explicit post-query or graph-index operations.
6. Show syntax errors inline.
7. Debounce interactive execution.
8. Allow cancellation.
9. Save named filters.

Never concatenate user text into SQL.

---

# 19. Threading model

## 19.1 Event Dispatch Thread

The EDT may perform only:

- Component creation
- Component mutation
- Small model notifications
- Painting
- Input handling
- Reading immutable cached view data

It must never perform:

- File parsing
- Protobuf decoding
- SQL queries
- Database writes
- Graph traversal
- Graph layout
- Profile import
- Archive compression
- Process waiting
- Hashing large files

## 19.2 Executors

Use distinct executors:

- Virtual-thread executor for orchestration and blocking I/O
- Single journal writer
- Bounded protobuf parser pool
- Single SQLite writer
- Small SQLite read pool
- CPU analysis pool
- Graph-layout pool
- Export/import pool

Name threads and include session IDs in diagnostic context.

## 19.3 UI notifications

Aggregate model changes.

Do not issue one Swing event per BEP event.

Use:

- Periodic immutable snapshots for dashboard summaries
- Coalesced row updates
- Stable selection IDs
- Backpressure-aware refresh rates

---

# 20. Massive-scale strategy

## 20.1 Benchmark tiers

### Tier 1

- 100,000 actions
- 1 million events
- 1 million graph edges
- 8 GB system memory

### Tier 2

- 1 million actions
- 10 million events
- 20 million graph edges
- 16 GB system memory

### Tier 3

- 5 million actions
- 50 million events
- 100 million graph edges
- 32 GB system memory

Generate deterministic synthetic fixtures for all tiers.

## 20.2 Initial performance objectives

On a representative Apple Silicon Mac with SSD:

- Raw capture path sustains 100,000 small synthetic events per second for burst tests without loss.
- Normalization sustains at least 25,000 representative events per second.
- Capture remains correct if normalization temporarily falls behind.
- Live UI updates at least four times per second under ordinary load.
- Panning and zooming aggregate timeline/graph views targets 30 frames per second.
- A cached action-table page appears within 100 milliseconds.
- An uncached indexed page normally appears within 500 milliseconds.
- Opening an already indexed Tier 3 session shows its overview within five seconds without loading all actions.
- Application-managed heap remains below 4 GB for Tier 3 under normal aggregate viewing.
- No routine EDT pause exceeds 100 milliseconds.
- Long queries are cancellable.
- Session finalization can resume after application restart.

Treat these as benchmark targets, not unsupported guarantees. Record actual results in `docs/performance.md`.

## 20.3 User-configurable limits

Settings must include:

- Maximum application heap guidance
- Raw journal segment size
- Journal flush interval
- Normalization batch size
- Parser concurrency
- UI refresh frequency
- Table cache size
- Complete versus essential event indexing
- Maximum detailed graph nodes
- Maximum detailed graph edges
- Maximum graph-layout time
- Traversal node budget
- Traversal depth
- Exact transitive-count budget
- Timeline individual-span threshold
- Disk quota warning
- Portable-export temporary-space policy
- Full-text command indexing
- Filesystem stat enrichment

## 20.4 Limit behavior

When a limit is reached:

- Stop the expensive operation safely.
- Preserve raw capture.
- Show the exact limit.
- Show total known entities.
- State whether results are partial.
- Offer to raise the limit.
- Offer aggregation.
- Offer export.
- Never silently discard or sample.

---

# 21. Reliability and recovery

## 21.1 Crash recovery

On startup:

1. Find sessions with active locks or non-terminal states.
2. Scan journal segments to the final valid checksum.
3. Truncate only invalid trailing bytes.
4. Compare raw checkpoints with database checkpoints.
5. Resume normalization from the last committed raw sequence.
6. Rebuild incomplete indexes.
7. Mark the session recovered with a diagnostic entry.

## 21.2 Disk-full behavior

- Detect write failures immediately.
- Stop acknowledging new BES events.
- Warn the user prominently.
- Attempt to preserve already written data.
- Offer process cancellation.
- Do not continue while claiming capture is complete.
- Mark the source incomplete.

## 21.3 Malformed input

- Limit protobuf message size.
- Limit JSON record size.
- Catch parse errors per frame where boundaries are known.
- Record offsets and error details.
- Continue only when a reliable next boundary exists.
- Do not scan arbitrary binary data for guessed protobuf boundaries.
- Mark truncation distinctly from corruption.

## 21.4 Enrichment failure

Each enrichment task is independent.

A failed profile import must not invalidate BEP.

Show:

- Task
- Command or source
- Exit status
- Error excerpt
- Retriability
- Resulting unavailable metrics

## 21.5 Unknown schema fields

- Preserve original raw bytes.
- Preserve protobuf unknown fields where supported.
- Record unrecognized payloads.
- Show an "unknown event fields" diagnostic.
- Permit later reindexing with a newer application version.

---

# 22. Security and privacy

## 22.1 Local-only networking

- Bind BES to loopback.
- Reject non-loopback configuration in v1 unless a developer-only switch is enabled.
- Do not expose an HTTP server.
- Do not send telemetry by default.

## 22.2 Sensitive data

Potentially sensitive fields include:

- Absolute paths
- Command arguments
- Environment values
- Repository names
- Remote-cache endpoints
- User names
- Test logs
- Artifact names
- Credentials accidentally passed to actions

Provide:

- Environment-name redaction patterns
- Command-argument redaction patterns
- Redacted copy actions
- Redacted portable export
- Session sensitivity warning
- Optional omission of environment values while retaining names

Default secret-name patterns should cover common token, password, credential, and key names, while remaining user-editable.

## 22.3 Process safety

- Use direct argv by default.
- Do not interpolate command text into shell scripts.
- Clearly label explicit shell mode.
- Do not execute commands embedded in imported sessions.
- Imported session commands are display-only.

## 22.4 Archive and parser safety

- Prevent zip-slip.
- Limit expanded archive size.
- Limit entry count.
- Reject duplicate manifest entries.
- Validate checksums.
- Treat imported SQLite databases as untrusted; prefer rebuilding from raw files unless the archive format and database schema pass validation.
- Never load native code from a session archive.

---

# 23. Testing strategy

## 23.1 Unit tests

Test:

- Command tokenization
- Flag detection
- Flag precedence
- Instrumentation plans
- Capability parsing
- Session-state transitions
- Journal frame encoding
- Journal recovery
- Sequence handling
- Duplicate handling
- Gap detection
- Binary framing
- JSON stream detection
- Event-ID canonicalization
- Domain normalization
- Correlation scoring
- Metric formulas
- Critical-path calculation
- Cycle handling
- Query parser
- SQL compiler
- Redaction
- Manifest migration
- Archive security

## 23.2 Integration tests

### Embedded BES

Use a fake client to test:

- Lifecycle events
- Ordered stream
- Duplicate sequence
- Gap
- Reconnect
- Slow journal
- Backpressure
- Cancellation
- Final acknowledgement
- Incomplete stream

### Real Bazel fixtures

Maintain tiny workspaces covering:

- Successful build
- Incremental no-op build
- Local cache hit
- Failed compile
- Failed analysis
- Successful test
- Failed test
- Test shards
- Test retries/flakiness where reproducible
- Cancelled build
- Multiple configurations
- Generated files
- Tree artifacts where practical
- Actions with large input sets
- Workspace-relative and absolute paths

Run or preserve fixtures for Bazel 6, 7, 8, and 9.

### Import tests

- Complete binary BEP
- Truncated binary BEP
- Complete JSON BEP
- Large JSON record
- Unknown fields
- Portable session
- Redacted session
- Interrupted import resume

## 23.3 Database tests

- Migration forward
- Recovery after interrupted transaction
- Batched ingestion
- Concurrent reads during writes
- Query cancellation
- Index correctness
- Large row counts
- Corruption reporting

## 23.4 Graph tests

- DAG
- Disconnected graph
- Diamond dependencies
- High-fan-out graph
- High-fan-in graph
- Cycle
- Multi-edge artifacts
- Missing producer
- Partial graph
- Million-node synthetic graph
- CSR forward/reverse consistency

## 23.5 UI tests

Use a Swing UI test harness for:

- Launch validation
- Session navigation
- Table paging
- Selection synchronization
- Filter errors
- Inspector updates
- Cancel flow
- Open-file handling
- Limit warnings
- Dark/light themes
- Keyboard navigation

Do not rely exclusively on screenshot tests.

## 23.6 Performance tests

Create repeatable benchmarks for:

- Journal append throughput
- Protobuf decode throughput
- SQLite action inserts
- Artifact/depset inserts
- Action-edge external sort
- CSR construction
- BFS traversal
- Longest-path calculation
- Action-page queries
- Timeline LOD generation
- Java2D timeline painting
- Java2D graph painting
- Portable export
- Session reopen

Performance regressions above agreed thresholds should fail a dedicated benchmark gate or at least generate a report.

## 23.7 Fuzzing

Fuzz:

- Binary frame lengths
- Protobuf payloads
- JSON events
- Query language
- Archive paths
- Manifest fields
- Journal recovery

---

# 24. Implementation phases

Implement phases in order. Each phase must end with tests, documentation, and an executable demonstration.

## Phase 0: Repository and architectural spikes

### Tasks

- Create Gradle multi-module repository.
- Configure Java 21 toolchains. *(Delivered as Java 25 toolchains; see ADR-008.)*
- Add CI for macOS and Linux.
- Configure dependency locking.
- Add logging.
- Add FlatLaf shell.
- Vendor initial protobuf definitions.
- Add JMH.
- Create Tier 1–3 synthetic data generators.
- Benchmark:
  - JTable with 50 million logical rows
  - SQL-backed page retrieval
  - Custom Java2D timeline
  - Custom Java2D graph painting
- Write ADRs.
- Establish application-support paths.

### Exit criteria

- Application window launches on macOS.
- Synthetic paged table remains responsive.
- Aggregate timeline can pan and zoom without loading individual spans.
- Chosen Swing approach meets initial spike thresholds or an ADR documents the revised rendering strategy.
- `./gradlew check` succeeds.

## Phase 1: Session, journal, and offline BEP import

### Tasks

- Implement session state machine.
- Implement managed-session directories.
- Implement manifest.
- Implement raw segmented journal.
- Implement binary BEP stream parser.
- Implement JSON format detection and streaming parser.
- Implement SQLite schema v1.
- Normalize basic lifecycle and event metadata.
- Implement recovery checkpoints.
- Build a minimal headless import command for testing.
- Add official and generated fixtures.

### UI deliverable

- Open BEP file.
- Show import progress.
- Show chronological event table.
- Select an event and inspect raw protobuf.
- Reopen indexed session.

### Exit criteria

- Complete and truncated BEP files import.
- Restart resumes interrupted indexing.
- Original source is preserved.
- Event counts and offsets are reproducible.
- No full file is loaded into memory.

## Phase 2: Bazel launcher and embedded BES

### Tasks

- Implement executable/workspace detection.
- Implement command model.
- Implement capability detection.
- Implement instrumentation planner.
- Implement gRPC BES server.
- Implement sequence tracking and acknowledgements.
- Implement process launch.
- Capture stdout/stderr.
- Implement cancellation.
- Correlate invocation and stream identifiers.
- Persist effective command and injected flags.
- Add binary-file fallback for BES conflicts.

### UI deliverable

- Launcher
- Instrumentation-plan dialog
- Live capture status
- Console
- Cancel/terminate controls

### Exit criteria

- A real Bazel 6–9 fixture build can be launched.
- Events arrive through the embedded BES.
- No accepted event is silently dropped.
- Duplicate sequences are idempotent.
- A cancelled build creates an inspectable partial session.
- Existing BES conflicts require an explicit decision.

## Phase 3: Core target, action, test, and artifact normalization

### Tasks

- Normalize configurations.
- Normalize targets.
- Normalize named file/depset structures.
- Normalize logical actions.
- Normalize action timestamps and status.
- Normalize outputs and logs.
- Normalize tests and summaries.
- Build string/path dictionaries.
- Implement incremental overview aggregates.
- Add source completeness.

### UI deliverable

- Live overview
- Actions table
- Targets tree
- Tests table
- Failures table
- Shared inspector

### Exit criteria

- Successful and failed builds produce coherent action/target/test records.
- Action table supports paging, filtering, and sorting.
- Live updates are coalesced.
- Unknown values are visibly unknown.
- Event-to-domain provenance is inspectable.

## Phase 4: Execution-log and profile enrichment

### Tasks

- Detect supported execution-log format.
- Plan and capture execution-log output.
- Parse it streaming.
- Create action attempts.
- Correlate attempts with actions.
- Parse JSON trace profile.
- Normalize build phases and selected spans.
- Import final build metrics.
- Calculate correlation diagnostics.
- Add optional filesystem stat enrichment.

### UI deliverable

- Attempt inspector
- Detailed timing breakdown
- Runner/cache columns
- Phase overview
- Data-coverage panel
- Enrichment task status

### Exit criteria

- Detailed metrics appear without replacing source-specific values.
- Ambiguous correlations remain visible.
- Enrichment failure does not invalidate BEP.
- Profile and execution-log imports are resumable where practical.

## Phase 5: `aquery`, `cquery`, and graph construction

### Tasks

- Generate auxiliary command plans.
- Capture protobuf query outputs.
- Import action graph.
- Import configured-target graph.
- Correlate graph actions.
- Preserve depset DAG.
- Derive artifact producer/consumer edges.
- Implement external edge sorting.
- Build forward/reverse CSR indexes.
- Add graph completeness diagnostics.

### UI deliverable

- Dependency and reverse-dependency trees
- Selected-action neighborhood
- Path-between-nodes
- Graph-source selector

### Exit criteria

- Direct action dependencies and reverse dependencies are queryable.
- Large graph construction is bounded-memory.
- Configuration mismatches are visible.
- Forward and reverse indexes are consistent.
- A failed auxiliary query leaves the rest of the session usable.

## Phase 6: Timeline

### Tasks

- Build timeline LOD index.
- Implement custom Swing timeline.
- Add action and attempt lanes.
- Add grouping and sorting.
- Add live retroactive insertion.
- Add selection synchronization.
- Add time-range filtering.
- Add critical-path overlay.

### Exit criteria

- Broad views use aggregate bins.
- Close views show exact spans.
- Tier 2 remains interactive.
- No SQLite access occurs during painting.
- Live updates do not reset viewport or selection.

## Phase 7: Graph visualization

### Tasks

- Implement graph extraction API.
- Implement clustering.
- Implement layered, radial, and critical-path layouts.
- Implement spatial index.
- Implement custom Java2D graph canvas.
- Add semantic zoom.
- Add limit estimation and warnings.
- Cache layouts.
- Add export of visible and complete filtered graphs.

### Exit criteria

- Small subgraphs render in full detail.
- Large graphs automatically aggregate.
- Exact totals remain visible.
- Raising limits is explicit.
- Layout is cancellable.
- Panning and selection remain responsive.

## Phase 8: Metrics and findings

### Tasks

- Implement action/invocation metrics.
- Implement quantile sketches.
- Implement concurrency sweep.
- Implement dependency critical path and slack.
- Implement findings engine.
- Add evidence and caveats.
- Add dashboard navigation.

### Exit criteria

- Every displayed metric reports source and completeness.
- Bazel-reported and derived critical paths remain distinct.
- Findings link to supporting records.
- Findings avoid unsupported causal language.
- Formulas have deterministic unit tests.

## Phase 9: Session export, redaction, and macOS packaging

### Tasks

- Implement portable `.bviz`.
- Implement binary BEP export.
- Implement CSV/JSON graph and table exports.
- Implement redacted export.
- Add recent-session library.
- Add retention and cleanup.
- Add macOS file associations.
- Add macOS app menu and open-file handlers.
- Build Apple Silicon and Intel packages.
- Add signing/notarization hooks without embedding credentials.

### Exit criteria

- Sessions survive application restart and relocation.
- Portable archives validate before opening.
- File associations open the app.
- Export does not require loading the entire session into memory.
- Redaction tests pass.

## Phase 10: Scale hardening and compatibility release gate

### Tasks

- Run all benchmark tiers.
- Profile heap allocation.
- Remove per-event/per-edge retained objects.
- Tune SQLite and queues.
- Verify Bazel 6–9 fixtures.
- Test incomplete and corrupt sessions.
- Perform privacy review.
- Perform archive/parser security review.
- Document known version limitations.
- Write user guide and troubleshooting guide.

### Exit criteria

- Tier 3 raw capture succeeds without data loss.
- Tier 3 indexed session can be reopened and queried.
- Aggregate timeline and graph remain usable.
- Every limit is explicit.
- Bazel compatibility matrix is published.
- No known routine path blocks the EDT.
- Release candidate packages launch on supported macOS architectures.

---

# 25. Definition of done for v1

The product is complete when all of the following are true:

## Invocation

- User can select a workspace and executable.
- User can enter and run a Bazel command.
- Effective argv and environment policy are visible.
- Added flags and auxiliary commands are explained.
- Cancellation works.
- Console output is retained.

## Capture

- Embedded loopback BES works.
- Binary-file fallback works.
- Binary and JSON BEP import works.
- Raw data is preserved.
- Interrupted sessions recover.
- Missing sequences and partial captures are disclosed.

## Analysis

- Actions, attempts, targets, configurations, artifacts, tests, and events are represented separately.
- Detailed timings appear when source data exists.
- Input/output counts and sizes include coverage.
- Direct dependencies and reverse dependencies work.
- Critical path, concurrency, cache, and runner analysis work.
- Findings include evidence and caveats.

## Visualization

- Overview, action table, timeline, trees, graph, tests, failures, events, console, and capture views exist.
- Selection synchronizes across views.
- Huge views aggregate rather than freeze.
- Every display limit is visible and configurable.
- Any captured action can be searched and inspected.

## Persistence

- Managed sessions reopen.
- Portable sessions export/import.
- Binary BEP export works.
- Redacted export works.
- Schema migrations are tested.

## Performance

- Tier 3 capture completes without silent loss.
- The UI remains responsive during capture and indexing.
- The application does not construct an object-per-edge whole graph.
- Common indexed queries meet documented targets or clearly show progress and cancellation.

## Quality

- Bazel 6, 7, 8, and 9 fixtures pass.
- Unit, integration, UI, and recovery tests pass.
- Security checks pass.
- Packaging works on Apple Silicon and Intel macOS.
- Known limitations are documented.

---

# 26. Agent execution contract

The coding agent must follow these rules:

1. Implement phases in order.
2. Do not attempt the full UI before raw capture and storage are reliable.
3. Maintain `docs/implementation-status.md` with every phase and task.
4. Add or update an ADR before changing a fixed architectural decision.
5. Run scoped Bazel targets while iterating, then `bazel build //...` and
   `bazel test //... --test_tag_filters=-bazel-sweep` after a meaningful code
   or build change. Never run the `bazel-sweep` suite casually.
6. Add tests with every parser, metric, migration, and graph algorithm.
7. Preserve raw source data before deriving normalized data.
8. Never block the Swing EDT with I/O or computation.
9. Never retain all events as Java objects.
10. Never use an ORM.
11. Never represent unavailable values as zero.
12. Never silently truncate, sample, drop, or override.
13. Never claim a graph is complete unless its source and correlation checks support that claim.
14. Never claim a derived finding is definitively causal without explicit evidence.
15. Never execute commands embedded in imported files.
16. Prefer bounded-memory streaming algorithms.
17. Introduce third-party dependencies only with:
    - License review
    - Maintenance assessment
    - Performance justification
    - Dependency locking
18. When Bazel behavior is uncertain, create a real Bazel fixture before encoding the assumption.
19. Keep UI components dependent on service interfaces, not SQLite implementation classes.
20. Update performance results and compatibility documentation continuously.

---

# 27. Required documentation

Create and maintain:

```text
docs/
├── architecture.md
├── implementation-status.md
├── capture-sources.md
├── instrumentation-planner.md
├── bazel-compatibility.md
├── session-format.md
├── database-schema.md
├── graph-model.md
├── metric-definitions.md
├── performance.md
├── privacy.md
├── troubleshooting.md
└── adr/
    ├── 001-swing.md
    ├── 002-java-21.md
    ├── 003-gradle.md
    ├── 004-raw-first-storage.md
    ├── 005-per-session-sqlite.md
    ├── 006-custom-graph-index.md
    └── 007-transparent-instrumentation.md
```

Metric documentation must state:

- Definition
- Units
- Source
- Formula
- Completeness requirements
- Known caveats

---

# 28. Deferred roadmap

After v1:

- Compare two or more sessions.
- Match equivalent actions across builds.
- Highlight action-duration, cache, key, and input-size regressions.
- Forward local BES events to an existing enterprise BES.
- Connect to remote historical BES storage.
- Build annotations and team-shared reports.
- Plugin API for custom metrics.
- IDE integration.
- Automated performance regression detection.
- Optional Skia renderer if Java2D benchmarks justify it.
- Remote artifact inspection with explicit credentials.
- Headless report-generation CLI.

These features must not delay the local single-user v1.

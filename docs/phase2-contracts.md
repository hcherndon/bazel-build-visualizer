# Phase 2 shared contracts

Binding source: `docs/product-plan.md` sections 4.2–4.3, 7.1, 8.1–8.7, 9.1–9.5,
10.2–10.3, 22.1–22.3, 24 (Phase 2).

**These contracts are frozen and already exist in code.** Read the source, not
just this document — the code is authoritative and this page explains it:

| Contract | Implemented in |
|---|---|
| Structured command model | `runner` `runner.command.BazelCommand` |
| Environment inheritance | `runner` `runner.command.EnvironmentInheritance` |
| Executable identity | `runner` `runner.exec.BazelExecutable` |
| Workspace detection result | `runner` `runner.workspace.WorkspaceInfo` |
| Capability vocabulary | `runner` `runner.caps.Capability`, `CapabilityStatus`, `FlagSpec`, `BazelCapabilities` |
| Capture presets | `runner` `runner.plan.CapturePreset` |
| Instrumentation plan | `runner` `runner.plan.InstrumentationPlan` and the `plan` package |
| Launch and cancellation | `runner` `runner.launch.LaunchRequest`; `runner.proc.CancellationMode`, `ConsoleSink`, `ProcessOutcome` |
| BES endpoint and stream identity | `capture-bes` `capture.bes.BesEndpoint`, `BesStreamKey` |
| BES stream state | `capture-bes` `capture.bes.BesStreamState` |
| Raw handoff | `capture-bes` `capture.bes.RawEventSink`, `RawBesEvent` |
| BES envelope decoding | `bep-codec` `bepcodec.BesEnvelopeDecoder`, `BesEnvelope` |
| Journal source kinds | `core-model` `core.journal.JournalFormat.SourceKind` |
| Session file names | `session-format` `format.session.ManagedSessionLayout` |

## 1. What gets journaled, and as what

Phase 1 froze the frame layout. Phase 2 adds one source kind and pins the exact
meaning of the two BES kinds. Both hold **the serialized request as it arrived on
the wire**, obtained through a raw gRPC marshaller — never by re-serializing a
parsed message, because a protobuf round-trip is not guaranteed to reproduce its
input and ADR-004 promises the bytes back unchanged.

| `SourceKind` | Ordinal | Payload |
|---|---:|---|
| `BES_ENVELOPE` | 0 | `google.devtools.build.v1.PublishBuildToolEventStreamRequest` |
| `BEP_BINARY` | 1 | `build_event_stream.BuildEvent` (unchanged) |
| `BEP_JSON_RECORD` | 2 | one JSON record (unchanged) |
| `BES_LIFECYCLE` | 3 | `google.devtools.build.v1.PublishLifecycleEventRequest` |

Frame header fields for a BES frame:

- `sequence` is the BES sequence number from `OrderedBuildEvent`, not an import
  ordinal. Bazel numbers a stream from 1, consecutively.
- `streamOrdinal` is the index of the stream within this session, assigned in
  the order streams open. A build has more than one stream when an invocation is
  retried.
- `receiveMicros` is when *this process* received it. It is deliberately a
  different measurement from the envelope's `event_time`, which is Bazel's clock
  (plan 11.5); neither substitutes for the other.

### Not every envelope becomes a row

`bep_events` holds BEP events. A lifecycle transition, a `ConsoleOutput`
envelope and the terminating `component_stream_finished` are all accepted BES
traffic with no BEP payload: they are journaled, they move stream state, and
they produce **no** `bep_events` row. `EventNormalizer.normalizeBesEnvelope`
returns empty for them.

A row with `event_type = 0` means "a build event arrived and could not be
decoded". Using it for "this was never a build event" would make the two
indistinguishable afterwards.

Journal frames and `bep_events` rows are therefore not 1:1 for a live capture.
Resume logic must reconcile on `(stream_id, sequence)`, which is the journal's
own identity for a frame, and never on a running count of frames.

## 2. Stream identity and the database

`BesStreamKey` is `(buildId, invocationId, component)` and its `storageKey()` is
what goes in `event_streams.stream_key`, whose `UNIQUE` constraint does the
deduplication. Keying on fewer fields merges streams that are genuinely
separate, and two streams' events then collide at the same sequence number.

`event_streams` columns map to `BesStreamState` directly: `first_sequence`,
`last_sequence`, `contiguous_through`, `duplicate_count`, `gap_count`, `state`.

**No schema migration is required for Phase 2.** Schema v1 already carries every
column live capture needs. Console output goes to files, not tables, and the
effective command goes to the manifest.

## 3. Acknowledgement rules

These are the rules that make "no accepted event is silently dropped" checkable
rather than asserted:

1. An event is acknowledged **only after** its frame has been appended to the
   journal channel (plan 9.3 balanced durability). Not after it is queued, and
   not after it is decoded.
2. `highestAcknowledged <= highestContiguous <= highestReceived`, enforced in
   `BesStreamState`'s constructor. Acknowledging past a gap tells Bazel we hold
   data we do not.
3. A duplicate sequence is acknowledged again and normalized **once**. Bazel
   retransmits; the `UNIQUE (stream_id, sequence)` constraint and the tracker's
   duplicate counter both have to agree that this is normal.
4. Out-of-order events are buffered up to a bounded count. Past that bound the
   pipeline applies backpressure rather than growing.
5. `RawEventSink.submit` either accepts the event or throws. There is no boolean
   return that a caller could ignore, and there is no queue that discards.

## 4. Threading

```text
 gRPC callback thread (per stream)
   -> RawEventSink.submit            bounded, blocking
   -> journal writer thread          one per session, owns JournalWriter
   -> onJournaled -> ack             back on the stream observer
   -> decode + normalize             CPU pool
   -> SQLite writer thread           single writer, batched transactions
   -> aggregate + throttled UI notify
```

Rules that are not negotiable:

- The gRPC callback does no database access, no Swing access, and no deep
  protobuf traversal (plan 9.3). It measures the bytes, wraps them, submits.
- `JournalWriter` is not thread-safe by design and has exactly one writer
  thread. Do not add a lock to it; add a thread boundary in front of it.
- Never interrupt the journal-writer thread to cancel. `FileChannel` closes
  itself on interruption, so an interrupt truncates the capture. Stop feeding
  the writer, then close it.
- Automatic gRPC flow control is disabled; the server requests the next message
  only after the previous one has been submitted. That is what turns a blocking
  sink into backpressure instead of a stalled read.

## 5. Capability detection

Two probes, in order, per resolved executable:

1. `bazel help flags-as-proto` — base64 `bazel_flags.FlagCollection`. Exact,
   per-command, and available on every version this project targets (verified on
   6.5.0, 7.6.1, 8.4.1 and 9.2.0). This is `DetectionMethod.FLAGS_PROTO`.
2. `bazel help <command> --long` text scan, when the first fails. Weaker: it can
   tell that a flag exists but not whether it takes a value, so `FlagSpec`
   leaves `requiresValue` absent rather than guessing. This is
   `DetectionMethod.HELP_TEXT`.

If both fail, the result is `BazelCapabilities.unprobed(reason)`: every
capability `UNKNOWN`, nothing injected, and a reason the user can read. An empty
flag table must never be reported as a successful probe — that reads as "your
Bazel supports nothing".

Cache key: resolved executable path, its version output, and the startup options
in effect. Startup options select a different server, and a different server can
have different flags.

**Never gate behavior on a version number.** Versions are recorded for display
and diagnostics only (`docs/bazel-compatibility.md`).

## 6. Instrumentation planner

Inputs and outputs are plan 7.1's lists, expressed as `InstrumentationPlan`.
The rules that constrain the implementation:

- Flags are placed as `COMMAND` options. The planner never adds a startup
  option: changing one restarts the Bazel server and discards its analysis
  cache, which is a cost the user did not ask for.
- User-supplied options win. Overriding one requires a `ReplacedFlag`, and that
  record cannot be constructed without the id of the `PlanConflict.Resolution`
  the user chose. The type makes a silent override impossible.
- Every generated path is session-local and absolute.
- No shell quoting, ever, in direct-argv mode. Each flag is one argv element.
- A capability whose status is not `SUPPORTED` produces an `AddedFlag` that is
  displayed and explained but not applied. `plan.canLaunch()` is the single
  gate that enforces "do not launch until mandatory conflicts are resolved".

### The Phase 2 flag catalog

Phase 2 implements the mechanism and these entries. Later phases add catalog
entries; they do not change the mechanism.

| Capability | Flag | Overhead | Writes a file | User can disable |
|---|---|---|---|---|
| `BES_BACKEND` | `--bes_backend=grpc://127.0.0.1:<port>` | low | no | no — without it there is no live capture |
| `PUBLISH_ALL_ACTIONS` | `--build_event_publish_all_actions` | high | no | yes |
| `BEP_BINARY_FILE` | `--build_event_binary_file=<session>/raw/…` | medium | yes | yes |

`--build_event_binary_file` is injected only as the fallback for a BES conflict
(plan 8.5 option 2), never alongside a working embedded backend: two copies of
the same stream double the write cost and prove nothing.

### Conflicts

The upstream-BES case (plan 8.5) offers exactly three resolutions, and the
constants for them are on `PlanConflict`:

- `RESOLUTION_REPLACE_BES` — replace the user's backend with the local one.
- `RESOLUTION_KEEP_BES_USE_FILE` — keep theirs, capture through a local binary
  BEP file instead.
- `RESOLUTION_CANCEL` — cancel and edit the command.

No forwarding relay in v1. The selected resolution is recorded in the manifest.

## 7. Cancellation

`CancellationMode` is a ladder: `CANCEL` (SIGINT) → `TERMINATE` (SIGTERM) →
`FORCE_KILL` (SIGKILL, plus descendants). Each has a grace period; escalation is
explicit, never automatic past what the user asked for.

Whatever the mode, plan 8.7's "always" list applies: keep draining output
briefly, finalize the raw data already captured, mark the invocation cancelled,
and permit enrichment only when its source files are valid.

A pre-existing Bazel server is a **sibling**, not a descendant. Force-kill reaps
the process tree this launch created; killing a server started by the user's
other terminal would discard analysis state that is not ours to discard.

## 8. Session states

```text
NEW -> PREFLIGHT -> CAPTURING -> BUILD_FINISHED -> INDEXING -> READY
                        |
                        +-> CANCELLED      user stopped it
                        +-> INCOMPLETE     process died, or a journal write failed
                        +-> CORRUPT_PARTIAL
```

`PREFLIGHT` covers detection, capability probing and planning — everything
before a process exists. The transition to `CAPTURING` happens after the BES
server is listening and before `ProcessBuilder.start()`, so that a session
exists to hold whatever the first event turns out to be.

A cancelled build must leave an inspectable session: that is a Phase 2 exit
criterion, and it means finalizing the journal, flushing the batch writer and
writing the manifest on the cancellation path, not only on the happy path.

## 9. Security constraints that are enforced in types

- `BesEndpoint` cannot be constructed for a non-loopback host (plan 22.1).
- `BazelCommand.toArgv()` emits argv elements; nothing is shell-quoted, and
  shell mode is a separate explicit flag (plan 22.3).
- Imported session commands are display-only and are never executed (plan 22.3,
  rule 15). Nothing in `runner` reads a command out of a session.

## 10. What Phase 2 does not do

Stated so that a reviewer does not read absence as oversight:

- No profile, execution-log, `aquery` or `cquery` capture. The presets name
  them, the planner reports them as not-yet-implemented, and Phases 4–5 add the
  catalog entries.
- No normalization beyond Phase 1's: lifecycle, event metadata and raw
  provenance. Targets, actions and tests are Phase 3.
- No BES forwarding relay, in v1 at all (plan 8.5).
- No non-loopback binding, and no developer-only switch for it yet.

### Known limits, found by the Phase 2 audit

Each of these is a place where the implementation is honest about doing less
than the ideal, rather than a gap nobody noticed. `docs/phase2-audit.md` is the
full record.

- **rc-file detection covers `common` and `build`, not every section.** The
  planner reads Bazel's own `--announce_rc` output, which reports the sections
  `canonicalize-flags` inherits. An option set only under a command-specific
  section — a `test`-only `--bes_backend` — is not visible, and the plan says
  so rather than claiming to have checked. Closing it needs either a full rc
  parser or an announcement obtained for the user's actual command.
- **`--json` reports the plan's flags but not its warnings.** A scripted
  consumer sees `injectedFlags` and the capture counters; the unapplied flags
  and the plan's notes are on stderr for a human. A machine-readable plan
  belongs with the Phase 7 planner UI.
- **The veto is in the dialog, not in the CLI.** Unticking a flag re-plans and
  reopens; `bbv run` has no equivalent switch, so a headless caller takes the
  preset as it is.
- **Objective 1 is not met**: the corrected result is about 79,400 events/sec
  against 100,000, end to end without loss. A transport-only sink measured in
  the same range, so storage was not observed as the bottleneck at that scale;
  the cause of the remaining gap has not been established. No acknowledgement
  change is proposed without a separate experiment: a wrong sequence number
  kills the user's Bazel server on 6.5 and 9.2, and no experiment has
  established that Bazel accepts one acknowledgement covering several events.
  See `docs/performance.md`.
- **Everything was measured on macOS arm64.** Linux and Windows behaviour —
  particularly signal handling and the `flags-as-proto` line format — is
  unverified.

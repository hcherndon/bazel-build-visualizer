# Phase 1 shared contracts

Binding source: `docs/product-plan.md` sections 5.2, 9.3 (journal writer), 9.5
(file-tail / truncation), 10.1–10.3, 10.7–10.9, 11.1, 11.4–11.5, 21.1–21.3, 24 (Phase 1).

**These contracts are frozen and already exist in code.** Read the source, not
just this document — the code is authoritative and this page explains it:

| Contract | Implemented in |
|---|---|
| Journal frame + segment layout | `core-model` `com.holtherndon.bazelviz.core.journal.JournalFormat` |
| Source completeness | `core-model` `com.holtherndon.bazelviz.core.source.Completeness` |
| Data provenance | `core-model` `com.holtherndon.bazelviz.core.source.DataSource` |
| SQLite schema v1 DDL + indexes | `storage-sqlite` `com.holtherndon.bazelviz.storage.schema.SchemaV1` |

`JournalFormatTest` pins the byte layout deliberately: a change that breaks it
orphans every journal an earlier build wrote, so a failure there means bumping
`FORMAT_VERSION` and writing a reader for the old layout, not editing the
expected values.

## 1. Raw journal segment format

One append-only file per segment under `raw/`, named `bes-%06d.journal`.
All integers little-endian. The journal is the recovery source of truth
(ADR-004), so every frame is independently checksummed and the reader can
always find the last intact frame.

### Segment header (32 bytes, written once at creation)

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | magic `BBVJRNL\x01` |
| 8 | 4 | format version (= 1) |
| 12 | 4 | segment index (0-based) |
| 16 | 8 | session UUID high bits |
| 24 | 8 | session UUID low bits |

### Frame (27-byte header + payload + 4-byte CRC)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | frame magic `BFRM` — lets recovery confirm a boundary |
| 4 | 4 | payload length (bounded by `DEFAULT_MAX_PAYLOAD_BYTES`, reject larger) |
| 8 | 1 | source kind ordinal: 0 = BES `PublishBuildToolEventStreamRequest`, 1 = BEP `BuildEvent` (binary), 2 = imported JSON record, 3 = BES `PublishLifecycleEventRequest` (added in Phase 2) |
| 9 | 2 | stream ordinal (index into the session's streams) |
| 11 | 8 | sequence number (BES sequence, or import ordinal for files) |
| 19 | 8 | receive timestamp, epoch micros |
| 27 | n | payload bytes, verbatim and unmodified |
| 27+n | 4 | CRC-32C over bytes `[0, 27+n)` |

Rules:
- Payload bytes are stored exactly as received. Never re-serialize.
- `DEFAULT_MAX_PAYLOAD_BYTES` is configurable, default 64 MiB; a larger declared length
  is corruption, not a big event (plan 21.3 "limit protobuf message size").
- Rotation at a configurable segment size (default 256 MiB), at a frame
  boundary only.
- Recovery scans forward from the last checkpoint; the first frame that fails
  magic, length-bound, or CRC ends the valid region. Trailing invalid bytes
  are truncated, and the event is recorded in `import_diagnostics`
  distinguishing TRUNCATED (clean short tail) from CORRUPT (bad CRC on a
  fully present frame) per plan 21.3.
- Durability default (plan 9.3): append to the channel, flush to the OS
  periodically, no per-event fsync; force on graceful finalize.

## 2. Checkpoint format

`checkpoints/import.ckpt`, written atomically (temp file + `ATOMIC_MOVE`).

```json
{
  "formatVersion": 1,
  "segmentIndex": 3,
  "segmentOffset": 12345678,
  "lastSequence": 987654,
  "framesWritten": 987655,
  "eventsNormalized": 987600,
  "updatedAtMicros": 1755800000000000
}
```

`eventsNormalized <= framesWritten` always; the gap is the normalization
backlog that recovery replays. Recovery resumes normalization from the raw
journal at the recorded position rather than re-reading the source file.

## 3. Manifest

`manifest.json` at the session root, fields per plan 10.3. Written on create
and rewritten on each state transition; atomic replace. Must include format
version, app version, session UUID, timestamps, state, command/workspace info
(null for pure imports), capture sources with sha256 and byte size,
per-source completeness, counts, schema version, index versions, warnings,
and whether absolute paths / environment values are present.

## 4. SQLite schema v1

Applied by an explicit migration runner; `schema_metadata` carries the
version. Explicit SQL only, no ORM (ADR-006).

```sql
CREATE TABLE schema_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE session_info (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  session_uuid TEXT NOT NULL,
  state TEXT NOT NULL,
  created_micros INTEGER NOT NULL,
  finalized_micros INTEGER,          -- NULL = not finalized, never 0
  app_version TEXT NOT NULL
);

CREATE TABLE capture_sources (
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL,                -- BEP_BINARY, BEP_JSON, BES_STREAM, STDOUT, ...
  path TEXT,
  sha256 TEXT,
  byte_size INTEGER,                 -- NULL when unknown, never 0
  completeness TEXT NOT NULL,        -- COMPLETE, TRUNCATED, CORRUPT_PARTIAL, UNKNOWN
  note TEXT
);

CREATE TABLE event_streams (
  id INTEGER PRIMARY KEY,
  stream_key TEXT NOT NULL UNIQUE,
  invocation_id TEXT,
  build_id TEXT,
  first_sequence INTEGER,
  last_sequence INTEGER,
  contiguous_through INTEGER,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  gap_count INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL
);

CREATE TABLE import_diagnostics (
  id INTEGER PRIMARY KEY,
  severity TEXT NOT NULL,            -- INFO, WARNING, ERROR
  code TEXT NOT NULL,                -- TRUNCATED_TAIL, CRC_MISMATCH, DECODE_FAILED, ...
  message TEXT NOT NULL,
  segment_index INTEGER,
  byte_offset INTEGER,
  at_micros INTEGER NOT NULL
);

CREATE TABLE strings (id INTEGER PRIMARY KEY, value TEXT NOT NULL UNIQUE);

-- One row per accepted raw event.
CREATE TABLE bep_events (
  id INTEGER PRIMARY KEY,            -- normalization order
  stream_id INTEGER NOT NULL REFERENCES event_streams(id),
  sequence INTEGER NOT NULL,
  event_type INTEGER NOT NULL,       -- payload-case number; 0 = none/unknown
  event_id_hash INTEGER,             -- NULL when the event carries no id
  last_message INTEGER NOT NULL DEFAULT 0,
  child_count INTEGER NOT NULL DEFAULT 0,
  raw_segment INTEGER NOT NULL,
  raw_offset INTEGER NOT NULL,
  raw_length INTEGER NOT NULL,
  decode_status TEXT NOT NULL,       -- OK, UNKNOWN_FIELDS, FAILED
  has_unknown_fields INTEGER NOT NULL DEFAULT 0,
  event_micros INTEGER,              -- NULL when the event carries no timestamp
  receive_micros INTEGER NOT NULL,
  UNIQUE (stream_id, sequence)       -- makes duplicate delivery idempotent
);

-- Canonical event identities. The hash is of the serialized BuildEventId;
-- id_bytes is retained so a hash collision can be resolved exactly.
CREATE TABLE bep_event_ids (
  event_id_hash INTEGER PRIMARY KEY,
  id_kind INTEGER NOT NULL,
  id_bytes BLOB NOT NULL,
  display TEXT NOT NULL
);

CREATE TABLE bep_event_edges (
  parent_event_id INTEGER NOT NULL REFERENCES bep_events(id),
  child_event_id_hash INTEGER NOT NULL,
  ordinal INTEGER NOT NULL,
  PRIMARY KEY (parent_event_id, ordinal)
);

-- A child announced by a parent that never arrived (plan 17.11).
CREATE TABLE bep_announced_missing (
  child_event_id_hash INTEGER PRIMARY KEY,
  announced_by_event_id INTEGER NOT NULL REFERENCES bep_events(id)
);
```

Indexes: `bep_events(sequence)`, `bep_events(event_type, sequence)`,
`bep_events(event_id_hash)`, `bep_event_edges(child_event_id_hash)`.

Unknown-is-not-zero: every column that can be unavailable is nullable and is
left NULL, never defaulted to 0 (plan 11.4). That is why `byte_size`,
`event_micros`, `finalized_micros` and `event_id_hash` are nullable.

## 5. Event-ID canonicalization

`BuildEventId` is a oneof over ~28 nested id messages. Canonical key is a
128-bit hash of the serialized `BuildEventId` bytes, stored as the low 64 bits
in `event_id_hash` with the full `id_bytes` retained for exact comparison.
Announced children are matched by the same key, so parent/child linking works
without decoding every id variant, and stays correct when a future Bazel adds
a variant this build does not know.

## 6. Format detection (by content, never extension — plan 5.2)

- **Binary BEP**: a varint length prefix followed by that many bytes that
  parse as a `BuildEvent`. Validate the first frame before committing.
- **JSON BEP**: first non-whitespace byte is `{`. Bazel writes
  protobuf-JSON objects; handle both one-object-per-line and pretty-printed
  concatenated objects.
- **Managed session directory**: contains `manifest.json`.
- Ambiguity is reported, not guessed.

## 7. Streaming requirement

No parser may read a whole file into memory (Phase 1 exit criterion). Binary
parsing reads incrementally through a bounded buffer; JSON parsing streams
record by record with a configurable per-record size cap (plan 9.5). Tests
must assert this against a file larger than the test JVM heap would tolerate,
or by instrumenting peak buffer allocation.

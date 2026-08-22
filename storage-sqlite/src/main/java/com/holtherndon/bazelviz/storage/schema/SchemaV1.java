package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Session database schema version 1 (plan section 10.7), covering what Phase 1
 * needs: raw BEP events, their identities and announced-child edges, the
 * streams they arrived on, the sources they came from, and import diagnostics.
 * Targets, actions, artifacts and tests arrive with Phase 3.
 *
 * <p>Two rules shape every column here.
 *
 * <p><b>Unknown is never zero</b> (plan 11.4). Any value that can be
 * unavailable is nullable and is left NULL. That is why {@code byte_size},
 * {@code event_micros}, {@code finalized_micros} and {@code event_id_hash} are
 * nullable — a missing timestamp must not read back as the epoch, and an
 * unknown size must not read back as an empty file.
 *
 * <p><b>Duplicate delivery is idempotent</b> (plan 9.2). BES may retransmit a
 * sequence, and recovery replays the journal from the last checkpoint, so the
 * same event can legitimately be normalized twice. {@code UNIQUE (stream_id,
 * sequence)} makes the second write a no-op rather than a duplicate row.
 *
 * <p>Indexes are deliberately separate from the tables: the Phase 0 SQLite
 * spike showed bulk loading before index creation is substantially faster, so
 * ingestion creates tables, loads, then calls {@link #INDEXES} as an explicit
 * finalize step.
 */
public final class SchemaV1 {

    private SchemaV1() {}

    public static final int VERSION = 1;

    /** Key in {@code schema_metadata} holding the applied schema version. */
    public static final String VERSION_KEY = "schema_version";

    public static final List<String> TABLES = List.of(
            """
            CREATE TABLE schema_metadata (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE session_info (
              singleton        INTEGER PRIMARY KEY CHECK (singleton = 1),
              session_uuid     TEXT    NOT NULL,
              state            TEXT    NOT NULL,
              created_micros   INTEGER NOT NULL,
              finalized_micros INTEGER,
              app_version      TEXT    NOT NULL
            )
            """,
            """
            CREATE TABLE capture_sources (
              id           INTEGER PRIMARY KEY,
              kind         TEXT NOT NULL,
              path         TEXT,
              sha256       TEXT,
              byte_size    INTEGER,
              completeness TEXT NOT NULL,
              note         TEXT
            )
            """,
            """
            CREATE TABLE event_streams (
              id                 INTEGER PRIMARY KEY,
              stream_key         TEXT NOT NULL UNIQUE,
              invocation_id      TEXT,
              build_id           TEXT,
              first_sequence     INTEGER,
              last_sequence      INTEGER,
              contiguous_through INTEGER,
              duplicate_count    INTEGER NOT NULL DEFAULT 0,
              gap_count          INTEGER NOT NULL DEFAULT 0,
              state              TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE import_diagnostics (
              id            INTEGER PRIMARY KEY,
              severity      TEXT NOT NULL,
              code          TEXT NOT NULL,
              message       TEXT NOT NULL,
              segment_index INTEGER,
              byte_offset   INTEGER,
              at_micros     INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE strings (
              id    INTEGER PRIMARY KEY,
              value TEXT NOT NULL UNIQUE
            )
            """,
            """
            CREATE TABLE bep_events (
              id                 INTEGER PRIMARY KEY,
              stream_id          INTEGER NOT NULL REFERENCES event_streams(id),
              sequence           INTEGER NOT NULL,
              event_type         INTEGER NOT NULL,
              event_id_hash      INTEGER,
              last_message       INTEGER NOT NULL DEFAULT 0,
              child_count        INTEGER NOT NULL DEFAULT 0,
              raw_segment        INTEGER NOT NULL,
              raw_offset         INTEGER NOT NULL,
              raw_length         INTEGER NOT NULL,
              decode_status      TEXT    NOT NULL,
              has_unknown_fields INTEGER NOT NULL DEFAULT 0,
              event_micros       INTEGER,
              receive_micros     INTEGER NOT NULL,
              UNIQUE (stream_id, sequence)
            )
            """,
            """
            CREATE TABLE bep_event_ids (
              event_id_hash INTEGER PRIMARY KEY,
              id_kind       INTEGER NOT NULL,
              id_bytes      BLOB    NOT NULL,
              display       TEXT    NOT NULL
            )
            """,
            """
            CREATE TABLE bep_event_edges (
              parent_event_id     INTEGER NOT NULL REFERENCES bep_events(id),
              child_event_id_hash INTEGER NOT NULL,
              ordinal             INTEGER NOT NULL,
              PRIMARY KEY (parent_event_id, ordinal)
            )
            """,
            """
            CREATE TABLE bep_announced_missing (
              child_event_id_hash   INTEGER PRIMARY KEY,
              announced_by_event_id INTEGER NOT NULL REFERENCES bep_events(id)
            )
            """);

    /** Applied after bulk load, not before — see the class comment. */
    public static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_bep_events_sequence ON bep_events(sequence)",
            "CREATE INDEX IF NOT EXISTS idx_bep_events_type_sequence ON bep_events(event_type, sequence)",
            "CREATE INDEX IF NOT EXISTS idx_bep_events_id_hash ON bep_events(event_id_hash)",
            "CREATE INDEX IF NOT EXISTS idx_bep_event_edges_child ON bep_event_edges(child_event_id_hash)");
}

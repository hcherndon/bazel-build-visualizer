package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Schema version 4: execution-log attempts and the JSON trace profile.
 *
 * <h2>What Phase 4 adds, and what it deliberately does not</h2>
 *
 * <p>Version 2 built the entity layer out of one source, the BEP. This version
 * adds two more, and the governing rule is ADR-009: where two sources measure
 * the same thing, both values survive under names that say whose they are.
 * Nothing here overwrites a column version 2 wrote.
 *
 * <p>Every non-obvious choice below cites a finding id from
 * {@code docs/exec-log-and-profile.md}, which is measurement against real Bazel
 * 6.5.0, 7.6.1, 8.4.1 and 9.2.0 rather than documentation.
 *
 * <p>There is no {@code action_coverage} table. How many actions have attempt
 * data is a count over {@code action_attempts}, and a stored copy would be one
 * more number that can disagree with the rows it summarises — which is exactly
 * the defect the Phase 3 audit found in {@code saw_last_message}.
 */
final class SchemaV4 {

    private SchemaV4() {}

    public static final int VERSION = 4;

    public static final List<String> STATEMENTS = List.of(

            // --- enrichment tasks ------------------------------------------------
            //
            // Plan 21.4: each enrichment task is independent and a failed one
            // must not invalidate the BEP. That is a storage requirement before
            // it is a UI one -- the failure has to be recorded somewhere that
            // survives, with enough detail for the panel the plan specifies:
            // task, source, exit status, error excerpt, retriability, and the
            // metrics that are unavailable as a result.
            //
            // unavailable_metrics is a JSON array rather than a join table
            // because it is written once, read whole, and never queried across
            // tasks.
            """
            CREATE TABLE enrichment_tasks (
              id                   INTEGER PRIMARY KEY,
              kind                 TEXT    NOT NULL,
              source_path          TEXT,
              source_id            INTEGER REFERENCES capture_sources(id),
              state                TEXT    NOT NULL,
              started_micros       INTEGER,
              finished_micros      INTEGER,
              exit_status          TEXT,
              error_excerpt        TEXT,
              retriable            INTEGER NOT NULL DEFAULT 0,
              unavailable_metrics  TEXT,
              records_read         INTEGER,
              resume_offset        INTEGER
            )
            """,
            "CREATE UNIQUE INDEX ux_enrichment_tasks_kind ON enrichment_tasks (kind)",

            // --- execution-log input sets ----------------------------------------
            //
            // ExecLogEntry.InputSet is a DAG with transitive_set_ids, the same
            // shape as the BEP's NamedSetOfFiles (S5). Plan 10.7's rule is
            // unchanged: store the edges, never flatten into duplicated
            // per-attempt input rows. A million-action build's flattened inputs
            // do not fit and would not be read whole anyway.
            //
            // log_id is the id the log itself used. It is unique per task, not
            // globally, hence the composite key -- and per the proto, entries
            // other than Invocation and Spawn "must not be assumed to be
            // canonical: they may be serialized multiple times with different
            // ids", so this table can legitimately hold two rows describing the
            // same set.
            """
            CREATE TABLE input_sets (
              id       INTEGER PRIMARY KEY,
              task_id  INTEGER NOT NULL REFERENCES enrichment_tasks(id),
              log_id   INTEGER NOT NULL,
              UNIQUE (task_id, log_id)
            )
            """,
            """
            CREATE TABLE input_set_children (
              parent_id INTEGER NOT NULL REFERENCES input_sets(id),
              child_id  INTEGER NOT NULL REFERENCES input_sets(id),
              PRIMARY KEY (parent_id, child_id)
            )
            """,
            """
            CREATE TABLE input_set_files (
              input_set_id INTEGER NOT NULL REFERENCES input_sets(id),
              artifact_id  INTEGER NOT NULL REFERENCES artifacts(id),
              PRIMARY KEY (input_set_id, artifact_id)
            )
            """,

            // --- attempts ---------------------------------------------------------
            //
            // One row per spawn. "Attempt" rather than "action" because the
            // execution log's unit is an execution, and one action can produce
            // several -- a test produces exactly two on every measured version,
            // the second being XML generation which succeeds even when the test
            // failed (K3).
            //
            // action_id is NULLABLE and that is the point. The execution log
            // covers a strict subset of BEP actions -- 4 spawns against 13
            // actionCompleted events, because the rest never spawn a
            // subprocess (K1) -- and test spawns correlate to no action at all
            // on any version (K2). A NOT NULL action_id would force either
            // dropping those rows or inventing an action for them. correlation
            // records which of those cases this is, so "unmatched" is a stated
            // fact rather than a null nobody explains.
            //
            // Timing, and why there are so many columns for it: SpawnMetrics is
            // the detailed timing breakdown the plan's UI deliverable asks for,
            // and collapsing it into one duration would discard exactly what
            // makes it worth importing. Each is nullable because each is
            // genuinely absent on some version or some runner.
            //
            // start_micros is nullable and often null on purpose: Bazel 6.5.0
            // NEVER emits start_time, on any flag setting (S2). A 6.5.0 attempt
            // has a length and no position. start_unknown_reason carries why,
            // so the inspector can say "this version does not report it"
            // instead of leaving a blank (plan 11.4).
            //
            // total_micros is Bazel's total_time. It is NOT the same as
            // execution_wall_micros, which excludes queue, setup, upload and
            // fetch; both are kept because the difference is the answer to
            // "where did the time go".
            //
            // runner is a free string, not an enum: spawn.proto documents
            // "remote", "linux-sandbox", "worker", "disk cache hit" and
            // "remote cache hit", says it varies with the dynamic strategy, and
            // nothing constrains it. An enum would turn an unrecognised runner
            // into either a crash or an "OTHER" that loses the name.
            //
            // digest_* is the action cache digest, available only when remote
            // execution, remote cache or disk cache was enabled. It is a
            // sensitive diagnostic identifier per plan 20.4 and is redacted in
            // exports rather than omitted here.
            """
            CREATE TABLE action_attempts (
              id                        INTEGER PRIMARY KEY,
              task_id                   INTEGER NOT NULL REFERENCES enrichment_tasks(id),
              log_entry_index           INTEGER NOT NULL,
              action_id                 INTEGER REFERENCES actions(id),
              correlation               TEXT    NOT NULL,
              correlation_note          TEXT,
              label_id                  INTEGER REFERENCES labels(id),
              mnemonic_id               INTEGER REFERENCES mnemonics(id),
              runner                    TEXT,
              cache_hit                 INTEGER,
              exit_code                 INTEGER,
              status                    TEXT,
              start_micros              INTEGER,
              start_unknown_reason      TEXT,
              total_micros              INTEGER,
              execution_wall_micros     INTEGER,
              parse_micros              INTEGER,
              network_micros            INTEGER,
              fetch_micros              INTEGER,
              queue_micros              INTEGER,
              setup_micros              INTEGER,
              upload_micros             INTEGER,
              process_outputs_micros    INTEGER,
              retry_micros              INTEGER,
              input_bytes               INTEGER,
              input_files               INTEGER,
              memory_estimate_bytes     INTEGER,
              measured_memory_peak_bytes INTEGER,
              timeout_millis            INTEGER,
              remotable                 INTEGER,
              cacheable                 INTEGER,
              remote_cacheable          INTEGER,
              digest_hash               TEXT,
              digest_size_bytes         INTEGER,
              digest_function           TEXT,
              input_set_id              INTEGER REFERENCES input_sets(id),
              tool_set_id               INTEGER REFERENCES input_sets(id),
              UNIQUE (task_id, log_entry_index)
            )
            """,
            "CREATE INDEX ix_action_attempts_action ON action_attempts (action_id)",
            "CREATE INDEX ix_action_attempts_label ON action_attempts (label_id)",
            "CREATE INDEX ix_action_attempts_correlation ON action_attempts (correlation)",

            // --- attempt outputs --------------------------------------------------
            //
            // kind distinguishes File, Directory and UnresolvedSymlink, which
            // the compact log's output_id can each reference and which all
            // declare path = 1 (S5). A resolver that assumes File loses every
            // tree-artifact output, and on 8.4.1+ that is the ONLY resolvable
            // output a test spawn has.
            //
            // produced = 0 is the invalid_output_path case: an output the spawn
            // was allowed to produce and did not. Those are kept rather than
            // skipped because on 7.6.1 a failing test's entire output list is
            // invalid entries, and a row that showed nothing there would be
            // indistinguishable from a spawn that declared no outputs.
            """
            CREATE TABLE attempt_outputs (
              id          INTEGER PRIMARY KEY,
              attempt_id  INTEGER NOT NULL REFERENCES action_attempts(id),
              artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
              kind        TEXT    NOT NULL,
              produced    INTEGER NOT NULL,
              digest_hash TEXT,
              size_bytes  INTEGER
            )
            """,
            "CREATE INDEX ix_attempt_outputs_attempt ON attempt_outputs (attempt_id)",
            "CREATE INDEX ix_attempt_outputs_artifact ON attempt_outputs (artifact_id)",

            // --- attempt environment ----------------------------------------------
            //
            // Kept because the attempt inspector's job is to answer "why did
            // this run differently from that one", and the environment is
            // usually the answer. Values are redacted on the way in when the
            // name matches the secret-name patterns of plan 22.2; redacted = 1
            // records that a value was withheld rather than absent, because a
            // variable that is present and empty and one that was hidden are
            // different facts.
            """
            CREATE TABLE attempt_env_vars (
              attempt_id INTEGER NOT NULL REFERENCES action_attempts(id),
              name       TEXT    NOT NULL,
              value      TEXT,
              redacted   INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (attempt_id, name)
            )
            """,

            // --- profile: the anchor ----------------------------------------------
            //
            // The single most dangerous value in Phase 4. On 6.5.0 and 7.6.1 the
            // profile's absolute anchor is called profile_finish_ts and holds
            // the START, floored to the whole second; on 8.4.1+ it is called
            // profile_start_ts and is exact to the millisecond (P1). Read by its
            // name on the older pair, every span lands about one build-length
            // too late.
            //
            // So the anchor is stored with its meaning and its uncertainty
            // beside it, and no reader is allowed to infer either from the
            // version. uncertainty_micros is 1,000,000 where the value was
            // floored, and every UI that draws a profile span against an
            // execution-log start must show that it cannot resolve the two
            // within that window.
            //
            // build_id is the provenance check: it equalled the BEP's
            // started.uuid on all four versions, 4 of 4 (V1).
            """
            CREATE TABLE profile_metadata (
              id                  INTEGER PRIMARY KEY CHECK (id = 1),
              task_id             INTEGER NOT NULL REFERENCES enrichment_tasks(id),
              build_id            TEXT,
              build_id_matches    INTEGER,
              bazel_version       TEXT,
              output_base         TEXT,
              anchor_micros       INTEGER,
              anchor_source_key   TEXT,
              anchor_meaning      TEXT    NOT NULL,
              uncertainty_micros  INTEGER NOT NULL DEFAULT 0,
              trace_min_micros    INTEGER,
              trace_max_micros    INTEGER
            )
            """,

            // --- profile: threads --------------------------------------------------
            //
            // Thread names arrive as ph:"M" metadata events, 109 of each on
            // 9.2.0 (P6). Without them a timeline labels its rows with bare
            // integers.
            """
            CREATE TABLE profile_threads (
              id         INTEGER PRIMARY KEY,
              thread_id  INTEGER NOT NULL UNIQUE,
              name       TEXT,
              sort_index INTEGER
            )
            """,

            // --- profile: build phases ---------------------------------------------
            //
            // The phase set is NOT stable: 6.5.0 emits seven markers, 7.6.1+
            // five, with three of 6.5.0's merged into one (P2). So phases are
            // rows read from the file, never a constant in code, and a phase
            // overview renders what it finds.
            //
            // Markers are instant events with no duration except "Launch
            // Blaze". end_micros is therefore derived -- the next marker's
            // start -- and end_is_derived records that, so nobody later reads a
            // computed boundary as a measured one. The last phase has no next
            // marker and its end is null.
            //
            // start_micros is relative to the anchor and CAN BE NEGATIVE:
            // ts = 0 is "Initialize command" and Launch Blaze begins at
            // -17,000 to -20,000 us (P3).
            """
            CREATE TABLE build_phases (
              id             INTEGER PRIMARY KEY,
              ordinal        INTEGER NOT NULL,
              name           TEXT    NOT NULL,
              start_micros   INTEGER NOT NULL,
              end_micros     INTEGER,
              end_is_derived INTEGER NOT NULL DEFAULT 1
            )
            """,
            "CREATE UNIQUE INDEX ux_build_phases_ordinal ON build_phases (ordinal)",

            // --- profile: spans ----------------------------------------------------
            //
            // Only spans worth keeping: the plan says "normalize build phases
            // and selected spans", not every event. A 5M-action build's full
            // profile is far larger than its BEP and most of it is Skyframe
            // bookkeeping nobody asked about.
            //
            // primary_output is the join key, and it is the same key the BEP
            // uses for action identity (A1 in docs/bep-content.md). It is
            // present on 6.5.0 too, but ONLY on "action processing" events --
            // the sibling "complete action execution" category carries no out
            // and no args on any version and cannot be attributed to anything
            // (P4). Both flags that produce it default to false.
            //
            // action_id is resolved at write time where primary_output matches
            // an action, and left null otherwise rather than guessed.
            """
            CREATE TABLE profile_spans (
              id             INTEGER PRIMARY KEY,
              category       TEXT    NOT NULL,
              name           TEXT    NOT NULL,
              thread_id      INTEGER,
              start_micros   INTEGER NOT NULL,
              duration_micros INTEGER,
              primary_output TEXT,
              action_id      INTEGER REFERENCES actions(id),
              label_id       INTEGER REFERENCES labels(id),
              mnemonic_id    INTEGER REFERENCES mnemonics(id)
            )
            """,
            "CREATE INDEX ix_profile_spans_action ON profile_spans (action_id)",
            "CREATE INDEX ix_profile_spans_start ON profile_spans (start_micros)",
            "CREATE INDEX ix_profile_spans_category ON profile_spans (category, start_micros)",

            // --- profile: counters --------------------------------------------------
            //
            // Ten named series on 9.2.0: action count, CPU and memory for Bazel
            // and for the machine, network up and down, system load average,
            // worker memory (P6). One row per sample per series.
            """
            CREATE TABLE profile_counters (
              id           INTEGER PRIMARY KEY,
              series       TEXT    NOT NULL,
              at_micros    INTEGER NOT NULL,
              value        REAL    NOT NULL
            )
            """,
            "CREATE INDEX ix_profile_counters_series ON profile_counters (series, at_micros)",

            // --- profile: Bazel's own critical path -----------------------------------
            //
            // Deliberately NOT joined to actions. Its only identifier is a
            // human-readable progress message wrapped in action '...', with no
            // label, no output path and no mnemonic (P5). Parsing that string
            // would attach a number to an action on the strength of a
            // presentation string Bazel is free to reword, and ADR-009 requires
            // Bazel's critical path to survive as Bazel's regardless.
            //
            // So it is stored as written and displayed as Bazel's answer. The
            // visualizer's own dependency critical path is a Phase 6
            // computation over the action graph and gets its own table then.
            """
            CREATE TABLE bazel_critical_path (
              id              INTEGER PRIMARY KEY,
              ordinal         INTEGER NOT NULL,
              description     TEXT    NOT NULL,
              start_micros    INTEGER,
              duration_micros INTEGER,
              thread_id       INTEGER
            )
            """,
            "CREATE UNIQUE INDEX ux_bazel_critical_path_ordinal ON bazel_critical_path (ordinal)");
}

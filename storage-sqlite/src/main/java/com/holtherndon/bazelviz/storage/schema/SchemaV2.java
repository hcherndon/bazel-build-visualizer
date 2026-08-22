package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Session database schema version 2 (plan 10.7): configurations, targets,
 * actions, artifacts, file sets, tests and build-level metrics — what Phase 3
 * normalizes out of the raw events schema v1 stores.
 *
 * <p>Every shape here is traceable to a measurement against real Bazel 6.5.0,
 * 7.6.1, 8.4.1 and 9.2.0. {@code docs/bep-content.md} is the record, and its
 * numbered findings ({@code A1}, {@code T4}, {@code F2}, …) are cited where they
 * decided a column, because a constraint whose reason is not written down is a
 * constraint the next person will relax.
 *
 * <h2>Absence has three meanings and they are not interchangeable</h2>
 *
 * <p>Bazel emits protobuf-JSON, which omits a field at its default. So an
 * absent {@code success} means <em>false</em>; an absent {@code startTime} on
 * Bazel 7 means <em>this version cannot report it</em>; an absent action event
 * means <em>the flag that publishes it was not passed</em>. All three look
 * identical in the JSON and could not be more different in a database.
 *
 * <p>Columns whose absence is a proto3 default are NOT NULL — the parser
 * supplies the default, because the value is known. Columns whose absence means
 * the observation does not exist are nullable and stay NULL, never 0 (plan
 * 11.4). Which of the two a column is, is stated where it is declared. What
 * disambiguates the second and third cases at read time is
 * {@code build_invocation}: the Bazel version and the capture flags. Without
 * that row every NULL here is unreadable.
 *
 * <h2>Identity comes from event ids, never from payload content</h2>
 *
 * <p>An action is its {@code id.actionCompleted.primaryOutput} (A1, A2). A
 * configuration is its opaque id and nothing else — two configuration events
 * were observed with byte-identical payloads and different ids (C2). A named
 * set is {@code (invocation, id)}, because the ids are dense integers reshuffled
 * between runs of the same build (F2).
 *
 * <h2>Depsets are stored as a DAG, never flattened</h2>
 *
 * <p>A named set is a node with two edge kinds (F1, finding 26). Sets are shared
 * — in-degree up to 4 and depth up to 10 in a small probe workspace — so
 * materialising each set's transitive files per referencing target multiplies
 * rows by the sharing factor, which is largest on exactly the builds worth
 * measuring. It is also the only path to a target's outputs on Bazel 8 and 9,
 * where {@code importantOutput} is empty by default (T1).
 *
 * <h2>One invocation per session</h2>
 *
 * <p>These tables describe a single build. A session can hold more than one
 * event stream — BES splits lifecycle events from build-tool events, and a
 * fallback BEP file can re-deliver a build the live capture missed — but
 * exactly one build-tool stream is normalized, chosen by the capture
 * coordinator, and {@code build_invocation} records which. {@code stream_id}
 * still appears on {@code configurations} and {@code depsets} because their ids
 * are only meaningful inside one stream, and a global unique index there would
 * cross-link one build's outputs into another's the moment a second stream is
 * ingested (F2).
 */
public final class SchemaV2 {

    private SchemaV2() {}

    public static final int VERSION = 2;

    public static final List<String> TABLES = List.of(
            // --- dictionaries -------------------------------------------------
            //
            // Labels and mnemonics are interned; paths are not. A mnemonic
            // repeats across millions of action rows from a vocabulary of a few
            // dozen, and a label across the actions of one target. A path is
            // very nearly unique per artifact, so a `paths` table would be the
            // artifacts table with an extra join — plan 10.7 lists one table for
            // artifacts, and `artifacts` is it.
            //
            // Both hold free text. A label can be `@@repo//pkg:target` (A10), so
            // nothing here may assume a `//` prefix; the mnemonic vocabulary
            // grows between Bazel versions (A18), so it is never an enum.
            """
            CREATE TABLE labels (
              id    INTEGER PRIMARY KEY,
              value TEXT NOT NULL UNIQUE
            )
            """,
            """
            CREATE TABLE mnemonics (
              id    INTEGER PRIMARY KEY,
              value TEXT NOT NULL UNIQUE
            )
            """,

            // --- the invocation ------------------------------------------------
            //
            // Read this row before reading any NULL elsewhere. It carries the
            // Bazel version and the capture flags, which are what separate
            // "this version cannot report it" from "the flag was not passed"
            // (finding 1).
            //
            // Two durations, deliberately both stored and never averaged
            // (M7, finding 44). started/finished bracket what the user watched
            // in their terminal and match the `elapsed time` line in
            // buildToolLogs; wall_time_millis is Bazel's own internal span and
            // was measured disagreeing by up to a second. The UI shows the
            // first and labels the second as Bazel's.
            //
            // overall_success is nullable and its three states are all real:
            // 1 when BuildFinished said so, 0 when a BuildFinished arrived
            // without the flag (proto3 omits it on failure — the literal false
            // never appears), and NULL when no BuildFinished arrived at all,
            // which is a build that died before it could report.
            //
            // publishes_all_actions is nullable for the same reason: unknown
            // until the structured command line is read, and it is what lets the
            // actions view explain an empty table instead of implying that
            // nothing ran (A8, finding 22).
            """
            CREATE TABLE build_invocation (
              singleton             INTEGER PRIMARY KEY CHECK (singleton = 1),
              stream_id             INTEGER NOT NULL REFERENCES event_streams(id),
              invocation_id         TEXT,
              build_tool_version    TEXT,
              command               TEXT,
              working_directory     TEXT,
              workspace_directory   TEXT,
              options_description   TEXT,
              publishes_all_actions INTEGER,
              server_pid            INTEGER,
              started_micros        INTEGER,
              finished_micros       INTEGER,
              exit_code_name        TEXT,
              exit_code             INTEGER,
              overall_success       INTEGER,
              saw_last_message      INTEGER NOT NULL DEFAULT 0
            )
            """,

            // --- configurations -----------------------------------------------
            //
            // Keyed on the opaque BEP id and nothing else (C2, finding 6). Two
            // configuration events in one stream were observed with
            // byte-identical payloads and different ids, so deduplicating on
            // (mnemonic, cpu, platform) merges configurations that are genuinely
            // distinct. The mnemonic is also not parseable: its exec form
            // changed spelling across the four measured versions (C1).
            //
            // Every payload column is nullable because the `none` configuration
            // is published with an entirely empty payload (C3). is_tool is NOT
            // NULL because an absent `isTool` is proto3's false — and it is the
            // only reliable exec-vs-target discriminator, never the mnemonic.
            //
            // `declared` is what makes the foreign keys elsewhere work. Actions
            // reference the configuration id `system`, which Bazel never
            // publishes a Configuration event for, on every build on every
            // version (C4). Rather than drop those actions to a foreign-key
            // violation, or give up integer joins for a loose text column, the
            // normalizer inserts a row for any referenced-but-undeclared id with
            // declared = 0. That invents no data: it records that the id was
            // referenced and nothing described it, which is exactly what
            // happened, and the UI shows it as the bare id.
            """
            CREATE TABLE configurations (
              id             INTEGER PRIMARY KEY,
              stream_id      INTEGER NOT NULL REFERENCES event_streams(id),
              bep_id         TEXT    NOT NULL,
              declared       INTEGER NOT NULL DEFAULT 0,
              mnemonic       TEXT,
              platform_name  TEXT,
              cpu            TEXT,
              is_tool        INTEGER NOT NULL DEFAULT 0,
              bep_event_id   INTEGER REFERENCES bep_events(id),
              UNIQUE (stream_id, bep_id)
            )
            """,
            """
            CREATE TABLE configuration_make_variables (
              configuration_id INTEGER NOT NULL REFERENCES configurations(id),
              name             TEXT    NOT NULL,
              value            TEXT    NOT NULL,
              PRIMARY KEY (configuration_id, name)
            )
            """,

            // --- targets -------------------------------------------------------
            //
            // Two tables, because the BEP describes two different things.
            //
            // A TargetConfiguredId carries a label and an aspect — no
            // configuration (T-matrix) — so a (label, configuration) key cannot
            // be populated at the moment a target is configured. And the row
            // must not wait for TargetComplete: in a build interrupted during
            // analysis, six targets were configured and zero completed (T4), so
            // waiting drops every target there is.
            //
            // So `targets` is what analysis said a label is, and
            // `configured_targets` is what building it in one configuration did.
            // That split is the plan's own distinction between the target graph
            // and the configured-target graph (plan 2.1); here it is also forced
            // by the wire format.
            //
            // target_kind and test_size are nullable: from 7.6.1 an
            // analysis-failed target emits no `configured` payload at all, only
            // an `aborted` riding the targetConfigured id (T5), and the row must
            // still exist so the failures view can name the target.
            //
            // aspect is NOT NULL with '' meaning "the target itself, not an
            // aspect on it". Nullable would look tidier and would break the
            // table: SQLite treats NULLs as distinct inside a UNIQUE, so
            // ON CONFLICT would never fire for a plain target and every repeat
            // delivery would insert another row. '' is unambiguous here because
            // an aspect name is never empty.
            //
            // outcome on this table is what the target-level events said —
            // CONFIGURED, or ABORTED when analysis never finished. What
            // happened when it was built lives on configured_targets. Two
            // columns spelled the same because they answer different questions;
            // neither is derived from the other.
            """
            CREATE TABLE targets (
              id           INTEGER PRIMARY KEY,
              label_id     INTEGER NOT NULL REFERENCES labels(id),
              aspect       TEXT    NOT NULL DEFAULT '',
              target_kind  TEXT,
              test_size    TEXT,
              outcome      TEXT    NOT NULL,
              bep_event_id INTEGER REFERENCES bep_events(id),
              UNIQUE (label_id, aspect)
            )
            """,
            // No `success` column: `outcome` already carries it, and two columns
            // encoding one fact is how they come to disagree. The parser sets
            // outcome from `success is true` (T3, finding 13) — an absent
            // success means failed, never "not yet finished", because the
            // literal false is dead code in the wire format.
            //
            // test_timeout_seconds is the effective timeout and the only place
            // it appears; it is what makes a TIMEOUT status interpretable
            // (finding 38). Present only under `bazel test` (Contradiction 5),
            // so its absence is not evidence that a target is not a test.
            """
            CREATE TABLE configured_targets (
              id                   INTEGER PRIMARY KEY,
              target_id            INTEGER NOT NULL REFERENCES targets(id),
              configuration_id     INTEGER NOT NULL REFERENCES configurations(id),
              outcome              TEXT    NOT NULL,
              test_timeout_seconds INTEGER,
              failure_category     TEXT,
              failure_message      TEXT,
              bep_event_id         INTEGER REFERENCES bep_events(id),
              UNIQUE (target_id, configuration_id)
            )
            """,
            // completed.tag and configured.tag are not the same list: from 7.6.1
            // Bazel appends synthetic tags — the test size, the timeout,
            // noflaky, nolocal — to the completion list (T7). Recording which
            // event supplied a tag is what stops the UI showing `small` as if
            // the user had written it in a BUILD file.
            """
            CREATE TABLE target_tags (
              target_id  INTEGER NOT NULL REFERENCES targets(id),
              tag        TEXT    NOT NULL,
              from_event TEXT    NOT NULL,
              PRIMARY KEY (target_id, tag, from_event)
            )
            """,

            // --- artifacts and file sets ---------------------------------------
            //
            // Identity is the exec-root-relative path: path_prefix joined with
            // name (F4). `name` alone collides — the same name appears under two
            // prefixes when a target is built for both the target and the exec
            // configuration. `uri` is display-only and deliberately not a key
            // (F5): it embeds an absolute output-base path that differs by
            // machine and whose execroot segment changed between versions.
            //
            // Almost everything is nullable because there is no single File
            // shape (finding 30). A source file has no pathPrefix; a
            // directoryOutput has no uri and no length; the File inside
            // `action.primaryOutput` carries only a uri; the Files in
            // `testSummary.passed` carry only a uri and no name. A normalizer
            // that required any one of these would drop rows.
            //
            // is_directory marks a tree artifact, whose digest is a tree digest
            // and is not comparable to a file digest (finding 31). Its size is
            // unknown, not zero, and roll-ups reach files through the depset
            // graph rather than by summing directory entries — Bazel reports a
            // tree twice, once as the directory and once as its expanded
            // children, and adding both double-counts.
            """
            CREATE TABLE artifacts (
              id             INTEGER PRIMARY KEY,
              path           TEXT    NOT NULL UNIQUE,
              name           TEXT,
              path_prefix    TEXT,
              digest         TEXT,
              size_bytes     INTEGER,
              uri            TEXT,
              is_directory   INTEGER NOT NULL DEFAULT 0,
              is_source      INTEGER NOT NULL DEFAULT 0
            )
            """,
            // Named sets are per-invocation: the ids are small dense decimal
            // strings that every build reuses, and they are NOT stable across
            // runs of the same build — a warm rerun reshuffles the id-to-content
            // mapping (F2). A globally unique index on the raw id would
            // cross-link one build's outputs into another's on the second
            // ingest. Cross-run identity, when Phase 10 needs it, comes from
            // content: path plus digest, never the id.
            """
            CREATE TABLE depsets (
              id           INTEGER PRIMARY KEY,
              stream_id    INTEGER NOT NULL REFERENCES event_streams(id),
              bep_id       TEXT    NOT NULL,
              bep_event_id INTEGER REFERENCES bep_events(id),
              UNIQUE (stream_id, bep_id)
            )
            """,
            // Both edge kinds can be non-empty on the same set — 10 of 19 sets
            // in one Starlark build had files and children (F1) — so neither
            // table may assume the other is empty.
            //
            // Children are always published before their parent: 1,829
            // references across 43 streams, zero forward references, on all four
            // versions and under --jobs=64, --keep_going failures, SIGINT and
            // SIGKILL (O1). The normalizer takes that as its fast path and
            // treats a forward reference as a surfaced warning rather than a
            // dropped row, because the guarantee was measured on the JSON file
            // transport and not on gRPC BES (Contradiction 4).
            """
            CREATE TABLE depset_children (
              parent_id INTEGER NOT NULL REFERENCES depsets(id),
              child_id  INTEGER NOT NULL REFERENCES depsets(id),
              ordinal   INTEGER NOT NULL,
              PRIMARY KEY (parent_id, ordinal)
            )
            """,
            """
            CREATE TABLE depset_files (
              depset_id   INTEGER NOT NULL REFERENCES depsets(id),
              artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
              ordinal     INTEGER NOT NULL,
              PRIMARY KEY (depset_id, ordinal)
            )
            """,
            // One row per output group, not one outputs pointer per target
            // (finding 15). Declared after `depsets` so its reference points at
            // a table that already exists: foreign keys are enforced
            // (SessionDatabase sets PRAGMA foreign_keys=ON) and SQLite resolves
            // the target by name at insert time, so a forward reference fails on
            // the first write rather than at CREATE.
            //
            // `incomplete` is persisted because a group marked incomplete means
            // the roll-up under it under-reports, and a total that silently
            // under-reports is worse than no total. One experiment never
            // observed the flag and another captured it twice on a failing
            // genrule under --keep_going (Contradiction 1); the column is
            // required either way, and a failed target may legitimately have
            // zero output groups.
            """
            CREATE TABLE target_output_groups (
              configured_target_id INTEGER NOT NULL REFERENCES configured_targets(id),
              name                 TEXT    NOT NULL,
              root_depset_id       INTEGER REFERENCES depsets(id),
              incomplete           INTEGER NOT NULL DEFAULT 0,
              ordinal              INTEGER NOT NULL,
              PRIMARY KEY (configured_target_id, ordinal)
            )
            """,
            // Tree artifacts a target declared, kept apart from the output
            // groups so that a byte roll-up traversing depsets cannot pick them
            // up and count a tree alongside the files it expands to.
            """
            CREATE TABLE target_directory_outputs (
              configured_target_id INTEGER NOT NULL REFERENCES configured_targets(id),
              artifact_id          INTEGER NOT NULL REFERENCES artifacts(id),
              PRIMARY KEY (configured_target_id, artifact_id)
            )
            """,

            // --- actions --------------------------------------------------------
            //
            // primary_output is the identity: measured unique across every
            // stream on all four versions, while (label, configuration) collides
            // heavily because one target legitimately emits many actions — a
            // single test target produced six (A1). It is read from the event
            // id, never from the payload: the payload's File is absent on every
            // failure, and on 9.2.0 absent from a successful RunfilesTree action
            // too, so its presence is not even a success proxy (A2, A3).
            //
            // UNIQUE, and the normalizer surfaces a collision as an anomaly
            // rather than upserting over it (finding 23) — a duplicate would
            // mean the identity assumption has broken, which the user needs to
            // be told rather than have quietly resolved.
            //
            // label_id is NULLABLE: the workspace-status action carries no label
            // on 6.5.0, 7.6.1 and 8.4.1 (A1). Absent, never empty — the
            // normalizer must not normalise one into the other.
            //
            // Two exit codes, because they are two different things (A6). Bazel
            // reported action.exitCode == 1 for every failure regardless of what
            // the process actually returned; the real code is
            // failureDetail.spawn.spawnExitCode, and that is what the UI shows.
            // failure_category is the failureDetail oneof key, never a regex
            // over the message — the wording changes between versions.
            //
            // start_micros/end_micros are nullable because Bazel 6.5.0 and 7.6.1
            // emit no action timestamps at all, and on 8.4.1/9.2.0 roughly a
            // third of action events carry none (A4). duration_unknown_reason
            // records why, including the measured 8.4.1 defect where endTime
            // equals startTime for 100% of actions — a five-second sleep
            // included (A5) — which must be read as unavailable, not as a
            // zero-length action. Duration aggregates exclude unknowns; they
            // never count them as zero.
            //
            // There is no action_outputs table, because the BEP does not name
            // an action's outputs: ActionExecuted carries a primary_output File
            // with a uri and nothing else, and no list. The primary output path
            // on this row is the whole of what the stream says. Outputs arrive
            // with the execution log in Phase 4.
            //
            // No is_test_runner column: the mnemonic is right there.
            //
            // A test execution appears both here, with mnemonic TestRunner, and
            // in the tests tables (TS7). The action row is a real action and is
            // counted as one; what must never happen is deriving a test's
            // result from it, or adding its duration into a "time spent
            // testing" figure alongside the test attempts that describe the
            // same work. Tests are keyed off testResult only — never by parsing
            // shard_N_of_M out of a path.
            """
            CREATE TABLE actions (
              id                      INTEGER PRIMARY KEY,
              primary_output          TEXT    NOT NULL UNIQUE,
              label_id                INTEGER REFERENCES labels(id),
              configuration_id        INTEGER REFERENCES configurations(id),
              mnemonic_id             INTEGER REFERENCES mnemonics(id),
              outcome                 TEXT    NOT NULL,
              bazel_exit_code         INTEGER,
              spawn_exit_code         INTEGER,
              failure_category        TEXT,
              failure_message         TEXT,
              start_micros            INTEGER,
              end_micros              INTEGER,
              duration_unknown_reason TEXT,
              command_line            TEXT,
              stdout_uri              TEXT,
              stderr_uri              TEXT,
              bep_event_id            INTEGER REFERENCES bep_events(id)
            )
            """,

            // --- tests -----------------------------------------------------------
            //
            // A test's verdict comes from testSummary.overallStatus and never
            // from the target's completion: targetCompleted.success was measured
            // TRUE for a test that failed (TS5), so deriving pass/fail from it
            // shows every failing test green.
            //
            // attempt_count is stored under its measured meaning — the maximum
            // attempts any (run, shard) needed — and never labelled "retries",
            // which it is not: it equals run_count for a healthy multi-run test
            // (TS1, finding 36). shard_count is nullable, absent meaning not
            // sharded; zero would put phantom zero-shard tests in a histogram.
            //
            // Three timing columns, and all three are Bazel's own. Schema v3
            // renames the first two to say so; they are declared here under
            // their original names because this file is what version 2 created
            // and a migration is not a licence to rewrite history.
            //
            // The summary's window excludes failed retries and its first start
            // landed 218-747 ms after the earliest attempt (TS2), so none of
            // these is the elapsed time of the test. That is computed from
            // `test_attempts` at read time (TestQueries), because the retries
            // appear nowhere else.
            //
            // total_num_cached is NOT NULL at 0 when absent — proto3 default,
            // a known value (TS3).
            """
            CREATE TABLE tests (
              id                            INTEGER PRIMARY KEY,
              configured_target_id          INTEGER NOT NULL
                                              REFERENCES configured_targets(id) UNIQUE,
              overall_status                TEXT    NOT NULL,
              total_run_count               INTEGER,
              run_count                     INTEGER,
              shard_count                   INTEGER,
              attempt_count                 INTEGER,
              total_num_cached              INTEGER NOT NULL DEFAULT 0,
              first_start_micros            INTEGER,
              last_stop_micros              INTEGER,
              bazel_reported_duration_micros INTEGER,
              bep_event_id                  INTEGER REFERENCES bep_events(id)
            )
            """,
            // (run, shard, attempt) are all present and 1-based on every
            // measured version, so the composite key needs no sentinel handling
            // (TS1). Dropping attempt would hide the failure that made a test
            // flaky.
            //
            // status is a different enum domain from tests.overall_status: FLAKY
            // exists only on the summary, never on an attempt (TS4). They do not
            // share a lookup table.
            //
            // cached_locally is NOT NULL because an absent cachedLocally is
            // proto3's false. A cached attempt legitimately replays timestamps
            // from before the build started — measured 2.1 s earlier (TS3) — so
            // a timeline must clamp or mark these rows, and a "time spent
            // testing" sum over them reports two seconds for a 58 ms build.
            //
            // exit_code is nullable and its NULL is genuinely two-valued: Bazel
            // 6.5.0 and 7.6.1 cannot report it, while on 8.4.1 and 9.2.0 absent
            // means zero. build_invocation.build_tool_version is what tells them
            // apart.
            """
            CREATE TABLE test_attempts (
              id              INTEGER PRIMARY KEY,
              test_id         INTEGER NOT NULL REFERENCES tests(id),
              run             INTEGER NOT NULL,
              shard           INTEGER NOT NULL,
              attempt         INTEGER NOT NULL,
              status          TEXT    NOT NULL,
              cached_locally  INTEGER NOT NULL DEFAULT 0,
              start_micros    INTEGER,
              duration_micros INTEGER,
              exit_code       INTEGER,
              strategy        TEXT,
              bep_event_id    INTEGER REFERENCES bep_events(id),
              UNIQUE (test_id, run, shard, attempt)
            )
            """,
            // Test logs, from both places they appear: per attempt (named
            // test.log / test.xml) and per summary (uri only, no name). One
            // table with a nullable attempt and a nullable name rather than two,
            // because they are the same thing recorded at two granularities.
            //
            // Keyed on (test, uri) rather than on the attempt, because the two
            // sources can name the same file: a summary's `passed` list points
            // at the winning attempt's log. Merging the two sightings into one
            // row that carries both the attempt and the summary status is the
            // truth; two rows would double the log count for every test.
            //
            // These are URIs into the output base, which the next build or a
            // `bazel clean` removes. Stored so the session can say where they
            // were; the content is not captured, and the inspector says so
            // rather than showing a dead link as though it worked.
            """
            CREATE TABLE test_logs (
              id              INTEGER PRIMARY KEY,
              test_id         INTEGER NOT NULL REFERENCES tests(id),
              test_attempt_id INTEGER REFERENCES test_attempts(id),
              name            TEXT,
              uri             TEXT    NOT NULL,
              summary_status  TEXT,
              UNIQUE (test_id, uri)
            )
            """,

            // --- build-level ------------------------------------------------------
            //
            // Every metric nullable, every single one. buildMetrics always
            // arrives — including on SIGINT — but its contents are version-gated
            // (M1): networkMetrics and the Skyframe counters are 8.4.1+,
            // criticalPathTime is 9.2.0 only, executionPhaseTimeInMs is absent
            // on 6.5.0, and on a loading or analysis failure targetMetrics and
            // packageMetrics arrive as empty objects (M6). A NOT NULL DEFAULT 0
            // would invent a number for every one of those, and the Overview
            // would show a confident zero where Bazel said nothing.
            //
            // actions_executed excludes cache hits and actions_created can be
            // smaller than actions_executed (M3, M5), so the two are presented
            // as separate numbers and never as a ratio.
            """
            CREATE TABLE build_metrics (
              singleton               INTEGER PRIMARY KEY CHECK (singleton = 1),
              actions_created         INTEGER,
              actions_executed        INTEGER,
              action_cache_hits       INTEGER,
              action_cache_misses     INTEGER,
              targets_configured      INTEGER,
              targets_loaded          INTEGER,
              packages_loaded         INTEGER,
              wall_time_millis        INTEGER,
              cpu_time_millis         INTEGER,
              analysis_phase_millis   INTEGER,
              execution_phase_millis  INTEGER,
              actions_start_millis    INTEGER,
              critical_path_micros    INTEGER,
              bep_event_id            INTEGER REFERENCES bep_events(id)
            )
            """,
            // Per-mnemonic work. actions_executed counts only executed actions,
            // and on 6.5.0/7.6.1 a mnemonic whose actions were all cache hits
            // vanishes from the breakdown entirely (M4) — so a "work by
            // mnemonic" chart is complete only on 8.4.1+, and the view says so
            // rather than implying the missing mnemonics did no work.
            """
            CREATE TABLE mnemonic_metrics (
              mnemonic_id      INTEGER NOT NULL PRIMARY KEY REFERENCES mnemonics(id),
              actions_created  INTEGER,
              actions_executed INTEGER
            )
            """,
            // How work was executed. Bazel appends a synthetic "total" row to
            // runnerCount; is_total marks it so it is not summed alongside the
            // real ones (X7).
            """
            CREATE TABLE runner_counts (
              id           INTEGER PRIMARY KEY,
              name         TEXT    NOT NULL,
              exec_kind    TEXT    NOT NULL DEFAULT '',
              action_count INTEGER,
              is_total     INTEGER NOT NULL DEFAULT 0,
              UNIQUE (name, exec_kind)
            )
            """,
            // Why the action cache missed. The first missDetails entry is a
            // defaulted enum-zero row with no reason and is skipped rather than
            // stored as a null-reason row; unknown future reason strings are
            // kept verbatim (X7).
            """
            CREATE TABLE cache_miss_details (
              reason TEXT    NOT NULL PRIMARY KEY,
              count  INTEGER NOT NULL
            )
            """,
            // garbageMetrics is the only memory figure reliably present. The
            // heap numbers require --memory_profile on all four versions (M2),
            // so no heap tile is shown by default and none is stored here.
            """
            CREATE TABLE garbage_metrics (
              type            TEXT    NOT NULL PRIMARY KEY,
              collected_bytes INTEGER NOT NULL
            )
            """,
            // Aborted events, which arrive AFTER buildFinished on all four
            // versions (O5) — an ingest that stops at buildFinished loses the
            // entire failed and skipped target list — and whose volume scales
            // with target count rather than failure count: one interrupt during
            // analysis produced 12,000 of them (X5). Their own table so the
            // failures view can summarize ("12,000 targets not built") without
            // loading them.
            //
            // The same aborted payload rides four different id types, so the
            // failed-target set is the union across all of them keyed on the
            // label inside the id (E2, finding 47): scanning only targetCompleted
            // finds nothing on 7.6.1+, and scanning only targetConfigured finds
            // nothing on 6.5.0.
            //
            // Unlike every other table here, identity is the event itself:
            // an abort has no natural key of its own -- the same label can
            // abort under several id kinds -- so a redelivered event would
            // otherwise insert a second row and inflate the count the failures
            // view exists to report. bep_event_id is NOT NULL for the same
            // reason: a row that could not name its source event would be a
            // silent duplicate waiting to happen.
            //
            // reason is nullable and maps to an explicit unknown, never
            // defaulted to INCOMPLETE — two experiments disagreed on whether a
            // skipped sibling carries one (Contradiction 2), and unrecognised
            // future values are stored verbatim rather than rejected.
            """
            CREATE TABLE aborted_events (
              id               INTEGER PRIMARY KEY,
              id_kind          TEXT    NOT NULL,
              label_id         INTEGER REFERENCES labels(id),
              configuration_id INTEGER REFERENCES configurations(id),
              reason           TEXT,
              description      TEXT,
              bep_event_id     INTEGER NOT NULL REFERENCES bep_events(id) UNIQUE
            )
            """,
            // An index into the raw progress events, not a copy of them. A
            // syntax error produces thirteen events and zero structured
            // diagnostics: the compiler's message exists only in
            // progress.stderr (X2), so the failures view has to reach it — but
            // the bytes already live in the journal, and copying them here would
            // duplicate the largest thing in the stream to no benefit (ADR-004,
            // rule 7). This records which progress events carry output and how
            // much, so the view can find them without scanning every event.
            """
            CREATE TABLE progress_output (
              bep_event_id  INTEGER NOT NULL PRIMARY KEY REFERENCES bep_events(id),
              ordinal       INTEGER NOT NULL,
              stdout_bytes  INTEGER NOT NULL DEFAULT 0,
              stderr_bytes  INTEGER NOT NULL DEFAULT 0
            )
            """);

    /**
     * Indexes, created after bulk load like schema v1's.
     *
     * <p>Chosen for the queries Phase 3's views actually make: the actions table
     * pages by id and sorts by start time, duration and mnemonic; the failures
     * view filters actions and targets by outcome and unions aborted events by
     * label; the targets tree walks label order; the inspector looks an entity
     * up by its natural key and walks the depset graph in both directions.
     */
    public static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_actions_start ON actions(start_micros)",
            // An index over the same expression the duration sort orders by.
            // Without it that sort is a full scan and a temporary b-tree per
            // page; with it SQLite reads the index in order. Measured at
            // 200,000 actions: 17.2 ms a page becomes 1.5 ms.
            "CREATE INDEX IF NOT EXISTS idx_actions_duration ON actions("
                    + "(CASE WHEN duration_unknown_reason IS NULL"
                    + " THEN end_micros - start_micros END))",
            "CREATE INDEX IF NOT EXISTS idx_actions_mnemonic ON actions(mnemonic_id)",
            "CREATE INDEX IF NOT EXISTS idx_actions_outcome ON actions(outcome)",
            "CREATE INDEX IF NOT EXISTS idx_actions_label ON actions(label_id)",
            "CREATE INDEX IF NOT EXISTS idx_configured_targets_target"
                    + " ON configured_targets(target_id)",
            "CREATE INDEX IF NOT EXISTS idx_configured_targets_outcome"
                    + " ON configured_targets(outcome)",
            "CREATE INDEX IF NOT EXISTS idx_targets_label ON targets(label_id)",
            "CREATE INDEX IF NOT EXISTS idx_target_tags_tag ON target_tags(tag)",
            "CREATE INDEX IF NOT EXISTS idx_depset_children_child ON depset_children(child_id)",
            "CREATE INDEX IF NOT EXISTS idx_depset_files_artifact ON depset_files(artifact_id)",
            "CREATE INDEX IF NOT EXISTS idx_test_attempts_test ON test_attempts(test_id)",
            "CREATE INDEX IF NOT EXISTS idx_test_attempts_status ON test_attempts(status)",
            "CREATE INDEX IF NOT EXISTS idx_test_logs_test ON test_logs(test_id)",
            "CREATE INDEX IF NOT EXISTS idx_aborted_label ON aborted_events(label_id)");
}

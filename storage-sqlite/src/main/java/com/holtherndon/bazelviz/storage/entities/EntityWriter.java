package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.core.domain.TargetOutcome;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.core.entity.FailureInfo;
import com.holtherndon.bazelviz.core.entity.FileRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Applies {@link EntityCommand}s to schema v2.
 *
 * <h2>Foreign keys are resolved in SQL, never in Java</h2>
 *
 * <p>Every statement here that needs a parent row's id finds it with a SELECT
 * inside the INSERT — the same technique {@code EventWriter} uses for announced
 * edges, and for the same three reasons. A writer that cached ids in Java would
 * need a map from every artifact path to its row id, which at five million
 * actions is hundreds of megabytes of strings the session already has on disk.
 * It would hand out an id for a row a conflicting insert never created. And it
 * would have to be rebuilt from scratch after a crash, from exactly the table it
 * was meant to avoid reading.
 *
 * <p>The cost is an index probe per reference. Every one of them rides a UNIQUE
 * constraint that the table definition already carries, so none of them waits
 * for the finalize indexes.
 *
 * <h2>Every write is idempotent</h2>
 *
 * <p>Re-delivery is normal rather than exceptional (plan 9.2): BES retransmits
 * sequences and crash recovery replays the journal, so the same event
 * legitimately reaches this class twice. Each statement therefore either does
 * nothing on conflict or merges what the second delivery adds, and the merges
 * are all {@code coalesce} — a later sighting can fill a hole but never
 * overwrite an observation with a blank.
 *
 * <p>The one exception is {@code actions}, where a conflict is not merged at
 * all. See {@link #conflictingActions()}.
 *
 * <h2>Referenced-but-undeclared parents are recorded, not dropped</h2>
 *
 * <p>Two kinds of parent are legitimately referenced before — or without ever
 * — being described. Configuration {@code system} is referenced on every build
 * on every measured version and never published as a Configuration event. And a
 * named set could in principle be referenced before its own event, though 1,829
 * measured references contained zero such cases.
 *
 * <p>Both get a placeholder row on first reference: a configuration with
 * {@code declared = 0}, a depset with a NULL {@code bep_event_id}. Nothing is
 * invented — the row records that the id was referenced and nothing described
 * it, which is exactly what happened — the foreign keys hold, and
 * {@link #undefinedDepsets(long)} can afterwards report any set that stayed
 * undescribed as the truncation evidence it is.
 *
 * <h2>Threading and transactions</h2>
 *
 * <p>Not thread-safe: it owns statements on the single writer connection. It
 * suspends auto-commit and commits every {@link #commitEvery} commands, because
 * the Phase 0 spike measured the commit, not the insert, as the expensive part.
 * All work here is blocking I/O and must never run on the Swing EDT (plan 19.1).
 *
 * <p>The caller must have written the {@code bep_events} row for a sequence
 * before applying that sequence's commands: every table's {@code bep_event_id}
 * is resolved by a lookup on {@code (stream_id, sequence)}, and that lookup is
 * what makes any normalized row traceable back to its raw bytes (ADR-004). A
 * command applied ahead of its event lands with a NULL there, and on
 * {@code aborted_events}, where the event <em>is</em> the identity, it fails
 * outright rather than duplicating.
 */
public final class EntityWriter implements AutoCloseable {

    /** Commands per transaction. */
    public static final int DEFAULT_COMMIT_EVERY = 5_000;

    /**
     * How many label and mnemonic strings to remember as already-inserted.
     *
     * <p>Purely a way to skip a no-op INSERT: a miss costs one redundant
     * statement, never a wrong answer, so the cache can be as small as memory
     * requires. Actions of one target arrive together, so even a small window
     * catches most repeats.
     */
    public static final int DEFAULT_DICTIONARY_CACHE = 65_536;

    private static final String EVENT_LOOKUP =
            "(SELECT e.id FROM bep_events e WHERE e.stream_id = ? AND e.sequence = ?)";

    private static final String INSERT_LABEL =
            "INSERT INTO labels (value) VALUES (?) ON CONFLICT (value) DO NOTHING";
    private static final String INSERT_MNEMONIC =
            "INSERT INTO mnemonics (value) VALUES (?) ON CONFLICT (value) DO NOTHING";

    // A later sighting may know more than the first -- a file reached through a
    // named set carries a digest and a length that the same path as a tree's
    // directory entry does not -- so holes are filled and observations are
    // never overwritten with blanks.
    private static final String INSERT_ARTIFACT =
            "INSERT INTO artifacts (path, name, path_prefix, digest, size_bytes, uri,"
                    + " is_directory, is_source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (path) DO UPDATE SET"
                    + " name = coalesce(artifacts.name, excluded.name),"
                    + " path_prefix = coalesce(artifacts.path_prefix, excluded.path_prefix),"
                    + " digest = coalesce(artifacts.digest, excluded.digest),"
                    + " size_bytes = coalesce(artifacts.size_bytes, excluded.size_bytes),"
                    + " uri = coalesce(artifacts.uri, excluded.uri),"
                    + " is_directory = max(artifacts.is_directory, excluded.is_directory)";

    private static final String INSERT_CONFIGURATION =
            "INSERT INTO configurations (stream_id, bep_id, declared, mnemonic, platform_name,"
                    + " cpu, is_tool, bep_event_id)"
                    + " VALUES (?, ?, 1, ?, ?, ?, ?, " + EVENT_LOOKUP + ")"
                    + " ON CONFLICT (stream_id, bep_id) DO UPDATE SET"
                    + " declared = 1,"
                    + " mnemonic = excluded.mnemonic,"
                    + " platform_name = excluded.platform_name,"
                    + " cpu = excluded.cpu,"
                    + " is_tool = excluded.is_tool,"
                    + " bep_event_id = coalesce(configurations.bep_event_id, excluded.bep_event_id)";

    private static final String REFERENCE_CONFIGURATION =
            "INSERT INTO configurations (stream_id, bep_id, declared) VALUES (?, ?, 0)"
                    + " ON CONFLICT (stream_id, bep_id) DO NOTHING";

    private static final String INSERT_MAKE_VARIABLE =
            "INSERT INTO configuration_make_variables (configuration_id, name, value)"
                    + " SELECT c.id, ?, ? FROM configurations c"
                    + " WHERE c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (configuration_id, name) DO UPDATE SET value = excluded.value";

    // outcome never goes backwards: an aborted target that a later event
    // touches must not be relabelled as merely configured, because the failures
    // view is built from exactly that column.
    private static final String INSERT_TARGET =
            "INSERT INTO targets (label_id, aspect, target_kind, test_size, outcome, bep_event_id)"
                    + " SELECT l.id, ?, ?, ?, ?, " + EVENT_LOOKUP
                    + " FROM labels l WHERE l.value = ?"
                    + " ON CONFLICT (label_id, aspect) DO UPDATE SET"
                    + " target_kind = coalesce(targets.target_kind, excluded.target_kind),"
                    + " test_size = coalesce(targets.test_size, excluded.test_size),"
                    + " outcome = CASE WHEN targets.outcome = 'ABORTED' THEN 'ABORTED'"
                    + " ELSE excluded.outcome END,"
                    + " bep_event_id = coalesce(targets.bep_event_id, excluded.bep_event_id)";

    private static final String TARGET_JOIN =
            " FROM targets t JOIN labels l ON l.id = t.label_id"
                    + " WHERE l.value = ? AND t.aspect = ?";

    private static final String INSERT_TARGET_TAG =
            "INSERT INTO target_tags (target_id, tag, from_event) SELECT t.id, ?, ?"
                    + TARGET_JOIN + " ON CONFLICT DO NOTHING";

    private static final String INSERT_CONFIGURED_TARGET =
            "INSERT INTO configured_targets (target_id, configuration_id, outcome,"
                    + " test_timeout_seconds, failure_category, failure_message, bep_event_id)"
                    + " SELECT t.id, c.id, ?, ?, ?, ?, " + EVENT_LOOKUP
                    + " FROM targets t JOIN labels l ON l.id = t.label_id, configurations c"
                    + " WHERE l.value = ? AND t.aspect = ? AND c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (target_id, configuration_id) DO UPDATE SET"
                    + " outcome = excluded.outcome,"
                    + " test_timeout_seconds = coalesce(excluded.test_timeout_seconds,"
                    + "   configured_targets.test_timeout_seconds),"
                    + " failure_category = coalesce(excluded.failure_category,"
                    + "   configured_targets.failure_category),"
                    + " failure_message = coalesce(excluded.failure_message,"
                    + "   configured_targets.failure_message),"
                    + " bep_event_id = coalesce(configured_targets.bep_event_id,"
                    + "   excluded.bep_event_id)";

    // Marks a configured target aborted, but only if nothing completed it.
    // Aborts arrive after buildFinished, so an unconditional update would let
    // a skipped sibling's abort overwrite a completion that really happened.
    private static final String MARK_CONFIGURED_TARGET_ABORTED =
            "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
                    + " SELECT t.id, c.id, 'ABORTED'"
                    + " FROM targets t JOIN labels l ON l.id = t.label_id, configurations c"
                    + " WHERE l.value = ? AND t.aspect = '' AND c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (target_id, configuration_id) DO UPDATE SET"
                    + " outcome = CASE WHEN configured_targets.outcome = 'CONFIGURED'"
                    + " THEN 'ABORTED' ELSE configured_targets.outcome END";

    // Creates the row without claiming an outcome, for the events that name a
    // configured target before its completion arrives. DO NOTHING, so it can
    // never downgrade a real outcome to this placeholder one.
    private static final String ENSURE_CONFIGURED_TARGET =
            "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
                    + " SELECT t.id, c.id, 'CONFIGURED'"
                    + " FROM targets t JOIN labels l ON l.id = t.label_id, configurations c"
                    + " WHERE l.value = ? AND t.aspect = ? AND c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (target_id, configuration_id) DO NOTHING";

    private static final String CONFIGURED_TARGET_JOIN =
            " FROM configured_targets ct"
                    + " JOIN targets t ON t.id = ct.target_id"
                    + " JOIN labels l ON l.id = t.label_id"
                    + " JOIN configurations c ON c.id = ct.configuration_id";

    private static final String CONFIGURED_TARGET_WHERE =
            " WHERE l.value = ? AND t.aspect = ? AND c.stream_id = ? AND c.bep_id = ?";

    private static final String INSERT_OUTPUT_GROUP =
            "INSERT INTO target_output_groups (configured_target_id, name, root_depset_id,"
                    + " incomplete, ordinal) SELECT ct.id, ?, d.id, ?, ?"
                    + CONFIGURED_TARGET_JOIN
                    + " LEFT JOIN depsets d ON d.stream_id = ? AND d.bep_id = ?"
                    + CONFIGURED_TARGET_WHERE
                    + " ON CONFLICT (configured_target_id, ordinal) DO UPDATE SET"
                    + " name = excluded.name,"
                    + " root_depset_id = excluded.root_depset_id,"
                    + " incomplete = excluded.incomplete";

    private static final String INSERT_DIRECTORY_OUTPUT =
            "INSERT INTO target_directory_outputs (configured_target_id, artifact_id)"
                    + " SELECT ct.id, a.id" + CONFIGURED_TARGET_JOIN + ", artifacts a"
                    + CONFIGURED_TARGET_WHERE + " AND a.path = ?"
                    + " ON CONFLICT DO NOTHING";

    private static final String INSERT_DEPSET =
            "INSERT INTO depsets (stream_id, bep_id, bep_event_id) VALUES (?, ?, " + EVENT_LOOKUP + ")"
                    + " ON CONFLICT (stream_id, bep_id) DO UPDATE SET"
                    + " bep_event_id = coalesce(depsets.bep_event_id, excluded.bep_event_id)";

    private static final String REFERENCE_DEPSET =
            "INSERT INTO depsets (stream_id, bep_id) VALUES (?, ?)"
                    + " ON CONFLICT (stream_id, bep_id) DO NOTHING";

    private static final String INSERT_DEPSET_CHILD =
            "INSERT INTO depset_children (parent_id, child_id, ordinal)"
                    + " SELECT p.id, c.id, ? FROM depsets p, depsets c"
                    + " WHERE p.stream_id = ? AND p.bep_id = ? AND c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (parent_id, ordinal) DO UPDATE SET child_id = excluded.child_id";

    private static final String INSERT_DEPSET_FILE =
            "INSERT INTO depset_files (depset_id, artifact_id, ordinal)"
                    + " SELECT d.id, a.id, ? FROM depsets d, artifacts a"
                    + " WHERE d.stream_id = ? AND d.bep_id = ? AND a.path = ?"
                    + " ON CONFLICT (depset_id, ordinal) DO UPDATE SET"
                    + " artifact_id = excluded.artifact_id";

    private static final String INSERT_ACTION =
            "INSERT INTO actions (primary_output, label_id, configuration_id, mnemonic_id, outcome,"
                    + " bazel_exit_code, spawn_exit_code, failure_category, failure_message,"
                    + " start_micros, end_micros, duration_unknown_reason, command_line,"
                    + " stdout_uri, stderr_uri, bep_event_id)"
                    + " SELECT ?, (SELECT id FROM labels WHERE value = ?), c.id,"
                    + " (SELECT id FROM mnemonics WHERE value = ?),"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + EVENT_LOOKUP
                    + " FROM configurations c WHERE c.stream_id = ? AND c.bep_id = ?"
                    + " ON CONFLICT (primary_output) DO NOTHING";

    private static final String INSERT_TEST =
            "INSERT INTO tests (configured_target_id, overall_status, total_run_count, run_count,"
                    + " shard_count, attempt_count, total_num_cached, bazel_first_start_micros,"
                    + " bazel_last_stop_micros, bazel_reported_duration_micros, bep_event_id)"
                    + " SELECT ct.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + EVENT_LOOKUP
                    + CONFIGURED_TARGET_JOIN + CONFIGURED_TARGET_WHERE
                    + " ON CONFLICT (configured_target_id) DO UPDATE SET"
                    + " overall_status = excluded.overall_status,"
                    + " total_run_count = coalesce(excluded.total_run_count, tests.total_run_count),"
                    + " run_count = coalesce(excluded.run_count, tests.run_count),"
                    + " shard_count = coalesce(excluded.shard_count, tests.shard_count),"
                    + " attempt_count = coalesce(excluded.attempt_count, tests.attempt_count),"
                    + " total_num_cached = excluded.total_num_cached,"
                    + " bazel_first_start_micros = coalesce(excluded.bazel_first_start_micros,"
                    + "   tests.bazel_first_start_micros),"
                    + " bazel_last_stop_micros = coalesce(excluded.bazel_last_stop_micros,"
                    + "   tests.bazel_last_stop_micros),"
                    + " bazel_reported_duration_micros = coalesce("
                    + "   excluded.bazel_reported_duration_micros,"
                    + "   tests.bazel_reported_duration_micros),"
                    + " bep_event_id = coalesce(tests.bep_event_id, excluded.bep_event_id)";

    // The row a test attempt needs before its summary has arrived. NO_STATUS is
    // Bazel's own name for "no verdict yet", so the placeholder says exactly
    // that rather than guessing at one.
    private static final String ENSURE_TEST =
            "INSERT INTO tests (configured_target_id, overall_status) SELECT ct.id, 'NO_STATUS'"
                    + CONFIGURED_TARGET_JOIN + CONFIGURED_TARGET_WHERE
                    + " ON CONFLICT (configured_target_id) DO NOTHING";

    /**
     * Records a test target that never ran.
     *
     * <p>Bazel emits no {@code testResult} and no {@code testSummary} for a
     * test whose own build failed (TS6), so without this such a target reaches
     * the tests view not at all — and a run where every test failed to build
     * showed "this session recorded no tests". Requirement 39 asks for the
     * explicit state, and {@code FAILED_TO_BUILD} is the one Bazel's own
     * vocabulary has for it.
     *
     * <p>Guarded on the target actually being a test: {@code testSize} comes
     * from the analysis payload under either command, and
     * {@code testTimeoutSeconds} from the completion under {@code bazel test}.
     * A non-test target that failed to build is a failed target and nothing
     * more.
     */
    private static final String MARK_TEST_FAILED_TO_BUILD =
            "INSERT INTO tests (configured_target_id, overall_status)"
                    + " SELECT ct.id, 'FAILED_TO_BUILD'" + CONFIGURED_TARGET_JOIN
                    + CONFIGURED_TARGET_WHERE
                    + " AND (t.test_size IS NOT NULL OR ct.test_timeout_seconds IS NOT NULL)"
                    + " ON CONFLICT (configured_target_id) DO NOTHING";

    private static final String TEST_JOIN =
            " FROM tests te JOIN configured_targets ct ON ct.id = te.configured_target_id"
                    + " JOIN targets t ON t.id = ct.target_id"
                    + " JOIN labels l ON l.id = t.label_id"
                    + " JOIN configurations c ON c.id = ct.configuration_id"
                    + " WHERE l.value = ? AND t.aspect = '' AND c.stream_id = ? AND c.bep_id = ?";

    private static final String INSERT_TEST_ATTEMPT =
            "INSERT INTO test_attempts (test_id, run, shard, attempt, status, cached_locally,"
                    + " start_micros, duration_micros, exit_code, strategy, bep_event_id)"
                    + " SELECT te.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + EVENT_LOOKUP + TEST_JOIN
                    + " ON CONFLICT (test_id, run, shard, attempt) DO UPDATE SET"
                    + " status = excluded.status,"
                    + " cached_locally = excluded.cached_locally,"
                    + " start_micros = coalesce(excluded.start_micros, test_attempts.start_micros),"
                    + " duration_micros = coalesce(excluded.duration_micros,"
                    + "   test_attempts.duration_micros),"
                    + " exit_code = coalesce(excluded.exit_code, test_attempts.exit_code),"
                    + " strategy = coalesce(excluded.strategy, test_attempts.strategy),"
                    + " bep_event_id = coalesce(test_attempts.bep_event_id, excluded.bep_event_id)";

    // Keyed on (test, uri) because a summary's `passed` list names the winning
    // attempt's log: the two sightings are one file, and the row ends up
    // knowing both which attempt produced it and what the summary called it.
    private static final String INSERT_TEST_LOG =
            "INSERT INTO test_logs (test_id, test_attempt_id, name, uri, summary_status)"
                    + " SELECT te.id, (SELECT ta.id FROM test_attempts ta WHERE ta.test_id = te.id"
                    + "   AND ta.run = ? AND ta.shard = ? AND ta.attempt = ?), ?, ?, ?" + TEST_JOIN
                    + " ON CONFLICT (test_id, uri) DO UPDATE SET"
                    + " test_attempt_id = coalesce(test_logs.test_attempt_id,"
                    + "   excluded.test_attempt_id),"
                    + " name = coalesce(test_logs.name, excluded.name),"
                    + " summary_status = coalesce(excluded.summary_status, test_logs.summary_status)";

    private static final String INSERT_INVOCATION =
            "INSERT INTO build_invocation (singleton, stream_id, invocation_id, build_tool_version,"
                    + " command, working_directory, workspace_directory, options_description,"
                    + " server_pid, started_micros) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (singleton) DO UPDATE SET"
                    + " invocation_id = coalesce(excluded.invocation_id,"
                    + "   build_invocation.invocation_id),"
                    + " build_tool_version = coalesce(excluded.build_tool_version,"
                    + "   build_invocation.build_tool_version),"
                    + " command = coalesce(excluded.command, build_invocation.command),"
                    + " working_directory = coalesce(excluded.working_directory,"
                    + "   build_invocation.working_directory),"
                    + " workspace_directory = coalesce(excluded.workspace_directory,"
                    + "   build_invocation.workspace_directory),"
                    + " options_description = coalesce(excluded.options_description,"
                    + "   build_invocation.options_description),"
                    + " server_pid = coalesce(excluded.server_pid, build_invocation.server_pid),"
                    + " started_micros = coalesce(excluded.started_micros,"
                    + "   build_invocation.started_micros)";

    private static final String UPDATE_INVOCATION_OPTIONS =
            "INSERT INTO build_invocation (singleton, stream_id, publishes_all_actions)"
                    + " VALUES (1, ?, ?)"
                    + " ON CONFLICT (singleton) DO UPDATE SET"
                    + " publishes_all_actions = excluded.publishes_all_actions";

    private static final String UPDATE_INVOCATION_FINISH =
            "INSERT INTO build_invocation (singleton, stream_id, exit_code_name, exit_code,"
                    + " overall_success, finished_micros) VALUES (1, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (singleton) DO UPDATE SET"
                    + " exit_code_name = excluded.exit_code_name,"
                    + " exit_code = excluded.exit_code,"
                    + " overall_success = excluded.overall_success,"
                    + " finished_micros = coalesce(excluded.finished_micros,"
                    + "   build_invocation.finished_micros)";

    private static final String MARK_LAST_MESSAGE =
            "INSERT INTO build_invocation (singleton, stream_id, saw_last_message)"
                    + " VALUES (1, ?, 1)"
                    + " ON CONFLICT (singleton) DO UPDATE SET saw_last_message = 1";

    private static final String INSERT_BUILD_METRICS =
            "INSERT INTO build_metrics (singleton, actions_created, actions_executed,"
                    + " action_cache_hits, action_cache_misses, targets_configured, targets_loaded,"
                    + " packages_loaded, wall_time_millis, cpu_time_millis, analysis_phase_millis,"
                    + " execution_phase_millis, actions_start_millis, critical_path_micros,"
                    + " bep_event_id) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + EVENT_LOOKUP + ")"
                    + " ON CONFLICT (singleton) DO UPDATE SET"
                    + " actions_created = excluded.actions_created,"
                    + " actions_executed = excluded.actions_executed,"
                    + " action_cache_hits = excluded.action_cache_hits,"
                    + " action_cache_misses = excluded.action_cache_misses,"
                    + " targets_configured = excluded.targets_configured,"
                    + " targets_loaded = excluded.targets_loaded,"
                    + " packages_loaded = excluded.packages_loaded,"
                    + " wall_time_millis = excluded.wall_time_millis,"
                    + " cpu_time_millis = excluded.cpu_time_millis,"
                    + " analysis_phase_millis = excluded.analysis_phase_millis,"
                    + " execution_phase_millis = excluded.execution_phase_millis,"
                    + " actions_start_millis = excluded.actions_start_millis,"
                    + " critical_path_micros = excluded.critical_path_micros,"
                    + " bep_event_id = excluded.bep_event_id";

    private static final String INSERT_MNEMONIC_METRIC =
            "INSERT INTO mnemonic_metrics (mnemonic_id, actions_created, actions_executed)"
                    + " SELECT m.id, ?, ? FROM mnemonics m WHERE m.value = ?"
                    + " ON CONFLICT (mnemonic_id) DO UPDATE SET"
                    + " actions_created = excluded.actions_created,"
                    + " actions_executed = excluded.actions_executed";

    private static final String INSERT_RUNNER_COUNT =
            "INSERT INTO runner_counts (name, exec_kind, action_count, is_total)"
                    + " VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT (name, exec_kind) DO UPDATE SET"
                    + " action_count = excluded.action_count, is_total = excluded.is_total";

    private static final String INSERT_CACHE_MISS =
            "INSERT INTO cache_miss_details (reason, count) VALUES (?, ?)"
                    + " ON CONFLICT (reason) DO UPDATE SET count = excluded.count";

    private static final String INSERT_GARBAGE =
            "INSERT INTO garbage_metrics (type, collected_bytes) VALUES (?, ?)"
                    + " ON CONFLICT (type) DO UPDATE SET collected_bytes = excluded.collected_bytes";

    private static final String INSERT_ABORTED =
            "INSERT INTO aborted_events (id_kind, label_id, configuration_id, reason, description,"
                    + " bep_event_id) SELECT ?, (SELECT id FROM labels WHERE value = ?),"
                    + " (SELECT id FROM configurations WHERE stream_id = ? AND bep_id = ?),"
                    + " ?, ?, " + EVENT_LOOKUP
                    // WHERE true is not decoration: without a WHERE clause
                    // SQLite cannot tell this ON CONFLICT from a join's ON.
                    + " WHERE true ON CONFLICT (bep_event_id) DO NOTHING";

    // Selected FROM bep_events rather than through a scalar subquery, which
    // matters more than it looks: bep_event_id is INTEGER NOT NULL PRIMARY KEY,
    // which makes it a rowid alias, and SQLite assigns a rowid when NULL is
    // inserted into one -- so an unresolved lookup would have attributed the
    // console output to an event id it made up. Selecting from the table
    // inserts nothing when the event is not there, which is a missing index
    // entry rather than a wrong one.
    private static final String INSERT_PROGRESS =
            "INSERT INTO progress_output (bep_event_id, ordinal, stdout_bytes, stderr_bytes)"
                    + " SELECT e.id, ?, ?, ? FROM bep_events e"
                    + " WHERE e.stream_id = ? AND e.sequence = ?"
                    + " ON CONFLICT (bep_event_id) DO NOTHING";

    private static final String COUNT_UNDEFINED_DEPSETS =
            "SELECT COUNT(*) FROM depsets WHERE stream_id = ? AND bep_event_id IS NULL";

    private final Connection connection;
    private final int commitEvery;
    private final boolean previousAutoCommit;

    private final Map<String, Boolean> knownLabels;
    private final Map<String, Boolean> knownMnemonics;

    private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();

    private int pending;
    private long applied;
    private long conflictingActions;
    private boolean closed;

    public EntityWriter(Connection connection) throws SQLException {
        this(connection, DEFAULT_COMMIT_EVERY, DEFAULT_DICTIONARY_CACHE);
    }

    public EntityWriter(Connection connection, int commitEvery, int dictionaryCache)
            throws SQLException {
        if (commitEvery < 1) {
            throw new IllegalArgumentException("commitEvery must be >= 1, got " + commitEvery);
        }
        if (dictionaryCache < 1) {
            throw new IllegalArgumentException(
                    "dictionaryCache must be >= 1, got " + dictionaryCache);
        }
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commitEvery = commitEvery;
        this.knownLabels = boundedCache(dictionaryCache);
        this.knownMnemonics = boundedCache(dictionaryCache);
        this.previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
    }

    private static Map<String, Boolean> boundedCache(int capacity) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Commands applied since construction. */
    public long appliedCommands() {
        return applied;
    }

    /**
     * Action events whose primary output was already recorded.
     *
     * <p>Expected to be non-zero only after a resumed import, where the journal
     * replays events the previous run already normalized. On a clean import a
     * non-zero count means two different actions reported the same primary
     * output — which the measurements say cannot happen, and which the user
     * therefore needs told rather than silently resolved by keeping whichever
     * row arrived first. The importer surfaces it as a diagnostic; nothing here
     * merges the two.
     */
    public long conflictingActions() {
        return conflictingActions;
    }

    /**
     * Named sets referenced by something but never defined by an event of their
     * own.
     *
     * <p>Zero in 1,829 measured references. A non-zero count is evidence of a
     * truncated capture rather than a normal state, and the byte totals under
     * those sets are lower bounds.
     */
    public long undefinedDepsets(long streamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_UNDEFINED_DEPSETS)) {
            statement.setLong(1, streamId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /**
     * Applies one command.
     *
     * @param streamId the stream the event arrived on
     * @param sequence the event's sequence, used to find its {@code bep_events}
     *     row so the resulting entities can be traced back to raw bytes
     */
    public void apply(long streamId, long sequence, EntityCommand command) throws SQLException {
        try {
            dispatch(streamId, sequence, command);
        } catch (UncheckedSqlException wrapped) {
            // A few steps run inside a lambda and cannot declare SQLException.
            // Unwrapping here keeps the checked type at this class's boundary,
            // so a caller that catches SQLException catches every failure --
            // rather than most of them, and taking an unchecked one through
            // the floor for the rest.
            throw (SQLException) wrapped.getCause();
        }
        applied++;
        if (++pending >= commitEvery) {
            flush();
        }
    }

    private void dispatch(long streamId, long sequence, EntityCommand command) throws SQLException {
        switch (command) {
            case EntityCommand.InvocationStarted started -> invocationStarted(streamId, started);
            case EntityCommand.InvocationOptions options -> invocationOptions(streamId, options);
            case EntityCommand.InvocationFinished finished -> invocationFinished(streamId, finished);
            case EntityCommand.ConfigurationDeclared configuration ->
                    configuration(streamId, sequence, configuration);
            case EntityCommand.TargetConfigured target -> targetConfigured(streamId, sequence, target);
            case EntityCommand.TargetCompleted target -> targetCompleted(streamId, sequence, target);
            case EntityCommand.DepsetDeclared depset -> depset(streamId, sequence, depset);
            case EntityCommand.ActionCompleted action -> action(streamId, sequence, action);
            case EntityCommand.TestAttemptCompleted attempt ->
                    testAttempt(streamId, sequence, attempt);
            case EntityCommand.TestSummarized summary -> testSummary(streamId, sequence, summary);
            case EntityCommand.BuildMetricsReported metrics -> metrics(streamId, sequence, metrics);
            case EntityCommand.TargetAborted aborted -> aborted(streamId, sequence, aborted);
            case EntityCommand.ProgressOutputSeen progress ->
                    progress(streamId, sequence, progress);
            case EntityCommand.StreamEnded ignored -> streamEnded(streamId);
        }
    }

    /**
     * Records that the stream reached its end marker.
     *
     * <p>Only ever sets the flag, never clears it. A session can be re-indexed,
     * resumed, or fed a fallback file after a live capture, and none of those
     * unsees an end marker that arrived.
     */
    private void streamEnded(long streamId) throws SQLException {
        PreparedStatement statement = prepare(MARK_LAST_MESSAGE);
        statement.setLong(1, streamId);
        statement.executeUpdate();
    }

    /** Commits everything applied since the last flush. */
    public void flush() throws SQLException {
        if (pending == 0) {
            return;
        }
        try {
            connection.commit();
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            pending = 0;
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            try {
                closeStatements();
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private void closeStatements() throws SQLException {
        SQLException first = null;
        for (PreparedStatement statement : statements.values()) {
            try {
                statement.close();
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        statements.clear();
        if (first != null) {
            throw first;
        }
    }

    // --- build level ------------------------------------------------------

    private void invocationStarted(long streamId, EntityCommand.InvocationStarted started)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_INVOCATION);
        statement.setLong(1, streamId);
        setText(statement, 2, started.uuid());
        setText(statement, 3, started.buildToolVersion());
        setText(statement, 4, started.command());
        setText(statement, 5, started.workingDirectory());
        setText(statement, 6, started.workspaceDirectory());
        setText(statement, 7, started.optionsDescription());
        // A pid of zero is not a process. Bazel omits the field rather than
        // sending one, so zero here means "not reported".
        setLong(statement, 8, started.serverPid() == 0
                ? OptionalLong.empty()
                : OptionalLong.of(started.serverPid()));
        setLong(statement, 9, started.startMicros());
        statement.executeUpdate();
    }

    private void invocationOptions(long streamId, EntityCommand.InvocationOptions options)
            throws SQLException {
        PreparedStatement statement = prepare(UPDATE_INVOCATION_OPTIONS);
        statement.setLong(1, streamId);
        statement.setInt(2, options.publishesAllActions() ? 1 : 0);
        statement.executeUpdate();
    }

    private void invocationFinished(long streamId, EntityCommand.InvocationFinished finished)
            throws SQLException {
        PreparedStatement statement = prepare(UPDATE_INVOCATION_FINISH);
        statement.setLong(1, streamId);
        setText(statement, 2, finished.exitCodeName());
        statement.setInt(3, finished.exitCode());
        statement.setInt(4, finished.overallSuccess() ? 1 : 0);
        setLong(statement, 5, finished.finishMicros());
        statement.executeUpdate();
    }

    private void metrics(long streamId, long sequence, EntityCommand.BuildMetricsReported metrics)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_BUILD_METRICS);
        setLong(statement, 1, metrics.actionsCreated());
        setLong(statement, 2, metrics.actionsExecuted());
        setLong(statement, 3, metrics.actionCacheHits());
        setLong(statement, 4, metrics.actionCacheMisses());
        setLong(statement, 5, metrics.targetsConfigured());
        setLong(statement, 6, metrics.targetsLoaded());
        setLong(statement, 7, metrics.packagesLoaded());
        setLong(statement, 8, metrics.wallTimeMillis());
        setLong(statement, 9, metrics.cpuTimeMillis());
        setLong(statement, 10, metrics.analysisPhaseMillis());
        setLong(statement, 11, metrics.executionPhaseMillis());
        setLong(statement, 12, metrics.actionsStartMillis());
        setLong(statement, 13, metrics.criticalPathMicros());
        statement.setLong(14, streamId);
        statement.setLong(15, sequence);
        statement.executeUpdate();

        for (EntityCommand.MnemonicWork work : metrics.mnemonics()) {
            intern(INSERT_MNEMONIC, knownMnemonics, work.mnemonic());
            PreparedStatement row = prepare(INSERT_MNEMONIC_METRIC);
            setLong(row, 1, work.created());
            setLong(row, 2, work.executed());
            row.setString(3, work.mnemonic());
            row.executeUpdate();
        }
        for (EntityCommand.RunnerWork runner : metrics.runners()) {
            PreparedStatement row = prepare(INSERT_RUNNER_COUNT);
            row.setString(1, runner.name());
            row.setString(2, runner.execKind().orElse(""));
            row.setInt(3, runner.actionCount());
            row.setInt(4, runner.total() ? 1 : 0);
            row.executeUpdate();
        }
        for (EntityCommand.CacheMiss miss : metrics.cacheMisses()) {
            PreparedStatement row = prepare(INSERT_CACHE_MISS);
            row.setString(1, miss.reason());
            row.setLong(2, miss.count());
            row.executeUpdate();
        }
        for (EntityCommand.Garbage garbage : metrics.garbage()) {
            PreparedStatement row = prepare(INSERT_GARBAGE);
            row.setString(1, garbage.type());
            row.setLong(2, garbage.collectedBytes());
            row.executeUpdate();
        }
    }

    // --- configurations ---------------------------------------------------

    private void configuration(
            long streamId, long sequence, EntityCommand.ConfigurationDeclared configuration)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_CONFIGURATION);
        statement.setLong(1, streamId);
        statement.setString(2, configuration.bepId());
        setText(statement, 3, configuration.mnemonic());
        setText(statement, 4, configuration.platformName());
        setText(statement, 5, configuration.cpu());
        statement.setInt(6, configuration.tool() ? 1 : 0);
        statement.setLong(7, streamId);
        statement.setLong(8, sequence);
        statement.executeUpdate();

        for (Map.Entry<String, String> variable : configuration.makeVariables().entrySet()) {
            PreparedStatement row = prepare(INSERT_MAKE_VARIABLE);
            row.setString(1, variable.getKey());
            row.setString(2, variable.getValue());
            row.setLong(3, streamId);
            row.setString(4, configuration.bepId());
            row.executeUpdate();
        }
    }

    /**
     * Makes sure a configuration row exists for an id something referenced.
     *
     * <p>The id {@code system} arrives this way on every build, and {@code none}
     * can too. The placeholder carries {@code declared = 0} and no payload
     * columns, which is the whole of what is known about it.
     */
    private void referenceConfiguration(long streamId, String bepId) throws SQLException {
        PreparedStatement statement = prepare(REFERENCE_CONFIGURATION);
        statement.setLong(1, streamId);
        statement.setString(2, bepId);
        statement.executeUpdate();
    }

    // --- targets ----------------------------------------------------------

    private void targetConfigured(
            long streamId, long sequence, EntityCommand.TargetConfigured target) throws SQLException {
        intern(INSERT_LABEL, knownLabels, target.label());
        String aspect = target.aspect().orElse("");
        PreparedStatement statement = prepare(INSERT_TARGET);
        statement.setString(1, aspect);
        setText(statement, 2, target.targetKind());
        setText(statement, 3, target.testSize());
        statement.setString(4, TargetOutcome.CONFIGURED.name());
        statement.setLong(5, streamId);
        statement.setLong(6, sequence);
        statement.setString(7, target.label());
        statement.executeUpdate();

        for (String tag : target.tags()) {
            tag(target.label(), aspect, tag, "CONFIGURED");
        }
    }

    private void targetCompleted(
            long streamId, long sequence, EntityCommand.TargetCompleted target) throws SQLException {
        String aspect = target.aspect().orElse("");
        ensureTarget(streamId, sequence, target.label(), aspect);
        referenceConfiguration(streamId, target.configurationId());

        Optional<FailureInfo> failure = target.failure();
        PreparedStatement statement = prepare(INSERT_CONFIGURED_TARGET);
        statement.setString(
                1,
                (target.success() ? TargetOutcome.BUILT : TargetOutcome.FAILED).name());
        setLong(statement, 2, target.testTimeoutSeconds());
        setText(statement, 3, failure.map(FailureInfo::describeCategory));
        setText(statement, 4, failure.map(FailureInfo::message));
        statement.setLong(5, streamId);
        statement.setLong(6, sequence);
        statement.setString(7, target.label());
        statement.setString(8, aspect);
        statement.setLong(9, streamId);
        statement.setString(10, target.configurationId());
        statement.executeUpdate();

        for (String tag : target.tags()) {
            tag(target.label(), aspect, tag, "COMPLETED");
        }
        int ordinal = 0;
        for (EntityCommand.OutputGroupRef group : target.outputGroups()) {
            group.rootDepsetId().ifPresent(id -> referenceDepsetQuietly(streamId, id));
            PreparedStatement row = prepare(INSERT_OUTPUT_GROUP);
            row.setString(1, group.name());
            row.setInt(2, group.incomplete() ? 1 : 0);
            row.setInt(3, ordinal++);
            row.setLong(4, streamId);
            setText(row, 5, group.rootDepsetId());
            row.setString(6, target.label());
            row.setString(7, aspect);
            row.setLong(8, streamId);
            row.setString(9, target.configurationId());
            row.executeUpdate();
        }
        if (!target.success()) {
            PreparedStatement failedTest = prepare(MARK_TEST_FAILED_TO_BUILD);
            failedTest.setString(1, target.label());
            failedTest.setString(2, aspect);
            failedTest.setLong(3, streamId);
            failedTest.setString(4, target.configurationId());
            failedTest.executeUpdate();
        }
        for (FileRef directory : target.directoryOutputs()) {
            artifact(directory);
            PreparedStatement row = prepare(INSERT_DIRECTORY_OUTPUT);
            row.setString(1, target.label());
            row.setString(2, aspect);
            row.setLong(3, streamId);
            row.setString(4, target.configurationId());
            row.setString(5, directory.path());
            row.executeUpdate();
        }
    }

    /**
     * Makes sure a target row exists for a label something completed or
     * aborted.
     *
     * <p>Measured ordering says {@code configured} always precedes
     * {@code completed}, so this is normally a no-op — but from Bazel 7.6.1 an
     * analysis-failed target emits no {@code configured} payload at all, and
     * this is the only thing that gives it a row. {@code CONFIGURED} is not a
     * guess: a target that completed was configured.
     */
    private void ensureTarget(long streamId, long sequence, String label, String aspect)
            throws SQLException {
        intern(INSERT_LABEL, knownLabels, label);
        PreparedStatement statement = prepare(INSERT_TARGET);
        statement.setString(1, aspect);
        statement.setNull(2, Types.VARCHAR);
        statement.setNull(3, Types.VARCHAR);
        statement.setString(4, TargetOutcome.CONFIGURED.name());
        statement.setLong(5, streamId);
        statement.setLong(6, sequence);
        statement.setString(7, label);
        statement.executeUpdate();
    }

    private void tag(String label, String aspect, String tag, String fromEvent) throws SQLException {
        PreparedStatement statement = prepare(INSERT_TARGET_TAG);
        statement.setString(1, tag);
        statement.setString(2, fromEvent);
        statement.setString(3, label);
        statement.setString(4, aspect);
        statement.executeUpdate();
    }

    // --- file sets --------------------------------------------------------

    private void depset(long streamId, long sequence, EntityCommand.DepsetDeclared depset)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_DEPSET);
        statement.setLong(1, streamId);
        statement.setString(2, depset.bepId());
        statement.setLong(3, streamId);
        statement.setLong(4, sequence);
        statement.executeUpdate();

        int childOrdinal = 0;
        for (String child : depset.childIds()) {
            referenceDepset(streamId, child);
            PreparedStatement row = prepare(INSERT_DEPSET_CHILD);
            row.setInt(1, childOrdinal++);
            row.setLong(2, streamId);
            row.setString(3, depset.bepId());
            row.setLong(4, streamId);
            row.setString(5, child);
            row.executeUpdate();
        }
        int fileOrdinal = 0;
        for (FileRef file : depset.files()) {
            artifact(file);
            PreparedStatement row = prepare(INSERT_DEPSET_FILE);
            row.setInt(1, fileOrdinal++);
            row.setLong(2, streamId);
            row.setString(3, depset.bepId());
            row.setString(4, file.path());
            row.executeUpdate();
        }
    }

    private void referenceDepset(long streamId, String bepId) throws SQLException {
        PreparedStatement statement = prepare(REFERENCE_DEPSET);
        statement.setLong(1, streamId);
        statement.setString(2, bepId);
        statement.executeUpdate();
    }

    /** {@link #referenceDepset} for use inside a lambda. */
    private void referenceDepsetQuietly(long streamId, String bepId) {
        try {
            referenceDepset(streamId, bepId);
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
    }

    private void artifact(FileRef file) throws SQLException {
        PreparedStatement statement = prepare(INSERT_ARTIFACT);
        statement.setString(1, file.path());
        statement.setString(2, file.name());
        setText(statement, 3, file.pathPrefix());
        setText(statement, 4, file.digest());
        setLong(statement, 5, file.sizeBytes());
        setText(statement, 6, file.uri());
        statement.setInt(7, file.directory() ? 1 : 0);
        statement.setInt(8, file.source() ? 1 : 0);
        statement.executeUpdate();
    }

    // --- actions ----------------------------------------------------------

    private void action(long streamId, long sequence, EntityCommand.ActionCompleted action)
            throws SQLException {
        action.label().ifPresent(label -> internQuietly(INSERT_LABEL, knownLabels, label));
        action.mnemonic().ifPresent(m -> internQuietly(INSERT_MNEMONIC, knownMnemonics, m));
        referenceConfiguration(streamId, action.configurationId());

        Optional<FailureInfo> failure = action.failure();
        PreparedStatement statement = prepare(INSERT_ACTION);
        statement.setString(1, action.primaryOutput());
        setText(statement, 2, action.label());
        setText(statement, 3, action.mnemonic());
        statement.setString(
                4, ActionOutcome.ofSuccessFlag(action.success()).name());
        setInt(statement, 5, action.bazelExitCode());
        setInt(statement, 6, failure.map(FailureInfo::spawnExitCode).orElse(OptionalInt.empty()));
        setText(statement, 7, failure.map(FailureInfo::describeCategory));
        setText(statement, 8, failure.map(FailureInfo::message));
        setLong(statement, 9, action.timing().startMicros());
        setLong(statement, 10, action.timing().endMicros());
        setText(statement, 11, action.timing().unknownReason());
        setText(statement, 12, commandLineText(action));
        setText(statement, 13, action.stdoutUri());
        setText(statement, 14, action.stderrUri());
        statement.setLong(15, streamId);
        statement.setLong(16, sequence);
        statement.setLong(17, streamId);
        statement.setString(18, action.configurationId());
        if (statement.executeUpdate() == 0) {
            conflictingActions++;
        }
    }

    /**
     * The argv as a JSON array.
     *
     * <p>Not shell-quoted: re-quoting would produce something that looks
     * executable and is not, and the command was never run through a shell
     * (plan 22.2). Not newline-joined either, which is what this used to do —
     * an argument may itself contain a newline (every {@code /bin/bash -c}
     * script does), so joining on one destroys the argument boundaries and no
     * reader can recover them. JSON is what requirement 21 asks for and the
     * only form here that round-trips.
     *
     * <p>Written by hand rather than through a JSON library because the shape
     * is fixed — an array of strings — and pulling a binding into the storage
     * layer to emit six escapes would be the larger cost.
     *
     * <p>Empty means the event carried no command line, which is every action
     * that did not run a spawn.
     */
    private static Optional<String> commandLineText(EntityCommand.ActionCompleted action) {
        if (action.commandLine().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < action.commandLine().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendJsonString(json, action.commandLine().get(i));
        }
        return Optional.of(json.append(']').toString());
    }

    /**
     * RFC 8259 string escaping: the six named escapes, then the numeric form
     * for the remaining control characters.
     */
    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // --- tests ------------------------------------------------------------

    private void testAttempt(
            long streamId, long sequence, EntityCommand.TestAttemptCompleted attempt)
            throws SQLException {
        ensureTest(streamId, sequence, attempt.label(), attempt.configurationId());

        PreparedStatement statement = prepare(INSERT_TEST_ATTEMPT);
        statement.setInt(1, attempt.run());
        statement.setInt(2, attempt.shard());
        statement.setInt(3, attempt.attempt());
        statement.setString(4, attempt.status().name());
        statement.setInt(5, attempt.cachedLocally() ? 1 : 0);
        setLong(statement, 6, attempt.startMicros());
        setLong(statement, 7, attempt.durationMicros());
        setInt(statement, 8, attempt.exitCode());
        setText(statement, 9, attempt.strategy());
        statement.setLong(10, streamId);
        statement.setLong(11, sequence);
        statement.setString(12, attempt.label());
        statement.setLong(13, streamId);
        statement.setString(14, attempt.configurationId());
        statement.executeUpdate();

        for (EntityCommand.TestLogRef log : attempt.outputs()) {
            testLog(streamId, attempt.label(), attempt.configurationId(),
                    OptionalInt.of(attempt.run()), OptionalInt.of(attempt.shard()),
                    OptionalInt.of(attempt.attempt()), log);
        }
    }

    private void testSummary(long streamId, long sequence, EntityCommand.TestSummarized summary)
            throws SQLException {
        ensureConfiguredTarget(streamId, sequence, summary.label(), summary.configurationId());

        PreparedStatement statement = prepare(INSERT_TEST);
        statement.setString(1, summary.overallStatus().name());
        setInt(statement, 2, summary.totalRunCount());
        setInt(statement, 3, summary.runCount());
        setInt(statement, 4, summary.shardCount());
        setInt(statement, 5, summary.attemptCount());
        statement.setInt(6, summary.totalNumCached());
        setLong(statement, 7, summary.firstStartMicros());
        setLong(statement, 8, summary.lastStopMicros());
        setLong(statement, 9, summary.bazelReportedDurationMicros());
        statement.setLong(10, streamId);
        statement.setLong(11, sequence);
        statement.setString(12, summary.label());
        statement.setString(13, "");
        statement.setLong(14, streamId);
        statement.setString(15, summary.configurationId());
        statement.executeUpdate();

        for (EntityCommand.TestLogRef log : summary.logs()) {
            testLog(streamId, summary.label(), summary.configurationId(),
                    OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), log);
        }
    }

    private void testLog(
            long streamId,
            String label,
            String configurationId,
            OptionalInt run,
            OptionalInt shard,
            OptionalInt attempt,
            EntityCommand.TestLogRef log)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_TEST_LOG);
        // A summary's log names no attempt, so the sub-select is given values
        // no attempt can match and resolves to NULL. If an attempt row later
        // claims the same uri, the conflict clause fills it in.
        setInt(statement, 1, run);
        setInt(statement, 2, shard);
        setInt(statement, 3, attempt);
        setText(statement, 4, log.name());
        statement.setString(5, log.uri());
        setText(statement, 6, log.summaryStatus());
        statement.setString(7, label);
        statement.setLong(8, streamId);
        statement.setString(9, configurationId);
        statement.executeUpdate();
    }

    private void ensureTest(long streamId, long sequence, String label, String configurationId)
            throws SQLException {
        ensureConfiguredTarget(streamId, sequence, label, configurationId);
        PreparedStatement statement = prepare(ENSURE_TEST);
        statement.setString(1, label);
        statement.setString(2, "");
        statement.setLong(3, streamId);
        statement.setString(4, configurationId);
        statement.executeUpdate();
    }

    private void ensureConfiguredTarget(
            long streamId, long sequence, String label, String configurationId) throws SQLException {
        ensureTarget(streamId, sequence, label, "");
        referenceConfiguration(streamId, configurationId);
        PreparedStatement statement = prepare(ENSURE_CONFIGURED_TARGET);
        statement.setString(1, label);
        statement.setString(2, "");
        statement.setLong(3, streamId);
        statement.setString(4, configurationId);
        statement.executeUpdate();
    }

    // --- aborts and progress ----------------------------------------------

    private void aborted(long streamId, long sequence, EntityCommand.TargetAborted aborted)
            throws SQLException {
        if (aborted.label().isPresent()) {
            intern(INSERT_LABEL, knownLabels, aborted.label().get());
        }
        aborted.configurationId().ifPresent(id -> {
            try {
                referenceConfiguration(streamId, id);
            } catch (SQLException e) {
                throw new UncheckedSqlException(e);
            }
        });

        // An abort names a target, and on Bazel 7.6.1 and later it is the only
        // event that names an analysis-failed one -- those emit no `configured`
        // payload at all. Recording only the abort row left every such target
        // out of the targets tree and out of the target counts, which is the
        // whole of what a failed analysis produces.
        if (aborted.label().isPresent() && namesATarget(aborted.idKind())) {
            markTargetAborted(streamId, sequence, aborted);
        }

        PreparedStatement statement = prepare(INSERT_ABORTED);
        statement.setString(1, aborted.idKind());
        setText(statement, 2, aborted.label());
        statement.setLong(3, streamId);
        setText(statement, 4, aborted.configurationId());
        statement.setString(5, aborted.reason());
        setText(statement, 6, aborted.description().isEmpty()
                ? Optional.empty()
                : Optional.of(aborted.description()));
        statement.setLong(7, streamId);
        statement.setLong(8, sequence);
        statement.executeUpdate();
    }

    /**
     * Whether an abort's id kind identifies a target rather than something
     * else.
     *
     * <p>Patterns and other ids abort too; they carry no label and describe no
     * target, so they stay in {@code aborted_events} and nowhere else.
     */
    private static boolean namesATarget(String idKind) {
        return switch (idKind) {
            case "targetConfigured", "targetCompleted", "unconfiguredLabel", "configuredLabel" ->
                    true;
            default -> false;
        };
    }

    /**
     * Gives an aborted target a row, and marks it aborted.
     *
     * <p>{@code INSERT_TARGET}'s conflict clause protects {@code ABORTED} from
     * being written back to {@code CONFIGURED} by a later event, which matters
     * because aborts arrive after everything else.
     */
    private void markTargetAborted(
            long streamId, long sequence, EntityCommand.TargetAborted aborted) throws SQLException {
        String label = aborted.label().orElseThrow();
        intern(INSERT_LABEL, knownLabels, label);
        PreparedStatement target = prepare(INSERT_TARGET);
        target.setString(1, "");
        target.setNull(2, Types.VARCHAR);
        target.setNull(3, Types.VARCHAR);
        target.setString(4, TargetOutcome.ABORTED.name());
        target.setLong(5, streamId);
        target.setLong(6, sequence);
        target.setString(7, label);
        target.executeUpdate();

        if (aborted.configurationId().isEmpty()) {
            // `targetConfigured` and `unconfiguredLabel` carry no configuration,
            // so there is no configured target to mark -- which is correct: the
            // target never got as far as being one.
            return;
        }
        PreparedStatement configured = prepare(MARK_CONFIGURED_TARGET_ABORTED);
        configured.setString(1, label);
        configured.setLong(2, streamId);
        configured.setString(3, aborted.configurationId().orElseThrow());
        configured.executeUpdate();
    }

    private void progress(long streamId, long sequence, EntityCommand.ProgressOutputSeen progress)
            throws SQLException {
        PreparedStatement statement = prepare(INSERT_PROGRESS);
        statement.setLong(1, sequence);
        statement.setInt(2, progress.stdoutBytes());
        statement.setInt(3, progress.stderrBytes());
        statement.setLong(4, streamId);
        statement.setLong(5, sequence);
        statement.executeUpdate();
    }

    // --- plumbing ---------------------------------------------------------

    private void intern(String sql, Map<String, Boolean> cache, String value) throws SQLException {
        if (cache.put(value, Boolean.TRUE) != null) {
            return;
        }
        PreparedStatement statement = prepare(sql);
        statement.setString(1, value);
        statement.executeUpdate();
    }

    private void internQuietly(String sql, Map<String, Boolean> cache, String value) {
        try {
            intern(sql, cache, value);
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        PreparedStatement existing = statements.get(sql);
        if (existing != null) {
            return existing;
        }
        PreparedStatement statement = connection.prepareStatement(sql);
        statements.put(sql, statement);
        return statement;
    }

    private static void setText(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void setText(PreparedStatement statement, int index, String value)
            throws SQLException {
        setText(statement, index, value.isEmpty() ? Optional.empty() : Optional.of(value));
    }

    private static void setLong(PreparedStatement statement, int index, OptionalLong value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }

    private static void setInt(PreparedStatement statement, int index, OptionalInt value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.getAsInt());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    /** Carries an {@link SQLException} out of a lambda without swallowing it. */
    static final class UncheckedSqlException extends RuntimeException {
        UncheckedSqlException(SQLException cause) {
            super(cause);
        }
    }
}

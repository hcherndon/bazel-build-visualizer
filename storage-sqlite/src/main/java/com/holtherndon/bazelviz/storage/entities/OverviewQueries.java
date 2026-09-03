package com.holtherndon.bazelviz.storage.entities;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The overview's read path: one snapshot of the whole session.
 *
 * <h2>Counted live, deliberately</h2>
 *
 * <p>Plan 24's Phase 3 task list asks for incremental overview aggregates, and these are the
 * queries they will eventually maintain. They are counts and grouped counts over tables the schema
 * indexes, so they answer in milliseconds on a normal build and in a second or two on a very large
 * one — fast enough that a live overview can refresh on a timer, which is what "live updates are
 * coalesced" means here: the view re-reads a whole snapshot at an interval rather than reacting to
 * individual rows.
 *
 * <p>Making them incremental before measuring one that is slow would be optimizing a number nobody
 * has seen. What matters now is that every number is derived from the same read and that none of
 * them invents a value the build did not produce.
 */
public final class OverviewQueries implements AutoCloseable {

  private static final String INVOCATION =
      "SELECT build_tool_version, command, workspace_directory, publishes_all_actions,"
          + " overall_success, exit_code_name, started_micros, finished_micros,"
          + " saw_last_message FROM build_invocation WHERE singleton = 1";

  private static final String COUNTS =
      "SELECT (SELECT COUNT(*) FROM targets),"
          + " (SELECT COUNT(*) FROM configured_targets),"
          + " (SELECT COUNT(*) FROM configured_targets WHERE outcome = 'BUILT'),"
          + " (SELECT COUNT(*) FROM configured_targets WHERE outcome = 'FAILED'),"
          + " (SELECT COUNT(*) FROM targets t WHERE NOT EXISTS"
          + "    (SELECT 1 FROM configured_targets ct WHERE ct.target_id = t.id)),"
          + " (SELECT COUNT(*) FROM actions),"
          + " (SELECT COUNT(*) FROM actions WHERE outcome = 'FAILED'),"
          + " (SELECT COUNT(*) FROM tests),"
          + " (SELECT COUNT(*) FROM tests WHERE overall_status NOT IN"
          + "    ('PASSED', 'SKIPPED')),"
          + " (SELECT COUNT(*) FROM artifacts),"
          + " (SELECT COUNT(*) FROM aborted_events),"
          + " (SELECT COUNT(DISTINCT label_id) FROM aborted_events"
          + "    WHERE label_id IS NOT NULL)";

  private static final String METRICS =
      "SELECT actions_created, actions_executed, action_cache_hits, targets_configured,"
          + " packages_loaded, wall_time_millis, cpu_time_millis, analysis_phase_millis,"
          + " execution_phase_millis, critical_path_micros FROM build_metrics"
          + " WHERE singleton = 1";

  private static final String TOP_MNEMONICS =
      "SELECT m.value, mm.actions_created, mm.actions_executed FROM mnemonic_metrics mm"
          + " JOIN mnemonics m ON m.id = mm.mnemonic_id"
          + " ORDER BY COALESCE(mm.actions_executed, 0) DESC,"
          + " COALESCE(mm.actions_created, 0) DESC, m.value ASC LIMIT ?";

  /** Rows in the mnemonic breakdown. Enough to be useful, few enough to read. */
  public static final int DEFAULT_TOP_MNEMONICS = 12;

  private final Connection connection;

  public OverviewQueries(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  public OverviewSnapshot snapshot() throws SQLException {
    return snapshot(DEFAULT_TOP_MNEMONICS);
  }

  public OverviewSnapshot snapshot(int topMnemonics) throws SQLException {
    // One transaction, so the four statements see one state of the
    // database. In auto-commit each is its own implicit read transaction,
    // and during a live capture the counts and the metrics would come from
    // different instants -- a screen whose totals disagree with each other,
    // which is the thing the timer-based refresh exists to avoid. SQLite's
    // WAL gives a reader a snapshot for the life of its transaction, so
    // this costs nothing beyond the two statements that open and close it,
    // and it is still a short read transaction (plan 10.9).
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      return readSnapshot(topMnemonics);
    } finally {
      // Read-only, so there is nothing to commit; ending the transaction
      // is what releases the snapshot and lets WAL checkpoints proceed.
      connection.rollback();
      connection.setAutoCommit(autoCommit);
    }
  }

  private OverviewSnapshot readSnapshot(int topMnemonics) throws SQLException {
    Invocation invocation = readInvocation();
    Counts counts = readCounts();
    Metrics metrics = readMetrics();
    return new OverviewSnapshot(
        invocation.bazelVersion,
        invocation.command,
        invocation.workspace,
        invocation.publishesAllActions,
        invocation.overallSuccess,
        invocation.exitCodeName,
        invocation.elapsedMicros,
        invocation.sawLastMessage,
        counts.targets,
        counts.configuredTargets,
        counts.built,
        counts.failedTargets,
        counts.notCompleted,
        counts.actions,
        counts.failedActions,
        counts.tests,
        counts.failedTests,
        counts.artifacts,
        counts.aborted,
        counts.abortedTargets,
        metrics.actionsCreated,
        metrics.actionsExecuted,
        metrics.cacheHits,
        metrics.targetsConfigured,
        metrics.packagesLoaded,
        metrics.wallMillis,
        metrics.cpuMillis,
        metrics.analysisMillis,
        metrics.executionMillis,
        metrics.criticalPathMicros,
        readTopMnemonics(topMnemonics));
  }

  private Invocation readInvocation() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INVOCATION);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        // No BuildStarted reached this session -- an import of a
        // truncated file, or a capture that failed before Bazel spoke.
        // Everything is unknown, and saying so is the honest answer.
        return new Invocation(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            false);
      }
      OptionalLong started = number(rows, 7);
      OptionalLong finished = number(rows, 8);
      OptionalLong elapsed =
          started.isPresent() && finished.isPresent()
              ? OptionalLong.of(finished.getAsLong() - started.getAsLong())
              : OptionalLong.empty();
      return new Invocation(
          text(rows, 1),
          text(rows, 2),
          text(rows, 3),
          bool(rows, 4),
          bool(rows, 5),
          text(rows, 6),
          elapsed,
          rows.getInt(9) != 0);
    }
  }

  private Counts readCounts() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(COUNTS);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return new Counts(
          rows.getLong(1),
          rows.getLong(2),
          rows.getLong(3),
          rows.getLong(4),
          rows.getLong(5),
          rows.getLong(6),
          rows.getLong(7),
          rows.getLong(8),
          rows.getLong(9),
          rows.getLong(10),
          rows.getLong(11),
          rows.getLong(12));
    }
  }

  private Metrics readMetrics() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(METRICS);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return new Metrics(
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
      }
      return new Metrics(
          number(rows, 1),
          number(rows, 2),
          number(rows, 3),
          number(rows, 4),
          number(rows, 5),
          number(rows, 6),
          number(rows, 7),
          number(rows, 8),
          number(rows, 9),
          number(rows, 10));
    }
  }

  private List<OverviewSnapshot.MnemonicWork> readTopMnemonics(int limit) throws SQLException {
    List<OverviewSnapshot.MnemonicWork> work = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(TOP_MNEMONICS)) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          work.add(
              new OverviewSnapshot.MnemonicWork(
                  rows.getString(1), number(rows, 2), number(rows, 3)));
        }
      }
    }
    return work;
  }

  private record Invocation(
      Optional<String> bazelVersion,
      Optional<String> command,
      Optional<String> workspace,
      Optional<Boolean> publishesAllActions,
      Optional<Boolean> overallSuccess,
      Optional<String> exitCodeName,
      OptionalLong elapsedMicros,
      boolean sawLastMessage) {}

  private record Counts(
      long targets,
      long configuredTargets,
      long built,
      long failedTargets,
      long notCompleted,
      long actions,
      long failedActions,
      long tests,
      long failedTests,
      long artifacts,
      long aborted,
      long abortedTargets) {}

  private record Metrics(
      OptionalLong actionsCreated,
      OptionalLong actionsExecuted,
      OptionalLong cacheHits,
      OptionalLong targetsConfigured,
      OptionalLong packagesLoaded,
      OptionalLong wallMillis,
      OptionalLong cpuMillis,
      OptionalLong analysisMillis,
      OptionalLong executionMillis,
      OptionalLong criticalPathMicros) {}

  private static Optional<String> text(ResultSet rows, int index) throws SQLException {
    String value = rows.getString(index);
    return value == null ? Optional.empty() : Optional.of(value);
  }

  /** Three-state: true, false, or never recorded. */
  private static Optional<Boolean> bool(ResultSet rows, int index) throws SQLException {
    int value = rows.getInt(index);
    return rows.wasNull() ? Optional.empty() : Optional.of(value != 0);
  }

  private static OptionalLong number(ResultSet rows, int index) throws SQLException {
    long value = rows.getLong(index);
    return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }
}

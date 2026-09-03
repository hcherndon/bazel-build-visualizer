package com.holtherndon.bazelviz.storage.enrich;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads and writes the {@code enrichment_tasks} table.
 *
 * <p>Plan 21.4 makes each enrichment task independent and requires a failed one to be explained
 * rather than merely absent. This is where that explanation lives, and it is written in the same
 * transaction that finishes the task, so a crash cannot leave a task that succeeded silently or
 * failed silently.
 */
public final class EnrichmentTaskStore {

  private static final String UPSERT =
      "INSERT INTO enrichment_tasks (kind, source_path, state, started_micros)"
          + " VALUES (?, ?, ?, ?)"
          + " ON CONFLICT (kind) DO UPDATE SET"
          + " source_path = excluded.source_path,"
          + " state = excluded.state,"
          + " started_micros = excluded.started_micros,"
          + " finished_micros = NULL, exit_status = NULL, error_excerpt = NULL,"
          + " retriable = 0, unavailable_metrics = NULL, records_read = NULL";
  private static final String SELECT_ID = "SELECT id FROM enrichment_tasks WHERE kind = ?";
  private static final String FINISH =
      "UPDATE enrichment_tasks SET state = ?, finished_micros = ?, exit_status = ?,"
          + " error_excerpt = ?, retriable = ?, unavailable_metrics = ?,"
          + " records_read = ?, resume_offset = ? WHERE id = ?";
  private static final String SELECT_ALL =
      "SELECT kind, source_path, state, exit_status, error_excerpt, retriable,"
          + " unavailable_metrics, records_read, resume_offset"
          + " FROM enrichment_tasks ORDER BY id";

  /** How much of an error message is kept. Enough to diagnose, not a whole stack. */
  public static final int ERROR_EXCERPT_LIMIT = 2000;

  private final Connection connection;

  public EnrichmentTaskStore(Connection connection) {
    this.connection = connection;
  }

  /**
   * Marks a task started, replacing any previous run of the same kind.
   *
   * <p>Re-running an enrichment clears the previous outcome rather than appending, because "the
   * profile import failed" is a fact about the current state of the session, not a history.
   *
   * @return the task's row id, which every row it writes carries
   */
  public long begin(EnrichmentTask.Kind kind, Optional<String> sourcePath, long atMicros)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
      statement.setString(1, kind.name());
      if (sourcePath.isPresent()) {
        statement.setString(2, sourcePath.get());
      } else {
        statement.setNull(2, Types.VARCHAR);
      }
      statement.setString(3, EnrichmentTask.State.RUNNING.name());
      statement.setLong(4, atMicros);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(SELECT_ID)) {
      statement.setString(1, kind.name());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("enrichment task " + kind + " vanished after insert");
        }
        return rows.getLong(1);
      }
    }
  }

  /** Records how a task ended. */
  public void finish(
      long taskId,
      EnrichmentTask.State state,
      Optional<String> exitStatus,
      Optional<String> errorExcerpt,
      boolean retriable,
      List<String> unavailableMetrics,
      OptionalLong recordsRead,
      OptionalLong resumeOffset,
      long atMicros)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FINISH)) {
      statement.setString(1, state.name());
      statement.setLong(2, atMicros);
      setNullable(statement, 3, exitStatus);
      setNullable(statement, 4, errorExcerpt.map(EnrichmentTaskStore::excerpt));
      statement.setInt(5, retriable ? 1 : 0);
      setNullable(
          statement,
          6,
          unavailableMetrics.isEmpty()
              ? Optional.empty()
              : Optional.of(String.join("\n", unavailableMetrics)));
      if (recordsRead.isPresent()) {
        statement.setLong(7, recordsRead.getAsLong());
      } else {
        statement.setNull(7, Types.INTEGER);
      }
      if (resumeOffset.isPresent()) {
        statement.setLong(8, resumeOffset.getAsLong());
      } else {
        statement.setNull(8, Types.INTEGER);
      }
      statement.setLong(9, taskId);
      statement.executeUpdate();
    }
  }

  /** Every task, in the order they were created. */
  public List<EnrichmentTask> all() throws SQLException {
    List<EnrichmentTask> tasks = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String metrics = rows.getString("unavailable_metrics");
        long records = rows.getLong("records_read");
        boolean recordsNull = rows.wasNull();
        long offset = rows.getLong("resume_offset");
        boolean offsetNull = rows.wasNull();
        tasks.add(
            new EnrichmentTask(
                EnrichmentTask.Kind.valueOf(rows.getString("kind")),
                Optional.ofNullable(rows.getString("source_path")),
                EnrichmentTask.State.valueOf(rows.getString("state")),
                Optional.ofNullable(rows.getString("exit_status")),
                Optional.ofNullable(rows.getString("error_excerpt")),
                rows.getInt("retriable") != 0,
                metrics == null || metrics.isEmpty() ? List.of() : List.of(metrics.split("\n")),
                recordsNull ? OptionalLong.empty() : OptionalLong.of(records),
                offsetNull ? OptionalLong.empty() : OptionalLong.of(offset)));
      }
    }
    return tasks;
  }

  /** The first {@value #ERROR_EXCERPT_LIMIT} characters, with a marker when cut. */
  static String excerpt(String message) {
    if (message.length() <= ERROR_EXCERPT_LIMIT) {
      return message;
    }
    return message.substring(0, ERROR_EXCERPT_LIMIT) + "\n… (truncated)";
  }

  private static void setNullable(PreparedStatement statement, int index, Optional<String> value)
      throws SQLException {
    if (value.isPresent()) {
      statement.setString(index, value.get());
    } else {
      statement.setNull(index, Types.VARCHAR);
    }
  }
}

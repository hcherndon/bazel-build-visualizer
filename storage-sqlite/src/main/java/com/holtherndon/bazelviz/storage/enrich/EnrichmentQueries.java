package com.holtherndon.bazelviz.storage.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Reads what Phase 4 added: attempts, phases, and how much of the build each source actually
 * covers.
 *
 * <p>Coverage is computed, never stored. How many actions have attempt data is a count over {@code
 * action_attempts}; a cached copy would be one more number that can disagree with its rows.
 */
public final class EnrichmentQueries implements AutoCloseable {

  private static final String ATTEMPTS_FOR_ACTION =
      "SELECT a.*, "
          + " (SELECT count(*) FROM attempt_outputs o"
          + "   WHERE o.attempt_id = a.id AND o.produced = 1) AS produced_outputs,"
          + " (SELECT count(*) FROM attempt_outputs o"
          + "   WHERE o.attempt_id = a.id AND o.produced = 0) AS unproduced_outputs,"
          + " (SELECT value FROM labels WHERE id = a.label_id) AS label,"
          + " (SELECT value FROM mnemonics WHERE id = a.mnemonic_id) AS mnemonic"
          + " FROM action_attempts a WHERE a.action_id = ? ORDER BY a.log_entry_index";

  private static final String ATTEMPTS_FOR_LABEL =
      "SELECT a.*, "
          + " (SELECT count(*) FROM attempt_outputs o"
          + "   WHERE o.attempt_id = a.id AND o.produced = 1) AS produced_outputs,"
          + " (SELECT count(*) FROM attempt_outputs o"
          + "   WHERE o.attempt_id = a.id AND o.produced = 0) AS unproduced_outputs,"
          + " (SELECT value FROM labels WHERE id = a.label_id) AS label,"
          + " (SELECT value FROM mnemonics WHERE id = a.mnemonic_id) AS mnemonic"
          + " FROM action_attempts a"
          + " WHERE a.label_id = (SELECT id FROM labels WHERE value = ?)"
          + " ORDER BY a.log_entry_index";

  private static final String CORRELATION_COUNTS =
      "SELECT correlation, count(*) FROM action_attempts GROUP BY correlation";
  private static final String ACTION_COUNT = "SELECT count(*) FROM actions";
  private static final String ACTIONS_WITH_ATTEMPTS =
      "SELECT count(DISTINCT action_id) FROM action_attempts WHERE action_id IS NOT NULL";
  private static final String ATTEMPT_COUNT = "SELECT count(*) FROM action_attempts";
  private static final String SPAN_COUNT = "SELECT count(*) FROM profile_spans";
  private static final String ATTRIBUTED_SPAN_COUNT =
      "SELECT count(*) FROM profile_spans WHERE action_id IS NOT NULL";
  private static final String PHASES =
      "SELECT ordinal, name, start_micros, end_micros, end_is_derived"
          + " FROM build_phases ORDER BY ordinal";
  private static final String CRITICAL_PATH =
      "SELECT ordinal, description, start_micros, duration_micros"
          + " FROM bazel_critical_path ORDER BY ordinal";
  private static final String ANCHOR =
      "SELECT anchor_micros, anchor_source_key, anchor_meaning, uncertainty_micros,"
          + " build_id, build_id_matches FROM profile_metadata WHERE id = 1";
  private static final String RUNNER_COUNTS =
      "SELECT coalesce(runner, '(not reported)'), count(*) FROM action_attempts"
          + " GROUP BY runner ORDER BY count(*) DESC";

  private final Connection connection;

  public EnrichmentQueries(Connection connection) {
    this.connection = connection;
  }

  /** Every attempt attached to this action, in log order. */
  public List<AttemptRow> attemptsForAction(long actionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ATTEMPTS_FOR_ACTION)) {
      statement.setLong(1, actionId);
      return readAttempts(statement);
    }
  }

  /**
   * Every attempt carrying this label.
   *
   * <p>How a test's attempts are reached, since they attach to no action (K2). Returns both of the
   * two spawns a test produces, in log order, with no attempt to decide which is the real one.
   */
  public List<AttemptRow> attemptsForLabel(String label) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ATTEMPTS_FOR_LABEL)) {
      statement.setString(1, label);
      return readAttempts(statement);
    }
  }

  /** The numbers the data-coverage panel shows. */
  public Coverage coverage() throws SQLException {
    Map<AttemptCorrelation, Long> byCorrelation = new EnumMap<>(AttemptCorrelation.class);
    try (PreparedStatement statement = connection.prepareStatement(CORRELATION_COUNTS);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        try {
          byCorrelation.put(AttemptCorrelation.valueOf(rows.getString(1)), rows.getLong(2));
        } catch (IllegalArgumentException unknownToThisBuild) {
          // A session written by a newer build. Counting it under a
          // name this build does not have would be inventing one.
        }
      }
    }
    return new Coverage(
        scalar(ACTION_COUNT),
        scalar(ACTIONS_WITH_ATTEMPTS),
        scalar(ATTEMPT_COUNT),
        scalar(SPAN_COUNT),
        scalar(ATTRIBUTED_SPAN_COUNT),
        byCorrelation);
  }

  /** The build's phases, in order. */
  public List<Phase> phases() throws SQLException {
    List<Phase> phases = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(PHASES);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        long end = rows.getLong("end_micros");
        boolean endNull = rows.wasNull();
        phases.add(
            new Phase(
                rows.getInt("ordinal"),
                rows.getString("name"),
                rows.getLong("start_micros"),
                endNull ? OptionalLong.empty() : OptionalLong.of(end),
                rows.getInt("end_is_derived") != 0));
      }
    }
    return phases;
  }

  /** Bazel's own critical path, in order, unjoined. */
  public List<CriticalPathComponent> bazelCriticalPath() throws SQLException {
    List<CriticalPathComponent> components = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(CRITICAL_PATH);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        long duration = rows.getLong("duration_micros");
        boolean durationNull = rows.wasNull();
        components.add(
            new CriticalPathComponent(
                rows.getInt("ordinal"),
                rows.getString("description"),
                durationNull ? OptionalLong.empty() : OptionalLong.of(duration)));
      }
    }
    return components;
  }

  /** How many attempts ran under each runner. */
  public List<RunnerCount> runnerCounts() throws SQLException {
    List<RunnerCount> counts = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(RUNNER_COUNTS);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        counts.add(new RunnerCount(rows.getString(1), rows.getLong(2)));
      }
    }
    return counts;
  }

  /** The profile's anchor, with its meaning, or empty when no profile was imported. */
  public Optional<ProfileAnchor> anchor() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ANCHOR);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return Optional.empty();
      }
      long micros = rows.getLong("anchor_micros");
      boolean absent = rows.wasNull();
      ProfileAnchor.Meaning meaning =
          ProfileAnchor.Meaning.valueOf(rows.getString("anchor_meaning"));
      if (absent || meaning == ProfileAnchor.Meaning.ABSENT) {
        return Optional.of(ProfileAnchor.absent());
      }
      return Optional.of(
          new ProfileAnchor(
              micros,
              meaning,
              rows.getString("anchor_source_key"),
              rows.getLong("uncertainty_micros")));
    }
  }

  private List<AttemptRow> readAttempts(PreparedStatement statement) throws SQLException {
    List<AttemptRow> attempts = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        attempts.add(attemptOf(rows));
      }
    }
    return attempts;
  }

  private static AttemptRow attemptOf(ResultSet rows) throws SQLException {
    return new AttemptRow(
        rows.getLong("id"),
        optionalLong(rows, "action_id"),
        AttemptCorrelation.valueOf(rows.getString("correlation")),
        Optional.ofNullable(rows.getString("correlation_note")),
        Optional.ofNullable(rows.getString("label")),
        Optional.ofNullable(rows.getString("mnemonic")),
        Optional.ofNullable(rows.getString("runner")),
        rows.getInt("cache_hit") != 0,
        optionalInt(rows, "exit_code"),
        Optional.ofNullable(rows.getString("status")),
        optionalLong(rows, "start_micros"),
        Optional.ofNullable(rows.getString("start_unknown_reason")),
        new AttemptRow.Timing(
            optionalLong(rows, "total_micros"),
            optionalLong(rows, "execution_wall_micros"),
            optionalLong(rows, "parse_micros"),
            optionalLong(rows, "network_micros"),
            optionalLong(rows, "fetch_micros"),
            optionalLong(rows, "queue_micros"),
            optionalLong(rows, "setup_micros"),
            optionalLong(rows, "upload_micros"),
            optionalLong(rows, "process_outputs_micros"),
            optionalLong(rows, "retry_micros")),
        Optional.ofNullable(rows.getString("digest_hash")),
        optionalLong(rows, "input_bytes"),
        optionalLong(rows, "input_files"),
        optionalLong(rows, "measured_memory_peak_bytes"),
        rows.getLong("produced_outputs"),
        rows.getLong("unproduced_outputs"));
  }

  private static OptionalLong optionalLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static OptionalInt optionalInt(ResultSet rows, String column) throws SQLException {
    int value = rows.getInt(column);
    return rows.wasNull() ? OptionalInt.empty() : OptionalInt.of(value);
  }

  private long scalar(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }

  /**
   * How much of the build each source covers.
   *
   * <p>{@code actionsWithAttempts} being far below {@code actions} is the normal state, not a
   * defect: two thirds of a build's actions run inside the Bazel server and never spawn a
   * subprocess (K1). The panel says so in those words.
   */
  public record Coverage(
      long actions,
      long actionsWithAttempts,
      long attempts,
      long profileSpans,
      long attributedProfileSpans,
      Map<AttemptCorrelation, Long> byCorrelation) {

    public Coverage {
      byCorrelation = Map.copyOf(byCorrelation);
    }

    /** True when no execution log was imported at all. */
    public boolean hasNoAttempts() {
      return attempts == 0;
    }

    /** Attempts whose correlation the user should look at. */
    public long attemptsNeedingAttention() {
      return byCorrelation.entrySet().stream()
          .filter(entry -> entry.getKey().needsAttention())
          .mapToLong(Map.Entry::getValue)
          .sum();
    }

    /**
     * True when the profile has spans but none can be tied to an action.
     *
     * <p>Means the build ran without {@code --experimental_profile_include_primary_output} (P4).
     */
    public boolean spansCannotBeAttributed() {
      return profileSpans > 0 && attributedProfileSpans == 0;
    }
  }

  /**
   * One build phase.
   *
   * @param endMicros absent for the last phase, whose end the profile never states
   * @param endIsDerived always true in practice: the profile states starts and never ends, so an
   *     end is the next marker's start
   */
  public record Phase(
      int ordinal, String name, long startMicros, OptionalLong endMicros, boolean endIsDerived) {

    /** How long the phase lasted, when a successor gave it an end. */
    public OptionalLong durationMicros() {
      return endMicros.isPresent()
          ? OptionalLong.of(endMicros.getAsLong() - startMicros)
          : OptionalLong.empty();
    }
  }

  /** One component of Bazel's own critical path, as Bazel described it. */
  public record CriticalPathComponent(
      int ordinal, String description, OptionalLong durationMicros) {}

  /** How many attempts ran under one runner. */
  public record RunnerCount(String runner, long attempts) {}
}

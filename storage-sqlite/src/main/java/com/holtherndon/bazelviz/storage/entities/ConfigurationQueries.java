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

/** Read path for the configuration explorer and its two-way comparison. */
public final class ConfigurationQueries {

  private static final String SUMMARY_CTES =
      """
      WITH configuration_keys AS (
        SELECT bep_id AS checksum FROM configurations
        UNION
        SELECT checksum FROM queried_configurations
      ),
      bep AS (
        SELECT bep_id AS checksum,
               max(declared) AS declared,
               max(mnemonic) AS mnemonic,
               max(platform_name) AS platform_name,
               max(cpu) AS cpu,
               max(is_tool) AS is_tool
        FROM configurations GROUP BY bep_id
      ),
      bep_targets AS (
        SELECT c.bep_id AS checksum, count(ct.id) AS target_count
        FROM configurations c
        JOIN configured_targets ct ON ct.configuration_id = c.id
        GROUP BY c.bep_id
      ),
      bep_actions AS (
        SELECT c.bep_id AS checksum, count(a.id) AS action_count
        FROM configurations c
        JOIN actions a ON a.configuration_id = c.id
        GROUP BY c.bep_id
      ),
      make_variables AS (
        SELECT c.bep_id AS checksum, count(DISTINCT mv.name) AS variable_count
        FROM configurations c
        JOIN configuration_make_variables mv ON mv.configuration_id = c.id
        GROUP BY c.bep_id
      ),
      queried AS (
        SELECT q.id, q.checksum, q.mnemonic, q.platform_name, q.is_tool,
               q.options_available
        FROM queried_configurations q
      ),
      queried_options AS (
        SELECT o.configuration_id,
               count(DISTINCT o.option_set_name) AS option_set_count,
               count(DISTINCT o.option_set_name || char(0) || o.ordinal) AS option_count
        FROM queried_configuration_options o GROUP BY o.configuration_id
      ),
      queried_fragments AS (
        SELECT f.configuration_id,
               count(DISTINCT f.fragment_name) AS fragment_count
        FROM queried_configuration_fragments f GROUP BY f.configuration_id
      ),
      queried_targets AS (
        SELECT configuration_checksum AS checksum, count(*) AS target_count
        FROM configured_target_nodes
        WHERE configuration_checksum IS NOT NULL
        GROUP BY configuration_checksum
      )
      """;

  private static final String SUMMARY_COLUMNS =
      """
      k.checksum,
      coalesce(b.mnemonic, q.mnemonic),
      coalesce(b.platform_name, q.platform_name),
      b.cpu,
      CASE WHEN b.declared = 1 THEN b.is_tool
           WHEN q.id IS NOT NULL THEN q.is_tool ELSE NULL END,
      CASE WHEN b.checksum IS NULL THEN 0 ELSE 1 END,
      coalesce(b.declared, 0),
      CASE WHEN q.id IS NULL THEN 0 ELSE 1 END,
      coalesce(q.options_available, 0),
      coalesce(bt.target_count, 0),
      coalesce(ba.action_count, 0),
      coalesce(qt.target_count, 0),
      coalesce(qf.fragment_count, 0),
      coalesce(qo.option_set_count, 0),
      coalesce(qo.option_count, 0),
      coalesce(mv.variable_count, 0)
      """;

  private static final String SUMMARY_FROM =
      """
      FROM configuration_keys k
      LEFT JOIN bep b ON b.checksum = k.checksum
      LEFT JOIN bep_targets bt ON bt.checksum = k.checksum
      LEFT JOIN bep_actions ba ON ba.checksum = k.checksum
      LEFT JOIN make_variables mv ON mv.checksum = k.checksum
      LEFT JOIN queried q ON q.checksum = k.checksum
      LEFT JOIN queried_options qo ON qo.configuration_id = q.id
      LEFT JOIN queried_fragments qf ON qf.configuration_id = q.id
      LEFT JOIN queried_targets qt ON qt.checksum = k.checksum
      """;

  private static final String VALUES_CTE =
      """
      WITH configuration_values AS (
        SELECT 'Effective option' AS kind, o.option_set_name AS value_group,
               o.option_name AS value_name, o.option_value AS value,
               o.redacted AS redacted, o.ordinal AS ordinal
        FROM queried_configurations q
        JOIN queried_configuration_options o ON o.configuration_id = q.id
        WHERE q.checksum = ?
        UNION ALL
        SELECT DISTINCT 'Make variable', 'BEP', mv.name, mv.value, 0, 0
        FROM configurations c
        JOIN configuration_make_variables mv ON mv.configuration_id = c.id
        WHERE c.bep_id = ?
      )
      """;

  private static final String DIFFERENCES_CTE =
      """
      WITH baseline AS (
        SELECT o.configuration_id, o.option_set_name, o.option_name, o.ordinal,
               o.option_value, o.redacted
        FROM queried_configurations q
        JOIN queried_configuration_options o ON o.configuration_id = q.id
        WHERE q.checksum = ?
      ), candidate AS (
        SELECT o.configuration_id, o.option_set_name, o.option_name, o.ordinal,
               o.option_value, o.redacted
        FROM queried_configurations q
        JOIN queried_configuration_options o ON o.configuration_id = q.id
        WHERE q.checksum = ?
      ), differences AS (
        SELECT b.option_set_name, b.option_name, b.ordinal,
               b.option_value AS baseline_value, b.redacted AS baseline_redacted,
               c.option_value AS candidate_value, coalesce(c.redacted, 0) AS candidate_redacted,
               1 AS baseline_present,
               CASE WHEN c.configuration_id IS NULL THEN 0 ELSE 1 END AS candidate_present
        FROM baseline b
        LEFT JOIN candidate c
          ON c.option_set_name = b.option_set_name
         AND c.option_name = b.option_name
        WHERE c.configuration_id IS NULL OR b.redacted = 1 OR c.redacted = 1
           OR b.option_value IS NOT c.option_value
        UNION ALL
        SELECT c.option_set_name, c.option_name, c.ordinal,
               NULL, 0, c.option_value, c.redacted, 0, 1
        FROM candidate c
        LEFT JOIN baseline b
          ON b.option_set_name = c.option_set_name
         AND b.option_name = c.option_name
        WHERE b.configuration_id IS NULL
      )
      """;

  private final Connection connection;

  public ConfigurationQueries(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  /** Exact number of distinct checksums reported by either BEP or cquery. */
  public long count() throws SQLException {
    String sql = SUMMARY_CTES + "SELECT count(*) FROM configuration_keys";
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /** A bounded page, ordered by the stable checksum identity. */
  public List<Summary> page(long offset, int limit) throws SQLException {
    pageArguments(offset, limit);
    String sql =
        SUMMARY_CTES
            + "SELECT "
            + SUMMARY_COLUMNS
            + SUMMARY_FROM
            + " ORDER BY k.checksum LIMIT ? OFFSET ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      statement.setLong(2, offset);
      List<Summary> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(summary(rows));
        }
      }
      return values;
    }
  }

  public Optional<Summary> summary(String checksum) throws SQLException {
    Objects.requireNonNull(checksum, "checksum");
    String sql =
        SUMMARY_CTES + "SELECT " + SUMMARY_COLUMNS + SUMMARY_FROM + " WHERE k.checksum = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, checksum);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(summary(rows)) : Optional.empty();
      }
    }
  }

  /** Zero-based row position under the checksum ordering used by {@link #page}. */
  public OptionalLong position(String checksum) throws SQLException {
    Objects.requireNonNull(checksum, "checksum");
    String sql =
        SUMMARY_CTES
            + "SELECT (SELECT count(*) FROM configuration_keys smaller"
            + " WHERE smaller.checksum < wanted.checksum)"
            + " FROM configuration_keys wanted WHERE wanted.checksum = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, checksum);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  /** The cquery source supplying option details, when cquery was attempted. */
  public Optional<Source> source() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT state, configuration_match, mismatch_detail, error_excerpt"
                    + " FROM graph_sources WHERE kind = 'CONFIGURED_TARGETS'");
        ResultSet rows = statement.executeQuery()) {
      return rows.next()
          ? Optional.of(
              new Source(
                  rows.getString(1),
                  rows.getString(2),
                  Optional.ofNullable(rows.getString(3)),
                  Optional.ofNullable(rows.getString(4))))
          : Optional.empty();
    }
  }

  /** Exact number of details (effective options plus BEP make variables). */
  public long valueCount(String checksum) throws SQLException {
    Objects.requireNonNull(checksum, "checksum");
    try (PreparedStatement statement =
        connection.prepareStatement(VALUES_CTE + "SELECT count(*) FROM configuration_values")) {
      statement.setString(1, checksum);
      statement.setString(2, checksum);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /** A bounded page of one configuration's effective values. */
  public List<Value> values(String checksum, long offset, int limit) throws SQLException {
    Objects.requireNonNull(checksum, "checksum");
    pageArguments(offset, limit);
    String sql =
        VALUES_CTE
            + "SELECT kind, value_group, value_name, value, redacted"
            + " FROM configuration_values"
            + " ORDER BY kind, value_group, value_name, ordinal LIMIT ? OFFSET ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, checksum);
      statement.setString(2, checksum);
      statement.setInt(3, limit);
      statement.setLong(4, offset);
      List<Value> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(
              new Value(
                  rows.getString(1),
                  rows.getString(2),
                  displayName(rows.getString(3)),
                  Optional.ofNullable(rows.getString(4)),
                  rows.getInt(5) != 0));
        }
      }
      return values;
    }
  }

  /** Exact effective-option difference count for two configurations. */
  public long differenceCount(String baseline, String candidate) throws SQLException {
    try (PreparedStatement statement =
            comparisonStatement(
                DIFFERENCES_CTE + "SELECT count(*) FROM differences", baseline, candidate);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /** A bounded page of effective-option differences. */
  public List<Difference> differences(String baseline, String candidate, long offset, int limit)
      throws SQLException {
    pageArguments(offset, limit);
    String sql =
        DIFFERENCES_CTE
            + "SELECT option_set_name, option_name, baseline_value, baseline_redacted,"
            + " candidate_value, candidate_redacted, baseline_present, candidate_present"
            + " FROM differences ORDER BY option_set_name, option_name, ordinal"
            + " LIMIT ? OFFSET ?";
    try (PreparedStatement statement = comparisonStatement(sql, baseline, candidate)) {
      statement.setInt(3, limit);
      statement.setLong(4, offset);
      List<Difference> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(
              new Difference(
                  rows.getString(1),
                  displayName(rows.getString(2)),
                  Optional.ofNullable(rows.getString(3)),
                  rows.getInt(4) != 0,
                  Optional.ofNullable(rows.getString(5)),
                  rows.getInt(6) != 0,
                  rows.getInt(7) != 0,
                  rows.getInt(8) != 0));
        }
      }
      return values;
    }
  }

  private PreparedStatement comparisonStatement(String sql, String baseline, String candidate)
      throws SQLException {
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(candidate, "candidate");
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setString(1, baseline);
    statement.setString(2, candidate);
    return statement;
  }

  private static Summary summary(ResultSet rows) throws SQLException {
    return new Summary(
        rows.getString(1),
        Optional.ofNullable(rows.getString(2)),
        Optional.ofNullable(rows.getString(3)),
        Optional.ofNullable(rows.getString(4)),
        optionalBoolean(rows, 5),
        rows.getInt(6) != 0,
        rows.getInt(7) != 0,
        rows.getInt(8) != 0,
        rows.getInt(9) != 0,
        rows.getLong(10),
        rows.getLong(11),
        rows.getLong(12),
        rows.getLong(13),
        rows.getLong(14),
        rows.getLong(15),
        rows.getLong(16));
  }

  private static Optional<Boolean> optionalBoolean(ResultSet rows, int column) throws SQLException {
    int value = rows.getInt(column);
    return rows.wasNull() ? Optional.empty() : Optional.of(value != 0);
  }

  private static void pageArguments(long offset, int limit) {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative: " + offset);
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
  }

  private static String displayName(String name) {
    return name == null || name.isEmpty() ? "(unnamed option)" : name;
  }

  /** One checksum and what each captured source knows about it. */
  public record Summary(
      String checksum,
      Optional<String> mnemonic,
      Optional<String> platform,
      Optional<String> cpu,
      Optional<Boolean> tool,
      boolean bepReported,
      boolean bepDeclared,
      boolean queryReported,
      boolean optionsAvailable,
      long bepTargets,
      long executedActions,
      long queriedTargets,
      long fragments,
      long optionSets,
      long options,
      long makeVariables) {
    public Summary {
      Objects.requireNonNull(checksum, "checksum");
      Objects.requireNonNull(mnemonic, "mnemonic");
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(cpu, "cpu");
      Objects.requireNonNull(tool, "tool");
    }
  }

  /** One configuration value and whether import withheld it. */
  public record Value(
      String kind, String group, String name, Optional<String> value, boolean withheld) {
    public Value {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(group, "group");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }

  /** One effective option that is different or unavailable on either side. */
  public record Difference(
      String group,
      String name,
      Optional<String> baselineValue,
      boolean baselineWithheld,
      Optional<String> candidateValue,
      boolean candidateWithheld,
      boolean baselinePresent,
      boolean candidatePresent) {
    public Difference {
      Objects.requireNonNull(group, "group");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(baselineValue, "baselineValue");
      Objects.requireNonNull(candidateValue, "candidateValue");
    }

    public String change() {
      if (baselineWithheld || candidateWithheld) {
        return "Unknown (withheld)";
      }
      if (!baselinePresent) {
        return "Only candidate";
      }
      if (!candidatePresent) {
        return "Only baseline";
      }
      return "Changed";
    }
  }

  /** Honest status of the cquery source behind the details. */
  public record Source(
      String state,
      String configurationMatch,
      Optional<String> mismatchDetail,
      Optional<String> error) {
    public Source {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(configurationMatch, "configurationMatch");
      Objects.requireNonNull(mismatchDetail, "mismatchDetail");
      Objects.requireNonNull(error, "error");
    }
  }
}

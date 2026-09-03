package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TargetOutcome;
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
 * The targets tree's read path.
 *
 * <h2>Two levels, loaded separately</h2>
 *
 * <p>A tree that loaded every target to build its package nodes would hold the whole build in
 * memory to draw the collapsed view. So packages come from one grouped query that returns a row per
 * package, and a package's targets are fetched when it is expanded.
 *
 * <p>The package is derived from the label rather than stored, because Bazel never sends it: it is
 * the text before the last colon. The derivation handles {@code @@repo//pkg:target} as well as
 * {@code //pkg:target} — an expression anchored on {@code //} would collapse every external
 * repository into one unnamed group.
 *
 * <h2>One row per (target, configuration)</h2>
 *
 * <p>A label built for both the target and the exec platform is two rows, and the tree shows them
 * as two, because their actions and outputs are different. A target that was configured and never
 * completed is also a row — with no configuration and no outcome — because a build interrupted
 * during analysis consists entirely of those, and a tree that required a completion would show such
 * a build as empty.
 */
public final class TargetQueries implements AutoCloseable {

  /**
   * The package expression. {@code instr} finds the first colon, which is the only one a label can
   * contain, so this is the package for both label forms. A label with no colon — which the wire
   * does not produce but a future one might — keeps the whole string rather than becoming empty.
   */
  private static final String PACKAGE_EXPR =
      "CASE WHEN instr(l.value, ':') > 0"
          + " THEN substr(l.value, 1, instr(l.value, ':') - 1) ELSE l.value END";

  private static final String COLUMNS =
      "t.id, l.value, t.aspect, t.target_kind, t.test_size, t.outcome,"
          + " c.bep_id, ct.outcome, ct.id, t.bep_event_id";

  private static final String FROM =
      " FROM targets t"
          + " JOIN labels l ON l.id = t.label_id"
          + " LEFT JOIN configured_targets ct ON ct.target_id = t.id"
          + " LEFT JOIN configurations c ON c.id = ct.configuration_id";

  private static final String PACKAGES_SELECT =
      "SELECT "
          + PACKAGE_EXPR
          + " AS pkg, COUNT(*),"
          + " SUM(CASE WHEN ct.outcome = 'FAILED' THEN 1 ELSE 0 END),"
          + " SUM(CASE WHEN ct.id IS NULL THEN 1 ELSE 0 END)"
          + FROM;

  private static final String PACKAGES = PACKAGES_SELECT + " GROUP BY pkg ORDER BY pkg ASC";

  private static final String PACKAGES_FILTERED =
      PACKAGES_SELECT + " WHERE l.value LIKE ? ESCAPE '\\' GROUP BY pkg ORDER BY pkg ASC";

  private static final String IN_PACKAGE =
      "SELECT "
          + COLUMNS
          + FROM
          + " WHERE "
          + PACKAGE_EXPR
          + " = ?"
          + " ORDER BY l.value ASC, t.aspect ASC, c.bep_id ASC";

  private static final String IN_PACKAGE_FILTERED =
      "SELECT "
          + COLUMNS
          + FROM
          + " WHERE "
          + PACKAGE_EXPR
          + " = ? AND l.value LIKE ? ESCAPE '\\'"
          + " ORDER BY l.value ASC, t.aspect ASC, c.bep_id ASC";

  private static final String BY_LABEL =
      "SELECT " + COLUMNS + FROM + " WHERE l.value = ? ORDER BY t.aspect ASC, c.bep_id ASC";

  private static final String TOP_LEVEL_LABEL_FROM =
      " FROM targets t JOIN labels l ON l.id = t.label_id";

  private static final String TOP_LEVEL_LABEL_COUNT =
      "SELECT COUNT(DISTINCT t.label_id)" + TOP_LEVEL_LABEL_FROM;

  private static final String TOP_LEVEL_LABEL_COUNT_FILTERED =
      TOP_LEVEL_LABEL_COUNT + " WHERE l.value LIKE ? ESCAPE '\\'";

  private static final String TOP_LEVEL_LABELS_FIRST =
      "SELECT l.value" + TOP_LEVEL_LABEL_FROM + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String TOP_LEVEL_LABELS_FIRST_FILTERED =
      "SELECT l.value"
          + TOP_LEVEL_LABEL_FROM
          + " WHERE l.value LIKE ? ESCAPE '\\'"
          + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String TOP_LEVEL_LABELS_AFTER =
      "SELECT l.value"
          + TOP_LEVEL_LABEL_FROM
          + " WHERE l.value > ? GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String TOP_LEVEL_LABELS_AFTER_FILTERED =
      "SELECT l.value"
          + TOP_LEVEL_LABEL_FROM
          + " WHERE l.value > ? AND l.value LIKE ? ESCAPE '\\'"
          + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String CONFIGURED_FROM =
      " FROM configured_target_nodes n JOIN labels l ON l.id = n.label_id";

  private static final String LABEL_SUMMARY_COLUMNS =
      "l.value, COUNT(DISTINCT COALESCE(n.configuration_checksum, '')), COUNT(*)";

  private static final String LABELS_FIRST =
      "SELECT "
          + LABEL_SUMMARY_COLUMNS
          + CONFIGURED_FROM
          + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String LABELS_FIRST_FILTERED =
      "SELECT "
          + LABEL_SUMMARY_COLUMNS
          + CONFIGURED_FROM
          + " WHERE l.value LIKE ? ESCAPE '\\'"
          + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String LABELS_AFTER =
      "SELECT "
          + LABEL_SUMMARY_COLUMNS
          + CONFIGURED_FROM
          + " WHERE l.value > ? GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String LABELS_AFTER_FILTERED =
      "SELECT "
          + LABEL_SUMMARY_COLUMNS
          + CONFIGURED_FROM
          + " WHERE l.value > ? AND l.value LIKE ? ESCAPE '\\'"
          + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";

  private static final String LABEL_COUNT = "SELECT COUNT(DISTINCT n.label_id)" + CONFIGURED_FROM;

  private static final String LABEL_COUNT_FILTERED =
      LABEL_COUNT + " WHERE l.value LIKE ? ESCAPE '\\'";

  private static final String CONFIGURED_BY_LABEL =
      "SELECT n.id, l.value, n.configuration_checksum, n.rule_class"
          + CONFIGURED_FROM
          + " WHERE l.value = ?"
          + " ORDER BY COALESCE(n.configuration_checksum, ''), n.id";

  private static final String CONFIGURED_SOURCE =
      "SELECT state, configuration_match, mismatch_detail, error_excerpt"
          + " FROM graph_sources WHERE kind = 'CONFIGURED_TARGETS'";

  private static final String BY_ID = "SELECT " + COLUMNS + FROM + " WHERE t.id = ?";

  private static final String TAGS =
      "SELECT tag, from_event FROM target_tags WHERE target_id = ?"
          + " ORDER BY from_event ASC, tag ASC";

  private static final String OUTPUT_GROUPS =
      "SELECT g.name, g.incomplete, d.bep_id FROM target_output_groups g"
          + " LEFT JOIN depsets d ON d.id = g.root_depset_id"
          + " WHERE g.configured_target_id = ? ORDER BY g.ordinal ASC";

  private final Connection connection;

  public TargetQueries(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  /** Every package with a target in it, alphabetically. */
  public List<PackageSummary> packages() throws SQLException {
    List<PackageSummary> packages = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(PACKAGES);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        packages.add(
            new PackageSummary(
                rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getLong(4)));
      }
    }
    return packages;
  }

  /** Packages containing a top-level target whose full label contains {@code labelText}. */
  public List<PackageSummary> packages(String labelText) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return packages();
    }
    List<PackageSummary> packages = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(PACKAGES_FILTERED)) {
      statement.setString(1, contains(labelText));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          packages.add(
              new PackageSummary(
                  rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getLong(4)));
        }
      }
    }
    return packages;
  }

  public List<TargetRow> inPackage(String packagePath) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(IN_PACKAGE)) {
      statement.setString(1, packagePath);
      return readRows(statement);
    }
  }

  /** Matching target rows in one package, using the same literal label filter as its summary. */
  public List<TargetRow> inPackage(String packagePath, String labelText) throws SQLException {
    Objects.requireNonNull(packagePath, "packagePath");
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return inPackage(packagePath);
    }
    try (PreparedStatement statement = connection.prepareStatement(IN_PACKAGE_FILTERED)) {
      statement.setString(1, packagePath);
      statement.setString(2, contains(labelText));
      return readRows(statement);
    }
  }

  public List<TargetRow> byLabel(String label) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BY_LABEL)) {
      statement.setString(1, label);
      return readRows(statement);
    }
  }

  /** Exact distinct-label count in the build event stream's top-level target set. */
  public long topLevelLabelCount() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TOP_LEVEL_LABEL_COUNT);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /** Exact distinct top-level label count after a literal contains filter. */
  public long topLevelLabelCount(String labelText) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return topLevelLabelCount();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(TOP_LEVEL_LABEL_COUNT_FILTERED)) {
      statement.setString(1, contains(labelText));
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /** First alphabetical page of top-level labels. */
  public List<String> firstTopLevelLabelPage(int limit) throws SQLException {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    try (PreparedStatement statement = connection.prepareStatement(TOP_LEVEL_LABELS_FIRST)) {
      statement.setInt(1, limit);
      return readLabels(statement);
    }
  }

  /** First alphabetical page of top-level labels matching a literal contains filter. */
  public List<String> firstTopLevelLabelPage(String labelText, int limit) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return firstTopLevelLabelPage(limit);
    }
    requirePositiveLimit(limit);
    try (PreparedStatement statement =
        connection.prepareStatement(TOP_LEVEL_LABELS_FIRST_FILTERED)) {
      statement.setString(1, contains(labelText));
      statement.setInt(2, limit);
      return readLabels(statement);
    }
  }

  /** Alphabetical page after an exact top-level label boundary. */
  public List<String> topLevelLabelPageAfter(String label, int limit) throws SQLException {
    Objects.requireNonNull(label, "label");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    try (PreparedStatement statement = connection.prepareStatement(TOP_LEVEL_LABELS_AFTER)) {
      statement.setString(1, label);
      statement.setInt(2, limit);
      return readLabels(statement);
    }
  }

  /** Alphabetical filtered page after an exact top-level label boundary. */
  public List<String> topLevelLabelPageAfter(String labelText, String label, int limit)
      throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    Objects.requireNonNull(label, "label");
    if (labelText.isEmpty()) {
      return topLevelLabelPageAfter(label, limit);
    }
    requirePositiveLimit(limit);
    try (PreparedStatement statement =
        connection.prepareStatement(TOP_LEVEL_LABELS_AFTER_FILTERED)) {
      statement.setString(1, label);
      statement.setString(2, contains(labelText));
      statement.setInt(3, limit);
      return readLabels(statement);
    }
  }

  /** Exact number of distinct fully-qualified target labels. */
  public long labelCount() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LABEL_COUNT);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /** Exact distinct configured-target label count after a literal contains filter. */
  public long labelCount(String labelText) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return labelCount();
    }
    try (PreparedStatement statement = connection.prepareStatement(LABEL_COUNT_FILTERED)) {
      statement.setString(1, contains(labelText));
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /** First page of distinct labels for the All Targets explorer. */
  public List<LabelSummary> firstLabelPage(int limit) throws SQLException {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    try (PreparedStatement statement = connection.prepareStatement(LABELS_FIRST)) {
      statement.setInt(1, limit);
      return readLabelSummaries(statement);
    }
  }

  /** First configured-target label page matching a literal contains filter. */
  public List<LabelSummary> firstLabelPage(String labelText, int limit) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    if (labelText.isEmpty()) {
      return firstLabelPage(limit);
    }
    requirePositiveLimit(limit);
    try (PreparedStatement statement = connection.prepareStatement(LABELS_FIRST_FILTERED)) {
      statement.setString(1, contains(labelText));
      statement.setInt(2, limit);
      return readLabelSummaries(statement);
    }
  }

  /** Distinct label page after the previous page's exact label boundary. */
  public List<LabelSummary> labelPageAfter(String label, int limit) throws SQLException {
    Objects.requireNonNull(label, "label");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
    try (PreparedStatement statement = connection.prepareStatement(LABELS_AFTER)) {
      statement.setString(1, label);
      statement.setInt(2, limit);
      return readLabelSummaries(statement);
    }
  }

  /** Filtered configured-target label page after the previous page's exact boundary. */
  public List<LabelSummary> labelPageAfter(String labelText, String label, int limit)
      throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    Objects.requireNonNull(label, "label");
    if (labelText.isEmpty()) {
      return labelPageAfter(label, limit);
    }
    requirePositiveLimit(limit);
    try (PreparedStatement statement = connection.prepareStatement(LABELS_AFTER_FILTERED)) {
      statement.setString(1, label);
      statement.setString(2, contains(labelText));
      statement.setInt(3, limit);
      return readLabelSummaries(statement);
    }
  }

  /** Configured-target query rows for one exact label. */
  public List<ConfiguredTarget> configuredByLabel(String label) throws SQLException {
    Objects.requireNonNull(label, "label");
    List<ConfiguredTarget> configured = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(CONFIGURED_BY_LABEL)) {
      statement.setString(1, label);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          configured.add(
              new ConfiguredTarget(
                  rows.getLong(1),
                  rows.getString(2),
                  Optional.ofNullable(rows.getString(3)),
                  Optional.ofNullable(rows.getString(4))));
        }
      }
    }
    return configured;
  }

  /** The cquery import whose rows All Targets lists, when one was attempted. */
  public Optional<ConfiguredSource> configuredSource() throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CONFIGURED_SOURCE);
        ResultSet rows = statement.executeQuery()) {
      return rows.next()
          ? Optional.of(
              new ConfiguredSource(
                  rows.getString(1),
                  rows.getString(2),
                  Optional.ofNullable(rows.getString(3)),
                  Optional.ofNullable(rows.getString(4))))
          : Optional.empty();
    }
  }

  public Optional<TargetRow> byId(long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(BY_ID)) {
      statement.setLong(1, id);
      List<TargetRow> rows = readRows(statement);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
  }

  /**
   * A target's tags, each with the event that supplied it.
   *
   * <p>From Bazel 7.6.1 the completion event appends synthetic tags — the size, the timeout, {@code
   * noflaky} — that the user never wrote. Returning them merged would show {@code small} as though
   * it were in the BUILD file.
   */
  public List<Tag> tags(long targetId) throws SQLException {
    List<Tag> tags = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(TAGS)) {
      statement.setLong(1, targetId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tags.add(new Tag(rows.getString(1), rows.getString(2)));
        }
      }
    }
    return tags;
  }

  /**
   * A configured target's output groups.
   *
   * <p>{@code incomplete} is carried through because a group marked incomplete means any roll-up
   * under it under-reports, and a total that silently under-reports is worse than no total.
   */
  public List<OutputGroup> outputGroups(long configuredTargetId) throws SQLException {
    List<OutputGroup> groups = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(OUTPUT_GROUPS)) {
      statement.setLong(1, configuredTargetId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          groups.add(
              new OutputGroup(
                  rows.getString(1), rows.getInt(2) != 0, Optional.ofNullable(rows.getString(3))));
        }
      }
    }
    return groups;
  }

  /**
   * A package and what is in it.
   *
   * @param notCompleted targets configured but never completed — the whole of a build interrupted
   *     during analysis
   */
  public record PackageSummary(String path, long targets, long failed, long notCompleted) {}

  /** One distinct label and the configured variants stored beneath it. */
  public record LabelSummary(String label, long configurations, long rows) {
    public LabelSummary {
      Objects.requireNonNull(label, "label");
    }
  }

  /** One label/configuration pair declared by the imported cquery output. */
  public record ConfiguredTarget(
      long id, String label, Optional<String> configuration, Optional<String> ruleClass) {
    public ConfiguredTarget {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(configuration, "configuration");
      Objects.requireNonNull(ruleClass, "ruleClass");
    }
  }

  /** Honest status of the cquery source behind the configured target rows. */
  public record ConfiguredSource(
      String state,
      String configurationMatch,
      Optional<String> mismatchDetail,
      Optional<String> error) {
    public ConfiguredSource {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(configurationMatch, "configurationMatch");
      Objects.requireNonNull(mismatchDetail, "mismatchDetail");
      Objects.requireNonNull(error, "error");
    }
  }

  /** A tag and the event that supplied it. */
  public record Tag(String tag, String fromEvent) {}

  /** An output group and the file set at its root. */
  public record OutputGroup(String name, boolean incomplete, Optional<String> rootDepsetId) {}

  private static void requirePositiveLimit(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, got " + limit);
    }
  }

  /** Makes SQL wildcards literal so the filter means exactly what its label says. */
  private static String contains(String text) {
    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static List<TargetRow> readRows(PreparedStatement statement) throws SQLException {
    List<TargetRow> rows = new ArrayList<>();
    try (ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        rows.add(
            new TargetRow(
                result.getLong(1),
                result.getString(2),
                aspectOf(result.getString(3)),
                text(result, 4),
                text(result, 5),
                outcomeOf(result.getString(6)).orElse(TargetOutcome.CONFIGURED),
                text(result, 7),
                outcomeOf(result.getString(8)),
                number(result, 9),
                number(result, 10)));
      }
    }
    return rows;
  }

  private static List<LabelSummary> readLabelSummaries(PreparedStatement statement)
      throws SQLException {
    List<LabelSummary> summaries = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        summaries.add(new LabelSummary(rows.getString(1), rows.getLong(2), rows.getLong(3)));
      }
    }
    return summaries;
  }

  private static List<String> readLabels(PreparedStatement statement) throws SQLException {
    List<String> labels = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        labels.add(rows.getString(1));
      }
    }
    return labels;
  }

  /** {@code ''} is the storage spelling of "not an aspect"; the model says absent. */
  private static Optional<String> aspectOf(String stored) {
    return stored == null || stored.isEmpty() ? Optional.empty() : Optional.of(stored);
  }

  /**
   * A stored outcome name, or empty when the column was NULL.
   *
   * <p>An unrecognised value also becomes empty rather than being forced into the nearest known
   * state: a newer schema's extra outcome must not be reported as a failure.
   */
  private static Optional<TargetOutcome> outcomeOf(String stored) {
    if (stored == null) {
      return Optional.empty();
    }
    for (TargetOutcome outcome : TargetOutcome.values()) {
      if (outcome.name().equals(stored)) {
        return Optional.of(outcome);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> text(ResultSet result, int index) throws SQLException {
    String value = result.getString(index);
    return value == null ? Optional.empty() : Optional.of(value);
  }

  private static OptionalLong number(ResultSet result, int index) throws SQLException {
    long value = result.getLong(index);
    return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }
}

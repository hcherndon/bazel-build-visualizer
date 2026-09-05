package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TargetOutcome;
import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.SqlCancellation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

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

  private static final String TOP_LEVEL_LABEL_FROM =
      " FROM targets t JOIN labels l ON l.id = t.label_id";

  private static final String TOP_LEVEL_LABEL_COUNT =
      "SELECT COUNT(DISTINCT t.label_id)" + TOP_LEVEL_LABEL_FROM;

  private static final String CONFIGURED_FROM =
      " FROM configured_target_nodes n JOIN labels l ON l.id = n.label_id";

  private static final String LABEL_SUMMARY_COLUMNS =
      "l.value, COUNT(DISTINCT n.configuration_checksum)"
          + " + MAX(CASE WHEN n.configuration_checksum IS NULL THEN 1 ELSE 0 END), COUNT(*)";

  private static final String LABEL_COUNT = "SELECT COUNT(DISTINCT n.label_id)" + CONFIGURED_FROM;

  private static final String CONFIGURED_SOURCE =
      "SELECT state, configuration_match, mismatch_detail, error_excerpt"
          + " FROM graph_sources WHERE kind = 'CONFIGURED_TARGETS'";

  private static final String BY_ID = "SELECT " + COLUMNS + FROM + " WHERE t.id = ?";

  private final Connection connection;
  private volatile Statement running;
  private final AtomicLong cancellationEpoch = new AtomicLong();
  private final ThreadLocal<Long> requestEpoch = new ThreadLocal<>();

  public TargetQueries(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  /** Invalidates the current request immediately and cancels its statement off the caller. */
  public void cancel() {
    cancellationEpoch.incrementAndGet();
    Statement statement = running;
    SqlCancellation.request(statement, "bbv-target-query-cancel");
  }

  /** A counted, alphabetical package page from one read snapshot. */
  public CountedPage<PackageSummary, String> packagePage(
      String labelText, Optional<String> afterPath, int limit) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    Objects.requireNonNull(afterPath, "afterPath");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          String grouped =
              PACKAGES_SELECT
                  + (labelText.isEmpty() ? "" : " WHERE l.value LIKE ? ESCAPE '\\'")
                  + " GROUP BY pkg";
          long total =
              scalar("SELECT COUNT(*) FROM (" + grouped + ")", labelText, Optional.empty());
          String sql =
              "SELECT "
                  + PACKAGE_EXPR
                  + " AS pkg, COUNT(*),"
                  + " SUM(CASE WHEN ct.outcome = 'FAILED' THEN 1 ELSE 0 END),"
                  + " SUM(CASE WHEN ct.id IS NULL THEN 1 ELSE 0 END)"
                  + FROM
                  + (labelText.isEmpty() ? " WHERE 1=1" : " WHERE l.value LIKE ? ESCAPE '\\'")
                  + (afterPath.isPresent() ? " AND " + PACKAGE_EXPR + " > ?" : "")
                  + " GROUP BY pkg ORDER BY pkg ASC LIMIT ?";
          List<PackageSummary> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = bindLabelFilter(statement, 1, labelText);
              if (afterPath.isPresent()) {
                statement.setString(parameter++, afterPath.orElseThrow());
              }
              statement.setInt(parameter, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  rows.add(
                      new PackageSummary(
                          result.getString(1),
                          result.getLong(2),
                          result.getLong(3),
                          result.getLong(4)));
                }
              }
            } finally {
              running = null;
            }
          }
          Optional<String> last =
              rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast().path());
          long remaining =
              last.isEmpty()
                  ? 0
                  : scalar("SELECT COUNT(*) FROM (" + grouped + ") WHERE pkg > ?", labelText, last);
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  /** A counted package-child page, ordered by every stable row identity component. */
  public CountedPage<TargetRow, TargetAnchor> targetsInPackagePage(
      String packagePath, String labelText, Optional<TargetAnchor> after, int limit)
      throws SQLException {
    Objects.requireNonNull(packagePath, "packagePath");
    Objects.requireNonNull(labelText, "labelText");
    return targetPage(
        PACKAGE_EXPR + " = ?" + (labelText.isEmpty() ? "" : " AND l.value LIKE ? ESCAPE '\\'"),
        statement -> {
          statement.setString(1, packagePath);
          return bindLabelFilter(statement, 2, labelText);
        },
        after,
        limit);
  }

  /** A counted exact-label page; cross-view reveal never materializes every variant. */
  public CountedPage<TargetRow, TargetAnchor> targetsByLabelPage(
      String label, Optional<TargetAnchor> after, int limit) throws SQLException {
    Objects.requireNonNull(label, "label");
    return targetPage(
        "l.value = ?",
        statement -> {
          statement.setString(1, label);
          return 2;
        },
        after,
        limit);
  }

  /** One counted top-level-label page; count and rows share the live-capture snapshot. */
  public CountedPage<String, String> topLevelLabelPage(
      String labelText, Optional<String> after, int limit) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    Objects.requireNonNull(after, "after");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          String where = labelText.isEmpty() ? "" : " WHERE l.value LIKE ? ESCAPE '\\'";
          long total = scalar(TOP_LEVEL_LABEL_COUNT + where, labelText, Optional.empty());
          String sql =
              "SELECT l.value"
                  + TOP_LEVEL_LABEL_FROM
                  + (labelText.isEmpty() ? " WHERE 1=1" : where)
                  + (after.isPresent() ? " AND l.value > ?" : "")
                  + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";
          List<String> rows = readStringPage(sql, labelText, after, limit);
          Optional<String> last = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast());
          long remaining =
              last.isEmpty()
                  ? 0
                  : scalar(TOP_LEVEL_LABEL_COUNT + where + afterClause(where), labelText, last);
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  /** One counted All Targets label page; existing flat-label paging remains bounded. */
  public CountedPage<LabelSummary, String> labelPage(
      String labelText, Optional<String> after, int limit) throws SQLException {
    Objects.requireNonNull(labelText, "labelText");
    Objects.requireNonNull(after, "after");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          String where = labelText.isEmpty() ? "" : " WHERE l.value LIKE ? ESCAPE '\\'";
          long total = scalar(LABEL_COUNT + where, labelText, Optional.empty());
          String sql =
              "SELECT "
                  + LABEL_SUMMARY_COLUMNS
                  + CONFIGURED_FROM
                  + (labelText.isEmpty() ? " WHERE 1=1" : where)
                  + (after.isPresent() ? " AND l.value > ?" : "")
                  + " GROUP BY l.value ORDER BY l.value ASC LIMIT ?";
          List<LabelSummary> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = bindLabelFilter(statement, 1, labelText);
              if (after.isPresent()) {
                statement.setString(parameter++, after.orElseThrow());
              }
              statement.setInt(parameter, limit);
              rows = readLabelSummaries(statement);
            } finally {
              running = null;
            }
          }
          Optional<String> last =
              rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast().label());
          long remaining =
              last.isEmpty()
                  ? 0
                  : scalar(LABEL_COUNT + where + afterClause(where), labelText, last);
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  /** Configuration-checksum groups beneath one exact label. */
  public CountedPage<ConfigurationGroup, ConfigurationAnchor> configurationGroupPage(
      String label, Optional<ConfigurationAnchor> after, int limit) throws SQLException {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(after, "after");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          String groups =
              "SELECT n.configuration_checksum AS checksum, COUNT(*) AS variants"
                  + CONFIGURED_FROM
                  + " WHERE l.value = ? GROUP BY n.configuration_checksum";
          long total = scalarLabel("SELECT COUNT(*) FROM (" + groups + ")", label);
          String seek = configurationGroupSeek(after);
          String sql =
              "SELECT checksum, variants FROM ("
                  + groups
                  + ")"
                  + seek
                  + " ORDER BY checksum IS NOT NULL ASC, checksum ASC LIMIT ?";
          List<ConfigurationGroup> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = 1;
              statement.setString(parameter++, label);
              if (after.isPresent() && after.orElseThrow().configuration().isPresent()) {
                statement.setString(parameter++, after.orElseThrow().configuration().orElseThrow());
              }
              statement.setInt(parameter, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  rows.add(
                      new ConfigurationGroup(
                          Optional.ofNullable(result.getString(1)), result.getLong(2)));
                }
              }
            } finally {
              running = null;
            }
          }
          Optional<ConfigurationAnchor> last =
              rows.isEmpty()
                  ? Optional.empty()
                  : Optional.of(ConfigurationAnchor.of(rows.getLast().configuration()));
          long remaining =
              last.isEmpty()
                  ? 0
                  : scalarConfigurationGroupsAfter(groups, label, last.orElseThrow());
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  /** Stable configured-target variants beneath one label/checksum group. */
  public CountedPage<ConfiguredTarget, Long> configuredTargetPage(
      String label, Optional<String> configuration, OptionalLong afterId, int limit)
      throws SQLException {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(afterId, "afterId");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          String match =
              configuration.isPresent()
                  ? "n.configuration_checksum = ?"
                  : "n.configuration_checksum IS NULL";
          String base = CONFIGURED_FROM + " WHERE l.value = ? AND " + match;
          long total = scalarConfigured(base, label, configuration, OptionalLong.empty());
          String sql =
              "SELECT n.id, l.value, n.configuration_checksum, n.rule_class"
                  + base
                  + (afterId.isPresent() ? " AND n.id > ?" : "")
                  + " ORDER BY n.id ASC LIMIT ?";
          List<ConfiguredTarget> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = bindConfiguration(statement, label, configuration);
              if (afterId.isPresent()) {
                statement.setLong(parameter++, afterId.getAsLong());
              }
              statement.setInt(parameter, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  rows.add(
                      new ConfiguredTarget(
                          result.getLong(1),
                          result.getString(2),
                          Optional.ofNullable(result.getString(3)),
                          Optional.ofNullable(result.getString(4))));
                }
              }
            } finally {
              running = null;
            }
          }
          OptionalLong lastId =
              rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.getLast().id());
          OptionalLong boundary = lastId.isPresent() ? lastId : afterId;
          long remaining =
              boundary.isEmpty() ? 0 : scalarConfigured(base, label, configuration, boundary);
          Optional<Long> next = remaining == 0 ? Optional.empty() : Optional.of(lastId.getAsLong());
          return new CountedPage<>(rows, total, remaining, next);
        });
  }

  /** Stable target-tag page by source and literal tag. */
  public CountedPage<Tag, TagAnchor> tagPage(long targetId, Optional<TagAnchor> after, int limit)
      throws SQLException {
    Objects.requireNonNull(after, "after");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          long total = scalarId("SELECT COUNT(*) FROM target_tags WHERE target_id = ?", targetId);
          String sql =
              "SELECT tag, from_event FROM target_tags WHERE target_id = ?"
                  + (after.isPresent() ? " AND (from_event, tag) > (?, ?)" : "")
                  + " ORDER BY from_event ASC, tag ASC LIMIT ?";
          List<Tag> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = 1;
              statement.setLong(parameter++, targetId);
              if (after.isPresent()) {
                statement.setString(parameter++, after.orElseThrow().fromEvent());
                statement.setString(parameter++, after.orElseThrow().tag());
              }
              statement.setInt(parameter, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  rows.add(new Tag(result.getString(1), result.getString(2)));
                }
              }
            } finally {
              running = null;
            }
          }
          Optional<TagAnchor> last =
              rows.isEmpty()
                  ? Optional.empty()
                  : Optional.of(new TagAnchor(rows.getLast().fromEvent(), rows.getLast().tag()));
          long remaining = last.isEmpty() ? 0 : scalarTagAfter(targetId, last.orElseThrow());
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  /** Stable output-group page by the producer's ordinal. */
  public CountedPage<OutputGroup, Long> outputGroupPage(
      long configuredTargetId, OptionalLong afterOrdinal, int limit) throws SQLException {
    Objects.requireNonNull(afterOrdinal, "afterOrdinal");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          long total =
              scalarId(
                  "SELECT COUNT(*) FROM target_output_groups WHERE configured_target_id = ?",
                  configuredTargetId);
          String sql =
              "SELECT g.name, g.incomplete, d.bep_id, g.ordinal"
                  + " FROM target_output_groups g LEFT JOIN depsets d ON d.id = g.root_depset_id"
                  + " WHERE g.configured_target_id = ?"
                  + (afterOrdinal.isPresent() ? " AND g.ordinal > ?" : "")
                  + " ORDER BY g.ordinal ASC LIMIT ?";
          List<OutputGroup> rows = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = 1;
              statement.setLong(parameter++, configuredTargetId);
              if (afterOrdinal.isPresent()) {
                statement.setLong(parameter++, afterOrdinal.getAsLong());
              }
              statement.setInt(parameter, limit);
              try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                  rows.add(
                      new OutputGroup(
                          result.getString(1),
                          result.getInt(2) != 0,
                          Optional.ofNullable(result.getString(3)),
                          result.getLong(4)));
                }
              }
            } finally {
              running = null;
            }
          }
          OptionalLong last =
              rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.getLast().ordinal());
          long remaining =
              last.isEmpty()
                  ? 0
                  : scalarIdAfter(
                      "SELECT COUNT(*) FROM target_output_groups"
                          + " WHERE configured_target_id = ? AND ordinal > ?",
                      configuredTargetId,
                      last.getAsLong());
          Optional<Long> next = remaining == 0 ? Optional.empty() : Optional.of(last.getAsLong());
          return new CountedPage<>(rows, total, remaining, next);
        });
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
   * A package and what is in it.
   *
   * @param notCompleted targets configured but never completed — the whole of a build interrupted
   *     during analysis
   */
  public record PackageSummary(String path, long targets, long failed, long notCompleted) {}

  /** Full stable seek key for one top-level target row. */
  public record TargetAnchor(
      String label,
      String aspect,
      Optional<String> configuration,
      long targetId,
      OptionalLong configuredTargetId) {
    public TargetAnchor {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(aspect, "aspect");
      Objects.requireNonNull(configuration, "configuration");
      Objects.requireNonNull(configuredTargetId, "configuredTargetId");
    }

    public static TargetAnchor of(TargetRow row) {
      return new TargetAnchor(
          row.label(),
          row.aspect().orElse(""),
          row.configurationId(),
          row.id(),
          row.configuredTargetId());
    }
  }

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

  /** One nullable configuration checksum and its exact configured-target row count. */
  public record ConfigurationGroup(Optional<String> configuration, long variants) {
    public ConfigurationGroup {
      Objects.requireNonNull(configuration, "configuration");
      if (variants < 1) {
        throw new IllegalArgumentException("variants must be positive: " + variants);
      }
    }
  }

  /** Stable configuration-group seek key, keeping NULL separate from the empty string. */
  public record ConfigurationAnchor(Optional<String> configuration) {
    public ConfigurationAnchor {
      Objects.requireNonNull(configuration, "configuration");
    }

    public static ConfigurationAnchor of(Optional<String> configuration) {
      Objects.requireNonNull(configuration, "configuration");
      return new ConfigurationAnchor(configuration);
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

  /** Stable source/tag seek key. */
  public record TagAnchor(String fromEvent, String tag) {
    public TagAnchor {
      Objects.requireNonNull(fromEvent, "fromEvent");
      Objects.requireNonNull(tag, "tag");
    }
  }

  /** An output group and the file set at its root. */
  public record OutputGroup(
      String name, boolean incomplete, Optional<String> rootDepsetId, long ordinal) {
    public OutputGroup {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(rootDepsetId, "rootDepsetId");
    }

    public OutputGroup(String name, boolean incomplete, Optional<String> rootDepsetId) {
      this(name, incomplete, rootDepsetId, 0);
    }
  }

  private CountedPage<TargetRow, TargetAnchor> targetPage(
      String baseWhere, StatementBinder binder, Optional<TargetAnchor> after, int limit)
      throws SQLException {
    Objects.requireNonNull(after, "after");
    requirePositiveLimit(limit);
    return snapshot(
        () -> {
          long total = scalarTargets(baseWhere, binder, Optional.empty());
          String sql =
              "SELECT "
                  + COLUMNS
                  + FROM
                  + " WHERE "
                  + baseWhere
                  + (after.isPresent() ? " AND " + targetAfterPredicate() : "")
                  + targetOrder()
                  + " LIMIT ?";
          List<TargetRow> rows;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            running = statement;
            try {
              checkCancellation();
              int parameter = binder.bind(statement);
              if (after.isPresent()) {
                parameter = bindTargetAnchor(statement, parameter, after.orElseThrow());
              }
              statement.setInt(parameter, limit);
              rows = readRows(statement);
            } finally {
              running = null;
            }
          }
          Optional<TargetAnchor> last =
              rows.isEmpty() ? Optional.empty() : Optional.of(TargetAnchor.of(rows.getLast()));
          Optional<TargetAnchor> boundary = last.isPresent() ? last : after;
          long remaining = boundary.isEmpty() ? 0 : scalarTargets(baseWhere, binder, boundary);
          return new CountedPage<>(
              rows, total, remaining, remaining == 0 ? Optional.empty() : last);
        });
  }

  private long scalarTargets(String baseWhere, StatementBinder binder, Optional<TargetAnchor> after)
      throws SQLException {
    String sql =
        "SELECT COUNT(*)"
            + FROM
            + " WHERE "
            + baseWhere
            + (after.isPresent() ? " AND " + targetAfterPredicate() : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        int parameter = binder.bind(statement);
        if (after.isPresent()) {
          bindTargetAnchor(statement, parameter, after.orElseThrow());
        }
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private static String targetAfterPredicate() {
    return "(l.value, t.aspect,"
        + " CASE WHEN c.bep_id IS NULL THEN 0 ELSE 1 END, COALESCE(c.bep_id, ''),"
        + " t.id, CASE WHEN ct.id IS NULL THEN 0 ELSE 1 END, COALESCE(ct.id, 0))"
        + " > (?, ?, ?, ?, ?, ?, ?)";
  }

  private static String targetOrder() {
    return " ORDER BY l.value ASC, t.aspect ASC,"
        + " CASE WHEN c.bep_id IS NULL THEN 0 ELSE 1 END ASC, c.bep_id ASC,"
        + " t.id ASC, CASE WHEN ct.id IS NULL THEN 0 ELSE 1 END ASC, ct.id ASC";
  }

  private static int bindTargetAnchor(
      PreparedStatement statement, int parameter, TargetAnchor anchor) throws SQLException {
    statement.setString(parameter++, anchor.label());
    statement.setString(parameter++, anchor.aspect());
    statement.setInt(parameter++, anchor.configuration().isPresent() ? 1 : 0);
    statement.setString(parameter++, anchor.configuration().orElse(""));
    statement.setLong(parameter++, anchor.targetId());
    statement.setInt(parameter++, anchor.configuredTargetId().isPresent() ? 1 : 0);
    statement.setLong(parameter++, anchor.configuredTargetId().orElse(0));
    return parameter;
  }

  private List<String> readStringPage(
      String sql, String labelText, Optional<String> after, int limit) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        int parameter = bindLabelFilter(statement, 1, labelText);
        if (after.isPresent()) {
          statement.setString(parameter++, after.orElseThrow());
        }
        statement.setInt(parameter, limit);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            rows.add(result.getString(1));
          }
        }
      } finally {
        running = null;
      }
    }
    return rows;
  }

  private long scalar(String sql, String labelText, Optional<String> after) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        int parameter = bindLabelFilter(statement, 1, labelText);
        if (after.isPresent()) {
          statement.setString(parameter, after.orElseThrow());
        }
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private static long scalar(PreparedStatement statement) throws SQLException {
    try (ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private long scalarLabel(String sql, String label) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        statement.setString(1, label);
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private long scalarConfigurationGroupsAfter(
      String groups, String label, ConfigurationAnchor anchor) throws SQLException {
    String sql =
        "SELECT COUNT(*) FROM (" + groups + ")" + configurationGroupSeek(Optional.of(anchor));
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        statement.setString(1, label);
        if (anchor.configuration().isPresent()) {
          statement.setString(2, anchor.configuration().orElseThrow());
        }
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private static String configurationGroupSeek(Optional<ConfigurationAnchor> after) {
    if (after.isEmpty()) {
      return "";
    }
    return after.orElseThrow().configuration().isPresent()
        ? " WHERE checksum > ?"
        : " WHERE checksum IS NOT NULL";
  }

  private long scalarConfigured(
      String base, String label, Optional<String> configuration, OptionalLong afterId)
      throws SQLException {
    String sql = "SELECT COUNT(*)" + base + (afterId.isPresent() ? " AND n.id > ?" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        int parameter = bindConfiguration(statement, label, configuration);
        if (afterId.isPresent()) {
          statement.setLong(parameter, afterId.getAsLong());
        }
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private static int bindConfiguration(
      PreparedStatement statement, String label, Optional<String> configuration)
      throws SQLException {
    statement.setString(1, label);
    if (configuration.isPresent()) {
      statement.setString(2, configuration.orElseThrow());
      return 3;
    }
    return 2;
  }

  private long scalarId(String sql, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        statement.setLong(1, id);
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private long scalarIdAfter(String sql, long id, long after) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      running = statement;
      try {
        checkCancellation();
        statement.setLong(1, id);
        statement.setLong(2, after);
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private long scalarTagAfter(long targetId, TagAnchor anchor) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM target_tags WHERE target_id = ?"
                + " AND (from_event, tag) > (?, ?)")) {
      running = statement;
      try {
        checkCancellation();
        statement.setLong(1, targetId);
        statement.setString(2, anchor.fromEvent());
        statement.setString(3, anchor.tag());
        return scalar(statement);
      } finally {
        running = null;
      }
    }
  }

  private static int bindLabelFilter(PreparedStatement statement, int parameter, String labelText)
      throws SQLException {
    if (!labelText.isEmpty()) {
      statement.setString(parameter++, contains(labelText));
    }
    return parameter;
  }

  private static String afterClause(String where) {
    return where.isEmpty() ? " WHERE l.value > ?" : " AND l.value > ?";
  }

  private <T> T snapshot(SqlWork<T> work) throws SQLException {
    long expectedEpoch = cancellationEpoch.get();
    boolean autoCommit = connection.getAutoCommit();
    requestEpoch.set(expectedEpoch);
    try {
      if (!autoCommit) {
        return runAndCheck(work);
      }
      connection.setAutoCommit(false);
      try {
        return runAndCheck(work);
      } finally {
        try {
          connection.rollback();
        } finally {
          connection.setAutoCommit(true);
        }
      }
    } finally {
      requestEpoch.remove();
    }
  }

  private <T> T runAndCheck(SqlWork<T> work) throws SQLException {
    checkCancellation();
    T result = work.run();
    // Do not publish a page when cancellation raced with its final statement.
    checkCancellation();
    return result;
  }

  private void checkCancellation() throws SQLException {
    Long expectedEpoch = requestEpoch.get();
    if (expectedEpoch != null && cancellationEpoch.get() != expectedEpoch) {
      throw new SQLTransientException("target query was cancelled");
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  @FunctionalInterface
  private interface StatementBinder {
    int bind(PreparedStatement statement) throws SQLException;
  }

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

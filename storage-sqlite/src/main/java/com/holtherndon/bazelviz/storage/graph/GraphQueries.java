package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.GraphIndexCache;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import com.holtherndon.bazelviz.graph.ShortestPath;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Reads the graph: what sources exist, what a node's neighbours are, and whether two nodes are
 * connected.
 *
 * <h2>Indexes are leased from the session cache</h2>
 *
 * <p>A CSR index is immutable. The open session owns its bounded mapped-index cache; callers can
 * use an index only inside a callback, so a mapping cannot outlive the cache lease that protects
 * it.
 *
 * <h2>Nothing here computes a transitive closure</h2>
 *
 * <p>Plan 13.3 forbids it. Every traversal takes a depth and a node budget, and a traversal that
 * hits either says so rather than returning a shorter answer that looks complete.
 */
public final class GraphQueries implements AutoCloseable {

  private static final String SOURCES =
      "SELECT kind, command, state, configuration_match, mismatch_detail,"
          + " target_scope, target_scope_detail,"
          + " declared_actions, correlated_actions, unresolved_artifacts,"
          + " unresolved_depset_references, error_excerpt"
          + " FROM graph_sources ORDER BY id";

  private static final String NODE_BY_ACTION =
      "SELECT node_index FROM declared_actions WHERE action_id = ?"
          + " AND node_index IS NOT NULL LIMIT 1";

  /**
   * The action-graph search: any of the three name parts may match.
   *
   * <p>The canvas draws an action as "Mnemonic — output basename", so a search that only matched
   * the target label would answer "nothing matches Javac" about a graph full of nodes drawn "Javac
   * — …" — a false claim about the graph. Labels, mnemonics and primary-output paths are all
   * searched, which is exactly the set of parts a drawn name is composed from. Ordered by label
   * with the unlabelled last, so the ordering is unchanged for every row the old label-only search
   * found.
   */
  private static final String NODES_BY_NAME =
      "SELECT da.node_index, l.value, m.value, art.path FROM declared_actions da"
          + " LEFT JOIN labels l ON l.id = da.label_id"
          + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
          + " LEFT JOIN artifacts art ON art.id = da.primary_output_id"
          + " WHERE da.node_index IS NOT NULL"
          + " AND (l.value LIKE ? OR m.value LIKE ? OR art.path LIKE ?)"
          + " ORDER BY l.value IS NULL, l.value LIMIT ?";

  private static final String NODE_DETAIL_COLUMNS =
      "SELECT da.node_index, l.value, m.value, art.path, da.action_id"
          + " FROM declared_actions da"
          + " LEFT JOIN labels l ON l.id = da.label_id"
          + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
          + " LEFT JOIN artifacts art ON art.id = da.primary_output_id";

  private static final String NODE_DETAIL = NODE_DETAIL_COLUMNS + " WHERE da.node_index = ?";

  private static final String NODE_BY_EXACT_LABEL =
      "SELECT da.node_index FROM declared_actions da"
          + " JOIN labels l ON l.id = da.label_id"
          + " WHERE l.value = ? AND da.node_index IS NOT NULL"
          + " ORDER BY da.node_index LIMIT 1";

  private static final String LABEL_NODES_CTE =
      "WITH label_nodes(label_id, node_index) AS ("
          + " SELECT label_id, row_number() OVER (ORDER BY label_id) - 1"
          + " FROM (SELECT DISTINCT label_id FROM configured_target_nodes)) ";

  private static final String LABEL_NODE_BY_EXACT_LABEL =
      LABEL_NODES_CTE
          + "SELECT n.node_index FROM label_nodes n"
          + " JOIN labels l ON l.id = n.label_id WHERE l.value = ? LIMIT 1";

  /**
   * The label-graph search: the label or the rule class may match, because both appear in the rows
   * a search shows — the same reasoning as {@link #NODES_BY_NAME}, over the parts a label node is
   * presented with.
   */
  private static final String LABEL_NODES_BY_PATTERN =
      LABEL_NODES_CTE
          + "SELECT numbered.node_index, l.value, min(n.rule_class)"
          + " FROM label_nodes numbered"
          + " JOIN configured_target_nodes n ON n.label_id = numbered.label_id"
          + " JOIN labels l ON l.id = numbered.label_id"
          + " WHERE l.value LIKE ? OR n.rule_class LIKE ?"
          + " GROUP BY numbered.node_index, l.value ORDER BY l.value LIMIT ?";

  private static final String LABEL_NODE_BY_ID =
      LABEL_NODES_CTE
          + "SELECT l.value, min(n.rule_class)"
          + " FROM label_nodes numbered"
          + " JOIN configured_target_nodes n ON n.label_id = numbered.label_id"
          + " JOIN labels l ON l.id = numbered.label_id"
          + " WHERE numbered.node_index = ? GROUP BY l.value";

  private final Connection connection;
  private final GraphIndexBuilder indexes;
  private final GraphSessionResources resources;
  private final boolean ownsResources;

  public GraphQueries(Connection connection, Path indexDirectory) {
    this(connection, indexDirectory, new GraphSessionResources(), true);
  }

  /** Uses the graph resources shared by every reader of one open session. */
  public GraphQueries(Connection connection, Path indexDirectory, GraphSessionResources resources) {
    this(connection, indexDirectory, resources, false);
  }

  private GraphQueries(
      Connection connection,
      Path indexDirectory,
      GraphSessionResources resources,
      boolean ownsResources) {
    this.connection = connection;
    this.indexes = new GraphIndexBuilder(connection, indexDirectory);
    this.resources = Objects.requireNonNull(resources, "resources");
    this.ownsResources = ownsResources;
  }

  /**
   * One duration per graph node, indexed by {@code node_index}.
   *
   * <p>The array the critical path is weighted by. A node nothing timed gets {@code
   * unknownDuration} rather than a zero, so the computation can count how many it had to guess at
   * and report the answer as a lower bound.
   *
   * <p>Two sources, and they are different measurements of overlapping work: the build event
   * stream's action window, and the execution log's spawn total. Which one was used travels with
   * the answer, because plan 13.4 requires it to.
   *
   * <p>When an action has several execution attempts, the dependency path uses the shortest
   * recorded attempt. Summing raced or retried attempts would turn parallel subprocess work into
   * serial elapsed time and would no longer be a dependency-only lower bound. The general action
   * metrics may still sum attempts when they describe total work rather than path latency.
   *
   * @param fromAttempts true to weight by execution-log spawn time, false to weight by the action's
   *     own start and end
   */
  public long[] durationsByNodeIndex(boolean fromAttempts, long unknownDuration)
      throws SQLException {
    int nodes = actionNodeCount();
    long[] durations = new long[nodes];
    Arrays.fill(durations, unknownDuration);
    if (nodes == 0) {
      return durations;
    }
    String sql =
        fromAttempts
            ? "SELECT d.node_index, min(t.total_micros) FROM declared_actions d"
                + " JOIN action_attempts t ON t.action_id = d.action_id"
                + " JOIN enrichment_tasks et ON et.id = t.task_id"
                + "   AND et.kind = 'EXECUTION_LOG' AND et.state = 'SUCCEEDED'"
                + " WHERE d.node_index IS NOT NULL"
                + " GROUP BY d.node_index"
                + " HAVING COUNT(t.total_micros) = COUNT(t.id)"
                + "   AND MIN(t.total_micros) >= 0"
            : "SELECT d.node_index, a.end_micros - a.start_micros FROM declared_actions d"
                + " JOIN actions a ON a.id = d.action_id"
                + " WHERE d.node_index IS NOT NULL"
                + "   AND a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                + "   AND a.end_micros > a.start_micros";
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = rows.getInt(1);
        if (index >= 0 && index < nodes) {
          durations[index] = Math.max(0, rows.getLong(2));
        }
      }
    }
    return durations;
  }

  /**
   * Each graph node's primary-output size in bytes, indexed by {@code node_index}.
   *
   * <p>The cheap size join: {@code declared_actions.primary_output_id} to {@code
   * artifacts.size_bytes}. A node whose output was never sized — no primary output recorded, or an
   * artifact the capture never measured — keeps {@code unknownSize} rather than a zero, because
   * "this produced an empty file" and "nothing measured this" are different facts and only one of
   * them is about the build.
   */
  public long[] outputSizesByNodeIndex(long unknownSize) throws SQLException {
    int nodes = actionNodeCount();
    long[] sizes = new long[nodes];
    Arrays.fill(sizes, unknownSize);
    if (nodes == 0) {
      return sizes;
    }
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT da.node_index, art.size_bytes FROM declared_actions da"
                    + " JOIN artifacts art ON art.id = da.primary_output_id"
                    + " WHERE da.node_index IS NOT NULL"
                    + "   AND art.size_bytes IS NOT NULL");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = rows.getInt(1);
        long value = rows.getLong(2);
        if (index >= 0 && index < nodes && value >= 0) {
          sizes[index] = value;
        }
      }
    }
    return sizes;
  }

  /** Primary-output sizes aligned only with the bounded extracted node list. */
  public long[] outputSizes(List<Integer> nodeIndexes, long unknownSize) throws SQLException {
    Objects.requireNonNull(nodeIndexes, "nodeIndexes");
    long[] sizes = new long[nodeIndexes.size()];
    Arrays.fill(sizes, unknownSize);
    if (nodeIndexes.isEmpty()) {
      return sizes;
    }
    for (Integer node : nodeIndexes) {
      Objects.requireNonNull(node, "nodeIndexes contains null");
      if (node < 0) {
        throw new IllegalArgumentException("negative graph node index " + node);
      }
    }
    for (int from = 0; from < nodeIndexes.size(); from += 400) {
      int to = Math.min(nodeIndexes.size(), from + 400);
      StringBuilder values = new StringBuilder();
      for (int position = from; position < to; position++) {
        if (!values.isEmpty()) {
          values.append(',');
        }
        values
            .append('(')
            .append(nodeIndexes.get(position))
            .append(',')
            .append(position)
            .append(')');
      }
      String sql =
          "WITH requested(node_index, position) AS (VALUES "
              + values
              + ") SELECT r.position, art.size_bytes FROM requested r"
              + " LEFT JOIN declared_actions da ON da.node_index = r.node_index"
              + " LEFT JOIN artifacts art ON art.id = da.primary_output_id";
      try (PreparedStatement statement = connection.prepareStatement(sql);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          long value = rows.getLong(2);
          if (!rows.wasNull() && value >= 0) {
            sizes[rows.getInt(1)] = value;
          }
        }
      }
    }
    return sizes;
  }

  /** Streams complete-export metadata in dense node order without retaining whole-graph arrays. */
  public void forEachNodeMetadata(GraphKind graphKind, NodeMetadataVisitor visitor)
      throws SQLException, IOException {
    Objects.requireNonNull(visitor, "visitor");
    String sql;
    if (graphKind == GraphKind.CONFIGURED_TARGETS) {
      sql =
          LABEL_NODES_CTE
              + "SELECT numbered.node_index, l.value, NULL"
              + " FROM label_nodes numbered LEFT JOIN labels l ON l.id = numbered.label_id"
              + " ORDER BY numbered.node_index";
    } else {
      sql =
          "SELECT da.node_index, l.value,"
              + " CASE WHEN a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
              + " AND a.end_micros > a.start_micros"
              + " THEN a.end_micros - a.start_micros END"
              + " FROM declared_actions da LEFT JOIN labels l ON l.id = da.label_id"
              + " LEFT JOIN actions a ON a.id = da.action_id"
              + " WHERE da.node_index IS NOT NULL ORDER BY da.node_index";
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setFetchSize(4_096);
      try (ResultSet rows = statement.executeQuery()) {
        long read = 0;
        while (rows.next()) {
          if ((read++ & 4_095L) == 0 && Thread.currentThread().isInterrupted()) {
            throw new IOException("complete graph metadata export was cancelled");
          }
          long duration = rows.getLong(3);
          visitor.node(rows.getInt(1), rows.getString(2), rows.wasNull() ? -1 : duration);
        }
      }
    }
  }

  /** One streamed complete-export metadata row. */
  @FunctionalInterface
  public interface NodeMetadataVisitor {
    void node(int nodeIndex, String label, long durationMicros) throws IOException;
  }

  /**
   * The executed action behind each graph node, where there is one.
   *
   * <p>What turns a path of node indices into something another view can highlight. Nodes with no
   * executed action -- every test's TestRunner in a `build` invocation -- are absent rather than
   * mapped to zero.
   */
  public Map<Integer, Long> actionIdsByNodeIndex() throws SQLException {
    Map<Integer, Long> byNode = new HashMap<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT node_index, action_id FROM declared_actions"
                    + " WHERE node_index IS NOT NULL AND action_id IS NOT NULL");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        byNode.put(rows.getInt(1), rows.getLong(2));
      }
    }
    return Map.copyOf(byNode);
  }

  /**
   * Streams correlated graph-node/action pairs without materializing the graph as boxed maps.
   *
   * <p>Metrics that only select a bounded top-N can consume this cursor directly, keeping memory
   * bounded for Tier-3 graphs. New rendering code uses extraction-aligned metadata instead of the
   * compatibility whole-session map returned by {@link #actionIdsByNodeIndex()}.
   */
  public void forEachActionIdByNodeIndex(NodeActionVisitor visitor) throws SQLException {
    Objects.requireNonNull(visitor, "visitor");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT node_index, action_id FROM declared_actions"
                + " WHERE node_index IS NOT NULL AND action_id IS NOT NULL")) {
      statement.setFetchSize(4_096);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          visitor.accept(rows.getInt(1), rows.getLong(2));
        }
      }
    }
  }

  /** One primitive graph-node/action pair from the streaming correlation cursor. */
  @FunctionalInterface
  public interface NodeActionVisitor {
    void accept(int nodeIndex, long actionId);
  }

  /**
   * The target label behind each graph node, indexed by {@code node_index}.
   *
   * <p>This whole-session helper remains for compatibility and inherently whole-graph analysis. New
   * clustering uses admitted {@code clusterKeys}, and rendering uses extraction-aligned metadata.
   * Nodes whose label the import never learned remain null rather than becoming a real, empty
   * label.
   */
  public String[] labelsByNodeIndex() throws SQLException {
    return keysByNodeIndex(
        "SELECT da.node_index, l.value FROM declared_actions da"
            + " JOIN labels l ON l.id = da.label_id"
            + " WHERE da.node_index IS NOT NULL");
  }

  /**
   * A display name per action-graph node: "Mnemonic — output basename".
   *
   * <p>This whole-session compatibility helper is not used while opening or drawing a graph page;
   * the renderer loads the same composition only for its admitted extraction.
   *
   * <p>{@link #labelsByNodeIndex()} names a node by its owning target, and a target owns many
   * actions — so on the canvas every action under one target read as the same string. This is the
   * per-action name: the mnemonic and the primary output's basename, which together distinguish the
   * {@code Javac} from the {@code JavaSourceJar} of the same label.
   *
   * <p>Absent pieces degrade honestly rather than being invented: no output leaves the mnemonic
   * alone, no mnemonic falls back to the target label (then to the basename), and a node with none
   * of the three stays null so the canvas can say "(name not recorded)" instead of showing a blank.
   */
  public String[] displayLabelsByNodeIndex() throws SQLException {
    int nodes = actionNodeCount();
    String[] names = new String[nodes];
    if (nodes == 0) {
      return names;
    }
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT da.node_index, m.value, art.path, l.value"
                    + " FROM declared_actions da"
                    + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
                    + " LEFT JOIN artifacts art ON art.id = da.primary_output_id"
                    + " LEFT JOIN labels l ON l.id = da.label_id"
                    + " WHERE da.node_index IS NOT NULL");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = rows.getInt(1);
        if (index >= 0 && index < nodes) {
          names[index] =
              composeDisplayLabel(rows.getString(2), rows.getString(3), rows.getString(4));
        }
      }
    }
    return names;
  }

  /**
   * "Mnemonic — output basename", degrading honestly when pieces are absent.
   *
   * <p>Static and public so the views composing search rows reuse this grammar for the distinct
   * half of a row — they show it in parentheses after the target label — rather than keeping a
   * second, drifting copy of it.
   *
   * @return the composed name, or null when there is nothing to compose — never an empty string,
   *     which would draw as a blank that reads as "this action has no identity"
   */
  public static String composeDisplayLabel(
      String mnemonic, String primaryOutputPath, String label) {
    String basename = basenameOf(primaryOutputPath);
    if (mnemonic != null && !mnemonic.isBlank()) {
      return basename == null ? mnemonic : mnemonic + " — " + basename;
    }
    if (label != null && !label.isBlank()) {
      return label;
    }
    return basename;
  }

  private static String basenameOf(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    int slash = path.lastIndexOf('/');
    String basename = slash < 0 ? path : path.substring(slash + 1);
    return basename.isBlank() ? null : basename;
  }

  /** The mnemonic behind each graph node; the coarsest useful clustering. */
  public String[] mnemonicsByNodeIndex() throws SQLException {
    return keysByNodeIndex(
        "SELECT da.node_index, m.value FROM declared_actions da"
            + " JOIN mnemonics m ON m.id = da.mnemonic_id"
            + " WHERE da.node_index IS NOT NULL");
  }

  /**
   * One string per node index, sized to the whole graph.
   *
   * <p>Dense rather than a map because the clustering pass indexes it once per node, and because
   * its length is then a checkable claim about coverage -- {@code GraphClustering} rejects an array
   * that does not span the graph instead of treating the shortfall as unknown.
   */
  private String[] keysByNodeIndex(String sql) throws SQLException {
    int nodes = actionNodeCount();
    String[] keys = new String[nodes];
    if (nodes == 0) {
      return keys;
    }
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = rows.getInt(1);
        if (index >= 0 && index < nodes) {
          keys[index] = rows.getString(2);
        }
      }
    }
    return keys;
  }

  private long scalar(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private int actionNodeCount() throws SQLException {
    long count = scalar("SELECT count(*) FROM declared_actions WHERE node_index IS NOT NULL");
    try {
      return resources.validateActionNodes(connection, count);
    } catch (IOException malformed) {
      throw new SQLException("declared action node indexes are not safe to allocate", malformed);
    }
  }

  private int labelNodeCount() throws SQLException {
    long count =
        scalar("SELECT count(*) FROM (SELECT DISTINCT label_id FROM configured_target_nodes)");
    try {
      return resources.validateLabelNodes(connection, count);
    } catch (IOException malformed) {
      throw new SQLException("configured-target label indexes are not safe to allocate", malformed);
    }
  }

  /** Every graph this session holds, with what may be claimed about it. */
  public List<GraphSource> sources() throws SQLException {
    List<GraphSource> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SOURCES);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        long declared = rows.getLong("declared_actions");
        boolean declaredNull = rows.wasNull();
        long correlated = rows.getLong("correlated_actions");
        boolean correlatedNull = rows.wasNull();
        long unresolved = rows.getLong("unresolved_artifacts");
        boolean unresolvedNull = rows.wasNull();
        long unresolvedDepsets = rows.getLong("unresolved_depset_references");
        boolean unresolvedDepsetsNull = rows.wasNull();
        out.add(
            new GraphSource(
                rows.getString("kind"),
                Optional.ofNullable(rows.getString("command")),
                rows.getString("state"),
                ConfigurationMatch.valueOf(rows.getString("configuration_match")),
                Optional.ofNullable(rows.getString("mismatch_detail")),
                Optional.ofNullable(rows.getString("target_scope"))
                    .map(GraphTargetScope::valueOf)
                    .orElse(GraphTargetScope.UNKNOWN),
                Optional.ofNullable(rows.getString("target_scope_detail")),
                declaredNull ? OptionalLong.empty() : OptionalLong.of(declared),
                correlatedNull ? OptionalLong.empty() : OptionalLong.of(correlated),
                unresolvedNull ? OptionalLong.empty() : OptionalLong.of(unresolved),
                unresolvedDepsetsNull ? OptionalLong.empty() : OptionalLong.of(unresolvedDepsets),
                Optional.ofNullable(rows.getString("error_excerpt"))));
      }
    }
    return out;
  }

  /** The node for an executed action, when the graph declares it. */
  public OptionalLong nodeForAction(long actionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(NODE_BY_ACTION)) {
      statement.setLong(1, actionId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  /**
   * The node for exactly this label, when the graph carries one.
   *
   * <p>Not {@link #search(GraphKind, String, int)} with a {@code %pattern%}: a substring search for
   * {@code //app:server} also matches {@code //app:server_lib}, and a cross-view jump that lands on
   * a neighbour of the target the user asked for is worse than one that says the target is not in
   * the graph. {@code labels.value} is UNIQUE, so equality here is both exact and indexed.
   *
   * <p>The two graphs answer with their own numbering, which is why the kind is a parameter rather
   * than a guess: an action-graph index means nothing to the label graph's CSR and vice versa. In
   * the action graph a label usually owns several actions, and the lowest node index is returned so
   * that repeated jumps to one label land in the same place every time.
   *
   * @param graphKind which graph's numbering the answer is in
   * @return the node index, or empty when no node in that graph carries the label — never a zero
   *     standing in for "not found"
   */
  public OptionalInt nodeForLabel(GraphKind graphKind, String label) throws SQLException {
    Objects.requireNonNull(label, "label");
    if (graphKind != GraphKind.CONFIGURED_TARGETS) {
      try (PreparedStatement statement = connection.prepareStatement(NODE_BY_EXACT_LABEL)) {
        statement.setString(1, label);
        try (ResultSet rows = statement.executeQuery()) {
          return rows.next() ? OptionalInt.of(rows.getInt(1)) : OptionalInt.empty();
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODE_BY_EXACT_LABEL)) {
      statement.setString(1, label);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalInt.of(rows.getInt(1)) : OptionalInt.empty();
      }
    }
  }

  /**
   * Nodes whose label, mnemonic or primary-output path matches {@code pattern}, which may contain
   * {@code %}.
   *
   * <p>All three parts because they are what the drawn name is composed from: a Find that could not
   * match "Javac" — the first thing on every Javac node's label — would report a false absence
   * about the graph.
   */
  public List<GraphNode> search(String pattern, int limit) throws SQLException {
    List<GraphNode> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(NODES_BY_NAME)) {
      statement.setString(1, pattern);
      statement.setString(2, pattern);
      statement.setString(3, pattern);
      statement.setInt(4, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.add(
              new GraphNode(
                  rows.getInt(1),
                  Optional.ofNullable(rows.getString(2)),
                  Optional.ofNullable(rows.getString(3)),
                  Optional.ofNullable(rows.getString(4)),
                  OptionalLong.empty()));
        }
      }
    }
    return out;
  }

  /** One node, by its index. */
  public Optional<GraphNode> node(int nodeIndex) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(NODE_DETAIL)) {
      statement.setInt(1, nodeIndex);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(readGraphNode(rows));
      }
    }
  }

  /**
   * Details for a caller-bounded set of action-graph node indexes.
   *
   * <p>Every distinct requested index remains a key, in first-requested order. A key whose node is
   * absent maps to {@link Optional#empty()}, so a caller can distinguish "the node was not stored"
   * from "the node was not requested" without issuing one query per row.
   *
   * <p>Large inputs are read in several SQL batches rather than silently truncated or made
   * dependent on SQLite's configured bind-variable ceiling. Callers should still bound the list to
   * the rows they intend to display; this method preserves all of them.
   */
  public Map<Integer, Optional<GraphNode>> nodes(List<Integer> nodeIndexes) throws SQLException {
    Objects.requireNonNull(nodeIndexes, "nodeIndexes");
    Map<Integer, Optional<GraphNode>> byIndex = new LinkedHashMap<>();
    for (Integer nodeIndex : nodeIndexes) {
      byIndex.putIfAbsent(
          Objects.requireNonNull(nodeIndex, "nodeIndexes contains null"), Optional.empty());
    }
    if (byIndex.isEmpty()) {
      return Map.of();
    }

    List<Integer> distinctIndexes = new ArrayList<>(byIndex.keySet());
    // SQLite historically guarantees at least 999 bind variables. Keeping
    // a little headroom makes this portable without limiting the input:
    // additional indexes simply use another query.
    int bindParametersPerBatch = 900;
    for (int from = 0; from < distinctIndexes.size(); from += bindParametersPerBatch) {
      int to = Math.min(distinctIndexes.size(), from + bindParametersPerBatch);
      String placeholders = String.join(",", Collections.nCopies(to - from, "?"));
      try (PreparedStatement statement =
          connection.prepareStatement(
              NODE_DETAIL_COLUMNS + " WHERE da.node_index IN (" + placeholders + ")")) {
        for (int index = from; index < to; index++) {
          statement.setInt(index - from + 1, distinctIndexes.get(index));
        }
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            GraphNode node = readGraphNode(rows);
            byIndex.put(node.nodeIndex(), Optional.of(node));
          }
        }
      }
    }
    return Collections.unmodifiableMap(byIndex);
  }

  /**
   * Loads only metadata for the bounded nodes in one extraction, positionally aligned with it.
   * String byte lengths are counted and admitted before JDBC materializes any string value.
   */
  public NodeMetadata metadata(GraphKind graphKind, List<Integer> nodeIndexes)
      throws SQLException, IOException {
    Objects.requireNonNull(nodeIndexes, "nodeIndexes");
    for (Integer node : nodeIndexes) {
      Objects.requireNonNull(node, "nodeIndexes contains null");
      if (node < 0) {
        throw new IllegalArgumentException("negative graph node index " + node);
      }
    }
    return inReadSnapshot(() -> loadMetadata(graphKind, nodeIndexes));
  }

  /** The database string column used to group a whole graph. */
  public enum ClusterKeySource {
    LABEL,
    MNEMONIC,
    RULE_CLASS
  }

  /**
   * Loads admitted whole-graph grouping keys on one stable sizing/materialization snapshot. Package
   * derivation may briefly retain both each label and its substring, so it is charged at twice the
   * variable string storage.
   */
  public ClusterKeyData clusterKeys(
      GraphKind graphKind, ClusterKeySource source, int expectedNodes, boolean derivePackages)
      throws SQLException, IOException {
    if (expectedNodes < 0) {
      throw new IllegalArgumentException("negative graph node count " + expectedNodes);
    }
    if (source == ClusterKeySource.RULE_CLASS && graphKind != GraphKind.CONFIGURED_TARGETS) {
      throw new IllegalArgumentException("rule-class keys belong only to configured targets");
    }
    if (graphKind == GraphKind.CONFIGURED_TARGETS && source == ClusterKeySource.MNEMONIC) {
      throw new IllegalArgumentException(
          "configured-target nodes have rule classes, not mnemonics");
    }
    return inReadSnapshot(() -> loadClusterKeys(graphKind, source, expectedNodes, derivePackages));
  }

  private ClusterKeyData loadClusterKeys(
      GraphKind graphKind, ClusterKeySource source, int expectedNodes, boolean derivePackages)
      throws SQLException, IOException {
    String rowsSql = clusterKeyRows(graphKind, source);
    long encodedBytes =
        scalar(
            "SELECT coalesce(sum(coalesce(length(CAST(value AS BLOB)), 0)), 0) FROM ("
                + rowsSql
                + ")");
    long retainedBytes;
    try {
      retainedBytes =
          Math.addExact(
              4_096,
              Math.addExact(
                  Math.multiplyExact((long) expectedNodes, 16L),
                  Math.multiplyExact(encodedBytes, derivePackages ? 8L : 4L)));
    } catch (ArithmeticException overflow) {
      throw new IOException("whole-graph cluster keys are too large to account safely", overflow);
    }
    GraphResourceBudget.Reservation reservation =
        resources.budget().reserve(retainedBytes, "whole-graph cluster keys");
    try {
      String[] keys = new String[expectedNodes];
      boolean[] present = new boolean[expectedNodes];
      int rowsRead = 0;
      try (PreparedStatement statement =
              connection.prepareStatement(rowsSql + " ORDER BY node_index");
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          int node = rows.getInt(1);
          if (node < 0 || node >= expectedNodes || present[node]) {
            throw new IOException("cluster-key rows do not match the dense graph node universe");
          }
          present[node] = true;
          keys[node] = rows.getString(2);
          rowsRead++;
        }
      }
      if (rowsRead != expectedNodes) {
        throw new IOException(
            "cluster-key rows cover "
                + rowsRead
                + " nodes but the graph index has "
                + expectedNodes);
      }
      return new ClusterKeyData(keys, retainedBytes, reservation);
    } catch (SQLException | IOException | RuntimeException failure) {
      reservation.close();
      throw failure;
    }
  }

  private static String clusterKeyRows(GraphKind graphKind, ClusterKeySource source) {
    if (graphKind == GraphKind.CONFIGURED_TARGETS) {
      if (source == ClusterKeySource.LABEL) {
        return LABEL_NODES_CTE
            + "SELECT numbered.node_index AS node_index, l.value AS value"
            + " FROM label_nodes numbered LEFT JOIN labels l ON l.id = numbered.label_id";
      }
      return LABEL_NODES_CTE
          + "SELECT numbered.node_index AS node_index, min(n.rule_class) AS value"
          + " FROM label_nodes numbered LEFT JOIN configured_target_nodes n"
          + " ON n.label_id = numbered.label_id GROUP BY numbered.node_index";
    }
    String table = source == ClusterKeySource.MNEMONIC ? "mnemonics" : "labels";
    String foreignKey = source == ClusterKeySource.MNEMONIC ? "mnemonic_id" : "label_id";
    return "SELECT da.node_index AS node_index, value.value AS value"
        + " FROM declared_actions da LEFT JOIN "
        + table
        + " value ON value.id = da."
        + foreignKey
        + " WHERE da.node_index IS NOT NULL";
  }

  private NodeMetadata loadMetadata(GraphKind graphKind, List<Integer> nodeIndexes)
      throws SQLException, IOException {
    long boundedSqlScratch =
        Math.addExact(8_192L, Math.multiplyExact(Math.min(400L, nodeIndexes.size()), 64L));
    try (GraphResourceBudget.Reservation ignored =
        resources.budget().reserve(boundedSqlScratch, "bounded graph metadata SQL scratch")) {
      long encodedBytes = metadataEncodedBytes(graphKind, nodeIndexes);
      long retainedBytes;
      try {
        retainedBytes =
            Math.addExact(
                4_096,
                Math.addExact(
                    Math.multiplyExact((long) nodeIndexes.size(), 96L),
                    Math.multiplyExact(encodedBytes, 4L)));
      } catch (ArithmeticException overflow) {
        throw new IOException("extraction metadata is too large to account safely", overflow);
      }
      GraphResourceBudget.Reservation reservation =
          resources.budget().reserve(retainedBytes, "extraction-aligned graph metadata");
      try {
        String[] display = new String[nodeIndexes.size()];
        String[] owners = new String[nodeIndexes.size()];
        String[] mnemonics = new String[nodeIndexes.size()];
        long[] durations = new long[nodeIndexes.size()];
        long[] actionIds = new long[nodeIndexes.size()];
        Arrays.fill(durations, -1);
        Arrays.fill(actionIds, -1);
        forMetadataBatches(
            graphKind,
            nodeIndexes,
            false,
            rows -> {
              int position = rows.getInt(1);
              if (graphKind == GraphKind.CONFIGURED_TARGETS) {
                String label = rows.getString(2);
                display[position] = label;
                owners[position] = label;
                return;
              }
              String owner = rows.getString(2);
              String mnemonic = rows.getString(3);
              mnemonics[position] = mnemonic;
              String output = rows.getString(4);
              display[position] = composeDisplayLabel(mnemonic, output, owner);
              owners[position] = owner;
              long actionId = rows.getLong(5);
              if (!rows.wasNull()) {
                actionIds[position] = actionId;
              }
              long duration = rows.getLong(6);
              if (!rows.wasNull() && duration >= 0) {
                durations[position] = duration;
              }
            });
        return new NodeMetadata(display, owners, mnemonics, durations, actionIds, reservation);
      } catch (SQLException | RuntimeException failure) {
        reservation.close();
        throw failure;
      }
    }
  }

  private long metadataEncodedBytes(GraphKind graphKind, List<Integer> nodeIndexes)
      throws SQLException {
    long[] total = {0};
    forMetadataBatches(
        graphKind, nodeIndexes, true, rows -> total[0] = Math.addExact(total[0], rows.getLong(1)));
    return total[0];
  }

  private void forMetadataBatches(
      GraphKind graphKind, List<Integer> nodeIndexes, boolean sizing, MetadataRows visitor)
      throws SQLException {
    // Node ids come from the bounded extraction and were validated above, so embedding their
    // decimal forms is safe. Fixed batches keep both the Java builder and SQLite statement bounded
    // even when the user raises the drawing limit. Graph-reader temporary b-trees are file-backed.
    int nodesPerBatch = 400;
    for (int from = 0; from < nodeIndexes.size(); from += nodesPerBatch) {
      int to = Math.min(nodeIndexes.size(), from + nodesPerBatch);
      StringBuilder values = new StringBuilder();
      for (int index = from; index < to; index++) {
        if (!values.isEmpty()) {
          values.append(',');
        }
        values.append('(').append(nodeIndexes.get(index)).append(',').append(index).append(')');
      }
      String requested = "WITH requested(node_index, position) AS (VALUES " + values + ") ";
      String sql;
      if (graphKind == GraphKind.CONFIGURED_TARGETS) {
        String labelNodes =
            ", label_nodes(label_id, node_index) AS ("
                + " SELECT label_id, row_number() OVER (ORDER BY label_id) - 1"
                + " FROM (SELECT DISTINCT label_id FROM configured_target_nodes)) ";
        sql =
            requested.substring(0, requested.length() - 1)
                + labelNodes
                + (sizing
                    ? "SELECT coalesce(sum(length(CAST(l.value AS BLOB))), 0)"
                    : "SELECT r.position, l.value")
                + " FROM requested r LEFT JOIN label_nodes n ON n.node_index = r.node_index"
                + " LEFT JOIN labels l ON l.id = n.label_id";
      } else {
        sql =
            requested
                + (sizing
                    ? "SELECT coalesce(sum(coalesce(length(CAST(l.value AS BLOB)), 0)"
                        + " + coalesce(length(CAST(m.value AS BLOB)), 0)"
                        + " + coalesce(length(CAST(art.path AS BLOB)), 0)), 0)"
                    : "SELECT r.position, l.value, m.value, art.path, da.action_id,"
                        + " CASE WHEN a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                        + " AND a.end_micros > a.start_micros"
                        + " THEN a.end_micros - a.start_micros END")
                + " FROM requested r"
                + " LEFT JOIN declared_actions da ON da.node_index = r.node_index"
                + " LEFT JOIN labels l ON l.id = da.label_id"
                + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
                + " LEFT JOIN artifacts art ON art.id = da.primary_output_id"
                + " LEFT JOIN actions a ON a.id = da.action_id";
      }
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            visitor.visit(rows);
          }
        }
      }
    }
  }

  @FunctionalInterface
  private interface MetadataRows {
    void visit(ResultSet rows) throws SQLException;
  }

  /** Keeps metadata sizing and materialization on one SQLite snapshot without owning mutations. */
  private <T> T inReadSnapshot(ReadWork<T> work) throws SQLException, IOException {
    boolean ownsTransaction = connection.getAutoCommit();
    if (ownsTransaction) {
      connection.setAutoCommit(false);
    }
    T result = null;
    Throwable failure = null;
    try {
      result = work.run();
      return result;
    } catch (SQLException | IOException | RuntimeException caught) {
      failure = caught;
      throw caught;
    } finally {
      if (ownsTransaction) {
        SQLException cleanup = null;
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          cleanup = rollbackFailure;
        }
        try {
          connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
          if (cleanup == null) {
            cleanup = restoreFailure;
          } else {
            cleanup.addSuppressed(restoreFailure);
          }
        }
        if (cleanup != null) {
          if (failure != null) {
            failure.addSuppressed(cleanup);
          } else {
            closeOwnedResult(result, cleanup);
            throw cleanup;
          }
        }
      }
    }
  }

  /** Releases a callback result hidden inside the optional API wrappers after cleanup fails. */
  static void closeOwnedResult(Object result, Throwable cleanup) {
    Object owned = result;
    while (owned instanceof Optional<?> optional) {
      owned = optional.orElse(null);
    }
    if (owned instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception closeFailure) {
        cleanup.addSuppressed(closeFailure);
      }
    }
  }

  @FunctionalInterface
  private interface ReadWork<T> {
    T run() throws SQLException, IOException;
  }

  /** Extraction-aligned metadata and the charge protecting its retained strings and arrays. */
  public static final class NodeMetadata implements AutoCloseable {
    private final String[] displayLabels;
    private final String[] ownerLabels;
    private final String[] mnemonics;
    private final long[] durations;
    private final long[] actionIds;
    private GraphResourceBudget.Reservation reservation;

    NodeMetadata(
        String[] displayLabels,
        String[] ownerLabels,
        String[] mnemonics,
        long[] durations,
        long[] actionIds,
        GraphResourceBudget.Reservation reservation) {
      this.displayLabels = displayLabels;
      this.ownerLabels = ownerLabels;
      this.mnemonics = mnemonics;
      this.durations = durations;
      this.actionIds = actionIds;
      this.reservation = reservation;
    }

    public String[] displayLabels() {
      return displayLabels;
    }

    public String[] ownerLabels() {
      return ownerLabels;
    }

    public String[] mnemonics() {
      return mnemonics;
    }

    public long[] durations() {
      return durations;
    }

    public long[] actionIds() {
      return actionIds;
    }

    @Override
    public void close() {
      if (reservation != null) {
        reservation.close();
        reservation = null;
      }
    }
  }

  /** Whole-graph cluster keys and the variable-string charge that must follow retained clusters. */
  public static final class ClusterKeyData implements AutoCloseable {
    private final String[] keys;
    private final long retainedBytes;
    private GraphResourceBudget.Reservation reservation;

    private ClusterKeyData(
        String[] keys, long retainedBytes, GraphResourceBudget.Reservation reservation) {
      this.keys = keys;
      this.retainedBytes = retainedBytes;
      this.reservation = reservation;
    }

    public String[] keys() {
      return keys;
    }

    public long retainedBytes() {
      return retainedBytes;
    }

    public GraphResourceBudget.Reservation transferReservation() {
      if (reservation == null) {
        throw new IllegalStateException("cluster-key reservation was already transferred");
      }
      GraphResourceBudget.Reservation transferred = reservation;
      reservation = null;
      return transferred;
    }

    @Override
    public void close() {
      if (reservation != null) {
        reservation.close();
        reservation = null;
      }
    }
  }

  private static GraphNode readGraphNode(ResultSet rows) throws SQLException {
    int nodeIndex = rows.getInt(1);
    Optional<String> label = Optional.ofNullable(rows.getString(2));
    Optional<String> mnemonic = Optional.ofNullable(rows.getString(3));
    Optional<String> primaryOutput = Optional.ofNullable(rows.getString(4));
    long actionId = rows.getLong(5);
    boolean actionNull = rows.wasNull();
    return new GraphNode(
        nodeIndex,
        label,
        mnemonic,
        primaryOutput,
        actionNull ? OptionalLong.empty() : OptionalLong.of(actionId));
  }

  /** {@link #search(String, int)} for any indexed graph. */
  public List<GraphNode> search(GraphKind graphKind, String pattern, int limit)
      throws SQLException {
    if (graphKind != GraphKind.CONFIGURED_TARGETS) {
      return search(pattern, limit);
    }
    List<GraphNode> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODES_BY_PATTERN)) {
      statement.setString(1, pattern);
      statement.setString(2, pattern);
      statement.setInt(3, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.add(
              new GraphNode(
                  rows.getInt(1),
                  Optional.ofNullable(rows.getString(2)),
                  Optional.ofNullable(rows.getString(3)),
                  Optional.empty(),
                  OptionalLong.empty()));
        }
      }
    }
    return out;
  }

  /** {@link #node(int)} for any indexed graph. */
  public Optional<GraphNode> node(GraphKind graphKind, int nodeIndex) throws SQLException {
    if (graphKind != GraphKind.CONFIGURED_TARGETS) {
      return node(nodeIndex);
    }
    if (nodeIndex < 0 || nodeIndex >= labelNodeCount()) {
      return Optional.empty();
    }
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODE_BY_ID)) {
      statement.setInt(1, nodeIndex);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new GraphNode(
                nodeIndex,
                Optional.ofNullable(rows.getString(1)),
                Optional.ofNullable(rows.getString(2)),
                Optional.empty(),
                OptionalLong.empty()));
      }
    }
  }

  /**
   * One label per label-graph node, or the action-graph labels.
   *
   * <p>The label-graph flavour of {@link #labelsByNodeIndex()}. This whole-session compatibility
   * helper remains useful to analysis that inherently visits every node; rendering loads only the
   * extracted nodes and never calls it while opening a graph page.
   */
  public String[] labelsByNodeIndex(GraphKind graphKind) throws SQLException {
    if (graphKind != GraphKind.CONFIGURED_TARGETS) {
      return labelsByNodeIndex();
    }
    String[] out = new String[labelNodeCount()];
    try (PreparedStatement statement =
            connection.prepareStatement(
                LABEL_NODES_CTE
                    + "SELECT numbered.node_index, l.value FROM label_nodes numbered"
                    + " JOIN labels l ON l.id = numbered.label_id");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out[rows.getInt(1)] = rows.getString(2);
      }
    }
    return out;
  }

  /**
   * One rule class per label-graph node; the label graph's answer to {@link
   * #mnemonicsByNodeIndex()}.
   *
   * <p>A label analysed in several configurations has one rule class — the rule is the same rule —
   * so {@code min} is a formality, not a choice between different answers. Left null where cquery
   * did not report one, which stays distinguishable from a real name (plan 11.4).
   */
  public String[] ruleClassesByNodeIndex() throws SQLException {
    String[] out = new String[labelNodeCount()];
    try (PreparedStatement statement =
            connection.prepareStatement(
                LABEL_NODES_CTE
                    + "SELECT numbered.node_index, min(n.rule_class)"
                    + " FROM label_nodes numbered JOIN configured_target_nodes n"
                    + " ON n.label_id = numbered.label_id GROUP BY numbered.node_index");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        out[rows.getInt(1)] = rows.getString(2);
      }
    }
    return out;
  }

  /** How many nodes the configured-target label graph has. */
  public int labelGraphNodeCount() throws SQLException {
    return labelNodeCount();
  }

  /**
   * The nodes directly reachable from {@code nodeIndex}.
   *
   * @param direction {@code true} for dependencies this action feeds, {@code false} for the ones
   *     that feed it
   */
  public Optional<List<GraphNode>> neighbours(
      EdgeDerivation derivation, int nodeIndex, boolean forwards, int limit)
      throws SQLException, IOException {
    return neighbours(kindOf(derivation), nodeIndex, forwards, limit);
  }

  /** {@link #neighbours(EdgeDerivation, int, boolean, int)} for any indexed graph. */
  public Optional<List<GraphNode>> neighbours(
      GraphKind graphKind, int nodeIndex, boolean forwards, int limit)
      throws SQLException, IOException {
    if (limit < 0) {
      throw new IllegalArgumentException("negative neighbour limit " + limit);
    }
    return withIndex(
        graphKind,
        forwards,
        graph -> {
          List<Integer> targets = new ArrayList<>(Math.min(limit, graph.degree(nodeIndex)));
          graph.forEachNeighbor(
              nodeIndex,
              neighbour -> {
                if (targets.size() < limit) {
                  targets.add(neighbour);
                }
              });
          List<GraphNode> out = new ArrayList<>(targets.size());
          for (int target : targets) {
            node(graphKind, target).ifPresent(out::add);
          }
          return List.copyOf(out);
        });
  }

  /** How many direct neighbours a node has, whether or not they are listed. */
  public OptionalInt degree(EdgeDerivation derivation, int nodeIndex, boolean forwards)
      throws SQLException, IOException {
    return degree(kindOf(derivation), nodeIndex, forwards);
  }

  /** {@link #degree(EdgeDerivation, int, boolean)} for any indexed graph. */
  public OptionalInt degree(GraphKind graphKind, int nodeIndex, boolean forwards)
      throws SQLException, IOException {
    Optional<Integer> value = withIndex(graphKind, forwards, graph -> graph.degree(nodeIndex));
    return value.isPresent() ? OptionalInt.of(value.get()) : OptionalInt.empty();
  }

  /**
   * The shortest path between two nodes.
   *
   * @return empty when there is no index to search
   */
  public <T> Optional<T> withPath(
      EdgeDerivation derivation, int from, int to, long budget, PathWork<T> work)
      throws SQLException, IOException {
    return withPath(kindOf(derivation), from, to, budget, work);
  }

  /** Runs work against one admitted shortest-path result while its charge is held. */
  public <T> Optional<T> withPath(
      GraphKind graphKind, int from, int to, long budget, PathWork<T> work)
      throws SQLException, IOException {
    Objects.requireNonNull(work, "work");
    return withIndexPair(
        graphKind,
        (forward, reverse) -> {
          try (GraphResourceBudget.Reservation admitted =
              resources
                  .budget()
                  .reserve(
                      ShortestPath.peakBytes(forward.nodeCount()),
                      "shortest-path scratch and scoped result")) {
            ShortestPath.Result result = new ShortestPath(forward, reverse).find(from, to, budget);
            return work.run(result);
          }
        });
  }

  /** The graph an edge derivation's index describes. */
  private static GraphKind kindOf(EdgeDerivation derivation) {
    return derivation == EdgeDerivation.DECLARED
        ? GraphKind.DECLARED_ACTIONS
        : GraphKind.OBSERVED_EXECUTION;
  }

  /** Header-only metadata for one registered direction. No CSR body is mapped or checksummed. */
  public Optional<CsrFile.Descriptor> indexDescriptor(GraphKind graph, boolean forwards)
      throws SQLException, IOException {
    Optional<String> kind = indexKind(graph);
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    return descriptor(kind.get(), forwards ? "FORWARD" : "REVERSE");
  }

  /** Runs work while one mapped index lease is held; the graph cannot escape this callback. */
  public <T> Optional<T> withIndex(GraphKind graph, boolean forwards, IndexWork<T> work)
      throws SQLException, IOException {
    Objects.requireNonNull(work, "work");
    return withIndexDescriptor(graph, forwards, (ignored, index) -> work.run(index));
  }

  /** Runs work with the immutable descriptor that identifies the held mapped-index lease. */
  public <T> Optional<T> withIndexDescriptor(
      GraphKind graph, boolean forwards, DescriptorIndexWork<T> work)
      throws SQLException, IOException {
    Objects.requireNonNull(work, "work");
    return inReadSnapshot(
        () -> {
          Optional<CsrFile.Descriptor> descriptor = indexDescriptor(graph, forwards);
          if (descriptor.isEmpty()) {
            return Optional.empty();
          }
          validateIndexPopulation(graph, descriptor.get());
          try (GraphIndexCache.Lease lease = resources.cache().acquire(descriptor.get())) {
            return Optional.ofNullable(work.run(descriptor.get(), lease.graph()));
          }
        });
  }

  /** Runs work while the matching pair is leased atomically from one stable registry snapshot. */
  public <T> Optional<T> withIndexPair(GraphKind graph, IndexPairWork<T> work)
      throws SQLException, IOException {
    Objects.requireNonNull(work, "work");
    return inReadSnapshot(
        () -> {
          Optional<String> kind = indexKind(graph);
          if (kind.isEmpty()) {
            return Optional.empty();
          }
          Optional<GraphIndexBuilder.DescriptorPair> pair = indexes.descriptorPair(kind.get());
          if (pair.isEmpty()) {
            return Optional.empty();
          }
          validateIndexPopulation(graph, pair.get().forward());
          if (pair.get().forward().header().nodeCount() != pair.get().reverse().header().nodeCount()
              || pair.get().forward().header().edgeCount()
                  != pair.get().reverse().header().edgeCount()) {
            throw new IOException("forward and reverse graph index descriptors disagree");
          }
          try (GraphIndexCache.PairLease lease =
              resources.cache().acquirePair(pair.get().forward(), pair.get().reverse())) {
            return Optional.ofNullable(work.run(lease.forward().graph(), lease.reverse().graph()));
          }
        });
  }

  /** Action-derivation form of {@link #withIndex(GraphKind, boolean, IndexWork)}. */
  public <T> Optional<T> withIndex(EdgeDerivation derivation, boolean forwards, IndexWork<T> work)
      throws SQLException, IOException {
    return withIndex(kindOf(derivation), forwards, work);
  }

  /** Action-derivation form of {@link #withIndexDescriptor}. */
  public <T> Optional<T> withIndexDescriptor(
      EdgeDerivation derivation, boolean forwards, DescriptorIndexWork<T> work)
      throws SQLException, IOException {
    return withIndexDescriptor(kindOf(derivation), forwards, work);
  }

  /** Action-derivation form of {@link #withIndexPair(GraphKind, IndexPairWork)}. */
  public <T> Optional<T> withIndexPair(EdgeDerivation derivation, IndexPairWork<T> work)
      throws SQLException, IOException {
    return withIndexPair(kindOf(derivation), work);
  }

  /** The {@code graph_indexes.kind} behind a graph, or empty when none exists. */
  private static Optional<String> indexKind(GraphKind graph) {
    return switch (graph) {
      case DECLARED_ACTIONS -> Optional.of(EdgeDerivation.DECLARED.name());
      case OBSERVED_EXECUTION -> Optional.of(EdgeDerivation.OBSERVED.name());
      case CONFIGURED_TARGETS -> Optional.of(GraphIndexBuilder.CONFIGURED_TARGETS_KIND);
      case BEP_EVENTS, TARGETS, TEMPORAL -> Optional.empty();
    };
  }

  private Optional<CsrFile.Descriptor> descriptor(String kind, String direction)
      throws SQLException, IOException {
    return kind.equals(GraphIndexBuilder.CONFIGURED_TARGETS_KIND)
        ? indexes.configuredTargetsDescriptor(direction)
        : indexes.descriptor(EdgeDerivation.valueOf(kind), direction);
  }

  private void validateIndexPopulation(GraphKind graph, CsrFile.Descriptor descriptor)
      throws SQLException, IOException {
    if (graph == GraphKind.CONFIGURED_TARGETS) {
      resources.validateLabelNodes(connection, descriptor);
    } else {
      resources.validateActionNodes(connection, descriptor);
    }
  }

  /** The aggregate budget shared by every graph reader for this open session. */
  public GraphResourceBudget resourceBudget() {
    return resources.budget();
  }

  /** Returns a charged immutable result already computed for this exact session generation. */
  public <T> Optional<T> cachedSessionResult(String slot, String generationKey, Class<T> type)
      throws IOException {
    return resources.retainedResult(slot, generationKey, type);
  }

  /** Installs one charged immutable result, or reuses a concurrently installed identical result. */
  public <T> T retainSessionResult(
      String slot,
      String generationKey,
      Class<T> type,
      T value,
      GraphResourceBudget.Reservation reservation)
      throws IOException {
    return resources.retainResult(slot, generationKey, type, value, reservation);
  }

  /** Visible for operational state and tests; never maps an index. */
  public int cachedIndexCount() {
    return resources.cache().cachedCount();
  }

  @Override
  public void close() throws SQLException {
    SQLException failure = null;
    try {
      connection.close();
    } catch (SQLException caught) {
      failure = caught;
    } finally {
      if (ownsResources) {
        resources.close();
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Work whose graph reference is valid only for the duration of {@link #withIndex}. */
  @FunctionalInterface
  public interface IndexWork<T> {
    T run(CsrGraph graph) throws SQLException, IOException;
  }

  /** Work whose descriptor and graph are valid only for the duration of the held lease. */
  @FunctionalInterface
  public interface DescriptorIndexWork<T> {
    T run(CsrFile.Descriptor descriptor, CsrGraph graph) throws SQLException, IOException;
  }

  /** Work whose two graph references are valid only for the duration of {@link #withIndexPair}. */
  @FunctionalInterface
  public interface IndexPairWork<T> {
    T run(CsrGraph forward, CsrGraph reverse) throws SQLException, IOException;
  }

  /** Work whose path result is valid only while its aggregate-budget charge is held. */
  @FunctionalInterface
  public interface PathWork<T> {
    T run(ShortestPath.Result result) throws SQLException, IOException;
  }

  /**
   * One graph and what may be said about it.
   *
   * @param configurationMatch the only thing that decides whether this graph has the same
   *     configurations as the build (plan 8.6)
   * @param targetScope whether the query used the exact BEP top-level target population
   * @param unresolvedArtifacts artifact paths the aquery importer could not resolve; empty means an
   *     older session did not retain the measurement
   * @param unresolvedDepsetReferences action-input and transitive-child references whose depsets
   *     were absent; empty has the same meaning
   */
  public record GraphSource(
      String kind,
      Optional<String> command,
      String state,
      ConfigurationMatch configurationMatch,
      Optional<String> mismatchDetail,
      GraphTargetScope targetScope,
      Optional<String> targetScopeDetail,
      OptionalLong declaredActions,
      OptionalLong correlatedActions,
      OptionalLong unresolvedArtifacts,
      OptionalLong unresolvedDepsetReferences,
      Optional<String> error) {

    /** True when this graph is loadable and describes this build. */
    public boolean isTrustworthy() {
      return state.equals("SUCCEEDED")
          && configurationMatch.permitsExactClaim()
          && targetScope.permitsExactClaim()
          && actionGraphCompletenessProblem().isEmpty();
    }

    /** Why this graph's target population is not confirmed, if it is not exact. */
    public Optional<String> targetScopeProblem() {
      if (targetScope.permitsExactClaim()) {
        return Optional.empty();
      }
      return Optional.of(
          targetScopeDetail.filter(detail -> !detail.isBlank()).orElseGet(targetScope::describe));
    }

    /**
     * Why an action graph's dependency structure is not confirmed complete, or empty when this
     * source is structurally usable.
     *
     * <p>Configuration and import-state checks remain separate. A cquery source does not contain
     * artifact paths, so this measurement does not apply to it.
     */
    public Optional<String> actionGraphCompletenessProblem() {
      if (!kind.equals("DECLARED_ACTIONS")) {
        return Optional.empty();
      }
      if (unresolvedArtifacts.isEmpty() || unresolvedDepsetReferences.isEmpty()) {
        return Optional.of(
            "structural completeness was not recorded for this session,"
                + " so the action graph is unverified");
      }
      long artifacts = unresolvedArtifacts.getAsLong();
      long depsets = unresolvedDepsetReferences.getAsLong();
      if (artifacts > 0 || depsets > 0) {
        StringBuilder problem = new StringBuilder();
        if (artifacts > 0) {
          problem
              .append(artifacts)
              .append(" artifact ")
              .append(artifacts == 1 ? "path was" : "paths were")
              .append(" unresolved");
        }
        if (depsets > 0) {
          if (!problem.isEmpty()) {
            problem.append(" and ");
          }
          problem
              .append(depsets)
              .append(" depset ")
              .append(depsets == 1 ? "reference was" : "references were")
              .append(" unresolved");
        }
        return Optional.of(problem + ", so dependency edges are missing");
      }
      return Optional.empty();
    }

    /** The words for the graph-source selector. */
    public String displayName() {
      return switch (kind) {
        case "DECLARED_ACTIONS" -> "Declared action graph (aquery)";
        case "CONFIGURED_TARGETS" -> "Configured targets (cquery)";
        default -> kind;
      };
    }

    /**
     * The traversable graph behind this source, when there is one.
     *
     * <p>What the selector switches: a source row is a statement about an import, and this is the
     * graph that import populated. Empty for a kind this build does not know how to traverse, which
     * refuses rather than guesses.
     */
    public Optional<GraphKind> graphKind() {
      return switch (kind) {
        case "DECLARED_ACTIONS" -> Optional.of(GraphKind.DECLARED_ACTIONS);
        case "CONFIGURED_TARGETS" -> Optional.of(GraphKind.CONFIGURED_TARGETS);
        default -> Optional.empty();
      };
    }
  }

  /**
   * One node of the action graph.
   *
   * @param actionId the executed action this was matched to, absent when it was declared and never
   *     ran
   */
  public record GraphNode(
      int nodeIndex,
      Optional<String> label,
      Optional<String> mnemonic,
      Optional<String> primaryOutput,
      OptionalLong actionId) {

    /** How the tree labels this node. */
    public String displayName() {
      String name = label.orElseGet(() -> primaryOutput.orElse("action " + nodeIndex));
      return mnemonic.map(value -> name + "  (" + value + ")").orElse(name);
    }

    /** True when this action was declared and never executed. */
    public boolean declaredOnly() {
      return actionId.isEmpty();
    }
  }
}

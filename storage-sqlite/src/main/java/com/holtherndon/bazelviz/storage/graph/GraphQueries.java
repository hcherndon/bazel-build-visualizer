package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.graph.CsrGraph;
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
 * <h2>Indexes are loaded once and kept</h2>
 *
 * <p>A CSR index is two primitive arrays and is immutable, so one load serves every question.
 * Loading per query would re-read and re-verify the file for each keystroke in a dependency tree.
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

  private static final String LABEL_NODE_BY_EXACT_LABEL =
      "SELECT n.label_id FROM configured_target_nodes n"
          + " JOIN labels l ON l.id = n.label_id"
          + " WHERE l.value = ? LIMIT 1";

  /**
   * The label-graph search: the label or the rule class may match, because both appear in the rows
   * a search shows — the same reasoning as {@link #NODES_BY_NAME}, over the parts a label node is
   * presented with.
   */
  private static final String LABEL_NODES_BY_PATTERN =
      "SELECT n.label_id, l.value, min(n.rule_class)"
          + " FROM configured_target_nodes n"
          + " JOIN labels l ON l.id = n.label_id"
          + " WHERE l.value LIKE ? OR n.rule_class LIKE ?"
          + " GROUP BY n.label_id, l.value ORDER BY l.value LIMIT ?";

  private static final String LABEL_NODE_BY_ID =
      "SELECT l.value, min(n.rule_class)"
          + " FROM configured_target_nodes n"
          + " JOIN labels l ON l.id = n.label_id"
          + " WHERE n.label_id = ? GROUP BY l.value";

  private final Connection connection;
  private final GraphIndexBuilder indexes;
  private final Map<String, CsrGraph> forward = new HashMap<>();
  private final Map<String, CsrGraph> reverse = new HashMap<>();

  /**
   * The label graph's node numbering: sorted distinct label ids, loaded once.
   *
   * <p>One {@code long} per analysed label, which is the same order of cost as the CSR index it
   * accompanies. Kept because every label-graph question translates through it, in both directions
   * — array position to label id by indexing, label id to position by binary search.
   */
  private long[] labelUniverse;

  public GraphQueries(Connection connection, Path indexDirectory) {
    this.connection = connection;
    this.indexes = new GraphIndexBuilder(connection, indexDirectory);
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
    int nodes =
        Math.toIntExact(scalar("SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
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
    int nodes =
        Math.toIntExact(scalar("SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
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
        if (index >= 0 && index < nodes) {
          sizes[index] = Math.max(0, rows.getLong(2));
        }
      }
    }
    return sizes;
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
   * <p>The rendered graph needs a random-access map and uses {@link #actionIdsByNodeIndex()}.
   * Metrics that only select a bounded top-N can consume this cursor directly, keeping memory
   * bounded for Tier-3 graphs.
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
   * <p>What the cluster view groups by, once the caller has reduced a label to its package. Nodes
   * whose label the import never learned are left null rather than filled with a placeholder: plan
   * 11.4 wants unknown to stay distinguishable from a real name all the way to the drawing, and a
   * clustering that invented "" here would show a package called nothing.
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
    int nodes =
        Math.toIntExact(scalar("SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
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
    int nodes =
        Math.toIntExact(scalar("SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
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
    long[] universe = labelUniverse();
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODE_BY_EXACT_LABEL)) {
      statement.setString(1, label);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return OptionalInt.empty();
        }
        int index = Arrays.binarySearch(universe, rows.getLong(1));
        // A label imported after the universe was read is treated
        // exactly as search(GraphKind, ...) treats it: reported as
        // absent rather than given an index the CSR does not have.
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
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
    long[] universe = labelUniverse();
    List<GraphNode> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODES_BY_PATTERN)) {
      statement.setString(1, pattern);
      statement.setString(2, pattern);
      statement.setInt(3, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          int index = Arrays.binarySearch(universe, rows.getLong(1));
          if (index < 0) {
            // A label imported after the universe was read; the
            // session is read-only in practice, but skipping is
            // safer than inventing an index the CSR does not have.
            continue;
          }
          out.add(
              new GraphNode(
                  index,
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
    long[] universe = labelUniverse();
    if (nodeIndex < 0 || nodeIndex >= universe.length) {
      return Optional.empty();
    }
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODE_BY_ID)) {
      statement.setLong(1, universe[nodeIndex]);
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
   * <p>The label-graph flavour of {@link #labelsByNodeIndex()}: what the canvas names nodes with
   * and the clustering groups by, fetched once per session rather than during any paint.
   */
  public String[] labelsByNodeIndex(GraphKind graphKind) throws SQLException {
    if (graphKind != GraphKind.CONFIGURED_TARGETS) {
      return labelsByNodeIndex();
    }
    long[] universe = labelUniverse();
    String[] out = new String[universe.length];
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT DISTINCT n.label_id, l.value FROM configured_target_nodes n"
                    + " JOIN labels l ON l.id = n.label_id");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = Arrays.binarySearch(universe, rows.getLong(1));
        if (index >= 0) {
          out[index] = rows.getString(2);
        }
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
    long[] universe = labelUniverse();
    String[] out = new String[universe.length];
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT n.label_id, min(n.rule_class) FROM configured_target_nodes n"
                    + " GROUP BY n.label_id");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        int index = Arrays.binarySearch(universe, rows.getLong(1));
        if (index >= 0) {
          out[index] = rows.getString(2);
        }
      }
    }
    return out;
  }

  /** How many nodes the configured-target label graph has. */
  public int labelGraphNodeCount() throws SQLException {
    return labelUniverse().length;
  }

  /**
   * The label graph's node numbering, loaded once and kept.
   *
   * <p>The same numbering {@link GraphIndexBuilder#configuredLabelUniverse} built the CSR files
   * with: a pure function of the imported rows, so the index files and these queries cannot
   * disagree about which label a node id means unless the tables changed — and a changed table
   * invalidates the registered index by checksum anyway.
   */
  private long[] labelUniverse() throws SQLException {
    if (labelUniverse == null) {
      labelUniverse = GraphIndexBuilder.configuredLabelUniverse(connection);
    }
    return labelUniverse;
  }

  /**
   * The nodes directly reachable from {@code nodeIndex}.
   *
   * @param direction {@code true} for dependencies this action feeds, {@code false} for the ones
   *     that feed it
   */
  public List<GraphNode> neighbours(
      EdgeDerivation derivation, int nodeIndex, boolean forwards, int limit)
      throws SQLException, IOException {
    return neighbours(kindOf(derivation), nodeIndex, forwards, limit);
  }

  /** {@link #neighbours(EdgeDerivation, int, boolean, int)} for any indexed graph. */
  public List<GraphNode> neighbours(GraphKind graphKind, int nodeIndex, boolean forwards, int limit)
      throws SQLException, IOException {
    Optional<CsrGraph> graph = forwards ? forwardIndex(graphKind) : reverseIndex(graphKind);
    if (graph.isEmpty()) {
      return List.of();
    }
    List<Integer> targets = new ArrayList<>();
    graph
        .get()
        .forEachNeighbor(
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
    return out;
  }

  /** How many direct neighbours a node has, whether or not they are listed. */
  public int degree(EdgeDerivation derivation, int nodeIndex, boolean forwards)
      throws SQLException, IOException {
    return degree(kindOf(derivation), nodeIndex, forwards);
  }

  /** {@link #degree(EdgeDerivation, int, boolean)} for any indexed graph. */
  public int degree(GraphKind graphKind, int nodeIndex, boolean forwards)
      throws SQLException, IOException {
    Optional<CsrGraph> graph = forwards ? forwardIndex(graphKind) : reverseIndex(graphKind);
    return graph.map(value -> value.degree(nodeIndex)).orElse(0);
  }

  /**
   * The shortest path between two nodes.
   *
   * @return empty when there is no index to search
   */
  public Optional<ShortestPath.Result> path(
      EdgeDerivation derivation, int from, int to, long budget) throws SQLException, IOException {
    return path(kindOf(derivation), from, to, budget);
  }

  /** {@link #path(EdgeDerivation, int, int, long)} for any indexed graph. */
  public Optional<ShortestPath.Result> path(GraphKind graphKind, int from, int to, long budget)
      throws SQLException, IOException {
    Optional<CsrGraph> forwardGraph = forwardIndex(graphKind);
    Optional<CsrGraph> reverseGraph = reverseIndex(graphKind);
    if (forwardGraph.isEmpty() || reverseGraph.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ShortestPath(forwardGraph.get(), reverseGraph.get()).find(from, to, budget));
  }

  /** The graph an edge derivation's index describes. */
  private static GraphKind kindOf(EdgeDerivation derivation) {
    return derivation == EdgeDerivation.DECLARED
        ? GraphKind.DECLARED_ACTIONS
        : GraphKind.OBSERVED_EXECUTION;
  }

  /**
   * The producer-to-consumer index, memory-mapped and cached.
   *
   * <p>Public because extraction and layout happen outside this module -- a CSR graph is a {@code
   * graph-core} value, not a SQLite implementation detail, so handing one out does not put the UI
   * back in touch with the database the way rule 19 forbids. Empty when the index was never built,
   * which is the honest answer for a session with no aquery output.
   */
  public Optional<CsrGraph> forwardIndex(EdgeDerivation derivation)
      throws SQLException, IOException {
    return cached(forward, derivation.name(), "FORWARD");
  }

  /** The consumer-to-producer index; see {@link #forwardIndex}. */
  public Optional<CsrGraph> reverseIndex(EdgeDerivation derivation)
      throws SQLException, IOException {
    return cached(reverse, derivation.name(), "REVERSE");
  }

  /**
   * The producer-to-consumer index of whichever graph is asked for.
   *
   * <p>Three graphs have indexes: the two action graphs (declared and observed edges, nodes are
   * actions) and the configured-target label graph (nodes are labels). Any other {@link GraphKind}
   * has no CSR index and gets an empty answer, which is the honest one — those graphs exist in the
   * schema but are not traversable this way.
   */
  public Optional<CsrGraph> forwardIndex(GraphKind graph) throws SQLException, IOException {
    Optional<String> kind = indexKind(graph);
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    return cached(forward, kind.get(), "FORWARD");
  }

  /** The consumer-to-producer index of whichever graph is asked for. */
  public Optional<CsrGraph> reverseIndex(GraphKind graph) throws SQLException, IOException {
    Optional<String> kind = indexKind(graph);
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    return cached(reverse, kind.get(), "REVERSE");
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

  private Optional<CsrGraph> cached(Map<String, CsrGraph> into, String kind, String direction)
      throws SQLException, IOException {
    CsrGraph existing = into.get(kind);
    if (existing != null) {
      return Optional.of(existing);
    }
    Optional<CsrGraph> loaded =
        kind.equals(GraphIndexBuilder.CONFIGURED_TARGETS_KIND)
            ? indexes.loadConfiguredTargets(direction)
            : indexes.load(EdgeDerivation.valueOf(kind), direction);
    loaded.ifPresent(graph -> into.put(kind, graph));
    return loaded;
  }

  @Override
  public void close() throws SQLException {
    connection.close();
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

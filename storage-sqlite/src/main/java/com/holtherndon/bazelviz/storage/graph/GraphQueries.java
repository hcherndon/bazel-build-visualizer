package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.ShortestPath;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the graph: what sources exist, what a node's neighbours are, and
 * whether two nodes are connected.
 *
 * <h2>Indexes are loaded once and kept</h2>
 *
 * <p>A CSR index is two primitive arrays and is immutable, so one load serves
 * every question. Loading per query would re-read and re-verify the file for
 * each keystroke in a dependency tree.
 *
 * <h2>Nothing here computes a transitive closure</h2>
 *
 * <p>Plan 13.3 forbids it. Every traversal takes a depth and a node budget, and
 * a traversal that hits either says so rather than returning a shorter answer
 * that looks complete.
 */
public final class GraphQueries implements AutoCloseable {

    private static final String SOURCES =
            "SELECT kind, command, state, configuration_match, mismatch_detail,"
                    + " declared_actions, correlated_actions, error_excerpt"
                    + " FROM graph_sources ORDER BY id";

    private static final String NODE_BY_ACTION =
            "SELECT node_index FROM declared_actions WHERE action_id = ?"
                    + " AND node_index IS NOT NULL LIMIT 1";

    private static final String NODES_BY_LABEL =
            "SELECT da.node_index, l.value, m.value, art.path FROM declared_actions da"
                    + " LEFT JOIN labels l ON l.id = da.label_id"
                    + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
                    + " LEFT JOIN artifacts art ON art.id = da.primary_output_id"
                    + " WHERE da.node_index IS NOT NULL AND l.value LIKE ?"
                    + " ORDER BY l.value LIMIT ?";

    private static final String NODE_DETAIL =
            "SELECT da.node_index, l.value, m.value, art.path, da.action_id"
                    + " FROM declared_actions da"
                    + " LEFT JOIN labels l ON l.id = da.label_id"
                    + " LEFT JOIN mnemonics m ON m.id = da.mnemonic_id"
                    + " LEFT JOIN artifacts art ON art.id = da.primary_output_id"
                    + " WHERE da.node_index = ?";

    private static final String LABEL_NODES_BY_PATTERN =
            "SELECT n.label_id, l.value, min(n.rule_class)"
                    + " FROM configured_target_nodes n"
                    + " JOIN labels l ON l.id = n.label_id"
                    + " WHERE l.value LIKE ?"
                    + " GROUP BY n.label_id, l.value ORDER BY l.value LIMIT ?";

    private static final String LABEL_NODE_BY_ID =
            "SELECT l.value, min(n.rule_class)"
                    + " FROM configured_target_nodes n"
                    + " JOIN labels l ON l.id = n.label_id"
                    + " WHERE n.label_id = ? GROUP BY l.value";

    private final Connection connection;
    private final GraphIndexBuilder indexes;
    private final Map<String, CsrGraph> forward = new java.util.HashMap<>();
    private final Map<String, CsrGraph> reverse = new java.util.HashMap<>();

    /**
     * The label graph's node numbering: sorted distinct label ids, loaded once.
     *
     * <p>One {@code long} per analysed label, which is the same order of cost
     * as the CSR index it accompanies. Kept because every label-graph question
     * translates through it, in both directions — array position to label id
     * by indexing, label id to position by binary search.
     */
    private long[] labelUniverse;

    public GraphQueries(Connection connection, Path indexDirectory) {
        this.connection = connection;
        this.indexes = new GraphIndexBuilder(connection, indexDirectory);
    }

    /**
     * One duration per graph node, indexed by {@code node_index}.
     *
     * <p>The array the critical path is weighted by. A node nothing timed gets
     * {@code unknownDuration} rather than a zero, so the computation can count
     * how many it had to guess at and report the answer as a lower bound.
     *
     * <p>Two sources, and they are different measurements of overlapping work:
     * the build event stream's action window, and the execution log's spawn
     * total. Which one was used travels with the answer, because plan 13.4
     * requires it to.
     *
     * @param fromAttempts true to weight by execution-log spawn time, false to
     *     weight by the action's own start and end
     */
    public long[] durationsByNodeIndex(boolean fromAttempts, long unknownDuration)
            throws SQLException {
        int nodes = Math.toIntExact(scalar(
                "SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
        long[] durations = new long[nodes];
        java.util.Arrays.fill(durations, unknownDuration);
        if (nodes == 0) {
            return durations;
        }
        String sql = fromAttempts
                ? "SELECT d.node_index, min(t.total_micros) FROM declared_actions d"
                        + " JOIN action_attempts t ON t.action_id = d.action_id"
                        + " WHERE d.node_index IS NOT NULL AND t.total_micros IS NOT NULL"
                        + " GROUP BY d.node_index"
                : "SELECT d.node_index, a.end_micros - a.start_micros FROM declared_actions d"
                        + " JOIN actions a ON a.id = d.action_id"
                        + " WHERE d.node_index IS NOT NULL"
                        + "   AND a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL";
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
     * The executed action behind each graph node, where there is one.
     *
     * <p>What turns a path of node indices into something another view can
     * highlight. Nodes with no executed action -- every test's TestRunner in a
     * `build` invocation -- are absent rather than mapped to zero.
     */
    public Map<Integer, Long> actionIdsByNodeIndex() throws SQLException {
        Map<Integer, Long> byNode = new java.util.HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
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
     * The target label behind each graph node, indexed by {@code node_index}.
     *
     * <p>What the cluster view groups by, once the caller has reduced a label to
     * its package. Nodes whose label the import never learned are left null
     * rather than filled with a placeholder: plan 11.4 wants unknown to stay
     * distinguishable from a real name all the way to the drawing, and a
     * clustering that invented "" here would show a package called nothing.
     */
    public String[] labelsByNodeIndex() throws SQLException {
        return keysByNodeIndex(
                "SELECT da.node_index, l.value FROM declared_actions da"
                        + " JOIN labels l ON l.id = da.label_id"
                        + " WHERE da.node_index IS NOT NULL");
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
     * <p>Dense rather than a map because the clustering pass indexes it once per
     * node, and because its length is then a checkable claim about coverage --
     * {@code GraphClustering} rejects an array that does not span the graph
     * instead of treating the shortfall as unknown.
     */
    private String[] keysByNodeIndex(String sql) throws SQLException {
        int nodes = Math.toIntExact(scalar(
                "SELECT coalesce(max(node_index), -1) + 1 FROM declared_actions"));
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
                out.add(new GraphSource(
                        rows.getString("kind"),
                        Optional.ofNullable(rows.getString("command")),
                        rows.getString("state"),
                        ConfigurationMatch.valueOf(rows.getString("configuration_match")),
                        Optional.ofNullable(rows.getString("mismatch_detail")),
                        declaredNull ? OptionalLong.empty() : OptionalLong.of(declared),
                        correlatedNull ? OptionalLong.empty() : OptionalLong.of(correlated),
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

    /** Nodes whose label matches {@code pattern}, which may contain {@code %}. */
    public List<GraphNode> search(String pattern, int limit) throws SQLException {
        List<GraphNode> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(NODES_BY_LABEL)) {
            statement.setString(1, pattern);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new GraphNode(
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
                long actionId = rows.getLong(5);
                boolean actionNull = rows.wasNull();
                return Optional.of(new GraphNode(
                        rows.getInt(1),
                        Optional.ofNullable(rows.getString(2)),
                        Optional.ofNullable(rows.getString(3)),
                        Optional.ofNullable(rows.getString(4)),
                        actionNull ? OptionalLong.empty() : OptionalLong.of(actionId)));
            }
        }
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
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int index = java.util.Arrays.binarySearch(universe, rows.getLong(1));
                    if (index < 0) {
                        // A label imported after the universe was read; the
                        // session is read-only in practice, but skipping is
                        // safer than inventing an index the CSR does not have.
                        continue;
                    }
                    out.add(new GraphNode(
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
                return Optional.of(new GraphNode(
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
     * <p>The label-graph flavour of {@link #labelsByNodeIndex()}: what the
     * canvas names nodes with and the clustering groups by, fetched once per
     * session rather than during any paint.
     */
    public String[] labelsByNodeIndex(GraphKind graphKind) throws SQLException {
        if (graphKind != GraphKind.CONFIGURED_TARGETS) {
            return labelsByNodeIndex();
        }
        long[] universe = labelUniverse();
        String[] out = new String[universe.length];
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT n.label_id, l.value FROM configured_target_nodes n"
                                + " JOIN labels l ON l.id = n.label_id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int index = java.util.Arrays.binarySearch(universe, rows.getLong(1));
                if (index >= 0) {
                    out[index] = rows.getString(2);
                }
            }
        }
        return out;
    }

    /**
     * One rule class per label-graph node; the label graph's answer to
     * {@link #mnemonicsByNodeIndex()}.
     *
     * <p>A label analysed in several configurations has one rule class — the
     * rule is the same rule — so {@code min} is a formality, not a choice
     * between different answers. Left null where cquery did not report one,
     * which stays distinguishable from a real name (plan 11.4).
     */
    public String[] ruleClassesByNodeIndex() throws SQLException {
        long[] universe = labelUniverse();
        String[] out = new String[universe.length];
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT n.label_id, min(n.rule_class) FROM configured_target_nodes n"
                                + " GROUP BY n.label_id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int index = java.util.Arrays.binarySearch(universe, rows.getLong(1));
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
     * <p>The same numbering {@link GraphIndexBuilder#configuredLabelUniverse}
     * built the CSR files with: a pure function of the imported rows, so the
     * index files and these queries cannot disagree about which label a node
     * id means unless the tables changed — and a changed table invalidates the
     * registered index by checksum anyway.
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
     * @param direction {@code true} for dependencies this action feeds,
     *     {@code false} for the ones that feed it
     */
    public List<GraphNode> neighbours(
            EdgeDerivation derivation, int nodeIndex, boolean forwards, int limit)
            throws SQLException, IOException {
        return neighbours(kindOf(derivation), nodeIndex, forwards, limit);
    }

    /** {@link #neighbours(EdgeDerivation, int, boolean, int)} for any indexed graph. */
    public List<GraphNode> neighbours(
            GraphKind graphKind, int nodeIndex, boolean forwards, int limit)
            throws SQLException, IOException {
        Optional<CsrGraph> graph = forwards
                ? forwardIndex(graphKind) : reverseIndex(graphKind);
        if (graph.isEmpty()) {
            return List.of();
        }
        List<Integer> targets = new ArrayList<>();
        graph.get().forEachNeighbor(nodeIndex, neighbour -> {
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
        Optional<CsrGraph> graph = forwards
                ? forwardIndex(graphKind) : reverseIndex(graphKind);
        return graph.map(value -> value.degree(nodeIndex)).orElse(0);
    }

    /**
     * The shortest path between two nodes.
     *
     * @return empty when there is no index to search
     */
    public Optional<ShortestPath.Result> path(
            EdgeDerivation derivation, int from, int to, long budget)
            throws SQLException, IOException {
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
                ? GraphKind.DECLARED_ACTIONS : GraphKind.OBSERVED_EXECUTION;
    }

    /**
     * The producer-to-consumer index, memory-mapped and cached.
     *
     * <p>Public because extraction and layout happen outside this module -- a
     * CSR graph is a {@code graph-core} value, not a SQLite implementation
     * detail, so handing one out does not put the UI back in touch with the
     * database the way rule 19 forbids. Empty when the index was never built,
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
     * <p>Three graphs have indexes: the two action graphs (declared and
     * observed edges, nodes are actions) and the configured-target label graph
     * (nodes are labels). Any other {@link GraphKind} has no CSR index and
     * gets an empty answer, which is the honest one — those graphs exist in
     * the schema but are not traversable this way.
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

    private Optional<CsrGraph> cached(
            Map<String, CsrGraph> into, String kind, String direction)
            throws SQLException, IOException {
        CsrGraph existing = into.get(kind);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<CsrGraph> loaded = kind.equals(GraphIndexBuilder.CONFIGURED_TARGETS_KIND)
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
     * @param configurationMatch the only thing that decides whether this graph
     *     may be presented as the build's (plan 8.6)
     */
    public record GraphSource(
            String kind,
            Optional<String> command,
            String state,
            ConfigurationMatch configurationMatch,
            Optional<String> mismatchDetail,
            OptionalLong declaredActions,
            OptionalLong correlatedActions,
            Optional<String> error) {

        /** True when this graph is loadable and describes this build. */
        public boolean isTrustworthy() {
            return state.equals("SUCCEEDED") && configurationMatch.permitsExactClaim();
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
         * <p>What the selector switches: a source row is a statement about an
         * import, and this is the graph that import populated. Empty for a
         * kind this build does not know how to traverse, which refuses rather
         * than guesses.
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
     * @param actionId the executed action this was matched to, absent when it
     *     was declared and never ran
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

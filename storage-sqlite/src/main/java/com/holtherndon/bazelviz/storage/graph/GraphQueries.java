package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.ShortestPath;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
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

    /** Neighbourhood rows returned before the caller is told to narrow down. */
    public static final int DEFAULT_NODE_BUDGET = 2_000;

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

    private final Connection connection;
    private final GraphIndexBuilder indexes;
    private final Map<EdgeDerivation, CsrGraph> forward = new EnumMap<>(EdgeDerivation.class);
    private final Map<EdgeDerivation, CsrGraph> reverse = new EnumMap<>(EdgeDerivation.class);

    public GraphQueries(Connection connection, Path indexDirectory) {
        this.connection = connection;
        this.indexes = new GraphIndexBuilder(connection, indexDirectory);
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

    /** True when an index for this derivation is loadable. */
    public boolean hasIndex(EdgeDerivation derivation) throws SQLException, IOException {
        return forwardIndex(derivation).isPresent();
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

    /**
     * The nodes directly reachable from {@code nodeIndex}.
     *
     * @param direction {@code true} for dependencies this action feeds,
     *     {@code false} for the ones that feed it
     */
    public List<GraphNode> neighbours(
            EdgeDerivation derivation, int nodeIndex, boolean forwards, int limit)
            throws SQLException, IOException {
        Optional<CsrGraph> graph = forwards
                ? forwardIndex(derivation) : reverseIndex(derivation);
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
            node(target).ifPresent(out::add);
        }
        return out;
    }

    /** How many direct neighbours a node has, whether or not they are listed. */
    public int degree(EdgeDerivation derivation, int nodeIndex, boolean forwards)
            throws SQLException, IOException {
        Optional<CsrGraph> graph = forwards
                ? forwardIndex(derivation) : reverseIndex(derivation);
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
        Optional<CsrGraph> forwardGraph = forwardIndex(derivation);
        Optional<CsrGraph> reverseGraph = reverseIndex(derivation);
        if (forwardGraph.isEmpty() || reverseGraph.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new ShortestPath(forwardGraph.get(), reverseGraph.get()).find(from, to, budget));
    }

    private Optional<CsrGraph> forwardIndex(EdgeDerivation derivation)
            throws SQLException, IOException {
        return cached(forward, derivation, "FORWARD");
    }

    private Optional<CsrGraph> reverseIndex(EdgeDerivation derivation)
            throws SQLException, IOException {
        return cached(reverse, derivation, "REVERSE");
    }

    private Optional<CsrGraph> cached(
            Map<EdgeDerivation, CsrGraph> into, EdgeDerivation derivation, String direction)
            throws SQLException, IOException {
        CsrGraph existing = into.get(derivation);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<CsrGraph> loaded = indexes.load(derivation, direction);
        loaded.ifPresent(graph -> into.put(derivation, graph));
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

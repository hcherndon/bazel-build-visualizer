package com.holtherndon.bazelviz.enrich.graph;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.WireFormat;
import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Imports a {@code cquery --output=proto} file: the configured-target graph.
 *
 * <h2>Same streaming rule as the action graph</h2>
 *
 * <p>{@code CqueryResult} is one message with a repeated {@code results} field,
 * so it has the same shape problem as {@code ActionGraphContainer} and gets the
 * same treatment: top-level tags are read with a {@code CodedInputStream} and
 * one {@code ConfiguredTarget} is held at a time.
 *
 * <h2>Edges are between labels, and that is Bazel's choice not ours</h2>
 *
 * <p>Dependencies come from {@code Rule.rule_input}, a repeated string of
 * labels. The proto has a richer field — {@code configured_rule_input}, which
 * carries a dependency's label together with its configuration checksum — and
 * Bazel populates it zero times on all four supported versions, with and
 * without {@code --proto:include_configurations} (finding Q12). It is read here
 * when present, so a future Bazel that starts filling it needs no change; it
 * simply never is.
 */
public final class ConfiguredTargetImporter {

    /** The kind recorded in {@code graph_sources}. */
    static final String KIND = "CONFIGURED_TARGETS";

    private final Connection connection;
    private final java.util.function.LongSupplier clock;

    public ConfiguredTargetImporter(Connection connection) {
        this(connection, () -> System.currentTimeMillis() * 1_000L);
    }

    ConfiguredTargetImporter(Connection connection, java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** Imports {@code file}. Never throws for a bad file; see the action-graph importer. */
    public Result importFrom(Path file, List<String> command) throws SQLException {
        long started = clock.getAsLong();
        long sourceId = beginSource(command, started);

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Result result = read(file, sourceId);
            finishSource(sourceId, "SUCCEEDED", Optional.empty(), result);
            connection.commit();
            return result;
        } catch (IOException | RuntimeException | SQLException failure) {
            rollbackQuietly();
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            Result failed = Result.failed(message);
            finishSource(sourceId, "FAILED", Optional.of(message), failed);
            connection.commit();
            return failed;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private Result read(Path file, long sourceId) throws IOException, SQLException {
        if (Files.size(file) == 0) {
            throw new ActionGraphImporter.EmptyQueryOutputException(file);
        }

        // Configurations arrive in their own top-level field and may follow the
        // results that reference them, so nodes are buffered until the whole
        // file is read. The buffer is bounded by the number of configured
        // targets, which is the graph -- so for a very large cquery this is the
        // one place Phase 5 does not stream. Recorded rather than hidden: see
        // the class comment on ActionGraphImporter for why the action graph,
        // which is the one that actually gets large, does not do this.
        List<Node> nodes = new ArrayList<>();
        Map<Integer, String> checksums = new HashMap<>();

        try (var stream = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            CodedInputStream in = CodedInputStream.newInstance(stream);
            in.setSizeLimit(Integer.MAX_VALUE);
            ExtensionRegistryLite registry = ExtensionRegistryLite.getEmptyRegistry();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) {
                    break;
                }
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> nodes.add(nodeOf(in.readMessage(ConfiguredTarget.parser(), registry)));
                    case 2 -> {
                        Configuration configuration =
                                in.readMessage(Configuration.parser(), registry);
                        if (!configuration.getChecksum().isEmpty()) {
                            checksums.put(configuration.getId(), configuration.getChecksum());
                        }
                    }
                    default -> in.skipField(tag);
                }
            }
        }

        long written = write(sourceId, nodes, checksums);
        long edges = scalar("SELECT count(*) FROM configured_target_edges e"
                + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
                + " WHERE n.source_id = " + sourceId);

        List<String> queried = checksums.values().stream().distinct().toList();
        List<String> declared = sessionConfigurations();
        ConfigurationMatch match = ConfigurationMatch.of(declared, queried);
        List<String> missing = declared.stream().filter(id -> !queried.contains(id)).toList();
        List<String> extra = queried.stream().filter(id -> !declared.contains(id)).toList();

        return new Result(written, edges, match, match.describe(missing, extra), Optional.empty());
    }

    private static Node nodeOf(ConfiguredTarget target) {
        Build.Target inner = target.getTarget();
        String label = inner.hasRule() ? inner.getRule().getName() : "";
        String ruleClass = inner.hasRule() ? inner.getRule().getRuleClass() : null;
        List<Dep> deps = new ArrayList<>();
        if (inner.hasRule()) {
            for (String input : inner.getRule().getRuleInputList()) {
                deps.add(new Dep(input, Optional.empty()));
            }
            // Read when present; empty on every supported version today (Q12).
            for (Build.ConfiguredRuleInput configured
                    : inner.getRule().getConfiguredRuleInputList()) {
                deps.add(new Dep(
                        configured.getLabel(),
                        configured.getConfigurationChecksum().isEmpty()
                                ? Optional.empty()
                                : Optional.of(configured.getConfigurationChecksum())));
            }
        }
        return new Node(label, ruleClass, target.getConfigurationId(), deps);
    }

    private long write(long sourceId, List<Node> nodes, Map<Integer, String> checksums)
            throws SQLException {
        try (PreparedStatement label = connection.prepareStatement(
                        "INSERT INTO labels (value) VALUES (?) ON CONFLICT DO NOTHING");
                PreparedStatement node = connection.prepareStatement(
                        "INSERT INTO configured_target_nodes (source_id, label_id,"
                                + " configuration_checksum, rule_class)"
                                + " VALUES (?, (SELECT id FROM labels WHERE value = ?), ?, ?)"
                                + " ON CONFLICT DO NOTHING");
                PreparedStatement edge = connection.prepareStatement(
                        "INSERT INTO configured_target_edges (from_node_id, to_label_id, attribute)"
                                + " SELECT n.id, (SELECT id FROM labels WHERE value = ?), ''"
                                + " FROM configured_target_nodes n"
                                + " WHERE n.source_id = ?"
                                + "   AND n.label_id = (SELECT id FROM labels WHERE value = ?)"
                                + " ON CONFLICT DO NOTHING")) {

            // Labels first, for both endpoints: an edge names a target that may
            // be a source file and therefore never appears as a node.
            for (Node value : nodes) {
                intern(label, value.label());
                for (Dep dep : value.deps()) {
                    intern(label, dep.label());
                }
            }
            label.executeBatch();

            for (Node value : nodes) {
                if (value.label().isEmpty()) {
                    continue;
                }
                node.setLong(1, sourceId);
                node.setString(2, value.label());
                String checksum = checksums.get(value.configurationId());
                if (checksum == null) {
                    node.setNull(3, java.sql.Types.VARCHAR);
                } else {
                    node.setString(3, checksum);
                }
                if (value.ruleClass() == null) {
                    node.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    node.setString(4, value.ruleClass());
                }
                node.addBatch();
            }
            node.executeBatch();

            for (Node value : nodes) {
                if (value.label().isEmpty()) {
                    continue;
                }
                for (Dep dep : value.deps()) {
                    edge.setString(1, dep.label());
                    edge.setLong(2, sourceId);
                    edge.setString(3, value.label());
                    edge.addBatch();
                }
            }
            edge.executeBatch();
        }
        return scalar("SELECT count(*) FROM configured_target_nodes WHERE source_id = " + sourceId);
    }

    private static void intern(PreparedStatement statement, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            return;
        }
        statement.setString(1, value);
        statement.addBatch();
    }

    private List<String> sessionConfigurations() throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT bep_id FROM configurations WHERE declared = 1")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        return ids;
    }

    private long beginSource(List<String> command, long started) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_sources (kind, command, state, configuration_match,"
                        + " started_micros) VALUES (?, ?, 'RUNNING', 'UNKNOWN', ?)"
                        + " ON CONFLICT (kind) DO UPDATE SET command = excluded.command,"
                        + " state = 'RUNNING', configuration_match = 'UNKNOWN',"
                        + " started_micros = excluded.started_micros,"
                        + " error_excerpt = NULL, mismatch_detail = NULL")) {
            statement.setString(1, KIND);
            statement.setString(2, String.join(" ", command));
            statement.setLong(3, started);
            statement.executeUpdate();
        }
        return scalar("SELECT id FROM graph_sources WHERE kind = '" + KIND + "'");
    }

    private void finishSource(
            long sourceId, String state, Optional<String> error, Result result)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE graph_sources SET state = ?, error_excerpt = ?, configuration_match = ?,"
                        + " mismatch_detail = ?, declared_actions = ?, finished_micros = ?"
                        + " WHERE id = ?")) {
            statement.setString(1, state);
            if (error.isPresent()) {
                statement.setString(2, error.get());
            } else {
                statement.setNull(2, java.sql.Types.VARCHAR);
            }
            statement.setString(3, result.configurationMatch().name());
            statement.setString(4, result.configurationDetail());
            statement.setLong(5, result.nodes());
            statement.setLong(6, clock.getAsLong());
            statement.setLong(7, sourceId);
            statement.executeUpdate();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the failure being reported is the one worth reporting
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private record Node(String label, String ruleClass, int configurationId, List<Dep> deps) {}

    private record Dep(String label, Optional<String> configurationChecksum) {}

    /** What an import did. */
    public record Result(
            long nodes,
            long edges,
            ConfigurationMatch configurationMatch,
            String configurationDetail,
            Optional<String> error) {

        static Result failed(String message) {
            return new Result(0, 0, ConfigurationMatch.UNKNOWN,
                    "the query did not produce a graph", Optional.of(message));
        }

        public boolean succeeded() {
            return error.isEmpty();
        }
    }
}

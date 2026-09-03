package com.holtherndon.bazelviz.enrich.graph;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Fragment;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.FragmentOptions;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Option;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.WireFormat;
import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.enrich.execlog.EnvironmentRedactor;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
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

    /**
     * Rows per JDBC batch.
     *
     * <p>The driver holds a batched statement's parameters until execution, so
     * an unbounded batch is an unbounded allocation. A cquery over a large
     * workspace has a configured target per target and several edges each; the
     * first version of this class batched all of them and executed once.
     */
    private static final int BATCH = 5_000;

    /**
     * Option names that can carry credentials but do not contain the general
     * environment-name words. Raw cquery bytes remain untouched; normalized
     * option values matching this or the execution-log defaults are withheld.
     */
    private static final EnvironmentRedactor OPTION_REDACTOR = optionRedactor();

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
        return importFrom(file, command, GraphTargetScope.UNKNOWN,
                GraphTargetScope.UNKNOWN.describe());
    }

    /** Imports {@code file} with the target-scope evidence captured beside the query. */
    public Result importFrom(
            Path file, List<String> command, GraphTargetScope targetScope, String scopeDetail)
            throws SQLException {
        long started = clock.getAsLong();
        long sourceId = beginSource(command, started, targetScope, scopeDetail);

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Result result = read(file, sourceId);
            finishSource(sourceId, "SUCCEEDED", Optional.empty(), result, file);
            connection.commit();
            return result;
        } catch (IOException | RuntimeException | SQLException failure) {
            rollbackQuietly();
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            Result failed = Result.failed(message);
            finishSource(sourceId, "FAILED", Optional.of(message), failed, file);
            connection.commit();
            return failed;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Records a cquery process failure even though there is no protobuf to import. */
    public Result recordFailure(Path file, List<String> command, String message)
            throws SQLException {
        return recordFailure(file, command, message, GraphTargetScope.UNKNOWN,
                GraphTargetScope.UNKNOWN.describe());
    }

    /** Records a failed cquery together with the scope it attempted. */
    public Result recordFailure(
            Path file,
            List<String> command,
            String message,
            GraphTargetScope targetScope,
            String scopeDetail) throws SQLException {
        long sourceId = beginSource(command, clock.getAsLong(), targetScope, scopeDetail);
        Result failed = Result.failed(message);
        finishSource(sourceId, "FAILED", Optional.of(message), failed, file);
        return failed;
    }

    private Result read(Path file, long sourceId) throws IOException, SQLException {
        if (Files.size(file) == 0) {
            throw new ActionGraphImporter.EmptyQueryOutputException(file);
        }

        // A source row is reused when enrichment is run again. Its previous
        // details must not survive a successful import of a smaller or older
        // payload. This delete is in the import transaction, so a failed
        // replacement rolls back to the prior rows while the source status
        // still reports that the new attempt failed.
        try (PreparedStatement clear = connection.prepareStatement(
                "DELETE FROM queried_configurations WHERE source_id = ?")) {
            clear.setLong(1, sourceId);
            clear.executeUpdate();
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

        try (ConfigurationWriter configurationWriter = new ConfigurationWriter(sourceId);
                var stream = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
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
                            configurationWriter.write(configuration);
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

    private static EnvironmentRedactor optionRedactor() {
        List<String> patterns = new ArrayList<>(EnvironmentRedactor.DEFAULT_PATTERNS);
        patterns.add("REMOTE_HEADER");
        patterns.add("REMOTE_EXEC_HEADER");
        return new EnvironmentRedactor(patterns);
    }

    /** Writes each configuration while only that protobuf is resident. */
    private final class ConfigurationWriter implements AutoCloseable {

        private final PreparedStatement configuration;
        private final PreparedStatement fragment;
        private final PreparedStatement option;
        private int pendingFragments;
        private int pendingOptions;

        ConfigurationWriter(long sourceId) throws SQLException {
            configuration = connection.prepareStatement(
                    "INSERT INTO queried_configurations"
                            + " (source_id, graph_id, checksum, mnemonic, platform_name,"
                            + " is_tool, options_available) VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (source_id, checksum) DO UPDATE SET"
                            + " graph_id=excluded.graph_id, mnemonic=excluded.mnemonic,"
                            + " platform_name=excluded.platform_name, is_tool=excluded.is_tool,"
                            + " options_available=excluded.options_available RETURNING id");
            configuration.setLong(1, sourceId);
            fragment = connection.prepareStatement(
                    "INSERT INTO queried_configuration_fragments"
                            + " (configuration_id, fragment_name, option_set_name, ordinal)"
                            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING");
            option = connection.prepareStatement(
                    "INSERT INTO queried_configuration_options"
                            + " (configuration_id, option_set_name, option_name, option_value,"
                            + " redacted, ordinal) VALUES (?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (configuration_id, option_set_name, ordinal)"
                            + " DO UPDATE SET option_name=excluded.option_name,"
                            + " option_value=excluded.option_value, redacted=excluded.redacted");
        }

        void write(Configuration value) throws SQLException {
            configuration.setInt(2, value.getId());
            configuration.setString(3, value.getChecksum());
            nullable(configuration, 4, value.getMnemonic());
            nullable(configuration, 5, value.getPlatformName());
            configuration.setInt(6, value.getIsTool() ? 1 : 0);
            configuration.setInt(7, value.getFragmentOptionsCount() > 0 ? 1 : 0);
            long rowId;
            try (ResultSet rows = configuration.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("cquery configuration insert returned no row id");
                }
                rowId = rows.getLong(1);
            }

            int fragmentOrdinal = 0;
            for (Fragment valueFragment : value.getFragmentsList()) {
                if (valueFragment.getFragmentOptionNamesCount() == 0) {
                    addFragment(rowId, valueFragment.getName(), "", fragmentOrdinal++);
                    continue;
                }
                for (String setName : valueFragment.getFragmentOptionNamesList()) {
                    addFragment(rowId, valueFragment.getName(), setName, fragmentOrdinal++);
                }
            }

            for (FragmentOptions set : value.getFragmentOptionsList()) {
                int ordinal = 0;
                for (Option valueOption : set.getOptionsList()) {
                    String name = valueOption.hasName() ? valueOption.getName() : "";
                    boolean withheld = valueOption.hasValue()
                            && OPTION_REDACTOR.shouldRedact(name);
                    option.setLong(1, rowId);
                    option.setString(2, set.getName());
                    option.setString(3, name);
                    if (!valueOption.hasValue() || withheld) {
                        option.setNull(4, java.sql.Types.VARCHAR);
                    } else {
                        option.setString(4, valueOption.getValue());
                    }
                    option.setInt(5, withheld ? 1 : 0);
                    option.setInt(6, ordinal++);
                    option.addBatch();
                    if (++pendingOptions >= BATCH) {
                        option.executeBatch();
                        pendingOptions = 0;
                    }
                }
            }
        }

        private void addFragment(long rowId, String name, String setName, int ordinal)
                throws SQLException {
            fragment.setLong(1, rowId);
            fragment.setString(2, name);
            fragment.setString(3, setName);
            fragment.setInt(4, ordinal);
            fragment.addBatch();
            if (++pendingFragments >= BATCH) {
                fragment.executeBatch();
                pendingFragments = 0;
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                fragment.executeBatch();
                option.executeBatch();
            } catch (SQLException e) {
                failure = e;
            }
            for (PreparedStatement statement : List.of(option, fragment, configuration)) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void nullable(PreparedStatement statement, int parameter, String value)
            throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(parameter, java.sql.Types.VARCHAR);
        } else {
            statement.setString(parameter, value);
        }
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
            int pending = 0;
            for (Node value : nodes) {
                pending += intern(label, value.label());
                for (Dep dep : value.deps()) {
                    pending += intern(label, dep.label());
                }
                if (pending >= BATCH) {
                    label.executeBatch();
                    pending = 0;
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
                if (++pending >= BATCH) {
                    node.executeBatch();
                    pending = 0;
                }
            }
            node.executeBatch();
            pending = 0;

            for (Node value : nodes) {
                if (value.label().isEmpty()) {
                    continue;
                }
                for (Dep dep : value.deps()) {
                    edge.setString(1, dep.label());
                    edge.setLong(2, sourceId);
                    edge.setString(3, value.label());
                    edge.addBatch();
                    if (++pending >= BATCH) {
                        edge.executeBatch();
                        pending = 0;
                    }
                }
            }
            edge.executeBatch();
        }
        return scalar("SELECT count(*) FROM configured_target_nodes WHERE source_id = " + sourceId);
    }

    /** @return 1 when a row was batched, 0 when the value was nothing */
    private static int intern(PreparedStatement statement, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        statement.setString(1, value);
        statement.addBatch();
        return 1;
    }

    /**
     * The configurations this build actually ran under.
     *
     * <p>Not every configuration the event stream mentioned. Bazel publishes a
     * {@code none} configuration — a placeholder with no mnemonic, for targets
     * that have no configuration at all (finding C3) — and no query can report
     * it, because it is not a configuration analysis produced. Comparing
     * against it made {@code EXACT} unreachable: the first version of this
     * check selected every declared configuration and returned {@code PARTIAL}
     * for a query that had in fact analysed exactly the right build.
     *
     * <p>So the set is the configurations at least one configured target was
     * built in. See {@code ActionGraphImporter} for the measurement.
     */
    private List<String> sessionConfigurations() throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT c.bep_id FROM configurations c"
                                + " JOIN configured_targets ct ON ct.configuration_id = c.id"
                                + " WHERE c.declared = 1")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        return ids;
    }

    private long beginSource(
            List<String> command,
            long started,
            GraphTargetScope targetScope,
            String scopeDetail) throws SQLException {
        // The sorted label universe is derived from this import. Remove its
        // registry first so a failed replacement or rebuild cannot leave the
        // preceding label graph available under the new source status.
        GraphIndexBuilder.invalidateConfiguredTargetIndexes(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_sources (kind, command, state, configuration_match,"
                        + " target_scope, target_scope_detail, started_micros)"
                        + " VALUES (?, ?, 'RUNNING', 'UNKNOWN', ?, ?, ?)"
                        + " ON CONFLICT (kind) DO UPDATE SET command = excluded.command,"
                        + " state = 'RUNNING', configuration_match = 'UNKNOWN',"
                        + " target_scope = excluded.target_scope,"
                        + " target_scope_detail = excluded.target_scope_detail,"
                        + " started_micros = excluded.started_micros,"
                        + " error_excerpt = NULL, mismatch_detail = NULL")) {
            statement.setString(1, KIND);
            statement.setString(2, String.join(" ", command));
            statement.setString(3, targetScope.name());
            statement.setString(4, scopeDetail);
            statement.setLong(5, started);
            statement.executeUpdate();
        }
        return scalar("SELECT id FROM graph_sources WHERE kind = '" + KIND + "'");
    }

    private void finishSource(
            long sourceId, String state, Optional<String> error, Result result, Path file)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE graph_sources SET state = ?, error_excerpt = ?, configuration_match = ?,"
                        + " mismatch_detail = ?, declared_actions = ?, raw_output_path = ?,"
                        + " raw_output_bytes = ?, finished_micros = ?"
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
            statement.setString(6, file.toString());
            try {
                statement.setLong(7, Files.exists(file) ? Files.size(file) : 0);
            } catch (IOException unreadable) {
                statement.setLong(7, 0);
            }
            statement.setLong(8, clock.getAsLong());
            statement.setLong(9, sourceId);
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

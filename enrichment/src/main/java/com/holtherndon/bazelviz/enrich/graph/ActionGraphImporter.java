package com.holtherndon.bazelviz.enrich.graph;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.storage.graph.GraphStaging;
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
import java.util.List;
import java.util.Optional;

/**
 * Imports an {@code aquery --output=proto} file into a session.
 *
 * <h2>Two passes over the file, in the only order that works</h2>
 *
 * <ol>
 *   <li>Stream every entity into temporary tables, resolving nothing. The file
 *       references entities before declaring them on three of four supported
 *       versions (Q4), so anything resolved here would be resolved against an
 *       incomplete picture.
 *   <li>Resolve: build the artifact paths from the fragment tree, intern them,
 *       then insert the graph proper with its references satisfied.
 * </ol>
 *
 * <h2>What it refuses to claim</h2>
 *
 * <p>Whether this graph is <em>this build's</em> is decided by comparing the
 * configurations the query reported against the ones the build event stream
 * published — a set comparison, because {@code Configuration.checksum} equals
 * the BEP's configuration id (Q6). A graph that does not match is still
 * imported and is marked, because its actions and edges are real and only their
 * relevance to this build is unestablished (plan 12.4).
 */
public final class ActionGraphImporter {

    /** The kind recorded in {@code graph_sources}. */
    static final String KIND = "DECLARED_ACTIONS";

    private final Connection connection;
    private final java.util.function.LongSupplier clock;

    public ActionGraphImporter(Connection connection) {
        this(connection, () -> System.currentTimeMillis() * 1_000L);
    }

    ActionGraphImporter(Connection connection, java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /**
     * Imports {@code file}.
     *
     * <p>Never throws for a bad file: the failure becomes a {@code graph_sources}
     * row and a returned result. Plan 24 requires a failed auxiliary query to
     * leave the rest of the session usable, and this writes only to tables
     * schema v5 added.
     */
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
        long size = Files.size(file);
        if (size == 0) {
            // A query naming a target that does not exist exits non-zero and
            // writes zero bytes (Q8). Reading that as an empty graph would
            // record a build with no actions.
            throw new EmptyQueryOutputException(file);
        }

        try (GraphStaging staging = new GraphStaging(connection);
                var stream = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            StagingVisitor visitor = new StagingVisitor(staging);
            new ActionGraphReader(visitor).read(stream);
            visitor.flush();

            staging.resolvePaths();
            long unresolved = staging.unresolvedArtifacts();

            long artifacts = internArtifacts();
            long depsets = insertDepsets(sourceId);
            long actions = insertActions(sourceId);
            long correlated = correlateActions(sourceId);

            List<String> queried = visitor.configurationChecksums();
            List<String> declared = sessionConfigurations();
            ConfigurationMatch match = ConfigurationMatch.of(declared, queried);
            List<String> missing = declared.stream().filter(id -> !queried.contains(id)).toList();
            List<String> extra = queried.stream().filter(id -> !declared.contains(id)).toList();

            return new Result(
                    actions, correlated, artifacts, depsets, unresolved, size,
                    match, match.describe(missing, extra), Optional.empty());
        }
    }

    // ------------------------------------------------------------- resolution

    /** Interns every resolved path into the shared {@code artifacts} table. */
    private long internArtifacts() throws SQLException {
        exec("INSERT INTO artifacts (path, is_directory)"
                + " SELECT p.path, max(a.is_tree) FROM stage_path p"
                + " JOIN stage_artifact a ON a.id = p.artifact"
                + " GROUP BY p.path"
                + " ON CONFLICT (path) DO UPDATE SET"
                + " is_directory = max(artifacts.is_directory, excluded.is_directory)");
        return scalar("SELECT count(*) FROM stage_path");
    }

    private long insertDepsets(long sourceId) throws SQLException {
        exec("DELETE FROM graph_depset_children WHERE parent_id IN"
                + " (SELECT id FROM graph_depsets WHERE source_id = " + sourceId + ")");
        exec("DELETE FROM graph_depset_artifacts WHERE depset_id IN"
                + " (SELECT id FROM graph_depsets WHERE source_id = " + sourceId + ")");
        exec("DELETE FROM graph_depsets WHERE source_id = " + sourceId);
        exec("INSERT INTO graph_depsets (source_id, graph_id)"
                + " SELECT " + sourceId + ", id FROM stage_depset");
        exec("INSERT INTO graph_depset_children (parent_id, child_id)"
                + " SELECT p.id, c.id FROM stage_depset_child sc"
                + " JOIN graph_depsets p ON p.source_id = " + sourceId
                + "   AND p.graph_id = sc.parent"
                + " JOIN graph_depsets c ON c.source_id = " + sourceId
                + "   AND c.graph_id = sc.child");
        exec("INSERT INTO graph_depset_artifacts (depset_id, artifact_id)"
                + " SELECT d.id, art.id FROM stage_depset_artifact sda"
                + " JOIN graph_depsets d ON d.source_id = " + sourceId
                + "   AND d.graph_id = sda.depset"
                + " JOIN stage_path sp ON sp.artifact = sda.artifact"
                + " JOIN artifacts art ON art.path = sp.path"
                + " ON CONFLICT DO NOTHING");
        return scalar("SELECT count(*) FROM graph_depsets WHERE source_id = " + sourceId);
    }

    private long insertActions(long sourceId) throws SQLException {
        // Re-importing replaces. The rows are keyed (source_id, graph_id) and
        // graph_sources reuses its row per kind, so without this a second
        // import violates the unique constraint rather than refreshing -- and
        // a stale half of a graph is worse than no graph.
        exec("DELETE FROM action_edges WHERE producer_id IN"
                + " (SELECT id FROM declared_actions WHERE source_id = " + sourceId + ")"
                + " OR consumer_id IN"
                + " (SELECT id FROM declared_actions WHERE source_id = " + sourceId + ")");
        exec("DELETE FROM declared_action_inputs WHERE action_row_id IN"
                + " (SELECT id FROM declared_actions WHERE source_id = " + sourceId + ")");
        exec("DELETE FROM declared_action_outputs WHERE action_row_id IN"
                + " (SELECT id FROM declared_actions WHERE source_id = " + sourceId + ")");
        exec("DELETE FROM declared_actions WHERE source_id = " + sourceId);

        exec("INSERT INTO labels (value) SELECT DISTINCT label FROM stage_target"
                + " WHERE label <> '' ON CONFLICT DO NOTHING");
        exec("INSERT INTO mnemonics (value) SELECT DISTINCT mnemonic FROM stage_action"
                + " WHERE mnemonic IS NOT NULL AND mnemonic <> '' ON CONFLICT DO NOTHING");

        exec("INSERT INTO declared_actions (source_id, graph_id, label_id, mnemonic_id,"
                + " configuration_checksum, primary_output_id, execution_platform, action_key,"
                + " discovers_inputs, is_executable)"
                + " SELECT " + sourceId + ", sa.ordinal,"
                + "   (SELECT id FROM labels WHERE value = st.label),"
                + "   (SELECT id FROM mnemonics WHERE value = sa.mnemonic),"
                + "   sc.checksum,"
                + "   (SELECT art.id FROM stage_path sp JOIN artifacts art ON art.path = sp.path"
                + "     WHERE sp.artifact = sa.primary_output),"
                + "   sa.execution_platform, sa.action_key, sa.discovers_inputs, sa.is_executable"
                + " FROM stage_action sa"
                + " LEFT JOIN stage_target st ON st.id = sa.target_id"
                + " LEFT JOIN stage_config sc ON sc.id = sa.config_id");

        exec("INSERT INTO declared_action_inputs (action_row_id, depset_id)"
                + " SELECT da.id, gd.id FROM stage_action_input sai"
                + " JOIN declared_actions da ON da.source_id = " + sourceId
                + "   AND da.graph_id = sai.ordinal"
                + " JOIN graph_depsets gd ON gd.source_id = " + sourceId
                + "   AND gd.graph_id = sai.depset"
                + " ON CONFLICT DO NOTHING");

        exec("INSERT INTO declared_action_outputs (action_row_id, artifact_id)"
                + " SELECT da.id, art.id FROM stage_action_output sao"
                + " JOIN declared_actions da ON da.source_id = " + sourceId
                + "   AND da.graph_id = sao.ordinal"
                + " JOIN stage_path sp ON sp.artifact = sao.artifact"
                + " JOIN artifacts art ON art.path = sp.path"
                + " ON CONFLICT DO NOTHING");

        // Dense node numbering for the CSR index, assigned once the rows exist.
        // Row ids keep growing across re-imports; a CSR is two arrays indexed
        // from zero with no room for gaps.
        exec("UPDATE declared_actions SET node_index = ("
                + "   SELECT count(*) FROM declared_actions earlier"
                + "    WHERE earlier.source_id = declared_actions.source_id"
                + "      AND earlier.id < declared_actions.id)"
                + " WHERE source_id = " + sourceId);

        return scalar("SELECT count(*) FROM declared_actions WHERE source_id = " + sourceId);
    }

    /**
     * Links declared actions to the ones that executed, by primary output path.
     *
     * <p>The same key the BEP uses for action identity (finding A1). Measured
     * 10 of 11 to 14 of 15 — and the unmatched rows on both sides are expected,
     * because the declared and executed populations overlap without either
     * containing the other (Q7).
     */
    private long correlateActions(long sourceId) throws SQLException {
        exec("UPDATE declared_actions SET action_id = ("
                + "   SELECT a.id FROM actions a JOIN artifacts art"
                + "     ON art.path = a.primary_output"
                + "   WHERE art.id = declared_actions.primary_output_id)"
                + " WHERE source_id = " + sourceId
                + "   AND primary_output_id IS NOT NULL");
        return scalar("SELECT count(*) FROM declared_actions"
                + " WHERE source_id = " + sourceId + " AND action_id IS NOT NULL");
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
     * built in, which is what "the configurations the build ran under" means.
     * Measured: two, and {@code aquery} reported those two and nothing else.
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

    // ------------------------------------------------------------ bookkeeping

    private long beginSource(List<String> command, long started) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_sources (kind, command, state, configuration_match,"
                        + " started_micros) VALUES (?, ?, 'RUNNING', 'UNKNOWN', ?)"
                        + " ON CONFLICT (kind) DO UPDATE SET command = excluded.command,"
                        + " state = 'RUNNING', configuration_match = 'UNKNOWN',"
                        + " started_micros = excluded.started_micros, exit_code = NULL,"
                        + " error_excerpt = NULL, mismatch_detail = NULL,"
                        + " declared_actions = NULL, correlated_actions = NULL")) {
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
                        + " mismatch_detail = ?, declared_actions = ?, correlated_actions = ?,"
                        + " raw_output_bytes = ?, finished_micros = ? WHERE id = ?")) {
            statement.setString(1, state);
            if (error.isPresent()) {
                statement.setString(2, error.get());
            } else {
                statement.setNull(2, java.sql.Types.VARCHAR);
            }
            statement.setString(3, result.configurationMatch().name());
            statement.setString(4, result.configurationDetail());
            statement.setLong(5, result.declaredActions());
            statement.setLong(6, result.correlatedActions());
            statement.setLong(7, result.bytes());
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

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /** The query produced no bytes, which means it failed. */
    static final class EmptyQueryOutputException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EmptyQueryOutputException(Path file) {
            super(file + " is empty. A query that names no analysable target exits non-zero"
                    + " and writes nothing, so this is a failed query rather than a build"
                    + " with no actions.");
        }
    }

    /**
     * What an import did.
     *
     * @param unresolvedArtifacts artifacts whose path could not be built from
     *     the fragment tree; non-zero means paths reached through a fragment the
     *     file never declared, and every total below is a lower bound
     */
    public record Result(
            long declaredActions,
            long correlatedActions,
            long artifacts,
            long depsets,
            long unresolvedArtifacts,
            long bytes,
            ConfigurationMatch configurationMatch,
            String configurationDetail,
            Optional<String> error) {

        static Result failed(String message) {
            return new Result(0, 0, 0, 0, 0, 0, ConfigurationMatch.UNKNOWN,
                    "the query did not produce a graph", Optional.of(message));
        }

        public boolean succeeded() {
            return error.isEmpty();
        }

        /** True when the graph may be described as this build's action graph. */
        public boolean matchesTheBuild() {
            return configurationMatch.permitsExactClaim();
        }
    }
}

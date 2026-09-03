package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index builder's contract with itself: what it writes, it loads; what it
 * cannot vouch for, it refuses.
 *
 * <p>The build path is also exercised end to end by the enrichment and
 * ui-swing suites; these are the storage module's own tests for the parts
 * those cannot isolate — the refusal of a stale file, the missing-file answer,
 * and the empty-graph answer, each of which is a different kind of "no".
 */
final class GraphIndexBuilderTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;
    private Path indexDirectory;

    @BeforeEach
    void buildFixture() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        indexDirectory = tempDir.resolve("indexes");
        exec("INSERT INTO graph_sources"
                + " (id, kind, state, configuration_match, unresolved_artifacts,"
                + " unresolved_depset_references)"
                + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT', 0, 0)");
        // A diamond: 1 feeds 2 and 3, both feed 4.
        for (int i = 1; i <= 4; i++) {
            exec("INSERT INTO labels (id, value) VALUES (" + i + ", '//d:t" + i + "')");
            exec("INSERT INTO declared_actions (id, source_id, graph_id, label_id, node_index)"
                    + " VALUES (" + i + ", 1, " + i + ", " + i + ", " + (i - 1) + ")");
        }
        exec("INSERT INTO action_edges (producer_id, consumer_id, derivation) VALUES"
                + " (1, 2, 'DECLARED'), (1, 3, 'DECLARED'),"
                + " (2, 4, 'DECLARED'), (3, 4, 'DECLARED')");
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("what was built loads back, in both directions, and they agree")
    void buildAndLoadRoundTrip() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

        Optional<GraphIndexBuilder.Result> built = builder.build(EdgeDerivation.DECLARED);

        assertThat(built).isPresent();
        assertThat(built.orElseThrow().nodeCount()).isEqualTo(4);
        assertThat(built.orElseThrow().edgeCount()).isEqualTo(4);

        CsrGraph forward = builder.load(EdgeDerivation.DECLARED, "FORWARD").orElseThrow();
        CsrGraph reverse = builder.load(EdgeDerivation.DECLARED, "REVERSE").orElseThrow();
        assertThat(forward.edgeCount()).isEqualTo(reverse.edgeCount());
        // The diamond, exactly: node 0 feeds 1 and 2; node 3 is fed by both.
        assertThat(forward.degree(0)).isEqualTo(2);
        assertThat(forward.degree(3)).isZero();
        assertThat(reverse.degree(3)).isEqualTo(2);
        assertThat(reverse.degree(0)).isZero();
        assertThat(scalar("SELECT count(*) FROM graph_indexes"
                + " WHERE kind = 'DECLARED' AND source_id = 1")).isEqualTo(2);
    }

    @Test
    @DisplayName("observed action indexes point to the declared-action query source")
    void observedIndexesCarryActionSource() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

        builder.build(EdgeDerivation.OBSERVED).orElseThrow();

        assertThat(scalar("SELECT count(*) FROM graph_indexes"
                + " WHERE kind = 'OBSERVED' AND source_id = 1")).isEqualTo(2);
    }

    @Test
    @DisplayName("an index tied to a failed graph source is unavailable")
    void failedSourceCannotServeItsIndex() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
        builder.build(EdgeDerivation.DECLARED).orElseThrow();

        exec("UPDATE graph_sources SET state = 'FAILED' WHERE id = 1");

        assertThat(builder.load(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
        assertThat(builder.load(EdgeDerivation.DECLARED, "REVERSE")).isEmpty();
    }

    @Test
    @DisplayName("a session with no graph builds nothing and says so with an empty")
    void emptyGraphIsNotAFailure() throws Exception {
        exec("DELETE FROM action_edges");
        exec("DELETE FROM declared_actions");
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

        assertThat(builder.build(EdgeDerivation.DECLARED)).isEmpty();
        assertThat(builder.load(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
    }

    @Test
    @DisplayName("an index that was never registered answers empty, not wrongly")
    void unregisteredIndexIsEmpty() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

        assertThat(builder.load(EdgeDerivation.OBSERVED, "FORWARD")).isEmpty();
        assertThat(builder.loadConfiguredTargets("FORWARD")).isEmpty();
    }

    @Test
    @DisplayName("a registered index whose file is gone answers empty rather than throwing")
    void missingFileIsEmpty() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
        GraphIndexBuilder.Result built = builder.build(EdgeDerivation.DECLARED).orElseThrow();

        Files.delete(built.forwardFile());

        assertThat(builder.load(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
    }

    @Test
    @DisplayName("a file that no longer matches its registration is refused, not used")
    void staleIndexIsRefused() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
        builder.build(EdgeDerivation.DECLARED);

        // The registry now describes a different graph than the file holds —
        // what a re-import without a rebuild would leave behind.
        exec("UPDATE graph_indexes SET edge_count = edge_count + 1 WHERE kind = 'DECLARED'");

        // A stale index is worse than none: it answers, and its answers look
        // like the others.
        assertThatThrownBy(() -> builder.load(EdgeDerivation.DECLARED, "FORWARD"))
                .isInstanceOf(GraphIndexBuilder.StaleIndexException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("rebuilding re-registers in place; the registry never grows a second row")
    void rebuildReplacesTheRegistration() throws Exception {
        GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
        builder.build(EdgeDerivation.DECLARED);
        builder.build(EdgeDerivation.DECLARED);

        assertThat(scalar("SELECT count(*) FROM graph_indexes WHERE kind = 'DECLARED'"))
                .isEqualTo(2);
        assertThat(builder.load(EdgeDerivation.DECLARED, "FORWARD")).isPresent();
    }

    // ------------------------------------------------------------- plumbing

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}

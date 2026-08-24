package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read side of the action graph, on its own module's terms.
 *
 * <p>Until now {@code GraphQueries} was covered only through the enrichment
 * and ui-swing suites, which means a regression here surfaced as a failure two
 * modules away. The fixture is a five-action chain with one action executed
 * and timed, so search, neighbourhood, degree, correlation and the
 * unknown-duration rule each have something to bite on.
 */
final class GraphQueriesTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;
    private GraphQueries queries;

    @BeforeEach
    void buildFixture() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO graph_sources (id, kind, state, configuration_match,"
                + " declared_actions, correlated_actions)"
                + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT', 5, 1)");
        exec("INSERT INTO graph_sources (id, kind, state, configuration_match, error_excerpt)"
                + " VALUES (2, 'CONFIGURED_TARGETS', 'FAILED', 'UNKNOWN', 'cquery exploded')");
        exec("INSERT INTO mnemonics (id, value) VALUES (1, 'Javac')");
        for (int i = 0; i < 5; i++) {
            exec("INSERT INTO labels (id, value) VALUES ("
                    + (i + 1) + ", '//chain:t" + i + "')");
            exec("INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 1) + ", 1, " + i + ", " + (i + 1) + ", 1, " + i + ")");
        }
        for (int i = 0; i + 1 < 5; i++) {
            exec("INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (" + (i + 1) + ", " + (i + 2) + ", 'DECLARED')");
        }
        // t2 ran, for 30 ms; nothing else did.
        exec("INSERT INTO actions (id, primary_output, outcome, start_micros, end_micros)"
                + " VALUES (7, 'bin/t2.out', 'OK', 1000, 31000)");
        exec("UPDATE declared_actions SET action_id = 7 WHERE id = 3");
        new GraphIndexBuilder(connection, tempDir.resolve("indexes"))
                .build(EdgeDerivation.DECLARED);
        queries = new GraphQueries(connection, tempDir.resolve("indexes"));
    }

    @AfterEach
    void closeDatabase() throws Exception {
        // queries shares the writer connection; closing the database is enough.
        database.close();
    }

    @Test
    @DisplayName("a label pattern finds its node, and the limit is honoured")
    void searchFindsByLabel() throws Exception {
        List<GraphQueries.GraphNode> found = queries.search("%:t3%", 10);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().nodeIndex()).isEqualTo(3);
        assertThat(found.getFirst().label()).contains("//chain:t3");

        assertThat(queries.search("%chain%", 2)).hasSize(2);
    }

    @Test
    @DisplayName("a node's detail names its label, mnemonic and executed action")
    void nodeDetailIsComplete() throws Exception {
        GraphQueries.GraphNode node = queries.node(2).orElseThrow();

        assertThat(node.label()).contains("//chain:t2");
        assertThat(node.mnemonic()).contains("Javac");
        assertThat(node.actionId()).hasValue(7);
        assertThat(node.declaredOnly()).isFalse();
        // And one that never ran says so through an absent action, not zero.
        assertThat(queries.node(0).orElseThrow().declaredOnly()).isTrue();
    }

    @Test
    @DisplayName("forward neighbours are what a node feeds; reverse are what feeds it")
    void neighboursFollowTheIndexDirection() throws Exception {
        List<GraphQueries.GraphNode> fed = queries.neighbours(
                EdgeDerivation.DECLARED, 2, true, 10);
        List<GraphQueries.GraphNode> feeding = queries.neighbours(
                EdgeDerivation.DECLARED, 2, false, 10);

        // The chain is t0 -> t1 -> t2 -> t3 -> t4 in producer-to-consumer
        // order: t2 feeds t3 and is fed by t1.
        assertThat(fed).extracting(GraphQueries.GraphNode::nodeIndex).containsExactly(3);
        assertThat(feeding).extracting(GraphQueries.GraphNode::nodeIndex).containsExactly(1);
        assertThat(queries.degree(EdgeDerivation.DECLARED, 2, true)).isEqualTo(1);
        assertThat(queries.degree(EdgeDerivation.DECLARED, 0, false)).isZero();
    }

    @Test
    @DisplayName("a graph with no index answers empty lists, never a guess")
    void missingIndexAnswersEmpty() throws Exception {
        // OBSERVED was never derived or built in this fixture.
        assertThat(queries.forwardIndex(EdgeDerivation.OBSERVED)).isEmpty();
        assertThat(queries.neighbours(EdgeDerivation.OBSERVED, 2, true, 10)).isEmpty();
        assertThat(queries.degree(EdgeDerivation.OBSERVED, 2, true)).isZero();
        assertThat(queries.path(EdgeDerivation.OBSERVED, 0, 4, 1000)).isEmpty();
        // And graph kinds that never have a CSR index refuse the same way.
        assertThat(queries.forwardIndex(GraphKind.BEP_EVENTS)).isEmpty();
    }

    @Test
    @DisplayName("a path is found within budget and walked end to end")
    void pathsAreFound() throws Exception {
        var result = queries.path(EdgeDerivation.DECLARED, 0, 4, 100_000).orElseThrow();

        assertThat(result.found()).isPresent();
        assertThat(result.found().orElseThrow()).startsWith(0).endsWith(4);
    }

    @Test
    @DisplayName("the executed action maps to its node, and unknown actions to nothing")
    void nodeForActionCorrelates() throws Exception {
        assertThat(queries.nodeForAction(7)).hasValue(2);
        assertThat(queries.nodeForAction(999)).isEmpty();
        assertThat(queries.actionIdsByNodeIndex()).containsExactly(java.util.Map.entry(2, 7L));
    }

    @Test
    @DisplayName("an untimed node keeps the unknown sentinel rather than a zero")
    void unknownDurationsStayUnknown() throws Exception {
        long[] durations = queries.durationsByNodeIndex(false, -1);

        assertThat(durations).hasSize(5);
        assertThat(durations[2]).isEqualTo(30_000);
        // Rule 11: "nothing measured this" and "this took no time" are
        // opposite claims, and the array must keep them apart.
        assertThat(durations[0]).isEqualTo(-1);
    }

    @Test
    @DisplayName("labels and mnemonics arrive as dense arrays spanning the graph")
    void perNodeArraysSpanTheGraph() throws Exception {
        String[] labels = queries.labelsByNodeIndex();
        String[] mnemonics = queries.mnemonicsByNodeIndex();

        assertThat(labels).hasSize(5);
        assertThat(labels[4]).isEqualTo("//chain:t4");
        assertThat(mnemonics[0]).isEqualTo("Javac");
    }

    @Test
    @DisplayName("sources report their state, and each names the graph it feeds")
    void sourcesCarryTheirTrust() throws Exception {
        List<GraphQueries.GraphSource> sources = queries.sources();

        assertThat(sources).hasSize(2);
        GraphQueries.GraphSource actions = sources.get(0);
        assertThat(actions.isTrustworthy()).isTrue();
        assertThat(actions.graphKind()).contains(GraphKind.DECLARED_ACTIONS);
        assertThat(actions.declaredActions()).hasValue(5);

        GraphQueries.GraphSource targets = sources.get(1);
        assertThat(targets.isTrustworthy()).isFalse();
        assertThat(targets.graphKind()).contains(GraphKind.CONFIGURED_TARGETS);
        assertThat(targets.error()).contains("cquery exploded");
    }

    // ------------------------------------------------------------- plumbing

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

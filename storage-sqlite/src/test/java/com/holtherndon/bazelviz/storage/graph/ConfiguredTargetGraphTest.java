package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configured-target label graph, end to end within storage: build the
 * index from the imported rows, load it, and answer deps and rdeps questions
 * over labels.
 *
 * <h2>The fixture, and what each row is there to prove</h2>
 *
 * <p>Three rules: {@code //app:bin} depends on {@code //lib:core}, which
 * depends on {@code //lib:base}. {@code //lib:core} is analysed in <em>two</em>
 * configurations — the case that makes this a label graph rather than a
 * configured-target graph, because the two nodes collapse to one label and
 * their identical dependency collapses to one edge. {@code //app:bin} also
 * names {@code main.c}, a source file no analysis covers, which must be
 * excluded from the graph and counted rather than silently dropped.
 */
final class ConfiguredTargetGraphTest {

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
        exec("INSERT INTO graph_sources (id, kind, state, configuration_match)"
                + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
        exec("INSERT INTO labels (id, value) VALUES"
                + " (1, '//app:bin'), (2, '//lib:core'), (3, '//lib:base'), (4, '//app:main.c')");
        exec("INSERT INTO configured_target_nodes"
                + " (id, source_id, label_id, configuration_checksum, rule_class) VALUES"
                + " (1, 1, 1, 'cfg-target', 'cc_binary'),"
                + " (2, 1, 2, 'cfg-target', 'cc_library'),"
                + " (3, 1, 2, 'cfg-exec',   'cc_library'),"
                + " (4, 1, 3, 'cfg-target', 'cc_library')");
        // bin -> core, bin -> main.c (a source file: no node anywhere),
        // core -> base in both of core's configurations.
        exec("INSERT INTO configured_target_edges (from_node_id, to_label_id, attribute) VALUES"
                + " (1, 2, 'deps'), (1, 4, 'srcs'), (2, 3, 'deps'), (3, 3, 'deps')");
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    private GraphIndexBuilder builder() {
        return new GraphIndexBuilder(connection, indexDirectory);
    }

    private GraphQueries queries() {
        return new GraphQueries(connection, indexDirectory);
    }

    @Test
    @DisplayName("nodes are labels: two configurations of one label are one node")
    void configurationsCollapseToLabels() throws Exception {
        Optional<GraphIndexBuilder.Result> built = builder().buildConfiguredTargets();

        assertThat(built).isPresent();
        // Four configured targets, three labels.
        assertThat(built.orElseThrow().nodeCount()).isEqualTo(3);
        // core -> base existed once per configuration and is one label edge;
        // bin -> core is the other. bin -> main.c has no node to land on.
        assertThat(built.orElseThrow().edgeCount()).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM graph_indexes"
                + " WHERE kind = 'CONFIGURED_TARGETS' AND source_id = 1")).isEqualTo(2);
    }

    @Test
    @DisplayName("an edge to a label the analysis did not cover is counted, not silent")
    void excludedEdgesAreCounted() throws Exception {
        GraphIndexBuilder.Result built = builder().buildConfiguredTargets().orElseThrow();

        // Rule 12: the source file's edge is not in the graph, and the build
        // result says exactly how many edges that happened to.
        assertThat(built.excludedEdges()).isEqualTo(1);
    }

    @Test
    @DisplayName("the forward index is dependency-to-depender, like the action graph's")
    void forwardMeansProducerToConsumer() throws Exception {
        builder().buildConfiguredTargets();
        CsrGraph forward = builder().loadConfiguredTargets("FORWARD").orElseThrow();

        // Node numbering is label_id order: bin=0, core=1, base=2. base feeds
        // core feeds bin, so forward walks base -> core -> bin.
        assertThat(forward.degree(2)).isEqualTo(1);
        List<Integer> fedByBase = neighbours(forward, 2);
        assertThat(fedByBase).containsExactly(1);
        assertThat(neighbours(forward, 1)).containsExactly(0);
        assertThat(forward.degree(0)).isZero();
    }

    @Test
    @DisplayName("a session with no cquery import builds nothing, which is not a failure")
    void noImportIsEmpty() throws Exception {
        exec("DELETE FROM configured_target_edges");
        exec("DELETE FROM configured_target_nodes");

        assertThat(builder().buildConfiguredTargets()).isEmpty();
        assertThat(queries().forwardIndex(GraphKind.CONFIGURED_TARGETS)).isEmpty();
    }

    @Test
    @DisplayName("a label search seeds a node, with its rule class along for the ride")
    void searchFindsLabels() throws Exception {
        builder().buildConfiguredTargets();
        GraphQueries queries = queries();

        List<GraphQueries.GraphNode> found =
                queries.search(GraphKind.CONFIGURED_TARGETS, "%lib:core%", 10);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().label()).contains("//lib:core");
        assertThat(found.getFirst().mnemonic()).contains("cc_library");
        // One hit however many configurations the label was analysed in.
        assertThat(found.getFirst().nodeIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("deps and rdeps of a label answer over labels")
    void neighboursTraverseLabels() throws Exception {
        builder().buildConfiguredTargets();
        GraphQueries queries = queries();
        int core = queries.search(GraphKind.CONFIGURED_TARGETS, "%lib:core%", 1)
                .getFirst().nodeIndex();

        // Reverse of "core feeds bin" is "core needs base".
        List<GraphQueries.GraphNode> needs = queries.neighbours(
                GraphKind.CONFIGURED_TARGETS, core, false, 10);
        List<GraphQueries.GraphNode> neededBy = queries.neighbours(
                GraphKind.CONFIGURED_TARGETS, core, true, 10);

        assertThat(needs).extracting(node -> node.label().orElseThrow())
                .containsExactly("//lib:base");
        assertThat(neededBy).extracting(node -> node.label().orElseThrow())
                .containsExactly("//app:bin");
        assertThat(queries.degree(GraphKind.CONFIGURED_TARGETS, core, true)).isEqualTo(1);
    }

    @Test
    @DisplayName("a path runs across the label graph the same as across the action graph")
    void pathsWorkOverLabels() throws Exception {
        builder().buildConfiguredTargets();

        var result = queries()
                .path(GraphKind.CONFIGURED_TARGETS, 2, 0, 10_000)
                .orElseThrow();

        assertThat(result.found()).isPresent();
        assertThat(result.found().orElseThrow()).containsExactly(2, 1, 0);
    }

    @Test
    @DisplayName("per-node labels and rule classes align with the numbering")
    void perNodeArraysAlign() throws Exception {
        builder().buildConfiguredTargets();
        GraphQueries queries = queries();

        assertThat(queries.labelGraphNodeCount()).isEqualTo(3);
        assertThat(queries.labelsByNodeIndex(GraphKind.CONFIGURED_TARGETS))
                .containsExactly("//app:bin", "//lib:core", "//lib:base");
        assertThat(queries.ruleClassesByNodeIndex())
                .containsExactly("cc_binary", "cc_library", "cc_library");
    }

    @Test
    @DisplayName("node lookups outside the graph answer empty rather than throwing")
    void outOfRangeNodesAreEmpty() throws Exception {
        builder().buildConfiguredTargets();

        assertThat(queries().node(GraphKind.CONFIGURED_TARGETS, 99)).isEmpty();
        assertThat(queries().node(GraphKind.CONFIGURED_TARGETS, -1)).isEmpty();
    }

    @Test
    @DisplayName("the numbering is a pure function of the rows: a rebuild changes nothing")
    void numberingIsDeterministic() throws Exception {
        builder().buildConfiguredTargets();
        String[] first = queries().labelsByNodeIndex(GraphKind.CONFIGURED_TARGETS);

        builder().buildConfiguredTargets();
        String[] second = queries().labelsByNodeIndex(GraphKind.CONFIGURED_TARGETS);

        // The mapping lives nowhere: builder and queries each derive it from
        // the sorted distinct label ids, so there is nothing stored to go
        // stale and nothing that can drift between them.
        assertThat(second).containsExactly(first);
        assertThat(scalar("SELECT count(*) FROM graph_indexes"
                + " WHERE kind = 'CONFIGURED_TARGETS'")).isEqualTo(2);
    }

    // ------------------------------------------------------------- plumbing

    private static List<Integer> neighbours(CsrGraph graph, int node) {
        List<Integer> out = new java.util.ArrayList<>();
        graph.forEachNeighbor(node, out::add);
        return out;
    }

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

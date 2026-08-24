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
    @DisplayName("the drawn name is findable: mnemonic and output basename match too")
    void searchFindsByMnemonicAndOutput() throws Exception {
        // The canvas draws "Javac — t2.out"; a search that could not match
        // either half would claim a graph full of Javac nodes has none.
        exec("INSERT INTO artifacts (id, path) VALUES (31, 'bazel-out/bin/chain/t2.out')");
        exec("UPDATE declared_actions SET primary_output_id = 31 WHERE id = 3");

        List<GraphQueries.GraphNode> byMnemonic = queries.search("%Javac%", 10);
        assertThat(byMnemonic).hasSize(5);

        List<GraphQueries.GraphNode> byOutput = queries.search("%t2.out%", 10);
        assertThat(byOutput).hasSize(1);
        assertThat(byOutput.getFirst().nodeIndex()).isEqualTo(2);

        // The limit binds the widened search exactly as it bound the old one.
        assertThat(queries.search("%Javac%", 2)).hasSize(2);
        // And a pattern matching none of the three parts is still an absence.
        assertThat(queries.search("%NoSuchThing%", 10)).isEmpty();
    }

    @Test
    @DisplayName("the label-graph search matches rule classes as well as labels")
    void labelGraphSearchFindsByRuleClass() throws Exception {
        exec("INSERT INTO configured_target_nodes (source_id, label_id, rule_class)"
                + " VALUES (2, 4, 'java_library')");
        GraphQueries reopened = new GraphQueries(connection, tempDir.resolve("indexes"));

        List<GraphQueries.GraphNode> byRule =
                reopened.search(GraphKind.CONFIGURED_TARGETS, "%java_library%", 10);

        // The search rows show the rule class beside the label, so a rule
        // class a user can read must be one they can type back.
        assertThat(byRule).hasSize(1);
        assertThat(byRule.getFirst().label()).contains("//chain:t3");
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
    @DisplayName("an exact label finds its node, and a longer label sharing its prefix does not")
    void nodeForLabelIsExact() throws Exception {
        // //chain:t3 is a proper prefix of //chain:t3_extra, which is the case
        // the old "%pattern%" jump got wrong: it would land on whichever the
        // LIKE happened to order first.
        exec("INSERT INTO labels (id, value) VALUES (6, '//chain:t3_extra')");
        exec("INSERT INTO declared_actions"
                + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                + " VALUES (6, 1, 5, 6, 1, 5)");

        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//chain:t3"))
                .hasValue(3);
        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//chain:t3_extra"))
                .hasValue(5);
        // A label this session never declared is absent, not node 0.
        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//no/such:target"))
                .isEmpty();
        // And the substring that would have matched both matches neither.
        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "chain:t3"))
                .isEmpty();
        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "%:t3%")).isEmpty();
    }

    @Test
    @DisplayName("one label's several actions answer with the same node every time")
    void nodeForLabelIsStableAcrossActions() throws Exception {
        // Two more actions for //chain:t0, at higher node indexes: a label
        // owns many actions in any real build, and a jump to the label must
        // not land somewhere different each time.
        exec("INSERT INTO declared_actions"
                + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                + " VALUES (7, 1, 6, 1, 1, 6)");
        exec("INSERT INTO declared_actions"
                + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                + " VALUES (8, 1, 7, 1, 1, 7)");

        assertThat(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//chain:t0"))
                .hasValue(0)
                .isEqualTo(queries.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//chain:t0"));
    }

    @Test
    @DisplayName("the label graph answers in its own numbering, and empty when it has no such node")
    void nodeForLabelInTheLabelGraph() throws Exception {
        // The fixture's cquery source failed, so the label graph is empty:
        // every label is absent there even though the action graph has it.
        assertThat(queries.nodeForLabel(GraphKind.CONFIGURED_TARGETS, "//chain:t3"))
                .isEmpty();

        // With configured targets imported, the answer is the label graph's
        // own index — the position in the sorted label universe, which is not
        // the action graph's node index for the same label. A fresh reader,
        // because the universe is loaded once per GraphQueries and the one
        // above has already read the empty one.
        exec("INSERT INTO configured_target_nodes (source_id, label_id, rule_class)"
                + " VALUES (2, 4, 'java_library')");
        exec("INSERT INTO configured_target_nodes (source_id, label_id, rule_class)"
                + " VALUES (2, 5, 'java_binary')");
        GraphQueries reopened = new GraphQueries(connection, tempDir.resolve("indexes"));

        assertThat(reopened.nodeForLabel(GraphKind.CONFIGURED_TARGETS, "//chain:t3"))
                .hasValue(0);
        assertThat(reopened.nodeForLabel(GraphKind.CONFIGURED_TARGETS, "//chain:t4"))
                .hasValue(1);
        assertThat(reopened.nodeForLabel(GraphKind.CONFIGURED_TARGETS, "//chain:t0"))
                .isEmpty();
        // The same label, two numberings: node 3 in the action graph, node 0
        // in the label graph. A jump that mixed them up would draw a stranger.
        assertThat(reopened.nodeForLabel(GraphKind.DECLARED_ACTIONS, "//chain:t3"))
                .hasValue(3);
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
    @DisplayName("an unsized output keeps the unknown sentinel rather than a zero")
    void unknownOutputSizesStayUnknown() throws Exception {
        // Node 0's output was sized; node 1's artifact exists but was never
        // measured; nodes 2..4 declare no primary output at all.
        exec("INSERT INTO artifacts (id, path, size_bytes)"
                + " VALUES (11, 'bin/t0.out', 2048)");
        exec("INSERT INTO artifacts (id, path) VALUES (12, 'bin/t1.out')");
        exec("UPDATE declared_actions SET primary_output_id = 11 WHERE id = 1");
        exec("UPDATE declared_actions SET primary_output_id = 12 WHERE id = 2");

        long[] sizes = queries.outputSizesByNodeIndex(-1);

        assertThat(sizes).hasSize(5);
        assertThat(sizes[0]).isEqualTo(2_048);
        // Rule 11 again: an artifact nothing measured and an empty file are
        // different facts, and the join must keep them apart.
        assertThat(sizes[1]).isEqualTo(-1);
        assertThat(sizes[2]).isEqualTo(-1);
    }

    @Test
    @DisplayName("display labels name each action distinctly: mnemonic and output basename")
    void displayLabelsAreDistinctPerAction() throws Exception {
        // The complaint this exists for: every action under one target showed
        // the target's label, so "all the labels are the same". t0 gets an
        // output; t1 keeps its mnemonic only; a node with neither mnemonic
        // nor label falls back to its basename; a node with nothing stays
        // null so the canvas can say "(name not recorded)".
        exec("INSERT INTO artifacts (id, path, size_bytes)"
                + " VALUES (21, 'bazel-out/k8-fastbuild/bin/chain/t0.o', 100)");
        exec("UPDATE declared_actions SET primary_output_id = 21 WHERE id = 1");
        exec("INSERT INTO artifacts (id, path) VALUES (22, 'bin/only.out')");
        exec("INSERT INTO declared_actions"
                + " (id, source_id, graph_id, node_index, primary_output_id)"
                + " VALUES (10, 1, 9, 5, 22)");
        exec("INSERT INTO declared_actions (id, source_id, graph_id, node_index)"
                + " VALUES (11, 1, 10, 6)");

        String[] names = queries.displayLabelsByNodeIndex();

        assertThat(names).hasSize(7);
        assertThat(names[0]).isEqualTo("Javac — t0.o");
        assertThat(names[1]).isEqualTo("Javac");
        assertThat(names[5]).isEqualTo("only.out");
        assertThat(names[6]).isNull();
        // Two actions of one target no longer collapse to one string.
        assertThat(names[0]).isNotEqualTo(names[1]);
    }

    @Test
    @DisplayName("the display-label grammar degrades honestly and never invents a blank")
    void composeDisplayLabelDegradesHonestly() {
        assertThat(GraphQueries.composeDisplayLabel("Javac", "bin/a/b.o", "//a:b"))
                .isEqualTo("Javac — b.o");
        assertThat(GraphQueries.composeDisplayLabel("Javac", null, "//a:b"))
                .isEqualTo("Javac");
        assertThat(GraphQueries.composeDisplayLabel(null, null, "//a:b"))
                .isEqualTo("//a:b");
        assertThat(GraphQueries.composeDisplayLabel(null, "bin/a/b.o", null))
                .isEqualTo("b.o");
        // A pathological path with nothing after the slash is not a name.
        assertThat(GraphQueries.composeDisplayLabel(null, "bin/a/", null)).isNull();
        assertThat(GraphQueries.composeDisplayLabel(null, null, null)).isNull();
        assertThat(GraphQueries.composeDisplayLabel("", "", "")).isNull();
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

package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Graph card as a card: its own source selector, its own way to pick a
 * node, and honest words when an action is not in the graph.
 *
 * <p>The canvas machinery itself is covered by the {@code GraphCanvasPanel}
 * suite; this covers what moved when the canvas left the old Graph card's
 * sub-tab and became a destination of its own.
 *
 * <p>Same fixture as {@code TreeViewSourceTest}: an action chain
 * {@code //pkg:t0 → //pkg:t1 → //pkg:t2} and a configured-target graph with
 * one extra label only the cquery knows.
 */
final class GraphExplorerViewTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private GraphExplorerView view;

    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        com.holtherndon.bazelviz.storage.schema.MigrationRunner.standard().migrate(database);
        Connection connection = database.writerConnection();
        exec(connection, "INSERT INTO graph_sources (id, kind, state, configuration_match,"
                + " declared_actions, correlated_actions)"
                + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT', 3, 0)");
        exec(connection, "INSERT INTO graph_sources (id, kind, state, configuration_match,"
                + " declared_actions)"
                + " VALUES (2, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT', 4)");
        exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Genrule')");
        for (int i = 0; i < 3; i++) {
            exec(connection, "INSERT INTO labels (id, value) VALUES ("
                    + (i + 1) + ", '//pkg:t" + i + "')");
            exec(connection, "INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 1) + ", 1, " + i + ", " + (i + 1) + ", 1, " + i + ")");
        }
        exec(connection, "INSERT INTO labels (id, value) VALUES (4, '//pkg:libextra')");
        exec(connection, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                + " VALUES (1, 2, 'DECLARED'), (2, 3, 'DECLARED')");
        exec(connection, "INSERT INTO configured_target_nodes (id, source_id, label_id,"
                + " rule_class) VALUES (1, 2, 1, 'genrule'), (2, 2, 2, 'genrule'),"
                + " (3, 2, 3, 'genrule'), (4, 2, 4, 'cc_library')");
        exec(connection, "INSERT INTO configured_target_edges (from_node_id, to_label_id,"
                + " attribute) VALUES (2, 1, 'srcs'), (3, 2, 'srcs')");
        GraphIndexBuilder builder =
                new GraphIndexBuilder(connection, tempDir.resolve("indexes"));
        builder.build(EdgeDerivation.DECLARED);
        builder.buildConfiguredTargets();

        view = new GraphExplorerView();
        view.setSize(1000, 700);
        view.openSession(new GraphOnlySource());
        awaitCondition(() -> view.sourceSelector().getItemCount() == 2,
                "the sources to install");
    }

    @AfterEach
    void closeSession() throws Exception {
        view.closeSession();
        database.close();
    }

    @Test
    @DisplayName("the trustworthy action graph is what a user who chooses nothing draws")
    void actionGraphIsPreferred() {
        assertThat(view.canvasPanel().shownGraph()).isEqualTo(GraphKind.DECLARED_ACTIONS);
        assertThat(view.detailLabel().getText()).contains("A node is one declared action");
        assertThat(view.canvasPanel().descriptionText()).contains("Pick an action");
    }

    @Test
    @DisplayName("switching the source switches what the canvas draws, in its words")
    void selectorSwitchesTheCanvas() throws Exception {
        selectConfiguredTargets();

        assertThat(view.canvasPanel().shownGraph()).isEqualTo(GraphKind.CONFIGURED_TARGETS);
        assertThat(view.detailLabel().getText()).contains("A node is one target label");
        // The canvas asks for a target, in words, rather than keeping the old
        // action-graph drawing that its numbering no longer describes.
        assertThat(view.canvasPanel().descriptionText()).contains("Pick a target");
    }

    @Test
    @DisplayName("a search draws the found node's neighbourhood")
    void searchDrawsTheNeighbourhood() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.searchForTesting("t1"));
        awaitCondition(
                () -> view.canvasPanel().descriptionText().contains("from a graph of 3"),
                "the neighbourhood to draw");

        assertThat(view.canvasPanel().descriptionText()).contains("Neighbourhood");
    }

    @Test
    @DisplayName("the label graph draws in target words, and its nodes map to no action")
    void canvasDrawsTargets() throws Exception {
        selectConfiguredTargets();

        SwingUtilities.invokeAndWait(() -> view.searchForTesting("t1"));
        awaitCondition(
                () -> view.canvasPanel().descriptionText().contains("from a graph of 4"),
                "the label-graph neighbourhood to draw");

        // A drawing of labels captioned "actions" would be the conflation the
        // selector exists to prevent.
        assertThat(view.canvasPanel().descriptionText())
                .contains("targets")
                .doesNotContain("actions");
        // A label-graph node never maps to an executed action; index 1 of the
        // label numbering must not open action 1's detail.
        assertThat(view.canvasPanel().actionIdAt(0)).isEmpty();
    }

    @Test
    @DisplayName("an action the graph never declared says so instead of drawing nothing")
    void undeclaredActionsAreNamed() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.showAction(9_999));
        awaitCondition(
                () -> view.statusForTesting().contains("not in the dependency graph"),
                "the honest absence message");

        // An empty canvas would read as "this depends on nothing", which is a
        // claim about the build rather than about the data.
        assertThat(view.statusForTesting())
                .contains("Some actions run without being declared by analysis");
    }

    @Test
    @DisplayName("typing in Find lists the matches, and choosing one lands on that exact node")
    void findListsMatchesAndLandsExactly() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.typeFindForTesting("t"));
        awaitCondition(() -> view.findResultsForTesting().size() == 3, "the find matches");

        assertThat(view.findTruncatedForTesting()).isFalse();
        // Built off the EDT and answered in order, so the user chooses
        // between near-misses instead of silently getting the first.
        int chosen = view.findResultsForTesting().get(1).nodeIndex();
        SwingUtilities.invokeAndWait(() -> view.chooseFindResultForTesting(1));
        awaitCondition(
                () -> view.canvasPanel().descriptionText().contains("from a graph of 3"),
                "the chosen node's neighbourhood");

        assertThat(view.canvasPanel().descriptionText()).contains("Neighbourhood");
        assertThat(view.canvasPanel().canvas().model().extract().nodes()).contains(chosen);
    }

    @Test
    @DisplayName("typing a drawn name's mnemonic finds its nodes, not a false absence")
    void findMatchesTheDrawnName() throws Exception {
        // Every fixture action draws as "Genrule" (no outputs recorded), so a
        // Find that answered "nothing matches Genrule" would be a false claim
        // about the graph. The search covers the parts the name is made of.
        SwingUtilities.invokeAndWait(() -> view.typeFindForTesting("Genrule"));
        awaitCondition(
                () -> view.findResultsForTesting().size() == 3, "the mnemonic matches");

        assertThat(view.statusForTesting()).isBlank();
    }

    @Test
    @DisplayName("a pattern nothing matches says so instead of listing nothing silently")
    void findAdmitsNoMatches() throws Exception {
        SwingUtilities.invokeAndWait(() -> view.typeFindForTesting("zzz"));
        awaitCondition(
                () -> view.statusForTesting().contains("matches zzz"),
                "the honest no-match sentence");

        assertThat(view.findResultsForTesting()).isEmpty();
    }

    @Test
    @DisplayName("find lists at most its limit and admits there is more")
    void findAdmitsTruncation() throws Exception {
        // Enough extra actions that the pattern has more matches than the
        // dropdown lists. Search reads the table live, so no re-index needed.
        Connection connection = database.writerConnection();
        for (int i = 3; i < 3 + GraphExplorerView.FIND_LIMIT + 4; i++) {
            exec(connection, "INSERT INTO labels (id, value) VALUES ("
                    + (i + 10) + ", '//pkg:extra_t" + i + "')");
            exec(connection, "INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 10) + ", 1, " + i + ", " + (i + 10) + ", 1, "
                    + i + ")");
        }

        SwingUtilities.invokeAndWait(() -> view.typeFindForTesting("t"));
        awaitCondition(
                () -> view.findTruncatedForTesting()
                        && view.findResultsForTesting().size() == GraphExplorerView.FIND_LIMIT,
                "the truncated find");

        assertThat(view.findResultsForTesting()).hasSize(GraphExplorerView.FIND_LIMIT);
        assertThat(view.findTruncatedForTesting()).isTrue();
    }

    @Test
    @DisplayName("browse lists the graph's nodes and a double-click lands on one")
    void browseListsAndLands() throws Exception {
        SwingUtilities.invokeAndWait(view::openBrowserForTesting);
        awaitCondition(
                () -> view.browserForTesting().listedEntriesForTesting().size() == 3,
                "the browse listing");

        // The root states the totals, so three rows cannot read as a
        // three-action build if the graph held more.
        assertThat(view.browserForTesting().rootLabelForTesting())
                .contains("3 action").contains("of 3 in this graph");
        assertThat(view.browserForTesting().summaryForTesting()).contains("Double-click");

        SwingUtilities.invokeAndWait(() -> view.browserForTesting().chooseForTesting(1));
        awaitCondition(
                () -> view.canvasPanel().descriptionText().contains("from a graph of 3"),
                "the browsed node's neighbourhood");
        assertThat(view.canvasPanel().descriptionText()).contains("Neighbourhood");
    }

    @Test
    @DisplayName("the browse filter narrows the listing as you type")
    void browseFilterNarrows() throws Exception {
        SwingUtilities.invokeAndWait(view::openBrowserForTesting);
        awaitCondition(
                () -> view.browserForTesting().listedEntriesForTesting().size() == 3,
                "the full browse listing");

        SwingUtilities.invokeAndWait(
                () -> view.browserForTesting().filterForTesting("t1"));
        awaitCondition(
                () -> view.browserForTesting().listedEntriesForTesting().size() == 1,
                "the filtered browse listing");

        assertThat(view.browserForTesting().listedEntriesForTesting().getFirst())
                .contains("t1");
    }

    // ------------------------------------------------------------- plumbing

    private void selectConfiguredTargets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < view.sourceSelector().getItemCount(); i++) {
                GraphQueries.GraphSource source = view.sourceSelector().getItemAt(i);
                if (source.graphKind()
                        .filter(GraphKind.CONFIGURED_TARGETS::equals).isPresent()) {
                    view.sourceSelector().setSelectedIndex(i);
                    return;
                }
            }
            throw new AssertionError("no configured-target source installed");
        });
    }

    private void awaitCondition(BooleanSupplier done, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what
                + "; the canvas says: " + view.canvasPanel().descriptionText()
                + "; the status says: " + view.statusForTesting());
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** A session source that answers only the graph question. */
    private final class GraphOnlySource implements SessionSource {

        @Override
        public GraphQueries openGraphQueries() {
            try {
                return new GraphQueries(
                        database.newReadConnection(), tempDir.resolve("indexes"));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public SessionInfo info() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public SessionReader openReader() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public EntityReader openEntityReader() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public MetricQueries openMetricQueries() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public com.holtherndon.bazelviz.ui.session.QueryReader openQueryReader() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public Connection openTimelineConnection() {
            throw new UnsupportedOperationException("the graph view never asks");
        }

        @Override
        public void close() {
            // The view closes the readers it opened; nothing else to release.
        }
    }
}

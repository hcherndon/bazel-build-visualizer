package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.util.List;
import java.sql.Connection;
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
 * What the view says about a drawing, which is the half a picture cannot say.
 *
 * <p>A view of nine actions from a build of ninety thousand looks exactly like a
 * build with nine actions. Plan 13.6's "never claim the omitted nodes do not
 * exist" therefore lives in the sentence under the canvas, and these tests are
 * about that sentence.
 */
final class GraphCanvasPanelTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private GraphQueries queries;
    private GraphLayoutService service;
    private GraphCanvasPanel panel;

    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        Connection connection = database.writerConnection();
        exec(connection, "INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
        exec(connection, "INSERT INTO graph_sources (id, kind, state, configuration_match)"
                + " VALUES (1, 'AQUERY', 'COMPLETE', 'EXACT')");
        exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Javac')");
        for (int i = 0; i < 6; i++) {
            exec(connection, "INSERT INTO labels (id, value) VALUES ("
                    + (i + 1) + ", '//a:target" + i + "')");
            exec(connection, "INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 1) + ", 1, " + i + ", " + (i + 1) + ", 1, " + i + ")");
        }
        for (int i = 0; i + 1 < 6; i++) {
            exec(connection, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (" + (i + 1) + ", " + (i + 2) + ", 'DECLARED')");
        }
        new GraphIndexBuilder(connection, tempDir.resolve("indexes"))
                .build(EdgeDerivation.DECLARED);

        queries = new GraphQueries(connection, tempDir.resolve("indexes"));
        service = new GraphLayoutService(queries);
        panel = new GraphCanvasPanel();
        panel.setSize(800, 600);
        panel.attach(
                service,
                queries.labelsByNodeIndex(),
                queries.durationsByNodeIndex(false, GraphModel.UNKNOWN_DURATION),
                queries.actionIdsByNodeIndex());
    }

    @AfterEach
    void closeSession() throws Exception {
        service.close();
        database.close();
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Waits for the background layout and the EDT callback that follows it. */
    private void awaitDrawn() throws Exception {
        awaitCondition(
                () -> !panel.descriptionText().startsWith("Drawing")
                        && panel.canvas().model().size() > 0,
                "a drawing");
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
        throw new AssertionError(
                "timed out waiting for " + what + "; the panel says: "
                        + panel.descriptionText());
    }

    @Test
    @DisplayName("a drawn neighbourhood names the totals it came from")
    void totalsAreShown() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // Not decoration: three actions drawn from a graph of six is a
        // different picture from a build with three actions.
        assertThat(panel.descriptionText())
                .contains("Neighbourhood")
                .contains("from a graph of 6");
    }

    @Test
    @DisplayName("a session with no timings says so instead of colouring everything cold")
    void untimedIsAdmitted() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // Nothing in this fixture ran, so nothing has a duration. Rule 11: the
        // legend has to say that rather than let a blue graph imply "all fast".
        assertThat(panel.canvas().model().untimedCount())
                .isEqualTo(panel.canvas().model().size());
        assertThat(panel.legendText()).contains("Nothing here was timed");
    }

    @Test
    @DisplayName("selecting a node that was never timed says so rather than showing zero")
    void selectionAdmitsMissingTiming() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        panel.canvas().select(0);

        assertThat(panel.legendText())
                .contains("//a:target")
                .contains("not timed in this session")
                .doesNotContain("0 ms");
    }

    @Test
    @DisplayName("nothing is omitted at this size, and the panel does not claim otherwise")
    void noFalseOmissionWarning() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // A standing "some things are hidden" notice would be as misleading as
        // hiding them silently.
        assertThat(panel.omissionText()).isBlank();
    }

    @Test
    @DisplayName("before an action is chosen the panel says what it is waiting for")
    void emptyStateExplainsItself() {
        assertThat(panel.descriptionText()).contains("Pick an action");
        assertThat(panel.canvas().model().size()).isZero();
    }

    @Test
    @DisplayName("detaching clears the drawing and says the graph is closed")
    void detachClears() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        panel.detach();

        assertThat(panel.canvas().model().size()).isZero();
        assertThat(panel.descriptionText()).contains("No dependency graph is open");
        assertThat(panel.legendText()).isBlank();
    }

    @Test
    @DisplayName("a graph too big to draw offers the three things plan 13.6 names")
    void overLimitOffersThreeWays() throws Exception {
        panel.raiseLimits(2, 2);
        panel.showNode(2);
        awaitCondition(panel::isOverLimitShown, "the over-limit bar to appear");

        // Raise, group, export. Never "there is nothing here".
        assertThat(panel.overLimitText()).isNotBlank();
        assertThat(panel.descriptionText()).doesNotContain("no actions");
    }

    @Test
    @DisplayName("narrowing a rooted view steps its depth down rather than doing nothing")
    void narrowingIsARealAction() throws Exception {
        panel.setDepthForTesting(4);
        panel.showNode(2);
        awaitDrawn();

        panel.narrowForTesting();
        awaitCondition(() -> panel.depthForTesting() == 3, "the depth to step down");

        // Plan 13.6 lists "refine filter" beside "raise limit" and "export".
        // A hint that the controls are above is not an offer.
        assertThat(panel.depthForTesting()).isEqualTo(3);
    }

    @Test
    @DisplayName("narrowing at the narrowest view says so instead of silently doing nothing")
    void narrowingHasAFloor() throws Exception {
        panel.setDepthForTesting(1);
        panel.showNode(2);
        awaitDrawn();

        panel.narrowForTesting();

        assertThat(panel.depthForTesting()).isEqualTo(1);
        assertThat(panel.descriptionText()).contains("already the narrowest");
    }

    @Test
    @DisplayName("a found path is drawn in the order the search returned it")
    void pathsAreDrawn() throws Exception {
        panel.showPath(
                List.of(0, 2, 5), com.holtherndon.bazelviz.analysis.GraphExtract.Mode.PATH);
        awaitDrawn();

        GraphModel model = panel.canvas().model();
        assertThat(model.size()).isEqualTo(3);
        // Laid out left to right in path order, not in node-id order.
        assertThat(model.layout().xAt(0)).isLessThan(model.layout().xAt(1));
        assertThat(model.layout().xAt(1)).isLessThan(model.layout().xAt(2));
        assertThat(panel.descriptionText()).startsWith("Path between two actions:");
    }

    @Test
    @DisplayName("a node with no executed action reports none rather than action zero")
    void nodesWithoutActionsReportNothing() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // This fixture declares six actions and ran none of them. A zero here
        // would open whichever row happened to have id 0.
        for (int position = 0; position < panel.canvas().model().size(); position++) {
            assertThat(panel.actionIdAt(position)).as("position %d", position).isEmpty();
        }
    }

    @Test
    @DisplayName("a cluster box is never mistaken for an action")
    void clusterBoxesHaveNoActionId() throws Exception {
        panel.setModeForTesting(com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CLUSTERS);
        awaitDrawn();

        // Cluster ordinals and node indices are different numbering schemes;
        // looking one up as the other would open an unrelated action.
        assertThat(panel.canvas().model().isCluster()).isTrue();
        assertThat(panel.actionIdAt(0)).isEmpty();
        assertThat(panel.legendText()).contains("groups holding 6 actions");
    }

    @Test
    @DisplayName("nothing is offered when everything fits")
    void noStandingWarning() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // A permanent notice trains a user to stop reading the place the real
        // warning appears.
        assertThat(panel.isOverLimitShown()).isFalse();
    }

    @Test
    @DisplayName("an estimate arrives before anyone waits for a drawing")
    void estimateIsFetched() throws Exception {
        panel.showNode(2);
        awaitDrawn();
        awaitCondition(() -> panel.estimate().isPresent(), "an estimate");

        LimitEstimate found = panel.estimate().orElseThrow();
        assertThat(found.totalNodes()).isEqualTo(6);
        assertThat(found.fits()).isTrue();
        assertThat(found.warning()).isEmpty();
        // A neighbourhood's size is not knowable without walking it, and the
        // estimate says so rather than passing a ceiling off as a count.
        assertThat(found.exact()).isFalse();
    }

    @Test
    @DisplayName("exporting the complete graph writes every node, not the drawn ones")
    void exportingTheCompleteGraph() throws Exception {
        panel.raiseLimits(2, 2);
        panel.showNode(2);
        awaitCondition(panel::isOverLimitShown, "the over-limit bar to appear");

        Path target = tempDir.resolve("exported.csv");
        java.util.concurrent.atomic.AtomicReference<GraphExport.Result> written =
                new java.util.concurrent.atomic.AtomicReference<>();
        panel.onExport(written::set, failure -> { });
        panel.exportComplete(target, GraphExport.Format.CSV);
        awaitCondition(() -> written.get() != null, "the export to finish");

        // Two could be drawn; six exist. That difference is the whole point of
        // offering export beside the limit.
        assertThat(written.get().nodes()).isEqualTo(6);
        assertThat(java.nio.file.Files.readString(written.get().files().get(0)))
                .contains("//a:target5");
    }

    @Test
    @DisplayName("exporting the visible graph writes what is on screen")
    void exportingTheVisibleGraph() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        Path target = tempDir.resolve("visible.dot");
        java.util.concurrent.atomic.AtomicReference<GraphExport.Result> written =
                new java.util.concurrent.atomic.AtomicReference<>();
        panel.onExport(written::set, failure -> { });
        panel.exportVisible(target, GraphExport.Format.DOT);
        awaitCondition(() -> written.get() != null, "the export to finish");

        assertThat(written.get().nodes()).isEqualTo(panel.canvas().model().size());
        assertThat(java.nio.file.Files.readString(written.get().primary()))
                .contains("Visible graph")
                .contains("from a graph of 6");
    }

    @Test
    @DisplayName("raising the limit is what makes an over-sized whole graph drawable")
    void raisingTheLimitRedraws() throws Exception {
        panel.raiseLimits(2, 2);
        panel.showNode(2);
        awaitDrawn();

        assertThat(panel.nodeLimit()).isEqualTo(2);
        // And back up: the same query, explicitly paid for.
        panel.raiseLimits(
                com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_NODE_LIMIT,
                com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_EDGE_LIMIT);
        awaitDrawn();
        assertThat(panel.descriptionText()).contains("from a graph of 6");
    }
}

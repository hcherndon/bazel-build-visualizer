package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 24's six Phase 7 exit criteria, one test each.
 *
 * <p>Written against a real session database and the real panel, because five
 * of the six are statements about what a user sees rather than about what a
 * function returns. The sixth — that panning and selection stay responsive —
 * is measured in {@code GraphCanvasScaleTest} at the plan's own limits, and is
 * only restated here.
 *
 * <p>The fixture is a 60-action chain across three packages. Small enough to
 * assert exactly, big enough that a limit of two is a real refusal rather than
 * a contrivance.
 */
final class Phase7ExitCriteriaTest {

    private static final int ACTIONS = 60;

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
        exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Javac'), (2, 'Genrule')");
        for (int i = 0; i < ACTIONS; i++) {
            exec(connection, "INSERT INTO labels (id, value) VALUES ("
                    + (i + 1) + ", '//pkg" + (i % 3) + ":target" + i + "')");
            exec(connection, "INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 1) + ", 1, " + i + ", " + (i + 1) + ", "
                    + (i % 2 + 1) + ", " + i + ")");
        }
        for (int i = 0; i + 1 < ACTIONS; i++) {
            exec(connection, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (" + (i + 1) + ", " + (i + 2) + ", 'DECLARED')");
        }
        new GraphIndexBuilder(connection, tempDir.resolve("indexes"))
                .build(EdgeDerivation.DECLARED);

        queries = new GraphQueries(connection, tempDir.resolve("indexes"));
        service = new GraphLayoutService(queries);
        panel = new GraphCanvasPanel();
        panel.setSize(900, 700);
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
                + "; the panel says: " + panel.descriptionText());
    }

    private void awaitDrawn() throws Exception {
        awaitCondition(
                () -> !panel.descriptionText().startsWith("Drawing")
                        && panel.canvas().model().size() > 0,
                "a drawing");
    }

    // ---------------------------------------------------------- criterion 1

    @Test
    @DisplayName("1. a small subgraph renders in full detail")
    void smallSubgraphsRenderInFullDetail() throws Exception {
        panel.showNode(30);
        awaitDrawn();
        // No layout pass happens in a headless test, so the canvas has no
        // bounds of its own until it is given some.
        panel.canvas().setSize(900, 700);
        panel.canvas().fitToView();

        // Two steps either way along the chain from node 30, so five nodes.
        // Every one drawn, every edge between them drawn, and the zoom lands in
        // the band that draws a label on each.
        GraphModel model = panel.canvas().model();
        assertThat(model.size()).isEqualTo(5);
        assertThat(model.extract().isComplete()).isTrue();
        assertThat(panel.canvas().detail()).isEqualTo(GraphCanvas.Detail.NEAR);
        for (int i = 0; i < model.size(); i++) {
            assertThat(model.displayLabelAt(i)).startsWith("//pkg");
        }
        assertThat(panel.isOverLimitShown()).isFalse();
    }

    // ---------------------------------------------------------- criterion 2

    @Test
    @DisplayName("2. a graph too large to draw aggregates on its own, without being asked")
    void largeGraphsAutomaticallyAggregate() throws Exception {
        panel.raiseLimits(4, 4);

        panel.setModeForTesting(GraphExtract.Mode.WHOLE);
        awaitCondition(panel::isAutomaticallyGrouped, "the automatic switch to clusters");
        awaitDrawn();

        // Asked for the whole build, given it grouped. Not a blank canvas with
        // an explanation, which is a refusal dressed as an answer.
        assertThat(panel.modeForTesting()).isEqualTo(GraphExtract.Mode.CLUSTERS);
        assertThat(panel.canvas().model().isCluster()).isTrue();
        assertThat(panel.canvas().model().size()).isEqualTo(3);
        assertThat(panel.descriptionText()).startsWith("Too big to draw action by action");
        assertThat(panel.overLimitText()).contains("Grouped because");
    }

    @Test
    @DisplayName("2b. the aggregation accounts for every action, not a sample of them")
    void aggregationLosesNothing() throws Exception {
        panel.raiseLimits(4, 4);
        panel.setModeForTesting(GraphExtract.Mode.WHOLE);
        awaitCondition(panel::isAutomaticallyGrouped, "the automatic switch to clusters");
        awaitDrawn();

        // Rule 12. Three boxes standing for sixty actions is only honest if the
        // three add up to the sixty.
        assertThat(panel.canvas().model().clustering().clusteredNodes()).isEqualTo(ACTIONS);
        assertThat(panel.canvas().model().clustering().clusteredEdges())
                .isEqualTo(ACTIONS - 1);
        assertThat(panel.legendText()).contains("holding " + ACTIONS + " actions");
    }

    // ---------------------------------------------------------- criterion 3

    @Test
    @DisplayName("3. the exact totals stay on screen whether or not anything was omitted")
    void exactTotalsRemainVisible() throws Exception {
        panel.showNode(30);
        awaitDrawn();

        // Five of sixty. Without the second number this is indistinguishable
        // from a build with five actions.
        assertThat(panel.descriptionText()).contains("from a graph of " + ACTIONS);

        panel.raiseLimits(4, 4);
        panel.setModeForTesting(GraphExtract.Mode.WHOLE);
        awaitCondition(panel::isAutomaticallyGrouped, "the automatic switch");
        awaitDrawn();

        assertThat(panel.descriptionText())
                .contains(String.valueOf(ACTIONS))
                .doesNotContain("no actions");
    }

    // ---------------------------------------------------------- criterion 4

    @Test
    @DisplayName("4. raising the limit takes a press, and the press is honoured")
    void raisingLimitsIsExplicit() throws Exception {
        panel.raiseLimits(4, 4);
        panel.setModeForTesting(GraphExtract.Mode.WHOLE);
        awaitCondition(panel::isAutomaticallyGrouped, "the automatic switch");
        awaitDrawn();

        // Nothing raised itself: the limit is still four and the view is
        // grouped until somebody says otherwise.
        assertThat(panel.nodeLimit()).isEqualTo(4);

        panel.drawItAnywayForTesting();
        awaitCondition(
                () -> panel.modeForTesting() == GraphExtract.Mode.WHOLE
                        && panel.canvas().model().size() == ACTIONS,
                "the whole build to be drawn");

        // And the press was honoured all the way: back to the view asked for,
        // at a limit that fits it.
        assertThat(panel.nodeLimit()).isGreaterThanOrEqualTo(ACTIONS);
        assertThat(panel.canvas().model().isCluster()).isFalse();
        assertThat(panel.canvas().model().extract().isComplete()).isTrue();
    }

    // ---------------------------------------------------------- criterion 5

    @Test
    @DisplayName("5. a layout that is cancelled places nothing, at every layout and size")
    void layoutIsCancellable() {
        GraphExtract.Result big = GraphExtract.whole(
                CsrBuilder.build(100_000, visitor -> {
                    for (int i = 0; i + 1 < 100_000; i++) {
                        visitor.edge(i, i + 1);
                    }
                }), 200_000, 200_000);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        for (GraphLayout.Kind kind : GraphLayout.Kind.values()) {
            GraphLayout.Result stopped = GraphLayout.run(kind, big, cancelled);

            // Not a partial placement. Half a graph drawn looks like an answer,
            // and is the more expensive mistake.
            assertThat(stopped.cancelled()).as("%s", kind).isTrue();
            assertThat(stopped.nodes()).as("%s", kind).isEmpty();
            assertThat(stopped.bounds()).as("%s", kind).isEmpty();
        }
    }

    @Test
    @DisplayName("5b. a superseded request is cancelled rather than left to finish")
    void supersededRequestsAreCancelled() throws Exception {
        panel.showNode(10);
        awaitDrawn();

        // Rapid changes, as a user dragging the depth control produces. What
        // must hold is that the last one is what ends up on screen.
        panel.setDepthForTesting(2);
        panel.setDepthForTesting(3);
        panel.setDepthForTesting(4);
        awaitCondition(() -> panel.canvas().model().size() == 9, "the last request to win");

        assertThat(panel.depthForTesting()).isEqualTo(4);
        assertThat(panel.canvas().model().size()).isEqualTo(9);
    }

    // ---------------------------------------------------------- criterion 6

    @Test
    @DisplayName("6. selection stays constant-time however large the drawing is")
    void selectionRemainsResponsive() throws Exception {
        panel.raiseLimits(1_000, 1_000);
        panel.setModeForTesting(GraphExtract.Mode.WHOLE);
        awaitDrawn();
        panel.canvas().setSize(900, 700);
        panel.canvas().fitToView();

        GraphModel model = panel.canvas().model();
        assertThat(model.size()).isEqualTo(ACTIONS);

        long start = System.nanoTime();
        int found = 0;
        for (int i = 0; i < model.size(); i++) {
            int x = (int) Math.round(panel.canvas().transform().screenX(model.layout().xAt(i)));
            int y = (int) Math.round(panel.canvas().transform().screenY(model.layout().yAt(i)));
            if (panel.canvas().positionAt(x, y).isPresent()) {
                found++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        // Every node is findable by clicking where it is drawn, and the
        // aggregate cost of doing so is nothing. The measurement that matters
        // for scale is GraphCanvasScaleTest, at fifty thousand nodes.
        assertThat(found).isEqualTo(ACTIONS);
        assertThat(millis).as("%d hit tests took %dms", ACTIONS, millis).isLessThan(1_000);
    }
}

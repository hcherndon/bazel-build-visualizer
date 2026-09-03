package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
    @DisplayName("the drawing form has explicit accessible labels and explained defaults")
    void drawingControlsAreExplicitAndExplained() {
        assertThat(namedLabel(panel, "graph.scopeLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.scope"));
        assertThat(namedLabel(panel, "graph.depthLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.depth"));
        assertThat(namedLabel(panel, "graph.nodeBudgetLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.nodeBudget"));
        assertThat(namedLabel(panel, "graph.layoutLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.layout"));
        assertThat(namedLabel(panel, "graph.edgesLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.edges"));
        assertThat(namedLabel(panel, "graph.nodeEncodingLabel").getLabelFor())
                .isSameAs(namedComponent(panel, "graph.nodeEncoding"));

        assertThat(panel.layoutForTesting()).isEqualTo(GraphLayout.Kind.HIERARCHY);
        assertThat(panel.edgeDisplayForTesting()).isEqualTo(GraphEdgeDisplay.DECLUTTERED);
        assertThat(panel.appearanceExplanationForTesting())
                .contains("Top-down primary dependency branches")
                .contains("shared and cyclic links")
                .contains("Node size and colour encode duration");
        assertThat(java.util.List.of(GraphLayout.Kind.values())).contains(
                GraphLayout.Kind.HIERARCHY,
                GraphLayout.Kind.LAYERED,
                GraphLayout.Kind.RADIAL,
                GraphLayout.Kind.LINEAR,
                GraphLayout.Kind.GRID);
    }

    @Test
    @DisplayName("the drawing controls use one compact row and reveal help on request")
    void drawingControlsAreCompactAndResponsive() throws Exception {
        JPanel controls = (JPanel) namedComponent(panel, "graph.controlsRow");
        Component helpPanel = namedComponent(panel, "graph.controlsHelpPanel");
        assertThat(controls.getLayout()).isInstanceOf(WrapLayout.class);
        assertThat(helpPanel.isVisible()).isFalse();
        assertThat(((javax.swing.JTextArea) namedComponent(panel, "graph.limitWarningText"))
                .getLineWrap()).isTrue();
        assertThat(((Container) namedComponent(panel, "graph.limitActions")).getLayout())
                .isInstanceOf(WrapLayout.class);

        int[] heights = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            controls.setSize(1_900, 200);
            heights[0] = controls.getPreferredSize().height;
            controls.setSize(600, 500);
            heights[1] = controls.getPreferredSize().height;
        });

        assertThat(heights[0]).as("wide default controls height").isLessThan(90);
        assertThat(heights[1]).as("narrow controls reflow").isGreaterThan(heights[0]);

        javax.swing.JToggleButton help = (javax.swing.JToggleButton)
                namedComponent(panel, "graph.controlsHelp");
        SwingUtilities.invokeAndWait(help::doClick);
        assertThat(helpPanel.isVisible()).isTrue();
        assertThat(((javax.swing.JTextArea) namedComponent(panel, "graph.scopeExplanation"))
                .getLineWrap()).isTrue();
        assertThat(((javax.swing.JTextArea) namedComponent(panel, "graph.appearanceExplanation"))
                .getLineWrap()).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            helpPanel.setSize(600, 200);
            ((Container) helpPanel).doLayout();
        });
        assertThat(helpPanel.getPreferredSize().height)
                .as("expanded help stays bounded")
                .isLessThan(120);
        assertThat(panel.scopeExplanationForTesting()).contains("selected action");
        assertThat(panel.appearanceExplanationForTesting())
                .contains("Top-down primary dependency branches")
                .contains("shared and cyclic links");
    }

    @Test
    @DisplayName("scope help follows the selected action or target graph")
    void scopeHelpUsesTheCurrentNodeKind() {
        assertThat(panel.scopeExplanationForTesting()).contains("selected action");

        panel.setShownGraph(com.holtherndon.bazelviz.core.graph.GraphKind.CONFIGURED_TARGETS);

        assertThat(panel.scopeExplanationForTesting())
                .contains("selected target")
                .doesNotContain("selected action");
    }

    @Test
    @DisplayName("attached display labels are what the canvas names actions with")
    void displayLabelsReachTheCanvas() throws Exception {
        // The per-action names — "Mnemonic — output basename" — arrive
        // separately from the target labels, because the complete export's
        // label column must keep meaning the target.
        String[] display = new String[6];
        for (int i = 0; i < 6; i++) {
            display[i] = "Javac — t" + i + ".o";
        }
        panel.attachActionDisplayLabels(display);

        panel.showNode(2);
        awaitDrawn();

        GraphModel model = panel.canvas().model();
        for (int i = 0; i < model.size(); i++) {
            assertThat(model.displayLabelAt(i)).startsWith("Javac — t");
            assertThat(model.canvasLabelAt(i))
                    .contains("Javac — t")
                    .contains("target //a:target");
        }
        // Two actions no longer read as the same string.
        assertThat(model.displayLabelAt(0)).isNotEqualTo(model.displayLabelAt(1));
    }

    @Test
    @DisplayName("selection names both the action and the target it belongs to")
    void selectionNamesTargetOwnership() throws Exception {
        String[] display = new String[6];
        for (int i = 0; i < display.length; i++) {
            display[i] = "Javac — t" + i + ".o";
        }
        panel.attachActionDisplayLabels(display);
        panel.showNode(2);
        awaitDrawn();

        panel.canvas().select(0);

        assertThat(panel.legendText())
                .contains("Javac — t")
                .contains("target //a:target")
                .contains("not timed in this session");
    }

    @Test
    @DisplayName("the original layouts remain selectable beside the hierarchy")
    void existingLayoutsRemainSelectable() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        SwingUtilities.invokeAndWait(() -> panel.setLayoutForTesting(GraphLayout.Kind.LAYERED));
        awaitCondition(
                () -> panel.canvas().model().layout().kind() == GraphLayout.Kind.LAYERED,
                "the layered drawing");

        assertThat(panel.layoutForTesting()).isEqualTo(GraphLayout.Kind.LAYERED);
        assertThat(panel.edgeDisplayForTesting()).isEqualTo(GraphEdgeDisplay.ALL);
        assertThat(panel.edgeDisplayEnabledForTesting()).isFalse();
        assertThat(panel.appearanceExplanationForTesting())
                .contains("Longest-path columns")
                .contains("Draw every dependency");
    }

    @Test
    @DisplayName("without display labels the canvas falls back to target labels, never blanks")
    void displayLabelsFallBackToTargetLabels() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        GraphModel model = panel.canvas().model();
        for (int i = 0; i < model.size(); i++) {
            assertThat(model.displayLabelAt(i)).startsWith("//a:target");
        }
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
        SwingUtilities.invokeAndWait(() -> panel.setModeForTesting(
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.WHOLE));

        panel.detach();

        assertThat(panel.canvas().model().size()).isZero();
        assertThat(panel.modeForTesting())
                .isEqualTo(com.holtherndon.bazelviz.analysis.GraphExtract.Mode.NEIGHBOURHOOD);
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
    @DisplayName("an oversized path is refused with exact counts and can use raised limits")
    void pathsRespectTheCurrentDrawingBudgets() throws Exception {
        panel.raiseLimits(2, 10);

        assertThat(panel.showPath(
                List.of(0, 2, 5),
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CRITICAL_PATH))
                .isFalse();
        assertThat(panel.descriptionText())
                .contains("3 actions and 2 dependencies")
                .contains("budget of 2 actions and 10 dependencies")
                .contains("Nothing was drawn");
        assertThat(panel.canvas().model().size()).isZero();

        panel.raiseLimits(3, 2);
        assertThat(panel.showPath(
                List.of(0, 2, 5),
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CRITICAL_PATH))
                .isTrue();
        awaitDrawn();
        assertThat(panel.canvas().model().extract().nodes()).containsExactly(0, 2, 5);
    }

    @Test
    @DisplayName("opening a node from a path returns to a neighbourhood")
    void nodeNavigationLeavesPathMode() throws Exception {
        panel.showPath(
                List.of(0, 2, 5), com.holtherndon.bazelviz.analysis.GraphExtract.Mode.PATH);
        awaitDrawn();

        panel.showNode(2);
        awaitCondition(
                () -> panel.modeForTesting()
                                == com.holtherndon.bazelviz.analysis.GraphExtract.Mode.NEIGHBOURHOOD
                        && panel.descriptionText().startsWith("Neighbourhood"),
                "the node neighbourhood to replace the path");

        assertThat(panel.canvas().model().extract().nodes()).contains(2);
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
        panel.exportComplete(target, GraphExport.Format.CSV);
        awaitCondition(
                () -> panel.legendText().startsWith("Wrote"), "the export to finish");

        // Two could be drawn; six exist. That difference is the whole point of
        // offering export beside the limit.
        assertThat(panel.legendText()).contains("Wrote 6 actions");
        assertThat(java.nio.file.Files.readString(tempDir.resolve("exported-nodes.csv")))
                .contains("//a:target5");
    }

    @Test
    @DisplayName("exporting the visible graph writes what is on screen")
    void exportingTheVisibleGraph() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        Path target = tempDir.resolve("visible.dot");
        panel.exportVisible(target, GraphExport.Format.DOT);
        awaitCondition(
                () -> panel.legendText().startsWith("Wrote"), "the export to finish");

        assertThat(panel.legendText())
                .contains("Wrote " + panel.canvas().model().size() + " actions");
        assertThat(java.nio.file.Files.readString(target))
                .contains("Visible graph")
                .contains("from a graph of 6");
    }

    @Test
    @DisplayName("a typed node limit is a real setting, not a hint")
    void typedNodeLimitIsHonoured() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        // Before the spinner existed, the only way to change the budget was
        // the over-limit bar's doubling button — so the one direction a user
        // could not go was down, and no exact number was reachable at all.
        SwingUtilities.invokeAndWait(() -> panel.nodeLimitControlForTesting().setValue(2));
        awaitCondition(panel::isOverLimitShown, "the smaller budget to be enforced");

        assertThat(panel.nodeLimit()).isEqualTo(2);
        assertThat(panel.overLimitText()).isNotBlank();

        SwingUtilities.invokeAndWait(() -> panel.nodeLimitControlForTesting().setValue(50));
        awaitCondition(() -> !panel.isOverLimitShown(), "the raised budget to fit");
        assertThat(panel.nodeLimit()).isEqualTo(50);
    }

    @Test
    @DisplayName("the spinner shows the limit however the limit was raised")
    void spinnerFollowsProgrammaticRaises() throws Exception {
        panel.raiseLimits(7, 7);

        // One number, two faces. A bar that raised the limit while the
        // control still showed the old one would make the control a lie.
        assertThat((Integer) panel.nodeLimitControlForTesting().getValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("cluster scope exposes a separate group budget instead of a dead node limit")
    void clusterScopeUsesAGroupBudget() throws Exception {
        int originalNodeBudget = panel.nodeLimit();

        SwingUtilities.invokeAndWait(() -> panel.setModeForTesting(
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CLUSTERS));
        assertThat(namedLabel(panel, "graph.nodeBudgetLabel").getText())
                .isEqualTo("Group budget:");
        assertThat((Integer) panel.nodeLimitControlForTesting().getValue())
                .isEqualTo(com.holtherndon.bazelviz.analysis.GraphClustering.DEFAULT_CLUSTER_LIMIT);

        SwingUtilities.invokeAndWait(() -> panel.nodeLimitControlForTesting().setValue(7));
        assertThat(panel.clusterLimitForTesting()).isEqualTo(7);
        assertThat(panel.nodeLimit()).isEqualTo(originalNodeBudget);

        SwingUtilities.invokeAndWait(() -> panel.setModeForTesting(
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.WHOLE));
        assertThat(namedLabel(panel, "graph.nodeBudgetLabel").getText())
                .isEqualTo("Node budget:");
        assertThat((Integer) panel.nodeLimitControlForTesting().getValue())
                .isEqualTo(originalNodeBudget);
    }

    @Test
    @DisplayName("a failed automatic grouping raises the group budget, not the node budget")
    void automaticGroupingRefusalUsesClusterRecovery() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.setModeForTesting(
                    com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CLUSTERS);
            panel.setGroupByForTesting(
                    com.holtherndon.bazelviz.analysis.GraphClustering.By.TARGET);
            panel.nodeLimitControlForTesting().setValue(1);
            panel.setModeForTesting(com.holtherndon.bazelviz.analysis.GraphExtract.Mode.WHOLE);
            panel.raiseLimits(2, 2);
        });
        awaitCondition(
                () -> panel.modeForTesting()
                                == com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CLUSTERS
                        && panel.isOverLimitShown()
                        && panel.descriptionText().contains("more than the 1"),
                "the exact cluster refusal");

        assertThat(panel.isAutomaticallyGrouped()).isFalse();
        assertThat(panel.overLimitText()).contains("Too many groups");
        assertThat(panel.refineTextForTesting()).isEqualTo("Try another grouping");
        int nodeBudget = panel.nodeLimit();

        panel.drawItAnywayForTesting();
        awaitDrawn();

        assertThat(panel.clusterLimitForTesting()).isGreaterThanOrEqualTo(6);
        assertThat(panel.nodeLimit()).isEqualTo(nodeBudget);
        assertThat(panel.canvas().model().isCluster()).isTrue();
        assertThat(panel.canvas().model().clustering().clusters()).hasSize(6);
    }

    @Test
    @DisplayName("switching graph sources removes the old whole-graph model immediately")
    void sourceSwitchCannotExposeOldNodeNumbering() throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.setModeForTesting(
                com.holtherndon.bazelviz.analysis.GraphExtract.Mode.WHOLE));
        awaitDrawn();
        assertThat(panel.canvas().model().size()).isEqualTo(6);

        boolean[] clearedInsideSwitch = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            panel.setShownGraph(
                    com.holtherndon.bazelviz.core.graph.GraphKind.CONFIGURED_TARGETS);
            clearedInsideSwitch[0] = panel.canvas().model().size() == 0;
        });

        assertThat(clearedInsideSwitch[0]).isTrue();
        assertThat(panel.canvas().selectedPositions()).isEmpty();
    }

    @Test
    @DisplayName("focusing a drawn node redraws the graph around it")
    void focusRecentresTheDrawing() throws Exception {
        panel.showNode(0);
        awaitDrawn();

        // The neighbourhood of node 0 at depth 2 along the chain: 0, 1, 2.
        GraphModel before = panel.canvas().model();
        assertThat(before.size()).isEqualTo(3);
        int position = -1;
        for (int i = 0; i < before.size(); i++) {
            if (before.nodeAt(i) == 2) {
                position = i;
            }
        }
        assertThat(position).isNotNegative();

        // Clicking used to select a node while the canvas kept drawing the
        // old root, so there was no way to walk the graph by looking at it.
        // Double-click and the context menu both land here.
        int at = position;
        SwingUtilities.invokeAndWait(() -> panel.focusOnPosition(at));
        awaitCondition(() -> panel.canvas().model().size() == 5,
                "the drawing to recentre on node 2");

        // Node 2's neighbourhood at depth 2 spans the whole six-node chain's
        // middle, 0..4 — reachable only from a drawing rooted at 2, which is
        // what proves the focus actually moved the root.
        GraphModel after = panel.canvas().model();
        java.util.Set<Integer> drawn = new java.util.HashSet<>();
        for (int i = 0; i < after.size(); i++) {
            drawn.add(after.nodeAt(i));
        }
        assertThat(drawn).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("selecting a weight restyles the drawing without re-laying it out")
    void weightRestylesWithoutRelayout() throws Exception {
        panel.showNode(2);
        awaitDrawn();
        GraphTransform before = panel.canvas().transform();

        SwingUtilities.invokeAndWait(
                () -> panel.setWeightForTesting(GraphWeight.IMMEDIATE_DEPS));
        awaitCondition(
                () -> panel.legendText().contains("Colour and size are immediate dependencies"),
                "the weight legend");

        // The camera did not move: positions are weight-independent, so a
        // weight change is a restyle, never a re-layout or a re-fit.
        assertThat(panel.canvas().transform()).isSameAs(before);
        assertThat(panel.canvas().model().weight())
                .isEqualTo(GraphWeight.IMMEDIATE_DEPS);
        // The chain 0→1→…→5: every drawn node except the head has exactly
        // one immediate dependency, and the legend names the scale.
        assertThat(panel.legendText()).contains("up to 1");
    }

    @Test
    @DisplayName("on-screen transitive counts are exact and the whole-graph count is budgeted")
    void transitiveWeightsCountTheChain() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        SwingUtilities.invokeAndWait(
                () -> panel.setWeightForTesting(GraphWeight.TRANSITIVE_DEPS));
        awaitCondition(
                () -> panel.legendText()
                        .contains("Colour and size are transitive dependencies on screen"),
                "the transitive weight legend");

        // showNode(2) at depth 2 draws nodes 0..4 of the chain, so node 4
        // has four on-screen transitive dependencies and the scale says so.
        assertThat(panel.legendText()).contains("up to 4");

        GraphModel model = panel.canvas().model();
        int position = -1;
        for (int i = 0; i < model.size(); i++) {
            if (model.nodeAt(i) == 2) {
                position = i;
            }
        }
        assertThat(position).isNotNegative();
        int at = position;
        SwingUtilities.invokeAndWait(() -> panel.canvas().select(at));
        awaitCondition(
                () -> panel.legendText().contains("whole graph: 2"),
                "the budgeted whole-graph count");

        // Node 2 of the six-node chain transitively needs 0 and 1: the
        // whole-graph BFS finished under its budget, so the number is plain
        // rather than a "≥" lower bound.
        assertThat(panel.legendText())
                .contains("transitive dependencies on screen: 2")
                .contains("whole graph: 2")
                .doesNotContain("budget reached");
    }

    @Test
    @DisplayName("absent output sizes are unavailable, never zero bytes")
    void absentSizesAreUnavailable() throws Exception {
        panel.showNode(2);
        awaitDrawn();

        SwingUtilities.invokeAndWait(
                () -> panel.setWeightForTesting(GraphWeight.OUTPUT_SIZE));
        awaitCondition(
                () -> panel.legendText().contains("Nothing here has a recorded output size"),
                "the size legend");

        // This fixture records no artifact sizes. Rule 11: the legend admits
        // the absence instead of colouring six empty files.
        assertThat(panel.legendText())
                .contains("Nothing here has a recorded output size")
                .doesNotContain("0 B");
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

    private static JLabel namedLabel(Container root, String name) {
        Component found = namedComponent(root, name);
        if (found instanceof JLabel label) {
            return label;
        }
        throw new AssertionError(name + " is not a label");
    }

    private static Component namedComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                try {
                    return namedComponent(nested, name);
                } catch (AssertionError ignored) {
                    // Keep looking in sibling containers.
                }
            }
        }
        throw new AssertionError("No component named " + name);
    }
}

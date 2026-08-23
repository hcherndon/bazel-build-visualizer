package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
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
                queries.durationsByNodeIndex(false, GraphModel.UNKNOWN_DURATION));
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
        BooleanSupplier drawn = () -> !panel.descriptionText().startsWith("Drawing")
                && panel.canvas().model().size() > 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (drawn.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the panel never finished drawing: " + panel.descriptionText());
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

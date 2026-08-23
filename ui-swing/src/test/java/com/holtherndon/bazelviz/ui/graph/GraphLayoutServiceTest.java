package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The service that keeps layout off the event thread.
 *
 * <p>Built on a real session database with a real CSR index file rather than a
 * stub, because the two things most likely to be wrong — that the node indices
 * line up with the labels, and that the index on disk is the graph the layout
 * gets — only exist once the storage is real.
 */
final class GraphLayoutServiceTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private GraphQueries queries;
    private GraphLayoutService service;

    /**
     * Six actions in two packages, chained so the graph has a direction.
     *
     * <p>{@code //a:one → //a:two → //a:three → //b:one → //b:two → //b:three}.
     */
    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        Connection connection = database.writerConnection();
        exec(connection, "INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
        exec(connection, "INSERT INTO graph_sources (id, kind, state, configuration_match)"
                + " VALUES (1, 'AQUERY', 'COMPLETE', 'EXACT')");
        exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Javac'), (2, 'Genrule')");
        for (int i = 0; i < 6; i++) {
            String label = (i < 3 ? "//a:" : "//b:") + "target" + i;
            exec(connection, "INSERT INTO labels (id, value) VALUES ("
                    + (i + 1) + ", '" + label + "')");
            exec(connection, "INSERT INTO declared_actions"
                    + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
                    + " VALUES (" + (i + 1) + ", 1, " + i + ", " + (i + 1) + ", "
                    + (i < 3 ? 1 : 2) + ", " + i + ")");
        }
        for (int i = 0; i + 1 < 6; i++) {
            exec(connection, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (" + (i + 1) + ", " + (i + 2) + ", 'DECLARED')");
        }
        new GraphIndexBuilder(connection, tempDir.resolve("indexes"))
                .build(EdgeDerivation.DECLARED);

        queries = new GraphQueries(connection, tempDir.resolve("indexes"));
        service = new GraphLayoutService(queries);
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

    /** Submits and blocks until the callback fires, or fails the test. */
    private GraphLayoutService.Rendered await(GraphLayoutService.Request request) throws Exception {
        return await(service, request);
    }

    private static GraphLayoutService.Rendered await(
            GraphLayoutService target, GraphLayoutService.Request request) throws Exception {
        AtomicReference<GraphLayoutService.Rendered> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        target.submit(
                request,
                rendered -> {
                    result.set(rendered);
                    done.countDown();
                },
                error -> {
                    failure.set(error);
                    done.countDown();
                });
        assertThat(done.await(10, TimeUnit.SECONDS)).as("layout finished").isTrue();
        assertThat(failure.get()).isNull();
        return result.get();
    }

    @Test
    @DisplayName("a neighbourhood comes back extracted, laid out and described")
    void neighbourhoodIsRendered() throws Exception {
        GraphLayoutService.Rendered rendered =
                await(GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 2, 1));

        assertThat(rendered.extract().nodes()).contains(1, 2, 3);
        assertThat(rendered.layout().kind()).isEqualTo(GraphLayout.Kind.RADIAL);
        assertThat(rendered.layout().size()).isEqualTo(rendered.extract().nodes().size());
        assertThat(rendered.description()).contains("from a graph of 6");
        assertThat(rendered.isCluster()).isFalse();
    }

    @Test
    @DisplayName("the answer arrives on the event dispatch thread")
    void callbacksLandOnTheEdt() throws Exception {
        AtomicReference<Boolean> onEdt = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        service.submit(
                GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2),
                rendered -> {
                    // Rule 8 is about not blocking the EDT, but a callback that
                    // touched Swing from the worker would be the same class of
                    // bug from the other side.
                    onEdt.set(SwingUtilities.isEventDispatchThread());
                    done.countDown();
                },
                error -> done.countDown());

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(onEdt.get()).isTrue();
    }

    @Test
    @DisplayName("the same request twice is computed once")
    void repeatedRequestsAreCached() throws Exception {
        GraphLayoutService.Request request =
                GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2);

        GraphLayoutService.Rendered first = await(request);
        GraphLayoutService.Rendered second = await(request);

        // Same instance, not merely equal: the cache returned it rather than
        // laying the graph out again.
        assertThat(second).isSameAs(first);
        assertThat(service.cachedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("changing only the layout is a different cache entry")
    void settingsArePartOfTheKey() throws Exception {
        GraphLayoutService.Request radial =
                GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2);

        GraphLayoutService.Rendered ringed = await(radial);
        GraphLayoutService.Rendered layered = await(radial.withLayout(GraphLayout.Kind.LAYERED));

        // Plan 13.7 says cached by query AND settings. The same neighbourhood
        // drawn two ways is two pictures, and a query-only key would hand back
        // the wrong one.
        assertThat(layered).isNotSameAs(ringed);
        assertThat(layered.layout().kind()).isEqualTo(GraphLayout.Kind.LAYERED);
        assertThat(service.cachedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the cache does not grow without bound")
    void cacheIsBounded() throws Exception {
        for (int depth = 1; depth <= GraphLayoutService.CACHE_ENTRIES + 5; depth++) {
            await(GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, depth));
        }

        assertThat(service.cachedCount()).isEqualTo(GraphLayoutService.CACHE_ENTRIES);
    }

    @Test
    @DisplayName("invalidating drops everything, for when the indexes are rebuilt")
    void invalidateClears() throws Exception {
        await(GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2));
        service.invalidate();

        assertThat(service.cachedCount()).isZero();
    }

    @Test
    @DisplayName("clustering by package groups the session into its two packages")
    void clusteringByPackage() throws Exception {
        GraphLayoutService.Rendered rendered = await(
                GraphLayoutService.Request.clustered(
                        EdgeDerivation.DECLARED, GraphClustering.By.PACKAGE));

        assertThat(rendered.isCluster()).isTrue();
        assertThat(rendered.clustering().clusters())
                .extracting(GraphClustering.Cluster::key)
                .containsExactly("//a", "//b");
        // Every action still accounted for, even though only two boxes are
        // drawn -- which is the whole promise of the cluster view.
        assertThat(rendered.clustering().clusteredNodes()).isEqualTo(6);
        assertThat(rendered.description()).contains("covering all 6 actions");
        assertThat(rendered.layout().kind()).isEqualTo(GraphLayout.Kind.GRID);
    }

    @Test
    @DisplayName("clustering by mnemonic uses the mnemonic, not the label")
    void clusteringByMnemonic() throws Exception {
        GraphLayoutService.Rendered rendered = await(
                GraphLayoutService.Request.clustered(
                        EdgeDerivation.DECLARED, GraphClustering.By.MNEMONIC));

        assertThat(rendered.clustering().clusters())
                .extracting(GraphClustering.Cluster::key)
                .containsExactly("Genrule", "Javac");
    }

    @Test
    @DisplayName("a whole graph that does not fit is refused, with its exact size")
    void wholeGraphRefusal() throws Exception {
        GraphLayoutService.Rendered rendered =
                await(GraphLayoutService.Request.whole(EdgeDerivation.DECLARED, 2, 2));

        assertThat(rendered.refused()).isTrue();
        assertThat(rendered.description()).contains("6 actions").contains("Nothing is hidden");
    }

    @Test
    @DisplayName("raising the limit is what makes the same graph drawable")
    void raisingTheLimitWorks() throws Exception {
        GraphLayoutService.Request tight =
                GraphLayoutService.Request.whole(EdgeDerivation.DECLARED, 2, 2);

        assertThat(await(tight).refused()).isTrue();
        // Plan 13.6: raising the limit is explicit, and it is the same query.
        GraphLayoutService.Rendered raised = await(tight.withLimits(100, 100));
        assertThat(raised.refused()).isFalse();
        assertThat(raised.extract().nodes()).hasSize(6);
    }

    @Test
    @DisplayName("a session with no action graph says so instead of drawing nothing")
    void missingGraphIsExplained() throws Exception {
        try (SessionDatabase empty = SessionDatabase.open(tempDir.resolve("empty.db"))) {
            MigrationRunner.standard().migrate(empty);
            try (GraphQueries none =
                            new GraphQueries(empty.writerConnection(), tempDir.resolve("none"));
                    GraphLayoutService bare = new GraphLayoutService(none)) {
                GraphLayoutService.Rendered rendered = await(
                        bare, GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1));

                // Rule 11: an absent graph is not an empty graph, and the
                // difference has to reach the user.
                assertThat(rendered.extract().nodes()).isEmpty();
                assertThat(rendered.description()).contains("no action graph");
            }
        }
    }

    @Test
    @DisplayName("overlapping submissions still answer the newest one")
    void theLatestRequestAlwaysAnswers() throws Exception {
        List<String> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch second = new CountDownLatch(1);

        service.submit(
                GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1),
                rendered -> callbacks.add("first"),
                error -> callbacks.add("first-error"));
        service.submit(
                GraphLayoutService.Request.around(EdgeDerivation.DECLARED, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2),
                rendered -> {
                    callbacks.add("second");
                    second.countDown();
                },
                error -> second.countDown());

        assertThat(second.await(10, TimeUnit.SECONDS)).isTrue();
        // This asserts the invariant, not the cancellation: on a six-node graph
        // the first request usually finishes before the second cancels it, so
        // "first" may or may not appear and asserting its absence would be a
        // flaky test dressed as a strong one. That a superseded layout stops at
        // its next check is proved deterministically in GraphLayoutTest, by
        // handing it an already-set flag.
        assertThat(callbacks).contains("second");
        assertThat(callbacks).doesNotContain("first-error");
    }

    @Test
    @DisplayName("a path cannot be reconstructed from a request alone, and says so")
    void pathsNeedTheirNodes() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        service.submit(
                new GraphLayoutService.Request(
                        EdgeDerivation.DECLARED, GraphExtract.Mode.CRITICAL_PATH, 0, 1,
                        100, 100, GraphLayout.Kind.LINEAR, GraphClustering.By.PACKAGE, 100),
                rendered -> done.countDown(),
                error -> {
                    failure.set(error);
                    done.countDown();
                });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be submitted with its nodes");
    }
}

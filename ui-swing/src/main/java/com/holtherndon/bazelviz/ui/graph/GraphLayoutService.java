package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extraction, clustering and layout, off the event thread and cached.
 *
 * <h2>Rule 8, which is the reason this class exists</h2>
 *
 * <p>"Never block the Swing event dispatch thread." Laying out fifty thousand
 * nodes is a linear pass, but linear over fifty thousand is still long enough to
 * freeze a window. Everything expensive happens on one background thread; only
 * the finished {@link Rendered} crosses back, and it crosses on the EDT.
 *
 * <h2>One request at a time, and the old one is told to stop</h2>
 *
 * <p>Plan 24 wants long operations cancellable. A user dragging a depth slider
 * generates a request per notch, and the answer they want is the last one. Each
 * new submission cancels its predecessor's flag, so a superseded layout stops at
 * its next check rather than finishing work nobody will look at.
 *
 * <h2>Cached by query and settings together</h2>
 *
 * <p>Plan 13.7: results cached by query and settings. Both halves matter — the
 * same neighbourhood drawn layered and drawn radially are different pictures,
 * and a cache keyed on the query alone would hand back the wrong one. The key is
 * the whole {@link Request} record, so adding a setting to it cannot forget to
 * update the cache.
 */
public final class GraphLayoutService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

    /**
     * How many finished layouts to keep.
     *
     * <p>Small: each entry holds two double arrays the size of its extraction,
     * so a dozen 50,000-node layouts is around ten megabytes. Enough that
     * stepping back and forth between two views is instant, not so many that the
     * cache becomes the memory problem.
     */
    static final int CACHE_ENTRIES = 12;

    private final GraphQueries queries;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "graph-layout");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<Request, Rendered> cache =
            new LinkedHashMap<>(CACHE_ENTRIES * 2, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Request, Rendered> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            };

    private AtomicBoolean inFlight = new AtomicBoolean(false);

    public GraphLayoutService(GraphQueries queries) {
        this.queries = queries;
    }

    /**
     * Runs a request, or answers it from the cache.
     *
     * <p>A cache hit still calls back rather than returning, so a caller has one
     * code path instead of two and cannot accidentally paint a cached result
     * synchronously and a computed one later.
     *
     * @param onDone called on the EDT with the finished drawing
     * @param onError called on the EDT when the graph could not be read
     */
    public void submit(Request request, Consumer<Rendered> onDone, Consumer<Throwable> onError) {
        Rendered hit;
        synchronized (cache) {
            hit = cache.get(request);
        }
        if (hit != null) {
            SwingUtilities.invokeLater(() -> onDone.accept(hit));
            return;
        }

        // Whatever was running is now answering a question the user has moved
        // on from.
        inFlight.set(true);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inFlight = cancelled;

        worker.execute(() -> {
            try {
                Rendered rendered = compute(request, cancelled);
                if (cancelled.get()) {
                    return;
                }
                synchronized (cache) {
                    cache.put(request, rendered);
                }
                SwingUtilities.invokeLater(() -> onDone.accept(rendered));
            } catch (RuntimeException | java.io.IOException | java.sql.SQLException failure) {
                if (cancelled.get()) {
                    return;
                }
                log.warn("graph layout failed for {}", request, failure);
                SwingUtilities.invokeLater(() -> onError.accept(failure));
            }
        });
    }

    /**
     * Works out how big a drawing would be, without laying it out.
     *
     * <p>Reads the index's counts, which is why it is cheap: the answer to
     * "will this fit" should arrive while the user is still choosing, not after
     * a traversal has run and been refused.
     */
    public void estimate(
            Request request, Consumer<LimitEstimate> onDone, Consumer<Throwable> onError) {
        worker.execute(() -> {
            try {
                Optional<CsrGraph> forward = queries.forwardIndex(request.derivation());
                LimitEstimate estimate = forward
                        .map(graph -> LimitEstimate.of(
                                request.mode(), graph.nodeCount(), graph.edgeCount(),
                                request.nodeLimit(), request.edgeLimit()))
                        .orElseGet(() -> LimitEstimate.of(
                                request.mode(), 0, 0,
                                request.nodeLimit(), request.edgeLimit()));
                SwingUtilities.invokeLater(() -> onDone.accept(estimate));
            } catch (RuntimeException | java.io.IOException | java.sql.SQLException failure) {
                log.warn("could not size the graph for {}", request, failure);
                SwingUtilities.invokeLater(() -> onError.accept(failure));
            }
        });
    }

    /** Work to run against the session's graph, off the event thread. */
    public interface GraphWork<T> {
        T runOn(CsrGraph forward) throws Exception;
    }

    /**
     * Runs work against the session's forward index on the layout thread.
     *
     * <p>The escape hatch for the things that need the whole graph rather than
     * a drawing of it — chiefly the complete export, which streams five million
     * edges to a file and must not do so on the event thread. Keeping it here
     * keeps every route to the graph on one thread.
     */
    public <T> void onGraph(
            EdgeDerivation derivation, GraphWork<T> work,
            Consumer<T> onDone, Consumer<Throwable> onError) {
        worker.execute(() -> {
            try {
                Optional<CsrGraph> forward = queries.forwardIndex(derivation);
                if (forward.isEmpty()) {
                    throw new IllegalStateException(
                            "this session has no " + derivation.displayName() + " graph");
                }
                T result = work.runOn(forward.get());
                SwingUtilities.invokeLater(() -> onDone.accept(result));
            } catch (Exception failure) {
                log.warn("graph work failed", failure);
                SwingUtilities.invokeLater(() -> onError.accept(failure));
            }
        });
    }

    /**
     * Draws a path that has already been found.
     *
     * <p>A path's nodes come from a search the caller ran, so it cannot be
     * recomputed from a {@link Request} alone — which is why {@link #submit}
     * refuses the two path modes and points here.
     *
     * <p>Deliberately not cached. A path is tens of nodes, laying one out is
     * microseconds, and the cache key would have to include the node list,
     * which is the kind of key that grows without bound as a user tries
     * different pairs.
     */
    public void submitPath(
            Request request,
            java.util.List<Integer> nodes,
            Consumer<Rendered> onDone,
            Consumer<Throwable> onError) {
        inFlight.set(true);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inFlight = cancelled;

        worker.execute(() -> {
            try {
                Optional<CsrGraph> forward = queries.forwardIndex(request.derivation());
                if (forward.isEmpty()) {
                    Rendered nothing = Rendered.unavailable(request);
                    SwingUtilities.invokeLater(() -> onDone.accept(nothing));
                    return;
                }
                GraphExtract.Result extract =
                        GraphExtract.path(forward.get(), nodes, request.mode());
                GraphLayout.Result layout =
                        GraphLayout.run(request.layout(), extract, cancelled);
                if (cancelled.get()) {
                    return;
                }
                Rendered rendered =
                        new Rendered(request, extract, layout, null, extract.describe());
                SwingUtilities.invokeLater(() -> onDone.accept(rendered));
            } catch (RuntimeException | java.io.IOException | java.sql.SQLException failure) {
                if (cancelled.get()) {
                    return;
                }
                log.warn("path layout failed for {}", request, failure);
                SwingUtilities.invokeLater(() -> onError.accept(failure));
            }
        });
    }

    /** Stops the running request without submitting another. */
    public void cancel() {
        inFlight.set(true);
    }

    private Rendered compute(Request request, AtomicBoolean cancelled)
            throws java.io.IOException, java.sql.SQLException {
        Optional<CsrGraph> forward = queries.forwardIndex(request.derivation());
        if (forward.isEmpty()) {
            return Rendered.unavailable(request);
        }
        CsrGraph graph = forward.get();

        if (request.mode() == GraphExtract.Mode.CLUSTERS) {
            String[] keys = clusterKeys(request.clusterBy());
            GraphClustering.Result clustering = GraphClustering.cluster(
                    graph, keys, request.clusterBy(), request.clusterLimit(), cancelled);
            GraphExtract.Result extract = clustering.asExtract();
            GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
            return new Rendered(request, extract, layout, clustering, clustering.describe());
        }

        GraphExtract.Result extract = switch (request.mode()) {
            case DEPENDENCIES -> GraphExtract.dependencies(
                    graph, request.sourceNode(), request.maxDepth(), request.nodeLimit());
            case DEPENDENTS -> GraphExtract.dependents(
                    reverse(request), request.sourceNode(), request.maxDepth(),
                    request.nodeLimit());
            case NEIGHBOURHOOD -> GraphExtract.neighbourhood(
                    graph, reverse(request), request.sourceNode(), request.maxDepth(),
                    request.nodeLimit());
            case WHOLE -> GraphExtract.whole(
                    graph, request.nodeLimit(), request.edgeLimit());
            // A path's nodes come from a search the caller already ran, so it
            // cannot be recomputed from the request alone.
            case PATH, CRITICAL_PATH -> throw new IllegalArgumentException(
                    request.mode() + " must be submitted with its nodes, via "
                            + "submitPath(Request, List, ...)");
            case CLUSTERS -> throw new IllegalStateException("handled above");
        };
        GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
        return new Rendered(request, extract, layout, null, extract.describe());
    }

    private CsrGraph reverse(Request request) throws java.io.IOException, java.sql.SQLException {
        return queries.reverseIndex(request.derivation()).orElseThrow(() ->
                new IllegalStateException(
                        "the reverse index for " + request.derivation() + " was never built"));
    }

    private String[] clusterKeys(GraphClustering.By by) throws java.sql.SQLException {
        return switch (by) {
            case MNEMONIC -> queries.mnemonicsByNodeIndex();
            case TARGET -> queries.labelsByNodeIndex();
            case PACKAGE -> {
                String[] labels = queries.labelsByNodeIndex();
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = GraphClustering.packageOf(labels[i]);
                }
                yield labels;
            }
        };
    }

    /** Empties the cache; for when the session's indexes have been rebuilt. */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    int cachedCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    @Override
    public void close() {
        cancel();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("the graph layout thread did not stop within two seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A query and the settings it was drawn with, which together are the cache
     * key.
     *
     * <p>A record rather than a hand-rolled key so that adding a setting is a
     * compile error at every construction site rather than a silent cache
     * collision between two different pictures.
     */
    public record Request(
            EdgeDerivation derivation,
            GraphExtract.Mode mode,
            int sourceNode,
            int maxDepth,
            int nodeLimit,
            int edgeLimit,
            GraphLayout.Kind layout,
            GraphClustering.By clusterBy,
            int clusterLimit) {

        /** One of the three views rooted at an action, at the default limits. */
        public static Request around(
                EdgeDerivation derivation, GraphExtract.Mode mode, int node, int depth) {
            return new Request(
                    derivation, mode, node, depth,
                    GraphExtract.DEFAULT_NODE_LIMIT, GraphExtract.DEFAULT_EDGE_LIMIT,
                    GraphLayout.defaultFor(mode), GraphClustering.By.PACKAGE,
                    GraphClustering.DEFAULT_CLUSTER_LIMIT);
        }

        /** The whole build, which will refuse itself unless it fits. */
        public static Request whole(EdgeDerivation derivation, int nodeLimit, int edgeLimit) {
            return new Request(
                    derivation, GraphExtract.Mode.WHOLE, 0, Integer.MAX_VALUE,
                    nodeLimit, edgeLimit, GraphLayout.Kind.LAYERED,
                    GraphClustering.By.PACKAGE, GraphClustering.DEFAULT_CLUSTER_LIMIT);
        }

        /** A path that a search has already found. */
        public static Request forPath(EdgeDerivation derivation, GraphExtract.Mode mode) {
            return new Request(
                    derivation, mode, 0, Integer.MAX_VALUE,
                    GraphExtract.DEFAULT_NODE_LIMIT, GraphExtract.DEFAULT_EDGE_LIMIT,
                    GraphLayout.Kind.LINEAR, GraphClustering.By.PACKAGE,
                    GraphClustering.DEFAULT_CLUSTER_LIMIT);
        }

        /** The far-zoom view: one box per group. */
        public static Request clustered(EdgeDerivation derivation, GraphClustering.By by) {
            return new Request(
                    derivation, GraphExtract.Mode.CLUSTERS, 0, Integer.MAX_VALUE,
                    GraphExtract.DEFAULT_NODE_LIMIT, GraphExtract.DEFAULT_EDGE_LIMIT,
                    GraphLayout.Kind.GRID, by, GraphClustering.DEFAULT_CLUSTER_LIMIT);
        }

        /** The same query drawn a different way. */
        public Request withLayout(GraphLayout.Kind kind) {
            return new Request(
                    derivation, mode, sourceNode, maxDepth, nodeLimit, edgeLimit,
                    kind, clusterBy, clusterLimit);
        }

        /** The same query with a raised ceiling; plan 13.6's explicit opt-in. */
        public Request withLimits(int nodes, int edges) {
            return new Request(
                    derivation, mode, sourceNode, maxDepth, nodes, edges,
                    layout, clusterBy, clusterLimit);
        }
    }

    /**
     * A finished drawing.
     *
     * @param clustering the grouping behind a cluster view, or null for a
     *     node-level one. The canvas needs it to name a box: a cluster
     *     ordinal is not a graph node index, and looking one up as the other
     *     would label a group with an unrelated action.
     * @param description the sentence the view shows beside the drawing, which
     *     plan 13.6 requires to name the totals whether or not anything was
     *     omitted
     */
    public record Rendered(
            Request request,
            GraphExtract.Result extract,
            GraphLayout.Result layout,
            GraphClustering.Result clustering,
            String description) {

        static Rendered unavailable(Request request) {
            return new Rendered(
                    request,
                    new GraphExtract.Result(
                            request.mode(), java.util.List.of(), java.util.List.of(),
                            0, 0, false, request.nodeLimit()),
                    GraphLayout.Result.empty(request.layout()),
                    null,
                    "This session has no action graph. Run an aquery from the graph view"
                            + " to build one.");
        }

        /** True when there is a graph but it was too big to draw at this setting. */
        public boolean refused() {
            return extract.hitLimit() && extract.nodes().isEmpty();
        }

        public boolean isCluster() {
            return clustering != null;
        }
    }
}

package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.analysis.GraphWeights;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>"Never block the Swing event dispatch thread." Laying out fifty thousand nodes is a linear
 * pass, but linear over fifty thousand is still long enough to freeze a window. Everything
 * expensive happens on one background thread; only finished immutable render data or prepared
 * models cross back, on the EDT.
 *
 * <h2>One request at a time, and the old one is told to stop</h2>
 *
 * <p>Plan 24 wants long operations cancellable. A user dragging a depth slider generates a request
 * per notch, and the answer they want is the last one. Each new submission cancels its
 * predecessor's flag, so a superseded layout stops at its next check rather than finishing work
 * nobody will look at.
 *
 * <h2>Cached by query and settings together</h2>
 *
 * <p>Plan 13.7: results cached by query and settings. Both halves matter — the same neighbourhood
 * drawn layered and drawn radially are different pictures, and a cache keyed on the query alone
 * would hand back the wrong one. The key is the whole {@link Request} record, so adding a setting
 * to it cannot forget to update the cache.
 */
public final class GraphLayoutService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

  /**
   * How many finished layouts to keep.
   *
   * <p>Small: each entry holds two double arrays the size of its extraction, so a dozen 50,000-node
   * layouts is around ten megabytes. Enough that stepping back and forth between two views is
   * instant, not so many that the cache becomes the memory problem.
   */
  static final int CACHE_ENTRIES = 12;

  private final GraphQueries queries;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          runnable -> {
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
  private AtomicBoolean preparationInFlight = new AtomicBoolean(false);

  public GraphLayoutService(GraphQueries queries) {
    this.queries = queries;
  }

  /**
   * Runs a request, or answers it from the cache.
   *
   * <p>A cache hit still calls back rather than returning, so a caller has one code path instead of
   * two and cannot accidentally paint a cached result synchronously and a computed one later.
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
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    inFlight = cancelled;

    worker.execute(
        () -> {
          try {
            Rendered rendered = compute(request, cancelled);
            if (cancelled.get()) {
              return;
            }
            synchronized (cache) {
              cache.put(request, rendered);
            }
            SwingUtilities.invokeLater(() -> onDone.accept(rendered));
          } catch (RuntimeException | IOException | SQLException failure) {
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
   * <p>Reads the index's counts, which is why it is cheap: the answer to "will this fit" should
   * arrive while the user is still choosing, not after a traversal has run and been refused.
   */
  public void estimate(
      Request request, Consumer<LimitEstimate> onDone, Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          try {
            Optional<CsrGraph> forward = queries.forwardIndex(request.graph());
            LimitEstimate estimate =
                forward
                    .map(
                        graph ->
                            LimitEstimate.of(
                                request.mode(),
                                graph.nodeCount(),
                                graph.edgeCount(),
                                request.nodeLimit(),
                                request.edgeLimit()))
                    .orElseGet(
                        () ->
                            LimitEstimate.of(
                                request.mode(), 0, 0, request.nodeLimit(), request.edgeLimit()));
            SwingUtilities.invokeLater(() -> onDone.accept(estimate));
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("could not size the graph for {}", request, failure);
            SwingUtilities.invokeLater(() -> onError.accept(failure));
          }
        });
  }

  /** Work to run against the session's graph, off the event thread. */
  public interface GraphWork<T> {
    T runOn(CsrGraph forward) throws Exception;
  }

  /** Work that prepares an immutable view model without reading the graph. */
  @FunctionalInterface
  public interface Preparation<T> {
    T run() throws Exception;
  }

  /**
   * Prepares derived drawing state on the graph worker, then returns to EDT.
   *
   * <p>Building the spatial index, translating edge endpoints and restyling edge buckets are all
   * bounded linear work, but still too much for Swing's event thread at the documented graph
   * ceiling.
   */
  public <T> void prepare(Preparation<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    preparationInFlight = cancelled;
    worker.execute(
        () -> {
          if (cancelled.get()) {
            return;
          }
          try {
            T result = work.run();
            if (!cancelled.get()) {
              SwingUtilities.invokeLater(
                  () -> {
                    if (!cancelled.get()) {
                      onDone.accept(result);
                    }
                  });
            }
          } catch (Exception failure) {
            if (!cancelled.get()) {
              log.warn("graph model preparation failed", failure);
              SwingUtilities.invokeLater(
                  () -> {
                    if (!cancelled.get()) {
                      onError.accept(failure);
                    }
                  });
            }
          }
        });
  }

  /**
   * Runs work against the session's forward index on the layout thread.
   *
   * <p>The escape hatch for the things that need the whole graph rather than a drawing of it —
   * chiefly the complete export, which streams five million edges to a file and must not do so on
   * the event thread. Keeping it here keeps every route to the graph on one thread.
   */
  public <T> void onGraph(
      GraphKind graph, GraphWork<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          try {
            Optional<CsrGraph> forward = queries.forwardIndex(graph);
            if (forward.isEmpty()) {
              throw new IllegalStateException("this session has no " + graph.displayName());
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
   * A weight's values for the drawn nodes, keyed by graph node index.
   *
   * @param valueByNode absent keys mean unknown — never zero
   * @param truncated true when a budget stopped the computation early
   * @param note the computation's own sentence for the legend, or empty
   */
  public record WeightSet(
      GraphWeight weight, Map<Integer, Long> valueByNode, boolean truncated, String note) {}

  /**
   * Computes the selected weight for an extraction, off the event thread.
   *
   * <p>Degrees read the CSR offsets; transitive counts run {@code GraphWeights}'
   * exact-over-the-subgraph pass under its work budget; sizes run the primary-output join. A weight
   * this graph cannot answer — sizes over the label graph, counts with no index on disk — comes
   * back with every node unknown and a note saying why, because an empty map that looked like "all
   * zero" would be the exact lie rule 11 forbids.
   */
  public void weights(
      GraphKind graph,
      GraphWeight weight,
      GraphExtract.Result extract,
      Consumer<WeightSet> onDone,
      Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          try {
            GraphWeights.Result computed = computeWeights(graph, weight, extract);
            Map<Integer, Long> byNode = new HashMap<>();
            List<Integer> nodes = extract.nodes();
            for (int i = 0; i < nodes.size(); i++) {
              long value = computed.values()[i];
              if (value >= 0) {
                byNode.put(nodes.get(i), value);
              }
            }
            WeightSet set = new WeightSet(weight, byNode, computed.truncated(), computed.note());
            SwingUtilities.invokeLater(() -> onDone.accept(set));
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("weight computation failed for {} over {}", weight, graph, failure);
            SwingUtilities.invokeLater(() -> onError.accept(failure));
          }
        });
  }

  private GraphWeights.Result computeWeights(
      GraphKind graph, GraphWeight weight, GraphExtract.Result extract)
      throws IOException, SQLException {
    int size = extract.nodes().size();
    switch (weight) {
      case IMMEDIATE_DEPS, INPUT_COUNT:
        {
          Optional<CsrGraph> reverse = queries.reverseIndex(graph);
          if (reverse.isEmpty()) {
            return GraphWeights.unavailable(
                size,
                "This session has no reverse index for this graph,"
                    + " so dependency counts are unavailable.");
          }
          GraphWeights.Result counted =
              GraphWeights.immediateDegrees(reverse.get(), extract.nodes());
          return weight == GraphWeight.INPUT_COUNT
              ? new GraphWeights.Result(
                  counted.values(),
                  counted.truncated(),
                  "Inputs are counted as immediate dependencies;"
                      + " the session records no distinct raw-input"
                      + " count.")
              : counted;
        }
      case IMMEDIATE_RDEPS:
        {
          Optional<CsrGraph> forward = queries.forwardIndex(graph);
          if (forward.isEmpty()) {
            return GraphWeights.unavailable(
                size,
                "This session has no index for this graph,"
                    + " so dependent counts are unavailable.");
          }
          return GraphWeights.immediateDegrees(forward.get(), extract.nodes());
        }
      case TRANSITIVE_DEPS, TRANSITIVE_RDEPS:
        return GraphWeights.subgraphTransitiveCounts(
            extract.nodes(),
            extract.edges(),
            weight.countsForwards(),
            GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET);
      case OUTPUT_SIZE:
        {
          if (graph == GraphKind.CONFIGURED_TARGETS) {
            return GraphWeights.unavailable(
                size, "A target label has no output, so nothing here" + " has an output size.");
          }
          long[] sizes = queries.outputSizesByNodeIndex(GraphWeights.UNKNOWN);
          long[] values = new long[size];
          for (int i = 0; i < size; i++) {
            int node = extract.nodes().get(i);
            values[i] = node >= 0 && node < sizes.length ? sizes[node] : GraphWeights.UNKNOWN;
          }
          return new GraphWeights.Result(values, false, "");
        }
      case DURATION:
      default:
        throw new IllegalArgumentException(
            weight + " is not computed here; the panel already holds it");
    }
  }

  /**
   * A budgeted whole-graph transitive count for one node, off the event thread.
   *
   * <p>The one place the weights machinery touches the full CSR index, and it is budgeted because
   * plan 13.3 forbids a transitive closure. The caller renders {@code BudgetedCount.describe()},
   * which says "≥N (budget reached)" when the traversal gave up. A graph with no index calls back
   * with nothing at all rather than a zero.
   */
  public void globalTransitiveCount(
      GraphKind graph, int node, boolean forwards, Consumer<GraphWeights.BudgetedCount> onDone) {
    worker.execute(
        () -> {
          try {
            Optional<CsrGraph> index =
                forwards ? queries.forwardIndex(graph) : queries.reverseIndex(graph);
            if (index.isEmpty()) {
              return;
            }
            GraphWeights.BudgetedCount counted =
                GraphWeights.globalTransitiveCount(
                    index.get(), node, GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET);
            SwingUtilities.invokeLater(() -> onDone.accept(counted));
          } catch (RuntimeException | IOException | SQLException failure) {
            log.debug("whole-graph transitive count failed", failure);
          }
        });
  }

  /**
   * Draws a path that has already been found.
   *
   * <p>A path's nodes come from a search the caller ran, so it cannot be recomputed from a {@link
   * Request} alone — which is why {@link #submit} refuses the two path modes and points here.
   *
   * <p>Deliberately not cached. A path is tens of nodes, laying one out is microseconds, and the
   * cache key would have to include the node list, which is the kind of key that grows without
   * bound as a user tries different pairs.
   */
  public void submitPath(
      Request request,
      List<Integer> nodes,
      Consumer<Rendered> onDone,
      Consumer<Throwable> onError) {
    inFlight.set(true);
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    inFlight = cancelled;

    worker.execute(
        () -> {
          try {
            Optional<CsrGraph> forward = queries.forwardIndex(request.graph());
            if (forward.isEmpty()) {
              Rendered nothing = Rendered.unavailable(request);
              if (!cancelled.get()) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (!cancelled.get()) {
                        onDone.accept(nothing);
                      }
                    });
              }
              return;
            }
            GraphExtract.Result extract =
                GraphExtract.path(
                    forward.get(), nodes, request.mode(), request.nodeLimit(), request.edgeLimit());
            GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
            if (cancelled.get()) {
              return;
            }
            Rendered rendered =
                new Rendered(
                    request, extract, layout, null, extract.describe(nounFor(request.graph())));
            SwingUtilities.invokeLater(() -> onDone.accept(rendered));
          } catch (RuntimeException | IOException | SQLException failure) {
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
    preparationInFlight.set(true);
  }

  private Rendered compute(Request request, AtomicBoolean cancelled)
      throws IOException, SQLException {
    Optional<CsrGraph> forward = queries.forwardIndex(request.graph());
    if (forward.isEmpty()) {
      return Rendered.unavailable(request);
    }
    CsrGraph graph = forward.get();

    if (request.mode() == GraphExtract.Mode.CLUSTERS) {
      String[] keys = clusterKeys(request.graph(), request.clusterBy());
      GraphClustering.Result clustering =
          GraphClustering.cluster(
              graph, keys, request.clusterBy(), request.clusterLimit(), cancelled);
      GraphExtract.Result extract = clustering.asExtract();
      GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
      return new Rendered(
          request, extract, layout, clustering, clustering.describe(nounFor(request.graph())));
    }

    GraphExtract.Result extract =
        switch (request.mode()) {
          // Dependencies live behind a node in the producer-to-consumer
          // index, so "what does this need" walks the reverse index and
          // "what needs this" walks forward. This pairing was inverted once,
          // and the trees showed a leaf compile as depending on the linker.
          case DEPENDENCIES ->
              GraphExtract.dependencies(
                  reverse(request),
                  request.sourceNode(),
                  request.maxDepth(),
                  request.nodeLimit(),
                  request.edgeLimit());
          case DEPENDENTS ->
              GraphExtract.dependents(
                  graph,
                  request.sourceNode(),
                  request.maxDepth(),
                  request.nodeLimit(),
                  request.edgeLimit());
          case NEIGHBOURHOOD ->
              GraphExtract.neighbourhood(
                  graph,
                  reverse(request),
                  request.sourceNode(),
                  request.maxDepth(),
                  request.nodeLimit(),
                  request.edgeLimit());
          case WHOLE -> GraphExtract.whole(graph, request.nodeLimit(), request.edgeLimit());
          // A path's nodes come from a search the caller already ran, so it
          // cannot be recomputed from the request alone.
          case PATH, CRITICAL_PATH ->
              throw new IllegalArgumentException(
                  request.mode()
                      + " must be submitted with its nodes, via "
                      + "submitPath(Request, List, ...)");
          case CLUSTERS -> throw new IllegalStateException("handled above");
        };
    GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
    return new Rendered(request, extract, layout, null, extract.describe(nounFor(request.graph())));
  }

  /** What one node of a graph is, for every sentence a drawing carries. */
  static String nounFor(GraphKind graph) {
    return graph == GraphKind.CONFIGURED_TARGETS ? "target" : "action";
  }

  private CsrGraph reverse(Request request) throws IOException, SQLException {
    return queries
        .reverseIndex(request.graph())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "the reverse index for " + request.graph() + " was never built"));
  }

  private String[] clusterKeys(GraphKind graph, GraphClustering.By by) throws SQLException {
    // The label graph's answer to "mnemonic" is the rule class: the
    // coarsest useful kind grouping a target has.
    return switch (by) {
      case MNEMONIC ->
          graph == GraphKind.CONFIGURED_TARGETS
              ? queries.ruleClassesByNodeIndex()
              : queries.mnemonicsByNodeIndex();
      case TARGET -> queries.labelsByNodeIndex(graph);
      case PACKAGE -> {
        String[] labels = queries.labelsByNodeIndex(graph);
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
   * A query and the settings it was drawn with, which together are the cache key.
   *
   * <p>A record rather than a hand-rolled key so that adding a setting is a compile error at every
   * construction site rather than a silent cache collision between two different pictures.
   */
  public record Request(
      GraphKind graph,
      GraphExtract.Mode mode,
      int sourceNode,
      int maxDepth,
      int nodeLimit,
      int edgeLimit,
      GraphLayout.Kind layout,
      GraphClustering.By clusterBy,
      int clusterLimit) {

    /** One of the three views rooted at a node, at the default limits. */
    public static Request around(GraphKind graph, GraphExtract.Mode mode, int node, int depth) {
      return new Request(
          graph,
          mode,
          node,
          depth,
          GraphExtract.DEFAULT_NODE_LIMIT,
          GraphExtract.DEFAULT_EDGE_LIMIT,
          GraphLayout.defaultFor(mode),
          GraphClustering.By.PACKAGE,
          GraphClustering.DEFAULT_CLUSTER_LIMIT);
    }

    /** The whole build, which will refuse itself unless it fits. */
    public static Request whole(GraphKind graph, int nodeLimit, int edgeLimit) {
      return new Request(
          graph,
          GraphExtract.Mode.WHOLE,
          0,
          Integer.MAX_VALUE,
          nodeLimit,
          edgeLimit,
          GraphLayout.defaultFor(GraphExtract.Mode.WHOLE),
          GraphClustering.By.PACKAGE,
          GraphClustering.DEFAULT_CLUSTER_LIMIT);
    }

    /** A path that a search has already found. */
    public static Request forPath(GraphKind graph, GraphExtract.Mode mode) {
      return new Request(
          graph,
          mode,
          0,
          Integer.MAX_VALUE,
          GraphExtract.DEFAULT_NODE_LIMIT,
          GraphExtract.DEFAULT_EDGE_LIMIT,
          GraphLayout.Kind.LINEAR,
          GraphClustering.By.PACKAGE,
          GraphClustering.DEFAULT_CLUSTER_LIMIT);
    }

    /** The far-zoom view: one box per group. */
    public static Request clustered(GraphKind graph, GraphClustering.By by) {
      return new Request(
          graph,
          GraphExtract.Mode.CLUSTERS,
          0,
          Integer.MAX_VALUE,
          GraphExtract.DEFAULT_NODE_LIMIT,
          GraphExtract.DEFAULT_EDGE_LIMIT,
          GraphLayout.Kind.GRID,
          by,
          GraphClustering.DEFAULT_CLUSTER_LIMIT);
    }

    /** The same query drawn a different way. */
    public Request withLayout(GraphLayout.Kind kind) {
      return new Request(
          graph, mode, sourceNode, maxDepth, nodeLimit, edgeLimit, kind, clusterBy, clusterLimit);
    }

    /** The same query with a raised ceiling; plan 13.6's explicit opt-in. */
    public Request withLimits(int nodes, int edges) {
      return new Request(
          graph, mode, sourceNode, maxDepth, nodes, edges, layout, clusterBy, clusterLimit);
    }

    /** The same cluster query with an explicitly raised group ceiling. */
    public Request withClusterLimit(int groups) {
      return new Request(
          graph, mode, sourceNode, maxDepth, nodeLimit, edgeLimit, layout, clusterBy, groups);
    }
  }

  /**
   * A finished drawing.
   *
   * @param clustering the grouping behind a cluster view, or null for a node-level one. The canvas
   *     needs it to name a box: a cluster ordinal is not a graph node index, and looking one up as
   *     the other would label a group with an unrelated action.
   * @param description the sentence the view shows beside the drawing, which plan 13.6 requires to
   *     name the totals whether or not anything was omitted
   */
  public record Rendered(
      Request request,
      GraphExtract.Result extract,
      GraphLayout.Result layout,
      GraphClustering.Result clustering,
      String description) {

    static Rendered unavailable(Request request) {
      String explanation =
          request.graph() == GraphKind.CONFIGURED_TARGETS
              ? "This session has no configured-target graph index. It is built"
                  + " when a capture's cquery succeeds; a session imported from"
                  + " a BEP file alone, or captured before indexing existed,"
                  + " has none."
              : "This session has no action graph index. It is built when a"
                  + " capture's aquery succeeds; a session imported from a BEP"
                  + " file alone, or captured before indexing existed, has"
                  + " none.";
      return new Rendered(
          request,
          new GraphExtract.Result(
              request.mode(),
              List.of(),
              List.of(),
              0,
              0,
              request.nodeLimit(),
              request.edgeLimit(),
              false,
              false),
          GraphLayout.Result.empty(request.layout()),
          null,
          explanation);
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

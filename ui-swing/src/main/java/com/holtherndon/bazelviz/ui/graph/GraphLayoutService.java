package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.analysis.GraphWeights;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.Bfs;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

  /** Maximum aggregate charge owned by retained layout cache entries. */
  public static final long MAX_CACHE_BYTES = 134_217_728L;

  /** Maximum request keys retained even when their renderings are tiny or unavailable. */
  public static final int MAX_CACHE_ENTRIES = 12;

  private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

  private final GraphQueries queries;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "graph-layout");
            thread.setDaemon(true);
            return thread;
          });

  private final Map<Request, Rendered> cache = new LinkedHashMap<>(4, 0.75f, true);
  private long cachedBytes;

  private volatile AtomicBoolean inFlight = new AtomicBoolean(false);
  private volatile AtomicBoolean preparationInFlight = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

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
    if (rejectClosed(onError)) {
      return;
    }
    inFlight.set(true);
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    inFlight = cancelled;
    preparationInFlight = cancelled;
    Rendered hit;
    synchronized (cache) {
      Rendered cached = cache.get(request);
      hit = cached == null ? null : cached.retain();
    }
    if (hit != null) {
      SwingUtilities.invokeLater(
          () -> {
            if (cancelled.get()) {
              hit.close();
            } else {
              onDone.accept(hit);
            }
          });
      return;
    }

    executeOwned(
        () -> {
          try {
            Rendered rendered = compute(request, cancelled);
            if (cancelled.get()) {
              rendered.close();
              return;
            }
            Rendered delivery = rendered;
            if (rendered.retainedBytes() <= MAX_CACHE_BYTES) {
              synchronized (cache) {
                Rendered old = cache.put(request, rendered);
                if (old != null) {
                  cachedBytes -= old.retainedBytes();
                  old.close();
                }
                cachedBytes = Math.addExact(cachedBytes, rendered.retainedBytes());
                trimCache();
                delivery = rendered.retain();
              }
            }
            Rendered handedOff = delivery;
            SwingUtilities.invokeLater(
                () -> {
                  if (cancelled.get()) {
                    handedOff.close();
                  } else {
                    onDone.accept(handedOff);
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            if (cancelled.get()) {
              return;
            }
            log.warn("graph layout failed for {}", request, failure);
            SwingUtilities.invokeLater(() -> onError.accept(failure));
          }
        },
        () -> {},
        onError);
  }

  private void trimCache() {
    var entries = cache.entrySet().iterator();
    while ((cachedBytes > MAX_CACHE_BYTES || cache.size() > MAX_CACHE_ENTRIES)
        && entries.hasNext()) {
      Rendered evicted = entries.next().getValue();
      entries.remove();
      cachedBytes -= evicted.retainedBytes();
      evicted.close();
    }
  }

  /**
   * Works out how big a drawing would be, without laying it out.
   *
   * <p>Reads the index's counts, which is why it is cheap: the answer to "will this fit" should
   * arrive while the user is still choosing, not after a traversal has run and been refused.
   */
  public void estimate(
      Request request, Consumer<LimitEstimate> onDone, Consumer<Throwable> onError) {
    executeOwned(
        () -> {
          try {
            CsrFile.Descriptor forward =
                queries
                    .indexDescriptor(request.graph(), true)
                    .orElseThrow(
                        () ->
                            new GraphUnavailableException(
                                "No trustworthy "
                                    + request.graph().displayName()
                                    + " index is available to estimate."));
            LimitEstimate estimate =
                LimitEstimate.of(
                    request.mode(),
                    forward.header().nodeCount(),
                    forward.header().edgeCount(),
                    request.nodeLimit(),
                    request.edgeLimit());
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onDone.accept(estimate);
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("could not size the graph for {}", request, failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onError.accept(failure);
                  }
                });
          }
        },
        () -> {},
        onError);
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
    prepareInternal(work, () -> {}, onDone, onError);
  }

  /**
   * Like {@link #prepare}, but closes the input owner if cancellation prevents the work running.
   */
  public <T> void prepareOwned(
      AutoCloseable owner, Preparation<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
    prepareInternal(work, () -> closeIfOwned(owner), onDone, onError);
  }

  private <T> void prepareInternal(
      Preparation<T> work,
      Runnable discardBeforeRun,
      Consumer<T> onDone,
      Consumer<Throwable> onError) {
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    preparationInFlight = cancelled;
    executeOwned(
        () -> {
          if (cancelled.get()) {
            discardBeforeRun.run();
            return;
          }
          try {
            T result = work.run();
            if (cancelled.get()) {
              closeIfOwned(result);
              return;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (cancelled.get()) {
                    closeIfOwned(result);
                  } else {
                    onDone.accept(result);
                  }
                });
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
        },
        discardBeforeRun,
        onError);
  }

  private static void closeIfOwned(Object value) {
    if (value instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // Cancellation cleanup has no useful recovery path.
      }
    }
  }

  /** Loads extraction-aligned metadata and prepares a charged model on the graph worker. */
  public void prepareModel(
      Rendered rendered, Consumer<GraphModel> onDone, Consumer<Throwable> onError) {
    preparationInFlight.set(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    preparationInFlight = cancelled;
    executeOwned(
        () -> {
          if (cancelled.get()) {
            rendered.close();
            return;
          }
          boolean renderingOwned = true;
          try {
            GraphModel model;
            if (rendered.isCluster()) {
              model = GraphModel.of(rendered, null, null, null);
            } else {
              GraphQueries.NodeMetadata metadata =
                  queries.metadata(rendered.request().graph(), rendered.layout().nodes());
              model = GraphModel.ofAligned(rendered, metadata);
            }
            renderingOwned = false;
            if (cancelled.get()) {
              model.close();
              return;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (cancelled.get()) {
                    model.close();
                  } else {
                    onDone.accept(model);
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            if (renderingOwned) {
              rendered.close();
            }
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
        },
        rendered::close,
        onError);
  }

  private void executeOwned(
      Runnable task, Runnable discardBeforeRun, Consumer<Throwable> onRejected) {
    OwnedTask owned = new OwnedTask(task, discardBeforeRun);
    try {
      if (closed.get()) {
        throw new RejectedExecutionException("graph layout service is closed");
      }
      worker.execute(owned);
    } catch (RejectedExecutionException rejected) {
      owned.discard();
      SwingUtilities.invokeLater(() -> onRejected.accept(rejected));
    }
  }

  private boolean rejectClosed(Consumer<Throwable> onRejected) {
    if (!closed.get()) {
      return false;
    }
    RejectedExecutionException rejected =
        new RejectedExecutionException("graph layout service is closed");
    SwingUtilities.invokeLater(() -> onRejected.accept(rejected));
    return true;
  }

  /** Exports one already-admitted visible model without mapping an unrelated whole CSR index. */
  public void exportVisible(
      GraphModel.Lease modelLease,
      Path target,
      GraphExport.Format format,
      Consumer<GraphExport.Result> onDone,
      Consumer<Throwable> onError) {
    executeOwned(
        () -> {
          try (modelLease) {
            GraphExport.Result result = GraphExport.visible(modelLease.model(), target, format);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onDone.accept(result);
                  }
                });
          } catch (RuntimeException | IOException failure) {
            log.warn("visible graph export failed", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onError.accept(failure);
                  }
                });
          }
        },
        modelLease::close,
        onError);
  }

  /** Streams one complete graph export with metadata read in dense order under the graph lease. */
  public void exportComplete(
      GraphKind graph,
      Path target,
      GraphExport.Format format,
      String nodeNoun,
      Consumer<GraphExport.Result> onDone,
      Consumer<Throwable> onError) {
    executeOwned(
        () -> {
          try {
            Optional<GraphExport.Result> result =
                queries.withIndex(
                    graph,
                    true,
                    index ->
                        GraphExport.whole(
                            index,
                            visitor -> {
                              try {
                                queries.forEachNodeMetadata(graph, visitor::node);
                              } catch (SQLException failure) {
                                throw new IOException(
                                    "could not stream complete graph metadata", failure);
                              }
                            },
                            target,
                            format,
                            nodeNoun));
            if (result.isEmpty()) {
              throw new GraphUnavailableException(
                  "No trustworthy " + graph.displayName() + " index is available to export.");
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onDone.accept(result.orElseThrow());
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("complete graph export failed", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onError.accept(failure);
                  }
                });
          }
        },
        () -> {},
        onError);
  }

  /**
   * A weight's values for the drawn nodes, keyed by graph node index.
   *
   * @param valueByNode absent keys mean unknown — never zero
   * @param truncated true when a budget stopped the computation early
   * @param note the computation's own sentence for the legend, or empty
   */
  public static final class WeightSet implements AutoCloseable {
    private final GraphWeight weight;
    private final long[] values;
    private final boolean truncated;
    private final String note;
    private GraphResourceBudget.Reservation retained;

    private WeightSet(
        GraphWeight weight,
        long[] values,
        boolean truncated,
        String note,
        GraphResourceBudget.Reservation retained) {
      this.weight = weight;
      this.values = values;
      this.truncated = truncated;
      this.note = note;
      this.retained = retained;
    }

    public GraphWeight weight() {
      return weight;
    }

    public long[] values() {
      return values;
    }

    public boolean truncated() {
      return truncated;
    }

    public String note() {
      return note;
    }

    @Override
    public void close() {
      if (retained != null) {
        retained.close();
        retained = null;
      }
    }
  }

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
      GraphModel.Lease modelLease,
      Consumer<WeightSet> onDone,
      Consumer<Throwable> onError) {
    executeOwned(
        () -> {
          GraphResourceBudget.Reservation retained = null;
          try (modelLease) {
            GraphExtract.Result extract = modelLease.model().extract();
            long nodes = extract.nodes().size();
            long edges = extract.edges().size();
            long retainedBytes = estimate("retained graph weights", 1_024, nodes, 16, 0, 0);
            long scratchBytes =
                estimate("graph weight computation scratch", 8_192, nodes, 256, edges, 128);
            List<GraphResourceBudget.Reservation> reservations =
                reserveWithCacheEviction(
                    List.of(
                        new GraphResourceBudget.Request(
                            retainedBytes, "retained extraction-aligned graph weights"),
                        new GraphResourceBudget.Request(
                            scratchBytes, "graph weight computation scratch")));
            retained = reservations.get(0);
            GraphWeights.Result computed;
            try (GraphResourceBudget.Reservation scratch = reservations.get(1)) {
              computed = computeWeights(graph, weight, extract);
            }
            WeightSet set =
                new WeightSet(
                    weight, computed.values(), computed.truncated(), computed.note(), retained);
            retained = null;
            SwingUtilities.invokeLater(
                () -> {
                  if (closed.get()) {
                    set.close();
                  } else {
                    onDone.accept(set);
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("weight computation failed for {} over {}", weight, graph, failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onError.accept(failure);
                  }
                });
          } finally {
            if (retained != null) {
              retained.close();
            }
          }
        },
        modelLease::close,
        onError);
  }

  private GraphWeights.Result computeWeights(
      GraphKind graph, GraphWeight weight, GraphExtract.Result extract)
      throws IOException, SQLException {
    int size = extract.nodes().size();
    switch (weight) {
      case IMMEDIATE_DEPS, INPUT_COUNT:
        {
          Optional<GraphWeights.Result> reverse =
              queries.withIndex(
                  graph, false, index -> GraphWeights.immediateDegrees(index, extract.nodes()));
          if (reverse.isEmpty()) {
            return GraphWeights.unavailable(
                size,
                "This session has no reverse index for this graph,"
                    + " so dependency counts are unavailable.");
          }
          GraphWeights.Result counted = reverse.orElseThrow();
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
          Optional<GraphWeights.Result> forward =
              queries.withIndex(
                  graph, true, index -> GraphWeights.immediateDegrees(index, extract.nodes()));
          if (forward.isEmpty()) {
            return GraphWeights.unavailable(
                size,
                "This session has no index for this graph,"
                    + " so dependent counts are unavailable.");
          }
          return forward.orElseThrow();
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
          return new GraphWeights.Result(
              queries.outputSizes(extract.nodes(), GraphWeights.UNKNOWN), false, "");
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
      GraphKind graph,
      int node,
      boolean forwards,
      Consumer<GraphWeights.BudgetedCount> onDone,
      Consumer<Throwable> onError) {
    executeOwned(
        () -> {
          try {
            Optional<GraphWeights.BudgetedCount> counted =
                queries.withIndex(
                    graph,
                    forwards,
                    index -> {
                      try (GraphResourceBudget.Reservation admitted =
                          queries
                              .resourceBudget()
                              .reserve(
                                  Bfs.peakBytes(
                                      index.nodeCount(),
                                      GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET),
                                  "whole-graph transitive traversal scratch")) {
                        return GraphWeights.globalTransitiveCount(
                            index, node, GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET);
                      }
                    });
            if (counted.isEmpty()) {
              SwingUtilities.invokeLater(
                  () -> {
                    if (!closed.get()) {
                      onError.accept(
                          new GraphUnavailableException(
                              "No trustworthy graph index is available for this traversal."));
                    }
                  });
              return;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onDone.accept(counted.orElseThrow());
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            log.warn("whole-graph transitive count failed", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (!closed.get()) {
                    onError.accept(failure);
                  }
                });
          }
        },
        () -> {},
        onError);
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

    executeOwned(
        () -> {
          try {
            Optional<Rendered> extracted =
                queries.withIndex(
                    request.graph(),
                    true,
                    forward -> computePath(request, nodes, cancelled, forward));
            if (extracted.isEmpty()) {
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
            Rendered rendered = extracted.orElseThrow();
            if (cancelled.get()) {
              rendered.close();
              return;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (cancelled.get()) {
                    rendered.close();
                  } else {
                    onDone.accept(rendered);
                  }
                });
          } catch (RuntimeException | IOException | SQLException failure) {
            if (cancelled.get()) {
              return;
            }
            log.warn("path layout failed for {}", request, failure);
            SwingUtilities.invokeLater(() -> onError.accept(failure));
          }
        },
        () -> {},
        onError);
  }

  private Rendered computePath(
      Request request, List<Integer> nodes, AtomicBoolean cancelled, CsrGraph forward)
      throws IOException {
    long pathNodes = nodes.size();
    long pathEdges = Math.max(0, pathNodes - 1);
    long retainedBytes = estimate("retained path rendering", 4_096, pathNodes, 128, pathEdges, 96);
    long scratchBytes = estimate("path layout scratch", 8_192, pathNodes, 256, pathEdges, 128);
    List<GraphResourceBudget.Reservation> reservations =
        reserveWithCacheEviction(
            List.of(
                new GraphResourceBudget.Request(retainedBytes, "retained path rendering"),
                new GraphResourceBudget.Request(scratchBytes, "path extraction and layout")));
    GraphResourceBudget.Reservation retained = reservations.get(0);
    try (GraphResourceBudget.Reservation scratch = reservations.get(1)) {
      GraphExtract.Result extract =
          GraphExtract.path(
              forward, nodes, request.mode(), request.nodeLimit(), request.edgeLimit());
      GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
      Rendered rendered =
          Rendered.charged(
              request,
              extract,
              layout,
              null,
              extract.describe(nounFor(request.graph())),
              queries.resourceBudget(),
              retainedBytes,
              retained);
      retained = null;
      return rendered;
    } finally {
      if (retained != null) {
        retained.close();
      }
    }
  }

  /** Stops the running request without submitting another. */
  public void cancel() {
    inFlight.set(true);
    preparationInFlight.set(true);
  }

  private Rendered compute(Request request, AtomicBoolean cancelled)
      throws IOException, SQLException {
    Optional<Rendered> rendered;
    if (request.mode() == GraphExtract.Mode.NEIGHBOURHOOD) {
      rendered =
          queries.withIndexPair(
              request.graph(), (forward, reverse) -> compute(request, cancelled, forward, reverse));
    } else if (request.mode() == GraphExtract.Mode.DEPENDENCIES) {
      rendered =
          queries.withIndex(
              request.graph(), false, reverse -> compute(request, cancelled, null, reverse));
    } else {
      rendered =
          queries.withIndex(
              request.graph(), true, forward -> compute(request, cancelled, forward, null));
    }
    return rendered.orElseGet(() -> Rendered.unavailable(request));
  }

  private Rendered compute(
      Request request, AtomicBoolean cancelled, CsrGraph graph, CsrGraph reverse)
      throws SQLException, IOException {
    CsrGraph counted = graph == null ? reverse : graph;
    long retainedBytes = retainedRenderingBytes(request, counted);
    long scratchBytes = scratchBytes(request, counted);
    List<GraphResourceBudget.Reservation> reservations =
        reserveWithCacheEviction(
            List.of(
                new GraphResourceBudget.Request(retainedBytes, "retained graph rendering"),
                new GraphResourceBudget.Request(scratchBytes, "graph extraction and layout")));
    GraphResourceBudget.Reservation retained = reservations.get(0);
    try (GraphResourceBudget.Reservation scratch = reservations.get(1)) {
      Rendered rendered =
          computeAdmitted(request, cancelled, graph, reverse, retainedBytes, retained);
      retained = null;
      return rendered;
    } finally {
      if (retained != null) {
        retained.close();
      }
    }
  }

  private Rendered computeAdmitted(
      Request request,
      AtomicBoolean cancelled,
      CsrGraph graph,
      CsrGraph reverse,
      long retainedBytes,
      GraphResourceBudget.Reservation retained)
      throws SQLException, IOException {

    if (request.mode() == GraphExtract.Mode.CLUSTERS) {
      try (GraphQueries.ClusterKeyData keyData =
          clusterKeys(request.graph(), request.clusterBy(), Math.toIntExact(graph.nodeCount()))) {
        String[] keys = keyData.keys();
        if (request.clusterBy() == GraphClustering.By.PACKAGE) {
          for (int node = 0; node < keys.length; node++) {
            keys[node] = GraphClustering.packageOf(keys[node]);
          }
        }
        GraphClustering.Result clustering =
            GraphClustering.cluster(
                graph, keys, request.clusterBy(), request.clusterLimit(), cancelled);
        GraphExtract.Result extract = clustering.asExtract();
        GraphLayout.Result layout = GraphLayout.run(request.layout(), extract, cancelled);
        if (clustering.clusters().isEmpty()) {
          return Rendered.charged(
              request,
              extract,
              layout,
              clustering,
              clustering.describe(nounFor(request.graph())),
              queries.resourceBudget(),
              retainedBytes,
              retained);
        }
        long totalRetained = Math.addExact(retainedBytes, keyData.retainedBytes());
        return Rendered.charged(
            request,
            extract,
            layout,
            clustering,
            clustering.describe(nounFor(request.graph())),
            queries.resourceBudget(),
            totalRetained,
            retained,
            keyData.transferReservation());
      }
    }

    GraphExtract.Result extract =
        switch (request.mode()) {
          // Dependencies live behind a node in the producer-to-consumer
          // index, so "what does this need" walks the reverse index and
          // "what needs this" walks forward. This pairing was inverted once,
          // and the trees showed a leaf compile as depending on the linker.
          case DEPENDENCIES ->
              GraphExtract.dependencies(
                  reverse,
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
                  reverse,
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
    return Rendered.charged(
        request,
        extract,
        layout,
        null,
        extract.describe(nounFor(request.graph())),
        queries.resourceBudget(),
        retainedBytes,
        retained);
  }

  private static long retainedRenderingBytes(Request request, CsrGraph graph) throws IOException {
    long nodes;
    long edges;
    if (request.mode() == GraphExtract.Mode.CLUSTERS) {
      nodes = Math.min(graph.nodeCount(), request.clusterLimit());
      edges = Math.min(graph.edgeCount(), square(request.clusterLimit()));
    } else {
      nodes = Math.min(graph.nodeCount(), request.nodeLimit());
      edges = Math.min(graph.edgeCount(), request.edgeLimit());
    }
    return estimate("retained graph rendering", 4_096, nodes, 128, edges, 96);
  }

  private static long scratchBytes(Request request, CsrGraph graph) throws IOException {
    if (request.mode() == GraphExtract.Mode.CLUSTERS) {
      long possibleClusterEdges = Math.min(graph.edgeCount(), square(request.clusterLimit()));
      return estimate(
          "graph clustering scratch", 8_192, graph.nodeCount(), 192, possibleClusterEdges, 160);
    }
    long nodes = Math.min(graph.nodeCount(), request.nodeLimit());
    long edges = Math.min(graph.edgeCount(), request.edgeLimit());
    return estimate("graph extraction and layout scratch", 8_192, nodes, 256, edges, 128);
  }

  private static long square(long value) throws IOException {
    try {
      return Math.multiplyExact(value, value);
    } catch (ArithmeticException overflow) {
      throw new IOException("graph cluster limit is too large to account safely", overflow);
    }
  }

  private static long estimate(
      String purpose,
      long fixed,
      long firstCount,
      long firstBytes,
      long secondCount,
      long secondBytes)
      throws IOException {
    try {
      return Math.addExact(
          fixed,
          Math.addExact(
              Math.multiplyExact(firstCount, firstBytes),
              Math.multiplyExact(secondCount, secondBytes)));
    } catch (ArithmeticException overflow) {
      throw new IOException(purpose + " is too large to account safely", overflow);
    }
  }

  private List<GraphResourceBudget.Reservation> reserveWithCacheEviction(
      List<GraphResourceBudget.Request> requests) throws GraphResourceBudget.RefusedException {
    while (true) {
      try {
        return queries.resourceBudget().reserveAll(requests);
      } catch (GraphResourceBudget.RefusedException refused) {
        Rendered evicted;
        synchronized (cache) {
          var entries = cache.entrySet().iterator();
          if (!entries.hasNext()) {
            throw refused;
          }
          evicted = entries.next().getValue();
          entries.remove();
          cachedBytes -= evicted.retainedBytes();
        }
        evicted.close();
      }
    }
  }

  /** What one node of a graph is, for every sentence a drawing carries. */
  static String nounFor(GraphKind graph) {
    return graph == GraphKind.CONFIGURED_TARGETS ? "target" : "action";
  }

  /** A missing or untrusted index is unavailable, never an exact empty graph. */
  public static final class GraphUnavailableException extends IOException {
    private static final long serialVersionUID = 1L;

    GraphUnavailableException(String message) {
      super(message);
    }
  }

  private GraphQueries.ClusterKeyData clusterKeys(
      GraphKind graph, GraphClustering.By by, int expectedNodes) throws SQLException, IOException {
    // The label graph's answer to "mnemonic" is the rule class: the
    // coarsest useful kind grouping a target has.
    GraphQueries.ClusterKeySource source =
        switch (by) {
          case MNEMONIC ->
              graph == GraphKind.CONFIGURED_TARGETS
                  ? GraphQueries.ClusterKeySource.RULE_CLASS
                  : GraphQueries.ClusterKeySource.MNEMONIC;
          case TARGET, PACKAGE -> GraphQueries.ClusterKeySource.LABEL;
        };
    while (true) {
      try {
        return queries.clusterKeys(graph, source, expectedNodes, by == GraphClustering.By.PACKAGE);
      } catch (GraphResourceBudget.RefusedException refused) {
        if (!evictOldestCached()) {
          throw refused;
        }
      }
    }
  }

  private boolean evictOldestCached() {
    Rendered evicted;
    synchronized (cache) {
      var entries = cache.entrySet().iterator();
      if (!entries.hasNext()) {
        return false;
      }
      evicted = entries.next().getValue();
      entries.remove();
      cachedBytes -= evicted.retainedBytes();
    }
    evicted.close();
    return true;
  }

  /** Empties the cache; for when the session's indexes have been rebuilt. */
  public void invalidate() {
    synchronized (cache) {
      for (Rendered rendered : cache.values()) {
        rendered.close();
      }
      cache.clear();
      cachedBytes = 0;
    }
  }

  int cachedCount() {
    synchronized (cache) {
      return cache.size();
    }
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    cancel();
    List<Runnable> dropped = worker.shutdownNow();
    for (Runnable runnable : dropped) {
      if (runnable instanceof OwnedTask owned) {
        owned.discard();
      }
    }
    boolean interrupted = false;
    while (!worker.isTerminated()) {
      try {
        if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
          log.warn("still waiting for the graph layout thread to stop");
        }
      } catch (InterruptedException waiting) {
        interrupted = true;
      }
    }
    invalidate();
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Executor task with explicit ownership for work discarded before it starts. */
  private static final class OwnedTask implements Runnable {
    private final Runnable task;
    private final Runnable discard;
    private final AtomicBoolean claimed = new AtomicBoolean(false);

    private OwnedTask(Runnable task, Runnable discard) {
      this.task = task;
      this.discard = discard;
    }

    @Override
    public void run() {
      if (claimed.compareAndSet(false, true)) {
        task.run();
      }
    }

    private void discard() {
      if (claimed.compareAndSet(false, true)) {
        discard.run();
      }
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
  public static final class Rendered implements AutoCloseable {

    private final Request request;
    private final GraphExtract.Result extract;
    private final GraphLayout.Result layout;
    private final GraphClustering.Result clustering;
    private final String description;
    private final GraphResourceBudget budget;
    private final long retainedBytes;
    private final SharedCharge charge;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Uncharged constructor for fixed, test-owned renderings. */
    public Rendered(
        Request request,
        GraphExtract.Result extract,
        GraphLayout.Result layout,
        GraphClustering.Result clustering,
        String description) {
      this(request, extract, layout, clustering, description, null, 0, null);
    }

    private Rendered(
        Request request,
        GraphExtract.Result extract,
        GraphLayout.Result layout,
        GraphClustering.Result clustering,
        String description,
        GraphResourceBudget budget,
        long retainedBytes,
        SharedCharge charge) {
      this.request = request;
      this.extract = extract;
      this.layout = layout;
      this.clustering = clustering;
      this.description = description;
      this.budget = budget;
      this.retainedBytes = retainedBytes;
      this.charge = charge;
    }

    private static Rendered charged(
        Request request,
        GraphExtract.Result extract,
        GraphLayout.Result layout,
        GraphClustering.Result clustering,
        String description,
        GraphResourceBudget budget,
        long retainedBytes,
        GraphResourceBudget.Reservation reservation) {
      return new Rendered(
          request,
          extract,
          layout,
          clustering,
          description,
          budget,
          retainedBytes,
          new SharedCharge(reservation));
    }

    private static Rendered charged(
        Request request,
        GraphExtract.Result extract,
        GraphLayout.Result layout,
        GraphClustering.Result clustering,
        String description,
        GraphResourceBudget budget,
        long retainedBytes,
        GraphResourceBudget.Reservation first,
        GraphResourceBudget.Reservation second) {
      return new Rendered(
          request,
          extract,
          layout,
          clustering,
          description,
          budget,
          retainedBytes,
          new SharedCharge(List.of(first, second)));
    }

    public Request request() {
      return request;
    }

    public GraphExtract.Result extract() {
      return extract;
    }

    public GraphLayout.Result layout() {
      return layout;
    }

    public GraphClustering.Result clustering() {
      return clustering;
    }

    public String description() {
      return description;
    }

    long retainedBytes() {
      return retainedBytes;
    }

    GraphResourceBudget budget() {
      return budget;
    }

    /** A separately closeable reference for a cache, callback or model owner. */
    Rendered retain() {
      if (charge == null) {
        return new Rendered(request, extract, layout, clustering, description);
      }
      charge.retain();
      return new Rendered(
          request, extract, layout, clustering, description, budget, retainedBytes, charge);
    }

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

    @Override
    public void close() {
      if (closed.compareAndSet(false, true) && charge != null) {
        charge.release();
      }
    }
  }

  private static final class SharedCharge {
    private final List<GraphResourceBudget.Reservation> reservations;
    private int references = 1;

    SharedCharge(GraphResourceBudget.Reservation reservation) {
      this(List.of(reservation));
    }

    SharedCharge(List<GraphResourceBudget.Reservation> reservations) {
      this.reservations = List.copyOf(reservations);
    }

    synchronized void retain() {
      if (references == 0) {
        throw new IllegalStateException("rendered graph charge is already released");
      }
      references++;
    }

    synchronized void release() {
      if (references <= 0) {
        throw new IllegalStateException("rendered graph charge released more than once");
      }
      references--;
      if (references == 0) {
        for (GraphResourceBudget.Reservation reservation : reservations) {
          reservation.close();
        }
      }
    }
  }
}

package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.graph.GraphSessionResources;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>Built on a real session database with a real CSR index file rather than a stub, because the
 * two things most likely to be wrong — that the node indices line up with the labels, and that the
 * index on disk is the graph the layout gets — only exist once the storage is real.
 */
final class GraphLayoutServiceTest {

  @TempDir Path tempDir;

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
    exec(
        connection,
        "INSERT INTO graph_sources (id, kind, state, configuration_match)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT')");
    exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Javac'), (2, 'Genrule')");
    for (int i = 0; i < 6; i++) {
      String label = (i < 3 ? "//a:" : "//b:") + "target" + i;
      exec(connection, "INSERT INTO labels (id, value) VALUES (" + (i + 1) + ", '" + label + "')");
      exec(
          connection,
          "INSERT INTO declared_actions"
              + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
              + " VALUES ("
              + (i + 1)
              + ", 1, "
              + i
              + ", "
              + (i + 1)
              + ", "
              + (i < 3 ? 1 : 2)
              + ", "
              + i
              + ")");
    }
    for (int i = 0; i + 1 < 6; i++) {
      exec(
          connection,
          "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
              + " VALUES ("
              + (i + 1)
              + ", "
              + (i + 2)
              + ", 'DECLARED')");
    }
    new GraphIndexBuilder(connection, tempDir.resolve("indexes")).build(EdgeDerivation.DECLARED);

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
        await(
            GraphLayoutService.Request.around(
                GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 2, 1));

    assertThat(rendered.extract().nodes()).contains(1, 2, 3);
    assertThat(rendered.layout().kind()).isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(rendered.layout().size()).isEqualTo(rendered.extract().nodes().size());
    assertThat(rendered.description()).contains("from a graph of 6");
    assertThat(rendered.isCluster()).isFalse();
  }

  private static Condition filter(String field, Operator operator, String... values) {
    return new Condition(field, operator, List.of(values));
  }

  @Test
  void incompleteTransitiveScopesAndClusterFilteringFailClearly() throws Exception {
    var requests =
        List.of(
            GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 2, 2)
                .withFilter(filter("transitive_deps", Operator.GREATER_THAN, "0")),
            GraphLayoutService.Request.clustered(
                    GraphKind.DECLARED_ACTIONS, GraphClustering.By.PACKAGE)
                .withFilter(filter("mnemonic", Operator.EQUALS, "Javac")));
    for (var request : requests) {
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      service.submit(
          request,
          rendered -> {
            rendered.close();
            done.countDown();
          },
          error -> {
            failure.set(error);
            done.countDown();
          });
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isNotNull();
      assertThat(failure.get().getMessage()).containsIgnoringCase("scope");
    }
  }

  @Test
  void filtersNarrowAnOversizedWholeGraphBeforeItsDrawingBudget() throws Exception {
    var request =
        GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 3, 2)
            .withFilter(filter("label", Operator.STARTS_WITH, "//B:"));
    try (var rendered = await(request)) {
      assertThat(rendered.extract().nodes()).containsExactly(3, 4, 5);
      assertThat(rendered.extract().edges())
          .containsExactly(new GraphExtract.Edge(3, 4), new GraphExtract.Edge(4, 5));
      assertThat(rendered.extract().hitLimit()).isFalse();
      assertThat(rendered.description()).contains("3 of 6", "3 hidden");
    }
    try (var rendered = await(request.withFilter(FilterExpression.ALL))) {
      assertThat(rendered.extract().hitLimit()).isTrue();
    }
  }

  @Test
  void composedFiltersUseFullSourceDegreesAndNeverBridgeHiddenNodes() throws Exception {
    var request = GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 10, 10);
    var composed =
        new Group(
            Junction.ALL,
            List.of(
                filter("mnemonic", Operator.IN, "Javac"),
                filter("deps", Operator.GREATER_THAN, "0"),
                filter("rdeps", Operator.GREATER_THAN, "0")));
    try (var rendered = await(request.withFilter(composed))) {
      assertThat(rendered.extract().nodes()).containsExactly(1, 2);
    }
    var separated =
        new Group(
            Junction.ANY,
            List.of(
                filter("label", Operator.ENDS_WITH, "target0"),
                filter("label", Operator.ENDS_WITH, "target5")));
    try (var rendered = await(request.withFilter(separated))) {
      assertThat(rendered.extract().nodes()).containsExactly(0, 5);
      assertThat(rendered.extract().edges()).isEmpty();
    }
  }

  @Test
  void transitiveCountsUseTheUnfilteredScopeAndCorrectDirection() throws Exception {
    var request = GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 10, 10);
    try (var rendered =
        await(request.withFilter(filter("transitive_deps", Operator.GREATER_THAN, "2")))) {
      assertThat(rendered.extract().nodes()).containsExactly(3, 4, 5);
      assertThat(rendered.description()).contains("scope before filtering");
    }
    try (var rendered =
        await(request.withFilter(filter("transitive_rdeps", Operator.GREATER_THAN, "2")))) {
      assertThat(rendered.extract().nodes()).containsExactly(0, 1, 2);
    }
  }

  @Test
  void unknownDurationsAreNotZeroAndFilteredBudgetRefusalIsExplicit() throws Exception {
    var request = GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 10, 10);
    try (var rendered = await(request.withFilter(filter("duration", Operator.EQUALS, "0")))) {
      assertThat(rendered.extract().nodes()).isEmpty();
      assertThat(rendered.extract().hitLimit()).isFalse();
    }
    try (var rendered = await(request.withFilter(filter("duration", Operator.IS_ABSENT)))) {
      assertThat(rendered.extract().nodes()).hasSize(6);
    }
    try (var rendered =
        await(request.withLimits(2, 10).withFilter(filter("label", Operator.STARTS_WITH, "//")))) {
      assertThat(rendered.extract().nodes()).isEmpty();
      assertThat(rendered.extract().hitNodeLimit()).isTrue();
      assertThat(rendered.description()).contains("6 of 6", "nothing was drawn");
    }
    try (var rendered =
        await(request.withLimits(10, 2).withFilter(filter("label", Operator.STARTS_WITH, "//")))) {
      assertThat(rendered.extract().nodes()).isEmpty();
      assertThat(rendered.extract().hitEdgeLimit()).isTrue();
    }
  }

  @Test
  @DisplayName("rooted graph requests pass their edge budget to extraction")
  void rootedEdgeBudgetIsApplied() throws Exception {
    GraphLayoutService.Request request =
        GraphLayoutService.Request.around(
                GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 2, 6)
            .withLimits(100, 2);

    GraphLayoutService.Rendered rendered = await(request);

    assertThat(rendered.extract().edges()).hasSize(2);
    assertThat(rendered.extract().hitEdgeLimit()).isTrue();
    assertThat(rendered.description()).contains("2-dependency budget");
  }

  @Test
  @DisplayName("model preparation runs off EDT and returns to EDT")
  void preparationKeepsLinearModelWorkOffTheEventThread() throws Exception {
    AtomicBoolean workWasEdt = new AtomicBoolean(true);
    AtomicBoolean callbackWasEdt = new AtomicBoolean(false);
    CountDownLatch done = new CountDownLatch(1);

    service.prepare(
        () -> {
          workWasEdt.set(SwingUtilities.isEventDispatchThread());
          return 42;
        },
        value -> {
          callbackWasEdt.set(SwingUtilities.isEventDispatchThread());
          assertThat(value).isEqualTo(42);
          done.countDown();
        },
        failure -> done.countDown());

    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(workWasEdt).isFalse();
    assertThat(callbackWasEdt).isTrue();
  }

  @Test
  @DisplayName("closing discards a queued owned preparation and releases its charge once")
  void closeDiscardsQueuedOwnedPreparation() throws Exception {
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch holdWorker = new CountDownLatch(1);
    service.prepare(
        () -> {
          workerEntered.countDown();
          holdWorker.await(10, TimeUnit.SECONDS);
          return null;
        },
        ignored -> {},
        failure -> {});
    assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();

    GraphResourceBudget budget = new GraphResourceBudget(32);
    GraphResourceBudget.Reservation reservation = budget.reserve(32, "queued model input");
    AtomicInteger closeCalls = new AtomicInteger();
    AtomicInteger workCalls = new AtomicInteger();
    AutoCloseable owner =
        () -> {
          closeCalls.incrementAndGet();
          reservation.close();
        };
    service.prepareOwned(
        owner,
        () -> {
          workCalls.incrementAndGet();
          return null;
        },
        ignored -> {},
        failure -> {});

    service.close();

    holdWorker.countDown();
    assertThat(workCalls).hasValue(0);
    assertThat(closeCalls).hasValue(1);
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }

  @Test
  @DisplayName("close does not finish while active graph work still owns the reader")
  void closeWaitsForActiveWorkerTermination() throws Exception {
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    service.prepare(
        () -> {
          workerEntered.countDown();
          while (releaseWorker.getCount() != 0) {
            try {
              releaseWorker.await();
            } catch (InterruptedException cancelled) {
              workerInterrupted.countDown();
            }
          }
          return null;
        },
        ignored -> {},
        failure -> {});
    assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Void> closing = CompletableFuture.runAsync(service::close);
    assertThat(workerInterrupted.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> concurrentClose = CompletableFuture.runAsync(service::close);
    assertThat(closing).isNotDone();
    assertThat(concurrentClose).isNotDone();

    releaseWorker.countDown();
    closing.get(10, TimeUnit.SECONDS);
    concurrentClose.get(10, TimeUnit.SECONDS);
    assertThat(closing).isCompleted();
    assertThat(concurrentClose).isCompleted();
  }

  @Test
  @DisplayName("a dropped weight-to-restyle handoff keeps and then releases both owners")
  void droppedWeightRestyleOwnsBothStages() throws Exception {
    GraphLayoutService.Rendered rendered =
        await(
            GraphLayoutService.Request.around(
                GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 2, 1));
    AtomicReference<GraphModel> prepared = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch modelReady = new CountDownLatch(1);
    service.prepareModel(
        rendered,
        model -> {
          prepared.set(model);
          modelReady.countDown();
        },
        error -> {
          failure.set(error);
          modelReady.countDown();
        });
    assertThat(modelReady.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
    GraphModel model = prepared.get();

    CountDownLatch weightsReady = new CountDownLatch(1);
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch holdWorker = new CountDownLatch(1);
    AtomicReference<GraphLayoutService.WeightSet> weights = new AtomicReference<>();
    service.weights(
        GraphKind.DECLARED_ACTIONS,
        GraphWeight.IMMEDIATE_DEPS,
        model.lease(),
        set -> {
          weights.set(set);
          service.prepare(
              () -> {
                workerEntered.countDown();
                holdWorker.await(10, TimeUnit.SECONDS);
                return null;
              },
              ignored -> {},
              failure::set);
          weightsReady.countDown();
        },
        error -> {
          failure.set(error);
          weightsReady.countDown();
        });
    assertThat(weightsReady.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
    assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();

    AtomicInteger restyleRuns = new AtomicInteger();
    AtomicInteger installed = new AtomicInteger();
    SwingUtilities.invokeAndWait(
        () -> {
          GraphLayoutService.WeightSet set = weights.get();
          GraphModel.Lease modelLease = model.lease();
          AutoCloseable inputs =
              () -> {
                set.close();
                modelLease.close();
              };
          service.prepareOwned(
              inputs,
              () -> {
                restyleRuns.incrementAndGet();
                try (inputs) {
                  return modelLease
                      .model()
                      .withWeights(set.weight(), set.values(), set.truncated(), set.note());
                }
              },
              styled -> {
                installed.incrementAndGet();
                styled.close();
              },
              failure::set);
          model.close();
        });

    assertThat(queries.resourceBudget().snapshot().retainedByPurpose())
        .containsKeys(
            "retained graph model and spatial index", "retained extraction-aligned graph weights");
    service.close();
    holdWorker.countDown();

    assertThat(restyleRuns).hasValue(0);
    assertThat(installed).hasValue(0);
    assertThat(failure.get()).isNull();
    assertThat(queries.resourceBudget().snapshot().retainedByPurpose())
        .doesNotContainKeys(
            "retained graph model and spatial index", "retained extraction-aligned graph weights");
  }

  @Test
  @DisplayName("submitting after close reports rejection on EDT instead of throwing")
  void submitAfterCloseReportsOnEdt() throws Exception {
    service.close();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean callbackWasEdt = new AtomicBoolean(false);
    CountDownLatch done = new CountDownLatch(1);

    assertThatCode(
            () ->
                service.submit(
                    GraphLayoutService.Request.around(
                        GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1),
                    rendered -> {
                      rendered.close();
                      done.countDown();
                    },
                    error -> {
                      failure.set(error);
                      callbackWasEdt.set(SwingUtilities.isEventDispatchThread());
                      done.countDown();
                    }))
        .doesNotThrowAnyException();

    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isInstanceOf(RejectedExecutionException.class);
    assertThat(callbackWasEdt).isTrue();
  }

  @Test
  @DisplayName("a newer model preparation suppresses the stale preparation callback")
  void stalePreparationDoesNotInstall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch newestDone = new CountDownLatch(1);
    AtomicInteger staleCallbacks = new AtomicInteger();
    AtomicReference<Integer> installed = new AtomicReference<>();

    service.prepare(
        () -> {
          started.countDown();
          assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
          return 1;
        },
        value -> staleCallbacks.incrementAndGet(),
        failure -> staleCallbacks.incrementAndGet());
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

    service.prepare(
        () -> 2,
        value -> {
          installed.set(value);
          newestDone.countDown();
        },
        failure -> newestDone.countDown());
    release.countDown();

    assertThat(newestDone.await(10, TimeUnit.SECONDS)).isTrue();
    SwingUtilities.invokeAndWait(() -> {});
    assertThat(staleCallbacks).hasValue(0);
    assertThat(installed).hasValue(2);
  }

  @Test
  @DisplayName("the answer arrives on the event dispatch thread")
  void callbacksLandOnTheEdt() throws Exception {
    AtomicReference<Boolean> onEdt = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    service.submit(
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2),
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
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2);

    GraphLayoutService.Rendered first = await(request);
    GraphLayoutService.Rendered second = await(request);

    // Each caller gets a separate closeable reference, while the expensive
    // extraction and layout are shared from the cache.
    assertThat(second).isNotSameAs(first);
    assertThat(second.extract()).isSameAs(first.extract());
    assertThat(second.layout()).isSameAs(first.layout());
    assertThat(service.cachedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("aggregate graph-budget refusal is visible and releases every provisional charge")
  void aggregateBudgetRefusalIsVisibleAndReleased() throws Exception {
    long mappedPairBytes =
        Math.addExact(
            queries.indexDescriptor(GraphKind.DECLARED_ACTIONS, true).orElseThrow().fileBytes(),
            queries.indexDescriptor(GraphKind.DECLARED_ACTIONS, false).orElseThrow().fileBytes());
    GraphResourceBudget budget = new GraphResourceBudget(mappedPairBytes);
    GraphSessionResources resources = new GraphSessionResources(budget);
    GraphQueries constrainedQueries =
        new GraphQueries(database.newGraphReadConnection(), tempDir.resolve("indexes"), resources);
    GraphLayoutService constrained = new GraphLayoutService(constrainedQueries);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean deliveredOnEdt = new AtomicBoolean(false);
    CountDownLatch done = new CountDownLatch(1);
    try {
      constrained.submit(
          GraphLayoutService.Request.around(
              GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1),
          rendered -> {
            rendered.close();
            done.countDown();
          },
          error -> {
            failure.set(error);
            deliveredOnEdt.set(SwingUtilities.isEventDispatchThread());
            done.countDown();
          });

      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get())
          .isInstanceOf(GraphResourceBudget.RefusedException.class)
          .hasMessageContaining("session budget is " + mappedPairBytes + " bytes")
          .hasMessageContaining("retained");
      assertThat(deliveredOnEdt).isTrue();
      assertThat(budget.snapshot().retainedBytes()).isEqualTo(mappedPairBytes);
      assertThat(budget.snapshot().retainedByPurpose().keySet())
          .allMatch(purpose -> purpose.startsWith("mapped graph index"));
    } finally {
      constrained.close();
      constrainedQueries.close();
      resources.close();
    }
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }

  @Test
  @DisplayName("changing only the layout is a different cache entry")
  void settingsArePartOfTheKey() throws Exception {
    GraphLayoutService.Request hierarchy =
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2);

    GraphLayoutService.Rendered tree = await(hierarchy);
    GraphLayoutService.Rendered layered = await(hierarchy.withLayout(GraphLayout.Kind.LAYERED));

    // Plan 13.7 says cached by query AND settings. The same neighbourhood
    // drawn two ways is two pictures, and a query-only key would hand back
    // the wrong one.
    assertThat(tree.layout().kind()).isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(layered).isNotSameAs(tree);
    assertThat(layered.layout().kind()).isEqualTo(GraphLayout.Kind.LAYERED);
    assertThat(service.cachedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("the cache does not grow without bound")
  void cacheIsBounded() throws Exception {
    for (int depth = 1; depth <= GraphLayoutService.MAX_CACHE_ENTRIES + 5; depth++) {
      await(
          GraphLayoutService.Request.around(
              GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, depth));
    }

    assertThat(service.cachedCount()).isEqualTo(GraphLayoutService.MAX_CACHE_ENTRIES);
  }

  @Test
  @DisplayName("invalidating drops everything, for when the indexes are rebuilt")
  void invalidateClears() throws Exception {
    await(
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2));
    service.invalidate();

    assertThat(service.cachedCount()).isZero();
  }

  @Test
  @DisplayName("clustering by package groups the session into its two packages")
  void clusteringByPackage() throws Exception {
    GraphLayoutService.Rendered rendered =
        await(
            GraphLayoutService.Request.clustered(
                GraphKind.DECLARED_ACTIONS, GraphClustering.By.PACKAGE));

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
  @DisplayName("a raised group budget makes a previously refused grouping drawable")
  void clusterLimitCanBeRaisedExplicitly() throws Exception {
    GraphLayoutService.Request tight =
        GraphLayoutService.Request.clustered(GraphKind.DECLARED_ACTIONS, GraphClustering.By.PACKAGE)
            .withClusterLimit(1);

    GraphLayoutService.Rendered refused = await(tight);
    GraphLayoutService.Rendered raised = await(tight.withClusterLimit(2));

    assertThat(refused.refused()).isTrue();
    assertThat(refused.description()).contains("more than the 1").doesNotContain("2 groups");
    assertThat(raised.refused()).isFalse();
    assertThat(raised.clustering().clusters()).hasSize(2);
  }

  @Test
  @DisplayName("clustering by mnemonic uses the mnemonic, not the label")
  void clusteringByMnemonic() throws Exception {
    GraphLayoutService.Rendered rendered =
        await(
            GraphLayoutService.Request.clustered(
                GraphKind.DECLARED_ACTIONS, GraphClustering.By.MNEMONIC));

    assertThat(rendered.clustering().clusters())
        .extracting(GraphClustering.Cluster::key)
        .containsExactly("Genrule", "Javac");
  }

  @Test
  @DisplayName("a whole graph that does not fit is refused, with its exact size")
  void wholeGraphRefusal() throws Exception {
    GraphLayoutService.Rendered rendered =
        await(GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 2, 2));

    assertThat(rendered.refused()).isTrue();
    assertThat(rendered.description()).contains("6 actions").contains("Nothing is hidden");
  }

  @Test
  @DisplayName("raising the limit is what makes the same graph drawable")
  void raisingTheLimitWorks() throws Exception {
    GraphLayoutService.Request tight =
        GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 2, 2);

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
      try (GraphQueries none = new GraphQueries(empty.writerConnection(), tempDir.resolve("none"));
          GraphLayoutService bare = new GraphLayoutService(none)) {
        GraphLayoutService.Rendered rendered =
            await(
                bare,
                GraphLayoutService.Request.around(
                    GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1));

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
    List<String> callbacks = new CopyOnWriteArrayList<>();
    CountDownLatch second = new CountDownLatch(1);

    service.submit(
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 1),
        rendered -> callbacks.add("first"),
        error -> callbacks.add("first-error"));
    service.submit(
        GraphLayoutService.Request.around(
            GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.NEIGHBOURHOOD, 0, 2),
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
            GraphKind.DECLARED_ACTIONS,
            GraphExtract.Mode.CRITICAL_PATH,
            0,
            1,
            100,
            100,
            GraphLayout.Kind.LINEAR,
            GraphClustering.By.PACKAGE,
            100),
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

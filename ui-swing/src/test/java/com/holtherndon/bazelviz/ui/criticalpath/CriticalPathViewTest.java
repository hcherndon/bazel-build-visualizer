package com.holtherndon.bazelviz.ui.criticalpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries.GraphSource;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.ui.metrics.MetricsService;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless behavior contract for the Critical Path page. */
final class CriticalPathViewTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("Bazel and dependency paths stay visibly separate")
  void pathNamesDoNotCollapseIntoOneCriticalPath() throws Exception {
    CriticalPathView view = onEdt(CriticalPathView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Critical Path"));
    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installGraphSourceForTest(graphSource(ConfigurationMatch.EXACT, null));
          view.show(metrics(paths(8_000, Optional.of(path(5_000)), List.of())));
          return null;
        });

    JTabbedPane tabs = onEdt(() -> findOne(view, JTabbedPane.class));
    assertThat(tabs.getTitleAt(0)).isEqualTo("Bazel-reported critical path");
    assertThat(tabs.getTitleAt(1)).isEqualTo("Visualizer-computed dependency critical path");
    assertThat(tabs.getTitleAt(2)).isEqualTo("Observed timing fallback");
    assertThat(onEdt(view::summaryTextForTest))
        .contains("Bazel-reported critical path")
        .contains("Visualizer-computed dependency critical path")
        .contains("Difference between paths")
        .contains("component breakdown unavailable")
        .doesNotContain("0 reported components");
    assertThat(onEdt(view::statusTextForTest))
        .contains("reported the critical-path total")
        .contains("component breakdown is unavailable");
    assertThat(onEdt(toolbar::actionCount)).isEqualTo(2);
    assertThat(onEdt(toolbar::metadata)).contains("Bazel", "Dependency");
  }

  @Test
  @DisplayName("the path and inspector remain readable at the minimum window width")
  void splitDoesNotSqueezeTheInspectorAtNarrowWidths() throws Exception {
    CriticalPathView view = onEdt(CriticalPathView::new);
    JSplitPane split =
        onEdt(
            () ->
                findAll(view, JSplitPane.class).stream()
                    .filter(candidate -> candidate.getOrientation() == JSplitPane.HORIZONTAL_SPLIT)
                    .findFirst()
                    .orElseThrow());

    onEdt(
        () -> {
          split.setSize(780, 420);
          split.doLayout();
          return null;
        });

    assertThat(onEdt(() -> split.getRightComponent().getWidth())).isGreaterThanOrEqualTo(260);
    assertThat(onEdt(() -> split.getLeftComponent().getWidth())).isGreaterThanOrEqualTo(320);
  }

  @Test
  @DisplayName("the responsive overview scrolls and keeps verbose query output collapsed")
  void overviewScrollsWithoutLettingQueryOutputStretchThePage() throws Exception {
    String failure =
        "the declared action-graph import state is FAILED: The aquery did not run:\n"
            + "ERROR: //pkg:windows-only is incompatible\n"
            + "Dependency chain: lots of diagnostic detail";
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(8_000L, DataSource.PROFILE),
            List.of(),
            0,
            Optional.empty(),
            Optional.of(failure),
            Optional.of(observedFallback()));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths));
          return null;
        });

    JScrollPane scroll = onEdt(view::summaryScrollForTest);
    assertThat(scroll.getHorizontalScrollBarPolicy())
        .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    assertThat(scroll.getVerticalScrollBar().getUnitIncrement()).isEqualTo(16);
    assertThat((Scrollable) scroll.getViewport().getView())
        .matches(Scrollable::getScrollableTracksViewportWidth);
    assertThat(onEdt(view::summaryTextForTest))
        .contains("aquery failed; open Details")
        .contains("Longest observed action (not a dependency path)")
        .doesNotContain("windows-only")
        .doesNotContain("Difference between paths");
    assertThat(onEdt(() -> view.detailsPaneForTest().isVisible())).isFalse();
    assertThat(onEdt(view::statusTextForTest)).contains("windows-only");

    onEdt(
        () -> {
          view.detailsButtonForTest().doClick();
          return null;
        });
    assertThat(onEdt(() -> view.detailsPaneForTest().isVisible())).isTrue();
  }

  @Test
  @DisplayName("an observed action fallback never invents a dependency path or slack")
  void observedFallbackRemainsSeparateFromTheMissingDependencyPath() throws Exception {
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(8_000L, DataSource.PROFILE),
            List.of(),
            0,
            Optional.empty(),
            Optional.of("aquery failed"),
            Optional.of(observedFallback()));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths));
          return null;
        });

    JTable table = onEdt(view::observedFallbackTableForTest);
    JTabbedPane tabs = onEdt(() -> findOne(view, JTabbedPane.class));
    assertThat(onEdt(() -> tabs.isEnabledAt(2))).isTrue();
    assertThat(onEdt(tabs::getSelectedIndex)).isEqualTo(2);
    assertThat(onEdt(table::getRowCount)).isEqualTo(1);
    assertThat(onEdt(() -> table.getValueAt(0, 0))).isEqualTo("Javac");
    assertThat(onEdt(() -> table.getValueAt(0, 1))).isEqualTo("//pkg:app");
    assertThat(onEdt(() -> table.getValueAt(0, 3))).isEqualTo("4.0 ms");
    assertThat(onEdt(() -> view.openGraphForTest().isEnabled())).isFalse();
    assertThat(onEdt(() -> view.inspectorForTest().displayed().subtitle()))
        .hasValueSatisfying(text -> assertThat(text).contains("not a dependency path"));
    assertThat(onEdt(() -> view.inspectorForTest().displayed().sections()))
        .flatExtracting(section -> section.fields())
        .extracting(field -> field.name())
        .doesNotContain("Slack", "Earliest start", "Earliest finish");
    assertThat(onEdt(view::statusTextForTest))
        .contains("Observed timing fallback")
        .contains("not a dependency path")
        .contains("no inferred predecessor chain, path total, slack, or path comparison")
        .doesNotContain("The table instead");

    onEdt(
        () -> {
          tabs.setSelectedIndex(1);
          return null;
        });
    assertThat(onEdt(view::statusTextForTest))
        .contains("No dependency path was computed")
        .contains("available in the Observed timing fallback tab")
        .doesNotContain("The table instead");
  }

  @Test
  @DisplayName("the signed path difference explains positive, negative, and unavailable gaps")
  void gapWordingExplainsItsDirectionAndAbsence() throws Exception {
    CriticalPathView positive = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          positive.installGraphSourceForTest(graphSource(ConfigurationMatch.EXACT, null));
          positive.show(metrics(paths(8_000, Optional.of(path(5_000)), List.of())));
          return null;
        });
    assertThat(onEdt(positive::summaryTextForTest))
        .contains("+3.0 ms")
        .contains("Bazel's path is longer")
        .contains("scheduling, resource limits, or queues may contribute");

    CriticalPathView negative = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          negative.installGraphSourceForTest(graphSource(ConfigurationMatch.EXACT, null));
          negative.show(metrics(paths(2_000, Optional.of(path(5_000)), List.of())));
          return null;
        });
    assertThat(onEdt(negative::summaryTextForTest))
        .contains("−3.0 ms")
        .contains("The dependency result is longer")
        .contains("inspect graph and timing coverage");

    CriticalPathView unavailable = onEdt(CriticalPathView::new);
    CriticalPaths missingBazel =
        new CriticalPaths(
            Measured.unknown(
                DataSource.PROFILE, Completeness.UNAVAILABLE, "the trace profile was not imported"),
            List.of(),
            Optional.of(path(5_000)));
    onEdt(
        () -> {
          unavailable.installGraphSourceForTest(graphSource(ConfigurationMatch.EXACT, null));
          unavailable.show(metrics(missingBazel));
          return null;
        });
    assertThat(onEdt(unavailable::summaryTextForTest))
        .contains("Difference between paths")
        .contains("—")
        .contains(
            "Both a Bazel total and a fully timed, confirmed dependency path are" + " required");
  }

  @Test
  @DisplayName("missing or mismatched graph provenance keeps the dependency result unavailable")
  void untrustedGraphNeverProducesAConfidentComparison() throws Exception {
    CriticalPathView loading = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          loading.show(metrics(paths(8_000, Optional.empty(), List.of())));
          return null;
        });
    assertThat(onEdt(loading::summaryTextForTest))
        .contains("Visualizer-computed dependency critical path")
        .contains("No confirmed action graph and usable duration source")
        .contains(
            "Both a Bazel total and a fully timed, confirmed dependency path are" + " required");

    String mismatch = "This action graph contains a configuration the build never used";
    CriticalPathView mismatched = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          mismatched.installGraphSourceForTest(
              graphSource(ConfigurationMatch.MISMATCHED, mismatch));
          mismatched.show(metrics(paths(8_000, Optional.empty(), List.of())));
          return null;
        });
    assertThat(onEdt(mismatched::summaryTextForTest))
        .contains("Visualizer-computed dependency critical path")
        .contains("—")
        .contains(
            "Both a Bazel total and a fully timed, confirmed dependency path are" + " required")
        .contains(mismatch);
    assertThat(onEdt(() -> mismatched.openGraphForTest().isEnabled())).isFalse();
  }

  @Test
  @DisplayName("Bazel components keep reported order and never invent action links")
  void bazelComponentsAreOrderedButUnlinked() throws Exception {
    List<CriticalPaths.BazelComponent> components =
        List.of(
            new CriticalPaths.BazelComponent(0, "Compiling //app:library", OptionalLong.of(2_500)),
            new CriticalPaths.BazelComponent(1, "Linking //app:binary", OptionalLong.empty()));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths(2_500, Optional.empty(), components)));
          return null;
        });

    JTable table = onEdt(view::bazelTableForTest);
    assertThat(table.getRowCount()).isEqualTo(2);
    assertThat(table.getValueAt(0, 0)).isEqualTo(1L);
    assertThat(table.getValueAt(0, 1)).isEqualTo("Compiling //app:library");
    assertThat(table.getValueAt(1, 0)).isEqualTo(2L);
    assertThat(table.getValueAt(1, 1)).isEqualTo("Linking //app:binary");
    assertThat(table.getValueAt(1, 2)).isEqualTo("—");

    var inspection = onEdt(() -> view.inspectorForTest().displayed());
    assertThat(inspection.title()).isEqualTo("Compiling //app:library");
    assertThat(inspection.refs()).isEmpty();
    assertThat(inspection.sections())
        .flatExtracting(section -> section.fields())
        .filteredOn(field -> field.name().equals("Action link"))
        .singleElement()
        .satisfies(
            field ->
                assertThat(field.value().orElseThrow())
                    .contains("Unavailable")
                    .contains("not an action key"));
  }

  @Test
  @DisplayName("Bazel components page lazily without reading on the EDT")
  void bazelComponentsUseTheExactPagedCount() throws Exception {
    AtomicBoolean readOnEdt = new AtomicBoolean();
    List<String> requests = new CopyOnWriteArrayList<>();
    BazelPathRowSource source =
        new BazelPathRowSource(
            405,
            (first, limit) -> {
              readOnEdt.set(SwingUtilities.isEventDispatchThread());
              requests.add(first + ":" + limit);
              List<CriticalPaths.BazelComponent> rows = new ArrayList<>();
              for (long ordinal = first; ordinal < first + limit; ordinal++) {
                rows.add(
                    new CriticalPaths.BazelComponent(
                        Math.toIntExact(ordinal),
                        "component " + ordinal,
                        OptionalLong.of(ordinal + 1)));
              }
              return rows;
            });
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(12_000L, DataSource.PROFILE),
            List.of(),
            405,
            Optional.empty(),
            Optional.of("no graph"));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths));
          view.installBazelSourceForTest(source);
          view.bazelTableForTest().setRowSelectionInterval(200, 200);
          assertThat(view.bazelTableForTest().getValueAt(200, 1)).isEqualTo("…");
          assertThat(view.inspectorForTest().displayed().title())
              .isEqualTo("Loading Bazel component…");
          return null;
        });

    waitUntil(() -> view.bazelModelForTest().isPageLoaded(1));

    assertThat(readOnEdt).isFalse();
    assertThat(requests).containsExactly("200:200");
    assertThat(onEdt(() -> view.bazelTableForTest().getRowCount())).isEqualTo(405);
    assertThat(onEdt(() -> view.bazelTableForTest().getValueAt(200, 1))).isEqualTo("component 200");
    assertThat(onEdt(() -> view.inspectorForTest().displayed().title())).isEqualTo("component 200");
    assertThat(onEdt(view::summaryTextForTest)).contains("405 reported components");
    assertThat(onEdt(view::statusTextForTest))
        .contains("Showing all 405 components")
        .contains("pages of 200");
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("a failed Bazel component page replaces its loading inspector")
  void bazelComponentPageFailureIsVisible() throws Exception {
    BazelPathRowSource source =
        new BazelPathRowSource(
            1,
            (first, limit) -> {
              throw new SQLException("profile index unavailable");
            });
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(12_000L, DataSource.PROFILE),
            List.of(),
            1,
            Optional.empty(),
            Optional.of("no graph"));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths));
          view.installBazelSourceForTest(source);
          view.bazelTableForTest().setRowSelectionInterval(0, 0);
          assertThat(view.bazelTableForTest().getValueAt(0, 1)).isEqualTo("…");
          return null;
        });

    waitUntil(() -> view.bazelModelForTest().isPageFailed(0));

    assertThat(onEdt(() -> view.inspectorForTest().displayed().title()))
        .isEqualTo("Bazel component unavailable");
    assertThat(onEdt(() -> view.inspectorForTest().displayed().sections()))
        .flatExtracting(section -> section.fields())
        .extracting(field -> field.value().orElse(""))
        .anyMatch(value -> value.contains("profile index unavailable"));
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("a withheld profile breakdown keeps its exact warning")
  void profileMismatchWarningIsNotReplacedByAGenericMessage() throws Exception {
    String warning = "profile component breakdown withheld: build id mismatch";
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(8_000L, DataSource.BEP).warn(warning),
            List.of(),
            0,
            Optional.empty(),
            Optional.of("no graph"));
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metrics(paths));
          return null;
        });

    assertThat(onEdt(view::summaryTextForTest))
        .contains(warning)
        .doesNotContain("component breakdown unavailable");
    assertThat(onEdt(view::statusTextForTest))
        .contains(warning)
        .doesNotContain("component breakdown is unavailable");
  }

  @Test
  @DisplayName("the empty page fills its card and keeps its message selectable")
  void emptyStateIsCenteredAndSelectable() throws Exception {
    CriticalPathView view = onEdt(CriticalPathView::new);

    onEdt(
        () -> {
          layoutDeep(view, 920, 540);
          assertThat(view.emptyStateForTest().isVisible()).isTrue();
          assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());
          assertThat(view.emptyStateForTest().getText()).isEqualTo("No session is open.");
          assertThat(view.emptyStateForTest().isTextSelectable()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("the graph button opens the computed dependency chain and disables without one")
  void graphButtonHasOneHonestDestination() throws Exception {
    CriticalPathView view = onEdt(CriticalPathView::new);
    AtomicInteger opens = new AtomicInteger();
    onEdt(
        () -> {
          view.onOpenGraph(opens::incrementAndGet);
          view.installGraphSourceForTest(graphSource(ConfigurationMatch.EXACT, null));
          view.show(metrics(paths(8_000, Optional.of(path(5_000)), List.of())));
          assertThat(view.openGraphForTest().getText()).isEqualTo("Open dependency chain in Graph");
          assertThat(view.openGraphForTest().isEnabled()).isTrue();
          view.openGraphForTest().doClick();
          assertThat(opens).hasValue(1);

          view.show(metrics(paths(8_000, Optional.empty(), List.of())));
          assertThat(view.openGraphForTest().isEnabled()).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("a build with no usable spans reports idle time as unavailable, not zero")
  void absentConcurrencyDoesNotInventZeroIdleTime() throws Exception {
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(metricsWithoutConcurrency(paths(8_000, Optional.of(path(5_000)), List.of())));
          return null;
        });

    assertThat(onEdt(view::summaryTextForTest))
        .contains("Observed idle time")
        .contains("—")
        .contains("No usable action spans; this is not zero");
  }

  @Test
  @DisplayName("selecting an unloaded dependency row replaces stale inspector content")
  void unloadedSelectionShowsLoadingInspection() throws Exception {
    CriticalPath.Result path = path(5_000);
    CriticalPathRowSource source = new CriticalPathRowSource(path, requested -> Map.of());
    CriticalPathView view = onEdt(CriticalPathView::new);
    onEdt(
        () -> {
          view.show(
              metrics(
                  paths(
                      8_000,
                      Optional.of(path),
                      List.of(
                          new CriticalPaths.BazelComponent(
                              0, "Old Bazel component", OptionalLong.of(8_000))))));
          assertThat(view.inspectorForTest().displayed().title()).isEqualTo("Old Bazel component");

          view.installDependencySourceForTest(path, source);
          view.dependencyTableForTest().setRowSelectionInterval(0, 0);
          return null;
        });

    assertThat(onEdt(() -> view.inspectorForTest().displayed().title()))
        .isEqualTo("Loading dependency step…");
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  private static CriticalPath.Result path(long weightMicros) {
    return CriticalPath.compute(
        CsrBuilder.build(1, visitor -> {}),
        new long[] {weightMicros},
        CriticalPath.DurationSource.BEP_ACTION);
  }

  private static CriticalPaths.ObservedActionLowerBound observedFallback() {
    return new CriticalPaths.ObservedActionLowerBound(
        42,
        "bazel-out/bin/pkg/app.jar",
        Optional.of("//pkg:app"),
        Optional.of("Javac"),
        4_000,
        3,
        7);
  }

  private static CriticalPaths paths(
      long bazelMicros,
      Optional<CriticalPath.Result> derived,
      List<CriticalPaths.BazelComponent> components) {
    return new CriticalPaths(Measured.of(bazelMicros, DataSource.PROFILE), components, derived);
  }

  private static GraphSource graphSource(ConfigurationMatch match, String detail) {
    return new GraphSource(
        "DECLARED_ACTIONS",
        Optional.of("bazel aquery //..."),
        "SUCCEEDED",
        match,
        Optional.ofNullable(detail),
        GraphTargetScope.EXACT_BEP_TARGETS,
        Optional.of("The query used exact completed-BEP targets."),
        OptionalLong.of(5),
        OptionalLong.of(4),
        OptionalLong.of(0),
        OptionalLong.of(0),
        Optional.empty());
  }

  private static MetricsService.Result metrics(CriticalPaths paths) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    spans.add(0, 1);
    return metrics(paths, spans, Optional.of(ConcurrencySweep.sweep(spans)));
  }

  private static MetricsService.Result metricsWithoutConcurrency(CriticalPaths paths) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    return metrics(paths, spans, Optional.empty());
  }

  private static MetricsService.Result metrics(
      CriticalPaths paths,
      ConcurrencySweep.Spans spans,
      Optional<ConcurrencySweep.Result> invocationConcurrency) {
    ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
    InvocationMetrics invocation =
        new InvocationMetrics(
            new InvocationMetrics.Timing(
                Measured.of(1L, DataSource.BEP),
                Measured.of(1L, DataSource.BES_ENVELOPE),
                List.of()),
            new InvocationMetrics.Work(0, 0, 0, 0, 0, 0, 0, 0, List.of()),
            new InvocationMetrics.Bytes(
                Measured.of(0L, DataSource.EXECUTION_LOG), 0, Measured.of(0L, DataSource.BEP), 0),
            invocationConcurrency,
            paths,
            new InvocationMetrics.Tests(0, 0, 0, 0),
            new InvocationMetrics.Ingest(0, 0, 0, Measured.of(0L, DataSource.BES_ENVELOPE), 0, 0),
            new Coverage.Report(List.of()));
    SessionMetrics session =
        new SessionMetrics(
            CriticalPath.DurationSource.BEP_ACTION,
            invocation,
            Map.of(),
            spans,
            sweep,
            List.of(),
            List.of());
    return new MetricsService.Result(session, List.of(), FindingThresholds.defaults());
  }

  private static void layoutDeep(Container root, int width, int height) {
    root.setSize(width, height);
    for (int pass = 0; pass < 4; pass++) {
      invalidateDeep(root);
      layoutChildren(root);
    }
  }

  private static void invalidateDeep(Container root) {
    root.invalidate();
    for (Component child : root.getComponents()) {
      if (child instanceof Container nested) {
        invalidateDeep(nested);
      }
    }
  }

  private static void layoutChildren(Container root) {
    root.doLayout();
    for (Component child : root.getComponents()) {
      if (child instanceof Container nested) {
        layoutChildren(nested);
      }
    }
  }

  private static <T extends Component> T findOne(Container root, Class<T> type) {
    List<T> matches = new ArrayList<>();
    collect(root, type, matches);
    assertThat(matches).hasSize(1);
    return matches.getFirst();
  }

  private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
    List<T> matches = new ArrayList<>();
    collect(root, type, matches);
    return matches;
  }

  private static <T extends Component> void collect(
      Container root, Class<T> type, List<T> matches) {
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) {
        matches.add(type.cast(child));
      }
      if (child instanceof Container nested) {
        collect(nested, type, matches);
      }
    }
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable throwable) {
            failure.set(throwable);
          }
        });
    if (failure.get() instanceof Exception exception) {
      throw exception;
    }
    if (failure.get() instanceof Error error) {
      throw error;
    }
    return value.get();
  }

  private static void waitUntil(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.call() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.call()).isTrue();
  }
}

package com.holtherndon.bazelviz.ui.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the findings card puts on screen, rendered headlessly from a result the test builds by hand.
 *
 * <p>Building the result by hand is the point of the fixture as much as the convenience: this view
 * is handed a finished collection and renders it, so a test that needs no database is evidence that
 * the view needs none either.
 */
final class FindingsViewTest {

  @Test
  @DisplayName("the no-session message replaces and fills the whole Findings pane")
  void emptyStateFillsThePane() {
    FindingsView view = new FindingsView();
    view.setSize(960, 600);
    layoutTree(view);

    assertThat(view.emptyStateForTest().isVisible()).isTrue();
    assertThat(view.emptyStateForTest().getLocation()).isEqualTo(new Point());
    assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()), List.of(), FindingThresholds.defaults()));
    layoutTree(view);
    assertThat(view.emptyStateForTest().isVisible()).isFalse();

    view.detach();
    layoutTree(view);
    assertThat(view.emptyStateForTest().isVisible()).isTrue();
    assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());
  }

  @Test
  @DisplayName("a queued result cannot reopen Findings after its session is detached")
  void detachedViewDropsQueuedResult(@TempDir Path directory) throws Exception {
    BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
    FindingsView view = new FindingsView();

    try (SessionDatabase database = SessionDatabase.open(directory.resolve("session.db"))) {
      MigrationRunner.standard().migrate(database);
      SessionSource source = metricsOnlySource(database);
      try (MetricsService service =
          new MetricsService(source, uiQueue::add, FindingThresholds.defaults())) {
        view.attach(service);
        Runnable queued = uiQueue.poll(10, TimeUnit.SECONDS);
        assertThat(queued).as("the queued Findings delivery").isNotNull();

        view.detach();
        queued.run();

        assertThat(view.emptyStateForTest().isVisible()).isTrue();
        assertThat(view.findingTitlesForTest()).isEmpty();
        assertThat(view.headlineForTest()).isBlank();
      }
    }
  }

  private static InvocationMetrics invocation(CriticalPaths paths, List<Coverage> coverage) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    spans.add(0, 1_000_000);
    spans.add(500_000, 1_500_000);
    return new InvocationMetrics(
        new InvocationMetrics.Timing(
            Measured.of(2_000_000L, DataSource.BEP),
            Measured.unknown(DataSource.BES_ENVELOPE, Completeness.UNAVAILABLE, "n/a"),
            List.of()),
        new InvocationMetrics.Work(2, 2, 2, 0, 0, 1, 1, 0, List.of()),
        new InvocationMetrics.Bytes(
            Measured.of(1_024L, DataSource.EXECUTION_LOG), 0,
            Measured.of(2_048L, DataSource.BEP), 1),
        Optional.of(ConcurrencySweep.sweep(spans)),
        paths,
        new InvocationMetrics.Tests(0, 0, 0, 0),
        new InvocationMetrics.Ingest(4, 0, 0, Measured.of(300L, DataSource.BES_ENVELOPE), 2, 0),
        new Coverage.Report(coverage));
  }

  private static SessionMetrics metrics(CriticalPaths paths, List<Coverage> coverage) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    spans.add(0, 1_000_000);
    return new SessionMetrics(
        CriticalPath.DurationSource.EXECUTION_ATTEMPT,
        invocation(paths, coverage),
        Map.of(),
        spans,
        ConcurrencySweep.sweep(spans),
        List.of(),
        List.of());
  }

  private static Finding finding(String title) {
    return new Finding(
        "test-rule",
        title,
        Finding.Severity.HIGH,
        Finding.Confidence.MEDIUM,
        List.of(Finding.Evidence.action(11, "//pkg:lib", "took 4.2 s")),
        List.of(new Finding.MetricValue("Duration", "4.2 s", "execution log")),
        "above 1 s",
        "This may be worth investigating.",
        "Measured over one build on one machine.",
        "Open the action and look at its inputs.",
        List.of(Finding.Link.to(Finding.Link.View.TIMELINE, "See it on the timeline")),
        false);
  }

  /**
   * A finding whose evidence and link carry the kind of long, build-reported text that made the
   * findings pane grow wider than the window: a deeply nested target label as the evidence button's
   * text, and a full sentence as the link's.
   */
  private static Finding longFinding() {
    String longLabel =
        "//some/really/deeply/nested/bazel/package/path/with/many/segments"
            + "/that/a/real/monorepo/would/have:a_target_name_that_is_quite_long_on_its_own";
    return new Finding(
        "test-rule",
        "The dependency chain accounts for a large share of the build's wall time,"
            + " which is a full sentence long enough to overflow a narrow detail pane",
        Finding.Severity.HIGH,
        Finding.Confidence.MEDIUM,
        List.of(
            Finding.Evidence.action(
                11,
                longLabel,
                "took much longer than every other action of this mnemonic, by a wide"
                    + " margin worth reading in full rather than cut off")),
        List.of(
            new Finding.MetricValue(
                "A rather long metric name describing exactly what was measured",
                "a similarly long formatted value with several clauses in it",
                "execution log")),
        "above 1 s",
        "This may be worth investigating.",
        "Measured over one build on one machine.",
        "Open the action and look at its inputs.",
        List.of(
            Finding.Link.to(
                Finding.Link.View.TIMELINE,
                "This link description is also a full sentence naming exactly where"
                    + " to look next, long enough on its own to force a button wider"
                    + " than a narrow window")),
        false);
  }

  private static CriticalPaths bothPaths() {
    CriticalPath.Result derived =
        CriticalPath.compute(
            CsrBuilder.build(2, visitor -> visitor.edge(0, 1)),
            new long[] {400_000, 300_000},
            CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    return new CriticalPaths(
        Measured.of(1_100_000L, DataSource.PROFILE),
        List.of(
            new CriticalPaths.BazelComponent(
                0, "action 'Compiling foo.cc'", OptionalLong.of(1_100_000))),
        Optional.of(derived));
  }

  @Test
  @DisplayName("both critical paths appear, under their own names, as two rows")
  void bothCriticalPathsAreShown() {
    FindingsView view = new FindingsView();

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));

    String summary = view.summaryTextForTest();
    assertThat(summary)
        .contains("Bazel-reported critical path")
        .contains("Visualizer-computed dependency critical path")
        .contains("they measure different things");
    // There is no row that simply says "Critical path": the unqualified
    // name is the one this whole arrangement exists to prevent.
    assertThat(summary.lines().filter(line -> line.equals("Critical path")))
        .as("an unqualified critical-path row")
        .isEmpty();
  }

  @Test
  @DisplayName("a missing path says why it is missing rather than borrowing the other's number")
  void anAbsentPathExplainsItself() {
    FindingsView view = new FindingsView();
    CriticalPaths onlyBazel =
        new CriticalPaths(
            Measured.of(900_000L, DataSource.PROFILE),
            List.of(),
            Optional.empty(),
            Optional.of("the action graph has unresolved artifact references"));

    view.show(
        new MetricsService.Result(
            metrics(onlyBazel, List.of()), List.of(), FindingThresholds.defaults()));

    assertThat(view.summaryTextForTest())
        .contains("the action graph has unresolved artifact references")
        .doesNotContain("no imported action graph to compute it over");
    assertThat(view.headlineForTest()).contains("No findings");
  }

  @Test
  @DisplayName("the Bazel path row preserves an exact profile provenance warning")
  void bazelProfileMismatchWarningIsVisible() {
    FindingsView view = new FindingsView();
    CriticalPaths warned =
        new CriticalPaths(
            Measured.of(900_000L, DataSource.BEP)
                .warn("profile component breakdown withheld: build id mismatch"),
            List.of(),
            Optional.empty(),
            Optional.of("no graph"));

    view.show(
        new MetricsService.Result(
            metrics(warned, List.of()), List.of(), FindingThresholds.defaults()));

    assertThat(view.summaryTextForTest())
        .contains("profile component breakdown withheld: build id mismatch")
        .doesNotContain("component breakdown unavailable");
  }

  @Test
  @DisplayName("coverage is on the same screen as the findings that rest on it")
  void coverageIsShownBesideTheFindings() {
    FindingsView view = new FindingsView();
    List<Coverage> coverage =
        List.of(
            Coverage.of(
                "Timing coverage",
                4,
                13,
                DataSource.EXECUTION_LOG,
                "most actions never spawn a subprocess"),
            Coverage.unavailable(
                "Action-graph correlation",
                13,
                DataSource.AQUERY,
                "no aquery output was imported"));

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), coverage),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));

    assertThat(view.summaryTextForTest())
        .contains("Timing coverage")
        .contains("30.8%")
        .contains("Action-graph correlation")
        .contains("unavailable");
  }

  @Test
  @DisplayName("selecting a finding shows every part plan 16 requires")
  void everyPartOfAFindingIsRendered() {
    FindingsView view = new FindingsView();

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));
    view.selectForTest(0);

    String detail = view.detailTextForTest();
    assertThat(detail)
        .contains("Something is slow")
        .contains("Severity High")
        .contains("confidence Medium")
        .contains("Why it may matter")
        .contains("This may be worth investigating.")
        .contains("Threshold used")
        .contains("above 1 s")
        .contains("Duration: 4.2 s")
        .contains("Source — execution log")
        .contains("//pkg:lib")
        .contains("Caveats")
        .contains("Suggested next investigation")
        .contains("See it on the timeline");
  }

  @Test
  @DisplayName("the evidence button reports the action it points at")
  void evidenceNavigates() {
    FindingsView view = new FindingsView();
    List<Long> opened = new ArrayList<>();
    view.onActionSelected(opened::add);

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));
    view.selectForTest(0);
    clickButtonLabelled(view, "//pkg:lib — took 4.2 s");

    assertThat(opened).containsExactly(11L);
  }

  @Test
  @DisplayName("a link reports the view the rule named")
  void linksNavigate() {
    FindingsView view = new FindingsView();
    List<Finding.Link> followed = new ArrayList<>();
    view.onNavigate(followed::add);

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));
    view.selectForTest(0);
    clickButtonLabelled(view, "See it on the timeline");

    assertThat(followed).hasSize(1);
    assertThat(followed.getFirst().view()).isEqualTo(Finding.Link.View.TIMELINE);
  }

  @Test
  @DisplayName(
      "the evidence and link buttons carry their full text as a tooltip, so a"
          + " narrow window's ellipsis-clipped label is still readable on hover")
  void evidenceAndLinkButtonsHaveATooltip() {
    FindingsView view = new FindingsView();

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));
    view.selectForTest(0);

    AbstractButton evidence = findButton(view, "//pkg:lib — took 4.2 s");
    assertThat(evidence).as("the evidence button").isNotNull();
    assertThat(evidence.getToolTipText()).isEqualTo("//pkg:lib — took 4.2 s");

    AbstractButton link = findButton(view, "See it on the timeline");
    assertThat(link).as("the link button").isNotNull();
    assertThat(link.getToolTipText()).isEqualTo("See it on the timeline");
  }

  @Test
  @DisplayName(
      "the summary and catalog grids track the viewport's width instead of" + " overflowing it")
  void topContentTracksViewportWidth() {
    FindingsView view = new FindingsView();
    List<Coverage> coverage =
        List.of(
            Coverage.of(
                "Timing coverage",
                4,
                13,
                DataSource.EXECUTION_LOG,
                "most actions never spawn a subprocess"),
            Coverage.unavailable(
                "Action-graph correlation",
                13,
                DataSource.AQUERY,
                "no aquery output was imported"));

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), coverage),
            List.of(finding("Something is slow")),
            FindingThresholds.defaults()));

    ScrollableViewport top = view.topForTest();
    // The scroll pane only narrows its view when the view says to. Without
    // this, the summary and catalog grids keep their own preferred width
    // and the scroll pane grows a horizontal scrollbar instead of
    // shrinking the content -- which is the bug: the tab reads as wider
    // than the app, not merely scrollable within it.
    assertThat(top.getScrollableTracksViewportWidth())
        .as(
            "a JScrollPane must be told to size this view to its own width, or it hands"
                + " the view its preferred width unconditionally")
        .isTrue();

    // Rows like "Difference between them" carry a full sentence, not just
    // a number, and under the old GridLayout(0, 2, ...) every row in a
    // column was forced to the width of that column's single widest
    // cell. Embed the real scroll pane at a width narrower than that
    // sentence and confirm no horizontal scrollbar appears: the content
    // must stay within the app's bounds regardless of what any one row
    // says.
    JScrollPane scroll = view.topScrollForTest();
    scroll.setSize(320, 400);
    scroll.doLayout();
    scroll.validate();
    assertThat(scroll.getHorizontalScrollBar().isVisible())
        .as(
            "the findings pane must not grow a horizontal scrollbar, however long a"
                + " single row's value is")
        .isFalse();
  }

  @Test
  @DisplayName(
      "the detail pane tracks the viewport's width even when a finding's evidence"
          + " or link text is long")
  void detailContentTracksViewportWidth() {
    FindingsView view = new FindingsView();

    view.show(
        new MetricsService.Result(
            metrics(bothPaths(), List.of()), List.of(longFinding()), FindingThresholds.defaults()));
    view.selectForTest(0);

    ScrollableViewport detail = view.detailForTest();
    assertThat(detail.getScrollableTracksViewportWidth())
        .as(
            "a JScrollPane must be told to size this view to its own width, or it hands"
                + " the view its preferred width unconditionally")
        .isTrue();

    // The evidence and link rows are JButtons, and a JButton's text
    // cannot wrap -- so the property under test is not that the button
    // itself gets narrower, but that a button demanding far more width
    // than it is given still does not grow the scroll pane a horizontal
    // scrollbar. 320px is comfortably narrower than the long evidence
    // and link text used here.
    JScrollPane scroll = view.detailScrollForTest();
    scroll.setSize(320, 400);
    scroll.doLayout();
    scroll.validate();
    assertThat(scroll.getHorizontalScrollBar().isVisible())
        .as(
            "the detail pane must not grow a horizontal scrollbar, however long an"
                + " evidence or link button's text is")
        .isFalse();
  }

  @Test
  @DisplayName(
      "every scroll pane uses a fast wheel increment, not the 1-pixel-per-notch" + " default")
  void scrollingIsNotExtremelySlow() {
    FindingsView view = new FindingsView();

    // The default JScrollBar unit increment is a single pixel per notch,
    // which over content this tall reads as "scrolling is extremely
    // slow" -- the second half of this bug, alongside the overflow.
    assertThat(view.topScrollForTest().getVerticalScrollBar().getUnitIncrement())
        .as("top scroll pane")
        .isEqualTo(16);
    assertThat(view.listScrollForTest().getVerticalScrollBar().getUnitIncrement())
        .as("list scroll pane")
        .isEqualTo(16);
    assertThat(view.detailScrollForTest().getVerticalScrollBar().getUnitIncrement())
        .as("detail scroll pane")
        .isEqualTo(16);
  }

  @Test
  @DisplayName("the view holds no connection and no executor; the service holds both")
  void theViewCannotRead() throws Exception {
    // The Phase 6 and Phase 7 pattern: assert what the class can reach, not
    // what it happens to call. A view that could open a connection would be
    // one release away from doing so on the EDT.
    for (Field field : FindingsView.class.getDeclaredFields()) {
      assertThat(field.getType().getName())
          .as("FindingsView.%s", field.getName())
          .doesNotContain("java.sql")
          .doesNotContain("storage.metrics.MetricQueries");
      assertThat(Executor.class.isAssignableFrom(field.getType()))
          .as("FindingsView.%s is an executor", field.getName())
          .isFalse();
    }
    // And the split has not collapsed: the service really does hold one.
    assertThat(
            Arrays.stream(MetricsService.class.getDeclaredFields())
                .anyMatch(field -> ExecutorService.class.isAssignableFrom(field.getType())))
        .as("MetricsService must own the executor")
        .isTrue();
  }

  private static void clickButtonLabelled(Container root, String text) {
    AbstractButton button = findButton(root, text);
    assertThat(button).as("a button labelled \"%s\"", text).isNotNull();
    button.doClick();
  }

  private static AbstractButton findButton(Container root, String text) {
    for (Component component : root.getComponents()) {
      if (component instanceof AbstractButton button && text.equals(button.getText())) {
        return button;
      }
      if (component instanceof Container container) {
        AbstractButton found = findButton(container, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static void layoutTree(Container container) {
    for (int pass = 0; pass < 4; pass++) {
      invalidateTree(container);
      layoutChildren(container);
    }
  }

  private static void invalidateTree(Container container) {
    container.invalidate();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        invalidateTree(nested);
      }
    }
  }

  private static void layoutChildren(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        layoutChildren(nested);
      }
    }
  }

  private static SessionSource metricsOnlySource(SessionDatabase database) {
    return (SessionSource)
        Proxy.newProxyInstance(
            SessionSource.class.getClassLoader(),
            new Class<?>[] {SessionSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "openMetricQueries" -> metricQueries(database);
                  case "close" -> null;
                  case "toString" -> "FindingsMetricsSession";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static MetricQueries metricQueries(SessionDatabase database) {
    try {
      return new MetricQueries(database.newReadConnection());
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}

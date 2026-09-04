package com.holtherndon.bazelviz.ui.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.ui.metrics.MetricsService;
import com.holtherndon.bazelviz.ui.nav.NavEntry;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What the overview says, and how often it says it. */
class OverviewPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  @Test
  @DisplayName("a build that never reported an outcome is not reported as a failure")
  void outcomeHasThreeStates() throws Exception {
    assertThat(headlineFor(Optional.of(true))).isEqualTo("bazel build — succeeded");
    assertThat(headlineFor(Optional.of(false))).isEqualTo("bazel build — failed");
    // The third state is the one that matters: a build whose BuildFinished
    // never arrived died before it could say, and calling that "failed"
    // would state something the stream does not.
    assertThat(headlineFor(Optional.empty())).isEqualTo("bazel build — outcome not reported");
  }

  @Test
  @DisplayName("a stream that never reached its end marker says so")
  void truncatedStreamsAreCalledOut() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Overview"));
    onEdt(
        () -> {
          panel.installPageToolbar(toolbar);
          panel.show(snapshot(Optional.of(true), false));
          return null;
        });

    // Aborted events arrive after buildFinished, so a capture that stopped
    // at the wrong place is missing the whole failed-target list. The
    // subhead is where that gets said.
    assertThat(panel.subheadForTest()).contains("did not reach its end marker");
    assertThat(onEdt(toolbar::actionCount)).isZero();
    assertThat(onEdt(toolbar::metadata))
        .contains("bazel build — succeeded", "did not reach its end marker");
  }

  @Test
  @DisplayName("the tile grid tracks the viewport's width instead of overflowing it")
  void contentTracksViewportWidth() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    onEdt(
        () -> {
          panel.show(snapshot(Optional.of(true), true));
          return null;
        });

    ScrollableViewport content = onEdt(panel::contentForTest);
    // The scroll pane only narrows its view when the view says to. Without
    // this, the view keeps its own preferred width and the scroll pane
    // grows a horizontal scrollbar instead of shrinking the content --
    // which is the bug: the tab reads as wider than the app, not merely
    // scrollable within it.
    assertThat(content.getScrollableTracksViewportWidth())
        .as(
            "a JScrollPane must be told to size this view to its own width, or it hands"
                + " the view its preferred width unconditionally")
        .isTrue();

    // Four tiles under the old GridLayout(0, 4, 12, 12) preferred 580px
    // wide even with these short, un-embellished values (measured
    // directly): every column is sized to the widest cell, times four,
    // regardless of how narrow the window actually is. 360px is
    // comfortably narrower than that, so a container that still demands
    // more than 360px here has not actually started reflowing.
    int narrowWidth = 360;
    onEdt(
        () -> {
          content.setSize(narrowWidth, 2000);
          content.validate();
          return null;
        });

    int preferredWidth = onEdt(() -> content.getPreferredSize().width);
    assertThat(preferredWidth)
        .as(
            "once actually given a narrow width, the tile grid must reflow to fit it"
                + " instead of continuing to demand a wider one")
        .isLessThanOrEqualTo(narrowWidth);
  }

  @Test
  @DisplayName("the empty overview is centred in the pane instead of laid out as a top card")
  void emptyStateUsesTheWholePane() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    onEdt(
        () -> {
          JPanelLayout.layoutTree(panel, 1_000, 600);
          assertThat(panel.emptyStateForTest().isVisible()).isTrue();
          assertThat(panel.emptyStateForTest().getSize()).isEqualTo(panel.getSize());

          panel.show(snapshot(Optional.of(true), true));
          JPanelLayout.layoutTree(panel, 1_000, 600);
          assertThat(panel.emptyStateForTest().isVisible()).isFalse();
          assertThat(panel.scrollForTest().isVisible()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("overview fills the viewport and collapses to safe narrow columns")
  void cardsAreCompactAndResponsive() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    onEdt(
        () -> {
          panel.show(snapshot(Optional.of(true), true));

          JPanelLayout.layout(panel.tilesForTest(), 1_000, 1_000);
          Component[] tiles = panel.tilesForTest().getComponents();
          assertThat(tiles).hasSize(4);
          assertThat(tiles[0].getY()).isEqualTo(tiles[3].getY());
          assertThat(tiles[0].getX()).isLessThan(tiles[1].getX());
          assertThat(tiles[3].getX() + tiles[3].getWidth()).isLessThanOrEqualTo(1_000);

          JPanelLayout.layout(panel.detailsForTest(), 1_400, 2_000);
          Component[] details = panel.detailsForTest().getComponents();
          assertThat(details).hasSize(2);
          assertThat(details[0].getX()).isLessThan(details[1].getX());

          JPanelLayout.layout(panel.tilesForTest(), 360, 2_000);
          for (int i = 1; i < tiles.length; i++) {
            assertThat(tiles[i].getX()).isEqualTo(tiles[0].getX());
            assertThat(tiles[i].getY()).isGreaterThan(tiles[i - 1].getY());
            assertThat(tiles[i].getX() + tiles[i].getWidth()).isLessThanOrEqualTo(360);
          }

          JPanelLayout.layout(panel.detailsForTest(), 360, 3_000);
          assertThat(details[1].getX()).isEqualTo(details[0].getX());
          assertThat(details[1].getY()).isGreaterThan(details[0].getY());
          assertThat(details[1].getX() + details[1].getWidth()).isLessThanOrEqualTo(360);

          // Exercise the real panel through a resize. The old centred cap
          // looked correct in direct inner-grid tests, then kept the old
          // width and manufactured gutters when the window expanded.
          JPanelLayout.layoutTree(panel, 1_100, 700);
          Component dashboard = panel.dashboardForTest();
          int initialWidth = dashboard.getWidth();
          int initialExtent = panel.scrollForTest().getViewport().getExtentSize().width;
          assertThat(panel.contentForTest().getWidth()).isEqualTo(initialExtent);
          assertThat(initialWidth).isEqualTo(panel.contentForTest().getWidth());
          assertThat(panel.tilesForTest().getWidth()).isEqualTo(initialWidth);
          assertThat(panel.detailsForTest().getWidth()).isEqualTo(initialWidth);
          assertThat(dashboard.getX()).isZero();

          JPanelLayout.layoutTree(panel, 1_500, 700);
          int expandedExtent = panel.scrollForTest().getViewport().getExtentSize().width;
          assertThat(expandedExtent).isGreaterThan(initialExtent + 300);
          assertThat(panel.contentForTest().getWidth()).isEqualTo(expandedExtent);
          assertThat(dashboard.getWidth()).isGreaterThan(initialWidth + 300);
          assertThat(dashboard.getWidth()).isEqualTo(panel.contentForTest().getWidth());
          assertThat(panel.tilesForTest().getWidth()).isEqualTo(dashboard.getWidth());
          assertThat(panel.detailsForTest().getWidth()).isEqualTo(dashboard.getWidth());
          assertThat(dashboard.getX()).isZero();
          assertThat(panel.scrollForTest().getHorizontalScrollBarPolicy())
              .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
          assertThat(panel.scrollForTest().getHorizontalScrollBar().isVisible()).isFalse();

          JPanelLayout.layoutTree(panel, 360, 700);
          assertThat(dashboard.getWidth()).isLessThanOrEqualTo(360);
          assertThat(dashboard.getX() + dashboard.getWidth())
              .isLessThanOrEqualTo(dashboard.getParent().getWidth());
          return null;
        });
  }

  @Test
  @DisplayName("summary and metric reads share one grid without losing absence explanations")
  void metricCardsRemainCohesiveAndExplicit() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    onEdt(
        () -> {
          panel.show(snapshot(Optional.of(true), true));
          panel.showMetrics(unavailableMetrics());
          JPanelLayout.layout(panel.tilesForTest(), 1_000, 2_000);
          return null;
        });

    Component[] tiles = onEdt(() -> panel.tilesForTest().getComponents());
    assertThat(tiles).hasSize(8);
    assertThat(tiles[0].getY()).isEqualTo(tiles[3].getY());
    assertThat(tiles[4].getY()).isEqualTo(tiles[7].getY());
    assertThat(tiles[4].getY()).isGreaterThan(tiles[0].getY());

    String text = onEdt(() -> textIn(panel));
    assertThat(text)
        .contains("Bazel-reported critical path")
        .contains("not reported by this build")
        .contains("Visualizer-computed dependency critical path")
        .contains("no confirmed action graph")
        .contains("nothing was timed")
        .contains("1 coverage gaps to read them against")
        .contains("Action-graph correlation")
        .contains("unavailable");
    assertThat(onEdt(() -> panel.detailsForTest().getComponentCount())).isEqualTo(3);
    assertThat(
            onEdt(
                () ->
                    Arrays.stream(panel.detailsForTest().getComponents())
                        .map(
                            component ->
                                ((TitledBorder) ((JComponent) component).getBorder()).getTitle())
                        .toList()))
        .containsExactly("This session counted", "Bazel reported", "Data completeness");
  }

  @Test
  @DisplayName("a Bazel total without profile rows says its component breakdown is unavailable")
  void bazelTotalDoesNotClaimZeroComponents() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(8_000L, DataSource.BEP),
            List.of(),
            Optional.empty(),
            Optional.of("no graph"));

    onEdt(
        () -> {
          panel.showMetrics(metrics(paths));
          return null;
        });

    assertThat(onEdt(() -> textIn(panel)))
        .contains("component breakdown unavailable")
        .doesNotContain("0 components");
  }

  @Test
  @DisplayName("a withheld profile breakdown keeps its exact provenance warning")
  void bazelProfileMismatchWarningIsVisible() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(8_000L, DataSource.BEP)
                .warn("profile component breakdown withheld: build id mismatch"),
            List.of(),
            Optional.empty(),
            Optional.of("no graph"));

    onEdt(
        () -> {
          panel.showMetrics(metrics(paths));
          return null;
        });

    assertThat(onEdt(() -> textIn(panel)))
        .contains("profile component breakdown withheld: build id mismatch")
        .doesNotContain("component breakdown unavailable");
  }

  @Test
  @DisplayName("compacting the dashboard keeps summary-card navigation")
  void tilesStillNavigate() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    List<NavEntry> opened = new ArrayList<>();
    onEdt(
        () -> {
          panel.onNavigate(opened::add);
          panel.show(snapshot(Optional.of(true), true));
          Component targets = panel.tilesForTest().getComponent(0);
          Component visibleValue = ((Container) targets).getComponent(1);
          MouseEvent click =
              new MouseEvent(
                  visibleValue,
                  MouseEvent.MOUSE_CLICKED,
                  System.currentTimeMillis(),
                  0,
                  4,
                  4,
                  1,
                  false,
                  MouseEvent.BUTTON1);
          visibleValue.dispatchEvent(click);
          assertThat(targets.isFocusable()).isTrue();
          assertThat(((JComponent) targets).getAccessibleContext().getAccessibleDescription())
              .isEqualTo("Open Top Level Targets");
          return null;
        });

    assertThat(opened).containsExactly(NavEntry.TARGETS);
  }

  @Test
  @DisplayName("both critical-path summary cards open the analysis page")
  void criticalPathTilesShareTheExplicitAnalysisDestination() throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    List<NavEntry> opened = new ArrayList<>();
    onEdt(
        () -> {
          panel.onNavigate(opened::add);
          panel.show(snapshot(Optional.of(true), true));
          panel.showMetrics(unavailableMetrics());

          for (int index : List.of(4, 5)) {
            Component card = panel.tilesForTest().getComponent(index);
            Component visibleValue = ((Container) card).getComponent(1);
            visibleValue.dispatchEvent(
                new MouseEvent(
                    visibleValue,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    4,
                    4,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            assertThat(((JComponent) card).getAccessibleContext().getAccessibleDescription())
                .isEqualTo("Open Critical Path");
          }
          return null;
        });

    assertThat(opened).containsExactly(NavEntry.CRITICAL_PATH, NavEntry.CRITICAL_PATH);
  }

  @Test
  @Timeout(60)
  @DisplayName("a build changing thousands of times refreshes on the interval, not per change")
  void liveUpdatesAreCoalesced() throws Exception {
    FakeEntityReader reader = new FakeEntityReader();
    Duration interval = Duration.ofMillis(40);
    OverviewPanel panel = onEdt(() -> new OverviewPanel(interval));
    AtomicInteger renders = new AtomicInteger();
    onEdt(
        () -> {
          panel.onSnapshot(snapshot -> renders.incrementAndGet());
          panel.openSession(new FakeSource(reader));
          return null;
        });

    // Change the underlying numbers far faster than the refresh interval,
    // as a build writing rows would.
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
    long changes = 0;
    while (System.nanoTime() < deadline) {
      reader.advance();
      changes++;
    }
    TimeUnit.MILLISECONDS.sleep(100);

    long reads = reader.overviewReads();
    assertThat(changes).isGreaterThan(1_000L);
    // The property: reads follow the clock, not the data. Ten intervals
    // elapsed, so a couple of dozen reads is generous headroom and still
    // orders of magnitude below the number of changes.
    assertThat(reads).isPositive().isLessThan(50L);
    assertThat(renders.get()).isPositive().isLessThanOrEqualTo((int) reads);

    onEdt(
        () -> {
          panel.closeSession();
          return null;
        });
  }

  @Test
  @Timeout(10)
  @DisplayName("a replaced opener cannot overwrite or read through the new session")
  void replacementOwnsItsReaderAndClosesTheRetiredReader() throws Exception {
    CountDownLatch oldOpenStarted = new CountDownLatch(1);
    CountDownLatch releaseOldOpen = new CountDownLatch(1);
    SnapshotReader oldReader = new SnapshotReader(snapshot("old", Optional.of(true), false));
    SnapshotReader newReader = new SnapshotReader(snapshot("new", Optional.of(true), false));
    FakeSource oldSource =
        new FakeSource(
            oldReader,
            () -> {
              oldOpenStarted.countDown();
              awaitIgnoringInterruption(releaseOldOpen);
              return oldReader;
            });
    FakeSource newSource = new FakeSource(newReader);
    OverviewPanel panel = onEdt(() -> new OverviewPanel(Duration.ofMillis(25)));
    List<String> renderedCommands = new CopyOnWriteArrayList<>();

    try {
      onEdt(
          () -> {
            panel.onSnapshot(
                snapshot -> renderedCommands.add(snapshot.command().orElse("missing")));
            panel.openSession(oldSource);
            return null;
          });
      await(oldOpenStarted, "old reader open to begin");

      onEdt(
          () -> {
            panel.openSession(newSource);
            return null;
          });
      awaitUntil(
          () -> renderedCommands.contains("new") && newReader.reads() >= 2,
          "new session to refresh");
      assertThat(oldReader.closeCalls())
          .as("the retired reader cannot close until its opener returns")
          .isZero();

      releaseOldOpen.countDown();
      awaitUntil(() -> oldReader.closeCalls() == 1, "retired reader to close");
      onEdt(() -> null);

      assertThat(oldReader.reads())
          .as("a retired opener must not start reading after its context was replaced")
          .isZero();
      assertThat(renderedCommands).containsOnly("new");
      assertThat(onEdt(panel::attachedSession)).contains(newSource);

      CompletionStage<Void> closed = onEdt(panel::closeSessionAsync);
      closed.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertThat(newReader.closeCalls()).isEqualTo(1);
    } finally {
      releaseOldOpen.countDown();
      onEdt(
          () -> {
            panel.closeSession();
            return null;
          });
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("a stale final snapshot cannot publish or stop the replacement timer")
  void staleSnapshotCannotAffectReplacement() throws Exception {
    BlockingSnapshotReader oldReader =
        new BlockingSnapshotReader(snapshot("old", Optional.of(true), true));
    SnapshotReader newReader = new SnapshotReader(snapshot("new", Optional.of(true), false));
    OverviewPanel panel = onEdt(() -> new OverviewPanel(Duration.ofMillis(25)));
    List<String> renderedCommands = new CopyOnWriteArrayList<>();

    try {
      onEdt(
          () -> {
            panel.onSnapshot(
                snapshot -> renderedCommands.add(snapshot.command().orElse("missing")));
            panel.openSession(new FakeSource(oldReader));
            return null;
          });
      await(oldReader.started(), "old snapshot read to begin");

      onEdt(
          () -> {
            panel.openSession(new FakeSource(newReader));
            return null;
          });
      awaitUntil(() -> renderedCommands.size() >= 2, "replacement snapshots to render");
      onEdt(() -> null);
      int renderedBeforeRelease = renderedCommands.size();

      oldReader.release();
      await(oldReader.returned(), "old snapshot read to return");
      awaitUntil(() -> oldReader.closeCalls() == 1, "old reader to close");
      awaitUntil(
          () -> renderedCommands.size() >= renderedBeforeRelease + 2,
          "replacement timer to keep rendering");

      assertThat(renderedCommands).containsOnly("new");
    } finally {
      oldReader.release();
      CompletionStage<Void> closed = onEdt(panel::closeSessionAsync);
      closed.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("a timed-out refresher leaves its reader open and fails close")
  void closeTimeoutDoesNotCloseReaderUnderRunningWork() throws Exception {
    BlockingSnapshotReader reader =
        new BlockingSnapshotReader(snapshot("blocked", Optional.of(true), false));
    FakeSource source = new FakeSource(reader);
    OverviewPanel panel = onEdt(() -> new OverviewPanel(Duration.ofSeconds(30)));

    try {
      onEdt(
          () -> {
            panel.openSession(source);
            return null;
          });
      await(reader.started(), "overview read to begin");

      CompletionStage<Void> close = onEdt(panel::closeSessionAsync);
      assertThatThrownBy(() -> close.toCompletableFuture().get(5, TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("bbv-overview did not stop after bounded shutdown waits");
      assertThat(reader.closeCalls())
          .as("the reader must remain open while its query still owns it")
          .isZero();
      assertThat(onEdt(panel::attachedSession)).isEmpty();
      assertThat(onEdt(panel::closeSessionAsync))
          .as("repeated close should retain this detached context's failure")
          .isSameAs(close);

      SnapshotReader replacement =
          new SnapshotReader(snapshot("replacement", Optional.of(true), false));
      onEdt(
          () -> {
            panel.openSession(new FakeSource(replacement));
            return null;
          });
      awaitUntil(() -> replacement.reads() > 0, "replacement session to read");
      CompletionStage<Void> replacementClose = onEdt(panel::closeSessionAsync);
      replacementClose.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertThat(replacement.closeCalls())
          .as("an earlier timeout must not poison a later session's close")
          .isEqualTo(1);

      reader.release();
      await(reader.returned(), "blocked read to return during cleanup");
      awaitUntil(() -> reader.closeCalls() == 1, "stale reader to close after its query returns");
    } finally {
      reader.release();
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("a reader returned after its opener times out is still closed")
  void lateReaderFromTimedOutOpenerIsClosed() throws Exception {
    CountDownLatch openStarted = new CountDownLatch(1);
    CountDownLatch releaseOpen = new CountDownLatch(1);
    CountDownLatch openReturned = new CountDownLatch(1);
    SnapshotReader reader = new SnapshotReader(snapshot("late", Optional.of(true), false));
    FakeSource source =
        new FakeSource(
            reader,
            () -> {
              openStarted.countDown();
              awaitIgnoringInterruption(releaseOpen);
              openReturned.countDown();
              return reader;
            });
    OverviewPanel panel = onEdt(() -> new OverviewPanel(Duration.ofSeconds(30)));

    try {
      onEdt(
          () -> {
            panel.openSession(source);
            return null;
          });
      await(openStarted, "entity reader open to begin");

      CompletionStage<Void> close = onEdt(panel::closeSessionAsync);
      assertThatThrownBy(() -> close.toCompletableFuture().get(5, TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("bbv-overview did not stop after bounded shutdown waits");
      assertThat(reader.closeCalls()).isZero();

      releaseOpen.countDown();
      await(openReturned, "entity reader open to return");
      awaitUntil(() -> reader.closeCalls() == 1, "late reader to close after its opener returns");
      assertThat(reader.closedOnEdt()).isFalse();
      assertThat(reader.reads())
          .as("a reader returned to a stale context must never be read")
          .isZero();
    } finally {
      releaseOpen.countDown();
    }
  }

  private static String headlineFor(Optional<Boolean> success) throws Exception {
    OverviewPanel panel = onEdt(OverviewPanel::new);
    onEdt(
        () -> {
          panel.show(snapshot(success, true));
          return null;
        });
    return panel.headlineForTest();
  }

  private static OverviewSnapshot snapshot(Optional<Boolean> success, boolean sawLastMessage) {
    return snapshot("build", success, sawLastMessage);
  }

  private static OverviewSnapshot snapshot(
      String command, Optional<Boolean> success, boolean sawLastMessage) {
    return new OverviewSnapshot(
        Optional.of("9.2.0"),
        Optional.of(command),
        Optional.of("/ws"),
        Optional.of(true),
        success,
        Optional.empty(),
        OptionalLong.of(1_000_000L),
        sawLastMessage,
        4,
        4,
        4,
        0,
        0,
        12,
        0,
        0,
        0,
        9,
        0,
        0,
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        List.of());
  }

  private static MetricsService.Result unavailableMetrics() {
    CriticalPaths paths =
        new CriticalPaths(
            Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE, "not reported"),
            List.of(),
            Optional.empty());
    return metrics(paths);
  }

  private static MetricsService.Result metrics(CriticalPaths paths) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    spans.add(0, 1);
    ConcurrencySweep.Result swept = ConcurrencySweep.sweep(spans);
    InvocationMetrics invocation =
        new InvocationMetrics(
            new InvocationMetrics.Timing(
                Measured.of(1L, DataSource.BEP),
                Measured.of(1L, DataSource.BES_ENVELOPE),
                List.of()),
            new InvocationMetrics.Work(0, 0, 0, 0, 0, 0, 0, 0, List.of()),
            new InvocationMetrics.Bytes(
                Measured.of(0L, DataSource.EXECUTION_LOG), 0, Measured.of(0L, DataSource.BEP), 0),
            Optional.empty(),
            paths,
            new InvocationMetrics.Tests(0, 0, 0, 0),
            new InvocationMetrics.Ingest(0, 0, 0, Measured.of(0L, DataSource.BES_ENVELOPE), 0, 0),
            new Coverage.Report(
                List.of(
                    Coverage.unavailable(
                        "Action-graph correlation",
                        13,
                        DataSource.AQUERY,
                        "no aquery output was imported"))));
    SessionMetrics metrics =
        new SessionMetrics(
            CriticalPath.DurationSource.EXECUTION_ATTEMPT,
            invocation,
            Map.of(),
            spans,
            swept,
            List.of(),
            List.of());
    return new MetricsService.Result(metrics, List.of(), FindingThresholds.defaults());
  }

  private static String textIn(Container root) {
    StringBuilder text = new StringBuilder();
    collectText(root, text);
    return text.toString();
  }

  private static void collectText(Component component, StringBuilder into) {
    if (component instanceof JLabel label) {
      into.append(label.getText()).append('\n');
    } else if (component instanceof JTextArea area) {
      into.append(area.getText()).append('\n');
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectText(child, into);
      }
    }
  }

  private static void await(CountDownLatch latch, String description) throws InterruptedException {
    assertThat(latch.await(3, TimeUnit.SECONDS)).as(description).isTrue();
  }

  private static void awaitUntil(BooleanSupplier condition, String description)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(condition.getAsBoolean()).as(description).isTrue();
  }

  private static void awaitIgnoringInterruption(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Exception e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) {
      throw failure.get();
    }
    return value.get();
  }

  /** Keeps the repetitive headless layout sequence out of the assertions. */
  private static final class JPanelLayout {

    private static void layout(JPanel panel, int width, int height) {
      panel.setSize(width, height);
      panel.invalidate();
      panel.doLayout();
    }

    private static void layoutTree(Container root, int width, int height) {
      root.setSize(width, height);
      for (int pass = 0; pass < 4; pass++) {
        invalidateTree(root);
        layoutChildren(root);
      }
    }

    private static void invalidateTree(Container root) {
      root.invalidate();
      for (Component child : root.getComponents()) {
        if (child instanceof Container nested) {
          invalidateTree(nested);
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
  }

  private static class SnapshotReader extends FakeEntityReader {

    private final OverviewSnapshot snapshot;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicReference<Boolean> closedOnEdt = new AtomicReference<>();

    private SnapshotReader(OverviewSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public OverviewSnapshot overview() {
      reads.incrementAndGet();
      return snapshot;
    }

    @Override
    public void close() {
      closedOnEdt.compareAndSet(null, SwingUtilities.isEventDispatchThread());
      closeCalls.incrementAndGet();
    }

    int reads() {
      return reads.get();
    }

    int closeCalls() {
      return closeCalls.get();
    }

    boolean closedOnEdt() {
      return Boolean.TRUE.equals(closedOnEdt.get());
    }
  }

  private static final class BlockingSnapshotReader extends SnapshotReader {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch returned = new CountDownLatch(1);

    private BlockingSnapshotReader(OverviewSnapshot snapshot) {
      super(snapshot);
    }

    @Override
    public OverviewSnapshot overview() {
      started.countDown();
      awaitIgnoringInterruption(release);
      try {
        return super.overview();
      } finally {
        returned.countDown();
      }
    }

    private CountDownLatch started() {
      return started;
    }

    private CountDownLatch returned() {
      return returned;
    }

    private void release() {
      release.countDown();
    }
  }

  /** A source that hands out one fake reader and nothing else. */
  private record FakeSource(EntityReader reader, Supplier<EntityReader> opener)
      implements SessionSource {

    private FakeSource(EntityReader reader) {
      this(reader, () -> reader);
    }

    @Override
    public SessionInfo info() {
      return new SessionInfo(
          Path.of("."), "fake", SessionState.READY, OptionalLong.empty(), List.of(), List.of());
    }

    @Override
    public SessionReader openReader() {
      throw new UnsupportedOperationException("the overview does not read raw events");
    }

    @Override
    public GraphQueries openGraphQueries() {
      throw new UnsupportedOperationException("the overview does not read the graph");
    }

    @Override
    public MetricQueries openMetricQueries() {
      throw new UnsupportedOperationException("the overview's own read collects no metrics");
    }

    @Override
    public Connection openTimelineConnection() {
      throw new UnsupportedOperationException("the overview draws no timeline");
    }

    @Override
    public QueryReader openQueryReader() {
      throw new UnsupportedOperationException("the overview runs no ad hoc SQL");
    }

    @Override
    public EntityReader openEntityReader() {
      return opener.get();
    }

    @Override
    public void close() {
      reader.close();
    }
  }
}

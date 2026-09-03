package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * t6: "The events tab should populate live with a build as events are coming in from the server."
 * Before this, {@link EventsView#openSession} was only ever reached once from {@code MainWindow} --
 * after {@code captureFinished} -- so the table showed nothing at all while a build was running.
 *
 * <p>These tests sit one layer below that wiring: given a session that grows while the view is
 * already open (a {@link FakeSessionReader} grown from the test thread, standing in for a live
 * capture's connection watching rows a writer is still committing), does the table pick up the
 * growth on its own, and does it do so without discarding the row the user is looking at or the
 * place they scrolled to?
 */
final class EventsViewLiveRefreshTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  private EventsView events;

  @AfterEach
  void tearDown() throws Exception {
    if (events != null) {
      onEdt(
          () -> {
            events.closeSession();
            return null;
          });
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("the view's own timer picks up growth with no external call to refreshLive")
  void ownTickerRefreshesALiveSession() throws Exception {
    FakeSessionReader reader = FakeSessionReader.dense(50);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    // A short interval so several ticks fit in a fraction of a second,
    // exactly as TimelineControllerTest drives TimelineController's own
    // interval faster than its real two seconds for the same reason.
    long tickIntervalMicros = 40_000; // 40 ms
    events = onEdt(() -> new EventsView(tickIntervalMicros));

    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });

    await(
        () ->
            onEdt(() -> events.tableModelForTest() != null)
                && onEdt(() -> events.tableModelForTest().getRowCount()) == 50);
    assertThat(failures).isEmpty();

    // Grow the store the way a live capture does: nothing from here on
    // calls refreshLive() or otherwise pokes the view. If the table only
    // ever grows when told to, this is where the test would time out.
    for (int id = 51; id <= 80; id++) {
      reader.add(id, id - 1, DecodeStatus.OK);
    }

    await(() -> onEdt(() -> events.tableModelForTest().getRowCount()) == 80);
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("follow tail is checked by default")
  void followTailIsOnByDefault() throws Exception {
    events = onEdt(EventsView::new);
    assertThat(onEdt(events::followingForTest))
        .as("follow tail starts checked, with no session even open")
        .isTrue();
  }

  @Test
  @Timeout(30)
  @DisplayName("a live refresh preserves the selected row and the scroll position")
  void refreshPreservesSelectionAndScroll() throws Exception {
    FakeSessionReader reader = FakeSessionReader.dense(400);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    // Same short interval as the ticker test above; this test lets the
    // view's own timer do the refresh rather than calling refreshLive()
    // itself, so the swap it observes is exactly the one a live capture
    // would trigger.
    long tickIntervalMicros = 40_000; // 40 ms
    events = onEdt(() -> new EventsView(tickIntervalMicros));

    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));
    assertThat(failures).isEmpty();

    // Select a row deep in the table and set a scroll position, so that
    // install()'s row-0 default, or any reset of the viewport, would show
    // up as a failure below. This scroll is also a move away from the
    // tail, which t8's auto-pause must catch on its own -- nothing here
    // touches the follow-tail checkbox directly.
    onEdt(
        () -> {
          events.tableForTest().setRowSelectionInterval(120, 120);
          events.scrollForTest().getViewport().setViewPosition(new Point(0, 4_321));
          return null;
        });

    assertThat(onEdt(events::followingForTest))
        .as(
            "scrolling away from the tail auto-pauses follow, so the refresh below"
                + " must not jump the view back to the bottom")
        .isFalse();

    reader.add(401, 400, DecodeStatus.OK);

    await(() -> onEdt(() -> events.tableModelForTest().getRowCount()) == 401);

    assertThat(onEdt(() -> events.tableForTest().getSelectedRow()))
        .as("the selected row survives the model swap")
        .isEqualTo(120);
    assertThat(onEdt(() -> events.scrollForTest().getViewport().getViewPosition()))
        .as("the scroll position survives the model swap while follow stays paused")
        .isEqualTo(new Point(0, 4_321));
    assertThat(onEdt(events::followingForTest))
        .as("the swap itself must not re-enable follow tail")
        .isFalse();
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("a live refresh preserves a column width the user resized")
  void refreshPreservesAResizedColumnWidth() throws Exception {
    // A regression test for a review finding on this same task: EventsView
    // never disables autoCreateColumnsFromModel, so JTable.setModel throws
    // away the TableColumnModel (and every width in it) on every call --
    // including the one a live refresh makes every couple of seconds.
    // Before swapRows() read the widths back and reapplied them, a column
    // the user had resized to read a long value would silently snap back
    // to sizeColumns()'s hardcoded default on the very next tick.
    FakeSessionReader reader = FakeSessionReader.dense(300);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    long tickIntervalMicros = 40_000; // 40 ms, same as the tests above
    events = onEdt(() -> new EventsView(tickIntervalMicros));

    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));
    assertThat(failures).isEmpty();

    // sizeColumns()'s hardcoded default for column 4 ("Event id") is 320;
    // resize it the way a user's mouse drag would, to a value nothing in
    // this view would ever pick on its own.
    int resizedWidth = 555;
    onEdt(
        () -> {
          TableColumn column = events.tableForTest().getColumnModel().getColumn(4);
          column.setPreferredWidth(resizedWidth);
          column.setWidth(resizedWidth);
          return null;
        });

    reader.add(301, 300, DecodeStatus.OK);

    await(() -> onEdt(() -> events.tableModelForTest().getRowCount()) == 301);

    assertThat(onEdt(() -> events.tableForTest().getColumnModel().getColumn(4).getPreferredWidth()))
        .as("the resized preferred width survives the live model swap")
        .isEqualTo(resizedWidth);
    assertThat(onEdt(() -> events.tableForTest().getColumnModel().getColumn(4).getWidth()))
        .as("the resized actual width survives the live model swap")
        .isEqualTo(resizedWidth);
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("a live refresh scrolls to the tail while follow is on")
  void refreshFollowsTheTailWhileFollowing() throws Exception {
    FakeSessionReader reader = FakeSessionReader.dense(200);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    long tickIntervalMicros = 40_000; // 40 ms, same as the tests above
    events = onEdt(() -> new EventsView(tickIntervalMicros));

    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));
    assertThat(failures).isEmpty();
    assertThat(onEdt(events::followingForTest)).as("follow tail is on by default").isTrue();

    // Nothing here touches the checkbox or the viewport by hand -- the
    // ticker's own refresh, seeing follow still checked, must do the
    // scrolling on its own.
    reader.add(201, 200, DecodeStatus.OK);

    await(() -> onEdt(() -> events.tableModelForTest().getRowCount()) == 201);

    assertThat(onEdt(events::followingForTest))
        .as("a refresh made while following must not itself pause")
        .isTrue();
    assertThat(onEdt(() -> isScrolledToBottom(events)))
        .as("the viewport tracks the new last row")
        .isTrue();
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("scrolling away from the bottom pauses follow tail")
  void scrollingAwayFromBottomPausesFollow() throws Exception {
    FakeSessionReader reader = FakeSessionReader.dense(400);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    events = onEdt(EventsView::new);
    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));
    assertThat(onEdt(events::followingForTest)).isTrue();

    // A user's scroll and this harness's programmatic setViewPosition
    // calls above (in refreshPreservesSelectionAndScroll) reach the same
    // real JViewport -- there is no separate "user scroll" API to call.
    // A nonzero y is required: the viewport starts at (0, 0), and setting
    // that same point again is a no-op the viewport does not fire an
    // event for.
    onEdt(
        () -> {
          events.scrollForTest().getViewport().setViewPosition(new Point(0, 50));
          return null;
        });

    assertThat(onEdt(events::followingForTest))
        .as("scrolling away from the bottom auto-pauses follow tail")
        .isFalse();
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("scrolling back to the bottom resumes follow tail")
  void scrollingBackToBottomResumesFollow() throws Exception {
    FakeSessionReader reader = FakeSessionReader.dense(400);
    FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
    List<String> failures = new ArrayList<>();

    events = onEdt(EventsView::new);
    onEdt(
        () -> {
          events.openSession(source, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));

    // See scrollingAwayFromBottomPausesFollow: a nonzero y, since the
    // viewport starts at (0, 0) and re-setting that exact point fires no
    // event.
    onEdt(
        () -> {
          events.scrollForTest().getViewport().setViewPosition(new Point(0, 50));
          return null;
        });
    assertThat(onEdt(events::followingForTest))
        .as("scrolled away from the bottom, follow tail is paused first")
        .isFalse();

    int contentHeight = onEdt(() -> events.tableForTest().getPreferredSize().height);
    onEdt(
        () -> {
          events.scrollForTest().getViewport().setViewPosition(new Point(0, contentHeight));
          return null;
        });

    assertThat(onEdt(events::followingForTest))
        .as("scrolling back to the bottom resumes follow tail")
        .isTrue();
    assertThat(failures).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("rule 11: a live session's count is stated as a lower bound, a finished one is not")
  void liveCountIsStatedAsALowerBound() throws Exception {
    FakeSessionReader liveReader = FakeSessionReader.dense(10);
    FakeSource live = new FakeSource(liveReader, SessionState.CAPTURING);
    events = onEdt(EventsView::new);
    List<String> failures = new ArrayList<>();

    onEdt(
        () -> {
          events.openSession(live, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));

    assertThat(onEdt(events::statusTextForTest))
        .as("a growing count must say it is one, not read as a final total (rule 11)")
        .contains("at least")
        .contains("10 events");

    FakeSessionReader doneReader = FakeSessionReader.dense(10);
    FakeSource done = new FakeSource(doneReader, SessionState.READY);
    onEdt(
        () -> {
          events.openSession(done, failures::add);
          return null;
        });
    await(() -> onEdt(() -> events.tableModelForTest() != null));

    assertThat(onEdt(events::statusTextForTest))
        .as("a finished session's count is not hedged")
        .doesNotContain("at least")
        .contains("10 events");
    assertThat(failures).isEmpty();
  }

  /**
   * Mirrors {@code EventsView.isScrolledToBottom}'s formula exactly, from the public geometry a
   * test can reach: the viewport's visible rectangle against the table's real preferred height
   * (rows × row height, computed without any layout pass), with the same row-or-two tolerance.
   */
  private static boolean isScrolledToBottom(EventsView events) {
    Rectangle visible = events.scrollForTest().getViewport().getViewRect();
    int contentHeight = events.tableForTest().getPreferredSize().height;
    int tolerance = Math.max(1, events.tableForTest().getRowHeight()) * 2;
    return visible.y + visible.height >= contentHeight - tolerance;
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(condition.call())) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("condition never became true within the deadline");
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

  /**
   * A {@link SessionSource} whose {@link #openReader()} always hands back the same {@link
   * FakeSessionReader}, so that appending to it (as the test does) is visible to whichever of the
   * view's two readers -- page or detail -- asks next, exactly as a live capture's growth is
   * visible to every connection opened against the same SQLite file.
   */
  private record FakeSource(FakeSessionReader reader, SessionState state) implements SessionSource {

    @Override
    public SessionInfo info() {
      return new SessionInfo(
          Path.of("."), "fake-live", state, OptionalLong.empty(), List.of(), List.of());
    }

    @Override
    public SessionReader openReader() {
      return reader;
    }

    @Override
    public EntityReader openEntityReader() {
      throw new UnsupportedOperationException("the events view reads no entities");
    }

    @Override
    public GraphQueries openGraphQueries() {
      throw new UnsupportedOperationException("the events view reads no graph");
    }

    @Override
    public MetricQueries openMetricQueries() {
      throw new UnsupportedOperationException("the events view collects no metrics");
    }

    @Override
    public QueryReader openQueryReader() {
      throw new UnsupportedOperationException("the events view runs no ad hoc SQL");
    }

    @Override
    public Connection openTimelineConnection() {
      throw new UnsupportedOperationException("the events view is not the timeline");
    }

    @Override
    public void close() {
      // The reader is shared and outlives any one FakeSource in these
      // tests; nothing here owns a connection to release.
    }
  }
}

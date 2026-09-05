package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Swing wiring, end to end and without a display: open a real imported session in the view, let
 * the first page arrive, and check that selecting a row reached the inspector with that row's
 * bytes.
 *
 * <p>This is the chain the unit tests each cover one link of — row source, page cache, selection
 * resolution, inspector fetch — and that nothing else exercises together. It runs headless because
 * none of these components realizes a peer; the assertion at the top makes that a stated
 * precondition rather than a coincidence.
 */
class EventsViewWiringTest {

  private static final int EVENT_COUNT = 300;

  @Test
  @Timeout(180)
  void filteringReachesRowsBeyondTheFirstPageAndClearRestoresTheWholeSession(
      @TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    try (SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot())) {
      List<Long> totals = new ArrayList<>();
      EventsView events =
          onEdt(
              () -> {
                EventsView view = new EventsView();
                view.setRowCountListener(totals::add);
                view.openSession(
                    opened,
                    failure -> {
                      throw new AssertionError(failure);
                    });
                return view;
              });
      try {
        await(() -> onEdt(() -> events.tableModelForTest() != null));
        SwingUtilities.invokeAndWait(
            () ->
                events
                    .filtersForTest()
                    .setExpression(
                        new Group(
                            Junction.ALL,
                            List.of(new Condition("id", Operator.GREATER_THAN, List.of("280"))))));
        await(
            () ->
                onEdt(
                    () ->
                        events.tableModelForTest() != null
                            && events.tableModelForTest().getRowCount() == 20));
        assertThat(onEdt(events::statusTextForTest)).contains("20 matching of 300 events");
        await(
            () ->
                onEdt(
                    () ->
                        events.tableModelForTest().getValueAt(0, EventTableColumns.ID_COLUMN)
                            instanceof Long));
        assertThat(
                onEdt(() -> events.tableModelForTest().getValueAt(0, EventTableColumns.ID_COLUMN)))
            .isEqualTo(281L);
        SwingUtilities.invokeAndWait(() -> events.tableForTest().setRowSelectionInterval(0, 0));
        await(
            () ->
                onEdt(
                    () ->
                        events
                            .inspectorModelForTest()
                            .current()
                            .row()
                            .map(row -> row.id() == 281)
                            .orElse(false)));
        // Rapid changes: the first result may be queued, but must never replace the latest one.
        SwingUtilities.invokeAndWait(
            () -> {
              events
                  .filtersForTest()
                  .setExpression(
                      new Group(
                          Junction.ALL,
                          List.of(new Condition("id", Operator.LESS_THAN, List.of("100")))));
              events
                  .filtersForTest()
                  .setExpression(
                      new Group(
                          Junction.ALL,
                          List.of(new Condition("id", Operator.GREATER_THAN, List.of("999")))));
            });
        await(() -> onEdt(() -> events.statusTextForTest().contains("no events match")));
        assertThat(onEdt(() -> events.tableModelForTest().getRowCount())).isZero();
        assertThat(onEdt(() -> events.revealEvent(1))).isTrue();
        await(
            () ->
                onEdt(
                    () ->
                        events
                            .inspectorModelForTest()
                            .current()
                            .row()
                            .map(row -> row.id() == 1)
                            .orElse(false)));
        SwingUtilities.invokeAndWait(
            () -> events.filtersForTest().setExpression(FilterExpression.ALL));
        await(
            () ->
                onEdt(
                    () ->
                        events.tableModelForTest() != null
                            && events.tableModelForTest().getRowCount() == EVENT_COUNT));
        assertThat(onEdt(() -> List.copyOf(totals))).containsOnly(300L);
      } finally {
        onEdt(events::closeAsync).toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    }
  }

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display; the build sets" + " java.awt.headless=true")
        .isTrue();
  }

  @Test
  @Timeout(180)
  @DisplayName("opening a session fills the table and the first selection reaches the inspector")
  void openingASessionWiresTableAndInspector(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);

    SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot());
    List<String> failures = new ArrayList<>();
    List<Long> reportedCounts = new ArrayList<>();
    AtomicReference<EventsView> view = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          EventsView events = new EventsView();
          events.setRowCountListener(reportedCounts::add);
          events.openSession(opened, failures::add);
          view.set(events);
        });
    EventsView events = view.get();

    await(() -> onEdt(() -> events.tableModelForTest() != null));
    assertThat(failures).isEmpty();
    assertThat(reportedCounts).containsExactly((long) EVENT_COUNT);

    PagedTableModel<EventRow> model = onEdt(events::tableModelForTest);
    assertThat(model.getRowCount()).isEqualTo(EVENT_COUNT);
    assertThat(onEdt(events::followingForTest))
        .as("t8: follow tail is checked by default")
        .isTrue();

    // The first page arrives asynchronously; until it does the cells are
    // the loading placeholder, which is the correct thing for them to be.
    await(() -> onEdt(() -> model.getValueAt(0, EventTableColumns.ID_COLUMN) instanceof Long));

    assertThat(onEdt(() -> model.getValueAt(0, 1))).isEqualTo(0L);
    assertThat(onEdt(() -> model.getValueAt(0, 2)))
        .as("the payload case of the first event of a real BEP stream")
        .isEqualTo("started");
    assertThat(onEdt(() -> model.getValueAt(0, 3))).isEqualTo("ok");
    assertThat(onEdt(() -> model.getValueAt(0, 7)))
        .as("a timestamped event never renders as an em dash")
        .isNotEqualTo(EventValueFormat.UNKNOWN);

    // install() selects row 0, so the inspector must end up showing it —
    // through the pending-selection retry, because the page was not loaded
    // at the moment the selection was made.
    EventInspectorModel inspector = onEdt(events::inspectorModelForTest);
    await(() -> inspector.current().state() == EventInspection.State.LOADED);

    EventInspection inspection = inspector.current();
    assertThat(inspection.row().orElseThrow().sequence()).isZero();
    assertThat(inspection.payload().orElseThrow().bytes())
        .isEqualTo(stream.eventAt(0).toByteArray());
    assertThat(inspector.payloadFetchCount()).as("one selection, one payload read").isEqualTo(1);

    SwingUtilities.invokeAndWait(events::closeSession);
    assertThat(onEdt(() -> events.openSession() == null)).isTrue();
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  /** Reads a value from the EDT, which is the only thread allowed to touch these models. */
  private static <T> T onEdt(Callable<T> read) {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              value.set(read.call());
            } catch (Exception e) {
              failure.set(e);
            }
          });
    } catch (Exception e) {
      throw new AssertionError("EDT read failed", e);
    }
    if (failure.get() != null) {
      throw new AssertionError("EDT read failed", failure.get());
    }
    return value.get();
  }

  private static boolean onEdt(BooleanSupplier read) {
    return onEdt((Callable<Boolean>) read::getAsBoolean);
  }
}

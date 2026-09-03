package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.ColumnState;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Actions tab's one sort state, worn by two faces: the toolbar combo and the table headers. A
 * header click must move the combo and reload through the backend ordering; a combo change must
 * move the header indicator; and the cycle's third click must land on the default — arrival
 * ascending — because that is the ordering the view opens with.
 *
 * <p>No client-side {@code RowSorter} is ever involved: every transition below is observed as a new
 * {@link ActionRowSource} built with the chosen {@link ActionSort}, which is the honest way to sort
 * five million rows.
 */
final class ActionsViewHeaderSortTest {

  private static final int EVENT_COUNT = 120;

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  @Test
  @Timeout(180)
  @DisplayName(
      "header clicks drive the backend sort and the toolbar follows; the combo drives the header"
          + " back")
  void headerAndComboAreOneState(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    AtomicReference<ActionsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          ActionsView view = new ActionsView();
          view.openSession(opened);
          held.set(view);
        });
    ActionsView view = held.get();
    await(() -> onEdt(() -> view.tableModelForTest() != null));

    assertThat(onEdt(() -> view.rowSourceForTest().sort()))
        .as("the view opens in arrival order")
        .isEqualTo(ActionSort.ARRIVAL);

    // Click 1 on Duration: ascending, through the backend.
    onEdt(
        () -> {
          view.headerInteractionsForTest().clickForTest("Duration");
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.rowSourceForTest().sort() == ActionSort.DURATION
                        && !view.rowSourceForTest().descending()));
    assertThat(onEdt(() -> actionsTable(view).getRowSorter()))
        .as("never a client RowSorter on the paged model")
        .isNull();

    // Click 2: descending — and the reload it triggers is a new source.
    onEdt(
        () -> {
          view.headerInteractionsForTest().clickForTest("Duration");
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.rowSourceForTest().sort() == ActionSort.DURATION
                        && view.rowSourceForTest().descending()));

    // Click 3: the default — arrival ascending, indicator gone.
    onEdt(
        () -> {
          view.headerInteractionsForTest().clickForTest("Duration");
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.rowSourceForTest().sort() == ActionSort.ARRIVAL
                        && !view.rowSourceForTest().descending()));
    assertThat(onEdt(() -> view.headerInteractionsForTest().currentSort())).isEmpty();

    // The other direction: the toolbar's combo moves the header state.
    onEdt(
        () -> {
          view.applyForTest(null, ActionSort.MNEMONIC, true);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.rowSourceForTest().sort() == ActionSort.MNEMONIC
                        && view.rowSourceForTest().descending()));
    assertThat(onEdt(() -> view.headerInteractionsForTest().currentSort()))
        .contains(new ColumnState.Sort("Mnemonic", true));

    // A toolbar ordering with no column of its own shows no indicator
    // but is still the recorded sort choice.
    onEdt(
        () -> {
          view.applyForTest(null, ActionSort.START_TIME, false);
          return null;
        });
    await(() -> onEdt(() -> view.rowSourceForTest().sort() == ActionSort.START_TIME));
    assertThat(onEdt(() -> view.headerInteractionsForTest().currentSort()))
        .contains(new ColumnState.Sort("START_TIME", false));

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  @Test
  @Timeout(180)
  @DisplayName("columns without a backend ordering do not sort, and their tooltip says why")
  void unsortableColumnsAreHonest(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    AtomicReference<ActionsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          ActionsView view = new ActionsView();
          view.openSession(opened);
          held.set(view);
        });
    ActionsView view = held.get();
    await(() -> onEdt(() -> view.tableModelForTest() != null));

    onEdt(
        () -> {
          for (String sortable : new String[] {"Target", "Mnemonic", "Outcome", "Duration"}) {
            assertThat(view.headerInteractionsForTest().isSortableForTest(sortable))
                .as("%s has a backend ordering", sortable)
                .isTrue();
          }
          for (String fixed : new String[] {"Exit", "Runner", "Cached", "Output"}) {
            assertThat(view.headerInteractionsForTest().isSortableForTest(fixed))
                .as("%s has no backend ordering", fixed)
                .isFalse();
            assertThat(view.headerInteractionsForTest().explanationForTest(fixed))
                .contains("no index orders actions by " + fixed);
          }
          return null;
        });

    // A click on an unsortable header changes nothing.
    ActionSort before = onEdt(() -> view.rowSourceForTest().sort());
    onEdt(
        () -> {
          view.headerInteractionsForTest().clickForTest("Runner");
          return null;
        });
    assertThat(onEdt(() -> view.rowSourceForTest().sort())).isEqualTo(before);

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  /** The card's table, found by its installed model. EDT only. */
  private static JTable actionsTable(ActionsView view) {
    ArrayDeque<Container> queue = new ArrayDeque<>();
    queue.add(view);
    while (!queue.isEmpty()) {
      Container current = queue.poll();
      for (Component child : current.getComponents()) {
        if (child instanceof JTable table && table.getModel() == view.tableModelForTest()) {
          return table;
        }
        if (child instanceof Container container) {
          queue.add(container);
        }
      }
    }
    throw new AssertionError("the actions table was not found");
  }

  private static SqliteSessionSource openImportedSession(Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    return SqliteSessionSource.open(sessions, imported.sessionRoot());
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

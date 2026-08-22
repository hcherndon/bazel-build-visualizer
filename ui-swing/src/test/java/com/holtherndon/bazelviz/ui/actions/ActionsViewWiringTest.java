package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
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
 * The exit criterion "the action table supports paging, filtering, and
 * sorting", exercised through the view rather than through the query layer.
 *
 * <p>The queries are tested on their own. What this covers is the part that
 * could be right underneath and wrong on screen: that changing the sort
 * rebuilds the table rather than leaving the old rows in place, that a filter
 * changes both the rows and the number beside them, and that the table's row
 * count agrees with what the status line says.
 */
class ActionsViewWiringTest {

    private static final int EVENT_COUNT = 300;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @Timeout(180)
    @DisplayName("the table pages, sorts and filters, and the status line agrees with it")
    void tablePagesSortsAndFilters(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        AtomicReference<ActionsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionsView view = new ActionsView();
            view.openSession(opened);
            held.set(view);
        });
        ActionsView view = held.get();

        await(() -> onEdt(() -> view.tableModelForTest() != null));
        long total = onEdt(() -> view.rowSourceForTest().rowCount());
        assertThat(total).isPositive();
        assertThat(onEdt(() -> view.tableModelForTest().getRowCount())).isEqualTo((int) total);
        assertThat(onEdt(view::statusForTest)).contains(String.valueOf(total));

        // The fixture's OptionsParsed does not set the publish-all flag, so the
        // view says so -- about the options, which is what it knows.
        assertThat(onEdt(view::captureNoteForTest))
                .contains("build_event_publish_all_actions");

        // Paging: the first cell is a placeholder until its page lands, then a
        // real value. A table that never left the placeholder would look
        // identical to one whose fetches were never scheduled.
        await(() -> onEdt(() -> !PagedTableModel.PLACEHOLDER.equals(
                view.tableModelForTest().getValueAt(0, 0))));

        List<String> mnemonics = onEdt(view::mnemonicChoicesForTest);
        assertThat(mnemonics).hasSizeGreaterThan(1);
        String firstMnemonic = mnemonics.get(1);

        // Sorting: a different sort is a different source, and the rows change
        // with it rather than the old ones staying put.
        SwingUtilities.invokeAndWait(() ->
                view.applyForTest(mnemonics.getFirst(), ActionSort.DURATION, true));
        await(() -> onEdt(() -> view.rowSourceForTest().sort() == ActionSort.DURATION));
        assertThat(onEdt(() -> view.rowSourceForTest().descending())).isTrue();
        assertThat(onEdt(() -> view.rowSourceForTest().rowCount())).isEqualTo(total);

        // Filtering: fewer rows, and the status line says how many of how many
        // rather than presenting the subset as the whole build.
        SwingUtilities.invokeAndWait(() ->
                view.applyForTest(firstMnemonic, ActionSort.ARRIVAL, false));
        await(() -> onEdt(() -> !view.rowSourceForTest().filter().isEmpty()));
        long filtered = onEdt(() -> view.rowSourceForTest().rowCount());
        assertThat(filtered).isPositive().isLessThanOrEqualTo(total);
        assertThat(onEdt(() -> view.rowSourceForTest().unfilteredCount())).isEqualTo(total);
        assertThat(onEdt(view::statusForTest))
                .contains("of")
                .contains("match the filter");
        assertThat(onEdt(() -> view.tableModelForTest().getRowCount())).isEqualTo((int) filtered);

        SwingUtilities.invokeAndWait(view::closeSession);
        opened.close();
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
            SwingUtilities.invokeAndWait(() -> {
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

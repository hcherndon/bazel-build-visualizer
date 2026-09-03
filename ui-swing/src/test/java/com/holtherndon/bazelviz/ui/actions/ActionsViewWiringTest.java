package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        // real value. Both placeholders are excluded, not just the loading one
        // -- an earlier version of this assertion accepted the error
        // placeholder and so passed while every page fetch was failing.
        await(() -> onEdt(() -> isLoaded(view.tableModelForTest().getValueAt(0, 0))));
        assertThat(onEdt(() -> view.tableModelForTest().failedFetchCount()))
                .as("no page fetch failed")
                .isZero();

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

        // The filtered table's pages come through the same reader the reload
        // built its index with, so they must still be readable.
        await(() -> onEdt(() -> isLoaded(view.tableModelForTest().getValueAt(0, 0))));
        assertThat(onEdt(() -> view.tableModelForTest().failedFetchCount()))
                .as("no page fetch failed after a filter change")
                .isZero();

        SwingUtilities.invokeAndWait(view::closeSession);
        opened.close();
    }

    @Test
    @Timeout(180)
    @DisplayName("Reveal Action clears excluding filters, loads the page, and selects the row")
    void revealActionSelectsAnUnloadedFilteredOutRow(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        List<ActionRow> targets;
        try (EntityReader reader = opened.openEntityReader()) {
            targets = reader.firstActionPage(
                    ActionFilter.NONE, ActionSort.ARRIVAL, false, 2);
        }
        assertThat(targets).hasSize(2);
        ActionRow target = targets.getFirst();
        ActionRow second = targets.getLast();

        AtomicReference<ActionsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionsView view = new ActionsView();
            held.set(view);
            view.openSession(opened);
        });
        ActionsView view = held.get();
        try {
            await(() -> onEdt(() -> view.tableModelForTest() != null));

            // Start from a model that cannot contain the target. Its zero rows
            // also guarantee no target page was accidentally loaded by paint.
            SwingUtilities.invokeAndWait(() ->
                    view.filterToLabel("//__bbv_missing_package__:__bbv_missing_target__"));
            await(() -> onEdt(() -> view.rowSourceForTest() != null
                    && view.rowSourceForTest().filter().labelContains().isPresent()
                    && view.rowSourceForTest().rowCount() == 0));

            SwingUtilities.invokeAndWait(() -> view.revealAction(target.id()));
            await(() -> onEdt(() ->
                    view.selectedActionIdForTest().orElse(-1L) == target.id()));

            assertThat(onEdt(() -> view.rowSourceForTest().filter().isEmpty())).isTrue();
            assertThat(onEdt(view::labelChipForTest)).isNull();
            assertThat(onEdt(() -> view.tableModelForTest().failedFetchCount())).isZero();

            // The usual unfiltered path reuses the existing source and page.
            // It must still restore the table count after its temporary
            // "Locating action…" status.
            SwingUtilities.invokeAndWait(() -> view.revealAction(second.id()));
            await(() -> onEdt(() ->
                    view.selectedActionIdForTest().orElse(-1L) == second.id()));
            assertThat(onEdt(view::statusForTest))
                    .contains("action")
                    .doesNotContain("Locating");

            long allActions = onEdt(() -> view.rowSourceForTest().rowCount());
            AtomicBoolean externalRangeCleared = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> {
                view.onClearExternalRange(() -> externalRangeCleared.set(true));
                view.filterToRange(
                        java.util.OptionalLong.of(0),
                        java.util.OptionalLong.of(Long.MAX_VALUE));
                view.filterToLabel("//__bbv_show_all_filter__:target");
            });
            await(() -> onEdt(() -> view.rowSourceForTest().filter().hasRange()
                    && view.rowSourceForTest().filter().labelContains().isPresent()));

            assertThat(onEdt(() -> view.showAllButtonForTest().isEnabled())).isTrue();
            SwingUtilities.invokeAndWait(() -> view.showAllButtonForTest().doClick());
            await(() -> onEdt(() -> view.rowSourceForTest().filter().isEmpty()
                    && view.rowSourceForTest().rowCount() == allActions));
            await(() -> onEdt(() -> view.tableModelForTest().isPageLoaded(0)));
            assertThat(externalRangeCleared).isTrue();
            assertThat(onEdt(view::selectedActionIdForTest)).isEmpty();
            assertThat(onEdt(view::labelChipForTest)).isNull();
            assertThat(onEdt(view::statusForTest)).doesNotContain("match the filter");

            // Show all is also newer intent than an exact-row lookup already
            // accepted by the detail lane; the late lookup must not reselect.
            AtomicReference<CompletionStage<Void>> resetBarrier = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                view.revealAction(target.id());
                view.showAllButtonForTest().doClick();
                resetBarrier.set(view.detailBarrierForTest());
            });
            resetBarrier.get().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertThat(onEdt(view::selectedActionIdForTest)).isEmpty();
            assertThat(onEdt(() -> view.rowSourceForTest().filter().isEmpty())).isTrue();

            // A user filter entered after a reveal request is newer intent.
            // Drain the detail lane so the stale lookup and its EDT callback
            // have both had a chance to run before asserting it did not win.
            AtomicReference<CompletionStage<Void>> detailBarrier = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                view.revealAction(target.id());
                view.filterToLabel("//__bbv_newer_filter__:target");
                detailBarrier.set(view.detailBarrierForTest());
            });
            detailBarrier.get().toCompletableFuture().get(60, TimeUnit.SECONDS);
            await(() -> onEdt(() -> view.rowSourceForTest() != null
                    && view.rowSourceForTest().filter().labelContains().isPresent()
                    && view.rowSourceForTest().rowCount() == 0));
            assertThat(onEdt(view::labelChipForTest)).contains("__bbv_newer_filter__");
            assertThat(onEdt(view::selectedActionIdForTest)).isEmpty();
        } finally {
            SwingUtilities.invokeAndWait(view::closeSession);
            opened.close();
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("the action anchor index locates every row's keyset page")
    void actionAnchorIndexLocatesEveryPage(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        try (EntityReader reader = opened.openEntityReader()) {
            int pageSize = 3;
            for (ActionSort sort : ActionSort.values()) {
                for (boolean descending : List.of(false, true)) {
                    ActionRowSource rows = ActionRowSource.open(
                            reader, ActionFilter.NONE, sort, descending, pageSize);
                    long pageCount = (rows.rowCount() + pageSize - 1) / pageSize;
                    for (long page = 0; page < pageCount; page++) {
                        for (ActionRow row : rows.fetchPage(page, pageSize).rows()) {
                            assertThat(rows.pageIndexOf(row))
                                    .as("%s descending=%s action=%s", sort, descending, row.id())
                                    .isEqualTo(page);
                        }
                    }
                }
            }
        } finally {
            opened.close();
        }
    }

    @Test
    @DisplayName("text page boundaries use SQLite BINARY order for supplementary Unicode")
    void textPageBoundariesUseSqliteBinaryOrder() {
        // UTF-16 String.compareTo puts the surrogate pair first. SQLite's
        // BINARY collation compares UTF-8 bytes and puts the BMP value first.
        assertThat(ActionRowSource.compareSqliteText("\uE000", "\uD800\uDC00"))
                .isNegative();
    }

    /** True once a cell holds a value rather than either placeholder. */
    private static boolean isLoaded(Object value) {
        return !PagedTableModel.PLACEHOLDER.equals(value)
                && !PagedTableModel.ERROR_PLACEHOLDER.equals(value);
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

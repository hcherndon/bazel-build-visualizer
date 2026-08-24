package com.holtherndon.bazelviz.ui.table;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared header machinery, against a plain table: the 3-state sort
 * cycle, the visibility menu's hidden-is-not-dropped contract, width and
 * order surviving a {@code setModel} swap, and the persisted state coming
 * back in a fresh "application run".
 */
final class TableHeaderInteractionsTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    /** Records what the view would have been asked to do. */
    private static final class RecordingAdapter implements TableHeaderInteractions.Adapter {

        final List<String> applied = new ArrayList<>();
        final Set<String> sortable;

        RecordingAdapter(String... sortableColumns) {
            this.sortable = Set.of(sortableColumns);
        }

        @Override
        public boolean isSortable(String columnId) {
            return sortable.contains(columnId);
        }

        @Override
        public void applySort(Optional<String> sortKey, boolean descending) {
            applied.add(sortKey.orElse("<default>") + (descending ? " desc" : " asc"));
        }

        @Override
        public String sortUnavailableExplanation(String columnId) {
            return "No ordering stands behind " + columnId + ".";
        }
    }

    private static DefaultTableModel model() {
        return new DefaultTableModel(new Object[] {"A", "B", "C"}, 0);
    }

    @Test
    @DisplayName("a header click cycles ascending, descending, default — and stops applying nothing")
    void threeStateCycle() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            RecordingAdapter adapter = new RecordingAdapter("A");
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, adapter);

            interactions.clickForTest("A");
            interactions.clickForTest("A");
            interactions.clickForTest("A");
            interactions.clickForTest("A");

            assertThat(adapter.applied).containsExactly(
                    "A asc", "A desc", "<default> asc", "A asc");
            return null;
        });
    }

    @Test
    @DisplayName("clicking one sorted column then another starts the second ascending")
    void switchingColumnsRestartsTheCycle() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            RecordingAdapter adapter = new RecordingAdapter("A", "B");
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, adapter);

            interactions.clickForTest("A");
            interactions.clickForTest("B");

            assertThat(adapter.applied).containsExactly("A asc", "B asc");
            assertThat(interactions.currentSort())
                    .contains(new ColumnState.Sort("B", false));
            return null;
        });
    }

    @Test
    @DisplayName("a click on a column with no ordering applies nothing")
    void unsortableColumnsIgnoreClicks() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            RecordingAdapter adapter = new RecordingAdapter("A");
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, adapter);

            interactions.clickForTest("B");

            assertThat(adapter.applied).isEmpty();
            assertThat(interactions.currentSort()).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("a sort adopted from the view moves the indicator without calling the view back")
    void viewDrivenSortDoesNotLoop() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            RecordingAdapter adapter = new RecordingAdapter("A");
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, adapter);

            interactions.setSortFromView(Optional.of("A"), true);

            assertThat(adapter.applied)
                    .as("the view already applied this sort itself")
                    .isEmpty();
            assertThat(interactions.currentSort())
                    .contains(new ColumnState.Sort("A", true));

            // The next click continues the cycle from the adopted state:
            // A descending's successor is the default order.
            interactions.clickForTest("A");
            assertThat(adapter.applied).containsExactly("<default> asc");
            return null;
        });
    }

    @Test
    @DisplayName("hiding a column is presentation only, and its width is remembered when it returns")
    void hiddenIsNotDroppedAndWidthIsRemembered() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, new RecordingAdapter());

            table.getColumnModel().getColumn(1).setPreferredWidth(222);
            interactions.setColumnVisible("B", false);

            assertThat(interactions.visibleColumnIds()).containsExactly("A", "C");
            assertThat(table.getModel().getColumnCount())
                    .as("hidden is not dropped: the model keeps all its columns")
                    .isEqualTo(3);

            interactions.setColumnVisible("B", true);
            assertThat(interactions.visibleColumnIds()).containsExactly("A", "B", "C");
            assertThat(table.getColumnModel().getColumn(1).getPreferredWidth())
                    .as("the hidden column kept its width")
                    .isEqualTo(222);
            return null;
        });
    }

    @Test
    @DisplayName("widths, order and hidden columns survive a setModel swap")
    void stateSurvivesAModelSwap() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, new RecordingAdapter());

            table.getColumnModel().getColumn(0).setPreferredWidth(333);
            table.getColumnModel().moveColumn(0, 2); // A,B,C -> B,C,A
            interactions.setColumnVisible("B", false); // -> C,A

            // The swap a sort change or live refresh makes: a brand-new
            // model, every column rebuilt at its default.
            table.setModel(model());
            interactions.modelInstalled();

            assertThat(interactions.visibleColumnIds()).containsExactly("C", "A");
            assertThat(table.getColumnModel().getColumn(1).getPreferredWidth())
                    .as("A's width, across the swap")
                    .isEqualTo(333);

            interactions.setColumnVisible("B", true);
            assertThat(interactions.visibleColumnIds())
                    .as("B returns to its remembered place")
                    .containsExactly("B", "C", "A");
            return null;
        });
    }

    @Test
    @DisplayName("the last visible column cannot be hidden")
    void lastVisibleColumnStays() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, new RecordingAdapter());

            interactions.setColumnVisible("A", false);
            interactions.setColumnVisible("B", false);
            interactions.setColumnVisible("C", false);

            assertThat(interactions.visibleColumnIds())
                    .as("a table with no columns would read as no data")
                    .containsExactly("C");
            return null;
        });
    }

    @Test
    @DisplayName("the header menu offers a sort submenu where sorting is real, and says why where it is not")
    void headerMenuIsHonest() throws Exception {
        onEdt(() -> {
            JTable sortableTable = new JTable(model());
            TableHeaderInteractions sortable = TableHeaderInteractions.install(
                    sortableTable, new RecordingAdapter("A"));
            List<String> sortableItems = itemTexts(sortable.buildHeaderMenu());
            assertThat(sortableItems)
                    .contains("Sort")
                    .contains("A", "B", "C")
                    .contains("Default order", "A — ascending", "A — descending")
                    .doesNotContain("B — ascending");

            JTable fixedTable = new JTable(model());
            TableHeaderInteractions fixed = TableHeaderInteractions.install(
                    fixedTable, TableHeaderInteractions.Adapter.unsortable(
                            "This order is fixed by design."));
            List<String> fixedItems = itemTexts(fixed.buildHeaderMenu());
            assertThat(fixedItems)
                    .as("the absence of a sort submenu is named, not silent")
                    .contains("This order is fixed by design.")
                    .doesNotContain("Sort");
            return null;
        });
    }

    @Test
    @DisplayName("header tooltips explain the cycle on sortable columns and the reason on the rest")
    void tooltipsAreHonest() throws Exception {
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions = TableHeaderInteractions.install(
                    table, new RecordingAdapter("A"));

            assertThat(interactions.tooltipForTest(0))
                    .contains("Click to sort by A")
                    .contains("restores the default order");
            assertThat(interactions.tooltipForTest(1))
                    .contains("No ordering stands behind B.");
            assertThat(interactions.tooltipForTest(-1)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("column state persists to disk and a fresh run picks it up, sort included")
    void persistedStateSurvivesARestart(@TempDir Path settings) throws Exception {
        ColumnStateStore store = new ColumnStateStore(settings, "restart");
        onEdt(() -> {
            JTable table = new JTable(model());
            RecordingAdapter adapter = new RecordingAdapter("A");
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, adapter);
            interactions.attachPersistence(store, Runnable::run);

            table.getColumnModel().getColumn(0).setPreferredWidth(287);
            interactions.setColumnVisible("C", false);
            interactions.clickForTest("A"); // A ascending
            interactions.flushSaveForTest();
            return null;
        });

        ColumnState onDisk = store.load();
        assertThat(onDisk.hidden()).containsExactly("C");
        assertThat(onDisk.widths()).containsEntry("A", 287);
        assertThat(onDisk.sort()).contains(new ColumnState.Sort("A", false));

        // "The next run": a new table, a new installer, the same store. The
        // loaded state applies on the EDT a turn after attach, so the
        // assertions take their own turn.
        AtomicReference<TableHeaderInteractions> restored = new AtomicReference<>();
        AtomicReference<JTable> restoredTable = new AtomicReference<>();
        RecordingAdapter restoredAdapter = new RecordingAdapter("A");
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, restoredAdapter);
            interactions.attachPersistence(store, Runnable::run);
            restored.set(interactions);
            restoredTable.set(table);
            return null;
        });
        onEdt(() -> {
            assertThat(restored.get().visibleColumnIds()).containsExactly("A", "B");
            assertThat(restoredTable.get().getColumnModel().getColumn(0).getPreferredWidth())
                    .isEqualTo(287);
            assertThat(restored.get().currentSort())
                    .contains(new ColumnState.Sort("A", false));
            assertThat(restoredAdapter.applied)
                    .as("the restored sort is applied through the view, once")
                    .containsExactly("A asc");
            return null;
        });
    }

    @Test
    @DisplayName("a save that would change nothing schedules no write")
    void unchangedStateIsNotRewritten(@TempDir Path settings) throws Exception {
        ColumnStateStore store = new ColumnStateStore(settings, "quiet");
        List<Runnable> submissions = new ArrayList<>();
        onEdt(() -> {
            JTable table = new JTable(model());
            TableHeaderInteractions interactions =
                    TableHeaderInteractions.install(table, new RecordingAdapter());
            interactions.attachPersistence(store, task -> {
                submissions.add(task);
                task.run();
            });

            int afterLoad = submissions.size();
            table.getColumnModel().getColumn(0).setPreferredWidth(287);
            interactions.flushSaveForTest();
            assertThat(submissions).as("the change was written").hasSize(afterLoad + 1);

            interactions.flushSaveForTest();
            assertThat(submissions)
                    .as("nothing changed, so nothing else was written")
                    .hasSize(afterLoad + 1);
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
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

    /** Every item text in the menu tree, submenus included. */
    private static List<String> itemTexts(JPopupMenu menu) {
        List<String> texts = new ArrayList<>();
        collect(menu, texts);
        return texts;
    }

    private static void collect(MenuElement element, List<String> texts) {
        if (element instanceof JMenu submenu) {
            texts.add(submenu.getText());
        } else if (element instanceof JCheckBoxMenuItem checkbox) {
            texts.add(checkbox.getText());
        } else if (element instanceof javax.swing.JMenuItem item) {
            texts.add(item.getText());
        }
        for (MenuElement child : element.getSubElements()) {
            collect(child, texts);
        }
    }
}

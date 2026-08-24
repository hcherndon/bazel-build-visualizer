package com.holtherndon.bazelviz.ui.errors;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Errors card's client-side sort: the shared 3-state header cycle over a
 * {@code TableRowSorter}, whose third state is the deliberate kind-priority
 * load order — unsorted, exactly as the pages arrived.
 *
 * <p>Client-side is legitimate here and only here among the entity tables:
 * every loaded row is already in memory, so sorting them misrepresents
 * nothing. The fixture is a console row plus aborts, the shape the fake
 * session supports, loaded in that order.
 */
final class ErrorsViewSortTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    private ErrorsView view;

    @AfterEach
    void closeView() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> view.closeSession());
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("the header cycle sorts ascending, descending, then restores the load order")
    void cycleSortsAndRestoresLoadOrder() throws Exception {
        openWithRows("//zeta:last", "//alpha:first", "//mid:way");
        List<String> loadOrder = subjectsInViewOrder();
        assertThat(onEdt(() -> view.sorterForTest().getSortKeys())).isEmpty();
        assertThat(labelsOf(loadOrder))
                .containsExactly("//zeta:last", "//alpha:first", "//mid:way");

        onEdt(() -> {
            view.headerInteractionsForTest().clickForTest("Subject");
            return null;
        });
        assertThat(onEdt(() -> view.sorterForTest().getSortKeys()))
                .singleElement()
                .satisfies(key -> assertThat(key.getSortOrder())
                        .isEqualTo(SortOrder.ASCENDING));
        assertThat(labelsOf(subjectsInViewOrder()))
                .containsExactly("//alpha:first", "//mid:way", "//zeta:last");

        onEdt(() -> {
            view.headerInteractionsForTest().clickForTest("Subject");
            return null;
        });
        assertThat(labelsOf(subjectsInViewOrder()))
                .containsExactly("//zeta:last", "//mid:way", "//alpha:first");

        // The third click: back to the deliberate kind-priority load order,
        // not to some arbitrary "unsorted" that differs from it.
        onEdt(() -> {
            view.headerInteractionsForTest().clickForTest("Subject");
            return null;
        });
        assertThat(onEdt(() -> view.sorterForTest().getSortKeys())).isEmpty();
        assertThat(subjectsInViewOrder()).isEqualTo(loadOrder);
    }

    @Test
    @Timeout(30)
    @DisplayName("the L&F's own header toggle is disabled; only the shared cycle sorts")
    void lookAndFeelToggleIsDisabled() throws Exception {
        openWithRows("//a:a", "//b:b");
        for (int column = 0; column < 4; column++) {
            int checked = column;
            assertThat(onEdt(() -> view.sorterForTest().isSortable(checked)))
                    .as("column %d must not answer to the two-state UI toggle", checked)
                    .isFalse();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("selecting a row while sorted inspects the row on screen, not the load-order row")
    void selectionFollowsTheViewOrder() throws Exception {
        openWithRows("//zeta:last", "//alpha:first");

        onEdt(() -> {
            view.headerInteractionsForTest().clickForTest("Subject"); // ascending
            return null;
        });
        int alphaViewRow = subjectsInViewOrder().indexOf("//alpha:first");
        assertThat(alphaViewRow).isNotNegative();
        onEdt(() -> {
            view.selectForTest(alphaViewRow);
            return null;
        });

        await(() -> onEdt(() ->
                view.inspectionForTest().title().contains("//alpha:first")));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Opens the view over a session holding one console row (which loads
     * first — the fake's first page is the console output) plus these aborts,
     * then presses "Load more" so the aborts are on screen too.
     */
    private void openWithRows(String... labels) throws Exception {
        FakeErrorSession session = new FakeErrorSession()
                .withConsoleEvent(11, 4, "boom\n", "");
        for (String label : labels) {
            session.withAbortedTarget(label);
        }
        SwingUtilities.invokeAndWait(() -> {
            view = new ErrorsView();
            view.openSession(session);
        });
        await(() -> onEdt(() -> view.rowCountForTest() == 1));
        onEdt(() -> {
            view.loadMoreForTest();
            return null;
        });
        await(() -> onEdt(() -> view.rowCountForTest() == 1 + labels.length));
    }

    /** Only the abort rows' labels, with the console row filtered out. */
    private static List<String> labelsOf(List<String> subjects) {
        return subjects.stream().filter(subject -> subject.startsWith("//")).toList();
    }

    private List<String> subjectsInViewOrder() throws Exception {
        return onEdt(() -> {
            List<String> subjects = new ArrayList<>();
            javax.swing.JTable table = viewTable();
            for (int row = 0; row < table.getRowCount(); row++) {
                subjects.add(String.valueOf(table.getValueAt(row, 1)));
            }
            return subjects;
        });
    }

    /** The card's table, found by its sorter — the view exposes no getter. */
    private javax.swing.JTable viewTable() {
        java.util.ArrayDeque<java.awt.Container> queue = new java.util.ArrayDeque<>();
        queue.add(view);
        while (!queue.isEmpty()) {
            java.awt.Container current = queue.poll();
            for (java.awt.Component child : current.getComponents()) {
                if (child instanceof javax.swing.JTable table
                        && table.getRowSorter() == view.sorterForTest()) {
                    return table;
                }
                if (child instanceof java.awt.Container container) {
                    queue.add(container);
                }
            }
        }
        throw new AssertionError("the errors table was not found");
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Boolean.TRUE.equals(condition.call())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true");
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
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
}

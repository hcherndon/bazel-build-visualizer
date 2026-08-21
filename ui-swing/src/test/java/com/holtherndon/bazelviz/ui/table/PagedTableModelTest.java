package com.holtherndon.bazelviz.ui.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import org.junit.jupiter.api.Test;

final class PagedTableModelTest {

    static {
        // The model touches only AbstractTableModel + EventQueue, both of
        // which work headless; make sure no toolkit ever wants a display.
        System.setProperty("java.awt.headless", "true");
    }

    private static final int PAGE_SIZE = 10;
    private static final int CACHE_CAPACITY = 4;

    /** Deterministic executor: nothing runs until the test says so. */
    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }

        int pending() {
            return queue.size();
        }
    }

    private static final class FakeSource implements RowSource<String> {
        final List<Long> fetchedPages = Collections.synchronizedList(new ArrayList<>());
        private final long rowCount;

        FakeSource(long rowCount) {
            this.rowCount = rowCount;
        }

        @Override
        public long rowCount() {
            return rowCount;
        }

        @Override
        public Page<String> fetchPage(long pageIndex, int pageSize) {
            fetchedPages.add(pageIndex);
            long first = pageIndex * pageSize;
            int n = (int) Math.min(pageSize, rowCount - first);
            List<String> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add("r" + (first + i));
            }
            return new Page<>(pageIndex, rows);
        }
    }

    private static PagedTableModel<String> model(FakeSource source, Executor executor) {
        List<ColumnSpec<String>> columns = List.of(
                new ColumnSpec<>("value", v -> v),
                new ColumnSpec<>("length", String::length));
        return new PagedTableModel<>(source, columns, executor, PAGE_SIZE, CACHE_CAPACITY);
    }

    /** Delivers all pending EDT work (the model fires its events via invokeLater). */
    private static void pumpEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void placeholderFirstThenRealValueOnceFetched() throws Exception {
        FakeSource source = new FakeSource(100);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);

        assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
        assertThat(model.isPageLoaded(0)).isFalse();

        executor.runAll();
        pumpEdt();

        assertThat(model.isPageLoaded(0)).isTrue();
        assertThat(model.getValueAt(0, 0)).isEqualTo("r0");
        assertThat(model.getValueAt(9, 0)).isEqualTo("r9");
        assertThat(model.getValueAt(0, 1)).isEqualTo(2);
        assertThat(model.fetchCount()).isEqualTo(1);
    }

    @Test
    void repeatedMissesOnOnePageScheduleOneFetch() {
        FakeSource source = new FakeSource(100);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);

        for (int row = 0; row < PAGE_SIZE; row++) {
            assertThat(model.getValueAt(row, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
        }
        assertThat(executor.pending()).isEqualTo(1);

        executor.runAll();
        assertThat(source.fetchedPages).containsExactly(0L);
    }

    @Test
    void cachedReadsScheduleNothing() throws Exception {
        FakeSource source = new FakeSource(100);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);

        model.getValueAt(0, 0);
        executor.runAll();
        pumpEdt();

        model.getValueAt(3, 0);
        model.getValueAt(7, 1);
        assertThat(executor.pending()).isZero();
        assertThat(source.fetchedPages).containsExactly(0L);
    }

    @Test
    void firesCoalescedRowUpdatesOnTheEdtWhenPagesArrive() throws Exception {
        FakeSource source = new FakeSource(100);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);
        List<TableModelEvent> events = Collections.synchronizedList(new ArrayList<>());
        TableModelListener listener = events::add;
        model.addTableModelListener(listener);

        model.getValueAt(0, 0);  // page 0
        model.getValueAt(10, 0); // page 1
        executor.runAll();       // both pages complete before the EDT flushes
        pumpEdt();               // single flush drains both

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getType()).isEqualTo(TableModelEvent.UPDATE);
        assertThat(events.get(0).getFirstRow()).isEqualTo(0);
        assertThat(events.get(0).getLastRow()).isEqualTo(9);
        assertThat(events.get(1).getFirstRow()).isEqualTo(10);
        assertThat(events.get(1).getLastRow()).isEqualTo(19);
    }

    @Test
    void shortLastPageClampsTheUpdateRange() throws Exception {
        FakeSource source = new FakeSource(25);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);
        List<TableModelEvent> events = Collections.synchronizedList(new ArrayList<>());
        model.addTableModelListener(events::add);

        assertThat(model.getValueAt(24, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
        executor.runAll();
        pumpEdt();

        assertThat(model.getValueAt(24, 0)).isEqualTo("r24");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFirstRow()).isEqualTo(20);
        assertThat(events.get(0).getLastRow()).isEqualTo(24);
    }

    @Test
    void farViewportJumpSkipsObsoleteInFlightFetch() throws Exception {
        FakeSource source = new FakeSource(1_000);
        QueueExecutor executor = new QueueExecutor();
        PagedTableModel<String> model = model(source, executor);

        model.getValueAt(0, 0);   // queues page 0
        model.getValueAt(990, 0); // queues page 99, moves the viewport proxy far away
        executor.runAll();

        // Page 0 was more than CACHE_CAPACITY pages from the last requested
        // page when its fetch ran, so it must have been dropped.
        assertThat(source.fetchedPages).containsExactly(99L);
        assertThat(model.skippedFetchCount()).isEqualTo(1);
        assertThat(model.isPageLoaded(0)).isFalse();
        assertThat(model.isPageLoaded(99)).isTrue();

        // Coming back re-schedules the page; the skip left no stuck in-flight state.
        assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
        executor.runAll();
        pumpEdt();
        assertThat(model.getValueAt(0, 0)).isEqualTo("r0");
        assertThat(source.fetchedPages).containsExactly(99L, 0L);
    }

    @Test
    void exposesColumnBindingAndRowCount() {
        FakeSource source = new FakeSource(100);
        PagedTableModel<String> model = model(source, new QueueExecutor());

        assertThat(model.getRowCount()).isEqualTo(100);
        assertThat(model.getColumnCount()).isEqualTo(2);
        assertThat(model.getColumnName(0)).isEqualTo("value");
        assertThat(model.getColumnName(1)).isEqualTo("length");
    }

    @Test
    void lastFetchNanosIsMinusOneBeforeAnyFetchCompletes() {
        PagedTableModel<String> model = model(new FakeSource(100), new QueueExecutor());
        assertThat(model.lastFetchNanos()).isEqualTo(-1);
    }

    @Test
    void rejectsRowCountsBeyondIntRange() {
        FakeSource huge = new FakeSource(Integer.MAX_VALUE + 1L);
        assertThatThrownBy(() -> model(huge, new QueueExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scroll shell");
    }
}

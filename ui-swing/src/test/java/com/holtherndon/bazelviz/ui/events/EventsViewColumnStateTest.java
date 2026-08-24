package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Events tab's slice of the shared column machinery: honest tooltips on a
 * table whose order is fixed by design, and column visibility surviving the
 * live-refresh model swap the same way widths already do
 * ({@code EventsViewLiveRefreshTest.refreshPreservesAResizedColumnWidth}).
 */
final class EventsViewColumnStateTest {

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
            onEdt(() -> {
                events.closeSession();
                return null;
            });
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("no header sorts, and the reason offered is the chronological design")
    void headersExplainTheFixedOrder() throws Exception {
        events = onEdt(EventsView::new);
        onEdt(() -> {
            for (String column : new String[] {"Row id", "Sequence", "Type"}) {
                assertThat(events.headerInteractionsForTest().isSortableForTest(column))
                        .as("%s must not offer a sort it cannot honestly do", column)
                        .isFalse();
            }
            assertThat(events.headerInteractionsForTest().explanationForTest("Sequence"))
                    .contains("arrival order")
                    .contains("EventRowIndex");
            return null;
        });
    }

    @Test
    @Timeout(30)
    @DisplayName("a hidden column stays hidden across a live refresh, and is hidden, not dropped")
    void hiddenColumnSurvivesALiveRefresh() throws Exception {
        FakeSessionReader reader = FakeSessionReader.dense(60);
        FakeSource source = new FakeSource(reader, SessionState.CAPTURING);
        List<String> failures = new ArrayList<>();

        long tickIntervalMicros = 40_000; // 40 ms, as the live-refresh tests use
        events = onEdt(() -> new EventsView(tickIntervalMicros));
        onEdt(() -> {
            events.openSession(source, failures::add);
            return null;
        });
        await(() -> onEdt(() -> events.tableModelForTest() != null));
        int modelColumns = onEdt(() -> events.tableModelForTest().getColumnCount());

        onEdt(() -> {
            events.headerInteractionsForTest().setColumnVisible("Sequence", false);
            return null;
        });
        assertThat(onEdt(() -> events.headerInteractionsForTest().visibleColumnIds()))
                .doesNotContain("Sequence");

        // Grow the session; the ticker's own refresh swaps the model.
        for (int id = 61; id <= 90; id++) {
            reader.add(id, id - 1, DecodeStatus.OK);
        }
        await(() -> onEdt(() -> events.tableModelForTest().getRowCount()) == 90);

        assertThat(onEdt(() -> events.headerInteractionsForTest().visibleColumnIds()))
                .as("the hidden column stays hidden across the swap")
                .doesNotContain("Sequence");
        assertThat(onEdt(() -> events.tableModelForTest().getColumnCount()))
                .as("hidden is presentation only: the model keeps every column")
                .isEqualTo(modelColumns);
        assertThat(failures).isEmpty();

        // And it comes back on request, which is what distinguishes hidden
        // from dropped for the person using it.
        onEdt(() -> {
            events.headerInteractionsForTest().setColumnVisible("Sequence", true);
            return null;
        });
        assertThat(onEdt(() -> events.headerInteractionsForTest().visibleColumnIds()))
                .contains("Sequence");
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

    /** The same always-the-same-reader source the live-refresh tests use. */
    private record FakeSource(FakeSessionReader reader, SessionState state)
            implements SessionSource {

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
        public com.holtherndon.bazelviz.ui.session.QueryReader openQueryReader() {
            throw new UnsupportedOperationException("the events view runs no ad hoc SQL");
        }

        @Override
        public Connection openTimelineConnection() {
            throw new UnsupportedOperationException("the events view is not the timeline");
        }

        @Override
        public void close() {
            // The reader is shared and outlives any one FakeSource here.
        }
    }
}

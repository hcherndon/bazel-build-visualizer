package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * t4: "The timeline does not appear to be properly rendering during a build."
 *
 * <h2>What was actually wrong</h2>
 *
 * <p>{@link TimelineController#refreshLive} only ever ran when something else
 * called it -- in the live app, {@code MainWindow}'s BES progress callback.
 * On a quiet build (a long-running action between progress events, or a build
 * that simply never emits many of them) the timeline had no other clock and
 * stalled along with the ticks, even though the wall kept growing underneath
 * it. This is the same shape of bug the overview panel does not have, because
 * it drives its own {@code ScheduledExecutorService} rather than waiting to be
 * told.
 *
 * <p>This test opens a session and then does nothing else: no progress tick,
 * no call to {@link TimelineController#refreshLive} from the test itself. If
 * the controller only rebuilds when told to, the database is read exactly
 * once, forever. The fix is that the controller now has its own timer, so the
 * database keeps getting read on its own schedule regardless.
 */
final class TimelineControllerTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    private Path databaseFile;
    private TimelineController controller;

    @AfterEach
    void tearDown() throws Exception {
        if (controller != null) {
            onEdt(() -> {
                controller.closeSession();
                return null;
            });
        }
        if (databaseFile != null) {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("the controller's own timer rebuilds a live session without any progress tick")
    void timerDrivesARebuildOnItsOwn() throws Exception {
        databaseFile = Files.createTempFile("timeline-controller-test", ".sqlite");
        String url = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        createMinimalSchema(url);

        AtomicInteger opens = new AtomicInteger();
        FakeSource source = new FakeSource(url, opens);

        // A short interval so several ticks fit in a fraction of a second,
        // exactly as OverviewPanelTest drives OverviewPanel's own interval
        // faster than its real two seconds for the same reason.
        long tickIntervalMicros = 40_000; // 40 ms
        controller = onEdt(() -> new TimelineController(tickIntervalMicros));

        onEdt(() -> {
            controller.openSession(source);
            return null;
        });

        // openSession's own initial build already opens the database once;
        // everything after this baseline is attributable only to the
        // controller's timer, since nothing else in this test ever calls
        // refreshLive() or simulates a progress tick.
        waitUntil(() -> opens.get() >= 1);
        int baseline = opens.get();

        TimeUnit.MILLISECONDS.sleep(400);

        assertThat(opens.get())
                .as("opens beyond the initial build, with no progress tick simulated")
                .isGreaterThan(baseline);
    }

    private static void createMinimalSchema(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE actions (id INTEGER PRIMARY KEY,"
                    + " start_micros INTEGER, end_micros INTEGER, outcome TEXT,"
                    + " mnemonic_id INTEGER, label_id INTEGER)");
            statement.execute("CREATE TABLE action_attempts (cache_hit INTEGER,"
                    + " runner TEXT, start_micros INTEGER, total_micros INTEGER,"
                    + " mnemonic_id INTEGER, exit_code INTEGER, input_bytes INTEGER)");
            statement.execute("CREATE TABLE mnemonics (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("CREATE TABLE labels (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("CREATE TABLE declared_actions (action_id INTEGER,"
                    + " execution_platform TEXT)");
            // One drawable action, so build() succeeds end to end on every
            // tick rather than exercising only the "nothing to draw yet" path.
            statement.execute("INSERT INTO actions (id, start_micros, end_micros, outcome)"
                    + " VALUES (1, 0, 1000, 'SUCCESS')");
        }
    }

    /** Polls a condition instead of sleeping a fixed guess, with a generous ceiling. */
    private static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
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

    /**
     * A source that hands out a fresh JDBC connection to a real (if minimal)
     * SQLite file on every call, and counts how many times that happened --
     * which is the observable this test is built around.
     */
    private record FakeSource(String url, AtomicInteger opens) implements SessionSource {

        @Override
        public SessionInfo info() {
            return new SessionInfo(
                    Path.of("."),
                    "fake",
                    com.holtherndon.bazelviz.core.session.SessionState.READY,
                    OptionalLong.empty(),
                    List.of(),
                    List.of());
        }

        @Override
        public SessionReader openReader() {
            throw new UnsupportedOperationException("the timeline does not read raw events");
        }

        @Override
        public EntityReader openEntityReader() {
            throw new UnsupportedOperationException("the timeline does not read entities");
        }

        @Override
        public com.holtherndon.bazelviz.storage.graph.GraphQueries openGraphQueries() {
            throw new UnsupportedOperationException("the timeline does not read the graph");
        }

        @Override
        public com.holtherndon.bazelviz.storage.metrics.MetricQueries openMetricQueries() {
            throw new UnsupportedOperationException("the timeline collects no metrics");
        }

        @Override
        public Connection openTimelineConnection() {
            opens.incrementAndGet();
            try {
                return DriverManager.getConnection(url);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void close() {
            // Nothing held open between calls.
        }
    }
}

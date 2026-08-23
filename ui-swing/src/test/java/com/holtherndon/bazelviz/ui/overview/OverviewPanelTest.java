package com.holtherndon.bazelviz.ui.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What the overview says, and how often it says it. */
class OverviewPanelTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @DisplayName("a build that never reported an outcome is not reported as a failure")
    void outcomeHasThreeStates() throws Exception {
        assertThat(headlineFor(Optional.of(true))).isEqualTo("bazel build — succeeded");
        assertThat(headlineFor(Optional.of(false))).isEqualTo("bazel build — failed");
        // The third state is the one that matters: a build whose BuildFinished
        // never arrived died before it could say, and calling that "failed"
        // would state something the stream does not.
        assertThat(headlineFor(Optional.empty())).isEqualTo("bazel build — outcome not reported");
    }

    @Test
    @DisplayName("a stream that never reached its end marker says so")
    void truncatedStreamsAreCalledOut() throws Exception {
        OverviewPanel panel = onEdt(OverviewPanel::new);
        onEdt(() -> {
            panel.show(snapshot(Optional.of(true), false));
            return null;
        });

        // Aborted events arrive after buildFinished, so a capture that stopped
        // at the wrong place is missing the whole failed-target list. The
        // subhead is where that gets said.
        assertThat(panel.subheadForTest()).contains("did not reach its end marker");
    }

    @Test
    @Timeout(60)
    @DisplayName("a build changing thousands of times refreshes on the interval, not per change")
    void liveUpdatesAreCoalesced() throws Exception {
        FakeEntityReader reader = new FakeEntityReader();
        Duration interval = Duration.ofMillis(40);
        OverviewPanel panel = onEdt(() -> new OverviewPanel(interval));
        AtomicInteger renders = new AtomicInteger();
        onEdt(() -> {
            panel.onSnapshot(snapshot -> renders.incrementAndGet());
            panel.openSession(new FakeSource(reader));
            return null;
        });

        // Change the underlying numbers far faster than the refresh interval,
        // as a build writing rows would.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        long changes = 0;
        while (System.nanoTime() < deadline) {
            reader.advance();
            changes++;
        }
        TimeUnit.MILLISECONDS.sleep(100);

        long reads = reader.overviewReads();
        assertThat(changes).isGreaterThan(1_000L);
        // The property: reads follow the clock, not the data. Ten intervals
        // elapsed, so a couple of dozen reads is generous headroom and still
        // orders of magnitude below the number of changes.
        assertThat(reads).isPositive().isLessThan(50L);
        assertThat(renders.get()).isPositive().isLessThanOrEqualTo((int) reads);

        onEdt(() -> {
            panel.closeSession();
            return null;
        });
    }

    private static String headlineFor(Optional<Boolean> success) throws Exception {
        OverviewPanel panel = onEdt(OverviewPanel::new);
        onEdt(() -> {
            panel.show(snapshot(success, true));
            return null;
        });
        return panel.headlineForTest();
    }

    private static OverviewSnapshot snapshot(Optional<Boolean> success, boolean sawLastMessage) {
        return new OverviewSnapshot(
                Optional.of("9.2.0"),
                Optional.of("build"),
                Optional.of("/ws"),
                Optional.of(true),
                success,
                Optional.empty(),
                OptionalLong.of(1_000_000L),
                sawLastMessage,
                4, 4, 4, 0, 0,
                12, 0, 0, 0, 9, 0, 0,
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(),
                List.of());
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

    /** A source that hands out one fake reader and nothing else. */
    private record FakeSource(EntityReader reader) implements SessionSource {

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
            throw new UnsupportedOperationException("the overview does not read raw events");
        }

        @Override
        public com.holtherndon.bazelviz.storage.graph.GraphQueries openGraphQueries() {
            throw new UnsupportedOperationException("the overview does not read the graph");
        }

        @Override
        public EntityReader openEntityReader() {
            return reader;
        }

        @Override
        public void close() {
            reader.close();
        }
    }
}

package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The inspector's two promises: it reads the selected event's payload once,
 * and it never reads it on the EDT (plan 17.11, project rule 8).
 *
 * <p>Headless. The one place an EDT is involved is the selection call itself,
 * made through {@code invokeAndWait} so that "the fetch did not happen on the
 * thread that selected" is actually being tested rather than assumed.
 */
class EventInspectorModelTest {

    private static final String FETCH_THREAD = "test-inspector-fetch";

    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, FETCH_THREAD));

    @AfterEach
    void tearDown() {
        fetchExecutor.shutdownNow();
    }

    @Test
    @Timeout(30)
    @DisplayName("selecting an event triggers exactly one raw fetch, off the EDT")
    void oneFetchPerSelectionAndNeverOnTheEdt() throws Exception {
        FakeSessionReader reader = FakeSessionReader.dense(10);
        List<EventInspection> seen = Collections.synchronizedList(new ArrayList<>());
        EventInspectorModel model =
                new EventInspectorModel(reader, fetchExecutor, Runnable::run);
        model.addListener(seen::add);

        SwingUtilities.invokeAndWait(() -> model.select(5));
        awaitLoaded(model);

        assertThat(model.payloadFetchCount()).isEqualTo(1);
        assertThat(reader.rawPayloadCalls()).isEqualTo(1);
        assertThat(reader.edtCalls())
                .as("no read may happen on the event dispatch thread")
                .isZero();
        assertThat(reader.callingThreads()).containsExactly(FETCH_THREAD);
        assertThat(seen).extracting(EventInspection::state)
                .containsExactly(EventInspection.State.LOADING, EventInspection.State.LOADED);

        // Re-selecting the row that is already shown must not read it again.
        SwingUtilities.invokeAndWait(() -> model.select(5));
        assertThat(model.payloadFetchCount()).isEqualTo(1);
        assertThat(reader.rawPayloadCalls()).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    @DisplayName("a loaded event carries its decoded row and its verbatim bytes")
    void loadedInspectionCarriesRowAndBytes() throws Exception {
        FakeSessionReader reader = FakeSessionReader.dense(4);
        EventInspectorModel model =
                new EventInspectorModel(reader, fetchExecutor, Runnable::run);

        model.select(2);
        awaitLoaded(model);
        EventInspection inspection = model.current();

        assertThat(inspection.state()).isEqualTo(EventInspection.State.LOADED);
        assertThat(inspection.row().orElseThrow().id()).isEqualTo(2);
        assertThat(inspection.payload().orElseThrow().bytes())
                .isEqualTo("payload-2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(inspection.hexDump().orElseThrow()).contains("|payload-2|");
    }

    @Test
    @Timeout(30)
    @DisplayName("a record that will not decode still shows its bytes, and says the decode failed")
    void failedDecodeStillShowsTheBytes() throws Exception {
        FakeSessionReader reader = new FakeSessionReader();
        reader.add(1, 0, DecodeStatus.FAILED);
        // A field header with no value after it: a genuinely truncated message,
        // not merely one with fields this build does not know.
        reader.setPayload(1, new byte[] {0x08}, SourceKind.BEP_BINARY);
        EventInspectorModel model =
                new EventInspectorModel(reader, fetchExecutor, Runnable::run);

        model.select(1);
        awaitLoaded(model);
        EventInspection inspection = model.current();

        assertThat(inspection.state()).isEqualTo(EventInspection.State.LOADED);
        assertThat(inspection.showsUndecodableBytes()).isTrue();
        RawPayloadRenderer.Rendered rendered = inspection.rendered().orElseThrow();
        assertThat(rendered.decodeFailure()).isPresent();
        assertThat(rendered.text()).contains("could not be decoded");
        assertThat(rendered.text()).contains("bytes are preserved");
        // The evidence itself is on screen, which is the whole reason the raw
        // payload was journaled before anything tried to interpret it.
        assertThat(inspection.hexDump().orElseThrow()).contains("00000000  08");
        assertThat(EventTableColumns.decode(inspection.row().orElseThrow())).isEqualTo("failed");
    }

    @Test
    @Timeout(30)
    @DisplayName("an event that is not there is a stated failure, not an empty pane")
    void missingEventIsReported() throws Exception {
        FakeSessionReader reader = FakeSessionReader.dense(3);
        EventInspectorModel model =
                new EventInspectorModel(reader, fetchExecutor, Runnable::run);

        model.select(99);
        awaitLoaded(model);

        assertThat(model.current().state()).isEqualTo(EventInspection.State.FAILED);
        assertThat(model.current().loadFailure().orElseThrow())
                .contains("no event with row id 99");
        assertThat(reader.rawPayloadCalls()).isZero();
    }

    @Test
    @Timeout(30)
    @DisplayName("clearing the selection reads nothing")
    void clearingSelectionReadsNothing() throws Exception {
        FakeSessionReader reader = FakeSessionReader.dense(3);
        EventInspectorModel model =
                new EventInspectorModel(reader, fetchExecutor, Runnable::run);
        model.select(1);
        awaitLoaded(model);
        int fetches = reader.rawPayloadCalls();

        model.clearSelection();

        assertThat(model.current().state()).isEqualTo(EventInspection.State.NONE);
        assertThat(reader.rawPayloadCalls()).isEqualTo(fetches);
        assertThat(model.selectedEventId()).isEmpty();
    }

    private static void awaitLoaded(EventInspectorModel model) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (model.current().state() == EventInspection.State.LOADING
                || model.current().state() == EventInspection.State.NONE) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the inspection never left "
                        + model.current().state());
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}

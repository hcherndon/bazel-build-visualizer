package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportOutcome;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepDamage;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 1 UI deliverable end to end, against a session a real import
 * produced: open a BEP file, page the event table, inspect an event's raw
 * protobuf, and reopen the indexed session without re-importing it.
 *
 * <p>Nothing here is faked. The fixtures are real synthetic BEP streams, the
 * import is the real pipeline, the rows come back through the real SQLite
 * queries and the bytes come back out of the real journal — so this is the test
 * that would catch a raw location the view reads differently from the way the
 * importer wrote it.
 */
class ImportedSessionEventsTest {

    private static final int EVENT_COUNT = 250;
    private static final int PAGE_SIZE = 100;

    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "test-session-fetch"));

    @AfterEach
    void tearDown() {
        fetchExecutor.shutdownNow();
    }

    @Test
    @Timeout(120)
    @DisplayName("an imported session opens, pages, and shows the selected event's real bytes")
    void importThenOpenThenInspect(@TempDir Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, stream);
        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(source);
        assertThat(imported.outcome()).isEqualTo(ImportOutcome.COMPLETE);

        // Reopening must not depend on the original file. Deleting it proves
        // the session is self-contained, which is what the default
        // copy-into-session preservation mode buys.
        Files.delete(source);

        try (SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot())) {
            assertThat(opened.info().state())
                    .isIn(SessionState.READY, SessionState.READY_WITH_WARNINGS);
            assertThat(opened.info().isPartial()).isFalse();

            SessionReader pageReader = opened.openReader();
            EventRowSource rows = EventRowSource.open(pageReader, PAGE_SIZE);
            assertThat(rows.rowCount()).isEqualTo(EVENT_COUNT);
            assertThat(rows.rowIndexMode())
                    .as("a freshly imported session has contiguous ids")
                    .isEqualTo(EventRowIndex.Mode.DENSE);

            Page<EventRow> lastPage = rows.fetchPage(2, PAGE_SIZE);
            assertThat(lastPage.rows()).hasSize(EVENT_COUNT - 2 * PAGE_SIZE);
            assertThat(lastPage.rows().getFirst().sequence()).isEqualTo(200);
            assertThat(lastPage.rows().getLast().sequence()).isEqualTo(EVENT_COUNT - 1);

            EventRow row = lastPage.rows().getFirst();
            EventInspectorModel inspector = new EventInspectorModel(
                    opened.openReader(), fetchExecutor, Runnable::run);
            inspector.select(row.id());
            awaitLoaded(inspector);
            EventInspection inspection = inspector.current();

            assertThat(inspection.state()).isEqualTo(EventInspection.State.LOADED);
            // The bytes the inspector shows are the fixture's own serialized
            // event, byte for byte, fetched through the stored raw location.
            assertThat(inspection.payload().orElseThrow().bytes())
                    .isEqualTo(stream.eventAt(200).toByteArray());
            assertThat(inspection.rendered().orElseThrow().decodeFailure()).isEmpty();
            assertThat(inspection.rendered().orElseThrow().text()).isNotEmpty();
            assertThat(inspection.hexDump().orElseThrow()).contains("00000000  ");
            assertThat(inspector.payloadFetchCount()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a truncated capture opens as a partial session and shows what did import")
    void truncatedCaptureIsViewableAndLabelledPartial(@TempDir Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path whole = temporary.resolve("build.bep");
        BepBinaryWriter.write(whole, stream);
        Path damaged = temporary.resolve("truncated.bep");
        BepDamage.truncateMidPayload(whole, damaged);

        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(damaged);

        assertThat(imported.outcome()).isEqualTo(ImportOutcome.TRUNCATED);
        assertThat(imported.sessionState()).isEqualTo(SessionState.INCOMPLETE);

        try (SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot())) {
            // The view says the capture is partial rather than presenting the
            // rows that survived as if they were the whole build.
            assertThat(opened.info().isPartial()).isTrue();

            EventRowSource rows = EventRowSource.open(opened.openReader(), PAGE_SIZE);
            assertThat(rows.rowCount())
                    .isEqualTo(imported.eventsInDatabase())
                    .isLessThan(EVENT_COUNT)
                    .isPositive();

            Page<EventRow> first = rows.fetchPage(0, PAGE_SIZE);
            assertThat(first.rows()).hasSize(PAGE_SIZE);
            assertThat(first.rows().getFirst().sequence()).isZero();
        }
    }

    private static void awaitLoaded(EventInspectorModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (model.current().state() == EventInspection.State.LOADING
                || model.current().state() == EventInspection.State.NONE) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the inspection never left " + model.current().state());
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}

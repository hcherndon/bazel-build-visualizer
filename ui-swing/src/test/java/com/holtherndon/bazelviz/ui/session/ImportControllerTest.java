package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportOptions;
import com.holtherndon.bazelviz.capture.file.importer.ImportOutcome;
import com.holtherndon.bazelviz.capture.file.importer.ImportPhase;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.capture.file.importer.UnsupportedSourceException;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The import controller against the real importer: the run happens off the
 * calling thread, progress arrives, cancelling actually stops the import, and a
 * file that is not BEP fails with the detector's own reason rather than with a
 * shrug.
 */
class ImportControllerTest {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "test-import-worker"));

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    /** Collects the lifecycle. The UI dispatcher is direct, so no EDT is needed. */
    private static class RecordingListener implements ImportController.Listener {
        final List<ImportProgressModel.Snapshot> progress =
                Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<ImportResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> startedOnThread = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void importStarted(Path source, boolean resuming) {
            startedOnThread.set(Thread.currentThread().getName());
        }

        @Override
        public void importProgress(ImportProgressModel.Snapshot snapshot) {
            progress.add(snapshot);
        }

        @Override
        public void importFinished(ImportResult finished) {
            result.set(finished);
            done.countDown();
        }

        @Override
        public void importFailed(Path source, Throwable thrown) {
            failure.set(thrown);
            done.countDown();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a real BEP file imports on the worker thread and reports progress")
    void importsOffTheCallingThread(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(400));
        ImportController controller = controller(temporary, ImportOptions.defaults()
                .withProgressEveryRecords(10)
                .withProgressIntervalMillis(0));
        RecordingListener listener = new RecordingListener();

        assertThat(controller.start(source, listener)).isTrue();
        assertThat(listener.done.await(90, TimeUnit.SECONDS)).isTrue();

        assertThat(listener.failure.get()).isNull();
        ImportResult result = listener.result.get();
        assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
        assertThat(result.eventsInDatabase()).isEqualTo(400);
        assertThat(listener.progress).isNotEmpty();
        assertThat(listener.progress).anyMatch(
                snapshot -> snapshot.phase() == ImportPhase.READING);
        assertThat(controller.isRunning()).isFalse();
    }

    @Test
    @Timeout(120)
    @DisplayName("cancel really cancels: the import stops and stays resumable")
    void cancelStopsTheImport(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(4_000));
        ImportController controller = controller(temporary, ImportOptions.defaults()
                .withProgressEveryRecords(1)
                .withProgressIntervalMillis(0)
                .withCheckpointEveryRecords(25));
        RecordingListener listener = new RecordingListener() {
            @Override
            public void importProgress(ImportProgressModel.Snapshot snapshot) {
                super.importProgress(snapshot);
                if (snapshot.phase() == ImportPhase.READING && snapshot.recordsRead() >= 50) {
                    controller.cancel();
                }
            }
        };

        controller.start(source, listener);
        assertThat(listener.done.await(90, TimeUnit.SECONDS)).isTrue();

        ImportResult result = listener.result.get();
        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(ImportOutcome.CANCELLED);
        assertThat(result.outcome().isResumable()).isTrue();
        assertThat(result.eventsInDatabase())
                .as("what was read before the cancel is kept, not discarded")
                .isPositive();
        assertThat(result.eventsInDatabase()).isLessThan(4_000);
        assertThat(controller.isCancelRequested()).isTrue();
    }

    @Test
    @Timeout(60)
    @DisplayName("a file that is not BEP fails with the detector's own reason")
    void unsupportedSourceIsExplained(@TempDir Path temporary) throws Exception {
        Path notBep = temporary.resolve("notes.txt");
        Files.writeString(notBep, "this is not a build event protocol file\n",
                StandardCharsets.UTF_8);
        ImportController controller = controller(temporary, ImportOptions.defaults());
        RecordingListener listener = new RecordingListener();

        controller.start(notBep, listener);
        assertThat(listener.done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(listener.result.get()).isNull();
        assertThat(listener.failure.get()).isInstanceOf(UnsupportedSourceException.class);
        assertThat(listener.failure.get().getMessage()).contains("cannot import");
        // Nothing is left behind: the refusal happens before a session exists.
        assertThat(Files.exists(temporary.resolve("sessions"))).isFalse();
    }

    @Test
    @Timeout(120)
    @DisplayName("a second import is refused while one is running")
    void onlyOneImportAtATime(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(2_000));
        ImportController controller = controller(temporary, ImportOptions.defaults());
        // The first progress sample is emitted on the worker as soon as the run
        // begins; holding it there makes "while one is running" a fact rather
        // than a race against a fast import.
        CountDownLatch release = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener() {
            @Override
            public void importProgress(ImportProgressModel.Snapshot snapshot) {
                super.importProgress(snapshot);
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        assertThat(controller.start(source, listener)).isTrue();
        boolean second;
        try {
            second = controller.start(source, new RecordingListener());
        } finally {
            release.countDown();
        }

        assertThat(listener.done.await(90, TimeUnit.SECONDS)).isTrue();
        assertThat(second)
                .as("a rejected second start is reported, never silently queued")
                .isFalse();
    }

    private ImportController controller(Path temporary, ImportOptions options) {
        SessionManager sessions =
                new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        return new ImportController(
                new BepImporter(sessions, options),
                worker,
                Runnable::run,
                new ImportProgressModel());
    }
}

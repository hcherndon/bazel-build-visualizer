package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.v1.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.StreamId;
import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.RawBesEvent;
import com.holtherndon.bazelviz.capture.bes.RawEventSink;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.format.journal.ImportCheckpointStore;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.format.journal.JournalWriterConfig;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LiveCapturePipelineInterruptionTest {

  private static final BesStreamKey KEY = new BesStreamKey("build", "invocation", "TOOL");

  @TempDir Path temp;

  @Test
  @DisplayName("an interrupted finish stops both workers before storage can be closed")
  void interruptedFinishStillJoinsWorkers() throws Exception {
    GateProgressListener progress = new GateProgressListener();
    try (Fixture fixture = new Fixture(temp, progress)) {
      CountDownLatch journaled = new CountDownLatch(1);
      fixture.pipeline.start();
      fixture.pipeline.submit(event(), callback(journaled));
      assertThat(journaled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(progress.entered.await(5, TimeUnit.SECONDS)).isTrue();

      AtomicReference<CaptureSummary> summary = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicBoolean interruptPreserved = new AtomicBoolean();
      AtomicBoolean workersStoppedBeforeStorageClose = new AtomicBoolean();
      Thread finisher =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      summary.set(fixture.pipeline.finish());
                      interruptPreserved.set(Thread.currentThread().isInterrupted());
                      // Model the coordinator's required dependency-close order.
                      boolean cleanupInterrupted = Thread.interrupted();
                      try {
                        fixture.pipeline.close();
                        cleanupInterrupted |= Thread.interrupted();
                        workersStoppedBeforeStorageClose.set(fixture.pipeline.workersTerminated());
                        fixture.closeStorage();
                      } finally {
                        if (cleanupInterrupted) {
                          Thread.currentThread().interrupt();
                        }
                      }
                    } catch (Throwable problem) {
                      failure.set(problem);
                    }
                  });

      awaitWaiting(finisher);
      finisher.interrupt();
      Thread.sleep(50);
      finisher.interrupt();
      Thread.sleep(50);
      assertThat(finisher.isAlive())
          .as("finish must not return while the journal worker still owns its dependencies")
          .isTrue();
      assertThat(fixture.pipeline.workersTerminated()).isFalse();
      assertThat(fixture.storageClosed).isFalse();
      assertThat(fixture.journal.position()).isNotNull();
      try (var reader = fixture.database.newReadConnection();
          var statement = reader.createStatement();
          var row = statement.executeQuery("SELECT 1")) {
        assertThat(row.next()).isTrue();
      }

      progress.release.countDown();
      finisher.join(5_000);

      assertThat(finisher.isAlive()).isFalse();
      assertThat(failure.get()).isNull();
      assertThat(interruptPreserved).isTrue();
      assertThat(workersStoppedBeforeStorageClose).isTrue();
      assertThat(fixture.storageClosed).isTrue();
      assertThat(summary.get()).isNotNull();
      assertThat(summary.get().failure()).isPresent();
      assertThat(summary.get().isComplete()).isFalse();
    } finally {
      progress.release.countDown();
    }
  }

  private static RawBesEvent event() {
    PublishBuildToolEventStreamRequest request =
        PublishBuildToolEventStreamRequest.newBuilder()
            .setOrderedBuildEvent(
                OrderedBuildEvent.newBuilder()
                    .setStreamId(
                        StreamId.newBuilder()
                            .setBuildId(KEY.buildId())
                            .setInvocationId(KEY.invocationId())
                            .setComponent(StreamId.BuildComponent.TOOL))
                    .setSequenceNumber(1)
                    .setEvent(BuildEvent.getDefaultInstance()))
            .build();
    return new RawBesEvent(SourceKind.BES_ENVELOPE, KEY, 1, 1, request.toByteArray());
  }

  private static RawEventSink.SubmissionCallback callback(CountDownLatch journaled) {
    return new RawEventSink.SubmissionCallback() {
      @Override
      public void onJournaled() {
        journaled.countDown();
      }

      @Override
      public void onRejected(Throwable failure) {
        throw new AssertionError("the test event was rejected", failure);
      }
    };
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.WAITING
          || thread.getState() == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError(
        "finish did not begin waiting for its workers; state="
            + thread.getState()
            + ", alive="
            + thread.isAlive());
  }

  private static final class Fixture implements AutoCloseable {

    private final SessionDatabase database;
    private final EventWriter events;
    private final EntityWriter entities;
    private final StreamRegistry streams;
    private final JournalWriter journal;
    private final LiveCapturePipeline pipeline;
    private final AtomicBoolean storageClosed = new AtomicBoolean();

    private Fixture(Path root, CaptureProgressListener progress) throws Exception {
      database = SessionDatabase.open(root.resolve("session.db"));
      MigrationRunner.standard().migrate(database);
      events = new EventWriter(database.writerConnection());
      entities = new EntityWriter(database.writerConnection());
      streams = new StreamRegistry(database.writerConnection());
      CaptureOptions defaults = CaptureOptions.defaults();
      CaptureOptions options =
          new CaptureOptions(
              1,
              1,
              defaults.batchSize(),
              Duration.ZERO,
              defaults.checkpointEveryFrames(),
              Duration.ZERO,
              defaults.maxMessageBytes(),
              Duration.ZERO);
      journal =
          JournalWriter.create(
              root.resolve("raw"),
              UUID.randomUUID(),
              JournalWriterConfig.defaults().withMaxPayloadBytes(options.maxMessageBytes()));
      pipeline =
          new LiveCapturePipeline(
              journal,
              events,
              entities,
              streams,
              new EventNormalizer(options.maxMessageBytes()),
              new ImportCheckpointStore(root.resolve("checkpoints")),
              options,
              progress);
    }

    @Override
    public void close() throws Exception {
      pipeline.close();
      closeStorage();
    }

    private void closeStorage() throws Exception {
      if (!storageClosed.compareAndSet(false, true)) {
        return;
      }
      journal.close();
      streams.close();
      entities.close();
      events.close();
      database.close();
    }
  }

  private static final class GateProgressListener implements CaptureProgressListener {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void progressed(CaptureProgress progress) {
      entered.countDown();
      boolean interrupted = false;
      while (true) {
        try {
          release.await();
          break;
        } catch (InterruptedException retry) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

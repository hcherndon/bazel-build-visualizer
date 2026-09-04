package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.live.CaptureOptions;
import com.holtherndon.bazelviz.capture.live.CaptureProgressListener;
import com.holtherndon.bazelviz.capture.live.LiveCapturePipeline;
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
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LiveCapturePipelineOwnershipTest {

  private static final BesStreamKey KEY = new BesStreamKey("build", "invocation", "TOOL");

  @TempDir Path temp;

  @Test
  @DisplayName("closing a full receive queue rejects its blocked producer and releases every lease")
  void blockedProducerCannotEnqueueAfterCloseDrain() throws Exception {
    try (Fixture fixture = new Fixture(temp)) {
      RetainedPayloadBudget budget = new RetainedPayloadBudget(2);
      Probe first = new Probe();
      Probe second = new Probe();
      fixture.pipeline.submit(event(budget, 1), first);
      AtomicReference<Throwable> secondFailure = new AtomicReference<>();
      Thread blocked =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      fixture.pipeline.submit(event(budget, 2), second);
                    } catch (Throwable failure) {
                      secondFailure.set(failure);
                    }
                  });

      awaitAlive(blocked);
      fixture.pipeline.close();
      blocked.join(TimeUnit.SECONDS.toMillis(2));

      assertThat(blocked.isAlive()).isFalse();
      assertThat(secondFailure.get()).isInstanceOf(RawEventSink.CaptureRejectedException.class);
      assertThat(first.rejected).hasValue(1);
      assertThat(first.journaled).hasValue(0);
      assertThat(second.rejected).hasValue(0);
      assertThat(budget.retainedBytes()).isZero();
    }
  }

  @Test
  @DisplayName(
      "a store failure with a full normalize queue drains callbacks and cannot hang finish")
  void storeFailureDrainsFullPipelineAndFinishReturns() throws Exception {
    try (Fixture fixture = new Fixture(temp)) {
      try (var timeout = fixture.database.writerConnection().createStatement()) {
        timeout.execute("PRAGMA busy_timeout=100");
      }
      Connection blocker = fixture.database.newReadConnection();
      try (var statement = blocker.createStatement()) {
        statement.execute("BEGIN IMMEDIATE");
      }

      RetainedPayloadBudget budget = new RetainedPayloadBudget(6);
      Probe callbacks = new Probe();
      AtomicInteger accepted = new AtomicInteger();
      AtomicReference<Throwable> producerFailure = new AtomicReference<>();
      fixture.pipeline.start();
      Thread producer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    for (long sequence = 1; sequence <= 6; sequence++) {
                      try {
                        fixture.pipeline.submit(event(budget, sequence), callbacks);
                        accepted.incrementAndGet();
                      } catch (Throwable failure) {
                        producerFailure.set(failure);
                        return;
                      }
                    }
                  });

      awaitFailure(fixture.pipeline);
      try (var statement = blocker.createStatement()) {
        statement.execute("ROLLBACK");
      }
      producer.join(TimeUnit.SECONDS.toMillis(3));
      assertThat(producer.isAlive()).isFalse();
      assertThat(producerFailure.get()).isInstanceOf(RawEventSink.CaptureRejectedException.class);

      AtomicReference<Throwable> finishFailure = new AtomicReference<>();
      Thread finisher =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      fixture.pipeline.finish();
                    } catch (Throwable failure) {
                      finishFailure.set(failure);
                    }
                  });
      finisher.join(TimeUnit.SECONDS.toMillis(3));

      assertThat(finisher.isAlive()).isFalse();
      assertThat(finishFailure.get()).isNull();
      assertThat(callbacks.journaled.get() + callbacks.rejected.get()).isEqualTo(accepted.get());
      assertThat(budget.retainedBytes()).isZero();
    }
  }

  private static RawBesEvent event(RetainedPayloadBudget budget, long sequence) {
    try {
      return new RawBesEvent(
          SourceKind.BES_ENVELOPE, KEY, sequence, sequence, budget.acquire(new byte[] {1}));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    } catch (RetainedPayloadBudget.PayloadRefusedException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void awaitAlive(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!thread.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    Thread.sleep(75);
    assertThat(thread.isAlive()).isTrue();
  }

  private static void awaitFailure(LiveCapturePipeline pipeline) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!pipeline.hasFailed() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(pipeline.hasFailed()).isTrue();
  }

  private static final class Probe implements RawEventSink.SubmissionCallback {

    private final AtomicInteger journaled = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    @Override
    public void onJournaled() {
      journaled.incrementAndGet();
    }

    @Override
    public void onRejected(Throwable failure) {
      rejected.incrementAndGet();
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final SessionDatabase database;
    private final EventWriter events;
    private final EntityWriter entities;
    private final StreamRegistry streams;
    private final JournalWriter journal;
    private final LiveCapturePipeline pipeline;

    private Fixture(Path root) throws Exception {
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
              CaptureProgressListener.ignoring());
    }

    @Override
    public void close() throws Exception {
      pipeline.close();
      journal.close();
      streams.close();
      entities.close();
      events.close();
      database.close();
    }
  }
}

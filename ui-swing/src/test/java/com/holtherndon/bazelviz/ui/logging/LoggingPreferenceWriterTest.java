package com.holtherndon.bazelviz.ui.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoggingPreferenceWriterTest {

  @TempDir Path settings;

  @Test
  @DisplayName("rapid choices persist only the newest level without concurrent writers")
  void newestSelectionWins() {
    Queue<Runnable> backgroundTasks = new ArrayDeque<>();
    LoggingSettingsStore store = new LoggingSettingsStore(settings);
    LoggingPreferenceWriter writer = new LoggingPreferenceWriter(store, backgroundTasks::add);

    writer.save(LogVerbosity.WARN);
    writer.save(LogVerbosity.DEBUG);
    writer.save(LogVerbosity.TRACE);

    assertThat(backgroundTasks).hasSize(1);
    backgroundTasks.remove().run();
    assertThat(backgroundTasks).isEmpty();
    assertThat(store.load()).isEqualTo(LogVerbosity.TRACE);
  }

  @Test
  @DisplayName("close is already complete when no save is pending")
  void idleCloseCompletesImmediately() {
    LoggingPreferenceWriter writer =
        new LoggingPreferenceWriter(new LoggingSettingsStore(settings), Runnable::run);

    assertThat(writer.closeAsync().toCompletableFuture()).isCompleted();
  }

  @Test
  @DisplayName("close completes only after an in-flight save finishes")
  void closeWaitsForInFlightSave() throws Exception {
    CountDownLatch replacementStarted = new CountDownLatch(1);
    CountDownLatch allowReplacement = new CountDownLatch(1);
    LoggingSettingsStore store =
        new LoggingSettingsStore(
            settings,
            (temporary, destination) -> {
              replacementStarted.countDown();
              try {
                if (!allowReplacement.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("test timed out waiting to release save");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test save was interrupted", interrupted);
              }
              Files.move(temporary, destination);
            });
    ExecutorService background = Executors.newSingleThreadExecutor();
    try {
      LoggingPreferenceWriter writer = new LoggingPreferenceWriter(store, background);
      writer.save(LogVerbosity.DEBUG);
      assertThat(replacementStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var closing = writer.closeAsync().toCompletableFuture();
      assertThat(closing).isNotDone();

      allowReplacement.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertThat(store.load()).isEqualTo(LogVerbosity.DEBUG);
    } finally {
      allowReplacement.countDown();
      background.shutdownNow();
    }
  }

  @Test
  @DisplayName("a failed save is reported to the owner")
  void reportsPersistenceFailure() {
    Queue<Runnable> backgroundTasks = new ArrayDeque<>();
    LoggingSettingsStore store =
        new LoggingSettingsStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("disk is read-only");
            });
    AtomicReference<LoggingPreferenceWriter.SaveFailure> reported = new AtomicReference<>();
    LoggingPreferenceWriter writer =
        new LoggingPreferenceWriter(store, backgroundTasks::add, reported::set);

    writer.save(LogVerbosity.WARN);
    backgroundTasks.remove().run();

    assertThat(reported.get()).isNotNull();
    assertThat(reported.get().verbosity()).isEqualTo(LogVerbosity.WARN);
    assertThat(reported.get().cause().getMessage())
        .contains("Logging settings could not be saved")
        .contains(store.file().toString());
  }

  @Test
  @DisplayName("choices made after close are ignored")
  void closedWriterIgnoresLaterChoices() {
    LoggingSettingsStore store = new LoggingSettingsStore(settings);
    LoggingPreferenceWriter writer = new LoggingPreferenceWriter(store, Runnable::run);
    writer.save(LogVerbosity.ERROR);
    writer.closeAsync();

    writer.save(LogVerbosity.TRACE);

    assertThat(store.load()).isEqualTo(LogVerbosity.ERROR);
  }
}

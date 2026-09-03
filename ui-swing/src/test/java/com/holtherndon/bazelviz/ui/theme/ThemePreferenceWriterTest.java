package com.holtherndon.bazelviz.ui.theme;

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

final class ThemePreferenceWriterTest {

  @TempDir Path settings;

  @Test
  @DisplayName("rapid previews persist the newest theme without concurrent writers")
  void newestSelectionWins() {
    Queue<Runnable> backgroundTasks = new ArrayDeque<>();
    ThemeSettingsStore store = new ThemeSettingsStore(settings);
    ThemePreferenceWriter writer = new ThemePreferenceWriter(store, backgroundTasks::add);

    writer.save(AppTheme.DARK);
    writer.save(AppTheme.LIGHT);
    writer.save(AppTheme.DARCULA);

    assertThat(backgroundTasks).hasSize(1);
    backgroundTasks.remove().run();
    assertThat(backgroundTasks).isEmpty();
    assertThat(store.load()).isEqualTo(AppTheme.DARCULA);
  }

  @Test
  @DisplayName("close is already complete when no save is pending")
  void idleCloseCompletesImmediately() {
    ThemePreferenceWriter writer =
        new ThemePreferenceWriter(new ThemeSettingsStore(settings), Runnable::run);

    assertThat(writer.closeAsync().toCompletableFuture()).isCompleted();
  }

  @Test
  @DisplayName("close completes only after an in-flight save finishes")
  void closeWaitsForInFlightSave() throws Exception {
    CountDownLatch replacementStarted = new CountDownLatch(1);
    CountDownLatch allowReplacement = new CountDownLatch(1);
    ThemeSettingsStore store =
        new ThemeSettingsStore(
            settings,
            (temporary, destination) -> {
              replacementStarted.countDown();
              try {
                if (!allowReplacement.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("test timed out waiting to release the save");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test save was interrupted", interrupted);
              }
              Files.move(temporary, destination);
            });
    ExecutorService background = Executors.newSingleThreadExecutor();
    try {
      ThemePreferenceWriter writer = new ThemePreferenceWriter(store, background);
      writer.save(AppTheme.MACOS_DARK);
      assertThat(replacementStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var closing = writer.closeAsync().toCompletableFuture();
      assertThat(closing).isNotDone();

      allowReplacement.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertThat(store.load()).isEqualTo(AppTheme.MACOS_DARK);
    } finally {
      allowReplacement.countDown();
      background.shutdownNow();
    }
  }

  @Test
  @DisplayName("a failed save is reported to the owner")
  void reportsPersistenceFailure() {
    Queue<Runnable> backgroundTasks = new ArrayDeque<>();
    ThemeSettingsStore store =
        new ThemeSettingsStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("disk is read-only");
            });
    AtomicReference<ThemePreferenceWriter.SaveFailure> reported = new AtomicReference<>();
    ThemePreferenceWriter writer =
        new ThemePreferenceWriter(store, backgroundTasks::add, reported::set);

    writer.save(AppTheme.INTELLIJ_LIGHT);
    backgroundTasks.remove().run();

    assertThat(reported.get()).isNotNull();
    assertThat(reported.get().theme()).isEqualTo(AppTheme.INTELLIJ_LIGHT);
    assertThat(reported.get().cause().getMessage())
        .contains("Appearance settings could not be saved")
        .contains(store.file().toString());
  }
}

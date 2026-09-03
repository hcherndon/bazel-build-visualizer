package com.holtherndon.bazelviz.ui.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoggingSettingsStoreTest {

  @TempDir Path settings;

  @Test
  @DisplayName("missing settings use Info without creating a file")
  void missingUsesDefault() {
    LoggingSettingsStore store = new LoggingSettingsStore(settings);

    LoggingSettingsStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.verbosity()).isEqualTo(LogVerbosity.INFO);
    assertThat(loaded.warning()).isEmpty();
    assertThat(store.load()).isEqualTo(LogVerbosity.INFO);
    assertThat(store.file()).isEqualTo(settings.resolve("logging.properties"));
    assertThat(store.file()).doesNotExist();
  }

  @Test
  @DisplayName("every logging level round-trips through the atomic settings file")
  void everyVerbosityRoundTrips() {
    for (LogVerbosity verbosity : LogVerbosity.values()) {
      LoggingSettingsStore store = new LoggingSettingsStore(settings);
      assertThat(store.save(verbosity)).isTrue();
      LoggingSettingsStore.LoadResult loaded =
          new LoggingSettingsStore(settings).loadWithDiagnostics();
      assertThat(loaded.verbosity()).isEqualTo(verbosity);
      assertThat(loaded.warning()).isEmpty();
    }
  }

  @Test
  @DisplayName("unknown and malformed settings recover to Info")
  void malformedSettingsUseDefault() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("logging.properties");

    Files.writeString(file, "format=2\nverbosity=trace\n", StandardCharsets.UTF_8);
    assertMalformedWarning(file);

    Files.writeString(file, "format=1\nverbosity=verbose\n", StandardCharsets.UTF_8);
    assertMalformedWarning(file);

    Files.writeString(file, "format=1\n", StandardCharsets.UTF_8);
    assertMalformedWarning(file);

    Files.writeString(file, "format=1\nverbosity=\\uNOTA\n", StandardCharsets.UTF_8);
    assertMalformedWarning(file);
  }

  @Test
  @DisplayName("an I/O failure returns a safe diagnostic without exception details")
  void ioFailureUsesSafeDiagnostic() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("logging.properties");
    Files.writeString(file, "format=1\nverbosity=debug\n", StandardCharsets.UTF_8);
    LoggingSettingsStore store =
        new LoggingSettingsStore(
            settings,
            (temporary, destination) -> {},
            ignored -> {
              throw new IOException("secret file content: do not expose");
            });

    LoggingSettingsStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.verbosity()).isEqualTo(LogVerbosity.INFO);
    assertThat(loaded.warning())
        .hasValue("Saved logging settings could not be read; Info will be used.");
    assertThat(loaded.warning().orElseThrow())
        .doesNotContain("secret file content")
        .doesNotContain(file.toString());
  }

  @Test
  @DisplayName("a failed atomic replacement preserves the previous choice and removes temp files")
  void failedReplacementPreservesPreviousFile() throws Exception {
    LoggingSettingsStore working = new LoggingSettingsStore(settings);
    assertThat(working.save(LogVerbosity.DEBUG)).isTrue();
    LoggingSettingsStore failing =
        new LoggingSettingsStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("disk is read-only");
            });

    assertThat(failing.save(LogVerbosity.TRACE)).isFalse();
    assertThat(new LoggingSettingsStore(settings).load()).isEqualTo(LogVerbosity.DEBUG);
    try (var children = Files.list(settings)) {
      assertThat(children.map(path -> path.getFileName().toString()))
          .containsExactly("logging.properties");
    }
  }

  @Test
  @DisplayName("settings reads and writes reject the Swing EDT")
  void rejectsEdtIo() throws Exception {
    LoggingSettingsStore store = new LoggingSettingsStore(settings);

    SwingUtilities.invokeAndWait(
        () -> {
          assertThatThrownBy(store::load)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThatThrownBy(() -> store.save(LogVerbosity.WARN))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
        });
  }

  private void assertMalformedWarning(Path file) {
    LoggingSettingsStore.LoadResult loaded =
        new LoggingSettingsStore(settings).loadWithDiagnostics();
    assertThat(loaded.verbosity()).isEqualTo(LogVerbosity.INFO);
    assertThat(loaded.warning()).hasValue("Saved logging settings are invalid; Info will be used.");
    assertThat(loaded.warning().orElseThrow())
        .doesNotContain(file.toString())
        .doesNotContain("verbosity=");
  }
}

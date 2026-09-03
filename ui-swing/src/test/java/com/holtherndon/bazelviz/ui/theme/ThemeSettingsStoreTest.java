package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ThemeSettingsStoreTest {

  @TempDir Path settings;

  @Test
  @DisplayName("an absent appearance file preserves the existing light default")
  void absentFileUsesLight() {
    ThemeSettingsStore store = new ThemeSettingsStore(settings);

    assertThat(store.load()).isEqualTo(AppTheme.LIGHT);
    assertThat(store.file()).isEqualTo(settings.resolve("appearance.properties"));
    assertThat(store.file()).doesNotExist();
  }

  @Test
  @DisplayName("every theme survives an application restart")
  void everyThemeRoundTrips() {
    for (AppTheme theme : AppTheme.values()) {
      assertThat(new ThemeSettingsStore(settings).save(theme)).isTrue();
      assertThat(new ThemeSettingsStore(settings).load()).isEqualTo(theme);
    }
  }

  @Test
  @DisplayName("unknown or malformed settings recover to Light")
  void malformedStateRecovers() throws IOException {
    Files.createDirectories(settings);
    Path file = settings.resolve("appearance.properties");

    Files.writeString(file, "format=2\ntheme=dark\n");
    assertThat(new ThemeSettingsStore(settings).load()).isEqualTo(AppTheme.LIGHT);

    Files.writeString(file, "format=1\ntheme=not-a-theme\n");
    assertThat(new ThemeSettingsStore(settings).load()).isEqualTo(AppTheme.LIGHT);

    Files.writeString(file, "this is not properties state\n");
    assertThat(new ThemeSettingsStore(settings).load()).isEqualTo(AppTheme.LIGHT);
  }

  @Test
  @DisplayName("a failed atomic replacement keeps the previous preference and no temp file")
  void failedReplacementKeepsPreviousState() throws IOException {
    ThemeSettingsStore working = new ThemeSettingsStore(settings);
    assertThat(working.save(AppTheme.DARK)).isTrue();
    ThemeSettingsStore failing =
        new ThemeSettingsStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });

    assertThat(failing.save(AppTheme.MACOS_LIGHT)).isFalse();

    assertThat(new ThemeSettingsStore(settings).load()).isEqualTo(AppTheme.DARK);
    try (var children = Files.list(settings)) {
      assertThat(children.map(path -> path.getFileName().toString()))
          .containsExactly("appearance.properties");
    }
  }

  @Test
  @DisplayName("appearance file I/O is never allowed on the EDT")
  void edtIoIsRejected() throws Exception {
    ThemeSettingsStore store = new ThemeSettingsStore(settings);

    SwingUtilities.invokeAndWait(
        () -> {
          assertThatThrownBy(store::load)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThatThrownBy(() -> store.save(AppTheme.DARK))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
        });
  }
}

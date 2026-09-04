package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.MainWindow;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AppInfoTest {

  @Test
  void releaseVersionHasNoDevelopmentSuffix() {
    assertThat(AppInfo.VERSION).isEqualTo("0.1.0");
  }

  @Test
  void releaseMetadataUsesTheApplicationVersionEverywhere() throws Exception {
    assertThat(Files.readString(runfile("app/packaging/jpackage.sh")))
        .contains("PROJECT_VERSION=\"" + AppInfo.VERSION + "\"");
    assertThat(Files.readString(runfile("app/src/main/packaging/Info.plist")))
        .contains(
            "<key>CFBundleShortVersionString</key>\n  <string>" + AppInfo.VERSION + "</string>")
        .doesNotContain("LSMinimumSystemVersion");

    Field uiVersion = MainWindow.class.getDeclaredField("APP_VERSION");
    uiVersion.setAccessible(true);
    assertThat(uiVersion.get(null)).isEqualTo(AppInfo.VERSION);
  }

  private static Path runfile(String path) {
    return Path.of(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"), path);
  }
}

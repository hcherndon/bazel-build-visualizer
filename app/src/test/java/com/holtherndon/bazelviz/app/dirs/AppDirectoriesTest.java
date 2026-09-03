package com.holtherndon.bazelviz.app.dirs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDirectoriesTest {

  @TempDir Path temp;

  private Path home() {
    return temp.resolve("home");
  }

  @Test
  void macosResolvesUnderLibraryApplicationSupport() {
    AppDirectories dirs = new AppDirectories(null, "Mac OS X", Map.of(), home());

    assertThat(dirs.rootPath())
        .isEqualTo(
            home()
                .resolve("Library")
                .resolve("Application Support")
                .resolve("BazelBuildVisualizer"));
  }

  @Test
  void linuxPrefersXdgDataHome() {
    Path xdg = temp.resolve("xdg-data");
    AppDirectories dirs =
        new AppDirectories(null, "Linux", Map.of("XDG_DATA_HOME", xdg.toString()), home());

    assertThat(dirs.rootPath()).isEqualTo(xdg.resolve("bazel-build-visualizer"));
  }

  @Test
  void linuxFallsBackToLocalShareWhenXdgAbsentOrBlank() {
    Path expected = home().resolve(".local").resolve("share").resolve("bazel-build-visualizer");

    assertThat(new AppDirectories(null, "Linux", Map.of(), home()).rootPath()).isEqualTo(expected);
    assertThat(new AppDirectories(null, "Linux", Map.of("XDG_DATA_HOME", "  "), home()).rootPath())
        .isEqualTo(expected);
  }

  @Test
  void windowsResolvesUnderAppData() {
    Path appData = temp.resolve("Roaming");
    AppDirectories dirs =
        new AppDirectories(null, "Windows 11", Map.of("APPDATA", appData.toString()), home());

    assertThat(dirs.rootPath()).isEqualTo(appData.resolve("BazelBuildVisualizer"));
  }

  @Test
  void windowsFallsBackToHomeAppDataRoamingWhenEnvAbsent() {
    AppDirectories dirs = new AppDirectories(null, "Windows 11", Map.of(), home());

    assertThat(dirs.rootPath())
        .isEqualTo(home().resolve("AppData").resolve("Roaming").resolve("BazelBuildVisualizer"));
  }

  @Test
  void overrideWinsOnEveryPlatform() {
    Path override = temp.resolve("override-root");
    Map<String, String> env =
        Map.of(
            "XDG_DATA_HOME", temp.resolve("xdg").toString(),
            "APPDATA", temp.resolve("appdata").toString());

    for (String osName : List.of("Mac OS X", "Linux", "Windows 11")) {
      AppDirectories dirs = new AppDirectories(override, osName, env, home());
      assertThat(dirs.rootPath()).as(osName).isEqualTo(override);
    }
  }

  @Test
  void nothingIsCreatedUntilAnAccessorIsCalled() {
    AppDirectories dirs = new AppDirectories(temp.resolve("lazy"), "Linux", Map.of(), home());

    assertThat(dirs.rootPath()).doesNotExist();

    Path catalog = dirs.catalog();

    assertThat(catalog).isDirectory();
    assertThat(dirs.rootPath()).isDirectory();
  }

  @Test
  void eachAccessorCreatesItsDirectory() {
    AppDirectories dirs = new AppDirectories(temp.resolve("acc"), "Linux", Map.of(), home());

    assertThat(dirs.root()).isDirectory();
    assertThat(dirs.catalog()).isDirectory().hasFileName("catalog");
    assertThat(dirs.managedSessions()).isDirectory().hasFileName("managed-sessions");
    assertThat(dirs.importCache()).isDirectory().hasFileName("import-cache");
    assertThat(dirs.temporaryCaptures()).isDirectory().hasFileName("temporary-captures");
    assertThat(dirs.settings()).isDirectory().hasFileName("settings");
    assertThat(dirs.logs()).isDirectory().hasFileName("logs");
  }

  @Test
  void subdirectorySetExactlyMatchesThePlanList() throws IOException {
    AppDirectories dirs = new AppDirectories(temp.resolve("all"), "Linux", Map.of(), home());

    dirs.ensureAll();

    try (Stream<Path> children = Files.list(dirs.rootPath())) {
      assertThat(children.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder(
              "catalog",
              "managed-sessions",
              "import-cache",
              "temporary-captures",
              "settings",
              "logs");
    }
  }
}

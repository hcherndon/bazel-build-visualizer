package com.holtherndon.bazelviz.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import com.holtherndon.bazelviz.testsupport.bep.BepFixtures;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the packaged entry point and Logback resource in a separate JVM. */
final class CliLoggingProcessTest {

  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);

  @TempDir Path temporary;

  @Test
  void debugLogsStayOnStderrAndHeadlessStartupCreatesNoApplicationDirectory() throws Exception {
    ProcessResult result = runImport("DEBUG", "debug");

    assertThat(result.exitCode()).as("stderr:%n%s", result.stderr()).isZero();
    assertImportJson(result.stdout());
    assertThat(result.stdout()).doesNotContain("DEBUG").doesNotContain("source path is");
    assertThat(result.stderr()).contains("DEBUG").contains("source path is");
    assertThat(result.applicationDirectory()).doesNotExist();
  }

  @Test
  void invalidLevelWarnsAndCannotEnableDebug() throws Exception {
    ProcessResult result = runImport("everything", "invalid");

    assertThat(result.exitCode()).as("stderr:%n%s", result.stderr()).isZero();
    assertImportJson(result.stdout());
    assertThat(result.stderr())
        .contains("bbv: invalid bbv.log.level; using warn")
        .doesNotContain("DEBUG")
        .doesNotContain("source path is");
    assertThat(result.applicationDirectory()).doesNotExist();
  }

  @Test
  void packagedConfigurationKeepsDependenciesAtWarn() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      var entry = jar.getEntry("logback.xml");
      assertThat(entry).as("packaged logback.xml").isNotNull();
      String configuration =
          new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);

      assertThat(configuration)
          .contains(
              "<logger name=\"com.holtherndon.bazelviz\"" + " level=\"${bbv.log.level:-WARN}\"/>")
          .contains("<root level=\"WARN\">")
          .doesNotContain("<root level=\"${bbv.log.level");
    }
  }

  private ProcessResult runImport(String level, String name) throws Exception {
    Path fixtureDirectory = temporary.resolve(name + "-fixture");
    Path source = BepFixtures.writeAll(fixtureDirectory, SyntheticBepStream.of(16)).binary();
    Path sessions = temporary.resolve(name + "-sessions");
    Path applicationDirectory = temporary.resolve(name + "-application");
    Path stdout = temporary.resolve(name + "-stdout.txt");
    Path stderr = temporary.resolve(name + "-stderr.txt");

    List<String> command = new ArrayList<>();
    command.add(javaExecutable().toString());
    command.add("-Xmx512m");
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-Djava.awt.headless=true");
    command.add("-Dbbv.log.level=" + level);
    command.add("-Dbbv.appdir=" + applicationDirectory);
    command.add("-jar");
    command.add(deployJar().toString());
    command.addAll(
        List.of(
            "import",
            source.toString(),
            "--sessions-root",
            sessions.toString(),
            "--quiet",
            "--json"));

    Process process =
        new ProcessBuilder(command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      throw new AssertionError("packaged CLI did not finish within " + PROCESS_TIMEOUT);
    }
    return new ProcessResult(
        process.exitValue(),
        Files.readString(stdout, StandardCharsets.UTF_8),
        Files.readString(stderr, StandardCharsets.UTF_8),
        applicationDirectory);
  }

  private static void assertImportJson(String stdout) {
    JsonObject summary = (JsonObject) JsonReader.parse(stdout);
    assertThat(summary.member("command")).contains(new JsonString("import"));
    assertThat(summary.member("outcome")).contains(new JsonString("COMPLETE"));
  }

  private static Path deployJar() {
    String workspace = System.getenv().getOrDefault("TEST_WORKSPACE", "_main");
    return Path.of(System.getenv("TEST_SRCDIR"), workspace, "app", "app_deploy.jar");
  }

  private static Path javaExecutable() {
    String executable =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe"
            : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable);
  }

  private record ProcessResult(
      int exitCode, String stdout, String stderr, Path applicationDirectory) {}
}

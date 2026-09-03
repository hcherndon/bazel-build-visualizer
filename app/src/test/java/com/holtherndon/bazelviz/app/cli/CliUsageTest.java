package com.holtherndon.bazelviz.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the tool says when it is asked for something it cannot do.
 *
 * <p>Every case here has the same two requirements: a non-zero exit so a script notices, and a
 * sentence a person can act on instead of a stack trace. A trace for a mistyped flag tells the user
 * nothing they can use and buries the one line that would have.
 */
class CliUsageTest {

  @TempDir Path workspace;

  private final CliHarness cli = new CliHarness();

  @Test
  void anUnknownOptionIsRefusedWithASuggestion() throws IOException {
    Path source = Files.createFile(workspace.resolve("build.bep"));

    CliHarness.Result result =
        cli.run(
            "import",
            source.toString(),
            "--sessions-root",
            workspace.resolve("sessions").toString(),
            "--jsn");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err())
        .contains("unknown option '--jsn'")
        .contains("did you mean '--json'?")
        .contains("run 'bbv import --help' for usage");
    assertThatNoStackTraceWasPrinted(result);
    assertThat(result.out()).isEmpty();
  }

  @Test
  void anUnknownOptionWithNoNearMissJustSaysSo() throws IOException {
    Path source = Files.createFile(workspace.resolve("build.bep"));

    CliHarness.Result result = cli.run("import", source.toString(), "--frobnicate");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("unknown option '--frobnicate'");
    assertThat(result.err()).doesNotContain("did you mean");
    assertThatNoStackTraceWasPrinted(result);
  }

  @Test
  void aMissingFileIsReportedByName() {
    Path missing = workspace.resolve("not-there.bep");

    CliHarness.Result result =
        cli.run(
            "import", missing.toString(),
            "--sessions-root", workspace.resolve("sessions").toString());

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("no such file: " + missing);
    assertThatNoStackTraceWasPrinted(result);
  }

  @Test
  void noFileAtAllIsReported() {
    CliHarness.Result result = cli.run("import");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("no BEP file given");
    assertThatNoStackTraceWasPrinted(result);
  }

  @Test
  void anOptionThatNeedsAValueSaysSo() throws IOException {
    Path source = Files.createFile(workspace.resolve("build.bep"));

    CliHarness.Result result = cli.run("import", source.toString(), "--sessions-root");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("option '--sessions-root' requires a value");
  }

  @Test
  void aSwitchGivenAValueSaysSo() throws IOException {
    Path source = Files.createFile(workspace.resolve("build.bep"));

    CliHarness.Result result = cli.run("import", source.toString(), "--json=yes");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("option '--json' is a switch and does not take a value");
  }

  @Test
  void anUnknownCommandListsTheRealOnes() {
    CliHarness.Result result = cli.run("improt", "x");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err())
        .contains("unknown command 'improt'")
        .contains("'import' and 'inspect'");
    assertThatNoStackTraceWasPrinted(result);
  }

  @Test
  void inspectingSomethingThatIsNotASessionIsRefused() throws IOException {
    Path directory = Files.createDirectory(workspace.resolve("elsewhere"));

    CliHarness.Result result = cli.run("inspect", directory.toString());

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("is not a managed session").contains("manifest.json");
    assertThatNoStackTraceWasPrinted(result);
  }

  @Test
  void importingADirectoryPointsAtTheOtherCommand() throws IOException {
    Path directory = Files.createDirectory(workspace.resolve("a-directory"));

    CliHarness.Result result = cli.run("import", directory.toString());

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("is a directory").contains("bbv inspect");
  }

  @Test
  void inspectRefusesTwoViewsAtOnce() throws IOException {
    Path directory = Files.createDirectory(workspace.resolve("session-ish"));
    Files.writeString(directory.resolve("manifest.json"), "{}");

    CliHarness.Result result =
        cli.run("inspect", directory.toString(), "--event", "1", "--diagnostics");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("--event and --diagnostics");
  }

  @Test
  void helpDocumentsTheExitCodes() {
    CliHarness.Result top = cli.run("--help");
    assertThat(top.code()).isZero();
    assertThat(top.out())
        .contains("bbv import <bep-file>")
        .contains("bbv inspect <session-dir>")
        .contains("exit codes")
        .contains("  0  success")
        .contains("  4  interrupted");

    CliHarness.Result importHelp = cli.run("import", "--help");
    assertThat(importHelp.code()).isZero();
    assertThat(importHelp.out())
        .contains("usage: bbv import <bep-file> [options]")
        .contains("--sessions-root")
        .contains("--name")
        .contains("--resume")
        .contains("--json")
        .contains("--quiet")
        .contains("exit codes:");

    CliHarness.Result inspectHelp = cli.run("inspect", "--help");
    assertThat(inspectHelp.code()).isZero();
    assertThat(inspectHelp.out())
        .contains("usage: bbv inspect <session-dir> [options]")
        .contains("--events N")
        .contains("--event ID")
        .contains("--diagnostics")
        .contains("--json")
        .contains("exit codes:");
  }

  @Test
  void versionPrintsTheApplicationVersion() {
    CliHarness.Result result = cli.run("--version");

    assertThat(result.code()).isZero();
    assertThat(result.out().trim()).isEqualTo("bbv " + CliHarness.APP_VERSION);
  }

  @Test
  void resumingWithNothingToResumeExplainsWhatToDoInstead() throws IOException {
    Path source = Files.createFile(workspace.resolve("build.bep"));

    CliHarness.Result result =
        cli.run(
            "import",
            source.toString(),
            "--sessions-root",
            workspace.resolve("sessions").toString(),
            "--resume");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err())
        .contains("no interrupted import of " + source)
        .contains("without --resume");
  }

  private static void assertThatNoStackTraceWasPrinted(CliHarness.Result result) {
    assertThat(result.err())
        .as("a usage mistake gets a sentence, not a trace")
        .doesNotContain("\tat ")
        .doesNotContain("Exception");
  }
}

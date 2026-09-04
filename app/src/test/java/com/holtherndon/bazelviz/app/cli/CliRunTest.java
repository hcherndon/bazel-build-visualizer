package com.holtherndon.bazelviz.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.live.BuildOutcome;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code bbv run}: the headless face of Phase 2 — the hermetic half.
 *
 * <p>The three methods that launch a real host Bazel live in {@link RealBazelCliRunTest}, split out
 * during the Bazel migration so their un-sandboxed, environment-inheriting test target cannot
 * dilute the treatment of these plain argument-contract tests.
 */
class CliRunTest {

  private final CliHarness cli = new CliHarness();

  @Test
  @DisplayName("a Bazel command is required, and the message says where it goes")
  void missingCommandIsAUsageError(@TempDir Path directory) {
    CliHarness.Result result = cli.run("run", "--sessions-root=" + directory);

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("after '--'").contains("bbv run -- build //...");
  }

  @Test
  @DisplayName("--replace-bes and --keep-bes cannot both be given")
  void contradictoryResolutionsAreRefused(@TempDir Path directory) {
    CliHarness.Result result =
        cli.run(
            "run",
            "--sessions-root=" + directory,
            "--replace-bes",
            "--keep-bes",
            "--",
            "build",
            "//...");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("opposite things");
  }

  @Test
  @DisplayName("an unknown preset lists the ones that exist")
  void unknownPresetIsRefused(@TempDir Path directory) {
    CliHarness.Result result =
        cli.run(
            "run", "--sessions-root=" + directory, "--preset=everything", "--", "build", "//...");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err())
        .contains("unknown preset 'everything'")
        .contains("live-essentials")
        .contains("performance-diagnostics");
  }

  @Test
  @DisplayName("a Bazel that is not there is a usage error, not a stack trace")
  void missingBazelIsAUsageError(@TempDir Path directory) {
    CliHarness.Result result =
        cli.run(
            "run",
            "--sessions-root=" + directory,
            "--bazel=/nonexistent/bazel",
            "--cwd=" + directory,
            "--",
            "build",
            "//...");

    assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(result.err()).contains("/nonexistent/bazel");
    assertThat(result.err()).doesNotContain("\tat ");
  }

  @Test
  @DisplayName("the help names the exit codes and where the Bazel command goes")
  void helpExplainsTheContract() {
    CliHarness.Result result = cli.run("run", "--help");

    assertThat(result.code()).isZero();
    assertThat(result.out())
        .contains("bbv run [options] -- <bazel command>")
        .contains("describes the capture, not the build");
  }

  @Test
  @DisplayName("unknown process and BES outcomes are never described as build failures")
  void unknownOutcomesStayUnknownInHumanOutput() {
    ProcessOutcome missingExit =
        new ProcessOutcome(OptionalInt.empty(), Optional.empty(), Duration.ZERO, Optional.empty());
    ProcessOutcome besFailure = ProcessOutcome.exited(38, Duration.ZERO);

    assertThat(RunCommand.describeBuild(BuildOutcome.UNKNOWN_PROCESS, missingExit))
        .contains("outcome unknown")
        .doesNotContain("build failed");
    assertThat(RunCommand.describeBuild(BuildOutcome.UNKNOWN_BES_TRANSPORT, besFailure))
        .contains("outcome unknown")
        .doesNotContain("build failed");
  }
}

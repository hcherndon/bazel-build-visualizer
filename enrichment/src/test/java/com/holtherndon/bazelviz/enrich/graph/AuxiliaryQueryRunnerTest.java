package com.holtherndon.bazelviz.enrich.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuxiliaryQueryRunnerTest {

  @Test
  @DisplayName("a remote executor receives executor-owned paths and writes bytes locally")
  void transportNeutralQueryPreservesRequestAndBinaryOutput(@TempDir Path local) throws Exception {
    Path remoteWorkingDirectory = Path.of("/remote/work tree");
    Map<String, Optional<String>> environment =
        Map.of(
            "USE_BAZEL_VERSION", Optional.of("9.2.0"),
            "REMOVE_ME", Optional.empty());
    BazelCommand command =
        BazelCommand.builder(Path.of("/remote/bin/bazel"), remoteWorkingDirectory)
            .command("aquery")
            .targets(List.of("//pkg:all"))
            .environmentOverrides(environment)
            .inheritance(EnvironmentInheritance.INHERIT_ALLOWLISTED)
            .build();
    Path planned = local.resolve("planned.proto");
    AuxiliaryQueryPlanner.Plan plan =
        new AuxiliaryQueryPlanner.Plan(command, planned, List.of(), List.of(), Optional.empty());
    byte[] protobuf = {0, 1, (byte) 0xff, 2};
    FakeExecutor executor = FakeExecutor.success(protobuf);
    Path explicitOutput = local.resolve("nested/remote-aquery.proto");

    AuxiliaryQueryRunner.Result result =
        new AuxiliaryQueryRunner(Duration.ofSeconds(17)).run(plan, executor, explicitOutput);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.output()).isEqualTo(explicitOutput);
    assertThat(Files.readAllBytes(explicitOutput)).containsExactly(protobuf);
    assertThat(executor.request.get().argv()).isEqualTo(command.toArgv());
    assertThat(executor.request.get().workingDirectory())
        .contains(remoteWorkingDirectory.toString());
    assertThat(executor.request.get().environmentOverrides()).isEqualTo(environment);
    assertThat(executor.request.get().environment())
        .isEqualTo(RuntimeEnvironment.INHERIT_ESSENTIAL);
    assertThat(executor.request.get().forceTty()).isFalse();
    assertThat(executor.timeout.get()).isEqualTo(Duration.ofSeconds(17));
    assertThat(executor.output.get()).isEqualTo(explicitOutput);
  }

  @Test
  @DisplayName("query failures retain an empty local output and bounded stderr")
  void failureRemainsNonFatalAndInspectable(@TempDir Path local) throws Exception {
    AuxiliaryQueryPlanner.Plan plan = plan(local, EnvironmentInheritance.NONE);
    String stderr = "problem ".repeat(1_000);
    FakeExecutor executor = FakeExecutor.result(new CommandResult(23, "", stderr, false));
    Path output = local.resolve("failed.proto");

    AuxiliaryQueryRunner.Result result = new AuxiliaryQueryRunner().run(plan, executor, output);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(23);
    assertThat(result.error().orElseThrow())
        .hasSize(AuxiliaryQueryRunner.STDERR_LIMIT + "\n… (truncated)".length())
        .endsWith("… (truncated)");
    assertThat(Files.exists(output)).isTrue();
    assertThat(Files.size(output)).isZero();
    assertThat(executor.request.get().environment()).isEqualTo(RuntimeEnvironment.NONE);
  }

  @Test
  @DisplayName("timeouts and interruption keep the established result semantics")
  void timeoutAndInterruptionAreResults(@TempDir Path local) {
    AuxiliaryQueryPlanner.Plan plan = plan(local, EnvironmentInheritance.INHERIT_ALL);
    AuxiliaryQueryRunner runner = new AuxiliaryQueryRunner(Duration.ofMinutes(3));
    AuxiliaryQueryRunner.Result timedOut =
        runner.run(
            plan,
            FakeExecutor.result(new CommandResult(-1, "", "still running", true)),
            local.resolve("timeout.proto"));

    assertThat(timedOut.succeeded()).isFalse();
    assertThat(timedOut.exitCode()).isEqualTo(-1);
    assertThat(timedOut.error()).contains("the query did not finish within 3 minutes");

    try {
      AuxiliaryQueryRunner.Result interrupted =
          runner.run(plan, FakeExecutor.interrupted(), local.resolve("interrupted.proto"));
      assertThat(interrupted.succeeded()).isFalse();
      assertThat(interrupted.error()).contains("the query was interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("the original run method remains a local executor compatibility path")
  void localCompatibility(@TempDir Path local) throws Exception {
    BazelCommand command =
        BazelCommand.builder(Path.of("/bin/echo"), local)
            .commandArgs(List.of("query-proto"))
            .build();
    Path output = local.resolve("local.proto");
    AuxiliaryQueryPlanner.Plan plan =
        new AuxiliaryQueryPlanner.Plan(command, output, List.of(), List.of(), Optional.empty());

    AuxiliaryQueryRunner.Result result = new AuxiliaryQueryRunner().run(plan);

    assertThat(result.succeeded()).isTrue();
    assertThat(Files.readString(output)).isEqualTo("query-proto\n");
  }

  private static AuxiliaryQueryPlanner.Plan plan(Path local, EnvironmentInheritance inheritance) {
    BazelCommand command =
        BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/workspace"))
            .command("cquery")
            .targets(List.of("//pkg:target"))
            .inheritance(inheritance)
            .build();
    return new AuxiliaryQueryPlanner.Plan(
        command, local.resolve("planned.proto"), List.of(), List.of(), Optional.empty());
  }

  private static final class FakeExecutor implements CommandExecutor {
    private final CommandResult result;
    private final byte[] stdout;
    private final boolean interrupt;
    private final AtomicReference<CommandRequest> request = new AtomicReference<>();
    private final AtomicReference<Duration> timeout = new AtomicReference<>();
    private final AtomicReference<Path> output = new AtomicReference<>();

    private FakeExecutor(CommandResult result, byte[] stdout, boolean interrupt) {
      this.result = result;
      this.stdout = stdout.clone();
      this.interrupt = interrupt;
    }

    static FakeExecutor success(byte[] stdout) {
      return new FakeExecutor(new CommandResult(0, "", "", false), stdout, false);
    }

    static FakeExecutor result(CommandResult result) {
      return new FakeExecutor(result, new byte[0], false);
    }

    static FakeExecutor interrupted() {
      return new FakeExecutor(new CommandResult(-1, "", "", false), new byte[0], true);
    }

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) throws InterruptedException {
      if (interrupt) {
        throw new InterruptedException("test interruption");
      }
      return result;
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile)
        throws IOException, InterruptedException {
      this.request.set(request);
      this.timeout.set(timeout);
      this.output.set(localOutputFile);
      if (interrupt) {
        throw new InterruptedException("test interruption");
      }
      if (result.isSuccess()) {
        Files.write(localOutputFile, stdout);
      }
      return result;
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) {
      throw new UnsupportedOperationException("not used by this test");
    }
  }
}

package com.holtherndon.bazelviz.runner.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EffectiveOptionsExecutorTest {

  @Test
  void usesExecutorOwnedPathTextAndParsesBothChannels() {
    RecordingExecutor executor =
        new RecordingExecutor(
            new CommandResult(
                0,
                "--remote_download_outputs=toplevel\n",
                "Inherited 'build' options: --keep_going --isatty=1\n",
                false));
    BazelCommand command =
        BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/repo"))
            .startupArgs(List.of("--output_base=/remote/output"))
            .command("build")
            .commandArgs(List.of("--config=ci"))
            .build();

    assertThat(EffectiveOptions.resolve("/remote/bin/bazel", "/remote/repo", command, executor))
        .contains(List.of("--keep_going", "--remote_download_outputs=toplevel"));
    assertThat(executor.request.workingDirectory()).contains("/remote/repo");
    assertThat(executor.request.argv())
        .containsExactly(
            "/remote/bin/bazel",
            "--output_base=/remote/output",
            "canonicalize-flags",
            "--announce_rc",
            "--for_command=build",
            "--",
            "--config=ci");
  }

  private static final class RecordingExecutor implements CommandExecutor {
    private final CommandResult result;
    private CommandRequest request;

    private RecordingExecutor(CommandResult result) {
      this.result = result;
    }

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      this.request = request;
      return result;
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}

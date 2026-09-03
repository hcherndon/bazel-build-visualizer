package com.holtherndon.bazelviz.runner.caps;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BazelCapabilityDetectorExecutorTest {

  @Test
  void createsScratchAndRunsEveryProbeOnTheExecutorHost() {
    RecordingExecutor executor = new RecordingExecutor();
    BazelExecutable executable =
        new BazelExecutable(
            "bazel",
            Path.of("/remote/bin/bazel"),
            "bazel 9.2.0",
            Optional.empty(),
            Optional.of("9.2.0"),
            Optional.empty(),
            false);

    BazelCapabilities capabilities =
        new BazelCapabilityDetector(executor, Duration.ofSeconds(5))
            .detect(executable, List.of("--host_jvm_args=-Xmx1g"));

    assertThat(capabilities.detection()).isEqualTo(BazelCapabilities.DetectionMethod.HELP_TEXT);
    assertThat(capabilities.flag("bes_backend")).isPresent();
    assertThat(executor.requests.getFirst().argv().getFirst()).isEqualTo("/usr/bin/mktemp");
    assertThat(
            executor.requests.stream()
                .filter(request -> request.argv().contains("help"))
                .allMatch(
                    request ->
                        request
                            .workingDirectory()
                            .equals(Optional.of("/tmp/bbv-bazel-probe.ABCDEF"))))
        .isTrue();
    assertThat(executor.requests.getLast().argv())
        .containsExactly("/bin/rmdir", "--", "/tmp/bbv-bazel-probe.ABCDEF");
  }

  private static final class RecordingExecutor implements CommandExecutor {
    private final List<CommandRequest> requests = new ArrayList<>();

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      requests.add(request);
      if (request.argv().getFirst().equals("/usr/bin/mktemp")) {
        return new CommandResult(0, "/tmp/bbv-bazel-probe.ABCDEF\n", "", false);
      }
      if (request.argv().getFirst().equals("/bin/rmdir")) {
        return new CommandResult(0, "", "", false);
      }
      if (request.argv().contains("flags-as-proto")) {
        return new CommandResult(2, "", "structured output unavailable", false);
      }
      return new CommandResult(
          0, "  --bes_backend (a string)\n  --build_event_binary_file (a path)\n", "", false);
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
    public InteractiveChannel openTerminal(String workingDirectory) {
      throw new UnsupportedOperationException();
    }
  }
}

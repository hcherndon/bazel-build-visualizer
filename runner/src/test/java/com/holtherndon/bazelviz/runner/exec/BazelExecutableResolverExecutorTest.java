package com.holtherndon.bazelviz.runner.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BazelExecutableResolverExecutorTest {

  @Test
  void versionParsingDistinguishesBazelFromItsLauncher() {
    assertThat(
            BazelExecutableResolver.reportedBazelVersion(
                "Bazelisk version: v1.26.0\nbazel 7.4.1\n"))
        .contains("7.4.1");
    assertThat(BazelExecutableResolver.reportedBazelVersion("Build label: 7.4.1-custom\n"))
        .contains("7.4.1-custom");
    assertThat(BazelExecutableResolver.reportedBazelVersion("Bazelisk version: v1.26.0\n"))
        .isEmpty();
    assertThat(BazelExecutableResolver.reportedBazelVersion("")).isEmpty();
  }

  @Test
  void resolvesOnExecutorWithoutTouchingDesktopFilesystem() throws Exception {
    RecordingExecutor executor = new RecordingExecutor();
    BazelExecutable executable =
        BazelExecutableResolver.resolve(
            "team bazel'wrapper", "/srv/repo", Map.of("USE_BAZEL_VERSION", "9.2.0"), executor);

    assertThat(executable.resolved().toString()).isEqualTo("/remote/tools/bazelisk");
    assertThat(executable.bazelVersion()).contains("9.2.0");
    assertThat(executable.sha256()).isEmpty();
    assertThat(executable.isBazelisk()).isTrue();
    assertThat(executor.requests).hasSize(2);
    assertThat(executor.requests.getFirst().workingDirectory()).contains("/srv/repo");
    assertThat(executor.requests.getFirst().argv().getLast())
        .contains("'team bazel'\"'\"'wrapper'");
    assertThat(executor.requests.getLast().argv())
        .containsExactly("/remote/tools/bazelisk", "--version");
    assertThat(executor.requests.getLast().environmentOverrides())
        .containsEntry("USE_BAZEL_VERSION", Optional.of("9.2.0"));
  }

  private static final class RecordingExecutor implements CommandExecutor {
    private final List<CommandRequest> requests = new ArrayList<>();

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      requests.add(request);
      if (requests.size() == 1) {
        return new CommandResult(0, "/remote/tools/bazelisk\n", "", false);
      }
      return new CommandResult(0, "Bazelisk version: v1.26.0\nbazel 9.2.0\n", "", false);
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

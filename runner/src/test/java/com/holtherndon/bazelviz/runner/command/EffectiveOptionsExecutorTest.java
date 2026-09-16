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
            "--config=ci",
            "--announce_rc",
            "--for_command=build",
            "--",
            "--config=ci");
  }

  @Test
  void expandsEqualAndSeparateConfigsBeforeTheCanonicalizeSeparator() {
    RecordingExecutor executor = new RecordingExecutor(new CommandResult(0, "", "", false));
    BazelCommand command =
        command(List.of("--keep_going", "--config=ci", "--config", "remote", "--jobs=2"))
            .toBuilder()
            .argsAfterDoubleDash(List.of("--config=program_argument"))
            .build();

    assertThat(EffectiveOptions.resolve("bazel", "/remote/repo", command, executor)).isPresent();
    assertThat(executor.request.argv())
        .containsExactly(
            "bazel",
            "canonicalize-flags",
            "--config=ci",
            "--config",
            "remote",
            "--announce_rc",
            "--for_command=build",
            "--",
            "--keep_going",
            "--config=ci",
            "--config",
            "remote",
            "--jobs=2");
  }

  @Test
  void preservesTheProbeArgvWhenNoConfigWasSelected() {
    RecordingExecutor executor = new RecordingExecutor(new CommandResult(0, "", "", false));

    assertThat(
            EffectiveOptions.resolve(
                "bazel", "/remote/repo", command(List.of("--keep_going")), executor))
        .contains(List.of());
    assertThat(executor.request.argv())
        .containsExactly(
            "bazel",
            "canonicalize-flags",
            "--announce_rc",
            "--for_command=build",
            "--",
            "--keep_going");
  }

  @Test
  void includesNestedConfigDefinitionsAlongsideInheritedAndTypedOptions() {
    RecordingExecutor executor =
        new RecordingExecutor(
            new CommandResult(
                0,
                "--config=ci\n--jobs=2\n",
                """
                INFO: Options provided by the client:
                  Inherited 'common' options: --isatty=0 --terminal_columns=80
                  Inherited 'build' options: --define=base=present
                INFO: Found applicable config definition build:ci in file /remote/repo/.bazelrc: --config=inner --define=outer=present
                INFO: Found applicable config definition build:inner in file /remote/shared.bazelrc: --strategy_regexp=.*=dynamic --strategy=Genrule=remote
                """,
                false));

    assertThat(
            EffectiveOptions.resolve(
                "bazel", "/remote/repo", command(List.of("--config=ci", "--jobs=2")), executor))
        .contains(
            List.of(
                "--define=base=present",
                "--config=inner",
                "--define=outer=present",
                "--strategy_regexp=.*=dynamic",
                "--strategy=Genrule=remote",
                "--config=ci",
                "--jobs=2"));
  }

  @Test
  void preservesSpaceValuesAndLiteralQuotesAfterBazelRemovesRcQuoting() {
    // Measured with Bazel 9.2: .bazelrc quotes are removed before announce_rc joins the arguments.
    // Re-tokenizing this as shell text loses both spaces and the literal quote characters.
    RecordingExecutor executor =
        new RecordingExecutor(
            new CommandResult(
                0,
                "--action_env=TYPED=trailing space \n",
                """
                  Inherited 'build' options: --action_env=CUSTOM_MESSAGE=one two three --define=quoted_literal=has 'quotes' here
                INFO: Found applicable config definition build:inner in file /remote/repo with spaces/.bazelrc: --strategy_regexp=space here=remote --define separate_value=one
                """,
                false));

    assertThat(
            EffectiveOptions.resolve(
                "bazel", "/remote/repo", command(List.of("--config=inner")), executor))
        .contains(
            List.of(
                "--action_env=CUSTOM_MESSAGE=one two three",
                "--define=quoted_literal=has 'quotes' here",
                "--strategy_regexp=space here=remote",
                "--define",
                "separate_value=one",
                "--action_env=TYPED=trailing space "));
  }

  @Test
  void keepsSplitRcValuesAndShortOptions() {
    RecordingExecutor executor =
        new RecordingExecutor(
            new CommandResult(
                0,
                "",
                "Inherited 'build' options: --action_env CUSTOM_MESSAGE=one two three -c opt\n",
                false));

    assertThat(EffectiveOptions.resolve("bazel", "/remote/repo", command(List.of()), executor))
        .contains(List.of("--action_env", "CUSTOM_MESSAGE=one two three", "-c", "opt"));
  }

  @Test
  void reportsAnUnreadableAnnouncementAsUnavailable() {
    RecordingExecutor executor =
        new RecordingExecutor(
            new CommandResult(
                0,
                "--config=ci\n",
                "INFO: Found applicable config definition build:ci without an option marker\n",
                false));

    assertThat(
            EffectiveOptions.resolve(
                "bazel", "/remote/repo", command(List.of("--config=ci")), executor))
        .isEmpty();
  }

  @Test
  void doesNotUsePartialAnnouncementsFromAFailedOrTimedOutProbe() {
    for (CommandResult result :
        List.of(
            new CommandResult(
                2, "--config=missing\n", "Inherited 'build' options: --jobs=2", false),
            new CommandResult(0, "--config=ci\n", "Inherited 'build' options: --jobs=2", true))) {
      RecordingExecutor executor = new RecordingExecutor(result);

      assertThat(
              EffectiveOptions.resolve(
                  "bazel", "/remote/repo", command(List.of("--config=ci")), executor))
          .isEmpty();
    }
  }

  private static BazelCommand command(List<String> arguments) {
    return BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/repo"))
        .command("build")
        .commandArgs(arguments)
        .build();
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

package com.holtherndon.bazelviz.runner.launch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class ExecutorLaunchTest {

  @Test
  void loggingOperationAllowlistRejectsArbitraryCommandText() {
    assertThat(BazelLauncher.operationForLogging("test")).isEqualTo("test");
    assertThat(BazelLauncher.operationForLogging("//secret/package:target")).isEqualTo("other");
    assertThat(BazelLauncher.operationForLogging(null)).isEqualTo("other");
  }

  @Test
  void passesRemotePathAsTextAndRequestsTty() throws Exception {
    RecordingExecutor executor = new RecordingExecutor();
    BazelCommand command =
        BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/repo"))
            .command("test")
            .targets(List.of("//pkg:all"))
            .build();

    BazelLauncher.BazelProcess process =
        BazelLauncher.start(LaunchRequest.of(command, ConsoleSink.discarding()), executor, true);

    assertThat(executor.request.workingDirectory()).contains("/remote/repo");
    assertThat(executor.request.argv()).containsExactly("/remote/bin/bazel", "test", "//pkg:all");
    assertThat(executor.request.forceTty()).isTrue();
    assertThat(process.pid()).isEqualTo(8123);
    assertThat(process.await().exitCode()).hasValue(0);
  }

  @Test
  void logsOnlySanitizedCommandStructure() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(BazelLauncher.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.TRACE);
    try {
      String argvSecret = "argv-secret-7d190";
      String environmentSecret = "environment-secret-cc913";
      RecordingExecutor executor = new RecordingExecutor();
      BazelCommand command =
          BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/repo"))
              .command("test")
              .commandArgs(
                  List.of(
                      "--remote_header=Authorization=Bearer " + argvSecret,
                      "--define=PASSWORD=" + argvSecret))
              .targets(List.of("//pkg:all"))
              .argsAfterDoubleDash(List.of("--token=" + argvSecret))
              .environmentOverrides(Map.of("API_TOKEN", Optional.of(environmentSecret)))
              .build();

      BazelLauncher.BazelProcess process =
          BazelLauncher.start(LaunchRequest.of(command, ConsoleSink.discarding()), executor, true);
      process.await();
      BazelCommand unsafeOperation =
          BazelCommand.builder(Path.of("/remote/bin/bazel"), Path.of("/remote/repo"))
              .command(argvSecret)
              .build();
      BazelLauncher.start(
              LaunchRequest.of(unsafeOperation, ConsoleSink.discarding()), executor, false)
          .await();

      String rendered =
          events.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.joining("\n"));
      assertThat(rendered)
          .contains("operation=test")
          .contains("operation=other")
          .contains("executor=custom")
          .contains("argumentCount=7")
          .contains("environmentOverrideCount=1")
          .contains("exitCode=0")
          .contains("durationMs=")
          .doesNotContain(argvSecret)
          .doesNotContain(environmentSecret)
          .doesNotContain("Authorization")
          .doesNotContain("remote_header")
          .doesNotContain("PASSWORD")
          .doesNotContain("API_TOKEN");
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previousLevel);
      events.stop();
    }
  }

  private static final class RecordingExecutor implements CommandExecutor {
    private CommandRequest request;

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      this.request = request;
      return new FinishedCommand();
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FinishedCommand implements RunningCommand {
    @Override
    public InputStream stdout() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream stderr() {
      return InputStream.nullInputStream();
    }

    @Override
    public OutputStream stdin() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public boolean streamsMerged() {
      return true;
    }

    @Override
    public long pid() {
      return 8123;
    }

    @Override
    public OptionalLong processGroupId() {
      return OptionalLong.of(8123);
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public boolean waitFor(Duration timeout) {
      return true;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void signal(CancellationMode mode) {}
  }
}

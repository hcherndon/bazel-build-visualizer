package com.holtherndon.bazelviz.runner.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class LocalCommandExecutorTest {

  @TempDir Path temporary;

  @Test
  void preservesArgvWorkingDirectoryAndEnvironment() throws Exception {
    CommandRequest request =
        new CommandRequest(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s|%s|%s' \"$PWD\" \"$VALUE\" \"$1\"",
                "ignored",
                "literal $HOME; value"),
            Optional.of(temporary.toString()),
            Map.of("VALUE", Optional.of("from override")),
            RuntimeEnvironment.INHERIT_ALL,
            false);
    CommandResult result = LocalCommandExecutor.INSTANCE.run(request, Duration.ofSeconds(5));
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.stdout())
        .isEqualTo(temporary.toRealPath() + "|from override|literal $HOME; value");
  }

  @Test
  void binaryRedirectDoesNotDecodeBytes() throws Exception {
    Path output = temporary.resolve("answer.bin");
    CommandResult result =
        LocalCommandExecutor.INSTANCE.runRedirectingStdout(
            CommandRequest.of(List.of("/bin/sh", "-c", "printf '\\001\\377'"), temporary),
            Duration.ofSeconds(5),
            output);
    assertThat(result.isSuccess()).isTrue();
    assertThat(Files.readAllBytes(output)).containsExactly(1, -1);
  }

  @Test
  void timeoutKillsTheProbe() throws Exception {
    CommandResult result =
        LocalCommandExecutor.INSTANCE.run(
            CommandRequest.of(List.of("/bin/sh", "-c", "sleep 10"), temporary),
            Duration.ofMillis(50));
    assertThat(result.timedOut()).isTrue();
    assertThat(result.exitCode()).isEqualTo(-1);
  }

  @Test
  void inheritedPipeAfterRootExitIsReportedIncomplete() {
    assertThatThrownBy(
            () ->
                LocalCommandExecutor.INSTANCE.run(
                    CommandRequest.of(List.of("/bin/sh", "-c", "(sleep 10) & exit 0"), temporary),
                    Duration.ofSeconds(2)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("drained");
  }

  @Test
  void redirectedOutputIsNotReplacedAfterTimeout() throws Exception {
    Path output = temporary.resolve("atomic-output.txt");
    Files.writeString(output, "old");

    CommandResult result =
        LocalCommandExecutor.INSTANCE.runRedirectingStdout(
            CommandRequest.of(List.of("/bin/sh", "-c", "printf new; sleep 10"), temporary),
            Duration.ofMillis(50),
            output);

    assertThat(result.timedOut()).isTrue();
    assertThat(Files.readString(output)).isEqualTo("old");
    try (var files = Files.list(temporary)) {
      assertThat(
              files.filter(
                  path -> path.getFileName().toString().startsWith(".bbv-command-output-")))
          .isEmpty();
    }
  }

  @Test
  void boundedCommandsReceiveEndOfInput() throws Exception {
    CommandResult result =
        LocalCommandExecutor.INSTANCE.run(
            CommandRequest.of(
                List.of("/bin/sh", "-c", "cat >/dev/null; printf reached-eof"), temporary),
            Duration.ofSeconds(2));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.stdout()).isEqualTo("reached-eof");
  }

  @Test
  void interactiveTerminalUsesALoginShellRealPtyWorkingDirectoryAndResize() throws Exception {
    Path shell =
        executable(
            "fake-login-shell",
            """
            #!/bin/sh
            printf 'login=%s\n' "$1"
            printf 'term=%s\n' "$TERM"
            printf 'cwd=%s\n' "$PWD"
            printf 'initial='
            stty size
            IFS= read -r ignored
            printf 'resized='
            stty size
            """);
    Map<String, String> environment = new HashMap<>(System.getenv());

    InteractiveChannel channel =
        LocalCommandExecutor.INSTANCE.openTerminal(
            temporary.toString(), shell.toString(), environment);
    try {
      BufferedReader output =
          new BufferedReader(new InputStreamReader(channel.input(), StandardCharsets.UTF_8));
      assertThat(readLine(output)).isEqualTo("login=-l");
      assertThat(readLine(output)).isEqualTo("term=xterm-256color");
      assertThat(readLine(output)).isEqualTo("cwd=" + temporary.toRealPath());
      assertThat(readLine(output)).isEqualTo("initial=24 80");

      channel.resize(new TerminalSize(132, 43));
      channel.write("report\n");

      assertThat(readLine(output)).isEqualTo("report");
      assertThat(readLine(output)).isEqualTo("resized=43 132");
      assertThat(channel.awaitExit()).isEqualTo(0);
    } finally {
      channel.close();
    }
  }

  @Test
  void closingTerminalForciblyReapsATermIgnoringLocalShell() throws Exception {
    Path shell =
        executable(
            "term-ignoring-login-shell",
            """
            #!/bin/sh
            trap '' TERM HUP
            printf 'ready\n'
            kill -STOP $$
            """);
    InteractiveChannel channel =
        LocalCommandExecutor.INSTANCE.openTerminal(
            temporary.toString(), shell.toString(), System.getenv());
    BufferedReader output =
        new BufferedReader(new InputStreamReader(channel.input(), StandardCharsets.UTF_8));
    assertThat(readLine(output)).isEqualTo("ready");

    long started = System.nanoTime();
    channel.close();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMillis).isLessThan(5_000);
    assertThat(channel.isOpen()).isFalse();
    assertThat(channel.awaitExit()).isNotEqualTo(0);
  }

  @Test
  void terminalLifecycleLogsDoNotContainItsShellOrWorkingDirectory() throws Exception {
    String secret = "local-terminal-secret-86c2f";
    Path directory = Files.createDirectory(temporary.resolve(secret));
    Path shell = executable(secret + "-shell", "#!/bin/sh\nexit 0\n");
    Logger logger = (Logger) LoggerFactory.getLogger(LocalCommandExecutor.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.TRACE);
    try {
      InteractiveChannel channel =
          LocalCommandExecutor.INSTANCE.openTerminal(
              directory.toString(), shell.toString(), System.getenv());
      assertThat(channel.awaitExit()).isEqualTo(0);
      channel.close();

      String rendered =
          events.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .collect(Collectors.joining("\n"));
      assertThat(rendered)
          .contains("local terminal opening")
          .contains("local terminal opened")
          .contains("local terminal closed")
          .contains("processId=", "durationMs=", "exitCode=0")
          .doesNotContain(secret)
          .doesNotContain(directory.toString())
          .doesNotContain(shell.toString());
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previousLevel);
      events.stop();
    }
  }

  @Test
  void rejectsEnvironmentNamesBeforeStartingAnything() {
    assertThatThrownBy(
            () ->
                new CommandRequest(
                    List.of("true"),
                    Optional.empty(),
                    Map.of("BAD-NAME", Optional.of("x")),
                    RuntimeEnvironment.NONE,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid environment name");
  }

  private Path executable(String name, String contents) throws Exception {
    Path path = temporary.resolve(name);
    Files.writeString(path, contents);
    path.toFile().setExecutable(true);
    return path;
  }

  private static String readLine(BufferedReader reader) throws Exception {
    FutureTask<String> read = new FutureTask<>(reader::readLine);
    Thread.ofVirtual().name("bbv-test-local-terminal-read").start(read);
    return read.get(5, TimeUnit.SECONDS);
  }
}

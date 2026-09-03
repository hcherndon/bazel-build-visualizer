package com.holtherndon.bazelviz.runner.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import com.holtherndon.bazelviz.runner.runtime.TerminalSize;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SshRuntimeTest {

  @TempDir Path temporary;

  @Test
  void destinationCannotSmuggleOpenSshOptions() {
    assertThat(SshTarget.of("builder@example.internal", 2222).displayName())
        .isEqualTo("builder@example.internal:2222");
    assertThatThrownBy(() -> SshTarget.of("-oProxyCommand=bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SshTarget.of("host -v")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SshTarget.of("host\nother"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SshTarget.of("builder:password@host"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SshTarget.of("first@second@host"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shellBoundaryQuotesEveryValueLiterally() {
    assertThat(PosixShell.quote("a'b $HOME; touch /tmp/no"))
        .isEqualTo("'a'\"'\"'b $HOME; touch /tmp/no'");
    String command =
        SshCommandExecutor.remoteCommand(
            CommandRequest.of(
                List.of("bazel", "test", "//pkg:has space", "$(false)"), "/repo/it's here"),
            "BBV_PROCESS_abcdef:");
    assertThat(command)
        .startsWith("cd -- '/repo/it'\"'\"'s here' && exec /usr/bin/setsid")
        .contains("'//pkg:has space'")
        .contains("'$(false)'");
  }

  @Test
  void nonTtyWrapperWaitsForTheIsolatedCommandsExitStatus() {
    String command =
        SshCommandExecutor.remoteCommand(
            CommandRequest.of(List.of("/usr/bin/stat", "--", "/missing"), "/repo"),
            "BBV_PROCESS_abcdef:");

    assertThat(command).contains("exec /usr/bin/setsid --wait /bin/sh -c ");
  }

  @Test
  void removalsPrecedeAssignmentsInRemoteEnvCommand() {
    CommandRequest request =
        new CommandRequest(
            List.of("bazel", "build"),
            Optional.of("/repo"),
            Map.of(
                "A_VALUE", Optional.of("literal value"),
                "HOME", Optional.empty(),
                "Z_REMOVE", Optional.empty()),
            RuntimeEnvironment.INHERIT_ESSENTIAL,
            false);
    String command = SshCommandExecutor.remoteCommand(request, "BBV_PROCESS_abc123:");
    assertThat(command.indexOf("/usr/bin/env -i -u HOME -u Z_REMOVE --")).isGreaterThanOrEqualTo(0);
    assertThat(command.indexOf("/usr/bin/env -i -u HOME -u Z_REMOVE --"))
        .isLessThan(command.indexOf("'A_VALUE=literal value'"));
    assertThat(command).doesNotContain("HOME=\"${HOME-}\"");
  }

  @Test
  void environmentAssignmentsFollowTheEnvOptionTerminator() {
    CommandRequest request =
        new CommandRequest(
            List.of("/remote/bin/bazelisk", "help", "flags-as-proto"),
            Optional.of("/tmp/probe"),
            Map.of("USE_BAZEL_VERSION", Optional.of("9.2.0")),
            RuntimeEnvironment.INHERIT_ALL,
            false);

    String command = SshCommandExecutor.remoteCommand(request, "BBV_PROCESS_abc123:");

    int env = command.indexOf("/usr/bin/env");
    int terminator = command.indexOf(" -- ", env);
    int assignment = command.indexOf("USE_BAZEL_VERSION=9.2.0", env);
    int executable = command.indexOf("/remote/bin/bazelisk", env);
    assertThat(env).isGreaterThanOrEqualTo(0);
    assertThat(terminator).isGreaterThan(env);
    assertThat(assignment).isGreaterThan(terminator);
    assertThat(executable).isGreaterThan(assignment);
  }

  @Test
  void markerIsRemovedWithoutDroppingEarlierSshOutput() throws Exception {
    byte[] source =
        ("banner\r\nBBV_PROCESS_abc:431:431\r\npayload").getBytes(StandardCharsets.US_ASCII);
    SshCommandExecutor.Marker marker =
        SshCommandExecutor.readMarkerBytes(new ByteArrayInputStream(source), "BBV_PROCESS_abc:");
    assertThat(marker.pid()).isEqualTo(431);
    assertThat(marker.pgid()).isEqualTo(431);
    assertThat(new String(marker.prefix(), StandardCharsets.US_ASCII)).isEqualTo("banner\r\n");
  }

  @Test
  void nonTtyAndTtyChannelsUseFixedSafeOpenSshArguments() {
    Path ssh = Path.of("/usr/bin/ssh");
    Path socket = Path.of("/private/control");
    SshTarget target = SshTarget.of("build@example.internal", 2201);
    List<String> nonTty =
        SshControlSession.commandArguments(ssh, socket, target, false, "'bazel' '--version'");
    List<String> tty =
        SshControlSession.commandArguments(ssh, socket, target, true, "'bazel' 'test'");

    assertThat(nonTty).contains("-T", "BatchMode=yes", "ClearAllForwardings=yes");
    assertThat(nonTty).doesNotContain("-tt", "StrictHostKeyChecking=no");
    assertThat(tty).contains("-tt", "-e", "none", "ForwardAgent=no", "ForwardX11=no");
    assertThat(tty.getLast()).isEqualTo("'bazel' 'test'");
  }

  @Test
  void interactiveTerminalHasARealResizablePtyAndXtermEnvironment() throws Exception {
    Path fake =
        executable(
            "fake-terminal-ssh",
            """
            #!/bin/sh
            printf 'term=%s\n' "$TERM"
            printf 'initial='
            stty size
            printf 'merged-error\n' >&2
            IFS= read -r ignored
            printf 'resized='
            stty size
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake-host"), fake, temporary.resolve("control"));

    InteractiveChannel channel = executor.openTerminal("/remote/repo");
    try {
      BufferedReader output =
          new BufferedReader(new InputStreamReader(channel.input(), StandardCharsets.UTF_8));
      assertThat(readLine(output)).isEqualTo("term=xterm-256color");
      assertThat(readLine(output)).isEqualTo("initial=24 80");
      assertThat(readLine(output)).isEqualTo("merged-error");

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
  void closingTerminalForciblyReapsATermIgnoringLocalSshProcess() throws Exception {
    Path fake =
        executable(
            "term-ignoring-terminal-ssh",
            """
            #!/bin/sh
            trap '' TERM HUP
            printf 'ready\n'
            kill -STOP $$
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake-host"), fake, temporary.resolve("control"));
    InteractiveChannel channel = executor.openTerminal("/remote/repo");
    BufferedReader output =
        new BufferedReader(new InputStreamReader(channel.input(), StandardCharsets.UTF_8));
    assertThat(readLine(output)).isEqualTo("ready");
    TimeUnit.MILLISECONDS.sleep(100);

    long started = System.nanoTime();
    channel.close();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMillis).isLessThan(5_000);
    assertThat(channel.isOpen()).isFalse();
    assertThat(channel.awaitExit()).isNotEqualTo(0);
  }

  @Test
  void interactiveTerminalNormalizesLinuxTtyModesBeforeStartingTheShell() {
    assertThat(SshCommandExecutor.terminalCommand("/remote/repo with space"))
        .isEqualTo(
            "cd -- '/remote/repo with space'"
                + " && /usr/bin/stty sane"
                + " && exec \"${SHELL:-/bin/sh}\" -l");
  }

  @Test
  void terminalSizeRejectsNonPositiveDimensions() {
    assertThatThrownBy(() -> new TerminalSize(0, 24))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("columns");
    assertThatThrownBy(() -> new TerminalSize(80, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rows");
  }

  @Test
  void sftpUsesTheSameControlSocketAndEscapesGlobs() {
    SftpClient client =
        new SftpClient(SshTarget.of("host"), Path.of("/usr/bin/sftp"), Path.of("/tmp/private/c"));
    assertThat(client.arguments())
        .contains("BatchMode=yes", "ControlPath=/tmp/private/c", "ClearAllForwardings=yes")
        .doesNotContain("StrictHostKeyChecking=no");
    assertThat(SftpClient.quoteBatchPath("/repo/a [x]*?.txt"))
        .isEqualTo("\"/repo/a \\[x\\]\\*\\?.txt\"");
    assertThatThrownBy(() -> SftpClient.quoteBatchPath("/repo/a\nfile"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sftpTransfersUseNonInteractiveBatchCommands() throws Exception {
    Path batch = temporary.resolve("sftp.batch");
    Path fake =
        executable(
            "fake-sftp",
            """
            #!/bin/sh
            cat > '%s'
            """
                .formatted(batch));
    SftpClient client = new SftpClient(SshTarget.of("host"), fake, temporary.resolve("control"));

    client.download("/remote/a [x].txt", temporary.resolve("local file.txt"), 100);

    assertThat(Files.readString(batch))
        .isEqualTo(
            "get \"/remote/a \\[x\\].txt\" \""
                + temporary.resolve("local file.txt")
                + "\"\nquit\n");
  }

  @Test
  void fakeOpenSshReportsRemoteIdentityAndReceivesGroupSignal() throws Exception {
    Path stop = temporary.resolve("stop");
    Path signal = temporary.resolve("signal.txt");
    Path fake =
        executable(
            "fake-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            case "$last" in
              *BBV_PROCESS_*)
                marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
                printf '%%s4321:4321\n' "$marker"
                printf 'remote output'
                while [ ! -f '%s' ]; do sleep 0.02; done
                ;;
              *"/bin/kill"*)
                printf '%%s' "$last" > '%s'
                : > '%s'
                ;;
            esac
            """
                .formatted(stop, signal, stop));
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake-host"), fake, temporary.resolve("control"));

    RunningCommand running =
        executor.start(
            CommandRequest.of(List.of("bazel", "test", "//..."), "/remote/repo")
                .withForceTty(true));
    assertThat(running.pid()).isEqualTo(4321);
    assertThat(running.processGroupId()).hasValue(4321);
    assertThat(running.streamsMerged()).isTrue();
    assertThat(
            new String(
                running.stdout().readNBytes("remote output".length()), StandardCharsets.UTF_8))
        .isEqualTo("remote output");

    running.signal(CancellationMode.TERMINATE);
    assertThat(running.waitFor(Duration.ofSeconds(2))).isTrue();
    assertThat(Files.readString(signal)).contains("'/bin/kill' '-TERM' '--' '-4321'");
  }

  @Test
  void binaryRedirectStripsPrivateMarkerWithoutChangingPayload() throws Exception {
    Path fake =
        executable(
            "fake-binary-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%s6001:6001\n' "$marker"
            printf '\\001\\377\\000A'
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));
    Path output = temporary.resolve("query.pb");

    CommandResult result =
        executor.runRedirectingStdout(
            CommandRequest.of(List.of("bazel", "aquery"), "/repo"), Duration.ofSeconds(2), output);

    assertThat(result.isSuccess()).isTrue();
    assertThat(Files.readAllBytes(output))
        .containsExactly((byte) 1, (byte) 0xff, (byte) 0, (byte) 'A');
  }

  @Test
  void boundedRemoteCommandsReceiveEndOfInput() throws Exception {
    Path fake =
        executable(
            "fake-eof-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%s6100:6100\n' "$marker"
            cat >/dev/null
            printf 'reached-eof'
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));

    CommandResult result =
        executor.run(CommandRequest.of(List.of("needs-eof"), "/repo"), Duration.ofSeconds(2));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.stdout()).isEqualTo("reached-eof");
  }

  @Test
  void sshCommandLogsNeverContainArgvOrEnvironmentSecrets() throws Exception {
    Path fake =
        executable(
            "fake-secret-safe-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%s6300:6300\n' "$marker"
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("private-control"));
    String argvSecret = "ssh-argv-secret-f017a";
    String environmentSecret = "ssh-env-secret-b80c4";
    CommandRequest request =
        CommandRequest.of(
                List.of("bazel", "test", "--remote_header=Authorization=Bearer " + argvSecret),
                "/repo")
            .withEnvironment(
                Map.of("API_TOKEN", Optional.of(environmentSecret)),
                RuntimeEnvironment.INHERIT_ALL);

    Logger logger = (Logger) LoggerFactory.getLogger(SshCommandExecutor.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.TRACE);
    try {
      CommandResult result = executor.run(request, Duration.ofSeconds(2));
      assertThat(result.isSuccess()).isTrue();

      String rendered = rendered(events);
      assertThat(rendered)
          .contains("kind=bounded")
          .contains("argumentCount=3")
          .contains("environmentOverrideCount=1")
          .contains("exitCode=0")
          .contains("durationMs=")
          .doesNotContain(argvSecret)
          .doesNotContain(environmentSecret)
          .doesNotContain("Authorization")
          .doesNotContain("remote_header")
          .doesNotContain("API_TOKEN")
          .doesNotContain("private-control");
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previousLevel);
      events.stop();
    }
  }

  @Test
  void sftpLogsNeverContainBatchScriptsOrPaths() throws Exception {
    Path fake = executable("fake-secret-safe-sftp", "#!/bin/sh\ncat >/dev/null\n");
    SftpClient client =
        new SftpClient(SshTarget.of("fake"), fake, temporary.resolve("private-control"));
    String pathSecret = "sftp-path-secret-31a6e";

    Logger logger = (Logger) LoggerFactory.getLogger(SftpClient.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(Level.TRACE);
    try {
      client.download("/remote/" + pathSecret, temporary.resolve(pathSecret + ".txt"), 100);

      assertThat(rendered(events))
          .contains("operation=download")
          .contains("maximumBytes=100")
          .doesNotContain(pathSecret)
          .doesNotContain("private-control")
          .doesNotContain("get \"")
          .doesNotContain("quit");
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previousLevel);
      events.stop();
    }
  }

  @Test
  void gracefulStopWritesCtrlCToForcedTty() throws Exception {
    Path received = temporary.resolve("tty-byte.txt");
    Path fake =
        executable(
            "fake-tty-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%%s6200:6200\n' "$marker"
            /usr/bin/od -An -tu1 -N1 > '%s'
            """
                .formatted(received));
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));
    RunningCommand running =
        executor.start(CommandRequest.of(List.of("bazel", "build"), "/repo").withForceTty(true));
    assertThat(
            SshCommandExecutor.remoteCommand(
                CommandRequest.of(List.of("bazel", "build"), "/repo").withForceTty(true),
                "BBV_PROCESS_abc:"))
        .contains("/usr/bin/ps -o pgid=")
        .doesNotContain("/usr/bin/setsid");

    running.signal(CancellationMode.CANCEL);

    assertThat(running.waitFor(Duration.ofSeconds(2))).isTrue();
    assertThat(Files.readString(received).strip()).isEqualTo("3");
  }

  @Test
  void sshFilesystemNeverTreatsRemotePathsAsDesktopPaths() throws Exception {
    Path fake = executable("unused", "#!/bin/sh\nexit 1\n");
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/srv/repo",
            executor,
            new SftpClient(SshTarget.of("fake"), fake, temporary.resolve("c")));

    ExecutionPath relative = files.path("pkg/../BUILD.bazel");
    assertThat(relative.value()).isEqualTo("/srv/repo/BUILD.bazel");
    assertThat(files.resolve(relative, "../src/Main.java").value())
        .isEqualTo("/srv/repo/src/Main.java");
    assertThat(files.localPath(relative)).isEmpty();
    assertThatThrownBy(() -> files.resolve(new ExecutionPath("other", "/srv/repo"), "child"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("belongs to execution other");
    assertThatThrownBy(() -> files.read(relative, Integer.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JVM byte-array limit");
  }

  @Test
  void remoteHelperFailureSurvivesSetsidForking() throws Exception {
    Path fake =
        executable(
            "fake-failed-stat-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%s7200:7200\n' "$marker"
            printf "/usr/bin/stat: cannot statx '/missing': No such file or directory\n" >&2
            case "$last" in
              *"/usr/bin/setsid --wait "*) exit 1 ;;
              *) exit 0 ;;
            esac
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/repo",
            executor,
            new SftpClient(SshTarget.of("fake"), fake, temporary.resolve("c")));

    FileMetadata result = files.stat(files.path("/missing"));

    assertThat(result.state()).isEqualTo(FileMetadata.State.MISSING);
  }

  @Test
  void batchStatKeepsRequestedOrderAndExplicitMissingRows() throws Exception {
    Path fake =
        executable(
            "fake-stat-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%s7100:7100\n' "$marker"
            printf 'P\\037regular file\\0373\\037100\\0'
            printf 'E\\037/usr/bin/stat: cannot stat: No such file or directory\\0'
            """);
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("c"));
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/repo",
            executor,
            new SftpClient(SshTarget.of("fake"), fake, temporary.resolve("c")));
    ExecutionPath first = files.path("one");
    ExecutionPath second = files.path("two");

    List<FileMetadata> result = files.statAll(List.of(first, second));

    assertThat(result).extracting(FileMetadata::path).containsExactly(first, second);
    assertThat(result.getFirst().isRegularFile()).isTrue();
    assertThat(result.getFirst().bytes()).hasValue(3);
    assertThat(result.getLast().state()).isEqualTo(FileMetadata.State.MISSING);
  }

  private Path executable(String name, String contents) throws Exception {
    Path path = temporary.resolve(name);
    Files.writeString(path, contents);
    path.toFile().setExecutable(true);
    return path;
  }

  private static String readLine(BufferedReader reader) throws Exception {
    FutureTask<String> read = new FutureTask<>(reader::readLine);
    Thread.ofVirtual().name("bbv-test-terminal-read").start(read);
    return read.get(5, TimeUnit.SECONDS);
  }

  private static String rendered(ListAppender<ILoggingEvent> events) {
    return events.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }
}

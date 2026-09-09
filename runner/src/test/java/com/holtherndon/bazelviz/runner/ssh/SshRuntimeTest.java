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
import com.holtherndon.bazelviz.runner.proc.Subprocess;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
  void snapshotDownloadsRetryOnceButUploadsAreNeverReplayed() throws Exception {
    Path destination = temporary.resolve("download-recovery");
    Path calls = temporary.resolve("sftp-recovery-calls");
    Path fake =
        executable(
            "sftp-recovery",
            """
            #!/bin/sh
            cat >/dev/null
            if [ ! -f '%s' ]; then
              echo first > '%s'
              printf partial > '%s'
              exit 1
            fi
            echo second >> '%s'
            printf complete > '%s'
            """
                .formatted(calls, calls, destination, calls, destination));
    AtomicInteger recoveries = new AtomicInteger();
    SshConnectionAccess connection =
        new SshConnectionAccess() {
          @Override
          public Path socket() {
            return temporary.resolve("socket");
          }

          @Override
          public IOException failed(Path socket, IOException failure) {
            recoveries.incrementAndGet();
            return new SshConnectionAccess.RecoveredFailure(failure);
          }
        };
    SftpClient client = new SftpClient(SshTarget.of("fake"), fake, connection);
    client.download("/private/snapshot", destination, 8);
    assertThat(Files.readString(destination)).isEqualTo("complete");
    assertThat(Files.readAllLines(calls)).hasSize(2);
    assertThat(recoveries.get()).isEqualTo(1);

    Files.delete(calls);
    assertThatThrownBy(() -> client.upload(destination, "/remote/save", 8))
        .isInstanceOf(SshConnectionAccess.RecoveredFailure.class);
    assertThat(Files.readAllLines(calls)).hasSize(1);
    assertThat(recoveries.get()).isEqualTo(2);
  }

  @Test
  void sftpUsesTheSameControlSocketAndEscapesGlobs() throws Exception {
    SftpClient client =
        new SftpClient(SshTarget.of("host"), Path.of("/usr/bin/sftp"), Path.of("/tmp/private/c"));
    assertThat(client.arguments())
        .contains(
            "BatchMode=yes",
            "ControlPath=/tmp/private/c",
            "ClearAllForwardings=yes",
            "ProxyCommand=false")
        .doesNotContain("StrictHostKeyChecking=no");
    assertThat(SftpClient.quoteBatchPath("/repo/a [x]*?.txt"))
        .isEqualTo("\"/repo/a \\[x\\]\\*\\?.txt\"");
    assertThatThrownBy(() -> SftpClient.quoteBatchPath("/repo/a\nfile"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sftpTransferTimeoutScalesToTheLargestRemoteCapture() {
    assertThat(SftpClient.transferTimeout(32L * 1024 * 1024 * 1024)).isEqualTo(Duration.ofHours(6));
  }

  @Test
  void sftpTransfersUseNonInteractiveBatchCommands() throws Exception {
    Path batch = temporary.resolve("sftp.batch");
    Path destination = temporary.resolve("local file.txt");
    Path fake =
        executable(
            "fake-sftp",
            """
            #!/bin/sh
            cat > '%s'
            /usr/bin/yes x | /usr/bin/head -c 100 > %s
            """
                .formatted(batch, PosixShell.quote(destination.toString())));
    SftpClient client = new SftpClient(SshTarget.of("host"), fake, temporary.resolve("control"));

    client.download("/remote/a [x].txt", destination, 100);

    assertThat(Files.readString(batch))
        .isEqualTo("get \"/remote/a \\[x\\].txt\" \"" + destination + "\"\nquit\n");
  }

  @Test
  void sftpDownloadAllowsExactBoundaryAndRejectsSizeMismatch() throws Exception {
    Path exact = temporary.resolve("exact.bin");
    Path fakeExact =
        executable(
            "fake-sftp-exact",
            "#!/bin/sh\ncat >/dev/null\nprintf '1234567890' > '%s'\n".formatted(exact));
    SftpClient exactClient =
        new SftpClient(SshTarget.of("host"), fakeExact, temporary.resolve("control-exact"));
    exactClient.download("/remote/exact", exact, 10);
    assertThat(Files.size(exact)).isEqualTo(10);

    Path overLimit = temporary.resolve("over-limit.bin");
    Path overLimitPid = temporary.resolve("over-limit.pid");
    Path fakeOverLimit =
        executable(
            "fake-sftp-over-limit",
            "#!/bin/sh\nprintf '%%s' \"$$\" > '%s'\ncat >/dev/null\nprintf '12345678901' > '%s'\n"
                .formatted(overLimitPid, overLimit));
    SftpClient overLimitClient =
        new SftpClient(SshTarget.of("host"), fakeOverLimit, temporary.resolve("control-over"));
    assertThatThrownBy(() -> overLimitClient.download("/remote/over", overLimit, 10))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("expected")
        .hasMessageContaining("10");
    assertThat(Files.exists(overLimit)).isFalse();
    long processId = Long.parseLong(Files.readString(overLimitPid));
    assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test
  void sshFilesystemDownloadPublishesAnExactBoundedSnapshotAndRejectsGrowth() throws Exception {
    Path source = temporary.resolve("remote-source.bin");
    Path destination = temporary.resolve("download.bin");
    Path snapshot = temporary.resolve("snapshot-path");
    Path stagedSize = temporary.resolve("snapshot-size");
    Path sftpPid = temporary.resolve("snapshot-sftp.pid");
    Files.writeString(source, "1234567890");
    Files.writeString(destination, "old");
    SshExecutionFileSystem exact =
        downloadFileSystem(source, false, false, snapshot, stagedSize, sftpPid);

    try {
      exact.download(exact.path("/remote/source"), destination, 10);

      assertThat(Files.readString(destination)).isEqualTo("1234567890");
      assertThat(Files.readString(stagedSize)).isEqualTo("10");
      assertRecordedSnapshotGone(snapshot);
      assertProcessGone(sftpPid);
      assertNoSftpDownloadTemporary();

      Files.writeString(source, "abcdefghij");
      Files.writeString(destination, "old");
      Files.deleteIfExists(snapshot);
      Files.deleteIfExists(stagedSize);
      Files.deleteIfExists(sftpPid);
      SshExecutionFileSystem growing =
          downloadFileSystem(source, true, false, snapshot, stagedSize, sftpPid);

      assertThatThrownBy(() -> growing.download(growing.path("/remote/source"), destination, 10))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("changed");
      assertThat(Files.readString(destination)).isEqualTo("old");
      assertThat(Files.readString(stagedSize)).isEqualTo("10");
      assertProcessGone(sftpPid);
      assertRecordedSnapshotGone(snapshot);
      assertNoSftpDownloadTemporary();
    } finally {
      deleteRecordedSnapshot(snapshot);
    }
  }

  @Test
  void interruptedSshFilesystemDownloadReapsTransferAndCleansBothSnapshots() throws Exception {
    Path source = temporary.resolve("interrupt-source.bin");
    Path destination = temporary.resolve("interrupt-download.bin");
    Path snapshot = temporary.resolve("interrupt-snapshot-path");
    Path stagedSize = temporary.resolve("interrupt-snapshot-size");
    Path sftpPid = temporary.resolve("interrupt-sftp.pid");
    Files.writeString(source, "1234567890");
    Files.writeString(destination, "old");
    SshExecutionFileSystem files =
        downloadFileSystem(source, false, true, snapshot, stagedSize, sftpPid);
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              files.download(files.path("/remote/source"), destination, 10);
              return null;
            });
    Thread worker = Thread.ofVirtual().start(task);
    try {
      awaitFile(sftpPid);

      worker.interrupt();

      assertThatThrownBy(() -> task.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IOException.class)
          .hasRootCauseInstanceOf(InterruptedException.class);
      assertThat(Files.readString(destination)).isEqualTo("old");
      assertProcessGone(sftpPid);
      assertRecordedSnapshotGone(snapshot);
      assertNoSftpDownloadTemporary();
    } finally {
      worker.interrupt();
      deleteRecordedSnapshot(snapshot);
    }
  }

  @Test
  void remoteContinuationKeysAreOpaqueAbsolutePathKeys() {
    String key = "/repo/last entry";
    String token = SshExecutionFileSystem.encodeKey("revision", key);
    assertThat(token).doesNotContain("/").doesNotContain(" ");
    assertThat(SshExecutionFileSystem.decodeKey(token).revision()).isEqualTo("revision");
    assertThat(SshExecutionFileSystem.decodeKey(token).after()).isEqualTo(key);
    assertThatThrownBy(() -> SshExecutionFileSystem.decodeKey("12"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("continuation token");
  }

  @Test
  void remoteDirectorySelectorUsesBoundedByteSafeKeysetPages() throws Exception {
    Path completed = temporary.resolve("find-completed");
    List<String> paths =
        List.of(
            "/repo/z-last",
            "/repo/a\\literal",
            "/repo/a\nnewline",
            "/repo/a\u001fseparator",
            "/repo/quote'$value",
            "/repo/middle");
    StringBuilder emitter = new StringBuilder("#!/bin/bash\n");
    for (String path : paths) {
      emitter
          .append("printf '%s\\0%s\\0%s\\0%s\\0' ")
          .append(PosixShell.quote(path))
          .append(" f 1 1.000000000\n");
    }
    emitter.append(": > ").append(PosixShell.quote(completed.toString())).append('\n');
    Path find = executable("unsorted-find", emitter.toString());

    List<String> walked = new ArrayList<>();
    String after = "";
    while (true) {
      Subprocess.Result result =
          Subprocess.run(
              SshExecutionFileSystem.directoryListingCommand(
                  "/repo", after, 2, find.toString(), "/bin/bash"),
              null,
              Map.of(),
              Duration.ofSeconds(5));
      assertThat(result.isSuccess()).as(result.failureDetail()).isTrue();
      List<String> page = directoryPaths(result.stdout());
      assertThat(page).hasSizeLessThanOrEqualTo(2);
      walked.addAll(page);
      assertThat(completed).exists();
      Files.delete(completed);
      if (page.size() < 2) {
        break;
      }
      after = page.getLast();
    }

    assertThat(walked).containsExactlyElementsOf(paths.stream().sorted().toList());
  }

  @Test
  void remoteDirectorySelectorTreatsGlobCharactersAsLiteralKeys() throws Exception {
    List<String> paths =
        List.of(
            "/repo/a*literal",
            "/repo/aaliteral",
            "/repo/a-literal",
            "/repo/b?literal",
            "/repo/baliteral",
            "/repo/c[ab]literal",
            "/repo/caliteral");
    StringBuilder emitter = new StringBuilder("#!/bin/bash\n");
    for (String path : paths) {
      emitter
          .append("printf '%s\\0%s\\0%s\\0%s\\0' ")
          .append(PosixShell.quote(path))
          .append(" f 1 1.000000000\n");
    }
    Path find = executable("glob-key-find", emitter.toString());

    Subprocess.Result heapPage =
        Subprocess.run(
            SshExecutionFileSystem.directoryListingCommand(
                "/repo", "", 2, find.toString(), "/bin/bash"),
            null,
            Map.of(),
            Duration.ofSeconds(5));
    assertThat(heapPage.isSuccess()).as(heapPage.failureDetail()).isTrue();
    assertThat(directoryPaths(heapPage.stdout()))
        .containsExactly("/repo/a*literal", "/repo/a-literal");

    List<String> walked = new ArrayList<>();
    String after = "";
    while (true) {
      Subprocess.Result result =
          Subprocess.run(
              SshExecutionFileSystem.directoryListingCommand(
                  "/repo", after, 1, find.toString(), "/bin/bash"),
              null,
              Map.of(),
              Duration.ofSeconds(5));
      assertThat(result.isSuccess()).as(result.failureDetail()).isTrue();
      List<String> page = directoryPaths(result.stdout());
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(page);
      after = page.getLast();
    }

    assertThat(walked).containsExactlyElementsOf(paths.stream().sorted().toList());
  }

  @Test
  void remoteDirectorySelectorPropagatesEveryPipelineFailureAndPartialTuple() throws Exception {
    Path failedFind =
        executable(
            "failed-find", "#!/bin/sh\nprintf '%s\\0%s\\0%s\\0%s\\0' /repo/a f 1 1.0\nexit 23\n");
    Path partialFind = executable("partial-find", "#!/bin/sh\nprintf partial\nexit 0\n");
    Path failedSelector = executable("failed-selector", "#!/bin/sh\ncat >/dev/null\nexit 24\n");

    assertThat(runDirectorySelector(failedFind, Path.of("/bin/bash")).exitCode()).isEqualTo(23);
    assertThat(runDirectorySelector(partialFind, Path.of("/bin/bash")).exitCode()).isEqualTo(65);
    assertThat(runDirectorySelector(partialFind, failedSelector).exitCode()).isEqualTo(24);
  }

  @Test
  void remoteDirectoryParserRejectsACompleteLookingRecordWithoutItsTerminator() throws Exception {
    Path unused = executable("unused-directory-parser-ssh", "#!/bin/sh\nexit 1\n");
    SshTarget target = SshTarget.of("fake");
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/repo",
            new SshCommandExecutor(target, unused, temporary.resolve("control")),
            new SftpClient(target, unused, temporary.resolve("control")));
    String record = "/repo/a" + '\0' + "f" + '\0' + "1" + '\0' + "1.0";

    assertThatThrownBy(() -> files.parseDirectoryRecords(record))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("incomplete");
  }

  @Test
  void remoteDirectoryContinuationRejectsARealMutationAndKeepsTotalUnknown() throws Exception {
    Path mutation = temporary.resolve("directory-mutated");
    Path fake =
        executable(
            "fake-directory-ssh",
            "#!/bin/sh\n"
                + "last=''\n"
                + "for argument do last=\"$argument\"; done\n"
                + "marker=$(printf '%s' \"$last\" | sed -n"
                + " 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')\n"
                + "printf '%s6500:6500\\n' \"$marker\"\n"
                + "case \"$last\" in\n"
                + "  *'/usr/bin/readlink'*) printf '/repo\\n' ;;\n"
                + "  *'--printf=%d:%i:%s:%y'*) if [ -f "
                + PosixShell.quote(mutation.toString())
                + " ]; then printf 'revision-after'; else printf 'revision-before'; fi ;;\n"
                + "  *'/usr/bin/stat'*) printf 'directory\\0' ; printf '0\\0' ; printf '1\\0' ;;\n"
                + "  *'/usr/bin/find'*) printf '%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0'"
                + " '/repo/a' f 1 1.0 '/repo/b' f 1 1.0 ;;\n"
                + "  *) exit 1 ;;\n"
                + "esac\n");
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("control"));
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/repo",
            executor,
            new SftpClient(SshTarget.of("fake"), fake, temporary.resolve("control")));

    var first = files.list(files.path("/repo"), Optional.empty(), 1);
    Files.createFile(mutation);

    assertThat(first.entries())
        .extracting(entry -> entry.path().value())
        .containsExactly("/repo/a");
    assertThat(first.totalEntries()).isEmpty();
    assertThatThrownBy(() -> files.list(files.path("/repo"), first.nextToken(), 1))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("changed");
  }

  @Test
  void remoteDirectoryMutationDuringTheScanRefusesThePage() throws Exception {
    Path remoteDirectory = Files.createDirectory(temporary.resolve("mutating-directory"));
    Path newEntry = remoteDirectory.resolve("new-entry");
    Path fake =
        executable(
            "fake-mutating-directory-ssh",
            "#!/bin/sh\n"
                + "last=''\n"
                + "for argument do last=\"$argument\"; done\n"
                + "marker=$(printf '%s' \"$last\" | sed -n"
                + " 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')\n"
                + "printf '%s6550:6550\\n' \"$marker\"\n"
                + "case \"$last\" in\n"
                + "  *'/usr/bin/readlink'*) printf '/repo\\n' ;;\n"
                + "  *'--printf=%d:%i:%s:%y'*) if [ -f "
                + PosixShell.quote(newEntry.toString())
                + " ]; then printf 'revision-after'; else printf 'revision-before'; fi ;;\n"
                + "  *'/usr/bin/stat'*) printf 'directory\\0' ; printf '0\\0' ; printf '1\\0' ;;\n"
                + "  *'/usr/bin/find'*) : > "
                + PosixShell.quote(newEntry.toString())
                + "; printf '%s\\0%s\\0%s\\0%s\\0' '/repo/a' f 1 1.0 ;;\n"
                + "  *) exit 1 ;;\n"
                + "esac\n");
    SshTarget target = SshTarget.of("fake");
    SshExecutionFileSystem files =
        new SshExecutionFileSystem(
            "execution-one",
            "/repo",
            new SshCommandExecutor(target, fake, temporary.resolve("control")),
            new SftpClient(target, fake, temporary.resolve("control")));

    assertThatThrownBy(() -> files.list(files.path("/repo"), Optional.empty(), 2))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("changed while it was being listed");
    assertThat(newEntry).exists();
  }

  @Test
  void openSshInheritedPipeAfterRootExitFailsClosed() throws Exception {
    Path child = temporary.resolve("openssh-child.pid");
    Path fake =
        executable(
            "fake-openssh-inherited-pipe",
            "#!/bin/sh\n(sleep 10) &\nprintf '%s' $! > "
                + PosixShell.quote(child.toString())
                + "\nexit 0\n");

    assertThatThrownBy(() -> OpenSshProcess.run(List.of(fake.toString()), Duration.ofSeconds(2)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("drained");
    assertProcessGone(child);
  }

  @Test
  void boundedAndRedirectedSshCommandsReapInheritedPipeChildren() throws Exception {
    Path boundedChild = temporary.resolve("bounded-ssh-child.pid");
    Path redirectedChild = temporary.resolve("redirected-ssh-child.pid");
    Path callCount = temporary.resolve("inherited-call-count");
    Path fake =
        executable(
            "fake-ssh-inherited-pipes",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            case "$last" in
              *"/bin/kill"*) exit 0 ;;
            esac
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%%s7300:7300\n' "$marker"
            if [ -f %s ]; then
              pid_file=%s
              printf new
            else
              : > %s
              pid_file=%s
            fi
            (sleep 10) &
            printf '%%s' $! > "$pid_file"
            # Let the caller attach both drains before the root exits. Otherwise the JVM
            # can close an unread process pipe before the inherited writer is observed.
            sleep 0.2
            exit 0
            """
                .formatted(
                    PosixShell.quote(callCount.toString()),
                    PosixShell.quote(redirectedChild.toString()),
                    PosixShell.quote(callCount.toString()),
                    PosixShell.quote(boundedChild.toString())));
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("control"));

    assertThatThrownBy(
            () -> executor.run(CommandRequest.of(List.of("probe"), "/repo"), Duration.ofSeconds(2)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("drained");
    assertProcessGone(boundedChild);

    Path destination = temporary.resolve("ssh-inherited-output");
    Files.writeString(destination, "old");
    assertThatThrownBy(
            () ->
                executor.runRedirectingStdout(
                    CommandRequest.of(List.of("redirect"), "/repo"),
                    Duration.ofSeconds(2),
                    destination))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("drained");
    assertThat(Files.readString(destination)).isEqualTo("old");
    assertProcessGone(redirectedChild);
    assertNoSshOutputTemporary();
  }

  @Test
  void interruptedSshRedirectSignalsRemoteAndCleansLocalProcessAndOutput() throws Exception {
    Path child = temporary.resolve("interrupted-ssh-child.pid");
    Path signal = temporary.resolve("interrupted-ssh-signal");
    Path stop = temporary.resolve("interrupted-ssh-stop");
    Path fake =
        executable(
            "fake-interrupted-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            case "$last" in
              *"/bin/kill"*) printf '%%s' "$last" > %s; : > %s; exit 0 ;;
            esac
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%%s7400:7400\nnew' "$marker"
            (sleep 30) & child=$!
            printf '%%s' "$child" > %s
            while [ ! -f %s ]; do sleep 0.02; done
            kill "$child" 2>/dev/null || :
            wait "$child" 2>/dev/null || :
            """
                .formatted(
                    PosixShell.quote(signal.toString()),
                    PosixShell.quote(stop.toString()),
                    PosixShell.quote(child.toString()),
                    PosixShell.quote(stop.toString())));
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("control"));
    Path destination = temporary.resolve("interrupted-ssh-output");
    Files.writeString(destination, "old");
    FutureTask<CommandResult> task =
        new FutureTask<>(
            () ->
                executor.runRedirectingStdout(
                    CommandRequest.of(List.of("redirect"), "/repo"),
                    Duration.ofSeconds(30),
                    destination));
    Thread worker = Thread.ofVirtual().start(task);
    awaitFile(child);

    worker.interrupt();

    assertThatThrownBy(() -> task.get(10, TimeUnit.SECONDS))
        .hasCauseInstanceOf(InterruptedException.class);
    assertThat(Files.readString(destination)).isEqualTo("old");
    assertThat(Files.readString(signal)).contains("'/bin/kill' '-KILL'");
    assertProcessGone(child);
    assertNoSshOutputTemporary();
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
  void signalAfterTheSshTransportExitedDoesNotTargetAStaleRemotePid() throws Exception {
    Path calls = temporary.resolve("stale-signal-calls");
    Path fake =
        executable(
            "fake-stale-signal-ssh",
            """
            #!/bin/sh
            last=''
            for argument do last="$argument"; done
            printf '%%s\n' "$last" >> %s
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%%s7600:7600\n' "$marker"
            exit 0
            """
                .formatted(PosixShell.quote(calls.toString())));
    SshCommandExecutor executor =
        new SshCommandExecutor(SshTarget.of("fake"), fake, temporary.resolve("control"));
    RunningCommand running = executor.start(CommandRequest.of(List.of("probe"), "/repo"));
    assertThat(running.waitFor(Duration.ofSeconds(2))).isTrue();

    running.signal(CancellationMode.FORCE_KILL);

    assertThat(Files.readString(calls)).doesNotContain("/bin/kill");
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
    Path destination = temporary.resolve("sftp-path-secret-31a6e.txt");
    Path fake =
        executable(
            "fake-secret-safe-sftp",
            "#!/bin/sh\ncat >/dev/null\n/usr/bin/yes x | /usr/bin/head -c 100 > "
                + PosixShell.quote(destination.toString())
                + "\n");
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
      client.download("/remote/" + pathSecret, destination, 100);

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

  private Subprocess.Result runDirectorySelector(Path find, Path selector) throws Exception {
    return Subprocess.run(
        SshExecutionFileSystem.directoryListingCommand(
            "/repo", "", 2, find.toString(), selector.toString()),
        null,
        Map.of(),
        Duration.ofSeconds(5));
  }

  private SshExecutionFileSystem downloadFileSystem(
      Path source,
      boolean growSource,
      boolean slowSftp,
      Path snapshotLog,
      Path stagedSize,
      Path sftpPid)
      throws Exception {
    Path ssh =
        executable(
            "fake-download-ssh-" + growSource + "-" + slowSftp,
            """
            #!/bin/bash
            last=''
            for argument do last="$argument"; done
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            printf '%%s7500:7500\n' "$marker"
            remote_snapshot=$(printf '%%s' "$last" | sed -n 's#.*\\(/tmp/\\.bbv-download-[0-9a-f-]*\\).*#\\1#p')
            case "$last" in
              *"/usr/bin/readlink"*) printf '%%s\n' %s ;;
              *"bbv-download-snapshot"*)
                /usr/bin/head -c 10 %s > "$remote_snapshot"
                /bin/chmod 0400 "$remote_snapshot"
                /usr/bin/wc -c < "$remote_snapshot" | /usr/bin/tr -d '[:space:]' > %s
                printf '%%s' "$remote_snapshot" > %s
                ;;
              *"/bin/rm"*) /bin/rm -f "$remote_snapshot" ;;
              *"/usr/bin/stat"*)
                if [ -n "$remote_snapshot" ] && [ -f "$remote_snapshot" ]; then
                  target="$remote_snapshot"
                else
                  target=%s
                fi
                bytes=$(/usr/bin/wc -c < "$target" | /usr/bin/tr -d '[:space:]')
                printf 'regular file\\0%%s\\0%%s\\0' "$bytes" 1
                ;;
              *) exit 1 ;;
            esac
            """
                .formatted(
                    PosixShell.quote(source.toString()),
                    PosixShell.quote(source.toString()),
                    PosixShell.quote(stagedSize.toString()),
                    PosixShell.quote(snapshotLog.toString()),
                    PosixShell.quote(source.toString())));
    String transfer;
    if (slowSftp) {
      transfer =
          "/usr/bin/head -c 5 \"$2\" > \"$3\"\nprintf '%s' \"$$\" > "
              + PosixShell.quote(sftpPid.toString())
              + "\nsleep 30\n";
    } else {
      transfer =
          "/bin/cp \"$2\" \"$3\"\nprintf '%s' \"$$\" > "
              + PosixShell.quote(sftpPid.toString())
              + "\n";
      if (growSource) {
        transfer += "printf x >> " + PosixShell.quote(source.toString()) + "\n";
      }
    }
    Path sftp =
        executable(
            "fake-download-sftp-" + growSource + "-" + slowSftp,
            "#!/bin/bash\nIFS= read -r line\neval \"set -- $line\"\ncat >/dev/null\n" + transfer);
    SshTarget target = SshTarget.of("fake");
    Path control = temporary.resolve("download-control");
    return new SshExecutionFileSystem(
        "execution-one",
        "/remote",
        new SshCommandExecutor(target, ssh, control),
        new SftpClient(target, sftp, control));
  }

  private static List<String> directoryPaths(String output) {
    String[] fields = output.split(String.valueOf('\0'), -1);
    List<String> paths = new ArrayList<>();
    for (int index = 0; index + 3 < fields.length; index += 4) {
      paths.add(fields[index]);
    }
    return paths;
  }

  private static void assertProcessGone(Path pidFile) throws IOException {
    assertThat(pidFile).exists();
    long pid = Long.parseLong(Files.readString(pidFile));
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  private static void assertRecordedSnapshotGone(Path snapshotLog) throws IOException {
    assertThat(snapshotLog).exists();
    Path snapshot = Path.of(Files.readString(snapshotLog));
    assertThat(snapshot.toString()).startsWith("/tmp/.bbv-download-");
    assertThat(snapshot).doesNotExist();
  }

  private static void deleteRecordedSnapshot(Path snapshotLog) throws IOException {
    if (!Files.exists(snapshotLog)) {
      return;
    }
    Path snapshot = Path.of(Files.readString(snapshotLog));
    if (snapshot.toString().startsWith("/tmp/.bbv-download-")) {
      Files.deleteIfExists(snapshot);
    }
  }

  private static void awaitFile(Path path) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!Files.exists(path) && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(path).exists();
  }

  private void assertNoSshOutputTemporary() throws IOException {
    try (var files = Files.list(temporary)) {
      assertThat(files.filter(path -> path.getFileName().toString().startsWith(".bbv-ssh-output-")))
          .isEmpty();
    }
  }

  private void assertNoSftpDownloadTemporary() throws IOException {
    try (var files = Files.list(temporary)) {
      assertThat(
              files.filter(path -> path.getFileName().toString().startsWith(".bbv-sftp-download-")))
          .isEmpty();
    }
  }
}

package com.holtherndon.bazelviz.runner.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SshControlSessionTest {

  @TempDir Path temporary;

  @Test
  void oneFakeMasterSuppliesPwdDynamicForwardAndFileSystem() throws Exception {
    Path stopped = temporary.resolve("master-stopped");
    Path log = temporary.resolve("calls.txt");
    Path ssh =
        executable(
            "fake-ssh",
            """
            #!/bin/sh
            printf '%%s\n' "$*" >> '%s'
            operation=''
            master=no
            previous=''
            last=''
            for argument do
              if [ "$previous" = '-O' ]; then operation="$argument"; fi
              if [ "$argument" = '-M' ]; then master=yes; fi
              previous="$argument"
              last="$argument"
            done
            if [ "$master" = yes ]; then
              while [ ! -f '%s' ]; do sleep 0.02; done
              exit 0
            fi
            case "$operation" in
              check) exit 0 ;;
              forward) printf '43123\n'; exit 0 ;;
              cancel) exit 0 ;;
              exit) : > '%s'; exit 0 ;;
            esac
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            if [ -n "$marker" ]; then
              printf '%%s9001:9001\n' "$marker"
              case "$last" in
                *"/bin/pwd"*) printf '/remote/home\n' ;;
              esac
              exit 0
            fi
            exit 1
            """
                .formatted(log, stopped, stopped));
    Path sftp = executable("fake-sftp", "#!/bin/sh\ncat >/dev/null\nexit 0\n");

    SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), new OpenSshBinaries(ssh, sftp));
    SshCommandExecutor retainedExecutor = session.commandExecutor();
    Path socket = socketFrom(Files.readAllLines(log).getFirst());
    try {
      if (Files.getFileStore(socket.getParent()).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(socket.getParent()))
            .isEqualTo(
                Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
      }
      assertThat(session.displayName()).isEqualTo("builder@fake");
      assertThat(session.fileSystem().path("repo/BUILD.bazel").value())
          .isEqualTo("/remote/home/repo/BUILD.bazel");
      try (SshReverseForward forward = session.openReverseForward(9876)) {
        assertThat(forward.localPort()).isEqualTo(9876);
        assertThat(forward.remotePort()).isEqualTo(43123);
        assertThat(forward.besBackendUri().toString()).isEqualTo("grpc://127.0.0.1:43123");
      }
    } finally {
      session.close();
    }
    assertThat(Files.readString(log))
        .contains("-R 127.0.0.1:0:127.0.0.1:9876")
        .contains("/bin/bash", "/usr/bin/find")
        .contains("-O cancel")
        .contains("-O exit")
        .doesNotContain("/usr/bin/awk")
        .doesNotContain("StrictHostKeyChecking=no");
    assertThat(Files.exists(socket.getParent())).isFalse();
    assertThatThrownBy(
            () ->
                retainedExecutor.run(
                    CommandRequest.of(List.of("true"), "/remote/home"), Duration.ofSeconds(1)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("control session is closed");
  }

  @Test
  void retainedServicesRecoverTogetherAndRestoreTheOriginalBesPort() throws Exception {
    OpenSshBinaries binaries = recoveryBinaries();
    try (SshControlSession session =
        SshControlSession.connect(SshTarget.of("builder@fake"), Duration.ofSeconds(3), binaries)) {
      var files = session.fileSystem();
      var path = files.path("repo/BUILD.bazel");
      var commands = session.commandExecutor();
      try (SshReverseForward forward = session.openReverseForward(9876)) {
        killFakeMaster();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
          var first =
              workers.submit(
                  () ->
                      commands.run(
                          CommandRequest.of(List.of("true"), "/remote/home"),
                          Duration.ofSeconds(2)));
          var second =
              workers.submit(
                  () ->
                      commands.run(
                          CommandRequest.of(List.of("true"), "/remote/home"),
                          Duration.ofSeconds(2)));
          assertThat(first.get(10, TimeUnit.SECONDS).isSuccess()).isTrue();
          assertThat(second.get(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        }
        assertThat(session.fileSystem()).isSameAs(files);
        assertThat(files.path("repo/BUILD.bazel")).isEqualTo(path);
        assertThat(forward.remotePort()).isEqualTo(43123);
        assertThat(Files.readString(temporary.resolve("recovery-calls")))
            .contains("-R 127.0.0.1:43123:127.0.0.1:9876");
        assertThat(masterStarts()).isEqualTo(2);
      }
    }
  }

  @Test
  void droppedMetadataReadRetriesButOrdinaryFailuresDoNotReconnect() throws Exception {
    try (SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries())) {
      Files.writeString(temporary.resolve("drop-stat"), "");
      var files = session.fileSystem();
      assertThat(files.stat(files.path("/remote/file")).bytes()).hasValue(12);
      assertThat(masterStarts()).isEqualTo(2);
      assertThat(Files.readAllLines(temporary.resolve("stat-calls"))).hasSize(2);
      assertThat(
              session
                  .commandExecutor()
                  .run(
                      CommandRequest.of(List.of("exit-255"), "/remote/home"), Duration.ofSeconds(2))
                  .exitCode())
          .isEqualTo(255);
      assertThat(
              session
                  .commandExecutor()
                  .run(CommandRequest.of(List.of("exit-1"), "/remote/home"), Duration.ofSeconds(2))
                  .exitCode())
          .isEqualTo(1);
      assertThat(masterStarts()).isEqualTo(2);
    }
  }

  @Test
  void dispatchedCommandsRecoverTheConnectionWithoutReplayingTheCommand() throws Exception {
    try (SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries())) {
      assertThatThrownBy(
              () ->
                  session
                      .commandExecutor()
                      .run(
                          CommandRequest.of(List.of("drop-command"), "/remote/home"),
                          Duration.ofSeconds(2)))
          .isInstanceOf(SshConnectionAccess.RecoveredFailure.class)
          .hasMessageContaining("not replayed");
      assertThat(masterStarts()).isEqualTo(2);
      assertThat(Files.readAllLines(temporary.resolve("mutation-calls"))).hasSize(1);
    }
  }

  @Test
  void interruptedRedirectPreservesTheExistingDestinationAndDoesNotReplay() throws Exception {
    try (SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries())) {
      Path output = temporary.resolve("query.pb");
      Files.writeString(output, "original");
      assertThatThrownBy(
              () ->
                  session
                      .commandExecutor()
                      .runRedirectingStdout(
                          CommandRequest.of(List.of("drop-command"), "/remote/home"),
                          Duration.ofSeconds(2),
                          output))
          .isInstanceOf(SshConnectionAccess.RecoveredFailure.class);
      assertThat(Files.readString(output)).isEqualTo("original");
      assertThat(Files.readAllLines(temporary.resolve("mutation-calls"))).hasSize(1);
      assertThat(masterStarts()).isEqualTo(2);
    }
  }

  @Test
  void failedRecoveryKeepsTheOriginalFailureAndDoesNotLoop() throws Exception {
    try (SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries())) {
      Files.writeString(temporary.resolve("deny-connect"), "");
      assertThatThrownBy(
              () ->
                  session
                      .commandExecutor()
                      .run(
                          CommandRequest.of(List.of("drop-command"), "/remote/home"),
                          Duration.ofSeconds(2)))
          .isInstanceOf(SshConnectionAccess.RecoveryFailure.class)
          .hasMessageContaining("recovery failed")
          .hasMessageContaining("authentication refused")
          .hasCauseInstanceOf(IOException.class);
      assertThat(masterStarts()).isEqualTo(2);
    }
  }

  @Test
  void failedTunnelRestorationIsNotReportedAsRecovered() throws Exception {
    try (SshControlSession session =
            SshControlSession.connect(
                SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries());
        SshReverseForward forward = session.openReverseForward(9876)) {
      assertThat(forward.remotePort()).isEqualTo(43123);
      Files.writeString(temporary.resolve("deny-forward"), "");
      killFakeMaster();
      assertThatThrownBy(
              () ->
                  session
                      .commandExecutor()
                      .run(
                          CommandRequest.of(List.of("true"), "/remote/home"),
                          Duration.ofSeconds(2)))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("restore the BES tunnel");
      assertThat(masterStarts()).isEqualTo(2);
    }
  }

  @Test
  void interruptionAndExplicitCloseNeverReconnect() throws Exception {
    SshControlSession session =
        SshControlSession.connect(
            SshTarget.of("builder@fake"), Duration.ofSeconds(3), recoveryBinaries());
    var executor = session.commandExecutor();
    try {
      killFakeMaster();
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(
                () ->
                    executor.run(
                        CommandRequest.of(List.of("true"), "/remote/home"), Duration.ofSeconds(2)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("interrupted");
      } finally {
        Thread.interrupted();
      }
      assertThat(masterStarts()).isEqualTo(1);
    } finally {
      session.close();
    }
    assertThatThrownBy(
            () ->
                executor.run(
                    CommandRequest.of(List.of("true"), "/remote/home"), Duration.ofSeconds(2)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("closed");
    assertThat(masterStarts()).isEqualTo(1);
  }

  private long masterStarts() throws IOException {
    return Files.readAllLines(temporary.resolve("master-starts")).size();
  }

  private void killFakeMaster() throws Exception {
    long pid = Long.parseLong(Files.readString(temporary.resolve("recovery-master.pid")).strip());
    ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
    process.destroyForcibly();
    process.onExit().get(5, TimeUnit.SECONDS);
  }

  private OpenSshBinaries recoveryBinaries() throws Exception {
    Path ssh =
        executable(
            "recovery-ssh",
            """
            #!/bin/sh
            root='%s'
            printf '%%s\n' "$*" >> "$root/recovery-calls"
            operation='' master=no previous='' last=''
            for argument do
              if [ "$previous" = '-O' ]; then operation="$argument"; fi
              if [ "$argument" = '-M' ]; then master=yes; fi
              previous="$argument"; last="$argument"
            done
            if [ "$master" = yes ]; then
              echo master >> "$root/master-starts"
              if [ -f "$root/deny-connect" ]; then echo 'authentication refused' >&2; exit 1; fi
              echo $$ > "$root/recovery-master.pid"
              while :; do sleep 0.02; done
            fi
            case "$operation" in
              check) [ -f "$root/recovery-master.pid" ] && kill -0 "$(cat "$root/recovery-master.pid")" 2>/dev/null; exit $? ;;
              forward)
                if [ -f "$root/deny-forward" ]; then echo 'port unavailable' >&2; exit 1; fi
                echo 43123; exit 0 ;;
              cancel) exit 0 ;;
              exit) kill "$(cat "$root/recovery-master.pid")" 2>/dev/null; exit 0 ;;
            esac
            kill -0 "$(cat "$root/recovery-master.pid")" 2>/dev/null || exit 255
            marker=$(printf '%%s' "$last" | sed -n 's/.*\\(BBV_PROCESS_[0-9a-f]*:\\).*/\\1/p')
            [ -z "$marker" ] || printf '%%s9001:9001\n' "$marker"
            case "$last" in
              *"/bin/pwd"*) echo '/remote/home' ;;
              *"--printf="*)
                echo stat >> "$root/stat-calls"
                if [ -f "$root/drop-stat" ]; then
                  rm "$root/drop-stat"
                  kill -9 "$(cat "$root/recovery-master.pid")"
                  exit 255
                fi
                printf 'regular file\\00012\\0001234\\000' ;;
              *"drop-command"*)
                echo mutation >> "$root/mutation-calls"
                kill -9 "$(cat "$root/recovery-master.pid")"; exit 255 ;;
              *"exit-255"*) exit 255 ;;
              *"exit-1"*) exit 1 ;;
            esac
            exit 0
            """
                .formatted(temporary));
    Path sftp = executable("recovery-sftp", "#!/bin/sh\ncat >/dev/null\nexit 0\n");
    return new OpenSshBinaries(ssh, sftp);
  }

  @Test
  void failedMasterReapsAChildThatInheritedItsPipesBeforeDeletingControlState() throws Exception {
    Path child = temporary.resolve("master-child.pid");
    Path calls = temporary.resolve("failed-master-calls");
    Path ssh =
        executable(
            "failed-master-ssh",
            """
            #!/bin/sh
            printf '%%s\n' "$*" >> '%s'
            case " $* " in
              *" -M "*)
                (sleep 10) &
                printf '%%s' $! > '%s'
                exit 1
                ;;
              *) exit 1 ;;
            esac
            """
                .formatted(calls, child));
    Path sftp = executable("unused-sftp", "#!/bin/sh\nexit 1\n");

    assertThatThrownBy(
            () ->
                SshControlSession.connect(
                    SshTarget.of("builder@fake"),
                    Duration.ofSeconds(3),
                    new OpenSshBinaries(ssh, sftp)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("drained");

    long pid = Long.parseLong(Files.readString(child));
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    Path socket = socketFrom(Files.readAllLines(calls).getFirst());
    assertThat(socket.getParent()).doesNotExist();
  }

  private static Path socketFrom(String argv) {
    String[] parts = argv.split(" ");
    for (int index = 0; index + 1 < parts.length; index++) {
      if (parts[index].equals("-S")) {
        return Path.of(parts[index + 1]);
      }
    }
    throw new AssertionError("no control socket in " + argv);
  }

  private Path executable(String name, String contents) throws Exception {
    Path path = temporary.resolve(name);
    Files.writeString(path, contents);
    path.toFile().setExecutable(true);
    return path;
  }
}

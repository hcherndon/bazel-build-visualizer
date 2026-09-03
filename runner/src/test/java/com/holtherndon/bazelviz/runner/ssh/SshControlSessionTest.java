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
        .contains("-O cancel")
        .contains("-O exit")
        .doesNotContain("StrictHostKeyChecking=no");
    assertThat(Files.exists(socket.getParent())).isFalse();
    assertThatThrownBy(
            () ->
                retainedExecutor.run(
                    CommandRequest.of(List.of("true"), "/remote/home"), Duration.ofSeconds(1)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("control session is closed");
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

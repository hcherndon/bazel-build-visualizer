package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.ssh.SshControlSession;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaptureRequestTest {

  @Test
  void remoteRequestKeepsTheLinuxWorkingDirectoryAsText(@TempDir Path sessionsRoot) {
    ArrayList<String> args = new ArrayList<>(List.of("test", "//service:all"));
    SshTarget target = SshTarget.of("builder@example.internal", 2222);

    CaptureRequest request =
        CaptureRequest.remote(
            sessionsRoot,
            "test-version",
            "bazelisk",
            "  /srv/monorepo/../checkout  ",
            args,
            target);
    args.add("//must-not-leak:into-request");

    assertThat(request.isRemote()).isTrue();
    assertThat(request.sshTarget()).contains(target);
    assertThat(request.connectedRemote()).isEmpty();
    assertThat(request.workingDirectory()).isEqualTo("/srv/monorepo/../checkout");
    assertThat(request.args()).containsExactly("test", "//service:all");
    assertThatThrownBy(request::localWorkingDirectory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a local Path");
  }

  @Test
  void requestAdjustmentsPreserveRemoteIdentityAndPath(@TempDir Path sessionsRoot) {
    SshTarget target = SshTarget.of("build-linux");
    CaptureRequest request =
        CaptureRequest.remote(
                sessionsRoot,
                "test-version",
                "bazel",
                "/work/project/../project",
                List.of("build", "//..."),
                target)
            .withPreset(CapturePreset.LIVE_ESSENTIALS)
            .withEnvironment("USE_BAZEL_VERSION", Optional.of("9.2.0"))
            .withOptions(CaptureOptions.defaults().withBatchSize(23));

    assertThat(request.sshTarget()).contains(target);
    assertThat(request.workingDirectory()).isEqualTo("/work/project/../project");
    assertThat(request.preset()).isEqualTo(CapturePreset.LIVE_ESSENTIALS);
    assertThat(request.environmentOverrides())
        .containsEntry("USE_BAZEL_VERSION", Optional.of("9.2.0"));
    assertThat(request.options().batchSize()).isEqualTo(23);
  }

  @Test
  void localFactoryStillNormalizesARealDesktopPath(@TempDir Path sessionsRoot) {
    Path entered = sessionsRoot.resolve("workspace").resolve("..").resolve("workspace");

    CaptureRequest request =
        CaptureRequest.of(
            sessionsRoot, "test-version", "bazel", entered, List.of("build", "//..."));

    assertThat(request.isRemote()).isFalse();
    assertThat(request.sshTarget()).isEmpty();
    assertThat(request.connectedRemote()).isEmpty();
    assertThat(request.localWorkingDirectory()).isEqualTo(entered.toAbsolutePath().normalize());
  }

  @Test
  void connectedWorkspaceSuppliesAndSurvivesTheRequestIdentity(@TempDir Path temporary)
      throws Exception {
    SshTarget target = SshTarget.of("builder@fake", 2202);
    try (FakeRemote fixture = FakeRemote.open(temporary, target)) {
      RemoteExecution remote = fixture.execution();
      CaptureRequest connected =
          CaptureRequest.of(
                  temporary.resolve("sessions"),
                  "test-version",
                  "bazel",
                  temporary,
                  List.of("build", "//..."))
              .withConnectedRemote(remote)
              .withPreset(CapturePreset.LIVE_ESSENTIALS)
              .withEnvironment("USE_BAZEL_VERSION", Optional.of("9.2.0"))
              .withOptions(CaptureOptions.defaults().withBatchSize(17));

      assertThat(connected.isRemote()).isTrue();
      assertThat(connected.sshTarget()).contains(target);
      assertThat(connected.workingDirectory()).isEqualTo(remote.workingDirectory());
      assertThat(connected.connectedRemote())
          .hasValueSatisfying(value -> assertThat(value).isSameAs(remote));

      SshTarget replacement = SshTarget.of("replacement@fake");
      CaptureRequest retargeted = connected.withSshTarget(replacement, "/other/repository");
      assertThat(retargeted.sshTarget()).contains(replacement);
      assertThat(retargeted.workingDirectory()).isEqualTo("/other/repository");
      assertThat(retargeted.connectedRemote()).isEmpty();
    }
  }

  @Test
  void connectedWorkspaceMustMatchBothTargetAndDirectory(@TempDir Path temporary) throws Exception {
    SshTarget target = SshTarget.of("builder@fake");
    try (FakeRemote fixture = FakeRemote.open(temporary, target)) {
      RemoteExecution remote = fixture.execution();
      CaptureRequest base =
          CaptureRequest.remote(
              temporary.resolve("sessions"),
              "test-version",
              "bazel",
              remote.workingDirectory(),
              List.of("build", "//..."),
              target);

      assertThatThrownBy(
              () ->
                  withRemoteIdentity(
                      base, Optional.empty(), remote.workingDirectory(), Optional.of(remote)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("match the request target");
      assertThatThrownBy(
              () ->
                  withRemoteIdentity(
                      base,
                      Optional.of(SshTarget.of("someone-else@fake")),
                      remote.workingDirectory(),
                      Optional.of(remote)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("match the request target");
      assertThatThrownBy(
              () ->
                  withRemoteIdentity(
                      base, Optional.of(target), "/different/repository", Optional.of(remote)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("match the request working directory");
    }
  }

  @Test
  void failedPreflightNeverClosesABorrowedWorkspaceConnection(@TempDir Path temporary)
      throws Exception {
    SshTarget target = SshTarget.of("builder@fake");
    FakeRemote fixture = FakeRemote.open(temporary, target);
    try {
      CaptureRequest request =
          CaptureRequest.remote(
                  temporary.resolve("sessions"),
                  "test-version",
                  "bazel",
                  fixture.execution().workingDirectory(),
                  List.of("build", "//..."),
                  target)
              .withConnectedRemote(fixture.execution());
      fixture.failCommands();

      try (CaptureCoordinator coordinator = new CaptureCoordinator(request)) {
        assertThatThrownBy(coordinator::preflight).isInstanceOf(IOException.class);

        assertThat(fixture.execution().target()).isEqualTo(target);
        assertThat(fixture.exitRequested()).isFalse();
        assertThat(coordinator.detachRemoteExecution()).isEmpty();
      }

      assertThat(fixture.execution().target()).isEqualTo(target);
      assertThat(fixture.exitRequested()).isFalse();
    } finally {
      fixture.close();
    }
    assertThat(fixture.exitRequested()).isTrue();
  }

  @Test
  void workingDirectoryMustContainUsableText(@TempDir Path sessionsRoot) {
    assertThatThrownBy(
            () ->
                CaptureRequest.remote(
                    sessionsRoot,
                    "test-version",
                    "bazel",
                    "   ",
                    List.of("build", "//..."),
                    SshTarget.of("build-linux")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("working directory");

    String withNul = "/srv/repo" + (char) 0 + "/unexpected";
    assertThatThrownBy(
            () ->
                CaptureRequest.remote(
                    sessionsRoot,
                    "test-version",
                    "bazel",
                    withNul,
                    List.of("build", "//..."),
                    SshTarget.of("build-linux")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("working directory");
  }

  private static CaptureRequest withRemoteIdentity(
      CaptureRequest base,
      Optional<SshTarget> target,
      String workingDirectory,
      Optional<RemoteExecution> remote) {
    return new CaptureRequest(
        base.sessionsRoot(),
        base.appVersion(),
        base.executable(),
        workingDirectory,
        base.args(),
        base.preset(),
        base.environmentOverrides(),
        base.inheritance(),
        base.shellMode(),
        base.console(),
        base.progress(),
        base.options(),
        target,
        remote);
  }

  /** A real control-session lifecycle driven by deterministic local scripts. */
  private record FakeRemote(RemoteExecution execution, Path commandFailure, Path calls)
      implements AutoCloseable {

    static FakeRemote open(Path temporary, SshTarget target) throws Exception {
      Path stopped = temporary.resolve("master-stopped");
      Path failure = temporary.resolve("fail-commands");
      Path calls = temporary.resolve("ssh-calls.txt");
      Path ssh =
          executable(
              temporary.resolve("fake-ssh"),
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
                exit) : > '%s'; exit 0 ;;
              esac
              if [ -f '%s' ]; then exit 74; fi
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
                  .formatted(calls, stopped, stopped, failure));
      Path sftp = executable(temporary.resolve("fake-sftp"), "#!/bin/sh\ncat >/dev/null\nexit 0\n");
      SshControlSession session = connectWithBinaries(target, ssh, sftp);
      return new FakeRemote(
          new RemoteExecution(
              session, "/remote/home/repository", Optional.of("/remote/home/repository")),
          failure,
          calls);
    }

    void failCommands() throws IOException {
      Files.createFile(commandFailure);
    }

    boolean exitRequested() throws IOException {
      return Files.readString(calls).contains("-O exit");
    }

    @Override
    public void close() {
      execution.close();
    }

    private static SshControlSession connectWithBinaries(SshTarget target, Path ssh, Path sftp)
        throws Exception {
      Class<?> binariesType = Class.forName("com.holtherndon.bazelviz.runner.ssh.OpenSshBinaries");
      var constructor = binariesType.getDeclaredConstructor(Path.class, Path.class);
      constructor.setAccessible(true);
      Object binaries = constructor.newInstance(ssh, sftp);
      Method connect =
          SshControlSession.class.getDeclaredMethod(
              "connect", SshTarget.class, Duration.class, binariesType);
      connect.setAccessible(true);
      try {
        return (SshControlSession) connect.invoke(null, target, Duration.ofSeconds(3), binaries);
      } catch (InvocationTargetException invocation) {
        Throwable cause = invocation.getCause();
        if (cause instanceof Exception exception) {
          throw exception;
        }
        throw invocation;
      }
    }

    private static Path executable(Path path, String contents) throws IOException {
      Files.writeString(path, contents);
      if (!path.toFile().setExecutable(true)) {
        throw new IOException("could not make test helper executable: " + path);
      }
      return path;
    }
  }
}

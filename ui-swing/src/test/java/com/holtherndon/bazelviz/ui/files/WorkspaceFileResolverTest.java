package com.holtherndon.bazelviz.ui.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Label and BEP path resolution, with no Swing or database involved. */
final class WorkspaceFileResolverTest {

  @Test
  @DisplayName("BUILD.bazel takes precedence and a label maps through its package")
  void buildFileUsesTheLabelsPackage(@TempDir Path workspace) throws Exception {
    Path pkg = Files.createDirectories(workspace.resolve("src/tools"));
    Files.writeString(pkg.resolve("BUILD"), "old");
    Path preferred = Files.writeString(pkg.resolve("BUILD.bazel"), "new");

    assertThat(WorkspaceFileResolver.buildFile(workspace, "//src/tools:runner"))
        .isEqualTo(preferred.toRealPath());
    assertThat(WorkspaceFileResolver.buildFile(workspace, "@//src/tools:runner"))
        .isEqualTo(preferred.toRealPath());
  }

  @Test
  @DisplayName("BUILD lookup returns a logical path owned by the execution filesystem")
  void buildFileUsesExecutionFilesystem(@TempDir Path workspace) throws Exception {
    Path pkg = Files.createDirectories(workspace.resolve("remote/pkg"));
    Path build = Files.writeString(pkg.resolve("BUILD.bazel"), "exports_files([])");
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("ssh-test-double");
    WorkspaceFileAccess access =
        new WorkspaceFileAccess(files, Optional.of(files.path(workspace)), Optional.empty());

    ExecutionPath resolved = WorkspaceFileResolver.buildFile(access, "//remote/pkg:all");

    assertThat(resolved.executionId()).isEqualTo("ssh-test-double");
    assertThat(resolved.value()).isEqualTo(build.toRealPath().toString());
  }

  @Test
  @DisplayName("root-package labels and legacy BUILD names remain supported")
  void rootPackageAndLegacyName(@TempDir Path workspace) throws Exception {
    Path build = Files.writeString(workspace.resolve("BUILD"), "root");

    assertThat(WorkspaceFileResolver.buildFile(workspace, "//:app")).isEqualTo(build.toRealPath());
  }

  @Test
  @DisplayName("external repositories and traversal-shaped labels never escape the workspace")
  void unsafeLabelsAreRefused(@TempDir Path workspace) {
    assertThatThrownBy(() -> WorkspaceFileResolver.buildFile(workspace, "@rules_java//java:defs"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("external-repository");
    assertThatThrownBy(() -> WorkspaceFileResolver.buildFile(workspace, "//../outside:secret"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe package");
    assertThat(WorkspaceFileResolver.mainRepositoryLabel("bazel-out/file")).isEmpty();
  }

  @Test
  @DisplayName("test file URIs and workspace-relative action outputs resolve locally")
  void fileLinksResolve(@TempDir Path workspace) throws Exception {
    Path log = Files.writeString(workspace.resolve("test log.txt"), "result");
    Path output = Files.createDirectories(workspace.resolve("bazel-out/bin")).resolve("report.txt");
    Files.writeString(output, "report");

    assertThat(
            WorkspaceFileResolver.linkedFile(
                FileLink.testLog("test.log", log.toUri().toString()),
                Optional.empty(),
                Optional.empty()))
        .isEqualTo(log.toRealPath());
    assertThat(
            WorkspaceFileResolver.linkedFile(
                FileLink.actionOutput("bazel-out/bin/report.txt"),
                Optional.of(workspace),
                Optional.empty()))
        .isEqualTo(output.toRealPath());
  }

  @Test
  @DisplayName("a stale file URI fails with the exact resolved location")
  void missingLinksAreExplicit(@TempDir Path workspace) {
    Path missing = workspace.resolve("gone.log");
    assertThatThrownBy(
            () ->
                WorkspaceFileResolver.linkedFile(
                    FileLink.testLog("test.log", missing.toUri().toString()),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(missing.toString());
  }

  @Test
  @DisplayName("remote file authorities and relative traversal are never followed")
  void unsafeFileLinksAreRefused(@TempDir Path workspace) {
    assertThatThrownBy(
            () ->
                WorkspaceFileResolver.linkedFile(
                    FileLink.testLog("test.log", "file://builder.example/tmp/test.log"),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("remote file URIs");
    assertThatThrownBy(
            () ->
                WorkspaceFileResolver.linkedFile(
                    FileLink.actionOutput("../outside.txt"),
                    Optional.of(workspace),
                    Optional.empty()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("escapes the workspace");
  }

  @Test
  @DisplayName("recorded SSH provenance never creates local filesystem access")
  void importedRemotePathsStayRemote(@TempDir Path sessionRoot) {
    SessionInfo remote =
        new SessionInfo(
            sessionRoot,
            "remote-session",
            SessionState.READY,
            OptionalLong.empty(),
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(ExecutionLocation.ssh("Builder", "builder.example", OptionalInt.empty())));

    assertThat(WorkspaceFileAccess.fromSession(remote)).isEmpty();
    assertThatThrownBy(
            () ->
                new SessionInfo(
                    sessionRoot,
                    "unsafe",
                    SessionState.READY,
                    OptionalLong.empty(),
                    List.of(),
                    List.of(),
                    Optional.of(Path.of("/remote/workspace")),
                    Optional.empty(),
                    remote.executionLocation()))
        .hasMessageContaining("must not be represented as local Paths");
  }
}

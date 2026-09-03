package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionInfoTest {

  @Test
  void importedSshPathsRemainExecutionTextAndNeverBecomeDesktopPaths(@TempDir Path root) {
    SessionManifest manifest =
        SessionManifest.newSession(SessionId.random(), "test-version", 1_000L)
            .workingDirectory(Optional.of("/srv/repo/service"))
            .workspaceRoot(Optional.of("/srv/repo"))
            .executionLocation(
                Optional.of(
                    SessionManifest.ExecutionLocation.ssh(
                        "builder@example.internal:2222",
                        "builder@example.internal",
                        OptionalInt.of(2222))))
            .build();

    SessionInfo info = SessionInfo.of(root, manifest);

    assertThat(info.executionLocation())
        .get()
        .extracting(SessionManifest.ExecutionLocation::kind)
        .isEqualTo(SessionManifest.ExecutionLocation.Kind.SSH);
    assertThat(info.executionWorkingDirectory()).contains("/srv/repo/service");
    assertThat(info.executionWorkspaceRoot()).contains("/srv/repo");
    assertThat(info.workingDirectory()).isEmpty();
    assertThat(info.workspaceRoot()).isEmpty();

    SessionInfo enriched =
        info.withInvocationPaths(
            Optional.of("/database/working-directory"), Optional.of("/database/workspace"));
    assertThat(enriched.workingDirectory()).isEmpty();
    assertThat(enriched.workspaceRoot()).isEmpty();
    assertThat(enriched.executionWorkingDirectory()).contains("/srv/repo/service");
    assertThat(enriched.executionWorkspaceRoot()).contains("/srv/repo");
  }

  @Test
  void oldAndExplicitLocalSessionsRetainLocalPathCompatibility(@TempDir Path root) {
    SessionManifest oldManifest =
        SessionManifest.newSession(SessionId.random(), "test-version", 1_000L)
            .workingDirectory(Optional.of(root.resolve("package").toString()))
            .workspaceRoot(Optional.of(root.toString()))
            .build();
    SessionManifest explicitLocal =
        oldManifest.toBuilder()
            .executionLocation(Optional.of(SessionManifest.ExecutionLocation.local()))
            .build();

    assertThat(SessionInfo.of(root, oldManifest).workingDirectory())
        .contains(root.resolve("package").toAbsolutePath().normalize());
    assertThat(SessionInfo.of(root, explicitLocal).workspaceRoot())
        .contains(root.toAbsolutePath().normalize());
  }

  @Test
  void sshProvenanceCannotBeCombinedWithLocalPathValues(@TempDir Path root) {
    assertThatThrownBy(
            () ->
                new SessionInfo(
                    root,
                    "session-id",
                    SessionState.NEW,
                    OptionalLong.empty(),
                    List.of(),
                    List.of(),
                    Optional.of(root.resolve("remote-misread-as-local")),
                    Optional.empty(),
                    Optional.of(
                        SessionManifest.ExecutionLocation.ssh(
                            "builder", "builder", OptionalInt.empty())),
                    Optional.of("/srv/repo"),
                    Optional.of("/srv")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be represented as local Paths");
  }
}

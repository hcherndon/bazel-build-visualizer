package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import com.holtherndon.bazelviz.ui.capture.SshConnectionProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLauncherHistoryMigrationTest {

  @TempDir Path settings;

  @Test
  void legacyHistoryMovesOnlyToTheMatchingWorkspaceAndKeepsNewerCommandsFirst() {
    LauncherStateStore legacy = new LauncherStateStore(settings);
    assertThat(
            legacy.save(
                new LauncherStateStore.State(
                    "/srv/repository",
                    "bazel",
                    CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                    "test //legacy:draft",
                    List.of("test //legacy:new", "build //legacy:old"),
                    LauncherStateStore.ExecutionHost.SSH,
                    "builder-alias",
                    "",
                    List.of(
                        new SshConnectionProfile(
                            "builder@10.0.0.18", "22", "/srv/repository", "bazelisk")))))
        .isTrue();
    WorkspaceProfile matching =
        WorkspaceProfile.ssh(
            "matching-id",
            "Remote repository",
            "builder@10.0.0.18",
            OptionalInt.of(22),
            "/srv/repository",
            "bazelisk",
            OptionalLong.of(2));
    WorkspaceProfile other =
        WorkspaceProfile.ssh(
            "other-id",
            "Other repository",
            "builder@10.0.0.18",
            OptionalInt.of(22),
            "/srv/other",
            "bazel",
            OptionalLong.of(1));
    LauncherStateStore matchingStore =
        new LauncherStateStore(WorkspaceUiSettings.workspace(settings, matching.id()));
    assertThat(
            matchingStore.save(
                new LauncherStateStore.State(
                    matching.workingDirectory(),
                    matching.bazelExecutable(),
                    CapturePreset.PERFORMANCE_DIAGNOSTICS,
                    "query //workspace:draft",
                    List.of("query //workspace:new"),
                    LauncherStateStore.ExecutionHost.SSH,
                    matching.destination().orElseThrow(),
                    "22",
                    List.of())))
        .isTrue();

    WorkspaceLauncherHistoryMigration.Result result =
        WorkspaceLauncherHistoryMigration.migrate(settings, List.of(matching, other));

    assertThat(result.complete()).isTrue();
    assertThat(result.workspacesUpdated()).isEqualTo(1);
    assertThat(result.diagnostics()).isEmpty();
    LauncherStateStore.State migrated = matchingStore.load();
    assertThat(migrated.history())
        .containsExactly("query //workspace:new", "test //legacy:new", "build //legacy:old");
    assertThat(migrated.command()).isEqualTo("query //workspace:draft");
    assertThat(migrated.workspace()).isEqualTo(matching.workingDirectory());
    assertThat(migrated.bazelExecutable()).isEqualTo(matching.bazelExecutable());
    assertThat(new LauncherStateStore(WorkspaceUiSettings.workspace(settings, other.id())).file())
        .doesNotExist();
  }

  @Test
  void completedMigrationNeverReintroducesCommandsThatAgeOut() {
    LauncherStateStore legacy = new LauncherStateStore(settings);
    assertThat(
            legacy.save(
                new LauncherStateStore.State(
                    "/code/repository",
                    "bazel",
                    CapturePreset.defaultPreset(),
                    "",
                    List.of("build //legacy"))))
        .isTrue();
    WorkspaceProfile workspace =
        WorkspaceProfile.local(
            "workspace-id", "Repository", "/code/repository", "bazelisk", OptionalLong.empty());

    assertThat(
            WorkspaceLauncherHistoryMigration.migrate(settings, List.of(workspace))
                .workspacesUpdated())
        .isEqualTo(1);

    LauncherStateStore workspaceStore =
        new LauncherStateStore(WorkspaceUiSettings.workspace(settings, workspace.id()));
    List<String> replacement =
        IntStream.range(0, 50).mapToObj(index -> "test //new:" + index).toList();
    LauncherStateStore.State current = workspaceStore.load();
    assertThat(
            workspaceStore.save(
                new LauncherStateStore.State(
                    current.workspace(),
                    current.bazelExecutable(),
                    current.preset(),
                    current.command(),
                    replacement,
                    current.executionHost(),
                    current.sshDestination(),
                    current.sshPort(),
                    current.sshProfiles())))
        .isTrue();

    WorkspaceLauncherHistoryMigration.Result repeated =
        WorkspaceLauncherHistoryMigration.migrate(settings, List.of(workspace));

    assertThat(repeated.complete()).isTrue();
    assertThat(repeated.workspacesUpdated()).isZero();
    assertThat(workspaceStore.load().history())
        .containsExactlyElementsOf(replacement)
        .doesNotContain("build //legacy");
  }

  @Test
  void ambiguousRemoteRepositoryDoesNotLeakHistoryAcrossMachines() {
    LauncherStateStore legacy = new LauncherStateStore(settings);
    assertThat(
            legacy.save(
                new LauncherStateStore.State(
                    "/srv/repository",
                    "bazel",
                    CapturePreset.defaultPreset(),
                    "",
                    List.of("test //private"),
                    LauncherStateStore.ExecutionHost.SSH,
                    "old-alias",
                    "",
                    List.of(
                        new SshConnectionProfile("first-host", "", "/srv/repository", "bazel"),
                        new SshConnectionProfile("second-host", "", "/srv/repository", "bazel")))))
        .isTrue();
    WorkspaceProfile first =
        WorkspaceProfile.ssh(
            "first",
            "First",
            "first-host",
            OptionalInt.empty(),
            "/srv/repository",
            "bazel",
            OptionalLong.empty());
    WorkspaceProfile second =
        WorkspaceProfile.ssh(
            "second",
            "Second",
            "second-host",
            OptionalInt.empty(),
            "/srv/repository",
            "bazel",
            OptionalLong.empty());

    WorkspaceLauncherHistoryMigration.Result result =
        WorkspaceLauncherHistoryMigration.migrate(settings, List.of(first, second));

    assertThat(result.complete()).isFalse();
    assertThat(result.workspacesUpdated()).isZero();
    assertThat(result.diagnostics())
        .singleElement()
        .asString()
        .contains("could not be matched safely");
    assertThat(WorkspaceUiSettings.workspace(settings, first.id())).doesNotExist();
    assertThat(WorkspaceUiSettings.workspace(settings, second.id())).doesNotExist();
  }

  @Test
  void migrationNeverPerformsDiskIoOnTheEventThread() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            WorkspaceLauncherHistoryMigration.migrate(settings, List.of());
          } catch (Throwable thrown) {
            failure.set(thrown);
          }
        });

    assertThat(failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not run on the EDT");
  }
}

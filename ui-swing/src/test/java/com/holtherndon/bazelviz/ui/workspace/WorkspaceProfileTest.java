package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceProfileTest {

  @Test
  void openWindowsHaveStableIndependentSettingsDirectories() {
    Path settings = Path.of("settings");

    Path first = WorkspaceUiSettings.workspace(settings, "workspace/one");
    Path same = WorkspaceUiSettings.workspace(settings, "workspace/one");
    Path second = WorkspaceUiSettings.workspace(settings, "workspace/two");

    assertThat(first).isEqualTo(same);
    assertThat(first).isNotEqualTo(second);
    assertThat(first.getParent()).isEqualTo(settings.resolve("workspace-windows"));
    assertThat(first.getFileName().toString()).matches("[0-9a-f]{64}").doesNotContain("/");
    assertThat(WorkspaceUiSettings.manager(settings))
        .isEqualTo(settings.resolve("workspace-windows").resolve("manager"));
    assertThat(WorkspaceUiSettings.forWorkspaceWindow(settings, "saved", false))
        .contains(WorkspaceUiSettings.workspace(settings, "saved"));
    assertThat(WorkspaceUiSettings.forWorkspaceWindow(settings, "discovered", true)).isEmpty();
    Path discoveredHistory = WorkspaceUiSettings.discoveredHistory(settings, "discovered");
    assertThat(discoveredHistory)
        .isEqualTo(WorkspaceUiSettings.discoveredHistory(settings, "discovered"))
        .isNotEqualTo(WorkspaceUiSettings.discoveredHistory(settings, "other"));
    assertThat(discoveredHistory.getParent())
        .isEqualTo(settings.resolve("discovered-workspace-history"));
    assertThat(
            WorkspaceUiSettings.discoveredHistory(settings, "discovered").getFileName().toString())
        .matches("[0-9a-f]{64}");
  }

  @Test
  void removedAndOrphanedSavedWorkspaceSettingsAreDeleted(@TempDir Path settings) throws Exception {
    Path retained = WorkspaceUiSettings.workspace(settings, "retained");
    Path orphaned = WorkspaceUiSettings.workspace(settings, "orphaned");
    Path manager = WorkspaceUiSettings.manager(settings);
    Files.createDirectories(retained.resolve("columns"));
    Files.writeString(retained.resolve("launcher.properties"), "retained");
    Files.createDirectories(orphaned.resolve("columns"));
    Files.writeString(orphaned.resolve("columns/actions.json"), "orphaned");
    Files.createDirectories(manager);

    assertThat(WorkspaceUiSettings.deleteOrphans(settings, List.of("retained"))).isEqualTo(1);
    assertThat(retained).isDirectory();
    assertThat(orphaned).doesNotExist();
    assertThat(manager).isDirectory();

    WorkspaceUiSettings.deleteWorkspace(settings, "retained");
    assertThat(retained).doesNotExist();
  }

  @Test
  void localAndSshFactoriesNormalizeConfigurationWithoutStartingAnything() {
    WorkspaceProfile local =
        WorkspaceProfile.local(
            "local-id", "  Main checkout  ", " /code/main ", " bazelisk ", OptionalLong.empty());
    WorkspaceProfile ssh =
        WorkspaceProfile.ssh(
            "ssh-id",
            " Linux checkout ",
            " builder@linux ",
            OptionalInt.of(2222),
            " /srv/main ",
            " bazel ",
            OptionalLong.of(123));

    assertThat(local)
        .extracting(
            WorkspaceProfile::id,
            WorkspaceProfile::label,
            WorkspaceProfile::kind,
            WorkspaceProfile::workingDirectory,
            WorkspaceProfile::bazelExecutable)
        .containsExactly(
            "local-id", "Main checkout", WorkspaceProfile.Kind.LOCAL, "/code/main", "bazelisk");
    assertThat(local.destination()).isEmpty();
    assertThat(local.port()).isEmpty();
    assertThat(local.lastOpenedMicros()).isEmpty();
    assertThat(local.machineKey()).isEqualTo("local");

    assertThat(ssh.destination()).hasValue("builder@linux");
    assertThat(ssh.port()).hasValue(2222);
    assertThat(ssh.machineKey()).isEqualTo("ssh\u001fbuilder@linux\u001f2222");
    assertThat(ssh.machineDisplayName()).isEqualTo("builder@linux:2222");
    assertThat(ssh.lastOpenedMicros()).hasValue(123);
  }

  @Test
  void recentOrderingPlacesNeverOpenedWorkspacesLastAndBreaksTiesDeterministically() {
    WorkspaceProfile oldest =
        WorkspaceProfile.local("old", "Old", "/old", "bazel", OptionalLong.of(5));
    WorkspaceProfile newest =
        WorkspaceProfile.local("new", "New", "/new", "bazel", OptionalLong.of(10));
    WorkspaceProfile neverB =
        WorkspaceProfile.local("b", "Beta", "/b", "bazel", OptionalLong.empty());
    WorkspaceProfile neverA =
        WorkspaceProfile.local("a", "alpha", "/a", "bazel", OptionalLong.empty());
    List<WorkspaceProfile> profiles = new ArrayList<>(List.of(neverB, oldest, neverA, newest));

    profiles.sort(WorkspaceProfile.RECENT_FIRST);

    assertThat(profiles).extracting(WorkspaceProfile::id).containsExactly("new", "old", "a", "b");
    assertThat(oldest.openedAt(99).id()).isEqualTo(oldest.id());
    assertThat(oldest.openedAt(99).lastOpenedMicros()).hasValue(99);
  }

  @Test
  void changingOnlyTheBazelExecutablePreservesWorkspaceIdentityAndConnection() {
    WorkspaceProfile original =
        WorkspaceProfile.ssh(
            "ssh-id",
            "Remote checkout",
            "builder",
            OptionalInt.of(2222),
            "/srv/repo",
            "bazel",
            OptionalLong.of(123));

    WorkspaceProfile changed = original.withBazelExecutable("  /opt/bin/bazelisk  ");

    assertThat(changed.id()).isEqualTo(original.id());
    assertThat(changed.label()).isEqualTo(original.label());
    assertThat(changed.kind()).isEqualTo(original.kind());
    assertThat(changed.destination()).isEqualTo(original.destination());
    assertThat(changed.port()).isEqualTo(original.port());
    assertThat(changed.workingDirectory()).isEqualTo(original.workingDirectory());
    assertThat(changed.lastOpenedMicros()).isEqualTo(original.lastOpenedMicros());
    assertThat(changed.bazelExecutable()).isEqualTo("/opt/bin/bazelisk");
  }

  @Test
  void invalidProfilesAreRejectedAtTheBoundary() {
    assertThatThrownBy(
            () ->
                new WorkspaceProfile(
                    "id",
                    "Local",
                    WorkspaceProfile.Kind.LOCAL,
                    Optional.of("host"),
                    OptionalInt.empty(),
                    "/code",
                    "bazel",
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("local workspace");
    assertThatThrownBy(
            () ->
                WorkspaceProfile.ssh(
                    "id",
                    "Remote",
                    "-oProxyCommand=bad",
                    OptionalInt.empty(),
                    "/srv/code",
                    "bazel",
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("without options");
    assertThatThrownBy(
            () ->
                WorkspaceProfile.ssh(
                    "id",
                    "Remote",
                    "host",
                    OptionalInt.of(70_000),
                    "/srv/code",
                    "bazel",
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
    assertThatThrownBy(
            () -> WorkspaceProfile.local("id", "bad\nname", "/code", "bazel", OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control characters");
    assertThatThrownBy(
            () -> WorkspaceProfile.local("id", "Local", "/code", "bazel", OptionalLong.of(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }
}

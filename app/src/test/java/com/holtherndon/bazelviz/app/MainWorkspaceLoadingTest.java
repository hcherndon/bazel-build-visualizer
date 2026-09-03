package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceStore;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainWorkspaceLoadingTest {

  @TempDir Path settingsDirectory;

  @Test
  void missingLegacySettingsDoNotCreateAWorkspaceFromLauncherDefaults() {
    WorkspaceStore store = new WorkspaceStore(settingsDirectory);

    WorkspaceStore.LoadResult result = Main.loadWorkspaces(store, settingsDirectory, 123_000L);

    assertThat(result.source()).isEqualTo(WorkspaceStore.Source.MISSING);
    assertThat(result.workspaces()).isEmpty();
    assertThat(store.file()).doesNotExist();
  }

  @Test
  void savedWorkspacesLoadWithoutLegacyLauncherSettings() {
    WorkspaceProfile saved =
        WorkspaceProfile.local(
            "saved-id", "Saved repository", "/code/saved", "bazelisk", OptionalLong.of(42));
    WorkspaceStore store = new WorkspaceStore(settingsDirectory);
    assertThat(store.save(List.of(saved))).isTrue();

    WorkspaceStore.LoadResult result = Main.loadWorkspaces(store, settingsDirectory, 123_000L);

    assertThat(result.source()).isEqualTo(WorkspaceStore.Source.STORED);
    assertThat(result.workspaces()).containsExactly(saved);
  }

  @Test
  void existingLegacyLauncherSettingsMigrateOnce() {
    LauncherStateStore legacy = new LauncherStateStore(settingsDirectory);
    assertThat(
            legacy.save(
                new LauncherStateStore.State(
                    "/code/legacy",
                    "bazelisk",
                    CapturePreset.defaultPreset(),
                    "test //...",
                    List.of())))
        .isTrue();
    WorkspaceStore store = new WorkspaceStore(settingsDirectory);

    WorkspaceStore.LoadResult migrated = Main.loadWorkspaces(store, settingsDirectory, 123_000L);
    WorkspaceStore.LoadResult reloaded = Main.loadWorkspaces(store, settingsDirectory, 999_000L);

    assertThat(migrated.source()).isEqualTo(WorkspaceStore.Source.LEGACY_MIGRATION);
    assertThat(migrated.persisted()).isTrue();
    assertThat(migrated.workspaces())
        .singleElement()
        .satisfies(
            profile -> {
              assertThat(profile.workingDirectory()).isEqualTo("/code/legacy");
              assertThat(profile.bazelExecutable()).isEqualTo("bazelisk");
              assertThat(profile.lastOpenedMicros()).hasValue(123_000L);
            });
    assertThat(reloaded.source()).isEqualTo(WorkspaceStore.Source.STORED);
    assertThat(reloaded.workspaces()).isEqualTo(migrated.workspaces());
  }
}

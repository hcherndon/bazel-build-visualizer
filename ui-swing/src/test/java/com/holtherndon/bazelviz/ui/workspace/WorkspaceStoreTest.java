package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import com.holtherndon.bazelviz.ui.capture.SshConnectionProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceStoreTest {

  @TempDir Path settings;

  @Test
  void missingSettingsAreANormalEmptyStateAndPerformNoWrite() {
    WorkspaceStore store = new WorkspaceStore(settings);

    WorkspaceStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.workspaces()).isEmpty();
    assertThat(loaded.source()).isEqualTo(WorkspaceStore.Source.MISSING);
    assertThat(loaded.persisted()).isFalse();
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(store.file()).isEqualTo(settings.resolve("workspaces.properties"));
    assertThat(store.file()).doesNotExist();
  }

  @Test
  void localAndSshProfilesRoundTripNewestFirst() {
    WorkspaceProfile neverOpened =
        WorkspaceProfile.local("never", "Never", "/code/never", "bazel", OptionalLong.empty());
    WorkspaceProfile older =
        WorkspaceProfile.ssh(
            "older",
            "Remote repo",
            "linux",
            OptionalInt.of(2222),
            "/srv/repo",
            "bazelisk",
            OptionalLong.of(25));
    WorkspaceProfile newest =
        WorkspaceProfile.local(
            "newest", "Local repo", "/code/repo", "/opt/bazel", OptionalLong.of(50));
    WorkspaceStore store = new WorkspaceStore(settings);

    WorkspaceStore.SaveResult saved =
        store.saveWithDiagnostics(List.of(neverOpened, older, newest));
    WorkspaceStore.LoadResult loaded = new WorkspaceStore(settings).loadWithDiagnostics();

    assertThat(saved.saved()).isTrue();
    assertThat(saved.diagnostics()).isEmpty();
    assertThat(loaded.source()).isEqualTo(WorkspaceStore.Source.STORED);
    assertThat(loaded.persisted()).isTrue();
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(loaded.workspaces()).containsExactly(newest, older, neverOpened);
  }

  @Test
  void malformedOrOversizedSettingsReturnSafeDiagnostics() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("workspaces.properties");
    Files.writeString(file, "format=9\ncount=0\n", StandardCharsets.UTF_8);

    WorkspaceStore.LoadResult malformed = new WorkspaceStore(settings).loadWithDiagnostics();

    assertThat(malformed.workspaces()).isEmpty();
    assertThat(malformed.source()).isEqualTo(WorkspaceStore.Source.UNUSABLE);
    assertThat(malformed.diagnostics())
        .containsExactly("Saved workspaces are invalid; no workspace was loaded.");
    assertThat(malformed.diagnostics().getFirst())
        .doesNotContain(file.toString())
        .doesNotContain("format=9");

    byte[] tooLarge = new byte[(int) WorkspaceStore.MAX_SETTINGS_FILE_BYTES + 1];
    Files.write(file, tooLarge);
    WorkspaceStore.LoadResult oversized = new WorkspaceStore(settings).loadWithDiagnostics();
    assertThat(oversized.source()).isEqualTo(WorkspaceStore.Source.UNUSABLE);
    assertThat(oversized.diagnostics())
        .containsExactly("Saved workspaces are invalid; no workspace was loaded.");
  }

  @Test
  void ioFailureReturnsASafeDiagnostic() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("workspaces.properties");
    Files.writeString(file, "format=1\ncount=0\n", StandardCharsets.UTF_8);
    WorkspaceStore store =
        new WorkspaceStore(
            settings,
            (temporary, destination) -> {},
            ignored -> {
              throw new IOException("secret file value");
            });

    WorkspaceStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.source()).isEqualTo(WorkspaceStore.Source.UNUSABLE);
    assertThat(loaded.diagnostics())
        .containsExactly("Saved workspaces could not be read; no workspace was loaded.");
    assertThat(loaded.diagnostics().getFirst())
        .doesNotContain("secret file value")
        .doesNotContain(file.toString());
  }

  @Test
  void failedReplacementPreservesPreviousFileAndRemovesTheTemporaryFile() throws Exception {
    WorkspaceProfile first =
        WorkspaceProfile.local("first", "First", "/first", "bazel", OptionalLong.of(1));
    WorkspaceProfile replacement =
        WorkspaceProfile.local("second", "Second", "/second", "bazel", OptionalLong.of(2));
    assertThat(new WorkspaceStore(settings).save(List.of(first))).isTrue();
    WorkspaceStore failing =
        new WorkspaceStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("disk unavailable");
            });

    WorkspaceStore.SaveResult result = failing.saveWithDiagnostics(List.of(replacement));

    assertThat(result.saved()).isFalse();
    assertThat(result.diagnostics())
        .containsExactly("Workspaces could not be saved; the previous list is unchanged.");
    assertThat(new WorkspaceStore(settings).load()).containsExactly(first);
    try (var files = Files.list(settings)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactly("workspaces.properties");
    }
  }

  @Test
  void countAndIdentifierBoundsRejectTheWholeSnapshotWithoutTruncation() {
    WorkspaceProfile original =
        WorkspaceProfile.local("original", "Original", "/original", "bazel", OptionalLong.of(1));
    WorkspaceStore store = new WorkspaceStore(settings);
    assertThat(store.save(List.of(original))).isTrue();

    List<WorkspaceProfile> tooMany = new ArrayList<>();
    for (int index = 0; index <= WorkspaceStore.MAX_SAVED_WORKSPACES; index++) {
      tooMany.add(
          WorkspaceProfile.local(
              "id-" + index,
              "Workspace " + index,
              "/workspaces/" + index,
              "bazel",
              OptionalLong.empty()));
    }
    WorkspaceStore.SaveResult bounded = store.saveWithDiagnostics(tooMany);
    WorkspaceStore.SaveResult duplicate = store.saveWithDiagnostics(List.of(original, original));

    assertThat(bounded.saved()).isFalse();
    assertThat(bounded.diagnostics().getFirst())
        .contains(Integer.toString(WorkspaceStore.MAX_SAVED_WORKSPACES));
    assertThat(duplicate.saved()).isFalse();
    assertThat(duplicate.diagnostics().getFirst()).contains("invalid");
    assertThat(new WorkspaceStore(settings).load()).containsExactly(original);
  }

  @Test
  void legacyMigrationIsPureDeterministicAndKeepsRepositoriesOnTheSameMachine() {
    LauncherStateStore.State legacy =
        new LauncherStateStore.State(
            "/srv/current",
            "bazelisk",
            CapturePreset.defaultPreset(),
            "test //...",
            List.of(),
            LauncherStateStore.ExecutionHost.SSH,
            "build-linux",
            "2222",
            List.of(
                new SshConnectionProfile("build-linux", "2222", "/srv/current", "old-bazel"),
                new SshConnectionProfile("build-linux", "2222", "/srv/other", "bazel")));

    WorkspaceStore.MigrationResult first = WorkspaceStore.migrateLegacy(legacy, 1_000);
    WorkspaceStore.MigrationResult second = WorkspaceStore.migrateLegacy(legacy, 1_000);

    assertThat(first).isEqualTo(second);
    assertThat(first.diagnostics()).isEmpty();
    assertThat(first.workspaces()).hasSize(2);
    assertThat(first.workspaces())
        .extracting(WorkspaceProfile::workingDirectory)
        .containsExactly("/srv/current", "/srv/other");
    assertThat(first.workspaces())
        .extracting(WorkspaceProfile::bazelExecutable)
        .containsExactly("bazelisk", "bazel");
    assertThat(first.workspaces())
        .extracting(WorkspaceProfile::machineKey)
        .containsOnly("ssh\u001fbuild-linux\u001f2222");
    assertThat(first.workspaces().get(0).lastOpenedMicros()).hasValue(1_000);
    assertThat(first.workspaces().get(1).lastOpenedMicros()).hasValue(999);
    assertThat(settings.resolve("workspaces.properties")).doesNotExist();
  }

  @Test
  void missingFileCanBeMigratedAtomicallyButAnInvalidFileIsNeverOverwritten() throws Exception {
    LauncherStateStore.State local =
        new LauncherStateStore.State(
            "/code/project", "bazel", CapturePreset.defaultPreset(), "", List.of());
    WorkspaceStore store = new WorkspaceStore(settings);

    WorkspaceStore.LoadResult migrated = store.loadOrMigrate(local, 42);

    assertThat(migrated.source()).isEqualTo(WorkspaceStore.Source.LEGACY_MIGRATION);
    assertThat(migrated.persisted()).isTrue();
    assertThat(migrated.workspaces()).hasSize(1);
    assertThat(new WorkspaceStore(settings).load()).isEqualTo(migrated.workspaces());

    Files.writeString(store.file(), "format=broken\n", StandardCharsets.UTF_8);
    WorkspaceStore.LoadResult invalid = store.loadOrMigrate(local, 99);
    assertThat(invalid.source()).isEqualTo(WorkspaceStore.Source.UNUSABLE);
    assertThat(Files.readString(store.file(), StandardCharsets.UTF_8)).isEqualTo("format=broken\n");
  }

  @Test
  void settingsIoRejectsTheSwingEventThreadWhileMigrationDoesNotUseIo() throws Exception {
    WorkspaceStore store = new WorkspaceStore(settings);
    LauncherStateStore.State legacy =
        new LauncherStateStore.State(
            "/code", "bazel", CapturePreset.defaultPreset(), "", List.of());

    SwingUtilities.invokeAndWait(
        () -> {
          assertThatThrownBy(store::load)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThatThrownBy(() -> store.save(List.of()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThat(WorkspaceStore.migrateLegacy(legacy, 1).workspaces()).hasSize(1);
        });
  }
}

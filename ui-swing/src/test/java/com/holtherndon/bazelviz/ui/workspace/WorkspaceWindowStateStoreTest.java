package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceWindowStateStoreTest {

  @TempDir Path settings;

  @Test
  void missingFileIsANormalEmptyStateAndPerformsNoWrite() {
    WorkspaceWindowStateStore store = new WorkspaceWindowStateStore(settings);

    WorkspaceWindowStateStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.state()).isEqualTo(WorkspaceWindowState.empty());
    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.MISSING);
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(store.file())
        .isEqualTo(settings.resolve("workspace-window-state.properties"))
        .doesNotExist();
  }

  @Test
  void orderedWindowsOptionalBoundsAndMaximizedStateRoundTrip() {
    WorkspaceWindowState state =
        new WorkspaceWindowState(
            List.of(
                open(
                    "first",
                    Optional.of(new WorkspaceWindowState.WindowBounds(-1200, 80, 1000, 700)),
                    false),
                open("second", Optional.empty(), true),
                open(
                    "third",
                    Optional.of(new WorkspaceWindowState.WindowBounds(60, 40, 800, 600)),
                    true)));
    WorkspaceWindowStateStore store = new WorkspaceWindowStateStore(settings);

    WorkspaceWindowStateStore.SaveResult saved = store.saveWithDiagnostics(state);
    WorkspaceWindowStateStore.LoadResult loaded =
        new WorkspaceWindowStateStore(settings).loadWithDiagnostics();

    assertThat(saved.saved()).isTrue();
    assertThat(saved.diagnostics()).isEmpty();
    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.STORED);
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(loaded.state()).isEqualTo(state);
    assertThat(loaded.state().orderedWorkspaceIds()).containsExactly("first", "second", "third");
  }

  @Test
  void stateIsBoundedUniqueAndDefensivelyCopied() {
    List<WorkspaceWindowState.OpenWorkspace> mutable = new ArrayList<>();
    for (int index = 0; index < WorkspaceWindowState.MAX_OPEN_WORKSPACES; index++) {
      mutable.add(open("workspace-" + index, Optional.empty(), false));
    }

    WorkspaceWindowState maximum = new WorkspaceWindowState(mutable);
    mutable.clear();

    assertThat(maximum.openWorkspaces()).hasSize(WorkspaceWindowState.MAX_OPEN_WORKSPACES);
    assertThatThrownBy(() -> maximum.openWorkspaces().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new WorkspaceWindowState(
                    List.of(
                        open("duplicate", Optional.empty(), false),
                        open("duplicate", Optional.empty(), true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");

    List<WorkspaceWindowState.OpenWorkspace> tooMany = new ArrayList<>();
    for (int index = 0; index <= WorkspaceWindowState.MAX_OPEN_WORKSPACES; index++) {
      tooMany.add(open("workspace-" + index, Optional.empty(), false));
    }
    assertThatThrownBy(() -> new WorkspaceWindowState(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Integer.toString(WorkspaceWindowState.MAX_OPEN_WORKSPACES));
  }

  @Test
  void logicalBoundsValidateSizeWithoutConsultingCurrentScreens() {
    WorkspaceWindowState.WindowBounds offScreen =
        new WorkspaceWindowState.WindowBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 640, 480);

    assertThat(offScreen.x()).isEqualTo(Integer.MIN_VALUE);
    assertThat(offScreen.y()).isEqualTo(Integer.MAX_VALUE);
    assertThat(WorkspaceWindowState.WindowBounds.hasUsableSize(1, 1)).isTrue();
    assertThat(WorkspaceWindowState.WindowBounds.hasUsableSize(0, 1)).isFalse();
    assertThat(WorkspaceWindowState.WindowBounds.hasUsableSize(1, -1)).isFalse();
    assertThatThrownBy(() -> new WorkspaceWindowState.WindowBounds(0, 0, 0, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new WorkspaceWindowState.WindowBounds(0, 0, 10, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void malformedFileFailsHonestlyWithoutReturningAPartialSnapshot() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("workspace-window-state.properties");
    Files.writeString(
        file,
        """
        format=1
        count=2
        window.0.workspaceId=valid
        window.0.maximized=false
        window.0.bounds.present=false
        window.1.workspaceId=broken
        window.1.maximized=maybe
        window.1.bounds.present=false
        """,
        StandardCharsets.UTF_8);

    WorkspaceWindowStateStore.LoadResult loaded =
        new WorkspaceWindowStateStore(settings).loadWithDiagnostics();

    assertThat(loaded.state()).isEqualTo(WorkspaceWindowState.empty());
    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.UNUSABLE);
    assertThat(loaded.diagnostics())
        .containsExactly("Saved Workspace windows are invalid; no windows were restored.");
    assertThat(loaded.diagnostics().getFirst())
        .doesNotContain(file.toString())
        .doesNotContain("maybe");
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("maximized=maybe");
  }

  @Test
  void oversizedCountOnDiskIsRejectedRatherThanTruncated() throws Exception {
    Files.createDirectories(settings);
    Files.writeString(
        settings.resolve("workspace-window-state.properties"),
        "format=1\ncount=" + (WorkspaceWindowState.MAX_OPEN_WORKSPACES + 1) + "\n",
        StandardCharsets.UTF_8);

    WorkspaceWindowStateStore.LoadResult loaded =
        new WorkspaceWindowStateStore(settings).loadWithDiagnostics();

    assertThat(loaded.state().openWorkspaces()).isEmpty();
    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.UNUSABLE);
    assertThat(loaded.diagnostics()).hasSize(1);
  }

  @Test
  void entriesOutsideTheDeclaredCountAreRejectedRatherThanIgnored() throws Exception {
    Files.createDirectories(settings);
    Files.writeString(
        settings.resolve("workspace-window-state.properties"),
        """
        format=1
        count=1
        window.0.workspaceId=first
        window.0.maximized=false
        window.0.bounds.present=false
        window.1.workspaceId=otherwise-ignored
        """,
        StandardCharsets.UTF_8);

    WorkspaceWindowStateStore.LoadResult loaded =
        new WorkspaceWindowStateStore(settings).loadWithDiagnostics();

    assertThat(loaded.state().openWorkspaces()).isEmpty();
    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.UNUSABLE);
    assertThat(loaded.diagnostics())
        .containsExactly("Saved Workspace windows are invalid; no windows were restored.");
  }

  @Test
  void invalidBoundsAreIgnoredWithADiagnosticWithoutDroppingTheWorkspace() throws Exception {
    Files.createDirectories(settings);
    Files.writeString(
        settings.resolve("workspace-window-state.properties"),
        """
        format=1
        count=1
        window.0.workspaceId=still-restored
        window.0.maximized=true
        window.0.bounds.present=true
        window.0.bounds.x=10
        window.0.bounds.y=20
        window.0.bounds.width=0
        window.0.bounds.height=500
        """,
        StandardCharsets.UTF_8);

    WorkspaceWindowStateStore.LoadResult loaded =
        new WorkspaceWindowStateStore(settings).loadWithDiagnostics();

    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.STORED);
    assertThat(loaded.state().orderedWorkspaceIds()).containsExactly("still-restored");
    assertThat(loaded.state().openWorkspaces().getFirst().bounds()).isEmpty();
    assertThat(loaded.state().openWorkspaces().getFirst().maximized()).isTrue();
    assertThat(loaded.diagnostics())
        .containsExactly(
            "Saved bounds for one or more Workspace windows were invalid and will be ignored.");
  }

  @Test
  void ioFailureReturnsASafeDiagnostic() throws Exception {
    Files.createDirectories(settings);
    Path file = settings.resolve("workspace-window-state.properties");
    Files.writeString(file, "format=1\ncount=0\n", StandardCharsets.UTF_8);
    WorkspaceWindowStateStore store =
        new WorkspaceWindowStateStore(
            settings,
            (temporary, destination) -> {},
            ignored -> {
              throw new IOException("secret file value");
            });

    WorkspaceWindowStateStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.source()).isEqualTo(WorkspaceWindowStateStore.Source.UNUSABLE);
    assertThat(loaded.diagnostics())
        .containsExactly("Saved Workspace windows could not be read; no windows were restored.");
    assertThat(loaded.diagnostics().getFirst())
        .doesNotContain("secret file value")
        .doesNotContain(file.toString());
  }

  @Test
  void failedAtomicReplacementPreservesPreviousStateAndRemovesTemporaryFile() throws Exception {
    WorkspaceWindowState original =
        new WorkspaceWindowState(List.of(open("original", Optional.empty(), false)));
    WorkspaceWindowState replacement =
        new WorkspaceWindowState(List.of(open("replacement", Optional.empty(), true)));
    assertThat(new WorkspaceWindowStateStore(settings).save(original)).isTrue();
    WorkspaceWindowStateStore failing =
        new WorkspaceWindowStateStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("disk unavailable");
            });

    WorkspaceWindowStateStore.SaveResult saved = failing.saveWithDiagnostics(replacement);

    assertThat(saved.saved()).isFalse();
    assertThat(saved.diagnostics())
        .containsExactly("Workspace windows could not be saved; the previous layout is unchanged.");
    assertThat(new WorkspaceWindowStateStore(settings).load()).isEqualTo(original);
    try (var files = Files.list(settings)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactly("workspace-window-state.properties");
    }
  }

  @Test
  void fileIoRejectsTheSwingEventThread() throws Exception {
    WorkspaceWindowStateStore store = new WorkspaceWindowStateStore(settings);

    SwingUtilities.invokeAndWait(
        () -> {
          assertThatThrownBy(store::load)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThatThrownBy(() -> store.save(WorkspaceWindowState.empty()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
        });
  }

  private static WorkspaceWindowState.OpenWorkspace open(
      String workspaceId, Optional<WorkspaceWindowState.WindowBounds> bounds, boolean maximized) {
    return new WorkspaceWindowState.OpenWorkspace(workspaceId, bounds, maximized);
  }
}

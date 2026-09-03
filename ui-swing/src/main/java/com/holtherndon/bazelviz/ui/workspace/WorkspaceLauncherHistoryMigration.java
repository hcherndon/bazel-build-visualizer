package com.holtherndon.bazelviz.ui.workspace;

import com.holtherndon.bazelviz.ui.capture.LauncherHistory;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import javax.swing.SwingUtilities;

/** One-time migration of pre-Workspace launcher history into matching saved Workspaces. */
public final class WorkspaceLauncherHistoryMigration {

  private static final String MARKER_FILE = "legacy-launcher-history-migrated";

  private WorkspaceLauncherHistoryMigration() {}

  /**
   * Copies the bounded legacy history into the matching saved Workspace settings.
   *
   * <p>The old launcher had one selected execution context, so its history is copied only to
   * profiles that can be matched to that context. Existing per-Workspace commands stay newer than
   * imported commands. A marker prevents commands that later age out of the bounded history from
   * being reintroduced on another startup. This method performs blocking I/O.
   */
  public static Result migrate(Path settingsDirectory, List<WorkspaceProfile> workspaces) {
    requireBackgroundThread();
    Path settings = Objects.requireNonNull(settingsDirectory, "settingsDirectory");
    List<WorkspaceProfile> saved = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
    Path marker = settings.resolve("workspace-windows").resolve(MARKER_FILE);
    if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
      return Result.alreadyComplete();
    }

    LauncherStateStore legacyStore = new LauncherStateStore(settings);
    if (!Files.isRegularFile(legacyStore.file(), LinkOption.NOFOLLOW_LINKS)) {
      return Result.notNeeded();
    }
    LauncherStateStore.State legacy = legacyStore.load();
    if (legacy.history().isEmpty()) {
      return markComplete(marker, 0);
    }

    List<WorkspaceProfile> matches = matchingWorkspaces(legacy, saved);
    if (matches.isEmpty()) {
      return new Result(
          0,
          false,
          List.of(
              "Legacy Bazel command history was kept because its Workspace "
                  + "could not be matched safely."));
    }

    int updated = 0;
    for (WorkspaceProfile workspace : matches) {
      Path privateSettings = WorkspaceUiSettings.workspace(settings, workspace.id());
      LauncherStateStore targetStore = new LauncherStateStore(privateSettings);
      LauncherStateStore.State current = targetStore.load();
      List<String> mergedHistory = mergeHistory(current.history(), legacy.history());
      LauncherStateStore.State migrated =
          new LauncherStateStore.State(
              workspace.workingDirectory(),
              workspace.bazelExecutable(),
              current.preset(),
              current.command(),
              mergedHistory,
              workspace.kind() == WorkspaceProfile.Kind.LOCAL
                  ? LauncherStateStore.ExecutionHost.LOCAL
                  : LauncherStateStore.ExecutionHost.SSH,
              workspace.destination().orElse(""),
              workspace.port().isPresent() ? Integer.toString(workspace.port().getAsInt()) : "",
              List.of());
      if (!targetStore.save(migrated)) {
        return new Result(
            updated,
            false,
            List.of(
                "Legacy Bazel command history could not be saved for Workspace ‘"
                    + workspace.label()
                    + "’. It will be retried at next startup."));
      }
      updated++;
    }
    return markComplete(marker, updated);
  }

  private static List<WorkspaceProfile> matchingWorkspaces(
      LauncherStateStore.State legacy, List<WorkspaceProfile> workspaces) {
    WorkspaceProfile.Kind kind =
        legacy.executionHost() == LauncherStateStore.ExecutionHost.LOCAL
            ? WorkspaceProfile.Kind.LOCAL
            : WorkspaceProfile.Kind.SSH;
    List<WorkspaceProfile> repositoryMatches =
        workspaces.stream()
            .filter(workspace -> workspace.kind() == kind)
            .filter(
                workspace ->
                    workspace.workingDirectory().strip().equals(legacy.workspace().strip()))
            .toList();
    if (kind == WorkspaceProfile.Kind.LOCAL) {
      return repositoryMatches;
    }
    List<WorkspaceProfile> selectedConnectionMatches =
        repositoryMatches.stream()
            .filter(
                workspace -> sameConnection(workspace, legacy.sshDestination(), legacy.sshPort()))
            .toList();
    if (!selectedConnectionMatches.isEmpty()) {
      return selectedConnectionMatches;
    }
    if (repositoryMatches.size() != 1) {
      return List.of();
    }
    WorkspaceProfile onlyCandidate = repositoryMatches.getFirst();
    boolean savedConnectionMatches =
        legacy.sshProfiles().stream()
            .filter(
                profile -> profile.workingDirectory().strip().equals(legacy.workspace().strip()))
            .anyMatch(
                profile -> sameConnection(onlyCandidate, profile.destination(), profile.port()));
    return savedConnectionMatches ? List.of(onlyCandidate) : List.of();
  }

  private static boolean sameConnection(
      WorkspaceProfile workspace, String destination, String port) {
    String portText = Objects.requireNonNull(port, "port").strip();
    OptionalInt parsedPort = parsePort(portText);
    return (portText.isEmpty() || parsedPort.isPresent())
        && workspace.destination().orElse("").equals(destination.strip())
        && samePort(workspace.port(), parsedPort);
  }

  private static OptionalInt parsePort(String value) {
    String text = Objects.requireNonNull(value, "port").strip();
    if (text.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int port = Integer.parseInt(text);
      return port >= 1 && port <= 65_535 ? OptionalInt.of(port) : OptionalInt.empty();
    } catch (NumberFormatException invalid) {
      return OptionalInt.empty();
    }
  }

  private static boolean samePort(OptionalInt left, OptionalInt right) {
    return left.isPresent() == right.isPresent()
        && (left.isEmpty() || left.getAsInt() == right.getAsInt());
  }

  private static List<String> mergeHistory(List<String> current, List<String> legacy) {
    LinkedHashSet<String> unique = new LinkedHashSet<>(current);
    unique.addAll(legacy);
    return unique.stream().limit(LauncherHistory.MAX_ENTRIES).toList();
  }

  private static Result markComplete(Path marker, int updated) {
    Path temporary = null;
    try {
      Files.createDirectories(marker.getParent());
      temporary = Files.createTempFile(marker.getParent(), ".history-migration-", ".tmp");
      Files.writeString(temporary, "format=1\n", StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
      }
      temporary = null;
      return new Result(updated, true, List.of());
    } catch (IOException | RuntimeException failure) {
      return new Result(
          updated,
          false,
          List.of(
              "Legacy Bazel command history was copied, but its migration marker "
                  + "could not be saved. The copy will be retried safely."));
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException ignored) {
          // The diagnostic above already makes the incomplete migration visible.
        }
      }
    }
  }

  private static void requireBackgroundThread() {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("launcher history migration must not run on the EDT");
    }
  }

  /** Startup migration outcome. Diagnostics are bounded to one actionable line. */
  public record Result(int workspacesUpdated, boolean complete, List<String> diagnostics) {
    public Result {
      if (workspacesUpdated < 0) {
        throw new IllegalArgumentException("workspacesUpdated cannot be negative");
      }
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    private static Result alreadyComplete() {
      return new Result(0, true, List.of());
    }

    private static Result notNeeded() {
      return new Result(0, true, List.of());
    }
  }
}

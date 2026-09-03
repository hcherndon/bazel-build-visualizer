package com.holtherndon.bazelviz.ui.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Stable, collision-resistant settings locations for independently open workspace windows. */
public final class WorkspaceUiSettings {

  private static final String WINDOWS_DIRECTORY = "workspace-windows";
  // Retain the original directory name so upgrading does not strand existing command history.
  private static final String DISCOVERED_HISTORY_DIRECTORY = "discovered-workspace-history";

  private WorkspaceUiSettings() {}

  /** Presentation and launcher state owned by the process-global workspace manager. */
  public static Path manager(Path settingsDirectory) {
    return checkedRoot(settingsDirectory).resolve(WINDOWS_DIRECTORY).resolve("manager");
  }

  /** Presentation and launcher state owned by one stable workspace profile. */
  public static Path workspace(Path settingsDirectory, String workspaceId) {
    Objects.requireNonNull(workspaceId, "workspaceId");
    if (workspaceId.isBlank()) {
      throw new IllegalArgumentException("workspaceId cannot be blank");
    }
    return checkedRoot(settingsDirectory).resolve(WINDOWS_DIRECTORY).resolve(sha256(workspaceId));
  }

  /**
   * Returns full launcher and presentation persistence only for saved profiles. Discovered profiles
   * use the separate launch-preference location below.
   */
  public static Optional<Path> forWorkspaceWindow(
      Path settingsDirectory, String workspaceId, boolean discovered) {
    Objects.requireNonNull(settingsDirectory, "settingsDirectory");
    Objects.requireNonNull(workspaceId, "workspaceId");
    return discovered ? Optional.empty() : Optional.of(workspace(settingsDirectory, workspaceId));
  }

  /**
   * Bounded history and Bazel-command state for a discovered profile, separate from saved Workspace
   * presentation and full launcher state.
   *
   * <p>The deterministic identifier is hashed before it becomes a path. The file stored beneath
   * this directory must not contain the discovered profile's connection or repository fields.
   */
  public static Path discoveredHistory(Path settingsDirectory, String workspaceId) {
    Objects.requireNonNull(workspaceId, "workspaceId");
    if (workspaceId.isBlank()) {
      throw new IllegalArgumentException("workspaceId cannot be blank");
    }
    return checkedRoot(settingsDirectory)
        .resolve(DISCOVERED_HISTORY_DIRECTORY)
        .resolve(sha256(workspaceId));
  }

  /** Removes one saved workspace's private presentation state. Blocking. */
  public static void deleteWorkspace(Path settingsDirectory, String workspaceId)
      throws IOException {
    deleteTree(workspace(settingsDirectory, workspaceId));
  }

  /**
   * Removes hashed settings directories that no longer belong to a saved profile. The manager
   * directory and unrelated files are retained. Blocking.
   */
  public static int deleteOrphans(Path settingsDirectory, Collection<String> savedWorkspaceIds)
      throws IOException {
    Objects.requireNonNull(savedWorkspaceIds, "savedWorkspaceIds");
    Path windows = checkedRoot(settingsDirectory).resolve(WINDOWS_DIRECTORY);
    if (!Files.isDirectory(windows, LinkOption.NOFOLLOW_LINKS)) {
      return 0;
    }
    Set<String> retained = new HashSet<>();
    for (String id : savedWorkspaceIds) {
      retained.add(sha256(Objects.requireNonNull(id, "workspaceId")));
    }
    int removed = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(windows)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (name.matches("[0-9a-f]{64}") && !retained.contains(name)) {
          deleteTree(entry);
          removed++;
        }
      }
    }
    return removed;
  }

  private static Path checkedRoot(Path settingsDirectory) {
    return Objects.requireNonNull(settingsDirectory, "settingsDirectory");
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("this Java runtime has no SHA-256", impossible);
    }
  }

  /** Deletes a known hash directory without following any symbolic link. */
  private static void deleteTree(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(directory)) {
      Files.deleteIfExists(directory);
      return;
    }
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path visited, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.deleteIfExists(visited);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}

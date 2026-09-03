package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Builds a catalog row from a session's manifest.
 *
 * <h2>Why the manifest and not the database</h2>
 *
 * <p>docs/session-format.md: catalog and manifest must agree, with the manifest winning on
 * conflict. Deriving the row from the manifest is what makes that true rather than aspirational —
 * there is no second reading to disagree with — and it is why a rescan can rebuild the whole
 * catalog by reading small JSON files rather than opening every session database.
 *
 * <h2>This lives here rather than in the catalog</h2>
 *
 * <p>{@code storage-sqlite} owns the catalog and knows nothing about manifests; {@code
 * session-format} owns manifests and knows nothing about SQLite. The join belongs to whoever
 * depends on both, which is the UI.
 */
public final class CatalogEntries {

  private CatalogEntries() {}

  /** Reads a session directory into a catalog row, or nothing when it is not one. */
  public static Optional<CatalogEntry> read(Path sessionRoot, ManifestReader reader) {
    ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
    if (!layout.isManagedSession()) {
      return Optional.empty();
    }
    try {
      return Optional.of(from(reader.read(sessionRoot), sessionRoot));
    } catch (Exception unreadable) {
      // A directory whose manifest will not parse is not a session this
      // application can list. Leaving it out of the catalog is the same
      // answer the open path gives.
      return Optional.empty();
    }
  }

  /** Reads one manifest. The caller supplies it because SessionManager owns the parsing. */
  @FunctionalInterface
  public interface ManifestReader {
    SessionManifest read(Path sessionRoot) throws Exception;
  }

  /** One catalog row, derived entirely from the manifest and the directory's size. */
  public static CatalogEntry from(SessionManifest manifest, Path sessionRoot) {
    String command = manifest.originalCommand().map(argv -> String.join(" ", argv)).orElse("");
    return new CatalogEntry(
        manifest.sessionId().toString(),
        displayName(manifest, command),
        sessionRoot,
        manifest.workspaceRoot(),
        command.isBlank() ? Optional.empty() : Optional.of(command),
        manifest.bazelVersion(),
        manifest.state().name(),
        OptionalLong.of(manifest.createdMicros()),
        manifest.finalizedMicros(),
        manifest.actionCount(),
        manifest.eventCount(),
        OptionalLong.of(directoryBytes(sessionRoot)),
        manifest.warnings().size(),
        OptionalLong.empty(),
        false,
        false,
        Optional.of(summary(manifest)));
  }

  /**
   * A name a person can pick out of a list.
   *
   * <p>The command if there is one, because that is what the user was doing; the workspace's last
   * segment otherwise; and the session id only when neither exists, since a UUID tells nobody
   * anything.
   */
  private static String displayName(SessionManifest manifest, String command) {
    if (!command.isBlank()) {
      return command.length() <= 80 ? command : command.substring(0, 77) + "…";
    }
    return manifest
        .workspaceRoot()
        .map(root -> Path.of(root).getFileName())
        .map(Object::toString)
        .orElse(manifest.sessionId().toString());
  }

  /** Plan 10.6's "thumbnail/summary metrics", as a line of words. */
  private static String summary(SessionManifest manifest) {
    StringBuilder text = new StringBuilder();
    manifest.actionCount().ifPresent(actions -> text.append(actions).append(" actions"));
    manifest
        .eventCount()
        .ifPresent(
            events -> {
              if (!text.isEmpty()) {
                text.append(" · ");
              }
              text.append(events).append(" events");
            });
    if (!manifest.warnings().isEmpty()) {
      if (!text.isEmpty()) {
        text.append(" · ");
      }
      text.append(manifest.warnings().size()).append(" warnings");
    }
    // An empty summary is the honest answer for a session whose import
    // never reached finalization: the manifest carries no counts, and a
    // "0 actions" line would be the unknown-as-zero mistake.
    return text.isEmpty() ? manifest.state().name().toLowerCase(Locale.ROOT) : text.toString();
  }

  /** The session's size on disk, which retention and the library both show. */
  private static long directoryBytes(Path sessionRoot) {
    try (var walk = Files.walk(sessionRoot)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              file -> {
                try {
                  return Files.size(file);
                } catch (IOException unreadable) {
                  return 0;
                }
              })
          .sum();
    } catch (IOException unreadable) {
      return 0;
    }
  }
}

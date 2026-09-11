package com.holtherndon.bazelviz.ui.audit;

import com.holtherndon.bazelviz.core.repro.ReproComparison;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManifestCodec;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator.ActiveSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** Explicit desktop files only. Neither a manifest nor an audit record can reconnect a machine. */
final class ComparisonSources {
  static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;

  record Source(Path path, Optional<Path> session, Optional<String> sha256) {
    Source {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(sha256, "sha256");
    }

    static Source selected(Path path) {
      return new Source(path, Optional.empty(), Optional.empty());
    }
  }

  record Resolved(Path log, ActiveSession lease) implements AutoCloseable {
    @Override
    public void close() {
      if (lease != null) lease.close();
    }
  }

  private ComparisonSources() {}

  static ReproComparison open(
      Source a,
      Source b,
      Path scratch,
      SessionMutationCoordinator sessions,
      BooleanSupplier cancelled)
      throws IOException {
    try (Resolved left = resolve(a, sessions, cancelled);
        Resolved right = resolve(b, sessions, cancelled)) {
      // The engine owns private copies before these short-lived retention leases are released.
      return ExecutionLogComparison.open(
          left.log(), right.log(), scratch, cancelled, a.sha256(), b.sha256());
    }
  }

  static Resolved resolve(
      Source source, SessionMutationCoordinator sessions, BooleanSupplier cancelled)
      throws IOException {
    if (SwingUtilities.isEventDispatchThread())
      throw new IllegalStateException("Comparison sources must open off the EDT.");
    checkCancelled(cancelled);
    Path selected = source.path().toRealPath();
    boolean directorySelected = source.session().isEmpty() && Files.isDirectory(selected);
    Path directory = source.session().map(path -> path.toAbsolutePath().normalize()).orElse(null);
    if (directorySelected) directory = selected;
    if (directory == null
        && selected.getParent() != null
        && selected.getParent().getFileName() != null
        && selected
            .getParent()
            .getFileName()
            .toString()
            .equals(ManagedSessionLayout.SessionDirectory.RAW.directoryName())) {
      Path candidate = selected.getParent().getParent();
      if (candidate != null
          && Files.exists(
              ManagedSessionLayout.at(candidate).manifestFile(), LinkOption.NOFOLLOW_LINKS))
        directory = candidate;
    }
    if (directory == null) return new Resolved(regularFile(selected, null), null);
    directory = directory.toRealPath();
    if (!Files.isDirectory(directory))
      throw new IOException("The source session is not a directory.");
    ManagedSessionLayout layout = ManagedSessionLayout.at(directory);
    String id = sessionId(layout, cancelled);
    var directoryId = ManagedSessionLayout.sessionIdFromDirectoryName(directory);
    if (directoryId.isPresent() && !directoryId.orElseThrow().toString().equals(id)) {
      throw new IOException("The session manifest identity does not match its directory.");
    }
    checkCancelled(cancelled);
    ActiveSession lease;
    try {
      lease = sessions.activate(id, directory);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Comparison source opening was cancelled.", interrupted);
    }
    try {
      checkCancelled(cancelled);
      // Retention could have replaced the directory while activation waited for its lock.
      if (!sessionId(layout, cancelled).equals(id))
        throw new IOException("The session identity changed while opening the comparison source.");
      Path raw = layout.rawDirectory().toRealPath();
      if (!raw.startsWith(directory) || !Files.isDirectory(raw))
        throw new IOException("The preserved raw directory escapes its managed session.");
      if (!directorySelected) return new Resolved(regularFile(selected, raw), lease);
      Path compact = layout.rawDirectory().resolve(InstrumentationPlanner.EXECUTION_LOG_FILE);
      Path binary = layout.rawDirectory().resolve(InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
      boolean hasCompact = Files.exists(compact, LinkOption.NOFOLLOW_LINKS);
      boolean hasBinary = Files.exists(binary, LinkOption.NOFOLLOW_LINKS);
      if (hasCompact == hasBinary) {
        throw new IOException(
            hasCompact
                ? "This session has multiple execution logs. Choose the exact log file."
                : "This session has no preserved compact or binary execution log.");
      }
      checkCancelled(cancelled);
      return new Resolved(regularFile(hasCompact ? compact : binary, raw), lease);
    } catch (IOException | RuntimeException failure) {
      lease.close();
      throw failure;
    }
  }

  private static String sessionId(ManagedSessionLayout layout, BooleanSupplier cancelled)
      throws IOException {
    Path manifest = regularFile(layout.manifestFile(), layout.root());
    BasicFileAttributes before =
        Files.readAttributes(manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (before.size() > MAX_MANIFEST_BYTES)
      throw new IOException("The session manifest exceeds the 2 MiB comparison limit.");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var channel =
            Files.newByteChannel(
                manifest, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        InputStream input = Channels.newInputStream(channel)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        checkCancelled(cancelled);
        if (count > MAX_MANIFEST_BYTES - bytes.size())
          throw new IOException("The session manifest exceeds the 2 MiB comparison limit.");
        bytes.write(buffer, 0, count);
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || before.size() != after.size()
        || bytes.size() != before.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !Objects.equals(before.fileKey(), after.fileKey()))
      throw new IOException("The session manifest changed while being read.");
    checkCancelled(cancelled);
    return SessionManifestCodec.standard()
        .readText(bytes.toString(StandardCharsets.UTF_8), manifest.toString())
        .sessionId()
        .toString();
  }

  private static Path regularFile(Path path, Path boundary) throws IOException {
    Path canonical = path.toRealPath();
    if (boundary != null && !canonical.startsWith(boundary))
      throw new IOException("The comparison source escapes its managed session.");
    if (!Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .isRegularFile())
      throw new IOException(
          "Select a regular execution-log or manifest file, not a directory or special file.");
    return canonical;
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("Comparison source opening was cancelled.");
  }
}

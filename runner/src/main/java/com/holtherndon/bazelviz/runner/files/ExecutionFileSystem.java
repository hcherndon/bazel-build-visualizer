package com.holtherndon.bazelviz.runner.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Blocking file operations scoped to one local or remote execution host.
 *
 * <p>Every method may block and must run away from the Swing EDT. Paths are opaque {@link
 * ExecutionPath}s owned by this instance. Implementations must reject a path from another execution
 * rather than interpreting it locally.
 */
public interface ExecutionFileSystem {

  String executionId();

  /** Interprets raw path text using this execution host's path rules. */
  ExecutionPath path(String value) throws IOException;

  /** Resolves a child using this execution host's path rules. */
  ExecutionPath resolve(ExecutionPath base, String child) throws IOException;

  /** Resolves symbolic links and requires the result to exist. */
  ExecutionPath canonicalize(ExecutionPath path) throws IOException;

  /** True when both paths are on this execution and candidate is beneath root. */
  boolean isWithin(ExecutionPath root, ExecutionPath candidate) throws IOException;

  /** Reads metadata without following the final symbolic link. */
  FileMetadata stat(ExecutionPath path) throws IOException;

  /**
   * Batch-friendly metadata hook. Remote implementations should override it to avoid one network
   * round trip per path. The result must have exactly one row per request, in request order, and
   * each row retains the requested path even when the implementation canonicalizes internally.
   */
  default List<FileMetadata> statAll(List<ExecutionPath> paths) throws IOException {
    Objects.requireNonNull(paths, "paths");
    List<FileMetadata> result = new ArrayList<>(paths.size());
    for (ExecutionPath path : paths) {
      result.add(stat(path));
    }
    return List.copyOf(result);
  }

  /** Lists at most maxEntries direct children. The continuation token is opaque. */
  DirectoryPage list(ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
      throws IOException;

  /** Reads at most maxBytes and fails rather than truncating when the file is larger. */
  FileContents read(ExecutionPath path, long maxBytes) throws IOException;

  /**
   * Streams one file to a local destination and fails rather than truncating. Implementations must
   * not leave a partial destination behind.
   */
  void download(ExecutionPath source, Path localDestination, long maxBytes) throws IOException;

  /** Streams a bounded local file to this execution host. */
  void upload(Path localSource, ExecutionPath destination, long maxBytes, UploadMode mode)
      throws IOException;

  /**
   * Replaces one regular file only when its current bytes match expected. The result contains the
   * stored bytes and their new strong version.
   */
  FileContents replaceAtomically(
      ExecutionPath path, FileVersion expected, byte[] replacement, long maxBytes)
      throws IOException;

  /** A local platform path only when this implementation is local. */
  default Optional<Path> localPath(ExecutionPath path) {
    return Optional.empty();
  }
}

package com.holtherndon.bazelviz.capture.repro;

import com.holtherndon.bazelviz.runner.files.DirectoryPage;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bounded source-byte snapshot, including untracked files, independent of Git availability. */
final class RepositorySnapshot {
  static final int MAX_FILES = 100_000;
  static final int MAX_DEPTH = 64;
  static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
  static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
  static final int DIRECTORY_PAGE_SIZE = 256;
  static final int MAX_PATH_CHARS = 8_192;

  record Result(String fingerprint, long files, long bytes) {}

  private final ExecutionFileSystem files;
  private final ExecutionPath root;
  private final BooleanSupplier cancelled;
  private final MessageDigest digest;
  private long count;
  private long bytes;

  private RepositorySnapshot(
      ExecutionFileSystem files, ExecutionPath root, BooleanSupplier cancelled) {
    this.files = files;
    this.root = root;
    this.cancelled = cancelled;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static Result capture(
      ExecutionFileSystem files, ExecutionPath root, Path destination, BooleanSupplier cancelled)
      throws IOException {
    ExecutionPath canonicalRoot = files.canonicalize(root);
    RepositorySnapshot snapshot = new RepositorySnapshot(files, canonicalRoot, cancelled);
    try (BufferedWriter manifest = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
      snapshot.visit(canonicalRoot, 0, manifest);
    }
    return new Result(
        HexFormat.of().formatHex(snapshot.digest.digest()), snapshot.count, snapshot.bytes);
  }

  private void visit(ExecutionPath directory, int depth, BufferedWriter manifest)
      throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("source snapshot exceeded directory depth " + MAX_DEPTH);
    }
    if (!files.canonicalize(directory).equals(directory)) {
      throw new IOException("source directory changed into a symbolic link during snapshot");
    }
    Optional<String> token = Optional.empty();
    String previous = "";
    do {
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
        throw new IOException("source snapshot cancelled");
      }
      DirectoryPage page = files.list(directory, token, DIRECTORY_PAGE_SIZE);
      for (FileMetadata entry : page.entries()) {
        String path = entry.path().value();
        if (path.length() > MAX_PATH_CHARS || !path.startsWith(root.value() + "/")) {
          throw new IOException("source snapshot encountered an invalid or oversized path");
        }
        // The filesystem's pagination contract is lexically ordered. Refuse a broken cursor.
        if (path.compareTo(previous) <= 0) {
          throw new IOException("source listing changed or did not make ordered progress");
        }
        previous = path;
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (depth == 0 && name.equals(".git")) {
          continue;
        }
        String workspaceDirectoryName = root.value().substring(root.value().lastIndexOf('/') + 1);
        if (depth == 0
            && (Set.of("bazel-bin", "bazel-out", "bazel-testlogs").contains(name)
                || name.equals("bazel-" + workspaceDirectoryName))
            && entry.kind() == FileMetadata.Kind.SYMBOLIC_LINK) {
          continue;
        }
        if (++count > MAX_FILES) {
          throw new IOException("source snapshot exceeded " + MAX_FILES + " entries");
        }
        String relative = path.substring(root.value().length());
        if (entry.isDirectory()) {
          record(relative, "directory", manifest);
          visit(entry.path(), depth + 1, manifest);
        } else if (entry.isRegularFile()) {
          if (entry.bytes().isEmpty() || entry.bytes().getAsLong() > MAX_FILE_BYTES) {
            throw new IOException("source snapshot cannot bound file: " + relative);
          }
          long size = entry.bytes().getAsLong();
          if (size > MAX_TOTAL_BYTES - bytes) {
            throw new IOException("source snapshot exceeded " + MAX_TOTAL_BYTES + " bytes");
          }
          var contents = files.read(entry.path(), MAX_FILE_BYTES);
          bytes += contents.version().bytes();
          if (bytes > MAX_TOTAL_BYTES) {
            throw new IOException("source snapshot exceeded " + MAX_TOTAL_BYTES + " bytes");
          }
          record(
              relative,
              "file:" + contents.version().bytes() + ":" + contents.version().sha256(),
              manifest);
        } else {
          throw new IOException(
              "source snapshot cannot verify this entry (symlink, unavailable or special file): "
                  + relative);
        }
      }
      if (page.nextToken().isPresent() && page.nextToken().equals(token)) {
        throw new IOException("source listing cursor did not advance");
      }
      token = page.nextToken();
    } while (token.isPresent());
  }

  private void record(String path, String identity, BufferedWriter manifest) throws IOException {
    String line =
        Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8))
            + " "
            + identity
            + "\n";
    digest.update(line.getBytes(StandardCharsets.UTF_8));
    manifest.write(line);
  }
}

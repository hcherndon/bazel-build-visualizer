package com.holtherndon.bazelviz.runner.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

/** {@link ExecutionFileSystem} backed by the desktop machine's filesystem. */
public final class LocalExecutionFileSystem implements ExecutionFileSystem {

  public static final String DEFAULT_EXECUTION_ID = "local";

  private static final Comparator<Path> BY_FILE_NAME =
      Comparator.comparing(candidate -> candidate.getFileName().toString());

  private final String executionId;

  public LocalExecutionFileSystem() {
    this(DEFAULT_EXECUTION_ID);
  }

  public LocalExecutionFileSystem(String executionId) {
    this.executionId = Objects.requireNonNull(executionId, "executionId");
    if (executionId.isBlank()) {
      throw new IllegalArgumentException("an execution filesystem needs an id");
    }
  }

  @Override
  public String executionId() {
    return executionId;
  }

  @Override
  public ExecutionPath path(String value) throws IOException {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IOException("a local path cannot be blank or contain NUL");
    }
    try {
      return owned(Path.of(value).toAbsolutePath().normalize());
    } catch (RuntimeException invalid) {
      throw new IOException("invalid local path: " + value, invalid);
    }
  }

  /** Convenience for local composition code without a string round-trip at the call site. */
  public ExecutionPath path(Path value) {
    Objects.requireNonNull(value, "value");
    return owned(value.toAbsolutePath().normalize());
  }

  @Override
  public ExecutionPath resolve(ExecutionPath base, String child) throws IOException {
    Path localBase = requireOwned(base);
    Objects.requireNonNull(child, "child");
    if (child.isBlank() || child.indexOf('\0') >= 0) {
      throw new IOException("a child path cannot be blank or contain NUL");
    }
    try {
      return owned(localBase.resolve(Path.of(child)).normalize());
    } catch (RuntimeException invalid) {
      throw new IOException("invalid path below " + base + ": " + child, invalid);
    }
  }

  @Override
  public ExecutionPath canonicalize(ExecutionPath path) throws IOException {
    return owned(requireOwned(path).toRealPath());
  }

  @Override
  public boolean isWithin(ExecutionPath root, ExecutionPath candidate) throws IOException {
    Path realRoot = requireOwned(root).toRealPath();
    Path realCandidate = requireOwned(candidate).toRealPath();
    return realCandidate.startsWith(realRoot);
  }

  @Override
  public FileMetadata stat(ExecutionPath path) throws IOException {
    Path local = requireOwned(path);
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(local, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      FileMetadata.Kind kind =
          attributes.isRegularFile()
              ? FileMetadata.Kind.REGULAR_FILE
              : attributes.isDirectory()
                  ? FileMetadata.Kind.DIRECTORY
                  : attributes.isSymbolicLink()
                      ? FileMetadata.Kind.SYMBOLIC_LINK
                      : FileMetadata.Kind.OTHER;
      OptionalLong bytes =
          attributes.isRegularFile() ? OptionalLong.of(attributes.size()) : OptionalLong.empty();
      return FileMetadata.present(path, kind, bytes, attributes.lastModifiedTime().toMillis());
    } catch (NoSuchFileException missing) {
      return FileMetadata.missing(path);
    } catch (AccessDeniedException denied) {
      return FileMetadata.inaccessible(path, describe(denied));
    }
  }

  @Override
  public DirectoryPage list(
      ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
      throws IOException {
    Objects.requireNonNull(continuationToken, "continuationToken");
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("directory page size must be positive");
    }
    Path localDirectory = requireOwned(directory).toRealPath();
    if (!Files.isDirectory(localDirectory)) {
      throw new IOException("not a directory: " + directory);
    }
    String afterName = continuationToken.map(LocalExecutionFileSystem::decodeToken).orElse(null);
    PriorityQueue<Path> selected =
        new PriorityQueue<>(Math.min(maxEntries, 1_024), BY_FILE_NAME.reversed());
    long total = 0;
    long afterCount = 0;
    try (DirectoryStream<Path> children = Files.newDirectoryStream(localDirectory)) {
      for (Path child : children) {
        total++;
        String name = child.getFileName().toString();
        if (afterName != null && name.compareTo(afterName) <= 0) {
          continue;
        }
        afterCount++;
        selected.add(child);
        if (selected.size() > maxEntries) {
          selected.remove();
        }
      }
    }
    List<Path> pagePaths = new ArrayList<>(selected);
    pagePaths.sort(BY_FILE_NAME);
    List<FileMetadata> entries = new ArrayList<>(pagePaths.size());
    for (Path child : pagePaths) {
      entries.add(stat(owned(child)));
    }
    Optional<String> next =
        afterCount > pagePaths.size() && !pagePaths.isEmpty()
            ? Optional.of(encodeToken(pagePaths.getLast().getFileName().toString()))
            : Optional.empty();
    return new DirectoryPage(owned(localDirectory), entries, next, OptionalLong.of(total));
  }

  @Override
  public FileContents read(ExecutionPath requested, long maxBytes) throws IOException {
    requirePositiveLimit(maxBytes);
    if (maxBytes > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException(
          "an in-memory file read cannot exceed the JVM byte-array limit");
    }
    Path path = requireOwned(requested).toRealPath();
    BasicFileAttributes before = regularAttributes(path);
    if (before.size() > maxBytes) {
      throw tooLarge(requested, before.size(), maxBytes);
    }
    byte[] bytes;
    try (InputStream input = Files.newInputStream(path)) {
      bytes = boundedBytes(input, requested, maxBytes);
    }
    BasicFileAttributes after = regularAttributes(path);
    requireUnchanged(requested, before, after);
    return contents(owned(path), bytes, after.lastModifiedTime().toMillis());
  }

  @Override
  public void download(ExecutionPath source, Path localDestination, long maxBytes)
      throws IOException {
    Objects.requireNonNull(localDestination, "localDestination");
    requirePositiveLimit(maxBytes);
    Path remoteNamedLocal = requireOwned(source).toRealPath();
    BasicFileAttributes before = regularAttributes(remoteNamedLocal);
    if (before.size() > maxBytes) {
      throw tooLarge(source, before.size(), maxBytes);
    }
    Path destination = localDestination.toAbsolutePath().normalize();
    Path parent = destination.getParent();
    if (parent == null) {
      throw new IOException("a download destination needs a parent directory: " + destination);
    }
    Files.createDirectories(parent);
    Path temporary =
        Files.createTempFile(parent, "." + destination.getFileName() + ".bbv-", ".tmp");
    boolean moved = false;
    try {
      try (InputStream input = Files.newInputStream(remoteNamedLocal);
          OutputStream output =
              Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
        copyBounded(input, output, source, maxBytes);
      }
      BasicFileAttributes after = regularAttributes(remoteNamedLocal);
      requireUnchanged(source, before, after);
      moveReplacement(temporary, destination);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  @Override
  public void upload(Path localSource, ExecutionPath destination, long maxBytes, UploadMode mode)
      throws IOException {
    Objects.requireNonNull(localSource, "localSource");
    Objects.requireNonNull(mode, "mode");
    requirePositiveLimit(maxBytes);
    Path source = localSource.toRealPath();
    BasicFileAttributes before = regularAttributes(source);
    if (before.size() > maxBytes) {
      throw tooLarge(path(source), before.size(), maxBytes);
    }
    Path target = requireOwned(destination);
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new IOException("upload destination has no existing parent directory: " + destination);
    }
    if (mode == UploadMode.CREATE_NEW && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".bbv-", ".tmp");
    boolean moved = false;
    try {
      try (InputStream input = Files.newInputStream(source);
          OutputStream output =
              Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
        copyBounded(input, output, path(source), maxBytes);
      }
      BasicFileAttributes after = regularAttributes(source);
      requireUnchanged(path(source), before, after);
      copyPermissions(source, temporary);
      if (mode == UploadMode.CREATE_NEW) {
        moveNew(temporary, target);
      } else {
        moveReplacement(temporary, target);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  @Override
  public FileContents replaceAtomically(
      ExecutionPath requested, FileVersion expected, byte[] replacement, long maxBytes)
      throws IOException {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(replacement, "replacement");
    requirePositiveLimit(maxBytes);
    if (replacement.length > maxBytes) {
      throw tooLarge(requested, replacement.length, maxBytes);
    }
    FileContents current = read(requested, maxBytes);
    if (!current.version().equals(expected)) {
      throw conflict(requested);
    }
    Path target = requireOwned(current.path());
    Path parent = target.getParent();
    Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".bbv-", ".tmp");
    boolean moved = false;
    try {
      Files.write(temporary, replacement, StandardOpenOption.TRUNCATE_EXISTING);
      copyPermissions(target, temporary);
      // Keep conflict detection adjacent to the replace. This cannot make
      // uncooperative filesystem writers transactional, but it closes the
      // potentially long temporary-file write window.
      if (!read(current.path(), maxBytes).version().equals(expected)) {
        throw conflict(requested);
      }
      moveReplacement(temporary, target);
      moved = true;
      return read(current.path(), maxBytes);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  @Override
  public Optional<Path> localPath(ExecutionPath path) {
    try {
      return Optional.of(requireOwned(path));
    } catch (IOException foreign) {
      return Optional.empty();
    }
  }

  private ExecutionPath owned(Path path) {
    return new ExecutionPath(executionId, path.toAbsolutePath().normalize().toString());
  }

  private Path requireOwned(ExecutionPath path) throws IOException {
    Objects.requireNonNull(path, "path");
    if (!executionId.equals(path.executionId())) {
      throw new IOException(
          "path belongs to execution " + path.executionId() + ", not " + executionId);
    }
    try {
      return Path.of(path.value());
    } catch (RuntimeException invalid) {
      throw new IOException("invalid local path: " + path.value(), invalid);
    }
  }

  private static BasicFileAttributes regularAttributes(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
    if (!attributes.isRegularFile()) {
      throw new IOException("not a regular file: " + path);
    }
    return attributes;
  }

  private static void requireUnchanged(
      ExecutionPath path, BasicFileAttributes before, BasicFileAttributes after)
      throws IOException {
    if (before.size() != after.size()
        || before.lastModifiedTime().toMillis() != after.lastModifiedTime().toMillis()) {
      throw new IOException("the file changed while it was being read; open it again: " + path);
    }
  }

  private static FileContents contents(ExecutionPath path, byte[] bytes, long modifiedMillis) {
    return new FileContents(
        path, bytes, new FileVersion(bytes.length, modifiedMillis, sha256(bytes)));
  }

  private static byte[] boundedBytes(InputStream input, ExecutionPath path, long maxBytes)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 64 * 1024));
    copyBounded(input, output, path, maxBytes);
    return output.toByteArray();
  }

  private static void copyBounded(
      InputStream input, OutputStream output, ExecutionPath path, long maxBytes)
      throws IOException {
    byte[] buffer = new byte[16 * 1024];
    long total = 0;
    while (true) {
      int wanted = (int) Math.min(buffer.length, maxBytes + 1 - total);
      int read = input.read(buffer, 0, wanted);
      if (read < 0) {
        return;
      }
      output.write(buffer, 0, read);
      total += read;
      if (total > maxBytes) {
        throw tooLarge(path, total, maxBytes);
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("the JDK has no SHA-256 implementation", impossible);
    }
  }

  private static void copyPermissions(Path source, Path target) {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
      Files.setPosixFilePermissions(target, permissions);
    } catch (IOException | UnsupportedOperationException ignored) {
      // Windows and some mounted filesystems have no POSIX permissions.
    }
  }

  private static void moveReplacement(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void moveNew(Path source, Path target) throws IOException {
    // A plain move preserves CREATE_NEW even on providers whose atomic
    // move is allowed to replace an existing target implementation-wise.
    Files.move(source, target);
  }

  private static String encodeToken(String name) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(name.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeToken(String token) {
    try {
      return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid directory continuation token", invalid);
    }
  }

  private static void requirePositiveLimit(long maxBytes) {
    if (maxBytes <= 0 || maxBytes >= Long.MAX_VALUE) {
      throw new IllegalArgumentException("file byte limit must be positive and bounded");
    }
  }

  private static IOException tooLarge(ExecutionPath path, long actual, long limit) {
    return new IOException(
        "the file is at least "
            + actual
            + " bytes; the operation limit is "
            + limit
            + " bytes: "
            + path);
  }

  private static FileConflictException conflict(ExecutionPath path) {
    return new FileConflictException(
        "the file changed on disk after this window loaded it."
            + " Reload it before saving so newer work is not overwritten: "
            + path);
  }

  private static String describe(Throwable failure) {
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }
}

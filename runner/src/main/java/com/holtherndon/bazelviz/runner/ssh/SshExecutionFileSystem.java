package com.holtherndon.bazelviz.runner.ssh;

import com.holtherndon.bazelviz.runner.files.DirectoryPage;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileConflictException;
import com.holtherndon.bazelviz.runner.files.FileContents;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.FileVersion;
import com.holtherndon.bazelviz.runner.files.UploadMode;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Linux filesystem operations backed by SSH commands and system SFTP transfers. */
public final class SshExecutionFileSystem implements ExecutionFileSystem {

  private static final Logger log = LoggerFactory.getLogger(SshExecutionFileSystem.class);

  private final String executionId;
  private final String defaultDirectory;
  private final SshCommandExecutor commands;
  private final SftpClient transfers;

  SshExecutionFileSystem(
      String executionId,
      String defaultDirectory,
      SshCommandExecutor commands,
      SftpClient transfers) {
    this.executionId = Objects.requireNonNull(executionId, "executionId");
    if (executionId.isBlank()) {
      throw new IllegalArgumentException("an execution filesystem needs an id");
    }
    this.defaultDirectory = normalizeAbsolute(defaultDirectory);
    this.commands = Objects.requireNonNull(commands, "commands");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    log.info(
        "SSH filesystem ready target={} workingDirectory={}",
        commands.target().displayName(),
        this.defaultDirectory);
  }

  @Override
  public String executionId() {
    return executionId;
  }

  @Override
  public ExecutionPath path(String value) throws IOException {
    Objects.requireNonNull(value, "value");
    try {
      return owned(normalize(value, defaultDirectory));
    } catch (RuntimeException invalid) {
      throw new IOException("invalid remote Linux path: " + value, invalid);
    }
  }

  @Override
  public ExecutionPath resolve(ExecutionPath base, String child) throws IOException {
    String parent = requireOwned(base);
    Objects.requireNonNull(child, "child");
    try {
      return owned(normalize(child, parent));
    } catch (RuntimeException invalid) {
      throw new IOException("invalid path below " + base + ": " + child, invalid);
    }
  }

  @Override
  public ExecutionPath canonicalize(ExecutionPath requested) throws IOException {
    long startedNanos = System.nanoTime();
    log.trace("SSH filesystem operation started operation=canonicalize execution={}", executionId);
    String remote = requireOwned(requested);
    CommandResult result = run(List.of("/usr/bin/readlink", "-f", "--", remote));
    if (!result.isSuccess()) {
      throw new IOException(
          "cannot resolve remote path " + requested + ": " + result.failureDetail());
    }
    ExecutionPath canonical =
        owned(normalizeAbsolute(RemoteText.singleLine(result.stdout(), "remote readlink")));
    log.trace(
        "SSH filesystem operation completed operation=canonicalize execution={}" + " durationMs={}",
        executionId,
        elapsedMillis(startedNanos));
    return canonical;
  }

  @Override
  public boolean isWithin(ExecutionPath root, ExecutionPath candidate) throws IOException {
    String canonicalRoot = canonicalize(root).value();
    String canonicalCandidate = canonicalize(candidate).value();
    return canonicalRoot.equals("/")
        || canonicalCandidate.equals(canonicalRoot)
        || canonicalCandidate.startsWith(canonicalRoot + "/");
  }

  @Override
  public FileMetadata stat(ExecutionPath requested) throws IOException {
    long startedNanos = System.nanoTime();
    log.trace("SSH filesystem operation started operation=stat execution={}", executionId);
    String remote = requireOwned(requested);
    CommandResult result = run(List.of("/usr/bin/stat", "--printf=%F\\0%s\\0%Y\\0", "--", remote));
    if (!result.isSuccess()) {
      String detail = result.failureDetail();
      String lower = detail.toLowerCase(Locale.ROOT);
      if (lower.contains("no such file") || lower.contains("not found")) {
        return loggedStat(FileMetadata.missing(requested), startedNanos);
      }
      if (lower.contains("permission denied") || lower.contains("not permitted")) {
        return loggedStat(FileMetadata.inaccessible(requested, detail), startedNanos);
      }
      return loggedStat(FileMetadata.unavailable(requested, detail), startedNanos);
    }
    String[] fields = result.stdout().split(String.valueOf('\0'), -1);
    if (fields.length < 3) {
      return loggedStat(
          FileMetadata.unavailable(requested, "remote stat returned malformed metadata"),
          startedNanos);
    }
    try {
      FileMetadata.Kind kind = kind(fields[0]);
      long bytes = Long.parseLong(fields[1]);
      long modified = Math.multiplyExact(Long.parseLong(fields[2]), 1_000L);
      return loggedStat(
          FileMetadata.present(
              requested,
              kind,
              kind == FileMetadata.Kind.REGULAR_FILE
                  ? OptionalLong.of(bytes)
                  : OptionalLong.empty(),
              modified),
          startedNanos);
    } catch (ArithmeticException | NumberFormatException malformed) {
      return loggedStat(
          FileMetadata.unavailable(requested, "remote stat returned malformed metadata"),
          startedNanos);
    }
  }

  /** Batches metadata probes while preserving one result per requested path. */
  @Override
  public List<FileMetadata> statAll(List<ExecutionPath> requested) throws IOException {
    Objects.requireNonNull(requested, "requested");
    long startedNanos = System.nanoTime();
    log.debug(
        "SSH filesystem operation started operation=stat-all execution={} pathCount={}",
        executionId,
        requested.size());
    List<FileMetadata> metadata = new ArrayList<>(requested.size());
    for (int start = 0; start < requested.size(); start += 128) {
      int end = Math.min(requested.size(), start + 128);
      List<String> argv = new ArrayList<>();
      argv.add("/bin/sh");
      argv.add("-c");
      argv.add(
          "for bbv_path do "
              + "if bbv_metadata=$(/usr/bin/stat --printf='%F\\037%s\\037%Y' -- "
              + "\"$bbv_path\" 2>&1); then "
              + "printf 'P\\037%s\\0' \"$bbv_metadata\"; else "
              + "printf 'E\\037%s\\0' \"$bbv_metadata\"; fi; done");
      argv.add("bbv-stat");
      for (int index = start; index < end; index++) {
        argv.add(requireOwned(requested.get(index)));
      }
      CommandResult result = run(argv);
      if (!result.isSuccess()) {
        throw new IOException("cannot read remote file metadata: " + result.failureDetail());
      }
      List<String> records = new ArrayList<>();
      for (String record : result.stdout().split(String.valueOf('\0'), -1)) {
        if (!record.isEmpty()) {
          records.add(record);
        }
      }
      if (records.size() != end - start) {
        throw new IOException(
            "remote batch stat returned "
                + records.size()
                + " rows for "
                + (end - start)
                + " paths");
      }
      for (int offset = 0; offset < records.size(); offset++) {
        ExecutionPath path = requested.get(start + offset);
        metadata.add(parseBatchStat(path, records.get(offset)));
      }
    }
    List<FileMetadata> result = List.copyOf(metadata);
    log.debug(
        "SSH filesystem operation completed operation=stat-all execution={}"
            + " pathCount={} durationMs={}",
        executionId,
        result.size(),
        elapsedMillis(startedNanos));
    return result;
  }

  @Override
  public DirectoryPage list(
      ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
      throws IOException {
    Objects.requireNonNull(continuationToken, "continuationToken");
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("directory page size must be positive");
    }
    long startedNanos = System.nanoTime();
    log.debug(
        "SSH filesystem operation started operation=list execution={} pageSize={}"
            + " continuation={}",
        executionId,
        maxEntries,
        continuationToken.isPresent());
    ExecutionPath canonical = canonicalize(directory);
    FileMetadata metadata = stat(canonical);
    if (!metadata.isDirectory()) {
      throw new IOException("not a remote directory: " + directory);
    }
    long offset = continuationToken.map(SshExecutionFileSystem::decodeOffset).orElse(0L);
    long first = Math.addExact(offset, 1);
    int requested = Math.addExact(maxEntries, 1);
    String temporary = "${TMPDIR:-/tmp}/bbv-list.XXXXXX";
    String script =
        "bbv_tmp=$(mktemp \""
            + temporary
            + "\") || exit 1; "
            + "trap 'rm -f -- \"$bbv_tmp\"' EXIT HUP INT TERM; "
            + "/usr/bin/find "
            + PosixShell.quote(canonical.value())
            + " -mindepth 1 -maxdepth 1 -printf '%p\\037%y\\037%s\\037%T@\\0'"
            + " >\"$bbv_tmp\" || exit 1; "
            + "LC_ALL=C /usr/bin/sort -z \"$bbv_tmp\" | /usr/bin/tail -z -n +"
            + first
            + " | /usr/bin/head -z -n "
            + requested;
    CommandResult result = run(List.of("/bin/sh", "-c", script));
    if (!result.isSuccess()) {
      throw new IOException(
          "cannot list remote directory " + directory + ": " + result.failureDetail());
    }
    List<FileMetadata> entries = parseDirectoryRecords(result.stdout());
    boolean hasMore = entries.size() > maxEntries;
    if (hasMore) {
      entries = new ArrayList<>(entries.subList(0, maxEntries));
    }
    Optional<String> next =
        hasMore
            ? Optional.of(encodeOffset(Math.addExact(offset, entries.size())))
            : Optional.empty();
    DirectoryPage page = new DirectoryPage(canonical, entries, next, OptionalLong.empty());
    log.debug(
        "SSH filesystem operation completed operation=list execution={} entryCount={}"
            + " hasMore={} durationMs={}",
        executionId,
        entries.size(),
        hasMore,
        elapsedMillis(startedNanos));
    return page;
  }

  @Override
  public FileContents read(ExecutionPath requested, long maxBytes) throws IOException {
    requirePositiveLimit(maxBytes);
    if (maxBytes > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException(
          "an in-memory file read cannot exceed the JVM byte-array limit");
    }
    long startedNanos = System.nanoTime();
    log.debug(
        "SSH filesystem operation started operation=read execution={} maximumBytes={}",
        executionId,
        maxBytes);
    ExecutionPath canonical = canonicalize(requested);
    Path temporary = Files.createTempFile("bbv-sftp-read-", ".tmp");
    try {
      downloadToTemporary(canonical, temporary, maxBytes);
      byte[] bytes = Files.readAllBytes(temporary);
      FileMetadata after = requireRegular(canonical);
      FileContents contents = contents(canonical, bytes, after.modifiedMillis().orElse(0));
      log.debug(
          "SSH filesystem operation completed operation=read execution={} bytes={}"
              + " durationMs={}",
          executionId,
          bytes.length,
          elapsedMillis(startedNanos));
      return contents;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public void download(ExecutionPath source, Path localDestination, long maxBytes)
      throws IOException {
    Objects.requireNonNull(localDestination, "localDestination");
    requirePositiveLimit(maxBytes);
    long startedNanos = System.nanoTime();
    log.info(
        "SSH filesystem transfer started operation=download execution={} maximumBytes={}",
        executionId,
        maxBytes);
    ExecutionPath canonical = canonicalize(source);
    Path destination = localDestination.toAbsolutePath().normalize();
    Path parent = destination.getParent();
    if (parent == null) {
      throw new IOException("a download destination needs a parent directory");
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, ".bbv-sftp-download-", ".tmp");
    boolean moved = false;
    try {
      downloadToTemporary(canonical, temporary, maxBytes);
      long downloadedBytes = Files.size(temporary);
      moveReplacement(temporary, destination);
      moved = true;
      log.info(
          "SSH filesystem transfer completed operation=download execution={} bytes={}"
              + " durationMs={}",
          executionId,
          downloadedBytes,
          elapsedMillis(startedNanos));
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
    if (!Files.isRegularFile(source)) {
      throw new IOException("not a regular local file: " + localSource);
    }
    long bytes = Files.size(source);
    if (bytes > maxBytes) {
      throw tooLarge(destination, bytes, maxBytes);
    }
    long startedNanos = System.nanoTime();
    log.info(
        "SSH filesystem transfer started operation=upload execution={} bytes={} mode={}",
        executionId,
        bytes,
        mode);
    String target = requireOwned(destination);
    ExecutionPath parent = owned(parentOf(target));
    if (!stat(parent).isDirectory()) {
      throw new IOException("upload destination has no existing remote parent: " + destination);
    }
    String temporary = temporarySibling(target, "upload");
    boolean installed = false;
    try {
      transferUpload(source, temporary, maxBytes);
      CommandResult result;
      if (mode == UploadMode.CREATE_NEW) {
        result =
            runScript(
                "/bin/ln -- "
                    + PosixShell.quote(temporary)
                    + " "
                    + PosixShell.quote(target)
                    + " && { /bin/rm -f -- "
                    + PosixShell.quote(temporary)
                    + " || :; }");
      } else {
        result = run(List.of("/bin/mv", "-f", "--", temporary, target));
      }
      if (!result.isSuccess()) {
        if (mode == UploadMode.CREATE_NEW
            && stat(destination).state() == FileMetadata.State.PRESENT) {
          throw new FileAlreadyExistsException(target);
        }
        throw new IOException(
            "cannot install remote upload " + destination + ": " + result.failureDetail());
      }
      installed = true;
      log.info(
          "SSH filesystem transfer completed operation=upload execution={} bytes={}"
              + " mode={} durationMs={}",
          executionId,
          bytes,
          mode,
          elapsedMillis(startedNanos));
    } finally {
      if (!installed) {
        removeRemoteTemporary(temporary);
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
    long startedNanos = System.nanoTime();
    log.info(
        "SSH filesystem edit started execution={} replacementBytes={} maximumBytes={}",
        executionId,
        replacement.length,
        maxBytes);
    ExecutionPath canonical = canonicalize(requested);
    String target = canonical.value();
    String temporary = temporarySibling(target, "edit");
    Path local = Files.createTempFile("bbv-remote-edit-", ".tmp");
    boolean installed = false;
    try {
      Files.write(local, replacement, StandardOpenOption.TRUNCATE_EXISTING);
      transferUpload(local, temporary, maxBytes);
      // Lock the current inode without writing it. A predictable lock
      // name in /tmp would let another account plant a symlink there;
      // opening the canonical target read-only avoids that filesystem
      // side effect while still serializing BBV editors around the
      // adjacent hash check and replacement.
      String script =
          "exec 9<"
              + PosixShell.quote(target)
              + "; "
              + "/usr/bin/flock -x 9 || exit 74; "
              + "bbv_hash=$(/usr/bin/sha256sum -- "
              + PosixShell.quote(target)
              + ") || exit 75; bbv_hash=${bbv_hash%% *}; "
              + "bbv_size=$(/usr/bin/stat --printf=%s -- "
              + PosixShell.quote(target)
              + ") || exit 75; "
              + "[ \"$bbv_hash\" = "
              + PosixShell.quote(expected.sha256())
              + " ] && [ \"$bbv_size\" = "
              + PosixShell.quote(Long.toString(expected.bytes()))
              + " ] || exit 73; "
              + "/bin/chmod --reference="
              + PosixShell.quote(target)
              + " -- "
              + PosixShell.quote(temporary)
              + " || exit 75; "
              + "/bin/mv -f -- "
              + PosixShell.quote(temporary)
              + " "
              + PosixShell.quote(target);
      CommandResult result = runScript(script);
      if (result.exitCode() == 73) {
        throw new FileConflictException(
            "the remote file changed after this window loaded it."
                + " Reload it before saving so newer work is not overwritten: "
                + requested);
      }
      if (!result.isSuccess()) {
        throw new IOException(
            "cannot replace remote file " + requested + ": " + result.failureDetail());
      }
      installed = true;
      FileContents contents = read(canonical, maxBytes);
      log.info(
          "SSH filesystem edit completed execution={} replacementBytes={}" + " durationMs={}",
          executionId,
          replacement.length,
          elapsedMillis(startedNanos));
      return contents;
    } finally {
      Files.deleteIfExists(local);
      if (!installed) {
        removeRemoteTemporary(temporary);
      }
    }
  }

  private void downloadToTemporary(ExecutionPath source, Path temporary, long maxBytes)
      throws IOException {
    FileMetadata before = requireRegular(source);
    long declared = before.bytes().orElseThrow();
    if (declared > maxBytes) {
      throw tooLarge(source, declared, maxBytes);
    }
    try {
      transfers.download(source.value(), temporary, maxBytes);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while downloading " + source, interrupted);
    }
    long actual = Files.size(temporary);
    if (actual > maxBytes) {
      throw tooLarge(source, actual, maxBytes);
    }
    FileMetadata after = requireRegular(source);
    if (!sameSnapshot(before, after) || actual != after.bytes().orElseThrow()) {
      throw new IOException("the remote file changed while it was being downloaded: " + source);
    }
  }

  private void transferUpload(Path source, String destination, long maxBytes) throws IOException {
    try {
      transfers.upload(source, destination, maxBytes);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while uploading " + destination, interrupted);
    }
  }

  private CommandResult run(List<String> argv) throws IOException {
    try {
      return commands.run(CommandRequest.of(argv, (String) null), Duration.ofSeconds(30));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while reading the remote filesystem", interrupted);
    }
  }

  private CommandResult runScript(String script) throws IOException {
    return run(List.of("/bin/sh", "-c", script));
  }

  private FileMetadata requireRegular(ExecutionPath path) throws IOException {
    FileMetadata metadata = stat(path);
    if (!metadata.isRegularFile()) {
      throw new IOException(
          "not a regular remote file: "
              + path
              + metadata.detail().map(detail -> " (" + detail + ")").orElse(""));
    }
    return metadata;
  }

  private void removeRemoteTemporary(String path) {
    try {
      run(List.of("/bin/rm", "-f", "--", path));
    } catch (IOException ignored) {
      // A failed operation already has the useful error. This is cleanup only.
    }
  }

  private ExecutionPath owned(String remote) {
    return new ExecutionPath(executionId, remote);
  }

  private String requireOwned(ExecutionPath path) throws IOException {
    Objects.requireNonNull(path, "path");
    if (!executionId.equals(path.executionId())) {
      throw new IOException(
          "path belongs to execution " + path.executionId() + ", not " + executionId);
    }
    return normalizeAbsolute(path.value());
  }

  private List<FileMetadata> parseDirectoryRecords(String output) throws IOException {
    List<FileMetadata> entries = new ArrayList<>();
    for (String record : output.split(String.valueOf('\0'), -1)) {
      if (record.isEmpty()) {
        continue;
      }
      String[] fields = record.split(String.valueOf('\u001f'), -1);
      if (fields.length != 4) {
        throw new IOException("remote directory listing returned malformed metadata");
      }
      try {
        ExecutionPath path = owned(normalizeAbsolute(fields[0]));
        FileMetadata.Kind kind =
            switch (fields[1]) {
              case "f" -> FileMetadata.Kind.REGULAR_FILE;
              case "d" -> FileMetadata.Kind.DIRECTORY;
              case "l" -> FileMetadata.Kind.SYMBOLIC_LINK;
              default -> FileMetadata.Kind.OTHER;
            };
        long bytes = Long.parseLong(fields[2]);
        long modified = new BigDecimal(fields[3]).movePointRight(3).longValue();
        entries.add(
            FileMetadata.present(
                path,
                kind,
                kind == FileMetadata.Kind.REGULAR_FILE
                    ? OptionalLong.of(bytes)
                    : OptionalLong.empty(),
                modified));
      } catch (ArithmeticException | NumberFormatException malformed) {
        throw new IOException("remote directory listing returned malformed metadata", malformed);
      }
    }
    return entries;
  }

  private static FileMetadata parseBatchStat(ExecutionPath path, String record) {
    String[] fields = record.split(String.valueOf('\u001f'), -1);
    if (fields.length == 2 && fields[0].equals("E")) {
      String detail = fields[1];
      String lower = detail.toLowerCase(Locale.ROOT);
      if (lower.contains("no such file") || lower.contains("not found")) {
        return FileMetadata.missing(path);
      }
      if (lower.contains("permission denied") || lower.contains("not permitted")) {
        return FileMetadata.inaccessible(path, detail);
      }
      return FileMetadata.unavailable(path, detail);
    }
    if (fields.length != 4 || !fields[0].equals("P")) {
      return FileMetadata.unavailable(path, "remote batch stat returned malformed metadata");
    }
    try {
      FileMetadata.Kind kind = kind(fields[1]);
      long bytes = Long.parseLong(fields[2]);
      long modified = Math.multiplyExact(Long.parseLong(fields[3]), 1_000L);
      return FileMetadata.present(
          path,
          kind,
          kind == FileMetadata.Kind.REGULAR_FILE ? OptionalLong.of(bytes) : OptionalLong.empty(),
          modified);
    } catch (ArithmeticException | NumberFormatException malformed) {
      return FileMetadata.unavailable(path, "remote batch stat returned malformed metadata");
    }
  }

  private static FileMetadata.Kind kind(String description) {
    String value = description.toLowerCase(Locale.ROOT);
    if (value.contains("regular file")) {
      return FileMetadata.Kind.REGULAR_FILE;
    }
    if (value.equals("directory")) {
      return FileMetadata.Kind.DIRECTORY;
    }
    if (value.contains("symbolic link")) {
      return FileMetadata.Kind.SYMBOLIC_LINK;
    }
    return FileMetadata.Kind.OTHER;
  }

  private FileMetadata loggedStat(FileMetadata metadata, long startedNanos) {
    log.trace(
        "SSH filesystem operation completed operation=stat execution={} state={}"
            + " kind={} durationMs={}",
        executionId,
        metadata.state(),
        metadata.kind(),
        elapsedMillis(startedNanos));
    return metadata;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static FileContents contents(ExecutionPath path, byte[] bytes, long modifiedMillis) {
    return new FileContents(
        path, bytes, new FileVersion(bytes.length, modifiedMillis, sha256(bytes)));
  }

  private static String normalize(String value, String base) {
    if (value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("a remote path cannot be blank or contain NUL");
    }
    return value.startsWith("/") ? normalizeAbsolute(value) : normalizeAbsolute(base + "/" + value);
  }

  private static String normalizeAbsolute(String value) {
    Objects.requireNonNull(value, "value");
    if (!value.startsWith("/") || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("a remote Linux path must be absolute: " + value);
    }
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : value.split("/", -1)) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (!parts.isEmpty()) {
          parts.removeLast();
        }
      } else {
        parts.addLast(part);
      }
    }
    return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
  }

  private static String parentOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "/" : path.substring(0, slash);
  }

  private static String temporarySibling(String target, String purpose) {
    String parent = parentOf(target);
    String name = target.substring(target.lastIndexOf('/') + 1);
    return (parent.equals("/") ? "" : parent)
        + "/."
        + name
        + ".bbv-"
        + purpose
        + "-"
        + UUID.randomUUID();
  }

  private static boolean sameSnapshot(FileMetadata left, FileMetadata right) {
    return left.state() == right.state()
        && left.kind() == right.kind()
        && left.bytes().equals(right.bytes())
        && left.modifiedMillis().equals(right.modifiedMillis());
  }

  private static String encodeOffset(long offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Long.toString(offset).getBytes(StandardCharsets.US_ASCII));
  }

  private static long decodeOffset(String token) {
    try {
      String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
      long offset = Long.parseLong(value);
      if (offset < 0) {
        throw new NumberFormatException("negative");
      }
      return offset;
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

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("the JDK has no SHA-256 implementation", impossible);
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
}

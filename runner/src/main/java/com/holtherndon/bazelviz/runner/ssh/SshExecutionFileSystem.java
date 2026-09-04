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

  private record RemoteDownloadSnapshot(ExecutionPath payload, long bytes) {}

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
    String revision = directoryRevision(canonical);
    DirectoryCursor cursor =
        continuationToken
            .map(SshExecutionFileSystem::decodeKey)
            .orElseGet(() -> new DirectoryCursor(revision, ""));
    if (!cursor.revision().equals(revision)) {
      throw new IOException("the remote directory changed while it was being paged; reload it");
    }
    String after = cursor.after();
    if (!after.isEmpty() && !parentOf(after).equals(canonical.value())) {
      throw new IllegalArgumentException("directory continuation token belongs to another path");
    }
    int requested = Math.addExact(maxEntries, 1);
    CommandResult result =
        run(
            directoryListingCommand(
                canonical.value(), after, requested, "/usr/bin/find", "/bin/bash"));
    if (!result.isSuccess()) {
      throw new IOException(
          "cannot list remote directory " + directory + ": " + result.failureDetail());
    }
    String afterRevision = directoryRevision(canonical);
    if (!revision.equals(afterRevision)) {
      throw new IOException("the remote directory changed while it was being listed; reload it");
    }
    List<FileMetadata> entries = parseDirectoryRecords(result.stdout());
    boolean hasMore = entries.size() > maxEntries;
    if (hasMore) {
      entries = new ArrayList<>(entries.subList(0, maxEntries));
    }
    Optional<String> next =
        hasMore
            ? Optional.of(encodeKey(revision, entries.getLast().path().value()))
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

  static List<String> directoryListingCommand(
      String directory, String after, int limit, String find, String selectorShell) {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(after, "after");
    Objects.requireNonNull(find, "find");
    Objects.requireNonNull(selectorShell, "selectorShell");
    if (limit <= 0) {
      throw new IllegalArgumentException("directory listing limit must be positive");
    }
    String program =
        """
        bbv_swap() {
          local a=$1 b=$2 t
          t=${bbv_key[$a]}; bbv_key[$a]=${bbv_key[$b]}; bbv_key[$b]=$t
          t=${bbv_kind[$a]}; bbv_kind[$a]=${bbv_kind[$b]}; bbv_kind[$b]=$t
          t=${bbv_size[$a]}; bbv_size[$a]=${bbv_size[$b]}; bbv_size[$b]=$t
          t=${bbv_time[$a]}; bbv_time[$a]=${bbv_time[$b]}; bbv_time[$b]=$t
        }
        bbv_up() {
          local i=$1 p
          while (( i > 1 )); do
            p=$((i / 2))
            if [[ ${bbv_key[$p]} > ${bbv_key[$i]} || ${bbv_key[$p]} == ${bbv_key[$i]} ]]; then
              break
            fi
            bbv_swap "$p" "$i"
            i=$p
          done
        }
        bbv_down() {
          local i=$1 n=$2 left right largest
          while :; do
            left=$((i * 2)); right=$((left + 1)); largest=$i
            if (( left <= n )) && [[ ${bbv_key[$left]} > ${bbv_key[$largest]} ]]; then
              largest=$left
            fi
            if (( right <= n )) && [[ ${bbv_key[$right]} > ${bbv_key[$largest]} ]]; then
              largest=$right
            fi
            if (( largest == i )); then
              break
            fi
            bbv_swap "$i" "$largest"
            i=$largest
          done
        }
        bbv_add() {
          local key=$1 kind=$2 size=$3 time=$4 index
          if [[ -n $BBV_AFTER ]] && [[ $key < $BBV_AFTER || $key == $BBV_AFTER ]]; then
            return
          fi
          if (( bbv_count < BBV_LIMIT )); then
            bbv_count=$((bbv_count + 1)); index=$bbv_count
            bbv_key[$index]=$key; bbv_kind[$index]=$kind
            bbv_size[$index]=$size; bbv_time[$index]=$time
            bbv_up "$index"
          elif [[ $key < ${bbv_key[1]} ]]; then
            bbv_key[1]=$key; bbv_kind[1]=$kind
            bbv_size[1]=$size; bbv_time[1]=$time
            bbv_down 1 "$bbv_count"
          fi
        }
        declare -a bbv_key bbv_kind bbv_size bbv_time
        bbv_count=0
        while IFS= read -r -d '' bbv_candidate_key; do
          IFS= read -r -d '' bbv_candidate_kind || exit 65
          IFS= read -r -d '' bbv_candidate_size || exit 65
          IFS= read -r -d '' bbv_candidate_time || exit 65
          bbv_add "$bbv_candidate_key" "$bbv_candidate_kind" "$bbv_candidate_size" "$bbv_candidate_time"
          bbv_candidate_key=
        done
        [[ -z $bbv_candidate_key ]] || exit 65
        bbv_heap_size=$bbv_count
        while (( bbv_heap_size > 1 )); do
          bbv_swap 1 "$bbv_heap_size"
          bbv_heap_size=$((bbv_heap_size - 1))
          bbv_down 1 "$bbv_heap_size"
        done
        for ((bbv_index=1; bbv_index<=bbv_count; bbv_index++)); do
          printf '%s\\0%s\\0%s\\0%s\\0' "${bbv_key[$bbv_index]}" "${bbv_kind[$bbv_index]}" "${bbv_size[$bbv_index]}" "${bbv_time[$bbv_index]}"
        done
        """;
    String pipeline =
        "export LC_ALL=C; "
            + PosixShell.quote(find)
            + " "
            + PosixShell.quote(directory)
            + " -mindepth 1 -maxdepth 1 -printf '%p\\0%y\\0%s\\0%T@\\0'"
            + " | BBV_AFTER="
            + PosixShell.quote(after)
            + " BBV_LIMIT="
            + limit
            + " "
            + PosixShell.quote(selectorShell)
            + " -c "
            + PosixShell.quote(program);
    return List.of("/bin/sh", "-c", "/bin/bash -o pipefail -c " + PosixShell.quote(pipeline));
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
    RemoteDownloadSnapshot snapshot = null;
    Throwable operationFailure = null;
    try {
      snapshot = createRemoteDownloadSnapshot(source, before, maxBytes);
      transfers.download(snapshot.payload().value(), temporary, snapshot.bytes());
      FileMetadata afterTransfer = requireRegular(source);
      if (!sameSnapshot(before, afterTransfer)) {
        throw new IOException("the remote file changed while it was being downloaded: " + source);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      IOException wrapped = new IOException("interrupted while downloading " + source, interrupted);
      operationFailure = wrapped;
      throw wrapped;
    } catch (IOException failure) {
      operationFailure = failure;
      throw failure;
    } catch (RuntimeException failure) {
      operationFailure = failure;
      throw failure;
    } finally {
      if (snapshot != null) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
          removeRemoteDownloadSnapshot(snapshot);
        } catch (IOException | RuntimeException cleanupFailure) {
          restoreInterrupt |= Thread.interrupted();
          if (operationFailure != null) {
            operationFailure.addSuppressed(cleanupFailure);
          } else {
            throw cleanupFailure instanceof IOException io
                ? io
                : new IOException(
                    "cannot remove the bounded remote download snapshot", cleanupFailure);
          }
        } finally {
          if (restoreInterrupt) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  private RemoteDownloadSnapshot createRemoteDownloadSnapshot(
      ExecutionPath source, FileMetadata before, long maxBytes) throws IOException {
    ExecutionPath payload = owned("/tmp/.bbv-download-" + UUID.randomUUID());
    RemoteDownloadSnapshot snapshot = new RemoteDownloadSnapshot(payload, 0);
    IOException operationFailure = null;
    try {
      CommandResult copy =
          run(
              List.of(
                  "/bin/sh",
                  "-c",
                  "umask 077; set -C; /usr/bin/head -c \"$1\" -- \"$2\" > \"$3\""
                      + " && /bin/chmod 0400 -- \"$3\"",
                  "bbv-download-snapshot",
                  Long.toString(maxBytes),
                  source.value(),
                  payload.value()));
      if (!copy.isSuccess()) {
        throw new IOException(
            "cannot create a bounded remote download snapshot: " + copy.failureDetail());
      }
      FileMetadata after = requireRegular(source);
      FileMetadata staged = requireRegular(payload);
      long sourceBytes = after.bytes().orElseThrow();
      long stagedBytes = staged.bytes().orElseThrow();
      if (sourceBytes > maxBytes) {
        throw tooLarge(source, sourceBytes, maxBytes);
      }
      if (!sameSnapshot(before, after) || stagedBytes != sourceBytes) {
        throw new IOException("the remote file changed while it was being downloaded: " + source);
      }
      if (stagedBytes > maxBytes) {
        throw tooLarge(source, stagedBytes, maxBytes);
      }
      return new RemoteDownloadSnapshot(payload, stagedBytes);
    } catch (IOException | RuntimeException failure) {
      operationFailure =
          failure instanceof IOException io
              ? io
              : new IOException("cannot create a bounded remote download snapshot", failure);
      throw operationFailure;
    } finally {
      if (operationFailure != null) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
          removeRemoteDownloadSnapshot(snapshot);
        } catch (IOException | RuntimeException cleanupFailure) {
          restoreInterrupt |= Thread.interrupted();
          operationFailure.addSuppressed(cleanupFailure);
        } finally {
          if (restoreInterrupt) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  private void removeRemoteDownloadSnapshot(RemoteDownloadSnapshot snapshot) throws IOException {
    CommandResult result = run(List.of("/bin/rm", "-f", "--", snapshot.payload().value()));
    if (!result.isSuccess()) {
      throw new IOException("cannot remove the bounded remote download snapshot");
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

  private String directoryRevision(ExecutionPath directory) throws IOException {
    CommandResult result =
        run(List.of("/usr/bin/stat", "--printf=%d:%i:%s:%y", "--", requireOwned(directory)));
    if (!result.isSuccess()) {
      throw new IOException("cannot read remote directory revision: " + result.failureDetail());
    }
    return RemoteText.singleLine(result.stdout(), "remote directory revision");
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
    boolean restoreInterrupt = Thread.interrupted();
    try {
      run(List.of("/bin/rm", "-f", "--", path));
    } catch (IOException | RuntimeException ignored) {
      // A failed operation already has the useful error. This is cleanup only.
      restoreInterrupt |= Thread.interrupted();
    } finally {
      if (restoreInterrupt) {
        Thread.currentThread().interrupt();
      }
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

  List<FileMetadata> parseDirectoryRecords(String output) throws IOException {
    List<FileMetadata> entries = new ArrayList<>();
    if (!output.isEmpty() && output.charAt(output.length() - 1) != '\0') {
      throw new IOException("remote directory listing returned an incomplete record");
    }
    String[] fields = output.split(String.valueOf('\0'), -1);
    int fieldCount = fields.length;
    if (fieldCount > 0 && fields[fieldCount - 1].isEmpty()) {
      fieldCount--;
    }
    if (fieldCount % 4 != 0) {
      throw new IOException("remote directory listing returned malformed metadata");
    }
    for (int index = 0; index < fieldCount; index += 4) {
      try {
        ExecutionPath path = owned(normalizeAbsolute(fields[index]));
        FileMetadata.Kind kind =
            switch (fields[index + 1]) {
              case "f" -> FileMetadata.Kind.REGULAR_FILE;
              case "d" -> FileMetadata.Kind.DIRECTORY;
              case "l" -> FileMetadata.Kind.SYMBOLIC_LINK;
              default -> FileMetadata.Kind.OTHER;
            };
        long bytes = Long.parseLong(fields[index + 2]);
        long modified = new BigDecimal(fields[index + 3]).movePointRight(3).longValue();
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

  record DirectoryCursor(String revision, String after) {
    DirectoryCursor {
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(after, "after");
    }
  }

  static String encodeKey(String revision, String key) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(key, "key");
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((revision + '\0' + key).getBytes(StandardCharsets.UTF_8));
  }

  static DirectoryCursor decodeKey(String token) {
    try {
      String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int separator = value.indexOf('\0');
      if (separator <= 0
          || separator != value.lastIndexOf('\0')
          || !value.substring(separator + 1).startsWith("/")) {
        throw new IllegalArgumentException("not a revision and absolute path");
      }
      return new DirectoryCursor(value.substring(0, separator), value.substring(separator + 1));
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
      throw new IOException("atomic download replacement is not supported", unsupported);
    }
  }
}

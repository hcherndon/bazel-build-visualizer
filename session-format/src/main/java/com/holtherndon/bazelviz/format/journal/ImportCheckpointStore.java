package com.holtherndon.bazelviz.format.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code checkpoints/import.ckpt} atomically.
 *
 * <p>Writing is temp file, {@code fsync}, {@code ATOMIC_MOVE}: a reader therefore sees either the
 * whole previous checkpoint or the whole new one, never half of either. The temp file is forced
 * before the rename because a rename that lands before the data does would leave a correctly named,
 * empty checkpoint after a power loss.
 *
 * <p>Before each replace the current checkpoint is kept as {@code import.ckpt.previous}. That
 * covers what atomicity alone cannot: a file that is intact but wrong — a bad sector, a truncated
 * copy, an interrupted sync on a filesystem that does not honour rename ordering. {@link #read()}
 * falls back to the previous checkpoint in that case, which is always a <em>behind</em> position,
 * never an ahead one, so recovery re-verifies journal bytes it has already seen instead of skipping
 * bytes it has not.
 *
 * <p>A checkpoint being behind the journal is always safe; being ahead is not. That asymmetry is
 * why every failure here degrades backwards.
 */
public final class ImportCheckpointStore {

  private static final Logger log = LoggerFactory.getLogger(ImportCheckpointStore.class);

  /** Conventional file name inside a session's {@code checkpoints/} directory. */
  public static final String CHECKPOINT_FILE_NAME = "import.ckpt";

  private static final String PREVIOUS_SUFFIX = ".previous";
  private static final String TEMP_SUFFIX = ".tmp";

  private final Path checkpointFile;
  private final Path previousFile;
  private final Path tempFile;

  /** Uses {@code import.ckpt} inside {@code checkpointDirectory}. */
  public ImportCheckpointStore(Path checkpointDirectory) {
    this(checkpointDirectory, CHECKPOINT_FILE_NAME);
  }

  public ImportCheckpointStore(Path checkpointDirectory, String fileName) {
    Objects.requireNonNull(checkpointDirectory, "checkpointDirectory");
    Objects.requireNonNull(fileName, "fileName");
    this.checkpointFile = checkpointDirectory.resolve(fileName);
    this.previousFile = checkpointDirectory.resolve(fileName + PREVIOUS_SUFFIX);
    this.tempFile = checkpointDirectory.resolve(fileName + TEMP_SUFFIX);
  }

  public Path checkpointFile() {
    return checkpointFile;
  }

  public Path previousCheckpointFile() {
    return previousFile;
  }

  /**
   * Replaces the checkpoint atomically.
   *
   * @throws IOException if the new checkpoint cannot be written; the existing checkpoint is left
   *     untouched, so a failure here loses progress tracking but never corrupts it
   */
  public void write(ImportCheckpoint checkpoint) throws IOException {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Files.createDirectories(checkpointFile.getParent());
    byte[] bytes = checkpoint.toJson().getBytes(StandardCharsets.UTF_8);

    try (FileChannel channel =
        FileChannel.open(
            tempFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }

    if (Files.exists(checkpointFile)) {
      copyToPrevious();
    }
    move(tempFile, checkpointFile);
  }

  /**
   * The most recent believable checkpoint: the current one if it parses, else the previous one,
   * else empty.
   *
   * <p>Empty means "resume from the beginning of the journal", which is correct but slower — never
   * "resume from zero events", which would be a lie about what was captured.
   */
  public Optional<ImportCheckpoint> read() throws IOException {
    Optional<ImportCheckpoint> current = readIfValid(checkpointFile);
    if (current.isPresent()) {
      return current;
    }
    Optional<ImportCheckpoint> previous = readIfValid(previousFile);
    if (previous.isPresent()) {
      log.warn("checkpoint {} is unusable; falling back to {}", checkpointFile, previousFile);
    }
    return previous;
  }

  /**
   * Reads one checkpoint file strictly.
   *
   * @throws CheckpointFormatException when the file exists but is not a valid checkpoint. Use
   *     {@link #read()} unless you want to know that.
   */
  public Optional<ImportCheckpoint> readFile(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    String text = Files.readString(file, StandardCharsets.UTF_8);
    return Optional.of(ImportCheckpoint.fromJson(text));
  }

  /** Deletes the checkpoint and its backup. Used when a session is finalized or discarded. */
  public void delete() throws IOException {
    Files.deleteIfExists(tempFile);
    Files.deleteIfExists(checkpointFile);
    Files.deleteIfExists(previousFile);
  }

  private Optional<ImportCheckpoint> readIfValid(Path file) throws IOException {
    try {
      return readFile(file);
    } catch (CheckpointFormatException malformed) {
      log.warn("ignoring unusable checkpoint {}: {}", file, malformed.getMessage());
      return Optional.empty();
    } catch (UncheckedIOException | MalformedInputException notText) {
      // Binary garbage where JSON was expected is the same class of
      // problem: the file cannot be believed, so it is not honoured.
      log.warn("ignoring unreadable checkpoint {}: {}", file, notText.toString());
      return Optional.empty();
    }
  }

  private void copyToPrevious() throws IOException {
    try {
      Files.copy(
          checkpointFile,
          previousFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
    } catch (IOException copyFailure) {
      // Losing the backup is not worth failing the checkpoint over: the
      // new checkpoint is already safely on disk and is the better of the
      // two. Say so rather than hiding it.
      log.warn("could not refresh {}: {}", previousFile, copyFailure.toString());
    }
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      // Only reachable on a filesystem that cannot rename atomically. The
      // fallback is a plain replace, which has a window where the target
      // is missing — recorded loudly because it weakens the guarantee.
      log.warn(
          "filesystem does not support atomic move of {} to {}; "
              + "checkpoint replacement is not crash-atomic here",
          source,
          target);
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

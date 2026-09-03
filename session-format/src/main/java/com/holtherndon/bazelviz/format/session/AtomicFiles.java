package com.holtherndon.bazelviz.format.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replace-a-small-file-or-leave-it-alone.
 *
 * <p>The manifest and the lock record are read by the next process to open the session, quite
 * possibly after this one was killed mid-write. A plain truncate-and-write leaves a window in which
 * the file on disk is half a document — and a session whose manifest is half a document is a
 * session that will not open. So every write here goes to a temporary sibling, is forced to stable
 * storage, and is then moved over the target in one step.
 *
 * <p>The force matters as much as the move: {@code ATOMIC_MOVE} guarantees the rename is
 * all-or-nothing, not that the new file's <em>contents</em> reached the platter before the rename
 * did. Without the force, a power loss can leave the rename durable and the bytes it points at not.
 *
 * <p>{@link AtomicMoveNotSupportedException} is caught and downgraded to a plain replace. Some
 * network and container filesystems cannot rename atomically; a degraded write there beats refusing
 * to save at all, and the temporary file is still fully written first.
 */
public final class AtomicFiles {

  private static final String TEMP_PREFIX = ".";
  private static final String TEMP_SUFFIX = ".tmp";

  private AtomicFiles() {}

  /** Writes {@code content} as UTF-8 to {@code target}, atomically replacing it. */
  public static void writeString(Path target, String content) throws IOException {
    writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes {@code content} to {@code target}, atomically replacing it. */
  public static void writeBytes(Path target, byte[] content) throws IOException {
    Path directory = target.toAbsolutePath().getParent();
    if (directory == null) {
      throw new IOException("cannot write to a path with no parent directory: " + target);
    }
    Files.createDirectories(directory);
    Path fileName = target.getFileName();
    Path temp =
        Files.createTempFile(
            directory, TEMP_PREFIX + (fileName == null ? "file" : fileName), TEMP_SUFFIX);
    try {
      try (FileChannel channel =
          FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        channel.write(ByteBuffer.wrap(content));
        channel.force(true);
      }
      move(temp, target);
    } catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    }
  }

  private static void move(Path temp, Path target) throws IOException {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

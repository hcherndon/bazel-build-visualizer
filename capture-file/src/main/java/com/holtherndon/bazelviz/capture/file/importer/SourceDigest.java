package com.holtherndon.bazelviz.capture.file.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming SHA-256 of a capture source, and the copy-while-hashing that {@link
 * SourcePreservation#COPY_INTO_SESSION} uses.
 *
 * <p>Both operations read through one fixed buffer. Nothing here allocates in proportion to file
 * size, which is what lets a session preserve a 40 GiB BEP file without the exit criterion "no full
 * file is loaded into memory" becoming a lie at the very first step.
 *
 * <p>Hashing and copying happen in the same pass on purpose. Two passes would leave a window in
 * which the file changed between being hashed and being copied, and the session would then hold a
 * digest that does not describe the bytes it kept.
 */
final class SourceDigest {

  /** Fixed transfer buffer. Independent of the file, by construction. */
  static final int BUFFER_BYTES = 256 * 1024;

  private SourceDigest() {}

  /** Digest and size of {@code file}, computed in one streaming pass. */
  static Result hash(Path file) throws IOException {
    MessageDigest digest = newDigest();
    byte[] buffer = new byte[BUFFER_BYTES];
    long bytes = 0;
    try (InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
        bytes += read;
      }
    }
    return new Result(hex(digest), bytes);
  }

  /**
   * Copies {@code source} to {@code target}, hashing the bytes as they pass.
   *
   * <p>The copy lands on a temporary sibling and is moved into place only once it is complete and
   * forced, so an interrupted import never leaves a half-copy that looks like a preserved source.
   */
  static Result copy(Path source, Path target) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = target.resolveSibling("." + target.getFileName() + ".partial");
    MessageDigest digest = newDigest();
    byte[] buffer = new byte[BUFFER_BYTES];
    long bytes = 0;
    try {
      try (InputStream in = Files.newInputStream(source);
          OutputStream out = Files.newOutputStream(temporary)) {
        int read;
        while ((read = in.read(buffer)) > 0) {
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
          bytes += read;
        }
        out.flush();
      }
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException failure) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    return new Result(hex(digest), bytes);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      // Every conformant JRE ships SHA-256; a JRE without it cannot run this
      // application at all, so failing loudly beats degrading to a weaker digest.
      throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
    }
  }

  private static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  /** A digest and the number of bytes it covers. */
  record Result(String sha256, long byteSize) {}
}

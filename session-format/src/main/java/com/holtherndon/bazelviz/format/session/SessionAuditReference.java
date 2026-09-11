package com.holtherndon.bazelviz.format.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Durable retention protection for ordinary sessions owned by a paired audit (ADR-014). */
public final class SessionAuditReference {
  /** Maximum UTF-8 ownership record. The path is descriptive, never permission to execute/clean. */
  public static final int MAX_REFERENCE_BYTES = 16_384;

  private SessionAuditReference() {}

  /**
   * Protect before registering a new audit session in the catalog. Never replaces another owner. A
   * crash during creation leaves conservative protection, not permission to delete the session.
   */
  public static void protect(Path sessionRoot, Path auditRoot) throws IOException {
    Path root = sessionRoot.toRealPath();
    ManagedSessionLayout layout = ManagedSessionLayout.at(root);
    Files.createDirectories(layout.locksDirectory());
    if (!layout.locksDirectory().toRealPath().equals(layout.locksDirectory())) {
      throw new IOException("Audit protection refused a redirected locks directory");
    }
    byte[] reference =
        (auditRoot.toAbsolutePath().normalize() + "\n").getBytes(StandardCharsets.UTF_8);
    if (reference.length > MAX_REFERENCE_BYTES) {
      throw new IOException("Audit reference exceeds " + MAX_REFERENCE_BYTES + " bytes");
    }
    Path target = layout.auditReferenceFile();
    try (FileChannel channel =
        FileChannel.open(
            target,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(reference);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    } catch (FileAlreadyExistsException exists) {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || Files.size(target) > MAX_REFERENCE_BYTES) {
        throw new IOException("An unreadable audit reference already protects this session");
      }
      try (FileChannel channel =
          FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_REFERENCE_BYTES + 1);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
        buffer.flip();
        if (!buffer.equals(ByteBuffer.wrap(reference))) {
          throw new IOException("A different or incomplete audit reference protects this session");
        }
      }
    }
  }

  /** Unknown filesystem access is conservatively protected too; contents never grant authority. */
  public static boolean isProtected(Path sessionRoot) {
    return !Files.notExists(
        ManagedSessionLayout.at(sessionRoot).auditReferenceFile(), LinkOption.NOFOLLOW_LINKS);
  }
}

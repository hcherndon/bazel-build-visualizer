package com.holtherndon.bazelviz.capture.repro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/** Private, atomic operation record. Reading it never executes a recorded command. */
final class AuditJournal {
  static final long MAX_RECORD_BYTES = 262_144;
  private final Path directory;
  private final Properties properties = new Properties();

  AuditJournal(Path auditsRoot) throws IOException {
    Files.createDirectories(auditsRoot);
    directory = auditsRoot.resolve("audit-" + UUID.randomUUID()).toAbsolutePath().normalize();
    Files.createDirectory(
        directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    properties.setProperty("format", "1");
    properties.setProperty("created", Instant.now().toString());
    properties.setProperty("state", "PREFLIGHT");
    properties.setProperty("cleanup", "NOT_ALLOCATED");
    publish(properties);
  }

  Path directory() {
    return directory;
  }

  synchronized void put(String key, String value) throws IOException {
    if (value.length() > MAX_RECORD_BYTES / 4) {
      throw new IOException("an audit record value is too large");
    }
    Properties replacement = new Properties();
    replacement.putAll(properties);
    replacement.setProperty(key, value);
    replacement.setProperty("updated", Instant.now().toString());
    publish(replacement);
    properties.clear();
    properties.putAll(replacement);
  }

  private void publish(Properties replacement) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    replacement.store(bytes, "Private audit evidence; not a portable session or executable plan");
    if (bytes.size() > MAX_RECORD_BYTES) {
      throw new IOException("the audit operation record exceeded " + MAX_RECORD_BYTES + " bytes");
    }
    Path temporary = Files.createTempFile(directory, ".operation-", ".tmp");
    try {
      try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      Files.move(
          temporary,
          directory.resolve("operation.properties"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static Properties read(Path directory) throws IOException {
    Path file = directory.resolve("operation.properties");
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "The audit operation record must be a regular file, not a symlink or special file.");
    }
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes((int) MAX_RECORD_BYTES + 1);
      if (bytes.length > MAX_RECORD_BYTES) {
        throw new IOException("the audit operation record is too large");
      }
      Properties result = new Properties();
      result.load(new ByteArrayInputStream(bytes));
      if (!"1".equals(result.getProperty("format"))) {
        throw new IOException("unsupported audit operation record format");
      }
      return result;
    }
  }
}

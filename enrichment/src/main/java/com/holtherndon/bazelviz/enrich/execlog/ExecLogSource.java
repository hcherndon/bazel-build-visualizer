package com.holtherndon.bazelviz.enrich.execlog;

import com.holtherndon.bazelviz.core.enrich.ExecLogFormat;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Opens an execution log, working out what it is from its first bytes.
 *
 * <h2>Why sniff rather than trust the extension</h2>
 *
 * <p>A session records which format it asked Bazel for, but an imported log
 * arrived from somewhere else and its name proves nothing. The formats are
 * cheaply distinguishable: a compact log is a zstd frame and begins with the
 * four-byte magic {@code 28 b5 2f fd}, measured on 7.6.1, 8.4.1 and 9.2.0
 * (S4); a binary log begins with a protobuf length varint; a JSON log begins
 * with whitespace or {@code '{'}.
 *
 * <p>Getting this wrong is not a crash. Handing a zstd frame to the protobuf
 * reader produces a plausible-looking varint and then garbage, which is why the
 * detection is explicit and its result is recorded rather than assumed.
 */
public final class ExecLogSource implements AutoCloseable {

  /** The zstd frame magic, little-endian {@code 0xFD2FB528}. */
  static final byte[] ZSTD_MAGIC = {(byte) 0x28, (byte) 0xb5, (byte) 0x2f, (byte) 0xfd};

  private final ExecLogFormat format;
  private final InputStream stream;
  private final long compressedSize;

  private ExecLogSource(ExecLogFormat format, InputStream stream, long compressedSize) {
    this.format = format;
    this.stream = stream;
    this.compressedSize = compressedSize;
  }

  /**
   * Opens {@code file}, detecting its format.
   *
   * @throws IOException if the file cannot be read
   * @throws UnknownExecLogFormatException if its first bytes match no format
   */
  public static ExecLogSource open(Path file) throws IOException {
    long size = Files.size(file);
    InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
    PushbackInputStream pushback = new PushbackInputStream(raw, ZSTD_MAGIC.length);

    byte[] head = new byte[ZSTD_MAGIC.length];
    int read = pushback.readNBytes(head, 0, head.length);
    if (read > 0) {
      pushback.unread(head, 0, read);
    }

    Optional<ExecLogFormat> detected = detect(head, read);
    if (detected.isEmpty()) {
      pushback.close();
      throw new UnknownExecLogFormatException(file, head, read);
    }
    ExecLogFormat format = detected.get();
    InputStream body = format == ExecLogFormat.COMPACT ? new ZstdInputStream(pushback) : pushback;
    return new ExecLogSource(format, body, size);
  }

  /**
   * The format implied by a file's first bytes.
   *
   * <p>An empty file matches nothing: on Bazel 6.5.0 a fully cached rebuild writes a zero-byte
   * binary log (S3), and so does a run that never got started. The caller has to decide which, and
   * cannot be helped from here.
   */
  static Optional<ExecLogFormat> detect(byte[] head, int length) {
    if (length >= ZSTD_MAGIC.length && startsWithZstdMagic(head)) {
      return Optional.of(ExecLogFormat.COMPACT);
    }
    if (length == 0) {
      return Optional.empty();
    }
    // JSON begins with '{' or leading whitespace before one.
    int first = head[0] & 0xff;
    if (first == '{' || first == ' ' || first == '\n' || first == '\r' || first == '\t') {
      return Optional.of(ExecLogFormat.JSON);
    }
    // A binary log begins with the length varint of its first SpawnExec.
    // Any non-zero first byte is a plausible varint start; zero is not,
    // because a zero-length message would carry no fields.
    return first == 0 ? Optional.empty() : Optional.of(ExecLogFormat.BINARY);
  }

  private static boolean startsWithZstdMagic(byte[] head) {
    for (int i = 0; i < ZSTD_MAGIC.length; i++) {
      if (head[i] != ZSTD_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  /** What this file turned out to be. */
  public ExecLogFormat format() {
    return format;
  }

  /** The decoded byte stream: decompressed for a compact log, the file itself otherwise. */
  public InputStream stream() {
    return stream;
  }

  /** The size on disk, which for a compact log is the compressed size. */
  public long compressedSize() {
    return compressedSize;
  }

  @Override
  public void close() throws IOException {
    stream.close();
  }
}

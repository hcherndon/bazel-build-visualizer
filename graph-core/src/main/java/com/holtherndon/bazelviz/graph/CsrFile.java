package com.holtherndon.bazelviz.graph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * Reads and writes a {@link CsrGraph} as a file.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   magic          8 bytes   "BBVCSR01"
 *   formatVersion  4 bytes
 *   flags          4 bytes   bit 0 means reverse direction
 *   nodeCount      8 bytes
 *   edgeCount      8 bytes
 *   checksum       8 bytes   CRC32C of everything after this field
 *   offsets        8 bytes x (nodeCount + 1)
 *   targets        4 bytes x edgeCount
 * </pre>
 *
 * <p>Little-endian throughout, because every machine this runs on is, and a byte order that matches
 * the hardware is what lets the arrays be read by bulk copy rather than element by element.
 *
 * <h2>Why a file at all</h2>
 *
 * <p>Plan 13.2. Rebuilding the CSR from SQLite costs a full scan of the edge table on every session
 * open, which at a hundred million edges is not a thing to do while a user waits. The file is the
 * same two primitive arrays the in-memory graph holds, so loading it is one read.
 *
 * <h2>Atomic rename, and why the checksum is not enough</h2>
 *
 * <p>Written to a sibling temporary name and moved into place with {@code ATOMIC_MOVE}, so a
 * crashed build leaves a stray temp file rather than a half-written index under the name a reader
 * trusts. The checksum catches corruption after the fact; the rename stops a reader ever seeing a
 * partial file in the first place, which matters because a truncated CSR is not detectably wrong —
 * it is a smaller, perfectly valid graph.
 */
public final class CsrFile {

  /** Identifies the format and its version in one 8-byte word. */
  static final byte[] MAGIC = {'B', 'B', 'V', 'C', 'S', 'R', '0', '1'};

  /** The layout this build writes and reads. */
  public static final int FORMAT_VERSION = 1;

  static final int HEADER_BYTES = 8 + 4 + 4 + 8 + 8 + 8;

  /** Header flag identifying a consumer-to-producer reverse index. */
  public static final int REVERSE_DIRECTION_FLAG = 1;

  private CsrFile() {}

  /**
   * Writes {@code graph} to {@code file}, atomically.
   *
   * @return the checksum recorded in the header, for the index registry
   */
  public static long write(CsrGraph graph, Path file) throws IOException {
    return write(graph, file, false);
  }

  /** Writes a graph and records whether its edges are the reverse direction. */
  public static long write(CsrGraph graph, Path file, boolean reverseDirection) throws IOException {
    long nodeCount = graph.nodeCount();
    long edgeCount = graph.edgeCount();
    Path temporary = file.resolveSibling(file.getFileName() + ".building");

    CRC32C crc = new CRC32C();
    ByteBuffer body =
        ByteBuffer.allocate(checkedBodyBytes(file, nodeCount, edgeCount))
            .order(ByteOrder.LITTLE_ENDIAN);
    for (long node = 0; node <= nodeCount; node++) {
      body.putLong(node == nodeCount ? edgeCount : graph.neighborsBegin((int) node));
    }
    for (long edge = 0; edge < edgeCount; edge++) {
      body.putInt(graph.neighborAt(edge));
    }
    body.flip();
    crc.update(body.duplicate());
    long checksum = crc.getValue();

    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    header.put(MAGIC);
    header.putInt(FORMAT_VERSION);
    header.putInt(reverseDirection ? REVERSE_DIRECTION_FLAG : 0);
    header.putLong(nodeCount);
    header.putLong(edgeCount);
    header.putLong(checksum);
    header.flip();

    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      while (header.hasRemaining() || body.hasRemaining()) {
        channel.write(new ByteBuffer[] {header, body});
      }
      // Forced before the rename: an atomic rename of unflushed bytes is
      // an atomic rename of nothing.
      channel.force(true);
    }
    Files.move(
        temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return checksum;
  }

  /**
   * Reads {@code file}, verifying its header and checksum.
   *
   * @throws CsrFormatException when the file is not a CSR index this build reads, or when its
   *     contents do not match its checksum
   */
  public static CsrGraph read(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ValidatedHeader header = readHeader(channel, file);
      long nodeCount = header.nodeCount();
      long edgeCount = header.edgeCount();
      int bodyBytes = header.bodyBytes();

      ByteBuffer body =
          channel
              .map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES, bodyBytes)
              .order(ByteOrder.LITTLE_ENDIAN);
      CRC32C crc = new CRC32C();
      crc.update(body.duplicate());
      if (crc.getValue() != header.checksum()) {
        throw new CsrFormatException(file, "checksum does not match its contents");
      }

      try {
        long[] offsets = new long[Math.toIntExact(nodeCount + 1)];
        body.asLongBuffer().get(offsets);
        int[] targets = new int[Math.toIntExact(edgeCount)];
        body.position(Math.toIntExact(Math.multiplyExact(nodeCount + 1, Long.BYTES)));
        body.asIntBuffer().get(targets);
        validateStructure(file, offsets, targets);
        return new CsrGraph(offsets, targets);
      } catch (ArithmeticException | IndexOutOfBoundsException malformed) {
        throw new CsrFormatException(
            file, "body structure cannot be represented safely", malformed);
      } catch (IllegalArgumentException malformed) {
        throw new CsrFormatException(file, malformed.getMessage(), malformed);
      }
    }
  }

  /** The node and edge counts in a file's header, without reading its body. */
  public static Header headerOf(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ValidatedHeader header = readHeader(channel, file);
      return new Header(
          header.formatVersion(),
          header.nodeCount(),
          header.edgeCount(),
          header.checksum(),
          header.reverseDirection());
    }
  }

  private static ValidatedHeader readHeader(FileChannel channel, Path file) throws IOException {
    long size = channel.size();
    if (size < HEADER_BYTES) {
      throw new CsrFormatException(file, "shorter than a header");
    }
    ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    while (bytes.hasRemaining()) {
      if (channel.read(bytes) < 0) {
        throw new CsrFormatException(file, "shorter than a header");
      }
    }
    bytes.flip();

    byte[] magic = new byte[MAGIC.length];
    bytes.get(magic);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new CsrFormatException(file, "not a CSR index");
    }
    int version = bytes.getInt();
    if (version != FORMAT_VERSION) {
      // Refused rather than guessed at. An index whose layout this build does not know is rebuilt,
      // which is cheap; reading it wrongly produces a graph that answers.
      throw new CsrFormatException(
          file, "format version " + version + ", and this build reads " + FORMAT_VERSION);
    }
    int flags = bytes.getInt();
    if ((flags & ~REVERSE_DIRECTION_FLAG) != 0) {
      throw new CsrFormatException(file, "unsupported flags " + flags);
    }
    long nodeCount = bytes.getLong();
    long edgeCount = bytes.getLong();
    long checksum = bytes.getLong();
    int bodyBytes = checkedBodyBytes(file, nodeCount, edgeCount);
    long actualBodyBytes = size - HEADER_BYTES;
    if (actualBodyBytes != bodyBytes) {
      throw new CsrFormatException(
          file,
          "header promises " + bodyBytes + " bytes of graph and the file holds " + actualBodyBytes);
    }
    return new ValidatedHeader(
        version, nodeCount, edgeCount, checksum, bodyBytes, (flags & REVERSE_DIRECTION_FLAG) != 0);
  }

  private static void validateStructure(Path file, long[] offsets, int[] targets)
      throws CsrFormatException {
    if (offsets.length == 0 || offsets[0] != 0) {
      throw new CsrFormatException(file, "offsets must start at zero");
    }
    for (int index = 0; index + 1 < offsets.length; index++) {
      long offset = offsets[index];
      long next = offsets[index + 1];
      if (offset < 0 || offset > next || next > targets.length) {
        throw new CsrFormatException(
            file, "invalid offsets at node " + index + ": " + offset + " then " + next);
      }
    }
    if (offsets[offsets.length - 1] != targets.length) {
      throw new CsrFormatException(
          file,
          "final offset "
              + offsets[offsets.length - 1]
              + " does not match edge count "
              + targets.length);
    }
    int nodeCount = offsets.length - 1;
    for (int edge = 0; edge < targets.length; edge++) {
      if (targets[edge] < 0 || targets[edge] >= nodeCount) {
        throw new CsrFormatException(
            file, "target " + targets[edge] + " at edge " + edge + " is outside the node range");
      }
    }
  }

  private static int checkedBodyBytes(Path file, long nodeCount, long edgeCount)
      throws CsrFormatException {
    if (nodeCount < 0) {
      throw new CsrFormatException(file, "negative node count " + nodeCount);
    }
    if (edgeCount < 0) {
      throw new CsrFormatException(file, "negative edge count " + edgeCount);
    }
    if (nodeCount > Integer.MAX_VALUE - 1L) {
      throw new CsrFormatException(
          file, "node count " + nodeCount + " does not fit the in-memory graph format");
    }
    if (edgeCount > Integer.MAX_VALUE) {
      throw new CsrFormatException(
          file, "edge count " + edgeCount + " does not fit the in-memory graph format");
    }
    long bodyBytes;
    try {
      long offsetBytes = Math.multiplyExact(Math.addExact(nodeCount, 1L), Long.BYTES);
      long targetBytes = Math.multiplyExact(edgeCount, Integer.BYTES);
      bodyBytes = Math.addExact(offsetBytes, targetBytes);
    } catch (ArithmeticException overflow) {
      throw new CsrFormatException(file, "node and edge counts overflow the body size");
    }
    if (bodyBytes > Integer.MAX_VALUE) {
      throw new CsrFormatException(
          file, "graph body of " + bodyBytes + " bytes is too large for one memory-mapped buffer");
    }
    return (int) bodyBytes;
  }

  private record ValidatedHeader(
      int formatVersion,
      long nodeCount,
      long edgeCount,
      long checksum,
      int bodyBytes,
      boolean reverseDirection) {}

  /** What a file's header says about it. */
  public record Header(
      int formatVersion, long nodeCount, long edgeCount, long checksum, boolean reverseDirection) {
    /** Compatibility constructor for callers written before direction was exposed. */
    public Header(int formatVersion, long nodeCount, long edgeCount, long checksum) {
      this(formatVersion, nodeCount, edgeCount, checksum, false);
    }
  }

  /** The file is not a CSR index this build can read. */
  public static final class CsrFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    CsrFormatException(Path file, String why) {
      super(file + " is not a usable graph index: " + why);
    }

    CsrFormatException(Path file, String why, Throwable cause) {
      super(file + " is not a usable graph index: " + why, cause);
    }
  }
}

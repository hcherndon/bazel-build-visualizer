package com.holtherndon.bazelviz.graph;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Reads, validates, maps and streams the versioned CSR index format from ADR-006. */
public final class CsrFile {

  /** Identifies the format and its version in one 8-byte word. */
  static final byte[] MAGIC = {'B', 'B', 'V', 'C', 'S', 'R', '0', '1'};

  /** The layout this build writes and reads. */
  public static final int FORMAT_VERSION = 1;

  /** Fixed descriptor size before the two arrays. */
  public static final int HEADER_BYTES = 40;

  /** Maximum size of one read-only mapped region. */
  public static final long MAP_SEGMENT_BYTES = 268_435_456L;

  /** Heap buffer used by file construction and checksum passes. */
  public static final int WRITE_BUFFER_BYTES = 1_048_576;

  /** Header flag identifying a consumer-to-producer reverse index. */
  public static final int REVERSE_DIRECTION_FLAG = 1;

  private CsrFile() {}

  /** Writes an already-built graph without allocating a second graph-sized body buffer. */
  public static long write(CsrGraph graph, Path file) throws IOException {
    return write(graph, file, false);
  }

  /** Writes an already-built graph and records its direction. */
  public static long write(CsrGraph graph, Path file, boolean reverseDirection) throws IOException {
    Objects.requireNonNull(graph, "graph");
    int nodes = Math.toIntExact(graph.nodeCount());
    WriteResult written =
        writeOrdered(
            nodes,
            visitor -> {
              for (int node = 0; node < nodes; node++) {
                long end = graph.neighborsEnd(node);
                for (long edge = graph.neighborsBegin(node); edge < end; edge++) {
                  visitor.edge(node, graph.neighborAt(edge));
                }
              }
            },
            file,
            reverseDirection);
    if (written.edgeCount() != graph.edgeCount()) {
      throw new CsrFormatException(file, "graph changed while it was written");
    }
    return written.checksum();
  }

  /**
   * Streams an adjacency whose edges are ordered by source node into a sibling temporary file. Only
   * the two fixed-size buffers are retained. The completed file is forced before one atomic rename
   * publishes it.
   */
  public static WriteResult writeOrdered(
      int nodeCount, EdgeStream orderedEdges, Path file, boolean reverseDirection)
      throws IOException {
    if (nodeCount < 0) {
      throw new CsrFormatException(file, "negative node count " + nodeCount);
    }
    Objects.requireNonNull(orderedEdges, "orderedEdges");
    Objects.requireNonNull(file, "file");
    Path absolute = file.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null) {
      throw new CsrFormatException(file, "has no parent directory");
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName() + ".", ".building");
    boolean published = false;
    try {
      long offsetBytes = layout(file, nodeCount, 0).offsetBytes();
      long targetsStart = Math.addExact(HEADER_BYTES, offsetBytes);
      long[] edgeCount = {0};
      int[] nextOffsetNode = {1};
      int[] previousFrom = {-1};
      int[] previousTo = {-1};

      WriteResult result;
      try (FileChannel channel =
          FileChannel.open(
              temporary,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        PositionalWriter offsets = new PositionalWriter(channel, HEADER_BYTES);
        PositionalWriter targets = new PositionalWriter(channel, targetsStart);
        offsets.putLong(0);
        orderedEdges.forEach(
            (from, to) -> {
              checkNode(file, from, nodeCount, "source");
              checkNode(file, to, nodeCount, "target");
              if (from < previousFrom[0] || (from == previousFrom[0] && previousTo[0] > to)) {
                throw new UnorderedEdgeException(previousFrom[0], previousTo[0], from, to);
              }
              while (nextOffsetNode[0] <= from) {
                offsets.putLongUnchecked(edgeCount[0]);
                nextOffsetNode[0]++;
              }
              targets.putIntUnchecked(to);
              edgeCount[0] = Math.addExact(edgeCount[0], 1L);
              previousFrom[0] = from;
              previousTo[0] = to;
            });
        while (nextOffsetNode[0] <= nodeCount) {
          offsets.putLong(edgeCount[0]);
          nextOffsetNode[0]++;
        }
        offsets.close();
        targets.close();

        Layout complete = layout(file, nodeCount, edgeCount[0]);
        if (offsets.bytesWritten() != complete.offsetBytes()
            || targets.bytesWritten() != complete.targetBytes()) {
          throw new CsrFormatException(file, "streamed body length disagrees with its counts");
        }
        channel.truncate(complete.fileBytes());
        long checksum = checksum(channel, complete.bodyBytes());
        writeHeader(channel, nodeCount, edgeCount[0], checksum, reverseDirection);
        channel.force(true);
        result = new WriteResult(edgeCount[0], checksum, complete.fileBytes());
      } catch (UncheckedWriteException writeFailure) {
        throw (IOException) writeFailure.getCause();
      } catch (UnorderedEdgeException unordered) {
        throw new CsrFormatException(file, unordered.getMessage(), unordered);
      } catch (IllegalArgumentException invalidNode) {
        throw new CsrFormatException(file, invalidNode.getMessage(), invalidNode);
      } catch (ArithmeticException overflow) {
        throw new CsrFormatException(
            file, "node and edge counts overflow the file layout", overflow);
      }
      Files.move(
          temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      published = true;
      return result;
    } finally {
      if (!published) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /**
   * Reads only the fixed header and checks its arithmetic and exact file size. No body byte is
   * mapped or checksummed by this method.
   */
  public static Descriptor describe(Path file) throws IOException {
    Objects.requireNonNull(file, "file");
    Path normalized = file.toAbsolutePath().normalize();
    try (FileChannel channel =
        FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return describe(channel, normalized);
    }
  }

  private static Descriptor describe(FileChannel channel, Path file) throws IOException {
    long size = channel.size();
    if (size < HEADER_BYTES) {
      throw new CsrFormatException(file, "shorter than a header");
    }
    ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    readFully(channel, bytes, 0, file);
    bytes.flip();
    byte[] magic = new byte[MAGIC.length];
    bytes.get(magic);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new CsrFormatException(file, "not a CSR index");
    }
    int version = bytes.getInt();
    if (version != FORMAT_VERSION) {
      throw new CsrFormatException(
          file, "format version " + version + ", and this build reads " + FORMAT_VERSION);
    }
    int flags = bytes.getInt();
    if ((flags & ~REVERSE_DIRECTION_FLAG) != 0) {
      throw new CsrFormatException(file, "unsupported flags " + flags);
    }
    long nodes = bytes.getLong();
    long edges = bytes.getLong();
    long checksum = bytes.getLong();
    Layout layout = layout(file, nodes, edges);
    if (layout.fileBytes() != size) {
      throw new CsrFormatException(
          file,
          "header promises "
              + layout.bodyBytes()
              + " bytes of graph and the file holds "
              + (size - HEADER_BYTES));
    }
    Header header =
        new Header(version, nodes, edges, checksum, (flags & REVERSE_DIRECTION_FLAG) != 0);
    return new Descriptor(
        file,
        header,
        layout.offsetBytes(),
        layout.targetBytes(),
        layout.bodyBytes(),
        layout.fileBytes());
  }

  /** The node and edge counts in a file's header, without reading its body. */
  public static Header headerOf(Path file) throws IOException {
    return describe(file).header();
  }

  /**
   * Maps and validates the body in bounded regions. The returned graph owns its arena and must be
   * closed, normally by a graph-index cache lease.
   */
  public static CsrGraph read(Path file) throws IOException {
    return open(describe(file));
  }

  /** Opens a descriptor already admitted by the caller, then checks checksum and structure. */
  public static CsrGraph open(Descriptor descriptor) throws IOException {
    return open(descriptor, MAP_SEGMENT_BYTES);
  }

  /** Test seam for exercising the same segmented reader at a small aligned segment size. */
  static CsrGraph open(Descriptor descriptor, long segmentBytes) throws IOException {
    Objects.requireNonNull(descriptor, "descriptor");
    if (segmentBytes <= 0 || segmentBytes % Long.BYTES != 0) {
      throw new IllegalArgumentException("CSR map segment size must be a positive multiple of 8");
    }
    Arena arena = Arena.ofShared();
    CsrGraph graph = null;
    try (FileChannel channel =
        FileChannel.open(descriptor.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      Descriptor pinned = describe(channel, descriptor.path());
      if (!pinned.equals(descriptor)) {
        throw new CsrFormatException(
            descriptor.path(), "changed after its header was admitted and before mapping");
      }
      Header header = pinned.header();
      int count = Math.toIntExact(ceilDiv(descriptor.bodyBytes(), segmentBytes));
      MemorySegment[] segments = new MemorySegment[count];
      long mapped = 0;
      for (int index = 0; index < count; index++) {
        long bytes = Math.min(segmentBytes, descriptor.bodyBytes() - mapped);
        segments[index] =
            channel.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES + mapped, bytes, arena);
        mapped += bytes;
      }
      CRC32C crc = new CRC32C();
      for (MemorySegment segment : segments) {
        crc.update(segment.asByteBuffer());
      }
      if (crc.getValue() != header.checksum()) {
        throw new CsrFormatException(descriptor.path(), "checksum does not match its contents");
      }
      graph =
          CsrGraph.mapped(
              header.nodeCount(),
              header.edgeCount(),
              descriptor.bodyBytes(),
              segmentBytes,
              segments,
              arena);
      validateStructure(descriptor.path(), graph);
      return graph;
    } catch (ArithmeticException malformed) {
      throw new CsrFormatException(
          descriptor.path(), "body structure cannot be represented safely", malformed);
    } catch (IOException | RuntimeException failure) {
      if (graph != null) {
        graph.close();
      } else if (arena.scope().isAlive()) {
        arena.close();
      }
      throw failure;
    }
  }

  private static void validateStructure(Path file, CsrGraph graph) throws CsrFormatException {
    long nodes = graph.nodeCount();
    long edges = graph.edgeCount();
    if (graph.offsetAt(0) != 0) {
      throw new CsrFormatException(file, "offsets must start at zero");
    }
    long previous = 0;
    for (int node = 0; node < nodes; node++) {
      long next = graph.neighborsEnd(node);
      if (next < previous || next > edges) {
        throw new CsrFormatException(
            file, "invalid offsets at node " + node + ": " + previous + " then " + next);
      }
      previous = next;
    }
    if (previous != edges) {
      throw new CsrFormatException(
          file, "final offset " + previous + " does not match edge count " + edges);
    }
    for (long edge = 0; edge < edges; edge++) {
      int target = graph.neighborAt(edge);
      if (target < 0 || target >= nodes) {
        throw new CsrFormatException(
            file, "target " + target + " at edge " + edge + " is outside the node range");
      }
    }
  }

  private static Layout layout(Path file, long nodeCount, long edgeCount)
      throws CsrFormatException {
    if (nodeCount < 0) {
      throw new CsrFormatException(file, "negative node count " + nodeCount);
    }
    if (nodeCount > Integer.MAX_VALUE - 1L) {
      throw new CsrFormatException(
          file, "node count " + nodeCount + " does not fit the dense int node-index format");
    }
    if (edgeCount < 0) {
      throw new CsrFormatException(file, "negative edge count " + edgeCount);
    }
    try {
      long offsets = Math.multiplyExact(Math.addExact(nodeCount, 1L), Long.BYTES);
      long targets = Math.multiplyExact(edgeCount, Integer.BYTES);
      long body = Math.addExact(offsets, targets);
      long total = Math.addExact(HEADER_BYTES, body);
      return new Layout(offsets, targets, body, total);
    } catch (ArithmeticException overflow) {
      throw new CsrFormatException(file, "node and edge counts overflow the file layout", overflow);
    }
  }

  private static void checkNode(Path file, int node, int nodeCount, String role) {
    if (node < 0 || node >= nodeCount) {
      throw new IllegalArgumentException(
          file + " has " + role + " node " + node + " outside [0, " + nodeCount + ")");
    }
  }

  private static long checksum(FileChannel channel, long bodyBytes) throws IOException {
    CRC32C crc = new CRC32C();
    ByteBuffer buffer = ByteBuffer.allocate(WRITE_BUFFER_BYTES);
    long read = 0;
    while (read < bodyBytes) {
      buffer.clear();
      buffer.limit((int) Math.min(buffer.capacity(), bodyBytes - read));
      int count = channel.read(buffer, HEADER_BYTES + read);
      if (count < 0) {
        throw new IOException("CSR body ended while calculating its checksum");
      }
      if (count == 0) {
        continue;
      }
      read += count;
      buffer.flip();
      crc.update(buffer);
    }
    return crc.getValue();
  }

  private static void writeHeader(
      FileChannel channel, long nodeCount, long edgeCount, long checksum, boolean reverseDirection)
      throws IOException {
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    header.put(MAGIC);
    header.putInt(FORMAT_VERSION);
    header.putInt(reverseDirection ? REVERSE_DIRECTION_FLAG : 0);
    header.putLong(nodeCount);
    header.putLong(edgeCount);
    header.putLong(checksum);
    header.flip();
    writeFully(channel, header, 0);
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position, Path file)
      throws IOException {
    long at = position;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, at);
      if (read < 0) {
        throw new CsrFormatException(file, "shorter than a header");
      }
      at += read;
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    long at = position;
    while (buffer.hasRemaining()) {
      at += channel.write(buffer, at);
    }
  }

  private static long ceilDiv(long value, long divisor) {
    return value == 0 ? 0 : 1L + (value - 1L) / divisor;
  }

  private record Layout(long offsetBytes, long targetBytes, long bodyBytes, long fileBytes) {}

  /** A validated header and exact layout, obtained without touching the body. */
  public record Descriptor(
      Path path,
      Header header,
      long offsetBytes,
      long targetBytes,
      long bodyBytes,
      long fileBytes) {}

  /** What a completed streaming write produced. */
  public record WriteResult(long edgeCount, long checksum, long fileBytes) {}

  /** What a file's header says about it. */
  public record Header(
      int formatVersion, long nodeCount, long edgeCount, long checksum, boolean reverseDirection) {
    /** Compatibility constructor for callers written before direction was exposed. */
    public Header(int formatVersion, long nodeCount, long edgeCount, long checksum) {
      this(formatVersion, nodeCount, edgeCount, checksum, false);
    }
  }

  private static final class PositionalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final long start;
    private final ByteBuffer buffer =
        ByteBuffer.allocate(WRITE_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private long written;

    PositionalWriter(FileChannel channel, long start) {
      this.channel = channel;
      this.start = start;
    }

    void putLong(long value) throws IOException {
      if (buffer.remaining() < Long.BYTES) {
        flush();
      }
      buffer.putLong(value);
    }

    void putLongUnchecked(long value) {
      try {
        putLong(value);
      } catch (IOException failure) {
        throw new UncheckedWriteException(failure);
      }
    }

    void putIntUnchecked(int value) {
      try {
        if (buffer.remaining() < Integer.BYTES) {
          flush();
        }
        buffer.putInt(value);
      } catch (IOException failure) {
        throw new UncheckedWriteException(failure);
      }
    }

    long bytesWritten() {
      return written;
    }

    private void flush() throws IOException {
      buffer.flip();
      int bytes = buffer.remaining();
      writeFully(channel, buffer, Math.addExact(start, written));
      written = Math.addExact(written, bytes);
      buffer.clear();
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }

  private static final class UncheckedWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UncheckedWriteException(IOException cause) {
      super(cause);
    }
  }

  private static final class UnorderedEdgeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnorderedEdgeException(int previousFrom, int previousTo, int from, int to) {
      super(
          "ordered edge stream moved backwards from "
              + previousFrom
              + " -> "
              + previousTo
              + " to "
              + from
              + " -> "
              + to);
    }
  }

  /** The file is not a CSR index this build reads safely. */
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

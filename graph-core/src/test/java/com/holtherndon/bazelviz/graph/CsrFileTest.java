package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A graph index survives a round trip, and a damaged one is refused.
 *
 * <p>The refusals matter more than the round trip. A truncated or stale CSR is not detectably wrong
 * by inspection — it is a smaller, perfectly valid graph that answers every question confidently.
 */
final class CsrFileTest {

  @TempDir Path tempDir;

  private static CsrGraph sample() {
    // 0 -> 1, 2 ; 1 -> 3 ; 2 -> 3 ; 3 -> (none)
    return CsrBuilder.build(
        4,
        visitor -> {
          visitor.edge(0, 1);
          visitor.edge(0, 2);
          visitor.edge(1, 3);
          visitor.edge(2, 3);
        });
  }

  @Test
  @DisplayName("a graph read back is the graph that was written")
  void roundTrip() throws Exception {
    Path file = tempDir.resolve("forward.csr");
    CsrFile.write(sample(), file);

    try (CsrGraph loaded = CsrFile.read(file)) {
      assertThat(loaded.nodeCount()).isEqualTo(4);
      assertThat(loaded.edgeCount()).isEqualTo(4);
      assertThat(neighbors(loaded, 0)).containsExactly(1, 2);
      assertThat(neighbors(loaded, 1)).containsExactly(3);
      assertThat(neighbors(loaded, 3)).isEmpty();
    }
  }

  @Test
  @DisplayName("an empty graph round-trips, because a build can have no edges")
  void emptyGraphRoundTrips() throws Exception {
    Path file = tempDir.resolve("empty.csr");
    CsrFile.write(CsrBuilder.build(3, visitor -> {}), file);

    try (CsrGraph loaded = CsrFile.read(file)) {
      assertThat(loaded.nodeCount()).isEqualTo(3);
      assertThat(loaded.edgeCount()).isZero();
      assertThat(neighbors(loaded, 0)).isEmpty();
    }
  }

  @Test
  @DisplayName("the header reports the counts without reading the body")
  void headerIsReadableAlone() throws Exception {
    Path file = tempDir.resolve("header.csr");
    long checksum = CsrFile.write(sample(), file);

    CsrFile.Header header = CsrFile.headerOf(file);
    assertThat(header.formatVersion()).isEqualTo(CsrFile.FORMAT_VERSION);
    assertThat(header.nodeCount()).isEqualTo(4);
    assertThat(header.edgeCount()).isEqualTo(4);
    // The registry stores this so a stale index can be spotted without
    // mapping it.
    assertThat(header.checksum()).isEqualTo(checksum);
  }

  @Test
  @DisplayName("a corrupted body is refused rather than answered from")
  void corruptionIsRefused() throws Exception {
    Path file = tempDir.resolve("corrupt.csr");
    CsrFile.write(sample(), file);

    byte[] bytes = Files.readAllBytes(file);
    // One edge target changed. The file is still structurally valid and
    // would produce a graph with a wrong edge in it.
    bytes[bytes.length - 1] ^= 0x01;
    Files.write(file, bytes);

    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("checksum does not match");
  }

  @Test
  @DisplayName("a truncated file is refused, not read as a smaller graph")
  void truncationIsRefused() throws Exception {
    Path file = tempDir.resolve("short.csr");
    CsrFile.write(sample(), file);

    byte[] bytes = Files.readAllBytes(file);
    Files.write(file, Arrays.copyOf(bytes, bytes.length - 4));

    // This is the failure the atomic rename exists to prevent, and the one
    // that would otherwise be invisible: a shorter CSR is a valid CSR.
    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("header promises");
  }

  @Test
  @DisplayName("a file from a future format is refused rather than guessed at")
  void unknownVersionIsRefused() throws Exception {
    Path file = tempDir.resolve("future.csr");
    CsrFile.write(sample(), file);

    byte[] bytes = Files.readAllBytes(file);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, CsrFile.FORMAT_VERSION + 1);
    Files.write(file, bytes);

    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("format version");
  }

  @Test
  @DisplayName("negative header counts are format errors")
  void negativeCountsAreRefusedBeforeArithmetic() throws Exception {
    Path negativeNodes = headerOnly("negative-nodes.csr", -1, 0);
    Path negativeEdges = headerOnly("negative-edges.csr", 0, -1);

    assertThatThrownBy(() -> CsrFile.read(negativeNodes))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("negative node count");
    assertThatThrownBy(() -> CsrFile.headerOf(negativeEdges))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("negative edge count");
  }

  @Test
  @DisplayName("node counts and overflowing layouts are refused before allocation")
  void unrepresentableCountsAreRefused() throws Exception {
    Path tooManyNodes = headerOnly("too-many-nodes.csr", Integer.MAX_VALUE, 0);
    Path overflowingEdges = headerOnly("overflowing-edges.csr", 0, Long.MAX_VALUE);

    assertThatThrownBy(() -> CsrFile.read(tooManyNodes))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("node count")
        .hasMessageContaining("does not fit");
    assertThatThrownBy(() -> CsrFile.describe(overflowingEdges))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("overflow");
  }

  @Test
  @DisplayName("a sparse descriptor larger than two GiB is inspected without mapping its body")
  void oversizedBodyDescriptorIsHeaderOnly() throws Exception {
    long nodes = 300_000_000L;
    long fileBytes = CsrFile.HEADER_BYTES + Math.multiplyExact(nodes + 1L, Long.BYTES);
    Path file = sparseHeader("large-header.csr", nodes, 0, fileBytes);

    CsrFile.Descriptor descriptor = CsrFile.describe(file);

    assertThat(descriptor.header().nodeCount()).isEqualTo(nodes);
    assertThat(descriptor.bodyBytes()).isGreaterThan((long) Integer.MAX_VALUE);
    assertThat(descriptor.fileBytes()).isEqualTo(fileBytes);
  }

  @Test
  @DisplayName("header-only reads normalize short input to a format error")
  void shortHeaderMetadataIsRefusedCleanly() throws Exception {
    Path file = tempDir.resolve("short-header.csr");
    Files.write(file, new byte[12]);

    assertThatThrownBy(() -> CsrFile.headerOf(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("shorter than a header");
  }

  @Test
  @DisplayName("something that is not an index at all is refused")
  void foreignFileIsRefused() throws Exception {
    Path file = tempDir.resolve("notanindex.csr");
    Files.writeString(file, "this is not a compressed sparse row anything at all");

    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("not a CSR index");
  }

  @Test
  @DisplayName("a rewrite leaves no temporary file behind")
  void writingIsAtomicAndTidy() throws Exception {
    Path file = tempDir.resolve("rewritten.csr");
    CsrFile.write(sample(), file);
    CsrFile.write(CsrBuilder.build(2, visitor -> visitor.edge(0, 1)), file);

    try (CsrGraph rewritten = CsrFile.read(file)) {
      assertThat(rewritten.nodeCount()).isEqualTo(2);
    }
    try (var entries = Files.list(tempDir)) {
      assertThat(entries.map(Path::getFileName).map(Path::toString))
          .noneMatch(name -> name.endsWith(".building"));
    }
  }

  @Test
  @DisplayName("the reverse index round-trips too, and disagrees with the forward one")
  void reverseRoundTrips() throws Exception {
    Path forward = tempDir.resolve("f.csr");
    Path reverse = tempDir.resolve("r.csr");
    CsrGraph graph = sample();
    CsrFile.write(graph, forward);
    CsrFile.write(CsrBuilder.reverse(graph), reverse, true);

    try (CsrGraph back = CsrFile.read(reverse);
        CsrGraph forwardBack = CsrFile.read(forward)) {
      assertThat(back.edgeCount()).isEqualTo(graph.edgeCount());
      // Node 3 has no forward neighbours and two reverse ones.
      assertThat(neighbors(forwardBack, 3)).isEmpty();
      assertThat(neighbors(back, 3)).containsExactlyInAnyOrder(1, 2);
    }
    assertThat(CsrFile.headerOf(forward).reverseDirection()).isFalse();
    assertThat(CsrFile.headerOf(reverse).reverseDirection()).isTrue();
  }

  @Test
  @DisplayName("the documented reverse flag is accepted and unknown flags are refused")
  void flagsAreValidatedBitwise() throws Exception {
    Path reverse = tempDir.resolve("flagged.csr");
    CsrFile.write(sample(), reverse);
    byte[] bytes = Files.readAllBytes(reverse);
    ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(12, CsrFile.REVERSE_DIRECTION_FLAG);
    Files.write(reverse, bytes);

    try (CsrGraph loaded = CsrFile.read(reverse)) {
      assertThat(loaded.edgeCount()).isEqualTo(4);
    }
    assertThat(CsrFile.headerOf(reverse).reverseDirection()).isTrue();

    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2);
    Files.write(reverse, bytes);
    assertThatThrownBy(() -> CsrFile.read(reverse))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("unsupported flags 2");
  }

  @Test
  @DisplayName("checksum-valid invalid offsets are still refused as structural corruption")
  void structurallyInvalidOffsetsAreRefused() throws Exception {
    Path file = tempDir.resolve("bad-offset.csr");
    CsrFile.write(sample(), file);
    byte[] bytes = Files.readAllBytes(file);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(CsrFile.HEADER_BYTES, 1L);
    updateChecksum(bytes);
    Files.write(file, bytes);

    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("offsets must start at zero");
  }

  @Test
  @DisplayName("checksum-valid out-of-range targets are format errors")
  void structurallyInvalidTargetsAreRefused() throws Exception {
    Path file = tempDir.resolve("bad-target.csr");
    CsrFile.write(sample(), file);
    byte[] bytes = Files.readAllBytes(file);
    int firstTarget = CsrFile.HEADER_BYTES + (4 + 1) * Long.BYTES;
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(firstTarget, 4);
    updateChecksum(bytes);
    Files.write(file, bytes);

    assertThatThrownBy(() -> CsrFile.read(file))
        .isInstanceOf(CsrFile.CsrFormatException.class)
        .hasMessageContaining("outside the node range");
  }

  @Test
  @DisplayName("aligned map-segment boundaries preserve offsets and targets")
  void segmentedMappingTraversesEveryBoundary() throws Exception {
    Path file = tempDir.resolve("segmented.csr");
    CsrGraph expected =
        CsrBuilder.build(
            12,
            visitor -> {
              for (int node = 0; node < 11; node++) {
                visitor.edge(node, node + 1);
              }
            });
    CsrFile.write(expected, file);

    try (CsrGraph mapped = CsrFile.open(CsrFile.describe(file), 16)) {
      assertThat(mapped.isMapped()).isTrue();
      assertThat(neighbors(mapped, 0)).containsExactly(1);
      assertThat(neighbors(mapped, 10)).containsExactly(11);
      assertThat(mapped.edgeCount()).isEqualTo(11);
    }
  }

  @Test
  @DisplayName("a mapped graph cannot be used after its scoped owner closes it")
  void mappedLifetimeIsScoped() throws Exception {
    Path file = tempDir.resolve("scoped.csr");
    CsrFile.write(sample(), file);
    CsrGraph graph = CsrFile.open(CsrFile.describe(file), 16);

    graph.close();

    assertThatThrownBy(() -> graph.degree(0)).isInstanceOf(IllegalStateException.class);
  }

  private static List<Integer> neighbors(CsrGraph graph, int node) {
    List<Integer> out = new ArrayList<>();
    graph.forEachNeighbor(node, out::add);
    return out;
  }

  private Path headerOnly(String name, long nodeCount, long edgeCount) throws Exception {
    ByteBuffer header = ByteBuffer.allocate(CsrFile.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    header.put(CsrFile.MAGIC);
    header.putInt(CsrFile.FORMAT_VERSION);
    header.putInt(0);
    header.putLong(nodeCount);
    header.putLong(edgeCount);
    header.putLong(0);
    Path file = tempDir.resolve(name);
    Files.write(file, header.array());
    return file;
  }

  private Path sparseHeader(String name, long nodeCount, long edgeCount, long fileBytes)
      throws Exception {
    Path file = headerOnly(name, nodeCount, edgeCount);
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      channel.position(fileBytes - 1);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    return file;
  }

  private static void updateChecksum(byte[] bytes) {
    CRC32C checksum = new CRC32C();
    checksum.update(bytes, CsrFile.HEADER_BYTES, bytes.length - CsrFile.HEADER_BYTES);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(32, checksum.getValue());
  }
}

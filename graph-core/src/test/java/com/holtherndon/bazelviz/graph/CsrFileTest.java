package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A graph index survives a round trip, and a damaged one is refused.
 *
 * <p>The refusals matter more than the round trip. A truncated or stale CSR is
 * not detectably wrong by inspection — it is a smaller, perfectly valid graph
 * that answers every question confidently.
 */
final class CsrFileTest {

    @TempDir
    Path tempDir;

    private static CsrGraph sample() {
        // 0 -> 1, 2 ; 1 -> 3 ; 2 -> 3 ; 3 -> (none)
        return CsrBuilder.build(4, visitor -> {
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

        CsrGraph loaded = CsrFile.read(file);

        assertThat(loaded.nodeCount()).isEqualTo(4);
        assertThat(loaded.edgeCount()).isEqualTo(4);
        assertThat(neighbors(loaded, 0)).containsExactly(1, 2);
        assertThat(neighbors(loaded, 1)).containsExactly(3);
        assertThat(neighbors(loaded, 3)).isEmpty();
    }

    @Test
    @DisplayName("an empty graph round-trips, because a build can have no edges")
    void emptyGraphRoundTrips() throws Exception {
        Path file = tempDir.resolve("empty.csr");
        CsrFile.write(CsrBuilder.build(3, visitor -> { }), file);

        CsrGraph loaded = CsrFile.read(file);
        assertThat(loaded.nodeCount()).isEqualTo(3);
        assertThat(loaded.edgeCount()).isZero();
        assertThat(neighbors(loaded, 0)).isEmpty();
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
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 4));

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

        assertThat(CsrFile.read(file).nodeCount()).isEqualTo(2);
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
        CsrFile.write(CsrBuilder.reverse(graph), reverse);

        CsrGraph back = CsrFile.read(reverse);
        assertThat(back.edgeCount()).isEqualTo(graph.edgeCount());
        // Node 3 has no forward neighbours and two reverse ones.
        assertThat(neighbors(CsrFile.read(forward), 3)).isEmpty();
        assertThat(neighbors(back, 3)).containsExactlyInAnyOrder(1, 2);
    }

    private static List<Integer> neighbors(CsrGraph graph, int node) {
        List<Integer> out = new ArrayList<>();
        graph.forEachNeighbor(node, out::add);
        return out;
    }
}

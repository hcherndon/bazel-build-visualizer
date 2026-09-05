package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves production CSR writing and traversal do not need a graph-sized heap body. */
final class CsrStreamingLowHeapTest {

  @TempDir Path tempDir;

  @Test
  void writesAndMapsBodyLargerThanMaximumHeap() throws Exception {
    int nodes = 8_500_000;
    Path file = tempDir.resolve("larger-than-heap.csr");

    CsrFile.WriteResult written = CsrFile.writeOrdered(nodes, visitor -> {}, file, false);
    CsrFile.Descriptor descriptor = CsrFile.describe(file);

    assertThat(written.edgeCount()).isZero();
    assertThat(descriptor.bodyBytes()).isGreaterThan(64L * 1_024 * 1_024);
    try (CsrGraph graph = CsrFile.open(descriptor)) {
      assertThat(graph.nodeCount()).isEqualTo(nodes);
      assertThat(graph.edgeCount()).isZero();
      assertThat(graph.degree(nodes - 1)).isZero();
    }
    try (var files = Files.list(tempDir)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.endsWith(".building"));
    }
  }
}

package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphIndexCacheTest {

  @TempDir Path tempDir;

  @Test
  void readersShareMappingsAndIdleEntriesEvictInAccessOrder() throws Exception {
    CsrFile.Descriptor first = graph("first.csr", 1);
    CsrFile.Descriptor second = graph("second.csr", 2);
    CsrFile.Descriptor third = graph("third.csr", 3);
    GraphResourceBudget budget = new GraphResourceBudget(10_000);
    GraphIndexCache cache = new GraphIndexCache(budget);

    CsrGraph identity;
    CsrGraph secondIdentity;
    try (GraphIndexCache.Lease one = cache.acquire(first);
        GraphIndexCache.Lease same = cache.acquire(first)) {
      identity = one.graph();
      assertThat(same.graph()).isSameAs(identity);
      assertThat(cache.cachedCount()).isEqualTo(1);
    }
    try (GraphIndexCache.Lease secondLease = cache.acquire(second)) {
      secondIdentity = secondLease.graph();
      // Make first most-recently used, leaving second as the idle LRU after this lease closes.
    }
    try (GraphIndexCache.Lease ignored = cache.acquire(first)) {}
    try (GraphIndexCache.Lease ignored = cache.acquire(third)) {
      assertThat(cache.cachedCount()).isEqualTo(GraphIndexCache.MAX_CACHED_INDEXES);
    }
    try (GraphIndexCache.Lease stillShared = cache.acquire(first)) {
      assertThat(stillShared.graph()).isSameAs(identity);
    }
    try (GraphIndexCache.Lease reloaded = cache.acquire(second)) {
      assertThat(reloaded.graph()).isNotSameAs(secondIdentity);
    }
    cache.close();
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }

  @Test
  void twoActiveEntriesRefuseAThirdAndClosingCacheDefersTheirRelease() throws Exception {
    CsrFile.Descriptor first = graph("active-one.csr", 1);
    CsrFile.Descriptor second = graph("active-two.csr", 2);
    CsrFile.Descriptor third = graph("blocked.csr", 3);
    GraphResourceBudget budget = new GraphResourceBudget(10_000);
    GraphIndexCache cache = new GraphIndexCache(budget);
    GraphIndexCache.Lease one = cache.acquire(first);
    GraphIndexCache.Lease two = cache.acquire(second);

    assertThatThrownBy(() -> cache.acquire(third))
        .isInstanceOf(GraphResourceBudget.RefusedException.class)
        .hasMessageContaining("leased");
    long retained = budget.snapshot().retainedBytes();
    cache.close();
    assertThat(budget.snapshot().retainedBytes()).isEqualTo(retained);
    one.close();
    assertThat(budget.snapshot().retainedBytes()).isPositive();
    two.close();
    assertThat(budget.snapshot().retainedBytes()).isZero();
    assertThatThrownBy(() -> cache.acquire(third)).isInstanceOf(IOException.class);
  }

  @Test
  void pairAdmissionAndSecondOpenFailureRollBackCompletely() throws Exception {
    CsrFile.Descriptor forward = graph("pair-forward.csr", 1);
    CsrFile.Descriptor reverse = graph("pair-reverse.csr", 2);
    GraphResourceBudget exact =
        new GraphResourceBudget(Math.addExact(forward.fileBytes(), reverse.fileBytes()));
    GraphIndexCache cache = new GraphIndexCache(exact);
    try (GraphIndexCache.PairLease pair = cache.acquirePair(forward, reverse)) {
      assertThat(cache.cachedCount()).isEqualTo(2);
    }
    cache.close();
    assertThat(exact.snapshot().retainedBytes()).isZero();

    CsrFile.Descriptor corrupt = graph("corrupt.csr", 3);
    byte[] bytes = Files.readAllBytes(corrupt.path());
    bytes[bytes.length - 1] ^= 1;
    Files.write(corrupt.path(), bytes);
    GraphResourceBudget rollbackBudget = new GraphResourceBudget(10_000);
    GraphIndexCache rollback = new GraphIndexCache(rollbackBudget);
    assertThatThrownBy(() -> rollback.acquirePair(forward, corrupt))
        .isInstanceOf(CsrFile.CsrFormatException.class);
    assertThat(rollback.cachedCount()).isZero();
    assertThat(rollbackBudget.snapshot().retainedBytes()).isZero();
  }

  private CsrFile.Descriptor graph(String name, int target) throws Exception {
    Path file = tempDir.resolve(name);
    CsrFile.write(
        CsrBuilder.build(4, visitor -> visitor.edge(0, Math.min(target, 3))), file, target == 2);
    return CsrFile.describe(file);
  }
}

package com.holtherndon.bazelviz.ui.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PageCacheTest {

  private static Page<String> page(long index) {
    return new Page<>(index, List.of("p" + index));
  }

  @Test
  void rejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new PageCache<String>(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageCache<String>(-3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void countsMissesAndHits() {
    PageCache<String> cache = new PageCache<>(2);
    assertThat(cache.get(7)).isNull();
    cache.put(page(7));
    assertThat(cache.get(7)).isEqualTo(page(7));
    assertThat(cache.get(7)).isEqualTo(page(7));

    PageCache.Stats stats = cache.stats();
    assertThat(stats.misses()).isEqualTo(1);
    assertThat(stats.hits()).isEqualTo(2);
    assertThat(stats.evictions()).isZero();
    assertThat(stats.size()).isEqualTo(1);
  }

  @Test
  void evictsLeastRecentlyUsedPage() {
    PageCache<String> cache = new PageCache<>(2);
    cache.put(page(0));
    cache.put(page(1));
    assertThat(cache.get(0)).isNotNull(); // refresh recency of page 0
    cache.put(page(2)); // page 1 is now LRU and must go

    assertThat(cache.peek(0)).isTrue();
    assertThat(cache.peek(1)).isFalse();
    assertThat(cache.peek(2)).isTrue();

    PageCache.Stats stats = cache.stats();
    assertThat(stats.evictions()).isEqualTo(1);
    assertThat(stats.size()).isEqualTo(2);
  }

  @Test
  void peekTouchesNeitherStatsNorRecency() {
    PageCache<String> cache = new PageCache<>(2);
    cache.put(page(0));
    cache.put(page(1));
    assertThat(cache.peek(0)).isTrue(); // must NOT refresh recency
    cache.put(page(2)); // page 0 is still eldest, so it is the one evicted

    assertThat(cache.peek(0)).isFalse();
    assertThat(cache.peek(1)).isTrue();
    assertThat(cache.peek(2)).isTrue();

    PageCache.Stats stats = cache.stats();
    assertThat(stats.hits()).isZero();
    assertThat(stats.misses()).isZero();
    assertThat(stats.evictions()).isEqualTo(1);
  }

  @Test
  void replacingAPageDoesNotEvict() {
    PageCache<String> cache = new PageCache<>(2);
    cache.put(page(0));
    cache.put(new Page<>(0, List.of("replacement")));
    cache.put(page(1));

    PageCache.Stats stats = cache.stats();
    assertThat(stats.evictions()).isZero();
    assertThat(stats.size()).isEqualTo(2);
    assertThat(cache.get(0).rows()).containsExactly("replacement");
  }
}

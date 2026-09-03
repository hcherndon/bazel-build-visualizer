package com.holtherndon.bazelviz.ui.criticalpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.analysis.CriticalPaths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bounded paging contract for Bazel's profile-reported path. */
final class BazelPathRowSourceTest {

  @Test
  @DisplayName("counting rows is free and a page reads only its ordinal window")
  void pagesOnlyTheRequestedOrdinalWindow() {
    List<String> requests = new ArrayList<>();
    BazelPathRowSource source =
        new BazelPathRowSource(
            405,
            (first, limit) -> {
              requests.add(first + ":" + limit);
              List<CriticalPaths.BazelComponent> rows = new ArrayList<>();
              for (long ordinal = first; ordinal < first + limit; ordinal++) {
                rows.add(component(Math.toIntExact(ordinal)));
              }
              return rows;
            });

    assertThat(source.rowCount()).isEqualTo(405);
    assertThat(requests).isEmpty();

    assertThat(source.fetchPage(1, 200).rows())
        .hasSize(200)
        .extracting(CriticalPaths.BazelComponent::ordinal)
        .startsWith(200, 201)
        .endsWith(398, 399);
    assertThat(source.fetchPage(2, 200).rows())
        .extracting(CriticalPaths.BazelComponent::ordinal)
        .containsExactly(400, 401, 402, 403, 404);
    assertThat(requests).containsExactly("200:200", "400:5");
  }

  @Test
  @DisplayName("a page beyond the exact count performs no query")
  void pageBeyondEndIsCheap() {
    AtomicInteger calls = new AtomicInteger();
    BazelPathRowSource source =
        new BazelPathRowSource(
            2,
            (first, limit) -> {
              calls.incrementAndGet();
              return List.of();
            });

    assertThat(source.fetchPage(1, 2).rows()).isEmpty();
    assertThat(calls).hasValue(0);
  }

  @Test
  @DisplayName("query and ordinal-integrity failures stay visible to the paged table")
  void failuresAreNotRenderedAsEmptyRows() {
    BazelPathRowSource failed =
        new BazelPathRowSource(
            2,
            (first, limit) -> {
              throw new SQLException("database is closed");
            });
    assertThatThrownBy(() -> failed.fetchPage(0, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read Bazel critical-path components")
        .hasRootCauseMessage("database is closed");

    BazelPathRowSource shortPage =
        new BazelPathRowSource(2, (first, limit) -> List.of(component(0)));
    assertThatThrownBy(() -> shortPage.fetchPage(0, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("returned 1 of 2 rows");

    BazelPathRowSource gap =
        new BazelPathRowSource(2, (first, limit) -> List.of(component(0), component(2)));
    assertThatThrownBy(() -> gap.fetchPage(0, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected 1 but read 2");
  }

  private static CriticalPaths.BazelComponent component(int ordinal) {
    return new CriticalPaths.BazelComponent(
        ordinal, "component " + ordinal, OptionalLong.of(ordinal + 1L));
  }
}

package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.table.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The million-row random-access case, isolated for cache and runtime accounting. It models a
 * scroll-thumb jump with nothing fetched in between: the case OFFSET paging would make slow and
 * arithmetic anchoring must keep constant.
 */
class EventRowSourceScaleTest {

  private static final int PAGE_SIZE = 100;

  @Test
  @DisplayName("a jump to the far end of the table fetches exactly that page's rows")
  void farJumpLandsOnTheRightRows() {
    FakeSessionReader reader = FakeSessionReader.dense(1_000_000);
    EventRowSource source = EventRowSource.open(reader, PAGE_SIZE);
    int pagesBefore = reader.pageAfterCalls();

    // Straight to row 900,000 with nothing fetched in between, the way a
    // dragged scroll thumb arrives.
    Page<EventRow> page = source.fetchPage(9_000, PAGE_SIZE);

    assertThat(page.pageIndex()).isEqualTo(9_000);
    assertThat(page.rows()).hasSize(PAGE_SIZE);
    assertThat(page.rows().getFirst().id()).isEqualTo(900_001);
    assertThat(page.rows().getLast().id()).isEqualTo(900_100);
    assertThat(page.rows()).extracting(EventRow::sequence).startsWith(900_000L).endsWith(900_099L);
    // One keyset page for the rows themselves. The store was never walked
    // from the beginning, and nothing counted the 900,000 skipped rows.
    assertThat(reader.pageAfterCalls() - pagesBefore).isEqualTo(1);
  }
}

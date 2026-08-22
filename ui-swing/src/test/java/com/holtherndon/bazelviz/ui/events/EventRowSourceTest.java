package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.table.Page;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bridge between an index-addressed table and a keyset-paged store.
 *
 * <p>These tests are about the one thing that could quietly go wrong here:
 * page N showing the rows of some other page. Every assertion checks the actual
 * ids that came back against the ids that row range must contain, including for
 * a jump to the far end of the table with nothing fetched in between — the
 * scroll-thumb drag that {@code OFFSET} paging would have made slow and that
 * arithmetic anchoring makes free.
 */
class EventRowSourceTest {

    private static final int PAGE_SIZE = 100;

    @Test
    @DisplayName("contiguous ids are recognised and resolved by arithmetic, not by scanning")
    void denseIdsResolveWithoutWalking() {
        FakeSessionReader reader = FakeSessionReader.dense(10_000);
        EventRowSource source = EventRowSource.open(reader, PAGE_SIZE);

        assertThat(source.rowCount()).isEqualTo(10_000);
        assertThat(source.rowIndexMode()).isEqualTo(EventRowIndex.Mode.DENSE);
        // Opening costs one count plus a first-row and a last-row probe. No scan.
        assertThat(reader.pageAfterCalls()).isEqualTo(1);
        assertThat(reader.pageBeforeCalls()).isEqualTo(1);
    }

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
        assertThat(page.rows()).extracting(EventRow::sequence)
                .startsWith(900_000L)
                .endsWith(900_099L);
        // One keyset page for the rows themselves. The store was never walked
        // from the beginning, and nothing counted the 900,000 skipped rows.
        assertThat(reader.pageAfterCalls() - pagesBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("every page boundary maps onto the rows that row range contains")
    void pageBoundariesLineUp() {
        FakeSessionReader reader = FakeSessionReader.dense(1_000);
        EventRowSource source = EventRowSource.open(reader, PAGE_SIZE);

        for (long pageIndex = 0; pageIndex < 10; pageIndex++) {
            Page<EventRow> page = source.fetchPage(pageIndex, PAGE_SIZE);
            long firstRow = pageIndex * PAGE_SIZE;
            assertThat(page.rows()).as("page %d", pageIndex).hasSize(PAGE_SIZE);
            assertThat(page.rows().getFirst().sequence()).isEqualTo(firstRow);
            assertThat(page.rows().getLast().sequence()).isEqualTo(firstRow + PAGE_SIZE - 1);
        }
    }

    @Test
    @DisplayName("the final page is short rather than padded")
    void finalPageIsShort() {
        FakeSessionReader reader = FakeSessionReader.dense(250);
        EventRowSource source = EventRowSource.open(reader, PAGE_SIZE);

        Page<EventRow> last = source.fetchPage(2, PAGE_SIZE);

        assertThat(last.rows()).hasSize(50);
        assertThat(last.rows().getLast().id()).isEqualTo(250);
        assertThat(source.fetchPage(3, PAGE_SIZE).rows()).isEmpty();
    }

    @Test
    @DisplayName("ids with gaps fall back to a bounded sparse anchor index and still land right")
    void sparseIdsResolveThroughAnchors() {
        // Every third id, so maxId - minId + 1 != count and arithmetic cannot work.
        long[] ids = new long[500];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 1 + i * 3L;
        }
        FakeSessionReader reader = FakeSessionReader.withIds(ids);
        EventRowSource source = EventRowSource.open(reader, 50);

        assertThat(source.rowIndexMode()).isEqualTo(EventRowIndex.Mode.SPARSE_ANCHORS);

        Page<EventRow> middle = source.fetchPage(7, 50);

        assertThat(middle.rows()).hasSize(50);
        assertThat(middle.rows().getFirst().id()).isEqualTo(ids[350]);
        assertThat(middle.rows().getLast().id()).isEqualTo(ids[399]);
        assertThat(middle.rows().getFirst().sequence()).isEqualTo(350);
    }

    @Test
    @DisplayName("a page that comes back short is reported, never silently rendered")
    void shortPageIsAFailureNotAnEmptyRow() {
        FakeSessionReader reader = FakeSessionReader.dense(300);
        EventRowSource source = EventRowSource.open(reader, PAGE_SIZE);
        assertThat(source.rowCount()).isEqualTo(300);

        // The rows go away underneath the open view. The model must not render
        // a page it could not fill: PagedTableModel turns this throw into the
        // ⚠ cell, which is distinguishable from a page still loading.
        reader.truncateTo(150);

        assertThatThrownBy(() -> source.fetchPage(2, PAGE_SIZE))
                .isInstanceOf(SessionDataException.class)
                .hasMessageContaining("the store returned 0");
    }

    @Test
    @DisplayName("a source opened for one page size refuses to serve another")
    void pageSizeIsFixedAtOpen() {
        EventRowSource source = EventRowSource.open(FakeSessionReader.dense(10), PAGE_SIZE);

        assertThatThrownBy(() -> source.fetchPage(0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot serve pages of 25");
    }

    @Test
    @DisplayName("an empty session is zero rows, not a failure")
    void emptySession() {
        EventRowSource source = EventRowSource.open(new FakeSessionReader(), PAGE_SIZE);

        assertThat(source.rowCount()).isZero();
        assertThat(source.fetchPage(0, PAGE_SIZE).rows()).isEqualTo(List.of());
    }
}

package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.table.Page;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The row-index-to-keyset-anchor mapping itself. */
class EventRowIndexTest {

    @Test
    @DisplayName("contiguous ids resolve arithmetically, with the first id predicted")
    void denseAnchors() {
        EventRowIndex index = EventRowIndex.open(FakeSessionReader.dense(1_000), 100);

        assertThat(index.mode()).isEqualTo(EventRowIndex.Mode.DENSE);
        assertThat(index.anchorForRow(0))
                .isEqualTo(new EventRowIndex.Anchor(OptionalLong.empty(), OptionalLong.of(1)));
        assertThat(index.anchorForRow(300))
                .isEqualTo(new EventRowIndex.Anchor(OptionalLong.of(300), OptionalLong.of(301)));
        assertThat(index.anchorForRow(900))
                .isEqualTo(new EventRowIndex.Anchor(OptionalLong.of(900), OptionalLong.of(901)));
        // Nothing was retained: dense mode needs no anchors at all.
        assertThat(index.retainedAnchors()).isZero();
    }

    @Test
    @DisplayName("only page starts are accepted, and only inside the table")
    void anchorsAreOnlyForPageStarts() {
        EventRowIndex index = EventRowIndex.open(FakeSessionReader.dense(500), 100);

        assertThatThrownBy(() -> index.anchorForRow(150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not start a page");
        assertThatThrownBy(() -> index.anchorForRow(500))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> index.anchorForRow(-100))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("gapped ids build a sparse index whose anchors are the real boundary rows")
    void sparseAnchorsPointAtRealRows() {
        long[] ids = new long[400];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 10 + i * 7L;
        }
        FakeSessionReader reader = FakeSessionReader.withIds(ids);
        EventRowIndex index = EventRowIndex.open(reader, 50);

        assertThat(index.mode()).isEqualTo(EventRowIndex.Mode.SPARSE_ANCHORS);

        EventRowIndex.Anchor anchor = index.anchorForRow(200);

        // Row 200's exclusive anchor is the id of row 199.
        assertThat(anchor.exclusiveId()).hasValue(ids[199]);
        // Sparse mode knows anchors and not the rows between them, so it makes
        // no prediction rather than a guess the caller would then "verify".
        assertThat(anchor.expectedFirstId()).isEmpty();
        assertThat(index.retainedAnchors()).isEqualTo(8);
    }

    @Test
    @DisplayName("a prediction that turns out wrong demotes the index and the fetch still lands right")
    void mismatchDemotesAndTheRetrySucceeds() {
        FakeSessionReader reader = FakeSessionReader.dense(500);
        EventRowSource source = EventRowSource.open(reader, 100);
        assertThat(source.rowIndexMode()).isEqualTo(EventRowIndex.Mode.DENSE);

        // Every id moves. Arithmetic now predicts row 0 to be id 1 when it is id 6.
        reader.shiftIdsBy(5);

        Page<EventRow> first = source.fetchPage(0, 100);

        assertThat(source.rowIndexMode()).isEqualTo(EventRowIndex.Mode.SPARSE_ANCHORS);
        assertThat(first.rows()).hasSize(100);
        assertThat(first.rows().getFirst().id()).isEqualTo(6);
        assertThat(first.rows().getLast().id()).isEqualTo(105);

        // And a later page, resolved through the rebuilt anchors, is right too.
        Page<EventRow> fourth = source.fetchPage(3, 100);
        assertThat(fourth.rows().getFirst().id()).isEqualTo(306);
        assertThat(fourth.rows().getFirst().sequence()).isEqualTo(300);
    }

    @Test
    @DisplayName("the anchor array stays bounded however large the table gets")
    void strideKeepsTheAnchorArrayBounded() {
        // A stride equal to the page size covers everything up to
        // MAX_ANCHORS * pageSize rows; past that the stride grows instead of
        // the array, so memory is capped at half a megabyte of longs.
        assertThat(EventRowIndex.chooseStride(1_000, 200)).isEqualTo(200);
        assertThat(EventRowIndex.chooseStride(10_000_000L, 200)).isEqualTo(200);
        long huge = 2_000_000_000L;
        int stride = EventRowIndex.chooseStride(huge, 200);
        assertThat(stride % 200).isZero();
        assertThat((huge + stride - 1) / stride).isLessThanOrEqualTo(EventRowIndex.MAX_ANCHORS);
    }
}

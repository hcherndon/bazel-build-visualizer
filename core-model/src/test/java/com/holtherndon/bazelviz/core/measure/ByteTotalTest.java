package com.holtherndon.bazelviz.core.measure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A byte total that says what it could not count (plan 11.3). */
class ByteTotalTest {

    @Test
    @DisplayName("a total with an unsized artifact is a lower bound, not a total")
    void unknownSizesMakeTheTotalInexact() {
        ByteTotal total = ByteTotal.EMPTY
                .plusKnown(1_000)
                .plusKnown(2_000)
                .plusUnknown();

        assertThat(total.knownBytes()).isEqualTo(3_000);
        assertThat(total.uniqueArtifacts()).isEqualTo(3);
        assertThat(total.unknownSizeArtifacts()).isEqualTo(1);
        assertThat(total.isPartial()).isTrue();
        // Refused rather than rounded: presenting 3,000 as the total would be
        // the "never convert unavailable data to zero" rule broken by omission.
        assertThat(total.exactTotal()).isEmpty();
    }

    @Test
    @DisplayName("a total where every artifact reported a size is exact")
    void completeTotalsAreExact() {
        ByteTotal total = ByteTotal.EMPTY.plusKnown(10).plusKnown(32);

        assertThat(total.exactTotal()).hasValue(42);
        assertThat(total.isPartial()).isFalse();
    }

    @Test
    @DisplayName("a repeated reference is counted as a duplicate, not as more bytes")
    void duplicateReferencesDoNotInflateTheTotal() {
        ByteTotal total = ByteTotal.EMPTY
                .plusKnown(500)
                .plusDuplicateReference()
                .plusDuplicateReference();

        // Depsets share heavily -- that is what they are for -- so counting a
        // reference rather than an artifact inflates the total by the sharing
        // factor, which is largest on exactly the builds people most want
        // measured.
        assertThat(total.knownBytes()).isEqualTo(500);
        assertThat(total.uniqueArtifacts()).isEqualTo(1);
        assertThat(total.duplicateReferences()).isEqualTo(2);
        assertThat(total.exactTotal()).hasValue(500);
    }

    @Test
    @DisplayName("totals combine without losing what either could not count")
    void totalsCombine() {
        ByteTotal left = ByteTotal.EMPTY.plusKnown(100).plusUnknown();
        ByteTotal right = ByteTotal.EMPTY.plusKnown(200);

        ByteTotal combined = left.plus(right);

        assertThat(combined.knownBytes()).isEqualTo(300);
        assertThat(combined.uniqueArtifacts()).isEqualTo(3);
        assertThat(combined.unknownSizeArtifacts()).isEqualTo(1);
        assertThat(combined.exactTotal()).isEmpty();
    }

    @Test
    @DisplayName("the description states the shortfall rather than hiding it")
    void descriptionStatesTheShortfall() {
        ByteTotal partial = ByteTotal.EMPTY.plusKnown(4_200).plusUnknown().plusUnknown();

        assertThat(partial.describe(bytes -> bytes + " B"))
                .isEqualTo("4200 B across 3 artifacts, 2 of unknown size");
        assertThat(ByteTotal.EMPTY.describe(bytes -> bytes + " B")).isEqualTo("no artifacts");
    }
}

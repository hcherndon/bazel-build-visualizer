package com.holtherndon.bazelviz.capture.file.importer;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One progress sample from a running import.
 *
 * <p>{@code totalBytes} is an {@link OptionalLong} and stays empty whenever the
 * total is genuinely unknown — a stream still being written, a source whose
 * size could not be stat'd. A caller that receives an empty total must show an
 * indeterminate indicator; inventing a denominator so the bar can reach 100%
 * would be reporting an unavailable value as if it were known (plan 11.4).
 *
 * <p>{@code recordsRead} counts records journaled so far, not events stored:
 * the two differ while a batch is still queued in the writer, and the honest
 * number to show during an import is the one the pipeline has actually
 * consumed.
 *
 * @param phase which stage produced this sample
 * @param recordsRead records read from the source and journaled so far
 * @param bytesRead bytes of the source consumed so far
 * @param totalBytes total size of the source when known, empty when not
 */
public record ImportProgress(
        ImportPhase phase, long recordsRead, long bytesRead, OptionalLong totalBytes) {

    public ImportProgress {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(totalBytes, "totalBytes");
        if (recordsRead < 0) {
            throw new IllegalArgumentException("recordsRead must be >= 0, got " + recordsRead);
        }
        if (bytesRead < 0) {
            throw new IllegalArgumentException("bytesRead must be >= 0, got " + bytesRead);
        }
    }

    /**
     * Completed fraction in {@code [0, 1]}, present only when a total is known
     * and non-zero. Empty is the honest answer otherwise, and callers must
     * render it as indeterminate rather than as zero or as complete.
     */
    public java.util.OptionalDouble fractionComplete() {
        if (totalBytes.isEmpty() || totalBytes.getAsLong() <= 0) {
            return java.util.OptionalDouble.empty();
        }
        double total = totalBytes.getAsLong();
        return java.util.OptionalDouble.of(Math.min(1.0, bytesRead / total));
    }
}

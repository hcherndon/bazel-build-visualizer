package com.holtherndon.bazelviz.core.source;

/**
 * How complete a capture source is known to be.
 *
 * <p>The distinction between {@link #TRUNCATED} and {@link #CORRUPT_PARTIAL}
 * is deliberate and load-bearing (plan section 21.3): a clean short tail is a
 * normal outcome of a cancelled or still-running build, while a checksum
 * failure on a fully present record means the bytes on disk are wrong. They
 * warrant different messages and different user actions, so they must never be
 * collapsed into one "incomplete" state.
 *
 * <p>{@link #UNKNOWN} is a real answer, not a placeholder for zero — a source
 * whose completeness has not been established must say so (plan 11.4).
 */
public enum Completeness {
    /** Every record the source claims to contain was read intact. */
    COMPLETE,

    /** The source ends mid-record. Everything before the cut is intact. */
    TRUNCATED,

    /** A fully present record failed validation. Data before it is still usable. */
    CORRUPT_PARTIAL,

    /** The source was expected but never produced (a failed enrichment command). */
    UNAVAILABLE,

    /** Completeness has not been determined yet. */
    UNKNOWN;

    /** True when the source can be read end to end with nothing missing. */
    public boolean isComplete() {
        return this == COMPLETE;
    }

    /** True when some data is present and usable, even if not all of it. */
    public boolean hasUsableData() {
        return this == COMPLETE || this == TRUNCATED || this == CORRUPT_PARTIAL;
    }
}

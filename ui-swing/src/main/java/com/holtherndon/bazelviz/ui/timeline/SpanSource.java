package com.holtherndon.bazelviz.ui.timeline;

/**
 * Minimal streaming feed of time spans for timeline aggregation.
 *
 * <p>This abstraction exists so ui-swing can consume spans from any producer
 * (synthetic generators, SQL cursors, capture pipelines) without depending on
 * those modules. Implementations must be able to replay the feed via
 * {@link #forEachSpan} without materializing the spans: the consumer is invoked
 * once per span and must not retain per-span objects.
 */
public interface SpanSource {

    /** Number of spans {@link #forEachSpan} will deliver. */
    long spanCount();

    /** Streams every span into {@code consumer}, in any order. */
    void forEachSpan(SpanConsumer consumer);

    /** Primitive-only per-span callback; no boxing, no per-span allocation required. */
    @FunctionalInterface
    interface SpanConsumer {

        /**
         * @param startMicros inclusive start of the span
         * @param endMicros exclusive end of the span; {@code endMicros == startMicros}
         *     is a legal zero-length span
         * @param categoryIndex small dense category id, normally a mnemonic
         * @param flags bitwise-or of the {@code FLAG_*} constants on
         *     {@link SpanSource}
         * @param bytes input or output bytes attributable to this span, or
         *     {@link #BYTES_UNKNOWN} when no source reported any. Zero means a
         *     span that really moved no bytes, which is not the same thing —
         *     plan 11.4, and the reason this is a named constant rather than a
         *     zero.
         */
        void accept(long startMicros, long endMicros, int categoryIndex, int flags, long bytes);
    }

    /** The span represents work that failed. */
    int FLAG_FAILED = 1;

    /**
     * The span was satisfied from a cache rather than executed.
     *
     * <p>From the execution log's {@code cache_hit}. A span with neither this
     * flag nor {@link #FLAG_CACHE_KNOWN} is one nothing has told us about,
     * which is every span in a session with no execution log.
     */
    int FLAG_CACHE_HIT = 1 << 1;

    /**
     * Something reported whether this span was a cache hit.
     *
     * <p>Without it a missing {@link #FLAG_CACHE_HIT} means "unknown" rather
     * than "executed", and a timeline coloured by cache result must say so
     * instead of painting every span as a miss.
     */
    int FLAG_CACHE_KNOWN = 1 << 2;

    /** The span ran somewhere other than this machine. */
    int FLAG_REMOTE = 1 << 3;

    /** Something reported where this span ran. */
    int FLAG_RUNNER_KNOWN = 1 << 4;

    /**
     * No source reported a byte count for this span.
     *
     * <p>Negative so it cannot be confused with a real total, and so a sum that
     * accidentally includes it goes obviously wrong rather than quietly small.
     */
    long BYTES_UNKNOWN = -1;
}

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
         * @param startMicros   inclusive start of the span
         * @param endMicros     exclusive end of the span; {@code endMicros == startMicros}
         *                      is a legal zero-length span
         * @param categoryIndex small dense category id (e.g. mnemonic index); carried for
         *                      future top-category aggregation, unused by the spike index
         * @param failed        whether the span represents a failed action
         */
        void accept(long startMicros, long endMicros, int categoryIndex, boolean failed);
    }
}

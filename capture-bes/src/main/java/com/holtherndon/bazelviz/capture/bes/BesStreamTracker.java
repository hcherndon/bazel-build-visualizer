package com.holtherndon.bazelviz.capture.bes;

import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * The mutable half of {@link BesStreamState}: one stream's sequence bookkeeping.
 *
 * <h2>Two watermarks, moved by two different events</h2>
 *
 * <p>{@link #accept} moves the <em>received</em> watermark, and it happens on
 * the gRPC thread as bytes arrive. {@link #journaled} moves the
 * <em>contiguous</em> watermark, and it happens on the pipeline thread once the
 * frame is durable enough to acknowledge. Keeping them apart is the whole point:
 * an event that has arrived is not an event we can promise Bazel we have, and
 * collapsing the two into one counter is exactly how a capture ends up
 * acknowledging data it later loses.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method is synchronized on this object. The contention is negligible —
 * two threads, a few field updates — and the alternative, reasoning about which
 * of the two watermarks may be read while the other is being written, is how a
 * gap gets reported that never existed.
 */
final class BesStreamTracker {

    /**
     * How many events may be held above the contiguous watermark before the
     * stream is treated as broken.
     *
     * <p>On a single BES stream this should never be reached: the events arrive
     * on one ordered HTTP/2 stream, so sequence numbers come in order. The bound
     * exists because "should never" is not "cannot", and an unbounded set here
     * would turn a misbehaving client into an out-of-memory failure of the whole
     * application (plan 9.2, "buffer only a bounded number of out-of-order
     * events").
     */
    static final int DEFAULT_MAX_OUT_OF_ORDER = 1024;

    /** What the tracker decided about an arriving sequence number. */
    enum Decision {
        /** Not seen before; journal it. */
        ACCEPTED,
        /**
         * Seen before. Acknowledge it again and do not journal it: Bazel
         * retransmits after a reconnect, and a retransmission is a normal
         * event, not an error (plan 9.2, "accept duplicate retransmissions
         * idempotently").
         */
        DUPLICATE,
        /** So far ahead of the watermark that buffering it would be unbounded. */
        TOO_FAR_AHEAD,
        /** Not a legal BES sequence number. */
        INVALID
    }

    private final BesStreamKey key;
    private final int maxOutOfOrder;

    /** Sequences journaled but sitting above the contiguous watermark. */
    private final NavigableSet<Long> journaledAhead = new TreeSet<>();

    /** Sequences accepted from the wire but not yet journaled. */
    private final NavigableSet<Long> inFlight = new TreeSet<>();

    private long highestReceived;
    private long highestContiguous;
    private long highestAcknowledged;
    private long eventsAccepted;
    private long duplicateCount;
    private long firstReceiveMicros = -1;
    private long lastReceiveMicros = -1;
    private BesStreamState.Completion completion = BesStreamState.Completion.OPEN;
    private String error;

    BesStreamTracker(BesStreamKey key) {
        this(key, DEFAULT_MAX_OUT_OF_ORDER);
    }

    BesStreamTracker(BesStreamKey key, int maxOutOfOrder) {
        this.key = Objects.requireNonNull(key, "key");
        if (maxOutOfOrder < 1) {
            throw new IllegalArgumentException("maxOutOfOrder must be positive, got " + maxOutOfOrder);
        }
        this.maxOutOfOrder = maxOutOfOrder;
    }

    BesStreamKey key() {
        return key;
    }

    /**
     * Records an arriving sequence number and says what to do with it.
     *
     * <p>A sequence at or below the contiguous watermark is a duplicate. So is
     * one already in flight or already journaled ahead of the watermark — a
     * reconnecting client can resend an event we are still writing, and treating
     * that as new would put the same event in the journal twice.
     */
    synchronized Decision accept(long sequence, long receiveMicros) {
        if (sequence < BesStreamState.FIRST_SEQUENCE) {
            return Decision.INVALID;
        }
        if (sequence <= highestContiguous || inFlight.contains(sequence) || journaledAhead.contains(sequence)) {
            duplicateCount++;
            return Decision.DUPLICATE;
        }
        if (inFlight.size() + journaledAhead.size() >= maxOutOfOrder) {
            return Decision.TOO_FAR_AHEAD;
        }
        inFlight.add(sequence);
        eventsAccepted++;
        highestReceived = Math.max(highestReceived, sequence);
        if (firstReceiveMicros < 0) {
            firstReceiveMicros = receiveMicros;
        }
        lastReceiveMicros = receiveMicros;
        return Decision.ACCEPTED;
    }

    /**
     * Records that a sequence's frame is in the journal, and returns every
     * sequence that may now be acknowledged.
     *
     * <p>The returned range is contiguous and starts just after the last
     * acknowledgement, so acknowledgements are emitted in order and never skip a
     * gap. When a gap exists the range is empty: the events above it stay
     * unacknowledged until the missing one arrives, which is what makes a lost
     * event visible to Bazel instead of silently absent from our session.
     */
    synchronized AckRange journaled(long sequence) {
        inFlight.remove(sequence);
        if (sequence <= highestContiguous) {
            // A duplicate that reached the journal anyway, or a replay. Nothing
            // new to acknowledge, and nothing to correct.
            return AckRange.empty();
        }
        journaledAhead.add(sequence);
        while (journaledAhead.remove(highestContiguous + 1)) {
            highestContiguous++;
        }
        if (highestContiguous <= highestAcknowledged) {
            return AckRange.empty();
        }
        AckRange range = new AckRange(highestAcknowledged + 1, highestContiguous);
        highestAcknowledged = highestContiguous;
        return range;
    }

    /**
     * Reopens a stream that a previous connection left ended.
     *
     * <p>Bazel's uploader survives the client process. If this application is
     * restarted while an upload is in flight, the uploader reconnects — to a
     * new port if it can find one — and <em>replays the stream from the
     * beginning</em>. The replay carries the same {@code StreamId}, so it is the
     * same stream, and reusing its tracker is what makes the replayed events
     * recognisable as duplicates instead of being journaled a second time.
     *
     * <p>The watermarks and counters are deliberately kept: they are what the
     * duplicate detection is made of.
     */
    synchronized void reopen() {
        completion = BesStreamState.Completion.OPEN;
        error = null;
    }

    /** Marks how the stream ended. The first ending wins; a later one is noise. */
    synchronized void end(BesStreamState.Completion how, String detail) {
        if (completion.isTerminal()) {
            return;
        }
        completion = Objects.requireNonNull(how, "how");
        error = detail;
    }

    synchronized boolean hasEnded() {
        return completion.isTerminal();
    }

    synchronized BesStreamState snapshot() {
        return new BesStreamState(
                key,
                highestReceived,
                highestContiguous,
                highestAcknowledged,
                eventsAccepted,
                duplicateCount,
                inFlight.size() + journaledAhead.size(),
                firstReceiveMicros < 0 ? OptionalLong.empty() : OptionalLong.of(firstReceiveMicros),
                lastReceiveMicros < 0 ? OptionalLong.empty() : OptionalLong.of(lastReceiveMicros),
                completion,
                Optional.ofNullable(error));
    }

    /**
     * A closed range of sequences to acknowledge, or an empty one.
     *
     * <p>{@code from > to} is the empty encoding rather than a null or an
     * {@link Optional}: the caller loops over it, and an empty loop is the
     * correct behavior with no branch needed.
     */
    record AckRange(long from, long to) {

        static AckRange empty() {
            return new AckRange(1, 0);
        }

        boolean isEmpty() {
            return from > to;
        }

        long count() {
            return isEmpty() ? 0 : to - from + 1;
        }
    }
}

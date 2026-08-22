package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a finished capture contains.
 *
 * <p>The counts are stated separately rather than summed because they answer
 * the question a session's honesty depends on: did everything that arrived get
 * kept? {@link #received} equal to {@link #journaled} means nothing was lost
 * between the wire and the disk. {@link #journaled} equal to
 * {@link #normalized} plus {@link #nonEventEnvelopes} means every frame was
 * accounted for — either as a row or as an envelope that legitimately has none.
 * {@link #isComplete()} is that arithmetic, done once, in the place that owns
 * the definition.
 *
 * @param received events accepted from the wire
 * @param journaled frames written
 * @param normalized {@code bep_events} rows written
 * @param nonEventEnvelopes envelopes journaled that carry no build event
 * @param decodeFailures rows written for payloads that would not decode
 * @param bytesJournaled total payload bytes
 * @param streams the final state of every BES stream seen
 * @param lagged whether backpressure was ever applied
 * @param failure the error that ended the capture, when one did
 */
public record CaptureSummary(
        long received,
        long journaled,
        long normalized,
        long nonEventEnvelopes,
        long decodeFailures,
        long bytesJournaled,
        List<BesStreamState> streams,
        boolean lagged,
        Optional<Throwable> failure) {

    public CaptureSummary {
        streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        failure = Objects.requireNonNull(failure, "failure");
    }

    /**
     * True when every accepted event reached the journal and every frame is
     * accounted for downstream, and no stream ended badly.
     *
     * <p>This is the machine-checkable form of the Phase 2 exit criterion "no
     * accepted event is silently dropped". A capture that returns false here
     * must not be finalized as {@code READY}.
     */
    public boolean isComplete() {
        return failure.isEmpty()
                && received == journaled
                && journaled == normalized + nonEventEnvelopes
                && streams.stream().allMatch(BesStreamState::isCleanlyComplete);
    }

    /** One line per discrepancy, for a warning the user can act on. */
    public List<String> discrepancies() {
        List<String> problems = new java.util.ArrayList<>();
        if (received != journaled) {
            problems.add((received - journaled) + " event(s) were accepted but never journaled");
        }
        if (journaled != normalized + nonEventEnvelopes) {
            problems.add((journaled - normalized - nonEventEnvelopes)
                    + " journaled frame(s) were never normalized");
        }
        for (BesStreamState stream : streams) {
            if (stream.isCleanlyComplete()) {
                continue;
            }
            problems.add("stream " + stream.key() + " ended " + stream.completion()
                    + " with " + stream.acknowledgementBacklog() + " event(s) unacknowledged"
                    + (stream.hasGap() ? " and a sequence gap" : ""));
        }
        failure.ifPresent(problem -> problems.add("the capture failed: " + problem));
        return List.copyOf(problems);
    }
}

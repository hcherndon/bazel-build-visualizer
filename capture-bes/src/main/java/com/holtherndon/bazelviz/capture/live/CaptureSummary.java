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
     * True when a stream arrived and everything in it was kept.
     *
     * <p>Two questions, and both have to be answered. The arithmetic —
     * everything received reached the journal, and every frame is accounted for
     * as a row or as an envelope that legitimately has none — is the
     * machine-checkable form of "no accepted event is silently dropped". But
     * every clause of it is <em>vacuously true at zero</em>: {@code 0 == 0},
     * {@code 0 == 0 + 0}, and {@code allMatch} over an empty list. A capture
     * that received nothing at all would have reported itself complete, and
     * did: a build that failed during option parsing, or one whose events went
     * to somebody else's backend, produced a session that claimed a COMPLETE
     * capture source containing no events, and exited 0.
     *
     * <p>So {@link #capturedAnything()} is asked first. A capture that got
     * nothing is not complete; it is empty, and the difference is the whole
     * value of the tool.
     */
    public boolean isComplete() {
        return capturedAnything()
                && failure.isEmpty()
                && received == journaled
                && journaled == normalized + nonEventEnvelopes
                && streams.stream().allMatch(BesStreamState::isCleanlyComplete);
    }

    /**
     * Whether any BES stream was ever opened.
     *
     * <p>Separate from {@link #isComplete()} because the two failures need
     * different words: "we lost some of what arrived" and "nothing arrived"
     * are not the same problem and do not have the same cause.
     */
    public boolean capturedAnything() {
        return !streams.isEmpty() || received > 0;
    }

    /** One line per discrepancy, for a warning the user can act on. */
    public List<String> discrepancies() {
        List<String> problems = new java.util.ArrayList<>();
        if (!capturedAnything()) {
            problems.add("no build events arrived at all: the build published none, or it"
                    + " published them somewhere else");
        }
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

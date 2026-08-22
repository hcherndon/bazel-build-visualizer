package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of decoding one raw BEP payload.
 *
 * <p>Failure is a value here, not an exception: the import pipeline records the
 * detail in {@code import_diagnostics} and keeps going, because a single
 * unparseable event must not abort a session (plan 21.3). The unavailable half
 * of the result is an empty {@link Optional} rather than a null or a
 * placeholder, per the project rule that unknown data stays visibly unknown.
 *
 * @param status decode outcome; {@link DecodeStatus#name()} is what
 *     {@code bep_events.decode_status} stores
 * @param event the parsed message, present unless {@code status} is
 *     {@link DecodeStatus#FAILED}
 * @param failureDetail a human-readable explanation, present only when
 *     {@code status} is {@link DecodeStatus#FAILED}
 */
public record DecodeResult(
        DecodeStatus status, Optional<BuildEvent> event, Optional<String> failureDetail) {

    public DecodeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(failureDetail, "failureDetail");
        if (status == DecodeStatus.FAILED) {
            if (event.isPresent()) {
                throw new IllegalArgumentException("a FAILED decode cannot carry an event");
            }
            if (failureDetail.isEmpty()) {
                throw new IllegalArgumentException("a FAILED decode must carry a failure detail");
            }
        } else {
            if (event.isEmpty()) {
                throw new IllegalArgumentException(status + " decode must carry an event");
            }
            if (failureDetail.isPresent()) {
                throw new IllegalArgumentException(status + " decode cannot carry a failure detail");
            }
        }
    }

    /** A payload that parsed with every field recognised. */
    public static DecodeResult ok(BuildEvent event) {
        return new DecodeResult(
                DecodeStatus.OK, Optional.of(event), Optional.empty());
    }

    /** A payload that parsed but carried fields this build does not know. */
    public static DecodeResult withUnknownFields(BuildEvent event) {
        return new DecodeResult(
                DecodeStatus.UNKNOWN_FIELDS, Optional.of(event), Optional.empty());
    }

    /** A payload that did not parse. {@code detail} must explain why. */
    public static DecodeResult failed(String detail) {
        return new DecodeResult(
                DecodeStatus.FAILED, Optional.empty(), Optional.of(Objects.requireNonNull(detail)));
    }

    /** True when a message is available, whether or not it had unknown fields. */
    public boolean isParsed() {
        return status.isParsed();
    }

    /** True when the payload did not parse at all. */
    public boolean isFailed() {
        return status == DecodeStatus.FAILED;
    }

    /**
     * True when the message carried at least one field this build does not
     * recognise. This is the value for {@code bep_events.has_unknown_fields}.
     */
    public boolean hasUnknownFields() {
        return status == DecodeStatus.UNKNOWN_FIELDS;
    }

    /**
     * The parsed message.
     *
     * @throws IllegalStateException if this result is {@link DecodeStatus#FAILED};
     *     callers that may see failures must branch on {@link #isFailed()} first
     */
    public BuildEvent requireEvent() {
        return event.orElseThrow(
                () -> new IllegalStateException("decode failed: " + failureDetail.orElse("")));
    }
}

package com.holtherndon.bazelviz.core.event;

/**
 * Outcome of turning one raw BEP record — binary or JSON — into a
 * {@code BuildEvent}.
 *
 * <p>The status is kept rather than collapsed into a boolean because the
 * outcomes call for different handling. {@link #UNKNOWN_FIELDS} still yields a
 * usable event and merely means this build's protos are older than the Bazel
 * that wrote the record, while {@link #FAILED} yields nothing decodable at
 * all. In every case the raw bytes are preserved verbatim (ADR-004, plan
 * 21.5), so a later version of this application can reindex the same session
 * and recover what this one could not interpret.
 *
 * <p>This enum lives in {@code core-model} because both decoding paths and the
 * storage layer must agree on it: {@code bep-codec} decodes binary payloads,
 * {@code capture-file} decodes JSON records, and both write the same
 * {@code bep_events.decode_status} column. Two parallel enums existed briefly
 * and were merged here — a split would have meant mapping by name at every
 * boundary, which is exactly the kind of silent drift that loses a status.
 *
 * <p>{@link #NOT_ATTEMPTED} has no persisted counterpart. It is a parser-level
 * state for callers that asked for raw records without decoding, and it must
 * be resolved before a row is written rather than stored as if it were an
 * outcome.
 */
public enum DecodeStatus {
    /** The record parsed cleanly and every field was recognized. */
    OK,

    /**
     * The record parsed, but only after unknown fields were ignored. The event
     * is usable and incomplete, and the unrecognized content is still present
     * in the preserved raw bytes.
     */
    UNKNOWN_FIELDS,

    /** The record could not be parsed as a {@code BuildEvent} at all. */
    FAILED,

    /** Decoding was not requested. Says so rather than pretending success. */
    NOT_ATTEMPTED;

    /** True when a decoded event is available. */
    public boolean hasEvent() {
        return this == OK || this == UNKNOWN_FIELDS;
    }

    /** True when parsing produced something, i.e. it neither failed nor was skipped. */
    public boolean isParsed() {
        return hasEvent();
    }

    /** True when this status may be written to {@code bep_events.decode_status}. */
    public boolean isPersistable() {
        return this != NOT_ATTEMPTED;
    }

    /**
     * Reads a status back from the {@code bep_events.decode_status} column.
     *
     * <p>An unrecognized value is rejected rather than mapped onto a plausible
     * neighbour: it means the database was written by a build that knew a
     * status this one does not, and guessing which would misreport how much of
     * the session was understood.
     */
    public static DecodeStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unrecognized decode status '" + value
                            + "'; the session may have been written by a newer version",
                    unknown);
        }
    }
}

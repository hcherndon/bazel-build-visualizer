package com.holtherndon.bazelviz.storage.events;

/**
 * How completely this build understood a raw event's bytes
 * ({@code bep_events.decode_status}).
 *
 * <p>This is provenance, not a health score. {@link #UNKNOWN_FIELDS} means the
 * event decoded fine but carried fields this build's protobuf schema does not
 * name — a normal outcome when a newer Bazel writes the stream (plan 21.5).
 * The raw bytes are journaled verbatim either way, so a later build can
 * re-derive what this one could not (ADR-004).
 */
public enum DecodeStatus {
    /** Decoded completely with no unrecognized fields. */
    OK,

    /** Decoded, but the message carried fields unknown to this build's schema. */
    UNKNOWN_FIELDS,

    /** The payload could not be parsed. The raw bytes are still recorded. */
    FAILED;

    public static DecodeStatus parse(String value) {
        return valueOf(value);
    }
}

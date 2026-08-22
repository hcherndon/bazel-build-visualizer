package com.holtherndon.bazelviz.capture.file.json;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One thing the JSON parser noticed that the user must be told about.
 *
 * <p>Every limit the parser enforces and every byte it declines to interpret
 * produces one of these (plan 21.3, project rule "no silent truncation"). The
 * shape mirrors the {@code import_diagnostics} row it will become — severity,
 * code, message, byte offset — so the import pipeline can persist it without
 * inventing fields. {@code recordLength} is carried in addition because an
 * oversized record's true size is exactly what the user needs in order to
 * choose a larger limit, and it is not recoverable afterwards.
 *
 * @param severity how loudly to report this
 * @param code stable machine-readable classification
 * @param message human-readable detail, already containing the numbers
 * @param byteOffset absolute offset in the source file the diagnostic refers to
 * @param recordLength length in bytes of the record concerned, when one applies
 */
public record JsonParseDiagnostic(
        Severity severity,
        Code code,
        String message,
        long byteOffset,
        OptionalLong recordLength) {

    public JsonParseDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recordLength, "recordLength");
    }

    /** Matches the severities the {@code import_diagnostics} table stores. */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** Stable diagnostic codes emitted by the JSON parser. */
    public enum Code {
        /** The source contained no bytes at all. */
        EMPTY_INPUT,

        /** A UTF-8 byte order mark preceded the first record and was skipped. */
        BYTE_ORDER_MARK_SKIPPED,

        /**
         * Objects were separated by commas rather than concatenated. Tolerated,
         * reported once, because it means the producer was writing an unwrapped
         * JSON array and a future record may be an array terminator.
         */
        COMMA_SEPARATED_RECORDS,

        /**
         * A record exceeded the configured maximum size. Its offset and true
         * length are reported and its bytes are not retained; parsing continues
         * from the next record boundary.
         */
        RECORD_TOO_LARGE,

        /** The final object is incomplete: a clean short tail, not corruption. */
        TRUNCATED_TAIL,

        /** A byte appeared between records where only an object may start. */
        MALFORMED_TOP_LEVEL,

        /** A structurally complete record could not be decoded to a BuildEvent. */
        DECODE_FAILED,

        /** A record decoded only after ignoring fields these protos do not know. */
        UNKNOWN_FIELDS
    }

    static JsonParseDiagnostic at(Severity severity, Code code, long byteOffset, String message) {
        return new JsonParseDiagnostic(severity, code, message, byteOffset, OptionalLong.empty());
    }

    static JsonParseDiagnostic forRecord(
            Severity severity, Code code, long byteOffset, long recordLength, String message) {
        return new JsonParseDiagnostic(
                severity, code, message, byteOffset, OptionalLong.of(recordLength));
    }
}

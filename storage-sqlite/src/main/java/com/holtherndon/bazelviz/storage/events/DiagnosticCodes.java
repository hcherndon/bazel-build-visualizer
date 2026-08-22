package com.holtherndon.bazelviz.storage.events;

/**
 * The {@code import_diagnostics.code} values the storage layer writes or reads.
 *
 * <p>Codes are a small stable vocabulary rather than free text so the UI can
 * group and explain them, and so the truncation/corruption distinction the plan
 * insists on (21.3) survives into the database instead of being buried in a
 * message string. Producers outside this module may write their own codes; the
 * column is plain TEXT and this class is a convention, not a constraint.
 */
public final class DiagnosticCodes {

    private DiagnosticCodes() {}

    /** The source ended mid-record. Everything before the cut is intact. */
    public static final String TRUNCATED_TAIL = "TRUNCATED_TAIL";

    /** A fully present frame failed its CRC — the bytes on disk are wrong. */
    public static final String CRC_MISMATCH = "CRC_MISMATCH";

    /** A frame's declared length exceeded the configured maximum. */
    public static final String FRAME_TOO_LARGE = "FRAME_TOO_LARGE";

    /** A payload could not be parsed as the protobuf/JSON it claimed to be. */
    public static final String DECODE_FAILED = "DECODE_FAILED";

    /** A payload decoded but carried fields unknown to this build. */
    public static final String UNKNOWN_FIELDS = "UNKNOWN_FIELDS";

    /** The same (stream, sequence) arrived more than once; the repeat was ignored. */
    public static final String DUPLICATE_SEQUENCE = "DUPLICATE_SEQUENCE";

    /** A sequence number was skipped — events are missing from the stream. */
    public static final String SEQUENCE_GAP = "SEQUENCE_GAP";

    /** A parent announced a child event that never arrived (plan 17.11). */
    public static final String ANNOUNCED_CHILD_MISSING = "ANNOUNCED_CHILD_MISSING";

    /** The session was resumed from a journal checkpoint after an unclean stop. */
    public static final String RECOVERED_SESSION = "RECOVERED_SESSION";

    /** A write failed because the volume is full (plan 21.2). */
    public static final String DISK_FULL = "DISK_FULL";

    /**
     * Two action events named the same primary output.
     *
     * <p>Measured unique across every stream on all four supported Bazel
     * versions, so this should never appear on a clean import. When it does,
     * the identity assumption has broken and one of the two actions is not in
     * the table — which the user has to be told, because the alternative is a
     * build that quietly reports fewer actions than it ran.
     */
    public static final String DUPLICATE_ACTION_OUTPUT = "DUPLICATE_ACTION_OUTPUT";

    /**
     * A named set of files was referenced and never defined.
     *
     * <p>Zero occurrences in 1,829 measured references. A non-zero count means
     * the capture is missing events, so every byte total that walks through
     * those sets is a lower bound rather than a total.
     */
    public static final String UNDEFINED_FILE_SET = "UNDEFINED_FILE_SET";
}

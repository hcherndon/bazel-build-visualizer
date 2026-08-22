package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.source.Completeness;

/**
 * Why a scan of a journal segment stopped. Every value except {@link #OK} names
 * a specific defect at a specific byte offset, because "the journal is broken"
 * is not an answer a user can act on (plan 21.3).
 *
 * <p>The split between {@link #TRUNCATED_TAIL} and the checksum/framing
 * failures is load-bearing: a short tail is the normal result of a crash or a
 * kill mid-append and costs at most one event, while a fully present frame that
 * fails its CRC means the bytes on disk are wrong and the storage underneath
 * should be suspected.
 */
public enum JournalScanStatus {

    /** The segment ended exactly on a frame boundary. Nothing is missing. */
    OK,

    /**
     * The segment ends part-way through a frame — mid-header, mid-payload or
     * mid-CRC. Everything before the cut is intact.
     */
    TRUNCATED_TAIL,

    /**
     * Enough bytes are present for a frame header but it does not begin with
     * {@code BFRM}, so the reader is not on a frame boundary.
     */
    BAD_MAGIC,

    /**
     * The frame header declares a payload length that is negative or larger
     * than the configured maximum. Honouring it would mean allocating whatever
     * a damaged length field happens to say.
     */
    LENGTH_OUT_OF_BOUNDS,

    /** A fully present frame failed its CRC-32C. The bytes on disk are wrong. */
    CRC_MISMATCH,

    /**
     * A fully present, correctly checksummed frame names a source kind this
     * build does not know. The bytes are valid and must never be truncated;
     * this build simply cannot interpret them (plan 21.5).
     */
    UNSUPPORTED_SOURCE_KIND;

    /** True when the scan reached a clean end with no defect. */
    public boolean isClean() {
        return this == OK;
    }

    /**
     * True when the trailing bytes from the stop offset onward are known to be
     * garbage and may be truncated. False for {@link #OK} (there is nothing to
     * truncate) and for {@link #UNSUPPORTED_SOURCE_KIND} (the bytes are valid
     * and a later build will read them).
     */
    public boolean isTruncatable() {
        return switch (this) {
            case TRUNCATED_TAIL, BAD_MAGIC, LENGTH_OUT_OF_BOUNDS, CRC_MISMATCH -> true;
            case OK, UNSUPPORTED_SOURCE_KIND -> false;
        };
    }

    /** How this status describes the completeness of the journal as a capture source. */
    public Completeness completeness() {
        return switch (this) {
            case OK -> Completeness.COMPLETE;
            case TRUNCATED_TAIL -> Completeness.TRUNCATED;
            case BAD_MAGIC, LENGTH_OUT_OF_BOUNDS, CRC_MISMATCH -> Completeness.CORRUPT_PARTIAL;
            case UNSUPPORTED_SOURCE_KIND -> Completeness.UNKNOWN;
        };
    }

    /**
     * The {@code import_diagnostics.code} value for this status. Codes are part
     * of the stored record, so they are spelled out here rather than derived
     * from {@link #name()} by accident.
     */
    public String diagnosticCode() {
        return switch (this) {
            case OK -> "JOURNAL_OK";
            case TRUNCATED_TAIL -> "TRUNCATED_TAIL";
            case BAD_MAGIC -> "BAD_MAGIC";
            case LENGTH_OUT_OF_BOUNDS -> "LENGTH_OUT_OF_BOUNDS";
            case CRC_MISMATCH -> "CRC_MISMATCH";
            case UNSUPPORTED_SOURCE_KIND -> "UNSUPPORTED_SOURCE_KIND";
        };
    }
}

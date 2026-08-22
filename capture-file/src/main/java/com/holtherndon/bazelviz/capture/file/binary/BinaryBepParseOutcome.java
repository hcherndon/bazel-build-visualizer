package com.holtherndon.bazelviz.capture.file.binary;

import com.holtherndon.bazelviz.core.source.Completeness;

/**
 * Why a binary BEP parse stopped.
 *
 * <p>Keeping {@link #TRUNCATED} and {@link #CORRUPT} apart is required by plan
 * 21.3 and is not a nicety: a short tail is the normal shape of a build that is
 * still running or was cancelled, while corruption means the bytes on disk
 * disagree with themselves and no reliable next frame boundary exists. The
 * first invites "resume later", the second must never silently resume, because
 * finding the next boundary would mean scanning binary data for guessed
 * protobuf boundaries — explicitly forbidden by the same section.
 */
public enum BinaryBepParseOutcome {

    /**
     * The input ended exactly on a frame boundary. Every byte between the start
     * offset and the resume offset belonged to a delivered frame.
     */
    COMPLETE,

    /**
     * The input ended inside a frame — an incomplete varint length prefix, or a
     * payload shorter than its declared length. Every preceding frame was
     * delivered; the partial frame was not. The result's resume offset is the
     * first byte of that partial frame, so appending more bytes and re-parsing
     * from there loses nothing.
     */
    TRUNCATED,

    /**
     * A frame header is self-contradictory: a length prefix that never
     * terminates, or a declared length past the configured maximum. Parsing
     * stops at the frame's first byte and does not attempt to resynchronize.
     */
    CORRUPT,

    /** The caller's cancellation signal fired between frames. */
    CANCELLED;

    /**
     * How this outcome describes the source as a whole. {@link #CANCELLED} maps
     * to {@link Completeness#UNKNOWN} rather than to anything more specific:
     * stopping early says nothing about the bytes not yet read, and reporting
     * unread data as complete or truncated would be an invention (plan 11.4).
     */
    public Completeness toCompleteness() {
        return switch (this) {
            case COMPLETE -> Completeness.COMPLETE;
            case TRUNCATED -> Completeness.TRUNCATED;
            case CORRUPT -> Completeness.CORRUPT_PARTIAL;
            case CANCELLED -> Completeness.UNKNOWN;
        };
    }

    /** True when the parse stopped on a frame boundary with nothing left over. */
    public boolean atFrameBoundary() {
        return this == COMPLETE || this == CANCELLED;
    }

    /** True when resuming from the result's resume offset can still make progress. */
    public boolean isResumable() {
        return this != CORRUPT;
    }
}

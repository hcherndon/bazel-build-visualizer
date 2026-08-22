package com.holtherndon.bazelviz.capture.file.binary;

import com.holtherndon.bazelviz.core.source.Completeness;
import java.util.OptionalLong;

/**
 * What one call to {@link BinaryBepParser#parse} did and where it stopped.
 *
 * <p>Everything an importer needs to write an {@code import_diagnostics} row
 * and a resumable checkpoint is here, including the exact byte offset of a
 * truncation or corruption. Nothing is rounded, sampled or summarized away.
 *
 * @param outcome why the parse stopped
 * @param eventCount frames delivered to the sink <em>by this call</em>, not
 *     cumulatively across resumes
 * @param startOffset the absolute offset this call began reading at
 * @param resumeOffset absolute offset of the first byte that was not consumed
 *     as part of a delivered frame. For {@link BinaryBepParseOutcome#COMPLETE}
 *     this is end-of-input; for {@code TRUNCATED} and {@code CORRUPT} it is the
 *     first byte of the offending frame, which is exactly the offset to record
 *     in a diagnostic and to resume from once more bytes arrive
 * @param incompleteTailBytes bytes present at {@code resumeOffset} that did not
 *     add up to a whole frame; 0 when the parse stopped on a frame boundary,
 *     which is a measured zero, not an unknown
 * @param rejectedDeclaredLength the payload length a corrupt frame declared,
 *     present only for a length-limit rejection. Empty — never zero — when the
 *     parse did not fail that way (plan 11.4)
 * @param peakBufferBytes the largest the parser's read buffer ever grew during
 *     this call. Bounded by the largest single frame plus its length prefix,
 *     never by the size of the input; asserted by the streaming tests
 * @param detail human-readable explanation, suitable for a diagnostic message
 */
public record BinaryBepParseResult(
        BinaryBepParseOutcome outcome,
        long eventCount,
        long startOffset,
        long resumeOffset,
        long incompleteTailBytes,
        OptionalLong rejectedDeclaredLength,
        int peakBufferBytes,
        String detail) {

    public BinaryBepParseResult {
        if (resumeOffset < startOffset) {
            throw new IllegalArgumentException(
                    "resumeOffset " + resumeOffset + " precedes startOffset " + startOffset);
        }
        if (incompleteTailBytes < 0) {
            throw new IllegalArgumentException(
                    "incompleteTailBytes must be >= 0, got " + incompleteTailBytes);
        }
    }

    /** Bytes consumed as complete frames by this call. */
    public long bytesConsumed() {
        return resumeOffset - startOffset;
    }

    /** Absolute offset of the frame that could not be read whole, when there is one. */
    public OptionalLong incompleteFrameOffset() {
        return outcome.atFrameBoundary() ? OptionalLong.empty() : OptionalLong.of(resumeOffset);
    }

    /** How this call describes the completeness of the source it read. */
    public Completeness completeness() {
        return outcome.toCompleteness();
    }
}

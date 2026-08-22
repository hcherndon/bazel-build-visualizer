package com.holtherndon.bazelviz.capture.file.json;

import com.holtherndon.bazelviz.core.source.Completeness;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Summary of one pass over a JSON BEP source.
 *
 * <p>Counts are separated rather than summed because they answer different
 * questions: {@code deliveredRecordCount} is how much data the session has,
 * {@code oversizedRecordCount} is how much the configured limit cost, and
 * {@code failedDecodeCount} is how much is present as raw bytes but not yet
 * interpretable. Collapsing them would hide exactly the losses plan 21.3
 * requires be visible.
 *
 * <p>{@code truncatedTailOffset} is present only when {@code completeness} is
 * {@link Completeness#TRUNCATED}, and is the offset of the opening brace of the
 * incomplete final object — the point a tailing reader resumes from.
 *
 * <p>{@code peakRecordBufferBytes} and {@code readBufferBytes} together are the
 * parser's peak heap footprint. They exist so a test can assert the streaming
 * requirement directly rather than inferring it (contract §7).
 *
 * @param deliveredRecordCount records handed to the listener with their bytes
 * @param oversizedRecordCount records whose size exceeded the configured limit
 * @param failedDecodeCount delivered records that could not be decoded at all
 * @param unknownFieldRecordCount delivered records that decoded only leniently
 * @param bytesRead total bytes consumed from the source
 * @param completeness what the source turned out to be
 * @param truncatedTailOffset offset of the incomplete final object, when truncated
 * @param truncatedTailBytes bytes present in that incomplete final object
 * @param peakRecordBufferBytes largest record-accumulation buffer ever allocated
 * @param readBufferBytes size of the fixed streaming read buffer used
 */
public record JsonBepParseResult(
        long deliveredRecordCount,
        long oversizedRecordCount,
        long failedDecodeCount,
        long unknownFieldRecordCount,
        long bytesRead,
        Completeness completeness,
        OptionalLong truncatedTailOffset,
        OptionalLong truncatedTailBytes,
        int peakRecordBufferBytes,
        int readBufferBytes) {

    public JsonBepParseResult {
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(truncatedTailOffset, "truncatedTailOffset");
        Objects.requireNonNull(truncatedTailBytes, "truncatedTailBytes");
    }

    /** Total records the scanner delimited, whether or not their bytes were kept. */
    public long delimitedRecordCount() {
        return deliveredRecordCount + oversizedRecordCount;
    }
}

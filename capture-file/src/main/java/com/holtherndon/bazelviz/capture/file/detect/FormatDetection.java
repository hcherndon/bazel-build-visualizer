package com.holtherndon.bazelviz.capture.file.detect;

/**
 * The verdict of {@link FormatDetector}, with the evidence behind it.
 *
 * <p>The reason is not decoration. When detection returns
 * {@link DetectedFormat#UNKNOWN} the user is looking at a file they believe is
 * a BEP file, and "unsupported file" tells them nothing; "first frame declares
 * 4 bytes that do not parse as a BuildEvent" tells them their file is
 * truncated at the head or is not BEP at all. The same string is what the
 * import surfaces as a diagnostic.
 *
 * @param format what the content says the input is
 * @param reason why, always populated, phrased for a user
 * @param bytesInspected how many bytes of the input were read to decide. Bounded
 *     by {@link FormatDetector#probeBytes()}, so a caller reading from a
 *     non-rewindable stream knows exactly how much it must buffer
 */
public record FormatDetection(DetectedFormat format, String reason, int bytesInspected) {

    public FormatDetection {
        if (format == null) {
            throw new IllegalArgumentException("format is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a detection must always state its reason");
        }
        if (bytesInspected < 0) {
            throw new IllegalArgumentException("bytesInspected must be >= 0, got " + bytesInspected);
        }
    }

    static FormatDetection of(DetectedFormat format, String reason, int bytesInspected) {
        return new FormatDetection(format, reason, bytesInspected);
    }

    static FormatDetection unknown(String reason, int bytesInspected) {
        return new FormatDetection(DetectedFormat.UNKNOWN, reason, bytesInspected);
    }

    /** True when a parser can be selected from this verdict. */
    public boolean isKnown() {
        return format.isKnown();
    }
}

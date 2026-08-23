package com.holtherndon.bazelviz.format.portable;

/**
 * The bounds a {@code .bviz} archive must stay inside to be opened.
 *
 * <h2>Why an archive needs limits at all</h2>
 *
 * <p>Plan 22.4: "limit expanded archive size, limit entry count". A Zip file
 * declares how large each entry expands to and can lie about it, and a
 * hand-built archive of a few kilobytes can expand to terabytes — the zip bomb
 * is not a theoretical attack, it is the reason every archive reader has these
 * numbers. So the reader counts bytes as it decompresses and stops at the
 * limit, rather than trusting the declared size.
 *
 * <p>The defaults are generous by the standards of the attack and tight by the
 * standards of a real session: a Tier 3 capture is single-digit gigabytes, and
 * a session with more than fifty thousand files is not a session.
 *
 * @param maxExpandedBytes total decompressed bytes across every entry
 * @param maxEntries how many entries the archive may contain
 * @param maxEntryBytes decompressed bytes in any single entry
 * @param maxCompressionRatio the largest expansion any one entry may show; a
 *     legitimate journal or database compresses perhaps 10:1, and 1000:1 is a
 *     file made of zeroes
 */
public record BvizLimits(
        long maxExpandedBytes, int maxEntries, long maxEntryBytes, int maxCompressionRatio) {

    public BvizLimits {
        if (maxExpandedBytes <= 0 || maxEntries <= 0 || maxEntryBytes <= 0
                || maxCompressionRatio <= 0) {
            throw new IllegalArgumentException("every limit must be positive");
        }
    }

    /** What the application opens an archive with. */
    public static BvizLimits defaults() {
        return new BvizLimits(
                /* maxExpandedBytes= */ 64L * 1024 * 1024 * 1024,
                /* maxEntries= */ 50_000,
                /* maxEntryBytes= */ 32L * 1024 * 1024 * 1024,
                /* maxCompressionRatio= */ 1_000);
    }
}

package com.holtherndon.bazelviz.ui.timeline;

/**
 * Level-of-detail pyramid of per-bin aggregates over action spans
 * (plan sections 14.1-14.3, Phase 0 spike scope).
 *
 * <p>Level 0 uses ~1 ms bins; each coarser level multiplies the bin width by
 * {@value #LEVEL_GROWTH} until one level covers the wall span with at most
 * {@value #MAX_TOP_LEVEL_BINS} bins. Per bin the index stores, in primitive
 * arrays only: the number of spans starting in the bin, the summed overlap
 * duration of all spans intersecting the bin, the count of intersecting spans
 * (active-count estimate), and the count of intersecting failed spans.
 * Top-category tracking is out of scope for the spike.
 *
 * <p>The build streams the {@link SpanSource} exactly once. Because synthetic
 * durations are heavy-tailed (individual spans can cover 100k+ finest-level
 * bins), the build never walks a span's bins directly: range quantities use
 * difference arrays (+1 at first bin, -1 after last) resolved by one prefix-sum
 * pass per level, so cost is O(spans x levels + totalBins), not
 * O(spans x binsPerSpan).
 *
 * <p>Instances are immutable after {@link #build} returns and safe to read from
 * any thread, which is what lets {@code TimelineCanvas} paint on the EDT
 * without locking.
 */
public final class TimelineLodIndex {

    /** Preferred level-0 bin width; grown by {@link #LEVEL_GROWTH} for absurdly long walls. */
    static final long FINEST_BIN_MICROS = 1_000;
    static final int LEVEL_GROWTH = 4;
    static final int MAX_TOP_LEVEL_BINS = 2048;

    /**
     * Peak build cost of one level-0 bin: {@code starts} (int) + {@code overlap}
     * (long) + the three difference arrays (int each) = 24 bytes, plus the
     * resolved {@code active} and {@code failure} arrays (int each) that
     * coexist with them = 32 bytes.
     */
    private static final int BYTES_PER_FINEST_BIN = 32;

    /**
     * Every coarser level has a quarter of its predecessor's bins, so the whole
     * pyramid costs the level-0 arrays times the geometric sum 1 + 1/4 + 1/16 +
     * … = 4/3. Rounded up to keep the budget conservative.
     */
    private static final double PYRAMID_BIN_MULTIPLIER = 4.0 / 3.0;

    /** Memory the pyramid may occupy at its peak, against the plan-20.2 4 GB heap objective. */
    static final long MAX_PYRAMID_BYTES = 256L * 1024 * 1024;

    /**
     * Hard cap on level-0 bins, derived from {@link #MAX_PYRAMID_BYTES} rather
     * than picked as a round number: bins are only a proxy for what actually
     * matters, which is bytes. Beyond this the level-0 bin width grows by
     * {@link #LEVEL_GROWTH} instead, trading time resolution for a bounded
     * footprint. Tier 3 (a ~2,344 s wall) needs ~2.3M bins at 1 ms, so the
     * benchmark tiers keep full 1 ms resolution.
     */
    static final int MAX_FINEST_BINS =
            (int) (MAX_PYRAMID_BYTES / (long) (BYTES_PER_FINEST_BIN * PYRAMID_BIN_MULTIPLIER));
    /** A level is selected when its bins are at least this wide on screen. */
    static final double TARGET_BIN_PIXELS = 2.0;

    private final long wallStartMicros;
    private final long wallEndMicros;
    private final long totalSpanCount;
    private final long[] binWidthMicros;
    private final int[] binCounts;
    private final int[][] startCounts;
    private final long[][] overlapMicros;
    private final int[][] activeCounts;
    private final int[][] failureCounts;
    private final long[] maxOverlapMicros;
    private final int[] maxActiveCounts;

    private TimelineLodIndex(long wallStartMicros, long wallEndMicros, long totalSpanCount,
            long[] binWidthMicros, int[] binCounts, int[][] startCounts, long[][] overlapMicros,
            int[][] activeCounts, int[][] failureCounts, long[] maxOverlapMicros,
            int[] maxActiveCounts) {
        this.wallStartMicros = wallStartMicros;
        this.wallEndMicros = wallEndMicros;
        this.totalSpanCount = totalSpanCount;
        this.binWidthMicros = binWidthMicros;
        this.binCounts = binCounts;
        this.startCounts = startCounts;
        this.overlapMicros = overlapMicros;
        this.activeCounts = activeCounts;
        this.failureCounts = failureCounts;
        this.maxOverlapMicros = maxOverlapMicros;
        this.maxActiveCounts = maxActiveCounts;
    }

    /**
     * Streams {@code source} once and builds the pyramid over
     * {@code [wallStartMicros, wallEndMicros)}. Spans reaching outside the wall
     * are clipped to it; spans entirely outside are ignored. A clipped span's
     * "start" is attributed to the bin containing its clipped start.
     */
    public static TimelineLodIndex build(SpanSource source, long wallStartMicros, long wallEndMicros) {
        if (wallEndMicros <= wallStartMicros) {
            throw new IllegalArgumentException(
                    "wall span must be positive: [" + wallStartMicros + ", " + wallEndMicros + ")");
        }
        long span = wallEndMicros - wallStartMicros;

        long finest = FINEST_BIN_MICROS;
        while (ceilDiv(span, finest) > MAX_FINEST_BINS) {
            finest *= LEVEL_GROWTH;
        }
        int countedLevels = 1;
        for (long w = finest; ceilDiv(span, w) > MAX_TOP_LEVEL_BINS; w *= LEVEL_GROWTH) {
            countedLevels++;
        }
        final int levelCount = countedLevels;

        long[] widths = new long[levelCount];
        int[] counts = new int[levelCount];
        int[][] starts = new int[levelCount][];
        long[][] overlap = new long[levelCount][];
        // Difference arrays (size n+1): range add via [b0]++ / [b1 + 1]--, resolved below.
        int[][] activeDiff = new int[levelCount][];
        int[][] failDiff = new int[levelCount][];
        int[][] coverDiff = new int[levelCount][];
        long w = finest;
        for (int l = 0; l < levelCount; l++) {
            widths[l] = w;
            counts[l] = (int) ceilDiv(span, w);
            starts[l] = new int[counts[l]];
            overlap[l] = new long[counts[l]];
            activeDiff[l] = new int[counts[l] + 1];
            failDiff[l] = new int[counts[l] + 1];
            coverDiff[l] = new int[counts[l] + 1];
            w *= LEVEL_GROWTH;
        }

        long[] fed = new long[1];
        source.forEachSpan((s, e, categoryIndex, failed) -> {
            fed[0]++;
            long cs = Math.max(s, wallStartMicros);
            long ce = Math.min(e, wallEndMicros);
            if (cs >= wallEndMicros || ce < cs) {
                return; // entirely outside the wall
            }
            for (int l = 0; l < levelCount; l++) {
                long bw = widths[l];
                int b0 = (int) ((cs - wallStartMicros) / bw);
                int b1 = (int) (((ce > cs ? ce - 1 : cs) - wallStartMicros) / bw);
                starts[l][b0]++;
                activeDiff[l][b0]++;
                activeDiff[l][b1 + 1]--;
                if (failed) {
                    failDiff[l][b0]++;
                    failDiff[l][b1 + 1]--;
                }
                if (b0 == b1) {
                    overlap[l][b0] += ce - cs;
                } else {
                    long firstBinEnd = wallStartMicros + (b0 + 1L) * bw;
                    overlap[l][b0] += firstBinEnd - cs;
                    overlap[l][b1] += ce - (wallStartMicros + (long) b1 * bw);
                    if (b1 > b0 + 1) {
                        // interior bins are fully covered: bw each, added in the prefix pass
                        coverDiff[l][b0 + 1]++;
                        coverDiff[l][b1]--;
                    }
                }
            }
        });

        int[][] active = new int[levelCount][];
        int[][] failure = new int[levelCount][];
        long[] maxOverlap = new long[levelCount];
        int[] maxActive = new int[levelCount];
        for (int l = 0; l < levelCount; l++) {
            int n = counts[l];
            active[l] = new int[n];
            failure[l] = new int[n];
            int runActive = 0;
            int runFail = 0;
            int runCover = 0;
            long bw = widths[l];
            for (int b = 0; b < n; b++) {
                runActive += activeDiff[l][b];
                runFail += failDiff[l][b];
                runCover += coverDiff[l][b];
                active[l][b] = runActive;
                failure[l][b] = runFail;
                overlap[l][b] += (long) runCover * bw;
                if (overlap[l][b] > maxOverlap[l]) {
                    maxOverlap[l] = overlap[l][b];
                }
                if (runActive > maxActive[l]) {
                    maxActive[l] = runActive;
                }
            }
        }

        return new TimelineLodIndex(wallStartMicros, wallEndMicros, fed[0], widths, counts,
                starts, overlap, active, failure, maxOverlap, maxActive);
    }

    public long wallStartMicros() {
        return wallStartMicros;
    }

    public long wallEndMicros() {
        return wallEndMicros;
    }

    /** Spans fed by the source, including any that fell entirely outside the wall. */
    public long totalSpanCount() {
        return totalSpanCount;
    }

    public int levelCount() {
        return binWidthMicros.length;
    }

    public long binWidthMicros(int level) {
        return binWidthMicros[level];
    }

    public int binCount(int level) {
        return binCounts[level];
    }

    /**
     * Finest level whose bins are at least {@value #TARGET_BIN_PIXELS} px wide at
     * this scale; the coarsest level when even it would be narrower (deep zoom-out).
     */
    public int levelForScale(double pixelsPerMicro) {
        for (int l = 0; l < binWidthMicros.length; l++) {
            if (binWidthMicros[l] * pixelsPerMicro >= TARGET_BIN_PIXELS) {
                return l;
            }
        }
        return binWidthMicros.length - 1;
    }

    /** Bin containing {@code timeMicros}, clamped to the level's valid range. */
    public int binIndexOf(int level, long timeMicros) {
        long idx = Math.floorDiv(timeMicros - wallStartMicros, binWidthMicros[level]);
        return Math.clamp(idx, 0, binCounts[level] - 1);
    }

    public long binStartMicros(int level, int bin) {
        return wallStartMicros + (long) bin * binWidthMicros[level];
    }

    /** Spans whose (clipped) start lies in the bin. */
    public int startCount(int level, int bin) {
        return startCounts[level][bin];
    }

    /** Summed duration of span-bin intersections; at most binWidth x activeCount. */
    public long overlapMicros(int level, int bin) {
        return overlapMicros[level][bin];
    }

    /** Count of spans intersecting the bin (active-count estimate). */
    public int activeCount(int level, int bin) {
        return activeCounts[level][bin];
    }

    /** Count of failed spans intersecting the bin. */
    public int failureCount(int level, int bin) {
        return failureCounts[level][bin];
    }

    /** Largest {@link #overlapMicros} on the level; normalization base for painting. */
    public long maxOverlapMicros(int level) {
        return maxOverlapMicros[level];
    }

    /** Largest {@link #activeCount} on the level. */
    public int maxActiveCount(int level) {
        return maxActiveCounts[level];
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}

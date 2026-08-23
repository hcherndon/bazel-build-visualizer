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
     * Peak build cost of one level-0 bin.
     *
     * <p>Phase 0 counted 32: {@code starts} (int) + {@code overlap} (long) +
     * the three difference arrays (int each) = 24, plus the resolved
     * {@code active} and {@code failure} arrays (int each) that coexist with
     * them.
     *
     * <p>Phase 6 adds what plan 14.3 asks a bin to carry and Phase 0 did not:
     * cache hits (int), remote count (int), bytes (long), and the two words of
     * the majority-category vote (short + int). That is 22 more, for 54.
     *
     * <p>The consequence is fewer level-0 bins for the same memory:
     * {@link #MAX_FINEST_BINS} falls from 6,391,320 to 3,728,270. Measured
     * rather than predicted — Tier 3's 2,343.8 s wall builds 2,343,750 level-0
     * bins, so both benchmark tiers keep the full millisecond. The trade is
     * paid by builds beyond roughly an hour, which lose time resolution rather
     * than data.
     */
    private static final int BYTES_PER_FINEST_BIN = 54;

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
    private final int[][] cacheHitCounts;
    private final int[][] cacheKnownCounts;
    private final int[][] remoteCounts;
    private final int[][] runnerKnownCounts;
    private final long[][] byteTotals;
    private final short[][] majorityCategories;
    private final long[] maxOverlapMicros;
    private final int[] maxActiveCounts;

    private TimelineLodIndex(long wallStartMicros, long wallEndMicros, long totalSpanCount,
            long[] binWidthMicros, int[] binCounts, int[][] startCounts, long[][] overlapMicros,
            int[][] activeCounts, int[][] failureCounts, int[][] cacheHitCounts,
            int[][] cacheKnownCounts, int[][] remoteCounts, int[][] runnerKnownCounts,
            long[][] byteTotals, short[][] majorityCategories, long[] maxOverlapMicros,
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
        this.cacheHitCounts = cacheHitCounts;
        this.cacheKnownCounts = cacheKnownCounts;
        this.remoteCounts = remoteCounts;
        this.runnerKnownCounts = runnerKnownCounts;
        this.byteTotals = byteTotals;
        this.majorityCategories = majorityCategories;
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
        // Start-attributed, like `starts`: a span counts once, in the bin its
        // start falls in. Spreading a cache hit across every bin it overlaps
        // would make one long cached action outweigh a hundred short ones.
        int[][] cacheHit = new int[levelCount][];
        int[][] cacheKnown = new int[levelCount][];
        int[][] remote = new int[levelCount][];
        int[][] runnerKnown = new int[levelCount][];
        long[][] bytes = new long[levelCount][];
        // Boyer-Moore majority vote, one candidate and one counter per bin.
        short[][] majority = new short[levelCount][];
        int[][] majorityVotes = new int[levelCount][];
        long w = finest;
        for (int l = 0; l < levelCount; l++) {
            widths[l] = w;
            counts[l] = (int) ceilDiv(span, w);
            starts[l] = new int[counts[l]];
            overlap[l] = new long[counts[l]];
            activeDiff[l] = new int[counts[l] + 1];
            failDiff[l] = new int[counts[l] + 1];
            coverDiff[l] = new int[counts[l] + 1];
            cacheHit[l] = new int[counts[l]];
            cacheKnown[l] = new int[counts[l]];
            remote[l] = new int[counts[l]];
            runnerKnown[l] = new int[counts[l]];
            bytes[l] = new long[counts[l]];
            majority[l] = new short[counts[l]];
            java.util.Arrays.fill(majority[l], (short) -1);
            majorityVotes[l] = new int[counts[l]];
            w *= LEVEL_GROWTH;
        }

        long[] fed = new long[1];
        source.forEachSpan((s, e, categoryIndex, flags, spanBytes) -> {
            boolean failed = (flags & SpanSource.FLAG_FAILED) != 0;
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
                if ((flags & SpanSource.FLAG_CACHE_KNOWN) != 0) {
                    cacheKnown[l][b0]++;
                    if ((flags & SpanSource.FLAG_CACHE_HIT) != 0) {
                        cacheHit[l][b0]++;
                    }
                }
                if ((flags & SpanSource.FLAG_RUNNER_KNOWN) != 0) {
                    runnerKnown[l][b0]++;
                    if ((flags & SpanSource.FLAG_REMOTE) != 0) {
                        remote[l][b0]++;
                    }
                }
                if (spanBytes > 0) {
                    bytes[l][b0] += spanBytes;
                }
                // One candidate, one counter: the candidate survives only if it
                // outnumbers everything else combined.
                short candidate = (short) categoryIndex;
                if (majorityVotes[l][b0] == 0) {
                    majority[l][b0] = candidate;
                    majorityVotes[l][b0] = 1;
                } else if (majority[l][b0] == candidate) {
                    majorityVotes[l][b0]++;
                } else {
                    majorityVotes[l][b0]--;
                }
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
                // The vote's counter equals the span count only when nothing
                // ever opposed the candidate -- that is, when the bin holds one
                // category. Anything less is a survivor the build cannot
                // verify, so it is discarded rather than reported.
                if (majorityVotes[l][b] != starts[l][b]) {
                    majority[l][b] = -1;
                }
            }
        }

        return new TimelineLodIndex(wallStartMicros, wallEndMicros, fed[0], widths, counts,
                starts, overlap, active, failure, cacheHit, cacheKnown, remote, runnerKnown,
                bytes, majority, maxOverlap, maxActive);
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
    /**
     * Spans starting in this bin that something reported a cache result for.
     *
     * <p>Zero means nobody said, not that nothing was cached. A session with no
     * execution log has zero here for every bin, and a timeline colouring by
     * cache result must render that as unknown rather than as a miss.
     */
    public int cacheKnownCount(int level, int bin) {
        return cacheKnownCounts[level][bin];
    }

    /** Spans starting in this bin that were cache hits. */
    public int cacheHitCount(int level, int bin) {
        return cacheHitCounts[level][bin];
    }

    /**
     * Cache misses in this bin: known results minus hits.
     *
     * <p>Derived rather than stored, so it cannot disagree with the two numbers
     * it comes from.
     */
    public int cacheMissCount(int level, int bin) {
        return cacheKnownCounts[level][bin] - cacheHitCounts[level][bin];
    }

    /** Spans starting in this bin that something reported a runner for. */
    public int runnerKnownCount(int level, int bin) {
        return runnerKnownCounts[level][bin];
    }

    /** Spans starting in this bin that ran off this machine. */
    public int remoteCount(int level, int bin) {
        return remoteCounts[level][bin];
    }

    /** Spans starting in this bin that ran on it: known runners minus remote. */
    public int localCount(int level, int bin) {
        return runnerKnownCounts[level][bin] - remoteCounts[level][bin];
    }

    /**
     * Bytes attributable to the spans starting in this bin.
     *
     * <p>A lower bound wherever a span reported none: spans with no byte count
     * contribute nothing rather than a guess, so this is "at least this many"
     * and never "this many" (plan rule 13).
     */
    public long byteTotal(int level, int bin) {
        return byteTotals[level][bin];
    }

    /**
     * The category of every span in this bin, when they are all the same.
     *
     * <p>Empty when the bin mixes categories, and empty when it is empty.
     *
     * <p>This is a weaker claim than plan 14.3's "top mnemonics or category
     * summary" and it is the strongest one the build can make honestly. A
     * Boyer-Moore vote costs two words per bin and survives with a candidate
     * that is a true majority only if one exists; proving which case happened
     * needs a second pass over the bin's members, which the single streaming
     * pass does not have. What the vote <em>can</em> establish for free is the
     * special case where its counter never dropped — every span was the same
     * category — and that is what this reports.
     *
     * <p>It is not a consolation prize. Real builds spend long stretches doing
     * one kind of work, so a bin that is all {@code Javac} is common and saying
     * so is worth more than a ranking that might be wrong.
     */
    public java.util.OptionalInt uniformCategory(int level, int bin) {
        short category = majorityCategories[level][bin];
        return category < 0
                ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(category);
    }

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

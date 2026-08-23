package com.holtherndon.bazelviz.ui.timeline;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the timeline needs to paint one frame, prepared before any frame
 * is painted.
 *
 * <h2>This type is the exit criterion</h2>
 *
 * <p>Plan 24 requires that no SQLite access occurs during painting. That is a
 * property nobody can hold in their head across a paint method, so it is made
 * structural instead: the view is handed one of these and can reach nothing
 * else. There is no connection, no reader and no query on it — only arrays and
 * counts — so a paint that wanted to hit the database would have nothing to
 * hit.
 *
 * <p>Every instance is built on a worker thread and then handed to the EDT.
 * Replacing it is one field assignment, which is why a live update can swap the
 * whole model without the view ever seeing a half-built one.
 *
 * @param index the level-of-detail pyramid, for broad zoom
 * @param lanes rows the spans are grouped into
 * @param criticalPathNodes graph node indices on the visualizer's computed
 *     critical path, for the overlay; empty when no graph or no timing
 * @param categoryNames mnemonic names by category index, for labelling a
 *     uniform bin
 * @param spansWithoutTime actions the session holds that have no timestamps and
 *     therefore appear nowhere on this timeline
 * @param cacheResultsKnown how many spans anything reported a cache result for;
 *     zero means the cache colour mode has nothing to say and must say so
 */
public record TimelineModel(
        TimelineLodIndex index,
        List<Lane> lanes,
        List<Integer> criticalPathNodes,
        Map<Integer, String> categoryNames,
        long spansWithoutTime,
        long cacheResultsKnown,
        long runnersKnown) {

    public TimelineModel {
        lanes = List.copyOf(lanes);
        criticalPathNodes = List.copyOf(criticalPathNodes);
        categoryNames = Map.copyOf(categoryNames);
    }

    /** The wall this timeline covers. */
    public long wallStartMicros() {
        return index.wallStartMicros();
    }

    public long wallEndMicros() {
        return index.wallEndMicros();
    }

    /** Total spans behind this model, drawn or not. */
    public long spanCount() {
        return index.totalSpanCount();
    }

    /**
     * Whether the cache colour mode has anything to colour by.
     *
     * <p>False for every session with no execution log. A mode that painted
     * those spans as misses would be inventing the most interesting fact on the
     * screen.
     */
    public boolean canColourByCache() {
        return cacheResultsKnown > 0;
    }

    /** Whether the runner colour mode has anything to colour by. */
    public boolean canColourByRunner() {
        return runnersKnown > 0;
    }

    /**
     * What the view says under the timeline about what is not on it.
     *
     * <p>Rule 13: a timeline showing 40 of a build's 12,000 actions is not
     * wrong, but a timeline that does not say so is.
     */
    public Optional<String> coverageNote() {
        if (spansWithoutTime == 0) {
            return Optional.empty();
        }
        return Optional.of(spansWithoutTime + " action" + (spansWithoutTime == 1 ? "" : "s")
                + " have no timestamps and are not on this timeline. Bazel 6.5.0 and 7.6.1"
                + " report no action times at all; importing an execution log supplies them"
                + " for the actions that spawned a subprocess.");
    }

    /**
     * One row of the timeline.
     *
     * @param name what the row is labelled
     * @param key the grouping value this lane holds, for selection round-trips
     * @param spanCount how many spans fall in it
     * @param totalMicros the sum of their durations, which is not the lane's
     *     elapsed time — overlapping spans are counted twice, deliberately,
     *     because that is what "total duration" means for sorting by work
     * @param firstMicros when the lane's earliest span starts
     */
    public record Lane(
            String name, String key, long spanCount, long totalMicros, long firstMicros,
            long lastMicros) {

        /** The lane's extent, which is not its total duration. */
        public long elapsedMicros() {
            return Math.max(0, lastMicros - firstMicros);
        }
    }
}

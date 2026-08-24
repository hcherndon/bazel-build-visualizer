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
 * @param liveBand what is in flight right now, for the band a live capture
 *     draws above the lanes; {@link LiveBand#EMPTY} for a finished session
 */
public record TimelineModel(
        TimelineLodIndex index,
        List<Lane> lanes,
        List<Integer> criticalPathNodes,
        Map<Integer, String> categoryNames,
        long spansWithoutTime,
        long cacheResultsKnown,
        long runnersKnown,
        LiveBand liveBand) {

    public TimelineModel {
        lanes = List.copyOf(lanes);
        criticalPathNodes = List.copyOf(criticalPathNodes);
        categoryNames = Map.copyOf(categoryNames);
        liveBand = liveBand == null ? LiveBand.EMPTY : liveBand;
    }

    /** A model with no live band — every finished session's shape. */
    public TimelineModel(
            TimelineLodIndex index,
            List<Lane> lanes,
            List<Integer> criticalPathNodes,
            Map<Integer, String> categoryNames,
            long spansWithoutTime,
            long cacheResultsKnown,
            long runnersKnown) {
        this(index, lanes, criticalPathNodes, categoryNames, spansWithoutTime,
                cacheResultsKnown, runnersKnown, LiveBand.EMPTY);
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
     * The targets a live capture has configured and not yet completed.
     *
     * <h2>Why targets and not actions</h2>
     *
     * <p>BEP has no action-start event: {@code ActionExecuted} fires once, at
     * completion, so "this action is running" is not a fact the stream can
     * supply and this band never claims it. {@code TargetConfigured} and
     * {@code TargetCompleted} are real live signals, so in-flight is
     * target-level — the band is labelled as targets so nobody reads its
     * spans as actions.
     *
     * <h2>Where the times come from</h2>
     *
     * <p>A target's start is the wall-clock instant its {@code
     * TargetConfigured} event was received ({@code bep_events.receive_micros}
     * via the target's {@code bep_event_id}) — BEP target events carry no
     * timestamp of their own. Receive time is a different measurement from
     * execution time and the band says so in its label. A configured target
     * whose event row has no receive time is counted in
     * {@link #withoutTimestamps} and drawn nowhere: absent, never zero-length.
     *
     * @param live whether the session is a running capture; a finished
     *     session's band is {@link #EMPTY} and never drawn
     * @param inFlight the earliest-configured in-flight targets, at most
     *     {@link SpanWindow#MAX_SPANS} of them
     * @param totalInFlight how many targets are in flight in all, so a capped
     *     list is stated with exact numbers rather than passing for the whole
     * @param withoutTimestamps in-flight targets with no receive time, which
     *     appear in the count but not in the band
     */
    public record LiveBand(
            boolean live,
            List<InFlight> inFlight,
            long totalInFlight,
            long withoutTimestamps) {

        /** No band at all: every finished or imported session. */
        public static final LiveBand EMPTY = new LiveBand(false, List.of(), 0, 0);

        public LiveBand {
            inFlight = List.copyOf(inFlight);
        }

        /** One target configured and not yet completed. */
        public record InFlight(String label, long startMicros) {}
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

package com.holtherndon.bazelviz.analysis;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One row of an aggregate: everything the session can say about the actions
 * that share a key.
 *
 * <h2>The dimensions the plan asks for, and the ones that exist</h2>
 *
 * <p>Plan 15.3 lists eleven aggregate dimensions. {@link Dimension} carries the
 * ones this session's schema can group by, and does not carry the others — a
 * dimension that appeared in a menu and returned an empty chart would be a
 * worse answer than one that is not offered, because the empty chart looks like
 * a build with nothing in it.
 *
 * @param key the mnemonic, runner, label or status shared by these actions;
 *     null when the group is the actions whose key was never recorded
 * @param actions how many actions are in this group, whatever they reported
 * @param duration how long they took
 * @param inputBytes what they read, where that was reported
 * @param cacheHits actions the execution log reported as served from a cache
 * @param cacheMisses actions it reported as not
 * @param cacheUnknown actions it said nothing about, which is neither
 * @param declaredNotCacheable actions whose spawns Bazel marked uncacheable —
 *     its own declaration, read verbatim, not a guess from a runner name
 * @param declaredNotRemotable actions whose spawns Bazel marked as unable to
 *     run remotely
 */
public record GroupAggregate(
        Dimension dimension,
        String key,
        long actions,
        MetricSeries duration,
        MetricSeries inputBytes,
        long cacheHits,
        long cacheMisses,
        long cacheUnknown,
        long declaredNotCacheable,
        long declaredNotRemotable) {

    public GroupAggregate {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(inputBytes, "inputBytes");
        if (cacheHits + cacheMisses + cacheUnknown != actions) {
            throw new IllegalArgumentException(
                    "cache states must account for every action in the group: "
                            + cacheHits + " + " + cacheMisses + " + " + cacheUnknown
                            + " is not " + actions);
        }
    }

    /** What actions were grouped by. */
    public enum Dimension {
        MNEMONIC("Mnemonic"),
        TARGET("Target"),
        PACKAGE("Package"),
        RUNNER("Runner"),
        CACHE_STATE("Cache state"),
        STATUS("Status");

        private final String displayName;

        Dimension(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** True for the group of actions whose key was never recorded. */
    public boolean isUnknownKey() {
        return key == null;
    }

    /** Plan 11.4: unknown has to read as unknown, never as an empty name. */
    public String displayKey() {
        return key == null ? "(not recorded)" : key;
    }

    /**
     * The share of this group whose cache state was reported as a hit, over the
     * actions that reported one at all.
     *
     * <p>Empty when none did. A miss rate computed with unknowns counted as
     * misses is the single easiest way to invent a cache problem, and plan 16.1
     * requires "sufficient cache-state coverage" before a cache finding may be
     * raised at all.
     */
    public OptionalDouble cacheHitRate() {
        long known = cacheHits + cacheMisses;
        return known == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) cacheHits / known);
    }

    /** How much of this group's cache state was reported. */
    public Coverage cacheCoverage() {
        return Coverage.of(
                "Cache state", cacheHits + cacheMisses, actions,
                com.holtherndon.bazelviz.core.source.DataSource.EXECUTION_LOG,
                "an action that never spawned a subprocess has no execution-log record");
    }

    /** One line naming the group and what is known about it. */
    public String describe() {
        StringBuilder text = new StringBuilder(displayKey())
                .append(": ").append(actions).append(actions == 1 ? " action" : " actions");
        duration.observedSum().ifPresent(total ->
                text.append(", ").append(total / 1000).append(" ms of observed work"));
        if (duration.unavailable() > 0) {
            text.append(" (").append(duration.unavailable()).append(" untimed)");
        }
        return text.toString();
    }

    /**
     * An aggregate over one dimension, in the order it should be shown.
     *
     * <p>{@link #totalGroups} and {@link #totalActions} describe the whole
     * aggregation; {@link #groups} may be the heaviest few of them. Rule 12
     * forbids truncating silently, so the difference between the two is
     * always available and {@link #describe()} states it.
     *
     * @param totalGroups distinct keys the dimension produced, whether or not
     *     they are listed
     * @param totalActions actions the aggregation ran over, whether or not
     *     their group is listed
     */
    public record Table(
            Dimension dimension,
            List<GroupAggregate> groups,
            long totalGroups,
            long totalActions) {

        public Table {
            Objects.requireNonNull(dimension, "dimension");
            groups = List.copyOf(groups);
        }

        /** True when the listed groups are not all of them. */
        public boolean isTruncated() {
            return groups.size() < totalGroups;
        }

        /** What must be said alongside the rows. */
        public String describe() {
            String base = dimension.displayName() + ": " + totalGroups
                    + (totalGroups == 1 ? " group" : " groups")
                    + " covering all " + totalActions
                    + (totalActions == 1 ? " action" : " actions");
            if (!isTruncated()) {
                return base + ".";
            }
            return base + ", of which the " + groups.size()
                    + " with the most observed work are shown. Nothing is lost — the remaining "
                    + (totalGroups - groups.size()) + " account for "
                    + (totalActions - groupedActions()) + " actions.";
        }

        /** The group with the most observed work, when any group has any. */
        public Optional<GroupAggregate> heaviest() {
            return groups.stream()
                    .filter(group -> group.duration().observedSum().isPresent())
                    .max((left, right) -> Long.compare(
                            left.duration().observedSum().orElse(0),
                            right.duration().observedSum().orElse(0)));
        }

        /**
         * The actions accounted for, which must equal the actions there are.
         *
         * <p>Exposed rather than only asserted in a test, for the same reason
         * {@code GraphClustering} exposes its own: a view drawing these numbers
         * should be able to check the arithmetic it is drawing.
         */
        public long groupedActions() {
            return groups.stream().mapToLong(GroupAggregate::actions).sum();
        }
    }
}

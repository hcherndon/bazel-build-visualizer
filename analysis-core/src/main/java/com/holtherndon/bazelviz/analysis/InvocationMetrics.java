package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.core.measure.Measured;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Plan 15.2's invocation metrics: what this build did, as a whole.
 *
 * <h2>Grouped, not flattened</h2>
 *
 * <p>The plan's list is twenty-eight entries long, and a record of twenty-eight
 * components is a record nobody reads correctly. They are grouped by what they
 * are about, which also puts the numbers that must not be confused with each
 * other into different types — {@link CriticalPaths} being the one that
 * matters, since keeping the two critical paths apart is an exit criterion
 * rather than a preference.
 *
 * <h2>Everything optional is optional in the type</h2>
 *
 * <p>Rule 11 and plan 11.4: unavailable never becomes zero. Where a number
 * might not exist, it is a {@link Measured} or an {@link Optional} rather than
 * a {@code long} with a sentinel, so a view cannot render it without deciding
 * what to say when it is missing.
 *
 * @param concurrency the sweep, absent when nothing had a usable span
 */
public record InvocationMetrics(
        Timing timing,
        Work work,
        Bytes bytes,
        Optional<ConcurrencySweep.Result> concurrency,
        CriticalPaths criticalPaths,
        Tests tests,
        Ingest ingest,
        Coverage.Report coverage) {

    public InvocationMetrics {
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(concurrency, "concurrency");
        Objects.requireNonNull(criticalPaths, "criticalPaths");
        Objects.requireNonNull(tests, "tests");
        Objects.requireNonNull(ingest, "ingest");
        Objects.requireNonNull(coverage, "coverage");
    }

    /**
     * How long the invocation and its phases took.
     *
     * @param totalWallMicros what the user watched, {@code buildFinished} minus
     *     {@code buildStarted}
     * @param timeToFirstEventMicros from the build starting to the first event
     *     this session received, which is the tool's own startup as seen from
     *     here rather than a Bazel measurement
     * @param phases the profile's phase markers, whose ends are derived because
     *     the profile records when each phase began and never when it ended
     */
    public record Timing(
            Measured<Long> totalWallMicros,
            Measured<Long> timeToFirstEventMicros,
            List<Phase> phases) {

        public Timing {
            Objects.requireNonNull(totalWallMicros, "totalWallMicros");
            Objects.requireNonNull(timeToFirstEventMicros, "timeToFirstEventMicros");
            phases = List.copyOf(phases);
        }

        /** One named stretch of the build. */
        public record Phase(
                int ordinal, String name, long startMicros, OptionalLong endMicros,
                boolean endIsDerived) {

            public Phase {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(endMicros, "endMicros");
            }

            public OptionalLong durationMicros() {
                return endMicros.isPresent()
                        ? OptionalLong.of(endMicros.getAsLong() - startMicros)
                        : OptionalLong.empty();
            }
        }
    }

    /**
     * What ran.
     *
     * @param runners counts by the runner string Bazel wrote, not by a category
     *     this application invented — {@code spawn.proto} constrains that field
     *     to nothing and says it varies under the dynamic strategy, so
     *     "local/remote/worker" is a reading of the data and not the data
     * @param cacheStateUnknown actions the execution log said nothing about,
     *     which is neither a hit nor a miss
     */
    public record Work(
            long actions,
            long attempts,
            long actionsSucceeded,
            long actionsFailed,
            long actionsOtherOutcome,
            long cacheHits,
            long cacheMisses,
            long cacheStateUnknown,
            List<RunnerCount> runners) {

        public Work {
            runners = List.copyOf(runners);
        }

        /** How many actions per runner string. */
        public record RunnerCount(String runner, long actions) {

            public RunnerCount {
                Objects.requireNonNull(runner, "runner");
            }
        }

        /**
         * Hits over the actions whose cache state was reported.
         *
         * <p>Never over all actions. An action with no execution-log record is
         * not a miss, and counting it as one manufactures a cache problem out
         * of an enrichment that did not run.
         */
        public OptionalDouble cacheHitRate() {
            long known = cacheHits + cacheMisses;
            return known == 0
                    ? OptionalDouble.empty()
                    : OptionalDouble.of((double) cacheHits / known);
        }

        /**
         * Attempts per action, when there were actions.
         *
         * <p>Legitimately far below one: most actions run inside the Bazel
         * server and never spawn a subprocess, so they have no attempt record
         * and never will.
         */
        public OptionalDouble attemptsPerAction() {
            return actions == 0
                    ? OptionalDouble.empty()
                    : OptionalDouble.of((double) attempts / actions);
        }
    }

    /**
     * Sizes, each with what it could not count.
     *
     * @param knownInputBytes summed over attempts that reported a size
     * @param actionsWithoutInputBytes attempts that did not
     * @param knownOutputBytes summed over artifacts with a recorded size
     * @param artifactsWithoutSize artifacts with none, which is the normal
     *     answer for a file that only ever existed on a remote executor
     */
    public record Bytes(
            Measured<Long> knownInputBytes,
            long actionsWithoutInputBytes,
            Measured<Long> knownOutputBytes,
            long artifactsWithoutSize) {

        public Bytes {
            Objects.requireNonNull(knownInputBytes, "knownInputBytes");
            Objects.requireNonNull(knownOutputBytes, "knownOutputBytes");
        }

        /** True when either total is a lower bound rather than a total. */
        public boolean isPartial() {
            return actionsWithoutInputBytes > 0 || artifactsWithoutSize > 0;
        }
    }

    /** Test totals, including the state that is neither pass nor fail. */
    public record Tests(long total, long failed, long flaky, long cached) {}

    /**
     * How the capture itself went.
     *
     * @param meanIngestLagMicros between an event's own timestamp and this
     *     session receiving it — a measure of this application, not of Bazel.
     *     A mean rather than a median because a median over every event costs a
     *     second full pass over the event table, and this number describes the
     *     capture rather than the build
     * @param undecodableEvents events whose bytes could not be decoded, which
     *     is damage
     * @param notAttemptedEvents events this session chose not to decode, which
     *     is the essential-only indexing policy and is not damage — counting
     *     the two together would report a setting as a corruption
     * @param attemptsCorrelated execution-log records matched to a BEP action
     * @param attemptsUnresolved records that matched nothing, which on a build
     *     without {@code --build_event_publish_all_actions} is most of them and
     *     is not an error
     */
    public record Ingest(
            long rawEvents,
            long undecodableEvents,
            long notAttemptedEvents,
            Measured<Long> meanIngestLagMicros,
            long attemptsCorrelated,
            long attemptsUnresolved) {

        public Ingest {
            Objects.requireNonNull(meanIngestLagMicros, "meanIngestLagMicros");
        }

        /** Matched records over records, when there were any. */
        public OptionalDouble correlationRate() {
            long total = attemptsCorrelated + attemptsUnresolved;
            return total == 0
                    ? OptionalDouble.empty()
                    : OptionalDouble.of((double) attemptsCorrelated / total);
        }
    }
}

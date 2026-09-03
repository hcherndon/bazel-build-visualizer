package com.holtherndon.bazelviz.analysis;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Plan 15.1's action metrics for one action: everything the session can say
 * about it, with every unknown left unknown.
 *
 * <h2>Why so many of these are optional</h2>
 *
 * <p>Almost none of these values exists for every action, and the reasons
 * differ. Most actions run inside the Bazel server and never spawn a
 * subprocess, so they have no execution-log record and therefore no queue,
 * setup, network, upload or fetch time — and never will. Bazel publishes an
 * event for a successful action only under
 * {@code --build_event_publish_all_actions}. The dependency counts exist only
 * when an {@code aquery} graph was imported, and slack only when that graph
 * could be scheduled. A field of {@code long} would force each of those into a
 * zero, and a zero for queue time is a claim that the action did not wait.
 *
 * <h2>The timing breakdown does not sum to the total</h2>
 *
 * <p>Bazel measures the components separately from the total, so they do not
 * add up, and the difference is genuinely unaccounted rather than something to
 * fold into execution time. {@link #unaccountedMicros()} names it instead of
 * hiding it.
 *
 * @param startMicros where the action sits on the build's clock, absent when
 *     the session's duration source did not place it
 * @param durationMicros under the session's chosen duration source; which one
 *     that was is a property of the session, not of the action
 * @param outputFiles outputs whose size was recorded, which is a floor on the
 *     action's output count rather than the count itself — the bounded query
 *     that supplies it reaches only the actions that produced the most bytes
 * @param directConsumers actions that consume something this one produced,
 *     from the imported action graph
 * @param slackMicros how much later it could have started without making the
 *     build longer, from the derived schedule
 * @param startConcurrency actions running when it started
 * @param completionConcurrency actions running immediately before it finished
 */
public record ActionMetrics(
        long actionId,
        String label,
        String mnemonic,
        String runner,
        String outcome,
        OptionalLong startMicros,
        OptionalLong endMicros,
        OptionalLong durationMicros,
        OptionalLong queueMicros,
        OptionalLong setupMicros,
        OptionalLong executionMicros,
        OptionalLong networkMicros,
        OptionalLong uploadMicros,
        OptionalLong fetchMicros,
        OptionalLong inputBytes,
        OptionalLong inputFiles,
        OptionalLong outputBytes,
        OptionalLong outputFiles,
        long attempts,
        CacheState cacheState,
        OptionalLong directDependencies,
        OptionalLong directConsumers,
        OptionalLong slackMicros,
        boolean onDerivedCriticalPath,
        OptionalLong startConcurrency,
        OptionalLong completionConcurrency) {

    public ActionMetrics {
        Objects.requireNonNull(cacheState, "cacheState");
    }

    /** Whether an action's spawns were served from a cache. */
    public enum CacheState {
        HIT("Hit"),
        MISS("Miss"),
        /** Nothing said. Not a miss. */
        NOT_REPORTED("Not reported");

        private final String displayName;

        CacheState(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Attempts beyond the first.
     *
     * <p>Plan 15.1 calls this retry count and asks for competing attempts to be
     * explained separately, because the dynamic strategy races a local and a
     * remote spawn and both are recorded. Two attempts can therefore mean one
     * retry or one race, and this number alone does not distinguish them —
     * which is why the runner of each attempt is what a reader has to look at.
     */
    public long retries() {
        return Math.max(0, attempts - 1);
    }

    /**
     * Queue time as a share of the action's duration, when both are known.
     *
     * <p>Plan 16.1's queue-dominated rule rests on this, together with its own
     * instruction: do not assume the cause is Bazel rather than remote
     * infrastructure.
     */
    public OptionalDouble queueFraction() {
        return fractionOf(queueMicros);
    }

    /** Network, upload and fetch time together, as a share of the duration. */
    public OptionalDouble transferFraction() {
        if (durationMicros.isEmpty() || durationMicros.getAsLong() <= 0) {
            return OptionalDouble.empty();
        }
        long transfer = 0;
        for (OptionalLong part : new OptionalLong[] {networkMicros, uploadMicros, fetchMicros}) {
            if (part.isEmpty()) {
                return OptionalDouble.empty();
            }
            try {
                transfer = Math.addExact(transfer, part.getAsLong());
            } catch (ArithmeticException overflow) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.of((double) transfer / durationMicros.getAsLong());
    }

    /**
     * The part of the duration the component timings do not explain.
     *
     * <p>Shown as unaccounted rather than folded into execution time, because
     * Bazel measures the total independently of the parts.
     */
    public OptionalLong unaccountedMicros() {
        if (durationMicros.isEmpty()) {
            return OptionalLong.empty();
        }
        long accounted = 0;
        for (OptionalLong part : new OptionalLong[] {
                queueMicros, setupMicros, executionMicros, networkMicros, uploadMicros,
                fetchMicros}) {
            if (part.isEmpty()) {
                return OptionalLong.empty();
            }
            try {
                accounted = Math.addExact(accounted, part.getAsLong());
            } catch (ArithmeticException overflow) {
                return OptionalLong.empty();
            }
        }
        try {
            return OptionalLong.of(Math.subtractExact(durationMicros.getAsLong(), accounted));
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    private OptionalDouble fractionOf(OptionalLong part) {
        if (part.isEmpty() || durationMicros.isEmpty() || durationMicros.getAsLong() <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) part.getAsLong() / durationMicros.getAsLong());
    }

    /** The label, or the primary output's stand-in when there is no label. */
    public String displayLabel() {
        return label == null || label.isBlank() ? "(no label recorded)" : label;
    }
}

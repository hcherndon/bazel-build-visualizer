package com.holtherndon.bazelviz.storage.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One execution of one subprocess, as the attempt inspector shows it.
 *
 * <p>Every optional is a fact about the build or the Bazel version. {@code
 * startMicros} is absent for every attempt captured on Bazel 6.5.0, which never
 * reports one; {@code queueMicros} and its neighbours are absent for anything
 * that ran locally; {@code digestHash} is absent unless a cache was in play.
 *
 * @param correlationNote why this attempt is attached to what it is attached
 *     to, or why it is attached to nothing — the exit criterion "ambiguous
 *     correlations remain visible" is this field reaching the screen
 * @param startUnknownReason why there is no start, when there is none
 */
public record AttemptRow(
        long id,
        OptionalLong actionId,
        AttemptCorrelation correlation,
        Optional<String> correlationNote,
        Optional<String> label,
        Optional<String> mnemonic,
        Optional<String> runner,
        boolean cacheHit,
        OptionalInt exitCode,
        Optional<String> status,
        OptionalLong startMicros,
        Optional<String> startUnknownReason,
        Timing timing,
        Optional<String> digestHash,
        OptionalLong inputBytes,
        OptionalLong inputFiles,
        OptionalLong memoryPeakBytes,
        long producedOutputs,
        long unproducedOutputs) {

    public AttemptRow {
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(timing, "timing");
    }

    /**
     * The timing breakdown, whole.
     *
     * <p>Kept as a group so the inspector can render every component that was
     * measured and say nothing about the ones that were not, rather than
     * showing a row of zeroes for a locally-executed spawn that never queued,
     * never uploaded and never fetched.
     */
    public record Timing(
            OptionalLong totalMicros,
            OptionalLong executionWallMicros,
            OptionalLong parseMicros,
            OptionalLong networkMicros,
            OptionalLong fetchMicros,
            OptionalLong queueMicros,
            OptionalLong setupMicros,
            OptionalLong uploadMicros,
            OptionalLong processOutputsMicros,
            OptionalLong retryMicros) {

        /** The components, in the order they happen, with their display names. */
        public List<Component> components() {
            return List.of(
                    new Component("Parse", parseMicros),
                    new Component("Queue", queueMicros),
                    new Component("Setup", setupMicros),
                    new Component("Network", networkMicros),
                    new Component("Execution", executionWallMicros),
                    new Component("Fetch outputs", fetchMicros),
                    new Component("Process outputs", processOutputsMicros),
                    new Component("Upload", uploadMicros),
                    new Component("Retries", retryMicros));
        }

        /**
         * The components that were measured.
         *
         * <p>An unmeasured component is not a zero-length one. A local spawn
         * has no queue time because it never queued, and a row saying
         * "Queue: 0ms" would be an answer to a question nobody asked.
         */
        public List<Component> measuredComponents() {
            return components().stream().filter(Component::isMeasured).toList();
        }

        /**
         * What the components do not account for.
         *
         * <p>{@code total_time} is measured separately from its parts, and on a
         * local build most parts are absent, so the remainder is usually most
         * of the total. It is shown as unaccounted rather than folded into
         * execution, which would claim a measurement that was not made.
         */
        public OptionalLong unaccountedMicros() {
            if (totalMicros.isEmpty()) {
                return OptionalLong.empty();
            }
            long summed = measuredComponents().stream()
                    .mapToLong(component -> component.micros().orElse(0))
                    .sum();
            long remainder = totalMicros.getAsLong() - summed;
            return remainder > 0 ? OptionalLong.of(remainder) : OptionalLong.empty();
        }

        /** One named part of a spawn's elapsed time. */
        public record Component(String name, OptionalLong micros) {
            public boolean isMeasured() {
                return micros.isPresent();
            }
        }
    }

    /** The elapsed time, carrying its source and its absence reason. */
    public Measured<Long> elapsed() {
        return timing.totalMicros().isPresent()
                ? Measured.of(timing.totalMicros().getAsLong(), DataSource.EXECUTION_LOG)
                : Measured.unknown(
                        DataSource.EXECUTION_LOG,
                        com.holtherndon.bazelviz.core.source.Completeness.UNAVAILABLE,
                        "this spawn's record carries no total time");
    }

    /** True when the correlation is one the user should look at. */
    public boolean correlationNeedsAttention() {
        return correlation.needsAttention();
    }
}

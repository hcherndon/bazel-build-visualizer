package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One test target's result.
 *
 * <p>{@code overallStatus} comes from {@code testSummary} and from nowhere
 * else: the target's own success flag is {@code true} for a test that failed,
 * so a view deriving pass/fail from the target would show every failure green.
 *
 * @param attemptCount the maximum attempts any (run, shard) needed. Not a
 *     retry count — it equals {@code runCount} for a healthy multi-run test.
 * @param shardCount absent when the test was not sharded, never zero
 * @param firstStartMicros the earliest attempt's start, computed from
 *     {@code test_attempts} rather than read from the summary — the summary's
 *     first start was measured 218–747 ms later than the earliest attempt
 * @param lastStopMicros the latest attempt's end, likewise
 * @param bazelReportedDurationMicros Bazel's own figure, which excludes failed
 *     retries; shown as Bazel's and never used as a timeline bound
 */
public record TestRow(
        long id,
        String label,
        Optional<String> configurationId,
        TestOutcome overallStatus,
        OptionalInt totalRunCount,
        OptionalInt runCount,
        OptionalInt shardCount,
        OptionalInt attemptCount,
        int totalNumCached,
        OptionalLong firstStartMicros,
        OptionalLong lastStopMicros,
        OptionalLong bazelReportedDurationMicros,
        OptionalLong timeoutSeconds,
        long attemptRows,
        long failedAttemptRows,
        OptionalLong bepEventId) {

    public TestRow {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(overallStatus, "overallStatus");
    }

    /** Elapsed time across every attempt, when both ends are known. */
    public OptionalLong wallMicros() {
        if (firstStartMicros.isEmpty() || lastStopMicros.isEmpty()) {
            return OptionalLong.empty();
        }
        long span = lastStopMicros.getAsLong() - firstStartMicros.getAsLong();
        return span < 0 ? OptionalLong.empty() : OptionalLong.of(span);
    }

    /** True when an attempt failed even though the test ultimately passed. */
    public boolean hadFailedAttempt() {
        return failedAttemptRows > 0;
    }
}

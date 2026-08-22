package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One attempt at one shard of one run.
 *
 * <p>{@code status} is a narrower domain than a test's overall status:
 * {@code FLAKY} describes a target's history and never an attempt.
 *
 * @param cachedLocally a cached attempt replays timestamps from before the
 *     build began — measured 2.1 s earlier — so any timeline has to treat these
 *     rows deliberately rather than plot them where they claim to be
 */
public record TestAttemptRow(
        long id,
        long testId,
        int run,
        int shard,
        int attempt,
        TestOutcome status,
        boolean cachedLocally,
        OptionalLong startMicros,
        OptionalLong durationMicros,
        OptionalInt exitCode,
        Optional<String> strategy,
        OptionalLong bepEventId) {

    public TestAttemptRow {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(strategy, "strategy");
    }

    /** {@code run 2 of 3, shard 1, attempt 2} style label, omitting the trivial parts. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (run > 1) {
            text.append("run ").append(run);
        }
        if (shard > 1) {
            text.append(text.isEmpty() ? "" : ", ").append("shard ").append(shard);
        }
        if (attempt > 1) {
            text.append(text.isEmpty() ? "" : ", ").append("attempt ").append(attempt);
        }
        return text.isEmpty() ? "single run" : text.toString();
    }
}

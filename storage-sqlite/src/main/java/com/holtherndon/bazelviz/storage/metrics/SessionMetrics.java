package com.holtherndon.bazelviz.storage.metrics;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One read of the whole metric catalog, taken at one instant.
 *
 * <h2>Why it travels as one object</h2>
 *
 * <p>Every number here came from the same scan, so the aggregate tables add up
 * to the invocation counts and the concurrency sweep describes the same actions
 * the duration distribution does. Handing them out separately would let a view
 * show a cache-hit rate from one instant beside an action count from another,
 * which during a live capture is not hypothetical — the counts move several
 * times a second.
 *
 * @param durationSource which measurement fed every duration here, and
 *     therefore the concurrency sweep and the derived critical path too
 * @param spans retained so per-action start and completion concurrency can be
 *     answered without a second sweep; at Tier 3 this is two longs per timed
 *     action and is the largest thing in this record
 */
public record SessionMetrics(
        CriticalPath.DurationSource durationSource,
        InvocationMetrics invocation,
        Map<GroupAggregate.Dimension, GroupAggregate.Table> aggregates,
        ConcurrencySweep.Spans spans,
        ConcurrencySweep.Result concurrency) {

    public SessionMetrics {
        Objects.requireNonNull(durationSource, "durationSource");
        Objects.requireNonNull(invocation, "invocation");
        aggregates = Map.copyOf(aggregates);
        Objects.requireNonNull(spans, "spans");
        Objects.requireNonNull(concurrency, "concurrency");
    }

    /** One aggregate table, when it was asked for. */
    public Optional<GroupAggregate.Table> aggregate(GroupAggregate.Dimension dimension) {
        return Optional.ofNullable(aggregates.get(dimension));
    }

    /**
     * How many actions were running when one action started (plan 15.1).
     *
     * <p>Empty when that action has no observed start under this session's
     * duration source, which is not the same as none having been running.
     */
    public OptionalLong startConcurrency(OptionalLong startMicros) {
        return startMicros.isPresent()
                ? OptionalLong.of(spans.activeAt(startMicros.getAsLong()))
                : OptionalLong.empty();
    }

    /**
     * How many were running immediately before one action finished.
     *
     * <p>One microsecond before its end, because spans are half-open and the
     * action's own end instant is a moment at which it is no longer running.
     * Plan 15.1's wording — "number active immediately before completion" — is
     * what that microsecond is for.
     */
    public OptionalLong completionConcurrency(OptionalLong endMicros) {
        if (endMicros.isEmpty()) {
            return OptionalLong.empty();
        }
        long instant = endMicros.getAsLong();
        return OptionalLong.of(spans.activeAt(instant == Long.MIN_VALUE ? instant : instant - 1));
    }

    /**
     * The typical concurrency this session ran at, for finding thresholds.
     *
     * <p>The average across the stretches something was running, rounded down.
     * Plan 16.1 defines a low-parallelism window as "significantly fewer active
     * actions than the session's typical concurrency", and this is that
     * baseline: it excludes the idle gaps, so a build with one long stall does
     * not lower its own bar for noticing the stall.
     */
    public OptionalLong typicalConcurrency() {
        return concurrency.averageActiveWhileBusy().isPresent()
                ? OptionalLong.of((long) Math.floor(
                        concurrency.averageActiveWhileBusy().orElseThrow()))
                : OptionalLong.empty();
    }
}

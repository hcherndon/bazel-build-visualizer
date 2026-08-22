package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;

/**
 * Describes a test for the shared inspector: the verdict, then every attempt.
 *
 * <p>The attempts are the point. A test that passed on its second try shows
 * {@code FLAKY} at the top and a {@code FAILED} attempt beneath it, so the
 * evidence for the verdict is on the same screen as the verdict — and a test
 * whose attempts were cached says so, because a cached attempt's timestamps
 * come from before this build started and would otherwise look like a
 * timeline error.
 */
public final class TestInspection {

    private TestInspection() {}

    public static Inspection of(
            TestRow test, List<TestAttemptRow> attempts, List<TestQueries.TestLog> logs) {
        Inspection.Builder builder = new Inspection.Builder(test.label())
                .subtitle(test.overallStatus().name())
                .sourceEvent(test.bepEventId());

        builder.section("Result")
                .field("Status", test.overallStatus().name())
                .field(EntityFormat.field("Configuration", test.configurationId()))
                .field(counted("Runs", test.totalRunCount().isPresent()
                        ? Optional.of(EntityFormat.count(test.totalRunCount()))
                        : Optional.empty()))
                .field(test.shardCount().isPresent()
                        ? Inspection.Field.of("Shards", EntityFormat.count(test.shardCount()))
                        // Absent means "not sharded". A zero here would put a
                        // phantom zero-shard test into any histogram over it.
                        : Inspection.Field.of("Shards", "not sharded"))
                .field("Cached runs", Integer.toString(test.totalNumCached()));

        builder.section("Timing")
                .field(test.wallMicros().isPresent()
                        ? Inspection.Field.of("Elapsed across attempts",
                                EntityFormat.duration(test.wallMicros()))
                        : Inspection.Field.unknown("Elapsed across attempts",
                                "no attempt reported both a start and a duration"))
                // Bazel's own figure, under its own name. It excludes failed
                // retries, and understated real wall time by 13x on a measured
                // six-attempt test, so it is never used as the elapsed time.
                .field(test.bazelReportedDurationMicros().isPresent()
                        ? Inspection.Field.of("Bazel's reported duration",
                                EntityFormat.duration(test.bazelReportedDurationMicros())
                                        + " (excludes failed retries)")
                        : Inspection.Field.unknown("Bazel's reported duration",
                                "not reported"))
                .field(test.timeoutSeconds().isPresent()
                        ? Inspection.Field.of("Timeout",
                                EntityFormat.duration(test.timeoutSeconds().getAsLong() * 1_000_000L))
                        : Inspection.Field.unknown("Timeout",
                                "only reported under `bazel test`"));

        if (attempts.isEmpty()) {
            builder.section("Attempts").field(Inspection.Field.unknown(
                    "Attempts",
                    "no testResult event arrived for this target; it may not have been run"));
        } else {
            builder.section("Attempts");
            for (TestAttemptRow attempt : attempts) {
                builder.field(new Inspection.Field(
                        attempt.describe(),
                        Optional.of(describe(attempt)),
                        Optional.empty()));
            }
        }

        if (!logs.isEmpty()) {
            builder.section("Logs");
            for (TestQueries.TestLog log : logs) {
                // The URI, not the content: these point into the output base,
                // which the next build removes. Saying where it was beats
                // offering a link that silently does nothing.
                builder.field(log.name().orElse(log.summaryStatus().orElse("log")), log.uri());
            }
        }
        return builder.build();
    }

    private static String describe(TestAttemptRow attempt) {
        StringBuilder text = new StringBuilder(attempt.status().name());
        if (attempt.durationMicros().isPresent()) {
            text.append(", ").append(EntityFormat.duration(attempt.durationMicros()));
        }
        attempt.strategy().ifPresent(strategy -> text.append(", ").append(strategy));
        if (attempt.exitCode().isPresent()) {
            text.append(", exit ").append(attempt.exitCode().getAsInt());
        }
        if (attempt.cachedLocally()) {
            // Worth saying: a cached attempt replays timestamps from before the
            // build began, so its start time is not a time during this build.
            text.append(", cached (timestamps predate this build)");
        }
        return text.toString();
    }

    private static Inspection.Field counted(String name, Optional<String> value) {
        return EntityFormat.field(name, value);
    }
}

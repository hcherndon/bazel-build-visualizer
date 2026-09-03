package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.util.List;
import java.util.Optional;

/**
 * Describes a test for the shared inspector: the verdict, then every attempt.
 *
 * <p>The attempts are the point. A test that passed on its second try shows {@code FLAKY} at the
 * top and a {@code FAILED} attempt beneath it, so the evidence for the verdict is on the same
 * screen as the verdict — and a test whose attempts were cached says so, because a cached attempt's
 * timestamps come from before this build started and would otherwise look like a timeline error.
 */
public final class TestInspection {

  private TestInspection() {}

  public static Inspection of(
      TestRow test, List<TestAttemptRow> attempts, List<TestQueries.TestLog> logs) {
    return of(test, attempts, logs, List.of());
  }

  /**
   * The test, its BEP attempts, its logs, and the spawns the execution log recorded for it.
   *
   * <p>The spawns are a separate list from the attempts and are not merged into them. They are
   * different measurements of overlapping things: a BEP {@code testResult} is one attempt of one
   * shard, and a spawn is one subprocess — and a test produces two spawns per attempt, the second
   * being XML generation which exits 0 even when the test failed (K3 in
   * docs/exec-log-and-profile.md). Pairing them up would need a rule nothing in either source
   * supports, so both are shown and neither is presented as the other's detail.
   */
  public static Inspection of(
      TestRow test,
      List<TestAttemptRow> attempts,
      List<TestQueries.TestLog> logs,
      List<AttemptRow> spawns) {
    Inspection.Builder builder =
        new Inspection.Builder(test.label())
            .subtitle(test.overallStatus().name())
            .sourceEvent(test.bepEventId());
    builder.ref(new EntityRef.TargetLabel(test.label()));
    test.bepEventId().ifPresent(eventId -> builder.ref(new EntityRef.EventId(eventId)));

    builder
        .section("Result")
        .field("Status", test.overallStatus().name())
        .field(EntityFormat.field("Configuration", test.configurationId()))
        .field(
            counted(
                "Runs",
                test.totalRunCount().isPresent()
                    ? Optional.of(EntityFormat.count(test.totalRunCount()))
                    : Optional.empty()))
        .field(
            test.shardCount().isPresent()
                ? Inspection.Field.of("Shards", EntityFormat.count(test.shardCount()))
                // Absent means "not sharded". A zero here would put a
                // phantom zero-shard test into any histogram over it.
                : Inspection.Field.of("Shards", "not sharded"))
        .field("Cached runs", Integer.toString(test.totalNumCached()));

    builder
        .section("Timing")
        .field(
            test.wallMicros().isPresent()
                ? Inspection.Field.of(
                    "Elapsed across attempts", EntityFormat.duration(test.wallMicros()))
                : Inspection.Field.unknown(
                    "Elapsed across attempts", "no attempt reported both a start and a duration"))
        // Bazel's own figure, under its own name. It excludes failed
        // retries, and understated real wall time by 13x on a measured
        // six-attempt test, so it is never used as the elapsed time.
        .field(
            test.bazelReportedDurationMicros().isPresent()
                ? Inspection.Field.of(
                    "Bazel's reported duration",
                    EntityFormat.duration(test.bazelReportedDurationMicros())
                        + " (excludes failed retries)")
                : Inspection.Field.unknown("Bazel's reported duration", "not reported"))
        .field(
            test.timeoutSeconds().isPresent()
                ? Inspection.Field.of(
                    "Timeout",
                    EntityFormat.duration(test.timeoutSeconds().getAsLong() * 1_000_000L))
                : Inspection.Field.unknown("Timeout", "only reported under `bazel test`"));

    if (attempts.isEmpty()) {
      builder
          .section("Attempts")
          .field(
              Inspection.Field.unknown(
                  "Attempts",
                  "no testResult event arrived for this target; it may not have been run"));
    } else {
      builder.section("Attempts");
      for (TestAttemptRow attempt : attempts) {
        builder.field(
            new Inspection.Field(
                attempt.describe(), Optional.of(describe(attempt)), Optional.empty()));
      }
    }

    if (!logs.isEmpty()) {
      builder.section("Logs");
      for (TestQueries.TestLog log : logs) {
        String name = log.name().orElse(log.summaryStatus().orElse("log"));
        // The URI stays visible and the Open button resolves it only
        // when pressed. A later bazel clean may have removed it; that
        // becomes an explicit modeless error rather than a dead link.
        builder.file(name, log.uri(), FileLink.testLog(name, log.uri()));
      }
    }
    addSpawns(builder, spawns);
    return builder.build();
  }

  /**
   * What the execution log recorded for this test.
   *
   * <p>Shown in log order and numbered, with no attempt to say which one is "the" test run. They
   * disagree about the exit code by design, and choosing between them on the shape of their outputs
   * would be a guess presented as a fact; the verdict above already comes from {@code testSummary},
   * which needs no help.
   */
  private static void addSpawns(Inspection.Builder builder, List<AttemptRow> spawns) {
    if (spawns.isEmpty()) {
      return;
    }
    builder.section("Recorded subprocesses");
    builder.field("Spawns", Integer.toString(spawns.size()));
    if (spawns.size() > 1) {
      builder.field(
          Inspection.Field.of(
              "Note",
              "Bazel runs a test as more than one subprocess -- the test itself, then a"
                  + " step that writes its XML, which succeeds even when the test"
                  + " failed. The pass or fail above comes from Bazel's own test"
                  + " summary, not from these."));
    }
    for (int i = 0; i < spawns.size(); i++) {
      AttemptRow spawn = spawns.get(i);
      builder
          .section("Subprocess " + (i + 1))
          .field(EntityFormat.field("Runner", spawn.runner()))
          .field(
              spawn.exitCode().isPresent()
                  ? Inspection.Field.of("Exit code", Integer.toString(spawn.exitCode().getAsInt()))
                  : Inspection.Field.unknown("Exit code", "not reported"))
          .field(EntityFormat.field("Status", spawn.status()))
          .field(EntityFormat.durationField("Elapsed", spawn.elapsed()));
      if (spawn.unproducedOutputs() > 0) {
        builder.field("Declared but not produced", Long.toString(spawn.unproducedOutputs()));
      }
    }
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

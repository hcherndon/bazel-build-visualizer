package com.holtherndon.bazelviz.ui.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Describes one execution-log attempt for the shared inspector.
 *
 * <p>Pure, for the same reason {@code ActionInspection} is: the awkward decisions here — what to
 * say when there is a duration and no start, how to word an ambiguous correlation, which timing
 * components to show — are exactly the places the tool could quietly say something untrue, and they
 * are testable without a database or a screen.
 */
public final class AttemptInspection {

  private AttemptInspection() {}

  public static Inspection of(AttemptRow row) {
    Inspection.Builder builder = new Inspection.Builder(title(row)).subtitle(subtitle(row));

    builder
        .section("Execution")
        .field(EntityFormat.field("Runner", row.runner()))
        .field("Cache hit", row.cacheHit() ? "yes" : "no")
        .field(
            row.exitCode().isPresent()
                ? Inspection.Field.of("Exit code", Integer.toString(row.exitCode().getAsInt()))
                : Inspection.Field.unknown("Exit code", "this spawn's record carries no exit code"))
        .field(EntityFormat.field("Status", row.status()));

    timingSection(builder, row);
    correlationSection(builder, row);
    outputsSection(builder, row);

    builder
        .section("Resources")
        .field(EntityFormat.field("Input bytes", optionalText(row.inputBytes())))
        .field(EntityFormat.field("Input files", optionalText(row.inputFiles())))
        .field(EntityFormat.field("Peak memory", optionalText(row.memoryPeakBytes())))
        .field(EntityFormat.field("Action cache digest", row.digestHash()));

    return builder.build();
  }

  /**
   * The timing breakdown.
   *
   * <p>Only the components that were measured. A spawn that ran locally never queued, never
   * uploaded and never fetched, so rows reading "Queue: 0ms" would be answers to questions nobody
   * asked and would suggest measurements that were not taken.
   *
   * <p>The remainder between the total and the parts is shown as unaccounted rather than folded
   * into execution — {@code total_time} is measured separately from its components and the
   * difference is real.
   */
  private static void timingSection(Inspection.Builder builder, AttemptRow row) {
    builder.section("Timing").field(EntityFormat.durationField("Total", row.elapsed()));

    // A duration with no position is the whole story on Bazel 6.5.0, which
    // never reports a spawn's start (S2).
    builder.field(
        row.startMicros().isPresent()
            ? Inspection.Field.of("Start", EntityFormat.count(row.startMicros()) + " µs")
            : Inspection.Field.unknown(
                "Start",
                row.startUnknownReason().orElse("this spawn's record carries no start time")));

    List<AttemptRow.Timing.Component> measured = row.timing().measuredComponents();
    if (measured.isEmpty()) {
      builder.field(
          Inspection.Field.unknown(
              "Breakdown", "this Bazel version reports no per-phase timing for a spawn"));
      return;
    }
    for (AttemptRow.Timing.Component component : measured) {
      builder.field(component.name(), EntityFormat.count(component.micros()) + " µs");
    }
    row.timing()
        .unaccountedMicros()
        .ifPresent(remainder -> builder.field("Unaccounted", remainder + " µs"));
  }

  /**
   * What this attempt is attached to, and why.
   *
   * <p>Plan 24 makes "ambiguous correlations remain visible" an exit criterion, and this is where
   * it becomes visible. The note is always shown when there is one, including for the healthy
   * cases, because "this is a test execution and tests attach to tests" is information the reader
   * needs in order not to go looking for a missing action.
   */
  private static void correlationSection(Inspection.Builder builder, AttemptRow row) {
    builder.section("Correlation").field("Attached to", describe(row.correlation()));
    row.correlationNote().ifPresent(note -> builder.field("Why", note));
    if (row.actionId().isPresent()) {
      builder.field("Action", "#" + row.actionId().getAsLong());
    }
  }

  private static void outputsSection(Inspection.Builder builder, AttemptRow row) {
    builder.section("Outputs").field("Produced", Long.toString(row.producedOutputs()));
    if (row.unproducedOutputs() > 0) {
      // Declared and not made. On 7.6.1 a failing test's entire output
      // list is these, and a spawn showing none of them is
      // indistinguishable from one that declared no outputs (K2).
      builder.field("Declared but not produced", Long.toString(row.unproducedOutputs()));
    }
  }

  static String describe(AttemptCorrelation correlation) {
    return switch (correlation) {
      case MATCHED_BY_OUTPUT -> "an action, matched by its primary output";
      case MATCHED_BY_TEST_LABEL -> "a test, matched by label";
      case AMBIGUOUS -> "nothing — more than one action matched";
      case UNMATCHED -> "nothing — no action matched";
      case NO_ACTION_EXPECTED -> "nothing, and nothing was expected";
    };
  }

  private static String title(AttemptRow row) {
    return row.label().orElseGet(() -> row.mnemonic().orElse("spawn"));
  }

  private static String subtitle(AttemptRow row) {
    String mnemonic = row.mnemonic().orElse("spawn");
    String runner = row.runner().map(value -> " · " + value).orElse("");
    return mnemonic + runner + (row.cacheHit() ? " · cache hit" : "");
  }

  private static Optional<String> optionalText(OptionalLong value) {
    return value.isPresent() ? Optional.of(Long.toString(value.getAsLong())) : Optional.empty();
  }
}

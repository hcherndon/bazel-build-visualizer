package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One row of the actions table.
 *
 * <p>Every optional here is a fact about the build rather than a gap in the query. {@code label} is
 * absent for the workspace-status action on three of the four supported Bazel versions; {@code
 * startMicros} is absent on two of them entirely; {@code spawnExitCode} is absent unless the action
 * failed as a spawn that reported one.
 *
 * @param id the row id, which is also the keyset anchor
 * @param primaryOutput the action's identity, from its event id
 * @param durationUnknownReason why no duration is derivable, when none is
 * @param commandLine the argv as a JSON array, absent for every action that ran no spawn
 * @param bepEventId the event this row came from, for the inspector
 */
public record ActionRow(
    long id,
    String primaryOutput,
    Optional<String> label,
    Optional<String> mnemonic,
    ActionOutcome outcome,
    OptionalLong startMicros,
    OptionalLong durationMicros,
    Optional<String> durationUnknownReason,
    OptionalInt bazelExitCode,
    OptionalInt spawnExitCode,
    Optional<String> failureCategory,
    Optional<String> failureMessage,
    Optional<String> configurationId,
    Optional<String> commandLine,
    OptionalLong bepEventId,
    Execution execution) {

  /**
   * What the execution log says about this action, when one was imported.
   *
   * <p>{@code runner} and {@code cacheHit} are present only when exactly one spawn was attached.
   * With more than one they are absent on purpose: showing the first would present one spawn's
   * answer as the action's, and an action with two spawns that ran differently has no single
   * answer. {@code attempts} is how the column says which case it is.
   */
  public record Execution(long attempts, Optional<String> runner, Optional<Boolean> cacheHit) {

    /** An action no execution log has anything to say about. */
    public static Execution none() {
      return new Execution(0, Optional.empty(), Optional.empty());
    }

    /** True when no spawn was attached — the normal case for most actions. */
    public boolean isAbsent() {
      return attempts == 0;
    }

    /** True when several spawns were attached and none of them speaks for the action. */
    public boolean isAmbiguous() {
      return attempts > 1;
    }
  }

  public ActionRow {
    Objects.requireNonNull(primaryOutput, "primaryOutput");
    Objects.requireNonNull(outcome, "outcome");
  }

  /**
   * The duration, carrying why it is unavailable when it is (plan 11.4).
   *
   * <p>Built here rather than in the view so that every place showing a duration shows the same
   * thing: a number, or an explanation, and never a zero standing in for either.
   */
  public Measured<Long> duration() {
    if (durationMicros.isPresent()) {
      return Measured.of(durationMicros.getAsLong(), DataSource.BEP);
    }
    String reason = durationUnknownReason.orElse(ActionTiming.NOT_REPORTED);
    return Measured.unknown(DataSource.BEP, Completeness.UNAVAILABLE, explain(reason));
  }

  /**
   * The process's exit code, when Bazel reported one.
   *
   * <p>Does <em>not</em> fall back to {@link #bazelExitCode}. That field was measured as 1 for
   * every failure regardless of what the command returned, and a failure whose {@code
   * failureDetail} carries no spawn sub-message — a genrule that produced no output, for instance —
   * has no process exit code at all: the command there exited 0. Showing 1 would state a number the
   * build did not produce, for a process that succeeded.
   *
   * <p>Bazel's own field is kept and shown beside it in the inspector, under a name that says whose
   * it is.
   */
  public OptionalInt processExitCode() {
    return spawnExitCode;
  }

  /** The output's file name, for a column too narrow for the path. */
  public String outputFileName() {
    int slash = primaryOutput.lastIndexOf('/');
    return slash < 0 ? primaryOutput : primaryOutput.substring(slash + 1);
  }

  private static String explain(String reason) {
    return switch (reason) {
      // Deliberately not "this Bazel version does not report action
      // timestamps". That is true on 6.5.0 and 7.6.1, where no action has
      // them, and false on 8.4.1 and 9.2.0, where roughly a third of
      // actions lack them because they ran no spawn. The row cannot tell
      // the two apart, so it says only what it knows.
      case ActionTiming.NOT_REPORTED -> "Bazel reported no start or end time for it";
      case ActionTiming.ZERO_LENGTH_SPAN ->
          "Bazel reported the same time for start and end, which it does for every"
              + " action on 8.4.x regardless of how long the action took";
      case ActionTiming.PARTIAL -> "only one of the two timestamps arrived";
      case ActionTiming.END_BEFORE_START -> "the reported end precedes the reported start";
      case ActionTiming.INVALID_REPORTED_VALUE ->
          "at least one reported timestamp was outside protobuf's valid range";
      default -> reason;
    };
  }
}

package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The complete answer to "what will actually be run, and why" (plan 7.1 {@code
 * InstrumentationPlanner} output, rendered by plan 4.3).
 *
 * <p>ADR-007 is the whole point of this record: the user sees the command they typed and the
 * command that will run, side by side, with a line of explanation for every difference. A plan is
 * inert — building one runs nothing — so it can be shown, edited, vetoed and re-planned before any
 * process starts.
 *
 * <h2>The launch gate</h2>
 *
 * <p>{@link #canLaunch()} is the single place plan 4.3's "do not launch until mandatory conflicts
 * are resolved" is enforced. Callers ask the plan rather than re-deriving the condition, so a new
 * conflict kind cannot be introduced without every launcher honouring it.
 *
 * @param original the command as the user gave it, untouched
 * @param effective the command that will be executed
 * @param addedFlags every flag the planner proposes, applied or not
 * @param replacedFlags user options this plan overrides, each with its approval
 * @param auxiliaryCommands commands scheduled around the primary invocation
 * @param conflicts everything requiring a decision or a warning
 * @param warnings non-blocking notes that are not tied to a specific flag
 * @param errors reasons this plan cannot produce a session at all
 * @param expectedOutputs files the build is expected to write, so the coordinator knows what to
 *     look for and the user knows what is being created
 * @param sourceAvailability what the resulting session will contain
 * @param preset the preset this plan was built from
 */
public record InstrumentationPlan(
    BazelCommand original,
    BazelCommand effective,
    List<AddedFlag> addedFlags,
    List<ReplacedFlag> replacedFlags,
    List<AuxiliaryCommandPlan> auxiliaryCommands,
    List<PlanConflict> conflicts,
    List<String> warnings,
    List<String> errors,
    List<Path> expectedOutputs,
    SourceAvailability sourceAvailability,
    CapturePreset preset) {

  public InstrumentationPlan {
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(effective, "effective");
    addedFlags = List.copyOf(addedFlags);
    replacedFlags = List.copyOf(replacedFlags);
    auxiliaryCommands = List.copyOf(auxiliaryCommands);
    conflicts = List.copyOf(conflicts);
    warnings = List.copyOf(warnings);
    errors = List.copyOf(errors);
    expectedOutputs = List.copyOf(expectedOutputs);
    Objects.requireNonNull(sourceAvailability, "sourceAvailability");
    Objects.requireNonNull(preset, "preset");
  }

  /** Conflicts that block the launch until the user decides. */
  public List<PlanConflict> mandatoryConflicts() {
    return conflicts.stream().filter(PlanConflict::mandatory).toList();
  }

  /**
   * Whether this plan may be launched. False while any mandatory conflict is unresolved, and false
   * when the plan recorded an error — an error means the planner could not build a command that
   * would capture anything.
   */
  public boolean canLaunch() {
    return errors.isEmpty() && mandatoryConflicts().isEmpty() && !effective.isEmpty();
  }

  /** The flags that will really be on the command line. */
  public List<AddedFlag> appliedFlags() {
    return addedFlags.stream().filter(AddedFlag::isApplied).toList();
  }

  /**
   * The injected arguments as plain strings, for {@code manifest.injectedFlags} (plan 10.3). Only
   * the applied ones: the manifest records what ran, not what was considered.
   */
  public List<String> injectedArgv() {
    return appliedFlags().stream().map(AddedFlag::argv).toList();
  }

  /** True when the plan changes nothing about the user's command. */
  public boolean isPassThrough() {
    return appliedFlags().isEmpty() && replacedFlags.isEmpty();
  }
}

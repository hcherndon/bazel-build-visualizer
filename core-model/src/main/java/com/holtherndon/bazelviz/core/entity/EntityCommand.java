package com.holtherndon.bazelviz.core.entity;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What one build event says about the entities in a build — the plan's "translation into
 * normalization commands" (plan, {@code bep-codec}).
 *
 * <h2>Why a command vocabulary rather than direct writes</h2>
 *
 * <p>Everything this project knows about how Bazel actually behaves has to live somewhere it can be
 * tested without a database. {@link EntityTranslator} turns events into these records with no I/O
 * at all, so every measured quirk — absent labels, a duplicated primary output, an {@code aborted}
 * riding four different id kinds — is checkable against a hand-built {@code BuildEvent} in a unit
 * test. The writer's job is then only to make rows, and the two failure modes stay separable: a
 * wrong reading of Bazel and a wrong SQL statement do not look alike.
 *
 * <p>It also keeps the storage layer free of proto types, which matters because the same commands
 * will later be produced from an execution log and from aquery (Phases 4 and 5) rather than from
 * the BEP.
 *
 * <h2>Absent is spelled {@code Optional}, and it means absent</h2>
 *
 * <p>Where a field is optional here, its emptiness is a fact about the build, not about the parser.
 * An action with no label really had none. A duration that is empty was never measured. Nothing in
 * this file substitutes a zero or an empty string for a missing observation (plan 11.4).
 *
 * <p>Conversely, where a field is a plain {@code boolean} or {@code long}, the wire's proto3
 * default <em>is</em> the value: an absent {@code success} means the thing failed, and an absent
 * {@code isTool} means false.
 */
public sealed interface EntityCommand {

  /**
   * The build began. Carries what the user needs in order to read every NULL elsewhere: which Bazel
   * this was, and what it was asked to do.
   */
  record InvocationStarted(
      String uuid,
      String buildToolVersion,
      String command,
      String workingDirectory,
      String workspaceDirectory,
      String optionsDescription,
      long serverPid,
      OptionalLong startMicros)
      implements EntityCommand {}

  /**
   * The effective options, after rc files and expansions.
   *
   * @param publishesAllActions whether {@code --build_event_publish_all_actions} was in effect.
   *     Without it Bazel publishes an action event only for failures, so an empty actions table
   *     means "not captured" rather than "nothing ran" — a distinction the view has to be able to
   *     explain.
   */
  record InvocationOptions(boolean publishesAllActions) implements EntityCommand {}

  /**
   * The build ended.
   *
   * @param overallSuccess taken from {@code exitCode.code == 0} rather than from {@code
   *     overallSuccess}, which proto3 omits on failure. The distinction that matters — "finished
   *     and failed" versus "never finished" — comes from whether this command arrives at all.
   */
  record InvocationFinished(
      String exitCodeName, int exitCode, boolean overallSuccess, OptionalLong finishMicros)
      implements EntityCommand {}

  /**
   * A configuration was described.
   *
   * <p>Keyed on {@code bepId} alone. Two of these were measured with byte-identical payloads and
   * different ids, so any content-based deduplication merges configurations that are genuinely
   * distinct.
   */
  record ConfigurationDeclared(
      String bepId,
      String mnemonic,
      String platformName,
      String cpu,
      boolean tool,
      Map<String, String> makeVariables)
      implements EntityCommand {

    public ConfigurationDeclared {
      Objects.requireNonNull(bepId, "bepId");
      makeVariables = Map.copyOf(makeVariables);
    }
  }

  /**
   * A target finished analysis.
   *
   * <p>There is no configuration here, and that is the wire's doing: {@code TargetConfiguredId}
   * carries a label and an aspect and nothing else. It is also why this command must create a row
   * rather than wait — a build interrupted during analysis produced these and no completions.
   */
  record TargetConfigured(
      String label,
      Optional<String> aspect,
      Optional<String> targetKind,
      Optional<String> testSize,
      List<String> tags)
      implements EntityCommand {

    public TargetConfigured {
      Objects.requireNonNull(label, "label");
      tags = List.copyOf(tags);
    }
  }

  /**
   * A target finished building in one configuration.
   *
   * @param success absent {@code success} means failed, never "not yet finished" — the literal
   *     {@code false} never appears on the wire
   * @param tags the completion list, which from Bazel 7.6.1 includes synthetic tags the user never
   *     wrote; kept separate from the analysis-time list rather than merged
   * @param outputGroups may be empty on a failed target
   * @param directoryOutputs tree artifacts, whose files also appear inside the output groups;
   *     adding both double-counts
   */
  record TargetCompleted(
      String label,
      Optional<String> aspect,
      String configurationId,
      boolean success,
      List<String> tags,
      OptionalLong testTimeoutSeconds,
      List<OutputGroupRef> outputGroups,
      List<FileRef> directoryOutputs,
      Optional<FailureInfo> failure)
      implements EntityCommand {

    public TargetCompleted {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(configurationId, "configurationId");
      tags = List.copyOf(tags);
      outputGroups = List.copyOf(outputGroups);
      directoryOutputs = List.copyOf(directoryOutputs);
    }
  }

  /**
   * A named set of files was defined.
   *
   * <p>Both lists can be non-empty on the same set — 10 of 19 sets in one measured build had files
   * and children — so neither may be treated as exclusive of the other.
   */
  record DepsetDeclared(String bepId, List<String> childIds, List<FileRef> files)
      implements EntityCommand {

    public DepsetDeclared {
      Objects.requireNonNull(bepId, "bepId");
      childIds = List.copyOf(childIds);
      files = List.copyOf(files);
    }
  }

  /**
   * An action was executed.
   *
   * @param primaryOutput from the event <em>id</em>. The payload field of the same name is absent
   *     on every failure and on some successes, so it can be neither an identity nor a success
   *     proxy.
   * @param label absent for the workspace-status action on Bazel 6.5.0, 7.6.1 and 8.4.1. Absent,
   *     never empty.
   * @param bazelExitCode {@code ActionExecuted.exit_code}, which was measured as 1 for every
   *     failure regardless of the process's real code. Kept as Bazel's status field; the code to
   *     show the user is in {@code failure}.
   */
  record ActionCompleted(
      String primaryOutput,
      Optional<String> label,
      String configurationId,
      Optional<String> mnemonic,
      boolean success,
      OptionalInt bazelExitCode,
      Optional<FailureInfo> failure,
      ActionTiming timing,
      List<String> commandLine,
      Optional<String> stdoutUri,
      Optional<String> stderrUri)
      implements EntityCommand {

    public ActionCompleted {
      Objects.requireNonNull(primaryOutput, "primaryOutput");
      Objects.requireNonNull(configurationId, "configurationId");
      Objects.requireNonNull(timing, "timing");
      commandLine = List.copyOf(commandLine);
    }
  }

  /**
   * One attempt at running one shard of one run of a test.
   *
   * <p>{@code run}, {@code shard} and {@code attempt} are all present and 1-based on every measured
   * version. {@code status} here is a narrower domain than a summary's: {@code FLAKY} describes a
   * target's history and never an attempt.
   *
   * @param startMicros a cached attempt legitimately replays timestamps from before the build began
   *     — measured 2.1 s earlier — so a timeline must treat these rows deliberately rather than
   *     plot them naively
   * @param exitCode absent on Bazel 6.5.0 and 7.6.1, which cannot report it, and absent on
   *     8.4.1/9.2.0 when it was zero
   */
  record TestAttemptCompleted(
      String label,
      String configurationId,
      int run,
      int shard,
      int attempt,
      TestOutcome status,
      boolean cachedLocally,
      OptionalLong startMicros,
      OptionalLong durationMicros,
      OptionalInt exitCode,
      Optional<String> strategy,
      List<TestLogRef> outputs)
      implements EntityCommand {

    public TestAttemptCompleted {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(configurationId, "configurationId");
      Objects.requireNonNull(status, "status");
      outputs = List.copyOf(outputs);
    }
  }

  /**
   * A test target's verdict.
   *
   * <p>This is the only trustworthy source of pass/fail: the target's own {@code completed.success}
   * was measured {@code true} for a test that failed.
   *
   * @param attemptCount the maximum attempts any (run, shard) needed. Not a retry count — it equals
   *     {@code runCount} for a healthy multi-run test.
   * @param shardCount absent when the test was not sharded. Zero would put a phantom zero-shard
   *     test into a histogram.
   * @param bazelReportedDurationMicros {@code totalRunDuration} verbatim, which excludes failed
   *     retries and understated real wall time by 13x on a measured six-attempt test. Shown as
   *     Bazel's figure, never used as a timeline bound.
   */
  record TestSummarized(
      String label,
      String configurationId,
      TestOutcome overallStatus,
      OptionalInt totalRunCount,
      OptionalInt runCount,
      OptionalInt shardCount,
      OptionalInt attemptCount,
      int totalNumCached,
      OptionalLong firstStartMicros,
      OptionalLong lastStopMicros,
      OptionalLong bazelReportedDurationMicros,
      List<TestLogRef> logs)
      implements EntityCommand {

    public TestSummarized {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(configurationId, "configurationId");
      Objects.requireNonNull(overallStatus, "overallStatus");
      logs = List.copyOf(logs);
    }
  }

  /**
   * Bazel's own counters.
   *
   * <p>Every scalar is optional because the sub-messages are version-gated: network and Skyframe
   * counters are 8.4.1+, {@code criticalPathTime} is 9.2.0 only, {@code executionPhaseTimeInMs} is
   * absent on 6.5.0, and on a loading or analysis failure the target and package sub-messages
   * arrive empty.
   */
  record BuildMetricsReported(
      OptionalLong actionsCreated,
      OptionalLong actionsExecuted,
      OptionalLong actionCacheHits,
      OptionalLong actionCacheMisses,
      OptionalLong targetsLoaded,
      OptionalLong targetsConfigured,
      OptionalLong packagesLoaded,
      OptionalLong wallTimeMillis,
      OptionalLong cpuTimeMillis,
      OptionalLong analysisPhaseMillis,
      OptionalLong executionPhaseMillis,
      OptionalLong actionsStartMillis,
      OptionalLong criticalPathMicros,
      List<MnemonicWork> mnemonics,
      List<RunnerWork> runners,
      List<CacheMiss> cacheMisses,
      List<Garbage> garbage)
      implements EntityCommand {

    public BuildMetricsReported {
      mnemonics = List.copyOf(mnemonics);
      runners = List.copyOf(runners);
      cacheMisses = List.copyOf(cacheMisses);
      garbage = List.copyOf(garbage);
    }
  }

  /**
   * Something did not complete.
   *
   * <p>The same payload rides {@code targetConfigured}, {@code targetCompleted}, {@code
   * unconfiguredLabel} and {@code configuredLabel} ids, and which one carries an analysis failure
   * changed between 6.5.0 and 7.6.1 — so {@code idKind} is recorded rather than assumed, and the
   * failed set is the union across all of them.
   *
   * @param reason the enum constant's name; {@code UNKNOWN} when the stream gave none, never
   *     defaulted to a plausible one
   */
  record TargetAborted(
      String idKind,
      Optional<String> label,
      Optional<String> configurationId,
      String reason,
      String description)
      implements EntityCommand {

    public TargetAborted {
      Objects.requireNonNull(idKind, "idKind");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(description, "description");
    }
  }

  /**
   * The stream reached its end marker.
   *
   * <p>The only trustworthy signal that a capture is whole. "Saw {@code buildFinished}" and "saw
   * {@code buildMetrics}" each declare premature completion on half the supported versions, and the
   * event that carries the flag changed between 7.6.1 and 8.4.1 — so the flag is what is keyed on,
   * not the event holding it.
   *
   * <p>Its absence matters more than its presence: {@code aborted} events arrive after {@code
   * buildFinished}, so a stream that stopped early is missing the whole failed and skipped target
   * list, and the overview has to be able to say so.
   */
  record StreamEnded() implements EntityCommand {}

  /**
   * A progress event carried console output.
   *
   * <p>Only the sizes: the bytes stay in the journal, which is the source of truth (ADR-004), and
   * this exists so the failures view can find the events worth reading without scanning every one.
   * A syntax error produces thirteen events, zero structured diagnostics, and the compiler's actual
   * message only here.
   */
  record ProgressOutputSeen(int stdoutBytes, int stderrBytes) implements EntityCommand {}

  // --- shapes used by the commands above --------------------------------

  /** An output group and the file set at its root. */
  record OutputGroupRef(String name, Optional<String> rootDepsetId, boolean incomplete) {
    public OutputGroupRef {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(rootDepsetId, "rootDepsetId");
    }
  }

  /**
   * A test log.
   *
   * @param name {@code test.log} or {@code test.xml} when it came from an attempt; absent when it
   *     came from a summary, whose Files carry a uri and nothing else
   */
  record TestLogRef(Optional<String> name, String uri, Optional<String> summaryStatus) {
    public TestLogRef {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(uri, "uri");
      Objects.requireNonNull(summaryStatus, "summaryStatus");
    }
  }

  /**
   * Work attributed to one mnemonic.
   *
   * <p>On Bazel 6.5.0 and 7.6.1 a mnemonic whose actions were all cache hits is absent from this
   * breakdown entirely, so a chart built from it is complete only on 8.4.1+.
   */
  record MnemonicWork(String mnemonic, OptionalLong created, OptionalLong executed) {}

  /** How many actions ran under one runner. {@code total} is Bazel's own synthetic row. */
  record RunnerWork(String name, Optional<String> execKind, int actionCount, boolean total) {}

  /** One reason the action cache missed. */
  record CacheMiss(String reason, long count) {}

  /** Garbage collected, by collector. Not a heap size. */
  record Garbage(String type, long collectedBytes) {}
}

package com.holtherndon.bazelviz.enrich.execlog;

import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.Protos.SpawnMetrics;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.Digest;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.EnvVar;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.OutputRef;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.SpawnTiming;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Reads the binary and JSON-free execution logs: a bare sequence of length-delimited {@link
 * SpawnExec} messages.
 *
 * <h2>How this differs from the compact reader</h2>
 *
 * <p>There are no shared entries and no ids: every spawn repeats its inputs and outputs in full,
 * which is why the format is 3.5x the size for identical content (S4). So this parser synthesises
 * path ids of its own, emitting a {@link EnrichmentCommand.PathDeclared} before the spawn that
 * references it. The writer's id map does not care where the ids came from.
 *
 * <p>There is also no invocation header, so a binary log cannot be checked against the session it
 * claims to describe (V3). This parser emits no {@code InvocationHeaderSeen} and the importer
 * records the log as unverified.
 *
 * <p>And it may be a Bazel 6.5.0 log, in which case timing lives in fields the current descriptor
 * reserves — see {@link LegacySpawnFields}.
 */
public final class BinaryExecLogParser {

  private final Consumer<EnrichmentCommand> sink;
  private final EnvironmentRedactor redactor;
  private long nextPathId = 1;
  private long entryIndex;
  private long spawnCount;
  private boolean sawLegacyFields;

  public BinaryExecLogParser(Consumer<EnrichmentCommand> sink, EnvironmentRedactor redactor) {
    this.sink = sink;
    this.redactor = redactor;
  }

  /**
   * Reads every spawn in {@code stream}.
   *
   * @return how many spawns were seen
   */
  public long parse(InputStream stream) throws IOException {
    SpawnExec spawn;
    while ((spawn = SpawnExec.parseDelimitedFrom(stream)) != null) {
      if (LegacySpawnFields.looksLegacy(spawn)) {
        sawLegacyFields = true;
      }
      sink.accept(commandFor(spawn));
      spawnCount++;
      entryIndex++;
    }
    return spawnCount;
  }

  /**
   * True when at least one spawn carried Bazel 6.5.0's {@code progress_message} or {@code
   * walltime}, which is how a legacy log announces itself.
   */
  public boolean sawLegacyFields() {
    return sawLegacyFields;
  }

  private EnrichmentCommand.SpawnObserved commandFor(SpawnExec spawn) {
    List<OutputRef> outputs = new ArrayList<>();
    for (File output : spawn.getActualOutputsList()) {
      long id = nextPathId++;
      sink.accept(
          new EnrichmentCommand.PathDeclared(
              id,
              output.getPath(),
              OutputRef.Kind.FILE,
              digestOf(output.hasDigest() ? output.getDigest() : null)));
      outputs.add(OutputRef.produced(id, OutputRef.Kind.FILE));
    }
    // listed_outputs the spawn did not actually produce. The compact format
    // calls these invalid_output_path; here they are the set difference,
    // and they matter for the same reason (K2).
    for (String listed : spawn.getListedOutputsList()) {
      boolean produced =
          spawn.getActualOutputsList().stream().anyMatch(actual -> actual.getPath().equals(listed));
      if (!produced) {
        outputs.add(OutputRef.unproduced(listed));
      }
    }

    List<EnvVar> environment = new ArrayList<>(spawn.getEnvironmentVariablesCount());
    spawn
        .getEnvironmentVariablesList()
        .forEach(
            variable -> environment.add(redactor.apply(variable.getName(), variable.getValue())));

    SpawnTiming timing = timingFor(spawn);
    Optional<String> noStart =
        timing.startMicros().isEmpty()
            ? Optional.of(
                LegacySpawnFields.looksLegacy(spawn)
                    ? LegacySpawnFields.noStartReason()
                    : "this spawn's record carries no start time")
            : Optional.empty();

    return new EnrichmentCommand.SpawnObserved(
        entryIndex,
        spawn.getTargetLabel().isEmpty() ? Optional.empty() : Optional.of(spawn.getTargetLabel()),
        spawn.getMnemonic(),
        spawn.getRunner().isEmpty() ? Optional.empty() : Optional.of(spawn.getRunner()),
        spawn.getCacheHit(),
        OptionalInt.of(spawn.getExitCode()),
        spawn.getStatus().isEmpty() ? Optional.empty() : Optional.of(spawn.getStatus()),
        timing,
        noStart,
        outputs,
        environment,
        OptionalLong.empty(),
        OptionalLong.empty(),
        digestOf(spawn.hasDigest() ? spawn.getDigest() : null),
        spawn.getTimeoutMillis() == 0
            ? OptionalLong.empty()
            : OptionalLong.of(spawn.getTimeoutMillis()),
        spawn.getRemotable(),
        spawn.getCacheable(),
        spawn.getRemoteCacheable());
  }

  /**
   * Timing, from whichever of the three places this Bazel put it.
   *
   * <p>A 7.6.1+ log has {@code metrics}. A 6.5.0 log run with {@code
   * --experimental_execution_log_spawn_metrics} also has {@code metrics}, but only {@code
   * total_time} and {@code execution_wall_time} inside it. A 6.5.0 log without the flag has
   * neither, and its only duration is the legacy {@code walltime} at field 17 (S1, S2).
   */
  private static SpawnTiming timingFor(SpawnExec spawn) {
    if (spawn.hasMetrics()) {
      SpawnMetrics metrics = spawn.getMetrics();
      return new SpawnTiming(
          metrics.hasStartTime()
              ? OptionalLong.of(micros(metrics.getStartTime()))
              : OptionalLong.empty(),
          duration(metrics.hasTotalTime(), metrics.getTotalTime()),
          duration(metrics.hasExecutionWallTime(), metrics.getExecutionWallTime()),
          duration(metrics.hasParseTime(), metrics.getParseTime()),
          duration(metrics.hasNetworkTime(), metrics.getNetworkTime()),
          duration(metrics.hasFetchTime(), metrics.getFetchTime()),
          duration(metrics.hasQueueTime(), metrics.getQueueTime()),
          duration(metrics.hasSetupTime(), metrics.getSetupTime()),
          duration(metrics.hasUploadTime(), metrics.getUploadTime()),
          duration(metrics.hasProcessOutputsTime(), metrics.getProcessOutputsTime()),
          duration(metrics.hasRetryTime(), metrics.getRetryTime()),
          positive(metrics.getInputBytes()),
          positive(metrics.getInputFiles()),
          positive(metrics.getMemoryEstimateBytes()),
          positive(metrics.getMeasuredMemoryPeakBytes()));
    }
    OptionalLong legacy = LegacySpawnFields.walltimeMicros(spawn);
    return legacy.isPresent() ? SpawnTiming.durationOnly(legacy.getAsLong()) : SpawnTiming.none();
  }

  private static OptionalLong duration(boolean present, Duration duration) {
    if (!present) {
      return OptionalLong.empty();
    }
    long micros = duration.getSeconds() * 1_000_000L + duration.getNanos() / 1_000L;
    return micros == 0 ? OptionalLong.empty() : OptionalLong.of(micros);
  }

  private static long micros(Timestamp timestamp) {
    return timestamp.getSeconds() * 1_000_000L + timestamp.getNanos() / 1_000L;
  }

  private static OptionalLong positive(long value) {
    return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
  }

  private static Optional<Digest> digestOf(com.google.devtools.build.lib.exec.Protos.Digest d) {
    if (d == null || d.getHash().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Digest(
            d.getHash(),
            d.getSizeBytes(),
            d.getHashFunctionName().isEmpty()
                ? Optional.empty()
                : Optional.of(d.getHashFunctionName())));
  }
}

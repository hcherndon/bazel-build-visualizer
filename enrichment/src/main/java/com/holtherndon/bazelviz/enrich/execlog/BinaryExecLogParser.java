package com.holtherndon.bazelviz.enrich.execlog;

import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.Protos.SpawnMetrics;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.bepcodec.entity.ProtoTimes;
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

  private EnrichmentCommand.SpawnObserved commandFor(SpawnExec spawn) throws IOException {
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
    if (spawn.getTimeoutMillis() < 0) {
      throw malformed("spawn.timeout_millis", "must not be negative");
    }
    Optional<String> noStart =
        timing.startMicros().isEmpty()
            ? Optional.of(
                spawn.hasMetrics() && spawn.getMetrics().hasStartTime()
                    ? "this spawn's record carries a malformed or out-of-range start time"
                    : LegacySpawnFields.looksLegacy(spawn)
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
  private SpawnTiming timingFor(SpawnExec spawn) throws IOException {
    OptionalLong legacy = LegacySpawnFields.walltimeMicros(spawn);
    if (spawn.hasMetrics()) {
      SpawnMetrics metrics = spawn.getMetrics();
      return new SpawnTiming(
          timestamp(metrics.hasStartTime(), metrics.getStartTime(), "metrics.start_time"),
          duration(metrics.hasTotalTime(), metrics.getTotalTime(), "metrics.total_time"),
          duration(
              metrics.hasExecutionWallTime(),
              metrics.getExecutionWallTime(),
              "metrics.execution_wall_time"),
          duration(metrics.hasParseTime(), metrics.getParseTime(), "metrics.parse_time"),
          duration(metrics.hasNetworkTime(), metrics.getNetworkTime(), "metrics.network_time"),
          duration(metrics.hasFetchTime(), metrics.getFetchTime(), "metrics.fetch_time"),
          duration(metrics.hasQueueTime(), metrics.getQueueTime(), "metrics.queue_time"),
          duration(metrics.hasSetupTime(), metrics.getSetupTime(), "metrics.setup_time"),
          duration(metrics.hasUploadTime(), metrics.getUploadTime(), "metrics.upload_time"),
          duration(
              metrics.hasProcessOutputsTime(),
              metrics.getProcessOutputsTime(),
              "metrics.process_outputs_time"),
          duration(metrics.hasRetryTime(), metrics.getRetryTime(), "metrics.retry_time"),
          positive(metrics.getInputBytes()),
          positive(metrics.getInputFiles()),
          positive(metrics.getMemoryEstimateBytes()),
          positive(metrics.getMeasuredMemoryPeakBytes()));
    }
    return legacy.isPresent() ? SpawnTiming.durationOnly(legacy.getAsLong()) : SpawnTiming.none();
  }

  private OptionalLong timestamp(boolean present, Timestamp timestamp, String field)
      throws IOException {
    if (!present) {
      return OptionalLong.empty();
    }
    ProtoTimes.Checked checked = ProtoTimes.checkedTimestampMicros(timestamp);
    if (checked.isInvalid()) {
      throw malformed(field, "is malformed or outside microsecond representation");
    }
    return checked.micros();
  }

  private OptionalLong duration(boolean present, Duration duration, String field)
      throws IOException {
    if (!present) {
      return OptionalLong.empty();
    }
    ProtoTimes.Checked checked = ProtoTimes.checkedNonnegativeDurationMicros(duration);
    if (checked.isInvalid()) {
      throw malformed(field, "is malformed, negative, or outside microsecond representation");
    }
    return checked.micros();
  }

  private IOException malformed(String field, String detail) {
    return new IOException("execution-log entry " + entryIndex + " " + field + " " + detail);
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

package com.holtherndon.bazelviz.enrich.execlog;

import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
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
 * Reads the compact execution log, one entry at a time, and emits commands.
 *
 * <h2>Streaming, and why it has to be</h2>
 *
 * <p>Nothing here holds more than one entry. Path strings are passed through as {@link
 * EnrichmentCommand.PathDeclared} and never accumulated, because a five-million-action build names
 * tens of millions of files and holding those strings would cost more than the rest of the
 * application put together. The writer keeps a primitive id map instead.
 *
 * <p>The format permits this: the proto guarantees "every entry must be serialized after all other
 * entries it references by ID", so a spawn's inputs and outputs have always been declared by the
 * time the spawn arrives. Entries are <em>not</em> guaranteed to be in increasing id order, which
 * is why the writer's map must tolerate gaps.
 */
public final class CompactExecLogParser {

  private final Consumer<EnrichmentCommand> sink;
  private final EnvironmentRedactor redactor;
  private long entryIndex;
  private long spawnCount;

  public CompactExecLogParser(Consumer<EnrichmentCommand> sink, EnvironmentRedactor redactor) {
    this.sink = sink;
    this.redactor = redactor;
  }

  /**
   * Reads every entry in {@code stream}, emitting commands as it goes.
   *
   * @return how many spawn entries were seen; zero is a legitimate result for a build in which
   *     every action hit the action cache (S3)
   */
  public long parse(InputStream stream) throws IOException {
    ExecLogEntry entry;
    while ((entry = ExecLogEntry.parseDelimitedFrom(stream)) != null) {
      handle(entry);
      entryIndex++;
    }
    return spawnCount;
  }

  private void handle(ExecLogEntry entry) throws IOException {
    switch (entry.getTypeCase()) {
      case INVOCATION -> {
        ExecLogEntry.Invocation invocation = entry.getInvocation();
        sink.accept(
            new EnrichmentCommand.InvocationHeaderSeen(
                invocation.getId(),
                invocation.getHashFunctionName(),
                invocation.getWorkspaceRunfilesDirectory(),
                invocation.getSiblingRepositoryLayout()));
      }
      case FILE -> {
        ExecLogEntry.File file = entry.getFile();
        sink.accept(
            new EnrichmentCommand.PathDeclared(
                entry.getId(),
                file.getPath(),
                OutputRef.Kind.FILE,
                digestOf(file.hasDigest() ? file.getDigest() : null)));
      }
      // A Directory is a tree artifact. On 8.4.1 and 9.2.0 it is the only
      // resolvable output a test spawn has, so treating it as noise loses
      // the test (S5).
      case DIRECTORY ->
          sink.accept(
              new EnrichmentCommand.PathDeclared(
                  entry.getId(),
                  entry.getDirectory().getPath(),
                  OutputRef.Kind.DIRECTORY,
                  Optional.empty()));
      case UNRESOLVED_SYMLINK ->
          sink.accept(
              new EnrichmentCommand.PathDeclared(
                  entry.getId(),
                  entry.getUnresolvedSymlink().getPath(),
                  OutputRef.Kind.SYMLINK,
                  Optional.empty()));
      case INPUT_SET -> {
        ExecLogEntry.InputSet set = entry.getInputSet();
        sink.accept(
            new EnrichmentCommand.InputSetDeclared(
                entry.getId(),
                set.getTransitiveSetIdsList().stream().map(Integer::longValue).toList(),
                set.getInputIdsList().stream().map(Integer::longValue).toList()));
      }
      case SPAWN -> {
        spawnCount++;
        sink.accept(spawnOf(entry.getSpawn()));
      }
      // A SymlinkAction is a real action that produced no subprocess, and
      // RunfilesTree/SymlinkEntrySet describe runfiles layout. None of
      // them is an execution, so none becomes an attempt. They are named
      // here rather than falling into a default so that a new entry kind
      // in a future Bazel is a compile error and not a silent skip.
      case SYMLINK_ACTION, SYMLINK_ENTRY_SET, RUNFILES_TREE, TYPE_NOT_SET -> {
        // nothing to record
      }
    }
  }

  private EnrichmentCommand.SpawnObserved spawnOf(ExecLogEntry.Spawn spawn) throws IOException {
    List<OutputRef> outputs = new ArrayList<>(spawn.getOutputsCount());
    for (ExecLogEntry.Output output : spawn.getOutputsList()) {
      switch (output.getTypeCase()) {
        case OUTPUT_ID ->
            outputs.add(OutputRef.produced(output.getOutputId(), OutputRef.Kind.FILE));
        case INVALID_OUTPUT_PATH ->
            outputs.add(OutputRef.unproduced(output.getInvalidOutputPath()));
        case TYPE_NOT_SET -> {
          // an output whose type the log did not state; nothing to add
        }
      }
    }

    List<EnvVar> environment = new ArrayList<>(spawn.getEnvVarsCount());
    spawn
        .getEnvVarsList()
        .forEach(
            variable -> environment.add(redactor.apply(variable.getName(), variable.getValue())));

    SpawnTiming timing = spawn.hasMetrics() ? timingOf(spawn.getMetrics()) : SpawnTiming.none();
    if (spawn.getTimeoutMillis() < 0) {
      throw malformed("spawn.timeout_millis", "must not be negative");
    }

    return new EnrichmentCommand.SpawnObserved(
        entryIndex,
        spawn.getTargetLabel().isEmpty() ? Optional.empty() : Optional.of(spawn.getTargetLabel()),
        spawn.getMnemonic(),
        spawn.getRunner().isEmpty() ? Optional.empty() : Optional.of(spawn.getRunner()),
        spawn.getCacheHit(),
        OptionalInt.of(spawn.getExitCode()),
        spawn.getStatus().isEmpty() ? Optional.empty() : Optional.of(spawn.getStatus()),
        timing,
        startUnknownReason(timing),
        outputs,
        environment,
        spawn.getInputSetId() == 0 ? OptionalLong.empty() : OptionalLong.of(spawn.getInputSetId()),
        spawn.getToolSetId() == 0 ? OptionalLong.empty() : OptionalLong.of(spawn.getToolSetId()),
        digestOf(spawn.hasDigest() ? spawn.getDigest() : null),
        spawn.getTimeoutMillis() == 0
            ? OptionalLong.empty()
            : OptionalLong.of(spawn.getTimeoutMillis()),
        spawn.getRemotable(),
        spawn.getCacheable(),
        spawn.getRemoteCacheable());
  }

  /**
   * Why a compact-log spawn might still have no start.
   *
   * <p>Not a version statement: the compact format only exists from 7.6.1 and every measured spawn
   * there carried {@code start_time}. If one does not, the honest thing to say is that this spawn
   * did not report it.
   */
  private static final String NO_START_REASON = "this spawn's record carries no start time";

  private static Optional<String> startUnknownReason(SpawnTiming timing) {
    if (timing.startMicros().isPresent()) {
      return Optional.empty();
    }
    return Optional.of(NO_START_REASON);
  }

  /**
   * A duration or timestamp is meaningful only when the submessage is present. {@code hasX()} is
   * what distinguishes a reported zero from an absent value. Malformed and negative values fail the
   * enrichment explicitly instead of being stored as either state.
   */
  private SpawnTiming timingOf(SpawnMetrics metrics) throws IOException {
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

  /**
   * The proto documents these as "0 if unavailable", so zero really is absent here and not a
   * measured zero.
   */
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

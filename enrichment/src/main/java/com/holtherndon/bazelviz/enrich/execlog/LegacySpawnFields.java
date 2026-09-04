package com.holtherndon.bazelviz.enrich.execlog;

import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import com.holtherndon.bazelviz.bepcodec.entity.ProtoTimes;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the two fields Bazel 6.5.0 wrote and the current {@code spawn.proto} marks {@code
 * reserved}.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Measured at the wire level on a real 6.5.0 execution log (S1):
 *
 * <table>
 *   <caption>6.5.0 SpawnExec fields against the current descriptor</caption>
 *   <tr><th>Field</th><th>6.5.0 wrote</th><th>Current proto says</th></tr>
 *   <tr><td>9</td><td>{@code progress_message}</td><td>{@code reserved 9}</td></tr>
 *   <tr><td>17</td><td>{@code walltime}, a Duration</td><td>{@code reserved 17}</td></tr>
 * </table>
 *
 * <p>Parsing a 6.5.0 log with the current descriptor succeeds and produces a spawn with no timing
 * and no progress message: both land in the unknown-field set and are dropped. Nothing errors. The
 * result is a table of attempts whose durations are all unknown — indistinguishable from a build
 * whose durations Bazel declined to report, which is exactly the confusion plan rule 11 exists to
 * prevent.
 *
 * <p>So the bytes are recovered from {@link SpawnExec#getUnknownFields()} rather than by vendoring
 * a second copy of the proto. Two fields is less surface than a whole descriptor that would then
 * need keeping in step.
 */
final class LegacySpawnFields {

  /** {@code progress_message} on Bazel 6.5.0. */
  private static final int PROGRESS_MESSAGE_FIELD = 9;

  /** {@code walltime}, a {@code google.protobuf.Duration}, on Bazel 6.5.0. */
  private static final int WALLTIME_FIELD = 17;

  private LegacySpawnFields() {}

  /**
   * True when this spawn carries fields only Bazel 6.5.0 wrote.
   *
   * <p>Used to decide whether the log as a whole is a legacy one, which the importer records so
   * that the UI can say why starts are missing.
   */
  static boolean looksLegacy(SpawnExec spawn) {
    UnknownFieldSet unknown = spawn.getUnknownFields();
    return unknown.hasField(PROGRESS_MESSAGE_FIELD) || unknown.hasField(WALLTIME_FIELD);
  }

  /** The progress message, from field 9, or empty. */
  static Optional<String> progressMessage(SpawnExec spawn) {
    List<ByteString> values =
        spawn.getUnknownFields().getField(PROGRESS_MESSAGE_FIELD).getLengthDelimitedList();
    if (values.isEmpty()) {
      return Optional.empty();
    }
    String text = values.getFirst().toStringUtf8();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  /**
   * The wall time, from field 17, in microseconds.
   *
   * <p>The field holds a {@code Duration} submessage, so its bytes are parsed as one. A zero-length
   * duration reads as unknown rather than as an instantaneous spawn — a spawn that took no
   * measurable time did not take zero time.
   */
  static OptionalLong walltimeMicros(SpawnExec spawn) {
    List<ByteString> values =
        spawn.getUnknownFields().getField(WALLTIME_FIELD).getLengthDelimitedList();
    if (values.isEmpty()) {
      return OptionalLong.empty();
    }
    try {
      Duration duration = Duration.parseFrom(values.getFirst());
      OptionalLong micros = ProtoTimes.durationMicros(duration);
      return micros.isPresent() && micros.getAsLong() > 0 ? micros : OptionalLong.empty();
    } catch (InvalidProtocolBufferException notADuration) {
      // Field 17 held something else. Treating it as a duration anyway
      // would put an invented number on the row.
      return OptionalLong.empty();
    }
  }

  /**
   * Why an attempt from this log has no start.
   *
   * <p>Bazel 6.5.0 never emits {@code start_time}, on any flag setting (S2). Passing {@code
   * --experimental_execution_log_spawn_metrics} adds {@code total_time} and {@code
   * execution_wall_time} in their modern positions and still no start. So the attempt has a length
   * and no position, and this is the sentence that says so.
   */
  static String noStartReason() {
    return "this Bazel version does not report when a spawn started, only how" + " long it took";
  }
}

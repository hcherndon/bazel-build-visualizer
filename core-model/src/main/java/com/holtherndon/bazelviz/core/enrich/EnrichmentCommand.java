package com.holtherndon.bazelviz.core.enrich;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What one execution-log entry or profile event says, in terms the storage layer can write without
 * knowing what produced it.
 *
 * <h2>Same reasoning as {@link com.holtherndon.bazelviz.core.entity.EntityCommand}</h2>
 *
 * <p>The parsers turn bytes into these records with no I/O, so every measured quirk is checkable
 * against a hand-built input in a unit test: Bazel 6.5.0's timing arriving at a field the current
 * proto reserves, a test's second spawn being XML generation rather than a retry, a profile anchor
 * that names itself a finish and holds a start. The writer's job is then only to make rows.
 *
 * <p>Deliberately free of protobuf and of Jackson types. The compact log, the binary log and the
 * JSON log are three encodings of nearly the same thing, and a shared command vocabulary is what
 * stops there being three normalizers.
 *
 * <h2>Absent means absent</h2>
 *
 * <p>Every {@code Optional} here is a fact about the build or about the Bazel version, never about
 * the parser giving up. A 6.5.0 attempt has an empty {@code startMicros} because that version never
 * emits one, and {@link SpawnObserved#startUnknownReason} says so in words rather than leaving the
 * reader to guess (plan 11.4).
 */
public sealed interface EnrichmentCommand {

  // ---------------------------------------------------------- execution log

  /**
   * The compact log's opening entry, which is also the only proof a log belongs to this session.
   *
   * <p>Measured equal to the BEP's {@code started.uuid} 9 times out of 9 across 7.6.1, 8.4.1 and
   * 9.2.0 (V2). The binary and JSON formats have no such record at all, so a log in those formats
   * is unverifiable (V3).
   */
  record InvocationHeaderSeen(
      String buildId,
      String hashFunctionName,
      String workspaceRunfilesDirectory,
      boolean siblingRepositoryLayout)
      implements EnrichmentCommand {}

  /**
   * A path the log named, given an id that later entries reference.
   *
   * <p>The compact log references paths by id rather than repeating them, which is most of why it
   * is 3.5x smaller than the binary format (S4). The parser passes the id through rather than
   * resolving it, so that no component has to hold every path in the build: the writer interns the
   * path once and keeps a primitive {@code long -> long} map. At five million actions the
   * difference is a few hundred megabytes of strings against a flat array.
   *
   * @param kind File, Directory or UnresolvedSymlink — all three carry {@code path = 1} and an
   *     {@code output_id} may reference any of them, so a resolver that assumes files loses every
   *     tree artifact (S5)
   */
  record PathDeclared(long logId, String path, OutputRef.Kind kind, Optional<Digest> digest)
      implements EnrichmentCommand {}

  /**
   * An input set: a node in the DAG of a spawn's inputs.
   *
   * <p>Kept as a DAG, never flattened (plan 10.7, S5). Per the proto, a set may legitimately be
   * serialized more than once under different ids, so two of these can describe the same content.
   *
   * <p>Both lists are ids, resolved by the writer against {@link PathDeclared} and against earlier
   * sets.
   */
  record InputSetDeclared(long logId, List<Long> childSetIds, List<Long> fileLogIds)
      implements EnrichmentCommand {}

  /**
   * One spawn: a single execution of a subprocess on behalf of an action.
   *
   * <p>Not "an action". A test produces exactly two of these on every measured version, the second
   * being XML generation which exits 0 even when the test it describes failed (K3); and two thirds
   * of the actions the BEP reports produce none at all, because they run inside the Bazel server
   * and never spawn anything (K1).
   *
   * @param targetLabel the canonical label, absent when the spawn has none
   * @param runner a free string — {@code "darwin-sandbox"}, {@code "worker"}, {@code "remote"},
   *     {@code "disk cache hit"}. Never parsed into an enum; spawn.proto constrains it to nothing
   *     and says it varies with the dynamic strategy.
   * @param status Bazel's text execution error, empty string on success
   * @param outputs everything the spawn listed, produced or not
   * @param startUnknownReason why there is no start, when there is none
   */
  record SpawnObserved(
      long entryIndex,
      Optional<String> targetLabel,
      String mnemonic,
      Optional<String> runner,
      boolean cacheHit,
      OptionalInt exitCode,
      Optional<String> status,
      SpawnTiming timing,
      Optional<String> startUnknownReason,
      List<OutputRef> outputs,
      List<EnvVar> environment,
      OptionalLong inputSetLogId,
      OptionalLong toolSetLogId,
      Optional<Digest> digest,
      OptionalLong timeoutMillis,
      boolean remotable,
      boolean cacheable,
      boolean remoteCacheable)
      implements EnrichmentCommand {}

  // --------------------------------------------------------------- profile

  /**
   * The profile's {@code otherData} block.
   *
   * <p>{@link ProfileAnchor} carries the version-dependent meaning of the timestamp; this record
   * does not try to interpret it.
   */
  record ProfileHeaderSeen(
      Optional<String> buildId,
      Optional<String> bazelVersion,
      Optional<String> outputBase,
      ProfileAnchor anchor)
      implements EnrichmentCommand {}

  /** A thread's name, from a {@code ph: "M"} metadata event (P6). */
  record ThreadNamed(long threadId, String name, OptionalInt sortIndex)
      implements EnrichmentCommand {}

  /**
   * A build-phase marker.
   *
   * <p>Instant events with no duration, so a phase's end is the next marker's start and the
   * importer derives it. The set of phases differs by version — seven on 6.5.0, five from 7.6.1
   * (P2) — so these are read from the file, never assumed.
   *
   * @param startMicros relative to the anchor, and legitimately negative: {@code Launch Blaze}
   *     begins at −17,000 to −20,000 µs (P3)
   */
  record PhaseMarkerSeen(int ordinal, String name, long startMicros) implements EnrichmentCommand {}

  /**
   * A selected profile span.
   *
   * @param primaryOutput the {@code out} field, which is the same join key BEP uses for action
   *     identity. Present only on {@code action processing} events and only when {@code
   *     --experimental_profile_include_primary_output} was passed (P4).
   */
  record SpanObserved(
      String category,
      String name,
      OptionalLong threadId,
      long startMicros,
      OptionalLong durationMicros,
      Optional<String> primaryOutput,
      Optional<String> targetLabel,
      Optional<String> mnemonic)
      implements EnrichmentCommand {}

  /** One sample of one counter series (P6). */
  record CounterSampled(String series, long atMicros, double value) implements EnrichmentCommand {}

  /**
   * One component of the critical path Bazel itself computed.
   *
   * <p>Stored as written and never joined to an action: its only identifier is a human-readable
   * progress message, which is a presentation string Bazel is free to reword (P5, ADR-009).
   */
  record CriticalPathComponentSeen(
      int ordinal,
      String description,
      OptionalLong startMicros,
      OptionalLong durationMicros,
      OptionalLong threadId)
      implements EnrichmentCommand {}

  // ----------------------------------------------------------------- shapes

  /**
   * The SpawnMetrics breakdown, whole.
   *
   * <p>Every field is separately optional because every one is genuinely absent somewhere: Bazel
   * 6.5.0 emits no metrics submessage at all unless {@code
   * --experimental_execution_log_spawn_metrics} is passed, and never emits {@code startMicros} on
   * any setting (S2).
   *
   * <p>{@code totalMicros} and {@code executionWallMicros} are both kept and are not the same
   * number — the second excludes queue, setup, upload and fetch. The difference is the answer to
   * "where did the time go", which is the whole point of the detailed timing breakdown.
   */
  record SpawnTiming(
      OptionalLong startMicros,
      OptionalLong totalMicros,
      OptionalLong executionWallMicros,
      OptionalLong parseMicros,
      OptionalLong networkMicros,
      OptionalLong fetchMicros,
      OptionalLong queueMicros,
      OptionalLong setupMicros,
      OptionalLong uploadMicros,
      OptionalLong processOutputsMicros,
      OptionalLong retryMicros,
      OptionalLong inputBytes,
      OptionalLong inputFiles,
      OptionalLong memoryEstimateBytes,
      OptionalLong measuredMemoryPeakBytes) {

    /** Nothing measured — the shape of a 6.5.0 spawn without the flag. */
    public static SpawnTiming none() {
      OptionalLong e = OptionalLong.empty();
      return new SpawnTiming(e, e, e, e, e, e, e, e, e, e, e, e, e, e, e);
    }

    /** A duration and nothing else, which is all Bazel 6.5.0 can give. */
    public static SpawnTiming durationOnly(long totalMicros) {
      OptionalLong e = OptionalLong.empty();
      return new SpawnTiming(
          e, OptionalLong.of(totalMicros), e, e, e, e, e, e, e, e, e, e, e, e, e);
    }

    /** True when no field was measured. */
    public boolean isEmpty() {
      return startMicros.isEmpty() && totalMicros.isEmpty() && executionWallMicros.isEmpty();
    }
  }

  /**
   * One of a spawn's outputs.
   *
   * @param kind {@code FILE}, {@code DIRECTORY} or {@code SYMLINK} — the compact log's {@code
   *     output_id} references entries of all three, and a resolver that assumes files loses every
   *     tree artifact (S5)
   * @param produced false for {@code invalid_output_path}: declared and not made. On 7.6.1 a
   *     failing test's entire output list is these, so they are recorded rather than skipped (K2).
   */
  record OutputRef(OptionalLong logId, Optional<String> unproducedPath, Kind kind) {

    public OutputRef {
      if (logId.isPresent() == unproducedPath.isPresent()) {
        throw new IllegalArgumentException(
            "an output is either a reference to a declared path or an"
                + " unproduced path, never both and never neither");
      }
    }

    /** An output the spawn produced, named by the id of its path entry. */
    public static OutputRef produced(long logId, Kind kind) {
      return new OutputRef(OptionalLong.of(logId), Optional.empty(), kind);
    }

    /**
     * An {@code invalid_output_path}: declared and not made.
     *
     * <p>Recorded rather than skipped because on 7.6.1 a failing test's entire output list is
     * these, and a spawn showing none of them is indistinguishable from one that declared no
     * outputs (K2).
     */
    public static OutputRef unproduced(String path) {
      return new OutputRef(OptionalLong.empty(), Optional.of(path), Kind.UNKNOWN);
    }

    /** True when the spawn actually made this output. */
    public boolean wasProduced() {
      return logId.isPresent();
    }

    /** What an output turned out to be. */
    public enum Kind {
      FILE,
      DIRECTORY,
      SYMLINK,
      /** Declared but not produced, so its type was never established. */
      UNKNOWN
    }
  }

  /**
   * An environment variable as it will be stored.
   *
   * @param value absent when withheld by the secret-name patterns of plan 22.2; {@code redacted}
   *     says which of "absent" and "hidden" this is, because a variable set to the empty string and
   *     one whose value was withheld are different facts
   */
  record EnvVar(String name, Optional<String> value, boolean redacted) {
    public EnvVar {
      if (redacted && value.isPresent()) {
        throw new IllegalArgumentException("a redacted variable must not carry its value");
      }
    }
  }

  /** A content digest, kept whole so its hash function stays attached. */
  record Digest(String hash, long sizeBytes, Optional<String> hashFunctionName) {}
}

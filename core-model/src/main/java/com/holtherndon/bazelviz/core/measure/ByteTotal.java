package com.holtherndon.bazelviz.core.measure;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongFunction;

/**
 * A sum of artifact sizes that says what it could not count (plan 11.3).
 *
 * <h2>Why a total is four numbers</h2>
 *
 * <p>Plan 11.3 asks byte totals to retain known bytes, the unknown-artifact count, the
 * unique-artifact count and the duplicate-reference count, and each exists because a single number
 * would be a claim the data does not support:
 *
 * <ul>
 *   <li>Bazel does not report a size for every file. A total that silently omitted them would read
 *       as the whole build's output when it is not, and would shrink as more artifacts arrived
 *       without sizes.
 *   <li>The same artifact is referenced by many depsets. Adding it once per reference inflates the
 *       total by whatever the sharing factor happens to be — which is exactly what a build with
 *       heavy depset reuse maximises.
 * </ul>
 *
 * <p>So the total states what it counted, over how many distinct artifacts, how many it had to
 * skip, and how many references it collapsed. A UI can then say "4.2 GiB across 812 files, 137 of
 * unknown size" rather than "4.2 GiB", which is the difference between a measurement and a guess.
 *
 * @param knownBytes bytes summed from artifacts whose size was reported
 * @param uniqueArtifacts distinct artifacts contributing, counted once each
 * @param unknownSizeArtifacts distinct artifacts with no reported size
 * @param duplicateReferences references collapsed because the artifact had already been counted
 */
public record ByteTotal(
    long knownBytes, long uniqueArtifacts, long unknownSizeArtifacts, long duplicateReferences) {

  public static final ByteTotal EMPTY = new ByteTotal(0, 0, 0, 0);

  public ByteTotal {
    if (knownBytes < 0
        || uniqueArtifacts < 0
        || unknownSizeArtifacts < 0
        || duplicateReferences < 0) {
      throw new IllegalArgumentException(
          "byte totals cannot be negative: "
              + knownBytes
              + ", "
              + uniqueArtifacts
              + ", "
              + unknownSizeArtifacts
              + ", "
              + duplicateReferences);
    }
  }

  /** Counts one artifact of known size, seen for the first time. */
  public ByteTotal plusKnown(long bytes) {
    return new ByteTotal(
        knownBytes + bytes, uniqueArtifacts + 1, unknownSizeArtifacts, duplicateReferences);
  }

  /** Counts one artifact whose size nothing reported. */
  public ByteTotal plusUnknown() {
    return new ByteTotal(
        knownBytes, uniqueArtifacts + 1, unknownSizeArtifacts + 1, duplicateReferences);
  }

  /** Counts a reference to an artifact already counted. */
  public ByteTotal plusDuplicateReference() {
    return new ByteTotal(
        knownBytes, uniqueArtifacts, unknownSizeArtifacts, duplicateReferences + 1);
  }

  public ByteTotal plus(ByteTotal other) {
    Objects.requireNonNull(other, "other");
    return new ByteTotal(
        knownBytes + other.knownBytes,
        uniqueArtifacts + other.uniqueArtifacts,
        unknownSizeArtifacts + other.unknownSizeArtifacts,
        duplicateReferences + other.duplicateReferences);
  }

  /**
   * The total, but only when every artifact reported a size.
   *
   * <p>Empty as soon as one did not — because at that point the sum is a lower bound and presenting
   * it as the total is the "never convert unavailable data to zero" rule broken by omission rather
   * than by substitution.
   */
  public OptionalLong exactTotal() {
    return unknownSizeArtifacts == 0 ? OptionalLong.of(knownBytes) : OptionalLong.empty();
  }

  /** True when something was left out of {@link #knownBytes}. */
  public boolean isPartial() {
    return unknownSizeArtifacts > 0;
  }

  /**
   * One line for a UI, stating the shortfall rather than hiding it.
   *
   * <p>Formatting of the byte figure itself belongs to the view; this decides only what has to be
   * said.
   */
  public String describe(LongFunction<String> formatBytes) {
    String bytes = formatBytes.apply(knownBytes);
    if (uniqueArtifacts == 0) {
      return "no artifacts";
    }
    String base =
        bytes + " across " + uniqueArtifacts + " artifact" + (uniqueArtifacts == 1 ? "" : "s");
    return isPartial() ? base + ", " + unknownSizeArtifacts + " of unknown size" : base;
  }
}

package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TargetOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One target in one configuration, as the targets tree shows it.
 *
 * <p>{@code configurationId} and {@code outcome} are optional together: a target that was
 * configured and never completed has neither, and that is a real state rather than missing data —
 * an interrupt during analysis produces a build made entirely of them.
 *
 * @param analysisOutcome what the target-level events said, which is a different question from what
 *     building it did
 * @param outcome what building it in this configuration did, absent when it never got that far
 */
public record TargetRow(
    long id,
    String label,
    Optional<String> aspect,
    Optional<String> targetKind,
    Optional<String> testSize,
    TargetOutcome analysisOutcome,
    Optional<String> configurationId,
    Optional<TargetOutcome> outcome,
    OptionalLong configuredTargetId,
    OptionalLong bepEventId) {

  public TargetRow {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(analysisOutcome, "analysisOutcome");
  }

  /**
   * The package part of the label, for grouping.
   *
   * <p>Handles the canonical {@code @@repo//pkg:target} form as well as {@code //pkg:target}: an
   * anchor on {@code ^//} would put every external repository's targets into one unnamed bucket.
   */
  public String packagePath() {
    return packageOf(label);
  }

  /** The name after the colon, or the whole label when there is none. */
  public String targetName() {
    int colon = label.indexOf(':');
    return colon < 0 ? label : label.substring(colon + 1);
  }

  /**
   * The package part of a label.
   *
   * <p>The <em>first</em> colon, matching the SQL expression the tree groups by, which uses {@code
   * instr}. A label carries at most one — neither a repository name nor a target name may contain a
   * colon — so first and last are the same today; keeping the two definitions identical means they
   * stay that way if Bazel ever changes its mind.
   */
  static String packageOf(String label) {
    int colon = label.indexOf(':');
    return colon < 0 ? label : label.substring(0, colon);
  }
}

package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.core.source.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A Bazel command scheduled around the primary invocation (plan 8.6), described in the terms plan
 * 4.3 requires the dialog to show.
 *
 * <p>The honesty requirement in plan 8.6 rule 10 is why {@link #carriedOptions} and {@link
 * #droppedOptions} are both here: an {@code aquery} run without the primary command's configuration
 * options describes a <em>different</em> configuration, and a graph derived from it must not be
 * presented as matching the build. The planner records exactly which options it could carry forward
 * and which the auxiliary command rejects, and {@link #configurationMayDiffer()} is what the UI
 * asks before it is allowed to call the graph exact.
 *
 * @param label short name shown in the plan, e.g. {@code aquery}
 * @param purpose one sentence on what it is for
 * @param argv the complete command, ready for {@link ProcessBuilder}
 * @param timing when it runs relative to the primary invocation
 * @param carriedOptions primary-command options reproduced in this command
 * @param droppedOptions primary-command options this command would reject
 * @param produces the capture source it yields
 * @param outputPath where its output lands, session-local and absolute
 * @param estimatedCost relative cost
 * @param failureIsFatal false for every enrichment command (plan 8.6 rule 9): a failed {@code
 *     aquery} costs a graph, not the session
 */
public record AuxiliaryCommandPlan(
    String label,
    String purpose,
    List<String> argv,
    Timing timing,
    List<String> carriedOptions,
    List<String> droppedOptions,
    DataSource produces,
    Path outputPath,
    Overhead estimatedCost,
    boolean failureIsFatal) {

  /** When an auxiliary command runs (plan 8.6: after the build, by default). */
  public enum Timing {
    /**
     * After the primary invocation finishes. The default, so enrichment does not contend with the
     * execution being measured.
     */
    AFTER_BUILD,
    /**
     * Alongside the primary invocation. Available only for commands that cannot perturb it, and
     * never the default: two Bazel commands in one workspace contend for the same server lock.
     */
    CONCURRENT
  }

  public AuxiliaryCommandPlan {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(purpose, "purpose");
    argv = List.copyOf(argv);
    Objects.requireNonNull(timing, "timing");
    carriedOptions = List.copyOf(carriedOptions);
    droppedOptions = List.copyOf(droppedOptions);
    Objects.requireNonNull(produces, "produces");
    Objects.requireNonNull(outputPath, "outputPath");
    Objects.requireNonNull(estimatedCost, "estimatedCost");
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("auxiliary command " + label + " has no argv");
    }
  }

  /**
   * True when an option the primary build used could not be reproduced here, so any graph this
   * produces may describe a different configuration (plan 8.6 rule 10, and plan rule 13 on
   * completeness claims).
   */
  public boolean configurationMayDiffer() {
    return !droppedOptions.isEmpty();
  }
}

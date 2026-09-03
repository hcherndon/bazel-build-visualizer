package com.holtherndon.bazelviz.ui.graph;

import java.util.Locale;

/**
 * What a node's size, its edges' thickness and the colour ramp mean.
 *
 * <p>The Graph card's weight selector. Weights change the visual encoding only: positions come from
 * the deterministic layouts and do not move when the weight changes, so re-selecting a weight
 * re-renders without re-layout.
 *
 * <h2>Each weight says where its numbers come from</h2>
 *
 * <p>The immediate counts are CSR degrees — free. The transitive counts are exact over the drawn
 * subgraph and budgeted over the whole graph ({@code GraphWeights} carries the budgets), because
 * plan 13.3 forbids a transitive closure. Sizes come from the primary-output join, and a node
 * without a recorded size is unknown, never zero. {@link #INPUT_COUNT} is honest about being the
 * immediate dependency count: a distinct raw-input count is not derivable from what the session
 * stores, and a weight that pretended otherwise would be a fabricated number with a truthful name.
 */
public enum GraphWeight {

  /**
   * The Phase 7 encoding: colour is the action's duration. The default, and the only weight whose
   * values the panel already holds.
   */
  DURATION("Duration", "duration"),

  /** Reverse-index degree: how many things this node directly needs. */
  IMMEDIATE_DEPS("Immediate deps", "immediate dependencies"),

  /** Forward-index degree: how many things directly need this node. */
  IMMEDIATE_RDEPS("Immediate rdeps", "immediate dependents"),

  /** Exact count of drawn nodes this one transitively needs. */
  TRANSITIVE_DEPS("Transitive deps (on screen)", "transitive dependencies on screen"),

  /** Exact count of drawn nodes that transitively need this one. */
  TRANSITIVE_RDEPS("Transitive rdeps (on screen)", "transitive dependents on screen"),

  /** Primary-output bytes, where the session recorded them. */
  OUTPUT_SIZE("Output size", "output size"),

  /**
   * Presented as the immediate dependency count, and labelled as exactly that, because no distinct
   * raw-input count exists in the session.
   */
  INPUT_COUNT("Inputs (immediate deps)", "inputs, counted as immediate dependencies");

  private final String title;
  private final String subject;

  GraphWeight(String title, String subject) {
    this.title = title;
    this.subject = subject;
  }

  /** The selector's words. */
  public String displayName() {
    return title;
  }

  /** What the legend calls the quantity. */
  public String subject() {
    return subject;
  }

  /** True when the transitive machinery, not a lookup, produces the values. */
  public boolean isTransitive() {
    return this == TRANSITIVE_DEPS || this == TRANSITIVE_RDEPS;
  }

  /**
   * True when the count follows edges producer-to-consumer (dependents); false when it walks them
   * backwards (dependencies). Meaningless for the non-count weights.
   */
  public boolean countsForwards() {
    return this == IMMEDIATE_RDEPS || this == TRANSITIVE_RDEPS;
  }

  /** One value, in this weight's own units. */
  public String format(long value) {
    if (this == DURATION) {
      return value / 1_000 + " ms";
    }
    if (this == OUTPUT_SIZE) {
      return bytes(value);
    }
    return String.valueOf(value);
  }

  private static String bytes(long value) {
    if (value < 1_024) {
      return value + " B";
    }
    if (value < 1_024L * 1_024) {
      return String.format(Locale.ROOT, "%.1f KiB", value / 1_024.0);
    }
    if (value < 1_024L * 1_024 * 1_024) {
      return String.format(Locale.ROOT, "%.1f MiB", value / (1_024.0 * 1_024));
    }
    return String.format(Locale.ROOT, "%.1f GiB", value / (1_024.0 * 1_024 * 1_024));
  }
}

package com.holtherndon.bazelviz.ui.timeline;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How the timeline's rows are chosen and ordered.
 *
 * <p>Plan 14.2 lists eight groupings and five sort orders. Both are enums rather than strings
 * because each carries the SQL that produces it and the sentence that describes it, and a grouping
 * the session cannot supply must be able to say so rather than silently producing one lane called
 * "null".
 */
public final class LaneGrouping {

  private LaneGrouping() {}

  /** What the rows are grouped by. */
  public enum By {
    /**
     * Where the work ran. Needs an execution log; without one every action falls in a single
     * unknown lane, which is why {@link #needsExecutionLog} exists rather than letting the view
     * show one useless row.
     */
    RUNNER("Runner", true),
    MNEMONIC("Mnemonic", false),
    PACKAGE("Package", false),
    TARGET("Target", false),
    EXECUTION_PLATFORM("Execution platform", true),
    CACHE_RESULT("Cache result", true),
    /** One lane per worker or thread, from the execution log's runner detail. */
    THREAD("Worker or thread", true),
    /** Everything in one row: the plain view of a build's shape over time. */
    NONE("No grouping", false);

    private final String displayName;
    private final boolean needsExecutionLog;

    By(String displayName, boolean needsExecutionLog) {
      this.displayName = displayName;
      this.needsExecutionLog = needsExecutionLog;
    }

    public String displayName() {
      return displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }

    /** True when this grouping has nothing to say without an execution log. */
    public boolean needsExecutionLog() {
      return needsExecutionLog;
    }

    /**
     * Why this grouping is unavailable, or empty when it is available.
     *
     * @param hasExecutionLog whether the session imported one
     */
    public Optional<String> unavailableReason(boolean hasExecutionLog) {
      if (!needsExecutionLog || hasExecutionLog) {
        return Optional.empty();
      }
      return Optional.of(
          displayName.toLowerCase(Locale.ROOT)
              + " comes from the execution log, and this session has none");
    }
  }

  /** How the rows are ordered. */
  public enum SortBy {
    FIRST_ACTIVITY("First activity"),
    TOTAL_DURATION("Total duration"),
    CRITICAL_PATH_CONTRIBUTION("Critical-path contribution"),
    ACTION_COUNT("Action count"),
    NAME("Name");

    private final String displayName;

    SortBy(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

  /**
   * Orders {@code lanes}.
   *
   * @param criticalPathMicrosByLane how much of the critical path each lane accounts for; empty
   *     when no critical path has been computed, in which case sorting by contribution falls back
   *     to total duration rather than silently ordering by nothing
   */
  public static List<TimelineModel.Lane> sort(
      List<TimelineModel.Lane> lanes, SortBy sortBy, Map<String, Long> criticalPathMicrosByLane) {
    Comparator<TimelineModel.Lane> comparator =
        switch (sortBy) {
          case FIRST_ACTIVITY -> Comparator.comparingLong(TimelineModel.Lane::firstMicros);
          case TOTAL_DURATION ->
              Comparator.comparingLong(TimelineModel.Lane::totalMicros).reversed();
          case ACTION_COUNT -> Comparator.comparingLong(TimelineModel.Lane::spanCount).reversed();
          case NAME -> Comparator.comparing(TimelineModel.Lane::name);
          case CRITICAL_PATH_CONTRIBUTION ->
              criticalPathMicrosByLane.isEmpty()
                  // No critical path means no contribution to sort by. Falling
                  // back to total duration is a different order and the caller
                  // is told which it got; ordering by a map of zeroes would
                  // look like an answer.
                  ? Comparator.comparingLong(TimelineModel.Lane::totalMicros).reversed()
                  : Comparator.comparingLong(
                          (TimelineModel.Lane lane) ->
                              criticalPathMicrosByLane.getOrDefault(lane.key(), 0L))
                      .reversed();
        };
    // Name breaks every tie, so two runs over the same session put the lanes
    // in the same order. A timeline that reshuffles its rows when nothing
    // changed is one nobody can compare against a screenshot.
    return lanes.stream().sorted(comparator.thenComparing(TimelineModel.Lane::name)).toList();
  }

  /**
   * Whether sorting by critical-path contribution actually did.
   *
   * <p>Used by the view to say "sorted by total duration, because no critical path has been
   * computed" rather than showing a sort control that lies.
   */
  public static boolean contributionSortIsReal(
      SortBy sortBy, Map<String, Long> criticalPathMicrosByLane) {
    return sortBy != SortBy.CRITICAL_PATH_CONTRIBUTION || !criticalPathMicrosByLane.isEmpty();
  }
}

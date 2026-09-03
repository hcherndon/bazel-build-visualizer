package com.holtherndon.bazelviz.ui.timeline;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * How overlapping spans in one lane share the lane's vertical space.
 *
 * <h2>The rule</h2>
 *
 * <p>Within a lane, spans stack top-to-bottom by start time: each span takes the highest sub-row
 * whose previous occupant has already ended. Two spans that do not overlap share a sub-row; a burst
 * of concurrent work spreads downward, so the depth of the stack <em>is</em> the concurrency,
 * visibly.
 *
 * <h2>The budget, and what happens past it</h2>
 *
 * <p>A lane gets at most {@link #MAX_SUB_ROWS} sub-rows. The view draws each of them at a fixed
 * {@code TimelineView.SUB_ROW_HEIGHT} and lets the lane grow — depth is height — so the budget is
 * not about pixels running out; it is about how much vertical space one lane may claim from the
 * others before the plot stops being readable as a whole. A span that arrives when every sub-row is
 * still occupied is drawn in the last sub-row anyway, overlapping whatever is there, and counted in
 * {@link #overflowCount()}: the view states the exact number, because a lane whose bottom row is
 * secretly a pile would let a density read as a single span. Nothing is dropped — this is the same
 * honesty rule as {@link SpanWindow#MAX_SPANS}, applied vertically.
 *
 * <p>Pure computation over one {@link SpanWindow}; no Swing, no SQL, built once per window on the
 * EDT (at most {@link SpanWindow#MAX_SPANS} spans, a few hundred microseconds) and read by every
 * paint until the next window.
 */
public final class SpanStacking {

  /**
   * Sub-rows one lane may split into before overlapping spans pile into the last one. Documented in
   * docs/limits.md; LimitsDocTest checks the value.
   */
  public static final int MAX_SUB_ROWS = 6;

  /** The stacking of no spans at all. */
  public static final SpanStacking EMPTY = new SpanStacking(new int[0], Map.of(), 0);

  private final int[] subRows;
  private final Map<String, Integer> depthByLane;
  private final long overflow;

  private SpanStacking(int[] subRows, Map<String, Integer> depthByLane, long overflow) {
    this.subRows = subRows;
    this.depthByLane = depthByLane;
    this.overflow = overflow;
  }

  /**
   * Stacks {@code window}'s spans lane by lane.
   *
   * <p>Spans are processed in start order regardless of the window's own order, so the sub-row a
   * span lands in is a fact about the span and its lane-mates, never about fetch order — the same
   * stability rule the lane key itself enforces horizontally.
   */
  public static SpanStacking of(SpanWindow window) {
    int size = window.size();
    if (size == 0) {
      return EMPTY;
    }
    Integer[] order = new Integer[size];
    for (int i = 0; i < size; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        (a, b) -> {
          int byStart = Long.compare(window.startMicros(a), window.startMicros(b));
          // Node id breaks ties so equal starts stack the same way twice.
          return byStart != 0 ? byStart : Long.compare(window.nodeId(a), window.nodeId(b));
        });

    int[] subRows = new int[size];
    Map<String, long[]> endsByLane = new HashMap<>();
    Map<String, Integer> depthByLane = new HashMap<>();
    long overflow = 0;
    for (Integer index : order) {
      int i = index;
      long[] ends =
          endsByLane.computeIfAbsent(
              window.laneKey(i),
              key -> {
                long[] fresh = new long[MAX_SUB_ROWS];
                Arrays.fill(fresh, Long.MIN_VALUE);
                return fresh;
              });
      long start = window.startMicros(i);
      int placed = -1;
      for (int row = 0; row < MAX_SUB_ROWS; row++) {
        if (ends[row] <= start) {
          placed = row;
          break;
        }
      }
      if (placed < 0) {
        placed = MAX_SUB_ROWS - 1;
        overflow++;
        ends[placed] = Math.max(ends[placed], window.endMicros(i));
      } else {
        ends[placed] = window.endMicros(i);
      }
      subRows[i] = placed;
      String key = window.laneKey(i);
      int depth = placed + 1;
      depthByLane.merge(key, depth, Math::max);
    }
    return new SpanStacking(subRows, Map.copyOf(depthByLane), overflow);
  }

  /** The sub-row span {@code i} of the window occupies within its lane. */
  public int subRow(int i) {
    return subRows[i];
  }

  /**
   * How many sub-rows {@code laneKey} actually uses — 1 for a lane with no overlap, at most {@link
   * #MAX_SUB_ROWS}, and 1 for a lane this stacking never saw (a lane with no spans in the window
   * still gets its row).
   */
  public int depthOf(String laneKey) {
    return depthByLane.getOrDefault(laneKey, 1);
  }

  /**
   * Spans drawn overlapping in the last sub-row because their lane's concurrency exceeded {@link
   * #MAX_SUB_ROWS}. Zero means every span has its own patch of pixels; non-zero is stated on the
   * status line with this exact number, never smoothed over.
   */
  public long overflowCount() {
    return overflow;
  }
}

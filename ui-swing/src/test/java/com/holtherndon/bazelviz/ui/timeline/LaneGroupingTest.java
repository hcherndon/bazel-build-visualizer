package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the timeline's rows are chosen and ordered.
 *
 * <p>Plan 14.2. The interesting cases are the ones where the session cannot supply what the control
 * offers: a grouping that needs an execution log, and a sort by critical-path contribution with no
 * critical path.
 */
final class LaneGroupingTest {

  private static TimelineModel.Lane lane(
      String name, long spans, long total, long first, long last) {
    return new TimelineModel.Lane(name, name, spans, total, first, last);
  }

  private static final List<TimelineModel.Lane> LANES =
      List.of(
          lane("Javac", 100, 5_000, 300, 900),
          lane("Genrule", 10, 9_000, 100, 800),
          lane("CppCompile", 50, 1_000, 200, 400));

  @Test
  @DisplayName("sorting by total duration puts the most work first")
  void byTotalDuration() {
    assertThat(LaneGrouping.sort(LANES, LaneGrouping.SortBy.TOTAL_DURATION, Map.of()))
        .extracting(TimelineModel.Lane::name)
        .containsExactly("Genrule", "Javac", "CppCompile");
  }

  @Test
  @DisplayName("sorting by action count puts the busiest first, which is a different order")
  void byActionCount() {
    assertThat(LaneGrouping.sort(LANES, LaneGrouping.SortBy.ACTION_COUNT, Map.of()))
        .extracting(TimelineModel.Lane::name)
        .containsExactly("Javac", "CppCompile", "Genrule");
  }

  @Test
  @DisplayName("sorting by first activity follows the build")
  void byFirstActivity() {
    assertThat(LaneGrouping.sort(LANES, LaneGrouping.SortBy.FIRST_ACTIVITY, Map.of()))
        .extracting(TimelineModel.Lane::name)
        .containsExactly("Genrule", "CppCompile", "Javac");
  }

  @Test
  @DisplayName("sorting by critical-path contribution uses it when there is one")
  void byContribution() {
    Map<String, Long> contribution = Map.of("CppCompile", 900L, "Javac", 100L);

    assertThat(
            LaneGrouping.sort(LANES, LaneGrouping.SortBy.CRITICAL_PATH_CONTRIBUTION, contribution))
        .extracting(TimelineModel.Lane::name)
        .containsExactly("CppCompile", "Javac", "Genrule");
    assertThat(
            LaneGrouping.contributionSortIsReal(
                LaneGrouping.SortBy.CRITICAL_PATH_CONTRIBUTION, contribution))
        .isTrue();
  }

  @Test
  @DisplayName("with no critical path it falls back, and admits it did")
  void contributionFallsBackHonestly() {
    List<TimelineModel.Lane> sorted =
        LaneGrouping.sort(LANES, LaneGrouping.SortBy.CRITICAL_PATH_CONTRIBUTION, Map.of());

    // Ordering by a map of zeroes would leave the rows in whatever order
    // they arrived and look like an answer.
    assertThat(sorted)
        .extracting(TimelineModel.Lane::name)
        .containsExactly("Genrule", "Javac", "CppCompile");
    assertThat(
            LaneGrouping.contributionSortIsReal(
                LaneGrouping.SortBy.CRITICAL_PATH_CONTRIBUTION, Map.of()))
        .isFalse();
  }

  @Test
  @DisplayName("ties break by name, so two runs order the rows the same way")
  void tiesAreStable() {
    List<TimelineModel.Lane> tied =
        List.of(
            lane("beta", 1, 100, 0, 1), lane("alpha", 1, 100, 0, 1), lane("gamma", 1, 100, 0, 1));

    // A timeline that reshuffles its rows when nothing changed cannot be
    // compared against a screenshot from a minute ago.
    assertThat(LaneGrouping.sort(tied, LaneGrouping.SortBy.TOTAL_DURATION, Map.of()))
        .extracting(TimelineModel.Lane::name)
        .containsExactly("alpha", "beta", "gamma");
  }

  @Test
  @DisplayName("a grouping that needs an execution log says so when there is none")
  void groupingsDeclareTheirNeeds() {
    assertThat(LaneGrouping.By.RUNNER.unavailableReason(false))
        .hasValueSatisfying(why -> assertThat(why).contains("execution log"));
    assertThat(LaneGrouping.By.RUNNER.unavailableReason(true)).isEmpty();
    // Mnemonic and package come from the build event stream, so they work
    // in any session.
    assertThat(LaneGrouping.By.MNEMONIC.unavailableReason(false)).isEmpty();
    assertThat(LaneGrouping.By.PACKAGE.unavailableReason(false)).isEmpty();
  }

  @Test
  @DisplayName("every grouping and sort has a name, and none renders as an enum constant")
  void everythingIsWorded() {
    for (LaneGrouping.By by : LaneGrouping.By.values()) {
      assertThat(by.displayName()).as("%s", by).isNotBlank().doesNotContain("_");
      assertThat(by.toString()).isEqualTo(by.displayName());
    }
    for (LaneGrouping.SortBy sortBy : LaneGrouping.SortBy.values()) {
      assertThat(sortBy.displayName()).as("%s", sortBy).isNotBlank().doesNotContain("_");
      assertThat(sortBy.toString()).isEqualTo(sortBy.displayName());
    }
  }

  @Test
  @DisplayName("a lane's total duration is not its elapsed time")
  void totalIsNotElapsed() {
    // Two overlapping actions of 500 µs each inside a 600 µs window: 1,000
    // of work in 600 of wall. Counting them once would make a lane's total
    // useless for "where did the time go".
    TimelineModel.Lane overlapping = lane("Javac", 2, 1_000, 100, 700);

    assertThat(overlapping.totalMicros()).isEqualTo(1_000);
    assertThat(overlapping.elapsedMicros()).isEqualTo(600);
  }
}

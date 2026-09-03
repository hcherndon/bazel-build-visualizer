package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The longest weighted path, and the four things it must not claim.
 *
 * <p>It is not Bazel's critical path, it is not exact when durations are missing, it is undefined
 * on a cyclic graph, and it is not a build's actual elapsed time. Each has a test.
 */
final class CriticalPathTest {

  /**
   * A diamond: 0 feeds 1 and 2, both feed 3.
   *
   * <pre>
   *      1 (30)
   *     /      \
   * 0 (10)      3 (5)
   *     \      /
   *      2 (100)
   * </pre>
   *
   * The long way round is 0 → 2 → 3 = 115.
   */
  private static CsrGraph diamond() {
    return CsrBuilder.build(
        4,
        visitor -> {
          visitor.edge(0, 1);
          visitor.edge(0, 2);
          visitor.edge(1, 3);
          visitor.edge(2, 3);
        });
  }

  private static final long[] DIAMOND_WEIGHTS = {10, 30, 100, 5};

  @Test
  @DisplayName("the longest way round is the path, not the shortest or the first")
  void longestPathWins() {
    CriticalPath.Result result =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.COMPUTED);
    assertThat(result.path()).containsExactly(0, 2, 3);
    assertThat(result.makespanMicros()).isEqualTo(115);
    assertThatThrownBy(() -> result.path().set(0, 99))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("a node off the path has slack, and one on it has none")
  void slackIsComputed() {
    CriticalPath.Result result =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

    // Node 1 could start 70 µs later without delaying anything: it takes 30
    // where the parallel branch takes 100.
    assertThat(result.slackAt(1)).isEqualTo(70);
    assertThat(result.slackAt(0)).isZero();
    assertThat(result.slackAt(2)).isZero();
    assertThat(result.slackAt(3)).isZero();
    // Slack of zero is exactly what being on the path means.
    assertThat(result.isOnPath(1)).isFalse();
    assertThat(result.isOnPath(0)).isTrue();
    assertThat(result.isOnPath(2)).isTrue();
    assertThat(result.isOnPath(3)).isTrue();
  }

  @Test
  @DisplayName("equal zero-slack branches do not both claim membership in the selected path")
  void selectedPathMembershipIsDistinctFromZeroSlack() {
    CsrGraph equalBranches =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(0, 2);
              visitor.edge(1, 3);
              visitor.edge(2, 3);
            });

    CriticalPath.Result result =
        CriticalPath.compute(
            equalBranches, new long[] {10, 30, 30, 5}, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.slackAt(1)).isZero();
    assertThat(result.slackAt(2)).isZero();
    assertThat(result.path()).containsExactly(0, 1, 3);
    assertThat(result.isOnPath(1)).isTrue();
    assertThat(result.isOnPath(2)).isFalse();
  }

  @Test
  @DisplayName("earliest start respects every predecessor, not just one")
  void earliestStartWaitsForTheSlowestInput() {
    CriticalPath.Result result =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

    // Node 3 waits for node 2 (finishing at 110), not node 1 (at 40).
    assertThat(result.earliestStartAt(3)).isEqualTo(110);
    assertThat(result.earliestFinishAt(3)).isEqualTo(115);
  }

  @Test
  @DisplayName("an untimed action counts as instantaneous and makes the answer a lower bound")
  void untimedNodesMakeItPartial() {
    long[] weights = {10, CriticalPath.UNKNOWN_DURATION, 100, 5};

    CriticalPath.Result result =
        CriticalPath.compute(diamond(), weights, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.untimedNodes()).isEqualTo(1);
    assertThat(result.isPartial()).isTrue();
    // The number is still produced -- a partial answer beats none -- and it
    // says it is a lower bound rather than presenting itself as the answer.
    assertThat(result.describe()).contains("lower bound").contains("instantaneous");
  }

  @Test
  @DisplayName("an untimed node remains distinct from a node measured at zero")
  void perNodeTimingPresenceDistinguishesUnknownFromMeasuredZero() {
    CriticalPath.Result result =
        CriticalPath.compute(
            diamond(),
            new long[] {10, CriticalPath.UNKNOWN_DURATION, 0, 5},
            CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.isUntimedAt(1)).isTrue();
    assertThat(result.isUntimedAt(2)).isFalse();
    assertThat(result.earliestFinishAt(2) - result.earliestStartAt(2)).isZero();
  }

  @Test
  @DisplayName("an untimed prerequisite remains visible before a timed action")
  void untimedPrefixesRemainOnThePath() {
    CsrGraph chain = CsrBuilder.build(2, visitor -> visitor.edge(0, 1));

    CriticalPath.Result result =
        CriticalPath.compute(
            chain,
            new long[] {CriticalPath.UNKNOWN_DURATION, 25},
            CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.path()).containsExactly(0, 1);
    assertThat(result.makespanMicros()).isEqualTo(25);
    assertThat(result.isPartial()).isTrue();
  }

  @Test
  @DisplayName("an all-zero chain retains its full dependency history")
  void zeroDurationChainsRemainWhole() {
    CsrGraph chain =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
              visitor.edge(2, 3);
            });

    CriticalPath.Result result =
        CriticalPath.compute(
            chain, new long[] {0, 0, 0, 0}, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.path()).containsExactly(0, 1, 2, 3);
    assertThat(result.makespanMicros()).isZero();
  }

  @Test
  @DisplayName("a fully timed graph is not partial")
  void completeGraphsAreNotPartial() {
    CriticalPath.Result result =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.isPartial()).isFalse();
    assertThat(result.describe()).doesNotContain("lower bound");
  }

  @Test
  @DisplayName("a partial dependency path is not compared numerically with Bazel's path")
  void partialPathsWithholdTheSchedulingGap() {
    CriticalPath.Result partial =
        CriticalPath.compute(
            diamond(),
            new long[] {10, CriticalPath.UNKNOWN_DURATION, 100, 5},
            CriticalPath.DurationSource.BEP_ACTION);
    CriticalPaths paths =
        new CriticalPaths(Measured.of(150L, DataSource.PROFILE), List.of(), Optional.of(partial));

    assertThat(paths.bothAvailable()).isFalse();
    assertThat(paths.schedulingGapMicros()).isEmpty();
  }

  @Test
  @DisplayName("a partial Bazel observation is not compared with the dependency path")
  void partialBazelTotalsWithholdTheSchedulingGap() {
    CriticalPath.Result complete =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);
    CriticalPaths paths =
        new CriticalPaths(
            new Measured<>(
                Optional.of(150L),
                DataSource.BEP,
                Completeness.TRUNCATED,
                Optional.of("capture ended early")),
            List.of(),
            Optional.of(complete));

    assertThat(paths.bothAvailable()).isFalse();
    assertThat(paths.schedulingGapMicros()).isEmpty();
  }

  @Test
  @DisplayName("a negative Bazel critical-path total is refused")
  void negativeBazelTotalsAreRefused() {
    assertThatThrownBy(
            () -> new CriticalPaths(Measured.of(-1L, DataSource.BEP), List.of(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonnegative");
  }

  @Test
  @DisplayName("the exact Bazel component count can exceed the unloaded component list")
  void bazelComponentCountDescribesPagedRowsWithoutMaterializingThem() {
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(150_000L, DataSource.PROFILE),
            List.of(),
            4_000_000L,
            Optional.empty(),
            Optional.of("no graph"));

    assertThat(paths.bazelComponents()).isEmpty();
    assertThat(paths.bazelComponentCount()).isEqualTo(4_000_000L);
    assertThat(paths.describe())
        .contains("across 4,000,000 components")
        .doesNotContain("component breakdown is unavailable");
  }

  @Test
  @DisplayName("component metadata that would make paging dishonest is refused")
  void invalidBazelComponentMetadataIsRefused() {
    CriticalPaths.BazelComponent component =
        new CriticalPaths.BazelComponent(0, "one", OptionalLong.of(1));

    assertThatThrownBy(
            () ->
                new CriticalPaths(
                    Measured.of(1L, DataSource.PROFILE),
                    List.of(component),
                    0,
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("smaller than the loaded list");
    assertThatThrownBy(() -> new CriticalPaths.BazelComponent(-1, "invalid", OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ordinal");
    assertThatThrownBy(() -> new CriticalPaths.BazelComponent(0, "invalid", OptionalLong.of(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duration");
  }

  @Test
  @DisplayName("the observed action lower bound cannot masquerade as a dependency path")
  void observedActionLowerBoundCarriesItsLimitedEvidence() {
    CriticalPaths.ObservedActionLowerBound fallback =
        new CriticalPaths.ObservedActionLowerBound(
            7,
            "bazel-out/bin/pkg/app.jar",
            Optional.of("//pkg:app"),
            Optional.of("Javac"),
            4_000,
            3,
            9);

    assertThat(fallback.displayName()).contains("not a dependency path");
    assertThatThrownBy(
            () ->
                new CriticalPaths.ObservedActionLowerBound(
                    7, "out", Optional.empty(), Optional.empty(), 0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                new CriticalPaths.ObservedActionLowerBound(
                    7, "out", Optional.empty(), Optional.empty(), 4_000, 10, 9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coverage");
  }

  @Test
  @DisplayName("a Bazel total without profile rows does not claim zero components")
  void bazelTotalCanExistWithoutComponentBreakdown() {
    CriticalPaths paths =
        new CriticalPaths(
            Measured.of(150_000L, DataSource.BEP),
            List.of(),
            Optional.empty(),
            Optional.of("no graph"));

    assertThat(paths.describe())
        .contains("150.0 ms; the component breakdown is unavailable")
        .doesNotContain("across 0 components");
  }

  @Test
  @DisplayName("sub-millisecond path values remain distinct from zero")
  void descriptionsPreserveSubMillisecondDurations() {
    CriticalPath.Result derived =
        CriticalPath.compute(
            CsrBuilder.build(1, visitor -> {}),
            new long[] {999},
            CriticalPath.DurationSource.BEP_ACTION);
    CriticalPaths paths =
        new CriticalPaths(Measured.of(1_000L, DataSource.PROFILE), List.of(), Optional.of(derived));

    assertThat(derived.describe()).contains("999 µs").doesNotContain("0 ms");
    assertThat(paths.describe())
        .contains("1.0 ms")
        .contains("999 µs")
        .contains("differ by 1 µs")
        .doesNotContain("differ by 0 ms");
  }

  @Test
  @DisplayName("a cycle produces no path and reports every node it prevents ordering")
  void cyclesAreRefusedAndReported() {
    CsrGraph cyclic =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
              visitor.edge(2, 0);
              visitor.edge(2, 3);
            });

    CriticalPath.Result result =
        CriticalPath.compute(
            cyclic, new long[] {1, 1, 1, 1}, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.CYCLIC);
    assertThat(result.path()).isEmpty();
    assertThat(result.nodeCount()).isEqualTo(4);
    assertThat(result.unorderedNodes()).containsExactlyInAnyOrder(0, 1, 2, 3);
    assertThat(result.describe())
        .contains("4 of 4 actions could not be ordered")
        .contains("in or downstream of the cycle");
    // A producer-to-consumer graph cannot have a cycle, so this means the
    // graph is wrong rather than the build -- and the message says so.
    assertThat(result.describe()).contains("the graph is wrong rather than the build");
  }

  @Test
  @DisplayName("a large cycle is reported without a second graph pass or boxed node copy")
  void largeCyclesRetainPrimitiveUnorderedNodes() {
    int nodes = 100_000;
    CsrGraph ring =
        CsrBuilder.build(
            nodes,
            visitor -> {
              for (int node = 0; node < nodes; node++) {
                visitor.edge(node, (node + 1) % nodes);
              }
            });

    CriticalPath.Result result =
        CriticalPath.compute(ring, new long[nodes], CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.CYCLIC);
    assertThat(result.nodeCount()).isEqualTo(nodes);
    assertThat(result.unorderedNodes()).hasSize(nodes);
    assertThat(result.unorderedNodes().get(0)).isZero();
    assertThat(result.unorderedNodes().get(nodes - 1)).isEqualTo(nodes - 1);
  }

  @Test
  @DisplayName("negative durations other than the unknown sentinel are refused")
  void invalidNegativeDurationsAreRefused() {
    assertThatThrownBy(
            () ->
                CriticalPath.compute(
                    diamond(), new long[] {10, -2, 100, 5}, CriticalPath.DurationSource.BEP_ACTION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("node 1")
        .hasMessageContaining("nonnegative or UNKNOWN_DURATION");
  }

  @Test
  @DisplayName("a path whose total cannot fit in a long is refused")
  void overflowingSchedulesAreRefused() {
    CsrGraph chain = CsrBuilder.build(2, visitor -> visitor.edge(0, 1));

    assertThatThrownBy(
            () ->
                CriticalPath.compute(
                    chain, new long[] {Long.MAX_VALUE, 1}, CriticalPath.DurationSource.BEP_ACTION))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("the answer says which measurement it used")
  void durationSourceIsCarried() {
    assertThat(
            CriticalPath.compute(
                    diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.EXECUTION_ATTEMPT)
                .describe())
        .contains("shortest recorded spawn duration per action from the execution log");
    assertThat(
            CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION)
                .describe())
        .contains("action durations from the build event stream");
  }

  @Test
  @DisplayName("it is never called Bazel's critical path")
  void itIsNamedForWhatItIs() {
    CriticalPath.Result result =
        CriticalPath.compute(diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

    // Plan 13.4. Bazel writes its own into the profile and Phase 5 stores
    // it untouched; the two legitimately disagree, and presenting this one
    // as Bazel's would make the disagreement invisible.
    assertThat(result.displayName()).isEqualTo("Visualizer-computed dependency critical path");
    assertThat(result.describe()).contains("Visualizer-computed");
  }

  @Test
  @DisplayName("an empty graph says there is nothing to compute")
  void emptyGraphs() {
    CriticalPath.Result result =
        CriticalPath.compute(
            CsrBuilder.build(0, visitor -> {}), new long[0], CriticalPath.DurationSource.NONE);

    assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.EMPTY);
    assertThat(result.describe()).contains("no dependency graph");
  }

  @Test
  @DisplayName("a non-empty graph cannot claim to use no duration source")
  void nonEmptyGraphsRequireADurationSource() {
    assertThatThrownBy(
            () ->
                CriticalPath.compute(
                    CsrBuilder.build(1, visitor -> {}),
                    new long[] {0},
                    CriticalPath.DurationSource.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty dependency graph");
  }

  @Test
  @DisplayName("a graph with no edges has a path of one action")
  void disconnectedGraphs() {
    CsrGraph isolated = CsrBuilder.build(3, visitor -> {});

    CriticalPath.Result result =
        CriticalPath.compute(
            isolated, new long[] {5, 50, 5}, CriticalPath.DurationSource.BEP_ACTION);

    // Nothing depends on anything, so the longest chain is the slowest
    // single action -- which is the right answer and not a degenerate one.
    assertThat(result.path()).containsExactly(1);
    assertThat(result.makespanMicros()).isEqualTo(50);
  }

  @Test
  @DisplayName("a weight per node is required, and a mismatch is refused")
  void weightsMustMatchTheGraph() {
    assertThatThrownBy(
            () ->
                CriticalPath.compute(
                    diamond(), new long[] {1, 2}, CriticalPath.DurationSource.BEP_ACTION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one weight per node");
  }

  @Test
  @DisplayName("the schedule cannot be reached in bulk, so it cannot be rewritten")
  void resultIsDefensive() {
    // The Phase 8 replacement for a defensive-copy test. An accessor
    // returning long[] over five million nodes has to choose between
    // cloning forty megabytes and handing out the live schedule; there is
    // no third option, so the accessor does not exist.
    for (Method method : CriticalPath.Result.class.getMethods()) {
      if (method.getDeclaringClass() != CriticalPath.Result.class) {
        continue;
      }
      assertThat(method.getReturnType()).as("%s", method.getName()).isNotEqualTo(long[].class);
    }
  }

  @Test
  @DisplayName("a long chain is ordered correctly, not merely plausibly")
  void longChains() {
    int nodes = 1_000;
    CsrGraph chain =
        CsrBuilder.build(
            nodes,
            visitor -> {
              for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
              }
            });
    long[] weights = new long[nodes];
    Arrays.fill(weights, 3);

    CriticalPath.Result result =
        CriticalPath.compute(chain, weights, CriticalPath.DurationSource.BEP_ACTION);

    assertThat(result.path()).hasSize(nodes);
    assertThat(result.makespanMicros()).isEqualTo(3L * nodes);
    assertThat(result.path().getFirst()).isZero();
    assertThat(result.path().getLast()).isEqualTo(nodes - 1);
  }
}

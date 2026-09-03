package com.holtherndon.bazelviz.ui.criticalpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lazy bridge from the primitive dependency schedule to inspectable rows. */
final class CriticalPathRowSourceTest {

  @Test
  @DisplayName("pages preserve dependency order and load only the requested node details")
  void rowsAreLazyAndKeepPathOrder() {
    CriticalPath.Result path = nonIndexOrderedPath();
    List<List<Integer>> requests = new ArrayList<>();
    Map<Integer, GraphQueries.GraphNode> available = new LinkedHashMap<>();
    available.put(1, node(1, "//pkg:declared", "Genrule", OptionalLong.empty()));
    available.put(4, node(4, "//pkg:zero", "Touch", OptionalLong.of(41)));
    CriticalPathRowSource source =
        new CriticalPathRowSource(
            path,
            requested -> {
              requests.add(List.copyOf(requested));
              Map<Integer, Optional<GraphQueries.GraphNode>> result = new LinkedHashMap<>();
              requested.forEach(
                  index -> result.put(index, Optional.ofNullable(available.get(index))));
              return result;
            });

    assertThat(source.rowCount()).isEqualTo(4);
    assertThat(requests).as("counting rows must not read graph details").isEmpty();

    List<CriticalPathRow> rows = source.fetchPage(0, 3).rows();

    assertThat(requests).containsExactly(List.of(3, 1, 4));
    assertThat(rows).extracting(CriticalPathRow::nodeIndex).containsExactly(3, 1, 4);
    assertThat(rows).extracting(CriticalPathRow::stepNumber).containsExactly(1, 2, 3);
    assertThat(rows.get(1).actionId()).isEmpty();
    assertThat(rows.get(1).displayName()).contains("//pkg:declared").contains("Genrule");
    assertThat(rows.get(1).weightMicros()).as("an action nothing timed stays unknown").isEmpty();
    assertThat(rows.get(2).actionId()).hasValue(41);
    assertThat(rows.get(2).weightMicros())
        .as("a measured zero is not rewritten as unknown")
        .hasValue(0);
    assertThat(rows.get(1).earliestFinishMicros()).isEqualTo(rows.get(1).earliestStartMicros());
    assertThat(rows.get(2).earliestFinishMicros()).isEqualTo(rows.get(2).earliestStartMicros());
  }

  @Test
  @DisplayName("a missing stored node remains a visible path step")
  void missingNodeDetailsDoNotDropTheStep() {
    CriticalPath.Result path = nonIndexOrderedPath();
    CriticalPathRowSource source = new CriticalPathRowSource(path, requested -> Map.of());

    CriticalPathRow row = source.fetchPage(0, 1).rows().getFirst();

    assertThat(row.nodeIndex()).isEqualTo(3);
    assertThat(row.displayName()).isEqualTo("graph node 3 (details unavailable)");
    assertThat(row.actionId()).isEmpty();
    assertThat(row.targetLabel()).isEmpty();
  }

  @Test
  @DisplayName("a failed detail query is visible to the paged table instead of becoming empty")
  void lookupFailuresAreNotSilent() {
    CriticalPathRowSource source =
        new CriticalPathRowSource(
            nonIndexOrderedPath(),
            requested -> {
              throw new SQLException("database is closed");
            });

    assertThatThrownBy(() -> source.fetchPage(0, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read dependency-path node details")
        .hasRootCauseMessage("database is closed");
  }

  @Test
  @DisplayName("an empty page past the chain performs no node lookup")
  void pastEndIsCheap() {
    AtomicInteger lookups = new AtomicInteger();
    CriticalPathRowSource source =
        new CriticalPathRowSource(
            nonIndexOrderedPath(),
            requested -> {
              lookups.incrementAndGet();
              return Map.of();
            });

    assertThat(source.fetchPage(2, 2).rows()).isEmpty();
    assertThat(lookups).hasValue(0);
  }

  private static CriticalPath.Result nonIndexOrderedPath() {
    return CriticalPath.compute(
        CsrBuilder.build(
            5,
            visitor -> {
              visitor.edge(3, 1);
              visitor.edge(1, 4);
              visitor.edge(4, 2);
            }),
        new long[] {1, CriticalPath.UNKNOWN_DURATION, 5, 7, 0},
        CriticalPath.DurationSource.BEP_ACTION);
  }

  private static GraphQueries.GraphNode node(
      int index, String label, String mnemonic, OptionalLong actionId) {
    return new GraphQueries.GraphNode(
        index,
        Optional.of(label),
        Optional.of(mnemonic),
        Optional.of("bazel-out/" + index),
        actionId);
  }
}

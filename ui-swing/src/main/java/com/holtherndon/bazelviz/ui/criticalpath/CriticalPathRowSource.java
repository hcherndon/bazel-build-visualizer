package com.holtherndon.bazelviz.ui.criticalpath;

import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Lazy, exact-order rows over a computed dependency critical path. */
public final class CriticalPathRowSource implements RowSource<CriticalPathRow> {

  /** Rows loaded together when the dependency chain is scrolled. */
  public static final int DEFAULT_PAGE_SIZE = 200;

  /** Recently visited path pages retained by the table model. */
  public static final int DEFAULT_CACHE_PAGES = 8;

  @FunctionalInterface
  interface NodeLookup {
    Map<Integer, Optional<GraphQueries.GraphNode>> nodes(List<Integer> nodeIndices)
        throws SQLException;
  }

  private final CriticalPath.Result path;
  private final NodeLookup nodes;

  public CriticalPathRowSource(CriticalPath.Result path, GraphQueries queries) {
    this(path, queries::nodes);
  }

  CriticalPathRowSource(CriticalPath.Result path, NodeLookup nodes) {
    this.path = Objects.requireNonNull(path, "path");
    this.nodes = Objects.requireNonNull(nodes, "nodes");
    if (path.outcome() != CriticalPath.Outcome.COMPUTED) {
      throw new IllegalArgumentException("path rows require a computed path");
    }
  }

  @Override
  public long rowCount() {
    return path.path().size();
  }

  @Override
  public Page<CriticalPathRow> fetchPage(long pageIndex, int pageSize) {
    if (pageIndex < 0 || pageSize < 1) {
      throw new IllegalArgumentException("invalid page " + pageIndex + " at " + pageSize);
    }
    long firstLong = Math.multiplyExact(pageIndex, (long) pageSize);
    if (firstLong >= rowCount()) {
      return new Page<>(pageIndex, List.of());
    }
    int first = Math.toIntExact(firstLong);
    int end =
        Math.toIntExact(Math.min((long) path.path().size(), Math.addExact(firstLong, pageSize)));
    List<Integer> nodeIndices = path.path().subList(first, end);
    Map<Integer, Optional<GraphQueries.GraphNode>> details;
    try {
      details = nodes.nodes(nodeIndices);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read dependency-path node details", failure);
    }

    List<CriticalPathRow> rows = new ArrayList<>(end - first);
    for (int ordinal = first; ordinal < end; ordinal++) {
      int nodeIndex = path.path().get(ordinal);
      rows.add(
          new CriticalPathRow(
              ordinal,
              path.path().size(),
              nodeIndex,
              details.getOrDefault(nodeIndex, Optional.empty()),
              path.isUntimedAt(nodeIndex)
                  ? OptionalLong.empty()
                  : OptionalLong.of(
                      path.earliestFinishAt(nodeIndex) - path.earliestStartAt(nodeIndex)),
              path.earliestStartAt(nodeIndex),
              path.earliestFinishAt(nodeIndex),
              path.slackAt(nodeIndex)));
    }
    return new Page<>(pageIndex, List.copyOf(rows));
  }

  /** A tiny lookup useful to tests and adapters that already have materialized nodes. */
  static NodeLookup fromNodes(List<GraphQueries.GraphNode> nodes) {
    Map<Integer, GraphQueries.GraphNode> byIndex = new LinkedHashMap<>();
    for (GraphQueries.GraphNode node : nodes) {
      byIndex.put(node.nodeIndex(), node);
    }
    return requested -> {
      Map<Integer, Optional<GraphQueries.GraphNode>> selected = new LinkedHashMap<>();
      for (int node : requested) {
        selected.put(node, Optional.ofNullable(byIndex.get(node)));
      }
      return Map.copyOf(selected);
    };
  }
}

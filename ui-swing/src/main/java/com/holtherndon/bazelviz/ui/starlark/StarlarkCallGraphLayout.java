package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic top-down placement for a bounded pprof-style function graph. */
final class StarlarkCallGraphLayout {

  private static final double MIN_NODE_WIDTH = 132;
  private static final double MAX_NODE_WIDTH = 236;
  private static final double MIN_NODE_HEIGHT = 58;
  private static final double MAX_NODE_HEIGHT = 104;
  private static final double UNIFORM_NODE_WIDTH = 180;
  private static final double UNIFORM_NODE_HEIGHT = 78;
  private static final double NODE_GAP = 28;
  private static final double ROW_GAP = 22;
  private static final double RANK_GAP = 82;
  private static final int NODES_PER_ROW = 8;
  private static final double BOUNDS_MARGIN = 36;

  private StarlarkCallGraphLayout() {}

  /**
   * Uses the target graph's linear-time longest-path ranks, then packs each rank into bounded rows
   * for pprof's rectangular nodes. A wide set of roots therefore cannot shrink every label just
   * because the functions do not call one another inside this projection. Cycles remain visible in
   * the last rank rather than making the layout iterative or unbounded.
   */
  static Layout layout(StarlarkProfileReader.DirectedCallGraph graph) {
    return layout(graph, NodeWeight.SELF_CPU);
  }

  static Layout layout(StarlarkProfileReader.DirectedCallGraph graph, NodeWeight nodeWeight) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(nodeWeight, "nodeWeight");
    if (graph.nodes().isEmpty()) {
      return new Layout(graph, nodeWeight, List.of(), List.of(), new double[] {0, 0, 1, 1}, 0);
    }

    Map<Long, Integer> positionByFunction = new HashMap<>();
    List<Integer> positions = new ArrayList<>(graph.nodes().size());
    for (int index = 0; index < graph.nodes().size(); index++) {
      positions.add(index);
      positionByFunction.put(graph.nodes().get(index).functionId(), index);
    }
    List<GraphExtract.Edge> layoutEdges =
        graph.edges().stream()
            .map(
                edge ->
                    new GraphExtract.Edge(
                        positionByFunction.get(edge.callerFunctionId()),
                        positionByFunction.get(edge.calleeFunctionId())))
            .toList();
    GraphExtract.Result extracted =
        new GraphExtract.Result(
            GraphExtract.Mode.WHOLE,
            positions,
            layoutEdges,
            graph.totalFunctionCount(),
            graph.visibleEdgeCount(),
            graph.nodes().size(),
            layoutEdges.size(),
            graph.omittedFunctionCount() > 0,
            graph.omittedVisibleEdgeCount() > 0);
    GraphLayout.Result layered = GraphLayout.layered(extracted, new AtomicBoolean());

    long maxNodeWeight =
        graph.nodes().stream()
            .map(nodeWeight::value)
            .filter(OptionalLong::isPresent)
            .mapToLong(OptionalLong::getAsLong)
            .max()
            .orElse(0);
    NodeDimensions[] dimensions = new NodeDimensions[graph.nodes().size()];
    for (int sourcePosition = 0; sourcePosition < graph.nodes().size(); sourcePosition++) {
      dimensions[sourcePosition] =
          dimensions(graph.nodes().get(sourcePosition), nodeWeight, maxNodeWeight);
    }

    Map<Double, List<Integer>> ranks = new TreeMap<>();
    for (int layoutPosition = 0; layoutPosition < layered.size(); layoutPosition++) {
      ranks
          .computeIfAbsent(layered.xAt(layoutPosition), ignored -> new ArrayList<>())
          .add(layered.nodes().get(layoutPosition));
    }
    NodeBox[] boxes = new NodeBox[graph.nodes().size()];
    double rankTop = 0;
    for (List<Integer> rank : ranks.values()) {
      for (int first = 0; first < rank.size(); first += NODES_PER_ROW) {
        int last = Math.min(rank.size(), first + NODES_PER_ROW);
        double rowHeight = 0;
        double rowWidth = 0;
        for (int index = first; index < last; index++) {
          NodeDimensions size = dimensions[rank.get(index)];
          rowHeight = Math.max(rowHeight, size.height());
          rowWidth += size.width();
        }
        rowWidth += NODE_GAP * Math.max(0, last - first - 1);
        double cursorX = -rowWidth / 2;
        double centerY = rankTop + rowHeight / 2;
        for (int index = first; index < last; index++) {
          int sourcePosition = rank.get(index);
          NodeDimensions size = dimensions[sourcePosition];
          boxes[sourcePosition] =
              new NodeBox(
                  graph.nodes().get(sourcePosition),
                  cursorX + size.width() / 2,
                  centerY,
                  size.width(),
                  size.height());
          cursorX += size.width() + NODE_GAP;
        }
        rankTop += rowHeight + ROW_GAP;
      }
      rankTop += RANK_GAP - ROW_GAP;
    }

    List<NodeBox> nodes = new ArrayList<>(boxes.length);
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (NodeBox box : boxes) {
      nodes.add(box);
      minX = Math.min(minX, box.left());
      minY = Math.min(minY, box.top());
      maxX = Math.max(maxX, box.right());
      maxY = Math.max(maxY, box.bottom());
    }

    List<EdgePath> edges =
        graph.edges().stream()
            .map(
                edge ->
                    new EdgePath(
                        edge,
                        positionByFunction.get(edge.callerFunctionId()),
                        positionByFunction.get(edge.calleeFunctionId())))
            .toList();
    long maxEdgeCpu =
        graph.edges().stream()
            .map(StarlarkProfileReader.DirectedCallEdge::cpuMicros)
            .filter(OptionalLong::isPresent)
            .mapToLong(OptionalLong::getAsLong)
            .max()
            .orElse(0);
    return new Layout(
        graph,
        nodeWeight,
        nodes,
        edges,
        new double[] {
          minX - BOUNDS_MARGIN, minY - BOUNDS_MARGIN, maxX + BOUNDS_MARGIN, maxY + BOUNDS_MARGIN,
        },
        maxEdgeCpu);
  }

  private static NodeDimensions dimensions(
      StarlarkProfileReader.CallGraphNode node, NodeWeight nodeWeight, long maxNodeWeight) {
    if (nodeWeight == NodeWeight.UNIFORM) {
      return new NodeDimensions(UNIFORM_NODE_WIDTH, UNIFORM_NODE_HEIGHT);
    }
    OptionalLong value = nodeWeight.value(node);
    if (value.isEmpty()) {
      // Unknown is not zero. A neutral size keeps the absence visible in the tooltip and
      // avoids falsely presenting the smallest box as a measured zero.
      return new NodeDimensions(UNIFORM_NODE_WIDTH, UNIFORM_NODE_HEIGHT);
    }
    double fraction = maxNodeWeight <= 0 ? 0 : (double) value.getAsLong() / maxNodeWeight;
    double scale = Math.sqrt(Math.max(0, Math.min(1, fraction)));
    return new NodeDimensions(
        MIN_NODE_WIDTH + (MAX_NODE_WIDTH - MIN_NODE_WIDTH) * scale,
        MIN_NODE_HEIGHT + (MAX_NODE_HEIGHT - MIN_NODE_HEIGHT) * scale);
  }

  enum NodeWeight {
    SELF_CPU("Self") {
      @Override
      OptionalLong value(StarlarkProfileReader.CallGraphNode node) {
        return node.selfCpuMicros();
      }
    },
    CUMULATIVE_CPU("Cumulative") {
      @Override
      OptionalLong value(StarlarkProfileReader.CallGraphNode node) {
        return node.cumulativeCpuMicros();
      }
    },
    UNIFORM("Uniform") {
      @Override
      OptionalLong value(StarlarkProfileReader.CallGraphNode node) {
        return OptionalLong.empty();
      }
    };

    private final String displayName;

    NodeWeight(String displayName) {
      this.displayName = displayName;
    }

    abstract OptionalLong value(StarlarkProfileReader.CallGraphNode node);

    @Override
    public String toString() {
      return displayName;
    }
  }

  private record NodeDimensions(double width, double height) {}

  record NodeBox(
      StarlarkProfileReader.CallGraphNode node,
      double centerX,
      double centerY,
      double width,
      double height) {

    NodeBox {
      Objects.requireNonNull(node, "node");
      if (!(width > 0) || !(height > 0)) {
        throw new IllegalArgumentException("call-graph node dimensions must be positive");
      }
    }

    double left() {
      return centerX - width / 2;
    }

    double top() {
      return centerY - height / 2;
    }

    double right() {
      return centerX + width / 2;
    }

    double bottom() {
      return centerY + height / 2;
    }

    Rectangle2D.Double bounds() {
      return new Rectangle2D.Double(left(), top(), width, height);
    }
  }

  record EdgePath(
      StarlarkProfileReader.DirectedCallEdge edge, int callerPosition, int calleePosition) {

    EdgePath {
      Objects.requireNonNull(edge, "edge");
      if (callerPosition < 0 || calleePosition < 0) {
        throw new IllegalArgumentException("call-graph edge positions must be present");
      }
    }
  }

  record Layout(
      StarlarkProfileReader.DirectedCallGraph graph,
      NodeWeight nodeWeight,
      List<NodeBox> nodes,
      List<EdgePath> edges,
      double[] bounds,
      long maxEdgeCpuMicros) {

    Layout {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(nodeWeight, "nodeWeight");
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      bounds = bounds.clone();
      if (bounds.length != 4 || maxEdgeCpuMicros < 0) {
        throw new IllegalArgumentException("invalid call-graph layout metadata");
      }
    }

    @Override
    public double[] bounds() {
      return bounds.clone();
    }

    boolean containsFunction(long functionId) {
      return graph.containsFunction(functionId);
    }
  }
}

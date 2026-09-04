package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A bounded piece of a graph, and the truth about what it left out.
 *
 * <h2>Every extraction is bounded, and every extraction says so</h2>
 *
 * <p>Plan 13.6: above the detailed-layout limit, switch to cluster mode, show exact total counts,
 * offer to raise the limit, and <em>never claim the omitted nodes do not exist</em>. That last
 * clause is why {@link Result} carries the totals it drew from alongside what it returned — a
 * caller cannot render this without having been handed the number it is not showing.
 *
 * <p>Plan 13.3 forbids computing a transitive closure, so every traversal here takes node and edge
 * budgets and reports which one it hit. A neighbourhood that ran out of either budget is not a
 * neighbourhood that ended.
 */
public final class GraphExtract {

  private GraphExtract() {}

  /**
   * The default beyond which detail is not attempted.
   *
   * <p>Plan 13.6 suggests around 50,000 nodes and 200,000 edges. Configurable because the right
   * number depends on the machine, and a default because a user should not have to choose one
   * before seeing anything.
   */
  public static final int DEFAULT_NODE_LIMIT = 50_000;

  /** Default maximum dependency count for a detailed extraction. */
  public static final int DEFAULT_EDGE_LIMIT = 200_000;

  /**
   * What {@code source} needs: everything reachable over the <em>reverse</em> index, to a depth and
   * a budget.
   *
   * <p>Plan 13.5's "dependencies" mode. The forward index is producer-to-consumer, so the things a
   * node <em>depends on</em> are behind it, not ahead of it — this used to walk the forward index
   * and answered "what needs this" under the name "dependencies", which inverted both the trees and
   * the rooted canvas modes.
   */
  public static Result dependencies(CsrGraph reverse, int source, int maxDepth, int nodeLimit) {
    return dependencies(reverse, source, maxDepth, nodeLimit, DEFAULT_EDGE_LIMIT);
  }

  /** {@link #dependencies(CsrGraph, int, int, int)}, with an explicit edge budget. */
  public static Result dependencies(
      CsrGraph reverse, int source, int maxDepth, int nodeLimit, int edgeLimit) {
    return traverse(
        reverse, source, maxDepth, nodeLimit, edgeLimit, Direction.REVERSE, Mode.DEPENDENCIES);
  }

  /**
   * What needs {@code source}: everything reachable over the forward, producer-to-consumer index.
   * Plan 13.5's "reverse dependencies" mode.
   */
  public static Result dependents(CsrGraph forward, int source, int maxDepth, int nodeLimit) {
    return dependents(forward, source, maxDepth, nodeLimit, DEFAULT_EDGE_LIMIT);
  }

  /** {@link #dependents(CsrGraph, int, int, int)}, with an explicit edge budget. */
  public static Result dependents(
      CsrGraph forward, int source, int maxDepth, int nodeLimit, int edgeLimit) {
    return traverse(
        forward, source, maxDepth, nodeLimit, edgeLimit, Direction.FORWARD, Mode.DEPENDENTS);
  }

  /**
   * The neighbourhood around a node: both directions, to a shallow depth.
   *
   * <p>Plan 13.5's "selected action neighborhood", and the mode a user reaches for most. Both
   * directions because "what does this need and what needs this" is one question in practice.
   */
  public static Result neighbourhood(
      CsrGraph forward, CsrGraph reverse, int source, int maxDepth, int nodeLimit) {
    return neighbourhood(forward, reverse, source, maxDepth, nodeLimit, DEFAULT_EDGE_LIMIT);
  }

  /** {@link #neighbourhood(CsrGraph, CsrGraph, int, int, int)}, with an edge budget. */
  public static Result neighbourhood(
      CsrGraph forward, CsrGraph reverse, int source, int maxDepth, int nodeLimit, int edgeLimit) {
    Result out =
        traverse(
            forward, source, maxDepth, nodeLimit, edgeLimit, Direction.FORWARD, Mode.DEPENDENTS);
    Result back =
        traverse(
            reverse, source, maxDepth, nodeLimit, edgeLimit, Direction.REVERSE, Mode.DEPENDENCIES);

    LinkedHashSet<Integer> mergedNodes = new LinkedHashSet<>();
    boolean hitNodeLimit = out.hitNodeLimit() || back.hitNodeLimit();
    for (List<Integer> side : List.of(out.nodes(), back.nodes())) {
      for (int node : side) {
        if (mergedNodes.contains(node)) {
          continue;
        }
        if (mergedNodes.size() >= nodeLimit) {
          hitNodeLimit = true;
          break;
        }
        mergedNodes.add(node);
      }
    }

    LinkedHashSet<Edge> mergedEdges = new LinkedHashSet<>();
    boolean hitEdgeLimit = out.hitEdgeLimit() || back.hitEdgeLimit();
    for (List<Edge> side : List.of(out.edges(), back.edges())) {
      for (Edge edge : side) {
        if (!mergedNodes.contains(edge.from())
            || !mergedNodes.contains(edge.to())
            || mergedEdges.contains(edge)) {
          continue;
        }
        if (mergedEdges.size() >= edgeLimit) {
          hitEdgeLimit = true;
          break;
        }
        mergedEdges.add(edge);
      }
    }
    return new Result(
        Mode.NEIGHBOURHOOD,
        List.copyOf(mergedNodes),
        List.copyOf(mergedEdges),
        out.totalNodes(),
        out.totalEdges(),
        nodeLimit,
        edgeLimit,
        hitNodeLimit,
        hitEdgeLimit);
  }

  /**
   * The nodes on a path, as a subgraph.
   *
   * <p>Plan 13.5's "path between two nodes" and "critical path" both land here: a path is a list of
   * nodes and the edges between consecutive ones.
   */
  public static Result path(CsrGraph forward, List<Integer> nodes, Mode mode) {
    return path(forward, nodes, mode, DEFAULT_NODE_LIMIT, DEFAULT_EDGE_LIMIT);
  }

  /**
   * The nodes on a path, refusing the whole drawing before allocating it when it exceeds either
   * detailed-drawing budget.
   *
   * <p>A clipped path is not a path, so this never returns a prefix and calls it complete. The
   * exception reports the exact requested size for the UI.
   */
  public static Result path(
      CsrGraph forward, List<Integer> nodes, Mode mode, int nodeLimit, int edgeLimit) {
    requirePathWithinLimits(nodes.size(), mode, nodeLimit, edgeLimit);
    List<Edge> edges = new ArrayList<>(Math.max(0, nodes.size() - 1));
    for (int i = 0; i + 1 < nodes.size(); i++) {
      edges.add(new Edge(nodes.get(i), nodes.get(i + 1)));
    }
    return new Result(
        mode,
        List.copyOf(nodes),
        edges,
        forward.nodeCount(),
        forward.edgeCount(),
        nodeLimit,
        edgeLimit,
        false,
        false);
  }

  /** Validates an explicit path before any node or edge list is copied. */
  public static void requirePathWithinLimits(
      int requestedNodes, Mode mode, int nodeLimit, int edgeLimit) {
    long requestedEdges = Math.max(0L, (long) requestedNodes - 1L);
    if (requestedNodes <= nodeLimit && requestedEdges <= edgeLimit) {
      return;
    }
    String subject = mode == Mode.CRITICAL_PATH ? "The critical path" : "The requested path";
    throw new PathLimitExceededException(
        subject
            + " has "
            + requestedNodes
            + " actions and "
            + requestedEdges
            + " dependencies, exceeding the drawing budget of "
            + nodeLimit
            + " actions and "
            + edgeLimit
            + " dependencies. Nothing was drawn;"
            + " raise the Node or Edge budget and try again.",
        requestedNodes,
        requestedEdges,
        nodeLimit,
        edgeLimit);
  }

  /**
   * The whole graph, when it fits.
   *
   * <p>Returns an empty extraction marked {@code hitLimit} when it does not, rather than a
   * truncated one: a "whole graph" that silently showed the first fifty thousand nodes would be the
   * most misleading view in the application.
   */
  public static Result whole(CsrGraph forward, int nodeLimit, int edgeLimit) {
    requireTraversalLimits(nodeLimit, edgeLimit);
    long nodes = forward.nodeCount();
    long edges = forward.edgeCount();
    boolean hitNodeLimit = nodes > nodeLimit;
    boolean hitEdgeLimit = edges > edgeLimit;
    if (hitNodeLimit || hitEdgeLimit) {
      return new Result(
          Mode.WHOLE,
          List.of(),
          List.of(),
          nodes,
          edges,
          nodeLimit,
          edgeLimit,
          hitNodeLimit,
          hitEdgeLimit);
    }
    List<Integer> all = new ArrayList<>((int) nodes);
    List<Edge> allEdges = new ArrayList<>((int) edges);
    for (int node = 0; node < nodes; node++) {
      all.add(node);
      int from = node;
      forward.forEachNeighbor(node, to -> allEdges.add(new Edge(from, to)));
    }
    return new Result(Mode.WHOLE, all, allEdges, nodes, edges, nodeLimit, edgeLimit, false, false);
  }

  private enum Direction {
    FORWARD,
    REVERSE
  }

  private static Result traverse(
      CsrGraph graph,
      int source,
      int maxDepth,
      int nodeLimit,
      int edgeLimit,
      Direction direction,
      Mode mode) {
    requireTraversalLimits(nodeLimit, edgeLimit);
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth must not be negative: " + maxDepth);
    }
    int nodeCount = Math.toIntExact(graph.nodeCount());
    if (source < 0 || source >= nodeCount) {
      throw new IndexOutOfBoundsException("node " + source + " is outside a graph of " + nodeCount);
    }

    int boundedNodes = Math.min(nodeCount, nodeLimit);
    List<Integer> visited = new ArrayList<>(Math.min(boundedNodes, 1_024));
    VisitedNodes inside = new VisitedNodes();
    visited.add(source);
    inside.add(source);

    boolean hitNodeLimit = false;
    int levelStart = 0;
    int levelEnd = 1;
    int depth = 0;
    traversal:
    while (levelStart < levelEnd && depth < maxDepth) {
      for (int at = levelStart; at < levelEnd; at++) {
        int node = visited.get(at);
        long neighborEnd = graph.neighborsEnd(node);
        for (long edge = graph.neighborsBegin(node); edge < neighborEnd; edge++) {
          int target = graph.neighborAt(edge);
          if (inside.contains(target)) {
            continue;
          }
          if (visited.size() >= boundedNodes) {
            hitNodeLimit = true;
            break traversal;
          }
          inside.add(target);
          visited.add(target);
        }
      }
      levelStart = levelEnd;
      levelEnd = visited.size();
      depth++;
    }

    List<Edge> edges = new ArrayList<>(Math.min(edgeLimit, 1_024));
    boolean hitEdgeLimit = false;
    edgeCollection:
    for (int from : visited) {
      long neighborEnd = graph.neighborsEnd(from);
      for (long edge = graph.neighborsBegin(from); edge < neighborEnd; edge++) {
        int to = graph.neighborAt(edge);
        if (!inside.contains(to)) {
          continue;
        }
        if (edges.size() >= edgeLimit) {
          hitEdgeLimit = true;
          break edgeCollection;
        }
        // Reversed back to producer-to-consumer, so a subgraph drawn from a reverse traversal has
        // its arrows the right way round.
        edges.add(direction == Direction.FORWARD ? new Edge(from, to) : new Edge(to, from));
      }
    }
    return new Result(
        mode,
        visited,
        edges,
        graph.nodeCount(),
        graph.edgeCount(),
        nodeLimit,
        edgeLimit,
        hitNodeLimit,
        hitEdgeLimit);
  }

  private static void requireTraversalLimits(int nodeLimit, int edgeLimit) {
    if (nodeLimit <= 0) {
      throw new IllegalArgumentException("nodeLimit must be positive: " + nodeLimit);
    }
    if (edgeLimit < 0) {
      throw new IllegalArgumentException("edgeLimit must not be negative: " + edgeLimit);
    }
  }

  /** A primitive, dynamically growing membership set whose memory follows visited nodes. */
  private static final class VisitedNodes {

    private int[] table = new int[16];
    private int size;

    boolean add(int node) {
      if ((size + 1L) * 2L > table.length) {
        grow();
      }
      return insert(table, node);
    }

    boolean contains(int node) {
      int encoded = node + 1;
      int at = slot(node, table.length);
      while (table[at] != 0) {
        if (table[at] == encoded) {
          return true;
        }
        at = (at + 1) & (table.length - 1);
      }
      return false;
    }

    private boolean insert(int[] into, int node) {
      int encoded = node + 1;
      int at = slot(node, into.length);
      while (into[at] != 0) {
        if (into[at] == encoded) {
          return false;
        }
        at = (at + 1) & (into.length - 1);
      }
      into[at] = encoded;
      size++;
      return true;
    }

    private void grow() {
      int[] previous = table;
      table = new int[Math.multiplyExact(previous.length, 2)];
      size = 0;
      for (int encoded : previous) {
        if (encoded != 0) {
          insert(table, encoded - 1);
        }
      }
    }

    private static int slot(int node, int capacity) {
      int hash = node * 0x9e3779b9;
      hash ^= hash >>> 16;
      return hash & (capacity - 1);
    }
  }

  /** Which of plan 13.5's display modes produced an extraction. */
  public enum Mode {
    DEPENDENCIES("Dependencies"),
    DEPENDENTS("Reverse dependencies"),
    NEIGHBOURHOOD("Neighbourhood"),
    PATH("Path between two actions"),
    CRITICAL_PATH("Critical path"),
    WHOLE("Whole build"),
    CLUSTERS("Clusters");

    private final String displayName;

    Mode(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }

    /**
     * {@link #displayName()}, naming the nodes for what they are.
     *
     * <p>Only {@code PATH} carries a noun; "Path between two actions" over the label graph would
     * misname both endpoints.
     *
     * @param noun what one node is — {@code "action"} or {@code "target"}
     */
    public String displayName(String noun) {
      return this == PATH ? "Path between two " + noun + "s" : displayName;
    }
  }

  /** One directed edge, in producer-to-consumer order. */
  public record Edge(int from, int to) {}

  /** An explicit path was refused intact because it exceeded a drawing budget. */
  public static final class PathLimitExceededException extends IllegalArgumentException {

    private final int requestedNodes;
    private final long requestedEdges;

    private PathLimitExceededException(
        String message, int requestedNodes, long requestedEdges, int nodeLimit, int edgeLimit) {
      super(message);
      this.requestedNodes = requestedNodes;
      this.requestedEdges = requestedEdges;
    }

    public int requestedNodes() {
      return requestedNodes;
    }

    public long requestedEdges() {
      return requestedEdges;
    }
  }

  /**
   * What an extraction returned, and what it did not.
   *
   * @param totalNodes the whole graph's node count, whatever this extraction shows. Plan 13.6:
   *     exact totals remain visible.
   * @param totalEdges the whole graph's edge count, including dependencies outside this extraction
   * @param nodeLimit the node budget active for this extraction
   * @param edgeLimit the edge budget active for this extraction
   * @param hitNodeLimit whether node discovery stopped because it ran out of node budget
   * @param hitEdgeLimit whether dependency collection stopped because it ran out of edge budget
   */
  public record Result(
      Mode mode,
      List<Integer> nodes,
      List<Edge> edges,
      long totalNodes,
      long totalEdges,
      int nodeLimit,
      int edgeLimit,
      boolean hitNodeLimit,
      boolean hitEdgeLimit) {

    /** Source-compatible constructor for callers that predate the separate edge-limit status. */
    public Result(
        Mode mode,
        List<Integer> nodes,
        List<Edge> edges,
        long totalNodes,
        long totalEdges,
        boolean hitLimit,
        int nodeLimit) {
      this(
          mode,
          nodes,
          edges,
          totalNodes,
          totalEdges,
          nodeLimit,
          DEFAULT_EDGE_LIMIT,
          hitLimit,
          false);
    }

    public Result {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      if (nodeLimit < 0 || edgeLimit < 0) {
        throw new IllegalArgumentException("result budgets must not be negative");
      }
    }

    /** True when either output budget omitted part of what the query asked for. */
    public boolean hitLimit() {
      return hitNodeLimit || hitEdgeLimit;
    }

    /** True when this is the whole of what the query asked for. */
    public boolean isComplete() {
      return !hitLimit();
    }

    /**
     * What the view must say alongside the drawing.
     *
     * <p>Plan 13.6: never claim the omitted nodes do not exist. An extraction that stopped early
     * says how much it is showing of what, and an extraction that did not still names the totals —
     * because a neighbourhood of nine drawn from a graph of ninety thousand is a different picture
     * from a build with nine actions.
     */
    public String describe() {
      return describe("action");
    }

    /**
     * {@link #describe()}, naming the nodes for what they are.
     *
     * <p>The extraction is graph-agnostic but the sentence is not: a label-graph view that called
     * its nodes "actions" would misstate what is on screen, which is the kind of small wrong that
     * makes every other number suspect.
     *
     * @param noun what one node is — {@code "action"} or {@code "target"}
     */
    public String describe(String noun) {
      if (mode == Mode.WHOLE && hitLimit()) {
        return "This build has "
            + totalNodes
            + " "
            + noun
            + "s and "
            + totalEdges
            + " dependencies, which exceeds the detailed drawing budget of "
            + nodeLimit
            + "-"
            + noun
            + "s and "
            + edgeLimit
            + " dependencies. Nothing is hidden — nothing was drawn; raise the relevant limit,"
            + " narrow the filter, or switch to the cluster view.";
      }
      StringBuilder text = new StringBuilder();
      text.append(mode.displayName(noun))
          .append(": ")
          .append(nodes.size())
          .append(nodes.size() == 1 ? " " + noun : " " + noun + "s")
          .append(" and ")
          .append(edges.size())
          .append(edges.size() == 1 ? " dependency" : " dependencies");
      text.append(", from a graph of ")
          .append(totalNodes)
          .append(' ')
          .append(noun)
          .append("s and ")
          .append(totalEdges)
          .append(" dependencies.");
      if (hitLimit()) {
        text.append(" The search stopped at its ");
        if (hitNodeLimit) {
          text.append(nodeLimit).append('-').append(noun).append(" budget");
        }
        if (hitNodeLimit && hitEdgeLimit) {
          text.append(" and its ");
        }
        if (hitEdgeLimit) {
          text.append(edgeLimit).append("-dependency budget");
        }
        text.append(", so there is more beyond what is drawn.");
      }
      return text.toString();
    }
  }
}

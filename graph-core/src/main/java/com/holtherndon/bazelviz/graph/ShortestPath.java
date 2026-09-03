package com.holtherndon.bazelviz.graph;

import java.util.Arrays;
import java.util.Optional;

/**
 * The shortest unweighted path between two nodes, if there is one.
 *
 * <h2>Bidirectional, and why</h2>
 *
 * <p>A single-source breadth-first search from the start explores every node within the answer's
 * distance. Searching from both ends and meeting in the middle explores every node within half of
 * it, which on a graph whose branching factor is anything above one is the difference between a
 * fast answer and walking the build.
 *
 * <p>It needs the reverse index, which is why this takes both — and the two are required to be the
 * same graph in opposite directions, which {@code CsrBuilder.reverse} guarantees by construction.
 *
 * <h2>Budgeted, because plan 13.3 says so</h2>
 *
 * <p>"Never compute or store a complete transitive closure." A path query on a five-million-node
 * graph with no answer would otherwise visit every node twice. The budget bounds the nodes visited,
 * and running out returns {@link Outcome#BUDGET_EXHAUSTED} rather than {@link Outcome#NO_PATH} —
 * because "I did not find one" and "there is not one" are different answers and only one of them is
 * safe to show as a fact.
 */
public final class ShortestPath {

  /** No node; distinct from node 0. */
  private static final int NONE = -1;

  private final CsrGraph forward;
  private final CsrGraph reverse;

  /**
   * @param forward producer-to-consumer adjacency
   * @param reverse the same edges, reversed
   */
  public ShortestPath(CsrGraph forward, CsrGraph reverse) {
    if (forward.nodeCount() != reverse.nodeCount()) {
      throw new IllegalArgumentException(
          "forward and reverse indexes describe different graphs: "
              + forward.nodeCount()
              + " nodes against "
              + reverse.nodeCount());
    }
    this.forward = forward;
    this.reverse = reverse;
  }

  /**
   * The shortest path from {@code from} to {@code to}, following edge direction.
   *
   * @param maxNodes how many nodes may be visited before giving up
   */
  public Result find(int from, int to, long maxNodes) {
    int nodeCount = Math.toIntExact(forward.nodeCount());
    if (from < 0 || from >= nodeCount || to < 0 || to >= nodeCount) {
      throw new IndexOutOfBoundsException(
          "node out of range: " + from + " -> " + to + " of " + nodeCount);
    }
    if (from == to) {
      return new Result(Outcome.FOUND, new int[] {from}, 1);
    }

    int[] parentForward = filled(nodeCount);
    int[] parentBackward = filled(nodeCount);
    parentForward[from] = from;
    parentBackward[to] = to;

    int[] frontierForward = {from};
    int[] frontierBackward = {to};
    long visited = 2;

    while (frontierForward.length > 0 && frontierBackward.length > 0) {
      // Always expand the smaller frontier. On a graph with a wide side
      // and a narrow one, expanding the wide side first does most of the
      // work the meet-in-the-middle exists to avoid.
      boolean expandForward = frontierForward.length <= frontierBackward.length;
      int[] frontier = expandForward ? frontierForward : frontierBackward;
      int[] parents = expandForward ? parentForward : parentBackward;
      int[] otherParents = expandForward ? parentBackward : parentForward;
      CsrGraph graph = expandForward ? forward : reverse;

      IntBag next = new IntBag();
      for (int node : frontier) {
        int[] meeting = {NONE};
        graph.forEachNeighbor(
            node,
            neighbor -> {
              if (parents[neighbor] != NONE) {
                return;
              }
              parents[neighbor] = node;
              if (otherParents[neighbor] != NONE) {
                meeting[0] = neighbor;
              }
              next.add(neighbor);
            });
        if (meeting[0] != NONE) {
          return new Result(
              Outcome.FOUND,
              assemble(meeting[0], parentForward, parentBackward, from, to),
              visited + next.size());
        }
      }
      visited += next.size();
      if (visited > maxNodes) {
        // Not "no path": the search stopped early, and saying otherwise
        // would present a budget as a fact about the graph.
        return new Result(Outcome.BUDGET_EXHAUSTED, new int[0], visited);
      }
      if (expandForward) {
        frontierForward = next.toArray();
      } else {
        frontierBackward = next.toArray();
      }
    }
    return new Result(Outcome.NO_PATH, new int[0], visited);
  }

  /**
   * Walks both parent arrays out from the meeting node.
   *
   * <p>The forward half comes out reversed and is flipped; the backward half is already in order
   * because its parents point towards the target.
   */
  private static int[] assemble(
      int meeting, int[] parentForward, int[] parentBackward, int from, int to) {
    IntBag head = new IntBag();
    for (int node = meeting; node != from; node = parentForward[node]) {
      head.add(node);
    }
    head.add(from);
    int[] forwardHalf = head.toArray();
    reverseInPlace(forwardHalf);

    IntBag tail = new IntBag();
    for (int node = parentBackward[meeting]; node != to; node = parentBackward[node]) {
      tail.add(node);
    }
    if (meeting != to) {
      tail.add(to);
    }
    int[] backwardHalf = tail.toArray();

    int[] path = Arrays.copyOf(forwardHalf, forwardHalf.length + backwardHalf.length);
    System.arraycopy(backwardHalf, 0, path, forwardHalf.length, backwardHalf.length);
    return path;
  }

  private static void reverseInPlace(int[] values) {
    for (int i = 0, j = values.length - 1; i < j; i++, j--) {
      int swap = values[i];
      values[i] = values[j];
      values[j] = swap;
    }
  }

  private static int[] filled(int size) {
    int[] array = new int[size];
    Arrays.fill(array, NONE);
    return array;
  }

  /** How the search ended. */
  public enum Outcome {
    /** A path exists and is in the result. */
    FOUND,
    /** The whole reachable set was searched and the target was not in it. */
    NO_PATH,
    /**
     * The node budget ran out first.
     *
     * <p>Says nothing about whether a path exists, and must never be shown as though it did.
     */
    BUDGET_EXHAUSTED
  }

  /**
   * @param path node indices from source to target inclusive; empty unless {@link Outcome#FOUND}
   * @param nodesVisited what the search cost, for the budget message
   */
  public record Result(Outcome outcome, int[] path, long nodesVisited) {

    public Result {
      path = path.clone();
    }

    @Override
    public int[] path() {
      return path.clone();
    }

    /** The path, when there is one. */
    public Optional<int[]> found() {
      return outcome == Outcome.FOUND ? Optional.of(path()) : Optional.empty();
    }

    /** Edges traversed, which is one fewer than the nodes. */
    public int length() {
      return path.length == 0 ? 0 : path.length - 1;
    }

    /** The sentence for the user when there is no path to show. */
    public String describe() {
      return switch (outcome) {
        case FOUND ->
            "A path of " + length() + " step" + (length() == 1 ? "" : "s") + " connects them.";
        case NO_PATH ->
            "Nothing connects them: the first does not contribute to the"
                + " second, directly or indirectly.";
        case BUDGET_EXHAUSTED ->
            "The search stopped after "
                + nodesVisited
                + " nodes without reaching the target. Whether a path exists is"
                + " unknown — this is not an answer that there is none.";
      };
    }
  }

  /** A growable int array, so no path search allocates an Integer. */
  private static final class IntBag {
    private int[] values = new int[16];
    private int size;

    void add(int value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = value;
    }

    int size() {
      return size;
    }

    int[] toArray() {
      return Arrays.copyOf(values, size);
    }
  }
}

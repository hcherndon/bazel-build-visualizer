package com.holtherndon.bazelviz.graph;

/**
 * Two-pass CSR construction. Pass 1 counts out-degrees, pass 2 places
 * targets; peak transient memory beyond the final graph is one
 * {@code int[nodeCount]} fill cursor. No boxing, no per-edge objects.
 */
public final class CsrBuilder {

    /** Java array length ceiling (conservative; some VMs reserve a few slots). */
    private static final long MAX_EDGES = Integer.MAX_VALUE - 8;

    private CsrBuilder() {
    }

    public static CsrGraph build(int nodeCount, EdgeStream edges) {
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount " + nodeCount);
        }
        long[] offsets = new long[nodeCount + 1];

        // Pass 1: count degrees into offsets[from + 1], then prefix-sum in place.
        edges.forEach((from, to) -> {
            checkNode(from, nodeCount);
            checkNode(to, nodeCount);
            offsets[from + 1]++;
        });
        for (int i = 0; i < nodeCount; i++) {
            offsets[i + 1] += offsets[i];
        }
        long edgeCount = offsets[nodeCount];
        if (edgeCount > MAX_EDGES) {
            throw new IllegalStateException(
                    "edge count " + edgeCount + " exceeds int-indexed array limit (Phase 0 constraint)");
        }

        // Pass 2: place targets using a per-node fill cursor.
        int[] targets = new int[(int) edgeCount];
        int[] filled = new int[nodeCount];
        edges.forEach((from, to) -> {
            long slot = offsets[from] + filled[from];
            if (slot >= offsets[from + 1]) {
                throw new IllegalStateException(
                        "edge stream is not replayable: node " + from + " emitted more edges on pass 2");
            }
            targets[(int) slot] = to;
            filled[from]++;
        });
        long placed = 0;
        for (int f : filled) {
            placed += f;
        }
        if (placed != edgeCount) {
            throw new IllegalStateException(
                    "edge stream is not replayable: pass 1 counted " + edgeCount + ", pass 2 delivered " + placed);
        }
        return new CsrGraph(offsets, targets);
    }

    /**
     * Transposed graph via the same two-pass technique, reading the source
     * CSR arrays directly. Neighbor lists of the result are sorted ascending
     * (a consequence of scanning source nodes in order), regardless of the
     * input's per-node ordering.
     */
    public static CsrGraph reverse(CsrGraph graph) {
        long[] offsets = graph.rawOffsets();
        int[] targets = graph.rawTargets();
        int nodeCount = offsets.length - 1;

        long[] revOffsets = new long[nodeCount + 1];
        for (int t : targets) {
            revOffsets[t + 1]++;
        }
        for (int i = 0; i < nodeCount; i++) {
            revOffsets[i + 1] += revOffsets[i];
        }

        int[] revTargets = new int[targets.length];
        int[] filled = new int[nodeCount];
        for (int u = 0; u < nodeCount; u++) {
            long end = offsets[u + 1];
            for (long e = offsets[u]; e < end; e++) {
                int t = targets[(int) e];
                revTargets[(int) (revOffsets[t] + filled[t])] = u;
                filled[t]++;
            }
        }
        return new CsrGraph(revOffsets, revTargets);
    }

    private static void checkNode(int node, int nodeCount) {
        if (node < 0 || node >= nodeCount) {
            throw new IllegalArgumentException("node " + node + " out of range [0, " + nodeCount + ")");
        }
    }
}

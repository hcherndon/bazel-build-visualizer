package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphLayout;
import java.util.OptionalInt;
import java.util.function.IntConsumer;

/**
 * Which laid-out nodes are in a rectangle, and which one is under the pointer.
 *
 * <h2>A uniform grid, which plan 13.6 allows for</h2>
 *
 * <p>The plan says "quadtree or equivalent spatial index". This is the
 * equivalent, and for this data it is the better one: a layout produces points
 * on or near a lattice — layered puts them at exact multiples of the layer and
 * row gaps — so the recursive subdivision a quadtree pays for buys nothing. A
 * uniform grid is one counting pass and one filling pass, holds three int arrays
 * and no nodes, and answers a rectangle query by visiting only the cells the
 * rectangle touches.
 *
 * <h2>Positions, not node ids</h2>
 *
 * <p>Everything here is an index into the layout's coordinate arrays. That is
 * not a graph node index and it is certainly not an action id; the layout's
 * {@code nodes()} list is the translation. Keeping the index in position space
 * is what lets it be an array rather than a map.
 *
 * <h2>Degenerate layouts are the normal case, not the edge case</h2>
 *
 * <p>A linear layout has zero height and a one-node layout has zero of both.
 * Both fall out of the sizing below as a single row or a single cell rather than
 * as a division by zero, because a critical path is one of the views a user
 * reaches for most.
 */
public final class GraphSpatialIndex {

    /** Points per cell to aim for: enough that the per-cell scan is short. */
    private static final int TARGET_OCCUPANCY = 4;

    private final double[] x;
    private final double[] y;
    private final double minX;
    private final double minY;
    private final double cellWidth;
    private final double cellHeight;
    private final int columns;
    private final int rows;

    /** CSR over cells: {@code offsets[c]} to {@code offsets[c + 1]} indexes {@code points}. */
    private final int[] offsets;

    private final int[] points;

    private GraphSpatialIndex(
            double[] x, double[] y, double minX, double minY,
            double cellWidth, double cellHeight, int columns, int rows,
            int[] offsets, int[] points) {
        this.x = x;
        this.y = y;
        this.minX = minX;
        this.minY = minY;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.columns = columns;
        this.rows = rows;
        this.offsets = offsets;
        this.points = points;
    }

    /** Indexes a finished layout. */
    public static GraphSpatialIndex of(GraphLayout.Result layout) {
        int count = layout.size();
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            x[i] = layout.xAt(i);
            y[i] = layout.yAt(i);
        }
        if (count == 0) {
            return new GraphSpatialIndex(
                    x, y, 0, 0, 1, 1, 1, 1, new int[] {0, 0}, new int[0]);
        }

        double[] box = layout.bounds().orElseThrow();
        double width = Math.max(box[2] - box[0], 1e-9);
        double height = Math.max(box[3] - box[1], 1e-9);

        int target = Math.max(1, count / TARGET_OCCUPANCY);
        // Proportional to the extents, so a wide flat layout gets columns and a
        // linear one -- height 1e-9 -- collapses to a single row rather than
        // producing an unbounded column count.
        int columns = Math.max(1, Math.min(target,
                (int) Math.round(Math.sqrt(target * width / height))));
        int rows = Math.max(1, (int) Math.ceil((double) target / columns));
        double cellWidth = width / columns;
        double cellHeight = height / rows;

        int[] counts = new int[columns * rows + 1];
        int[] cellOf = new int[count];
        for (int i = 0; i < count; i++) {
            int column = clamp((int) ((x[i] - box[0]) / cellWidth), columns);
            int row = clamp((int) ((y[i] - box[1]) / cellHeight), rows);
            int cell = row * columns + column;
            cellOf[i] = cell;
            counts[cell + 1]++;
        }
        for (int cell = 0; cell < columns * rows; cell++) {
            counts[cell + 1] += counts[cell];
        }
        int[] cursor = counts.clone();
        int[] points = new int[count];
        for (int i = 0; i < count; i++) {
            points[cursor[cellOf[i]]++] = i;
        }
        return new GraphSpatialIndex(
                x, y, box[0], box[1], cellWidth, cellHeight, columns, rows, counts, points);
    }

    private static int clamp(int value, int limit) {
        return Math.min(limit - 1, Math.max(0, value));
    }

    public int size() {
        return points.length;
    }

    /**
     * Every position inside the rectangle, in no particular order.
     *
     * <p>The culling call. Its cost is the cells the rectangle touches plus the
     * points in them, not the size of the graph, which is the entire reason for
     * the index: panning around a corner of a fifty-thousand-node layout should
     * cost what the corner costs.
     */
    public void forEachInRect(
            double left, double top, double right, double bottom, IntConsumer positions) {
        if (points.length == 0 || right < left || bottom < top) {
            return;
        }
        int firstColumn = clamp((int) Math.floor((left - minX) / cellWidth), columns);
        int lastColumn = clamp((int) Math.floor((right - minX) / cellWidth), columns);
        int firstRow = clamp((int) Math.floor((top - minY) / cellHeight), rows);
        int lastRow = clamp((int) Math.floor((bottom - minY) / cellHeight), rows);

        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int cell = row * columns + column;
                for (int p = offsets[cell]; p < offsets[cell + 1]; p++) {
                    int position = points[p];
                    // The cell is a superset of the rectangle at its edges, so
                    // each candidate is still tested exactly.
                    if (x[position] >= left && x[position] <= right
                            && y[position] >= top && y[position] <= bottom) {
                        positions.accept(position);
                    }
                }
            }
        }
    }

    /** Every position inside the rectangle, as an array; the box-select call. */
    public int[] within(double left, double top, double right, double bottom) {
        // Counted then filled, so the array is allocated once at exactly the
        // right size. The scan is over cells the rectangle touches, so doing it
        // twice is cheaper than growing an array over a large selection.
        int[] count = {0};
        forEachInRect(left, top, right, bottom, position -> count[0]++);
        int[] found = new int[count[0]];
        int[] next = {0};
        forEachInRect(left, top, right, bottom, position -> found[next[0]++] = position);
        return found;
    }

    /**
     * The position nearest a point, within a radius.
     *
     * <p>The hit-testing call. The radius is what makes a click on empty canvas
     * a deselection rather than a selection of whatever happened to be closest —
     * a graph with one node in it should not respond to a click in the far
     * corner by selecting that node.
     */
    public OptionalInt nearest(double worldX, double worldY, double radius) {
        return nearest(worldX, worldY, radius, position -> true);
    }

    /**
     * {@link #nearest(double, double, double)} over an accepted subset.
     *
     * <p>For the canvas's drag overlay: a dragged node's indexed position is
     * where the layout put it, not where the user moved it, so hit testing
     * excludes dragged positions here and tests them separately at their
     * displaced coordinates. The index itself is never mutated — it is shared
     * with the layout cache — which is why this is a filter and not an update.
     */
    public OptionalInt nearest(
            double worldX, double worldY, double radius,
            java.util.function.IntPredicate accept) {
        if (points.length == 0 || radius <= 0) {
            return OptionalInt.empty();
        }
        int best = -1;
        double bestDistance = radius * radius;
        int firstColumn = clamp((int) Math.floor((worldX - radius - minX) / cellWidth), columns);
        int lastColumn = clamp((int) Math.floor((worldX + radius - minX) / cellWidth), columns);
        int firstRow = clamp((int) Math.floor((worldY - radius - minY) / cellHeight), rows);
        int lastRow = clamp((int) Math.floor((worldY + radius - minY) / cellHeight), rows);

        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int cell = row * columns + column;
                for (int p = offsets[cell]; p < offsets[cell + 1]; p++) {
                    int position = points[p];
                    if (!accept.test(position)) {
                        continue;
                    }
                    double dx = x[position] - worldX;
                    double dy = y[position] - worldY;
                    double distance = dx * dx + dy * dy;
                    // Ties go to the lower position, so hit testing is
                    // deterministic on a lattice where several nodes really are
                    // equidistant.
                    if (distance < bestDistance
                            || (distance == bestDistance && best >= 0 && position < best)) {
                        bestDistance = distance;
                        best = position;
                    }
                }
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /** How many cells the grid has; for tests and for reasoning about cost. */
    int cellCount() {
        return columns * rows;
    }
}

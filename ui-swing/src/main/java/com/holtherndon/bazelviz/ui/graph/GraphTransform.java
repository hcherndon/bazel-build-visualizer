package com.holtherndon.bazelviz.ui.graph;

/**
 * Immutable world-to-screen mapping for the graph canvas.
 *
 * <p>{@code screenX = (worldX - offsetX) * scale}, and the same in y. The
 * two-dimensional twin of {@link com.holtherndon.bazelviz.ui.timeline.TimelineTransform},
 * and deliberately its shape: one scale for both axes, because a graph drawn
 * with independent x and y zoom stops looking like a graph.
 *
 * <p>Plan 17.7 wants world coordinates in doubles. Offsets are doubles too, so
 * that composing hundreds of pan and zoom steps does not accumulate the rounding
 * drift that would slowly slide a node away from the thing it was pinned to.
 *
 * @param offsetX world x drawn at screen x = 0
 * @param offsetY world y drawn at screen y = 0
 * @param scale pixels per world unit; strictly positive and finite
 */
public record GraphTransform(double offsetX, double offsetY, double scale) {

    /** How far the view may zoom, so a scroll wheel cannot reach a degenerate scale. */
    public static final double MIN_SCALE = 1e-4;

    public static final double MAX_SCALE = 40;

    public GraphTransform {
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException(
                    "invalid transform: offset=(" + offsetX + ", " + offsetY + ") scale=" + scale);
        }
    }

    /** The identity: world units are pixels, origin at the top left. */
    public static GraphTransform identity() {
        return new GraphTransform(0, 0, 1);
    }

    /**
     * The transform that fits a bounding box into a viewport, with a margin.
     *
     * <p>An empty or degenerate box — one node, or a linear layout, which has no
     * height at all — would divide by zero, so each axis gets a minimum extent.
     * The result is a readable view of a single node rather than an exception or
     * an infinite zoom.
     */
    public static GraphTransform fit(
            double[] bounds, int widthPixels, int heightPixels, double marginPixels) {
        if (widthPixels <= 0 || heightPixels <= 0) {
            return identity();
        }
        double usableWidth = Math.max(1, widthPixels - 2 * marginPixels);
        double usableHeight = Math.max(1, heightPixels - 2 * marginPixels);
        double worldWidth = Math.max(1e-6, bounds[2] - bounds[0]);
        double worldHeight = Math.max(1e-6, bounds[3] - bounds[1]);

        double scale = clampScale(Math.min(usableWidth / worldWidth, usableHeight / worldHeight));
        // Centre what is left over, so a wide graph in a tall window does not
        // sit against the top edge.
        double centreX = (bounds[0] + bounds[2]) / 2;
        double centreY = (bounds[1] + bounds[3]) / 2;
        return new GraphTransform(
                centreX - widthPixels / (2 * scale),
                centreY - heightPixels / (2 * scale),
                scale);
    }

    public static double clampScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0) {
            return 1;
        }
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
    }

    public double screenX(double worldX) {
        return (worldX - offsetX) * scale;
    }

    public double screenY(double worldY) {
        return (worldY - offsetY) * scale;
    }

    public double worldX(double screenX) {
        return offsetX + screenX / scale;
    }

    public double worldY(double screenY) {
        return offsetY + screenY / scale;
    }

    /**
     * Pans so content follows a pointer dragged by {@code (dx, dy)} pixels: what
     * was under the pointer stays under the pointer.
     */
    public GraphTransform pannedByPixels(double dx, double dy) {
        return new GraphTransform(offsetX - dx / scale, offsetY - dy / scale, scale);
    }

    /**
     * Multiplies the zoom while keeping the world point under {@code (anchorX,
     * anchorY)} exactly there.
     *
     * <p>Anchored zoom rather than centre zoom: a user scrolling over a node
     * means "closer to that", and a view that zoomed to its own centre would
     * slide their target off the screen.
     */
    public GraphTransform zoomedAround(double anchorX, double anchorY, double factor) {
        double zoomed = clampScale(scale * factor);
        if (zoomed == scale) {
            return this;
        }
        double worldAnchorX = worldX(anchorX);
        double worldAnchorY = worldY(anchorY);
        return new GraphTransform(
                worldAnchorX - anchorX / zoomed, worldAnchorY - anchorY / zoomed, zoomed);
    }

    /** The world rectangle currently on screen, as min-x, min-y, max-x, max-y. */
    public double[] visibleWorld(int widthPixels, int heightPixels) {
        return new double[] {
            offsetX, offsetY, worldX(widthPixels), worldY(heightPixels),
        };
    }
}

package com.holtherndon.bazelviz.ui.timeline;

/**
 * Immutable world (microseconds) to screen (pixels) mapping for the timeline:
 * {@code x = (worldMicros - offsetMicros) * pixelsPerMicro}. Offsets are kept
 * as doubles so pan/zoom compositions do not accumulate rounding drift; with
 * wall spans below ~2^40 microseconds the 53-bit mantissa keeps sub-pixel
 * precision at every reachable zoom.
 *
 * @param offsetMicros   world time rendered at x = 0
 * @param pixelsPerMicro zoom factor; strictly positive and finite
 */
public record TimelineTransform(double offsetMicros, double pixelsPerMicro) {

    public TimelineTransform {
        if (!Double.isFinite(offsetMicros) || !Double.isFinite(pixelsPerMicro)
                || pixelsPerMicro <= 0) {
            throw new IllegalArgumentException(
                    "invalid transform: offset=" + offsetMicros + " ppm=" + pixelsPerMicro);
        }
    }

    /** Transform showing exactly {@code [startMicros, endMicros)} across {@code widthPixels}. */
    public static TimelineTransform fit(long startMicros, long endMicros, int widthPixels) {
        if (endMicros <= startMicros || widthPixels <= 0) {
            throw new IllegalArgumentException(
                    "cannot fit [" + startMicros + ", " + endMicros + ") into " + widthPixels + " px");
        }
        return new TimelineTransform(startMicros, (double) widthPixels / (endMicros - startMicros));
    }

    public double xForMicros(double worldMicros) {
        return (worldMicros - offsetMicros) * pixelsPerMicro;
    }

    public double microsAtX(double x) {
        return offsetMicros + x / pixelsPerMicro;
    }

    /**
     * Pans so content follows a pointer dragged by {@code dxPixels}: the world
     * time previously at x is afterwards at x + dxPixels.
     */
    public TimelineTransform pannedByPixels(double dxPixels) {
        return new TimelineTransform(offsetMicros - dxPixels / pixelsPerMicro, pixelsPerMicro);
    }

    /**
     * Multiplies the zoom by {@code factor} while keeping the world time under
     * {@code anchorX} at exactly {@code anchorX}.
     */
    public TimelineTransform zoomedAround(double anchorX, double factor) {
        double newPpm = pixelsPerMicro * factor;
        double anchorWorld = microsAtX(anchorX);
        return new TimelineTransform(anchorWorld - anchorX / newPpm, newPpm);
    }
}

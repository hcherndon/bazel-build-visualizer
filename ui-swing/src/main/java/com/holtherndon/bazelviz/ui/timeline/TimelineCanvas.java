package com.holtherndon.bazelviz.ui.timeline;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Objects;
import javax.swing.JComponent;

/**
 * Aggregate timeline view (plan 14.1-14.3 spike). Paints density columns for
 * the visible window straight from an immutable {@link TimelineLodIndex}:
 * column color intensity follows per-bin overlap density, with a red tint
 * whose strength follows the failure fraction. Mouse drag pans; the wheel
 * zooms around the cursor.
 *
 * <p>Paint reads only prebuilt primitive arrays and a precomputed color
 * palette — no data loading, no locking, no per-bin allocation. The transform
 * is confined to the painting thread (EDT in a window, the caller's thread in
 * offscreen harnesses).
 */
public final class TimelineCanvas extends JComponent {

    private static final int AXIS_HEIGHT = 26;
    private static final int INTENSITY_STEPS = 64;
    private static final int FAIL_STEPS = 8;
    private static final double WHEEL_ZOOM_STEP = 1.15;
    /** Max zoom: one pixel per microsecond. */
    private static final double MAX_PIXELS_PER_MICRO = 1.0;
    private static final double MIN_TICK_SPACING_PX = 90.0;
    private static final double[] TICK_MULTIPLIERS = {1, 2, 5, 10};

    private static final Color BACKGROUND = new Color(0x14, 0x16, 0x1e);
    private static final Color AXIS_BACKGROUND = new Color(0x1c, 0x1f, 0x2a);
    private static final Color AXIS_TEXT = new Color(0x9a, 0xa4, 0xb8);
    private static final Color TICK_LINE = new Color(0x2e, 0x33, 0x44);
    private static final Color OVERLAY_TEXT = new Color(0xd8, 0xde, 0xea);
    private static final Color OVERLAY_BACKGROUND = new Color(0x14, 0x16, 0x1e, 200);
    private static final Font AXIS_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    private static final Font OVERLAY_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final TimelineLodIndex index;
    /** [failStep][intensityStep], precomputed so paint never constructs colors. */
    private final Color[][] palette;

    private TimelineTransform transform; // null until the first fit; painting-thread confined
    private int lastLevel;
    private int lastBinsPainted;
    private double lastPaintMillis;

    public TimelineCanvas(TimelineLodIndex index) {
        this.index = Objects.requireNonNull(index, "index");
        this.palette = buildPalette();
        setOpaque(true);
        setPreferredSize(new Dimension(1200, 400));
        MouseAdapter mouse = new MouseAdapter() {
            private int lastX;

            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastX;
                lastX = e.getX();
                if (dx != 0 && transform != null) {
                    setTransform(transform.pannedByPixels(dx));
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (transform != null) {
                    double factor = Math.pow(WHEEL_ZOOM_STEP, -e.getPreciseWheelRotation());
                    setTransform(transform.zoomedAround(e.getX(), factor));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Shows the whole wall span; call after the component has a real width. */
    public void fitToWall() {
        setTransform(TimelineTransform.fit(
                index.wallStartMicros(), index.wallEndMicros(), Math.max(1, getWidth())));
    }

    /** Replaces the view transform (clamped to sane pan/zoom bounds) and repaints. */
    public void setTransform(TimelineTransform t) {
        this.transform = clamped(Objects.requireNonNull(t, "transform"));
        repaint();
    }

    /** Current transform, or null before the first layout/fit. */
    public TimelineTransform transform() {
        return transform;
    }

    /** Duration of the most recent column+axis paint pass, in milliseconds. */
    public double lastPaintMillis() {
        return lastPaintMillis;
    }

    @Override
    protected void paintComponent(Graphics g) {
        long paintStart = System.nanoTime();
        Graphics2D g2 = (Graphics2D) g;
        int width = getWidth();
        int height = getHeight();
        if (transform == null) {
            transform = clamped(TimelineTransform.fit(
                    index.wallStartMicros(), index.wallEndMicros(), Math.max(1, width)));
        }
        g2.setColor(BACKGROUND);
        g2.fillRect(0, 0, width, height);

        int plotHeight = Math.max(0, height - AXIS_HEIGHT);
        long visibleFrom = (long) Math.floor(transform.microsAtX(0));
        long visibleTo = (long) Math.ceil(transform.microsAtX(width));
        int level = index.levelForScale(transform.pixelsPerMicro());
        int painted = paintColumns(g2, level, visibleFrom, visibleTo, width, plotHeight);
        paintAxis(g2, width, height, plotHeight, visibleFrom, visibleTo);

        lastLevel = level;
        lastBinsPainted = painted;
        lastPaintMillis = (System.nanoTime() - paintStart) / 1_000_000.0;
        paintOverlay(g2, visibleFrom, visibleTo);
    }

    private int paintColumns(Graphics2D g2, int level, long visibleFrom, long visibleTo,
            int width, int plotHeight) {
        long from = Math.max(visibleFrom, index.wallStartMicros());
        long to = Math.min(visibleTo, index.wallEndMicros());
        long maxOverlap = index.maxOverlapMicros(level);
        if (plotHeight <= 0 || from >= to || maxOverlap == 0) {
            return 0;
        }
        int b0 = index.binIndexOf(level, from);
        int b1 = index.binIndexOf(level, to - 1);
        long binWidth = index.binWidthMicros(level);
        int painted = 0;
        for (int b = b0; b <= b1; b++) {
            int active = index.activeCount(level, b);
            if (active == 0) {
                continue;
            }
            long binStart = index.binStartMicros(level, b);
            int x0 = (int) Math.floor(transform.xForMicros(binStart));
            int x1 = (int) Math.floor(transform.xForMicros(binStart + binWidth));
            if (x1 <= x0) {
                x1 = x0 + 1;
            }
            if (x1 < 0 || x0 > width) {
                continue;
            }
            double density = (double) index.overlapMicros(level, b) / maxOverlap;
            int intensity = (int) Math.round(Math.sqrt(density) * (INTENSITY_STEPS - 1));
            g2.setColor(palette[failStep(index.failureCount(level, b), active)][intensity]);
            g2.fillRect(x0, 0, x1 - x0, plotHeight);
            painted++;
        }
        return painted;
    }

    private void paintAxis(Graphics2D g2, int width, int height, int plotHeight,
            long visibleFrom, long visibleTo) {
        g2.setColor(AXIS_BACKGROUND);
        g2.fillRect(0, plotHeight, width, height - plotHeight);
        g2.setFont(AXIS_FONT);
        long step = niceStep(MIN_TICK_SPACING_PX / transform.pixelsPerMicro());
        long tick = Math.ceilDiv(visibleFrom, step) * step;
        for (; tick <= visibleTo; tick += step) {
            int x = (int) Math.round(transform.xForMicros(tick));
            g2.setColor(TICK_LINE);
            g2.drawLine(x, 0, x, plotHeight);
            g2.setColor(AXIS_TEXT);
            g2.drawString(formatMicros(tick, step), x + 3, height - 8);
        }
    }

    private void paintOverlay(Graphics2D g2, long visibleFrom, long visibleTo) {
        long span = visibleTo - visibleFrom;
        String text = String.format(
                "window %s .. %s (%s)  level %d/%d bin=%s  bins %d  paint %.2f ms",
                formatMicros(visibleFrom, Math.max(1, span / 100)),
                formatMicros(visibleTo, Math.max(1, span / 100)),
                formatDuration(span),
                lastLevel, index.levelCount() - 1,
                formatDuration(index.binWidthMicros(lastLevel)),
                lastBinsPainted, lastPaintMillis);
        g2.setFont(OVERLAY_FONT);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(OVERLAY_BACKGROUND);
        g2.fillRect(4, 4, fm.stringWidth(text) + 12, fm.getHeight() + 8);
        g2.setColor(OVERLAY_TEXT);
        g2.drawString(text, 10, 8 + fm.getAscent());
    }

    /**
     * Failure fractions on real builds are tiny (well under 1%), so a linear
     * mapping would never leave step 0; any failure gets at least step 1 and
     * the rest of the ramp follows sqrt(fraction).
     */
    private static int failStep(int failures, int active) {
        if (failures == 0) {
            return 0;
        }
        double fraction = (double) failures / active;
        int step = 1 + (int) Math.round(Math.sqrt(fraction) * (FAIL_STEPS - 2));
        return Math.min(step, FAIL_STEPS - 1);
    }

    private TimelineTransform clamped(TimelineTransform t) {
        int width = Math.max(1, getWidth());
        double wallSpan = index.wallEndMicros() - index.wallStartMicros();
        double minPpm = width / (2.0 * wallSpan); // zoom-out floor: wall fills half the width
        double ppm = Math.clamp(t.pixelsPerMicro(), minPpm, Math.max(MAX_PIXELS_PER_MICRO, minPpm));
        double visible = width / ppm;
        double offset = Math.clamp(t.offsetMicros(),
                index.wallStartMicros() - 0.5 * visible,
                index.wallEndMicros() - 0.5 * visible);
        return ppm == t.pixelsPerMicro() && offset == t.offsetMicros()
                ? t
                : new TimelineTransform(offset, ppm);
    }

    /** Smallest 1-2-5 ladder step (in micros) that is >= rawMicros. */
    static long niceStep(double rawMicros) {
        double raw = Math.max(1.0, rawMicros);
        double pow10 = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : TICK_MULTIPLIERS) {
            if (pow10 * m >= raw) {
                return Math.max(1, (long) (pow10 * m));
            }
        }
        throw new AssertionError("unreachable: multiplier 10 always satisfies");
    }

    /** Formats an instant with precision appropriate to the tick step. */
    static String formatMicros(long micros, long stepMicros) {
        if (stepMicros >= 1_000_000) {
            long totalSec = Math.round(micros / 1_000_000.0);
            long abs = Math.abs(totalSec);
            String sign = totalSec < 0 ? "-" : "";
            return abs >= 60
                    ? String.format("%s%dm%02ds", sign, abs / 60, abs % 60)
                    : sign + abs + "s";
        }
        double sec = micros / 1_000_000.0;
        if (stepMicros >= 100_000) {
            return String.format("%.1fs", sec);
        }
        if (stepMicros >= 10_000) {
            return String.format("%.2fs", sec);
        }
        if (stepMicros >= 1_000) {
            return String.format("%.3fs", sec);
        }
        return String.format("%.6fs", sec);
    }

    private static String formatDuration(long micros) {
        if (micros >= 60_000_000) {
            return String.format("%.1fmin", micros / 60_000_000.0);
        }
        if (micros >= 1_000_000) {
            return String.format("%.1fs", micros / 1_000_000.0);
        }
        if (micros >= 1_000) {
            return String.format("%.1fms", micros / 1_000.0);
        }
        return micros + "us";
    }

    private static Color[][] buildPalette() {
        Color[][] palette = new Color[FAIL_STEPS][INTENSITY_STEPS];
        for (int f = 0; f < FAIL_STEPS; f++) {
            double fail = (double) f / (FAIL_STEPS - 1);
            for (int i = 0; i < INTENSITY_STEPS; i++) {
                double v = (double) i / (INTENSITY_STEPS - 1);
                double r = lerp(30, 120, v);
                double gr = lerp(48, 195, v);
                double b = lerp(84, 255, v);
                palette[f][i] = new Color(
                        (int) lerp(r, 235, fail),
                        (int) lerp(gr, 70, fail),
                        (int) lerp(b, 70, fail));
            }
        }
        return palette;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

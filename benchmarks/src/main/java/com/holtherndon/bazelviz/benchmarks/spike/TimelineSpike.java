package com.holtherndon.bazelviz.benchmarks.spike;

import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticAction;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import com.holtherndon.bazelviz.ui.timeline.SpanSource;
import com.holtherndon.bazelviz.ui.timeline.TimelineCanvas;
import com.holtherndon.bazelviz.ui.timeline.TimelineLodIndex;
import com.holtherndon.bazelviz.ui.timeline.TimelineTransform;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Phase 0 spike: custom Java2D aggregate timeline (plan section "Phase 0",
 * benchmark target plan 20.2: p95 frame under 33 ms).
 *
 * <p>Modes:
 * <ul>
 *   <li>default: interactive window (drag pans, wheel zooms);
 *       {@code -Dbbv.smoke=true} closes it automatically. Gradle CLI
 *       {@code -D} properties stay in the daemon and never reach this forked
 *       JVM, so {@code --smoke} and {@code BBV_SMOKE=true} work too.</li>
 *   <li>{@code --offscreen}: headless; paints {@value #MEASURED_FRAMES} frames at
 *       randomized pan/zoom into a 1600x900 RGB image and prints frame statistics
 *       with a PASS/FAIL verdict.</li>
 *   <li>{@code --tier3}: 5M actions instead of the TIER2 default of 1M.</li>
 * </ul>
 */
public final class TimelineSpike {

    private static final long SEED = 42;
    private static final int FRAME_WIDTH = 1600;
    private static final int FRAME_HEIGHT = 900;
    private static final int WARMUP_FRAMES = 30;
    private static final int MEASURED_FRAMES = 300;
    private static final double TARGET_P95_MILLIS = 33.0;
    /** Deepest randomized zoom shows a 10 ms window. */
    private static final double MIN_VISIBLE_MICROS = 10_000;

    private TimelineSpike() {}

    public static void main(String[] args) {
        boolean offscreen = hasArg(args, "--offscreen");
        SyntheticScale scale = hasArg(args, "--tier3") ? SyntheticScale.TIER3 : SyntheticScale.TIER2;
        if (offscreen) {
            System.setProperty("java.awt.headless", "true");
        }

        SyntheticActionGenerator generator = new SyntheticActionGenerator(scale, SEED);
        SpanSource source = spanSourceOver(generator);
        System.out.printf(Locale.ROOT, "Timeline spike: %s (%,d actions), wall %.1f s%n",
                scale, generator.actionCount(), generator.buildWallMicros() / 1e6);

        long buildStart = System.nanoTime();
        TimelineLodIndex index = TimelineLodIndex.build(source, 0, generator.buildWallMicros());
        double buildSeconds = (System.nanoTime() - buildStart) / 1e9;
        System.out.printf(Locale.ROOT, "LOD build: %.3f s streaming %,d spans%n",
                buildSeconds, index.totalSpanCount());
        for (int l = 0; l < index.levelCount(); l++) {
            System.out.printf(Locale.ROOT, "  level %d: bin %,d us x %,d bins%n",
                    l, index.binWidthMicros(l), index.binCount(l));
        }

        if (offscreen) {
            runOffscreen(index);
        } else {
            boolean smoke = Boolean.getBoolean("bbv.smoke") || hasArg(args, "--smoke")
                    || "true".equals(System.getenv("BBV_SMOKE"));
            EventQueue.invokeLater(() -> showWindow(index, scale, smoke));
        }
    }

    /**
     * Streams straight off the O(1) generator; nothing is materialized and the
     * per-call {@link SyntheticAction} records are not retained.
     */
    private static SpanSource spanSourceOver(SyntheticActionGenerator generator) {
        return new SpanSource() {
            @Override
            public long spanCount() {
                return generator.actionCount();
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                long n = generator.actionCount();
                for (long i = 0; i < n; i++) {
                    SyntheticAction a = generator.actionAt(i);
                    // The generator knows nothing about caching, runners or
                    // bytes, so those flags stay off and the byte count is
                    // BYTES_UNKNOWN -- which is what a session with no
                    // execution log looks like, and therefore the right thing
                    // for the spike to measure.
                    consumer.accept(a.startMicros(), a.endMicros(), a.mnemonicIndex(),
                            a.status() == 1 ? SpanSource.FLAG_FAILED : 0,
                            SpanSource.BYTES_UNKNOWN);
                }
            }
        };
    }

    private static void showWindow(TimelineLodIndex index, SyntheticScale scale, boolean smoke) {
        TimelineCanvas canvas = new TimelineCanvas(index);
        JFrame frame = new JFrame("Timeline spike - " + scale);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(canvas);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        canvas.fitToWall();
        if (smoke) {
            Timer close = new Timer(1_500, e -> {
                frame.dispose();
                System.exit(0);
            });
            close.setRepeats(false);
            close.start();
        }
    }

    private static void runOffscreen(TimelineLodIndex index) {
        TimelineCanvas canvas = new TimelineCanvas(index);
        canvas.setSize(FRAME_WIDTH, FRAME_HEIGHT);
        BufferedImage image =
                new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(SEED);
        long wallSpan = index.wallEndMicros() - index.wallStartMicros();

        for (int i = 0; i < WARMUP_FRAMES; i++) {
            paintRandomFrame(canvas, image, random, wallSpan, index.wallStartMicros());
        }
        double[] millis = new double[MEASURED_FRAMES];
        for (int i = 0; i < MEASURED_FRAMES; i++) {
            millis[i] = paintRandomFrame(canvas, image, random, wallSpan, index.wallStartMicros());
        }

        Arrays.sort(millis);
        double mean = Arrays.stream(millis).average().orElseThrow();
        double p50 = percentile(millis, 50);
        double p95 = percentile(millis, 95);
        double p99 = percentile(millis, 99);
        System.out.printf(Locale.ROOT,
                "Offscreen frames (%dx%d, %d warmup untimed): count=%d mean=%.2f ms "
                        + "p50=%.2f ms p95=%.2f ms p99=%.2f ms%n",
                FRAME_WIDTH, FRAME_HEIGHT, WARMUP_FRAMES, MEASURED_FRAMES, mean, p50, p95, p99);
        boolean pass = p95 <= TARGET_P95_MILLIS;
        System.out.printf(Locale.ROOT, "%s: p95 %.2f ms vs %.0f ms target (30 FPS, plan 20.2)%n",
                pass ? "PASS" : "FAIL", p95, TARGET_P95_MILLIS);
        if (!pass) {
            System.exit(1);
        }
    }

    private static double paintRandomFrame(TimelineCanvas canvas, BufferedImage image,
            Random random, long wallSpan, long wallStart) {
        // Log-uniform visible span between MIN_VISIBLE_MICROS and the whole wall,
        // panned uniformly inside it, so every LOD level gets exercised.
        double logMin = Math.log(Math.min(MIN_VISIBLE_MICROS, wallSpan));
        double visible = Math.exp(logMin + random.nextDouble() * (Math.log(wallSpan) - logMin));
        double offset = wallStart + random.nextDouble() * (wallSpan - visible);
        canvas.setTransform(new TimelineTransform(offset, FRAME_WIDTH / visible));

        Graphics2D g2 = image.createGraphics();
        long start = System.nanoTime();
        canvas.paint(g2);
        double elapsed = (System.nanoTime() - start) / 1e6;
        g2.dispose();
        return elapsed;
    }

    /** Nearest-rank percentile over an ascending-sorted array. */
    private static double percentile(double[] sortedMillis, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sortedMillis.length);
        return sortedMillis[Math.clamp(rank - 1, 0, sortedMillis.length - 1)];
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}

package com.holtherndon.bazelviz.benchmarks.spike;

import com.holtherndon.bazelviz.graph.Bfs;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.EdgeStream;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticEdges;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Phase 0 spike: CSR graph index build + budget-limited BFS + aggregate cluster painting with
 * Java2D (plan sections 13.2/13.6).
 *
 * <p>Modes: default opens a JFrame with drag-pan / wheel-zoom over the aggregate cluster view
 * ({@code -Dbbv.smoke=true} or {@code --smoke} auto-closes); {@code --offscreen} is a headless
 * stats run. {@code --tier2} switches from TIER1 to TIER2 synthetic data. The frame benchmark (300
 * frames at random pan/zoom into a 1600x900 BufferedImage) runs in every mode so numbers always
 * print.
 *
 * <p>All graph/cluster computation happens on the main thread before any Swing code runs; the EDT
 * only ever executes {@code paintComponent}.
 */
public final class GraphSpike {

  private static final long SEED = 42;
  private static final int CLUSTER_SIZE = 4096;
  private static final int WIDTH = 1600;
  private static final int HEIGHT = 900;
  private static final int WARMUP_FRAMES = 30;
  private static final int TIMED_FRAMES = 300;
  private static final double FRAME_P95_BUDGET_MS = 33.0;
  private static final int BFS_RUNS = 100;
  private static final long BFS_NODE_BUDGET = 100_000;

  private static final Color BACKGROUND = new Color(0x14, 0x17, 0x1c);
  private static final Color TILE_BORDER = new Color(0x8a, 0xb4, 0xf8);
  private static final Color EDGE_BASE = new Color(0xf2, 0xa6, 0x54);

  private GraphSpike() {}

  public static void main(String[] args) {
    boolean tier2 = hasArg(args, "--tier2");
    boolean offscreen = hasArg(args, "--offscreen");
    boolean smoke = Boolean.getBoolean("bbv.smoke") || hasArg(args, "--smoke");
    SyntheticScale scale = tier2 ? SyntheticScale.TIER2 : SyntheticScale.TIER1;

    int nodeCount = Math.toIntExact(scale.actionCount());
    SyntheticEdges edges = new SyntheticEdges(scale, SEED);
    System.out.printf(
        Locale.ROOT,
        "GraphSpike scale=%s nodes=%,d edges=%,d%n",
        scale,
        nodeCount,
        edges.edgeCount());

    // TIER1/TIER2 indices fit int; toIntExact guards if that ever changes.
    EdgeStream stream =
        visitor ->
            edges.forEachEdge(
                (producer, consumer) ->
                    visitor.edge(Math.toIntExact(producer), Math.toIntExact(consumer)));

    long t0 = System.nanoTime();
    CsrGraph forward = CsrBuilder.build(nodeCount, stream);
    double buildMs = (System.nanoTime() - t0) / 1e6;
    System.out.printf(
        Locale.ROOT, "CSR build (two passes over synthetic stream): %.1f ms%n", buildMs);

    t0 = System.nanoTime();
    CsrGraph reverse = CsrBuilder.reverse(forward);
    double reverseMs = (System.nanoTime() - t0) / 1e6;
    System.out.printf(Locale.ROOT, "CSR reverse (transpose): %.1f ms%n", reverseMs);

    printMemoryEstimate(forward, reverse);
    runBfsBenchmark(forward, nodeCount);

    t0 = System.nanoTime();
    ClusterModel model = ClusterModel.build(forward, nodeCount);
    System.out.printf(
        Locale.ROOT,
        "Cluster aggregation: %,d clusters (%d nodes each), %,d inter-cluster pairs, %.1f ms%n",
        model.clusterCount,
        CLUSTER_SIZE,
        model.pairFrom.length,
        (System.nanoTime() - t0) / 1e6);
    System.out.println(
        "  note: dense int[c][c] weight matrix is spike-only; real impl needs sparse weights");

    boolean framesPass = benchmarkFrames(model);

    if (!offscreen && GraphicsEnvironment.isHeadless()) {
      System.out.println("No display available; skipping interactive window.");
      offscreen = true;
    }
    if (offscreen) {
      System.exit(framesPass ? 0 : 1);
    } else {
      SwingUtilities.invokeLater(() -> showWindow(model, smoke));
    }
  }

  private static boolean hasArg(String[] args, String flag) {
    for (String a : args) {
      if (flag.equals(a)) {
        return true;
      }
    }
    return false;
  }

  private static void printMemoryEstimate(CsrGraph forward, CsrGraph reverse) {
    long oneDirection = forward.retainedArrayBytes();
    long both = oneDirection + reverse.retainedArrayBytes();
    // Naive baseline: Edge{int from, int to} object (16 B header + 8 B fields)
    // plus an ArrayList slot reference (~8 B) per edge, plus a per-node
    // ArrayList + backing-array overhead (~64 B).
    long naive = forward.edgeCount() * 32L + forward.nodeCount() * 64L;
    System.out.printf(
        Locale.ROOT,
        "Retained: CSR one direction %.1f MB, both directions %.1f MB; "
            + "naive object-per-edge estimate %.1f MB (%.1fx one direction)%n",
        oneDirection / 1e6,
        both / 1e6,
        naive / 1e6,
        naive / (double) oneDirection);
  }

  private static void runBfsBenchmark(CsrGraph graph, int nodeCount) {
    Bfs bfs = new Bfs(graph);
    SplittableRandom rng = new SplittableRandom(2024);
    for (int i = 0; i < 20; i++) {
      bfs.run(rng.nextInt(nodeCount), BFS_NODE_BUDGET, Integer.MAX_VALUE);
    }
    long visited = 0;
    long t0 = System.nanoTime();
    for (int i = 0; i < BFS_RUNS; i++) {
      visited += bfs.run(rng.nextInt(nodeCount), BFS_NODE_BUDGET, Integer.MAX_VALUE);
    }
    double seconds = (System.nanoTime() - t0) / 1e9;
    System.out.printf(
        Locale.ROOT,
        "BFS: %d random-source runs, %,d node budget: %,d nodes visited in %.1f ms "
            + "(%.1fM nodes/sec)%n",
        BFS_RUNS,
        BFS_NODE_BUDGET,
        visited,
        seconds * 1e3,
        visited / seconds / 1e6);
  }

  // ------------------------------------------------------------------
  // Aggregate cluster painting
  // ------------------------------------------------------------------

  /**
   * Aggregate view model: nodes grouped into ceil(n/4096) clusters by index, laid out on a grid;
   * inter-cluster edge weights precomputed into a compact draw list (parallel primitive arrays, no
   * per-edge objects).
   */
  private static final class ClusterModel {
    final int clusterCount;
    final double worldWidth;
    final double worldHeight;
    final double[] centerX;
    final double[] centerY;
    final double[] tileHalf;
    final float[] tileBrightness;
    final int[] pairFrom;
    final int[] pairTo;
    final float[] pairAlpha;
    final float[] pairWidth;

    private ClusterModel(
        int clusterCount,
        double worldWidth,
        double worldHeight,
        double[] centerX,
        double[] centerY,
        double[] tileHalf,
        float[] tileBrightness,
        int[] pairFrom,
        int[] pairTo,
        float[] pairAlpha,
        float[] pairWidth) {
      this.clusterCount = clusterCount;
      this.worldWidth = worldWidth;
      this.worldHeight = worldHeight;
      this.centerX = centerX;
      this.centerY = centerY;
      this.tileHalf = tileHalf;
      this.tileBrightness = tileBrightness;
      this.pairFrom = pairFrom;
      this.pairTo = pairTo;
      this.pairAlpha = pairAlpha;
      this.pairWidth = pairWidth;
    }

    static ClusterModel build(CsrGraph graph, int nodeCount) {
      int clusters = (nodeCount + CLUSTER_SIZE - 1) / CLUSTER_SIZE;
      int[] nodesPerCluster = new int[clusters];
      for (int c = 0; c < clusters; c++) {
        nodesPerCluster[c] = Math.min(CLUSTER_SIZE, nodeCount - c * CLUSTER_SIZE);
      }

      // Single pass over all edges; dense matrix is fine for <= ~1225
      // clusters (TIER3) but the production version must be sparse.
      int[][] weight = new int[clusters][clusters];
      for (int u = 0; u < nodeCount; u++) {
        int cu = u / CLUSTER_SIZE;
        long end = graph.neighborsEnd(u);
        int[] row = weight[cu];
        for (long e = graph.neighborsBegin(u); e < end; e++) {
          row[graph.neighborAt(e) / CLUSTER_SIZE]++;
        }
      }

      int pairs = 0;
      int maxWeight = 1;
      for (int i = 0; i < clusters; i++) {
        for (int j = 0; j < clusters; j++) {
          if (i != j && weight[i][j] > 0) {
            pairs++;
            maxWeight = Math.max(maxWeight, weight[i][j]);
          }
        }
      }

      int cols = Math.max(1, (int) Math.ceil(Math.sqrt(clusters * (16.0 / 9.0))));
      int rows = (clusters + cols - 1) / cols;
      double cell = 100.0;
      double[] cx = new double[clusters];
      double[] cy = new double[clusters];
      double[] half = new double[clusters];
      float[] brightness = new float[clusters];
      int maxNodes = 1;
      long maxSelf = 1;
      for (int c = 0; c < clusters; c++) {
        maxNodes = Math.max(maxNodes, nodesPerCluster[c]);
        maxSelf = Math.max(maxSelf, weight[c][c]);
      }
      for (int c = 0; c < clusters; c++) {
        cx[c] = (c % cols + 0.5) * cell;
        cy[c] = (c / cols + 0.5) * cell;
        half[c] = 0.5 * cell * (0.20 + 0.65 * Math.sqrt(nodesPerCluster[c] / (double) maxNodes));
        brightness[c] = (float) (0.25 + 0.75 * Math.log1p(weight[c][c]) / Math.log1p(maxSelf));
      }

      int[] pFrom = new int[pairs];
      int[] pTo = new int[pairs];
      float[] pAlpha = new float[pairs];
      float[] pWidth = new float[pairs];
      int k = 0;
      double logMax = Math.log1p(maxWeight);
      for (int i = 0; i < clusters; i++) {
        for (int j = 0; j < clusters; j++) {
          if (i != j && weight[i][j] > 0) {
            double norm = Math.log1p(weight[i][j]) / logMax;
            pFrom[k] = i;
            pTo[k] = j;
            pAlpha[k] = (float) (0.10 + 0.85 * norm);
            pWidth[k] = (float) (0.6 + 3.0 * norm);
            k++;
          }
        }
      }
      return new ClusterModel(
          clusters, cols * cell, rows * cell, cx, cy, half, brightness, pFrom, pTo, pAlpha, pWidth);
    }

    double fitScale(int width, int height) {
      return 0.92 * Math.min(width / worldWidth, height / worldHeight);
    }
  }

  private static void paintScene(
      Graphics2D g2, ClusterModel m, AffineTransform view, int width, int height) {
    g2.setColor(BACKGROUND);
    g2.fillRect(0, 0, width, height);
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    AffineTransform saved = g2.getTransform();
    g2.transform(view);

    Line2D.Double line = new Line2D.Double();
    for (int k = 0; k < m.pairFrom.length; k++) {
      int i = m.pairFrom[k];
      int j = m.pairTo[k];
      g2.setColor(
          new Color(
              EDGE_BASE.getRed(),
              EDGE_BASE.getGreen(),
              EDGE_BASE.getBlue(),
              Math.min(255, (int) (m.pairAlpha[k] * 255))));
      g2.setStroke(new BasicStroke(m.pairWidth[k]));
      line.setLine(m.centerX[i], m.centerY[i], m.centerX[j], m.centerY[j]);
      g2.draw(line);
    }

    Rectangle2D.Double rect = new Rectangle2D.Double();
    g2.setStroke(new BasicStroke(1.0f));
    for (int c = 0; c < m.clusterCount; c++) {
      double h = m.tileHalf[c];
      rect.setRect(m.centerX[c] - h, m.centerY[c] - h, 2 * h, 2 * h);
      float b = m.tileBrightness[c];
      g2.setColor(new Color(0.18f * b + 0.05f, 0.35f * b + 0.06f, 0.55f * b + 0.10f));
      g2.fill(rect);
      g2.setColor(TILE_BORDER);
      g2.draw(rect);
    }
    g2.setTransform(saved);
  }

  private static AffineTransform randomView(ClusterModel m, SplittableRandom rng) {
    double zoom = 0.5 * Math.pow(8.0, rng.nextDouble()); // 0.5x .. 4x
    double scale = m.fitScale(WIDTH, HEIGHT) * zoom;
    double cx = rng.nextDouble() * m.worldWidth;
    double cy = rng.nextDouble() * m.worldHeight;
    AffineTransform t = new AffineTransform();
    t.translate(WIDTH / 2.0, HEIGHT / 2.0);
    t.scale(scale, scale);
    t.translate(-cx, -cy);
    return t;
  }

  /** Returns true when the p95 frame time is within budget. */
  private static boolean benchmarkFrames(ClusterModel model) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2 = image.createGraphics();
    SplittableRandom rng = new SplittableRandom(99);
    long checksum = 0;
    for (int f = 0; f < WARMUP_FRAMES; f++) {
      paintScene(g2, model, randomView(model, rng), WIDTH, HEIGHT);
      checksum += image.getRGB(rng.nextInt(WIDTH), rng.nextInt(HEIGHT));
    }
    long[] nanos = new long[TIMED_FRAMES];
    for (int f = 0; f < TIMED_FRAMES; f++) {
      AffineTransform view = randomView(model, rng);
      long t0 = System.nanoTime();
      paintScene(g2, model, view, WIDTH, HEIGHT);
      nanos[f] = System.nanoTime() - t0;
      checksum += image.getRGB(rng.nextInt(WIDTH), rng.nextInt(HEIGHT));
    }
    g2.dispose();

    Arrays.sort(nanos);
    double p50 = nanos[TIMED_FRAMES / 2] / 1e6;
    double p95 = nanos[(int) (TIMED_FRAMES * 0.95)] / 1e6;
    double max = nanos[TIMED_FRAMES - 1] / 1e6;
    long total = 0;
    for (long n : nanos) {
      total += n;
    }
    boolean pass = p95 <= FRAME_P95_BUDGET_MS;
    System.out.printf(
        Locale.ROOT,
        "Frames: %d at random pan/zoom, %dx%d: avg %.2f ms, p50 %.2f ms, p95 %.2f ms, "
            + "max %.2f ms (checksum %d)%n",
        TIMED_FRAMES,
        WIDTH,
        HEIGHT,
        total / (double) TIMED_FRAMES / 1e6,
        p50,
        p95,
        max,
        checksum);
    System.out.printf(
        Locale.ROOT,
        "%s: p95 %.2f ms vs %.0f ms budget%n",
        pass ? "PASS" : "FAIL",
        p95,
        FRAME_P95_BUDGET_MS);
    return pass;
  }

  // ------------------------------------------------------------------
  // Interactive mode
  // ------------------------------------------------------------------

  private static void showWindow(ClusterModel model, boolean smoke) {
    JFrame frame = new JFrame("GraphSpike - aggregate cluster view");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.add(new GraphPanel(model));
    frame.setSize(WIDTH, HEIGHT);
    frame.setLocationByPlatform(true);
    frame.setVisible(true);
    if (smoke) {
      Timer timer =
          new Timer(
              1500,
              e -> {
                frame.dispose();
                System.exit(0);
              });
      timer.setRepeats(false);
      timer.start();
    }
  }

  /** Drag-pan / wheel-zoom view over the precomputed model; paint-only on the EDT. */
  private static final class GraphPanel extends JComponent {
    private final ClusterModel model;
    private double zoom = 1.0;
    private double centerX;
    private double centerY;
    private int lastDragX;
    private int lastDragY;

    GraphPanel(ClusterModel model) {
      this.model = model;
      this.centerX = model.worldWidth / 2;
      this.centerY = model.worldHeight / 2;
      MouseAdapter mouse =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              lastDragX = e.getX();
              lastDragY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              double scale = currentScale();
              centerX -= (e.getX() - lastDragX) / scale;
              centerY -= (e.getY() - lastDragY) / scale;
              lastDragX = e.getX();
              lastDragY = e.getY();
              repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
              double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
              double scaleBefore = currentScale();
              // Keep the world point under the cursor fixed while zooming.
              double worldX = centerX + (e.getX() - getWidth() / 2.0) / scaleBefore;
              double worldY = centerY + (e.getY() - getHeight() / 2.0) / scaleBefore;
              zoom = Math.max(0.2, Math.min(40.0, zoom * factor));
              double scaleAfter = currentScale();
              centerX = worldX - (e.getX() - getWidth() / 2.0) / scaleAfter;
              centerY = worldY - (e.getY() - getHeight() / 2.0) / scaleAfter;
              repaint();
            }
          };
      addMouseListener(mouse);
      addMouseMotionListener(mouse);
      addMouseWheelListener(mouse);
    }

    private double currentScale() {
      return model.fitScale(Math.max(1, getWidth()), Math.max(1, getHeight())) * zoom;
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      double scale = currentScale();
      AffineTransform view = new AffineTransform();
      view.translate(getWidth() / 2.0, getHeight() / 2.0);
      view.scale(scale, scale);
      view.translate(-centerX, -centerY);
      paintScene(g2, model, view, getWidth(), getHeight());
      g2.dispose();
    }
  }
}

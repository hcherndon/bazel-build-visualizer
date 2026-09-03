package com.holtherndon.bazelviz.ui.graph;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * What a node's colour is allowed to mean.
 *
 * <p>The graph's counterpart to {@code TimelineColours}, and it keeps the same rule: unknown is its
 * own colour, never the cold end of a scale. An action the session never timed and an action that
 * finished instantly look identical on a heat ramp and mean opposite things, so the ramp does not
 * get to claim either.
 */
public final class GraphColours {

  private GraphColours() {}

  /** Nodes nothing timed. Deliberately unremarkable, and off the ramp. */
  public static final Color UNKNOWN = new Color(0x9E, 0x9E, 0x9E);

  /** The selected node's outline. */
  public static final Color SELECTION = new Color(0x1A, 0x73, 0xE8);

  /** The node under the pointer. */
  public static final Color HOVER = new Color(0x5F, 0x6D, 0xF0);

  /** Edges into or out of the selection, drawn over the rest. */
  public static final Color EDGE_HIGHLIGHTED = new Color(0x1A, 0x73, 0xE8, 0xC0);

  /** The box-select rectangle. */
  public static final Color MARQUEE = new Color(0x1A, 0x73, 0xE8, 0x30);

  /** Ordinary edges follow the current text colour at low opacity. */
  public static Color edge() {
    return withAlpha(label(), 0x40);
  }

  /** Shared/cycle links sit behind the primary branches in either theme. */
  public static Color secondaryEdge() {
    return withAlpha(label(), 0x24);
  }

  /** Labels use the active look-and-feel foreground, including dark themes. */
  public static Color label() {
    Color label = UIManager.getColor("Label.foreground");
    return label == null ? new Color(0x20, 0x20, 0x20) : label;
  }

  /**
   * The heat ramp, cold to hot, over {@code [0, 1]}.
   *
   * <p>Blue through amber to red. Values outside the range are clamped rather than wrapped, so a
   * duration that somehow exceeded the maximum shows as the hottest thing rather than as the
   * coldest.
   */
  public static Color heat(double fraction) {
    double t = Math.min(1, Math.max(0, fraction));
    if (t < 0.5) {
      double u = t * 2;
      return blend(new Color(0x42, 0x85, 0xF4), new Color(0xF2, 0x8B, 0x30), u);
    }
    double u = (t - 0.5) * 2;
    return blend(new Color(0xF2, 0x8B, 0x30), new Color(0xD9, 0x3B, 0x3B), u);
  }

  private static Color blend(Color from, Color to, double u) {
    return new Color(
        (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * u),
        (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * u),
        (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * u));
  }

  private static Color withAlpha(Color color, int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
  }
}

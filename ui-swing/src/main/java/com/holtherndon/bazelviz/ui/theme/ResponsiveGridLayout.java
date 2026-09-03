package com.holtherndon.bazelviz.ui.theme;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Equal-width columns that collapse into fewer columns as their container narrows.
 *
 * <p>{@link WrapLayout} is the right choice when every member should keep its natural width.
 * Dashboard cards want the other behaviour: use the available row, keep related cards aligned, and
 * become one readable column before a card gets squeezed. This layout gives every column the same
 * width and chooses the largest column count that preserves the requested minimum width.
 *
 * <p>The compact form places the next card in the currently shortest column. It is intended for
 * independent detail cards with different row counts; it avoids reserving an empty rectangle under
 * a short card merely because its neighbour is tall. Component order is unchanged, so accessibility
 * and test traversal still follow the source's reading order.
 */
public final class ResponsiveGridLayout implements LayoutManager {

  private final int maximumColumns;
  private final int minimumColumnWidth;
  private final int horizontalGap;
  private final int verticalGap;
  private final boolean compact;

  /** A row-aligned responsive grid. */
  public ResponsiveGridLayout(
      int maximumColumns, int minimumColumnWidth, int horizontalGap, int verticalGap) {
    this(maximumColumns, minimumColumnWidth, horizontalGap, verticalGap, false);
  }

  /** A responsive grid that packs unequal-height cards into the shortest column. */
  public static ResponsiveGridLayout compact(
      int maximumColumns, int minimumColumnWidth, int horizontalGap, int verticalGap) {
    return new ResponsiveGridLayout(
        maximumColumns, minimumColumnWidth, horizontalGap, verticalGap, true);
  }

  private ResponsiveGridLayout(
      int maximumColumns,
      int minimumColumnWidth,
      int horizontalGap,
      int verticalGap,
      boolean compact) {
    if (maximumColumns < 1 || minimumColumnWidth < 1 || horizontalGap < 0 || verticalGap < 0) {
      throw new IllegalArgumentException("invalid responsive-grid dimensions");
    }
    this.maximumColumns = maximumColumns;
    this.minimumColumnWidth = minimumColumnWidth;
    this.horizontalGap = horizontalGap;
    this.verticalGap = verticalGap;
    this.compact = compact;
  }

  @Override
  public void addLayoutComponent(String name, Component component) {}

  @Override
  public void removeLayoutComponent(Component component) {}

  @Override
  public Dimension preferredLayoutSize(Container parent) {
    return measuredSize(parent, false);
  }

  @Override
  public Dimension minimumLayoutSize(Container parent) {
    return measuredSize(parent, true);
  }

  @Override
  public void layoutContainer(Container parent) {
    synchronized (parent.getTreeLock()) {
      List<Component> members = visibleMembers(parent);
      if (members.isEmpty()) {
        return;
      }
      Insets insets = parent.getInsets();
      int available = Math.max(1, parent.getWidth() - insets.left - insets.right);
      int columns = columnsFor(available, members.size());
      int columnWidth = Math.max(1, (available - horizontalGap * (columns - 1)) / columns);
      if (compact) {
        layoutCompact(members, insets, columns, columnWidth);
      } else {
        layoutRows(members, insets, columns, columnWidth);
      }
    }
  }

  private Dimension measuredSize(Container parent, boolean minimum) {
    synchronized (parent.getTreeLock()) {
      List<Component> members = visibleMembers(parent);
      Insets insets = parent.getInsets();
      if (members.isEmpty()) {
        return new Dimension(insets.left + insets.right, insets.top + insets.bottom);
      }
      int width = widthOf(parent);
      if (width <= insets.left + insets.right) {
        int columns = Math.min(maximumColumns, members.size());
        width =
            insets.left
                + insets.right
                + columns * minimumColumnWidth
                + (columns - 1) * horizontalGap;
      }
      int available = Math.max(1, width - insets.left - insets.right);
      int columns = columnsFor(available, members.size());
      int columnWidth = Math.max(1, (available - horizontalGap * (columns - 1)) / columns);
      int height =
          compact
              ? compactHeight(members, columns, columnWidth, minimum)
              : rowHeight(members, columns, columnWidth, minimum);
      return new Dimension(width, insets.top + height + insets.bottom);
    }
  }

  private void layoutRows(List<Component> members, Insets insets, int columns, int columnWidth) {
    int y = insets.top;
    for (int first = 0; first < members.size(); first += columns) {
      int end = Math.min(first + columns, members.size());
      int height = 1;
      for (int at = first; at < end; at++) {
        height = Math.max(height, heightAtWidth(members.get(at), columnWidth, false));
      }
      for (int at = first; at < end; at++) {
        int column = at - first;
        members
            .get(at)
            .setBounds(
                insets.left + column * (columnWidth + horizontalGap), y, columnWidth, height);
      }
      y += height + verticalGap;
    }
  }

  private void layoutCompact(List<Component> members, Insets insets, int columns, int columnWidth) {
    int[] bottoms = new int[columns];
    Arrays.fill(bottoms, insets.top);
    for (Component member : members) {
      int column = shortest(bottoms);
      int height = heightAtWidth(member, columnWidth, false);
      member.setBounds(
          insets.left + column * (columnWidth + horizontalGap),
          bottoms[column],
          columnWidth,
          height);
      bottoms[column] += height + verticalGap;
    }
  }

  private int rowHeight(List<Component> members, int columns, int columnWidth, boolean minimum) {
    int total = 0;
    for (int first = 0; first < members.size(); first += columns) {
      int end = Math.min(first + columns, members.size());
      int height = 1;
      for (int at = first; at < end; at++) {
        height = Math.max(height, measuredHeightAtWidth(members.get(at), columnWidth, minimum));
      }
      total += height;
      if (end < members.size()) {
        total += verticalGap;
      }
    }
    return total;
  }

  private int compactHeight(
      List<Component> members, int columns, int columnWidth, boolean minimum) {
    int[] heights = new int[columns];
    for (Component member : members) {
      int column = shortest(heights);
      if (heights[column] > 0) {
        heights[column] += verticalGap;
      }
      heights[column] += measuredHeightAtWidth(member, columnWidth, minimum);
    }
    return Arrays.stream(heights).max().orElse(0);
  }

  private int columnsFor(int available, int members) {
    int fitting = Math.max(1, (available + horizontalGap) / (minimumColumnWidth + horizontalGap));
    return Math.min(members, Math.min(maximumColumns, fitting));
  }

  /**
   * Gives wrapping descendants their real column width before asking how tall they need to be. Two
   * passes let a card lay out its row containers, then let the text areas inside those rows report
   * their wrapped height.
   */
  private static int heightAtWidth(Component member, int width, boolean minimum) {
    int height =
        Math.max(1, (minimum ? member.getMinimumSize() : member.getPreferredSize()).height);
    for (int pass = 0; pass < 2; pass++) {
      member.setSize(width, height);
      if (member instanceof Container container) {
        layoutTree(container);
        container.invalidate();
      }
      height = Math.max(1, (minimum ? member.getMinimumSize() : member.getPreferredSize()).height);
    }
    return height;
  }

  /** Restores the geometry temporarily used for width-aware size measurement. */
  private static int measuredHeightAtWidth(Component member, int width, boolean minimum) {
    List<ComponentBounds> originalBounds = boundsOfTree(member);
    try {
      return heightAtWidth(member, width, minimum);
    } finally {
      for (ComponentBounds original : originalBounds) {
        original.component().setBounds(original.bounds());
      }
    }
  }

  private static List<ComponentBounds> boundsOfTree(Component root) {
    List<ComponentBounds> bounds = new ArrayList<>();
    collectBounds(root, bounds);
    return bounds;
  }

  private static void collectBounds(Component component, List<ComponentBounds> bounds) {
    bounds.add(new ComponentBounds(component, new Rectangle(component.getBounds())));
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectBounds(child, bounds);
      }
    }
  }

  private record ComponentBounds(Component component, Rectangle bounds) {}

  private static void layoutTree(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        layoutTree(nested);
      }
    }
  }

  private static int shortest(int[] values) {
    int shortest = 0;
    for (int i = 1; i < values.length; i++) {
      if (values[i] < values[shortest]) {
        shortest = i;
      }
    }
    return shortest;
  }

  private static List<Component> visibleMembers(Container parent) {
    List<Component> members = new ArrayList<>();
    for (Component component : parent.getComponents()) {
      if (component.isVisible()) {
        members.add(component);
      }
    }
    return members;
  }

  private static int widthOf(Container target) {
    Container probe = target;
    while (probe.getWidth() == 0 && probe.getParent() != null) {
      probe = probe.getParent();
    }
    return probe.getWidth();
  }
}

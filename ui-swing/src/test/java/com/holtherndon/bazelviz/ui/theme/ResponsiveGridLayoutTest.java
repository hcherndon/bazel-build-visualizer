package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The responsive geometry shared by the Overview and coverage dashboards. */
final class ResponsiveGridLayoutTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("equal columns fill a wide row and collapse before becoming too narrow")
  void columnsRespondToAvailableWidth() throws Exception {
    JPanel grid = onEdt(() -> grid(new ResponsiveGridLayout(3, 200, 10, 10), 20, 30, 40));

    onEdt(
        () -> {
          layout(grid, 650, 500);
          Component[] cards = grid.getComponents();
          assertThat(cards[0].getY()).isEqualTo(cards[2].getY());
          assertThat(cards[0].getWidth()).isEqualTo(cards[1].getWidth());
          assertThat(cards[2].getX() + cards[2].getWidth()).isEqualTo(650);

          layout(grid, 390, 500);
          assertThat(cards[1].getX()).isEqualTo(cards[0].getX());
          assertThat(cards[1].getY()).isGreaterThan(cards[0].getY());
          assertThat(cards[2].getY()).isGreaterThan(cards[1].getY());
          assertThat(cards[2].getX() + cards[2].getWidth()).isEqualTo(390);
          return null;
        });
  }

  @Test
  @DisplayName("compact columns put the next card under the shorter neighbour")
  void compactPackingAvoidsAFalseRowGap() throws Exception {
    JPanel grid = onEdt(() -> grid(ResponsiveGridLayout.compact(2, 200, 10, 10), 100, 20, 20));

    onEdt(
        () -> {
          layout(grid, 410, 500);
          Component[] cards = grid.getComponents();
          assertThat(cards[0].getX()).isLessThan(cards[1].getX());
          assertThat(cards[2].getX()).isEqualTo(cards[1].getX());
          assertThat(cards[2].getY()).isEqualTo(cards[1].getHeight() + 10);
          assertThat(cards[2].getY()).isLessThan(cards[0].getHeight());
          return null;
        });
  }

  @Test
  @DisplayName("wrapping labels yield their width while retaining enough wrapped height")
  void wrappingLabelsHaveNoTextDrivenMinimumWidth() throws Exception {
    JTextArea label =
        onEdt(
            () ->
                WrappingLabel.create(
                    "A long build-provided value remains readable because this text wraps"
                        + " instead of forcing its dashboard card wider."));

    onEdt(
        () -> {
          assertThat(label.getMinimumSize().width).isZero();
          assertThat(label.isEditable()).isFalse();
          assertThat(label.isFocusable()).isTrue();
          label.select(0, 6);
          assertThat(label.getSelectedText()).isEqualTo("A long");
          int oneLine = label.getFontMetrics(label.getFont()).getHeight();
          label.setSize(120, 1_000);
          assertThat(label.getPreferredSize().width).isLessThanOrEqualTo(120);
          assertThat(label.getPreferredSize().height).isGreaterThan(oneLine);
          return null;
        });
  }

  @Test
  @DisplayName("preferred-size measurement is stable and preserves component geometry")
  void preferredSizeMeasurementHasNoPersistentGeometrySideEffects() throws Exception {
    JPanel grid =
        onEdt(
            () -> {
              JPanel panel = new JPanel(new ResponsiveGridLayout(2, 160, 10, 10));
              panel.add(wrappingCard("The first card has enough text to wrap at card width."));
              panel.add(wrappingCard("The second card also has width-sensitive content."));
              return panel;
            });

    onEdt(
        () -> {
          List<Rectangle> before = boundsOfTree(grid);

          grid.invalidate();
          Dimension first = grid.getPreferredSize();
          List<Rectangle> afterFirst = boundsOfTree(grid);
          grid.invalidate();
          Dimension second = grid.getPreferredSize();

          assertThat(second).isEqualTo(first);
          assertThat(afterFirst).isEqualTo(before);
          assertThat(boundsOfTree(grid)).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("visible, added, and removed members immediately reflow the columns")
  void membershipChangesReflowColumns() throws Exception {
    JPanel grid = onEdt(() -> grid(new ResponsiveGridLayout(3, 100, 10, 10), 20, 20, 20));

    onEdt(
        () -> {
          Component first = grid.getComponent(0);
          Component hidden = grid.getComponent(1);
          Component third = grid.getComponent(2);

          layout(grid, 320, 200);
          assertThat(List.of(first.getX(), hidden.getX(), third.getX()))
              .containsExactly(0, 110, 220);

          hidden.setVisible(false);
          layout(grid, 320, 200);
          assertThat(first.getWidth()).isEqualTo(155);
          assertThat(third.getX()).isEqualTo(165);

          JPanel added = fixedHeightCard(20);
          grid.add(added);
          layout(grid, 320, 200);
          assertThat(List.of(first.getX(), third.getX(), added.getX()))
              .containsExactly(0, 110, 220);

          grid.remove(first);
          layout(grid, 320, 200);
          assertThat(third.getWidth()).isEqualTo(155);
          assertThat(added.getX()).isEqualTo(165);

          hidden.setVisible(true);
          layout(grid, 320, 200);
          assertThat(List.of(hidden.getX(), third.getX(), added.getX()))
              .containsExactly(0, 110, 220);
          return null;
        });
  }

  private static JPanel grid(LayoutManager layout, int... heights) {
    JPanel panel = new JPanel(layout);
    for (int height : heights) {
      panel.add(fixedHeightCard(height));
    }
    return panel;
  }

  private static JPanel fixedHeightCard(int height) {
    JPanel card = new JPanel();
    card.setPreferredSize(new Dimension(200, height));
    return card;
  }

  private static JPanel wrappingCard(String text) {
    JPanel card = new JPanel(new BorderLayout());
    card.add(WrappingLabel.create(text));
    return card;
  }

  private static List<Rectangle> boundsOfTree(Container root) {
    List<Rectangle> bounds = new ArrayList<>();
    collectBounds(root, bounds);
    return bounds;
  }

  private static void collectBounds(Component component, List<Rectangle> bounds) {
    bounds.add(new Rectangle(component.getBounds()));
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectBounds(child, bounds);
      }
    }
  }

  private static void layout(JPanel panel, int width, int height) {
    panel.setSize(width, height);
    panel.invalidate();
    panel.doLayout();
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Exception exception) {
            failure.set(exception);
          }
        });
    if (failure.get() != null) {
      throw failure.get();
    }
    return value.get();
  }
}

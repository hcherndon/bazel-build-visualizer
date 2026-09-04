package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.AWTKeyStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

final class CanvasAccessibilityTest {

  @Test
  void configuredCanvasKeepsTabTraversalAndPaintsAThemeFocusRing() throws Exception {
    Color previous = UIManager.getColor("Component.focusColor");
    Color focus = new Color(0xC0, 0x22, 0xDD);
    try {
      UIManager.put("Component.focusColor", focus);
      SwingUtilities.invokeAndWait(
          () -> {
            FocusProbe canvas = new FocusProbe();
            canvas.setSize(24, 20);
            CanvasAccessibility.configure(canvas, "Test canvas", "Keyboard help");

            assertThat(canvas.isFocusable()).isTrue();
            assertThat(canvas.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
                .contains(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0));
            assertThat(canvas.getAccessibleContext().getAccessibleName()).isEqualTo("Test canvas");
            assertThat(canvas.getAccessibleContext().getAccessibleDescription())
                .isEqualTo("Keyboard help");
            assertThat(canvas.getBorder().getBorderInsets(canvas))
                .extracting(
                    insets -> insets.top,
                    insets -> insets.left,
                    insets -> insets.bottom,
                    insets -> insets.right)
                .containsExactly(0, 0, 0, 0);

            BufferedImage unfocused = imageOf(canvas, false);
            BufferedImage focused = imageOf(canvas, true);
            assertThat(new Color(unfocused.getRGB(1, 1), true)).isEqualTo(Color.BLACK);
            assertThat(new Color(focused.getRGB(1, 1), true)).isEqualTo(focus);

            int before = canvas.repaintCount;
            FocusEvent gained = new FocusEvent(canvas, FocusEvent.FOCUS_GAINED);
            FocusEvent lost = new FocusEvent(canvas, FocusEvent.FOCUS_LOST);
            for (FocusListener listener : canvas.getFocusListeners()) {
              listener.focusGained(gained);
              listener.focusLost(lost);
            }
            assertThat(canvas.repaintCount).isGreaterThanOrEqualTo(before + 2);
          });
    } finally {
      if (previous == null) {
        UIManager.getDefaults().remove("Component.focusColor");
      } else {
        UIManager.put("Component.focusColor", previous);
      }
    }
  }

  private static BufferedImage imageOf(FocusProbe canvas, boolean focused) {
    canvas.focused = focused;
    BufferedImage image = new BufferedImage(24, 20, BufferedImage.TYPE_INT_ARGB);
    Graphics graphics = image.getGraphics();
    try {
      canvas.paint(graphics);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static final class FocusProbe extends JPanel {
    private static final long serialVersionUID = 1L;

    private boolean focused;
    private int repaintCount;

    @Override
    public boolean isFocusOwner() {
      return focused;
    }

    @Override
    public void repaint() {
      repaintCount++;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      graphics.setColor(Color.BLACK);
      graphics.fillRect(0, 0, getWidth(), getHeight());
    }
  }
}

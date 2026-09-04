package com.holtherndon.bazelviz.ui.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;

/** Shared accessibility and keyboard wiring for custom-painted data views. */
public final class CanvasAccessibility {

  private CanvasAccessibility() {}

  /** Makes a painted component reachable and explains its interaction model. */
  public static void configure(JComponent component, String name, String description) {
    Objects.requireNonNull(component, "component");
    component.setFocusable(true);
    component.setBorder(FocusIndicatorBorder.INSTANCE);
    component.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent event) {
            component.repaint();
          }

          @Override
          public void focusLost(FocusEvent event) {
            component.repaint();
          }
        });
    component.getAccessibleContext().setAccessibleName(Objects.requireNonNull(name, "name"));
    describe(component, description);
  }

  /** Updates the screen-reader description as the painted model or selection changes. */
  public static void describe(JComponent component, String description) {
    Objects.requireNonNull(component, "component")
        .getAccessibleContext()
        .setAccessibleDescription(Objects.requireNonNull(description, "description"));
  }

  /** Adds one focused-component key equivalent without duplicating Swing action boilerplate. */
  public static void bind(
      JComponent component, String actionName, KeyStroke keyStroke, Runnable action) {
    String checkedName = Objects.requireNonNull(actionName, "actionName");
    Runnable checkedAction = Objects.requireNonNull(action, "action");
    Objects.requireNonNull(component, "component")
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(Objects.requireNonNull(keyStroke, "keyStroke"), checkedName);
    component
        .getActionMap()
        .put(
            checkedName,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                checkedAction.run();
              }
            });
  }

  /** A zero-inset ring painted inside a focused canvas, over any custom-painted content. */
  private static final class FocusIndicatorBorder extends AbstractBorder {

    private static final long serialVersionUID = 1L;
    private static final FocusIndicatorBorder INSTANCE = new FocusIndicatorBorder();
    private static final BasicStroke STROKE = new BasicStroke(2f);

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
      insets.set(0, 0, 0, 0);
      return insets;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      if (!component.isFocusOwner() || width < 3 || height < 3) {
        return;
      }
      Graphics2D ring = (Graphics2D) graphics.create();
      try {
        ring.setColor(focusColour(component));
        ring.setStroke(STROKE);
        ring.drawRect(x + 1, y + 1, width - 3, height - 3);
      } finally {
        ring.dispose();
      }
    }

    private static Color focusColour(Component component) {
      Color colour = UIManager.getColor("Component.focusColor");
      if (colour == null) {
        colour = UIManager.getColor("Component.focusedBorderColor");
      }
      if (colour != null) {
        return colour;
      }
      Color background = component.getBackground();
      boolean dark =
          background != null
              && background.getRed() + background.getGreen() + background.getBlue() < 384;
      return dark ? new Color(0xA9, 0xCE, 0xFF) : new Color(0x0B, 0x57, 0xD0);
    }
  }
}

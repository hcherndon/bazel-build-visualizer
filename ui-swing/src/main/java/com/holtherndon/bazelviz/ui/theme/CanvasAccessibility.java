package com.holtherndon.bazelviz.ui.theme;

import java.awt.event.ActionEvent;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** Shared accessibility and keyboard wiring for custom-painted data views. */
public final class CanvasAccessibility {

  private CanvasAccessibility() {}

  /** Makes a painted component reachable and explains its interaction model. */
  public static void configure(JComponent component, String name, String description) {
    Objects.requireNonNull(component, "component");
    component.setFocusable(true);
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
}

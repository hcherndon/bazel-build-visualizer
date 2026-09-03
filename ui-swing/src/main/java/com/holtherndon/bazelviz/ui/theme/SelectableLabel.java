package com.holtherndon.bazelviz.ui.theme;

import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JTextField;
import javax.swing.UIManager;

/** A one-line label look-alike whose plain text can be selected and copied. */
public final class SelectableLabel {

  private SelectableLabel() {}

  public static JTextField create(String text) {
    JTextField field = new JTextField(text);
    field.setEditable(false);
    field.setFocusable(true);
    field.setOpaque(false);
    field.setBorder(BorderFactory.createEmptyBorder());
    if (UIManager.getFont("Label.font") != null) {
      field.setFont(UIManager.getFont("Label.font"));
    }
    if (UIManager.getColor("Label.foreground") != null) {
      field.setForeground(UIManager.getColor("Label.foreground"));
    }
    Dimension minimum = field.getMinimumSize();
    field.setMinimumSize(new Dimension(0, minimum.height));
    return field;
  }
}

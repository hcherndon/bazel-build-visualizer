package com.holtherndon.bazelviz.ui.capture;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/** A keyboard-accessible inline section that keeps optional launch detail compact. */
final class DisclosurePanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final String title;
  private final JButton toggle = new JButton();
  private final JComponent details;

  DisclosurePanel(String title, JComponent details, boolean expanded) {
    super(new BorderLayout(0, 4));
    this.title = Objects.requireNonNull(title, "title");
    this.details = Objects.requireNonNull(details, "details");
    setAlignmentX(LEFT_ALIGNMENT);
    Color border = UIManager.getColor("Component.borderColor");
    if (border == null) {
      border = UIManager.getColor("Separator.foreground");
    }
    if (border == null) {
      border = Color.GRAY;
    }
    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border), BorderFactory.createEmptyBorder(4, 6, 6, 6)));
    toggle.setBorderPainted(false);
    toggle.setContentAreaFilled(false);
    toggle.setHorizontalAlignment(SwingConstants.LEFT);
    toggle.setFont(toggle.getFont().deriveFont(Font.BOLD));
    toggle.setToolTipText("Show or hide this section.");
    toggle.addActionListener(event -> setExpanded(!isExpanded()));
    add(toggle, BorderLayout.NORTH);
    add(details, BorderLayout.CENTER);
    setExpanded(expanded);
  }

  boolean isExpanded() {
    return details.isVisible();
  }

  void setExpanded(boolean expanded) {
    details.setVisible(expanded);
    toggle.setText((expanded ? "▾ " : "▸ ") + title);
    toggle.getAccessibleContext().setAccessibleName((expanded ? "Collapse " : "Expand ") + title);
    revalidate();
    repaint();
  }

  JButton toggleForTest() {
    return toggle;
  }
}

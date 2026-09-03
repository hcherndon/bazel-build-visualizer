package com.holtherndon.bazelviz.ui.theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/** A wrapping, plain-text hyperlink that never invokes Swing's HTML renderer. */
public final class HyperlinkLabel extends JTextArea {

  private static final long serialVersionUID = 1L;

  private final Runnable action;

  public HyperlinkLabel(String text, Runnable action) {
    super(Objects.requireNonNull(text, "text"));
    this.action = Objects.requireNonNull(action, "action");
    setEditable(false);
    setFocusable(true);
    setLineWrap(true);
    setWrapStyleWord(true);
    setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder());
    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    updateLinkColour();
    Map<TextAttribute, Object> attributes = new HashMap<>(getFont().getAttributes());
    attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
    setFont(getFont().deriveFont(attributes));
    Dimension minimum = getMinimumSize();
    setMinimumSize(new Dimension(0, minimum.height));
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (isEnabled() && event.getButton() == MouseEvent.BUTTON1) {
              activate();
            }
          }
        });
    addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(KeyEvent event) {
            if (isEnabled()
                && (event.getKeyCode() == KeyEvent.VK_ENTER
                    || event.getKeyCode() == KeyEvent.VK_SPACE)) {
              activate();
              event.consume();
            }
          }
        });
  }

  /** Activates the link. Public so headless tests and accessibility bridges can invoke it. */
  public void activate() {
    action.run();
  }

  @Override
  public void updateUI() {
    super.updateUI();
    updateLinkColour();
  }

  private void updateLinkColour() {
    Color link = UIManager.getColor("Component.linkColor");
    setForeground(link == null ? new Color(0x0B57D0) : link);
  }
}

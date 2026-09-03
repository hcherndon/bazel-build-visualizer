package com.holtherndon.bazelviz.ui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.UIManager;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;

/**
 * A full-pane message for a view that has no content to show yet.
 *
 * <p>The text is plain, selectable, read-only, and wrapping. Its paragraphs are centred
 * horizontally, while the complete wrapped message is centred vertically in the space the parent
 * gives this panel. The message never contributes its unwrapped width as a minimum size, so a long
 * error cannot make the surrounding view wider.
 */
public final class EmptyStatePanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final MessagePane message = new MessagePane();

  /** Creates a full-pane empty state containing {@code text}. */
  public EmptyStatePanel(String text) {
    setLayout(new MessageLayout());
    setBorder(BorderFactory.createEmptyBorder(24, 40, 24, 40));
    add(message);
    setText(text);
  }

  /** Replaces the plain-text message. */
  public void setText(String text) {
    message.setText(Objects.requireNonNull(text, "text"));
    centreParagraphs();
    message.setCaretPosition(0);
    revalidate();
    repaint();
  }

  /** Returns the message exactly as supplied to {@link #setText(String)}. */
  public String getText() {
    return message.getText();
  }

  /**
   * Confirms that the complete message can be selected for copying.
   *
   * <p>The caller's caret and selection are restored before this method returns.
   */
  public boolean isTextSelectable() {
    int dot = message.getCaret().getDot();
    int mark = message.getCaret().getMark();
    try {
      message.selectAll();
      int length = message.getDocument().getLength();
      boolean completeSelection =
          message.getSelectionStart() == 0 && message.getSelectionEnd() == length;
      String selected = message.getSelectedText();
      return message.isFocusable()
          && !message.isEditable()
          && completeSelection
          && (length == 0 ? selected == null : getText().equals(selected));
    } finally {
      message.getCaret().setDot(mark);
      message.getCaret().moveDot(dot);
    }
  }

  private void centreParagraphs() {
    SimpleAttributeSet centred = new SimpleAttributeSet();
    StyleConstants.setAlignment(centred, StyleConstants.ALIGN_CENTER);
    StyledDocument document = message.getStyledDocument();
    if (document.getLength() == 0) {
      message.setParagraphAttributes(centred, false);
    } else {
      document.setParagraphAttributes(0, document.getLength(), centred, false);
    }
  }

  /** A plain styled document gives wrapping and paragraph alignment without HTML. */
  private static final class MessagePane extends JTextPane {

    private static final long serialVersionUID = 1L;

    MessagePane() {
      setEditorKit(new StyledEditorKit());
      setEditable(false);
      setFocusable(true);
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder());
      setMargin(new Insets(0, 0, 0, 0));
      PlainText.disableHtml(this);
      applyLabelStyle();
    }

    @Override
    public void updateUI() {
      super.updateUI();
      applyLabelStyle();
    }

    private void applyLabelStyle() {
      if (UIManager.getFont("Label.font") != null) {
        setFont(UIManager.getFont("Label.font"));
      }
      // Empty-state JLabels elsewhere in the application are disabled so
      // Swing paints them with this quieter theme colour. Keep the text
      // pane enabled for selection, but give it that same foreground.
      Color foreground = UIManager.getColor("Label.disabledForeground");
      if (foreground == null) {
        foreground = UIManager.getColor("Label.foreground");
      }
      if (foreground != null) {
        setForeground(foreground);
      }
    }
  }

  /** Measures wrapping at the actual pane width, then centres that height. */
  private final class MessageLayout implements LayoutManager {

    @Override
    public void addLayoutComponent(String name, Component component) {}

    @Override
    public void removeLayoutComponent(Component component) {}

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      return minimumLayoutSize(parent);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      Insets insets = parent.getInsets();
      int lineHeight =
          message.getFont() == null ? 0 : message.getFontMetrics(message.getFont()).getHeight();
      return new Dimension(insets.left + insets.right, insets.top + insets.bottom + lineHeight);
    }

    @Override
    public void layoutContainer(Container parent) {
      Insets insets = parent.getInsets();
      int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
      int availableHeight = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
      if (width == 0 || availableHeight == 0) {
        message.setBounds(insets.left, insets.top, 0, 0);
        return;
      }

      // JTextPane computes wrapped height from its current width.
      message.setSize(width, Short.MAX_VALUE);
      int height = Math.min(availableHeight, message.getPreferredSize().height);
      int y = insets.top + (availableHeight - height) / 2;
      message.setBounds(insets.left, y, width, height);
    }
  }
}

package com.holtherndon.bazelviz.ui.capture;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Shared selectable, wrapping ANSI text surface for live Console and recorded error output. */
public final class ConsoleTextPane extends JTextPane {

  private static final long serialVersionUID = 1L;
  private static final ConsoleModel.AnsiStyle DEFAULT_STYLE =
      new ConsoleModel.AnsiStyle(null, null, false, false, false, false, false, false, false);

  private ConsoleModel.Transcript transcript;

  public ConsoleTextPane() {
    setEditable(false);
    setFont(new Font(Font.MONOSPACED, Font.PLAIN, getFont().getSize()));
    setMargin(new Insets(6, 8, 6, 8));
    setMinimumSize(new Dimension(0, getFontMetrics(getFont()).getHeight()));
    getAccessibleContext().setAccessibleName("Build output");
    setToolTipText("ANSI colours and emphasis are shown. Long lines wrap; select text to copy it.");
    ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
  }

  /** Displays an immutable transcript prepared off the EDT. Must be called on the EDT. */
  public void showTranscript(ConsoleModel.Transcript value) {
    transcript = value;
    setText("");
    try {
      for (ConsoleModel.StyledLine line : value.lines()) {
        appendLine(line, true);
      }
      appendLine(value.partialLine(), false);
      setCaretPosition(0);
    } catch (BadLocationException impossible) {
      throw new IllegalStateException("could not display the console transcript", impossible);
    }
  }

  @Override
  public void updateUI() {
    super.updateUI();
    if (transcript != null) {
      int start = getSelectionStart();
      int end = getSelectionEnd();
      showTranscript(transcript);
      select(start, end);
    }
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  /** Appends already interpreted ANSI runs; never interprets text as HTML or shell commands. */
  void appendLine(ConsoleModel.StyledLine line, boolean newline) throws BadLocationException {
    StyledDocument document = getStyledDocument();
    for (ConsoleModel.StyledRun run : line.runs()) {
      document.insertString(document.getLength(), run.text(), attributes(run.style()));
    }
    if (newline) {
      document.insertString(document.getLength(), "\n", attributes(DEFAULT_STYLE));
    }
  }

  private AttributeSet attributes(ConsoleModel.AnsiStyle style) {
    SimpleAttributeSet attributes = new SimpleAttributeSet();
    Color foreground = colour(style.foregroundRgb(), getForeground());
    Color background = colour(style.backgroundRgb(), getBackground());
    if (style.inverse()) {
      Color exchanged = foreground;
      foreground = background;
      background = exchanged;
    }
    if (style.faint()) {
      foreground = blend(foreground, background);
    }
    if (style.concealed()) {
      foreground = background;
    }
    StyleConstants.setForeground(attributes, foreground);
    StyleConstants.setBackground(attributes, background);
    StyleConstants.setBold(attributes, style.bold());
    StyleConstants.setItalic(attributes, style.italic());
    StyleConstants.setUnderline(attributes, style.underline());
    StyleConstants.setStrikeThrough(attributes, style.strikethrough());
    return attributes;
  }

  private static Color colour(Integer rgb, Color fallback) {
    return rgb == null ? fallback : new Color(rgb);
  }

  private static Color blend(Color foreground, Color background) {
    return new Color(
        (foreground.getRed() + background.getRed() * 2) / 3,
        (foreground.getGreen() + background.getGreen() * 2) / 3,
        (foreground.getBlue() + background.getBlue() * 2) / 3);
  }
}

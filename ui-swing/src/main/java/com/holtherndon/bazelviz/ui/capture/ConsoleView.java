package com.holtherndon.bazelviz.ui.capture;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The build's wrapping, ANSI-aware console output, as it arrives.
 *
 * <h2>Appending, not rebuilding</h2>
 *
 * <p>Bazel repaints a progress block several times a second. Setting the document's whole text on
 * every repaint is quadratic once a log grows, and it discards selection and scroll position.
 * Committed styled lines are therefore appended once; only the unfinished tail is replaced.
 *
 * <h2>Follow means follow</h2>
 *
 * <p>The caret never follows document edits by itself. Scrolling to the bottom happens only while
 * “Follow output” is selected, so reading or copying earlier output is not interrupted. Long lines
 * wrap to the viewport instead of forcing the full Console card wider than the window.
 */
public final class ConsoleView extends JPanel {

  private static final long serialVersionUID = 1L;

  private final ConsoleModel model;
  private final WrappingTextPane area = new WrappingTextPane();
  private final JCheckBox follow = new JCheckBox("Follow output", true);
  private final JLabel limits = new JLabel(" ");

  private boolean repaintPending;

  /** Lines already in the document, counted the way {@link ConsoleModel} counts. */
  private long renderedLines;

  /** Document offset where the unfinished last line starts. */
  private int partialStart;

  /** Document offsets for committed lines, relative to {@link #renderedBase}. */
  private final List<Integer> committedLineStarts = new ArrayList<>();

  /** Global model line represented by {@code committedLineStarts[0]}. */
  private long renderedBase;

  public ConsoleView() {
    this(ConsoleModel.DEFAULT_MAX_LINES);
  }

  /** Creates a Console with an explicit retained-line cap for focused tests. */
  ConsoleView(int maxLines) {
    super(new BorderLayout(0, 4));
    model = new ConsoleModel(maxLines);
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    area.setEditable(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
    area.setMargin(new Insets(6, 8, 6, 8));
    area.getAccessibleContext().setAccessibleName("Build output");
    area.setToolTipText(
        "ANSI colours and emphasis are shown. Long lines wrap; select text to copy it.");
    // Without this, every append scrolls the pane and clears the selection,
    // which is what made the follow checkbox look inert.
    ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

    JPanel controls = new JPanel(new BorderLayout());
    controls.add(follow, BorderLayout.WEST);
    controls.add(limits, BorderLayout.CENTER);

    add(controls, BorderLayout.NORTH);
    add(new JScrollPane(area), BorderLayout.CENTER);
  }

  /** Appends console bytes. EDT only. */
  public void append(byte[] data, int offset, int length) {
    assert SwingUtilities.isEventDispatchThread() : "the console must be updated on the EDT";
    model.append(data, offset, length);
    scheduleRepaint();
  }

  /** Empties the view and resets ANSI state for a new build. EDT only. */
  public void clear() {
    model.clear();
    area.setText("");
    renderedLines = 0;
    renderedBase = 0;
    committedLineStarts.clear();
    partialStart = 0;
    limits.setText(" ");
  }

  /** Re-resolves default and faint colours after a look-and-feel change. EDT only. */
  public void refreshTheme() {
    assert SwingUtilities.isEventDispatchThread() : "the console theme must change on the EDT";
    rebuildDocument();
  }

  /** The model, for tests. */
  public ConsoleModel model() {
    return model;
  }

  /** True when the view is following the tail. For tests. */
  public boolean isFollowing() {
    return follow.isSelected();
  }

  /** Sets whether the view follows the tail. For tests. */
  public void setFollowing(boolean value) {
    follow.setSelected(value);
  }

  /** Coalesces output bursts into at most one pending document update. */
  private void scheduleRepaint() {
    if (repaintPending) {
      return;
    }
    repaintPending = true;
    SwingUtilities.invokeLater(this::render);
  }

  private void render() {
    repaintPending = false;
    if (model.droppedLines() > renderedBase && !trimBefore(model.droppedLines())) {
      rebuildDocument();
      finishRender();
      return;
    }
    ConsoleModel.Delta delta = model.since(renderedLines, renderedBase);
    if (delta.resetRequired()) {
      area.setText("");
      renderedLines = delta.dropped();
      renderedBase = delta.dropped();
      committedLineStarts.clear();
      partialStart = 0;
    } else if (delta.replaceFrom() < renderedLines && !truncateFrom(delta.replaceFrom())) {
      rebuildDocument();
      finishRender();
      return;
    }

    try {
      for (ConsoleModel.StyledLine line : delta.styledLines()) {
        committedLineStarts.add(partialStart);
        replaceTail(line, true);
        partialStart = area.getDocument().getLength();
      }
      renderedLines = delta.total();
      replaceTail(model.partialStyledLine(), false);
    } catch (BadLocationException inconsistentDocument) {
      // All offsets come from this document on the EDT. A complete rebuild is
      // the safe recovery if a look and feel or document implementation changes them.
      rebuildDocument();
    }

    finishRender();
  }

  private void finishRender() {
    if (model.droppedLines() > 0) {
      limits.setText(
          "  showing the last %,d lines; %,d earlier lines are not shown"
                  .formatted(model.retainedLines(), model.droppedLines())
              + " (the complete output is in the session's raw/ directory)");
    }
    if (follow.isSelected()) {
      area.setCaretPosition(area.getDocument().getLength());
    }
  }

  /** Removes lines evicted by the model while retaining and restyling no surviving text. */
  private boolean trimBefore(long newBase) {
    long removeCount = newBase - renderedBase;
    if (removeCount < 0 || removeCount > committedLineStarts.size()) {
      return false;
    }
    int keepIndex = (int) removeCount;
    int documentOffset =
        keepIndex == committedLineStarts.size() ? partialStart : committedLineStarts.get(keepIndex);
    try {
      StyledDocument document = area.getStyledDocument();
      document.remove(0, documentOffset);
      committedLineStarts.subList(0, keepIndex).clear();
      for (int index = 0; index < committedLineStarts.size(); index++) {
        committedLineStarts.set(index, committedLineStarts.get(index) - documentOffset);
      }
      partialStart -= documentOffset;
      renderedBase = newBase;
      return true;
    } catch (BadLocationException inconsistentDocument) {
      return false;
    }
  }

  /** Removes a cursor-rewritten suffix without rebuilding the retained transcript prefix. */
  private boolean truncateFrom(long globalLine) {
    long relative = globalLine - renderedBase;
    if (relative < 0 || relative > committedLineStarts.size()) {
      return false;
    }
    int lineIndex = (int) relative;
    int documentOffset =
        lineIndex == committedLineStarts.size() ? partialStart : committedLineStarts.get(lineIndex);
    try {
      StyledDocument document = area.getStyledDocument();
      document.remove(documentOffset, document.getLength() - documentOffset);
      committedLineStarts.subList(lineIndex, committedLineStarts.size()).clear();
      partialStart = documentOffset;
      renderedLines = globalLine;
      return true;
    } catch (BadLocationException inconsistentDocument) {
      return false;
    }
  }

  /** Replaces the unfinished tail and optionally commits it with a newline. */
  private void replaceTail(ConsoleModel.StyledLine line, boolean newline)
      throws BadLocationException {
    StyledDocument document = area.getStyledDocument();
    int length = document.getLength();
    if (length > partialStart) {
      document.remove(partialStart, length - partialStart);
    }
    append(document, line);
    if (newline) {
      document.insertString(document.getLength(), "\n", attributes(ConsoleModelDefaults.STYLE));
    }
  }

  private void rebuildDocument() {
    area.setText("");
    StyledDocument document = area.getStyledDocument();
    committedLineStarts.clear();
    try {
      for (ConsoleModel.StyledLine line : model.styledLines()) {
        committedLineStarts.add(document.getLength());
        append(document, line);
        document.insertString(document.getLength(), "\n", attributes(ConsoleModelDefaults.STYLE));
      }
      partialStart = document.getLength();
      append(document, model.partialStyledLine());
      renderedBase = model.droppedLines();
      renderedLines = model.droppedLines() + model.retainedLines();
      model.acknowledgeChanges();
    } catch (BadLocationException impossible) {
      throw new IllegalStateException("could not rebuild the console document", impossible);
    }
  }

  private void append(StyledDocument document, ConsoleModel.StyledLine line)
      throws BadLocationException {
    for (ConsoleModel.StyledRun run : line.runs()) {
      document.insertString(document.getLength(), run.text(), attributes(run.style()));
    }
  }

  private AttributeSet attributes(ConsoleModel.AnsiStyle style) {
    SimpleAttributeSet attributes = new SimpleAttributeSet();
    Color foreground = colour(style.foregroundRgb(), area.getForeground());
    Color background = colour(style.backgroundRgb(), area.getBackground());
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

  // Focused tests inspect the rendered document rather than reaching through component order.
  String renderedTextForTest() {
    try {
      return area.getDocument().getText(0, area.getDocument().getLength());
    } catch (BadLocationException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  Color foregroundAtForTest(int offset) {
    return StyleConstants.getForeground(
        area.getStyledDocument().getCharacterElement(offset).getAttributes());
  }

  Color defaultForegroundForTest() {
    return area.getForeground();
  }

  boolean wrapsLinesForTest() {
    return area.getScrollableTracksViewportWidth();
  }

  private static final class WrappingTextPane extends JTextPane {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }
  }

  private static final class ConsoleModelDefaults {

    private static final ConsoleModel.AnsiStyle STYLE =
        new ConsoleModel.AnsiStyle(null, null, false, false, false, false, false, false, false);

    private ConsoleModelDefaults() {}
  }
}

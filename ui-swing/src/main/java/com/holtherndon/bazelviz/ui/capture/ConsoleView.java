package com.holtherndon.bazelviz.ui.capture;

import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;

/**
 * The build's console output, as it arrives (plan 24, Phase 2 UI deliverable "console").
 *
 * <h2>Appending, not rebuilding</h2>
 *
 * <p>Bazel repaints a progress block several times a second, each repaint a carriage return and a
 * rewritten line. Setting the document's whole text on every one of those is quadratic in the log's
 * length — measured at 15–50 ms per repaint once the retention cap is reached, on the event
 * dispatch thread — and it also throws away the user's selection and scroll position each time.
 *
 * <p>So the document is appended to. {@link ConsoleModel} resolves the carriage returns into
 * committed lines and one partial line; committed lines are appended once and never touched again,
 * and only the partial line is rewritten as it changes.
 *
 * <h2>Follow means follow</h2>
 *
 * <p>The caret is set to never update on its own, so appending text does not drag the viewport.
 * Scrolling to the bottom happens only when "Follow output" is ticked. A user who has scrolled up
 * to read an error is reading it, and the single most annoying thing a live console can do is yank
 * them away from it on the next progress repaint.
 */
public final class ConsoleView extends JPanel {

  private static final long serialVersionUID = 1L;

  private final ConsoleModel model = new ConsoleModel();
  private final JTextArea area = new JTextArea();
  private final JCheckBox follow = new JCheckBox("Follow output", true);
  private final JLabel limits = new JLabel(" ");

  private boolean repaintPending;

  /** Lines already in the document, counted the way {@link ConsoleModel} counts. */
  private long renderedLines;

  /** Document offset where the unfinished last line starts. */
  private int partialStart;

  public ConsoleView() {
    super(new BorderLayout(0, 4));
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    area.setEditable(false);
    area.setLineWrap(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
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

  /** Empties the view, for a new build. EDT only. */
  public void clear() {
    model.clear();
    area.setText("");
    renderedLines = 0;
    partialStart = 0;
    limits.setText(" ");
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

  /**
   * Coalesces repaints.
   *
   * <p>A build can deliver dozens of chunks between two frames. At most one update is queued at a
   * time, and it renders whatever has accumulated by the time it runs.
   */
  private void scheduleRepaint() {
    if (repaintPending) {
      return;
    }
    repaintPending = true;
    SwingUtilities.invokeLater(this::render);
  }

  private void render() {
    repaintPending = false;
    ConsoleModel.Delta delta = model.since(renderedLines);
    if (delta.resetRequired()) {
      // The retention cap discarded lines this document had not rendered,
      // so what is on screen is no longer a prefix of the log. Rebuilding
      // is the only correct answer, and it happens once per overflow
      // rather than once per repaint.
      area.setText("");
      renderedLines = delta.dropped();
      partialStart = 0;
    }

    StringBuilder appended = new StringBuilder();
    for (String line : delta.lines()) {
      appended.append(line).append('\n');
    }
    if (!appended.isEmpty()) {
      replaceTail(appended.toString());
      partialStart = area.getDocument().getLength();
      renderedLines = delta.total();
    }

    String partial = model.partialLine();
    if (!partial.isEmpty() || area.getDocument().getLength() > partialStart) {
      replaceTail(partial);
    }

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

  /** Replaces everything from {@link #partialStart} to the end. */
  private void replaceTail(String text) {
    try {
      int length = area.getDocument().getLength();
      if (length > partialStart) {
        area.getDocument().remove(partialStart, length - partialStart);
      }
      area.getDocument().insertString(area.getDocument().getLength(), text, null);
    } catch (BadLocationException impossible) {
      // The offsets come from the document itself a moment earlier, on the
      // same thread. Rebuilding is the safe recovery if that is ever wrong.
      area.setText(model.text());
      partialStart = area.getDocument().getLength();
    }
  }
}

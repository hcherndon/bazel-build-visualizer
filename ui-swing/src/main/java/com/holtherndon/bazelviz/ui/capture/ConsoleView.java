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

/**
 * The build's console output, as it arrives (plan 24, Phase 2 UI deliverable
 * "console").
 *
 * <h2>Repainting, cheaply</h2>
 *
 * <p>Bazel emits a progress repaint several times a second, and each one is a
 * carriage return followed by a rewritten line. Setting the whole document text
 * on every chunk would be quadratic in the size of the log; appending blindly
 * would fill the pane with half-overwritten progress. So {@link ConsoleModel}
 * resolves the carriage returns and this view rewrites only when the resolved
 * text has actually changed, coalescing repaints on the EDT.
 *
 * <p>Follow-the-tail is a checkbox rather than always-on: a user who has
 * scrolled up to read an error is reading it, and yanking them back to the
 * bottom on the next progress repaint is the single most annoying thing a live
 * console can do.
 */
public final class ConsoleView extends JPanel {

    private static final long serialVersionUID = 1L;

    private final ConsoleModel model = new ConsoleModel();
    private final JTextArea area = new JTextArea();
    private final JCheckBox follow = new JCheckBox("Follow output", true);
    private final JLabel limits = new JLabel(" ");

    private boolean repaintPending;

    public ConsoleView() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));

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
        limits.setText(" ");
    }

    /** The model, for tests. */
    public ConsoleModel model() {
        return model;
    }

    /**
     * Coalesces repaints.
     *
     * <p>A build can deliver dozens of chunks between two frames. Rewriting the
     * document for each of them would spend the whole EDT on text layout for
     * output the user never sees, so at most one rewrite is queued at a time and
     * it renders whatever has accumulated by the time it runs.
     */
    private void scheduleRepaint() {
        if (repaintPending) {
            return;
        }
        repaintPending = true;
        SwingUtilities.invokeLater(() -> {
            repaintPending = false;
            area.setText(model.text());
            if (model.droppedLines() > 0) {
                limits.setText("  showing the last %,d lines; %,d earlier lines are not shown"
                        .formatted(model.retainedLines(), model.droppedLines())
                        + " (the complete output is in the session's raw/ directory)");
            }
            if (follow.isSelected()) {
                area.setCaretPosition(area.getDocument().getLength());
            }
        });
    }
}

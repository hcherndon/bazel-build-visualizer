package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * The live capture status and the stop controls (plan 24, Phase 2 UI
 * deliverables "live capture status" and "cancel/terminate controls").
 *
 * <p>Three counters rather than a single number, because the gaps between them
 * are the only visible sign of pressure and each answers a different question.
 * The progress bar is indeterminate on purpose: the number of events a build
 * will emit is not known until it ends, and a bar that pretended to know would
 * be inventing a denominator.
 *
 * <p>Every method must be called on the EDT.
 */
public final class CapturePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel phase = new JLabel(CaptureStatusModel.Phase.IDLE.label());
    private final JLabel counters = new JLabel(" ");
    private final JLabel detail = new JLabel(" ");
    private final JProgressBar activity = new JProgressBar();
    private final JButton cancel = new JButton("Cancel Build");
    private final JButton terminate = new JButton("Terminate");
    private final JButton forceKill = new JButton("Force Kill");

    private Consumer<CancellationMode> stopAction = mode -> {};

    public CapturePanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        phase.setFont(phase.getFont().deriveFont(Font.BOLD, phase.getFont().getSize2D() + 2f));
        counters.setFont(new Font(Font.MONOSPACED, Font.PLAIN, counters.getFont().getSize()));
        activity.setIndeterminate(false);
        activity.setPreferredSize(new Dimension(240, 6));

        JPanel status = new JPanel();
        status.setLayout(new javax.swing.BoxLayout(status, javax.swing.BoxLayout.Y_AXIS));
        status.add(phase);
        status.add(counters);
        status.add(detail);
        status.add(activity);

        cancel.setToolTipText(
                "Ask Bazel to stop as Ctrl-C would. The build finishes its event stream, so the"
                        + " session records a real ending.");
        terminate.setToolTipText(
                "Send a terminate signal. For the Bazel client this behaves the same as Cancel.");
        forceKill.setToolTipText(
                "Kill the client outright. The Bazel server keeps working for a couple of seconds"
                        + " and finishes the stream itself, so this is slower to settle, not faster.");

        cancel.addActionListener(event -> stopAction.accept(CancellationMode.CANCEL));
        terminate.addActionListener(event -> stopAction.accept(CancellationMode.TERMINATE));
        forceKill.addActionListener(event -> stopAction.accept(CancellationMode.FORCE_KILL));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(cancel);
        buttons.add(terminate);
        buttons.add(forceKill);

        add(status, BorderLayout.NORTH);
        add(buttons, BorderLayout.CENTER);
        show(CaptureStatusModel.idle());
    }

    /** What the stop buttons do. */
    public void setStopAction(Consumer<CancellationMode> action) {
        this.stopAction = action == null ? mode -> {} : action;
    }

    /** Renders one snapshot. EDT only. */
    public void show(CaptureStatusModel model) {
        assert SwingUtilities.isEventDispatchThread() : "capture status must be shown on the EDT";
        phase.setText(model.phase().label());
        counters.setText(model.counterLine());
        detail.setText(model.detail().isEmpty() ? " " : model.detail());
        activity.setIndeterminate(!model.phase().isTerminal()
                && model.phase() != CaptureStatusModel.Phase.IDLE);
        cancel.setEnabled(model.cancellable());
        terminate.setEnabled(model.cancellable());
        forceKill.setEnabled(model.cancellable());
    }
}

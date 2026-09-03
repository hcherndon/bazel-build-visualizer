package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * The live capture status and the stop controls (plan 24, Phase 2 UI deliverables "live capture
 * status" and "cancel/terminate controls").
 *
 * <p>Three counters rather than a single number, because the gaps between them are the only visible
 * sign of pressure and each answers a different question. The progress bar is indeterminate on
 * purpose: the number of events a build will emit is not known until it ends, and a bar that
 * pretended to know would be inventing a denominator.
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
  private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
  private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

  private Consumer<CancellationMode> stopAction = mode -> {};

  public CapturePanel() {
    super(new BorderLayout(8, 0));
    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Build status"),
            BorderFactory.createEmptyBorder(2, 8, 4, 8)));

    PlainText.disableHtml(phase);
    PlainText.disableHtml(counters);
    PlainText.disableHtml(detail);
    phase.setFont(phase.getFont().deriveFont(Font.BOLD));
    counters.setFont(new Font(Font.MONOSPACED, Font.PLAIN, counters.getFont().getSize()));
    activity.setIndeterminate(false);
    activity.setPreferredSize(new Dimension(120, 6));
    detail.setMinimumSize(new Dimension(0, detail.getPreferredSize().height));

    JPanel status = new JPanel(new GridBagLayout());
    status.add(phase, statusConstraints(0, 0));
    status.add(counters, statusConstraints(1, 0));
    status.add(detail, statusConstraints(2, 1));

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

    buttons.add(cancel);
    buttons.add(terminate);
    buttons.add(forceKill);

    actions.add(activity);
    actions.add(buttons);

    add(status, BorderLayout.CENTER);
    add(actions, BorderLayout.EAST);
    show(CaptureStatusModel.idle());
  }

  private static GridBagConstraints statusConstraints(int x, double weightX) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.weightx = weightX;
    constraints.fill = weightX > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, x == 0 ? 0 : 10, 0, 0);
    return constraints;
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
    String context = model.detail().isEmpty() ? " " : model.detail();
    detail.setText(context);
    detail.setToolTipText(model.detail().isEmpty() ? null : PlainText.tooltip(model.detail()));
    detail
        .getAccessibleContext()
        .setAccessibleDescription(model.detail().isEmpty() ? null : model.detail());
    boolean active = !model.phase().isTerminal() && model.phase() != CaptureStatusModel.Phase.IDLE;
    activity.setIndeterminate(active);
    activity.setVisible(active);
    cancel.setEnabled(model.cancellable());
    terminate.setEnabled(model.cancellable());
    forceKill.setEnabled(model.cancellable());
    buttons.setVisible(model.cancellable());
    actions.setVisible(active || model.cancellable());
  }

  JLabel phaseForTest() {
    return phase;
  }

  JLabel countersForTest() {
    return counters;
  }

  JLabel detailForTest() {
    return detail;
  }

  JProgressBar activityForTest() {
    return activity;
  }

  JPanel buttonsForTest() {
    return buttons;
  }
}

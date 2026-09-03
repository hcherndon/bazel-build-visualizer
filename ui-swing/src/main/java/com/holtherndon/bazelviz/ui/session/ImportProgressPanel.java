package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.capture.file.importer.ImportPhase;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;

/**
 * The import progress view: what phase the import is in, how much it has read, how fast, and a
 * cancel button that really cancels.
 *
 * <p>All rendering, no logic — {@link ImportProgressModel} decides what is known and this panel
 * shows exactly that. The bar is determinate only when {@link
 * ImportProgressModel.Snapshot#determinate()} says a real percentage exists; during detection and
 * while the source is being hashed and copied there is no total to divide by, so the bar runs
 * indeterminate rather than displaying a number nobody measured.
 *
 * <p>EDT only.
 */
public final class ImportProgressPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JLabel sourceLabel = new JLabel(" ");
  private final JLabel phaseLabel = new JLabel(" ");
  private final JProgressBar bar = new JProgressBar(0, 1000);
  private final JLabel recordsValue = value();
  private final JLabel bytesValue = value();
  private final JLabel rateValue = value();
  private final JLabel byteRateValue = value();
  private final JLabel percentValue = value();
  private final JLabel elapsedValue = value();
  private final JButton cancelButton = new JButton("Cancel import");

  public ImportProgressPanel() {
    super(new BorderLayout());
    setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

    JPanel header = new JPanel();
    header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    sourceLabel.setAlignmentX(LEFT_ALIGNMENT);
    phaseLabel.setAlignmentX(LEFT_ALIGNMENT);
    header.add(sourceLabel);
    header.add(Box.createVerticalStrut(4));
    header.add(phaseLabel);
    header.add(Box.createVerticalStrut(12));
    bar.setAlignmentX(LEFT_ALIGNMENT);
    bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
    header.add(bar);

    JPanel grid = new JPanel(new GridLayout(0, 4, 16, 4));
    grid.setBorder(BorderFactory.createEmptyBorder(16, 0, 16, 0));
    grid.add(caption("Records read"));
    grid.add(recordsValue);
    grid.add(caption("Bytes read"));
    grid.add(bytesValue);
    grid.add(caption("Record rate"));
    grid.add(rateValue);
    grid.add(caption("Byte rate"));
    grid.add(byteRateValue);
    grid.add(caption("Complete"));
    grid.add(percentValue);
    grid.add(caption("Elapsed"));
    grid.add(elapsedValue);

    JPanel actions = new JPanel(new BorderLayout());
    actions.add(cancelButton, BorderLayout.EAST);

    JPanel body = new JPanel(new BorderLayout());
    body.add(header, BorderLayout.NORTH);
    body.add(grid, BorderLayout.CENTER);
    body.add(actions, BorderLayout.SOUTH);

    add(body, BorderLayout.NORTH);
    reset();
  }

  /** Names the file or session being worked on and re-enables cancelling. */
  public void beginRun(Path source, boolean resuming) {
    Objects.requireNonNull(source, "source");
    sourceLabel.setText((resuming ? "Resuming " : "Importing ") + source);
    cancelButton.setEnabled(true);
    cancelButton.setText("Cancel import");
    reset();
  }

  /** Installs the action the cancel button runs. */
  public void setCancelAction(Runnable action) {
    Objects.requireNonNull(action, "action");
    for (var listener : cancelButton.getActionListeners()) {
      cancelButton.removeActionListener(listener);
    }
    cancelButton.addActionListener(
        event -> {
          cancelButton.setEnabled(false);
          cancelButton.setText("Cancelling at the next record…");
          action.run();
        });
  }

  /** Renders one snapshot. */
  public void update(ImportProgressModel.Snapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    phaseLabel.setText(describe(snapshot.phase()));
    if (snapshot.determinate()) {
      bar.setIndeterminate(false);
      bar.setValue((int) Math.round(snapshot.fractionComplete().getAsDouble() * 1000));
    } else {
      // No total means no percentage. Anything else here would be invented.
      bar.setIndeterminate(true);
    }
    recordsValue.setText(EventValueFormat.count(snapshot.recordsRead()));
    bytesValue.setText(
        snapshot.totalBytes().isPresent()
            ? EventValueFormat.bytes(snapshot.bytesRead())
                + " of "
                + EventValueFormat.bytes(snapshot.totalBytes())
            : EventValueFormat.bytes(snapshot.bytesRead()) + " of " + EventValueFormat.UNKNOWN);
    rateValue.setText(EventValueFormat.rate(snapshot.recordsPerSecond(), "records"));
    byteRateValue.setText(EventValueFormat.byteRate(snapshot.bytesPerSecond()));
    percentValue.setText(EventValueFormat.percentage(snapshot.fractionComplete()));
    elapsedValue.setText("%.1f s".formatted(snapshot.elapsedNanos() / 1_000_000_000.0));
  }

  /** Shows a terminal message and disables cancelling. */
  public void finish(String message) {
    phaseLabel.setText(Objects.requireNonNull(message, "message"));
    bar.setIndeterminate(false);
    cancelButton.setEnabled(false);
  }

  private void reset() {
    bar.setIndeterminate(true);
    recordsValue.setText(EventValueFormat.count(0));
    bytesValue.setText(EventValueFormat.bytes(0));
    rateValue.setText(EventValueFormat.UNKNOWN);
    byteRateValue.setText(EventValueFormat.UNKNOWN);
    percentValue.setText(EventValueFormat.UNKNOWN);
    elapsedValue.setText(EventValueFormat.UNKNOWN);
    phaseLabel.setText(describe(ImportPhase.DETECTING));
  }

  private static String describe(ImportPhase phase) {
    return switch (phase) {
      case DETECTING -> "Detecting the format from the file's contents…";
      case PRESERVING -> "Hashing and preserving the original source…";
      case REPLAYING_JOURNAL -> "Replaying journaled records left by the interrupted run…";
      case READING -> "Reading records: journal first, then normalize…";
      case FINALIZING -> "Building indexes and writing the manifest…";
    };
  }

  private static JLabel caption(String text) {
    JLabel label = new JLabel(text, SwingConstants.RIGHT);
    label.setEnabled(false);
    return label;
  }

  private static JLabel value() {
    return new JLabel(EventValueFormat.UNKNOWN);
  }
}

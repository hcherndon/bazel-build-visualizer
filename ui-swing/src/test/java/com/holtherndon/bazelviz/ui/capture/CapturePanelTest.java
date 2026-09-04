package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The compact live-status strip shown above console output. */
final class CapturePanelTest {

  @Test
  @DisplayName("shared Console chrome reparents the live controls without replacing callbacks")
  void sharedChromeOwnsLiveActions() throws Exception {
    CapturePanel panel = panel();
    PageToolbar toolbar = new PageToolbar("Console");
    AtomicReference<CancellationMode> stopped = new AtomicReference<>();
    CaptureStatusModel active =
        new CaptureStatusModel(
            CaptureStatusModel.Phase.CAPTURING,
            Optional.of(new CaptureProgress(1, 1, 1, 1, 0, List.of(), true)),
            "running",
            true);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setStopAction(stopped::set);
          panel.installPageToolbar(toolbar);
          panel.show(active);
          findButton(toolbar, "Cancel Build").doClick();
        });

    assertThat(toolbar.actionCount()).isEqualTo(4);
    assertThat(panel.isVisible()).isFalse();
    assertThat(stopped).hasValue(CancellationMode.CANCEL);
  }

  @Test
  @DisplayName("idle status is one framed strip without inactive controls")
  void idleStatusIsCompact() throws Exception {
    CapturePanel panel = panel();

    assertThat(panel.getLayout()).isInstanceOf(BorderLayout.class);
    assertThat(panel.getBorder()).isInstanceOf(CompoundBorder.class);
    CompoundBorder border = (CompoundBorder) panel.getBorder();
    assertThat(border.getOutsideBorder()).isInstanceOf(TitledBorder.class);
    assertThat(((TitledBorder) border.getOutsideBorder()).getTitle()).isEqualTo("Build status");
    assertThat(panel.phaseForTest().getText()).isEqualTo("No build running");
    assertThat(panel.countersForTest().getText()).isEqualTo("no events yet");
    assertThat(panel.activityForTest().isVisible()).isFalse();
    assertThat(panel.buttonsForTest().isVisible()).isFalse();
    assertThat(panel.getPreferredSize().height).isLessThanOrEqualTo(70);
  }

  @Test
  @DisplayName("active status keeps the full context in a tooltip and reveals stop controls")
  void activeStatusKeepsFullContext() throws Exception {
    CapturePanel panel = panel();
    String detail = "bazel test //a/very/long/package:all --config=diagnostics";
    CaptureStatusModel active =
        new CaptureStatusModel(
            CaptureStatusModel.Phase.CAPTURING,
            Optional.of(new CaptureProgress(14, 12, 9, 1_024, 0, List.of(), true)),
            detail,
            true);

    SwingUtilities.invokeAndWait(() -> panel.show(active));

    assertThat(panel.phaseForTest().getText()).isEqualTo("Capturing");
    assertThat(panel.countersForTest().getText())
        .isEqualTo("14 received · 12 journaled · 9 indexed  ⚠ capture lagging");
    assertThat(panel.detailForTest().getText()).isEqualTo(detail);
    assertThat(panel.detailForTest().getToolTipText()).isEqualTo(detail);
    assertThat(panel.activityForTest().isVisible()).isTrue();
    assertThat(panel.activityForTest().isIndeterminate()).isTrue();
    assertThat(panel.buttonsForTest().isVisible()).isTrue();

    SwingUtilities.invokeAndWait(
        () ->
            panel.show(
                active.withPhase(CaptureStatusModel.Phase.DONE, "Build completed successfully.")));
    assertThat(panel.activityForTest().isVisible()).isFalse();
    assertThat(panel.buttonsForTest().isVisible()).isFalse();
  }

  private static CapturePanel panel() throws Exception {
    AtomicReference<CapturePanel> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> panel.set(new CapturePanel()));
    return panel.get();
  }

  private static AbstractButton findButton(Container root, String text) {
    for (Component component : root.getComponents()) {
      if (component instanceof AbstractButton button && text.equals(button.getText())) {
        return button;
      }
      if (component instanceof Container nested) {
        AbstractButton found = findButton(nested, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}

package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The compact detail sections used by the pre-launch review. */
final class DisclosurePanelTest {

  @Test
  @DisplayName("launch detail sections clearly expand and collapse from one control")
  void togglesDetails() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JLabel details = new JLabel("details");
          DisclosurePanel panel = new DisclosurePanel("Capture changes · 3", details, false);

          assertThat(panel.isExpanded()).isFalse();
          assertThat(details.isVisible()).isFalse();
          assertThat(panel.toggleForTest().getText()).startsWith("▸ ");
          assertThat(panel.toggleForTest().getAccessibleContext().getAccessibleName())
              .isEqualTo("Expand Capture changes · 3");

          panel.toggleForTest().doClick();

          assertThat(panel.isExpanded()).isTrue();
          assertThat(details.isVisible()).isTrue();
          assertThat(panel.toggleForTest().getText()).startsWith("▾ ");
        });
  }
}

package com.holtherndon.bazelviz.ui.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.CountedPage;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class InspectionPagingPanelTest {

  @Test
  void statesExactCurrentRangeAndRowsOutsideTheRetainedPage() throws Exception {
    InspectionPagingPanel panel = new InspectionPagingPanel();
    AtomicInteger loads = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () ->
            panel.setPage(
                "attempts",
                "Attempts",
                new CountedPage<>(List.of(101, 102), 250, 148, Optional.of(102)),
                loads::incrementAndGet));

    assertThat(textOf(panel)).contains("Attempts 101–102 of 250 · 248 not on this page");
    AbstractButton next = buttonsOf(panel).getFirst();
    assertThat(next.getText()).isEqualTo("Load next attempts");
    SwingUtilities.invokeAndWait(next::doClick);
    assertThat(loads).hasValue(1);
  }

  @Test
  void independentCollectionsRemainAndFinishedPagesHaveNoButton() throws Exception {
    InspectionPagingPanel panel = new InspectionPagingPanel();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.setPage(
              "attempts",
              "Attempts",
              new CountedPage<>(List.of("one"), 1, 0, Optional.empty()),
              () -> {});
          panel.setPage(
              "logs", "Logs", new CountedPage<>(List.of(), 0, 0, Optional.empty()), () -> {});
        });

    assertThat(textOf(panel))
        .contains("Attempts 1 of 1 · 0 not on this page", "Logs 0 of 0 · 0 not on this page");
    assertThat(buttonsOf(panel)).isEmpty();

    SwingUtilities.invokeAndWait(panel::clear);
    assertThat(panel.isVisible()).isFalse();
    assertThat(panel.getComponentCount()).isZero();
  }

  private static List<String> textOf(Container root) {
    List<String> text = new ArrayList<>();
    for (Component component : allComponents(root)) {
      if (component instanceof JLabel label) {
        text.add(label.getText());
      }
    }
    return text;
  }

  private static List<AbstractButton> buttonsOf(Container root) {
    return allComponents(root).stream()
        .filter(AbstractButton.class::isInstance)
        .map(AbstractButton.class::cast)
        .toList();
  }

  private static List<Component> allComponents(Container root) {
    List<Component> components = new ArrayList<>();
    for (Component component : root.getComponents()) {
      components.add(component);
      if (component instanceof Container child) {
        components.addAll(allComponents(child));
      }
    }
    return components;
  }
}

package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.actions.ActionsView;
import com.holtherndon.bazelviz.ui.errors.ErrorsView;
import com.holtherndon.bazelviz.ui.events.EventsView;
import com.holtherndon.bazelviz.ui.targets.TargetsView;
import com.holtherndon.bazelviz.ui.tests.TestsView;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Empty entity views use one selectable, wrapping, theme-aware presentation. */
final class EmptyStateConsistencyTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("entity panes share the full-pane selectable empty state")
  void entityPanesUseSharedEmptyState() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          List<JComponent> views =
              List.of(
                  new ActionsView(),
                  new EventsView(),
                  new ErrorsView(),
                  new TargetsView(),
                  new TestsView());
          for (JComponent view : views) {
            List<EmptyStatePanel> states = descendantsOfType(view, EmptyStatePanel.class);
            assertThat(states).as(view.getClass().getSimpleName()).hasSize(1);
            EmptyStatePanel state = states.getFirst();
            assertThat(state.getText()).contains("No session is open");
            assertThat(state.isTextSelectable()).isTrue();
            assertThat(state.getMinimumSize().width).isLessThanOrEqualTo(80);
          }
        });
  }

  private static <T> List<T> descendantsOfType(Container root, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) {
        found.add(type.cast(child));
      }
      if (child instanceof Container nested) {
        found.addAll(descendantsOfType(nested, type));
      }
    }
    return found;
  }
}

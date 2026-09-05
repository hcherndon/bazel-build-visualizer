package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared page-chrome ownership for the action table's root controls. */
final class ActionsPageChromeTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("the existing labelled controls move into chrome in place")
  void rootControlsMoveToChrome() throws Exception {
    ActionsView view = onEdt(ActionsView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Actions"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isEqualTo(13);
          assertThat(labels(toolbar))
              .containsSubsequence("Mnemonic:", "Outcome:", "Output contains:", "Sort:");
          assertThat(SwingUtilities.isDescendingFrom(view.labelChipComponentForTest(), toolbar))
              .isTrue();
          assertThat(toolbar.metadata()).isEmpty();
          view.showEmpty("Reading actions…");
          assertThat(toolbar.metadata()).isEqualTo("Reading actions…");
          view.showEmpty("No session is open.");
          assertThat(toolbar.metadata()).isEmpty();
          return null;
        });
  }

  private static List<String> labels(Container root) {
    List<String> values = new ArrayList<>();
    for (Component component : descendants(root)) {
      if (component instanceof JLabel label) {
        values.add(label.getText());
      }
    }
    return values;
  }

  private static List<Component> descendants(Container root) {
    List<Component> found = new ArrayList<>();
    for (Component component : root.getComponents()) {
      found.add(component);
      if (component instanceof Container child) {
        found.addAll(descendants(child));
      }
    }
    return found;
  }

  private static <T> T onEdt(Callable<T> task) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(task.call());
          } catch (Throwable problem) {
            failure.set(problem);
          }
        });
    if (failure.get() instanceof Exception exception) {
      throw exception;
    }
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}

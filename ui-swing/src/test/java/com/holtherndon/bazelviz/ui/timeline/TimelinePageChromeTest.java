package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared page-chrome ownership for the timeline's root controls. */
final class TimelinePageChromeTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("root grouping controls move to chrome while axis navigation stays local")
  void rootControlsMoveWithoutAxisControls() throws Exception {
    TimelineView view = onEdt(TimelineView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Timeline"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isEqualTo(6);
          assertThat(hasLabel(toolbar, "Group by:")).isTrue();
          assertThat(hasLabel(toolbar, "Sort:")).isTrue();
          assertThat(hasLabel(toolbar, "Colour:")).isTrue();
          assertThat(hasButton(toolbar, "Fit build")).isFalse();
          assertThat(hasButton(view, "Fit build")).isTrue();

          assertThat(toolbar.metadata()).isEmpty();
          view.showEmpty("Building timeline…");
          assertThat(toolbar.metadata()).isEqualTo("Building timeline…");
          view.showEmpty("No session is open.");
          assertThat(toolbar.metadata()).isEmpty();
          return null;
        });
  }

  private static boolean hasLabel(Container root, String text) {
    return descendants(root).stream()
        .anyMatch(component -> component instanceof JLabel label && label.getText().equals(text));
  }

  private static boolean hasButton(Container root, String text) {
    return descendants(root).stream()
        .anyMatch(
            component ->
                component instanceof AbstractButton button && button.getText().equals(text));
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

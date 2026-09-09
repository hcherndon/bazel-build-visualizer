package com.holtherndon.bazelviz.ui.graph;

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

/** Shared page-chrome ownership for the Graph card's root controls. */
final class GraphPageChromeTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("source/find/open/browse move to chrome while canvas controls stay nested")
  void onlyRootControlsMoveAndInstallationIsIdempotent() throws Exception {
    GraphExplorerView view = onEdt(GraphExplorerView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Graph"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isEqualTo(7);
          assertThat(labels(toolbar)).contains("Graph source:", "Find node:");
          assertThat(buttons(toolbar)).contains("Open", "Browse nodes…", "Source help");
          assertThat(buttons(toolbar))
              .doesNotContain("Fit graph", "Reset moved nodes", "Export graph…");
          assertThat(buttons(view)).contains("Fit graph", "Reset moved nodes", "Export graph…");
          assertThat(toolbar.metadata()).isEmpty();
          return null;
        });
  }

  private static List<String> labels(Container root) {
    List<String> out = new ArrayList<>();
    for (Component component : descendants(root)) {
      if (component instanceof JLabel label) {
        out.add(label.getText());
      }
    }
    return out;
  }

  private static List<String> buttons(Container root) {
    List<String> out = new ArrayList<>();
    for (Component component : descendants(root)) {
      if (component instanceof AbstractButton button) {
        out.add(button.getText());
      }
    }
    return out;
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

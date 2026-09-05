package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared page-chrome ownership for both target explorers. */
final class TargetsPageChromeTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("Top Level Targets moves root controls but leaves pagination by the tree")
  void topLevelRootControlsMoveToChrome() throws Exception {
    TargetsView view = onEdt(TargetsView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Top Level Targets"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isEqualTo(9);
          assertThat(labels(toolbar)).containsSubsequence("View:", "Filter labels:");
          assertThat(hasButton(toolbar, "Load more targets")).isFalse();
          assertThat(hasButton(view, "Load more targets")).isTrue();
          JButton treeAction = view.toolbarButtonForTest(EntityActions.Command.OPEN_IN_TREE);
          assertThat(SwingUtilities.isDescendingFrom(treeAction, toolbar)).isTrue();
          assertThat(treeAction.getParent())
              .isInstanceOfSatisfying(
                  JComponent.class, wrapper -> assertThat(wrapper.getToolTipText()).isNotBlank());
          assertThat(toolbar.metadata()).isEmpty();
          view.showEmpty("Reading targets…");
          assertThat(toolbar.metadata()).isEqualTo("Reading targets…");
          return null;
        });
  }

  @Test
  @DisplayName("All Targets moves only its root filter and keeps pagination local")
  void allTargetsFilterMovesToChrome() throws Exception {
    AllTargetsView view = onEdt(AllTargetsView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("All Targets"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isEqualTo(2);
          assertThat(labels(toolbar)).contains("Filter labels:");
          assertThat(hasButton(toolbar, "Load more targets")).isFalse();
          assertThat(hasButton(view, "Load more targets")).isTrue();
          assertThat(toolbar.metadata()).isEmpty();
          view.showEmpty("Open All Targets to read target labels.");
          assertThat(toolbar.metadata()).isEqualTo("Open All Targets to read target labels.");
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

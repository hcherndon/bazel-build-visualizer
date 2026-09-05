package com.holtherndon.bazelviz.ui.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared page metadata for the test table. */
final class TestsPageChromeTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("Tests exposes cached state without inventing root actions")
  void cachedStateMovesToChrome() throws Exception {
    TestsView view = onEdt(TestsView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Tests"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.installPageToolbar(toolbar);

          assertThat(toolbar.actionCount()).isZero();
          assertThat(toolbar.metadata()).isEmpty();
          view.showEmpty("Reading tests…");
          assertThat(toolbar.metadata()).isEqualTo("Reading tests…");
          view.showEmpty("No session is open.");
          assertThat(toolbar.metadata()).isEmpty();
          return null;
        });
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

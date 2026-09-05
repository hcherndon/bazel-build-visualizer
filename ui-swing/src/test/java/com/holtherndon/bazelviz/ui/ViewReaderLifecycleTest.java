package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.actions.ActionsView;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.tests.TestsView;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Reader ownership at the worker-to-EDT handoff used by entity views. */
final class ViewReaderLifecycleTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @Timeout(30)
  @DisplayName("Actions closes a reader whose worker-side initialization fails")
  void actionsFailedOpenClosesReaderOffTheEventThread() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);
    AtomicBoolean closeOnEdt = new AtomicBoolean();
    EntityReader reader =
        reader(
            closed, closeOnEdt, "mnemonics", new IllegalStateException("mnemonic catalog failed"));
    ActionsView view = onEdt(ActionsView::new);

    onEdt(
        () -> {
          view.openSession(session(List.of(reader)));
          return null;
        });

    assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(closeOnEdt).isFalse();
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @Timeout(30)
  @DisplayName("Tests closes both readers when row-source initialization fails")
  void testsFailedOpenClosesBothReadersOffTheEventThread() throws Exception {
    CountDownLatch closed = new CountDownLatch(2);
    AtomicBoolean closeOnEdt = new AtomicBoolean(false);
    EntityReader pages =
        reader(closed, closeOnEdt, "testIndex", new IllegalStateException("index failed"));
    EntityReader details = reader(closed, closeOnEdt, "", null);
    TestsView view = onEdt(TestsView::new);

    onEdt(
        () -> {
          view.openSession(session(List.of(pages, details)));
          return null;
        });

    assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(closeOnEdt).isFalse();
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  private static EntityReader reader(
      CountDownLatch closed,
      AtomicBoolean closeOnEdt,
      String failingMethod,
      RuntimeException failure) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) -> {
              if (method.getName().equals(failingMethod)) {
                throw failure;
              }
              return switch (method.getName()) {
                case "close" -> {
                  closeOnEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
                  closed.countDown();
                  yield null;
                }
                case "cancelRunningQuery" -> null;
                case "toString" -> "LifecycleEntityReader";
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  private static SessionSource session(List<EntityReader> readers) {
    AtomicInteger next = new AtomicInteger();
    return (SessionSource)
        Proxy.newProxyInstance(
            SessionSource.class.getClassLoader(),
            new Class<?>[] {SessionSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "openEntityReader" -> readers.get(next.getAndIncrement());
                  case "close" -> null;
                  case "toString" -> "LifecycleSession";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static <T> T onEdt(Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(task.call());
          } catch (Throwable problem) {
            failure.set(problem);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError("EDT task failed", failure.get());
    }
    return result.get();
  }
}

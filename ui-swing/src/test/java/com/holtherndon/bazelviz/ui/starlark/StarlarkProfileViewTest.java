package com.holtherndon.bazelviz.ui.starlark;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Headless behavior and threading contract for the Starlark profile page. */
final class StarlarkProfileViewTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void pageSeparatesViewsAndExplainsCpuSemantics() throws Exception {
    FakeStarlarkProfileReader reader = new FakeStarlarkProfileReader();
    StarlarkProfileView view = onEdt(StarlarkProfileView::new);

    onEdt(
        () -> {
          view.openSession(source(reader));
          return null;
        });
    waitUntil(() -> onEdt(() -> view.tabsForTest().isEnabledAt(1)));

    assertThat(onEdt(() -> view.tabsForTest().getTabCount())).isEqualTo(5);
    assertThat(
            onEdt(
                () ->
                    IntStream.range(0, 5)
                        .mapToObj(index -> view.tabsForTest().getTitleAt(index))
                        .toList()))
        .containsExactly("Summary", "Hot Functions", "Files", "Call Graph", "Flame");
    assertThat(onEdt(() -> view.summaryDetailForTest().getText()))
        .contains("sampled CPU")
        .contains("not an exact trace")
        .contains("can exceed profile duration")
        .contains("navigation hints")
        .contains("Captured by this invocation")
        .doesNotContain("profile was imported");
    JScrollPane summaryScroll = onEdt(view::summaryScrollForTest);
    assertThat(summaryScroll.getHorizontalScrollBarPolicy())
        .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    assertThat(summaryScroll.getVerticalScrollBar().getUnitIncrement()).isEqualTo(16);
    assertThat((Scrollable) summaryScroll.getViewport().getView())
        .satisfies(
            content -> {
              assertThat(content.getScrollableTracksViewportWidth()).isTrue();
              assertThat(content.getScrollableTracksViewportHeight()).isFalse();
            });
    assertThat(onEdt(view::summaryCardCountForTest)).isEqualTo(10);
    assertThat(onEdt(view::summaryExplanationVisibleForTest)).isTrue();
    assertThat(reader.queriedOnEdt).isFalse();

    close(view, reader);
  }

  @Test
  void functionsFilesEdgesAndFlameLoadOnlyThroughTheWorker() throws Exception {
    FakeStarlarkProfileReader reader = new FakeStarlarkProfileReader();
    StarlarkProfileView view = onEdt(StarlarkProfileView::new);
    AtomicReference<StarlarkProfileReader.SourceLocation> opened = new AtomicReference<>();
    onEdt(
        () -> {
          view.onOpenSource(opened::set);
          view.openSession(source(reader));
          return null;
        });
    waitUntil(() -> onEdt(() -> view.functionTableForTest().getRowCount()) == 2);

    assertThat(onEdt(() -> view.functionTableForTest().getValueAt(0, 0))).isEqualTo("…");
    waitUntil(() -> !onEdt(() -> view.functionTableForTest().getValueAt(0, 0)).equals("…"));
    assertThat(onEdt(() -> view.functionTableForTest().getValueAt(0, 0)))
        .isEqualTo("compile_rules");
    onEdt(
        () -> {
          view.functionTableForTest().setRowSelectionInterval(0, 0);
          view.openSelectedFunctionSourceForTest();
          return null;
        });
    assertThat(opened.get()).isNotNull();
    assertThat(opened.get().path()).isEqualTo("tools/compile.bzl");
    assertThat(onEdt(() -> view.callGraphSourceForTest().getText()))
        .isEqualTo("tools/compile.bzl:17");

    onEdt(
        () -> {
          view.tabsForTest().setSelectedIndex(2);
          return null;
        });
    waitUntil(
        () -> onEdt(() -> view.fileStatusForTest().getText()).contains("1 matching source files"));

    onEdt(
        () -> {
          view.tabsForTest().setSelectedIndex(3);
          return null;
        });
    waitUntil(
        () ->
            onEdt(() -> view.callGraphStatusForTest().getText())
                .contains("1 callers and 1 callees"));
    waitUntil(
        () ->
            onEdt(() -> view.directedCallGraphStatusForTest().getText())
                .contains("Showing all 2 functions"));
    assertThat(onEdt(() -> view.directedCallGraphStatusForTest().getText()))
        .contains("Box size is Self CPU")
        .contains("Drag a function to move it");
    assertThat(onEdt(() -> view.directedCallGraphForTest().layoutForTest().nodes().size()))
        .isEqualTo(2);

    onEdt(
        () -> {
          view.tabsForTest().setSelectedIndex(4);
          return null;
        });
    waitUntil(
        () ->
            onEdt(() -> view.flameStatusForTest().getText())
                .contains("Loaded 2 of 2 call contexts"));
    assertThat(onEdt(() -> view.flameGraphForTest().loadedNoticeForTest()))
        .contains("Showing all 2 call contexts");
    assertThat(reader.queriedOnEdt).isFalse();

    close(view, reader);
  }

  @Test
  void unavailableProfileKeepsAnalysisTabsDisabledWithoutInventingZeros() throws Exception {
    FakeStarlarkProfileReader reader = new FakeStarlarkProfileReader();
    reader.summary =
        new StarlarkProfileReader.Summary(
            StarlarkProfileReader.Availability.NOT_CAPTURED,
            "The capture did not request --starlark_cpu_profile.",
            StarlarkProfileReader.Correlation.UNKNOWN,
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            StarlarkProfileReader.AttributionCoverage.unavailable(),
            StarlarkProfileReader.AttributionCoverage.unavailable(),
            StarlarkProfileReader.AttributionCoverage.unavailable());
    StarlarkProfileView view = onEdt(StarlarkProfileView::new);

    onEdt(
        () -> {
          view.openSession(source(reader));
          return null;
        });
    waitUntil(() -> onEdt(view::summaryCardCountForTest) == 1);

    assertThat(
            onEdt(
                () ->
                    IntStream.range(1, 5)
                        .allMatch(index -> !view.tabsForTest().isEnabledAt(index))))
        .isTrue();
    assertThat(onEdt(view::summaryCardsTextForTest))
        .contains("did not request")
        .doesNotContain("0 µs");
    assertThat(onEdt(view::summaryCardCountForTest)).isEqualTo(1);
    assertThat(onEdt(view::summaryExplanationVisibleForTest)).isFalse();

    close(view, reader);
  }

  private static SessionSource source(StarlarkProfileReader reader) {
    return (SessionSource)
        Proxy.newProxyInstance(
            SessionSource.class.getClassLoader(),
            new Class<?>[] {SessionSource.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "openStarlarkProfileReader" -> reader;
                  case "close" -> null;
                  case "toString" -> "fake Starlark session";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == arguments[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static void close(StarlarkProfileView view, FakeStarlarkProfileReader reader)
      throws Exception {
    CompletionStage<Void> closing = onEdt(view::closeSessionAsync);
    closing.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertThat(reader.closed).isTrue();
    assertThat(reader.closedOnEdt).isFalse();
    assertThat(onEdt(view::emptyMessageForTest)).isEqualTo("No session is open.");
  }

  private static void waitUntil(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.call() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.call()).isTrue();
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable problem) {
            failure.set(problem);
          }
        });
    if (failure.get() instanceof Exception exception) {
      throw exception;
    }
    if (failure.get() instanceof Error error) {
      throw error;
    }
    return value.get();
  }
}

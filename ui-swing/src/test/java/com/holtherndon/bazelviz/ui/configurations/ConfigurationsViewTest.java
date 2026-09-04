package com.holtherndon.bazelviz.ui.configurations;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ConfigurationsViewTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("the no-session message owns the full Configurations pane")
  void emptyStateFillsThePane() throws Exception {
    ConfigurationsView view = onEdt(ConfigurationsView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Configurations"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.setSize(960, 600);
          layoutTree(view);

          assertThat(view.emptyTextForTest()).isEqualTo("No session is open.");
          assertThat(view.emptyStateForTest().isVisible()).isTrue();
          assertThat(view.emptyStateForTest().getLocation()).isEqualTo(new Point());
          assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());
          assertThat(toolbar.actionCount()).isZero();
          assertThat(toolbar.metadata()).isEqualTo("Inspect and compare build configurations");
          return null;
        });
  }

  @Test
  @DisplayName("the card is lazy, selectable, and compares two exact checksums off the EDT")
  void comparesSelectedConfigurations() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger edtReads = new AtomicInteger();
    ConfigurationQueries.Summary first = summary("aaa", "fastbuild", true, 2);
    ConfigurationQueries.Summary second = summary("bbb", "opt", true, 2);
    EntityReader reader = reader(reads, edtReads, List.of(first, second));
    ConfigurationsView view = onEdt(ConfigurationsView::new);

    onEdt(
        () -> {
          view.openSession(session(reader));
          return null;
        });
    assertThat(reads).hasValue(0);

    onEdt(
        () -> {
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.configurationTableForTest().getRowCount() == 2));
    await(() -> onEdt(() -> view.detailsForTest().contains("aaa")));
    assertThat(onEdt(() -> view.emptyStateForTest().isVisible())).isFalse();
    assertThat(onEdt(view::detailsForTest)).contains("Effective options: 2 values", "cannot prove");

    onEdt(
        () -> {
          view.baselineButtonForTest().doClick();
          view.configurationTableForTest().setRowSelectionInterval(1, 1);
          return null;
        });
    await(() -> onEdt(() -> view.comparisonForTest().contains("3 effective-option")));

    assertThat(onEdt(view::comparisonForTest)).contains("fastbuild → opt");
    assertThat(onEdt(() -> view.differenceTableForTest().getRowCount())).isEqualTo(3);
    assertThat(edtReads).hasValue(0);
    onEdt(
        () -> {
          view.closeSession();
          assertThat(view.emptyStateForTest().isVisible()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("missing option payload is unavailable, never an empty equal comparison")
  void oldCqueryIsExplicit() throws Exception {
    ConfigurationQueries.Summary first = summary("aaa", "fastbuild", false, 0);
    ConfigurationQueries.Summary second = summary("bbb", "fastbuild", false, 0);
    ConfigurationsView view = onEdt(ConfigurationsView::new);
    onEdt(
        () -> {
          view.openSession(
              session(reader(new AtomicInteger(), new AtomicInteger(), List.of(first, second))));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.detailsForTest().contains("did not publish")));
    onEdt(
        () -> {
          view.baselineButtonForTest().doClick();
          view.configurationTableForTest().setRowSelectionInterval(1, 1);
          return null;
        });
    await(() -> onEdt(() -> view.comparisonForTest().contains("comparison is unavailable")));

    assertThat(onEdt(view::comparisonForTest)).doesNotContain("No effective-option differences");
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("cross-view navigation selects the requested checksum without reading on the EDT")
  void revealsExactChecksum() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger edtReads = new AtomicInteger();
    ConfigurationsView view = onEdt(ConfigurationsView::new);
    onEdt(
        () -> {
          view.openSession(
              session(
                  reader(
                      reads,
                      edtReads,
                      List.of(
                          summary("aaa", "fastbuild", true, 2), summary("bbb", "opt", true, 2)))));
          view.revealChecksum("bbb");
          return null;
        });

    await(() -> onEdt(() -> view.detailsForTest().contains("bbb")));

    assertThat(onEdt(() -> view.configurationTableForTest().getSelectedRow())).isEqualTo(1);
    assertThat(edtReads).hasValue(0);
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  private static EntityReader reader(
      AtomicInteger reads, AtomicInteger edtReads, List<ConfigurationQueries.Summary> summaries) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("toString")) {
                reads.incrementAndGet();
                if (SwingUtilities.isEventDispatchThread()) {
                  edtReads.incrementAndGet();
                }
              }
              return switch (method.getName()) {
                case "configurationCount" -> (long) summaries.size();
                case "configurationSource" ->
                    Optional.of(
                        new ConfigurationQueries.Source(
                            "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                case "configurations" -> summaries;
                case "configurationPosition" -> {
                  String checksum = (String) args[0];
                  int index =
                      IntStream.range(0, summaries.size())
                          .filter(value -> summaries.get(value).checksum().equals(checksum))
                          .findFirst()
                          .orElse(-1);
                  yield index < 0 ? OptionalLong.empty() : OptionalLong.of(index);
                }
                case "configurationValueCount" -> 2L;
                case "configurationValues" ->
                    List.of(
                        new ConfigurationQueries.Value(
                            "Effective option",
                            "Core",
                            "compilation_mode",
                            Optional.of("fastbuild"),
                            false),
                        new ConfigurationQueries.Value(
                            "Effective option", "Core", "remote_header", Optional.empty(), true));
                case "configurationDifferenceCount" -> 3L;
                case "configurationDifferences" ->
                    List.of(
                        difference("compilation_mode", "fastbuild", "opt"),
                        difference("define", "A=1", null),
                        new ConfigurationQueries.Difference(
                            "Core",
                            "remote_header",
                            Optional.empty(),
                            false,
                            Optional.empty(),
                            true,
                            false,
                            true));
                case "close" -> null;
                case "toString" -> "ConfigurationsEntityReader";
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  private static SessionSource session(EntityReader reader) {
    return (SessionSource)
        Proxy.newProxyInstance(
            SessionSource.class.getClassLoader(),
            new Class<?>[] {SessionSource.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "openEntityReader" -> reader;
                  case "close" -> null;
                  case "toString" -> "ConfigurationsSession";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static ConfigurationQueries.Summary summary(
      String checksum, String mode, boolean available, long options) {
    return new ConfigurationQueries.Summary(
        checksum,
        Optional.of(mode),
        Optional.of("//platform:mac"),
        Optional.of("darwin_arm64"),
        Optional.of(false),
        true,
        true,
        true,
        available,
        1,
        2,
        3,
        available ? 4 : 0,
        available ? 1 : 0,
        options,
        0);
  }

  private static ConfigurationQueries.Difference difference(
      String name, String baseline, String candidate) {
    return new ConfigurationQueries.Difference(
        "Core",
        name,
        Optional.ofNullable(baseline),
        false,
        Optional.ofNullable(candidate),
        false,
        true,
        candidate != null);
  }

  private static void await(Checked condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.get()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }

  private static void layoutTree(Container container) {
    for (int pass = 0; pass < 4; pass++) {
      invalidateTree(container);
      layoutChildren(container);
    }
  }

  private static void invalidateTree(Container container) {
    container.invalidate();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        invalidateTree(nested);
      }
    }
  }

  private static void layoutChildren(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        layoutChildren(nested);
      }
    }
  }

  @FunctionalInterface
  private interface Checked {
    boolean get() throws Exception;
  }
}

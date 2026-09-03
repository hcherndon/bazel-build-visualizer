package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetQueries.ConfiguredTarget;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lazy fully-qualified target/configuration explorer. */
final class AllTargetsViewTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("the no-session message owns the full All Targets pane")
  void emptyStateFillsThePane() throws Exception {
    AllTargetsView view = onEdt(AllTargetsView::new);

    onEdt(
        () -> {
          view.setSize(960, 600);
          layoutTree(view);

          assertThat(view.emptyTextForTest()).isEqualTo("No session is open.");
          assertThat(view.emptyTextSelectableForTest()).isTrue();
          assertThat(view.emptyStateForTest().isVisible()).isTrue();
          assertThat(view.emptyStateForTest().getLocation()).isEqualTo(new Point());
          assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());
          assertThat(view.emptyStateForTest().getComponent(0))
              .isInstanceOfSatisfying(
                  JTextPane.class,
                  message ->
                      assertThat(message.getForeground())
                          .isEqualTo(UIManager.getColor("Label.disabledForeground")));
          return null;
        });
  }

  @Test
  @DisplayName("labels load lazily and duplicate labels expand to configuration hashes")
  void configurationsAreGroupedUnderOneLabel() throws Exception {
    AtomicInteger edtReads = new AtomicInteger();
    List<ConfiguredTarget> rows =
        List.of(row(1, "//app:server", "cfg-a"), row(2, "//app:server", "cfg-b"));
    EntityReader reader = entityReader(edtReads, rows);
    SessionSource source = session(reader);
    AllTargetsView view = onEdt(AllTargetsView::new);

    onEdt(
        () -> {
          view.openSession(source);
          assertThat(view.labelCountForTest()).isZero();
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 2));

    assertThat(onEdt(() -> view.labelTextForTest(0))).isEqualTo("//app:server  (2 configurations)");
    assertThat(onEdt(() -> view.labelTextForTest(1))).isEqualTo("//lib:util");
    assertThat(onEdt(() -> view.loadMoreEnabledForTest())).isFalse();

    onEdt(
        () -> {
          view.expandLabelForTest(0);
          return null;
        });
    await(() -> onEdt(() -> view.configurationTextsForTest(0).size() == 2));
    assertThat(onEdt(() -> view.configurationTextsForTest(0)))
        .containsExactly(
            "Configuration cfg-a  ·  java_library", "Configuration cfg-b  ·  java_library");
    assertThat(onEdt(() -> view.configurationRefsForTest(0, 0)))
        .contains(
            new EntityRef.TargetLabel("//app:server"),
            new EntityRef.ConfigurationChecksum("cfg-a"));
    assertThat(edtReads).hasValue(0);

    onEdt(
        () -> {
          view.closeSession();
          assertThat(view.emptyTextForTest()).isEqualTo("No session is open.");
          assertThat(view.emptyStateForTest().isVisible()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("typing filters the exact configured-label population")
  void liveFilterUsesTheExactMatchingPopulation() throws Exception {
    AtomicInteger edtReads = new AtomicInteger();
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(entityReader(edtReads, List.of())));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 2));

    onEdt(
        () -> {
          view.setFilterTextForTest("missing");
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.labelCountForTest() == 0
                        && view.statusForTest().contains("No target labels match")));

    onEdt(
        () -> {
          view.setFilterTextForTest("lib");
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.labelCountForTest() == 1
                        && view.labelTextForTest(0).equals("//lib:util")));

    assertThat(onEdt(view::statusForTest)).contains("1 of 1 matching target labels loaded");
    assertThat(edtReads).hasValue(0);
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("rapid filters skip pending database scans and install only the latest result")
  void rapidFiltersAreCoalesced() throws Exception {
    List<String> executedFilters = new CopyOnWriteArrayList<>();
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(coalescingReader(executedFilters, slowStarted, releaseSlow)));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 1));
    executedFilters.clear();

    onEdt(
        () -> {
          view.applyFilterTextForTest("slow");
          return null;
        });
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    onEdt(
        () -> {
          view.applyFilterTextForTest("middle");
          view.applyFilterTextForTest("latest");
          return null;
        });
    releaseSlow.countDown();

    await(
        () ->
            onEdt(
                () ->
                    view.labelCountForTest() == 1
                        && view.labelTextForTest(0).equals("//latest:target")));
    assertThat(executedFilters).containsExactly("slow", "latest");
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("duplicate unavailable checksums stay grouped as variants")
  void unavailableChecksumsDoNotPretendToBeConfigurations() {
    ConfiguredTarget first =
        new ConfiguredTarget(1, "//app:server", Optional.empty(), Optional.of("java_library"));
    ConfiguredTarget duplicate =
        new ConfiguredTarget(2, "//app:server", Optional.empty(), Optional.of("java_library"));

    assertThat(AllTargetsView.groupConfigurations(List.of(first, duplicate)))
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.hash()).isEqualTo("unavailable");
              assertThat(group.rows()).containsExactly(first, duplicate);
            });
  }

  @Test
  @DisplayName("a session without cquery explains why All Targets is unavailable")
  void missingCqueryIsExplicit() throws Exception {
    AtomicInteger edtReads = new AtomicInteger();
    EntityReader reader = entityReader(edtReads, List.of(), false);
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(reader));
          view.activate();
          return null;
        });

    await(() -> onEdt(() -> view.emptyTextForTest().contains("no cquery")));

    assertThat(onEdt(view::emptyTextForTest)).contains("Top Level Targets");
    assertThat(onEdt(view::emptyTextSelectableForTest)).isTrue();
    assertThat(edtReads).hasValue(0);
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("a failed cquery shows Bazel's recorded error instead of looking absent")
  void failedCqueryIsExplicit() throws Exception {
    EntityReader reader =
        (EntityReader)
            Proxy.newProxyInstance(
                EntityReader.class.getClassLoader(),
                new Class<?>[] {EntityReader.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "targetLabelCount" -> 0L;
                      case "configuredTargetSource" ->
                          Optional.of(
                              new TargetQueries.ConfiguredSource(
                                  "FAILED",
                                  "UNKNOWN",
                                  Optional.empty(),
                                  Optional.of("analysis failed on //bad")));
                      case "firstTargetLabels", "targetLabelsAfter" -> List.of();
                      case "close" -> null;
                      case "toString" -> "FailedCqueryEntityReader";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(reader));
          view.activate();
          return null;
        });

    await(() -> onEdt(() -> view.emptyTextForTest().contains("analysis failed on //bad")));
    assertThat(onEdt(view::emptyTextSelectableForTest)).isTrue();
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  private static EntityReader entityReader(
      AtomicInteger edtReads, List<ConfiguredTarget> serverRows) {
    return entityReader(edtReads, serverRows, true);
  }

  private static EntityReader entityReader(
      AtomicInteger edtReads, List<ConfiguredTarget> serverRows, boolean hasSource) {
    List<TargetQueries.LabelSummary> summaries =
        List.of(
            new TargetQueries.LabelSummary("//app:server", 2, 2),
            new TargetQueries.LabelSummary("//lib:util", 1, 1));
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) -> {
              if (SwingUtilities.isEventDispatchThread()) {
                edtReads.incrementAndGet();
              }
              return switch (method.getName()) {
                case "targetLabelCount" ->
                    summaries.stream().filter(summary -> matches(summary.label(), args)).count();
                case "configuredTargetSource" ->
                    hasSource
                        ? Optional.of(
                            new TargetQueries.ConfiguredSource(
                                "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()))
                        : Optional.empty();
                case "firstTargetLabels" ->
                    summaries.stream().filter(summary -> matches(summary.label(), args)).toList();
                case "targetLabelsAfter" -> List.of();
                case "configuredTargetsByLabel" ->
                    "//app:server".equals(args[0])
                        ? serverRows
                        : List.of(row(3, "//lib:util", "cfg-c"));
                case "close" -> null;
                case "toString" -> "AllTargetsEntityReader";
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }

  private static EntityReader coalescingReader(
      List<String> executedFilters, CountDownLatch slowStarted, CountDownLatch releaseSlow) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "targetLabelCount" -> {
                    String filter = (String) args[0];
                    executedFilters.add(filter);
                    if (filter.equals("slow")) {
                      slowStarted.countDown();
                      awaitRelease(releaseSlow);
                    }
                    yield 1L;
                  }
                  case "configuredTargetSource" ->
                      Optional.of(
                          new TargetQueries.ConfiguredSource(
                              "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                  case "firstTargetLabels" -> {
                    String filter = (String) args[0];
                    String name = filter.isEmpty() ? "initial" : filter;
                    yield List.of(new TargetQueries.LabelSummary("//" + name + ":target", 1, 1));
                  }
                  case "targetLabelsAfter" -> List.of();
                  case "configuredTargetsByLabel" -> List.of(row(1, (String) args[0], "cfg"));
                  case "close" -> null;
                  case "toString" -> "CoalescingAllTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("slow target read was never released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("slow target read was interrupted", interrupted);
    }
  }

  private static boolean matches(String label, Object[] arguments) {
    return arguments == null
        || arguments.length == 0
        || !(arguments[0] instanceof String filter)
        || filter.isEmpty()
        || label.contains(filter);
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
                  case "toString" -> "AllTargetsSession";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static ConfiguredTarget row(long id, String label, String configuration) {
    return new ConfiguredTarget(id, label, Optional.of(configuration), Optional.of("java_library"));
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

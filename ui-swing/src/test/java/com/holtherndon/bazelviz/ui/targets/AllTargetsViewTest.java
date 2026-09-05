package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.CountedPage;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    await(
        () ->
            onEdt(
                () ->
                    view.configurationTextsForTest(0)
                        .equals(
                            List.of(
                                "Configuration cfg-a  (1 variants)",
                                "Configuration cfg-b  (1 variants)"))));
    assertThat(onEdt(() -> view.configurationTextsForTest(0)))
        .containsExactly("Configuration cfg-a  (1 variants)", "Configuration cfg-b  (1 variants)");
    assertThat(onEdt(() -> view.configurationRefsForTest(0, 0)))
        .contains(
            new EntityRef.TargetLabel("//app:server"),
            new EntityRef.ConfigurationChecksum("cfg-a"));
    onEdt(
        () -> {
          view.expandConfigurationGroupForTest(0, 0);
          return null;
        });
    await(() -> onEdt(() -> view.variantTextsForTest(0, 0).getFirst().contains("java_library")));
    assertThat(onEdt(() -> view.variantTextsForTest(0, 0)))
        .containsExactly("cquery row 1  ·  java_library");
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
  @DisplayName("a new filter cancels every queued configuration expansion")
  void filterCancelsQueuedConfigurationExpansions() throws Exception {
    CountDownLatch firstNodeStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstNode = new CountDownLatch(1);
    AtomicInteger nodeReads = new AtomicInteger();
    EntityReader reader =
        (EntityReader)
            Proxy.newProxyInstance(
                EntityReader.class.getClassLoader(),
                new Class<?>[] {EntityReader.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "labelPage" -> {
                        String filter = (String) args[0];
                        yield completePage(
                            filter.isEmpty()
                                ? List.of(
                                    new TargetQueries.LabelSummary("//a:target", 1, 1),
                                    new TargetQueries.LabelSummary("//b:target", 1, 1))
                                : List.of(new TargetQueries.LabelSummary("//latest:target", 1, 1)));
                      }
                      case "configuredTargetSource" ->
                          Optional.of(
                              new TargetQueries.ConfiguredSource(
                                  "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                      case "configurationGroupPage" -> {
                        if (nodeReads.incrementAndGet() == 1) {
                          firstNodeStarted.countDown();
                          awaitRelease(releaseFirstNode);
                        }
                        yield completePage(
                            List.of(new TargetQueries.ConfigurationGroup(Optional.of("cfg"), 1)));
                      }
                      case "cancelRunningQuery", "close" -> null;
                      case "toString" -> "QueuedConfigurationExpansionReader";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(reader));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 2));

    onEdt(
        () -> {
          view.expandLabelForTest(0);
          return null;
        });
    assertThat(firstNodeStarted.await(10, TimeUnit.SECONDS)).isTrue();
    onEdt(
        () -> {
          view.expandLabelForTest(1);
          view.applyFilterTextForTest("latest");
          return null;
        });
    releaseFirstNode.countDown();
    await(
        () ->
            onEdt(
                () ->
                    view.labelCountForTest() == 1
                        && view.labelTextForTest(0).equals("//latest:target")));
    onEdt(
        () -> {
          view.expandLabelForTest(0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.configurationTextsForTest(0)
                        .equals(List.of("Configuration cfg  (1 variants)"))));

    assertThat(nodeReads).hasValue(2);
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("reopening the same source rejects the first open callback")
  void sameSourceReopenRejectsStaleOpen() throws Exception {
    CountDownLatch firstOpenStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstOpen = new CountDownLatch(1);
    CountDownLatch staleReaderClosed = new CountDownLatch(1);
    AtomicBoolean staleCloseOnEdt = new AtomicBoolean(true);
    EntityReader oldReader = singleLabelReader("//old:target", staleReaderClosed, staleCloseOnEdt);
    EntityReader newReader =
        singleLabelReader("//new:target", new CountDownLatch(0), new AtomicBoolean());
    AtomicInteger opens = new AtomicInteger();
    SessionSource sameSource =
        (SessionSource)
            Proxy.newProxyInstance(
                SessionSource.class.getClassLoader(),
                new Class<?>[] {SessionSource.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "openEntityReader" -> {
                        int call = opens.incrementAndGet();
                        if (call == 1) {
                          firstOpenStarted.countDown();
                          awaitIgnoringInterrupt(releaseFirstOpen);
                        }
                        yield call == 1 || call >= 4 ? oldReader : newReader;
                      }
                      case "close" -> null;
                      case "toString" -> "SameAllTargetsSession";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(sameSource);
          view.activate();
          return null;
        });
    assertThat(firstOpenStarted.await(10, TimeUnit.SECONDS)).isTrue();
    onEdt(
        () -> {
          view.openSession(sameSource);
          view.activate();
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.labelCountForTest() == 1
                        && view.labelTextForTest(0).equals("//new:target")));

    releaseFirstOpen.countDown();
    assertThat(staleReaderClosed.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(staleCloseOnEdt)
        .as("closing the rejected JDBC reader must stay off Swing's event thread")
        .isFalse();
    onEdt(
        () -> {
          assertThat(view.labelCountForTest()).isEqualTo(1);
          assertThat(view.labelTextForTest(0)).isEqualTo("//new:target");
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("duplicate unavailable checksums stay grouped as variants")
  void unavailableChecksumsDoNotPretendToBeConfigurations() throws Exception {
    ConfiguredTarget first =
        new ConfiguredTarget(1, "//app:server", Optional.empty(), Optional.of("java_library"));
    ConfiguredTarget duplicate =
        new ConfiguredTarget(2, "//app:server", Optional.empty(), Optional.of("java_library"));

    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(entityReader(new AtomicInteger(), List.of(first, duplicate))));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 2));
    onEdt(
        () -> {
          view.expandLabelForTest(0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.configurationTextsForTest(0)
                        .equals(List.of("Configuration unavailable  (2 variants)"))));
    onEdt(
        () -> {
          view.expandConfigurationGroupForTest(0, 0);
          return null;
        });
    await(() -> onEdt(() -> view.variantTextsForTest(0, 0).size() == 2));
    assertThat(onEdt(() -> view.variantTextsForTest(0, 0)))
        .containsExactly("cquery row 1  ·  java_library", "cquery row 2  ·  java_library");
    onEdt(
        () -> {
          view.closeSession();
          return null;
        });
  }

  @Test
  @DisplayName("configuration groups and variants remain reachable past one bounded page")
  void configurationAndVariantPagesExposeLoadNextNodes() throws Exception {
    List<TargetQueries.ConfigurationGroup> groups = new ArrayList<>();
    List<ConfiguredTarget> variants = new ArrayList<>();
    for (int index = 0; index <= AllTargetsView.CONFIGURATION_GROUP_PAGE_SIZE; index++) {
      groups.add(new TargetQueries.ConfigurationGroup(Optional.of("cfg" + index), 1));
    }
    for (int index = 0; index <= AllTargetsView.CONFIGURATION_VARIANT_PAGE_SIZE; index++) {
      variants.add(row(index + 1L, "//app:server", "cfg0"));
    }
    AllTargetsView view = onEdt(AllTargetsView::new);
    onEdt(
        () -> {
          view.openSession(session(pagedConfigurationReader(groups, variants)));
          view.activate();
          return null;
        });
    await(() -> onEdt(() -> view.labelCountForTest() == 1));
    onEdt(
        () -> {
          view.expandLabelForTest(0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.configurationTextsForTest(0).size()
                        == AllTargetsView.CONFIGURATION_GROUP_PAGE_SIZE + 1));
    assertThat(onEdt(() -> view.configurationTextsForTest(0).getLast()))
        .startsWith("Load next configurations");
    onEdt(
        () -> {
          view.loadNextConfigurationsForTest(0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.configurationTextsForTest(0).size()
                            == AllTargetsView.CONFIGURATION_GROUP_PAGE_SIZE + 1
                        && view.configurationTextsForTest(0).stream()
                            .noneMatch(text -> text.startsWith("Load next"))));

    onEdt(
        () -> {
          view.expandConfigurationGroupForTest(0, 0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.variantTextsForTest(0, 0).size()
                        == AllTargetsView.CONFIGURATION_VARIANT_PAGE_SIZE + 1));
    assertThat(onEdt(() -> view.variantTextsForTest(0, 0).getLast()))
        .startsWith("Load next variants");
    onEdt(
        () -> {
          view.loadNextVariantsForTest(0, 0);
          return null;
        });
    await(
        () ->
            onEdt(
                () ->
                    view.variantTextsForTest(0, 0).size()
                            == AllTargetsView.CONFIGURATION_VARIANT_PAGE_SIZE + 1
                        && view.variantTextsForTest(0, 0).stream()
                            .noneMatch(text -> text.startsWith("Load next"))));
    onEdt(
        () -> {
          view.closeSession();
          return null;
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
                      case "configuredTargetSource" ->
                          Optional.of(
                              new TargetQueries.ConfiguredSource(
                                  "FAILED",
                                  "UNKNOWN",
                                  Optional.empty(),
                                  Optional.of("analysis failed on //bad")));
                      case "labelPage" -> emptyPage();
                      case "cancelRunningQuery", "close" -> null;
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
              if (SwingUtilities.isEventDispatchThread()
                  && !method.getName().equals("cancelRunningQuery")
                  && !method.getName().equals("close")) {
                edtReads.incrementAndGet();
              }
              return switch (method.getName()) {
                case "configuredTargetSource" ->
                    hasSource
                        ? Optional.of(
                            new TargetQueries.ConfiguredSource(
                                "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()))
                        : Optional.empty();
                case "labelPage" -> {
                  String filter = (String) args[0];
                  List<TargetQueries.LabelSummary> matching =
                      summaries.stream()
                          .filter(summary -> filter.isEmpty() || summary.label().contains(filter))
                          .toList();
                  yield completePage(matching);
                }
                case "configurationGroupPage" ->
                    completePage(configurationGroups(rowsForLabel((String) args[0], serverRows)));
                case "configuredTargetPage" -> {
                  String label = (String) args[0];
                  @SuppressWarnings("unchecked")
                  Optional<String> configuration = (Optional<String>) args[1];
                  yield completePage(
                      rowsForLabel(label, serverRows).stream()
                          .filter(target -> target.configuration().equals(configuration))
                          .toList());
                }
                case "cancelRunningQuery", "close" -> null;
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
                  case "labelPage" -> {
                    String filter = (String) args[0];
                    executedFilters.add(filter);
                    if (filter.equals("slow")) {
                      slowStarted.countDown();
                      awaitRelease(releaseSlow);
                    }
                    String name = filter.isEmpty() ? "initial" : filter;
                    yield completePage(
                        List.of(new TargetQueries.LabelSummary("//" + name + ":target", 1, 1)));
                  }
                  case "configuredTargetSource" ->
                      Optional.of(
                          new TargetQueries.ConfiguredSource(
                              "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                  case "configurationGroupPage" ->
                      completePage(
                          List.of(new TargetQueries.ConfigurationGroup(Optional.of("cfg"), 1)));
                  case "configuredTargetPage" ->
                      completePage(List.of(row(1, (String) args[0], "cfg")));
                  case "cancelRunningQuery", "close" -> null;
                  case "toString" -> "CoalescingAllTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static EntityReader singleLabelReader(
      String label, CountDownLatch closed, AtomicBoolean closeOnEdt) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "configuredTargetSource" ->
                      Optional.of(
                          new TargetQueries.ConfiguredSource(
                              "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                  case "labelPage" ->
                      completePage(List.of(new TargetQueries.LabelSummary(label, 1, 1)));
                  case "cancelRunningQuery" -> null;
                  case "close" -> {
                    closeOnEdt.set(SwingUtilities.isEventDispatchThread());
                    closed.countDown();
                    yield null;
                  }
                  case "toString" -> "AllTargetsReader(" + label + ")";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static EntityReader pagedConfigurationReader(
      List<TargetQueries.ConfigurationGroup> groups, List<ConfiguredTarget> variants) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "configuredTargetSource" ->
                      Optional.of(
                          new TargetQueries.ConfiguredSource(
                              "SUCCEEDED", "EXACT", Optional.empty(), Optional.empty()));
                  case "labelPage" ->
                      completePage(
                          List.of(
                              new TargetQueries.LabelSummary(
                                  "//app:server", groups.size(), variants.size())));
                  case "configurationGroupPage" ->
                      configurationPage(groups, optionalArg(args[1]), (int) args[2]);
                  case "configuredTargetPage" ->
                      variantPage(variants, (OptionalLong) args[2], (int) args[3]);
                  case "cancelRunningQuery", "close" -> null;
                  case "toString" -> "PagedAllTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor>
      configurationPage(
          List<TargetQueries.ConfigurationGroup> rows,
          Optional<TargetQueries.ConfigurationAnchor> after,
          int limit) {
    int start =
        after
            .map(
                boundary -> {
                  for (int index = 0; index < rows.size(); index++) {
                    if (rows.get(index).configuration().equals(boundary.configuration())) {
                      return index + 1;
                    }
                  }
                  throw new AssertionError("unknown configuration anchor " + boundary);
                })
            .orElse(0);
    int end = Math.min(rows.size(), start + limit);
    List<TargetQueries.ConfigurationGroup> page = rows.subList(start, end);
    long remaining = rows.size() - end;
    Optional<TargetQueries.ConfigurationAnchor> next =
        remaining == 0
            ? Optional.empty()
            : Optional.of(TargetQueries.ConfigurationAnchor.of(page.getLast().configuration()));
    return new CountedPage<>(page, rows.size(), remaining, next);
  }

  private static CountedPage<ConfiguredTarget, Long> variantPage(
      List<ConfiguredTarget> rows, OptionalLong after, int limit) {
    int start = 0;
    if (after.isPresent()) {
      while (start < rows.size() && rows.get(start).id() <= after.getAsLong()) {
        start++;
      }
    }
    int end = Math.min(rows.size(), start + limit);
    List<ConfiguredTarget> page = rows.subList(start, end);
    long remaining = rows.size() - end;
    Optional<Long> next = remaining == 0 ? Optional.empty() : Optional.of(page.getLast().id());
    return new CountedPage<>(page, rows.size(), remaining, next);
  }

  @SuppressWarnings("unchecked")
  private static <T> Optional<T> optionalArg(Object argument) {
    return (Optional<T>) argument;
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

  private static void awaitIgnoringInterrupt(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("stale open was never released");
        }
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static List<ConfiguredTarget> rowsForLabel(
      String label, List<ConfiguredTarget> serverRows) {
    return "//app:server".equals(label) ? serverRows : List.of(row(3, "//lib:util", "cfg-c"));
  }

  private static List<TargetQueries.ConfigurationGroup> configurationGroups(
      List<ConfiguredTarget> rows) {
    List<TargetQueries.ConfigurationGroup> groups = new ArrayList<>();
    for (ConfiguredTarget row : rows) {
      int index = -1;
      for (int candidate = 0; candidate < groups.size(); candidate++) {
        if (groups.get(candidate).configuration().equals(row.configuration())) {
          index = candidate;
          break;
        }
      }
      if (index < 0) {
        groups.add(new TargetQueries.ConfigurationGroup(row.configuration(), 1));
      } else {
        TargetQueries.ConfigurationGroup current = groups.get(index);
        groups.set(
            index,
            new TargetQueries.ConfigurationGroup(current.configuration(), current.variants() + 1));
      }
    }
    groups.sort(
        Comparator.comparing(
            TargetQueries.ConfigurationGroup::configuration,
            Comparator.comparing(
                optional -> optional.orElse(null), Comparator.nullsFirst(String::compareTo))));
    return List.copyOf(groups);
  }

  private static <T> CountedPage<T, String> completePage(List<T> rows) {
    return new CountedPage<>(rows, rows.size(), 0, Optional.empty());
  }

  private static <T, A> CountedPage<T, A> emptyPage() {
    return new CountedPage<>(List.of(), 0, 0, Optional.empty());
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

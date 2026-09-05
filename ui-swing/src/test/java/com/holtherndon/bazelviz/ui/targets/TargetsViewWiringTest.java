package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.core.domain.TargetOutcome;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Targets card's header toolbar: the shared navigation vocabulary offered for the selected
 * target, and honest about itself when it cannot be used.
 *
 * <p>The toolbar exists instead of a row menu because it can say <em>why</em> a jump is
 * unavailable, so most of what is worth testing here is the disabled states: nothing selected, no
 * source event on the row, and a command with no read path behind it. A button that is merely dark
 * is not the point; a button that is dark and names its reason is.
 */
class TargetsViewWiringTest {

  private static final int EVENT_COUNT = 300;

  /** Exactly what {@code MainWindow.wiredCommands()} stands behind today. */
  private static final Set<EntityActions.Command> AS_WIRED_IN_THE_WINDOW =
      EnumSet.of(
          EntityActions.Command.OPEN_TARGET,
          EntityActions.Command.VIEW_CONFIGURATION,
          EntityActions.Command.OPEN_BUILD_FILE,
          EntityActions.Command.OPEN_IN_TREE,
          EntityActions.Command.OPEN_IN_GRAPH,
          EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
          EntityActions.Command.REVEAL_ACTION,
          EntityActions.Command.SHOW_ON_TIMELINE,
          EntityActions.Command.SHOW_SOURCE_EVENT);

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  /** Records dispatched pairs. */
  private static final class Recorder implements EntityActions.Handler {
    final List<EntityActions.Command> commands = new ArrayList<>();
    final List<EntityRef> refs = new ArrayList<>();

    @Override
    public void navigate(EntityActions.Command command, EntityRef ref) {
      commands.add(command);
      refs.add(ref);
    }
  }

  @Test
  @DisplayName("with nothing selected every button is off and says why, not silently dead")
  void nothingSelectedDisablesWithReasons() throws Exception {
    Recorder recorder = new Recorder();
    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          TargetsView view = new TargetsView();
          view.installEntityActions(new EntityActions(AS_WIRED_IN_THE_WINDOW, recorder));
          held.set(view);
        });
    TargetsView view = held.get();

    // The card the toolbar is on never offers "open target": the target
    // is already open, here.
    assertThat(onEdt(() -> view.toolbarCommandsForTest()))
        .containsExactly(
            EntityActions.Command.OPEN_IN_TREE,
            EntityActions.Command.OPEN_IN_GRAPH,
            EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
            EntityActions.Command.SHOW_EVENTS_FOR_LABEL,
            EntityActions.Command.SHOW_SOURCE_EVENT)
        .doesNotContain(EntityActions.Command.OPEN_TARGET);

    for (EntityActions.Command command : onEdt(() -> view.toolbarCommandsForTest())) {
      JButton button = onEdt(() -> view.toolbarButtonForTest(command));
      assertThat(onEdt(() -> button.isEnabled())).as("%s with no selection", command).isFalse();
      assertThat(onEdt(() -> button.getToolTipText()))
          .as("%s must say why it is off", command)
          .isNotBlank();
    }
    assertThat(
            onEdt(
                () ->
                    view.toolbarButtonForTest(EntityActions.Command.OPEN_IN_TREE).getToolTipText()))
        .contains("Select a target");

    // A button nobody can press dispatches nothing.
    SwingUtilities.invokeAndWait(
        () -> view.toolbarButtonForTest(EntityActions.Command.OPEN_IN_TREE).doClick());
    assertThat(recorder.commands).isEmpty();
  }

  @Test
  @DisplayName("a command with no read path behind it is off and names the missing path")
  void unwiredCommandsExplainThemselves() throws Exception {
    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          TargetsView view = new TargetsView();
          view.installEntityActions(new EntityActions(AS_WIRED_IN_THE_WINDOW, new Recorder()));
          held.set(view);
        });
    TargetsView view = held.get();

    // SHOW_EVENTS_FOR_LABEL is in the vocabulary and deliberately not in
    // the window's wired set, because no query selects events by label.
    // The button is present, off, and says exactly that.
    String tooltip =
        onEdt(
            () ->
                view.toolbarButtonForTest(EntityActions.Command.SHOW_EVENTS_FOR_LABEL)
                    .getToolTipText());
    assertThat(tooltip).contains("events-by-label read path");
    assertThat(
            onEdt(
                () ->
                    view.toolbarButtonForTest(EntityActions.Command.SHOW_EVENTS_FOR_LABEL)
                        .isEnabled()))
        .isFalse();
  }

  @Test
  @DisplayName("a view with no facility installed disables everything rather than pretending")
  void withoutTheFacilityNothingIsOffered() throws Exception {
    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> held.set(new TargetsView()));
    TargetsView view = held.get();

    JButton button =
        onEdt(() -> view.toolbarButtonForTest(EntityActions.Command.SHOW_ACTIONS_FOR_LABEL));
    assertThat(onEdt(() -> button.isEnabled())).isFalse();
    assertThat(onEdt(() -> button.getToolTipText())).contains("not wired");
  }

  @Test
  @Timeout(180)
  @DisplayName("selecting a target arms its jumps, and each dispatches that target's ref")
  void selectionArmsTheToolbarAndDispatches(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    TargetRow chosen = firstTargetWithASourceEvent(opened);

    Recorder recorder = new Recorder();
    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          TargetsView view = new TargetsView();
          view.installEntityActions(new EntityActions(AS_WIRED_IN_THE_WINDOW, recorder));
          view.openSession(opened);
          held.set(view);
        });
    TargetsView view = held.get();
    await(() -> onEdt(() -> view.packageCountForTest() > 0));

    SwingUtilities.invokeAndWait(() -> view.revealLabel(chosen.label()));
    await(() -> onEdt(() -> chosen.label().equals(view.selectedLabelForTest())));

    assertThat(onEdt(view::selectedTargetRefsForTest))
        .contains(new EntityRef.ConfigurationChecksum(chosen.configurationId().orElseThrow()));

    // Armed: the three label jumps and the source event, which this row
    // has. Still off: the one command with no read path behind it.
    for (EntityActions.Command command :
        List.of(
            EntityActions.Command.OPEN_IN_TREE,
            EntityActions.Command.OPEN_IN_GRAPH,
            EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
            EntityActions.Command.SHOW_SOURCE_EVENT)) {
      assertThat(onEdt(() -> view.toolbarButtonForTest(command).isEnabled()))
          .as("%s with a target selected", command)
          .isTrue();
    }
    assertThat(
            onEdt(
                () ->
                    view.toolbarButtonForTest(EntityActions.Command.SHOW_EVENTS_FOR_LABEL)
                        .isEnabled()))
        .isFalse();

    // Each button dispatches its own command with the selected target's
    // identity — the label for the label jumps, the source event id for
    // the event jump, never one standing in for the other.
    for (EntityActions.Command command :
        List.of(
            EntityActions.Command.OPEN_IN_TREE,
            EntityActions.Command.OPEN_IN_GRAPH,
            EntityActions.Command.SHOW_ACTIONS_FOR_LABEL)) {
      SwingUtilities.invokeAndWait(() -> view.toolbarButtonForTest(command).doClick());
    }
    SwingUtilities.invokeAndWait(
        () -> view.toolbarButtonForTest(EntityActions.Command.SHOW_SOURCE_EVENT).doClick());

    assertThat(recorder.commands)
        .containsExactly(
            EntityActions.Command.OPEN_IN_TREE,
            EntityActions.Command.OPEN_IN_GRAPH,
            EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
            EntityActions.Command.SHOW_SOURCE_EVENT);
    assertThat(recorder.refs)
        .containsExactly(
            new EntityRef.TargetLabel(chosen.label()),
            new EntityRef.TargetLabel(chosen.label()),
            new EntityRef.TargetLabel(chosen.label()),
            new EntityRef.EventId(chosen.bepEventId().orElseThrow()));

    // Closing the session takes the selection with it, and the toolbar
    // stops offering jumps for a target that is no longer on screen.
    SwingUtilities.invokeAndWait(view::closeSession);
    assertThat(
            onEdt(() -> view.toolbarButtonForTest(EntityActions.Command.OPEN_IN_TREE).isEnabled()))
        .isFalse();
    opened.close();
  }

  @Test
  @Timeout(180)
  @DisplayName("top-level package rows offer only their owning BUILD file")
  void packageBuildMenu(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    Recorder recorder = new Recorder();
    EntityActions actions = new EntityActions(AS_WIRED_IN_THE_WINDOW, recorder);
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.installEntityActions(actions);
              created.openSession(opened);
              return created;
            });
    await(() -> onEdt(() -> view.packageCountForTest() > 0));

    List<EntityRef> packageRefs = onEdt(() -> view.packageRefsForTest(0));
    Set<EntityActions.Command> omissions = onEdt(() -> view.packageOmissionsForTest(0));
    JPopupMenu packageMenu = onEdt(() -> actions.popupFor(packageRefs, omissions));
    assertThat(packageRefs).singleElement().isInstanceOf(EntityRef.TargetLabel.class);
    assertThat(
            Arrays.stream(packageMenu.getComponents())
                .filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast)
                .map(JMenuItem::getText))
        .containsExactly(EntityActions.Command.OPEN_BUILD_FILE.title());

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  @Test
  @Timeout(180)
  @DisplayName("Top Level Targets switches between packages and a flat label list")
  void packageAndFlatViews(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    TargetRow chosen = firstTargetWithASourceEvent(opened);
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(opened);
              return created;
            });
    await(() -> onEdt(() -> view.packageCountForTest() > 0));

    assertThat(onEdt(view::browseModesForTest)).containsExactly("Packages", "All Targets");
    SwingUtilities.invokeAndWait(view::showAllTargetsForTest);
    await(() -> onEdt(() -> view.flatLabelCountForTest() > 0));
    assertThat(onEdt(() -> view.flatLabelForTest(0))).startsWith("//");

    SwingUtilities.invokeAndWait(() -> view.setFilterTextForTest(chosen.label()));
    await(
        () ->
            onEdt(
                () ->
                    view.flatLabelCountForTest() == 1
                        && view.flatLabelForTest(0).equals(chosen.label())
                        && view.statusForTest().contains("matching top-level target labels")));
    assertThat(onEdt(view::statusForTest)).contains("1 of 1 matching top-level target labels");

    SwingUtilities.invokeAndWait(view::showPackagesForTest);
    await(() -> onEdt(() -> view.statusForTest().contains("matching packages loaded")));
    assertThat(onEdt(view::packageCountForTest)).isEqualTo(1);

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  @Test
  @Timeout(30)
  @DisplayName("rapid package filters skip pending reads and install only the latest result")
  void rapidPackageFiltersAreCoalesced() throws Exception {
    List<String> executedFilters = new CopyOnWriteArrayList<>();
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(
                  session(coalescingReader(executedFilters, slowStarted, releaseSlow)));
              return created;
            });
    await(() -> onEdt(() -> view.packageCountForTest() == 1));
    executedFilters.clear();

    SwingUtilities.invokeAndWait(() -> view.applyFilterTextForTest("slow"));
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    SwingUtilities.invokeAndWait(
        () -> {
          view.applyFilterTextForTest("middle");
          view.applyFilterTextForTest("latest");
        });
    releaseSlow.countDown();

    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == 1
                        && view.packageTextForTest(0).startsWith("//latest")));
    assertThat(executedFilters).containsExactly("slow", "latest");
    SwingUtilities.invokeAndWait(view::closeSession);
  }

  @Test
  @Timeout(30)
  @DisplayName("a new filter cancels every queued package expansion")
  void filterCancelsQueuedPackageExpansions() throws Exception {
    CountDownLatch firstChildStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstChild = new CountDownLatch(1);
    AtomicInteger childReads = new AtomicInteger();
    EntityReader reader =
        (EntityReader)
            Proxy.newProxyInstance(
                EntityReader.class.getClassLoader(),
                new Class<?>[] {EntityReader.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "packagePage" -> {
                        String filter = (String) args[0];
                        yield completePage(
                            filter.isEmpty()
                                ? List.of(
                                    new TargetQueries.PackageSummary("//a", 1, 0, 0),
                                    new TargetQueries.PackageSummary("//b", 1, 0, 0))
                                : List.of(new TargetQueries.PackageSummary("//latest", 1, 0, 0)));
                      }
                      case "targetsInPackagePage" -> {
                        if (childReads.incrementAndGet() == 1) {
                          firstChildStarted.countDown();
                          awaitRelease(releaseFirstChild);
                        }
                        yield new CountedPage<TargetRow, TargetQueries.TargetAnchor>(
                            List.of(), 0, 0, Optional.empty());
                      }
                      case "cancelRunningQuery", "close" -> null;
                      case "toString" -> "QueuedPackageExpansionReader";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(session(reader));
              return created;
            });
    await(() -> onEdt(() -> view.packageCountForTest() == 2));

    SwingUtilities.invokeAndWait(() -> view.expandPackageForTest(0));
    assertThat(firstChildStarted.await(10, TimeUnit.SECONDS)).isTrue();
    SwingUtilities.invokeAndWait(
        () -> {
          view.expandPackageForTest(1);
          view.applyFilterTextForTest("latest");
        });
    releaseFirstChild.countDown();

    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == 1
                        && view.packageTextForTest(0).startsWith("//latest")));
    assertThat(childReads).hasValue(1);
    SwingUtilities.invokeAndWait(view::closeSession);
  }

  @Test
  @Timeout(30)
  @DisplayName("rapid flat-label filters skip pending reads and install only the latest result")
  void rapidFlatFiltersAreCoalesced() throws Exception {
    List<String> executedFilters = new CopyOnWriteArrayList<>();
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(
                  session(flatCoalescingReader(executedFilters, slowStarted, releaseSlow)));
              return created;
            });
    await(() -> onEdt(() -> view.packageCountForTest() == 1));
    SwingUtilities.invokeAndWait(view::showAllTargetsForTest);
    await(() -> onEdt(() -> view.flatLabelCountForTest() == 1));
    executedFilters.clear();

    SwingUtilities.invokeAndWait(() -> view.applyFilterTextForTest("slow"));
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    SwingUtilities.invokeAndWait(
        () -> {
          view.applyFilterTextForTest("middle");
          view.applyFilterTextForTest("latest");
        });
    releaseSlow.countDown();

    await(
        () ->
            onEdt(
                () ->
                    view.flatLabelCountForTest() == 1
                        && view.flatLabelForTest(0).equals("//latest:target")));
    assertThat(executedFilters).containsExactly("slow", "latest");
    SwingUtilities.invokeAndWait(view::closeSession);
  }

  @Test
  @Timeout(30)
  @DisplayName("package and child boundaries expose inert Load next nodes")
  void packageAndChildPagesRemainReachable() throws Exception {
    List<TargetQueries.PackageSummary> packages = new ArrayList<>();
    List<TargetRow> targets = new ArrayList<>();
    for (int index = 0; index <= TargetsView.PACKAGE_PAGE_SIZE; index++) {
      packages.add(new TargetQueries.PackageSummary("//pkg" + index, 1, 0, 0));
    }
    for (int index = 0; index <= TargetsView.PACKAGE_TARGET_PAGE_SIZE; index++) {
      targets.add(target(index + 1L, "//pkg0:t" + index));
    }
    EntityReader reader = pagedTargetReader(packages, targets);
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(session(reader));
              return created;
            });

    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == TargetsView.PACKAGE_PAGE_SIZE
                        && view.packageLoadNextVisibleForTest()));
    assertThat(onEdt(view::statusForTest)).contains("200 of 201 packages loaded");
    SwingUtilities.invokeAndWait(view::loadNextPackagesForTest);
    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == TargetsView.PACKAGE_PAGE_SIZE + 1
                        && !view.packageLoadNextVisibleForTest()));

    SwingUtilities.invokeAndWait(() -> view.expandPackageForTest(0));
    await(
        () ->
            onEdt(
                () ->
                    view.targetCountForTest(0) == TargetsView.PACKAGE_TARGET_PAGE_SIZE
                        && view.targetLoadNextVisibleForTest(0)));
    SwingUtilities.invokeAndWait(() -> view.loadNextTargetsForTest(0));
    await(
        () ->
            onEdt(
                () ->
                    view.targetCountForTest(0) == TargetsView.PACKAGE_TARGET_PAGE_SIZE + 1
                        && !view.targetLoadNextVisibleForTest(0)));
    SwingUtilities.invokeAndWait(view::closeSession);
  }

  @Test
  @Timeout(30)
  @DisplayName("a direct reveal reconciles with its later package page")
  void directRevealReconcilesOrderingAndPageTallies() throws Exception {
    List<TargetQueries.PackageSummary> packages = new ArrayList<>();
    for (int index = 0; index < TargetsView.PACKAGE_PAGE_SIZE; index++) {
      packages.add(new TargetQueries.PackageSummary(String.format("//p%03d", index), 1, 0, 0));
    }
    packages.add(new TargetQueries.PackageSummary("//z", 7, 1, 2));
    TargetRow revealed = target(9_001, "//z:target");
    EntityReader reader =
        (EntityReader)
            Proxy.newProxyInstance(
                EntityReader.class.getClassLoader(),
                new Class<?>[] {EntityReader.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "packagePage" ->
                          packagePage(packages, optionalArg(args[1]), (int) args[2]);
                      case "targetsByLabelPage" ->
                          new CountedPage<>(List.of(revealed), 1, 0, Optional.empty());
                      case "targetsInPackagePage" ->
                          new CountedPage<>(List.of(revealed), 1, 0, Optional.empty());
                      case "tagPage" ->
                          new CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor>(
                              List.of(), 0, 0, Optional.empty());
                      case "outputGroupPage" ->
                          new CountedPage<TargetQueries.OutputGroup, Long>(
                              List.of(), 0, 0, Optional.empty());
                      case "cancelRunningQuery", "close" -> null;
                      case "toString" -> "DirectRevealTargetReader";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    TargetsView view =
        onEdt(
            () -> {
              TargetsView created = new TargetsView();
              created.openSession(session(reader));
              return created;
            });
    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == TargetsView.PACKAGE_PAGE_SIZE
                        && view.packageLoadNextVisibleForTest()));

    SwingUtilities.invokeAndWait(() -> view.revealLabel(revealed.label()));
    await(() -> onEdt(() -> revealed.label().equals(view.selectedLabelForTest())));
    SwingUtilities.invokeAndWait(view::loadNextPackagesForTest);
    await(
        () ->
            onEdt(
                () ->
                    view.packageCountForTest() == TargetsView.PACKAGE_PAGE_SIZE + 1
                        && !view.packageLoadNextVisibleForTest()));

    assertThat(onEdt(() -> view.packageTextForTest(TargetsView.PACKAGE_PAGE_SIZE)))
        .startsWith("//z")
        .doesNotContain("revealed directly");
    assertThat(onEdt(view::statusForTest)).contains("7 target rows in this page");
    SwingUtilities.invokeAndWait(view::closeSession);
  }

  /** A target row the session recorded a source event for. */
  private static TargetRow firstTargetWithASourceEvent(SqliteSessionSource opened) {
    try (EntityReader reader = opened.openEntityReader()) {
      CountedPage<TargetQueries.PackageSummary, String> packages =
          reader.packagePage("", Optional.empty(), TargetsView.PACKAGE_PAGE_SIZE);
      for (TargetQueries.PackageSummary summary : packages.rows()) {
        CountedPage<TargetRow, TargetQueries.TargetAnchor> targets =
            reader.targetsInPackagePage(
                summary.path(), "", Optional.empty(), TargetsView.PACKAGE_TARGET_PAGE_SIZE);
        for (TargetRow row : targets.rows()) {
          if (row.bepEventId().isPresent() && row.configurationId().isPresent()) {
            return row;
          }
        }
      }
    }
    throw new AssertionError("the fixture recorded no target with a source event");
  }

  private static SqliteSessionSource openImportedSession(Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    return SqliteSessionSource.open(sessions, imported.sessionRoot());
  }

  private static EntityReader coalescingReader(
      List<String> executedFilters, CountDownLatch slowStarted, CountDownLatch releaseSlow) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "packagePage" -> {
                    String filter = (String) args[0];
                    executedFilters.add(filter);
                    if (filter.equals("slow")) {
                      slowStarted.countDown();
                      awaitRelease(releaseSlow);
                    }
                    String name = filter.isEmpty() ? "initial" : filter;
                    yield completePage(
                        List.of(new TargetQueries.PackageSummary("//" + name, 1, 0, 0)));
                  }
                  case "cancelRunningQuery", "close" -> null;
                  case "toString" -> "CoalescingTopLevelTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static EntityReader flatCoalescingReader(
      List<String> executedFilters, CountDownLatch slowStarted, CountDownLatch releaseSlow) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "packagePage" ->
                      completePage(List.of(new TargetQueries.PackageSummary("//initial", 1, 0, 0)));
                  case "topLevelLabelPage" -> {
                    String filter = (String) args[0];
                    executedFilters.add(filter);
                    if (filter.equals("slow")) {
                      slowStarted.countDown();
                      awaitRelease(releaseSlow);
                    }
                    String name = filter.isEmpty() ? "initial" : filter;
                    yield completePage(List.of("//" + name + ":target"));
                  }
                  case "cancelRunningQuery", "close" -> null;
                  case "toString" -> "CoalescingFlatTopLevelTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static EntityReader pagedTargetReader(
      List<TargetQueries.PackageSummary> packages, List<TargetRow> targets) {
    return (EntityReader)
        Proxy.newProxyInstance(
            EntityReader.class.getClassLoader(),
            new Class<?>[] {EntityReader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "packagePage" -> packagePage(packages, optionalArg(args[1]), (int) args[2]);
                  case "targetsInPackagePage" ->
                      targetPage(targets, optionalArg(args[2]), (int) args[3]);
                  case "cancelRunningQuery", "close" -> null;
                  case "toString" -> "PagedTopLevelTargetsReader";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static CountedPage<TargetQueries.PackageSummary, String> packagePage(
      List<TargetQueries.PackageSummary> rows, Optional<String> after, int limit) {
    int start =
        after
            .map(
                boundary -> {
                  for (int index = 0; index < rows.size(); index++) {
                    if (rows.get(index).path().equals(boundary)) {
                      return index + 1;
                    }
                  }
                  throw new AssertionError("unknown package anchor " + boundary);
                })
            .orElse(0);
    int end = Math.min(rows.size(), start + limit);
    List<TargetQueries.PackageSummary> page = rows.subList(start, end);
    long remaining = rows.size() - end;
    Optional<String> next = remaining == 0 ? Optional.empty() : Optional.of(page.getLast().path());
    return new CountedPage<>(page, rows.size(), remaining, next);
  }

  private static CountedPage<TargetRow, TargetQueries.TargetAnchor> targetPage(
      List<TargetRow> rows, Optional<TargetQueries.TargetAnchor> after, int limit) {
    int start =
        after
            .map(
                boundary -> {
                  for (int index = 0; index < rows.size(); index++) {
                    if (rows.get(index).id() == boundary.targetId()) {
                      return index + 1;
                    }
                  }
                  throw new AssertionError("unknown target anchor " + boundary);
                })
            .orElse(0);
    int end = Math.min(rows.size(), start + limit);
    List<TargetRow> page = rows.subList(start, end);
    long remaining = rows.size() - end;
    Optional<TargetQueries.TargetAnchor> next =
        remaining == 0
            ? Optional.empty()
            : Optional.of(TargetQueries.TargetAnchor.of(page.getLast()));
    return new CountedPage<>(page, rows.size(), remaining, next);
  }

  private static TargetRow target(long id, String label) {
    return new TargetRow(
        id,
        label,
        Optional.empty(),
        Optional.of("java_library rule"),
        Optional.empty(),
        TargetOutcome.CONFIGURED,
        Optional.of("cfg"),
        Optional.of(TargetOutcome.BUILT),
        OptionalLong.of(id),
        OptionalLong.of(id));
  }

  @SuppressWarnings("unchecked")
  private static <T> Optional<T> optionalArg(Object argument) {
    return (Optional<T>) argument;
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
                  case "toString" -> "CoalescingTopLevelTargetsSession";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static <T> CountedPage<T, String> completePage(List<T> rows) {
    return new CountedPage<>(rows, rows.size(), 0, Optional.empty());
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("slow package read was never released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("slow package read was interrupted", interrupted);
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private static <T> T onEdt(Callable<T> read) {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              value.set(read.call());
            } catch (Exception e) {
              failure.set(e);
            }
          });
    } catch (Exception e) {
      throw new AssertionError("EDT read failed", e);
    }
    if (failure.get() != null) {
      throw new AssertionError("EDT read failed", failure.get());
    }
    return value.get();
  }

  private static boolean onEdt(BooleanSupplier read) {
    return onEdt((Callable<Boolean>) read::getAsBoolean);
  }
}

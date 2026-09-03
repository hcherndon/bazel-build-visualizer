package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
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

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  /** A target row the session recorded a source event for. */
  private static TargetRow firstTargetWithASourceEvent(SqliteSessionSource opened) {
    try (EntityReader reader = opened.openEntityReader()) {
      for (TargetQueries.PackageSummary summary : reader.packages()) {
        for (TargetRow row : reader.targetsInPackage(summary.path())) {
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

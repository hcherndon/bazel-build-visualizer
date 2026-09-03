package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.nav.NavEntry;
import com.holtherndon.bazelviz.ui.session.OpenRequest.Kind;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Graph/Tree split's wiring contract, checked without a window.
 *
 * <p>The suite runs headless, so {@code MainWindow} itself — a JFrame — cannot be constructed here.
 * What can be checked is the set of commands its {@code navigate} switch stands behind, which is
 * the half of the contract that is data rather than compilation: an unwired command is never
 * offered anywhere, so this set is exactly what users can reach.
 */
final class MainWindowNavWiringTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("open-path metadata is not read until the background executor runs")
  void openPathInspectionUsesBackgroundExecutor() throws Exception {
    Path missing = tempDir.resolve("missing.bep");
    ArrayDeque<Runnable> queuedReads = new ArrayDeque<>();
    AtomicReference<CompletionStage<MainWindow.OpenPathInspection>> scheduled =
        new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> scheduled.set(MainWindow.inspectOpenPathAsync(missing, queuedReads::add)));

    assertThat(scheduled.get().toCompletableFuture()).isNotDone();
    assertThat(queuedReads).hasSize(1);
    queuedReads.remove().run();
    MainWindow.OpenPathInspection inspection = scheduled.get().toCompletableFuture().join();
    assertThat(inspection.request().kind()).isEqualTo(Kind.UNSUPPORTED);
    assertThat(inspection.unsupportedDescription()).contains("not a file");
  }

  @Test
  @DisplayName("Open Recent does not touch SQLite until its background executor runs")
  void recentSessionsUseBackgroundExecutor() throws Exception {
    Path catalogDirectory = tempDir.resolve("catalog");
    ArrayDeque<Runnable> queuedReads = new ArrayDeque<>();
    AtomicReference<CompletionStage<?>> scheduled = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () ->
            scheduled.set(MainWindow.readRecentSessionsAsync(catalogDirectory, queuedReads::add)));

    assertThat(scheduled.get().toCompletableFuture()).isNotDone();
    assertThat(Files.exists(catalogDirectory)).isFalse();
    assertThat(queuedReads).hasSize(1);
    queuedReads.remove().run();
    assertThat(scheduled.get().toCompletableFuture().join()).isEqualTo(List.of());
    assertThat(Files.exists(catalogDirectory)).isTrue();
  }

  @Test
  @DisplayName("workspace connection completion includes its queued Swing transition")
  void workspaceConnectionCompletionIncludesEventThreadTransition() throws Exception {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    AtomicBoolean transitionRanOnEventThread = new AtomicBoolean();

    MainWindow.completeOnEventThread(
        completion, () -> transitionRanOnEventThread.set(SwingUtilities.isEventDispatchThread()));
    completion.get(5, TimeUnit.SECONDS);

    assertThat(transitionRanOnEventThread).isTrue();
  }

  @Test
  @DisplayName("window disposal waits for worker-owned background work off the EDT")
  void workerShutdownIsAsynchronousAndJoined() throws Exception {
    ExecutorService worker =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("test-window-worker").factory());
    ExecutorService waiter =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("test-window-worker-waiter").factory());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      worker.execute(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      CompletionStage<Void> closing =
          MainWindow.shutdownWorkerAsync(
              worker, waiter, Duration.ofSeconds(5), Duration.ofSeconds(1));

      assertThat(closing.toCompletableFuture()).isNotDone();
      release.countDown();
      closing.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertThat(worker.isTerminated()).isTrue();
    } finally {
      release.countDown();
      worker.shutdownNow();
      waiter.shutdownNow();
    }
  }

  @Test
  @DisplayName("worker shutdown reports a task that ignores the forced bounded reap")
  void workerShutdownHasABoundedForcedReap() throws Exception {
    ExecutorService worker =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("test-stubborn-window-worker").factory());
    ExecutorService waiter =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("test-stubborn-window-waiter").factory());
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean release = new AtomicBoolean();
    try {
      worker.execute(
          () -> {
            started.countDown();
            while (!release.get()) {
              LockSupport.parkNanos(1_000_000L);
              Thread.interrupted();
            }
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      CompletionStage<Void> closing =
          MainWindow.shutdownWorkerAsync(
              worker, waiter, Duration.ofMillis(25), Duration.ofMillis(25));

      Assertions.assertThatThrownBy(() -> closing.toCompletableFuture().get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("background work did not stop after bounded shutdown waits");
    } finally {
      release.set(true);
      worker.shutdownNow();
      waiter.shutdownNow();
    }
  }

  @Test
  @DisplayName("a session source closes only after its view cleanup stage settles")
  void sourceCloseIsOrderedAfterViewCleanup() {
    CompletableFuture<Void> views = new CompletableFuture<>();
    ArrayDeque<Runnable> sourceLane = new ArrayDeque<>();
    AtomicBoolean sourceClosed = new AtomicBoolean();

    CompletionStage<Void> closing =
        MainWindow.runAfterCompletion(views, () -> sourceClosed.set(true), sourceLane::add);

    assertThat(sourceLane).isEmpty();
    assertThat(sourceClosed).isFalse();
    assertThat(closing.toCompletableFuture()).isNotDone();

    views.complete(null);
    assertThat(sourceLane).hasSize(1);
    assertThat(sourceClosed).isFalse();
    sourceLane.remove().run();

    assertThat(sourceClosed).isTrue();
    assertThat(closing.toCompletableFuture()).isCompleted();
  }

  @Test
  @DisplayName("a failed view cleanup still releases its session source")
  void sourceCloseRunsAfterFailedViewCleanup() {
    CompletableFuture<Void> views = new CompletableFuture<>();
    ArrayDeque<Runnable> sourceLane = new ArrayDeque<>();
    AtomicBoolean sourceClosed = new AtomicBoolean();

    CompletionStage<Void> closing =
        MainWindow.runAfterCompletion(views, () -> sourceClosed.set(true), sourceLane::add);
    views.completeExceptionally(new IllegalStateException("reader did not stop"));
    sourceLane.remove().run();

    assertThat(sourceClosed).isTrue();
    assertThat(closing.toCompletableFuture()).isCompleted();
  }

  @Test
  @DisplayName("an old execution waits for editor, repository, terminal, and event-file users")
  void contextSwitchWaitsForEveryExecutionConsumer() {
    CompletableFuture<Void> editors = new CompletableFuture<>();
    CompletableFuture<Void> repository = new CompletableFuture<>();
    CompletableFuture<Void> terminal = new CompletableFuture<>();
    CompletableFuture<Void> eventFiles = new CompletableFuture<>();
    ArrayDeque<Runnable> transportLane = new ArrayDeque<>();
    AtomicBoolean transportClosed = new AtomicBoolean();

    CompletionStage<Void> consumers =
        MainWindow.executionContextReleased(editors, repository, terminal, eventFiles);
    CompletionStage<Void> closing =
        MainWindow.runAfterCompletion(
            consumers, () -> transportClosed.set(true), transportLane::add);

    editors.complete(null);
    repository.complete(null);
    terminal.complete(null);
    assertThat(transportLane).isEmpty();
    assertThat(closing.toCompletableFuture()).isNotDone();

    eventFiles.complete(null);
    assertThat(transportLane).hasSize(1);
    transportLane.remove().run();
    assertThat(transportClosed).isTrue();
    assertThat(closing.toCompletableFuture()).isCompleted();
  }

  @Test
  @DisplayName("Terminal is visible for both local and SSH workspaces")
  void executionNavigationReflectsConnectionState() {
    assertThat(MainWindow.visibleNavigation(false))
        .containsExactly(
            NavEntry.BUILD,
            NavEntry.REPOSITORY,
            NavEntry.OVERVIEW,
            NavEntry.TIMELINE,
            NavEntry.CRITICAL_PATH,
            NavEntry.STARLARK_PROFILE,
            NavEntry.ACTIONS,
            NavEntry.TARGETS,
            NavEntry.ALL_TARGETS,
            NavEntry.CONFIGURATIONS,
            NavEntry.GRAPH,
            NavEntry.TREE,
            NavEntry.TESTS,
            NavEntry.ERRORS,
            NavEntry.EVENTS,
            NavEntry.FINDINGS,
            NavEntry.QUERY);
    assertThat(MainWindow.visibleNavigation(true))
        .containsExactly(
            NavEntry.BUILD,
            NavEntry.TERMINAL,
            NavEntry.REPOSITORY,
            NavEntry.OVERVIEW,
            NavEntry.TIMELINE,
            NavEntry.CRITICAL_PATH,
            NavEntry.STARLARK_PROFILE,
            NavEntry.ACTIONS,
            NavEntry.TARGETS,
            NavEntry.ALL_TARGETS,
            NavEntry.CONFIGURATIONS,
            NavEntry.GRAPH,
            NavEntry.TREE,
            NavEntry.TESTS,
            NavEntry.ERRORS,
            NavEntry.EVENTS,
            NavEntry.FINDINGS,
            NavEntry.QUERY);
  }

  @Test
  @DisplayName("opening the same workspace reuses its execution connection")
  void workspaceConnectionIdentityIgnoresPresentationAndBazelChanges() {
    WorkspaceProfile first =
        WorkspaceProfile.ssh(
            "stable-id",
            "Old label",
            "builder",
            OptionalInt.of(2222),
            "/work/repo",
            "bazel",
            OptionalLong.of(1));
    WorkspaceProfile presentationOnly =
        WorkspaceProfile.ssh(
            "stable-id",
            "New label",
            "builder",
            OptionalInt.of(2222),
            "/work/repo",
            "bazelisk",
            OptionalLong.of(2));
    WorkspaceProfile otherRepository =
        WorkspaceProfile.ssh(
            "stable-id",
            "New label",
            "builder",
            OptionalInt.of(2222),
            "/work/other",
            "bazelisk",
            OptionalLong.of(2));

    assertThat(MainWindow.sameConnection(first, presentationOnly)).isTrue();
    assertThat(MainWindow.sameConnection(first, otherRepository)).isFalse();
    assertThat(MainWindow.sameConnection(null, first)).isFalse();
  }

  @Test
  void openWorkspaceMustAcceptAnEditBeforeTheManagerPersistsIt() {
    WorkspaceProfile profile =
        WorkspaceProfile.local("stable-id", "Edited", "/repo", "bazelisk", OptionalLong.empty());
    ArrayDeque<String> calls = new ArrayDeque<>();

    boolean rejected =
        MainWindow.applyWorkspaceUpdateBeforePersistence(
            profile,
            candidate -> {
              calls.add("apply");
              return false;
            },
            candidate -> {
              calls.add("persist");
              return true;
            });

    assertThat(rejected).isFalse();
    assertThat(calls).containsExactly("apply");

    calls.clear();
    boolean accepted =
        MainWindow.applyWorkspaceUpdateBeforePersistence(
            profile,
            candidate -> {
              calls.add("apply");
              return true;
            },
            candidate -> {
              calls.add("persist");
              return true;
            });
    assertThat(accepted).isTrue();
    assertThat(calls).containsExactly("apply", "persist");
  }

  @Test
  @DisplayName("discovered workspaces remain separate from saved workspace state")
  void workspaceChoicesPreserveTheirSource() {
    WorkspaceProfile saved =
        WorkspaceProfile.local("saved", "Saved", "/saved", "bazel", OptionalLong.of(20));
    WorkspaceProfile discovered =
        WorkspaceProfile.local("discovered", "Discovered", "/found", "bazel", OptionalLong.empty());
    WorkspaceProfile collidingDiscovery =
        WorkspaceProfile.local(
            "saved", "Discovery collision", "/other", "bazel", OptionalLong.empty());

    assertThat(
            MainWindow.availableWorkspaceProfiles(
                List.of(saved), List.of(discovered, collidingDiscovery)))
        .containsExactly(saved, discovered);
    assertThat(MainWindow.isDiscoveredWorkspace(List.of(saved), List.of(discovered), discovered))
        .isTrue();
    assertThat(
            MainWindow.isDiscoveredWorkspace(
                List.of(saved), List.of(collidingDiscovery), collidingDiscovery))
        .isFalse();
  }

  @Test
  @DisplayName("the Overview split names and frames both sections")
  void overviewSectionsAreDistinct() {
    JPanel summary = new JPanel();
    JPanel coverage = new JPanel();

    JSplitPane split = MainWindow.overviewSections(summary, coverage);

    assertThat(split.getOrientation()).isEqualTo(JSplitPane.VERTICAL_SPLIT);
    assertThat(split.getResizeWeight()).isEqualTo(0.62);
    assertThat(split.getBorder()).isNull();
    assertSection(split.getTopComponent(), "Build summary", summary);
    assertSection(split.getBottomComponent(), "Coverage & enrichment", coverage);
  }

  @Test
  @DisplayName("the Graph/Tree split wired both of its commands")
  void bothSplitCommandsAreWired() {
    Set<EntityActions.Command> wired = MainWindow.wiredCommands();

    // OPEN_IN_TREE waited, deliberately unwired, until today's Graph card
    // became the Tree card; OPEN_IN_GRAPH now targets the canvas card.
    // Both must be offered, or the split shipped half its point.
    assertThat(wired)
        .contains(
            EntityActions.Command.VIEW_CONFIGURATION,
            EntityActions.Command.OPEN_BUILD_FILE,
            EntityActions.Command.OPEN_IN_TREE,
            EntityActions.Command.OPEN_IN_GRAPH);
    // Still honestly absent: no events-by-label read path exists yet.
    assertThat(wired).doesNotContain(EntityActions.Command.SHOW_EVENTS_FOR_LABEL);
  }

  @Test
  @DisplayName("an action row is offered both destinations, tree and graph")
  void actionRowsAreOfferedBoth() {
    EntityActions actions = new EntityActions(MainWindow.wiredCommands(), (command, ref) -> {});

    List<EntityActions.Offer> offers =
        actions.offersFor(List.of(new EntityRef.ActionId(7)), Set.of());

    assertThat(offers)
        .extracting(EntityActions.Offer::command)
        .contains(EntityActions.Command.OPEN_IN_TREE, EntityActions.Command.OPEN_IN_GRAPH)
        .doesNotContain(EntityActions.Command.OPEN_BUILD_FILE);

    assertThat(
            actions.offersFor(
                List.of(new EntityRef.ActionId(7), new EntityRef.TargetLabel("//app:server")),
                Set.of()))
        .extracting(EntityActions.Offer::command)
        .contains(EntityActions.Command.OPEN_BUILD_FILE);
  }

  @Test
  @DisplayName("a target label reaches both destinations too, and travels as itself")
  void targetLabelsAreOfferedBoth() {
    EntityActions actions = new EntityActions(MainWindow.wiredCommands(), (command, ref) -> {});
    EntityRef.TargetLabel label = new EntityRef.TargetLabel("//app:server");

    List<EntityActions.Offer> offers = actions.offersFor(List.of(label), Set.of());

    // The Targets card's toolbar jumps into both cards for a target, so
    // both commands have to accept a label — and both arms of
    // MainWindow.navigate take one.
    assertThat(offers)
        .extracting(EntityActions.Offer::command)
        .contains(EntityActions.Command.OPEN_IN_TREE, EntityActions.Command.OPEN_IN_GRAPH);
    // The ref that travels is the label itself; the destination resolves
    // it with an exact lookup rather than the Find field's substring.
    assertThat(offers)
        .filteredOn(offer -> offer.command() == EntityActions.Command.OPEN_IN_GRAPH)
        .extracting(EntityActions.Offer::ref)
        .containsExactly(label);
  }

  private static void assertSection(Component candidate, String title, JPanel expectedBody) {
    assertThat(candidate).isInstanceOf(JPanel.class);
    JPanel section = (JPanel) candidate;
    assertThat(section.getLayout()).isInstanceOf(BorderLayout.class);
    assertThat(section.getBorder()).isInstanceOf(TitledBorder.class);
    assertThat(((TitledBorder) section.getBorder()).getTitle()).isEqualTo(title);
    assertThat(((BorderLayout) section.getLayout()).getLayoutComponent(BorderLayout.CENTER))
        .isSameAs(expectedBody);
  }
}

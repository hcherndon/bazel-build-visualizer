package com.holtherndon.bazelviz.app;

import com.holtherndon.bazelviz.ui.MainWindow;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseKey;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseOwner;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry;
import com.holtherndon.bazelviz.ui.logging.LogVerbosity;
import com.holtherndon.bazelviz.ui.logging.LoggingRuntime;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceStore;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceUiSettings;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowStateStore;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level owner of the workspace manager and native workspace windows.
 *
 * <p>The manager owns discovery and settings exactly once. Each entry in the registry owns one
 * connected workspace, terminal, repository browser, capture controller, and analysis session.
 * Closing one entry therefore cannot tear down another workspace's resources.
 */
final class ApplicationController implements MainWindow.ApplicationHost {

  private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

  private final Path sessionsRoot;
  private final Path catalogDirectory;
  private final Path settingsDirectory;
  private final LoggingRuntime loggingRuntime;
  private final MainWindow manager;
  private final WorkspaceWindowStateStore windowStateStore;
  private final ExecutorService windowStateIo =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("bbv-window-state", 0).factory());
  private final Timer windowStateTimer;
  private final ApplicationWindowRegistry<WorkspaceHandle> windows =
      new ApplicationWindowRegistry<>();
  private final CaptureLeaseRegistry captureLeases = new CaptureLeaseRegistry();
  private final SessionMutationCoordinator sessionMutations = new SessionMutationCoordinator();
  private final LinkedHashMap<String, WorkspaceWindowState.OpenWorkspace> pendingRestore =
      new LinkedHashMap<>();
  private final ArrayList<String> restoreOrder = new ArrayList<>();
  private final CompletableFuture<Void> quitCompletion = new CompletableFuture<>();
  private CompletableFuture<Void> windowStateSave = CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> settingsMaintenance = CompletableFuture.completedFuture(null);

  private boolean quitting;
  private boolean suppressWindowStateSave;
  private boolean restoredWorkspaceWindow;
  private boolean managerOwnsOpenRouting = true;

  ApplicationController(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime,
      WorkspaceStore workspaceStore,
      List<WorkspaceProfile> workspaces,
      WorkspaceWindowStateStore windowStateStore,
      WorkspaceWindowState restoredState) {
    requireEdt();
    this.sessionsRoot = Objects.requireNonNull(sessionsRoot, "sessionsRoot");
    this.catalogDirectory = Objects.requireNonNull(catalogDirectory, "catalogDirectory");
    this.settingsDirectory = Objects.requireNonNull(settingsDirectory, "settingsDirectory");
    this.loggingRuntime = Objects.requireNonNull(loggingRuntime, "loggingRuntime");
    this.windowStateStore = Objects.requireNonNull(windowStateStore, "windowStateStore");
    WorkspaceWindowState checkedRestoredState =
        Objects.requireNonNull(restoredState, "restoredState");
    windowStateTimer = new Timer(300, event -> persistWindowState());
    windowStateTimer.setRepeats(false);
    manager =
        new MainWindow(
            sessionsRoot,
            catalogDirectory,
            settingsDirectory,
            loggingRuntime,
            Objects.requireNonNull(workspaceStore, "workspaceStore"),
            Objects.requireNonNull(workspaces, "workspaces"),
            this);
    manager.setTitle("Bazel Build Visualizer — Workspaces");
    manager.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    manager.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowActivated(WindowEvent event) {
            managerOwnsOpenRouting = true;
          }

          @Override
          public void windowClosing(WindowEvent event) {
            if (!windows.hasOpenWindows()) {
              requestQuit();
            } else {
              managerOwnsOpenRouting = false;
              manager.setVisible(false);
            }
          }
        });
    restoreOrder.addAll(checkedRestoredState.orderedWorkspaceIds());
    restoreWindows(Objects.requireNonNull(workspaces, "workspaces"), checkedRestoredState);
    updatePendingRestoreNotice();
  }

  void showInitialWindows() {
    requireEdt();
    if (!restoredWorkspaceWindow) {
      showManager();
    }
  }

  private void showManager() {
    requireEdt();
    if (!quitting) {
      managerOwnsOpenRouting = true;
      manager.showWorkspaceManagerWindow();
    }
  }

  private void restoreWindows(
      List<WorkspaceProfile> savedWorkspaces, WorkspaceWindowState restoredState) {
    Map<String, WorkspaceProfile> byId =
        savedWorkspaces.stream()
            .collect(
                Collectors.toMap(
                    WorkspaceProfile::id, Function.identity(), (first, ignored) -> first));
    suppressWindowStateSave = true;
    try {
      for (WorkspaceWindowState.OpenWorkspace saved : restoredState.openWorkspaces()) {
        WorkspaceProfile profile = byId.get(saved.workspaceId());
        if (profile == null) {
          pendingRestore.put(saved.workspaceId(), saved);
          log.info("deferring workspace restore until discovery id={}", saved.workspaceId());
          continue;
        }
        windows.open(profile.id(), () -> createWorkspaceWindow(profile, false, saved));
        managerOwnsOpenRouting = false;
        restoredWorkspaceWindow = true;
      }
    } finally {
      suppressWindowStateSave = false;
    }
    if (restoredWorkspaceWindow) {
      manager.setVisible(false);
    }
  }

  Window activeParent() {
    if (managerOwnsOpenRouting) {
      return manager;
    }
    return windows.active().map(WorkspaceHandle::window).map(Window.class::cast).orElse(manager);
  }

  void openPath(Path path) {
    requireEdt();
    Objects.requireNonNull(path, "path");
    Optional<WorkspaceHandle> active = windows.active();
    if (WorkspaceWindowPolicy.desktopOpenTarget(managerOwnsOpenRouting, active.isPresent())
        == WorkspaceWindowPolicy.DesktopOpenTarget.MANAGER) {
      manager.setVisible(true);
      manager.openPath(path);
      manager.toFront();
      return;
    }
    WorkspaceHandle handle = active.orElseThrow();
    handle.showAndFocus();
    handle.window().openPath(path);
  }

  CompletionStage<Void> quit() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::quit);
      return quitCompletion;
    }
    if (quitting) {
      return quitCompletion;
    }
    quitting = true;
    windowStateTimer.stop();
    persistWindowState();
    suppressWindowStateSave = true;
    log.info("application shutdown started with {} workspace window(s)", windows.size());
    CompletableFuture<?>[] closes =
        windows.snapshot().stream()
            .map(handle -> handle.window().disposeAsync().toCompletableFuture())
            .toArray(CompletableFuture[]::new);
    windowStateSave
        .handle(
            (ignored, saveFailure) -> {
              if (saveFailure != null) {
                log.warn("workspace window state did not save during shutdown", saveFailure);
              }
              return null;
            })
        .thenCompose(ignored -> CompletableFuture.allOf(closes))
        .handle((ignored, failure) -> failure)
        .thenCompose(
            workspaceFailure ->
                manager
                    .disposeAsync()
                    .handle(
                        (ignored, managerFailure) -> {
                          if (workspaceFailure != null) {
                            throw new CompletionException(workspaceFailure);
                          }
                          if (managerFailure != null) {
                            throw new CompletionException(managerFailure);
                          }
                          return null;
                        }))
        .thenCompose(ignored -> settingsMaintenance)
        .whenComplete(
            (ignored, failure) -> {
              windowStateIo.execute(() -> finishApplicationShutdown(failure));
            });
    return quitCompletion;
  }

  /** Flushes the one process-global logging backend away from the EDT. */
  private void finishApplicationShutdown(Throwable failure) {
    try {
      if (failure == null) {
        log.info("application shutdown finished");
      } else {
        log.warn("application shutdown did not finish cleanly", failure);
      }
      if (loggingRuntime instanceof AutoCloseable closeable) {
        closeable.close();
      }
      if (failure == null) {
        quitCompletion.complete(null);
      } else {
        quitCompletion.completeExceptionally(failure);
      }
    } catch (Exception loggingFailure) {
      if (failure != null) {
        loggingFailure.addSuppressed(failure);
      }
      quitCompletion.completeExceptionally(loggingFailure);
    } finally {
      windowStateIo.shutdown();
    }
  }

  /**
   * Confirms user-owned edits and running builds before an interactive process quit.
   *
   * <p>No build is cancelled until every dirty-editor check and the one aggregate build
   * confirmation succeeds. Smoke mode and fatal shutdown may still call {@link #quit()} as the
   * non-interactive lifecycle primitive.
   */
  CompletionStage<Boolean> requestQuit() {
    if (!SwingUtilities.isEventDispatchThread()) {
      CompletableFuture<Boolean> result = new CompletableFuture<>();
      SwingUtilities.invokeLater(
          () ->
              requestQuit()
                  .whenComplete(
                      (approved, failure) -> {
                        if (failure == null) {
                          result.complete(approved);
                        } else {
                          result.completeExceptionally(failure);
                        }
                      }));
      return result;
    }
    if (quitting) {
      return quitCompletion.thenApply(ignored -> true);
    }
    List<MainWindow> openWindows =
        windows.snapshot().stream().map(WorkspaceHandle::window).toList();
    for (MainWindow window : openWindows) {
      if (!window.confirmApplicationCloseAllowed()) {
        return CompletableFuture.completedFuture(false);
      }
    }
    if (!manager.confirmApplicationCloseAllowed()) {
      return CompletableFuture.completedFuture(false);
    }
    long busy = openWindows.stream().filter(MainWindow::hasActiveCapture).count();
    if (busy > 0) {
      Object[] options = {"Cancel Builds and Quit", "Keep Application Open"};
      String message =
          busy == 1
              ? "A Bazel command is still running. Cancel it and quit?"
              : busy + " Bazel commands are still running. Cancel them and quit?";
      int choice =
          JOptionPane.showOptionDialog(
              activeParent(),
              message,
              "Quit Bazel Build Visualizer",
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.WARNING_MESSAGE,
              null,
              options,
              options[1]);
      if (choice != 0) {
        return CompletableFuture.completedFuture(false);
      }
      openWindows.stream()
          .filter(MainWindow::hasActiveCapture)
          .forEach(MainWindow::cancelCaptureForApplicationClose);
    }
    return quit().thenApply(ignored -> true);
  }

  private void scheduleWindowStateSave() {
    if (!quitting && !suppressWindowStateSave) {
      windowStateTimer.restart();
    }
  }

  private void persistWindowState() {
    requireEdt();
    WorkspaceWindowState snapshot;
    try {
      snapshot =
          WorkspaceWindowSnapshots.merge(
              windows.restorableSnapshot().stream().map(WorkspaceHandle::snapshot).toList(),
              pendingRestore,
              restoreOrder);
    } catch (IllegalArgumentException tooMany) {
      log.warn("workspace window state exceeds its restore bound", tooMany);
      return;
    }
    windowStateSave =
        windowStateSave
            .handle((ignored, priorFailure) -> null)
            .thenRunAsync(
                () -> {
                  WorkspaceWindowStateStore.SaveResult saved =
                      windowStateStore.saveWithDiagnostics(snapshot);
                  if (!saved.saved()) {
                    log.warn("workspace window state was not saved: {}", saved.diagnostics());
                  }
                },
                windowStateIo);
  }

  private static void applyRestoredBounds(
      MainWindow window, WorkspaceWindowState.OpenWorkspace restored) {
    restored
        .bounds()
        .ifPresent(
            saved -> {
              Rectangle desired =
                  new Rectangle(saved.x(), saved.y(), saved.width(), saved.height());
              Rectangle screen = bestScreenFor(desired);
              int width = Math.min(desired.width, screen.width);
              int height = Math.min(desired.height, screen.height);
              int x = Math.max(screen.x, Math.min(desired.x, screen.x + screen.width - width));
              int y = Math.max(screen.y, Math.min(desired.y, screen.y + screen.height - height));
              window.setBounds(x, y, width, height);
            });
    if (restored.maximized()) {
      window.setExtendedState(window.getExtendedState() | Frame.MAXIMIZED_BOTH);
    }
  }

  private static Rectangle bestScreenFor(Rectangle desired) {
    GraphicsConfiguration[] configurations =
        Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            .map(device -> device.getDefaultConfiguration())
            .toArray(GraphicsConfiguration[]::new);
    if (configurations.length == 0) {
      return new Rectangle(0, 0, Math.max(1, desired.width), Math.max(1, desired.height));
    }
    GraphicsConfiguration best = configurations[0];
    long bestArea = -1;
    for (GraphicsConfiguration configuration : configurations) {
      Rectangle intersection = desired.intersection(configuration.getBounds());
      long area = intersection.isEmpty() ? 0 : (long) intersection.width * intersection.height;
      if (area > bestArea) {
        best = configuration;
        bestArea = area;
      }
    }
    return best.getBounds();
  }

  @Override
  public void openWorkspace(WorkspaceProfile requested, boolean discovered) {
    requireEdt();
    if (quitting) {
      return;
    }
    if (windows.isClosing(requested.id())) {
      JOptionPane.showMessageDialog(
          activeParent(),
          "‘" + requested.label() + "’ is still closing. Try again when cleanup finishes.",
          "Workspace is closing",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    boolean alreadyOpen = windows.get(requested.id()).isPresent();
    boolean replacesPendingRestore = pendingRestore.containsKey(requested.id());
    if (!WorkspaceWindowPolicy.canOpenRestoreSlot(
        alreadyOpen, replacesPendingRestore, windows.size(), pendingRestore.size())) {
      JOptionPane.showMessageDialog(
          activeParent(),
          "At most "
              + WorkspaceWindowState.MAX_OPEN_WORKSPACES
              + " Workspace windows can be restored at once. Close one or forget "
              + "an unavailable previous window and try again.",
          "Workspace window limit reached",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    manager
        .recordWorkspaceOpened(requested, discovered)
        .ifPresent(
            profile -> {
              WorkspaceWindowState.OpenWorkspace restored = pendingRestore.remove(profile.id());
              ApplicationWindowRegistry.OpenResult<WorkspaceHandle> opened =
                  windows.open(
                      profile.id(), () -> createWorkspaceWindow(profile, discovered, restored));
              if (!opened.created()) {
                log.debug(
                    "focused existing workspace window id={} label={}",
                    profile.id(),
                    profile.label());
              } else {
                log.info(
                    "opened workspace window id={} kind={} label={}",
                    profile.id(),
                    profile.kind(),
                    profile.label());
              }
              managerOwnsOpenRouting = false;
              if (!restoreOrder.contains(profile.id())) {
                restoreOrder.add(profile.id());
              }
              manager.setVisible(false);
              updatePendingRestoreNotice();
              scheduleWindowStateSave();
            });
  }

  private WorkspaceHandle createWorkspaceWindow(
      WorkspaceProfile profile, boolean discovered, WorkspaceWindowState.OpenWorkspace restored) {
    MainWindow window =
        new MainWindow(
            sessionsRoot,
            catalogDirectory,
            settingsDirectory,
            loggingRuntime,
            profile,
            discovered,
            this);
    WorkspaceHandle handle = new WorkspaceHandle(profile.id(), discovered, window);
    if (restored != null) {
      window.clearCommandOnInitialLoad();
      applyRestoredBounds(window, restored);
      handle.rememberNormalBounds(window.getBounds());
    } else {
      handle.captureNormalBounds();
    }
    window.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentMoved(ComponentEvent event) {
            handle.captureNormalBounds();
            scheduleWindowStateSave();
          }

          @Override
          public void componentResized(ComponentEvent event) {
            handle.captureNormalBounds();
            scheduleWindowStateSave();
          }
        });
    window.addWindowStateListener(
        event -> {
          handle.captureNormalBounds();
          scheduleWindowStateSave();
        });
    window.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowActivated(WindowEvent event) {
            managerOwnsOpenRouting = false;
            windows.activated(handle);
          }

          @Override
          public void windowClosed(WindowEvent event) {
            if (windows.remove(profile.id(), handle)) {
              restoreOrder.remove(profile.id());
              log.info("closed workspace window id={}", profile.id());
              if (WorkspaceWindowPolicy.showManagerAfterCompletedClose(
                  quitting, windows.isEmpty())) {
                showManager();
              }
              scheduleWindowStateSave();
            }
          }
        });
    return handle;
  }

  @Override
  public void showWorkspaceManager() {
    showManager();
  }

  @Override
  public void showNewWorkspace() {
    requireEdt();
    if (!quitting) {
      manager.showNewWorkspaceWindow();
    }
  }

  @Override
  public void editWorkspace(WorkspaceProfile profile) {
    requireEdt();
    if (quitting
        || manager.applicationWorkspaceIsDiscovered(profile)
        || windows.isClosing(profile.id())) {
      return;
    }
    Optional<WorkspaceHandle> open = windows.get(profile.id());
    if (open.isPresent() && open.orElseThrow().window().hasActiveCapture()) {
      WorkspaceHandle handle = open.orElseThrow();
      handle.showAndFocus();
      JOptionPane.showMessageDialog(
          handle.window(),
          "Finish or cancel the running Bazel command before editing this Workspace.",
          "Build in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    manager.showEditWorkspaceWindow(profile);
  }

  @Override
  public boolean workspaceUpdated(WorkspaceProfile profile) {
    requireEdt();
    if (windows.isClosing(profile.id())) {
      return false;
    }
    Optional<WorkspaceHandle> open = windows.get(profile.id());
    boolean discovered =
        open.map(WorkspaceHandle::discovered)
            .orElseGet(() -> manager.applicationWorkspaceIsDiscovered(profile));
    if (open.isPresent()) {
      WorkspaceHandle handle = open.orElseThrow();
      if (!handle.window().updateManagedWorkspace(profile, discovered)) {
        return false;
      }
      handle.showAndFocus();
      manager.setVisible(false);
    }
    return manager.recordWorkspaceUpdated(profile, discovered);
  }

  @Override
  public boolean workspaceRemoved(WorkspaceProfile profile) {
    requireEdt();
    Optional<WorkspaceHandle> open = windows.get(profile.id());
    if (open.isPresent() && !open.orElseThrow().window().requestWorkspaceWindowCloseIfAllowed()) {
      return false;
    }
    return true;
  }

  @Override
  public void workspaceRemovalPersisted(WorkspaceProfile profile) {
    requireEdt();
    CompletionStage<Void> closed =
        windows
            .get(profile.id())
            .map(handle -> handle.window().disposeAsync())
            .orElseGet(() -> CompletableFuture.completedFuture(null));
    settingsMaintenance =
        settingsMaintenance
            .handle((ignored, priorFailure) -> null)
            .thenCompose(ignored -> closed.handle((closedIgnored, closeFailure) -> null))
            .thenRunAsync(
                () -> {
                  try {
                    WorkspaceUiSettings.deleteWorkspace(settingsDirectory, profile.id());
                  } catch (Exception failure) {
                    log.warn(
                        "could not remove settings for workspace id={}", profile.id(), failure);
                  }
                },
                windowStateIo);
  }

  @Override
  public List<WorkspaceProfile> availableWorkspaces() {
    return manager.applicationWorkspaces();
  }

  @Override
  public boolean isDiscoveredWorkspace(WorkspaceProfile profile) {
    return manager.applicationWorkspaceIsDiscovered(profile);
  }

  @Override
  public void workspaceChoicesChanged(boolean startupRestoreEligible) {
    requireEdt();
    if (quitting || pendingRestore.isEmpty()) {
      return;
    }
    if (!startupRestoreEligible) {
      updatePendingRestoreNotice();
      return;
    }
    Map<String, WorkspaceProfile> available =
        manager.applicationWorkspaces().stream()
            .collect(
                Collectors.toMap(
                    WorkspaceProfile::id, Function.identity(), (first, ignored) -> first));
    for (var entry : List.copyOf(pendingRestore.entrySet())) {
      WorkspaceProfile profile = available.get(entry.getKey());
      if (!WorkspaceWindowPolicy.canRestoreDiscoveredWindow(
          true, profile != null, windows.size())) {
        continue;
      }
      WorkspaceProfile resolvedProfile = profile;
      pendingRestore.remove(entry.getKey());
      boolean discovered = manager.applicationWorkspaceIsDiscovered(resolvedProfile);
      windows.open(
          resolvedProfile.id(),
          () -> createWorkspaceWindow(resolvedProfile, discovered, entry.getValue()));
      managerOwnsOpenRouting = false;
      restoredWorkspaceWindow = true;
      manager.setVisible(false);
    }
    updatePendingRestoreNotice();
    scheduleWindowStateSave();
  }

  @Override
  public void workspaceWindowClosing(String workspaceId) {
    requireEdt();
    windows
        .get(workspaceId)
        .ifPresent(
            handle -> {
              windows.beginClose(workspaceId, handle);
            });
  }

  private void updatePendingRestoreNotice() {
    manager.showUnavailableWorkspaceRestores(
        List.copyOf(pendingRestore.keySet()), this::forgetPendingRestores);
  }

  private void forgetPendingRestores() {
    requireEdt();
    if (pendingRestore.isEmpty()) {
      return;
    }
    restoreOrder.removeAll(pendingRestore.keySet());
    pendingRestore.clear();
    updatePendingRestoreNotice();
    persistWindowState();
  }

  @Override
  public void showPreferences() {
    requireEdt();
    manager.showPreferences();
  }

  @Override
  public boolean selectLogVerbosity(LogVerbosity verbosity) {
    requireEdt();
    return manager.selectApplicationLogVerbosity(verbosity);
  }

  @Override
  public void themeChanged() {
    requireEdt();
    for (WorkspaceHandle handle : windows.snapshot()) {
      handle.window().refreshTerminalTheme();
    }
  }

  @Override
  public CaptureLeaseRegistry.Acquisition tryAcquireCaptureLease(
      CaptureLeaseKey key, CaptureLeaseOwner owner) {
    return captureLeases.tryAcquire(key, owner);
  }

  @Override
  public SessionMutationCoordinator sessionMutationCoordinator() {
    return sessionMutations;
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("application windows must be managed on the EDT");
    }
  }

  private static final class WorkspaceHandle implements ApplicationWindowRegistry.Handle {
    private final String workspaceId;
    private final boolean discovered;
    private final MainWindow window;
    private Rectangle normalBounds;

    private WorkspaceHandle(String workspaceId, boolean discovered, MainWindow window) {
      this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
      this.discovered = discovered;
      this.window = Objects.requireNonNull(window, "window");
    }

    MainWindow window() {
      return window;
    }

    String workspaceId() {
      return workspaceId;
    }

    boolean discovered() {
      return discovered;
    }

    void captureNormalBounds() {
      if ((window.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
        Rectangle current = window.getBounds();
        if (current.width > 0 && current.height > 0) {
          normalBounds = new Rectangle(current);
        }
      }
    }

    void rememberNormalBounds(Rectangle bounds) {
      if (bounds.width > 0 && bounds.height > 0) {
        normalBounds = new Rectangle(bounds);
      }
    }

    WorkspaceWindowState.OpenWorkspace snapshot() {
      captureNormalBounds();
      Optional<WorkspaceWindowState.WindowBounds> bounds =
          Optional.ofNullable(normalBounds)
              .map(
                  value ->
                      new WorkspaceWindowState.WindowBounds(
                          value.x, value.y, value.width, value.height));
      boolean maximized = (window.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
      return new WorkspaceWindowState.OpenWorkspace(workspaceId, bounds, maximized);
    }

    @Override
    public void showAndFocus() {
      window.setVisible(true);
      int state = window.getExtendedState();
      if ((state & Frame.ICONIFIED) != 0) {
        window.setExtendedState(state & ~Frame.ICONIFIED);
      }
      window.toFront();
      window.requestFocus();
    }
  }
}

package com.holtherndon.bazelviz.ui;

import com.holtherndon.bazelviz.analysis.CriticalPath.Result;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportOutcome;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.capture.file.importer.UnsupportedSourceException;
import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.CaptureSummary;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.capture.live.RemoteExecution;
import com.holtherndon.bazelviz.core.redact.RedactionReport;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceDetector;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import com.holtherndon.bazelviz.storage.catalog.RetentionPolicy;
import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.export.TableExport;
import com.holtherndon.bazelviz.ui.actions.ActionsView;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseKey;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseOwner;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry;
import com.holtherndon.bazelviz.ui.capture.CapturePanel;
import com.holtherndon.bazelviz.ui.capture.CaptureStatusModel;
import com.holtherndon.bazelviz.ui.capture.ConsoleView;
import com.holtherndon.bazelviz.ui.capture.InstrumentationPlanDialog;
import com.holtherndon.bazelviz.ui.capture.LaunchController;
import com.holtherndon.bazelviz.ui.capture.LauncherPanel;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import com.holtherndon.bazelviz.ui.configurations.ConfigurationsView;
import com.holtherndon.bazelviz.ui.criticalpath.CriticalPathView;
import com.holtherndon.bazelviz.ui.enrich.CoverageView;
import com.holtherndon.bazelviz.ui.errors.ErrorsView;
import com.holtherndon.bazelviz.ui.events.EventsView;
import com.holtherndon.bazelviz.ui.export.ExportController;
import com.holtherndon.bazelviz.ui.files.FileEditorManager;
import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileAccess;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import com.holtherndon.bazelviz.ui.graph.GraphExplorerView;
import com.holtherndon.bazelviz.ui.graph.TreeView;
import com.holtherndon.bazelviz.ui.logging.LogVerbosity;
import com.holtherndon.bazelviz.ui.logging.LoggingMenu;
import com.holtherndon.bazelviz.ui.logging.LoggingPreferenceWriter;
import com.holtherndon.bazelviz.ui.logging.LoggingRuntime;
import com.holtherndon.bazelviz.ui.logging.LoggingSettingsStore;
import com.holtherndon.bazelviz.ui.metrics.FindingsView;
import com.holtherndon.bazelviz.ui.metrics.MetricsService;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.nav.NavEntry;
import com.holtherndon.bazelviz.ui.overview.OverviewPanel;
import com.holtherndon.bazelviz.ui.preferences.PreferencesPanel;
import com.holtherndon.bazelviz.ui.preferences.ThemePreferencesPanel;
import com.holtherndon.bazelviz.ui.preferences.WorkspaceDiscoveryPreferencesPanel;
import com.holtherndon.bazelviz.ui.query.QueryView;
import com.holtherndon.bazelviz.ui.repository.RepositoryBrowserView;
import com.holtherndon.bazelviz.ui.session.ArchiveImport;
import com.holtherndon.bazelviz.ui.session.CatalogAccess;
import com.holtherndon.bazelviz.ui.session.CatalogEntries;
import com.holtherndon.bazelviz.ui.session.ImportController;
import com.holtherndon.bazelviz.ui.session.ImportProgressModel;
import com.holtherndon.bazelviz.ui.session.OpenRequest;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.starlark.PprofWindow;
import com.holtherndon.bazelviz.ui.starlark.StarlarkProfileView;
import com.holtherndon.bazelviz.ui.targets.AllTargetsView;
import com.holtherndon.bazelviz.ui.targets.TargetsView;
import com.holtherndon.bazelviz.ui.terminal.TerminalView;
import com.holtherndon.bazelviz.ui.tests.TestsView;
import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.ThemePreferenceWriter;
import com.holtherndon.bazelviz.ui.theme.ThemeSettingsStore;
import com.holtherndon.bazelviz.ui.theme.Themes;
import com.holtherndon.bazelviz.ui.timeline.TimelineController;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscoveryScriptStore;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceSelectionPanel;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceStore;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceUiSettings;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application window shell (plan section 17.1). The frame regions — navigation sidebar,
 * card-switched center, status bar — were laid out as placeholders in Phase 0; each gains its
 * backing service in the phase noted on its card.
 *
 * <p>Phase 1 replaces the Events placeholder with a working card: open a BEP file, watch it import,
 * scroll the chronological event table, inspect the raw protobuf of the selected event, and reopen
 * an indexed session without re-importing it. The other placeholders are untouched.
 *
 * <p>Nothing in this class does file, database or parsing work on the EDT. The menu actions hand
 * paths to {@link ImportController} and to a worker executor; the table and inspector own their own
 * executors inside {@link EventsView}.
 */
public final class MainWindow extends JFrame {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

  /** Cooperative grace for import checkpoints and ordinary worker-lane completion. */
  public static final Duration WORKER_SHUTDOWN_GRACE = Duration.ofSeconds(10);

  /** Reap grace after interrupting worker work that ignored cooperative shutdown. */
  public static final Duration WORKER_FORCED_SHUTDOWN_GRACE = Duration.ofSeconds(2);

  /**
   * Process-level workspace/window operations supplied by the desktop application.
   *
   * <p>A {@code MainWindow} still owns one connected workspace and one analysis session. This host
   * owns the list of windows and the process-global workspace chooser and preferences lifecycle.
   * Compatibility constructors leave the host absent and retain the original single-window
   * behaviour.
   */
  public interface ApplicationHost {
    void openWorkspace(WorkspaceProfile profile, boolean discovered);

    void showWorkspaceManager();

    void showNewWorkspace();

    void editWorkspace(WorkspaceProfile profile);

    boolean workspaceUpdated(WorkspaceProfile profile);

    boolean workspaceRemoved(WorkspaceProfile profile);

    void workspaceRemovalPersisted(WorkspaceProfile profile);

    List<WorkspaceProfile> availableWorkspaces();

    boolean isDiscoveredWorkspace(WorkspaceProfile profile);

    void workspaceChoicesChanged(boolean startupRestoreEligible);

    void workspaceWindowClosing(String workspaceId);

    void showPreferences();

    boolean selectLogVerbosity(LogVerbosity verbosity);

    void themeChanged();

    CaptureLeaseRegistry.Acquisition tryAcquireCaptureLease(
        CaptureLeaseKey key, CaptureLeaseOwner owner);

    SessionMutationCoordinator sessionMutationCoordinator();
  }

  /**
   * Recorded into every session this window creates. Duplicated from the application module's
   * {@code AppInfo} because {@code :app} depends on this module and not the other way round, so the
   * version cannot be imported from there.
   */
  private static final String APP_VERSION = "0.1.0";

  // Unknown-is-not-zero: counts are unknown until a session exists, so the
  // status bar shows an em dash, never "0".
  private static final String UNKNOWN = EventValueFormat.UNKNOWN;

  /** One independent toolbar per page and per native window. */
  private final EnumMap<NavEntry, PageToolbar> pageToolbars = newPageToolbars();

  private final Path sessionsRoot;
  private final SessionManager sessions;
  private final EventsView eventsView = new EventsView();
  private final OverviewPanel overviewPanel = new OverviewPanel();
  private final ActionsView actionsView = new ActionsView();
  private final TargetsView targetsView = new TargetsView();
  private final AllTargetsView allTargetsView = new AllTargetsView();
  private final ConfigurationsView configurationsView = new ConfigurationsView();
  private final TestsView testsView = new TestsView();
  private final ErrorsView errorsView = new ErrorsView();
  private final CoverageView coverageView = new CoverageView();

  /**
   * The two halves of what used to be one Graph card: the trees browse dependencies one level at a
   * time, the canvas draws bounded pieces of the graph with selectable weights. Split so each is a
   * card something can navigate to — {@code OPEN_IN_TREE} lands on one, {@code OPEN_IN_GRAPH} on
   * the other.
   */
  private final TreeView treeView = new TreeView();

  private final GraphExplorerView graphExplorerView = new GraphExplorerView();
  private final CriticalPathView criticalPathView = new CriticalPathView();
  private final StarlarkProfileView starlarkProfileView = new StarlarkProfileView();
  private final FindingsView findingsView = new FindingsView();

  /**
   * The ad hoc SQL card.
   *
   * <p>Opened over the same {@link SessionSource} as every other view, but on a connection the
   * others do not share: {@code openQueryReader()} hands it one that is read-only in fact rather
   * than by convention, because it is the only view whose statements this codebase did not write.
   */
  private final QueryView queryView = new QueryView();

  private final RepositoryBrowserView repositoryBrowserView = new RepositoryBrowserView();
  private final TerminalView terminalView;
  private final WorkspaceSelectionPanel workspaceSelectionPanel;
  private final WorkspaceStore workspaceStore;
  private final WorkspaceDiscoveryScriptStore workspaceDiscoveryScriptStore;
  private final WorkspaceDiscovery workspaceDiscovery;
  private List<WorkspaceProfile> workspaceProfiles;
  private List<WorkspaceProfile> discoveredWorkspaceProfiles = List.of();
  private WorkspaceProfile activeWorkspace;
  private boolean activeWorkspaceDiscovered;
  private ExecutionFileSystem repositoryFileSystem;
  private CaptureLeaseKey captureLeaseKey;
  private final AtomicReference<CaptureLeaseRegistry.CaptureLease> activeCaptureLease =
      new AtomicReference<>();
  private RemoteExecution activeRemoteExecution;
  private long workspaceConnectionGeneration;
  private boolean workspaceContextTransition;
  private CompletableFuture<Void> workspaceSave = CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> workspaceDiscoveryOperations =
      CompletableFuture.completedFuture(null);
  private long workspaceDiscoveryGeneration;
  private JDialog preferencesDialog;
  private PreferencesPanel preferencesPanel;
  private WorkspaceDiscoveryPreferencesPanel discoveryPreferencesPanel;
  private final ApplicationHost applicationHost;
  private final boolean workspaceManagerWindow;
  private final TimelineController timeline = new TimelineController();

  /**
   * The shared cross-view navigation actions — the navigation half of the plan's {@code
   * SelectionService} (product-plan section 7). Views build refs for their rows; this is the one
   * place a command becomes a card switch — the Graph/Tree split re-pointed one switch arm in
   * {@link #navigate} and wired a second, exactly as planned, rather than hunting through the
   * views.
   *
   * <p>One command is deliberately absent from the wired set and therefore never offered anywhere:
   * {@code SHOW_EVENTS_FOR_LABEL}, until an events-by-label read path exists.
   */
  private final EntityActions entityActions = new EntityActions(wiredCommands(), this::navigate);

  /**
   * The commands {@link #navigate} genuinely answers.
   *
   * <p>A named method rather than an inline set so the wiring is checkable without constructing the
   * window — the test suite runs headless, and a {@code JFrame} cannot be built there. {@code
   * MainWindowNavWiringTest} pins this set; the switch in {@link #navigate} is exhaustive by
   * compilation.
   */
  static Set<EntityActions.Command> wiredCommands() {
    return EnumSet.of(
        EntityActions.Command.OPEN_TARGET,
        EntityActions.Command.VIEW_CONFIGURATION,
        EntityActions.Command.OPEN_BUILD_FILE,
        EntityActions.Command.OPEN_IN_TREE,
        EntityActions.Command.OPEN_IN_GRAPH,
        EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
        EntityActions.Command.REVEAL_ACTION,
        EntityActions.Command.SHOW_ON_TIMELINE,
        EntityActions.Command.SHOW_SOURCE_EVENT);
  }

  /**
   * The Overview card: the build's own summary above, what is known about it below.
   *
   * <p>Plan 17.1 fixes the left navigation at eleven entries and coverage is not one of them, so
   * Phase 4's data-coverage panel, phase overview and enrichment-task status share this card rather
   * than taking a twelfth. They belong together anyway: "this build ran 12,000 actions" and "4 of
   * them have attempt data, because the rest never spawned a subprocess" are halves of one answer.
   */
  private final JComponent overviewCard = buildOverviewCard();

  /**
   * The open session, owned here rather than by any one view.
   *
   * <p>Six views read it. If each closed the source when it was torn down, the first would take the
   * database out from under the other five, so the window opens it once, hands it round, and closes
   * it once.
   */
  private SessionSource currentSource;

  /**
   * A read-only view of the session a capture is writing right now.
   *
   * <p>Separate from {@link #currentSource}, which is the session the user opened. The overview
   * watches this one while the build runs — the Phase 3 "Live overview" deliverable — and it is
   * closed and replaced by the real source when the capture finishes.
   */
  private SessionSource liveSource;

  private final Path catalogDirectory;
  private final SessionMutationCoordinator sessionMutations;
  private final ExportController exports;
  private final JMenu recentMenu = new JMenu("Open Recent");

  /** Last completed catalog read; menu rendering itself never touches SQLite. */
  private List<CatalogEntry> recentCatalogEntries = List.of();

  private boolean recentCatalogLoaded;
  private boolean recentCatalogLoadInProgress;
  private String recentCatalogFailure;
  private long recentCatalogLoadGeneration;
  private MetricsService metricsService;

  /**
   * The derived chain's node indices, from the last metric collection.
   *
   * <p>Kept rather than recomputed: the collection weighted the graph with whichever duration
   * source covered this session, and a second computation with a different weighting would draw a
   * different chain from the one the findings describe.
   */
  private List<Integer> derivedCriticalPath = List.of();

  private final ExecutorService blockingIo;
  private final ScheduledExecutorService terminalScheduler;
  private final ExecutorService worker;
  private final ExecutorService captureWorker;
  private final ImportController importController;
  private final FileEditorManager fileEditors;
  private final ThemePreferenceWriter themePreferences;
  private final LoggingRuntime loggingRuntime;
  private final LoggingPreferenceWriter loggingPreferences;
  private CompletableFuture<Void> viewCloseOperations = CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> sourceCloseOperations = CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> remoteCloseOperations = CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> workspaceConnectionOperations =
      CompletableFuture.completedFuture(null);
  private CompletableFuture<Void> recentCatalogOperations = CompletableFuture.completedFuture(null);
  private final CompletableFuture<Void> disposalCompletion = new CompletableFuture<>();

  /** Serialises path classification without tying it to the long-running import lane. */
  private CompletableFuture<Void> openPathOperations = CompletableFuture.completedFuture(null);

  private volatile boolean disposalStarted;
  private boolean disposalFinished;

  private final JLabel sessionStatus = new JLabel("Session: none");
  private final JLabel workspaceStatus = new JLabel("Workspace: none");
  private final JLabel eventStatus = new JLabel("Events: " + UNKNOWN);
  private final JLabel actionStatus = new JLabel("Actions: " + UNKNOWN);
  private final JMenuItem cancelImportItem = new JMenuItem("Cancel Import");
  private final JMenuItem closeSessionItem = new JMenuItem("Close Session");
  private final DefaultListModel<NavEntry> navModel = new DefaultListModel<>();
  private final JList<NavEntry> nav = new JList<>(navModel);
  private final WindowNavigationKeys navigationKeys;

  private final LauncherPanel launcherPanel =
      new LauncherPanel(
          this::startLaunch, this::showWorkspaceHome, this::updateManagedBazelExecutable);
  private final CapturePanel capturePanel = new CapturePanel();
  private final ConsoleView consoleView = new ConsoleView();

  /**
   * The Console card: launch controls, capture status, then build output.
   *
   * <p>{@link NavEntry#BUILD} keeps its stable enum and card id, but the visible name is Console.
   * The launcher lives here instead of occupying frame-wide space above every view.
   *
   * <p>Declared after both halves: field initializers run in order, and {@link #buildBuildCard()}
   * reads them.
   */
  private final JComponent buildCard = buildBuildCard();

  private final LaunchController launchController;
  private CaptureStatusModel captureStatus = CaptureStatusModel.idle();

  private final CardLayout cardLayout = new CardLayout();
  private final JPanel cards = new JPanel(cardLayout);
  private final CardLayout rootCardLayout = new CardLayout();
  private final JPanel rootCards = new JPanel(rootCardLayout);

  private static final String ROOT_WORKSPACES = "workspaces";
  private static final String ROOT_SHELL = "shell";

  private Path lastChooserDirectory;

  /**
   * @param sessionsRoot directory imported sessions are created in; resolving it is pure path
   *     arithmetic, and nothing is created until an import actually runs
   */
  public MainWindow(Path sessionsRoot) {
    this(sessionsRoot, sessionsRoot.resolveSibling("catalog"));
  }

  /**
   * @param catalogDirectory where the session library's index lives; the application-support {@code
   *     catalog/} directory, which is outside any session because it is about all of them
   */
  public MainWindow(Path sessionsRoot, Path catalogDirectory) {
    this(sessionsRoot, catalogDirectory, sessionsRoot.resolveSibling("settings"));
  }

  /**
   * @param settingsDirectory exact application settings directory; unlike the compatibility
   *     constructors, this does not infer it from the managed-session path
   */
  public MainWindow(Path sessionsRoot, Path catalogDirectory, Path settingsDirectory) {
    this(sessionsRoot, catalogDirectory, settingsDirectory, LoggingRuntime.unavailable());
  }

  /**
   * Production constructor with the application-owned logging backend.
   *
   * <p>The runtime operations are in-memory and safe on the EDT. Preference writes use this
   * window's shared blocking-I/O executor.
   */
  public MainWindow(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime) {
    this(
        sessionsRoot,
        catalogDirectory,
        settingsDirectory,
        loggingRuntime,
        new WorkspaceStore(settingsDirectory),
        List.of());
  }

  /** Production constructor with workspaces loaded before Swing starts. */
  public MainWindow(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime,
      WorkspaceStore workspaceStore,
      List<WorkspaceProfile> workspaces) {
    this(
        sessionsRoot,
        catalogDirectory,
        settingsDirectory,
        loggingRuntime,
        workspaceStore,
        workspaces,
        null,
        true,
        null,
        false);
  }

  /**
   * Creates the process-global workspace manager/analysis window.
   *
   * <p>The supplied host opens workspaces in separate native windows. This manager remains the sole
   * owner of workspace discovery and preference persistence.
   */
  public MainWindow(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime,
      WorkspaceStore workspaceStore,
      List<WorkspaceProfile> workspaces,
      ApplicationHost applicationHost) {
    this(
        sessionsRoot,
        catalogDirectory,
        settingsDirectory,
        loggingRuntime,
        workspaceStore,
        workspaces,
        Objects.requireNonNull(applicationHost, "applicationHost"),
        true,
        null,
        false);
  }

  /** Creates one application-managed native window for a workspace. */
  public MainWindow(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime,
      WorkspaceProfile workspace,
      boolean discovered,
      ApplicationHost applicationHost) {
    this(
        sessionsRoot,
        catalogDirectory,
        settingsDirectory,
        loggingRuntime,
        null,
        List.of(),
        Objects.requireNonNull(applicationHost, "applicationHost"),
        false,
        Objects.requireNonNull(workspace, "workspace"),
        discovered);
  }

  private MainWindow(
      Path sessionsRoot,
      Path catalogDirectory,
      Path settingsDirectory,
      LoggingRuntime loggingRuntime,
      WorkspaceStore workspaceStore,
      List<WorkspaceProfile> workspaces,
      ApplicationHost applicationHost,
      boolean workspaceManagerWindow,
      WorkspaceProfile initialWorkspace,
      boolean initialWorkspaceDiscovered) {
    super("Bazel Build Visualizer");
    this.sessionsRoot = sessionsRoot;
    this.catalogDirectory = Objects.requireNonNull(catalogDirectory, "catalogDirectory");
    settingsDirectory = Objects.requireNonNull(settingsDirectory, "settingsDirectory");
    Path windowSettingsDirectory;
    Path discoveredLaunchSettingsDirectory;
    if (applicationHost == null) {
      windowSettingsDirectory = settingsDirectory;
      discoveredLaunchSettingsDirectory = null;
    } else if (workspaceManagerWindow) {
      windowSettingsDirectory = WorkspaceUiSettings.manager(settingsDirectory);
      discoveredLaunchSettingsDirectory = null;
    } else {
      String workspaceId = Objects.requireNonNull(initialWorkspace, "initialWorkspace").id();
      windowSettingsDirectory =
          WorkspaceUiSettings.forWorkspaceWindow(
                  settingsDirectory, workspaceId, initialWorkspaceDiscovered)
              .orElse(null);
      discoveredLaunchSettingsDirectory =
          initialWorkspaceDiscovered
              ? WorkspaceUiSettings.discoveredHistory(settingsDirectory, workspaceId)
              : null;
    }
    this.loggingRuntime = Objects.requireNonNull(loggingRuntime, "loggingRuntime");
    this.applicationHost = applicationHost;
    this.workspaceManagerWindow = workspaceManagerWindow;
    this.sessionMutations =
        applicationHost == null
            ? new SessionMutationCoordinator()
            : Objects.requireNonNull(
                applicationHost.sessionMutationCoordinator(),
                "applicationHost.sessionMutationCoordinator()");
    this.workspaceStore = workspaceStore;
    this.workspaceDiscoveryScriptStore =
        workspaceManagerWindow ? new WorkspaceDiscoveryScriptStore(settingsDirectory) : null;
    this.workspaceDiscovery =
        workspaceManagerWindow ? new WorkspaceDiscovery(workspaceDiscoveryScriptStore) : null;
    this.workspaceProfiles = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
    this.workspaceSelectionPanel = new WorkspaceSelectionPanel();
    this.sessions = new SessionManager(sessionsRoot, APP_VERSION);
    this.blockingIo =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bbv-io-", 0).factory());
    ScheduledThreadPoolExecutor terminalTimer =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("bbv-terminal-timer-", 0).factory());
    terminalTimer.setRemoveOnCancelPolicy(true);
    terminalTimer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    terminalTimer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    this.terminalScheduler = terminalTimer;
    this.terminalView = new TerminalView(blockingIo, terminalScheduler);
    if (workspaceManagerWindow) {
      configureWorkspaceSelection();
    }
    this.themePreferences =
        workspaceManagerWindow
            ? new ThemePreferenceWriter(
                new ThemeSettingsStore(settingsDirectory),
                blockingIo,
                this::showThemePersistenceFailure)
            : null;
    this.loggingPreferences =
        workspaceManagerWindow
            ? new LoggingPreferenceWriter(
                new LoggingSettingsStore(settingsDirectory),
                blockingIo,
                this::showLoggingPersistenceFailure)
            : null;
    this.worker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-import");
              thread.setDaemon(true);
              return thread;
            });
    this.importController =
        new ImportController(
            new BepImporter(sessions),
            worker,
            SwingUtilities::invokeLater,
            new ImportProgressModel());
    // A capture blocks its worker for the whole build, so it gets its own
    // thread rather than sharing the import worker: a running build must
    // not make "open a session" queue behind it.
    this.captureWorker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-capture");
              thread.setDaemon(true);
              return thread;
            });
    this.launchController =
        new LaunchController(
            captureWorker,
            SwingUtilities::invokeLater,
            new CaptureListener(),
            this::releaseCaptureLease);
    this.exports = new ExportController(worker, SwingUtilities::invokeLater);
    this.fileEditors = new FileEditorManager(this);
    // The Query card's saved queries and views use the caller-resolved
    // settings directory. Compatibility constructors still infer that
    // directory for older tests and embedders. The libraries create their
    // files lazily on their own I/O threads.
    if (windowSettingsDirectory != null) {
      launcherPanel.attachPersistence(windowSettingsDirectory);
    } else if (discoveredLaunchSettingsDirectory != null) {
      launcherPanel.attachHistoryPersistence(discoveredLaunchSettingsDirectory);
    }
    queryView.attachLibrary(settingsDirectory);
    // The entity tables' per-view column state (widths, visibility,
    // order, sort) lives in the same settings directory, one JSON file
    // per view, loaded and saved on the shared column-state I/O thread.
    if (windowSettingsDirectory != null) {
      actionsView.attachColumnState(windowSettingsDirectory);
      errorsView.attachColumnState(windowSettingsDirectory);
      eventsView.attachColumnState(windowSettingsDirectory);
      testsView.attachColumnState(windowSettingsDirectory);
      queryView.attachColumnState(windowSettingsDirectory);
    }

    boolean managedWorkspaceWindow = applicationHost != null && !workspaceManagerWindow;
    setDefaultCloseOperation(managedWorkspaceWindow ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
    if (managedWorkspaceWindow) {
      addWindowListener(
          new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
              requestWorkspaceWindowClose();
            }
          });
    }
    setMinimumSize(new Dimension(960, 640));
    setSize(1280, 840);
    setLocationByPlatform(true);

    installPageChrome();
    updatePageWorkspace();
    for (NavEntry entry : NavEntry.values()) {
      cards.add(cardFor(entry), entry.cardName());
    }

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildNavigation(), cards);
    split.setDividerLocation(180);
    split.setResizeWeight(0);

    JPanel shell = new JPanel(new BorderLayout());
    shell.add(split, BorderLayout.CENTER);
    shell.add(buildStatusBar(), BorderLayout.SOUTH);
    rootCards.add(workspaceSelectionPanel, ROOT_WORKSPACES);
    rootCards.add(shell, ROOT_SHELL);
    setContentPane(rootCards);
    if (workspaceManagerWindow) {
      refreshWorkspaceChoices();
      rootCardLayout.show(rootCards, ROOT_WORKSPACES);
    } else {
      rootCardLayout.show(rootCards, ROOT_SHELL);
    }
    setJMenuBar(buildMenuBar());

    eventsView.progressPanel().setCancelAction(importController::cancel);

    // The shared navigation actions, adopted where the copy-pasted
    // LongConsumer wiring used to be: the Actions tab's row menu,
    // inspector actions and bespoke buttons, and the Events tab's row
    // menu and inspector, all dispatch into this::navigate.
    actionsView.installEntityActions(entityActions);
    eventsView.installEntityActions(entityActions);
    // The Targets card keeps its explanatory header toolbar and also uses
    // the shared row menu for label actions such as Open Build File.
    targetsView.installEntityActions(entityActions);
    allTargetsView.installEntityActions(entityActions);
    testsView.installEntityActions(entityActions);
    errorsView.installEntityActions(entityActions);
    treeView.installEntityActions(entityActions);
    graphExplorerView.installEntityActions(entityActions);
    criticalPathView.installEntityActions(entityActions);
    starlarkProfileView.onOpenSource(this::openStarlarkSource);
    actionsView.onOpenFile(this::openFile);
    actionsView.onClearExternalRange(timeline::clearRange);
    testsView.onOpenFile(this::openFile);
    eventsView.onCopyFilePath(fileEditors::copyPath);
    eventsView.onOpenFile(fileEditors::openLocal);
    eventsView.onOpenExecutionFile(
        path -> {
          ExecutionFileSystem files = repositoryFileSystem;
          if (files != null && files.executionId().equals(path.executionId())) {
            fileEditors.open(files, path);
          }
        });
    eventsView.onRevealFile(fileEditors::reveal);
    repositoryBrowserView.onOpenFile(
        path -> {
          ExecutionFileSystem files = repositoryFileSystem;
          if (files != null) {
            fileEditors.open(files, path, true);
          }
        });
    // The timeline's inline inspector adopts the same vocabulary: a
    // clicked span's details offer the same jumps a table row does, and
    // they land in the same navigate() switch.
    timeline.installEntityActions(entityActions);
    graphExplorerView.onActionSelected(this::followGraphSelection);
    criticalPathView.onOpenGraph(this::openGraphOnDerivedPath);
    // A finding points at records; these are the two ways it does so.
    // Selecting the evidence opens the action; following a link opens the
    // view the rule named, filtered the way the rule filtered it.
    findingsView.onActionSelected(this::revealAction);
    findingsView.onNavigate(this::followFindingLink);
    // Plan 17.3: every card navigates. The overview names a destination and
    // the window decides what showing it means.
    overviewPanel.onNavigate(this::openFromOverview);
    timeline.onSelection(actionId -> actionsView.selectAction(actionId));
    // A range dragged out on the timeline narrows the actions table (plan
    // 14.5). Cleared the same way, so the two never disagree about what is
    // being shown.
    timeline.onRangeChanged(
        (from, to) -> {
          actionsView.filterToRange(from, to);
          if (from.isPresent()) {
            showCard(NavEntry.ACTIONS);
          }
        });
    // The status bar's counts come from the overview's own read, so the two
    // can never disagree about how many actions the session holds.
    overviewPanel.onSnapshot(
        snapshot -> actionStatus.setText("Actions: " + EventValueFormat.count(snapshot.actions())));
    targetsView.onShowSourceEvent(this::revealEvent);
    allTargetsView.onShowSourceEvent(this::revealEvent);
    testsView.onShowSourceEvent(this::revealEvent);
    errorsView.onShowSourceEvent(this::revealEvent);
    // The manifest's event count is absent for a session whose import never
    // finished; the database always knows, so the status bar takes the real
    // number from the view once it is open rather than keeping an em dash
    // for a count that is in fact available.
    eventsView.setRowCountListener(
        count -> eventStatus.setText("Events: " + EventValueFormat.count(count)));
    capturePanel.setStopAction(launchController::cancel);
    cancelImportItem.setEnabled(false);
    closeSessionItem.setEnabled(false);
    // Once per launch, on a worker. A sessions root that moved while the
    // application was closed is the case this exists for, and reconciling
    // it here means the first time the library is opened it is already
    // right rather than right after the second look.
    if (workspaceManagerWindow || applicationHost == null) {
      reconcileLibrary();
    }
    if (workspaceManagerWindow) {
      startWorkspaceDiscovery(false, null);
    }
    if (initialWorkspace != null) {
      openWorkspace(initialWorkspace, false, initialWorkspaceDiscovered);
      setTitle("Bazel Build Visualizer — " + initialWorkspace.label());
    }
    // Install the process-global dispatcher only after construction succeeds, so a constructor
    // failure cannot leave this window retained by KeyboardFocusManager.
    navigationKeys = WindowNavigationKeys.install(getRootPane(), nav);
  }

  /** The directory imported sessions are written into. */
  public Path sessionsRoot() {
    return sessionsRoot;
  }

  /** Records recency through the one process-global workspace manager. */
  public Optional<WorkspaceProfile> recordWorkspaceOpened(
      WorkspaceProfile requested, boolean discovered) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace records must change on the EDT");
    }
    if (!workspaceManagerWindow || workspaceStore == null) {
      throw new IllegalStateException("this window does not own workspace settings");
    }
    WorkspaceProfile profile =
        Objects.requireNonNull(requested, "requested")
            .openedAt(System.currentTimeMillis() * 1_000L);
    if (discovered) {
      discoveredWorkspaceProfiles =
          discoveredWorkspaceProfiles.stream()
              .map(candidate -> candidate.id().equals(profile.id()) ? profile : candidate)
              .toList();
      refreshWorkspaceChoices();
      return Optional.of(profile);
    }
    return upsertWorkspace(profile) ? Optional.of(profile) : Optional.empty();
  }

  /** Applies one edited profile to the manager's in-memory list and persistence path. */
  public boolean recordWorkspaceUpdated(WorkspaceProfile profile, boolean discovered) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace records must change on the EDT");
    }
    if (!workspaceManagerWindow || workspaceStore == null) {
      throw new IllegalStateException("this window does not own workspace settings");
    }
    WorkspaceProfile replacement = Objects.requireNonNull(profile, "profile");
    if (!discovered) {
      return upsertWorkspace(replacement);
    }
    ArrayList<WorkspaceProfile> updated = new ArrayList<>(discoveredWorkspaceProfiles.size());
    boolean found = false;
    for (WorkspaceProfile candidate : discoveredWorkspaceProfiles) {
      if (candidate.id().equals(replacement.id())) {
        updated.add(replacement);
        found = true;
      } else {
        updated.add(candidate);
      }
    }
    if (!found) {
      // A discovery rerun may remove an entry while its already-open window remains valid. Keep
      // that window ephemeral and let its per-ID launch sidecar persist the safe conveniences.
      return true;
    }
    discoveredWorkspaceProfiles = List.copyOf(updated);
    refreshWorkspaceChoices();
    return true;
  }

  /** Immutable current saved/discovered workspace menu, newest first. */
  public List<WorkspaceProfile> applicationWorkspaces() {
    return availableWorkspaceProfiles();
  }

  /** Whether the profile is ephemeral discovery output rather than saved configuration. */
  public boolean applicationWorkspaceIsDiscovered(WorkspaceProfile profile) {
    return isDiscoveredWorkspace(profile);
  }

  /** Shows the manager's recent-workspace card and brings its frame forward. */
  public void showWorkspaceManagerWindow() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::showWorkspaceManagerWindow);
      return;
    }
    refreshWorkspaceChoices();
    rootCardLayout.show(rootCards, ROOT_WORKSPACES);
    setVisible(true);
    setState(JFrame.NORMAL);
    toFront();
    requestFocus();
  }

  /** Shows the central manager directly in its new-workspace editor. */
  public void showNewWorkspaceWindow() {
    showWorkspaceManagerWindow();
    workspaceSelectionPanel.showNewWorkspaceForm();
  }

  /** Shows the central manager directly in the editor for one saved workspace. */
  public void showEditWorkspaceWindow(WorkspaceProfile profile) {
    showWorkspaceManagerWindow();
    workspaceSelectionPanel.showEditWorkspaceForm(profile);
  }

  /** Shows previously open ephemeral IDs that startup discovery did not resolve. */
  public void showUnavailableWorkspaceRestores(List<String> workspaceIds, Runnable forgetAction) {
    if (!workspaceManagerWindow) {
      throw new IllegalStateException("only the Workspace manager owns restore notices");
    }
    workspaceSelectionPanel.setUnavailableRestoreIds(workspaceIds, forgetAction);
  }

  /** Clears a restored command draft without removing launcher history. */
  public void clearCommandOnInitialLoad() {
    launcherPanel.clearCommandOnInitialLoad();
  }

  /** Applies one process-global logging choice through the manager-owned writer. */
  public boolean selectApplicationLogVerbosity(LogVerbosity verbosity) {
    return selectLogVerbosity(verbosity);
  }

  /** Refreshes terminal-style renderers after a process-global look-and-feel change. */
  public void refreshTerminalTheme() {
    try {
      terminalView.refreshTheme();
    } catch (RuntimeException failure) {
      log.warn("terminal theme refresh failed", failure);
    }
    try {
      consoleView.refreshTheme();
    } catch (RuntimeException failure) {
      log.warn("console theme refresh failed", failure);
    }
  }

  /** Applies an edited open profile only when its current resources can transition safely. */
  public boolean updateManagedWorkspace(WorkspaceProfile profile, boolean discovered) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace updates must run on the EDT");
    }
    if (disposalStarted || activeWorkspace == null) {
      return false;
    }
    if (workspaceContextTransition) {
      JOptionPane.showMessageDialog(
          this,
          "Wait for the current Workspace connection change to finish before editing it.",
          "Workspace is changing",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    if (launchController.isBusy()) {
      JOptionPane.showMessageDialog(
          this,
          "Finish or cancel the running Bazel command before editing this Workspace.",
          "Build in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    boolean executionContextChanges = !sameConnection(activeWorkspace, profile);
    if (executionContextChanges && !fileEditors.confirmContextChangeAllowed()) {
      return false;
    }
    openWorkspace(profile, executionContextChanges, discovered);
    setTitle("Bazel Build Visualizer — " + profile.label());
    return true;
  }

  /**
   * Handles an explicit close request for one workspace window.
   *
   * <p>A running capture is never silently cancelled by the title-bar close button or Workspaces
   * menu. The user chooses between cancelling it and keeping the workspace open.
   */
  public void requestWorkspaceWindowClose() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::requestWorkspaceWindowClose);
      return;
    }
    requestWorkspaceWindowCloseIfAllowed();
  }

  /** Returns whether the close was accepted and asynchronous teardown started. */
  public boolean requestWorkspaceWindowCloseIfAllowed() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace close confirmation must run on the EDT");
    }
    if (disposalStarted) {
      return true;
    }
    // Ask before stopping a build. If the user keeps unsaved editor
    // content, the workspace and its running command stay untouched.
    if (!fileEditors.confirmCloseAllowed()) {
      return false;
    }
    if (launchController.isBusy()) {
      Object[] options = {"Cancel Build and Close", "Keep Workspace Open"};
      int choice =
          JOptionPane.showOptionDialog(
              this,
              "A Bazel command is still running in this workspace.",
              "Close workspace",
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.WARNING_MESSAGE,
              null,
              options,
              options[1]);
      if (choice != 0) {
        return false;
      }
      launchController.cancel(CancellationMode.CANCEL);
    }
    disposeAsync();
    return true;
  }

  /** Checks dirty modeless editors without changing this window. EDT-only. */
  public boolean confirmApplicationCloseAllowed() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("application close confirmation must run on the EDT");
    }
    return fileEditors.confirmCloseAllowed();
  }

  /** Whether application quit would need to cancel this window's capture. */
  public boolean hasActiveCapture() {
    return launchController.isBusy();
  }

  /** Requests cancellation after the process-level quit confirmation succeeds. */
  public void cancelCaptureForApplicationClose() {
    launchController.cancel(CancellationMode.CANCEL);
  }

  @Override
  public void dispose() {
    disposeAsync();
  }

  /**
   * Starts disposal and completes after final preference saves and frame cleanup.
   *
   * <p>Callers that must terminate the JVM or answer a desktop quit request use this stage instead
   * of cutting off the shared I/O executor.
   */
  public CompletionStage<Void> disposeAsync() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::beginDisposal);
      return disposalCompletion;
    }
    beginDisposal();
    return disposalCompletion;
  }

  private void beginDisposal() {
    if (disposalStarted) {
      return;
    }
    disposalStarted = true;
    navigationKeys.close();
    ++workspaceConnectionGeneration;
    if (applicationHost != null && !workspaceManagerWindow && activeWorkspace != null) {
      applicationHost.workspaceWindowClosing(activeWorkspace.id());
    }
    log.info("main window disposal started");
    CompletableFuture<Void> discoveryClose =
        workspaceDiscovery == null
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.runAsync(workspaceDiscovery::close, blockingIo);
    JDialog closingPreferences = preferencesDialog;
    if (closingPreferences != null) {
      closingPreferences.dispose();
    }
    setEnabled(false);
    setVisible(false);
    CompletableFuture<Void> launcherClose = launcherPanel.closeAsync().toCompletableFuture();
    importController.cancel();
    CompletableFuture<Void> workerClose =
        shutdownWorkerAsync(worker, blockingIo, WORKER_SHUTDOWN_GRACE, WORKER_FORCED_SHUTDOWN_GRACE)
            .toCompletableFuture();

    // Detach the UI while its executors are still alive. Closing the
    // sources themselves can block behind an in-flight read, so that work
    // joins the asynchronous shutdown below.
    CompletableFuture<Void> viewsClose = releaseViews().toCompletableFuture();
    SessionSource closingLive = liveSource;
    liveSource = null;
    SessionSource closingSource = currentSource;
    currentSource = null;
    if (closingSource == closingLive) {
      closingSource = null;
    }
    CompletableFuture<Void> sourceClose =
        CompletableFuture.allOf(
            closeSourceAfter(closingLive, viewsClose), closeSourceAfter(closingSource, viewsClose));
    CompletableFuture<Void> repositoryClose =
        repositoryBrowserView.closeAsync().toCompletableFuture();
    CompletableFuture<Void> editorClose = fileEditors.closeAsync().toCompletableFuture();
    CompletableFuture<Void> entityViewsClose =
        CompletableFuture.allOf(
            eventsView.closeAsync().toCompletableFuture(),
            actionsView.closeAsync().toCompletableFuture(),
            testsView.closeAsync().toCompletableFuture(),
            errorsView.closeAsync().toCompletableFuture());
    CompletableFuture<Void> queryClose = queryView.closeAsync().toCompletableFuture();

    RemoteExecution closingRemote = activeRemoteExecution;
    activeRemoteExecution = null;
    CompletionStage<Void> captureClose = launchController.closeAsync();
    CompletionStage<Void> terminalClose = terminalView.closeAsync();
    // Preference saves use blockingIo. Keep both its virtual-thread executor
    // and this last displayable frame alive until the newest choice has
    // reached the atomic settings store. The completion only schedules
    // the ordinary cleanup; it never waits on the EDT.
    CompletableFuture<Void> themeClose =
        themePreferences == null
            ? CompletableFuture.completedFuture(null)
            : themePreferences.closeAsync().toCompletableFuture();
    CompletableFuture<Void> loggingClose =
        loggingPreferences == null
            ? CompletableFuture.completedFuture(null)
            : loggingPreferences.closeAsync().toCompletableFuture();
    CompletableFuture<Void> resourceClose =
        CompletableFuture.allOf(
            themeClose,
            loggingClose,
            workspaceSave,
            workspaceDiscoveryOperations,
            workspaceConnectionOperations,
            openPathOperations,
            recentCatalogOperations,
            discoveryClose,
            viewCloseOperations,
            sourceCloseOperations,
            remoteCloseOperations,
            sourceClose,
            launcherClose,
            viewsClose,
            repositoryClose,
            editorClose,
            entityViewsClose,
            queryClose,
            workerClose,
            captureClose.toCompletableFuture(),
            terminalClose.toCompletableFuture());

    // The selected SSH execution is the transport used by both capture
    // and Terminal. Release it only after both owners report that their
    // bounded teardown has finished. A failure in an earlier close is
    // logged but cannot skip the transport close.
    resourceClose
        .handle(
            (ignored, failure) -> {
              if (failure != null) {
                log.warn("a workspace resource did not close cleanly", failure);
              }
              return null;
            })
        .thenRunAsync(
            () -> {
              if (closingRemote != null) {
                closingRemote.close();
              }
            },
            blockingIo)
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                log.warn("workspace execution did not close cleanly", failure);
              }
              SwingUtilities.invokeLater(this::finishDisposal);
            });
  }

  private void finishDisposal() {
    if (disposalFinished) {
      return;
    }
    // A connection completion already queued on the EDT can discover the
    // disposal generation and enqueue one final transport close after the
    // first snapshot above. Drain that close (and the analogous late
    // source close) before shutting down their executor.
    CompletableFuture<Void> lateClose =
        CompletableFuture.allOf(sourceCloseOperations, remoteCloseOperations);
    if (!lateClose.isDone()) {
      lateClose.whenComplete(
          (ignored, failure) -> {
            if (failure != null) {
              log.warn("a late workspace resource did not close cleanly", failure);
            }
            SwingUtilities.invokeLater(this::finishDisposal);
          });
      return;
    }
    disposalFinished = true;
    // Capture and Terminal have completed before this point, so shutting
    // their executors down cannot strand a tunnel, PTY, or journal flush.
    terminalScheduler.shutdown();
    blockingIo.shutdown();
    // The shared import/archive/export/catalog lane reached its bounded
    // shutdown stage before this EDT cleanup.
    // Deliberately not shutdownNow(): interrupting the capture thread is
    // what loses the staged journal buffer. The thread is a daemon, so it
    // does not hold the JVM open, and shutdown() lets an in-flight
    // finalization finish.
    captureWorker.shutdown();
    releaseCaptureLease();
    super.dispose();
    log.info("main window disposal finished");
    disposalCompletion.complete(null);
  }

  /**
   * Stops accepting worker work, gives cooperative tasks time to finish, then interrupts once.
   *
   * <p>The waits run only on {@code waiter}; calling this method from the EDT is non-blocking. The
   * returned stage fails after both bounded waits if a task ignores interruption, allowing
   * application shutdown to continue while making that incomplete teardown observable.
   */
  static CompletionStage<Void> shutdownWorkerAsync(
      ExecutorService worker, Executor waiter, Duration gracefulWait, Duration forcedWait) {
    Objects.requireNonNull(worker, "worker");
    Objects.requireNonNull(waiter, "waiter");
    requirePositive(gracefulWait, "gracefulWait");
    requirePositive(forcedWait, "forcedWait");
    worker.shutdown();
    return CompletableFuture.runAsync(
        () -> {
          try {
            if (worker.awaitTermination(gracefulWait.toNanos(), TimeUnit.NANOSECONDS)) {
              return;
            }
            List<Runnable> abandoned = worker.shutdownNow();
            log.warn(
                "worker shutdown interrupted active work and abandoned {} queued task(s)",
                abandoned.size());
            if (!worker.awaitTermination(forcedWait.toNanos(), TimeUnit.NANOSECONDS)) {
              throw new IllegalStateException(
                  "background work did not stop after bounded shutdown waits");
            }
          } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
          }
        },
        waiter);
  }

  private static void requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  // ----------------------------------------------------------------- menus

  private JMenuBar buildMenuBar() {
    int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    JMenuItem openFile = new JMenuItem("Open BEP File…");
    openFile.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut));
    openFile.addActionListener(event -> chooseFileToImport());

    JMenuItem openSession = new JMenuItem("Open Session…");
    openSession.setAccelerator(
        KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut | KeyEvent.SHIFT_DOWN_MASK));
    openSession.addActionListener(event -> chooseSessionToOpen());

    cancelImportItem.addActionListener(event -> importController.cancel());
    closeSessionItem.addActionListener(event -> closeSession());

    JMenuItem openArchive = new JMenuItem("Open Portable Archive…");
    openArchive.addActionListener(event -> chooseArchiveToOpen());

    // Rebuilt every time it opens: the library changes while the window is
    // up, and a menu populated once would go stale the first time an import
    // finished.
    recentMenu.addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            refreshRecentMenu();
          }

          @Override
          public void menuDeselected(MenuEvent event) {}

          @Override
          public void menuCanceled(MenuEvent event) {}
        });
    // Warm the in-memory menu model without delaying window construction.
    // A very fast click may briefly see the loading row; normal openings
    // render the completed cache immediately and refresh it in background.
    requestRecentSessions();

    JMenuItem cleanUp = new JMenuItem("Clean Up Sessions…");
    cleanUp.addActionListener(event -> cleanUpSessions());

    JMenu file = new JMenu("File");
    file.add(openFile);
    file.add(openSession);
    file.add(openArchive);
    JMenuItem openPprof = new JMenuItem("Open pprof…");
    openPprof.addActionListener(
        event -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setDialogTitle("Open pprof");
          chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
          applyLastDirectory(chooser);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            PprofWindow.open(this, chooser.getSelectedFile().toPath());
          }
        });
    file.add(openPprof);
    file.add(recentMenu);
    file.add(cleanUp);
    file.addSeparator();
    file.add(exportMenu());
    file.addSeparator();
    file.add(cancelImportItem);
    file.add(closeSessionItem);

    JMenuBar bar = new JMenuBar();
    bar.add(buildWorkspacesMenu());
    bar.add(file);
    // macOS exposes Preferences in the application menu through DesktopIntegration.
    // Keep a Swing entry only on desktops without that native action.
    if (!Desktop.isDesktopSupported()
        || !Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES)) {
      JMenuItem preferences = new JMenuItem("Preferences…");
      preferences.addActionListener(event -> showPreferences());
      JMenu settings = new JMenu("Settings");
      settings.add(preferences);
      bar.add(settings);
    }
    bar.add(
        LoggingMenu.create(
            loggingRuntime,
            this::selectLogVerbosity,
            this::openApplicationLog,
            this::revealApplicationLog));
    return bar;
  }

  private JMenu buildWorkspacesMenu() {
    JMenuItem choose = new JMenuItem("Choose Workspace…");
    choose.addActionListener(event -> showWorkspaceHome());

    JMenuItem create = new JMenuItem("New Workspace…");
    create.addActionListener(
        event -> {
          if (applicationHost != null && !workspaceManagerWindow) {
            applicationHost.showNewWorkspace();
          } else if (showWorkspaceHome()) {
            workspaceSelectionPanel.showNewWorkspaceForm();
          }
        });

    JMenuItem edit = new JMenuItem("Edit Current Workspace…");
    edit.addActionListener(event -> editCurrentWorkspace());

    JMenuItem reconnect = new JMenuItem("Reconnect Current Workspace");
    reconnect.addActionListener(event -> reconnectCurrentWorkspace());

    JMenuItem close = new JMenuItem("Close Current Workspace");
    close.addActionListener(
        event -> {
          if (applicationHost != null && !workspaceManagerWindow) {
            requestWorkspaceWindowClose();
          } else {
            closeCurrentWorkspace(true);
          }
        });

    JMenu recent = new JMenu("Available Workspaces");
    JMenu workspaces = new JMenu("Workspaces");
    workspaces.addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            boolean selected = activeWorkspace != null;
            edit.setEnabled(selected && !activeWorkspaceDiscovered && !launchController.isBusy());
            reconnect.setEnabled(selected && !launchController.isBusy());
            close.setEnabled(selected && (applicationHost != null || !launchController.isBusy()));
            recent.removeAll();
            List<WorkspaceProfile> available =
                applicationHost == null
                    ? availableWorkspaceProfiles()
                    : applicationHost.availableWorkspaces();
            if (available.isEmpty()) {
              JMenuItem empty = new JMenuItem("No available workspaces");
              empty.setEnabled(false);
              recent.add(empty);
            } else {
              for (WorkspaceProfile profile : available) {
                boolean discovered =
                    applicationHost == null
                        ? isDiscoveredWorkspace(profile)
                        : applicationHost.isDiscoveredWorkspace(profile);
                JMenuItem item =
                    new JMenuItem(
                        profile.label()
                            + (discovered ? " [Discovered]" : "")
                            + " — "
                            + profile.machineDisplayName());
                item.setToolTipText(PlainText.tooltip(profile.workingDirectory()));
                item.addActionListener(
                    ignored -> {
                      if (applicationHost == null) {
                        openWorkspace(profile, false, discovered);
                      } else {
                        applicationHost.openWorkspace(profile, discovered);
                      }
                    });
                recent.add(item);
              }
            }
          }

          @Override
          public void menuDeselected(MenuEvent event) {}

          @Override
          public void menuCanceled(MenuEvent event) {}
        });
    workspaces.add(choose);
    workspaces.add(create);
    workspaces.add(recent);
    workspaces.addSeparator();
    workspaces.add(edit);
    workspaces.add(reconnect);
    workspaces.add(close);
    return workspaces;
  }

  /** Opens the application Preferences window; desktop app-menu handlers use this route too. */
  public void showPreferences() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::showPreferences);
      return;
    }
    if (applicationHost != null && !workspaceManagerWindow) {
      applicationHost.showPreferences();
      return;
    }
    if (disposalStarted) {
      return;
    }
    JDialog existing = preferencesDialog;
    if (existing != null && existing.isDisplayable()) {
      existing.setVisible(true);
      existing.toFront();
      return;
    }

    JDialog dialog = new JDialog(this, "Preferences", Dialog.ModalityType.MODELESS);
    WorkspaceDiscoveryPreferencesPanel discoveryPanel =
        new WorkspaceDiscoveryPreferencesPanel(
            "",
            script -> saveWorkspaceDiscoveryScript(script, false),
            script -> saveWorkspaceDiscoveryScript(script, true));
    ThemePreferencesPanel themePanel =
        new ThemePreferencesPanel(Themes.current(), this::selectTheme);
    PreferencesPanel panel = new PreferencesPanel(themePanel, discoveryPanel, dialog::dispose);
    preferencesDialog = dialog;
    preferencesPanel = panel;
    discoveryPreferencesPanel = discoveryPanel;
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    dialog.setContentPane(panel);
    dialog.setMinimumSize(new Dimension(740, 520));
    dialog.pack();
    dialog.setLocationRelativeTo(this);
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            if (preferencesDialog == dialog) {
              preferencesDialog = null;
              preferencesPanel = null;
              discoveryPreferencesPanel = null;
            }
          }
        });
    discoveryPanel.setOperationState(true, "Loading discovery script…");
    dialog.setVisible(true);

    try {
      CompletableFuture<Void> load =
          CompletableFuture.runAsync(
              () -> {
                WorkspaceDiscoveryScriptStore.LoadResult loaded =
                    workspaceDiscoveryScriptStore.loadWithDiagnostics();
                SwingUtilities.invokeLater(
                    () -> installLoadedDiscoveryScript(discoveryPanel, loaded));
              },
              blockingIo);
      workspaceDiscoveryOperations = CompletableFuture.allOf(workspaceDiscoveryOperations, load);
    } catch (RejectedExecutionException rejected) {
      discoveryPanel.setOperationState(false, "Preferences are unavailable while the app closes.");
    }
  }

  /** Opens or raises Preferences with the Workspace Discovery settings selected. */
  public void showDiscoveryPreferences() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::showDiscoveryPreferences);
      return;
    }
    showPreferences();
    if (preferencesPanel != null) {
      preferencesPanel.selectDiscovery();
    }
  }

  private void installLoadedDiscoveryScript(
      WorkspaceDiscoveryPreferencesPanel panel, WorkspaceDiscoveryScriptStore.LoadResult loaded) {
    if (!isCurrentPreferencesPanel(panel)) {
      return;
    }
    panel.setScript(loaded.script());
    String status = loaded.diagnostics().isEmpty() ? " " : loaded.diagnostics().getFirst();
    panel.setOperationState(false, status);
  }

  private void saveWorkspaceDiscoveryScript(String script, boolean discoverAfterSave) {
    WorkspaceDiscoveryPreferencesPanel panel = discoveryPreferencesPanel;
    if (panel == null) {
      return;
    }
    panel.setOperationState(
        true, discoverAfterSave ? "Saving, then running discovery…" : "Saving…");
    long wanted = discoverAfterSave ? beginWorkspaceDiscovery() : 0;
    workspaceDiscoveryOperations =
        workspaceDiscoveryOperations
            .handle((ignored, priorFailure) -> null)
            .thenRunAsync(
                () -> {
                  WorkspaceDiscoveryScriptStore.SaveResult saved =
                      workspaceDiscoveryScriptStore.saveWithDiagnostics(script);
                  if (!saved.saved()) {
                    SwingUtilities.invokeLater(
                        () -> discoveryScriptSaveFailed(panel, saved, discoverAfterSave, wanted));
                    return;
                  }
                  if (!discoverAfterSave) {
                    SwingUtilities.invokeLater(
                        () -> {
                          if (isCurrentPreferencesPanel(panel)) {
                            panel.setOperationState(false, "Saved.");
                          }
                        });
                    return;
                  }
                  runWorkspaceDiscovery(wanted, true, panel);
                },
                blockingIo);
  }

  private void discoveryScriptSaveFailed(
      WorkspaceDiscoveryPreferencesPanel panel,
      WorkspaceDiscoveryScriptStore.SaveResult saved,
      boolean discoveryRequested,
      long wanted) {
    String detail =
        saved.diagnostics().isEmpty()
            ? "The workspace discovery script could not be saved."
            : String.join("\n", saved.diagnostics());
    if (discoveryRequested && wanted == workspaceDiscoveryGeneration) {
      workspaceSelectionPanel.setDiscoveryStatus(
          "Discovery did not run because its script could not be saved.");
    }
    if (isCurrentPreferencesPanel(panel)) {
      panel.setOperationState(false, detail);
      showSelectableMessage(
          preferencesDialog, detail, "Workspace Discovery", JOptionPane.WARNING_MESSAGE);
    }
  }

  private void startWorkspaceDiscovery(
      boolean userRequested, WorkspaceDiscoveryPreferencesPanel panel) {
    long wanted = beginWorkspaceDiscovery();
    workspaceDiscoveryOperations =
        workspaceDiscoveryOperations
            .handle((ignored, priorFailure) -> null)
            .thenRunAsync(() -> runWorkspaceDiscovery(wanted, userRequested, panel), blockingIo);
  }

  private long beginWorkspaceDiscovery() {
    long wanted = ++workspaceDiscoveryGeneration;
    discoveredWorkspaceProfiles = List.of();
    refreshWorkspaceChoices();
    workspaceSelectionPanel.setDiscoveryStatus("Discovering workspaces…");
    return wanted;
  }

  private void runWorkspaceDiscovery(
      long wanted, boolean userRequested, WorkspaceDiscoveryPreferencesPanel panel) {
    try {
      WorkspaceDiscovery.DiscoveryResult result = workspaceDiscovery.discover();
      SwingUtilities.invokeLater(
          () -> installWorkspaceDiscovery(result, wanted, userRequested, panel));
    } catch (RuntimeException failure) {
      log.warn("workspace discovery failed unexpectedly", failure);
      SwingUtilities.invokeLater(
          () ->
              workspaceDiscoveryFailed(
                  wanted,
                  userRequested,
                  panel,
                  "Workspace discovery failed unexpectedly: " + describeFailure(failure)));
    }
  }

  private void installWorkspaceDiscovery(
      WorkspaceDiscovery.DiscoveryResult result,
      long wanted,
      boolean userRequested,
      WorkspaceDiscoveryPreferencesPanel panel) {
    if (disposalStarted || wanted != workspaceDiscoveryGeneration) {
      return;
    }
    discoveredWorkspaceProfiles =
        result.workspaces().stream()
            .filter(
                discovered ->
                    workspaceProfiles.stream()
                        .noneMatch(saved -> saved.id().equals(discovered.id())))
            .toList();
    int hiddenIdCollisions = result.workspaces().size() - discoveredWorkspaceProfiles.size();
    refreshWorkspaceChoices();
    if (applicationHost != null) {
      applicationHost.workspaceChoicesChanged(!userRequested);
    }
    String summary = workspaceDiscoverySummary(result);
    if (hiddenIdCollisions > 0) {
      summary +=
          " "
              + hiddenIdCollisions
              + " discovered workspace"
              + (hiddenIdCollisions == 1 ? "" : "s")
              + " conflicted with a saved workspace ID and "
              + (hiddenIdCollisions == 1 ? "was" : "were")
              + " not shown.";
    }
    workspaceSelectionPanel.setDiscoveryStatus(summary);
    if (isCurrentPreferencesPanel(panel)) {
      panel.setOperationState(false, summary);
    }
    boolean hasProblems =
        result.timedOut()
            || (result.exitCode().isPresent() && result.exitCode().getAsInt() != 0)
            || !result.diagnostics().isEmpty()
            || !result.stderr().isBlank();
    if (userRequested && hasProblems && isCurrentPreferencesPanel(panel)) {
      showDiscoveryDiagnostics(result);
    }
  }

  private void workspaceDiscoveryFailed(
      long wanted, boolean userRequested, WorkspaceDiscoveryPreferencesPanel panel, String detail) {
    if (disposalStarted || wanted != workspaceDiscoveryGeneration) {
      return;
    }
    discoveredWorkspaceProfiles = List.of();
    refreshWorkspaceChoices();
    if (applicationHost != null) {
      applicationHost.workspaceChoicesChanged(!userRequested);
    }
    workspaceSelectionPanel.setDiscoveryStatus(detail);
    if (isCurrentPreferencesPanel(panel)) {
      panel.setOperationState(false, detail);
      if (userRequested) {
        showSelectableMessage(
            preferencesDialog, detail, "Workspace Discovery", JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private static String workspaceDiscoverySummary(WorkspaceDiscovery.DiscoveryResult result) {
    String summary;
    if (result.exitCode().isEmpty() && !result.timedOut() && result.diagnostics().isEmpty()) {
      summary = "Workspace discovery is not configured.";
    } else if (result.workspaces().isEmpty()) {
      summary = "Workspace discovery found no workspaces.";
    } else if (result.workspaces().size() == 1) {
      summary = "Workspace discovery found 1 workspace.";
    } else {
      summary = "Workspace discovery found " + result.workspaces().size() + " workspaces.";
    }
    if (!result.diagnostics().isEmpty()) {
      summary += " " + result.diagnostics().getFirst();
      if (result.diagnostics().size() > 1) {
        summary += " (" + (result.diagnostics().size() - 1) + " more)";
      }
    }
    return summary;
  }

  private void showDiscoveryDiagnostics(WorkspaceDiscovery.DiscoveryResult result) {
    StringBuilder detail = new StringBuilder();
    for (String diagnostic : result.diagnostics()) {
      if (!detail.isEmpty()) {
        detail.append('\n');
      }
      detail.append(diagnostic);
    }
    if (!result.stderr().isBlank()) {
      if (!detail.isEmpty()) {
        detail.append("\n\n");
      }
      detail.append("Script stderr:\n").append(result.stderr().stripTrailing());
    }
    showSelectableMessage(
        preferencesDialog,
        detail.toString(),
        "Workspace Discovery",
        result.timedOut() || (result.exitCode().isPresent() && result.exitCode().getAsInt() != 0)
            ? JOptionPane.WARNING_MESSAGE
            : JOptionPane.INFORMATION_MESSAGE);
  }

  private static void showSelectableMessage(
      Component parent, String detail, String title, int messageType) {
    JTextArea text = new JTextArea(detail, 12, 72);
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setCaretPosition(0);
    JScrollPane scroll = new JScrollPane(text);
    scroll.setPreferredSize(new Dimension(700, 260));
    JOptionPane.showMessageDialog(parent, scroll, title, messageType);
  }

  private boolean isCurrentPreferencesPanel(WorkspaceDiscoveryPreferencesPanel panel) {
    return panel != null
        && panel == discoveryPreferencesPanel
        && preferencesDialog != null
        && preferencesDialog.isDisplayable();
  }

  /** Applies a colour theme immediately, then persists it away from the EDT. */
  private boolean selectTheme(AppTheme theme) {
    if (themePreferences == null) {
      return false;
    }
    if (theme == Themes.current()) {
      return true;
    }
    try {
      Themes.install(theme);
    } catch (RuntimeException failure) {
      log.error("could not apply theme {}", theme.id(), failure);
      JOptionPane.showMessageDialog(
          this,
          "The " + theme.displayName() + " theme could not be applied.",
          "Theme unavailable",
          JOptionPane.ERROR_MESSAGE);
      return false;
    }
    // JediTerm owns its renderer and snapshots Swing colours; unlike
    // ordinary Swing components it needs an explicit palette refresh.
    // A renderer fault must not undo a look and feel that is already live
    // or prevent that successful selection from being persisted.
    refreshTerminalTheme();
    themePreferences.save(theme);
    if (applicationHost != null) {
      applicationHost.themeChanged();
    }
    return true;
  }

  private void showThemePersistenceFailure(ThemePreferenceWriter.SaveFailure failure) {
    Runnable show =
        () -> {
          log.warn("could not persist theme {}", failure.theme().id(), failure.cause());
          if (!disposalStarted && isDisplayable()) {
            JOptionPane.showMessageDialog(
                this,
                "The theme changed for this session, but it could not be saved."
                    + " It may reset the next time the app starts.",
                "Theme not saved",
                JOptionPane.WARNING_MESSAGE);
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      show.run();
    } else {
      SwingUtilities.invokeLater(show);
    }
  }

  /** Applies logging detail immediately, then persists it away from the EDT. */
  private boolean selectLogVerbosity(LogVerbosity verbosity) {
    if (loggingPreferences == null && applicationHost != null) {
      return applicationHost.selectLogVerbosity(verbosity);
    }
    if (!loggingRuntime.available()) {
      return false;
    }
    try {
      loggingRuntime.setVerbosity(verbosity);
    } catch (RuntimeException failure) {
      log.error("could not apply logging verbosity {}", verbosity.id(), failure);
      JOptionPane.showMessageDialog(
          this,
          "The " + verbosity.displayName() + " logging level could not be applied.",
          "Logging unavailable",
          JOptionPane.ERROR_MESSAGE);
      return false;
    }
    log.info("application logging verbosity changed to {}", verbosity.id());
    loggingPreferences.save(verbosity);
    return true;
  }

  private void openApplicationLog() {
    if (loggingRuntime.available()) {
      fileEditors.openLocal(loggingRuntime.currentLog());
    }
  }

  private void revealApplicationLog() {
    if (loggingRuntime.available()) {
      fileEditors.reveal(loggingRuntime.currentLog());
    }
  }

  private void showLoggingPersistenceFailure(LoggingPreferenceWriter.SaveFailure failure) {
    Runnable show =
        () -> {
          log.warn(
              "could not persist logging verbosity {}", failure.verbosity().id(), failure.cause());
          if (!disposalStarted && isDisplayable()) {
            JOptionPane.showMessageDialog(
                this,
                "The logging level changed for this session, but it could not be saved."
                    + " It may reset the next time the app starts.",
                "Logging level not saved",
                JOptionPane.WARNING_MESSAGE);
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      show.run();
    } else {
      SwingUtilities.invokeLater(show);
    }
  }

  /** Everything a session can be turned into, and what each one carries. */
  private JMenu exportMenu() {
    JMenu menu = new JMenu("Export");

    JMenuItem redactedArchive = new JMenuItem("Redacted Session Archive…");
    redactedArchive.addActionListener(event -> exportArchive(true));
    JMenuItem completeArchive = new JMenuItem("Complete Session Archive…");
    completeArchive.addActionListener(event -> exportArchive(false));
    JMenuItem bep = new JMenuItem("Binary BEP File…");
    bep.addActionListener(event -> exportBep());

    menu.add(redactedArchive);
    menu.add(completeArchive);
    menu.addSeparator();
    menu.add(bep);
    menu.addSeparator();
    for (TableExport.Table table : TableExport.Table.values()) {
      for (TableExport.Format format : TableExport.Format.values()) {
        JMenuItem item = new JMenuItem(table.displayName() + " as " + format.displayName() + "…");
        item.addActionListener(event -> exportTable(table, format));
        menu.add(item);
      }
    }
    return menu;
  }

  private Optional<Path> currentSessionRoot() {
    SessionSource open = currentSource;
    if (open == null) {
      JOptionPane.showMessageDialog(
          this, "Open a session first.", "Nothing to export", JOptionPane.INFORMATION_MESSAGE);
      return Optional.empty();
    }
    return Optional.of(open.info().root());
  }

  private Optional<Path> chooseSaveTarget(String title, String suggestedName) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(title);
    chooser.setSelectedFile(new File(suggestedName));
    applyLastDirectory(chooser);
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return Optional.empty();
    }
    Path chosen = chooser.getSelectedFile().toPath();
    rememberDirectory(chosen.getParent());
    return Optional.of(chosen);
  }

  private void exportArchive(boolean redacted) {
    currentSessionRoot()
        .ifPresent(
            root ->
                chooseSaveTarget(
                        redacted ? "Export Redacted Session" : "Export Complete Session",
                        root.getFileName() + (redacted ? "-redacted.bviz" : ".bviz"))
                    .ifPresent(
                        target -> {
                          if (!redacted && !confirmCompleteExport()) {
                            return;
                          }
                          ExportController.RedactionOptions options =
                              redacted
                                  ? askRedactionOptions()
                                  : ExportController.RedactionOptions.defaults();
                          if (options == null) {
                            return;
                          }
                          exports.exportArchive(
                              root,
                              target,
                              redacted,
                              options,
                              APP_VERSION,
                              this::confirmRedaction,
                              result -> showExportResult("Export complete", result.describe()),
                              failure -> showExportFailure(failure));
                        }));
  }

  private void exportBep() {
    currentSessionRoot()
        .ifPresent(
            root ->
                chooseSaveTarget("Export Binary BEP", root.getFileName() + ".bep")
                    .ifPresent(
                        target ->
                            exports.exportBep(
                                root,
                                target,
                                result ->
                                    showExportResult("BEP export complete", result.describe()),
                                this::showExportFailure)));
  }

  private void exportTable(TableExport.Table table, TableExport.Format format) {
    currentSessionRoot()
        .ifPresent(
            root ->
                chooseSaveTarget(
                        "Export " + table.displayName(), table.fileStem() + format.extension())
                    .ifPresent(
                        target ->
                            exports.exportTable(
                                root,
                                table,
                                format,
                                target,
                                true,
                                result -> showExportResult("Export complete", result.describe()),
                                this::showExportFailure)));
  }

  /**
   * The two extra redaction choices, offered before anything runs.
   *
   * <p>Both are off by default and both cost something real — one removes every environment value
   * including the ordinary ones, the other makes the export hard to read — so they are decisions a
   * person makes rather than defaults they discover afterwards.
   *
   * @return null when the dialog was cancelled
   */
  private ExportController.RedactionOptions askRedactionOptions() {
    JCheckBox omitEnvironment =
        new JCheckBox("Omit every environment value, keeping only the names");
    JCheckBox hideLabels =
        new JCheckBox("Replace target labels with pseudonyms (makes the export hard to read)");
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            new Object[] {
              "Secrets and absolute paths are always redacted.",
              "These go further:",
              omitEnvironment,
              hideLabels,
            },
            "Redacted export",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return null;
    }
    return new ExportController.RedactionOptions(
        omitEnvironment.isSelected(), hideLabels.isSelected());
  }

  /**
   * Shows what redaction did, before the archive is written.
   *
   * <p>docs/privacy.md requires this, and the report deliberately does not promise that everything
   * sensitive was found — it says what the patterns matched, so the decision to share rests on
   * something a person read.
   */
  private boolean confirmRedaction(RedactionReport report) {
    if (disposalStarted) {
      return false;
    }
    JTextArea text = new JTextArea(String.join("\n", report.lines()));
    text.setEditable(false);
    text.setRows(Math.min(20, report.lines().size() + 2));
    text.setColumns(72);
    return JOptionPane.showConfirmDialog(
            this,
            new JScrollPane(text),
            "Redaction report",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION;
  }

  /**
   * A complete export is as sensitive as the machine it was taken on.
   *
   * <p>The raw capture holds the original bytes, secrets included, so this is the one export that
   * must be asked about rather than reported afterwards.
   */
  private boolean confirmCompleteExport() {
    return JOptionPane.showConfirmDialog(
            this,
            "A complete archive contains the raw capture: every command line,"
                + " every environment value and every absolute path, exactly as"
                + " captured.\n\nIt is as sensitive as this machine. Share it only"
                + " with somebody who could already read this session.\n\nExport it"
                + " anyway?",
            "Complete export",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.OK_OPTION;
  }

  private void showExportResult(String title, String message) {
    log.info("{}: {}", title, message);
    if (disposalStarted) {
      return;
    }
    JTextArea text = new JTextArea(message);
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setRows(6);
    text.setColumns(64);
    JOptionPane.showMessageDialog(
        this, new JScrollPane(text), title, JOptionPane.INFORMATION_MESSAGE);
  }

  private void showExportFailure(Throwable failure) {
    log.error("export failed", failure);
    if (disposalStarted) {
      return;
    }
    JOptionPane.showMessageDialog(
        this, String.valueOf(failure.getMessage()), "Export failed", JOptionPane.ERROR_MESSAGE);
  }

  // ----------------------------------------------------------- the library

  /** Rebuilds the Open Recent menu from the last background catalog read. */
  private void refreshRecentMenu() {
    recentMenu.removeAll();
    if (!recentCatalogLoaded) {
      addDisabledRecentItem("Loading recent sessions…");
    } else if (recentCatalogFailure != null) {
      addDisabledRecentItem("The session library could not be read");
    } else if (recentCatalogEntries.isEmpty()) {
      addDisabledRecentItem("No sessions yet");
    } else {
      addRecentEntries(recentCatalogEntries);
    }
    requestRecentSessions();
  }

  private void addDisabledRecentItem(String text) {
    JMenuItem item = new JMenuItem(text);
    item.setEnabled(false);
    recentMenu.add(item);
  }

  private void addRecentEntries(List<CatalogEntry> entries) {
    for (CatalogEntry entry : entries) {
      JMenu submenu =
          new JMenu(
              (entry.pinned() ? "\u2605 " : "")
                  + entry.displayName()
                  + (entry.missing() ? "  (not found)" : ""));
      submenu.setToolTipText(
          PlainText.tooltip(
              entry.directory().toString() + entry.summary().map(text -> " — " + text).orElse("")));

      JMenuItem open = new JMenuItem("Open");
      // A session whose directory is gone is listed and not offered:
      // seeing that it existed is the point of keeping the row.
      open.setEnabled(!entry.missing());
      open.addActionListener(event -> openPath(entry.directory()));
      submenu.add(open);

      // Pinning is the only way a user can say "this one matters",
      // and it is what retention refuses to override. Without a
      // control for it the protection exists and nobody can use it.
      JMenuItem pin = new JMenuItem(entry.pinned() ? "Unpin" : "Pin");
      pin.addActionListener(event -> setPinned(entry.sessionUuid(), !entry.pinned()));
      submenu.add(pin);

      JMenuItem forget = new JMenuItem("Remove from Recent");
      forget.setToolTipText(
          PlainText.tooltip("Takes it off this list. The session stays on disk."));
      forget.addActionListener(event -> forgetSession(entry.sessionUuid()));
      submenu.add(forget);

      recentMenu.add(submenu);
    }
  }

  /** Starts one catalog read; only its small immutable result returns to Swing. */
  private void requestRecentSessions() {
    if (recentCatalogLoadInProgress || disposalStarted) {
      return;
    }
    recentCatalogLoadInProgress = true;
    long generation = ++recentCatalogLoadGeneration;
    CompletableFuture<Void> read =
        readRecentSessionsAsync(catalogDirectory, blockingIo)
            .handle(
                (entries, failure) -> {
                  SwingUtilities.invokeLater(
                      () -> acceptRecentSessions(generation, entries, failure));
                  return (Void) null;
                })
            .toCompletableFuture();
    recentCatalogOperations = CompletableFuture.allOf(recentCatalogOperations, read);
  }

  private void acceptRecentSessions(
      long generation, List<CatalogEntry> entries, Throwable failure) {
    if (disposalStarted || generation != recentCatalogLoadGeneration) {
      return;
    }
    recentCatalogLoadInProgress = false;
    recentCatalogLoaded = true;
    if (failure == null) {
      recentCatalogEntries = List.copyOf(entries);
      recentCatalogFailure = null;
    } else {
      recentCatalogEntries = List.of();
      recentCatalogFailure = String.valueOf(failure.getMessage());
      log.warn("could not read the session catalog", failure);
    }
    // Do not mutate an open popup underneath the pointer. The constructor
    // prefetch normally makes the cache ready for the first opening; if it
    // is still loading, the completed result appears on the next opening.
  }

  /** Catalog access for Open Recent. The supplied executor must not be the EDT. */
  static CompletionStage<List<CatalogEntry>> readRecentSessionsAsync(
      Path directory, Executor executor) {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(executor, "executor");
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return CatalogAccess.withCatalog(directory, catalog -> catalog.recent(12));
          } catch (Exception failure) {
            throw new CompletionException(failure);
          }
        },
        executor);
  }

  /** Queues ordinary background work only while this window can still consume its result. */
  private boolean executeWorker(Runnable action) {
    if (disposalStarted) {
      return false;
    }
    try {
      worker.execute(action);
      return true;
    } catch (RejectedExecutionException rejected) {
      if (!disposalStarted) {
        log.warn("background work was rejected before window disposal", rejected);
      }
      return false;
    }
  }

  /** Reconciles the catalog against the directories that are actually there. */
  private void reconcileLibrary() {
    executeWorker(
        () -> {
          try {
            SessionCatalog.RescanResult result =
                CatalogAccess.withCatalog(
                    catalogDirectory,
                    catalog ->
                        catalog.rescan(
                            sessionsRoot,
                            directory -> CatalogEntries.read(directory, sessions::readManifest)));
            log.info("session library: {}", result.describe());
          } catch (Exception failure) {
            log.warn("could not reconcile the session library", failure);
          }
        });
  }

  private void setPinned(String sessionUuid, boolean pinned) {
    executeWorker(
        () -> {
          try {
            CatalogAccess.withCatalog(
                catalogDirectory,
                catalog -> {
                  catalog.setPinned(sessionUuid, pinned);
                  return null;
                });
          } catch (Exception failure) {
            log.warn("could not change the pin on {}", sessionUuid, failure);
          }
        });
  }

  private void forgetSession(String sessionUuid) {
    executeWorker(
        () -> {
          try {
            CatalogAccess.withCatalog(
                catalogDirectory,
                catalog -> {
                  catalog.forget(sessionUuid);
                  return null;
                });
          } catch (Exception failure) {
            log.warn("could not forget {}", sessionUuid, failure);
          }
        });
  }

  /**
   * Retention, with the plan shown before anything is deleted.
   *
   * <p>Deleting a session is not reversible and a session is sometimes the only record of a failure
   * that has stopped reproducing, so the flow is: choose a limit, see exactly what that limit
   * selects, and only then confirm. A sweep that ran and reported afterwards would be the wrong
   * shape for the thing being swept.
   */
  private void cleanUpSessions() {
    JSpinner keep = new JSpinner(new SpinnerNumberModel(20, 1, 10_000, 1));
    JCheckBox byAge = new JCheckBox("…and anything older than");
    JSpinner days = new JSpinner(new SpinnerNumberModel(90, 1, 3_650, 1));
    JCheckBox bySize = new JCheckBox("…and keep the total under");
    JSpinner gigabytes = new JSpinner(new SpinnerNumberModel(20, 1, 10_000, 1));
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            new Object[] {
              "Keep the most recently opened sessions and remove the rest.",
              "Pinned sessions are never removed.",
              keep,
              byAge,
              new Object[] {days, "days"},
              bySize,
              new Object[] {gigabytes, "GB"},
            },
            "Clean Up Sessions",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }
    long limit = ((Number) keep.getValue()).longValue();
    RetentionPolicy policy = RetentionPolicy.keepEverything().withMaxSessions(limit);
    if (byAge.isSelected()) {
      policy =
          policy.withMaxAgeMicros(((Number) days.getValue()).longValue() * 86_400L * 1_000_000L);
    }
    if (bySize.isSelected()) {
      policy =
          policy.withMaxTotalBytes(
              ((Number) gigabytes.getValue()).longValue() * 1_024L * 1_024 * 1_024);
    }
    RetentionPolicy chosen = policy;
    executeWorker(
        () -> {
          try {
            RetentionPolicy.Plan plan =
                CatalogAccess.withCatalog(
                    catalogDirectory,
                    catalog -> catalog.plan(chosen, System.currentTimeMillis() * 1_000L));
            SwingUtilities.invokeLater(() -> confirmSweep(plan));
          } catch (Exception failure) {
            log.error("could not plan a cleanup", failure);
            SwingUtilities.invokeLater(() -> showExportFailure(failure));
          }
        });
  }

  private void confirmSweep(RetentionPolicy.Plan plan) {
    if (disposalStarted) {
      return;
    }
    JTextArea text = new JTextArea(String.join("\n", plan.lines()));
    text.setEditable(false);
    text.setRows(Math.min(20, plan.lines().size() + 1));
    text.setColumns(72);
    if (plan.isEmpty()) {
      JOptionPane.showMessageDialog(
          this, new JScrollPane(text), "Nothing to remove", JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    if (JOptionPane.showConfirmDialog(
            this,
            new JScrollPane(text),
            "Remove these sessions?",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE)
        != JOptionPane.OK_OPTION) {
      return;
    }
    executeWorker(
        () -> {
          try {
            // The plan, not the policy: what is deleted is what was shown. Active-session,
            // pin and relocation state is rechecked under the process mutation lock.
            SessionCatalog.SweepResult result =
                sessionMutations.applyCleanup(catalogDirectory, plan);
            SwingUtilities.invokeLater(
                () -> showExportResult("Cleanup complete", result.describe()));
          } catch (Exception failure) {
            log.error("cleanup failed", failure);
            SwingUtilities.invokeLater(() -> showExportFailure(failure));
          }
        });
  }

  /** Records an opened session in the library, off the event thread. */
  private void recordInCatalog(Path sessionRoot) {
    executeWorker(
        () -> {
          try {
            Optional<CatalogEntry> entry = CatalogEntries.read(sessionRoot, sessions::readManifest);
            if (entry.isPresent()) {
              CatalogEntry opened = entry.orElseThrow();
              CatalogAccess.withCatalog(
                  catalogDirectory,
                  catalog -> {
                    catalog.record(opened);
                    catalog.touch(opened.sessionUuid(), System.currentTimeMillis() * 1_000L);
                    return null;
                  });
            }
          } catch (Exception failure) {
            // The library is a convenience. A session that opens perfectly
            // well must not fail because its index could not be updated.
            log.warn("could not record {} in the session catalog", sessionRoot, failure);
          }
        });
  }

  private void chooseArchiveToOpen() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Open Portable Archive");
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    applyLastDirectory(chooser);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path chosen = chooser.getSelectedFile().toPath();
    rememberDirectory(chosen.getParent());
    openPath(chosen);
  }

  private void chooseFileToImport() {
    if (importController.isRunning()) {
      JOptionPane.showMessageDialog(
          this,
          "An import is already running. Cancel it before starting another.",
          "Import in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Open BEP File");
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    // No file filter by extension: the format is decided from the file's
    // content (plan 5.2), and a filter would hide the very files whose
    // names do not match what they are.
    applyLastDirectory(chooser);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File chosen = chooser.getSelectedFile();
    rememberDirectory(chosen.toPath().getParent());
    startImport(chosen.toPath());
  }

  private void chooseSessionToOpen() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Open Session Directory");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    applyLastDirectory(chooser);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File chosen = chooser.getSelectedFile();
    rememberDirectory(chosen.toPath().getParent());
    openSessionDirectory(chosen.toPath(), true);
  }

  private void applyLastDirectory(JFileChooser chooser) {
    if (lastChooserDirectory != null) {
      chooser.setCurrentDirectory(lastChooserDirectory.toFile());
    }
  }

  private void rememberDirectory(Path directory) {
    if (directory != null) {
      lastChooserDirectory = directory;
    }
  }

  // ---------------------------------------------------------------- import

  /**
   * Opens whatever a path turns out to be.
   *
   * <p>The one route for the Open menu, a command-line argument and a file macOS hands over on a
   * double click. Three routes that each classified for themselves would be three chances to open a
   * {@code .bviz} as a BEP file and report a parser error about a Zip header — which tells a user
   * nothing about what they actually did.
   */
  public void openPath(Path path) {
    Objects.requireNonNull(path, "path");
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> openPath(path));
      return;
    }
    if (disposalStarted) {
      return;
    }
    rootCardLayout.show(rootCards, ROOT_SHELL);
    openPathOperations =
        openPathOperations
            .handle((ignored, previousFailure) -> null)
            .thenCompose(ignored -> inspectOpenPathAsync(path, blockingIo))
            .handle(
                (inspection, failure) -> {
                  SwingUtilities.invokeLater(() -> finishOpenPath(inspection, failure));
                  return null;
                });
  }

  private void finishOpenPath(OpenPathInspection inspection, Throwable failure) {
    if (disposalStarted) {
      return;
    }
    if (failure != null) {
      log.warn("could not inspect path before opening it", failure);
      showSessionFailure(String.valueOf(failure.getMessage()));
      return;
    }
    OpenRequest request = inspection.request();
    switch (request.kind()) {
      case SESSION_DIRECTORY -> openSessionDirectory(request.path(), true);
      case PORTABLE_ARCHIVE -> importArchive(request.path());
      case BEP_FILE -> startImport(request.path());
      case UNSUPPORTED -> showSessionFailure(inspection.unsupportedDescription());
    }
  }

  /** Everything the UI needs after one background-only path inspection. */
  record OpenPathInspection(OpenRequest request, String unsupportedDescription) {

    OpenPathInspection {
      Objects.requireNonNull(request, "request");
      if (request.kind() == OpenRequest.Kind.UNSUPPORTED) {
        Objects.requireNonNull(unsupportedDescription, "unsupportedDescription");
      }
    }
  }

  /** Performs all path metadata reads on the supplied background executor. */
  static CompletionStage<OpenPathInspection> inspectOpenPathAsync(Path path, Executor executor) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(executor, "executor");
    return CompletableFuture.supplyAsync(
        () -> {
          if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("open-path metadata must not be read on the EDT");
          }
          OpenRequest request = OpenRequest.classify(path);
          String description =
              request.kind() == OpenRequest.Kind.UNSUPPORTED ? request.describeUnsupported() : null;
          return new OpenPathInspection(request, description);
        },
        executor);
  }

  /**
   * Validates a portable archive and brings it into the library.
   *
   * <p>On a worker: validation decompresses every entry to check its checksum, which for a real
   * session is gigabytes and is never something the EDT does (rule 8).
   */
  private void importArchive(Path archive) {
    if (disposalStarted) {
      return;
    }
    showEventsCard();
    eventsView.showEmpty("Checking " + archive.getFileName() + "…");
    executeWorker(
        () -> {
          try {
            ArchiveImport.Result result =
                sessionMutations.importArchive(archive, sessionsRoot, BvizLimits.defaults());
            log.info("imported archive: {}", result.describe());
            SwingUtilities.invokeLater(
                () -> {
                  if (disposalStarted) {
                    return;
                  }
                  if (result.redacted()) {
                    // The user is about to look at a session whose raw
                    // capture is deliberately absent. Saying so once, here,
                    // is better than every later view explaining why an
                    // enrichment cannot be re-run.
                    JOptionPane.showMessageDialog(
                        this,
                        result.describe(),
                        "Redacted session",
                        JOptionPane.INFORMATION_MESSAGE);
                  }
                  openSessionDirectory(result.sessionRoot(), false);
                });
          } catch (Exception failure) {
            log.error("could not open archive {}", archive, failure);
            SwingUtilities.invokeLater(() -> showSessionFailure(failure.getMessage()));
          }
        });
  }

  private void startImport(Path source) {
    if (disposalStarted) {
      return;
    }
    showEventsCard();
    eventsView.closeSession();
    eventsView.showImportProgress();
    eventsView.progressPanel().beginRun(source, false);
    cancelImportItem.setEnabled(true);
    closeSessionItem.setEnabled(false);
    sessionStatus.setText("Session: importing");
    eventStatus.setText("Events: " + UNKNOWN);
    if (!importController.start(source, new ImportListener())) {
      JOptionPane.showMessageDialog(
          this,
          "An import is already running.",
          "Import in progress",
          JOptionPane.INFORMATION_MESSAGE);
    }
  }

  private void resumeImport(Path sessionRoot) {
    if (disposalStarted) {
      return;
    }
    showEventsCard();
    eventsView.closeSession();
    eventsView.showImportProgress();
    eventsView.progressPanel().beginRun(sessionRoot, true);
    cancelImportItem.setEnabled(true);
    closeSessionItem.setEnabled(false);
    sessionStatus.setText("Session: resuming");
    importController.resume(sessionRoot, new ImportListener());
  }

  /** Receives the import lifecycle on the EDT. */
  private final class ImportListener implements ImportController.Listener {

    @Override
    public void importStarted(Path source, boolean resuming) {
      if (disposalStarted) {
        return;
      }
      eventsView.progressPanel().beginRun(source, resuming);
    }

    @Override
    public void importProgress(ImportProgressModel.Snapshot snapshot) {
      if (disposalStarted) {
        return;
      }
      eventsView.progressPanel().update(snapshot);
    }

    @Override
    public void importFinished(ImportResult result) {
      if (disposalStarted) {
        return;
      }
      cancelImportItem.setEnabled(false);
      eventsView.progressPanel().finish(summarize(result));
      if (result.outcome() != ImportOutcome.COMPLETE) {
        // Never presented as a clean import. The user is told what was
        // read, where it stopped, and that the rest is absent.
        JOptionPane.showMessageDialog(
            MainWindow.this,
            summarize(result),
            "Import finished with findings",
            JOptionPane.WARNING_MESSAGE);
      }
      openSessionDirectory(result.sessionRoot(), false);
    }

    @Override
    public void importFailed(Path source, Throwable failure) {
      if (disposalStarted) {
        return;
      }
      cancelImportItem.setEnabled(false);
      eventsView.progressPanel().finish("Import failed.");
      eventsView.showEmpty("Import failed. Nothing was indexed.");
      sessionStatus.setText("Session: none");
      String message =
          failure instanceof UnsupportedSourceException ? failure.getMessage() : failure.toString();
      JOptionPane.showMessageDialog(
          MainWindow.this, message, "Cannot import", JOptionPane.ERROR_MESSAGE);
    }
  }

  private static String summarize(ImportResult result) {
    StringBuilder text = new StringBuilder();
    text.append(
        switch (result.outcome()) {
          case COMPLETE -> "Imported the whole file.";
          case TRUNCATED -> "The file ends mid-record. Everything before the cut was imported.";
          case CORRUPT_PARTIAL ->
              "A record's framing contradicted itself. Reading stopped there"
                  + " rather than guessing at the next boundary.";
          case CANCELLED -> "Import cancelled. The session is resumable from its checkpoint.";
        });
    text.append("\nEvents indexed: ").append(EventValueFormat.count(result.eventsInDatabase()));
    text.append("\nSource completeness: ").append(result.sourceCompleteness());
    result
        .damageOffset()
        .ifPresent(offset -> text.append("\nDamage begins at byte offset ").append(offset));
    text.append("\nSession: ").append(result.sessionRoot());
    return text.toString();
  }

  // --------------------------------------------------------------- session

  /**
   * Opens an indexed session without re-importing it.
   *
   * @param offerResume when true, a session that never finished importing prompts before opening,
   *     because resuming it is usually what the user wants and opening it silently would hide that
   *     the capture is partial
   */
  private void openSessionDirectory(Path root, boolean offerResume) {
    if (disposalStarted) {
      return;
    }
    showEventsCard();
    eventsView.showEmpty("Opening " + root + "…");
    executeWorker(
        () -> {
          try {
            SessionManifest manifest = sessions.readManifest(root);
            if (offerResume && !manifest.state().isTerminal()) {
              SwingUtilities.invokeLater(() -> promptResume(root, manifest));
              return;
            }
            SessionSource opened = openProtectedSession(root, manifest);
            SwingUtilities.invokeLater(
                () -> {
                  if (disposalStarted) {
                    closeSource(opened);
                    return;
                  }
                  installSession(opened);
                  showSessionInfo(opened.info());
                });
            recordInCatalog(root);
          } catch (Exception failure) {
            log.error("could not open session {}", root, failure);
            SwingUtilities.invokeLater(() -> showSessionFailure(failure.toString()));
          }
        });
  }

  /** Takes the process lease before opening any database handle for this session. */
  private SessionSource openProtectedSession(Path root, SessionManifest manifest)
      throws InterruptedException {
    SessionMutationCoordinator.ActiveSession active =
        sessionMutations.activate(manifest.sessionId().toString(), root);
    SessionSource opened = null;
    try {
      opened = SqliteSessionSource.open(sessions, root);
      return active.guard(opened);
    } catch (RuntimeException | Error failure) {
      try {
        if (opened != null) {
          opened.close();
        }
      } finally {
        active.close();
      }
      throw failure;
    }
  }

  /** Reads the live manifest before taking its lease; callers already run on a worker. */
  private SessionSource openProtectedSession(Path root) throws Exception {
    return openProtectedSession(root, sessions.readManifest(root));
  }

  private void promptResume(Path root, SessionManifest manifest) {
    if (disposalStarted) {
      return;
    }
    Object[] options = {"Resume import", "Open as it is", "Cancel"};
    int choice =
        JOptionPane.showOptionDialog(
            this,
            "This session is in state "
                + manifest.state()
                + ", so its import never finished.\n"
                + "Resuming continues from the last checkpoint without re-reading the"
                + " source from the beginning.",
            "Unfinished session",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);
    switch (choice) {
      case 0 -> resumeImport(root);
      case 1 -> openSessionDirectory(root, false);
      default -> eventsView.showEmpty("No session is open.");
    }
  }

  /**
   * Hands one session to every view and takes ownership of it.
   *
   * <p>The next session is installed immediately. The previous source closes asynchronously only
   * after every old view reader has finished its bounded teardown, so replacing a session never
   * closes SQLite under accepted work.
   */
  private void installSession(SessionSource opened) {
    if (disposalStarted) {
      closeSource(opened);
      return;
    }
    CompletionStage<Void> released = releaseViews();
    SessionSource previous = currentSource;
    SessionSource previousLive = liveSource;
    liveSource = null;
    currentSource = opened;
    openEventsSession(opened);
    overviewPanel.openSession(opened);
    actionsView.openSession(opened);
    targetsView.openSession(opened);
    allTargetsView.openSession(opened);
    configurationsView.openSession(opened);
    if (nav.getSelectedValue() == NavEntry.ALL_TARGETS) {
      allTargetsView.activate();
    } else if (nav.getSelectedValue() == NavEntry.CONFIGURATIONS) {
      configurationsView.activate();
    }
    testsView.openSession(opened);
    errorsView.openSession(opened);
    coverageView.openSession(opened);
    treeView.openSession(opened);
    graphExplorerView.openSession(opened);
    criticalPathView.openSession(opened);
    starlarkProfileView.openSession(opened);
    timeline.openSession(opened);
    // One metric collection feeds both the findings card and the overview's
    // cards, so opening a session scans its actions once rather than twice.
    metricsService = new MetricsService(opened);
    metricsService.addListener(overviewPanel::showMetrics);
    metricsService.addListener(criticalPathView::show);
    metricsService.addErrorListener(criticalPathView::showFailure);
    metricsService.addListener(
        result ->
            derivedCriticalPath =
                result
                    .metrics()
                    .invocation()
                    .criticalPaths()
                    .derived()
                    .map(Result::path)
                    .orElse(List.of()));
    findingsView.attach(metricsService);
    queryView.openSession(opened);
    closeSourceAfter(previous, released);
    if (previousLive != previous) {
      closeSourceAfter(previousLive, released);
    }
  }

  private void configureWorkspaceSelection() {
    workspaceSelectionPanel.onOpen(
        profile -> {
          if (applicationHost == null) {
            openWorkspace(profile);
          } else {
            applicationHost.openWorkspace(profile, isDiscoveredWorkspace(profile));
          }
        });
    workspaceSelectionPanel.onCreate(
        profile -> {
          if (applicationHost == null) {
            openWorkspace(profile);
          } else {
            applicationHost.openWorkspace(profile, false);
          }
        });
    workspaceSelectionPanel.onUpdate(
        profile -> {
          boolean active =
              applicationHost == null
                  && activeWorkspace != null
                  && activeWorkspace.id().equals(profile.id());
          if (active) {
            return updateManagedWorkspace(profile, activeWorkspaceDiscovered);
          }
          if (applicationHost != null) {
            return applicationHost.workspaceUpdated(profile);
          }
          return upsertWorkspace(profile);
        });
    workspaceSelectionPanel.onRemove(this::removeWorkspace);
    workspaceSelectionPanel.onDiscover(() -> startWorkspaceDiscovery(true, null));
    workspaceSelectionPanel.onEditDiscovery(this::showDiscoveryPreferences);
  }

  private void refreshWorkspaceChoices() {
    workspaceSelectionPanel.setWorkspaces(workspaceProfiles, discoveredWorkspaceProfiles);
  }

  private List<WorkspaceProfile> availableWorkspaceProfiles() {
    return availableWorkspaceProfiles(workspaceProfiles, discoveredWorkspaceProfiles);
  }

  static List<WorkspaceProfile> availableWorkspaceProfiles(
      List<WorkspaceProfile> savedProfiles, List<WorkspaceProfile> discoveredProfiles) {
    ArrayList<WorkspaceProfile> available =
        new ArrayList<>(savedProfiles.size() + discoveredProfiles.size());
    available.addAll(savedProfiles);
    for (WorkspaceProfile discovered : discoveredProfiles) {
      if (savedProfiles.stream().noneMatch(saved -> saved.id().equals(discovered.id()))) {
        available.add(discovered);
      }
    }
    available.sort(WorkspaceProfile.RECENT_FIRST);
    return List.copyOf(available);
  }

  private boolean isDiscoveredWorkspace(WorkspaceProfile profile) {
    return isDiscoveredWorkspace(workspaceProfiles, discoveredWorkspaceProfiles, profile);
  }

  static boolean isDiscoveredWorkspace(
      List<WorkspaceProfile> savedProfiles,
      List<WorkspaceProfile> discoveredProfiles,
      WorkspaceProfile profile) {
    if (savedProfiles.stream().anyMatch(saved -> saved.id().equals(profile.id()))) {
      return false;
    }
    return discoveredProfiles.stream().anyMatch(discovered -> discovered.id().equals(profile.id()));
  }

  /** Shows the startup workspace menu without disconnecting the current workspace. */
  private boolean showWorkspaceHome() {
    if (applicationHost != null && !workspaceManagerWindow) {
      applicationHost.showWorkspaceManager();
      return true;
    }
    if (launchController.isBusy()) {
      JOptionPane.showMessageDialog(
          this,
          "Finish or cancel the running build before changing workspaces.",
          "Build in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    refreshWorkspaceChoices();
    rootCardLayout.show(rootCards, ROOT_WORKSPACES);
    return true;
  }

  private void editCurrentWorkspace() {
    WorkspaceProfile current = activeWorkspace;
    if (current != null && applicationHost != null && !workspaceManagerWindow) {
      if (!activeWorkspaceDiscovered && !launchController.isBusy()) {
        applicationHost.editWorkspace(current);
      }
    } else if (current != null && !activeWorkspaceDiscovered && showWorkspaceHome()) {
      workspaceSelectionPanel.showEditWorkspaceForm(current);
    }
  }

  private void reconnectCurrentWorkspace() {
    WorkspaceProfile current = activeWorkspace;
    if (current != null) {
      openWorkspace(current, true, activeWorkspaceDiscovered);
    }
  }

  private boolean upsertWorkspace(WorkspaceProfile profile) {
    Objects.requireNonNull(profile, "profile");
    boolean existing =
        workspaceProfiles.stream().anyMatch(candidate -> candidate.id().equals(profile.id()));
    if (!existing && workspaceProfiles.size() >= WorkspaceStore.MAX_SAVED_WORKSPACES) {
      JOptionPane.showMessageDialog(
          this,
          "At most "
              + WorkspaceStore.MAX_SAVED_WORKSPACES
              + " workspaces can be saved. Remove one before adding another.",
          "Workspace limit reached",
          JOptionPane.WARNING_MESSAGE);
      return false;
    }
    ArrayList<WorkspaceProfile> updated = new ArrayList<>(workspaceProfiles.size() + 1);
    updated.add(profile);
    for (WorkspaceProfile candidate : workspaceProfiles) {
      if (!candidate.id().equals(profile.id())) {
        updated.add(candidate);
      }
    }
    updated.sort(WorkspaceProfile.RECENT_FIRST);
    workspaceProfiles = List.copyOf(updated);
    refreshWorkspaceChoices();
    queueWorkspaceSave();
    return true;
  }

  private void removeWorkspace(WorkspaceProfile profile) {
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            "Remove ‘"
                + profile.label()
                + "’ from saved workspaces?\n"
                + "Its command history and table layout settings will also be removed.\n"
                + "No repository files or captured sessions will be deleted.",
            "Remove workspace",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }
    if (applicationHost != null && !applicationHost.workspaceRemoved(profile)) {
      return;
    }
    workspaceProfiles =
        workspaceProfiles.stream()
            .filter(candidate -> !candidate.id().equals(profile.id()))
            .toList();
    refreshWorkspaceChoices();
    queueWorkspaceSave(
        applicationHost == null
            ? () -> {}
            : () -> applicationHost.workspaceRemovalPersisted(profile));
    if (activeWorkspace != null && activeWorkspace.id().equals(profile.id())) {
      closeCurrentWorkspace(false);
    }
  }

  private void queueWorkspaceSave() {
    queueWorkspaceSave(() -> {});
  }

  private void queueWorkspaceSave(Runnable afterSuccessfulSave) {
    if (workspaceStore == null) {
      return;
    }
    Objects.requireNonNull(afterSuccessfulSave, "afterSuccessfulSave");
    List<WorkspaceProfile> snapshot = List.copyOf(workspaceProfiles);
    workspaceSave =
        workspaceSave
            .handle((ignored, priorFailure) -> null)
            .thenComposeAsync(
                ignored -> {
                  WorkspaceStore.SaveResult result = workspaceStore.saveWithDiagnostics(snapshot);
                  CompletableFuture<Void> accepted = new CompletableFuture<>();
                  if (!result.saved()) {
                    String detail =
                        result.diagnostics().isEmpty()
                            ? "Workspaces could not be saved."
                            : String.join("\n", result.diagnostics());
                    completeOnEventThread(accepted, () -> showWorkspacePersistenceFailure(detail));
                  } else {
                    completeOnEventThread(accepted, afterSuccessfulSave);
                  }
                  return accepted;
                },
                blockingIo);
  }

  private void showWorkspacePersistenceFailure(String detail) {
    if (!disposalStarted && isDisplayable()) {
      JOptionPane.showMessageDialog(
          this,
          detail + "\nYour in-memory workspace list is still available in this window.",
          "Workspaces not saved",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private void openWorkspace(WorkspaceProfile requested) {
    openWorkspace(requested, false, isDiscoveredWorkspace(requested));
  }

  private void openWorkspace(WorkspaceProfile requested, boolean forceReconnect) {
    boolean discovered =
        activeWorkspace != null && activeWorkspace.id().equals(requested.id())
            ? activeWorkspaceDiscovered
            : isDiscoveredWorkspace(requested);
    openWorkspace(requested, forceReconnect, discovered);
  }

  private void openWorkspace(
      WorkspaceProfile requested, boolean forceReconnect, boolean discovered) {
    if (launchController.isBusy() || workspaceContextTransition) {
      showWorkspaceHome();
      return;
    }
    WorkspaceProfile profile =
        workspaceManagerWindow
            ? requested.openedAt(System.currentTimeMillis() * 1_000L)
            : requested;
    if (workspaceManagerWindow) {
      if (discovered) {
        discoveredWorkspaceProfiles =
            discoveredWorkspaceProfiles.stream()
                .map(candidate -> candidate.id().equals(profile.id()) ? profile : candidate)
                .toList();
        refreshWorkspaceChoices();
      } else if (!upsertWorkspace(profile)) {
        return;
      }
    }
    WorkspaceProfile previousProfile = activeWorkspace;
    activeWorkspace = profile;
    activeWorkspaceDiscovered = discovered;
    updatePageWorkspace();
    launcherPanel.useManagedWorkspace(
        profile.label(),
        profile.kind() == WorkspaceProfile.Kind.LOCAL ? ExecutionHost.LOCAL : ExecutionHost.SSH,
        profile.workingDirectory(),
        profile.bazelExecutable(),
        profile.destination().orElse(""),
        profile.port().isPresent() ? Integer.toString(profile.port().getAsInt()) : "");
    launcherPanel.setRunEnabled(false);
    rootCardLayout.show(rootCards, ROOT_SHELL);
    showCard(NavEntry.BUILD);

    if (!forceReconnect
        && repositoryFileSystem != null
        && sameConnection(previousProfile, profile)) {
      workspaceStatus.setText("Workspace: ready — " + profile.label());
      launcherPanel.setRunEnabled(true);
      rebuildNavigation(true);
      return;
    }

    long wanted = ++workspaceConnectionGeneration;
    RemoteExecution previous = activeRemoteExecution;
    activeRemoteExecution = null;
    workspaceContextTransition = true;
    CompletionStage<Void> detached = clearExecutionViewsAsync(true);
    CompletableFuture<Void> oldExecutionClosed = closeRemoteAfter(previous, detached);
    workspaceStatus.setText("Workspace: connecting — " + profile.label());
    CompletableFuture<Void> transition = new CompletableFuture<>();
    oldExecutionClosed.whenComplete(
        (ignored, failure) ->
            completeOnEventThread(
                transition,
                () -> {
                  if (failure != null) {
                    log.warn("the previous Workspace execution did not close cleanly", failure);
                  }
                  if (!isCurrentWorkspace(profile, wanted)) {
                    workspaceContextTransition = false;
                    return;
                  }
                  workspaceContextTransition = false;
                  if (profile.kind() == WorkspaceProfile.Kind.LOCAL) {
                    connectLocalWorkspace(profile, wanted);
                  } else {
                    connectSshWorkspace(profile, wanted);
                  }
                }));
    workspaceConnectionOperations =
        CompletableFuture.allOf(workspaceConnectionOperations, transition);
  }

  static boolean sameConnection(WorkspaceProfile current, WorkspaceProfile requested) {
    return current != null
        && current.id().equals(requested.id())
        && current.kind() == requested.kind()
        && current.destination().equals(requested.destination())
        && current.port().equals(requested.port())
        && current.workingDirectory().equals(requested.workingDirectory());
  }

  /** The open window must accept an edit before the manager may save it. */
  static boolean applyWorkspaceUpdateBeforePersistence(
      WorkspaceProfile profile,
      Predicate<WorkspaceProfile> apply,
      Predicate<WorkspaceProfile> persist) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(apply, "apply");
    Objects.requireNonNull(persist, "persist");
    return apply.test(profile) && persist.test(profile);
  }

  private void connectLocalWorkspace(WorkspaceProfile profile, long wanted) {
    CompletableFuture<Void> installed = new CompletableFuture<>();
    CompletableFuture<Void> workerCompletion =
        CompletableFuture.runAsync(
            () -> {
              try {
                Path working = Path.of(profile.workingDirectory()).toAbsolutePath().normalize();
                if (!Files.isDirectory(working)) {
                  throw new IOException("the working directory does not exist: " + working);
                }
                WorkspaceInfo detected = WorkspaceDetector.detect(working);
                Path repository = detected.workspaceRoot().orElse(working).toRealPath();
                completeOnEventThread(
                    installed, () -> installLocalWorkspace(profile, wanted, working, repository));
              } catch (IOException | RuntimeException failure) {
                completeOnEventThread(
                    installed, () -> workspaceConnectionFailed(profile, wanted, failure));
              }
            },
            blockingIo);
    workerCompletion.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            installed.completeExceptionally(failure);
          }
        });
    workspaceConnectionOperations =
        CompletableFuture.allOf(workspaceConnectionOperations, installed);
  }

  private void installLocalWorkspace(
      WorkspaceProfile profile, long wanted, Path working, Path repository) {
    if (!isCurrentWorkspace(profile, wanted)) {
      return;
    }
    LocalExecutionFileSystem files =
        new LocalExecutionFileSystem("local-workspace-" + profile.id());
    repositoryFileSystem = files;
    captureLeaseKey = CaptureLeaseKey.localRealPath(repository);
    repositoryBrowserView.openRepository("This computer", files, files.path(repository));
    terminalView.bind("This computer", working.toString(), LocalCommandExecutor.INSTANCE);
    workspaceReady(profile);
  }

  private void connectSshWorkspace(WorkspaceProfile profile, long wanted) {
    CompletableFuture<Void> installed = new CompletableFuture<>();
    CompletableFuture<Void> workerCompletion =
        CompletableFuture.runAsync(
            () -> {
              RemoteExecution connected = null;
              try {
                OptionalInt port = profile.port();
                SshTarget target =
                    port.isPresent()
                        ? SshTarget.of(profile.destination().orElseThrow(), port.getAsInt())
                        : SshTarget.of(profile.destination().orElseThrow());
                connected =
                    RemoteExecution.connect(
                        target, profile.workingDirectory(), Duration.ofSeconds(30));
                if (disposalStarted) {
                  connected.close();
                  installed.complete(null);
                  return;
                }
                RemoteExecution completed = connected;
                connected = null;
                completeOnEventThread(
                    installed, () -> installRemoteWorkspace(profile, wanted, completed));
              } catch (IOException | InterruptedException | RuntimeException failure) {
                if (failure instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
                if (connected != null) {
                  connected.close();
                }
                completeOnEventThread(
                    installed, () -> workspaceConnectionFailed(profile, wanted, failure));
              }
            },
            blockingIo);
    workerCompletion.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            installed.completeExceptionally(failure);
          }
        });
    workspaceConnectionOperations =
        CompletableFuture.allOf(workspaceConnectionOperations, installed);
  }

  /** Completes only after a posted workspace transition has run on Swing's event thread. */
  static void completeOnEventThread(CompletableFuture<Void> completion, Runnable transition) {
    SwingUtilities.invokeLater(
        () -> {
          try {
            transition.run();
            completion.complete(null);
          } catch (Throwable failure) {
            completion.completeExceptionally(failure);
          }
        });
  }

  private void installRemoteWorkspace(
      WorkspaceProfile profile, long wanted, RemoteExecution remote) {
    if (!isCurrentWorkspace(profile, wanted)) {
      closeRemoteOffEdt(remote);
      return;
    }
    activeRemoteExecution = remote;
    try {
      repositoryFileSystem = remote.fileSystem();
      captureLeaseKey =
          CaptureLeaseKey.ssh(
              profile.destination().orElseThrow(), profile.port(), remote.repositoryRootText());
      repositoryBrowserView.openRepository(
          remote.displayName(), repositoryFileSystem, remote.repositoryRoot());
      terminalView.bind(remote.displayName(), remote.workingDirectory(), remote.commandExecutor());
      workspaceReady(profile);
      if (currentSource != null) {
        openEventsSession(currentSource);
      }
    } catch (IOException | RuntimeException failure) {
      activeRemoteExecution = null;
      closeRemoteOffEdt(remote);
      workspaceConnectionFailed(profile, wanted, failure);
    }
  }

  private void workspaceReady(WorkspaceProfile profile) {
    rebuildNavigation(true);
    launcherPanel.setRunEnabled(true);
    workspaceStatus.setText("Workspace: ready — " + profile.label());
    log.info(
        "workspace ready kind={} label={} workingDirectory={}",
        profile.kind(),
        profile.label(),
        profile.workingDirectory());
    if (nav.getSelectedValue() == NavEntry.TERMINAL) {
      terminalView.activate();
    }
  }

  private void workspaceConnectionFailed(WorkspaceProfile profile, long wanted, Throwable failure) {
    if (!isCurrentWorkspace(profile, wanted)) {
      return;
    }
    clearExecutionViewsAsync(false);
    launcherPanel.setRunEnabled(false);
    workspaceStatus.setText("Workspace: connection failed — " + profile.label());
    log.warn(
        "workspace connection failed kind={} label={} failureType={}",
        profile.kind(),
        profile.label(),
        failure.getClass().getSimpleName());
    JOptionPane.showMessageDialog(
        this,
        "The workspace could not be opened:\n"
            + describeFailure(failure)
            + "\n\nUse Workspaces ▸ Reconnect Current Workspace to try again.",
        "Cannot open workspace",
        JOptionPane.ERROR_MESSAGE);
  }

  private boolean isCurrentWorkspace(WorkspaceProfile profile, long wanted) {
    return !disposalStarted
        && wanted == workspaceConnectionGeneration
        && activeWorkspace != null
        && activeWorkspace.id().equals(profile.id());
  }

  private static String describeFailure(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private CompletionStage<Void> clearExecutionViewsAsync(boolean closeEditors) {
    repositoryFileSystem = null;
    captureLeaseKey = null;
    CompletionStage<Void> repository = repositoryBrowserView.clearRepositoryAsync();
    CompletionStage<Void> terminal = terminalView.clearBindingAsync();
    CompletionStage<Void> editors =
        closeEditors
            ? fileEditors.closeEditorsForContextChangeAsync()
            : CompletableFuture.completedFuture(null);
    CompletionStage<Void> eventFiles = eventsView.detachExecutionFileAccessAsync();
    rebuildNavigation(false);
    return executionContextReleased(repository, terminal, editors, eventFiles);
  }

  /** The old execution remains owned until every context-backed UI consumer settles. */
  @SafeVarargs
  static CompletionStage<Void> executionContextReleased(CompletionStage<Void>... consumers) {
    Objects.requireNonNull(consumers, "consumers");
    CompletableFuture<?>[] futures = new CompletableFuture<?>[consumers.length];
    for (int index = 0; index < consumers.length; index++) {
      futures[index] = Objects.requireNonNull(consumers[index], "consumer").toCompletableFuture();
    }
    return CompletableFuture.allOf(futures);
  }

  private void closeCurrentWorkspace(boolean showHome) {
    if (launchController.isBusy() || workspaceContextTransition) {
      showWorkspaceHome();
      return;
    }
    ++workspaceConnectionGeneration;
    activeWorkspace = null;
    activeWorkspaceDiscovered = false;
    updatePageWorkspace();
    launcherPanel.clearManagedWorkspace();
    launcherPanel.setRunEnabled(false);
    RemoteExecution previous = activeRemoteExecution;
    activeRemoteExecution = null;
    workspaceContextTransition = true;
    long wanted = workspaceConnectionGeneration;
    CompletableFuture<Void> closed = closeRemoteAfter(previous, clearExecutionViewsAsync(true));
    closed.whenComplete(
        (ignored, failure) ->
            SwingUtilities.invokeLater(
                () -> {
                  if (wanted == workspaceConnectionGeneration) {
                    workspaceContextTransition = false;
                  }
                  if (failure != null) {
                    log.warn("the closed Workspace execution did not shut down cleanly", failure);
                  }
                }));
    workspaceStatus.setText("Workspace: none");
    if (showHome) {
      refreshWorkspaceChoices();
      rootCardLayout.show(rootCards, ROOT_WORKSPACES);
    }
  }

  private void closeRemoteOffEdt(RemoteExecution remote) {
    if (remote == null) {
      return;
    }
    if (!SwingUtilities.isEventDispatchThread()) {
      remote.close();
      return;
    }
    CompletableFuture<Void> close;
    try {
      close = CompletableFuture.runAsync(remote::close, blockingIo);
    } catch (RejectedExecutionException rejected) {
      try {
        close = CompletableFuture.runAsync(remote::close, captureWorker);
      } catch (RejectedExecutionException alsoRejected) {
        // A connection can finish racing with the last disposal
        // callback after both owned executors stopped accepting work.
        // It still needs a bounded transport close; a one-shot virtual
        // thread cannot retain the Swing window or block the EDT.
        close = new CompletableFuture<>();
        CompletableFuture<Void> fallback = close;
        Thread.ofVirtual()
            .name("bbv-remote-close-fallback")
            .start(
                () -> {
                  try {
                    remote.close();
                    fallback.complete(null);
                  } catch (Throwable failure) {
                    fallback.completeExceptionally(failure);
                  }
                });
      }
    }
    remoteCloseOperations = CompletableFuture.allOf(remoteCloseOperations, close);
  }

  /** Closes an execution only after every old-context consumer has released it. */
  private CompletableFuture<Void> closeRemoteAfter(
      RemoteExecution remote, CompletionStage<Void> consumersClosed) {
    CompletionStage<Void> observed =
        consumersClosed.whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                log.warn("an old Workspace consumer did not release cleanly", failure);
              }
            });
    CompletableFuture<Void> close =
        remote == null
            ? observed.handle((ignored, failure) -> (Void) null).toCompletableFuture()
            : runAfterCompletion(observed, remote::close, blockingIo).toCompletableFuture();
    remoteCloseOperations = CompletableFuture.allOf(remoteCloseOperations, close);
    return close;
  }

  /** Compatibility path for a controller-created connection. */
  private void installRemoteExecution(RemoteExecution remote) {
    WorkspaceProfile profile = activeWorkspace;
    if (profile == null || profile.kind() != WorkspaceProfile.Kind.SSH) {
      closeRemoteOffEdt(remote);
      return;
    }
    installRemoteWorkspace(profile, workspaceConnectionGeneration, remote);
  }

  private void openEventsSession(SessionSource source) {
    liveRemoteFileAccess(source.info())
        .ifPresentOrElse(
            access -> eventsView.openSession(source, access, this::showSessionFailure),
            () -> eventsView.openSession(source, this::showSessionFailure));
  }

  /** Uses a recorded SSH path only through the matching explicit live connection. */
  private Optional<WorkspaceFileAccess> liveRemoteFileAccess(SessionInfo info) {
    RemoteExecution remote = activeRemoteExecution;
    if (remote == null || info.executionLocation().isEmpty()) {
      return Optional.empty();
    }
    try {
      if (!info.executionLocation().orElseThrow().equals(remote.provenance())) {
        return Optional.empty();
      }
      if (info.executionWorkingDirectory().filter(remote.workingDirectory()::equals).isEmpty()) {
        return Optional.empty();
      }
      if (info.executionWorkspaceRoot().isPresent()
          && !info.executionWorkspaceRoot().equals(remote.workspaceRoot())) {
        return Optional.empty();
      }
      ExecutionFileSystem files = remote.fileSystem();
      return Optional.of(
          new WorkspaceFileAccess(
              files,
              info.executionWorkspaceRoot().map(value -> pathUnchecked(files, value)),
              info.executionWorkingDirectory().map(value -> pathUnchecked(files, value))));
    } catch (RuntimeException failure) {
      log.warn(
          "the live SSH filesystem could not be matched to session {}", info.sessionId(), failure);
      return Optional.empty();
    }
  }

  private static ExecutionPath pathUnchecked(ExecutionFileSystem files, String value) {
    try {
      return files.path(value);
    } catch (IOException failure) {
      throw new IllegalArgumentException("invalid execution path: " + value, failure);
    }
  }

  private void closeActiveRemoteExecution() {
    RemoteExecution previous = activeRemoteExecution;
    activeRemoteExecution = null;
    closeRemoteOffEdt(previous);
  }

  /** Tells every view to let go, without closing the source. */
  private CompletionStage<Void> releaseViews() {
    List<CompletableFuture<Void>> closes = new ArrayList<>();
    closes.add(eventsView.closeSessionAsync().toCompletableFuture());
    closes.add(overviewPanel.closeSessionAsync().toCompletableFuture());
    closes.add(actionsView.closeSessionAsync().toCompletableFuture());
    closes.add(targetsView.closeSessionAsync().toCompletableFuture());
    closes.add(allTargetsView.closeSessionAsync().toCompletableFuture());
    closes.add(configurationsView.closeSessionAsync().toCompletableFuture());
    closes.add(testsView.closeSessionAsync().toCompletableFuture());
    closes.add(errorsView.closeSessionAsync().toCompletableFuture());
    closes.add(coverageView.closeSessionAsync().toCompletableFuture());
    closes.add(treeView.closeSessionAsync().toCompletableFuture());
    closes.add(graphExplorerView.closeSessionAsync().toCompletableFuture());
    closes.add(criticalPathView.closeSessionAsync().toCompletableFuture());
    closes.add(starlarkProfileView.closeSessionAsync().toCompletableFuture());
    closes.add(timeline.closeSessionAsync().toCompletableFuture());
    closes.add(queryView.closeSessionAsync().toCompletableFuture());
    findingsView.detach();
    derivedCriticalPath = List.of();
    MetricsService closing = metricsService;
    metricsService = null;
    if (closing != null) {
      // Invalidate a delivery already queued for the EDT before the
      // potentially blocking worker shutdown is handed to background I/O.
      closing.cancel();
      closes.add(closing.closeAsync().toCompletableFuture());
    }
    CompletableFuture<Void> close =
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new));
    viewCloseOperations = CompletableFuture.allOf(viewCloseOperations, close);
    return close;
  }

  /** Runs cleanup after a prerequisite settles, even when that prerequisite failed. */
  static CompletionStage<Void> runAfterCompletion(
      CompletionStage<Void> prerequisite, Runnable cleanup, Executor executor) {
    Objects.requireNonNull(prerequisite, "prerequisite");
    Objects.requireNonNull(cleanup, "cleanup");
    Objects.requireNonNull(executor, "executor");
    return prerequisite.handle((ignored, failure) -> null).thenRunAsync(cleanup, executor);
  }

  /** Schedules one source close after all views that used it have settled. */
  private CompletableFuture<Void> closeSourceAfter(
      SessionSource source, CompletionStage<Void> viewsClose) {
    if (source == null) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> close =
        viewsClose.thenRunAsync(source::close, blockingIo).toCompletableFuture();
    close.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            log.warn(
                "view cleanup did not finish; leaving its session source open rather"
                    + " than closing it beneath an active query",
                failure);
          }
        });
    sourceCloseOperations = CompletableFuture.allOf(sourceCloseOperations, close);
    return close;
  }

  /**
   * Closes a source once its views have released it.
   *
   * <p>On a background thread: the views' executors are shutting down at the same time, and closing
   * JDBC connections behind an in-flight query can block. The source itself is idempotent about
   * being closed twice.
   */
  private void closeSource(SessionSource source) {
    if (source == null) {
      return;
    }
    CompletableFuture<Void> close;
    try {
      close = CompletableFuture.runAsync(source::close, blockingIo);
    } catch (RejectedExecutionException rejected) {
      close = new CompletableFuture<>();
      CompletableFuture<Void> fallback = close;
      Thread.ofVirtual()
          .name("bbv-source-close-fallback")
          .start(
              () -> {
                try {
                  source.close();
                  fallback.complete(null);
                } catch (Throwable failure) {
                  fallback.completeExceptionally(failure);
                }
              });
    }
    sourceCloseOperations = CompletableFuture.allOf(sourceCloseOperations, close);
  }

  private void showSessionInfo(SessionInfo info) {
    sessionStatus.setText("Session: " + info.state() + (info.isPartial() ? " (partial)" : ""));
    OptionalLong count = info.manifestEventCount();
    eventStatus.setText("Events: " + EventValueFormat.count(count));
    actionStatus.setText("Actions: " + UNKNOWN);
    closeSessionItem.setEnabled(true);
    if (!info.warnings().isEmpty()) {
      log.info("session {} carries {} manifest warning(s)", info.root(), info.warnings().size());
    }
  }

  private void showSessionFailure(String message) {
    if (disposalStarted) {
      return;
    }
    eventsView.showEmpty("The session could not be opened.");
    sessionStatus.setText("Session: none");
    closeSessionItem.setEnabled(false);
    JOptionPane.showMessageDialog(this, message, "Cannot open session", JOptionPane.ERROR_MESSAGE);
  }

  private void closeSession() {
    SessionSource live = liveSource;
    liveSource = null;
    SessionSource closing = currentSource;
    currentSource = null;
    CompletionStage<Void> released = releaseViews();
    closeSourceAfter(live, released);
    if (closing != live) {
      closeSourceAfter(closing, released);
    }
    eventsView.showEmpty(
        "No session is open. Use File ▸ Open BEP File… or" + " File ▸ Open Session…");
    actionsView.showEmpty("No session is open.");
    targetsView.showEmpty("No session is open.");
    allTargetsView.showEmpty("No session is open.");
    testsView.showEmpty("No session is open.");
    errorsView.showEmpty("No session is open.");
    sessionStatus.setText("Session: none");
    eventStatus.setText("Events: " + UNKNOWN);
    actionStatus.setText("Actions: " + UNKNOWN);
    closeSessionItem.setEnabled(false);
  }

  /** Switches to the timeline and highlights an action, leaving the view where it is. */
  private void revealOnTimeline(long actionId) {
    showCard(NavEntry.TIMELINE);
    timeline.select(actionId);
  }

  /**
   * Points the timeline at whatever is picked on the graph canvas.
   *
   * <p>Quietly: the selection moves, the card does not. A view that jumped away every time a user
   * clicked a node would make the graph unusable, but arriving at the timeline already on the right
   * action is exactly what the user who does switch expects.
   */
  /**
   * Opens the view a finding's link names, in the state the link asks for.
   *
   * <p>The switch is exhaustive over {@code Link.Kind}, so a new kind of link added to a rule fails
   * to compile here rather than opening an unfiltered view that looks like it honoured the request.
   */
  private void followFindingLink(Finding.Link link) {
    NavEntry destination =
        switch (link.view()) {
          case ACTIONS -> NavEntry.ACTIONS;
          case TIMELINE -> NavEntry.TIMELINE;
          case GRAPH -> NavEntry.GRAPH;
          case TESTS -> NavEntry.TESTS;
          case FAILURES -> NavEntry.ERRORS;
          case COVERAGE, OVERVIEW -> NavEntry.OVERVIEW;
        };
    if (link.focusId().isPresent()) {
      long id = link.focusId().getAsLong();
      if (destination == NavEntry.GRAPH) {
        revealInGraph(id);
        return;
      }
      revealAction(id);
      return;
    }
    switch (link.kind()) {
      case MNEMONIC -> actionsView.applyFilter(link.value(), ActionSort.DURATION, true);
      case DERIVED_CRITICAL_PATH -> {
        openGraphOnDerivedPath();
        return;
      }
      case NONE -> {}
    }
    showCard(destination);
  }

  /**
   * Opens the graph with the derived dependency chain drawn on it.
   *
   * <p>Used by the Critical Path page and the finding that explicitly asks to draw the dependency
   * chain. Overview now opens the analysis page first.
   */
  private void openGraphOnDerivedPath() {
    showCard(NavEntry.GRAPH);
    graphExplorerView.showCriticalPath(derivedCriticalPath);
  }

  private void openFromOverview(NavEntry entry) {
    if (entry == NavEntry.GRAPH) {
      openGraphOnDerivedPath();
      return;
    }
    showCard(entry);
  }

  /**
   * The one place a shared navigation command becomes a card switch.
   *
   * <p>Exhaustive over {@link EntityActions.Command}, so a command added to the vocabulary fails to
   * compile here rather than silently going nowhere. The ref casts are safe by construction: {@code
   * Command.appliesTo} only ever pairs a command with a ref kind its arm expects — the two graph
   * arms accept both kinds it allows them, a target label or an action id, and take the branch that
   * matches.
   */
  private void navigate(EntityActions.Command command, EntityRef ref) {
    switch (command) {
      case OPEN_TARGET -> {
        showCard(NavEntry.TARGETS);
        targetsView.revealLabel(((EntityRef.TargetLabel) ref).label());
      }
      case VIEW_CONFIGURATION -> {
        showCard(NavEntry.CONFIGURATIONS);
        configurationsView.revealChecksum(((EntityRef.ConfigurationChecksum) ref).checksum());
      }
      case OPEN_BUILD_FILE -> openBuildFile(((EntityRef.TargetLabel) ref).label());
      case SHOW_ACTIONS_FOR_LABEL -> {
        showCard(NavEntry.ACTIONS);
        actionsView.filterToLabel(((EntityRef.TargetLabel) ref).label());
      }
      case REVEAL_ACTION -> revealAction(((EntityRef.ActionId) ref).id());
      // The Graph/Tree split, completed: the canvas card draws the
      // neighbourhood, the tree card roots its trees — one arm each,
      // exactly as the split's plan said. Each takes either identity,
      // because the Targets card sends a label where the Actions card
      // sends an action id, and both are nodes the graphs can find.
      case OPEN_IN_GRAPH -> {
        if (ref instanceof EntityRef.TargetLabel target) {
          revealLabelInGraph(target.label());
        } else {
          revealInGraph(((EntityRef.ActionId) ref).id());
        }
      }
      case OPEN_IN_TREE -> {
        if (ref instanceof EntityRef.TargetLabel target) {
          revealLabelInTree(target.label());
        } else {
          revealInTree(((EntityRef.ActionId) ref).id());
        }
      }
      case SHOW_ON_TIMELINE -> revealOnTimeline(((EntityRef.ActionId) ref).id());
      case SHOW_SOURCE_EVENT -> revealEvent(((EntityRef.EventId) ref).id());
      // Not in the wired set, so nothing can offer it and nothing can
      // arrive here; the arm exists so the switch stays exhaustive.
      case SHOW_EVENTS_FOR_LABEL -> log.warn("{} arrived unwired; nothing offers it", command);
    }
  }

  private void openBuildFile(String label) {
    SessionSource open = currentSource;
    if (open != null) {
      liveRemoteFileAccess(open.info())
          .ifPresentOrElse(
              access -> fileEditors.openBuildFile(label, access),
              () -> fileEditors.openBuildFile(label, open.info()));
    }
  }

  private void openFile(FileLink link) {
    SessionSource open = currentSource;
    if (open != null) {
      liveRemoteFileAccess(open.info())
          .ifPresentOrElse(
              access -> fileEditors.open(link, access), () -> fileEditors.open(link, open.info()));
    }
  }

  /** Opens a profile source hint through the same local/SSH-safe resolver as other files. */
  private void openStarlarkSource(StarlarkProfileReader.SourceLocation source) {
    String title =
        source.line().isPresent()
            ? "Starlark source · definition line " + source.line().getAsInt()
            : "Starlark source";
    openFile(new FileLink(title, source.path(), FileLink.Base.WORKSPACE, source.line()));
  }

  /** Shows one action in the actions table, selected. */
  private void revealAction(long actionId) {
    showCard(NavEntry.ACTIONS);
    actionsView.revealAction(actionId);
  }

  private void followGraphSelection(long actionId) {
    timeline.select(actionId);
  }

  /** Draws an action's neighbourhood on the Graph card's canvas. */
  private void revealInGraph(long actionId) {
    showCard(NavEntry.GRAPH);
    graphExplorerView.showAction(actionId);
  }

  /** Roots the Tree card's dependency trees at an action. */
  private void revealInTree(long actionId) {
    showCard(NavEntry.TREE);
    treeView.showAction(actionId);
  }

  /**
   * Draws a target's neighbourhood on the Graph card's canvas.
   *
   * <p>By exact label ({@code GraphQueries.nodeForLabel}), not the Find field's substring: a jump
   * to {@code //app:server} that drew {@code //app:server_lib} would be indistinguishable from a
   * correct one.
   */
  private void revealLabelInGraph(String label) {
    showCard(NavEntry.GRAPH);
    graphExplorerView.showLabel(label);
  }

  /** Roots the Tree card's dependency trees at a target, by exact label. */
  private void revealLabelInTree(String label) {
    showCard(NavEntry.TREE);
    treeView.showLabel(label);
  }

  private void revealEvent(long eventId) {
    showEventsCard();
    if (!eventsView.revealEvent(eventId)) {
      eventsView.showEmpty("The events view is not ready yet.");
    }
  }

  private void showEventsCard() {
    nav.setSelectedValue(NavEntry.EVENTS, true);
    cardLayout.show(cards, NavEntry.EVENTS.cardName());
  }

  // --------------------------------------------------------------- capturing

  /**
   * Preflights the launcher's command and shows the plan.
   *
   * <p>Never launches directly. The dialog is what launches, because the user has to see the
   * effective command first (ADR-007) — and because a mandatory conflict has to be answered by a
   * person, not defaulted past.
   */
  private void startLaunch() {
    if (disposalStarted) {
      return;
    }
    if (launchController.isBusy()) {
      JOptionPane.showMessageDialog(
          this,
          "A build is already running. Cancel it before starting another.",
          "Capture in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    String typed = launcherPanel.command().strip();
    if (typed.isEmpty()) {
      JOptionPane.showMessageDialog(
          this,
          "Enter a Bazel command, such as: test //...",
          "Nothing to run",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    WorkspaceProfile workspace = activeWorkspace;
    if (workspace == null || repositoryFileSystem == null) {
      JOptionPane.showMessageDialog(
          this,
          "Choose and connect a workspace before running a Bazel command.",
          "No workspace",
          JOptionPane.INFORMATION_MESSAGE);
      showWorkspaceHome();
      return;
    }
    String workingDirectoryText = workspace.workingDirectory();
    String executable = launcherPanel.bazelExecutable().strip();
    List<String> arguments = CommandLineParser.tokenize(typed);
    CaptureRequest request;
    if (workspace.kind() == WorkspaceProfile.Kind.SSH) {
      RemoteExecution remote = activeRemoteExecution;
      if (remote == null) {
        JOptionPane.showMessageDialog(
            this,
            "The SSH workspace is not connected. Reconnect it and try again.",
            "Workspace disconnected",
            JOptionPane.ERROR_MESSAGE);
        return;
      }
      try {
        OptionalInt port = workspace.port();
        SshTarget target =
            port.isPresent()
                ? SshTarget.of(workspace.destination().orElseThrow(), port.getAsInt())
                : SshTarget.of(workspace.destination().orElseThrow());
        request =
            CaptureRequest.remote(
                    sessionsRoot, APP_VERSION, executable, workingDirectoryText, arguments, target)
                .withConnectedRemote(remote);
      } catch (IllegalArgumentException invalid) {
        JOptionPane.showMessageDialog(
            this, invalid.getMessage(), "Cannot connect", JOptionPane.ERROR_MESSAGE);
        return;
      }
    } else {
      Path workingDirectory;
      try {
        workingDirectory = Path.of(workingDirectoryText).toAbsolutePath().normalize();
      } catch (InvalidPathException bad) {
        JOptionPane.showMessageDialog(
            this,
            "That is not a usable directory: " + workingDirectoryText,
            "Cannot launch",
            JOptionPane.ERROR_MESSAGE);
        return;
      }
      request =
          CaptureRequest.of(sessionsRoot, APP_VERSION, executable, workingDirectory, arguments);
    }
    request = request.withPreset(launcherPanel.preset());

    if (!acquireCaptureLease(workspace)) {
      return;
    }

    launcherPanel.rememberCommand(typed);
    launcherPanel.setRunEnabled(false);
    setCaptureStatus(
        captureStatus.withPhase(
            CaptureStatusModel.Phase.PREPARING, "Resolving Bazel and probing capabilities…"));
    showCard(NavEntry.BUILD);
    try {
      launchController.preflight(request);
    } catch (RuntimeException failure) {
      releaseCaptureLease();
      launcherPanel.setRunEnabled(true);
      setCaptureStatus(
          captureStatus.withPhase(CaptureStatusModel.Phase.FAILED, describeFailure(failure)));
      log.warn("capture preflight could not be queued", failure);
      JOptionPane.showMessageDialog(
          this, describeFailure(failure), "Cannot capture", JOptionPane.ERROR_MESSAGE);
    }
  }

  /** Reserves this canonical repository until its accepted coordinator closes. */
  private boolean acquireCaptureLease(WorkspaceProfile workspace) {
    if (applicationHost == null) {
      return true;
    }
    CaptureLeaseKey key = captureLeaseKey;
    if (key == null) {
      JOptionPane.showMessageDialog(
          this,
          "The workspace repository identity is not ready. Reconnect and try again.",
          "Workspace not ready",
          JOptionPane.ERROR_MESSAGE);
      return false;
    }
    CaptureLeaseRegistry.Acquisition acquisition =
        applicationHost.tryAcquireCaptureLease(
            key, new CaptureLeaseOwner(workspace.id(), workspace.label()));
    if (acquisition instanceof CaptureLeaseRegistry.Conflict conflict) {
      CaptureLeaseOwner owner = conflict.activeLease().owner();
      JOptionPane.showMessageDialog(
          this,
          "‘"
              + owner.displayName()
              + "’ is already capturing this repository. "
              + "Finish or cancel that capture before starting another one.",
          "Repository capture in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    CaptureLeaseRegistry.CaptureLease lease = ((CaptureLeaseRegistry.Granted) acquisition).lease();
    if (!activeCaptureLease.compareAndSet(null, lease)) {
      lease.close();
      JOptionPane.showMessageDialog(
          this,
          "This workspace already owns a capture reservation.",
          "Capture in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    return true;
  }

  /** Called on the capture worker after tunnels, listeners, and staging close. */
  private void releaseCaptureLease() {
    CaptureLeaseRegistry.CaptureLease lease = activeCaptureLease.getAndSet(null);
    if (lease != null) {
      lease.close();
    }
  }

  /** Validates and persists a Console edit without reconnecting the Workspace. */
  private boolean updateManagedBazelExecutable(String executable) {
    WorkspaceProfile current = activeWorkspace;
    if (disposalStarted || current == null) {
      return false;
    }
    if (launchController.isBusy()) {
      JOptionPane.showMessageDialog(
          this,
          "Finish or cancel the running Bazel command before changing its executable.",
          "Build in progress",
          JOptionPane.INFORMATION_MESSAGE);
      return false;
    }
    WorkspaceProfile replacement;
    try {
      replacement = current.withBazelExecutable(executable);
    } catch (IllegalArgumentException invalid) {
      JOptionPane.showMessageDialog(
          this, invalid.getMessage(), "Invalid Bazel command", JOptionPane.ERROR_MESSAGE);
      return false;
    }
    if (applicationHost != null) {
      return applicationHost.workspaceUpdated(replacement);
    }
    activeWorkspace = replacement;
    updatePageWorkspace();
    if (activeWorkspaceDiscovered) {
      discoveredWorkspaceProfiles =
          discoveredWorkspaceProfiles.stream()
              .map(candidate -> candidate.id().equals(replacement.id()) ? replacement : candidate)
              .toList();
      refreshWorkspaceChoices();
      return true;
    }
    return upsertWorkspace(replacement);
  }

  private void chooseWorkingDirectory() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Working directory for the build");
    String current = launcherPanel.workspace().strip();
    if (!current.isEmpty()) {
      File asFile = new File(current);
      if (asFile.isDirectory()) {
        chooser.setCurrentDirectory(asFile);
      }
    }
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      launcherPanel.setWorkspace(chooser.getSelectedFile().getAbsolutePath());
    }
  }

  private void setCaptureStatus(CaptureStatusModel model) {
    this.captureStatus = model;
    capturePanel.show(model);
    updateConsoleMetadata(model);
  }

  private void showCard(NavEntry entry) {
    nav.setSelectedValue(entry, true);
    cardLayout.show(cards, entry.cardName());
  }

  /** Receives the capture's progress, always on the EDT. */
  private final class CaptureListener implements LaunchController.Listener {

    @Override
    public void remoteConnected(RemoteExecution remote) {
      installRemoteExecution(remote);
    }

    @Override
    public void planReady(Preflight preflight) {
      InstrumentationPlanDialog dialog = new InstrumentationPlanDialog(MainWindow.this, preflight);
      dialog.setVisible(true);
      switch (dialog.choice()) {
        case LAUNCH -> {
          consoleView.clear();
          setCaptureStatus(
              captureStatus.withPhase(
                  CaptureStatusModel.Phase.WAITING,
                  "Listening on "
                      + preflight
                          .remote()
                          .map(Preflight.RemoteDetails::remoteBesBackend)
                          .orElseGet(() -> preflight.endpoint().besBackendUri())));
          launchController.launch();
        }
        case RESOLVE -> {
          PlanConflict.Kind kind = dialog.resolvedKind().orElseThrow();
          // Re-planned rather than patched: the plan is a value, and
          // the dialog reopens showing what the answer actually
          // changed instead of asserting that it worked.
          launchController.resolve(kind, dialog.resolutionId().orElseThrow());
        }
        case VETO -> {
          // Re-planned, not patched: the dialog reopens showing what
          // the veto actually changed rather than asserting it worked.
          launchController.replan(
              request -> request.vetoing(dialog.vetoedCapability().orElseThrow()));
        }
        case CANCEL -> {
          launchController.discardPlan();
          launcherPanel.setRunEnabled(true);
          setCaptureStatus(CaptureStatusModel.idle());
        }
      }
    }

    /**
     * Opens the running capture's session for the overview, once.
     *
     * <p>Driven from the progress tick rather than from a new callback because the session
     * directory does not exist when the capture starts and does by the time the first events are
     * counted. A tick that arrives too early simply finds nothing and the next one tries again.
     */
    private void attachLiveOverview() {
      if (liveSource != null) {
        return;
      }
      Path root = launchController.current().flatMap(CaptureCoordinator::sessionRoot).orElse(null);
      if (root == null) {
        return;
      }
      executeWorker(
          () -> {
            try {
              SessionSource opened = openProtectedSession(root);
              SwingUtilities.invokeLater(
                  () -> {
                    if (disposalStarted || liveSource != null || !launchController.isBusy()) {
                      // A later tick won the race, or the capture ended
                      // while this was opening and the real source is
                      // about to arrive.
                      closeSource(opened);
                      return;
                    }
                    liveSource = opened;
                    overviewPanel.openSession(opened);
                    // The timeline follows the build too. It is the view
                    // where "live" is most of the point -- watching a build
                    // fill in is the reason to have one open while it runs.
                    // Live, so it shows the in-flight target band and its
                    // right edge advances with the wall clock; the finished
                    // session that replaces this one via installSession()
                    // opens without the flag and turns both off.
                    timeline.openSession(opened, true);
                    // So does the Events tab: previously it opened nothing
                    // until installSession() ran after captureFinished, so
                    // it showed nothing at all while a build was running.
                    // It keeps its own ticker (EventsView.startTicker),
                    // exactly like the timeline's, so it stays current even
                    // on a quiet build with no progress ticks.
                    openEventsSession(opened);
                  });
            } catch (Exception notYet) {
              if (notYet instanceof InterruptedException) {
                Thread.currentThread().interrupt();
              }
              // The manifest or the database is still being written. The
              // next progress tick tries again; there is nothing to
              // report, because nothing is wrong.
              log.debug("the capture's session is not readable yet", notYet);
            }
          });
    }

    @Override
    public void captureStarted(Preflight preflight) {
      setCaptureStatus(
          captureStatus.withPhase(
              CaptureStatusModel.Phase.CAPTURING,
              String.join(" ", preflight.plan().effective().userVisibleArgs())));
      // No card change. The status and the console are one card now, so
      // the user who navigated elsewhere while the plan dialog was up
      // stays where they went; startLaunch already put the Console card up
      // for the user who did not.
    }

    @Override
    public void captureProgress(CaptureProgress progress) {
      setCaptureStatus(captureStatus.withProgress(progress));
      eventStatus.setText("Events: " + EventValueFormat.count(progress.normalized()));
      attachLiveOverview();
      // Retroactive insertion (plan 14.4): an action that arrives after
      // it completed is inserted at its own timestamps and its bins
      // updated. The viewport is not touched -- TimelineViewport.withWall
      // moves a following view and leaves a navigated one where the user
      // put it.
      timeline.refreshLive();
      // A fast-path nudge on top of the Events tab's own ticker, exactly
      // as above for the timeline: harmless when the ticker already beat
      // it there, because both share refreshLive's own throttle.
      eventsView.refreshLive();
    }

    @Override
    public void consoleOutput(
        ConsoleSink.ConsoleStream stream, byte[] data, int offset, int length) {
      consoleView.append(data, offset, length);
    }

    @Override
    public void captureFinished(CaptureResult result) {
      launcherPanel.setRunEnabled(true);
      CaptureStatusModel.Phase phase =
          result.wasCancelled()
              ? CaptureStatusModel.Phase.CANCELLED
              : result.captureComplete()
                  ? CaptureStatusModel.Phase.DONE
                  : CaptureStatusModel.Phase.FAILED;
      setCaptureStatus(captureStatus.withPhase(phase, describe(result)));
      // Opened whatever the outcome: a cancelled or partial capture is
      // still a session, and being able to look at it is the point. The
      // live source remains attached until installSession can atomically
      // detach all live readers and order its close behind their stages.
      openSessionDirectory(result.sessionRoot(), false);
    }

    @Override
    public void captureFailed(Throwable failure) {
      launcherPanel.setRunEnabled(true);
      setCaptureStatus(
          captureStatus.withPhase(
              CaptureStatusModel.Phase.FAILED, String.valueOf(failure.getMessage())));
      log.warn("the capture could not run", failure);
      JOptionPane.showMessageDialog(
          MainWindow.this,
          failure.getMessage() == null ? failure.toString() : failure.getMessage(),
          "Cannot capture",
          JOptionPane.ERROR_MESSAGE);
    }

    private String describe(CaptureResult result) {
      StringBuilder text = new StringBuilder();
      result
          .process()
          .ifPresent(
              process ->
                  text.append(
                          switch (result.buildOutcome()) {
                            case NOT_STARTED -> "build not started";
                            case CANCELLED -> "build cancelled";
                            case UNKNOWN_PROCESS ->
                                "build outcome unknown (process did not finish)";
                            case UNKNOWN_BES_TRANSPORT ->
                                "build outcome unknown (event transport or drain failed)";
                            case SUCCEEDED -> "build succeeded";
                            case FAILED -> "build failed";
                          })
                      .append(" · "));
      result
          .capture()
          .ifPresent(
              capture ->
                  text.append(capture.isComplete() ? "capture complete" : "capture incomplete")
                      .append(" · ")
                      .append(capture.normalized())
                      .append(" events indexed"));
      // The discrepancies and warnings are the reason an incomplete
      // capture is incomplete. Showing "capture incomplete" without them
      // tells the user something is wrong and nothing about what.
      List<String> problems =
          new ArrayList<>(result.capture().map(CaptureSummary::discrepancies).orElse(List.of()));
      problems.addAll(result.warnings());
      if (!problems.isEmpty()) {
        text.append(" — ").append(String.join("; ", problems));
      }
      return text.toString();
    }
  }

  // ------------------------------------------------------------------ shell

  private JComponent buildNavigation() {
    rebuildNavigation(false);
    nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    nav.setToolTipText(
        "Ctrl+Tab moves to the next page; Ctrl+Shift+Tab moves to the previous page.");
    nav.getAccessibleContext()
        .setAccessibleDescription(
            "Application pages. Ctrl+Tab moves down and Ctrl+Shift+Tab moves up, wrapping at"
                + " either end.");
    nav.setCellRenderer(
        new DefaultListCellRenderer() {
          private static final long serialVersionUID = 1L;

          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(
                list, ((NavEntry) value).title(), index, isSelected, cellHasFocus);
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            return this;
          }
        });
    nav.addListSelectionListener(
        event -> {
          if (event.getValueIsAdjusting()) {
            return;
          }
          NavEntry selected = nav.getSelectedValue();
          if (selected != null) {
            log.trace("showing navigation card {}", selected.cardName());
            cardLayout.show(cards, selected.cardName());
            if (selected == NavEntry.ALL_TARGETS) {
              allTargetsView.activate();
            } else if (selected == NavEntry.CONFIGURATIONS) {
              configurationsView.activate();
            } else if (selected == NavEntry.TERMINAL) {
              terminalView.activate();
            }
          }
        });
    nav.setSelectedIndex(0);
    return new JScrollPane(nav);
  }

  /** Sidebar entries for the selected workspace; imported sessions never imply one. */
  static List<NavEntry> visibleNavigation(boolean workspaceSelected) {
    return Arrays.stream(NavEntry.values())
        .filter(entry -> workspaceSelected || entry != NavEntry.TERMINAL)
        .toList();
  }

  private void rebuildNavigation(boolean workspaceSelected) {
    NavEntry selected = nav.getSelectedValue();
    navModel.clear();
    for (NavEntry entry : visibleNavigation(workspaceSelected)) {
      navModel.addElement(entry);
    }
    if (selected != null && visibleNavigation(workspaceSelected).contains(selected)) {
      nav.setSelectedValue(selected, true);
    } else if (!navModel.isEmpty()) {
      nav.setSelectedIndex(0);
    }
  }

  /** The real view and common page toolbar for every navigation entry. */
  private JComponent cardFor(NavEntry entry) {
    JComponent body =
        switch (entry) {
          case OVERVIEW -> overviewCard;
          case ACTIONS -> actionsView;
          case TARGETS -> targetsView;
          case ALL_TARGETS -> allTargetsView;
          case CONFIGURATIONS -> configurationsView;
          case TESTS -> testsView;
          case ERRORS -> errorsView;
          case GRAPH -> graphExplorerView;
          case TREE -> treeView;
          case TIMELINE -> timeline.view();
          case CRITICAL_PATH -> criticalPathView;
          case STARLARK_PROFILE -> starlarkProfileView;
          case EVENTS -> eventsView;
          case BUILD -> buildCard;
          case FINDINGS -> findingsView;
          case QUERY -> queryView;
          case REPOSITORY -> repositoryBrowserView;
          case TERMINAL -> terminalView;
        };
    return pageCard(entry, body, pageToolbars);
  }

  /** Builds every toolbar from the same exhaustive navigation registry. */
  static EnumMap<NavEntry, PageToolbar> newPageToolbars() {
    EnumMap<NavEntry, PageToolbar> toolbars = new EnumMap<>(NavEntry.class);
    for (NavEntry entry : NavEntry.values()) {
      toolbars.put(entry, new PageToolbar(entry.title()));
    }
    return toolbars;
  }

  /** Wraps page chrome outside the body's scroll, empty, loading, and import decks. */
  static JComponent pageCard(NavEntry entry, JComponent body, Map<NavEntry, PageToolbar> toolbars) {
    PageToolbar toolbar =
        Objects.requireNonNull(toolbars.get(Objects.requireNonNull(entry, "entry")), "toolbar");
    JPanel card = new JPanel(new BorderLayout());
    card.add(toolbar, BorderLayout.NORTH);
    card.add(Objects.requireNonNull(body, "body"), BorderLayout.CENTER);
    return card;
  }

  /** Gives migrated views their existing root-level controls; later tasks use the same API. */
  private void installPageChrome() {
    installPageChrome(NavEntry.TERMINAL, terminalView);
    installPageChrome(NavEntry.REPOSITORY, repositoryBrowserView);
    installPageChrome(NavEntry.OVERVIEW, overviewPanel);
    installPageChrome(NavEntry.TIMELINE, timeline.view());
    installPageChrome(NavEntry.ACTIONS, actionsView);
    installPageChrome(NavEntry.TARGETS, targetsView);
    installPageChrome(NavEntry.ALL_TARGETS, allTargetsView);
    installPageChrome(NavEntry.TESTS, testsView);
    installPageChrome(NavEntry.CRITICAL_PATH, criticalPathView);
    installPageChrome(NavEntry.STARLARK_PROFILE, starlarkProfileView);
    installPageChrome(NavEntry.CONFIGURATIONS, configurationsView);
    installPageChrome(NavEntry.TREE, treeView);
    installPageChrome(NavEntry.GRAPH, graphExplorerView);
    installPageChrome(NavEntry.FINDINGS, findingsView);

    PageToolbar console = pageToolbars.get(NavEntry.BUILD);
    launcherPanel.installPageToolbar(console);
    capturePanel.installPageToolbar(console);
    console.setControls(launcherPanel);
    updateConsoleMetadata(captureStatus);
  }

  private void installPageChrome(NavEntry entry, PageChrome chrome) {
    chrome.installPageToolbar(pageToolbars.get(entry));
  }

  private void updatePageWorkspace() {
    String workspaceName = activeWorkspace == null ? "" : activeWorkspace.label();
    updatePageWorkspace(pageToolbars, workspaceName);
  }

  /** Applies only live workspace identity; imported-session provenance is deliberately excluded. */
  static void updatePageWorkspace(Map<NavEntry, PageToolbar> toolbars, String workspaceName) {
    for (PageToolbar toolbar : toolbars.values()) {
      toolbar.setWorkspaceName(workspaceName);
    }
  }

  private void updateConsoleMetadata(CaptureStatusModel model) {
    String counters = model.counterLine().strip();
    String concise = model.phase().label() + (counters.isEmpty() ? "" : " · " + counters);
    String detail = model.detail().isBlank() ? concise : concise + " — " + model.detail();
    pageToolbars.get(NavEntry.BUILD).setMetadata(concise, detail);
  }

  /** Stacks the build summary above the coverage panel, split and resizable. */
  private JComponent buildOverviewCard() {
    return overviewSections(overviewPanel, coverageView);
  }

  /**
   * Gives the Overview split two visible, named regions.
   *
   * <p>Package-private so the boundary can be checked headlessly without constructing this {@link
   * JFrame}.
   */
  static JSplitPane overviewSections(JComponent summary, JComponent coverage) {
    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new SectionPane("Build summary", summary),
            new SectionPane("Coverage & enrichment", coverage));
    split.setResizeWeight(0.62);
    split.setBorder(null);
    return split;
  }

  /** Keeps the build transcript below the window-owned Console toolbar. */
  private JComponent buildBuildCard() {
    JPanel card = new JPanel(new BorderLayout());
    card.add(new SectionPane("Build output", consoleView), BorderLayout.CENTER);
    return card;
  }

  private JComponent buildStatusBar() {
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
    bar.add(workspaceStatus);
    bar.add(sessionStatus);
    bar.add(eventStatus);
    bar.add(actionStatus);

    JPanel south = new JPanel(new BorderLayout());
    south.add(new JSeparator(), BorderLayout.NORTH);
    south.add(bar, BorderLayout.CENTER);
    return south;
  }
}

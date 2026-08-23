package com.holtherndon.bazelviz.ui;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportOutcome;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.capture.file.importer.UnsupportedSourceException;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.CaptureSummary;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.ui.capture.CapturePanel;
import com.holtherndon.bazelviz.ui.capture.CaptureStatusModel;
import com.holtherndon.bazelviz.ui.capture.ConsoleView;
import com.holtherndon.bazelviz.ui.capture.InstrumentationPlanDialog;
import com.holtherndon.bazelviz.ui.capture.LaunchController;
import com.holtherndon.bazelviz.ui.events.EventValueFormat;
import com.holtherndon.bazelviz.ui.actions.ActionsView;
import com.holtherndon.bazelviz.ui.events.EventsView;
import com.holtherndon.bazelviz.ui.enrich.CoverageView;
import com.holtherndon.bazelviz.ui.failures.FailuresView;
import com.holtherndon.bazelviz.ui.graph.GraphView;
import com.holtherndon.bazelviz.ui.timeline.TimelineController;
import com.holtherndon.bazelviz.ui.overview.OverviewPanel;
import com.holtherndon.bazelviz.ui.targets.TargetsView;
import com.holtherndon.bazelviz.ui.tests.TestsView;
import com.holtherndon.bazelviz.ui.nav.NavEntry;
import com.holtherndon.bazelviz.ui.session.ImportController;
import com.holtherndon.bazelviz.ui.session.ImportProgressModel;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application window shell (plan section 17.1). The frame regions —
 * launcher bar, navigation sidebar, card-switched center, status bar — were
 * laid out as placeholders in Phase 0; each gains its backing service in the
 * phase noted on its card.
 *
 * <p>Phase 1 replaces the Events placeholder with a working card: open a BEP
 * file, watch it import, scroll the chronological event table, inspect the raw
 * protobuf of the selected event, and reopen an indexed session without
 * re-importing it. The other placeholders are untouched.
 *
 * <p>Nothing in this class does file, database or parsing work on the EDT. The
 * menu actions hand paths to {@link ImportController} and to a worker executor;
 * the table and inspector own their own executors inside {@link EventsView}.
 */
public final class MainWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    /**
     * Recorded into every session this window creates. Duplicated from the
     * application module's {@code AppInfo} because {@code :app} depends on this
     * module and not the other way round, so the version cannot be imported
     * from there.
     */
    private static final String APP_VERSION = "0.1.0-SNAPSHOT";

    // Unknown-is-not-zero: counts are unknown until a session exists, so the
    // status bar shows an em dash, never "0".
    private static final String UNKNOWN = EventValueFormat.UNKNOWN;

    private final Path sessionsRoot;
    private final SessionManager sessions;
    private final EventsView eventsView = new EventsView();
    private final OverviewPanel overviewPanel = new OverviewPanel();
    private final ActionsView actionsView = new ActionsView();
    private final TargetsView targetsView = new TargetsView();
    private final TestsView testsView = new TestsView();
    private final FailuresView failuresView = new FailuresView();
    private final CoverageView coverageView = new CoverageView();
    private final GraphView graphView = new GraphView();
    private final TimelineController timeline = new TimelineController();

    /**
     * The Overview card: the build's own summary above, what is known about it
     * below.
     *
     * <p>Plan 17.1 fixes the left navigation at eleven entries and coverage is
     * not one of them, so Phase 4's data-coverage panel, phase overview and
     * enrichment-task status share this card rather than taking a twelfth.
     * They belong together anyway: "this build ran 12,000 actions" and "4 of
     * them have attempt data, because the rest never spawned a subprocess" are
     * halves of one answer.
     */
    private final JComponent overviewCard = buildOverviewCard();

    /**
     * The open session, owned here rather than by any one view.
     *
     * <p>Six views read it. If each closed the source when it was torn down,
     * the first would take the database out from under the other five, so the
     * window opens it once, hands it round, and closes it once.
     */
    private SessionSource currentSource;

    /**
     * A read-only view of the session a capture is writing right now.
     *
     * <p>Separate from {@link #currentSource}, which is the session the user
     * opened. The overview watches this one while the build runs — the Phase 3
     * "Live overview" deliverable — and it is closed and replaced by the real
     * source when the capture finishes.
     */
    private SessionSource liveSource;
    private final ExecutorService worker;
    private final ExecutorService captureWorker;
    private final ImportController importController;

    private final JLabel sessionStatus = new JLabel("Session: none");
    private final JLabel eventStatus = new JLabel("Events: " + UNKNOWN);
    private final JLabel actionStatus = new JLabel("Actions: " + UNKNOWN);
    private final JMenuItem cancelImportItem = new JMenuItem("Cancel Import");
    private final JMenuItem closeSessionItem = new JMenuItem("Close Session");
    private final JList<NavEntry> nav = new JList<>(NavEntry.values());

    private final JComboBox<CapturePreset> presetChoice = new JComboBox<>();
    private final JTextField commandField = new JTextField();
    private final JTextField workspaceField = new JTextField();
    private final JButton runButton = new JButton("Run");
    private final CapturePanel capturePanel = new CapturePanel();
    private final ConsoleView consoleView = new ConsoleView();
    private final LaunchController launchController;
    private CaptureStatusModel captureStatus = CaptureStatusModel.idle();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private Path lastChooserDirectory;

    /**
     * @param sessionsRoot directory imported sessions are created in; resolving
     *     it is pure path arithmetic, and nothing is created until an import
     *     actually runs
     */
    public MainWindow(Path sessionsRoot) {
        super("Bazel Build Visualizer");
        this.sessionsRoot = sessionsRoot;
        this.sessions = new SessionManager(sessionsRoot, APP_VERSION);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-import");
            thread.setDaemon(true);
            return thread;
        });
        this.importController = new ImportController(
                new BepImporter(sessions), worker, SwingUtilities::invokeLater,
                new ImportProgressModel());
        // A capture blocks its worker for the whole build, so it gets its own
        // thread rather than sharing the import worker: a running build must
        // not make "open a session" queue behind it.
        this.captureWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-capture");
            thread.setDaemon(true);
            return thread;
        });
        this.launchController = new LaunchController(
                captureWorker, SwingUtilities::invokeLater, new CaptureListener());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1280, 840);
        setLocationByPlatform(true);

        for (NavEntry entry : NavEntry.values()) {
            cards.add(cardFor(entry), entry.cardName());
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildNavigation(), cards);
        split.setDividerLocation(180);
        split.setResizeWeight(0);

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildLauncherBar(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);
        setJMenuBar(buildMenuBar());

        eventsView.progressPanel().setCancelAction(importController::cancel);

        // Every entity view's inspector can jump to the bytes its row came
        // from. One handler, so the behaviour is the same from all five.
        actionsView.onShowSourceEvent(this::revealEvent);
        actionsView.onShowInGraph(this::revealInGraph);
        // Selection synchronisation, both ways (plan 17.8). Neither direction
        // moves the other's viewport: a row selected in the table highlights
        // its span where it is, and a span picked on the timeline reveals its
        // row without scrolling the timeline.
        actionsView.onShowOnTimeline(this::revealOnTimeline);
        graphView.onActionSelected(this::followGraphSelection);
        timeline.onSelection(actionId -> actionsView.selectAction(actionId));
        // A range dragged out on the timeline narrows the actions table (plan
        // 14.5). Cleared the same way, so the two never disagree about what is
        // being shown.
        timeline.onRangeChanged((from, to) -> {
            actionsView.filterToRange(from, to);
            if (from.isPresent()) {
                showCard(NavEntry.ACTIONS);
            }
        });
        // The status bar's counts come from the overview's own read, so the two
        // can never disagree about how many actions the session holds.
        overviewPanel.onSnapshot(snapshot -> actionStatus.setText(
                "Actions: " + EventValueFormat.count(snapshot.actions())));
        targetsView.onShowSourceEvent(this::revealEvent);
        testsView.onShowSourceEvent(this::revealEvent);
        failuresView.onShowSourceEvent(this::revealEvent);
        // The manifest's event count is absent for a session whose import never
        // finished; the database always knows, so the status bar takes the real
        // number from the view once it is open rather than keeping an em dash
        // for a count that is in fact available.
        eventsView.setRowCountListener(count -> eventStatus.setText("Events: "
                + EventValueFormat.count(count)));
        capturePanel.setStopAction(launchController::cancel);
        cancelImportItem.setEnabled(false);
        closeSessionItem.setEnabled(false);
    }

    /** The directory imported sessions are written into. */
    public Path sessionsRoot() {
        return sessionsRoot;
    }

    @Override
    public void dispose() {
        // Every view, not just the events one: since this window took ownership
        // of the session source, closing only one view left the other five
        // holding executors and JDBC connections, and left the source open.
        releaseViews();
        SessionSource closing = currentSource;
        currentSource = null;
        closeSource(closing);
        // Only a plan that was never launched is discarded here, and only
        // because that releases its BES port. Discarding unconditionally meant
        // closing the window during a build called BesServer.close() on the
        // EDT, which waits out its ten-second graceful-shutdown budget with the
        // stream still open -- a frozen, unrepainted window -- and then killed
        // the live stream. The shutdownNow() below then interrupted the capture
        // thread mid-finalization, which is what discards journal frames that
        // were already acknowledged to Bazel.
        if (!launchController.isBusy()) {
            launchController.discardPlan();
        } else {
            // A capture is running. Ask it to stop and let it finalize on its
            // own thread; do not wait for it here, because here is the EDT.
            log.info("window closed during a capture; asking the build to stop");
            launchController.cancel(CancellationMode.CANCEL);
        }
        worker.shutdownNow();
        // Deliberately not shutdownNow(): interrupting the capture thread is
        // what loses the staged journal buffer. The thread is a daemon, so it
        // does not hold the JVM open, and shutdown() lets an in-flight
        // finalization finish.
        captureWorker.shutdown();
        super.dispose();
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

        JMenu file = new JMenu("File");
        file.add(openFile);
        file.add(openSession);
        file.addSeparator();
        file.add(cancelImportItem);
        file.add(closeSessionItem);

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        return bar;
    }

    private void chooseFileToImport() {
        if (importController.isRunning()) {
            JOptionPane.showMessageDialog(this,
                    "An import is already running. Cancel it before starting another.",
                    "Import in progress", JOptionPane.INFORMATION_MESSAGE);
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

    private void startImport(Path source) {
        showEventsCard();
        eventsView.closeSession();
        eventsView.showImportProgress();
        eventsView.progressPanel().beginRun(source, false);
        cancelImportItem.setEnabled(true);
        closeSessionItem.setEnabled(false);
        sessionStatus.setText("Session: importing");
        eventStatus.setText("Events: " + UNKNOWN);
        if (!importController.start(source, new ImportListener())) {
            JOptionPane.showMessageDialog(this, "An import is already running.",
                    "Import in progress", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void resumeImport(Path sessionRoot) {
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
            eventsView.progressPanel().beginRun(source, resuming);
        }

        @Override
        public void importProgress(ImportProgressModel.Snapshot snapshot) {
            eventsView.progressPanel().update(snapshot);
        }

        @Override
        public void importFinished(ImportResult result) {
            cancelImportItem.setEnabled(false);
            eventsView.progressPanel().finish(summarize(result));
            if (result.outcome() != ImportOutcome.COMPLETE) {
                // Never presented as a clean import. The user is told what was
                // read, where it stopped, and that the rest is absent.
                JOptionPane.showMessageDialog(MainWindow.this, summarize(result),
                        "Import finished with findings", JOptionPane.WARNING_MESSAGE);
            }
            openSessionDirectory(result.sessionRoot(), false);
        }

        @Override
        public void importFailed(Path source, Throwable failure) {
            cancelImportItem.setEnabled(false);
            eventsView.progressPanel().finish("Import failed.");
            eventsView.showEmpty("Import failed. Nothing was indexed.");
            sessionStatus.setText("Session: none");
            String message = failure instanceof UnsupportedSourceException
                    ? failure.getMessage()
                    : failure.toString();
            JOptionPane.showMessageDialog(
                    MainWindow.this, message, "Cannot import", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String summarize(ImportResult result) {
        StringBuilder text = new StringBuilder();
        text.append(switch (result.outcome()) {
            case COMPLETE -> "Imported the whole file.";
            case TRUNCATED -> "The file ends mid-record. Everything before the cut was imported.";
            case CORRUPT_PARTIAL -> "A record's framing contradicted itself. Reading stopped there"
                    + " rather than guessing at the next boundary.";
            case CANCELLED -> "Import cancelled. The session is resumable from its checkpoint.";
        });
        text.append("\nEvents indexed: ").append(EventValueFormat.count(result.eventsInDatabase()));
        text.append("\nSource completeness: ").append(result.sourceCompleteness());
        result.damageOffset().ifPresent(offset ->
                text.append("\nDamage begins at byte offset ").append(offset));
        text.append("\nSession: ").append(result.sessionRoot());
        return text.toString();
    }

    // --------------------------------------------------------------- session

    /**
     * Opens an indexed session without re-importing it.
     *
     * @param offerResume when true, a session that never finished importing
     *     prompts before opening, because resuming it is usually what the user
     *     wants and opening it silently would hide that the capture is partial
     */
    private void openSessionDirectory(Path root, boolean offerResume) {
        showEventsCard();
        eventsView.showEmpty("Opening " + root + "…");
        worker.execute(() -> {
            try {
                SessionManifest manifest = sessions.readManifest(root);
                if (offerResume && !manifest.state().isTerminal()) {
                    SwingUtilities.invokeLater(() -> promptResume(root, manifest));
                    return;
                }
                SqliteSessionSource opened = SqliteSessionSource.open(sessions, root);
                SwingUtilities.invokeLater(() -> {
                    installSession(opened);
                    showSessionInfo(opened.info());
                });
            } catch (Exception failure) {
                log.error("could not open session {}", root, failure);
                SwingUtilities.invokeLater(() -> showSessionFailure(failure.toString()));
            }
        });
    }

    private void promptResume(Path root, SessionManifest manifest) {
        Object[] options = {"Resume import", "Open as it is", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "This session is in state " + manifest.state()
                        + ", so its import never finished.\n"
                        + "Resuming continues from the last checkpoint without re-reading the"
                        + " source from the beginning.",
                "Unfinished session", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        switch (choice) {
            case 0 -> resumeImport(root);
            case 1 -> openSessionDirectory(root, false);
            default -> eventsView.showEmpty("No session is open.");
        }
    }

    /**
     * Hands one session to every view and takes ownership of it.
     *
     * <p>The previous source is closed only after the views have let go of it,
     * which they do synchronously here — their own teardown continues on
     * background threads, but each has already stopped issuing new queries.
     */
    private void installSession(SessionSource opened) {
        releaseViews();
        SessionSource previous = currentSource;
        currentSource = opened;
        eventsView.openSession(opened, this::showSessionFailure);
        overviewPanel.openSession(opened);
        actionsView.openSession(opened);
        targetsView.openSession(opened);
        testsView.openSession(opened);
        failuresView.openSession(opened);
        coverageView.openSession(opened);
        graphView.openSession(opened);
        timeline.openSession(opened);
        closeSource(previous);
    }

    /** Tells every view to let go, without closing the source. */
    private void releaseViews() {
        eventsView.closeSession();
        overviewPanel.closeSession();
        actionsView.closeSession();
        targetsView.closeSession();
        testsView.closeSession();
        failuresView.closeSession();
        coverageView.closeSession();
        graphView.closeSession();
        timeline.closeSession();
    }

    /**
     * Closes a source once its views have released it.
     *
     * <p>On a background thread: the views' executors are shutting down at the
     * same time, and closing JDBC connections behind an in-flight query can
     * block. The source itself is idempotent about being closed twice.
     */
    private void closeSource(SessionSource source) {
        if (source == null) {
            return;
        }
        Thread closer = new Thread(source::close, "bbv-source-close");
        closer.setDaemon(true);
        closer.start();
    }

    private void showSessionInfo(SessionInfo info) {
        sessionStatus.setText("Session: " + info.state() + (info.isPartial() ? " (partial)" : ""));
        OptionalLong count = info.manifestEventCount();
        eventStatus.setText("Events: " + EventValueFormat.count(count));
        actionStatus.setText("Actions: " + UNKNOWN);
        closeSessionItem.setEnabled(true);
        if (!info.warnings().isEmpty()) {
            log.info("session {} carries {} manifest warning(s)", info.root(),
                    info.warnings().size());
        }
    }

    private void showSessionFailure(String message) {
        eventsView.showEmpty("The session could not be opened.");
        sessionStatus.setText("Session: none");
        closeSessionItem.setEnabled(false);
        JOptionPane.showMessageDialog(
                this, message, "Cannot open session", JOptionPane.ERROR_MESSAGE);
    }

    private void closeSession() {
        SessionSource live = liveSource;
        liveSource = null;
        closeSource(live);
        releaseViews();
        SessionSource closing = currentSource;
        currentSource = null;
        closeSource(closing);
        eventsView.showEmpty("No session is open. Use File ▸ Open BEP File… or"
                + " File ▸ Open Session…");
        actionsView.showEmpty("No session is open.");
        targetsView.showEmpty("No session is open.");
        testsView.showEmpty("No session is open.");
        failuresView.showEmpty("No session is open.");
        sessionStatus.setText("Session: none");
        eventStatus.setText("Events: " + UNKNOWN);
        actionStatus.setText("Actions: " + UNKNOWN);
        closeSessionItem.setEnabled(false);
    }

    /** Shows the Events card with {@code eventId}'s raw payload loaded. */
    /**
     * Switches to the graph card and roots it at an action.
     *
     * <p>An action the graph does not declare says so there rather than showing
     * an empty tree, because an empty tree reads as "nothing depends on it".
     */
    /** Switches to the timeline and highlights an action, leaving the view where it is. */
    private void revealOnTimeline(long actionId) {
        showCard(NavEntry.TIMELINE);
        timeline.select(actionId);
    }

    /**
     * Points the timeline at whatever is picked on the graph canvas.
     *
     * <p>Quietly: the selection moves, the card does not. A view that jumped
     * away every time a user clicked a node would make the graph unusable, but
     * arriving at the timeline already on the right action is exactly what the
     * user who does switch expects.
     */
    private void followGraphSelection(long actionId) {
        timeline.select(actionId);
    }

    private void revealInGraph(long actionId) {
        showCard(NavEntry.GRAPH);
        graphView.showAction(actionId);
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
     * <p>Never launches directly. The dialog is what launches, because the user
     * has to see the effective command first (ADR-007) — and because a mandatory
     * conflict has to be answered by a person, not defaulted past.
     */
    private void startLaunch() {
        if (launchController.isBusy()) {
            JOptionPane.showMessageDialog(this,
                    "A build is already running. Cancel it before starting another.",
                    "Capture in progress", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String typed = commandField.getText().strip();
        if (typed.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Enter a Bazel command, such as: test //...",
                    "Nothing to run", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Path workingDirectory;
        try {
            workingDirectory = Path.of(workspaceField.getText().strip()).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            JOptionPane.showMessageDialog(this,
                    "That is not a usable directory: " + workspaceField.getText(),
                    "Cannot launch", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!java.nio.file.Files.isDirectory(workingDirectory)) {
            JOptionPane.showMessageDialog(this,
                    "The working directory does not exist:\n" + workingDirectory,
                    "Cannot launch", JOptionPane.ERROR_MESSAGE);
            return;
        }

        CapturePreset preset = (CapturePreset) presetChoice.getSelectedItem();
        CaptureRequest request = CaptureRequest.of(
                        sessionsRoot, APP_VERSION, "bazel", workingDirectory,
                        com.holtherndon.bazelviz.runner.command.CommandLineParser.tokenize(typed))
                .withPreset(preset == null ? CapturePreset.defaultPreset() : preset);

        runButton.setEnabled(false);
        setCaptureStatus(captureStatus.withPhase(
                CaptureStatusModel.Phase.PREPARING, "Resolving Bazel and probing capabilities…"));
        showCard(NavEntry.CAPTURE);
        launchController.preflight(request);
    }

    private void chooseWorkingDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Working directory for the build");
        String current = workspaceField.getText().strip();
        if (!current.isEmpty()) {
            File asFile = new File(current);
            if (asFile.isDirectory()) {
                chooser.setCurrentDirectory(asFile);
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            workspaceField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void setCaptureStatus(CaptureStatusModel model) {
        this.captureStatus = model;
        capturePanel.show(model);
    }

    private void showCard(NavEntry entry) {
        nav.setSelectedValue(entry, true);
        cardLayout.show(cards, entry.cardName());
    }

    /** Receives the capture's progress, always on the EDT. */
    private final class CaptureListener implements LaunchController.Listener {

        @Override
        public void planReady(Preflight preflight) {
            InstrumentationPlanDialog dialog =
                    new InstrumentationPlanDialog(MainWindow.this, preflight);
            dialog.setVisible(true);
            switch (dialog.choice()) {
                case LAUNCH -> {
                    consoleView.clear();
                    setCaptureStatus(captureStatus.withPhase(
                            CaptureStatusModel.Phase.WAITING,
                            "Listening on " + preflight.endpoint().besBackendUri()));
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
                    launchController.replan(request ->
                            request.vetoing(dialog.vetoedCapability().orElseThrow()));
                }
                case CANCEL -> {
                    launchController.discardPlan();
                    runButton.setEnabled(true);
                    setCaptureStatus(CaptureStatusModel.idle());
                }
            }
        }

        /**
         * Opens the running capture's session for the overview, once.
         *
         * <p>Driven from the progress tick rather than from a new callback
         * because the session directory does not exist when the capture starts
         * and does by the time the first events are counted. A tick that
         * arrives too early simply finds nothing and the next one tries again.
         */
        private void attachLiveOverview() {
            if (liveSource != null) {
                return;
            }
            Path root = launchController.current()
                    .flatMap(CaptureCoordinator::sessionRoot)
                    .orElse(null);
            if (root == null) {
                return;
            }
            worker.execute(() -> {
                try {
                    SqliteSessionSource opened = SqliteSessionSource.open(sessions, root);
                    SwingUtilities.invokeLater(() -> {
                        if (liveSource != null || !launchController.isBusy()) {
                            // A later tick won the race, or the capture ended
                            // while this was opening and the real source is
                            // about to arrive.
                            opened.close();
                            return;
                        }
                        liveSource = opened;
                        overviewPanel.openSession(opened);
                        // The timeline follows the build too. It is the view
                        // where "live" is most of the point -- watching a build
                        // fill in is the reason to have one open while it runs.
                        timeline.openSession(opened);
                    });
                } catch (RuntimeException notYet) {
                    // The manifest or the database is still being written. The
                    // next progress tick tries again; there is nothing to
                    // report, because nothing is wrong.
                    log.debug("the capture's session is not readable yet", notYet);
                }
            });
        }

        @Override
        public void captureStarted(Preflight preflight) {
            setCaptureStatus(captureStatus.withPhase(
                    CaptureStatusModel.Phase.CAPTURING,
                    String.join(" ", preflight.plan().effective().userVisibleArgs())));
            showCard(NavEntry.CONSOLE);
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
        }

        @Override
        public void consoleOutput(
                ConsoleSink.ConsoleStream stream, byte[] data, int offset, int length) {
            consoleView.append(data, offset, length);
        }

        @Override
        public void captureFinished(CaptureResult result) {
            runButton.setEnabled(true);
            CaptureStatusModel.Phase phase = result.wasCancelled()
                    ? CaptureStatusModel.Phase.CANCELLED
                    : result.captureComplete()
                            ? CaptureStatusModel.Phase.DONE
                            : CaptureStatusModel.Phase.FAILED;
            setCaptureStatus(captureStatus.withPhase(phase, describe(result)));
            // The live view is replaced by the real one, which every view gets.
            overviewPanel.closeSession();
            SessionSource live = liveSource;
            liveSource = null;
            closeSource(live);
            // Opened whatever the outcome: a cancelled or partial capture is
            // still a session, and being able to look at it is the point.
            openSessionDirectory(result.sessionRoot(), false);
        }

        @Override
        public void captureFailed(Throwable failure) {
            runButton.setEnabled(true);
            setCaptureStatus(captureStatus.withPhase(
                    CaptureStatusModel.Phase.FAILED, String.valueOf(failure.getMessage())));
            log.warn("the capture could not run", failure);
            JOptionPane.showMessageDialog(MainWindow.this,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    "Cannot capture", JOptionPane.ERROR_MESSAGE);
        }

        private String describe(CaptureResult result) {
            StringBuilder text = new StringBuilder();
            result.process().ifPresent(process -> text.append(
                    process.wasCancelled() ? "build cancelled"
                            : !result.buildOutcomeKnown()
                                    ? "build outcome unknown (event upload failed)"
                            : process.isSuccess() ? "build succeeded"
                            : "build failed").append(" · "));
            result.capture().ifPresent(capture -> text
                    .append(capture.isComplete() ? "capture complete" : "capture incomplete")
                    .append(" · ")
                    .append(capture.normalized())
                    .append(" events indexed"));
            // The discrepancies and warnings are the reason an incomplete
            // capture is incomplete. Showing "capture incomplete" without them
            // tells the user something is wrong and nothing about what.
            List<String> problems = new java.util.ArrayList<>(
                    result.capture().map(CaptureSummary::discrepancies).orElse(List.of()));
            problems.addAll(result.warnings());
            if (!problems.isEmpty()) {
                text.append(" — ").append(String.join("; ", problems));
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ shell

    /**
     * The launcher (plan 24, Phase 2 UI deliverable).
     *
     * <p>A preset, a command and a working directory. Pressing Run does not run
     * anything: it preflights, and the instrumentation dialog is what launches
     * (ADR-007). The field holds the command exactly as the user would type it
     * in a terminal, {@code bazel} omitted, because that is the thing they can
     * check against what they meant.
     */
    private JComponent buildLauncherBar() {
        for (CapturePreset preset : CapturePreset.values()) {
            presetChoice.addItem(preset);
        }
        presetChoice.setSelectedItem(CapturePreset.defaultPreset());
        presetChoice.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
                return super.getListCellRendererComponent(
                        list, value == null ? "" : ((CapturePreset) value).displayName(),
                        index, isSelected, hasFocus);
            }
        });

        commandField.setToolTipText(
                "The Bazel command, without 'bazel' — for example: test //...");
        commandField.addActionListener(event -> startLaunch());

        workspaceField.setColumns(18);
        workspaceField.setToolTipText("Where the build runs. Relative targets resolve against it.");
        workspaceField.setText(System.getProperty("user.dir", ""));

        JButton chooseWorkspace = new JButton("…");
        chooseWorkspace.setToolTipText("Choose the working directory");
        chooseWorkspace.addActionListener(event -> chooseWorkingDirectory());

        runButton.setToolTipText("Preflight the command and show what will run");
        runButton.addActionListener(event -> startLaunch());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(presetChoice);
        left.add(workspaceField);
        left.add(chooseWorkspace);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bar.add(left, BorderLayout.WEST);
        bar.add(commandField, BorderLayout.CENTER);
        bar.add(runButton, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.CENTER);
        north.add(new JSeparator(), BorderLayout.SOUTH);
        return north;
    }

    private JComponent buildNavigation() {
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                super.getListCellRendererComponent(
                        list, ((NavEntry) value).title(), index, isSelected, cellHasFocus);
                setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                return this;
            }
        });
        nav.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            NavEntry selected = nav.getSelectedValue();
            if (selected != null) {
                cardLayout.show(cards, selected.cardName());
            }
        });
        nav.setSelectedIndex(0);
        return new JScrollPane(nav);
    }

    /** The real view for an entry whose phase has arrived, else a placeholder. */
    private JComponent cardFor(NavEntry entry) {
        return switch (entry) {
            case OVERVIEW -> overviewCard;
            case ACTIONS -> actionsView;
            case TARGETS -> targetsView;
            case TESTS -> testsView;
            case FAILURES -> failuresView;
            case GRAPH -> graphView;
            case TIMELINE -> timeline.view();
            case EVENTS -> eventsView;
            case CONSOLE -> consoleView;
            case CAPTURE -> capturePanel;
            default -> placeholderCard(entry);
        };
    }

    private static JComponent placeholderCard(NavEntry entry) {
        JLabel label = new JLabel(
                entry.title() + " — arrives in Phase " + entry.arrivalPhase(),
                SwingConstants.CENTER);
        label.setEnabled(false);
        JPanel card = new JPanel(new BorderLayout());
        card.add(label, BorderLayout.CENTER);
        return card;
    }

    /** Stacks the build summary above the coverage panel, split and resizable. */
    private JComponent buildOverviewCard() {
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, overviewPanel, coverageView);
        split.setResizeWeight(0.62);
        split.setBorder(null);
        return split;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        bar.add(sessionStatus);
        bar.add(eventStatus);
        bar.add(actionStatus);

        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(bar, BorderLayout.CENTER);
        return south;
    }
}

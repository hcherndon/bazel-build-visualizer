package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

/**
 * The compact launcher at the top of the Console card.
 *
 * <p>Its four rows are deliberately labelled rather than inferred from field
 * order. Pressing Run only calls the window's preflight action; ADR-007's
 * separate effective-command review remains the only path to process launch.
 * Preference I/O is delegated to a background executor and only immutable
 * snapshots cross back onto the EDT.
 */
public final class LauncherPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final List<String> COMMON_SUBCOMMANDS = List.of(
            "aquery",
            "build",
            "clean",
            "cquery",
            "help",
            "info",
            "query",
            "run",
            "shutdown",
            "test",
            "version");

    private static final CapturePreset[] VISIBLE_PRESETS = {
        CapturePreset.LIVE_ESSENTIALS,
        CapturePreset.PERFORMANCE_DIAGNOSTICS,
        CapturePreset.FULL_GRAPH_DIAGNOSTICS
    };

    private final JTextField workspace = new JTextField(34);
    private final JTextField bazelExecutable = new JTextField(34);
    private final JComboBox<CapturePreset> captureDetail = new JComboBox<>(VISIBLE_PRESETS);
    private final JTextField command = new JTextField(34);
    private final JButton chooseWorkspace = new JButton("Choose workspace…");
    private final JButton chooseBazel = new JButton("Choose Bazel…");
    private final JButton run = new JButton("Run");
    private final JTextArea presetExplanation = explanationArea("", 1);
    private final JTextArea graphCostWarning = explanationArea(
            "Cost warning for every choice: after the build, BBV runs aquery and cquery and"
                    + " indexes both graphs, using extra disk, CPU, and indexing time.",
            2);
    private final JLabel historyExplanation = new JLabel(
            "Keeps the " + LauncherHistory.MAX_ENTRIES
                    + " most recent unique commands · Up/Down recalls · Ctrl+Space completes");
    private final LauncherHistory history = new LauncherHistory();
    private final Timer saveDebounce = new Timer(400, event -> flushPersistence());
    private final EnumSet<Setting> editedBeforeLoadCompletes = EnumSet.noneOf(Setting.class);
    private SaveQueue saveQueue;
    private boolean applying;
    private boolean loadPending;
    private boolean persistenceReady;
    private volatile boolean disposed;
    private volatile ClosedState closedWhileLoading;

    public LauncherPanel(
            Runnable runAction, Runnable chooseWorkspaceAction, Runnable chooseBazelAction) {
        super(new GridBagLayout());
        Objects.requireNonNull(runAction, "runAction");
        Objects.requireNonNull(chooseWorkspaceAction, "chooseWorkspaceAction");
        Objects.requireNonNull(chooseBazelAction, "chooseBazelAction");

        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Launch a Bazel build"),
                BorderFactory.createEmptyBorder(2, 8, 6, 8)));
        saveDebounce.setRepeats(false);

        LauncherStateStore.State defaults = LauncherStateStore.State.defaults();
        workspace.setText(defaults.workspace());
        bazelExecutable.setText(defaults.bazelExecutable());
        captureDetail.setSelectedItem(defaults.preset());

        workspace.setName("launcher.workspace");
        bazelExecutable.setName("launcher.bazel");
        captureDetail.setName("launcher.captureDetail");
        command.setName("launcher.command");
        chooseWorkspace.setName("launcher.chooseWorkspace");
        chooseBazel.setName("launcher.chooseBazel");
        run.setName("launcher.run");
        presetExplanation.setName("launcher.presetExplanation");
        graphCostWarning.setName("launcher.graphCostWarning");
        historyExplanation.setName("launcher.historyExplanation");

        workspace.setToolTipText(PlainText.tooltip(
                "Where the build runs. Relative targets resolve against this directory."));
        bazelExecutable.setToolTipText(PlainText.tooltip(
                "A Bazel name found on PATH, such as bazel or bazelisk, or a path to a binary."));
        command.setToolTipText(PlainText.tooltip(
                "The Bazel command without 'bazel', for example test //...."
                        + " Up/Down recalls up to 50 unique commands; Ctrl+Space completes a"
                        + " common Bazel subcommand."));
        run.setToolTipText("Preflight the command and show the effective command for review");

        captureDetail.setRenderer(new PresetRenderer());
        Color warning = UIManager.getColor("Actions.Red");
        graphCostWarning.setForeground(warning == null ? new Color(180, 36, 36) : warning);
        graphCostWarning.setFont(graphCostWarning.getFont().deriveFont(Font.BOLD));
        captureDetail.addActionListener(event -> {
            updatePresetExplanation();
            markDirty(Setting.PRESET);
        });
        updatePresetExplanation();

        chooseWorkspace.addActionListener(event -> chooseWorkspaceAction.run());
        chooseBazel.addActionListener(event -> chooseBazelAction.run());
        run.addActionListener(event -> runAction.run());
        command.addActionListener(event -> runAction.run());
        bazelExecutable.addActionListener(event -> runAction.run());

        installCompletion();
        installHistoryNavigation();
        installPersistenceListeners();
        buildForm();
    }

    private void buildForm() {
        JLabel workspaceLabel = label("Workspace", workspace, "launcher.workspaceLabel");
        JLabel bazelLabel = label(
                "Bazel executable", bazelExecutable, "launcher.bazelLabel");
        JLabel detailLabel = label(
                "Capture detail", captureDetail, "launcher.captureDetailLabel");
        JLabel commandLabel = label(
                "Bazel command (without bazel)", command, "launcher.commandLabel");

        add(workspaceLabel, constraints(0, 0, 0, 0));
        add(workspace, constraints(1, 0, 1, 1));
        add(chooseWorkspace, constraints(2, 0, 0, 0));

        add(bazelLabel, constraints(0, 1, 0, 0));
        add(bazelExecutable, constraints(1, 1, 1, 1));
        add(chooseBazel, constraints(2, 1, 0, 0));

        add(detailLabel, constraints(0, 2, 0, 0));
        add(captureDetail, constraints(1, 2, 0, 1));
        GridBagConstraints explanation = constraints(1, 3, 1, 2);
        explanation.insets = new Insets(0, 4, 4, 4);
        add(presetExplanation, explanation);

        GridBagConstraints graphWarning = constraints(1, 4, 1, 2);
        graphWarning.insets = new Insets(0, 4, 4, 4);
        add(graphCostWarning, graphWarning);

        add(commandLabel, constraints(0, 5, 0, 0));
        add(command, constraints(1, 5, 1, 1));
        add(run, constraints(2, 5, 0, 0));

        GridBagConstraints historyHint = constraints(1, 6, 1, 2);
        historyHint.insets = new Insets(0, 4, 2, 4);
        historyExplanation.setEnabled(false);
        add(historyExplanation, historyHint);
    }

    private static JLabel label(String text, JComponent target, String name) {
        JLabel label = new JLabel(text + ":");
        label.setLabelFor(target);
        label.setName(name);
        return label;
    }

    private static JTextArea explanationArea(String text, int rows) {
        JTextArea area = new JTextArea(text, rows, 20);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        return area;
    }

    private static GridBagConstraints constraints(
            int x, int y, double weightX, int gridWidth) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.gridwidth = Math.max(1, gridWidth);
        constraints.weightx = weightX;
        constraints.fill = weightX > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 4, 4, 4);
        return constraints;
    }

    private void updatePresetExplanation() {
        CapturePreset selected = preset();
        String text = switch (selected) {
            case LIVE_ESSENTIALS ->
                    "Live BEP and console; no execution log or timing profile.";
            case PERFORMANCE_DIAGNOSTICS ->
                    "Recommended: adds the execution log and timing profile to live BEP and console.";
            case FULL_GRAPH_DIAGNOSTICS ->
                    "Currently the same sources as Performance Diagnostics; it adds no graph capture today.";
            case CUSTOM -> throw new IllegalStateException("Custom is not a visible launcher option");
        };
        presetExplanation.setText(text);
    }

    private void installCompletion() {
        DefaultCompletionProvider provider = new DefaultCompletionProvider();
        for (String subcommand : COMMON_SUBCOMMANDS) {
            provider.addCompletion(new BasicCompletion(provider, subcommand, "Bazel subcommand"));
        }
        provider.setAutoActivationRules(false, null);
        AutoCompletion completion = new AutoCompletion(provider);
        completion.setAutoActivationEnabled(false);
        completion.install(command);
    }

    private void installHistoryNavigation() {
        command.getActionMap().put("bbv-older-command", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                history.olderThan(command.getText()).ifPresent(LauncherPanel.this::recall);
            }
        });
        command.getActionMap().put("bbv-newer-command", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                history.newerThan(command.getText()).ifPresent(LauncherPanel.this::recall);
            }
        });
        command.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0), "bbv-older-command");
        command.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0), "bbv-newer-command");
    }

    private void recall(String value) {
        applying = true;
        try {
            command.setText(value);
            command.setCaretPosition(value.length());
        } finally {
            applying = false;
        }
        markDirty(Setting.COMMAND);
    }

    private void installPersistenceListeners() {
        workspace.getDocument().addDocumentListener(dirtyListener(Setting.WORKSPACE, false));
        bazelExecutable.getDocument().addDocumentListener(
                dirtyListener(Setting.BAZEL_EXECUTABLE, false));
        command.getDocument().addDocumentListener(dirtyListener(Setting.COMMAND, true));
    }

    private DocumentListener dirtyListener(Setting setting, boolean resetsHistoryWalk) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                changed();
            }

            private void changed() {
                if (resetsHistoryWalk && !applying) {
                    history.resetNavigation();
                }
                markDirty(setting);
            }
        };
    }

    /** Attaches {@code settings/launcher.properties}; all I/O stays off the EDT. */
    public void attachPersistence(java.nio.file.Path settingsDirectory) {
        attachPersistence(new LauncherStateStore(settingsDirectory), SharedIo.EXECUTOR);
    }

    /** Explicit store/executor seam for focused headless tests. */
    void attachPersistence(LauncherStateStore newStore, Executor executor) {
        if (disposed) {
            return;
        }
        LauncherStateStore checkedStore = Objects.requireNonNull(newStore, "newStore");
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor");
        SaveQueue queue = new SaveQueue(checkedStore, checkedExecutor);
        saveQueue = queue;
        loadPending = true;
        checkedExecutor.execute(() -> {
            LauncherStateStore.State loaded = checkedStore.load();
            if (disposed) {
                finishClosedLoad(queue, loaded);
            } else {
                SwingUtilities.invokeLater(() -> finishLoad(queue, loaded));
            }
        });
    }

    private void finishLoad(SaveQueue queue, LauncherStateStore.State loaded) {
        if (disposed) {
            finishClosedLoad(queue, loaded);
            return;
        }
        LauncherStateStore.State merged = mergeLoaded(
                loaded, snapshot(), editedBeforeLoadCompletes);
        loadPending = false;
        persistenceReady = true;
        editedBeforeLoadCompletes.clear();
        queue.initialize(loaded);
        adopt(merged);
        queue.request(merged);
    }

    private void finishClosedLoad(SaveQueue queue, LauncherStateStore.State loaded) {
        ClosedState closed = closedWhileLoading;
        queue.initialize(loaded);
        if (closed != null) {
            queue.request(mergeLoaded(loaded, closed.snapshot(), closed.edited()));
        }
    }

    private void adopt(LauncherStateStore.State loaded) {
        applying = true;
        try {
            workspace.setText(loaded.workspace());
            bazelExecutable.setText(loaded.bazelExecutable());
            captureDetail.setSelectedItem(visibleOrDefault(loaded.preset()));
            command.setText(loaded.command());
            history.replaceNewestFirst(loaded.history());
            saveDebounce.stop();
        } finally {
            applying = false;
        }
        updatePresetExplanation();
    }

    private void markDirty(Setting setting) {
        if (applying || disposed) {
            return;
        }
        if (!persistenceReady) {
            editedBeforeLoadCompletes.add(setting);
        }
        if (persistenceReady) {
            saveDebounce.restart();
        }
    }

    /** Captures on the EDT and queues a save; the caller never waits for disk. */
    public void flushPersistence() {
        saveDebounce.stop();
        SaveQueue queue = saveQueue;
        if (disposed || queue == null || !persistenceReady || loadPending) {
            return;
        }
        queue.request(snapshot());
    }

    private LauncherStateStore.State snapshot() {
        return new LauncherStateStore.State(
                workspace.getText(),
                bazelExecutable.getText(),
                preset(),
                command.getText(),
                history.entries());
    }

    /** Promotes a command into history and queues the updated state for persistence. */
    public void rememberCommand(String value) {
        history.record(value);
        markDirty(Setting.HISTORY);
        flushPersistence();
    }

    /**
     * Stops persistence callbacks for a window that is closing.
     *
     * <p>If loading is still pending, the current values and their edited-field
     * set are captured on the EDT. The load worker later merges and persists
     * them without adopting the result into this disposed panel.
     */
    public void close() {
        if (disposed) {
            return;
        }
        saveDebounce.stop();
        if (loadPending) {
            closedWhileLoading = new ClosedState(snapshot(), editedBeforeLoadCompletes);
        } else if (persistenceReady && saveQueue != null) {
            saveQueue.request(snapshot());
        }
        disposed = true;
    }

    public String workspace() {
        return workspace.getText();
    }

    public void setWorkspace(String value) {
        workspace.setText(value == null ? "" : value);
    }

    public String bazelExecutable() {
        return bazelExecutable.getText();
    }

    public void setBazelExecutable(String value) {
        bazelExecutable.setText(value == null ? "" : value);
    }

    public String command() {
        return command.getText();
    }

    public CapturePreset preset() {
        CapturePreset selected = (CapturePreset) captureDetail.getSelectedItem();
        return visibleOrDefault(selected);
    }

    public void setRunEnabled(boolean enabled) {
        run.setEnabled(enabled);
    }

    private static CapturePreset visibleOrDefault(CapturePreset preset) {
        if (preset == CapturePreset.LIVE_ESSENTIALS
                || preset == CapturePreset.PERFORMANCE_DIAGNOSTICS
                || preset == CapturePreset.FULL_GRAPH_DIAGNOSTICS) {
            return preset;
        }
        return CapturePreset.defaultPreset();
    }

    private static LauncherStateStore.State mergeLoaded(
            LauncherStateStore.State loaded,
            LauncherStateStore.State current,
            Set<Setting> edited) {
        List<String> mergedHistory = loaded.history();
        if (edited.contains(Setting.HISTORY)) {
            LinkedHashSet<String> unique = new LinkedHashSet<>(current.history());
            unique.addAll(loaded.history());
            mergedHistory = unique.stream().limit(LauncherHistory.MAX_ENTRIES).toList();
        }
        return new LauncherStateStore.State(
                edited.contains(Setting.WORKSPACE) ? current.workspace() : loaded.workspace(),
                edited.contains(Setting.BAZEL_EXECUTABLE)
                        ? current.bazelExecutable()
                        : loaded.bazelExecutable(),
                edited.contains(Setting.PRESET) ? current.preset() : loaded.preset(),
                edited.contains(Setting.COMMAND) ? current.command() : loaded.command(),
                mergedHistory);
    }

    static List<String> commonSubcommands() {
        return COMMON_SUBCOMMANDS;
    }

    JTextField workspaceFieldForTest() {
        return workspace;
    }

    JTextField bazelFieldForTest() {
        return bazelExecutable;
    }

    JComboBox<CapturePreset> presetChoiceForTest() {
        return captureDetail;
    }

    JTextField commandFieldForTest() {
        return command;
    }

    JTextArea presetExplanationForTest() {
        return presetExplanation;
    }

    JTextArea graphCostWarningForTest() {
        return graphCostWarning;
    }

    JLabel historyExplanationForTest() {
        return historyExplanation;
    }

    JButton chooseWorkspaceForTest() {
        return chooseWorkspace;
    }

    JButton chooseBazelForTest() {
        return chooseBazel;
    }

    private static final class PresetRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused) {
            CapturePreset preset = value instanceof CapturePreset item ? item : null;
            String text = preset == null ? "" : preset.displayName()
                    + (preset == CapturePreset.PERFORMANCE_DIAGNOSTICS ? " (recommended)" : "");
            return super.getListCellRendererComponent(list, text, index, selected, focused);
        }
    }

    private enum Setting {
        WORKSPACE,
        BAZEL_EXECUTABLE,
        PRESET,
        COMMAND,
        HISTORY
    }

    private record ClosedState(LauncherStateStore.State snapshot, Set<Setting> edited) {
        private ClosedState {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            edited = Set.copyOf(edited);
        }
    }

    /**
     * Serializes launcher saves without making completion callbacks touch Swing.
     * The newest requested snapshot remains desired while an older write is in
     * flight, so a stale completion can never become the final disk value.
     */
    private static final class SaveQueue {
        private final LauncherStateStore store;
        private final Executor executor;
        private LauncherStateStore.State persisted;
        private LauncherStateStore.State desired;
        private LauncherStateStore.State inFlight;
        private boolean initialized;

        private SaveQueue(LauncherStateStore store, Executor executor) {
            this.store = store;
            this.executor = executor;
        }

        private synchronized void initialize(LauncherStateStore.State loaded) {
            if (initialized) {
                return;
            }
            persisted = loaded;
            initialized = true;
            startNextIfNeeded();
        }

        private synchronized void request(LauncherStateStore.State state) {
            desired = state;
            startNextIfNeeded();
        }

        private void startNextIfNeeded() {
            if (!initialized
                    || inFlight != null
                    || desired == null
                    || desired.equals(persisted)) {
                return;
            }
            LauncherStateStore.State next = desired;
            inFlight = next;
            executor.execute(() -> complete(next, store.save(next)));
        }

        private synchronized void complete(LauncherStateStore.State completed, boolean success) {
            if (!completed.equals(inFlight)) {
                return;
            }
            boolean superseded = !completed.equals(desired);
            if (success) {
                persisted = completed;
            }
            inFlight = null;
            if (success || superseded) {
                startNextIfNeeded();
            }
        }
    }

    private static final class SharedIo {
        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-launcher-settings");
            thread.setDaemon(true);
            return thread;
        });

        private SharedIo() {}
    }
}

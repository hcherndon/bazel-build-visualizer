package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
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
    private final JLabel presetExplanation = new JLabel();
    private final JLabel historyExplanation = new JLabel(
            "Keeps the " + LauncherHistory.MAX_ENTRIES
                    + " most recent unique commands · Up/Down recalls · Ctrl+Space completes");
    private final LauncherHistory history = new LauncherHistory();
    private final Timer saveDebounce = new Timer(400, event -> flushPersistence());
    private final Font ordinaryExplanationFont;
    private final Color ordinaryExplanationColor;

    private LauncherStateStore store;
    private Executor ioExecutor;
    private LauncherStateStore.State lastSaved;
    private boolean applying;

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
        ordinaryExplanationFont = presetExplanation.getFont();
        ordinaryExplanationColor = presetExplanation.getForeground();
        captureDetail.addActionListener(event -> {
            updatePresetExplanation();
            markDirty();
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

        add(commandLabel, constraints(0, 4, 0, 0));
        add(command, constraints(1, 4, 1, 1));
        add(run, constraints(2, 4, 0, 0));

        GridBagConstraints historyHint = constraints(1, 5, 1, 2);
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
            case LIVE_ESSENTIALS -> "BEP and console only; lowest capture overhead.";
            case PERFORMANCE_DIAGNOSTICS ->
                    "Recommended: adds timing, execution, cache, and action diagnostics.";
            case FULL_GRAPH_DIAGNOSTICS ->
                    "Warning: high disk, CPU, and indexing cost; use only for full-graph questions.";
            case CUSTOM -> throw new IllegalStateException("Custom is not a visible launcher option");
        };
        presetExplanation.setText(text);
        if (selected.requiresCostWarning()) {
            Color warning = UIManager.getColor("Actions.Red");
            presetExplanation.setForeground(warning == null ? new Color(180, 36, 36) : warning);
            presetExplanation.setFont(ordinaryExplanationFont.deriveFont(Font.BOLD));
        } else {
            presetExplanation.setForeground(ordinaryExplanationColor);
            presetExplanation.setFont(ordinaryExplanationFont);
        }
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
        markDirty();
    }

    private void installPersistenceListeners() {
        workspace.getDocument().addDocumentListener(dirtyListener(false));
        bazelExecutable.getDocument().addDocumentListener(dirtyListener(false));
        command.getDocument().addDocumentListener(dirtyListener(true));
    }

    private DocumentListener dirtyListener(boolean resetsHistoryWalk) {
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
                markDirty();
            }
        };
    }

    /** Attaches {@code settings/launcher.properties}; all I/O stays off the EDT. */
    public void attachPersistence(java.nio.file.Path settingsDirectory) {
        attachPersistence(new LauncherStateStore(settingsDirectory), SharedIo.EXECUTOR);
    }

    /** Explicit store/executor seam for focused headless tests. */
    void attachPersistence(LauncherStateStore newStore, Executor executor) {
        store = Objects.requireNonNull(newStore, "newStore");
        ioExecutor = Objects.requireNonNull(executor, "executor");
        executor.execute(() -> {
            LauncherStateStore.State loaded = newStore.load();
            SwingUtilities.invokeLater(() -> adopt(loaded));
        });
    }

    private void adopt(LauncherStateStore.State loaded) {
        applying = true;
        try {
            workspace.setText(loaded.workspace());
            bazelExecutable.setText(loaded.bazelExecutable());
            captureDetail.setSelectedItem(visibleOrDefault(loaded.preset()));
            command.setText(loaded.command());
            history.replaceNewestFirst(loaded.history());
            lastSaved = snapshot();
            saveDebounce.stop();
        } finally {
            applying = false;
        }
        updatePresetExplanation();
    }

    private void markDirty() {
        if (!applying && store != null) {
            saveDebounce.restart();
        }
    }

    /** Captures on the EDT and queues a save; the caller never waits for disk. */
    public void flushPersistence() {
        saveDebounce.stop();
        if (store == null) {
            return;
        }
        LauncherStateStore.State state = snapshot();
        if (state.equals(lastSaved)) {
            return;
        }
        lastSaved = state;
        LauncherStateStore target = store;
        ioExecutor.execute(() -> target.save(state));
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
        flushPersistence();
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

    JLabel presetExplanationForTest() {
        return presetExplanation;
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

    private static final class SharedIo {
        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-launcher-settings");
            thread.setDaemon(true);
            return thread;
        });

        private SharedIo() {}
    }
}

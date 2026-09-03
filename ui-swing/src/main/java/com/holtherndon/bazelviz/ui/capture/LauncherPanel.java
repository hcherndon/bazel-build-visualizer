package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

/**
 * The compact launcher at the top of the Console card.
 *
 * <p>Its four rows are deliberately labelled rather than inferred from field order. Pressing Run
 * only calls the window's preflight action; ADR-007's separate effective-command review remains the
 * only path to process launch. Preference I/O is delegated to a background executor and only
 * immutable snapshots cross back onto the EDT.
 */
public final class LauncherPanel extends JPanel {

  private static final long serialVersionUID = 1L;
  private static final String NEW_SSH_CONNECTION = "New connection…";

  private static final List<String> COMMON_SUBCOMMANDS =
      List.of(
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
  private final JComboBox<ExecutionHost> executionHost = new JComboBox<>(ExecutionHost.values());
  private final JTextField sshDestination = new JTextField(22);
  private final JTextField sshPort = new JTextField(5);
  private final JPanel sshOptions = new JPanel(new GridBagLayout());
  private final JComboBox<String> savedSshConnections = new JComboBox<>();
  private final JButton forgetSshConnection = new JButton("Forget");
  private final JButton chooseWorkspace = new JButton("Choose workspace…");
  private final JButton chooseBazel = new JButton("Choose Bazel…");
  private final JTextField selectedWorkspace = new JTextField();
  private final JButton changeWorkspace = new JButton("Change workspace…");
  private final JPanel selectedWorkspaceRow = new JPanel(new GridBagLayout());
  private final JButton run = new JButton("Run");
  private final JLabel presetSummary = new JLabel();
  private final LauncherHistory history = new LauncherHistory();
  private final Timer saveDebounce = new Timer(400, event -> flushPersistence());
  private final EnumSet<Setting> editedBeforeLoadCompletes = EnumSet.noneOf(Setting.class);
  private SaveQueue saveQueue;
  private boolean applying;
  private boolean loadPending;
  private boolean persistenceReady;
  private volatile boolean disposed;
  private volatile ClosedState closedWhileLoading;
  private final CompletableFuture<Void> persistenceClose = new CompletableFuture<>();
  private List<SshConnectionProfile> sshProfiles = List.of();
  private boolean updatingSshProfiles;
  private ExecutionHost displayedHost = ExecutionHost.LOCAL;
  private String localWorkspaceDraft = LauncherStateStore.State.defaults().workspace();
  private String localBazelDraft = LauncherStateStore.State.defaults().bazelExecutable();
  private String remoteWorkspaceDraft = "";
  private String remoteBazelDraft = "bazel";
  private boolean managedWorkspace;
  private ManagedWorkspace managedWorkspaceSelection;

  private JLabel hostLabel;
  private JLabel workspaceLabel;
  private JLabel bazelLabel;
  private JPanel hostRow;

  public LauncherPanel(
      Runnable runAction, Runnable chooseWorkspaceAction, Runnable chooseBazelAction) {
    super(new GridBagLayout());
    Objects.requireNonNull(runAction, "runAction");
    Objects.requireNonNull(chooseWorkspaceAction, "chooseWorkspaceAction");
    Objects.requireNonNull(chooseBazelAction, "chooseBazelAction");

    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Launch a Bazel build"),
            BorderFactory.createEmptyBorder(2, 8, 6, 8)));
    saveDebounce.setRepeats(false);

    LauncherStateStore.State defaults = LauncherStateStore.State.defaults();
    workspace.setText(defaults.workspace());
    bazelExecutable.setText(defaults.bazelExecutable());
    captureDetail.setSelectedItem(defaults.preset());
    executionHost.setSelectedItem(defaults.executionHost());

    workspace.setName("launcher.workspace");
    bazelExecutable.setName("launcher.bazel");
    captureDetail.setName("launcher.captureDetail");
    command.setName("launcher.command");
    executionHost.setName("launcher.executionHost");
    sshDestination.setName("launcher.sshDestination");
    sshPort.setName("launcher.sshPort");
    sshOptions.setName("launcher.sshOptions");
    savedSshConnections.setName("launcher.savedSshConnections");
    forgetSshConnection.setName("launcher.forgetSshConnection");
    chooseWorkspace.setName("launcher.chooseWorkspace");
    chooseBazel.setName("launcher.chooseBazel");
    selectedWorkspace.setName("launcher.selectedWorkspace");
    changeWorkspace.setName("launcher.changeWorkspace");
    selectedWorkspaceRow.setName("launcher.selectedWorkspaceRow");
    run.setName("launcher.run");
    presetSummary.setName("launcher.presetSummary");

    workspace.setToolTipText(
        PlainText.tooltip(
            "Where the build runs. Relative targets resolve against this directory."));
    bazelExecutable.setToolTipText(
        PlainText.tooltip(
            "A Bazel name found on PATH, such as bazel or bazelisk, or a path to a binary."));
    command.setToolTipText(
        PlainText.tooltip(
            "The Bazel command without 'bazel', for example test //...."
                + " Up/Down recalls up to "
                + LauncherHistory.MAX_ENTRIES
                + " unique commands; Ctrl+Space completes a"
                + " common Bazel subcommand."));
    executionHost.setToolTipText(
        PlainText.tooltip(
            "Run directly on this computer or through an explicit OpenSSH connection."));
    sshDestination.setToolTipText(
        PlainText.tooltip(
            "An SSH config host alias or user@host. Authentication uses your SSH config"
                + " and agent; the app does not store a password."));
    sshPort.setToolTipText(
        PlainText.tooltip("Optional SSH port. Leave blank to use your SSH config or port 22."));
    savedSshConnections.setToolTipText(
        PlainText.tooltip(
            "Saved, non-secret SSH settings. Selecting one restores its remote directory"
                + " and Bazel executable."));
    forgetSshConnection.setToolTipText("Remove the selected saved connection");
    run.setToolTipText("Preflight the command and show the effective command for review");
    selectedWorkspace.setEditable(false);
    selectedWorkspace.setFocusable(true);
    selectedWorkspace.setToolTipText(
        PlainText.tooltip("The selected repository and machine. Use Workspaces to change it."));
    changeWorkspace.setToolTipText("Return to the workspace chooser");

    captureDetail.setRenderer(new PresetRenderer());
    presetSummary.setEnabled(false);
    captureDetail.addActionListener(
        event -> {
          updatePresetSummary();
          markDirty(Setting.PRESET);
        });
    updatePresetSummary();

    executionHost.addActionListener(
        event -> {
          switchHostDraft();
          updateHostControls();
          markDirty(Setting.EXECUTION_HOST);
        });
    savedSshConnections.addActionListener(event -> selectSshProfile());
    forgetSshConnection.addActionListener(event -> forgetSelectedSshConnection());

    chooseWorkspace.addActionListener(event -> chooseWorkspaceAction.run());
    chooseBazel.addActionListener(event -> chooseBazelAction.run());
    changeWorkspace.addActionListener(event -> chooseWorkspaceAction.run());
    run.addActionListener(event -> runAction.run());
    command.addActionListener(event -> runAction.run());
    bazelExecutable.addActionListener(event -> runAction.run());

    installCompletion();
    installHistoryNavigation();
    installPersistenceListeners();
    buildForm();
    updateHostControls();
  }

  private void switchHostDraft() {
    ExecutionHost selected = executionHost();
    if (selected == displayedHost || applying) {
      displayedHost = selected;
      return;
    }
    applying = true;
    try {
      if (displayedHost == ExecutionHost.LOCAL) {
        localWorkspaceDraft = workspace.getText();
        localBazelDraft = bazelExecutable.getText();
        workspace.setText(remoteWorkspaceDraft);
        bazelExecutable.setText(remoteBazelDraft);
      } else {
        remoteWorkspaceDraft = workspace.getText();
        remoteBazelDraft = bazelExecutable.getText();
        workspace.setText(localWorkspaceDraft);
        bazelExecutable.setText(localBazelDraft);
      }
      displayedHost = selected;
    } finally {
      applying = false;
    }
  }

  private void buildForm() {
    hostLabel = label("Run on", executionHost, "launcher.executionHostLabel");
    workspaceLabel = label("Workspace", workspace, "launcher.workspaceLabel");
    bazelLabel = label("Bazel executable", bazelExecutable, "launcher.bazelLabel");
    JLabel detailLabel = label("Capture detail", captureDetail, "launcher.captureDetailLabel");
    JLabel commandLabel = label("Bazel command (without bazel)", command, "launcher.commandLabel");

    JLabel destinationLabel =
        label("SSH destination", sshDestination, "launcher.sshDestinationLabel");
    JLabel portLabel = label("Port", sshPort, "launcher.sshPortLabel");
    JLabel savedLabel =
        label("Connection", savedSshConnections, "launcher.savedSshConnectionsLabel");
    sshOptions.add(savedLabel, sshConstraints(0, 0, 0, 1));
    sshOptions.add(savedSshConnections, sshConstraints(1, 0, 1, 1));
    sshOptions.add(forgetSshConnection, sshConstraints(2, 0, 0, 1));
    sshOptions.add(destinationLabel, sshConstraints(0, 1, 0, 1));
    sshOptions.add(sshDestination, sshConstraints(1, 1, 1, 1));
    JPanel port = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    port.add(portLabel);
    port.add(sshPort);
    sshOptions.add(port, sshConstraints(2, 1, 0, 1));
    refreshSavedSshConnections("");

    hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    hostRow.add(executionHost);
    hostRow.add(sshOptions);
    add(hostLabel, constraints(0, 0, 0, 0));
    add(hostRow, constraints(1, 0, 1, 2));

    add(workspaceLabel, constraints(0, 1, 0, 0));
    add(workspace, constraints(1, 1, 1, 1));
    add(chooseWorkspace, constraints(2, 1, 0, 0));

    add(bazelLabel, constraints(0, 2, 0, 0));
    add(bazelExecutable, constraints(1, 2, 1, 1));
    add(chooseBazel, constraints(2, 2, 0, 0));

    add(detailLabel, constraints(0, 3, 0, 0));
    JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    presetRow.add(captureDetail);
    presetRow.add(presetSummary);
    add(presetRow, constraints(1, 3, 1, 2));

    add(commandLabel, constraints(0, 4, 0, 0));
    add(command, constraints(1, 4, 1, 1));
    add(run, constraints(2, 4, 0, 0));

    JLabel selectedLabel = label("Workspace", selectedWorkspace, "launcher.selectedWorkspaceLabel");
    selectedWorkspaceRow.add(selectedLabel, sshConstraints(0, 0, 0, 1));
    selectedWorkspaceRow.add(selectedWorkspace, sshConstraints(1, 0, 1, 1));
    selectedWorkspaceRow.add(changeWorkspace, sshConstraints(2, 0, 0, 1));
    selectedWorkspaceRow.setVisible(false);
    add(selectedWorkspaceRow, constraints(0, 0, 1, 3));
  }

  private static GridBagConstraints sshConstraints(int x, int y, double weightX, int gridWidth) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.gridwidth = gridWidth;
    constraints.weightx = weightX;
    constraints.fill = weightX > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(1, 4, 1, 4);
    return constraints;
  }

  private void updateHostControls() {
    boolean remote = executionHost() == ExecutionHost.SSH;
    hostLabel.setVisible(!managedWorkspace);
    hostRow.setVisible(!managedWorkspace);
    executionHost.setVisible(!managedWorkspace);
    workspaceLabel.setVisible(!managedWorkspace);
    workspace.setVisible(!managedWorkspace);
    bazelLabel.setVisible(!managedWorkspace);
    bazelExecutable.setVisible(!managedWorkspace);
    selectedWorkspaceRow.setVisible(managedWorkspace);
    sshOptions.setVisible(!managedWorkspace && remote);
    chooseWorkspace.setVisible(!managedWorkspace && !remote);
    chooseBazel.setVisible(!managedWorkspace && !remote);
    forgetSshConnection.setEnabled(remote && savedSshConnections.getSelectedIndex() > 0);
    workspace.setToolTipText(
        PlainText.tooltip(
            remote
                ? "Absolute working directory on the SSH host. Relative targets resolve here."
                : "Where the build runs. Relative targets resolve against this directory."));
    bazelExecutable.setToolTipText(
        PlainText.tooltip(
            remote
                ? "Bazel executable name or absolute path on the SSH host."
                : "A Bazel name found on PATH, such as bazel or bazelisk, or a path to a binary."));
    revalidate();
    repaint();
  }

  private void selectSshProfile() {
    if (updatingSshProfiles) {
      return;
    }
    Object selected = savedSshConnections.getSelectedItem();
    if (!(selected instanceof String displayName) || NEW_SSH_CONNECTION.equals(displayName)) {
      forgetSshConnection.setEnabled(false);
      return;
    }
    sshProfiles.stream()
        .filter(profile -> profile.displayName().equals(displayName))
        .findFirst()
        .ifPresent(
            profile -> {
              applying = true;
              try {
                sshDestination.setText(profile.destination());
                sshPort.setText(profile.port());
                workspace.setText(profile.workingDirectory());
                bazelExecutable.setText(profile.bazelExecutable());
                remoteWorkspaceDraft = profile.workingDirectory();
                remoteBazelDraft = profile.bazelExecutable();
              } finally {
                applying = false;
              }
            });
    forgetSshConnection.setEnabled(true);
    markDirty(Setting.SSH_DESTINATION);
    markDirty(Setting.SSH_PORT);
    markDirty(Setting.WORKSPACE);
    markDirty(Setting.BAZEL_EXECUTABLE);
  }

  private void refreshSavedSshConnections(String selectedProfileKey) {
    updatingSshProfiles = true;
    try {
      savedSshConnections.removeAllItems();
      savedSshConnections.addItem(NEW_SSH_CONNECTION);
      for (SshConnectionProfile profile : sshProfiles) {
        savedSshConnections.addItem(profile.displayName());
      }
      int selected = 0;
      for (int i = 0; i < sshProfiles.size(); i++) {
        if (sshProfiles.get(i).key().equals(selectedProfileKey)) {
          selected = i + 1;
          break;
        }
      }
      savedSshConnections.setSelectedIndex(selected);
      forgetSshConnection.setEnabled(executionHost() == ExecutionHost.SSH && selected > 0);
    } finally {
      updatingSshProfiles = false;
    }
  }

  private void forgetSelectedSshConnection() {
    Object selected = savedSshConnections.getSelectedItem();
    if (!(selected instanceof String displayName) || NEW_SSH_CONNECTION.equals(displayName)) {
      return;
    }
    sshProfiles =
        sshProfiles.stream().filter(profile -> !profile.displayName().equals(displayName)).toList();
    refreshSavedSshConnections("");
    markDirty(Setting.SSH_PROFILES);
    flushPersistence();
  }

  private static JLabel label(String text, JComponent target, String name) {
    JLabel label = new JLabel(text + ":");
    label.setLabelFor(target);
    label.setName(name);
    return label;
  }

  private static GridBagConstraints constraints(int x, int y, double weightX, int gridWidth) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.gridwidth = Math.max(1, gridWidth);
    constraints.weightx = weightX;
    constraints.fill = weightX > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(3, 4, 3, 4);
    return constraints;
  }

  private void updatePresetSummary() {
    CapturePreset selected = preset();
    String summary =
        switch (selected) {
          case LIVE_ESSENTIALS -> "BEP + console · graph queries after build";
          case PERFORMANCE_DIAGNOSTICS ->
              "Adds execution log + two profiles · graph queries after build";
          case FULL_GRAPH_DIAGNOSTICS ->
              "Same capture as Performance today · graph queries after build";
          case CUSTOM -> throw new IllegalStateException("Custom is not a visible launcher option");
        };
    String explanation =
        switch (selected) {
          case LIVE_ESSENTIALS ->
              "Live BEP and console; no execution log, timing trace, or Starlark CPU profile.";
          case PERFORMANCE_DIAGNOSTICS ->
              "Recommended: adds the execution log, timing trace, and Starlark CPU profile"
                  + " to live BEP and console.";
          case FULL_GRAPH_DIAGNOSTICS ->
              "Currently the same sources as Performance Diagnostics; it adds no graph capture"
                  + " today.";
          case CUSTOM -> throw new IllegalStateException("Custom is not a visible launcher option");
        };
    String tooltip =
        explanation
            + " After every build, BBV runs aquery and cquery and indexes both graphs,"
            + " using extra disk, CPU, and indexing time.";
    presetSummary.setText(summary);
    presetSummary.setToolTipText(PlainText.tooltip(tooltip));
    captureDetail.setToolTipText(PlainText.tooltip(tooltip));
    captureDetail.getAccessibleContext().setAccessibleDescription(tooltip);
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
    command
        .getActionMap()
        .put(
            "bbv-older-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                history.olderThan(command.getText()).ifPresent(LauncherPanel.this::recall);
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-newer-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                history.newerThan(command.getText()).ifPresent(LauncherPanel.this::recall);
              }
            });
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "bbv-older-command");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "bbv-newer-command");
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
    bazelExecutable
        .getDocument()
        .addDocumentListener(dirtyListener(Setting.BAZEL_EXECUTABLE, false));
    command.getDocument().addDocumentListener(dirtyListener(Setting.COMMAND, true));
    sshDestination.getDocument().addDocumentListener(dirtyListener(Setting.SSH_DESTINATION, false));
    sshPort.getDocument().addDocumentListener(dirtyListener(Setting.SSH_PORT, false));
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
  public void attachPersistence(Path settingsDirectory) {
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
    checkedExecutor.execute(
        () -> {
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
    LauncherStateStore.State current = snapshot();
    Set<Setting> edited = Set.copyOf(editedBeforeLoadCompletes);
    LauncherStateStore.State merged = mergeLoaded(loaded, current, edited);
    loadPending = false;
    persistenceReady = true;
    editedBeforeLoadCompletes.clear();
    queue.initialize(loaded);
    adopt(merged);
    preserveInactiveHostDraft(current, merged, edited);
    ManagedWorkspace managed = managedWorkspaceSelection;
    if (managed != null) {
      applyManagedWorkspace(managed);
    }
    queue.request(managed == null ? merged : snapshot());
  }

  /** Keeps an edit made before a different persisted host became visible. */
  private void preserveInactiveHostDraft(
      LauncherStateStore.State current, LauncherStateStore.State merged, Set<Setting> edited) {
    if (current.executionHost() == merged.executionHost()) {
      return;
    }
    if (current.executionHost() == ExecutionHost.LOCAL) {
      if (edited.contains(Setting.WORKSPACE)) {
        localWorkspaceDraft = current.workspace();
      }
      if (edited.contains(Setting.BAZEL_EXECUTABLE)) {
        localBazelDraft = current.bazelExecutable();
      }
    } else {
      if (edited.contains(Setting.WORKSPACE)) {
        remoteWorkspaceDraft = current.workspace();
      }
      if (edited.contains(Setting.BAZEL_EXECUTABLE)) {
        remoteBazelDraft = current.bazelExecutable();
      }
    }
  }

  private void finishClosedLoad(SaveQueue queue, LauncherStateStore.State loaded) {
    ClosedState closed = closedWhileLoading;
    queue.initialize(loaded);
    if (closed != null) {
      completePersistenceClose(
          queue.closeWhenSettled(mergeLoaded(loaded, closed.snapshot(), closed.edited())));
    } else {
      persistenceClose.complete(null);
    }
  }

  private void completePersistenceClose(CompletionStage<Void> completion) {
    completion.whenComplete(
        (ignored, failure) -> {
          if (failure == null) {
            persistenceClose.complete(null);
          } else {
            persistenceClose.completeExceptionally(failure);
          }
        });
  }

  private void adopt(LauncherStateStore.State loaded) {
    applying = true;
    try {
      workspace.setText(loaded.workspace());
      bazelExecutable.setText(loaded.bazelExecutable());
      captureDetail.setSelectedItem(visibleOrDefault(loaded.preset()));
      executionHost.setSelectedItem(loaded.executionHost());
      sshDestination.setText(loaded.sshDestination());
      sshPort.setText(loaded.sshPort());
      sshProfiles = loaded.sshProfiles();
      String selectedProfile =
          loaded.sshProfiles().stream()
              .filter(
                  profile ->
                      profile.destination().equals(loaded.sshDestination())
                          && profile.port().equals(loaded.sshPort())
                          && profile.workingDirectory().equals(loaded.workspace()))
              .map(SshConnectionProfile::key)
              .findFirst()
              .orElse("");
      refreshSavedSshConnections(selectedProfile);
      command.setText(loaded.command());
      history.replaceNewestFirst(loaded.history());
      displayedHost = loaded.executionHost();
      if (displayedHost == ExecutionHost.LOCAL) {
        localWorkspaceDraft = loaded.workspace();
        localBazelDraft = loaded.bazelExecutable();
      } else {
        remoteWorkspaceDraft = loaded.workspace();
        remoteBazelDraft = loaded.bazelExecutable();
      }
      saveDebounce.stop();
    } finally {
      applying = false;
    }
    updatePresetSummary();
    updateHostControls();
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
        history.entries(),
        executionHost(),
        sshDestination.getText(),
        sshPort.getText(),
        sshProfiles);
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
   * <p>If loading is still pending, the current values and their edited-field set are captured on
   * the EDT. The load worker later merges and persists them without adopting the result into this
   * disposed panel.
   */
  public void close() {
    closeAsync();
  }

  /**
   * Stops callbacks and completes after the final launcher snapshot has reached storage.
   *
   * <p>The snapshot itself is captured synchronously on the EDT. Loading, merging, and saving
   * remain on the persistence executor, including when the window closes before its initial load
   * finishes.
   */
  public CompletionStage<Void> closeAsync() {
    if (disposed) {
      return persistenceClose;
    }
    saveDebounce.stop();
    if (loadPending) {
      closedWhileLoading = new ClosedState(snapshot(), editedBeforeLoadCompletes);
    } else if (persistenceReady && saveQueue != null) {
      completePersistenceClose(saveQueue.closeWhenSettled(snapshot()));
    } else {
      persistenceClose.complete(null);
    }
    disposed = true;
    return persistenceClose;
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

  /**
   * Uses a workspace selected by the application-level workspace manager. The launcher then shows
   * one read-only summary instead of another host, connection, directory, and executable form.
   */
  public void useManagedWorkspace(
      String name,
      ExecutionHost host,
      String workingDirectory,
      String executable,
      String destination,
      String port) {
    ManagedWorkspace selected =
        new ManagedWorkspace(
            Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(host, "host"),
            Objects.requireNonNull(workingDirectory, "workingDirectory"),
            Objects.requireNonNull(executable, "executable"),
            destination == null ? "" : destination,
            port == null ? "" : port);
    managedWorkspaceSelection = selected;
    applyManagedWorkspace(selected);
  }

  private void applyManagedWorkspace(ManagedWorkspace selected) {
    applying = true;
    try {
      executionHost.setSelectedItem(selected.host());
      displayedHost = selected.host();
      workspace.setText(selected.workingDirectory());
      bazelExecutable.setText(selected.executable());
      sshDestination.setText(selected.destination());
      sshPort.setText(selected.port());
      managedWorkspace = true;
      String machine =
          selected.host() == ExecutionHost.LOCAL
              ? "This computer"
              : sshDestination.getText().strip()
                  + (sshPort.getText().isBlank() ? "" : ":" + sshPort.getText().strip());
      selectedWorkspace.setText(selected.name() + " · " + machine + " · " + workspace.getText());
      selectedWorkspace.setCaretPosition(0);
      selectedWorkspace.setToolTipText(PlainText.tooltip(selectedWorkspace.getText()));
    } finally {
      applying = false;
    }
    updateHostControls();
  }

  /** Clears the application-level selection while retaining command history. */
  public void clearManagedWorkspace() {
    managedWorkspaceSelection = null;
    managedWorkspace = false;
    selectedWorkspace.setText("");
    updateHostControls();
  }

  public boolean hasManagedWorkspace() {
    return managedWorkspace;
  }

  public String command() {
    return command.getText();
  }

  /**
   * Clears a restored draft command while retaining completion history.
   *
   * <p>This may be called immediately after constructing a workspace window. If persistence is
   * still loading, the explicit blank value wins the merge instead of briefly restoring the prior
   * command and overwriting this choice.
   */
  public void clearCommandOnInitialLoad() {
    if (disposed) {
      return;
    }
    applying = true;
    try {
      command.setText("");
    } finally {
      applying = false;
    }
    if (!persistenceReady) {
      editedBeforeLoadCompletes.add(Setting.COMMAND);
    } else {
      flushPersistence();
    }
  }

  public ExecutionHost executionHost() {
    ExecutionHost selected = (ExecutionHost) executionHost.getSelectedItem();
    return selected == null ? ExecutionHost.LOCAL : selected;
  }

  public boolean isRemote() {
    return executionHost() == ExecutionHost.SSH;
  }

  public String sshDestination() {
    return sshDestination.getText().trim();
  }

  /** Empty uses SSH config/default; malformed values are rejected before preflight. */
  public OptionalInt sshPort() {
    String text = sshPort.getText().trim();
    if (text.isEmpty()) {
      return OptionalInt.empty();
    }
    int port;
    try {
      port = Integer.parseInt(text);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("SSH port must be a number in 1..65535", invalid);
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("SSH port must be in 1..65535");
    }
    return OptionalInt.of(port);
  }

  /** Saves or updates the current non-secret SSH settings for later launches. */
  public void rememberSshConnection() {
    if (!isRemote()) {
      return;
    }
    SshConnectionProfile current =
        new SshConnectionProfile(
            sshDestination(), sshPort.getText(), workspace(), bazelExecutable());
    ArrayList<SshConnectionProfile> merged = new ArrayList<>();
    merged.add(current);
    for (SshConnectionProfile profile : sshProfiles) {
      if (!profile.key().equals(current.key())
          && merged.size() < SshConnectionProfile.MAX_SAVED_PROFILES) {
        merged.add(profile);
      }
    }
    sshProfiles = List.copyOf(merged);
    refreshSavedSshConnections(current.key());
    markDirty(Setting.SSH_PROFILES);
    flushPersistence();
  }

  public CapturePreset preset() {
    CapturePreset selected = (CapturePreset) captureDetail.getSelectedItem();
    return visibleOrDefault(selected);
  }

  public void setRunEnabled(boolean enabled) {
    run.setEnabled(enabled);
    executionHost.setEnabled(enabled);
    savedSshConnections.setEnabled(enabled);
    forgetSshConnection.setEnabled(
        enabled && isRemote() && savedSshConnections.getSelectedIndex() > 0);
    sshDestination.setEnabled(enabled);
    sshPort.setEnabled(enabled);
    workspace.setEnabled(enabled);
    bazelExecutable.setEnabled(enabled);
    captureDetail.setEnabled(enabled);
    command.setEnabled(enabled);
    chooseWorkspace.setEnabled(enabled);
    chooseBazel.setEnabled(enabled);
    changeWorkspace.setEnabled(enabled);
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
      LauncherStateStore.State loaded, LauncherStateStore.State current, Set<Setting> edited) {
    ExecutionHost mergedHost =
        edited.contains(Setting.EXECUTION_HOST) ? current.executionHost() : loaded.executionHost();
    boolean currentFieldsBelongToMergedHost = current.executionHost() == mergedHost;
    List<String> mergedHistory = loaded.history();
    if (edited.contains(Setting.HISTORY)) {
      LinkedHashSet<String> unique = new LinkedHashSet<>(current.history());
      unique.addAll(loaded.history());
      mergedHistory = unique.stream().limit(LauncherHistory.MAX_ENTRIES).toList();
    }
    return new LauncherStateStore.State(
        edited.contains(Setting.WORKSPACE) && currentFieldsBelongToMergedHost
            ? current.workspace()
            : loaded.workspace(),
        edited.contains(Setting.BAZEL_EXECUTABLE) && currentFieldsBelongToMergedHost
            ? current.bazelExecutable()
            : loaded.bazelExecutable(),
        edited.contains(Setting.PRESET) ? current.preset() : loaded.preset(),
        edited.contains(Setting.COMMAND) ? current.command() : loaded.command(),
        mergedHistory,
        mergedHost,
        edited.contains(Setting.SSH_DESTINATION)
            ? current.sshDestination()
            : loaded.sshDestination(),
        edited.contains(Setting.SSH_PORT) ? current.sshPort() : loaded.sshPort(),
        edited.contains(Setting.SSH_PROFILES)
            ? mergeProfiles(current.sshProfiles(), loaded.sshProfiles())
            : loaded.sshProfiles());
  }

  private static List<SshConnectionProfile> mergeProfiles(
      List<SshConnectionProfile> preferred, List<SshConnectionProfile> fallback) {
    LinkedHashMap<String, SshConnectionProfile> merged = new LinkedHashMap<>();
    for (SshConnectionProfile profile : preferred) {
      merged.putIfAbsent(profile.key(), profile);
    }
    for (SshConnectionProfile profile : fallback) {
      if (merged.size() == SshConnectionProfile.MAX_SAVED_PROFILES) {
        break;
      }
      merged.putIfAbsent(profile.key(), profile);
    }
    return List.copyOf(merged.values());
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

  List<String> historyEntriesForTest() {
    return history.entries();
  }

  JComboBox<ExecutionHost> executionHostForTest() {
    return executionHost;
  }

  JTextField sshDestinationForTest() {
    return sshDestination;
  }

  JTextField sshPortForTest() {
    return sshPort;
  }

  JPanel sshOptionsForTest() {
    return sshOptions;
  }

  JComboBox<String> savedSshConnectionsForTest() {
    return savedSshConnections;
  }

  JLabel presetSummaryForTest() {
    return presetSummary;
  }

  JButton chooseWorkspaceForTest() {
    return chooseWorkspace;
  }

  JButton chooseBazelForTest() {
    return chooseBazel;
  }

  JTextField selectedWorkspaceForTest() {
    return selectedWorkspace;
  }

  JButton changeWorkspaceForTest() {
    return changeWorkspace;
  }

  private static final class PresetRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected, boolean focused) {
      CapturePreset preset = value instanceof CapturePreset item ? item : null;
      String text =
          preset == null
              ? ""
              : preset.displayName()
                  + (preset == CapturePreset.PERFORMANCE_DIAGNOSTICS ? " (recommended)" : "");
      return super.getListCellRendererComponent(list, text, index, selected, focused);
    }
  }

  private enum Setting {
    WORKSPACE,
    BAZEL_EXECUTABLE,
    EXECUTION_HOST,
    SSH_DESTINATION,
    SSH_PORT,
    SSH_PROFILES,
    PRESET,
    COMMAND,
    HISTORY
  }

  private record ManagedWorkspace(
      String name,
      ExecutionHost host,
      String workingDirectory,
      String executable,
      String destination,
      String port) {}

  private record ClosedState(LauncherStateStore.State snapshot, Set<Setting> edited) {
    private ClosedState {
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
      edited = Set.copyOf(edited);
    }
  }

  /**
   * Serializes launcher saves without making completion callbacks touch Swing. The newest requested
   * snapshot remains desired while an older write is in flight, so a stale completion can never
   * become the final disk value.
   */
  private static final class SaveQueue {
    private final LauncherStateStore store;
    private final Executor executor;
    private LauncherStateStore.State persisted;
    private LauncherStateStore.State desired;
    private LauncherStateStore.State inFlight;
    private boolean initialized;
    private boolean closeRequested;
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

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

    private synchronized CompletionStage<Void> closeWhenSettled(LauncherStateStore.State state) {
      desired = state;
      closeRequested = true;
      startNextIfNeeded();
      completeCloseIfSettled();
      return closeCompletion;
    }

    private void startNextIfNeeded() {
      if (!initialized || inFlight != null || desired == null || desired.equals(persisted)) {
        return;
      }
      LauncherStateStore.State next = desired;
      inFlight = next;
      try {
        executor.execute(() -> complete(next, store.save(next)));
      } catch (RuntimeException rejected) {
        inFlight = null;
        if (closeRequested) {
          closeCompletion.completeExceptionally(rejected);
        }
        throw rejected;
      }
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
      if (closeRequested && !success && !superseded) {
        closeCompletion.completeExceptionally(
            new IllegalStateException("launcher settings could not be saved"));
      }
      completeCloseIfSettled();
    }

    private void completeCloseIfSettled() {
      if (closeRequested && initialized && inFlight == null && Objects.equals(desired, persisted)) {
        closeCompletion.complete(null);
      }
    }
  }

  private static final class SharedIo {
    private static final ExecutorService EXECUTOR =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-launcher-settings");
              thread.setDaemon(true);
              return thread;
            });

    private SharedIo() {}
  }
}

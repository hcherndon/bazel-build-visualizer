package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
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
import java.util.function.Predicate;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.ComboPopup;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.AutoCompletionEvent;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

/**
 * The compact launcher at the top of the Console card.
 *
 * <p>Its compact controls are deliberately labelled rather than inferred from field order. Pressing
 * Run only calls the window's preflight action; ADR-007's separate effective-command review remains
 * the only path to process launch. Preference I/O is delegated to a background executor and only
 * immutable snapshots cross back onto the EDT.
 */
public final class LauncherPanel extends JPanel {

  private static final long serialVersionUID = 1L;
  private static final String NEW_SSH_CONNECTION = "New connection…";
  private static int immediatePresetTooltipUsers;
  private static int savedTooltipInitialDelay;

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

  private final JTextField workspace = new JTextField(34);
  private final JTextField bazelExecutable = new JTextField(24);
  private final JComboBox<LaunchMode> captureDetail = new JComboBox<>(LaunchMode.values());
  private final JTextField command = new HistoryCommandField(34);
  private final JComboBox<ExecutionHost> executionHost = new JComboBox<>(ExecutionHost.values());
  private final JTextField sshDestination = new JTextField(22);
  private final JTextField sshPort = new JTextField(5);
  private final JPanel sshOptions = new JPanel(new GridBagLayout());
  private final JComboBox<String> savedSshConnections = new JComboBox<>();
  private final JButton forgetSshConnection = new JButton("Forget");
  private final JButton chooseWorkspace = new JButton("Choose workspace…");
  private final JTextField selectedWorkspace = new JTextField();
  private final JButton changeWorkspace = new JButton("Change workspace…");
  private final JButton run = new JButton("Run");
  private final JPopupMenu recentCommandsMenu = new JPopupMenu();
  private final DefaultListModel<String> recentCommandsModel = new DefaultListModel<>();
  private final JList<String> recentCommandsList =
      new JList<>(recentCommandsModel) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(MouseEvent event) {
          int index = locationToIndex(event.getPoint());
          Rectangle bounds = index < 0 ? null : getCellBounds(index, index);
          return bounds == null || !bounds.contains(event.getPoint())
              ? null
              : PlainText.tooltip(getModel().getElementAt(index));
        }
      };
  private final JScrollPane recentCommandsScroll = new JScrollPane(recentCommandsList);
  private final LauncherHistory history = new LauncherHistory();
  private final Runnable runAction;
  private final Predicate<String> managedBazelCommit;
  private AutoCompletion commandCompletion;
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
  private boolean pageToolbarInstalled;
  private boolean immediatePresetTooltipsActive;
  private CapturePreset lastNormalPreset = CapturePreset.defaultPreset();
  private ManagedWorkspace managedWorkspaceSelection;
  private String lastCommittedManagedBazel = "";

  private JLabel hostLabel;
  private JLabel workspaceLabel;
  private JLabel bazelLabel;
  private JLabel selectedWorkspaceLabel;
  private JPanel hostRow;

  public LauncherPanel(Runnable runAction, Runnable chooseWorkspaceAction) {
    this(runAction, chooseWorkspaceAction, ignored -> true);
  }

  public LauncherPanel(
      Runnable runAction, Runnable chooseWorkspaceAction, Predicate<String> managedBazelCommit) {
    super(new GridBagLayout());
    Objects.requireNonNull(runAction, "runAction");
    Objects.requireNonNull(chooseWorkspaceAction, "chooseWorkspaceAction");
    this.runAction = runAction;
    this.managedBazelCommit = Objects.requireNonNull(managedBazelCommit, "managedBazelCommit");

    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Launch a Bazel build"),
            BorderFactory.createEmptyBorder(2, 8, 6, 8)));
    saveDebounce.setRepeats(false);

    LauncherStateStore.State defaults = LauncherStateStore.State.defaults();
    workspace.setText(defaults.workspace());
    bazelExecutable.setText(defaults.bazelExecutable());
    lastNormalPreset = LaunchMode.fromPreset(defaults.preset()).preset();
    captureDetail.setSelectedItem(LaunchMode.fromPreset(lastNormalPreset));
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
    selectedWorkspace.setName("launcher.selectedWorkspace");
    changeWorkspace.setName("launcher.changeWorkspace");
    run.setName("launcher.run");
    recentCommandsMenu.setName("launcher.recentCommandsMenu");
    recentCommandsList.setName("launcher.recentCommandsList");
    recentCommandsScroll.setName("launcher.recentCommandsScroll");

    workspace.setToolTipText(
        PlainText.tooltip(
            "Where the build runs. Relative targets resolve against this directory."));
    bazelExecutable.setToolTipText(
        PlainText.tooltip(
            "A Bazel command found on PATH, such as bazel or bazelisk, or a path to its"
                + " executable on the selected Workspace machine."));
    command.setToolTipText(
        PlainText.tooltip(
            "Arguments passed to the selected Bazel executable, for example test //...."
                + " Click to show up to "
                + LauncherHistory.MAX_ENTRIES
                + " unique recent commands. Up/Down selects one, Tab fills it, and Enter"
                + " runs it. Start typing to enter a new command; Ctrl+Space completes a"
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
    selectedWorkspace.setEditable(false);
    selectedWorkspace.setFocusable(true);
    selectedWorkspace.setToolTipText(
        PlainText.tooltip("The selected repository and machine. Use Workspaces to change it."));
    changeWorkspace.setToolTipText("Return to the workspace chooser");
    captureDetail.setRenderer(new PresetRenderer());
    captureDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
    captureDetail.addActionListener(
        event -> {
          updatePresetTooltip();
          if (launchMode() != LaunchMode.HERMETICITY_DIAGNOSTIC) {
            lastNormalPreset = preset();
            markDirty(Setting.PRESET);
          }
        });
    captureDetail.addPopupMenuListener(
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            beginImmediatePresetTooltips();
            SwingUtilities.invokeLater(LauncherPanel.this::installPresetPopupTooltips);
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            endImmediatePresetTooltips();
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent event) {
            endImmediatePresetTooltips();
          }
        });
    updatePresetTooltip();

    executionHost.addActionListener(
        event -> {
          switchHostDraft();
          updateHostControls();
          markDirty(Setting.EXECUTION_HOST);
        });
    savedSshConnections.addActionListener(event -> selectSshProfile());
    forgetSshConnection.addActionListener(event -> forgetSelectedSshConnection());

    chooseWorkspace.addActionListener(event -> chooseWorkspaceAction.run());
    changeWorkspace.addActionListener(event -> chooseWorkspaceAction.run());
    run.addActionListener(event -> runCurrentCommand());
    command.addActionListener(event -> runCurrentCommand());
    bazelExecutable.addActionListener(event -> commitManagedBazelExecutable());
    bazelExecutable.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(FocusEvent event) {
            commitManagedBazelExecutable();
          }
        });

    installCompletion();
    installHistoryNavigation();
    installRecentCommandsChooser();
    installPersistenceListeners();
    buildForm();
    refreshRecentCommands();
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
    JLabel detailLabel = label("Build mode", captureDetail, "launcher.captureDetailLabel");
    JLabel commandLabel = label("Bazel command", command, "launcher.commandLabel");

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

    JPanel launchOptions = new JPanel(new GridBagLayout());
    launchOptions.setName("launcher.optionsRow");
    launchOptions.add(bazelExecutable, inlineConstraints(0, 0, 0));
    launchOptions.add(detailLabel, inlineConstraints(1, 8, 0));
    launchOptions.add(captureDetail, inlineConstraints(2, 8, 1));
    add(bazelLabel, constraints(0, 2, 0, 0));
    add(launchOptions, constraints(1, 2, 1, 2));

    add(commandLabel, constraints(0, 3, 0, 0));
    add(command, constraints(1, 3, 1, 1));
    add(run, constraints(2, 3, 0, 0));

    selectedWorkspaceLabel =
        label("Workspace", selectedWorkspace, "launcher.selectedWorkspaceLabel");
    selectedWorkspaceLabel.setVisible(false);
    selectedWorkspace.setVisible(false);
    changeWorkspace.setVisible(false);
    add(selectedWorkspaceLabel, constraints(0, 0, 0, 0));
    add(selectedWorkspace, constraints(1, 0, 1, 1));
    add(changeWorkspace, constraints(2, 0, 0, 0));
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

  private static GridBagConstraints inlineConstraints(int x, int leftInset, double weightX) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.weightx = weightX;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, leftInset, 0, 0);
    return constraints;
  }

  private void updateHostControls() {
    boolean remote = executionHost() == ExecutionHost.SSH;
    hostLabel.setVisible(!managedWorkspace);
    hostRow.setVisible(!managedWorkspace);
    executionHost.setVisible(!managedWorkspace);
    workspaceLabel.setVisible(!managedWorkspace);
    workspace.setVisible(!managedWorkspace);
    bazelLabel.setVisible(true);
    bazelExecutable.setVisible(true);
    selectedWorkspaceLabel.setVisible(managedWorkspace && !pageToolbarInstalled);
    selectedWorkspace.setVisible(managedWorkspace && !pageToolbarInstalled);
    changeWorkspace.setVisible(managedWorkspace);
    sshOptions.setVisible(!managedWorkspace && remote);
    chooseWorkspace.setVisible(!managedWorkspace && !remote);
    forgetSshConnection.setEnabled(remote && savedSshConnections.getSelectedIndex() > 0);
    workspace.setToolTipText(
        PlainText.tooltip(
            remote
                ? "Absolute working directory on the SSH host. Relative targets resolve here."
                : "Where the build runs. Relative targets resolve against this directory."));
    bazelExecutable.setToolTipText(
        PlainText.tooltip(
            remote
                ? "A Bazel command on PATH or an absolute executable path on the SSH host."
                : "A Bazel command found on PATH, such as bazel or bazelisk, or a path to its"
                    + " executable on this computer."));
    revalidate();
    repaint();
  }

  /** Moves the managed-workspace action into the Console page chrome and removes duplicate copy. */
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbarInstalled) {
      throw new IllegalStateException("the Console page toolbar is already installed");
    }
    pageToolbarInstalled = true;
    setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    toolbar.addAction(changeWorkspace);
    updateHostControls();
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

  private void updatePresetTooltip() {
    LaunchMode selected = launchMode();
    String tooltip = selected.tooltip();
    captureDetail.setToolTipText(PlainText.tooltip(tooltip));
    captureDetail.getAccessibleContext().setAccessibleDescription(tooltip);
    boolean diagnostic = selected == LaunchMode.HERMETICITY_DIAGNOSTIC;
    run.setText(diagnostic ? "Run diagnostic" : "Run");
    run.setToolTipText(
        diagnostic
            ? PlainText.tooltip(tooltip)
            : "Preflight the command and show the effective command for review");
  }

  private void beginImmediatePresetTooltips() {
    if (immediatePresetTooltipsActive) {
      return;
    }
    ToolTipManager tooltips = ToolTipManager.sharedInstance();
    if (immediatePresetTooltipUsers == 0) {
      savedTooltipInitialDelay = tooltips.getInitialDelay();
      tooltips.setInitialDelay(0);
    }
    immediatePresetTooltipUsers++;
    immediatePresetTooltipsActive = true;
  }

  private void endImmediatePresetTooltips() {
    if (!immediatePresetTooltipsActive) {
      return;
    }
    immediatePresetTooltipsActive = false;
    immediatePresetTooltipUsers = Math.max(0, immediatePresetTooltipUsers - 1);
    if (immediatePresetTooltipUsers == 0) {
      ToolTipManager.sharedInstance().setInitialDelay(savedTooltipInitialDelay);
    }
  }

  private void installPresetPopupTooltips() {
    Object child = captureDetail.getAccessibleContext().getAccessibleChild(0);
    if (!(child instanceof ComboPopup popup)) {
      return;
    }
    JList<?> list = popup.getList();
    if (Boolean.TRUE.equals(list.getClientProperty("bbv.presetTooltipsInstalled"))) {
      return;
    }
    list.putClientProperty("bbv.presetTooltipsInstalled", Boolean.TRUE);
    list.setToolTipText(captureDetail.getToolTipText());
    list.addMouseMotionListener(
        new MouseMotionAdapter() {
          @Override
          public void mouseMoved(MouseEvent event) {
            int index = list.locationToIndex(event.getPoint());
            Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
            Object value =
                bounds != null && bounds.contains(event.getPoint())
                    ? list.getModel().getElementAt(index)
                    : null;
            list.setToolTipText(
                value instanceof LaunchMode mode ? PlainText.tooltip(mode.tooltip()) : null);
          }
        });
  }

  private void installCompletion() {
    DefaultCompletionProvider provider = new DefaultCompletionProvider();
    for (String subcommand : COMMON_SUBCOMMANDS) {
      provider.addCompletion(new BasicCompletion(provider, subcommand, "Bazel subcommand"));
    }
    provider.setAutoActivationRules(false, null);
    commandCompletion = new AutoCompletion(provider);
    commandCompletion.setAutoActivationEnabled(false);
    commandCompletion.addAutoCompletionListener(
        event -> {
          if (event.getEventType() == AutoCompletionEvent.Type.POPUP_SHOWN) {
            hideRecentCommands();
          }
        });
    commandCompletion.install(command);
  }

  private void installHistoryNavigation() {
    command
        .getActionMap()
        .put(
            "bbv-older-recent-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                moveRecentCommandSelection(-1);
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-newer-recent-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                moveRecentCommandSelection(1);
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-fill-recent-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                if (!fillSelectedRecentCommand()) {
                  command.transferFocus();
                }
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-run-recent-command",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                runCurrentCommand();
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-close-recent-commands",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                hideRecentCommands();
              }
            });
    command
        .getActionMap()
        .put(
            "bbv-previous-launcher-field",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                hideRecentCommands();
                command.transferFocusBackward();
              }
            });
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "bbv-older-recent-command");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "bbv-newer-recent-command");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "bbv-fill-recent-command");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(
            KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK),
            "bbv-previous-launcher-field");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "bbv-run-recent-command");
    command
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "bbv-close-recent-commands");
    command.setFocusTraversalKeysEnabled(false);
    command.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent event) {
            SwingUtilities.invokeLater(
                () -> {
                  if (command.hasFocus()) {
                    showRecentCommands();
                  }
                });
          }

          @Override
          public void focusLost(FocusEvent event) {
            if (commandCompletion == null || !commandCompletion.isPopupVisible()) {
              hideRecentCommands();
            }
          }
        });
    command.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseReleased(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event) && command.isEnabled()) {
              showRecentCommands();
            }
          }
        });
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

  private void showRecentCommands() {
    if (recentCommandsModel.isEmpty() || !command.isEnabled()) {
      return;
    }
    recentCommandsList.clearSelection();
    sizeRecentCommandsPopup(availableScreenSize());
    recentCommandsMenu.setInvoker(command);
    if (command.isShowing() && !recentCommandsMenu.isVisible()) {
      recentCommandsMenu.show(command, 0, command.getHeight());
    }
  }

  private void refreshRecentCommands() {
    recentCommandsModel.clear();
    List<String> commands = history.entries();
    for (String priorCommand : commands) {
      recentCommandsModel.addElement(priorCommand);
    }
    if (commands.isEmpty()) {
      hideRecentCommands();
    }
  }

  private void installRecentCommandsChooser() {
    recentCommandsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    recentCommandsList.setVisibleRowCount(5);
    recentCommandsList.setCellRenderer(new RecentCommandRenderer());
    recentCommandsList.setFocusable(false);
    recentCommandsList.setToolTipText("");
    recentCommandsScroll.setFocusable(false);
    recentCommandsScroll.getViewport().setFocusable(false);
    recentCommandsScroll.getHorizontalScrollBar().setFocusable(false);
    recentCommandsScroll.getVerticalScrollBar().setFocusable(false);
    recentCommandsMenu.setFocusable(false);
    recentCommandsList.getAccessibleContext().setAccessibleName("Recent Bazel commands");
    recentCommandsList
        .getAccessibleContext()
        .setAccessibleDescription(
            "Commands previously run in this Workspace, newest first. Use Up and Down in the"
                + " command field, Tab to fill, or Enter to run.");
    recentCommandsList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseReleased(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)) {
              return;
            }
            int index = recentCommandsList.locationToIndex(event.getPoint());
            Rectangle bounds = index < 0 ? null : recentCommandsList.getCellBounds(index, index);
            if (bounds != null && bounds.contains(event.getPoint())) {
              recentCommandsList.setSelectedIndex(index);
              fillSelectedRecentCommand();
            }
          }
        });
    recentCommandsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    recentCommandsMenu.add(recentCommandsScroll);
    recentCommandsMenu.addPopupMenuListener(
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent event) {}

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            recentCommandsList.clearSelection();
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent event) {
            recentCommandsList.clearSelection();
          }
        });
  }

  private void moveRecentCommandSelection(int direction) {
    if (recentCommandsModel.isEmpty()
        || (commandCompletion != null && commandCompletion.isPopupVisible())) {
      return;
    }
    if (!recentCommandsMenu.isVisible()) {
      sizeRecentCommandsPopup(availableScreenSize());
      recentCommandsMenu.setInvoker(command);
      if (command.isShowing()) {
        recentCommandsMenu.show(command, 0, command.getHeight());
      }
    }
    int current = recentCommandsList.getSelectedIndex();
    int selected =
        current < 0
            ? 0
            : Math.max(0, Math.min(recentCommandsModel.getSize() - 1, current + direction));
    recentCommandsList.setSelectedIndex(selected);
    recentCommandsList.ensureIndexIsVisible(selected);
  }

  private boolean fillSelectedRecentCommand() {
    String selected = recentCommandsList.getSelectedValue();
    if (selected == null) {
      return false;
    }
    recall(selected);
    hideRecentCommands();
    command.requestFocusInWindow();
    return true;
  }

  private void runCurrentCommand() {
    if (!commitManagedBazelExecutable()) {
      return;
    }
    fillSelectedRecentCommand();
    hideRecentCommands();
    runAction.run();
  }

  private void hideRecentCommands() {
    if (recentCommandsMenu.isVisible()) {
      recentCommandsMenu.setVisible(false);
    }
    recentCommandsList.clearSelection();
  }

  private Dimension availableScreenSize() {
    GraphicsConfiguration configuration = getGraphicsConfiguration();
    if (configuration != null) {
      return configuration.getBounds().getSize();
    }
    try {
      return Toolkit.getDefaultToolkit().getScreenSize();
    } catch (RuntimeException unavailable) {
      return new Dimension(
          Math.max(getWidth(), command.getPreferredSize().width),
          Math.max(getHeight(), command.getPreferredSize().height));
    }
  }

  private void sizeRecentCommandsPopup(Dimension available) {
    int scrollBarWidth = recentCommandsScroll.getVerticalScrollBar().getPreferredSize().width;
    int maximumPopupWidth = Math.max(1, available.width - 48);
    int desiredViewportWidth =
        command.getWidth() > 0 ? command.getWidth() : command.getPreferredSize().width;
    int viewportWidth =
        Math.max(
            1,
            Math.min(
                desiredViewportWidth,
                maximumPopupWidth - Math.min(scrollBarWidth + 4, maximumPopupWidth - 1)));
    FontMetrics metrics = recentCommandsList.getFontMetrics(recentCommandsList.getFont());
    int rowHeight = Math.max(recentCommandsList.getFixedCellHeight(), metrics.getHeight() + 8);
    int visibleRows = Math.min(5, Math.max(1, recentCommandsModel.getSize()));
    recentCommandsList.setVisibleRowCount(visibleRows);
    int viewportHeight = rowHeight * visibleRows;
    recentCommandsList.setFixedCellWidth(viewportWidth);
    recentCommandsList.setFixedCellHeight(rowHeight);
    recentCommandsScroll.setPreferredSize(
        new Dimension(
            Math.min(maximumPopupWidth, viewportWidth + scrollBarWidth + 4), viewportHeight + 4));
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
          recentCommandsList.clearSelection();
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
    attachPersistenceStore(newStore, executor);
  }

  /** Attaches discovered-Workspace conveniences; no machine, repository, or draft is written. */
  public void attachHistoryPersistence(Path settingsDirectory) {
    attachHistoryPersistence(new LauncherHistoryStore(settingsDirectory), SharedIo.EXECUTOR);
  }

  /** Explicit history-store/executor seam for focused headless tests. */
  void attachHistoryPersistence(LauncherHistoryStore newStore, Executor executor) {
    attachPersistenceStore(newStore, executor);
  }

  private void attachPersistenceStore(LauncherSettingsStore newStore, Executor executor) {
    if (disposed) {
      return;
    }
    LauncherSettingsStore checkedStore = Objects.requireNonNull(newStore, "newStore");
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
    LauncherStateStore.State merged = mergeStoreLoaded(queue.store(), loaded, current, edited);
    loadPending = false;
    persistenceReady = true;
    editedBeforeLoadCompletes.clear();
    queue.initialize(loaded);
    adopt(merged);
    preserveInactiveHostDraft(current, merged, edited);
    ManagedWorkspace managed = managedWorkspaceSelection;
    if (managed != null) {
      ManagedWorkspace restored =
          queue.store().discoveredWorkspaceOnly()
              ? managed.withExecutable(merged.bazelExecutable())
              : managed;
      managedWorkspaceSelection = restored;
      applyManagedWorkspace(restored);
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
          queue.closeWhenSettled(
              mergeStoreLoaded(queue.store(), loaded, closed.snapshot(), closed.edited())));
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
      lastNormalPreset = LaunchMode.fromPreset(loaded.preset()).preset();
      // An asynchronous settings load must not undo a diagnostic explicitly selected in this
      // window. Its ordinary preset still loads normally for the next window and later saves.
      if (launchMode() != LaunchMode.HERMETICITY_DIAGNOSTIC) {
        captureDetail.setSelectedItem(LaunchMode.fromPreset(lastNormalPreset));
      }
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
      refreshRecentCommands();
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
    updatePresetTooltip();
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
        lastNormalPreset,
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
    refreshRecentCommands();
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
    endImmediatePresetTooltips();
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

  /** Commits a managed Workspace's edited Bazel command before it can be used. */
  public boolean commitManagedBazelExecutable() {
    if (!managedWorkspace) {
      return true;
    }
    String candidate = bazelExecutable.getText().strip();
    if (candidate.equals(lastCommittedManagedBazel)) {
      return true;
    }
    if (!managedBazelCommit.test(candidate)) {
      return false;
    }
    ManagedWorkspace current = managedWorkspaceSelection;
    if (current != null) {
      managedWorkspaceSelection = current.withExecutable(candidate);
    }
    applying = true;
    try {
      bazelExecutable.setText(candidate);
    } finally {
      applying = false;
    }
    lastCommittedManagedBazel = candidate;
    flushPersistence();
    return true;
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
    SaveQueue queue = saveQueue;
    if (persistenceReady && queue != null && queue.store().discoveredWorkspaceOnly()) {
      // MainWindow starts persistence before it applies the managed Workspace. If the small
      // discovered-Workspace sidecar won that race, retain its Bazel override instead of replacing
      // it with the discovery protocol's default "bazel".
      selected = selected.withExecutable(bazelExecutable.getText());
    }
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
      lastCommittedManagedBazel = selected.executable();
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
    lastCommittedManagedBazel = "";
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
    return launchMode().preset();
  }

  public LaunchMode launchMode() {
    LaunchMode selected = (LaunchMode) captureDetail.getSelectedItem();
    return selected == null ? LaunchMode.fromPreset(CapturePreset.defaultPreset()) : selected;
  }

  public void setRunEnabled(boolean enabled) {
    run.setEnabled(enabled);
    if (!enabled) {
      hideRecentCommands();
    }
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
    changeWorkspace.setEnabled(enabled);
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

  private static LauncherStateStore.State mergeStoreLoaded(
      LauncherSettingsStore store,
      LauncherStateStore.State loaded,
      LauncherStateStore.State current,
      Set<Setting> edited) {
    if (!store.discoveredWorkspaceOnly()) {
      return mergeLoaded(loaded, current, edited);
    }
    List<String> mergedHistory = loaded.history();
    if (edited.contains(Setting.HISTORY)) {
      LinkedHashSet<String> unique = new LinkedHashSet<>(current.history());
      unique.addAll(loaded.history());
      mergedHistory = unique.stream().limit(LauncherHistory.MAX_ENTRIES).toList();
    }
    return new LauncherStateStore.State(
        current.workspace(),
        edited.contains(Setting.BAZEL_EXECUTABLE)
            ? current.bazelExecutable()
            : loaded.bazelExecutable(),
        current.preset(),
        current.command(),
        mergedHistory,
        current.executionHost(),
        current.sshDestination(),
        current.sshPort(),
        current.sshProfiles());
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

  JComboBox<LaunchMode> presetChoiceForTest() {
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

  JButton chooseWorkspaceForTest() {
    return chooseWorkspace;
  }

  JTextField selectedWorkspaceForTest() {
    return selectedWorkspace;
  }

  JButton changeWorkspaceForTest() {
    return changeWorkspace;
  }

  JList<String> recentCommandsListForTest() {
    return recentCommandsList;
  }

  JPopupMenu recentCommandsMenuForTest() {
    return recentCommandsMenu;
  }

  JScrollPane recentCommandsScrollForTest() {
    return recentCommandsScroll;
  }

  void showRecentCommandsForTest() {
    showRecentCommands();
  }

  void sizeRecentCommandsPopupForTest(Dimension available) {
    sizeRecentCommandsPopup(available);
  }

  boolean processCommandKeyForTest(int keyCode, int modifiers) {
    KeyEvent event =
        new KeyEvent(
            command,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED);
    return ((HistoryCommandField) command).processForTest(event);
  }

  private final class HistoryCommandField extends JTextField {
    private static final long serialVersionUID = 1L;

    private HistoryCommandField(int columns) {
      super(columns);
    }

    @Override
    protected boolean processKeyBinding(
        KeyStroke keyStroke, KeyEvent event, int condition, boolean pressed) {
      if (pressed
          && event.getModifiersEx() == 0
          && (commandCompletion == null || !commandCompletion.isPopupVisible())
          && !recentCommandsModel.isEmpty()) {
        if (event.getKeyCode() == KeyEvent.VK_UP) {
          moveRecentCommandSelection(-1);
          return true;
        }
        if (event.getKeyCode() == KeyEvent.VK_DOWN) {
          moveRecentCommandSelection(1);
          return true;
        }
      }
      return super.processKeyBinding(keyStroke, event, condition, pressed);
    }

    private boolean processForTest(KeyEvent event) {
      return processKeyBinding(
          KeyStroke.getKeyStrokeForEvent(event), event, JComponent.WHEN_FOCUSED, true);
    }
  }

  private static final class PresetRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected, boolean focused) {
      LaunchMode mode = value instanceof LaunchMode item ? item : null;
      String text = mode == null ? "" : mode.displayName();
      JLabel label =
          (JLabel) super.getListCellRendererComponent(list, text, index, selected, focused);
      PlainText.disableHtml(label);
      label.setHorizontalAlignment(JLabel.LEFT);
      label.setToolTipText(mode == null ? null : PlainText.tooltip(mode.tooltip()));
      if (index >= 0 && selected) {
        list.setToolTipText(label.getToolTipText());
      }
      return label;
    }
  }

  private static final class RecentCommandRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;

    RecentCommandRenderer() {
      PlainText.disableHtml(this);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected, boolean focused) {
      JLabel label =
          (JLabel) super.getListCellRendererComponent(list, "", index, selected, focused);
      PlainText.disableHtml(label);
      String command = value == null ? "" : value.toString();
      int cellWidth = list.getFixedCellWidth();
      int textWidth =
          cellWidth > 0
              ? Math.max(1, cellWidth - label.getInsets().left - label.getInsets().right - 8)
              : Integer.MAX_VALUE;
      label.setText(ellipsize(command, label.getFontMetrics(label.getFont()), textWidth));
      label.setToolTipText(PlainText.tooltip(command));
      return label;
    }

    private static String ellipsize(String value, FontMetrics metrics, int width) {
      if (metrics.stringWidth(value) <= width) {
        return value;
      }
      String ellipsis = "…";
      int available = width - metrics.stringWidth(ellipsis);
      if (available <= 0) {
        return ellipsis;
      }
      int low = 0;
      int high = value.length();
      while (low < high) {
        int middle = (low + high + 1) >>> 1;
        if (metrics.stringWidth(value.substring(0, middle)) <= available) {
          low = middle;
        } else {
          high = middle - 1;
        }
      }
      return value.substring(0, low) + ellipsis;
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
      String port) {

    private ManagedWorkspace withExecutable(String replacement) {
      return new ManagedWorkspace(name, host, workingDirectory, replacement, destination, port);
    }
  }

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
    private final LauncherSettingsStore store;
    private final Executor executor;
    private LauncherStateStore.State persisted;
    private LauncherStateStore.State desired;
    private LauncherStateStore.State inFlight;
    private boolean initialized;
    private boolean closeRequested;
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    private SaveQueue(LauncherSettingsStore store, Executor executor) {
      this.store = store;
      this.executor = executor;
    }

    private LauncherSettingsStore store() {
      return store;
    }

    private synchronized void initialize(LauncherStateStore.State loaded) {
      if (initialized) {
        return;
      }
      persisted = store.persistedState(loaded);
      initialized = true;
      startNextIfNeeded();
    }

    private synchronized void request(LauncherStateStore.State state) {
      desired = store.persistedState(state);
      startNextIfNeeded();
    }

    private synchronized CompletionStage<Void> closeWhenSettled(LauncherStateStore.State state) {
      desired = store.persistedState(state);
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

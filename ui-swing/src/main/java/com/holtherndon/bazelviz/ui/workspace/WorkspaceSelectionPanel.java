package com.holtherndon.bazelviz.ui.workspace;

import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * I/O-free workspace menu with recent selection and inline create/edit forms.
 *
 * <p>The owner supplies immutable profiles and handles every callback. This panel never reads
 * settings, connects to a host, or starts a process merely because a row is selected.
 */
public final class WorkspaceSelectionPanel extends JPanel {

  private static final long serialVersionUID = 1L;
  private static final String LIST_CARD = "list";
  private static final String EDITOR_CARD = "editor";

  private final Supplier<String> idSupplier;
  private final DefaultListModel<WorkspaceProfile> workspaceModel = new DefaultListModel<>();
  private final JList<WorkspaceProfile> workspaceList = new JList<>(workspaceModel);
  private final JLabel workspaceCount = new JLabel();
  private final JLabel discoveryStatus = new JLabel(" ");
  private final JLabel unavailableRestoreStatus = new JLabel(" ");
  private final JButton forgetUnavailableRestores = new JButton("Forget unavailable windows");
  private final JButton open = new JButton("Open workspace");
  private final JButton create = new JButton("New workspace");
  private final JButton edit = new JButton("Edit");
  private final JButton remove = new JButton("Remove");

  private final CardLayout cardLayout = new CardLayout();
  private final JPanel cards = new JPanel(cardLayout);
  private final JLabel editorTitle = new JLabel();
  private final JTextField label = new JTextField(32);
  private final JComboBox<WorkspaceProfile.Kind> kind =
      new JComboBox<>(WorkspaceProfile.Kind.values());
  private final JTextField destination = new JTextField(32);
  private final JTextField port = new JTextField(8);
  private final JTextField workingDirectory = new JTextField(32);
  private final JTextField bazelExecutable = new JTextField(32);
  private final JLabel destinationLabel = new JLabel("SSH destination:");
  private final JLabel portLabel = new JLabel("Port:");
  private final JLabel editorError = new JLabel(" ");
  private final JButton saveEditor = new JButton("Save workspace");
  private final JButton cancelEditor = new JButton("Cancel");

  private Consumer<WorkspaceProfile> openCallback = ignored -> {};
  private Consumer<WorkspaceProfile> createCallback = ignored -> {};
  private Predicate<WorkspaceProfile> updateCallback = ignored -> true;
  private Consumer<WorkspaceProfile> removeCallback = ignored -> {};
  private WorkspaceProfile editingProfile;
  private String visibleCard = LIST_CARD;
  private Set<String> discoveredIds = Set.of();
  private int savedWorkspaceCount;
  private int discoveredWorkspaceCount;
  private Runnable forgetUnavailableRestoresCallback = () -> {};

  public WorkspaceSelectionPanel() {
    this(WorkspaceProfile::newId);
  }

  WorkspaceSelectionPanel(Supplier<String> idSupplier) {
    super(new BorderLayout(0, 16));
    this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
    setName("workspaces.panel");

    add(buildHeader(), BorderLayout.NORTH);
    cards.add(buildWorkspaceList(), LIST_CARD);
    cards.add(buildEditor(), EDITOR_CARD);
    add(cards, BorderLayout.CENTER);

    configureActions();
    updateSelectionActions();
    updateWorkspaceCount();
    updateSshFields();
  }

  /** Replaces the visible profiles, normalizing them newest first. Performs no I/O. */
  public void setWorkspaces(List<WorkspaceProfile> workspaces) {
    setWorkspaces(workspaces, List.of());
  }

  /**
   * Replaces saved and ephemeral discovered profiles.
   *
   * <p>Saved rows win an impossible identifier collision. This method owns no persistence; callers
   * replace the discovered list after every invocation.
   */
  public void setWorkspaces(
      List<WorkspaceProfile> savedWorkspaces, List<WorkspaceProfile> discoveredWorkspaces) {
    requireEdt();
    Objects.requireNonNull(savedWorkspaces, "savedWorkspaces");
    Objects.requireNonNull(discoveredWorkspaces, "discoveredWorkspaces");
    String selectedId = selectedWorkspace().map(WorkspaceProfile::id).orElse(null);
    List<WorkspaceProfile> sorted =
        new ArrayList<>(savedWorkspaces.size() + discoveredWorkspaces.size());
    Set<String> savedIds = new HashSet<>();
    for (WorkspaceProfile workspace : savedWorkspaces) {
      workspace = Objects.requireNonNull(workspace, "workspace");
      sorted.add(Objects.requireNonNull(workspace, "workspace"));
      savedIds.add(workspace.id());
    }
    Set<String> nextDiscoveredIds = new HashSet<>();
    for (WorkspaceProfile workspace : discoveredWorkspaces) {
      workspace = Objects.requireNonNull(workspace, "workspace");
      if (savedIds.contains(workspace.id()) || !nextDiscoveredIds.add(workspace.id())) {
        continue;
      }
      sorted.add(workspace);
    }
    sorted.sort(WorkspaceProfile.RECENT_FIRST);

    discoveredIds = Set.copyOf(nextDiscoveredIds);
    savedWorkspaceCount = savedIds.size();
    discoveredWorkspaceCount = nextDiscoveredIds.size();

    workspaceModel.clear();
    for (WorkspaceProfile workspace : sorted) {
      workspaceModel.addElement(workspace);
    }
    int restored = indexOfId(selectedId);
    if (restored >= 0) {
      workspaceList.setSelectedIndex(restored);
    } else if (!workspaceModel.isEmpty()) {
      workspaceList.setSelectedIndex(0);
    }
    updateWorkspaceCount();
    updateSelectionActions();
  }

  /** Shows non-modal progress or diagnostics from the most recent discovery run. */
  public void setDiscoveryStatus(String status) {
    requireEdt();
    String text = Objects.requireNonNull(status, "status").strip();
    discoveryStatus.setText(text.isEmpty() ? " " : text);
    discoveryStatus.setToolTipText(text.isEmpty() ? null : text);
  }

  /** Shows restore IDs that startup discovery did not resolve, with an explicit forget action. */
  public void setUnavailableRestoreIds(List<String> workspaceIds, Runnable forgetCallback) {
    requireEdt();
    List<String> ids = List.copyOf(Objects.requireNonNull(workspaceIds, "workspaceIds"));
    for (String id : ids) {
      if (Objects.requireNonNull(id, "workspaceId").isBlank()) {
        throw new IllegalArgumentException("workspace restore id must not be blank");
      }
    }
    forgetUnavailableRestoresCallback = Objects.requireNonNull(forgetCallback, "forgetCallback");
    if (ids.isEmpty()) {
      unavailableRestoreStatus.setText(" ");
      unavailableRestoreStatus.setToolTipText(null);
      forgetUnavailableRestores.setVisible(false);
      return;
    }
    String joined = String.join(", ", ids);
    String visibleIds = joined.length() <= 96 ? joined : joined.substring(0, 93) + "…";
    unavailableRestoreStatus.setText(
        ids.size() == 1
            ? "Previously open Workspace unavailable: " + visibleIds
            : ids.size() + " previously open Workspaces unavailable: " + visibleIds);
    unavailableRestoreStatus.setToolTipText(joined);
    forgetUnavailableRestores.setVisible(true);
  }

  /** The highlighted workspace. Highlighting alone never invokes a callback. */
  public Optional<WorkspaceProfile> selectedWorkspace() {
    return Optional.ofNullable(workspaceList.getSelectedValue());
  }

  /** Sets the callback invoked by Open, Enter, or a double-click. */
  public void onOpen(Consumer<WorkspaceProfile> callback) {
    openCallback = Objects.requireNonNull(callback, "callback");
  }

  /** Compatibility name for callers that treat opening as workspace selection. */
  public void onSelect(Consumer<WorkspaceProfile> callback) {
    onOpen(callback);
  }

  /** Sets the callback invoked with a validated new profile. */
  public void onCreate(Consumer<WorkspaceProfile> callback) {
    createCallback = Objects.requireNonNull(callback, "callback");
  }

  /** Sets the callback invoked with a validated replacement preserving ID and recency. */
  public void onUpdate(Predicate<WorkspaceProfile> callback) {
    updateCallback = Objects.requireNonNull(callback, "callback");
  }

  /** Sets the callback invoked for the selected profile after Remove is pressed. */
  public void onRemove(Consumer<WorkspaceProfile> callback) {
    removeCallback = Objects.requireNonNull(callback, "callback");
  }

  /** Opens a blank local-workspace editor. Performs no file selection or discovery. */
  public void showNewWorkspaceForm() {
    requireEdt();
    editingProfile = null;
    editorTitle.setText("New workspace");
    label.setText("");
    kind.setSelectedItem(WorkspaceProfile.Kind.LOCAL);
    destination.setText("");
    port.setText("");
    workingDirectory.setText("");
    bazelExecutable.setText("bazel");
    editorError.setText(" ");
    showCard(EDITOR_CARD);
    label.requestFocusInWindow();
  }

  /** Opens an editor for the highlighted profile, if one is selected. */
  public void showEditWorkspaceForm() {
    requireEdt();
    selectedWorkspace().filter(profile -> !isDiscovered(profile)).ifPresent(this::editWorkspace);
  }

  /** Opens an editor for a specific profile, independent of recent-row selection. */
  public void showEditWorkspaceForm(WorkspaceProfile profile) {
    requireEdt();
    editWorkspace(Objects.requireNonNull(profile, "profile"));
  }

  private JComponent buildHeader() {
    JPanel header = new JPanel(new BorderLayout(0, 5));
    JLabel title = new JLabel("Workspaces");
    title.setName("workspaces.title");
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6));
    JLabel explanation =
        new JLabel(
            "Choose one repository and machine. Opening it prepares Console, Browse Repository, and"
                + " Terminal.");
    explanation.setName("workspaces.explanation");
    header.add(title, BorderLayout.NORTH);
    header.add(explanation, BorderLayout.SOUTH);
    return header;
  }

  private JComponent buildWorkspaceList() {
    JPanel panel = new JPanel(new BorderLayout(0, 10));
    panel.setName("workspaces.listCard");

    workspaceCount.setName("workspaces.count");
    discoveryStatus.setName("workspaces.discoveryStatus");
    workspaceList.setName("workspaces.list");
    workspaceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    workspaceList.setVisibleRowCount(10);
    workspaceList.setFixedCellHeight(34);
    workspaceList.setCellRenderer(new WorkspaceRenderer());
    workspaceList.getAccessibleContext().setAccessibleName("Recent workspaces");
    unavailableRestoreStatus.setName("workspaces.unavailableRestoreStatus");
    forgetUnavailableRestores.setName("workspaces.forgetUnavailableRestores");
    forgetUnavailableRestores.setVisible(false);
    forgetUnavailableRestores.addActionListener(event -> forgetUnavailableRestoresCallback.run());
    JPanel restoreSummary = new JPanel(new BorderLayout(8, 0));
    restoreSummary.add(unavailableRestoreStatus, BorderLayout.CENTER);
    restoreSummary.add(forgetUnavailableRestores, BorderLayout.EAST);
    JPanel summary = new JPanel();
    summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
    summary.add(workspaceCount);
    summary.add(discoveryStatus);
    summary.add(restoreSummary);
    panel.add(summary, BorderLayout.NORTH);
    panel.add(new JScrollPane(workspaceList), BorderLayout.CENTER);

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    open.setName("workspaces.open");
    create.setName("workspaces.new");
    edit.setName("workspaces.edit");
    remove.setName("workspaces.remove");
    actions.add(remove);
    actions.add(edit);
    actions.add(create);
    actions.add(open);
    panel.add(actions, BorderLayout.SOUTH);
    return panel;
  }

  private JComponent buildEditor() {
    JPanel outer = new JPanel(new BorderLayout(0, 12));
    outer.setName("workspaces.editorCard");
    editorTitle.setName("workspaces.editorTitle");
    editorTitle.setFont(
        editorTitle.getFont().deriveFont(Font.BOLD, editorTitle.getFont().getSize2D() + 3));
    outer.add(editorTitle, BorderLayout.NORTH);

    JPanel form = new JPanel(new GridBagLayout());
    label.setName("workspaces.label");
    kind.setName("workspaces.kind");
    destination.setName("workspaces.destination");
    port.setName("workspaces.port");
    workingDirectory.setName("workspaces.workingDirectory");
    bazelExecutable.setName("workspaces.bazel");
    destinationLabel.setName("workspaces.destinationLabel");
    portLabel.setName("workspaces.portLabel");
    destinationLabel.setLabelFor(destination);
    portLabel.setLabelFor(port);

    addRow(form, 0, labelled("Name:", label, "workspaces.labelLabel"), label);
    addRow(form, 1, labelled("Run on:", kind, "workspaces.kindLabel"), kind);
    addRow(form, 2, destinationLabel, destination);
    addRow(form, 3, portLabel, port);
    addRow(
        form,
        4,
        labelled("Working directory:", workingDirectory, "workspaces.workingDirectoryLabel"),
        workingDirectory);
    addRow(
        form,
        5,
        labelled("Bazel executable:", bazelExecutable, "workspaces.bazelLabel"),
        bazelExecutable);
    GridBagConstraints filler = constraints(0, 6);
    filler.gridwidth = 2;
    filler.weighty = 1;
    form.add(new JPanel(), filler);
    outer.add(form, BorderLayout.CENTER);

    JPanel footer = new JPanel(new BorderLayout(8, 0));
    editorError.setName("workspaces.editorError");
    editorError.setForeground(new Color(180, 40, 40));
    editorError.setHorizontalAlignment(SwingConstants.LEFT);
    footer.add(editorError, BorderLayout.CENTER);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    saveEditor.setName("workspaces.save");
    cancelEditor.setName("workspaces.cancel");
    actions.add(cancelEditor);
    actions.add(saveEditor);
    footer.add(actions, BorderLayout.EAST);
    outer.add(footer, BorderLayout.SOUTH);
    return outer;
  }

  private void configureActions() {
    workspaceList.addListSelectionListener(event -> updateSelectionActions());
    open.addActionListener(event -> openSelectedWorkspace());
    create.addActionListener(event -> showNewWorkspaceForm());
    edit.addActionListener(event -> showEditWorkspaceForm());
    remove.addActionListener(
        event ->
            selectedWorkspace()
                .filter(profile -> !isDiscovered(profile))
                .ifPresent(removeCallback));
    saveEditor.addActionListener(event -> submitEditor());
    cancelEditor.addActionListener(event -> showCard(LIST_CARD));
    kind.addActionListener(event -> updateSshFields());

    workspaceList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
              int index = workspaceList.locationToIndex(event.getPoint());
              Rectangle bounds = index < 0 ? null : workspaceList.getCellBounds(index, index);
              if (bounds == null || !bounds.contains(event.getPoint())) {
                return;
              }
              openSelectedWorkspace();
            }
          }
        });
    workspaceList
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-workspace");
    workspaceList
        .getActionMap()
        .put(
            "open-workspace",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                openSelectedWorkspace();
              }
            });
    cards
        .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-workspace-editor");
    cards
        .getActionMap()
        .put(
            "cancel-workspace-editor",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                if (EDITOR_CARD.equals(visibleCard)) {
                  showCard(LIST_CARD);
                }
              }
            });

    DocumentListener clearError =
        new DocumentListener() {
          @Override
          public void insertUpdate(DocumentEvent event) {
            clearEditorError();
          }

          @Override
          public void removeUpdate(DocumentEvent event) {
            clearEditorError();
          }

          @Override
          public void changedUpdate(DocumentEvent event) {
            clearEditorError();
          }
        };
    label.getDocument().addDocumentListener(clearError);
    destination.getDocument().addDocumentListener(clearError);
    port.getDocument().addDocumentListener(clearError);
    workingDirectory.getDocument().addDocumentListener(clearError);
    bazelExecutable.getDocument().addDocumentListener(clearError);
  }

  private void openSelectedWorkspace() {
    selectedWorkspace().ifPresent(openCallback);
  }

  private void editWorkspace(WorkspaceProfile profile) {
    editingProfile = profile;
    editorTitle.setText("Edit workspace");
    label.setText(profile.label());
    kind.setSelectedItem(profile.kind());
    destination.setText(profile.destination().orElse(""));
    port.setText(profile.port().isPresent() ? Integer.toString(profile.port().getAsInt()) : "");
    workingDirectory.setText(profile.workingDirectory());
    bazelExecutable.setText(profile.bazelExecutable());
    editorError.setText(" ");
    showCard(EDITOR_CARD);
    label.requestFocusInWindow();
  }

  private void submitEditor() {
    WorkspaceProfile result;
    boolean updating;
    try {
      String id =
          editingProfile == null
              ? Objects.requireNonNull(idSupplier.get(), "generated workspace id")
              : editingProfile.id();
      OptionalLong lastOpened =
          editingProfile == null ? OptionalLong.empty() : editingProfile.lastOpenedMicros();
      WorkspaceProfile.Kind selectedKind =
          (WorkspaceProfile.Kind) Objects.requireNonNull(kind.getSelectedItem(), "workspace kind");
      OptionalInt parsedPort =
          selectedKind == WorkspaceProfile.Kind.SSH
              ? parsePort(port.getText())
              : OptionalInt.empty();
      result =
          selectedKind == WorkspaceProfile.Kind.LOCAL
              ? WorkspaceProfile.local(
                  id,
                  label.getText(),
                  workingDirectory.getText(),
                  bazelExecutable.getText(),
                  lastOpened)
              : WorkspaceProfile.ssh(
                  id,
                  label.getText(),
                  destination.getText(),
                  parsedPort,
                  workingDirectory.getText(),
                  bazelExecutable.getText(),
                  lastOpened);
      updating = editingProfile != null;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      String message = invalid.getMessage();
      editorError.setText(
          message == null || message.isBlank() ? "Check the workspace fields." : message);
      return;
    }
    if (updating) {
      if (!updateCallback.test(result)) {
        editorError.setText("This Workspace is currently in use and could not be updated.");
        return;
      }
    }
    editingProfile = null;
    showCard(LIST_CARD);
    if (!updating) {
      createCallback.accept(result);
    }
  }

  private static OptionalInt parsePort(String text) {
    String cleaned = Objects.requireNonNull(text, "port").strip();
    if (cleaned.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(cleaned));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("SSH port must be a number from 1 to 65535.");
    }
  }

  private void updateSshFields() {
    boolean ssh = kind.getSelectedItem() == WorkspaceProfile.Kind.SSH;
    destination.setEnabled(ssh);
    port.setEnabled(ssh);
    destinationLabel.setEnabled(ssh);
    portLabel.setEnabled(ssh);
    clearEditorError();
  }

  private void clearEditorError() {
    if (!" ".equals(editorError.getText())) {
      editorError.setText(" ");
    }
  }

  private void updateSelectionActions() {
    boolean selected = !workspaceList.isSelectionEmpty();
    boolean managed = selectedWorkspace().map(profile -> !isDiscovered(profile)).orElse(false);
    open.setEnabled(selected);
    edit.setEnabled(managed);
    remove.setEnabled(managed);
  }

  private void updateWorkspaceCount() {
    if (savedWorkspaceCount == 0 && discoveredWorkspaceCount == 0) {
      workspaceCount.setText("No saved workspaces yet. Create one to get started.");
      return;
    }
    if (discoveredWorkspaceCount == 0) {
      workspaceCount.setText(
          savedWorkspaceCount == 1
              ? "1 saved workspace, most recent first."
              : savedWorkspaceCount + " saved workspaces, most recent first.");
      return;
    }
    String saved =
        savedWorkspaceCount == 1 ? "1 saved workspace" : savedWorkspaceCount + " saved workspaces";
    String discovered =
        discoveredWorkspaceCount == 1
            ? "1 discovered this run"
            : discoveredWorkspaceCount + " discovered this run";
    workspaceCount.setText(saved + " · " + discovered + ".");
  }

  private boolean isDiscovered(WorkspaceProfile profile) {
    return discoveredIds.contains(profile.id());
  }

  private void showCard(String card) {
    visibleCard = card;
    cardLayout.show(cards, card);
  }

  private int indexOfId(String id) {
    if (id == null) {
      return -1;
    }
    for (int index = 0; index < workspaceModel.size(); index++) {
      if (workspaceModel.get(index).id().equals(id)) {
        return index;
      }
    }
    return -1;
  }

  private static JLabel labelled(String text, JComponent component, String name) {
    JLabel result = new JLabel(text);
    result.setName(name);
    result.setLabelFor(component);
    return result;
  }

  private static void addRow(JPanel form, int row, JLabel rowLabel, JComponent field) {
    GridBagConstraints labelConstraints = constraints(0, row);
    labelConstraints.weightx = 0;
    labelConstraints.fill = GridBagConstraints.NONE;
    form.add(rowLabel, labelConstraints);
    GridBagConstraints fieldConstraints = constraints(1, row);
    fieldConstraints.weightx = 1;
    form.add(field, fieldConstraints);
  }

  private static GridBagConstraints constraints(int x, int y) {
    GridBagConstraints result = new GridBagConstraints();
    result.gridx = x;
    result.gridy = y;
    result.anchor = GridBagConstraints.NORTHWEST;
    result.fill = GridBagConstraints.HORIZONTAL;
    result.insets = new Insets(5, 5, 5, 5);
    return result;
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace UI changes must run on the EDT");
    }
  }

  JList<WorkspaceProfile> workspaceListForTest() {
    return workspaceList;
  }

  JButton openForTest() {
    return open;
  }

  JButton createForTest() {
    return create;
  }

  JButton editForTest() {
    return edit;
  }

  JButton removeForTest() {
    return remove;
  }

  JTextField labelForTest() {
    return label;
  }

  JComboBox<WorkspaceProfile.Kind> kindForTest() {
    return kind;
  }

  JTextField destinationForTest() {
    return destination;
  }

  JTextField portForTest() {
    return port;
  }

  JTextField workingDirectoryForTest() {
    return workingDirectory;
  }

  JTextField bazelExecutableForTest() {
    return bazelExecutable;
  }

  JButton saveForTest() {
    return saveEditor;
  }

  JLabel errorForTest() {
    return editorError;
  }

  JLabel unavailableRestoreStatusForTest() {
    return unavailableRestoreStatus;
  }

  JButton forgetUnavailableRestoresForTest() {
    return forgetUnavailableRestores;
  }

  boolean editorVisibleForTest() {
    return EDITOR_CARD.equals(visibleCard);
  }

  private final class WorkspaceRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected, boolean focused) {
      JLabel rendered =
          (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
      PlainText.disableHtml(rendered);
      if (value instanceof WorkspaceProfile workspace) {
        rendered.setText(
            workspace.label()
                + (isDiscovered(workspace) ? "  [Discovered]" : "")
                + "  —  "
                + workspace.machineDisplayName()
                + "  —  "
                + workspace.workingDirectory());
        rendered.setToolTipText(
            PlainText.tooltip(
                workspace.machineDisplayName()
                    + " · "
                    + workspace.workingDirectory()
                    + " · "
                    + workspace.bazelExecutable()));
        rendered.getAccessibleContext().setAccessibleName(rendered.getText());
      }
      return rendered;
    }
  }
}

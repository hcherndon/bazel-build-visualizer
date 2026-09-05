package com.holtherndon.bazelviz.ui.targets;

import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileResolver;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectionPagingPanel;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Top Level Targets card: packages, and the targets requested by the build.
 *
 * <h2>Two levels, loaded separately</h2>
 *
 * <p>The tree shows packages collapsed and fetches a package's targets when it is expanded. A tree
 * that loaded every target to draw its collapsed view would hold the whole build in memory to show
 * a screen of folder names.
 *
 * <h2>What a package node says</h2>
 *
 * <p>Its target count, and — when either is non-zero — how many failed and how many were configured
 * but never completed. The second number is the one worth surfacing: a build interrupted during
 * analysis consists entirely of targets that were configured and never built, and a tree that
 * showed only completions would report such a build as empty.
 *
 * <h2>The header toolbar</h2>
 *
 * <p>The selected target's cross-view jumps, through the shared {@link EntityActions} facility. A
 * toolbar rather than the row menu the table cards use, because a fixed strip of buttons can carry
 * the <em>reason</em> a jump is unavailable — nothing selected, no source event on this row, no
 * read path behind the command — where a menu can only leave the item out. Every button is either
 * live or disabled with that reason in its tooltip; none is ever a control that clicks into
 * silence.
 */
public final class TargetsView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(TargetsView.class);

  private static final String CARD_EMPTY = "empty";
  private static final String CARD_TREE = "tree";

  private static final String BROWSE_PACKAGES = "packages";
  private static final String BROWSE_ALL_TARGETS = "all-targets";

  /** Top-level labels appended by one explicit flat-list page request. */
  public static final int FLAT_LABEL_PAGE_SIZE = 200;

  /** Package summaries read by one stable keyset request. */
  public static final int PACKAGE_PAGE_SIZE = 200;

  /** Target/configuration rows read for one expanded package request. */
  public static final int PACKAGE_TARGET_PAGE_SIZE = 200;

  /** Source-qualified tags retained for one selected target. */
  public static final int TARGET_TAG_PAGE_SIZE = 100;

  /** Ordinal output groups retained for one selected target. */
  public static final int OUTPUT_GROUP_PAGE_SIZE = 100;

  /** Placeholder child that makes a package node expandable before it is loaded. */
  private static final String PENDING = "…";

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel(" ");
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("targets");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
  private final JTree tree = new JTree(treeModel);
  private final DefaultMutableTreeNode flatRoot = new DefaultMutableTreeNode("all targets");
  private final DefaultTreeModel flatTreeModel = new DefaultTreeModel(flatRoot);
  private final JTree flatTree = new JTree(flatTreeModel);
  private final CardLayout browseCards = new CardLayout();
  private final JPanel browseDeck = new JPanel(browseCards);
  private final JComboBox<BrowseMode> browseMode = new JComboBox<>(BrowseMode.values());
  private final JTextField labelFilter = new JTextField(20);
  private final Timer filterDebounce = new Timer(250, event -> applyFilter());
  private final JButton loadMoreLabels = new JButton("Load more targets");
  private final InspectorPanel inspector = new InspectorPanel();
  private final InspectionPagingPanel inspectionPaging = new InspectionPagingPanel();
  private final JLabel statusLabel = new JLabel(" ");
  private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
  private final JPanel session = new JPanel(new BorderLayout());

  /** The header toolbar's buttons, in the order they are shown. */
  private final List<ToolbarAction> toolbarActions = new ArrayList<>();

  private ExecutorService executor;
  private ExecutorService detailExecutor;
  private ExecutorService inspectionExecutor;
  private EntityReader reader;
  private EntityReader detailReader;
  private SessionSource source;
  private Future<?> packageTask;
  private Future<?> flatPageTask;
  private final Map<DefaultMutableTreeNode, Future<?>> childTasks = new IdentityHashMap<>();
  private Future<?> flatLookupTask;
  private Future<?> tagTask;
  private Future<?> outputGroupTask;
  private Future<?> inspectionTask;
  private LongConsumer showEventHandler = eventId -> {};
  private long selectionGeneration;
  private long filterGeneration;

  /** Bumped whenever a session is opened or closed, including reuse of the same source object. */
  private long sessionGeneration;

  private boolean packageLoading;
  private boolean packageLoaded;
  private String packageAfterPath;
  private long packageTotal = -1;
  private boolean flatPageLoading;
  private long flatLabelCount = -1;
  private String flatAfterLabel;
  private SelectedTarget selectedFlatTarget;
  private String packageStatus = " ";
  private String flatStatus = " ";
  private String filterText = "";
  private PageToolbar pageToolbar;
  private String pageMetadata = "";
  private String pageMetadataDetail = "";

  /**
   * The shared cross-view navigation actions, once {@link #installEntityActions} has run. Null
   * until then, and the toolbar says so rather than offering buttons with nothing behind them.
   */
  private EntityActions entityActions;

  /** Bumped per reveal so a slow lookup cannot land after a newer one. */
  private long revealGeneration;

  private Optional<CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor>> tagPage =
      Optional.empty();
  private Optional<CountedPage<TargetQueries.OutputGroup, Long>> outputGroupPage = Optional.empty();
  private boolean tagPageLoading;
  private boolean outputGroupPageLoading;

  public TargetsView() {
    super(new BorderLayout());

    PlainText.install(tree);
    PlainText.install(flatTree);
    PlainText.disableHtml(statusLabel);
    PlainText.disableHtml(labelFilter);
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.addTreeSelectionListener(event -> selectionChanged());
    tree.addTreeWillExpandListener(
        new TreeWillExpandListener() {
          @Override
          public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
          }

          @Override
          public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            // Nothing to release: a collapsed package keeps its children,
            // which is what makes re-expanding it free.
          }
        });
    flatTree.setRootVisible(false);
    flatTree.setShowsRootHandles(false);
    flatTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    flatTree.addTreeSelectionListener(event -> flatSelectionChanged());
    inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));

    JScrollPane treeScroll = new JScrollPane(tree);
    treeScroll.setMinimumSize(new Dimension(320, 160));
    JScrollPane flatScroll = new JScrollPane(flatTree);
    flatScroll.setMinimumSize(new Dimension(320, 160));
    JPanel flatPanel = new JPanel(new BorderLayout());
    flatPanel.add(flatScroll, BorderLayout.CENTER);
    JPanel flatFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    loadMoreLabels.setEnabled(false);
    loadMoreLabels.setToolTipText(
        "Append the next "
            + FLAT_LABEL_PAGE_SIZE
            + " top-level labels without creating package groups.");
    loadMoreLabels.addActionListener(event -> loadNextFlatPage());
    flatFooter.add(loadMoreLabels);
    flatPanel.add(flatFooter, BorderLayout.SOUTH);
    browseDeck.add(treeScroll, BROWSE_PACKAGES);
    browseDeck.add(flatPanel, BROWSE_ALL_TARGETS);
    inspector.setMinimumSize(new Dimension(300, 160));
    JPanel inspectionPanel = new JPanel(new BorderLayout());
    inspectionPanel.add(inspector, BorderLayout.CENTER);
    inspectionPanel.add(inspectionPaging, BorderLayout.SOUTH);
    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("Top level targets", browseDeck),
            new SectionPane("Target details", inspectionPanel));
    split.setResizeWeight(0.55);

    JPanel status = new JPanel(new BorderLayout());
    status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    status.add(statusLabel, BorderLayout.WEST);

    session.add(buildToolbar(), BorderLayout.NORTH);
    session.add(split, BorderLayout.CENTER);
    session.add(status, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(session, CARD_TREE);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /** Moves page-wide browsing, filtering, and navigation controls into common page chrome. */
  @Override
  public void installPageToolbar(PageToolbar installed) {
    Objects.requireNonNull(installed, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = installed;
    Component[] controls = toolbar.getComponents();
    for (Component control : controls) {
      installed.addAction(control);
    }
    Container oldParent = toolbar.getParent();
    if (oldParent != null) {
      oldParent.remove(toolbar);
      oldParent.revalidate();
      oldParent.repaint();
    }
    syncPageMetadata();
  }

  /**
   * The header toolbar: what can be done with the selected target elsewhere in the application.
   *
   * <p>Built once, before anything is selected, which is exactly why each button has to be able to
   * explain itself: the strip is on screen from the moment a session opens, and for most of that
   * time some of it has nothing to act on.
   */
  private JPanel buildToolbar() {
    JPanel bar = toolbar;
    JLabel viewLabel = new JLabel("View:");
    viewLabel.setLabelFor(browseMode);
    browseMode.setToolTipText("Group top-level targets by package or show one flat label list.");
    browseMode.addActionListener(event -> switchBrowseMode());
    bar.add(viewLabel);
    bar.add(browseMode);
    filterDebounce.setRepeats(false);
    labelFilter.setToolTipText(
        PlainText.tooltip("Show top-level targets whose full Bazel label contains this text."));
    labelFilter.getDocument().addDocumentListener(filterListener());
    JLabel filterLabel = new JLabel("Filter labels:");
    filterLabel.setLabelFor(labelFilter);
    bar.add(filterLabel);
    bar.add(labelFilter);
    toolbarActions.add(
        new ToolbarAction(
            EntityActions.Command.OPEN_IN_TREE,
            row -> Optional.of(new EntityRef.TargetLabel(row.label())),
            "Root the Tree card's dependency trees at this target," + " by exact label",
            "This row carries no label to look up.",
            "The Tree card is not reachable from here in this build."));
    toolbarActions.add(
        new ToolbarAction(
            EntityActions.Command.OPEN_IN_GRAPH,
            row -> Optional.of(new EntityRef.TargetLabel(row.label())),
            "Draw this target's neighbourhood on the Graph card," + " by exact label",
            "This row carries no label to look up.",
            "The Graph card is not reachable from here in this build."));
    toolbarActions.add(
        new ToolbarAction(
            EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
            row -> Optional.of(new EntityRef.TargetLabel(row.label())),
            "Narrow the Actions card to this target's label",
            "This row carries no label to filter by.",
            "The Actions card cannot be filtered by label in this build."));
    toolbarActions.add(
        new ToolbarAction(
            EntityActions.Command.SHOW_EVENTS_FOR_LABEL,
            row -> Optional.of(new EntityRef.TargetLabel(row.label())),
            "Show this target's events in the Events card",
            "This row carries no label to filter by.",
            // Rule 11's shape applied to a control: the button is here,
            // visibly off, naming the missing read path -- rather than
            // silently absent, or present and doing nothing.
            "No events-by-label read path exists yet: the Events card"
                + " parses a label out of each row as it renders it,"
                + " so no query can select events by label."));
    toolbarActions.add(
        new ToolbarAction(
            EntityActions.Command.SHOW_SOURCE_EVENT,
            row ->
                row.sourceEventId().isPresent()
                    ? Optional.of(new EntityRef.EventId(row.sourceEventId().getAsLong()))
                    : Optional.empty(),
            "Open the event this target row was normalized from",
            "This target row records no source event, so there is" + " nothing to open.",
            "The Events card is not reachable from here in this build."));
    for (ToolbarAction action : toolbarActions) {
      bar.add(action.wrapper);
    }
    updateToolbar();
    return bar;
  }

  /**
   * Adopts the shared cross-view navigation actions. Call once, at wiring time; until then the
   * toolbar's buttons are disabled and say so.
   */
  public void installEntityActions(EntityActions actions) {
    this.entityActions = Objects.requireNonNull(actions, "actions");
    inspector.installEntityActions(actions, Set.of(EntityActions.Command.OPEN_TARGET));
    actions.installTreeMenu(tree, this::refsAtPath, this::omissionsAtPath);
    actions.installTreeMenu(
        flatTree, this::refsAtPath, path -> Set.of(EntityActions.Command.OPEN_TARGET));
    updateToolbar();
  }

  private List<EntityRef> refsAtPath(TreePath path) {
    Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    if (node instanceof PackageNode packageNode) {
      return WorkspaceFileResolver.mainRepositoryLabel(packageNode.summary.path())
          .<List<EntityRef>>map(label -> List.of(new EntityRef.TargetLabel(label)))
          .orElse(List.of());
    }
    if (node instanceof FlatLabelNode flat) {
      return List.of(new EntityRef.TargetLabel(flat.label));
    }
    if (!(node instanceof TargetNode target)) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>();
    refs.add(new EntityRef.TargetLabel(target.row.label()));
    target
        .row
        .configurationId()
        .ifPresent(checksum -> refs.add(new EntityRef.ConfigurationChecksum(checksum)));
    target.row.bepEventId().ifPresent(eventId -> refs.add(new EntityRef.EventId(eventId)));
    return refs;
  }

  private Set<EntityActions.Command> omissionsAtPath(TreePath path) {
    Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    if (node instanceof PackageNode) {
      EnumSet<EntityActions.Command> omitted = EnumSet.allOf(EntityActions.Command.class);
      omitted.remove(EntityActions.Command.OPEN_BUILD_FILE);
      return omitted;
    }
    if (node instanceof FlatLabelNode) {
      return Set.of(EntityActions.Command.OPEN_TARGET);
    }
    return Set.of(EntityActions.Command.OPEN_TARGET);
  }

  /** EDT: re-states every toolbar button against the current selection. */
  private void updateToolbar() {
    SelectedTarget selected = selectedTarget();
    for (ToolbarAction action : toolbarActions) {
      action.update(selected);
    }
  }

  /** The selected package-tree target row, or null when the selection is not one. */
  private TargetRow selectedRow() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return null;
    }
    Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    return selected instanceof TargetNode targetNode ? targetNode.row : null;
  }

  private SelectedTarget selectedTarget() {
    if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
      return selectedFlatTarget;
    }
    TargetRow row = selectedRow();
    return row == null ? null : new SelectedTarget(row.label(), row.bepEventId());
  }

  public void onShowSourceEvent(LongConsumer handler) {
    this.showEventHandler = Objects.requireNonNull(handler, "handler");
  }

  public void showEmpty(String message) {
    emptyState.setText(Objects.requireNonNull(message, "message"));
    cards.show(deck, CARD_EMPTY);
    setPageMetadata(message.startsWith("No session") ? "" : message, message);
  }

  /** Opens a session and loads the package level. Returns immediately. */
  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    long generation = ++sessionGeneration;
    source = newSource;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-targets");
              thread.setDaemon(true);
              return thread;
            });
    detailExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-target-details");
              thread.setDaemon(true);
              return thread;
            });
    inspectionExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-target-inspection");
              thread.setDaemon(true);
              return thread;
            });
    showEmpty("Reading targets…");
    ExecutorService opening = executor;
    opening.execute(
        () -> {
          try {
            EntityReader opened = newSource.openEntityReader();
            EntityReader details;
            try {
              details = newSource.openEntityReader();
            } catch (RuntimeException failure) {
              opened.close();
              throw failure;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (source != newSource || generation != sessionGeneration) {
                    ViewClose.runAsync(
                        "bbv-targets-stale-open-close",
                        () -> {
                          opened.close();
                          details.close();
                        });
                    return;
                  }
                  reader = opened;
                  detailReader = details;
                  cards.show(deck, CARD_TREE);
                  switchBrowseMode();
                });
          } catch (RuntimeException failure) {
            log.error("could not read targets", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (source == newSource && generation == sessionGeneration) {
                    showEmpty("Could not read targets: " + failure.getMessage());
                  }
                });
          }
        });
  }

  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's target reads have stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    sessionGeneration++;
    filterDebounce.stop();
    filterGeneration++;
    selectionGeneration++;
    filterText = "";
    labelFilter.setText("");
    filterDebounce.stop();
    revealGeneration++;
    root.removeAllChildren();
    treeModel.reload();
    packageLoading = false;
    packageLoaded = false;
    packageAfterPath = null;
    packageTotal = -1;
    flatRoot.removeAllChildren();
    flatTreeModel.reload();
    flatPageLoading = false;
    flatLabelCount = -1;
    flatAfterLabel = null;
    selectedFlatTarget = null;
    packageStatus = " ";
    flatStatus = " ";
    loadMoreLabels.setEnabled(false);
    inspector.show(Inspection.NONE);
    inspectionPaging.clear();
    cancelDetailTasks();
    resetDetailPages();
    // Whatever was selected is gone with the tree, and the toolbar must
    // not keep offering jumps for a target that is no longer on screen.
    updateToolbar();
    cancelTask(packageTask, true);
    cancelTask(flatPageTask, true);
    cancelChildTasks(true);
    packageTask = null;
    flatPageTask = null;
    ExecutorService stopping = executor;
    ExecutorService stoppingDetails = detailExecutor;
    ExecutorService stoppingInspections = inspectionExecutor;
    EntityReader closing = reader;
    EntityReader closingDetails = detailReader;
    if (closing != null) {
      closing.cancelRunningQuery();
    }
    if (closingDetails != null) {
      closingDetails.cancelRunningQuery();
    }
    source = null;
    executor = null;
    detailExecutor = null;
    inspectionExecutor = null;
    reader = null;
    detailReader = null;
    if (stopping == null
        && stoppingDetails == null
        && stoppingInspections == null
        && closing == null
        && closingDetails == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-targets-close",
        () -> {
          shutdown(stopping);
          shutdown(stoppingDetails);
          shutdown(stoppingInspections);
          if (closing != null) {
            closing.close();
          }
          if (closingDetails != null) {
            closingDetails.close();
          }
        });
  }

  /**
   * Shows one target by its label: the package path expanded, the target's row selected and
   * inspected — random access by label, where the tree is otherwise browsed top-down.
   *
   * <p>The label is looked up off the EDT ({@code targetsByLabelPage}), because the package path
   * shown in the tree is the one the database derived and deriving it here again would be a second
   * definition that could drift. A label this session never declared changes nothing but the status
   * line, which says so — cross-view navigation arrives here with labels parsed out of events, and
   * an event can name a label no target row carries.
   *
   * <p>Must be called on the EDT. Does nothing when no session is open.
   */
  public void revealLabel(String label) {
    Objects.requireNonNull(label, "label");
    browseMode.setSelectedItem(BrowseMode.PACKAGES);
    if (!filterText.isEmpty() || !labelFilter.getText().isEmpty()) {
      labelFilter.setText("");
      filterDebounce.stop();
      applyFilter();
    }
    ExecutorService running = executor;
    EntityReader current = reader;
    if (running == null || current == null) {
      return;
    }
    long generation = ++revealGeneration;
    long openedSessionGeneration = sessionGeneration;
    SessionSource opened = source;
    running.execute(
        () -> {
          try {
            CountedPage<TargetRow, TargetQueries.TargetAnchor> rows =
                current.targetsByLabelPage(label, Optional.empty(), 1);
            SwingUtilities.invokeLater(
                () -> {
                  if (generation != revealGeneration
                      || opened != source
                      || openedSessionGeneration != sessionGeneration) {
                    return;
                  }
                  if (rows.rows().isEmpty()) {
                    setStatus("No target named " + label + " in this session.");
                    return;
                  }
                  revealRow(rows.rows().getFirst());
                });
          } catch (RuntimeException failure) {
            log.warn("could not look up {}", label, failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (generation == revealGeneration
                      && opened == source
                      && openedSessionGeneration == sessionGeneration) {
                    setStatus("Could not look up " + label + ": " + failure.getMessage());
                  }
                });
          }
        });
  }

  /** EDT: inserts a marked direct result without walking any prior package page. */
  private void revealRow(TargetRow row) {
    DefaultMutableTreeNode packageNode = findPackage(row.packagePath());
    if (packageNode == null) {
      packageNode =
          new DefaultMutableTreeNode(
              new PackageNode(new TargetQueries.PackageSummary(row.packagePath(), 0, 0, 0), true));
      packageNode.add(new DefaultMutableTreeNode(PENDING));
      root.add(packageNode);
    }
    DefaultMutableTreeNode targetNode = findTarget(packageNode, row.id());
    if (targetNode == null) {
      targetNode = new DefaultMutableTreeNode(new TargetNode(row, true));
      packageNode.insert(targetNode, 0);
    }
    treeModel.reload(packageNode);
    TreePath packagePath = new TreePath(packageNode.getPath());
    TreePath targetPath = new TreePath(targetNode.getPath());
    tree.expandPath(packagePath);
    tree.setSelectionPath(targetPath);
    tree.scrollPathToVisible(targetPath);
    setStatus("Revealed " + row.label() + " directly; package paging remains unchanged.");
  }

  /** Visible for testing: the package nodes currently in the tree. */
  int packageCountForTest() {
    return Math.toIntExact(packageNodeCount());
  }

  String packageTextForTest(int index) {
    return root.getChildAt(index).toString();
  }

  boolean packageLoadNextVisibleForTest() {
    return childWithLoadKind(root, LoadKind.PACKAGES) != null;
  }

  void loadNextPackagesForTest() {
    selectLoadNextForTest(root, LoadKind.PACKAGES);
  }

  void expandPackageForTest(int index) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
    tree.expandPath(new TreePath(node.getPath()));
  }

  int targetCountForTest(int packageIndex) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(packageIndex);
    int count = 0;
    for (int index = 0; index < node.getChildCount(); index++) {
      if (((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject() instanceof TargetNode) {
        count++;
      }
    }
    return count;
  }

  boolean targetLoadNextVisibleForTest(int packageIndex) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(packageIndex);
    return childWithLoadKind(node, LoadKind.TARGETS) != null;
  }

  void loadNextTargetsForTest(int packageIndex) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(packageIndex);
    selectLoadNextForTest(node, LoadKind.TARGETS);
  }

  /** Visible for testing: the selected target row's label, or null. */
  String selectedLabelForTest() {
    TargetRow row = selectedRow();
    return row == null ? null : row.label();
  }

  /** Visible for testing. */
  String statusForTest() {
    return statusLabel.getText();
  }

  private void ensurePackageResults() {
    if (reader != null && !packageLoaded && !packageLoading) {
      loadPackages();
    }
  }

  /** Reads one stable package page off the EDT. Children remain lazy. */
  private void loadPackages() {
    EntityReader current = reader;
    ExecutorService running = executor;
    SessionSource opened = source;
    if (packageLoading || current == null || running == null || opened == null) {
      return;
    }
    packageLoading = true;
    Optional<String> after = Optional.ofNullable(packageAfterPath);
    String requestedFilter = filterText;
    long generation = filterGeneration;
    packageStatus = requestedFilter.isEmpty() ? "Reading targets…" : "Filtering target labels…";
    if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
      setStatus(packageStatus);
    }
    packageTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetQueries.PackageSummary, String> packages =
                    current.packagePage(requestedFilter, after, PACKAGE_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> installPackages(opened, generation, requestedFilter, packages));
              } catch (RuntimeException failure) {
                log.error("could not read targets", failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened && generation == filterGeneration) {
                        packageLoading = false;
                        packageStatus = "Could not read targets: " + failure.getMessage();
                        if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
                          setStatus(packageStatus);
                        }
                      }
                    });
              }
            });
  }

  private void installPackages(
      SessionSource opened,
      long generation,
      String requestedFilter,
      CountedPage<TargetQueries.PackageSummary, String> page) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    packageLoading = false;
    packageLoaded = true;
    TargetRow selectedBeforeReload = selectedRow();
    removeLoadNextChild(root);
    long targets = 0;
    long failed = 0;
    long notCompleted = 0;
    int firstInserted = root.getChildCount();
    for (TargetQueries.PackageSummary summary : page.rows()) {
      targets += summary.targets();
      failed += summary.failed();
      notCompleted += summary.notCompleted();
      DefaultMutableTreeNode existing = findPackage(summary.path());
      if (existing != null) {
        ((PackageNode) existing.getUserObject()).update(summary);
        packageAfterPath = summary.path();
        continue;
      }
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(new PackageNode(summary));
      node.add(new DefaultMutableTreeNode(PENDING));
      root.add(node);
      packageAfterPath = summary.path();
    }
    sortPackageNodes();
    packageTotal = page.totalRows();
    page.nextAnchor()
        .ifPresent(
            anchor ->
                root.add(
                    new DefaultMutableTreeNode(
                        new LoadNextNode(
                            "Load next packages ("
                                + EntityFormat.count(page.remainingRows())
                                + " remaining)",
                            LoadKind.PACKAGES))));
    treeModel.reload(root);
    if (selectedBeforeReload != null) {
      DefaultMutableTreeNode selectedPackage = findPackage(selectedBeforeReload.packagePath());
      DefaultMutableTreeNode selectedNode =
          selectedPackage == null ? null : findTarget(selectedPackage, selectedBeforeReload.id());
      if (selectedNode != null) {
        TreePath packagePath = new TreePath(selectedPackage.getPath());
        TreePath targetPath = new TreePath(selectedNode.getPath());
        tree.expandPath(packagePath);
        tree.setSelectionPath(targetPath);
        tree.scrollPathToVisible(targetPath);
      }
    }
    if (root.getChildCount() == 0) {
      packageStatus =
          requestedFilter.isEmpty()
              ? "This session recorded no targets."
              : "No top-level target labels match \"" + requestedFilter + "\".";
      if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
        setStatus(packageStatus);
        inspector.show(Inspection.NONE);
      }
      return;
    }
    long visiblePackages = packageNodeCount();
    StringBuilder status =
        new StringBuilder()
            .append(EntityFormat.count(visiblePackages))
            .append(" of ")
            .append(EntityFormat.count(packageTotal))
            .append(requestedFilter.isEmpty() ? " packages loaded" : " matching packages loaded")
            .append(" · ")
            .append(EntityFormat.count(targets))
            .append(" target rows in this page");
    if (failed > 0) {
      status.append("  ·  ").append(EntityFormat.count(failed)).append(" failed");
    }
    if (notCompleted > 0) {
      status
          .append("  ·  ")
          .append(EntityFormat.count(notCompleted))
          .append(" configured but never completed");
    }
    packageStatus = status.toString();
    cards.show(deck, CARD_TREE);
    if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
      setStatus(packageStatus);
      if (firstInserted == 0) {
        selectionChanged();
      }
    }
  }

  private long packageNodeCount() {
    long count = 0;
    for (int index = 0; index < root.getChildCount(); index++) {
      if (((DefaultMutableTreeNode) root.getChildAt(index)).getUserObject()
          instanceof PackageNode) {
        count++;
      }
    }
    return count;
  }

  /** Keeps directly revealed packages in the same alphabetical order as keyset-loaded rows. */
  private void sortPackageNodes() {
    List<DefaultMutableTreeNode> packages = new ArrayList<>();
    for (int index = 0; index < root.getChildCount(); index++) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
      if (node.getUserObject() instanceof PackageNode) {
        packages.add(node);
      }
    }
    packages.sort(
        (left, right) ->
            ((PackageNode) left.getUserObject())
                .summary
                .path()
                .compareTo(((PackageNode) right.getUserObject()).summary.path()));
    root.removeAllChildren();
    for (DefaultMutableTreeNode node : packages) {
      root.add(node);
    }
  }

  private DefaultMutableTreeNode findPackage(String path) {
    for (int index = 0; index < root.getChildCount(); index++) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
      if (node.getUserObject() instanceof PackageNode packageNode
          && packageNode.summary.path().equals(path)) {
        return node;
      }
    }
    return null;
  }

  private static void removeLoadNextChild(DefaultMutableTreeNode parent) {
    for (int index = parent.getChildCount() - 1; index >= 0; index--) {
      if (((DefaultMutableTreeNode) parent.getChildAt(index)).getUserObject()
          instanceof LoadNextNode) {
        parent.remove(index);
      }
    }
  }

  /** EDT: swaps the two presentations without changing what counts as top level. */
  private void switchBrowseMode() {
    BrowseMode mode = (BrowseMode) browseMode.getSelectedItem();
    if (mode == BrowseMode.ALL_TARGETS) {
      browseCards.show(browseDeck, BROWSE_ALL_TARGETS);
      setStatus(flatStatus);
      ensureFlatPage();
      flatSelectionChanged();
    } else {
      browseCards.show(browseDeck, BROWSE_PACKAGES);
      setStatus(packageStatus);
      ensurePackageResults();
      selectionChanged();
    }
    updateToolbar();
  }

  /** Restarts the current presentation against a literal label filter after typing settles. */
  private void applyFilter() {
    String next = labelFilter.getText().strip();
    if (next.equals(filterText)) {
      return;
    }
    filterText = next;
    filterGeneration++;
    cancelTask(packageTask, false);
    cancelTask(flatPageTask, false);
    cancelChildTasks(false);
    if (reader != null) {
      reader.cancelRunningQuery();
    }
    cancelDetailTasks();
    if (detailReader != null) {
      detailReader.cancelRunningQuery();
    }
    packageTask = null;
    flatPageTask = null;
    selectionGeneration++;
    revealGeneration++;

    packageLoading = false;
    packageLoaded = false;
    packageAfterPath = null;
    packageTotal = -1;
    root.removeAllChildren();
    treeModel.reload();
    packageStatus = next.isEmpty() ? "Reading targets…" : "Filtering target labels…";

    flatPageLoading = false;
    flatLabelCount = -1;
    flatAfterLabel = null;
    selectedFlatTarget = null;
    flatRoot.removeAllChildren();
    flatTreeModel.reload();
    flatStatus = next.isEmpty() ? "Reading top-level target labels…" : "Filtering target labels…";

    inspector.show(Inspection.NONE);
    inspectionPaging.clear();
    resetDetailPages();
    loadMoreLabels.setEnabled(false);
    updateToolbar();
    if (reader != null) {
      cards.show(deck, CARD_TREE);
      switchBrowseMode();
    }
  }

  private DocumentListener filterListener() {
    return new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        filterDebounce.restart();
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        filterDebounce.restart();
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        filterDebounce.restart();
      }
    };
  }

  private static void cancelTask(Future<?> task, boolean mayInterrupt) {
    if (task != null) {
      task.cancel(mayInterrupt);
    }
  }

  private void ensureFlatPage() {
    if (reader != null && flatRoot.getChildCount() == 0 && !flatPageLoading) {
      loadNextFlatPage();
    }
  }

  /** Appends one bounded page of direct label rows; every SQL read stays off the EDT. */
  private void loadNextFlatPage() {
    EntityReader current = reader;
    ExecutorService running = executor;
    SessionSource opened = source;
    if (flatPageLoading || current == null || running == null || opened == null) {
      return;
    }
    if (flatLabelCount >= 0 && flatRoot.getChildCount() >= flatLabelCount) {
      return;
    }
    flatPageLoading = true;
    loadMoreLabels.setEnabled(false);
    flatStatus =
        flatRoot.getChildCount() == 0
            ? "Reading top-level target labels…"
            : "Reading more target labels…";
    if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
      setStatus(flatStatus);
    }
    String boundary = flatAfterLabel;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    flatPageTask =
        running.submit(
            () -> {
              try {
                CountedPage<String, String> labels =
                    current.topLevelLabelPage(
                        requestedFilter, Optional.ofNullable(boundary), FLAT_LABEL_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> installFlatPage(opened, generation, requestedFilter, labels));
              } catch (RuntimeException failure) {
                log.warn("could not read the flat top-level target list", failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened && generation == filterGeneration) {
                        flatPageLoading = false;
                        flatStatus = "Could not read target labels: " + failure.getMessage();
                        if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
                          setStatus(flatStatus);
                        }
                      }
                    });
              }
            });
  }

  private void installFlatPage(
      SessionSource opened,
      long generation,
      String requestedFilter,
      CountedPage<String, String> page) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    for (String label : page.rows()) {
      flatRoot.add(new DefaultMutableTreeNode(new FlatLabelNode(label)));
    }
    page.nextAnchor().ifPresent(anchor -> flatAfterLabel = anchor);
    flatLabelCount = page.totalRows();
    flatPageLoading = false;
    flatTreeModel.reload();
    long loaded = flatRoot.getChildCount();
    flatStatus =
        page.totalRows() == 0 && !requestedFilter.isEmpty()
            ? "No top-level target labels match \"" + requestedFilter + "\"."
            : EntityFormat.count(loaded)
                + " of "
                + EntityFormat.count(page.totalRows())
                + (requestedFilter.isEmpty()
                    ? " distinct top-level target labels loaded"
                    : " matching top-level target labels loaded");
    boolean hasMore = page.nextAnchor().isPresent();
    loadMoreLabels.setEnabled(hasMore);
    loadMoreLabels.setToolTipText(
        hasMore
            ? "Append the next " + FLAT_LABEL_PAGE_SIZE + " top-level labels."
            : "Every top-level target label is loaded.");
    if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
      setStatus(flatStatus);
    }
  }

  private void flatSelectionChanged() {
    if (browseMode.getSelectedItem() != BrowseMode.ALL_TARGETS) {
      return;
    }
    TreePath path = flatTree.getSelectionPath();
    Object value =
        path == null
            ? null
            : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    if (!(value instanceof FlatLabelNode flat)) {
      selectionGeneration++;
      cancelDetailTasks();
      resetDetailPages();
      selectedFlatTarget = null;
      inspector.show(Inspection.NONE);
      inspectionPaging.clear();
      updateToolbar();
      return;
    }
    selectedFlatTarget = new SelectedTarget(flat.label, OptionalLong.empty());
    updateToolbar();
    inspector.show(Inspection.NONE);
    inspectionPaging.clear();
    cancelDetailTasks();
    resetDetailPages();
    long generation = ++selectionGeneration;
    EntityReader current = detailReader;
    ExecutorService running = detailExecutor;
    SessionSource opened = source;
    if (current == null || running == null) {
      return;
    }
    flatLookupTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetRow, TargetQueries.TargetAnchor> rows =
                    current.targetsByLabelPage(flat.label, Optional.empty(), 1);
                OptionalLong sourceEvent = OptionalLong.empty();
                TargetRow only = rows.totalRows() == 1 ? rows.rows().getFirst() : null;
                Inspection inspection =
                    only == null
                        ? flatInspection(flat.label, rows.totalRows())
                        : TargetInspection.of(only, List.of(), List.of());
                if (only != null) {
                  TargetRow row = only;
                  sourceEvent = row.bepEventId();
                }
                OptionalLong finalSourceEvent = sourceEvent;
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened
                          && generation == selectionGeneration
                          && browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
                        selectedFlatTarget = new SelectedTarget(flat.label, finalSourceEvent);
                        inspector.show(inspection);
                        updateToolbar();
                        if (only != null) {
                          loadTargetDetails(only, generation);
                        }
                      }
                    });
              } catch (RuntimeException failure) {
                log.warn("could not describe top-level target {}", flat.label, failure);
              }
            });
  }

  private static Inspection flatInspection(String label, long rows) {
    return new Inspection.Builder(label)
        .subtitle(EntityFormat.count(rows) + " recorded variants")
        .ref(new EntityRef.TargetLabel(label))
        .section("Top-level target")
        .field("Label", label)
        .field("Rows recorded", EntityFormat.count(rows))
        .field("Inspect individually", "Switch to Packages and expand its package")
        .build();
  }

  /**
   * Fills in a package's targets the first time it is expanded.
   *
   * <p>Synchronous on the EDT would be a query per expansion on the EDT, so the placeholder stays
   * until the rows arrive and the node repopulates itself. The expansion is not vetoed meanwhile: a
   * node that refused to open while loading would feel broken.
   */
  private void loadChildren(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof PackageNode packageNode)
        || packageNode.loading
        || (packageNode.started && packageNode.after.isEmpty())) {
      return;
    }
    packageNode.loading = true;
    ExecutorService running = executor;
    EntityReader current = reader;
    if (running == null || current == null) {
      return;
    }
    SessionSource opened = source;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    Optional<TargetQueries.TargetAnchor> after = packageNode.after;
    Future<?> childTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetRow, TargetQueries.TargetAnchor> page =
                    current.targetsInPackagePage(
                        packageNode.summary.path(),
                        requestedFilter,
                        after,
                        PACKAGE_TARGET_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      childTasks.remove(node);
                      if (source != opened
                          || generation != filterGeneration
                          || node.getParent() != root) {
                        return;
                      }
                      packageNode.loading = false;
                      packageNode.started = true;
                      TargetRow selectedBeforeReload = selectedRow();
                      Long selectedId =
                          selectedBeforeReload == null ? null : selectedBeforeReload.id();
                      removePendingChild(node);
                      removeLoadNextChild(node);
                      for (TargetRow row : page.rows()) {
                        if (findTarget(node, row.id()) == null) {
                          node.add(new DefaultMutableTreeNode(new TargetNode(row, false)));
                        }
                      }
                      packageNode.after = page.nextAnchor();
                      packageNode.total = page.totalRows();
                      page.nextAnchor()
                          .ifPresent(
                              ignored ->
                                  node.add(
                                      new DefaultMutableTreeNode(
                                          new LoadNextNode(
                                              "Load next targets ("
                                                  + EntityFormat.count(page.remainingRows())
                                                  + " remaining)",
                                              LoadKind.TARGETS))));
                      treeModel.nodeStructureChanged(node);
                      if (selectedId != null) {
                        DefaultMutableTreeNode selectedNode = findTarget(node, selectedId);
                        if (selectedNode != null) {
                          TreePath packagePath = new TreePath(node.getPath());
                          TreePath targetPath = new TreePath(selectedNode.getPath());
                          tree.expandPath(packagePath);
                          tree.setSelectionPath(targetPath);
                          tree.scrollPathToVisible(targetPath);
                        }
                      }
                    });
              } catch (RuntimeException failure) {
                log.warn("could not read targets in {}", packageNode.summary.path(), failure);
                SwingUtilities.invokeLater(
                    () -> {
                      childTasks.remove(node);
                      if (source != opened
                          || generation != filterGeneration
                          || node.getParent() != root) {
                        return;
                      }
                      packageNode.loading = false;
                      removePendingChild(node);
                      removeLoadNextChild(node);
                      node.add(
                          new DefaultMutableTreeNode("could not be read: " + failure.getMessage()));
                      treeModel.nodeStructureChanged(node);
                    });
              }
            });
    childTasks.put(node, childTask);
  }

  private void selectionChanged() {
    if (browseMode.getSelectedItem() != BrowseMode.PACKAGES) {
      return;
    }
    if (activateLoadNextSelection()) {
      return;
    }
    // Before any query: the toolbar states what the new selection can and
    // cannot do from what is already in hand, so it never lags a slow
    // inspection read.
    updateToolbar();
    TargetRow row = selectedRow();
    if (row == null) {
      selectionGeneration++;
      cancelDetailTasks();
      resetDetailPages();
      if (detailReader != null) {
        detailReader.cancelRunningQuery();
      }
      inspector.show(Inspection.NONE);
      inspectionPaging.clear();
      return;
    }
    long generation = ++selectionGeneration;
    cancelDetailTasks();
    resetDetailPages();
    if (detailReader != null) {
      detailReader.cancelRunningQuery();
    }
    inspectionPaging.clear();
    inspector.show(Inspection.NONE);
    renderTargetDetailsAsync(row, generation);
    loadTargetDetails(row, generation);
  }

  private void loadTargetDetails(TargetRow row, long generation) {
    loadTagPage(row, Optional.empty(), generation);
    if (row.configuredTargetId().isPresent()) {
      loadOutputGroupPage(row, OptionalLong.empty(), generation);
    } else {
      outputGroupPage = Optional.of(new CountedPage<>(List.of(), 0, 0, Optional.empty()));
      renderTargetDetailsAsync(row, generation);
      inspectionPaging.setPage(
          "output-groups", "Output groups", outputGroupPage.orElseThrow(), () -> {});
    }
  }

  private void loadTagPage(
      TargetRow row, Optional<TargetQueries.TagAnchor> after, long generation) {
    ExecutorService running = detailExecutor;
    EntityReader current = detailReader;
    SessionSource opened = source;
    if (running == null || current == null || opened == null || tagPageLoading) {
      return;
    }
    tagPageLoading = true;
    tagTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor> page =
                    current.tagPage(row.id(), after, TARGET_TAG_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (!sameTargetSelection(row, opened, generation)) {
                        return;
                      }
                      tagPageLoading = false;
                      tagPage = Optional.of(page);
                      renderTargetDetailsAsync(row, generation);
                      inspectionPaging.setPage(
                          "tags",
                          "Tags",
                          page,
                          () ->
                              page.nextAnchor()
                                  .ifPresent(
                                      anchor -> loadTagPage(row, Optional.of(anchor), generation)));
                    });
              } catch (RuntimeException failure) {
                log.warn("could not read tags of target {}", row.label(), failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (sameTargetSelection(row, opened, generation)) {
                        tagPageLoading = false;
                      }
                    });
              }
            });
  }

  private void loadOutputGroupPage(TargetRow row, OptionalLong after, long generation) {
    ExecutorService running = detailExecutor;
    EntityReader current = detailReader;
    SessionSource opened = source;
    if (running == null || current == null || opened == null || outputGroupPageLoading) {
      return;
    }
    outputGroupPageLoading = true;
    outputGroupTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetQueries.OutputGroup, Long> page =
                    current.outputGroupPage(
                        row.configuredTargetId().orElseThrow(), after, OUTPUT_GROUP_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (!sameTargetSelection(row, opened, generation)) {
                        return;
                      }
                      outputGroupPageLoading = false;
                      outputGroupPage = Optional.of(page);
                      renderTargetDetailsAsync(row, generation);
                      inspectionPaging.setPage(
                          "output-groups",
                          "Output groups",
                          page,
                          () ->
                              page.nextAnchor()
                                  .ifPresent(
                                      anchor ->
                                          loadOutputGroupPage(
                                              row, OptionalLong.of(anchor), generation)));
                    });
              } catch (RuntimeException failure) {
                log.warn("could not read output groups of target {}", row.label(), failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (sameTargetSelection(row, opened, generation)) {
                        outputGroupPageLoading = false;
                      }
                    });
              }
            });
  }

  private boolean sameTargetSelection(TargetRow row, SessionSource opened, long generation) {
    if (source != opened || generation != selectionGeneration) {
      return false;
    }
    if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
      TargetRow selected = selectedRow();
      return selected != null && selected.id() == row.id();
    }
    return selectedFlatTarget != null && selectedFlatTarget.label().equals(row.label());
  }

  /** Captures immutable page references on the EDT and builds the inspection on a worker. */
  private void renderTargetDetailsAsync(TargetRow row, long generation) {
    ExecutorService running = inspectionExecutor;
    SessionSource opened = source;
    if (running == null || opened == null) {
      return;
    }
    List<TargetQueries.Tag> tags = tagPage.map(CountedPage::rows).orElse(List.of());
    List<TargetQueries.OutputGroup> outputGroups =
        outputGroupPage.map(CountedPage::rows).orElse(List.of());
    inspectionTask =
        running.submit(
            () -> {
              Inspection inspection = TargetInspection.of(row, tags, outputGroups);
              SwingUtilities.invokeLater(
                  () -> {
                    if (sameTargetSelection(row, opened, generation)) {
                      inspector.show(inspection);
                    }
                  });
            });
  }

  private void resetDetailPages() {
    tagPage = Optional.empty();
    outputGroupPage = Optional.empty();
    tagPageLoading = false;
    outputGroupPageLoading = false;
  }

  private void cancelDetailTasks() {
    cancelTask(flatLookupTask, false);
    cancelTask(tagTask, false);
    cancelTask(outputGroupTask, false);
    cancelTask(inspectionTask, false);
    flatLookupTask = null;
    tagTask = null;
    outputGroupTask = null;
    inspectionTask = null;
  }

  /** Cancels every queued package expansion when its session or filter is replaced. */
  private void cancelChildTasks(boolean mayInterrupt) {
    for (Future<?> childTask : childTasks.values()) {
      childTask.cancel(mayInterrupt);
    }
    childTasks.clear();
  }

  private static void shutdown(ExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean activateLoadNextSelection() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return false;
    }
    DefaultMutableTreeNode selected = (DefaultMutableTreeNode) path.getLastPathComponent();
    if (!(selected.getUserObject() instanceof LoadNextNode loadNext)) {
      return false;
    }
    tree.clearSelection();
    inspector.show(Inspection.NONE);
    inspectionPaging.clear();
    if (loadNext.kind() == LoadKind.PACKAGES) {
      loadPackages();
    } else if (selected.getParent() instanceof DefaultMutableTreeNode parent) {
      loadChildren(parent);
    }
    return true;
  }

  private static void removePendingChild(DefaultMutableTreeNode node) {
    for (int index = node.getChildCount() - 1; index >= 0; index--) {
      if (PENDING.equals(((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject())) {
        node.remove(index);
      }
    }
  }

  private static DefaultMutableTreeNode findTarget(DefaultMutableTreeNode packageNode, long id) {
    for (int index = 0; index < packageNode.getChildCount(); index++) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) packageNode.getChildAt(index);
      if (node.getUserObject() instanceof TargetNode target && target.row.id() == id) {
        return node;
      }
    }
    return null;
  }

  private static DefaultMutableTreeNode childWithLoadKind(
      DefaultMutableTreeNode parent, LoadKind kind) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
      if (child.getUserObject() instanceof LoadNextNode loadNext && loadNext.kind() == kind) {
        return child;
      }
    }
    return null;
  }

  private void selectLoadNextForTest(DefaultMutableTreeNode parent, LoadKind kind) {
    DefaultMutableTreeNode child = childWithLoadKind(parent, kind);
    if (child == null) {
      throw new IllegalStateException("no " + kind + " page remains");
    }
    tree.setSelectionPath(new TreePath(child.getPath()));
  }

  private void setStatus(String value) {
    statusLabel.setText(value);
    setPageMetadata(value, value);
  }

  private void setPageMetadata(String concise, String detail) {
    pageMetadata = concise;
    pageMetadataDetail = detail;
    syncPageMetadata();
  }

  private void syncPageMetadata() {
    if (pageToolbar != null) {
      pageToolbar.setMetadata(pageMetadata, pageMetadataDetail);
    }
  }

  /**
   * One header-toolbar button: a command, the way its ref is built from the selected row, and what
   * to say when it cannot be pressed.
   *
   * <p>The button sits inside a wrapper panel carrying the same tooltip. A disabled Swing component
   * receives no mouse events and therefore never shows its own tooltip — so a disabled button alone
   * would be exactly the unexplained dead control this toolbar exists to avoid. The wrapper gets
   * the events the button cannot and shows the reason.
   */
  private final class ToolbarAction {

    private final JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JButton button;
    private final EntityActions.Command command;
    private final Function<SelectedTarget, Optional<EntityRef>> refOf;
    private final String enabledTip;
    private final String noRefReason;
    private final String unwiredReason;

    ToolbarAction(
        EntityActions.Command command,
        Function<SelectedTarget, Optional<EntityRef>> refOf,
        String enabledTip,
        String noRefReason,
        String unwiredReason) {
      this.command = command;
      this.refOf = refOf;
      this.enabledTip = enabledTip;
      this.noRefReason = noRefReason;
      this.unwiredReason = unwiredReason;
      this.button = new JButton(command.title());
      PlainText.disableHtml(button);
      button.addActionListener(event -> activate());
      wrapper.setOpaque(false);
      wrapper.add(button);
    }

    /** EDT: enables or disables against {@code selected}, with the reason. */
    void update(SelectedTarget selected) {
      Optional<String> unavailable = unavailableReason(selected);
      button.setEnabled(unavailable.isEmpty());
      String tip = PlainText.tooltip(unavailable.orElse(enabledTip));
      button.setToolTipText(tip);
      wrapper.setToolTipText(tip);
    }

    /** Why this cannot be pressed right now, or empty when it can. */
    private Optional<String> unavailableReason(SelectedTarget selected) {
      if (entityActions == null) {
        return Optional.of("Cross-view navigation is not wired into" + " this window.");
      }
      if (!entityActions.isWired(command)) {
        return Optional.of(unwiredReason);
      }
      if (selected == null) {
        return Optional.of("Select a target first — a package row is not a target.");
      }
      return refOf.apply(selected).isEmpty() ? Optional.of(noRefReason) : Optional.empty();
    }

    private void activate() {
      SelectedTarget selected = selectedTarget();
      EntityActions actions = entityActions;
      if (selected == null || actions == null || !actions.isWired(command)) {
        // Unreachable while update() has the last word on enablement,
        // and still checked: dispatching an unwired command throws by
        // design, and a stale click must not be how that is found out.
        return;
      }
      refOf.apply(selected).ifPresent(ref -> actions.navigate(command, ref));
    }
  }

  /** Visible for testing: the toolbar's button for one command. */
  JButton toolbarButtonForTest(EntityActions.Command command) {
    for (ToolbarAction action : toolbarActions) {
      if (action.command == command) {
        return action.button;
      }
    }
    throw new IllegalArgumentException("no toolbar button for " + command);
  }

  /** Visible for testing: the commands the toolbar offers, in order. */
  List<EntityActions.Command> toolbarCommandsForTest() {
    return toolbarActions.stream().map(action -> action.command).toList();
  }

  List<EntityRef> packageRefsForTest(int packageIndex) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(packageIndex);
    return refsAtPath(new TreePath(node.getPath()));
  }

  List<EntityRef> selectedTargetRefsForTest() {
    TreePath path = tree.getSelectionPath();
    return path == null ? List.of() : refsAtPath(path);
  }

  Set<EntityActions.Command> packageOmissionsForTest(int packageIndex) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(packageIndex);
    return omissionsAtPath(new TreePath(node.getPath()));
  }

  List<String> browseModesForTest() {
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < browseMode.getItemCount(); i++) {
      labels.add(browseMode.getItemAt(i).toString());
    }
    return labels;
  }

  void showAllTargetsForTest() {
    browseMode.setSelectedItem(BrowseMode.ALL_TARGETS);
  }

  void showPackagesForTest() {
    browseMode.setSelectedItem(BrowseMode.PACKAGES);
  }

  void setFilterTextForTest(String text) {
    labelFilter.setText(text);
  }

  void applyFilterTextForTest(String text) {
    labelFilter.setText(text);
    filterDebounce.stop();
    applyFilter();
  }

  int flatLabelCountForTest() {
    return flatRoot.getChildCount();
  }

  String flatLabelForTest(int index) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) flatRoot.getChildAt(index);
    return ((FlatLabelNode) node.getUserObject()).label;
  }

  private enum BrowseMode {
    PACKAGES("Packages"),
    ALL_TARGETS("All Targets");

    private final String label;

    BrowseMode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private record SelectedTarget(String label, OptionalLong sourceEventId) {}

  /** A direct, non-expandable row in the flat top-level view. */
  private static final class FlatLabelNode {
    private final String label;

    FlatLabelNode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** A package row in the tree. */
  private static final class PackageNode {
    private TargetQueries.PackageSummary summary;
    private boolean revealedOnly;
    private boolean loading;
    private boolean started;
    private Optional<TargetQueries.TargetAnchor> after = Optional.empty();
    private long total = -1;

    PackageNode(TargetQueries.PackageSummary summary) {
      this(summary, false);
    }

    PackageNode(TargetQueries.PackageSummary summary, boolean revealedOnly) {
      this.summary = summary;
      this.revealedOnly = revealedOnly;
    }

    private void update(TargetQueries.PackageSummary updated) {
      summary = updated;
      revealedOnly = false;
    }

    @Override
    public String toString() {
      if (revealedOnly) {
        return summary.path() + "  (revealed directly; package page not loaded)";
      }
      StringBuilder text =
          new StringBuilder(summary.path())
              .append("  (")
              .append(EntityFormat.count(summary.targets()));
      if (summary.failed() > 0) {
        text.append(", ").append(summary.failed()).append(" failed");
      }
      if (summary.notCompleted() > 0) {
        text.append(", ").append(summary.notCompleted()).append(" not completed");
      }
      return text.append(')').toString();
    }
  }

  /** A target row in the tree. */
  private static final class TargetNode {
    private final TargetRow row;
    private final boolean revealed;

    TargetNode(TargetRow row) {
      this(row, false);
    }

    TargetNode(TargetRow row, boolean revealed) {
      this.row = row;
      this.revealed = revealed;
    }

    @Override
    public String toString() {
      StringBuilder text =
          new StringBuilder(revealed ? "Revealed · " : "").append(row.targetName());
      row.aspect().ifPresent(aspect -> text.append(" (aspect ").append(aspect).append(')'));
      // The outcome of building it, or -- when it never got that far --
      // what analysis said. The two are different questions and the node
      // shows whichever one has an answer.
      text.append("  ")
          .append(
              row.outcome()
                  .map(Enum::name)
                  .orElseGet(() -> row.analysisOutcome().name() + ", not completed"));
      return text.toString();
    }
  }

  private enum LoadKind {
    PACKAGES,
    TARGETS
  }

  private record LoadNextNode(String text, LoadKind kind) {
    private LoadNextNode {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String toString() {
      return text;
    }
  }
}

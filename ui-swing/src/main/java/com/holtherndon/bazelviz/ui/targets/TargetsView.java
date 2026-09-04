package com.holtherndon.bazelviz.ui.targets;

import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileResolver;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
public final class TargetsView extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(TargetsView.class);

  private static final String CARD_EMPTY = "empty";
  private static final String CARD_TREE = "tree";

  private static final String BROWSE_PACKAGES = "packages";
  private static final String BROWSE_ALL_TARGETS = "all-targets";

  /** Top-level labels appended by one explicit flat-list page request. */
  public static final int FLAT_LABEL_PAGE_SIZE = 200;

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
  private final JLabel statusLabel = new JLabel(" ");

  /** The header toolbar's buttons, in the order they are shown. */
  private final List<ToolbarAction> toolbarActions = new ArrayList<>();

  private ExecutorService executor;
  private EntityReader reader;
  private SessionSource source;
  private Future<?> packageTask;
  private Future<?> flatPageTask;
  private LongConsumer showEventHandler = eventId -> {};
  private long selectionGeneration;
  private long filterGeneration;
  private boolean packageLoading;
  private boolean packageLoaded;
  private boolean flatPageLoading;
  private long flatLabelCount = -1;
  private String flatAfterLabel;
  private SelectedTarget selectedFlatTarget;
  private String packageStatus = " ";
  private String flatStatus = " ";
  private String filterText = "";

  /**
   * The shared cross-view navigation actions, once {@link #installEntityActions} has run. Null
   * until then, and the toolbar says so rather than offering buttons with nothing behind them.
   */
  private EntityActions entityActions;

  /**
   * The target a {@link #revealLabel} is waiting to select, once its package's children have
   * loaded. Null when no reveal is pending. Only ever touched on the EDT.
   */
  private Long pendingRevealTargetId;

  /** The package the pending reveal lives in, so another package's load cannot resolve it. */
  private String pendingRevealPackagePath;

  /** Bumped per reveal so a slow lookup cannot land after a newer one. */
  private long revealGeneration;

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
    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("Top level targets", browseDeck),
            new SectionPane("Target details", inspector));
    split.setResizeWeight(0.55);

    JPanel status = new JPanel(new BorderLayout());
    status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    status.add(statusLabel, BorderLayout.WEST);

    JPanel session = new JPanel(new BorderLayout());
    session.add(buildToolbar(), BorderLayout.NORTH);
    session.add(split, BorderLayout.CENTER);
    session.add(status, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(session, CARD_TREE);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /**
   * The header toolbar: what can be done with the selected target elsewhere in the application.
   *
   * <p>Built once, before anything is selected, which is exactly why each button has to be able to
   * explain itself: the strip is on screen from the moment a session opens, and for most of that
   * time some of it has nothing to act on.
   */
  private JPanel buildToolbar() {
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
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
  }

  /** Opens a session and loads the package level. Returns immediately. */
  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    source = newSource;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-targets");
              thread.setDaemon(true);
              return thread;
            });
    showEmpty("Reading targets…");
    ExecutorService opening = executor;
    opening.execute(
        () -> {
          try {
            EntityReader opened = newSource.openEntityReader();
            SwingUtilities.invokeLater(
                () -> {
                  if (source != newSource) {
                    opened.close();
                    return;
                  }
                  reader = opened;
                  cards.show(deck, CARD_TREE);
                  switchBrowseMode();
                });
          } catch (RuntimeException failure) {
            log.error("could not read targets", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (source == newSource) {
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
    filterDebounce.stop();
    filterGeneration++;
    selectionGeneration++;
    filterText = "";
    labelFilter.setText("");
    filterDebounce.stop();
    pendingRevealTargetId = null;
    pendingRevealPackagePath = null;
    revealGeneration++;
    root.removeAllChildren();
    treeModel.reload();
    packageLoading = false;
    packageLoaded = false;
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
    // Whatever was selected is gone with the tree, and the toolbar must
    // not keep offering jumps for a target that is no longer on screen.
    updateToolbar();
    cancelTask(packageTask, true);
    cancelTask(flatPageTask, true);
    packageTask = null;
    flatPageTask = null;
    ExecutorService stopping = executor;
    EntityReader closing = reader;
    source = null;
    executor = null;
    reader = null;
    if (stopping == null && closing == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-targets-close",
        () -> {
          if (stopping != null) {
            stopping.shutdownNow();
            try {
              stopping.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (closing != null) {
            closing.close();
          }
        });
  }

  /**
   * Shows one target by its label: the package path expanded, the target's row selected and
   * inspected — random access by label, where the tree is otherwise browsed top-down.
   *
   * <p>The label is looked up off the EDT ({@code targetsByLabel}), because the package path shown
   * in the tree is the one the database derived and deriving it here again would be a second
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
    running.execute(
        () -> {
          try {
            List<TargetRow> rows = current.targetsByLabel(label);
            SwingUtilities.invokeLater(
                () -> {
                  if (generation != revealGeneration) {
                    return;
                  }
                  if (rows.isEmpty()) {
                    statusLabel.setText("No target named " + label + " in this session.");
                    return;
                  }
                  revealRow(rows.getFirst());
                });
          } catch (RuntimeException failure) {
            log.warn("could not look up {}", label, failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (generation == revealGeneration) {
                    statusLabel.setText("Could not look up " + label + ": " + failure.getMessage());
                  }
                });
          }
        });
  }

  /** EDT: expands the row's package and selects the row, loading it first if needed. */
  private void revealRow(TargetRow row) {
    DefaultMutableTreeNode packageNode = null;
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
      if (child.getUserObject() instanceof PackageNode candidate
          && candidate.summary.path().equals(row.packagePath())) {
        packageNode = child;
        break;
      }
    }
    if (packageNode == null) {
      // The row exists and its package is not in the tree: the two
      // reads disagree, most plausibly because the session grew between
      // them. Saying so beats selecting nothing silently.
      statusLabel.setText(
          "The package "
              + row.packagePath()
              + " is not in the tree; reopen the session to refresh it.");
      return;
    }
    pendingRevealTargetId = row.id();
    pendingRevealPackagePath = row.packagePath();
    PackageNode node = (PackageNode) packageNode.getUserObject();
    if (node.loaded && !hasPendingPlaceholder(packageNode)) {
      // Already populated; select right away.
      selectPendingIn(packageNode);
    } else {
      // Expanding triggers loadChildren, whose arrival completes the
      // reveal. Expanding an already-expanding node is harmless.
      tree.expandPath(new TreePath(packageNode.getPath()));
    }
  }

  private static boolean hasPendingPlaceholder(DefaultMutableTreeNode packageNode) {
    return packageNode.getChildCount() == 1
        && PENDING.equals(((DefaultMutableTreeNode) packageNode.getChildAt(0)).getUserObject());
  }

  /** EDT: selects the pending reveal's target under {@code packageNode}, if both exist. */
  private void selectPendingIn(DefaultMutableTreeNode packageNode) {
    Long wanted = pendingRevealTargetId;
    if (wanted == null) {
      return;
    }
    // Another package loading — a user browsing while a reveal is in
    // flight — must not resolve, or fail, a reveal that lives elsewhere.
    if (!(packageNode.getUserObject() instanceof PackageNode loaded)
        || !loaded.summary.path().equals(pendingRevealPackagePath)) {
      return;
    }
    for (int i = 0; i < packageNode.getChildCount(); i++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) packageNode.getChildAt(i);
      if (child.getUserObject() instanceof TargetNode targetNode && targetNode.row.id() == wanted) {
        pendingRevealTargetId = null;
        pendingRevealPackagePath = null;
        TreePath path = new TreePath(child.getPath());
        tree.expandPath(new TreePath(packageNode.getPath()));
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        return;
      }
    }
    // The package loaded and the row is not among its children: the reads
    // disagree (a live session can grow between them). Say so.
    pendingRevealTargetId = null;
    pendingRevealPackagePath = null;
    statusLabel.setText(
        "The target is no longer in its package's rows;" + " reopen the session to refresh it.");
  }

  /** Visible for testing: the package nodes currently in the tree. */
  int packageCountForTest() {
    return root.getChildCount();
  }

  String packageTextForTest(int index) {
    return root.getChildAt(index).toString();
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

  /** Reads filtered package summaries off the EDT. Children remain lazy. */
  private void loadPackages() {
    EntityReader current = reader;
    ExecutorService running = executor;
    SessionSource opened = source;
    if (packageLoading || current == null || running == null || opened == null) {
      return;
    }
    packageLoading = true;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    packageStatus = requestedFilter.isEmpty() ? "Reading targets…" : "Filtering target labels…";
    if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
      statusLabel.setText(packageStatus);
    }
    packageTask =
        running.submit(
            () -> {
              try {
                List<TargetQueries.PackageSummary> packages = current.packages(requestedFilter);
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
                          statusLabel.setText(packageStatus);
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
      List<TargetQueries.PackageSummary> packages) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    packageLoading = false;
    packageLoaded = true;
    root.removeAllChildren();
    long targets = 0;
    long failed = 0;
    long notCompleted = 0;
    for (TargetQueries.PackageSummary summary : packages) {
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(new PackageNode(summary));
      node.add(new DefaultMutableTreeNode(PENDING));
      root.add(node);
      targets += summary.targets();
      failed += summary.failed();
      notCompleted += summary.notCompleted();
    }
    treeModel.reload();
    if (packages.isEmpty()) {
      packageStatus =
          requestedFilter.isEmpty()
              ? "This session recorded no targets."
              : "No top-level target labels match \"" + requestedFilter + "\".";
      if (browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
        statusLabel.setText(packageStatus);
        inspector.show(Inspection.NONE);
      }
      return;
    }
    StringBuilder status =
        new StringBuilder()
            .append(EntityFormat.count(targets))
            .append(requestedFilter.isEmpty() ? " target rows in " : " matching target rows in ")
            .append(EntityFormat.count(packages.size()))
            .append(packages.size() == 1 ? " package" : " packages");
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
      statusLabel.setText(packageStatus);
      selectionChanged();
    }
  }

  /** EDT: swaps the two presentations without changing what counts as top level. */
  private void switchBrowseMode() {
    BrowseMode mode = (BrowseMode) browseMode.getSelectedItem();
    if (mode == BrowseMode.ALL_TARGETS) {
      browseCards.show(browseDeck, BROWSE_ALL_TARGETS);
      statusLabel.setText(flatStatus);
      ensureFlatPage();
      flatSelectionChanged();
    } else {
      browseCards.show(browseDeck, BROWSE_PACKAGES);
      statusLabel.setText(packageStatus);
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
    packageTask = null;
    flatPageTask = null;
    selectionGeneration++;
    revealGeneration++;
    pendingRevealTargetId = null;
    pendingRevealPackagePath = null;

    packageLoading = false;
    packageLoaded = false;
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
      statusLabel.setText(flatStatus);
    }
    String boundary = flatAfterLabel;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    flatPageTask =
        running.submit(
            () -> {
              try {
                long total =
                    flatLabelCount < 0
                        ? current.topLevelTargetLabelCount(requestedFilter)
                        : flatLabelCount;
                List<String> labels =
                    boundary == null
                        ? current.firstTopLevelTargetLabels(requestedFilter, FLAT_LABEL_PAGE_SIZE)
                        : current.topLevelTargetLabelsAfter(
                            requestedFilter, boundary, FLAT_LABEL_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> installFlatPage(opened, generation, requestedFilter, total, labels));
              } catch (RuntimeException failure) {
                log.warn("could not read the flat top-level target list", failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened && generation == filterGeneration) {
                        flatPageLoading = false;
                        flatStatus = "Could not read target labels: " + failure.getMessage();
                        if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
                          statusLabel.setText(flatStatus);
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
      long total,
      List<String> labels) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    for (String label : labels) {
      flatRoot.add(new DefaultMutableTreeNode(new FlatLabelNode(label)));
    }
    if (!labels.isEmpty()) {
      flatAfterLabel = labels.getLast();
    }
    flatLabelCount = total;
    flatPageLoading = false;
    flatTreeModel.reload();
    long loaded = flatRoot.getChildCount();
    flatStatus =
        total == 0 && !requestedFilter.isEmpty()
            ? "No top-level target labels match \"" + requestedFilter + "\"."
            : EntityFormat.count(loaded)
                + " of "
                + EntityFormat.count(total)
                + (requestedFilter.isEmpty()
                    ? " distinct top-level target labels loaded"
                    : " matching top-level target labels loaded");
    boolean hasMore = loaded < total;
    loadMoreLabels.setEnabled(hasMore);
    loadMoreLabels.setToolTipText(
        hasMore
            ? "Append the next " + FLAT_LABEL_PAGE_SIZE + " top-level labels."
            : "Every top-level target label is loaded.");
    if (browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
      statusLabel.setText(flatStatus);
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
      selectedFlatTarget = null;
      inspector.show(Inspection.NONE);
      updateToolbar();
      return;
    }
    selectedFlatTarget = new SelectedTarget(flat.label, OptionalLong.empty());
    updateToolbar();
    inspector.show(Inspection.NONE);
    long generation = ++selectionGeneration;
    EntityReader current = reader;
    ExecutorService running = executor;
    if (current == null || running == null) {
      return;
    }
    running.execute(
        () -> {
          try {
            List<TargetRow> rows = current.targetsByLabel(flat.label);
            Inspection inspection;
            OptionalLong sourceEvent = OptionalLong.empty();
            if (rows.size() == 1) {
              TargetRow row = rows.getFirst();
              inspection =
                  TargetInspection.of(
                      row,
                      current.targetTags(row.id()),
                      row.configuredTargetId().isPresent()
                          ? current.outputGroups(row.configuredTargetId().getAsLong())
                          : List.of());
              sourceEvent = row.bepEventId();
            } else {
              inspection = flatInspection(flat.label, rows);
            }
            OptionalLong finalSourceEvent = sourceEvent;
            SwingUtilities.invokeLater(
                () -> {
                  if (generation == selectionGeneration
                      && browseMode.getSelectedItem() == BrowseMode.ALL_TARGETS) {
                    selectedFlatTarget = new SelectedTarget(flat.label, finalSourceEvent);
                    inspector.show(inspection);
                    updateToolbar();
                  }
                });
          } catch (RuntimeException failure) {
            log.warn("could not describe top-level target {}", flat.label, failure);
          }
        });
  }

  private static Inspection flatInspection(String label, List<TargetRow> rows) {
    long configurations =
        rows.stream().flatMap(row -> row.configurationId().stream()).distinct().count();
    return new Inspection.Builder(label)
        .subtitle(EntityFormat.count(rows.size()) + " recorded variants")
        .ref(new EntityRef.TargetLabel(label))
        .section("Top-level target")
        .field("Label", label)
        .field("Rows recorded", EntityFormat.count(rows.size()))
        .field("Configurations reported", EntityFormat.count(configurations))
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
    if (!(node.getUserObject() instanceof PackageNode packageNode) || packageNode.loaded) {
      return;
    }
    packageNode.loaded = true;
    ExecutorService running = executor;
    EntityReader current = reader;
    if (running == null || current == null) {
      return;
    }
    SessionSource opened = source;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    running.execute(
        () -> {
          try {
            List<TargetRow> rows =
                current.targetsInPackage(packageNode.summary.path(), requestedFilter);
            SwingUtilities.invokeLater(
                () -> {
                  if (source != opened || generation != filterGeneration) {
                    return;
                  }
                  node.removeAllChildren();
                  for (TargetRow row : rows) {
                    node.add(new DefaultMutableTreeNode(new TargetNode(row)));
                  }
                  treeModel.nodeStructureChanged(node);
                  // A reveal that expanded this package is waiting for
                  // exactly this arrival.
                  selectPendingIn(node);
                });
          } catch (RuntimeException failure) {
            log.warn("could not read targets in {}", packageNode.summary.path(), failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (source != opened || generation != filterGeneration) {
                    return;
                  }
                  node.removeAllChildren();
                  node.add(
                      new DefaultMutableTreeNode("could not be read: " + failure.getMessage()));
                  treeModel.nodeStructureChanged(node);
                });
          }
        });
  }

  private void selectionChanged() {
    if (browseMode.getSelectedItem() != BrowseMode.PACKAGES) {
      return;
    }
    // Before any query: the toolbar states what the new selection can and
    // cannot do from what is already in hand, so it never lags a slow
    // inspection read.
    updateToolbar();
    TargetRow row = selectedRow();
    if (row == null) {
      inspector.show(Inspection.NONE);
      return;
    }
    long generation = ++selectionGeneration;
    ExecutorService running = executor;
    EntityReader current = reader;
    if (running == null || current == null) {
      return;
    }
    SessionSource opened = source;
    running.execute(
        () -> {
          try {
            Inspection inspection =
                TargetInspection.of(
                    row,
                    current.targetTags(row.id()),
                    row.configuredTargetId().isPresent()
                        ? current.outputGroups(row.configuredTargetId().getAsLong())
                        : List.of());
            SwingUtilities.invokeLater(
                () -> {
                  if (source == opened
                      && generation == selectionGeneration
                      && browseMode.getSelectedItem() == BrowseMode.PACKAGES) {
                    inspector.show(inspection);
                  }
                });
          } catch (RuntimeException failure) {
            log.warn("could not describe target {}", row.label(), failure);
          }
        });
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
    private final TargetQueries.PackageSummary summary;
    private boolean loaded;

    PackageNode(TargetQueries.PackageSummary summary) {
      this.summary = summary;
    }

    @Override
    public String toString() {
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

    TargetNode(TargetRow row) {
      this.row = row;
    }

    @Override
    public String toString() {
      StringBuilder text = new StringBuilder(row.targetName());
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
}

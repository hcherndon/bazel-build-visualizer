package com.holtherndon.bazelviz.ui.targets;

import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetQueries.ConfiguredTarget;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
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
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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

/** Every distinct target label, with configured variants expanded on demand. */
public final class AllTargetsView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(AllTargetsView.class);

  /** Distinct labels appended by one explicit page request. */
  public static final int LABEL_PAGE_SIZE = 200;

  /** Nullable checksum groups loaded beneath one expanded label. */
  public static final int CONFIGURATION_GROUP_PAGE_SIZE = 200;

  /** Configured-target rows loaded beneath one checksum group. */
  public static final int CONFIGURATION_VARIANT_PAGE_SIZE = 200;

  private static final String CARD_EMPTY = "empty";
  private static final String CARD_TARGETS = "targets";
  private static final String PENDING = "…";

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel(" ");
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("all targets");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
  private final JTree tree = new JTree(treeModel);
  private final InspectorPanel inspector = new InspectorPanel();
  private final JLabel status = new JLabel(" ");
  private final JButton loadMore = new JButton("Load more targets");
  private final JTextField labelFilter = new JTextField(24);
  private final JLabel filterLabel = new JLabel("Filter labels:");
  private final JPanel localToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
  private final Timer filterDebounce = new Timer(250, event -> applyFilter());

  private SessionSource source;
  private EntityReader reader;
  private EntityReader nodeReader;
  private ExecutorService executor;
  private ExecutorService nodeExecutor;
  private Future<?> pageTask;
  private final Map<DefaultMutableTreeNode, Future<?>> nodeTasks = new IdentityHashMap<>();
  private Future<?> inspectionTask;
  private LongConsumer showEvent = ignored -> {};
  private boolean active;
  private boolean pageLoading;
  private long totalLabels = -1;
  private long loadedConfigurations;
  private String afterLabel;
  private TargetQueries.ConfiguredSource configuredSource;
  private String filterText = "";
  private volatile long filterGeneration;
  private long selectionGeneration;

  /** Bumped whenever a session is opened or closed, including reuse of the same source object. */
  private long sessionGeneration;

  private PageToolbar pageToolbar;
  private String pageMetadata = "";
  private String pageMetadataDetail = "";

  public AllTargetsView() {
    super(new BorderLayout());
    PlainText.install(tree);
    PlainText.disableHtml(status);
    PlainText.disableHtml(labelFilter);
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.addTreeSelectionListener(event -> selectionChanged());
    tree.addTreeWillExpandListener(
        new TreeWillExpandListener() {
          @Override
          public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
            Object selected = node.getUserObject();
            if (selected instanceof LabelNode label) {
              loadConfigurationGroups(label, node);
            } else if (selected instanceof ConfigurationGroupNode group) {
              loadConfigurationVariants(group, node);
            }
          }

          @Override
          public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            // Loaded configurations stay available when a label is reopened.
          }
        });
    inspector.onShowSourceEvent(eventId -> showEvent.accept(eventId));
    inspector.setMinimumSize(new Dimension(300, 160));

    JScrollPane treeScroll = new JScrollPane(tree);
    treeScroll.setMinimumSize(new Dimension(360, 160));
    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("All targets", treeScroll),
            new SectionPane("Target details", inspector));
    split.setResizeWeight(0.55);

    loadMore.setEnabled(false);
    loadMore.setToolTipText(
        "Append the next "
            + LABEL_PAGE_SIZE
            + " fully-qualified labels without loading their configurations.");
    loadMore.addActionListener(event -> loadNextPage());
    filterDebounce.setRepeats(false);
    labelFilter.setToolTipText(
        PlainText.tooltip("Show configured targets whose full Bazel label contains this text."));
    labelFilter.getDocument().addDocumentListener(filterListener());
    filterLabel.setLabelFor(labelFilter);
    localToolbar.add(filterLabel);
    localToolbar.add(labelFilter);
    localToolbar.add(new JLabel("Expand a label to see its configuration hashes."));
    localToolbar.add(loadMore);

    JPanel statusBar = new JPanel(new BorderLayout());
    statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    statusBar.add(status, BorderLayout.WEST);

    JPanel body = new JPanel(new BorderLayout());
    body.add(localToolbar, BorderLayout.NORTH);
    body.add(split, BorderLayout.CENTER);
    body.add(statusBar, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(body, CARD_TARGETS);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /** Moves the root label filter into common chrome while paging remains beside the tree. */
  @Override
  public void installPageToolbar(PageToolbar installed) {
    Objects.requireNonNull(installed, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = installed;
    installed.addAction(filterLabel);
    installed.addAction(labelFilter);
    syncPageMetadata();
    localToolbar.revalidate();
    localToolbar.repaint();
  }

  public void installEntityActions(EntityActions actions) {
    Objects.requireNonNull(actions, "actions");
    inspector.installEntityActions(actions, Set.of(EntityActions.Command.OPEN_TARGET));
    actions.installTreeMenu(
        tree, this::refsAtPath, path -> Set.of(EntityActions.Command.OPEN_TARGET));
  }

  public void onShowSourceEvent(LongConsumer handler) {
    showEvent = Objects.requireNonNull(handler, "handler");
  }

  public void showEmpty(String message) {
    emptyState.setText(Objects.requireNonNull(message, "message"));
    cards.show(deck, CARD_EMPTY);
    setPageMetadata(message.startsWith("No session") ? "" : message, message);
  }

  /** Starts the label query only when this navigation card is visited. */
  public void activate() {
    active = true;
    ensureFirstPage();
  }

  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    long generation = ++sessionGeneration;
    source = newSource;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-all-targets");
              thread.setDaemon(true);
              return thread;
            });
    nodeExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-all-target-nodes");
              thread.setDaemon(true);
              return thread;
            });
    showEmpty("Open All Targets to read target labels.");
    ExecutorService opening = executor;
    opening.execute(
        () -> {
          try {
            EntityReader opened = newSource.openEntityReader();
            EntityReader nodes;
            try {
              nodes = newSource.openEntityReader();
            } catch (RuntimeException failure) {
              opened.close();
              throw failure;
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (source != newSource || generation != sessionGeneration) {
                    ViewClose.runAsync(
                        "bbv-all-targets-stale-open-close",
                        () -> {
                          opened.close();
                          nodes.close();
                        });
                    return;
                  }
                  reader = opened;
                  nodeReader = nodes;
                  ensureFirstPage();
                });
          } catch (RuntimeException failure) {
            log.warn("could not open the All Targets reader", failure);
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
    active = false;
    filterDebounce.stop();
    filterGeneration++;
    selectionGeneration++;
    filterText = "";
    labelFilter.setText("");
    filterDebounce.stop();
    pageLoading = false;
    totalLabels = -1;
    loadedConfigurations = 0;
    afterLabel = null;
    configuredSource = null;
    root.removeAllChildren();
    treeModel.reload();
    inspector.show(Inspection.NONE);
    loadMore.setEnabled(false);
    Future<?> stoppingPage = pageTask;
    Future<?> stoppingInspection = inspectionTask;
    pageTask = null;
    inspectionTask = null;
    if (stoppingPage != null) {
      stoppingPage.cancel(true);
    }
    cancelNodeTasks(true);
    if (stoppingInspection != null) {
      stoppingInspection.cancel(true);
    }
    ExecutorService stopping = executor;
    ExecutorService stoppingNodes = nodeExecutor;
    EntityReader closing = reader;
    EntityReader closingNodes = nodeReader;
    if (closing != null) {
      closing.cancelRunningQuery();
    }
    if (closingNodes != null) {
      closingNodes.cancelRunningQuery();
    }
    source = null;
    executor = null;
    nodeExecutor = null;
    reader = null;
    nodeReader = null;
    showEmpty("No session is open.");
    if (stopping == null && stoppingNodes == null && closing == null && closingNodes == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-all-targets-close",
        () -> {
          if (stopping != null) {
            stopping.shutdownNow();
            try {
              stopping.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (stoppingNodes != null) {
            stoppingNodes.shutdownNow();
            try {
              stoppingNodes.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (closing != null) {
            closing.close();
          }
          if (closingNodes != null) {
            closingNodes.close();
          }
        });
  }

  private void ensureFirstPage() {
    if (active && reader != null && root.getChildCount() == 0 && !pageLoading) {
      loadNextPage();
    }
  }

  private void loadNextPage() {
    EntityReader current = reader;
    ExecutorService running = executor;
    SessionSource opened = source;
    if (!active || pageLoading || current == null || running == null || opened == null) {
      return;
    }
    if (totalLabels >= 0 && root.getChildCount() >= totalLabels) {
      return;
    }
    pageLoading = true;
    loadMore.setEnabled(false);
    String boundary = afterLabel;
    String requestedFilter = filterText;
    long generation = filterGeneration;
    TargetQueries.ConfiguredSource knownSource = configuredSource;
    if (root.getChildCount() == 0) {
      cards.show(deck, CARD_TARGETS);
      setStatus(
          requestedFilter.isEmpty() ? "Reading all target labels…" : "Filtering target labels…");
    } else {
      setStatus("Reading more target labels…");
    }
    pageTask =
        running.submit(
            () -> {
              try {
                CountedPage<TargetQueries.LabelSummary, String> rows =
                    current.labelPage(
                        requestedFilter, Optional.ofNullable(boundary), LABEL_PAGE_SIZE);
                // A replacement increments this volatile generation before asking the active
                // statement to stop. Do not begin the separate source-status read afterward.
                if (generation != filterGeneration) {
                  return;
                }
                Optional<TargetQueries.ConfiguredSource> sourceStatus =
                    knownSource == null
                        ? current.configuredTargetSource()
                        : Optional.of(knownSource);
                if (sourceStatus.isEmpty()) {
                  SwingUtilities.invokeLater(() -> noConfiguredSource(opened, generation));
                  return;
                }
                SwingUtilities.invokeLater(
                    () ->
                        appendPage(
                            opened, generation, requestedFilter, sourceStatus.orElseThrow(), rows));
              } catch (RuntimeException failure) {
                log.warn("could not page all target labels", failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened && generation == filterGeneration) {
                        pageLoading = false;
                        setStatus("Could not list targets: " + failure.getMessage());
                        if (root.getChildCount() == 0) {
                          if (filterText.isEmpty()) {
                            showEmpty(status.getText());
                          } else {
                            cards.show(deck, CARD_TARGETS);
                          }
                        } else {
                          loadMore.setEnabled(true);
                        }
                      }
                    });
              }
            });
  }

  private void noConfiguredSource(SessionSource opened, long generation) {
    if (source == opened && generation == filterGeneration) {
      pageLoading = false;
      showEmpty(
          "This session has no cquery configured-target data. "
              + "Top Level Targets remains available.");
    }
  }

  private void appendPage(
      SessionSource opened,
      long generation,
      String requestedFilter,
      TargetQueries.ConfiguredSource sourceStatus,
      CountedPage<TargetQueries.LabelSummary, String> page) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    pageLoading = false;
    configuredSource = sourceStatus;
    totalLabels = page.totalRows();
    int firstInserted = root.getChildCount();
    for (TargetQueries.LabelSummary summary : page.rows()) {
      LabelNode label = new LabelNode(summary);
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
      if (summary.rows() > 0) {
        node.add(new DefaultMutableTreeNode(PENDING));
      }
      root.add(node);
      loadedConfigurations += summary.configurations();
      afterLabel = summary.label();
    }
    if (!page.rows().isEmpty()) {
      int[] inserted = new int[page.rows().size()];
      for (int index = 0; index < inserted.length; index++) {
        inserted[index] = firstInserted + index;
      }
      treeModel.nodesWereInserted(root, inserted);
    }
    if (root.getChildCount() == 0) {
      if (!requestedFilter.isEmpty()) {
        cards.show(deck, CARD_TARGETS);
        inspector.show(Inspection.NONE);
        loadMore.setEnabled(false);
        setStatus(
            sourceStatus.state().equals("SUCCEEDED")
                ? "No target labels match \"" + requestedFilter + "\"."
                : "No recorded target labels match \""
                    + requestedFilter
                    + "\" · cquery "
                    + sourceStatus.state()
                    + "; results may be incomplete: "
                    + sourceStatus.error().orElse("no error detail was recorded"));
        return;
      }
      String reason =
          sourceStatus.state().equals("SUCCEEDED")
              ? "The cquery completed but reported no configured targets."
              : "The cquery did not produce configured targets: "
                  + sourceStatus.error().orElse(sourceStatus.state());
      showEmpty(reason);
      return;
    }
    cards.show(deck, CARD_TARGETS);
    loadMore.setEnabled(page.nextAnchor().isPresent());
    setStatus(
        EntityFormat.count(root.getChildCount())
            + " of "
            + EntityFormat.count(totalLabels)
            + (requestedFilter.isEmpty()
                ? " target labels loaded · "
                : " matching target labels loaded · ")
            + EntityFormat.count(loadedConfigurations)
            + " configurations represented · "
            + "cquery "
            + sourceStatus.state()
            + ", "
            + sourceStatus.configurationMatch());
    if (tree.getSelectionPath() == null) {
      DefaultMutableTreeNode first = (DefaultMutableTreeNode) root.getChildAt(0);
      tree.setSelectionPath(new TreePath(first.getPath()));
    }
  }

  /** Clears the current page and starts a fresh filtered keyset walk after typing settles. */
  private void applyFilter() {
    String next = labelFilter.getText().strip();
    if (next.equals(filterText)) {
      return;
    }
    filterText = next;
    filterGeneration++;
    selectionGeneration++;
    cancelPendingPage();
    cancelNodeTasks(false);
    cancelInspectionTask();
    if (reader != null) {
      reader.cancelRunningQuery();
    }
    if (nodeReader != null) {
      nodeReader.cancelRunningQuery();
    }
    pageLoading = false;
    totalLabels = -1;
    loadedConfigurations = 0;
    afterLabel = null;
    root.removeAllChildren();
    treeModel.reload();
    inspector.show(Inspection.NONE);
    loadMore.setEnabled(false);
    if (source != null && active) {
      cards.show(deck, CARD_TARGETS);
      setStatus(next.isEmpty() ? "Reading all target labels…" : "Filtering target labels…");
      loadNextPage();
    }
  }

  private void setStatus(String value) {
    status.setText(value);
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

  private void cancelPendingPage() {
    Future<?> pending = pageTask;
    pageTask = null;
    if (pending != null) {
      pending.cancel(false);
    }
  }

  private void cancelNodeTasks(boolean mayInterrupt) {
    for (Future<?> pending : nodeTasks.values()) {
      pending.cancel(mayInterrupt);
    }
    nodeTasks.clear();
  }

  private void cancelInspectionTask() {
    Future<?> pending = inspectionTask;
    inspectionTask = null;
    if (pending != null) {
      pending.cancel(false);
    }
  }

  private static void removePendingAndNext(DefaultMutableTreeNode node) {
    for (int index = node.getChildCount() - 1; index >= 0; index--) {
      Object value = ((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject();
      if (PENDING.equals(value) || value instanceof LoadNextNode) {
        node.remove(index);
      }
    }
  }

  private static DocumentListener filterListener(Timer timer) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        timer.restart();
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        timer.restart();
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        timer.restart();
      }
    };
  }

  private DocumentListener filterListener() {
    return filterListener(filterDebounce);
  }

  private void loadConfigurationGroups(LabelNode label, DefaultMutableTreeNode node) {
    if (label.loading || (label.started && label.after.isEmpty())) {
      return;
    }
    EntityReader current = nodeReader;
    ExecutorService running = nodeExecutor;
    SessionSource opened = source;
    if (current == null || running == null || opened == null) {
      return;
    }
    label.loading = true;
    long generation = filterGeneration;
    Optional<TargetQueries.ConfigurationAnchor> after = label.after;
    Future<?> task =
        running.submit(
            () -> {
              try {
                CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor>
                    page =
                        current.configurationGroupPage(
                            label.summary.label(), after, CONFIGURATION_GROUP_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> installConfigurationGroups(opened, generation, label, node, page));
              } catch (RuntimeException failure) {
                log.warn("could not load configurations for {}", label.summary.label(), failure);
                SwingUtilities.invokeLater(
                    () -> {
                      nodeTasks.remove(node);
                      if (source == opened
                          && generation == filterGeneration
                          && node.getParent() == root) {
                        label.loading = false;
                        removePendingAndNext(node);
                        node.add(
                            new DefaultMutableTreeNode(
                                "could not be read: " + failure.getMessage()));
                        treeModel.nodeStructureChanged(node);
                      }
                    });
              }
            });
    nodeTasks.put(node, task);
  }

  private void installConfigurationGroups(
      SessionSource opened,
      long generation,
      LabelNode label,
      DefaultMutableTreeNode node,
      CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor> page) {
    nodeTasks.remove(node);
    if (source != opened || generation != filterGeneration || node.getParent() != root) {
      return;
    }
    label.loading = false;
    label.started = true;
    removePendingAndNext(node);
    for (TargetQueries.ConfigurationGroup group : page.rows()) {
      DefaultMutableTreeNode configuration =
          new DefaultMutableTreeNode(new ConfigurationGroupNode(label.summary.label(), group));
      configuration.add(new DefaultMutableTreeNode(PENDING));
      node.add(configuration);
    }
    label.after = page.nextAnchor();
    label.total = page.totalRows();
    page.nextAnchor()
        .ifPresent(
            ignored ->
                node.add(
                    new DefaultMutableTreeNode(
                        new LoadNextNode(
                            "Load next configurations ("
                                + EntityFormat.count(page.remainingRows())
                                + " remaining)",
                            LoadKind.CONFIGURATIONS))));
    treeModel.nodeStructureChanged(node);
    setStatus(
        label.summary.label()
            + " · "
            + EntityFormat.count(page.shownThrough())
            + " of "
            + EntityFormat.count(page.totalRows())
            + " configuration groups reached");
  }

  private void loadConfigurationVariants(
      ConfigurationGroupNode group, DefaultMutableTreeNode node) {
    if (group.loading || (group.started && group.after.isEmpty())) {
      return;
    }
    EntityReader current = nodeReader;
    ExecutorService running = nodeExecutor;
    SessionSource opened = source;
    if (current == null || running == null || opened == null) {
      return;
    }
    group.loading = true;
    long generation = filterGeneration;
    OptionalLong after =
        group.after.isPresent() ? OptionalLong.of(group.after.orElseThrow()) : OptionalLong.empty();
    Future<?> task =
        running.submit(
            () -> {
              try {
                CountedPage<ConfiguredTarget, Long> page =
                    current.configuredTargetPage(
                        group.label,
                        group.group.configuration(),
                        after,
                        CONFIGURATION_VARIANT_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> installConfigurationVariants(opened, generation, group, node, page));
              } catch (RuntimeException failure) {
                log.warn("could not load variants for {}", group.label, failure);
                SwingUtilities.invokeLater(
                    () -> {
                      nodeTasks.remove(node);
                      if (source == opened
                          && generation == filterGeneration
                          && node.getParent() != null) {
                        group.loading = false;
                        removePendingAndNext(node);
                        node.add(
                            new DefaultMutableTreeNode(
                                "could not be read: " + failure.getMessage()));
                        treeModel.nodeStructureChanged(node);
                      }
                    });
              }
            });
    nodeTasks.put(node, task);
  }

  private void installConfigurationVariants(
      SessionSource opened,
      long generation,
      ConfigurationGroupNode group,
      DefaultMutableTreeNode node,
      CountedPage<ConfiguredTarget, Long> page) {
    nodeTasks.remove(node);
    if (source != opened || generation != filterGeneration || node.getParent() == null) {
      return;
    }
    group.loading = false;
    group.started = true;
    removePendingAndNext(node);
    for (ConfiguredTarget row : page.rows()) {
      node.add(new DefaultMutableTreeNode(new VariantNode(row)));
    }
    group.after = page.nextAnchor();
    page.nextAnchor()
        .ifPresent(
            ignored ->
                node.add(
                    new DefaultMutableTreeNode(
                        new LoadNextNode(
                            "Load next variants ("
                                + EntityFormat.count(page.remainingRows())
                                + " remaining)",
                            LoadKind.VARIANTS))));
    treeModel.nodeStructureChanged(node);
    setStatus(
        group.label
            + " · "
            + EntityFormat.count(page.shownThrough())
            + " of "
            + EntityFormat.count(page.totalRows())
            + " variants reached");
  }

  private void selectionChanged() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      selectionGeneration++;
      cancelInspectionTask();
      inspector.show(Inspection.NONE);
      return;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    Object selected = node.getUserObject();
    if (selected instanceof LoadNextNode loadNext) {
      tree.clearSelection();
      if (node.getParent() instanceof DefaultMutableTreeNode parent) {
        if (loadNext.kind() == LoadKind.CONFIGURATIONS
            && parent.getUserObject() instanceof LabelNode label) {
          loadConfigurationGroups(label, parent);
        } else if (loadNext.kind() == LoadKind.VARIANTS
            && parent.getUserObject() instanceof ConfigurationGroupNode group) {
          loadConfigurationVariants(group, parent);
        }
      }
      return;
    }
    ConfiguredTarget row = rowOf(selected);
    if (row == null) {
      selectionGeneration++;
      cancelInspectionTask();
      inspector.show(Inspection.NONE);
      return;
    }
    inspectAsync(row);
  }

  private void inspectAsync(ConfiguredTarget row) {
    ExecutorService running = nodeExecutor;
    SessionSource opened = source;
    if (running == null || opened == null) {
      return;
    }
    long generation = ++selectionGeneration;
    long requestedFilterGeneration = filterGeneration;
    cancelInspectionTask();
    inspectionTask =
        running.submit(
            () -> {
              Inspection inspection = inspectionOf(row);
              SwingUtilities.invokeLater(
                  () -> {
                    TreePath path = tree.getSelectionPath();
                    ConfiguredTarget selected =
                        path == null
                            ? null
                            : rowOf(
                                ((DefaultMutableTreeNode) path.getLastPathComponent())
                                    .getUserObject());
                    if (source == opened
                        && generation == selectionGeneration
                        && requestedFilterGeneration == filterGeneration
                        && selected != null
                        && selected.id() == row.id()) {
                      inspector.show(inspection);
                    }
                  });
            });
  }

  private static Inspection inspectionOf(ConfiguredTarget row) {
    Inspection.Builder builder =
        new Inspection.Builder(row.label())
            .subtitle(
                row.configuration()
                    .map(
                        configuration ->
                            "Configuration "
                                + (configuration.isEmpty() ? "<empty checksum>" : configuration))
                    .orElse("Configuration unavailable"))
            .ref(new EntityRef.TargetLabel(row.label()))
            .section("Configured target")
            .field("Label", row.label())
            .field(
                row.configuration().isPresent()
                    ? Inspection.Field.of(
                        "Configuration",
                        row.configuration().orElseThrow().isEmpty()
                            ? "<empty checksum>"
                            : row.configuration().orElseThrow())
                    : Inspection.Field.unknown("Configuration", "cquery did not report a checksum"))
            .field(
                row.ruleClass().isPresent()
                    ? Inspection.Field.of("Rule class", row.ruleClass().orElseThrow())
                    : Inspection.Field.unknown("Rule class", "cquery did not report one"))
            .section("Source")
            .field("Declared by", "configured-target cquery output");
    row.configuration()
        .ifPresent(checksum -> builder.ref(new EntityRef.ConfigurationChecksum(checksum)));
    return builder.build();
  }

  private List<EntityRef> refsAtPath(TreePath path) {
    Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    ConfiguredTarget row = rowOf(selected);
    String label =
        selected instanceof LabelNode labelNode
            ? labelNode.summary.label()
            : selected instanceof ConfigurationGroupNode group
                ? group.label
                : Optional.ofNullable(row).map(ConfiguredTarget::label).orElse(null);
    if (label == null) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>();
    refs.add(new EntityRef.TargetLabel(label));
    Optional<String> checksum =
        selected instanceof ConfigurationGroupNode group
            ? group.group.configuration()
            : Optional.ofNullable(row).flatMap(ConfiguredTarget::configuration);
    checksum.ifPresent(value -> refs.add(new EntityRef.ConfigurationChecksum(value)));
    return refs;
  }

  private static ConfiguredTarget rowOf(Object value) {
    if (value instanceof VariantNode variant) {
      return variant.row;
    }
    return null;
  }

  int labelCountForTest() {
    return root.getChildCount();
  }

  boolean loadMoreEnabledForTest() {
    return loadMore.isEnabled();
  }

  void loadMoreLabelsForTest() {
    loadMore.doClick();
  }

  String labelTextForTest(int index) {
    return root.getChildAt(index).toString();
  }

  String statusForTest() {
    return status.getText();
  }

  void setFilterTextForTest(String text) {
    labelFilter.setText(text);
  }

  void applyFilterTextForTest(String text) {
    labelFilter.setText(text);
    filterDebounce.stop();
    applyFilter();
  }

  String emptyTextForTest() {
    return emptyState.getText();
  }

  boolean emptyTextSelectableForTest() {
    return emptyState.isTextSelectable();
  }

  JPanel emptyStateForTest() {
    return emptyState;
  }

  void expandLabelForTest(int index) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
    tree.expandPath(new TreePath(node.getPath()));
  }

  void expandConfigurationGroupForTest(int labelIndex, int configurationIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    DefaultMutableTreeNode configuration =
        (DefaultMutableTreeNode) label.getChildAt(configurationIndex);
    tree.expandPath(new TreePath(configuration.getPath()));
  }

  List<String> configurationTextsForTest(int index) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
    List<String> texts = new ArrayList<>();
    for (int child = 0; child < node.getChildCount(); child++) {
      texts.add(node.getChildAt(child).toString());
    }
    return texts;
  }

  List<String> variantTextsForTest(int labelIndex, int configurationIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    DefaultMutableTreeNode configuration =
        (DefaultMutableTreeNode) label.getChildAt(configurationIndex);
    List<String> texts = new ArrayList<>();
    for (int child = 0; child < configuration.getChildCount(); child++) {
      texts.add(configuration.getChildAt(child).toString());
    }
    return texts;
  }

  void loadNextConfigurationsForTest(int labelIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    selectLoadNextForTest(label, LoadKind.CONFIGURATIONS);
  }

  void loadNextVariantsForTest(int labelIndex, int configurationIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    DefaultMutableTreeNode configuration =
        (DefaultMutableTreeNode) label.getChildAt(configurationIndex);
    selectLoadNextForTest(configuration, LoadKind.VARIANTS);
  }

  List<EntityRef> configurationRefsForTest(int labelIndex, int configurationIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    DefaultMutableTreeNode configuration =
        (DefaultMutableTreeNode) label.getChildAt(configurationIndex);
    return refsAtPath(new TreePath(configuration.getPath()));
  }

  private void selectLoadNextForTest(DefaultMutableTreeNode parent, LoadKind kind) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
      if (child.getUserObject() instanceof LoadNextNode loadNext && loadNext.kind() == kind) {
        tree.setSelectionPath(new TreePath(child.getPath()));
        return;
      }
    }
    throw new IllegalStateException("no " + kind + " page remains");
  }

  private static final class LabelNode {
    private final TargetQueries.LabelSummary summary;
    private boolean loading;
    private boolean started;
    private Optional<TargetQueries.ConfigurationAnchor> after = Optional.empty();
    private long total = -1;

    LabelNode(TargetQueries.LabelSummary summary) {
      this.summary = summary;
    }

    @Override
    public String toString() {
      if (summary.configurations() > 1) {
        return summary.label()
            + "  ("
            + EntityFormat.count(summary.configurations())
            + " configurations)";
      }
      if (summary.rows() > 1) {
        return summary.label()
            + "  (configuration unavailable, "
            + EntityFormat.count(summary.rows())
            + " variants)";
      }
      return summary.label();
    }
  }

  private static final class ConfigurationGroupNode {
    private final String label;
    private final TargetQueries.ConfigurationGroup group;
    private boolean loading;
    private boolean started;
    private Optional<Long> after = Optional.empty();

    private ConfigurationGroupNode(String label, TargetQueries.ConfigurationGroup group) {
      this.label = Objects.requireNonNull(label, "label");
      this.group = Objects.requireNonNull(group, "group");
    }

    @Override
    public String toString() {
      return "Configuration "
          + group
              .configuration()
              .map(value -> value.isEmpty() ? "<empty checksum>" : value)
              .orElse("unavailable")
          + "  ("
          + EntityFormat.count(group.variants())
          + " variants)";
    }
  }

  private record VariantNode(ConfiguredTarget row) {
    @Override
    public String toString() {
      return "cquery row " + row.id() + row.ruleClass().map(rule -> "  ·  " + rule).orElse("");
    }
  }

  private enum LoadKind {
    CONFIGURATIONS,
    VARIANTS
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

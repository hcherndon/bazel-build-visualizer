package com.holtherndon.bazelviz.ui.targets;

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
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
public final class AllTargetsView extends JPanel {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(AllTargetsView.class);

  /** Distinct labels appended by one explicit page request. */
  public static final int LABEL_PAGE_SIZE = 200;

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
  private final Timer filterDebounce = new Timer(250, event -> applyFilter());

  private SessionSource source;
  private EntityReader reader;
  private ExecutorService executor;
  private Future<?> pageTask;
  private LongConsumer showEvent = ignored -> {};
  private boolean active;
  private boolean pageLoading;
  private long totalLabels = -1;
  private long loadedConfigurations;
  private String afterLabel;
  private TargetQueries.ConfiguredSource configuredSource;
  private String filterText = "";
  private long filterGeneration;

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
            Object selected =
                ((DefaultMutableTreeNode) event.getPath().getLastPathComponent()).getUserObject();
            if (selected instanceof LabelNode label) {
              loadRows(label, (DefaultMutableTreeNode) event.getPath().getLastPathComponent());
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
    JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    JLabel filterLabel = new JLabel("Filter labels:");
    filterLabel.setLabelFor(labelFilter);
    toolbar.add(filterLabel);
    toolbar.add(labelFilter);
    toolbar.add(new JLabel("Expand a label to see its configuration hashes."));
    toolbar.add(loadMore);

    JPanel statusBar = new JPanel(new BorderLayout());
    statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    statusBar.add(status, BorderLayout.WEST);

    JPanel body = new JPanel(new BorderLayout());
    body.add(toolbar, BorderLayout.NORTH);
    body.add(split, BorderLayout.CENTER);
    body.add(statusBar, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(body, CARD_TARGETS);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
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
  }

  /** Starts the label query only when this navigation card is visited. */
  public void activate() {
    active = true;
    ensureFirstPage();
  }

  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    source = newSource;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-all-targets");
              thread.setDaemon(true);
              return thread;
            });
    showEmpty("Open All Targets to read target labels.");
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
                  ensureFirstPage();
                });
          } catch (RuntimeException failure) {
            log.warn("could not open the All Targets reader", failure);
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
    active = false;
    filterDebounce.stop();
    filterGeneration++;
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
    pageTask = null;
    if (stoppingPage != null) {
      stoppingPage.cancel(true);
    }
    ExecutorService stopping = executor;
    EntityReader closing = reader;
    source = null;
    executor = null;
    reader = null;
    showEmpty("No session is open.");
    if (stopping == null && closing == null) {
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
          if (closing != null) {
            closing.close();
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
    if (root.getChildCount() == 0) {
      cards.show(deck, CARD_TARGETS);
      status.setText(
          requestedFilter.isEmpty() ? "Reading all target labels…" : "Filtering target labels…");
    } else {
      status.setText("Reading more target labels…");
    }
    pageTask =
        running.submit(
            () -> {
              try {
                Optional<TargetQueries.ConfiguredSource> sourceStatus =
                    configuredSource == null
                        ? current.configuredTargetSource()
                        : Optional.of(configuredSource);
                if (sourceStatus.isEmpty()) {
                  SwingUtilities.invokeLater(() -> noConfiguredSource(opened, generation));
                  return;
                }
                long exactTotal =
                    totalLabels < 0 ? current.targetLabelCount(requestedFilter) : totalLabels;
                List<TargetQueries.LabelSummary> rows =
                    boundary == null
                        ? current.firstTargetLabels(requestedFilter, LABEL_PAGE_SIZE)
                        : current.targetLabelsAfter(requestedFilter, boundary, LABEL_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () ->
                        appendPage(
                            opened,
                            generation,
                            requestedFilter,
                            sourceStatus.orElseThrow(),
                            exactTotal,
                            rows));
              } catch (RuntimeException failure) {
                log.warn("could not page all target labels", failure);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source == opened && generation == filterGeneration) {
                        pageLoading = false;
                        status.setText("Could not list targets: " + failure.getMessage());
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
      long exactTotal,
      List<TargetQueries.LabelSummary> summaries) {
    if (source != opened || generation != filterGeneration) {
      return;
    }
    pageLoading = false;
    configuredSource = sourceStatus;
    totalLabels = exactTotal;
    int firstInserted = root.getChildCount();
    for (TargetQueries.LabelSummary summary : summaries) {
      LabelNode label = new LabelNode(summary);
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
      if (summary.rows() > 1) {
        node.add(new DefaultMutableTreeNode(PENDING));
      }
      root.add(node);
      loadedConfigurations += summary.configurations();
      afterLabel = summary.label();
    }
    if (!summaries.isEmpty()) {
      int[] inserted = new int[summaries.size()];
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
        status.setText(
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
    boolean countChangedDuringRead = summaries.isEmpty() && root.getChildCount() < totalLabels;
    loadMore.setEnabled(!countChangedDuringRead && root.getChildCount() < totalLabels);
    status.setText(
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
            + sourceStatus.configurationMatch()
            + (countChangedDuringRead
                ? " · the live target list changed; reopen to refresh its count"
                : ""));
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
    cancelPendingPage();
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
      status.setText(next.isEmpty() ? "Reading all target labels…" : "Filtering target labels…");
      loadNextPage();
    }
  }

  private void cancelPendingPage() {
    Future<?> pending = pageTask;
    pageTask = null;
    if (pending != null) {
      pending.cancel(false);
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

  private void loadRows(LabelNode label, DefaultMutableTreeNode node) {
    if (label.loading || label.loaded) {
      return;
    }
    EntityReader current = reader;
    ExecutorService running = executor;
    SessionSource opened = source;
    if (current == null || running == null || opened == null) {
      return;
    }
    label.loading = true;
    long generation = filterGeneration;
    running.execute(
        () -> {
          try {
            List<ConfiguredTarget> rows = current.configuredTargetsByLabel(label.summary.label());
            SwingUtilities.invokeLater(() -> installRows(opened, generation, label, node, rows));
          } catch (RuntimeException failure) {
            log.warn("could not load configurations for {}", label.summary.label(), failure);
            SwingUtilities.invokeLater(
                () -> {
                  label.loading = false;
                  if (source == opened && generation == filterGeneration) {
                    node.removeAllChildren();
                    node.add(
                        new DefaultMutableTreeNode("could not be read: " + failure.getMessage()));
                    treeModel.nodeStructureChanged(node);
                  }
                });
          }
        });
  }

  private void installRows(
      SessionSource opened,
      long generation,
      LabelNode label,
      DefaultMutableTreeNode node,
      List<ConfiguredTarget> rows) {
    label.loading = false;
    if (source != opened || generation != filterGeneration) {
      return;
    }
    label.loaded = true;
    node.removeAllChildren();
    if (rows.size() == 1) {
      label.singleRow = rows.getFirst();
      treeModel.nodeStructureChanged(node);
      if (tree.getSelectionPath() != null
          && tree.getSelectionPath().getLastPathComponent() == node) {
        selectionChanged();
      }
      return;
    }
    for (ConfigurationGroup group : groupConfigurations(rows)) {
      if (group.rows().size() == 1) {
        node.add(
            new DefaultMutableTreeNode(
                new ConfigurationNode(group.hash(), group.rows().getFirst())));
      } else {
        DefaultMutableTreeNode configuration =
            new DefaultMutableTreeNode(new ConfigurationGroupNode(group));
        for (ConfiguredTarget row : group.rows()) {
          configuration.add(new DefaultMutableTreeNode(new VariantNode(row)));
        }
        node.add(configuration);
      }
    }
    treeModel.nodeStructureChanged(node);
  }

  static List<ConfigurationGroup> groupConfigurations(List<ConfiguredTarget> rows) {
    Map<String, List<ConfiguredTarget>> grouped = new LinkedHashMap<>();
    for (ConfiguredTarget row : rows) {
      grouped.computeIfAbsent(configurationOf(row), ignored -> new ArrayList<>()).add(row);
    }
    return grouped.entrySet().stream()
        .map(entry -> new ConfigurationGroup(entry.getKey(), entry.getValue()))
        .toList();
  }

  private void selectionChanged() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      inspector.show(Inspection.NONE);
      return;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    Object selected = node.getUserObject();
    if (selected instanceof LabelNode label && label.summary.rows() == 1 && !label.loaded) {
      loadRows(label, node);
    }
    ConfiguredTarget row = rowOf(selected);
    if (row == null) {
      inspector.show(Inspection.NONE);
      return;
    }
    inspect(row);
  }

  private void inspect(ConfiguredTarget row) {
    Inspection.Builder builder =
        new Inspection.Builder(row.label())
            .subtitle(
                row.configuration()
                    .map(configuration -> "Configuration " + configuration)
                    .orElse("Configuration unavailable"))
            .ref(new EntityRef.TargetLabel(row.label()))
            .section("Configured target")
            .field("Label", row.label())
            .field(
                row.configuration().isPresent()
                    ? Inspection.Field.of("Configuration", row.configuration().orElseThrow())
                    : Inspection.Field.unknown("Configuration", "cquery did not report a checksum"))
            .field(
                row.ruleClass().isPresent()
                    ? Inspection.Field.of("Rule class", row.ruleClass().orElseThrow())
                    : Inspection.Field.unknown("Rule class", "cquery did not report one"))
            .section("Source")
            .field("Declared by", "configured-target cquery output");
    row.configuration()
        .ifPresent(checksum -> builder.ref(new EntityRef.ConfigurationChecksum(checksum)));
    inspector.show(builder.build());
  }

  private List<EntityRef> refsAtPath(TreePath path) {
    Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    ConfiguredTarget row = rowOf(selected);
    String label =
        selected instanceof LabelNode labelNode
            ? labelNode.summary.label()
            : selected instanceof ConfigurationGroupNode group
                ? group.group.rows().getFirst().label()
                : Optional.ofNullable(row).map(ConfiguredTarget::label).orElse(null);
    if (label == null) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>();
    refs.add(new EntityRef.TargetLabel(label));
    Optional<String> checksum =
        selected instanceof ConfigurationGroupNode group
                && !group.group.hash().equals("unavailable")
            ? Optional.of(group.group.hash())
            : Optional.ofNullable(row).flatMap(ConfiguredTarget::configuration);
    checksum.ifPresent(value -> refs.add(new EntityRef.ConfigurationChecksum(value)));
    return refs;
  }

  private static ConfiguredTarget rowOf(Object value) {
    if (value instanceof LabelNode label) {
      return label.singleRow;
    }
    if (value instanceof ConfigurationNode configuration) {
      return configuration.row;
    }
    if (value instanceof VariantNode variant) {
      return variant.row;
    }
    return null;
  }

  private static String configurationOf(ConfiguredTarget row) {
    return row.configuration().orElse("unavailable");
  }

  int labelCountForTest() {
    return root.getChildCount();
  }

  boolean loadMoreEnabledForTest() {
    return loadMore.isEnabled();
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

  List<String> configurationTextsForTest(int index) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(index);
    List<String> texts = new ArrayList<>();
    for (int child = 0; child < node.getChildCount(); child++) {
      texts.add(node.getChildAt(child).toString());
    }
    return texts;
  }

  List<EntityRef> configurationRefsForTest(int labelIndex, int configurationIndex) {
    DefaultMutableTreeNode label = (DefaultMutableTreeNode) root.getChildAt(labelIndex);
    DefaultMutableTreeNode configuration =
        (DefaultMutableTreeNode) label.getChildAt(configurationIndex);
    return refsAtPath(new TreePath(configuration.getPath()));
  }

  record ConfigurationGroup(String hash, List<ConfiguredTarget> rows) {
    ConfigurationGroup {
      Objects.requireNonNull(hash, "hash");
      rows = List.copyOf(rows);
    }
  }

  private static final class LabelNode {
    private final TargetQueries.LabelSummary summary;
    private boolean loading;
    private boolean loaded;
    private ConfiguredTarget singleRow;

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

  private record ConfigurationNode(String hash, ConfiguredTarget row) {
    @Override
    public String toString() {
      return "Configuration " + hash + row.ruleClass().map(rule -> "  ·  " + rule).orElse("");
    }
  }

  private record ConfigurationGroupNode(ConfigurationGroup group) {
    @Override
    public String toString() {
      return "Configuration "
          + group.hash()
          + "  ("
          + EntityFormat.count(group.rows().size())
          + " variants)";
    }
  }

  private record VariantNode(ConfiguredTarget row) {
    @Override
    public String toString() {
      return "cquery row " + row.id() + row.ruleClass().map(rule -> "  ·  " + rule).orElse("");
    }
  }
}

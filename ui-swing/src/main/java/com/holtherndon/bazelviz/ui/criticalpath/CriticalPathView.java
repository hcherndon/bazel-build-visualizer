package com.holtherndon.bazelviz.ui.criticalpath;

import com.holtherndon.bazelviz.analysis.ActionMetrics;
import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.MetricFormat;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose;
import com.holtherndon.bazelviz.ui.metrics.MetricsService;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ResponsiveGridLayout;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explains Bazel's reported critical path beside the dependency-only lower bound.
 *
 * <p>The two paths never share a table or an unqualified label. Bazel's profile components describe
 * what its scheduler says gated the invocation, but carry no action key. The derived chain has
 * graph and action identities, but models dependencies rather than scheduling. Their gap is a lead
 * for investigation, not a causal diagnosis.
 *
 * <p>Graph-node detail is paged on a dedicated worker. The EDT only formats rows already in the
 * page cache, and an unmatched graph node remains a row marked "not executed or not matched" rather
 * than disappearing from the path.
 */
public final class CriticalPathView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(CriticalPathView.class);
  private static final String CARD_EMPTY = "empty";
  private static final String CARD_CONTENT = "content";
  private static final int TAB_BAZEL_PATH = 0;
  private static final int TAB_DEPENDENCY_PATH = 1;
  private static final int TAB_OBSERVED_FALLBACK = 2;

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel("No session is open.");
  private final JPanel summary = new JPanel(new ResponsiveGridLayout(4, 190, 10, 10));
  private final ScrollableViewport summaryBody = new ScrollableViewport();
  private final JScrollPane summaryScroll = new JScrollPane(summaryBody);
  private final JButton openGraph = new JButton("Open dependency chain in Graph");
  private final JToggleButton showDetails = new JToggleButton("Show details");
  private final JPanel localHeader = new JPanel(new BorderLayout(12, 0));
  private final JPanel content = new JPanel(new BorderLayout());

  private final BazelPathTableModel inlineBazelModel = new BazelPathTableModel();
  private final JTable bazelTable = new JTable(inlineBazelModel);
  private final JTable dependencyTable = new JTable(emptyDependencyModel());
  private final JTable observedFallbackTable = new JTable(emptyObservedFallbackModel());
  private final JTextArea observedFallbackNote = WrappingLabel.create(" ");
  private final JTabbedPane pathTabs = new JTabbedPane();
  private final InspectorPanel inspector = new InspectorPanel();
  private final JTextArea status = WrappingLabel.create(" ");
  private final SectionPane detailsPane = new SectionPane("Coverage & interpretation", status);

  private Runnable onOpenGraph = () -> {};
  private EntityActions entityActions;
  private SessionSource session;
  private ExecutorService worker;
  private GraphQueries graphQueries;
  private MetricQueries metricQueries;
  private GraphQueries.GraphSource actionGraphSource;
  private boolean graphMetadataLoaded;
  private String graphOpenFailure;
  private boolean bazelDetailsLoaded;
  private String bazelOpenFailure;
  private String bazelDisplayFailure;
  private MetricsService.Result result;
  private PagedTableModel<CriticalPaths.BazelComponent> bazelModel;
  private PagedTableModel<CriticalPathRow> dependencyModel;
  private CriticalPaths.ObservedActionLowerBound observedFallback;
  private Map<Long, ActionMetrics> contributorByAction = Map.of();
  private long generation;
  private PageToolbar pageToolbar;

  public CriticalPathView() {
    super(new BorderLayout());

    configureTable(bazelTable);
    configureTable(dependencyTable);
    configureTable(observedFallbackTable);
    bazelTable.getColumnModel().getColumn(0).setPreferredWidth(55);
    bazelTable.getColumnModel().getColumn(1).setPreferredWidth(620);
    bazelTable.getColumnModel().getColumn(2).setPreferredWidth(100);
    bazelTable
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                showSelectedBazelComponent();
              }
            });
    dependencyTable
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                showSelectedDependencyStep();
              }
            });
    dependencyTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
              revealSelectedAction();
            }
          }
        });
    observedFallbackTable
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                showSelectedObservedFallback();
              }
            });

    pathTabs.addTab("Bazel-reported critical path", new JScrollPane(bazelTable));
    pathTabs.addTab(
        "Visualizer-computed dependency critical path", new JScrollPane(dependencyTable));
    JPanel fallbackContent = new JPanel(new BorderLayout(0, 8));
    fallbackContent.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    fallbackContent.add(observedFallbackNote, BorderLayout.NORTH);
    fallbackContent.add(
        new SectionPane("Observed action evidence", new JScrollPane(observedFallbackTable)),
        BorderLayout.CENTER);
    pathTabs.addTab("Observed timing fallback", fallbackContent);
    pathTabs.setEnabledAt(TAB_OBSERVED_FALLBACK, false);
    pathTabs.addChangeListener(
        event -> {
          updateStatus();
          showSelectedPathStep();
        });

    localHeader.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
    JLabel title = PlainText.disableHtml(new JLabel("Critical path analysis"));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 4f));
    title.setToolTipText(
        "Bazel's observed schedule and the visualizer's dependency-only lower bound are"
            + " different measurements.");
    localHeader.add(title, BorderLayout.CENTER);
    JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
    headerActions.add(showDetails);
    headerActions.add(openGraph);
    localHeader.add(headerActions, BorderLayout.EAST);
    openGraph.addActionListener(event -> onOpenGraph.run());
    openGraph.setEnabled(false);
    showDetails.addActionListener(
        event -> {
          detailsPane.setVisible(showDetails.isSelected());
          showDetails.setText(showDetails.isSelected() ? "Hide details" : "Show details");
          summaryBody.revalidate();
        });

    summary.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    SectionPane pathSteps = new SectionPane("Path steps", pathTabs);
    SectionPane stepDetails = new SectionPane("Step details", inspector);
    pathSteps.setMinimumSize(new Dimension(320, 120));
    stepDetails.setMinimumSize(new Dimension(260, 120));
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pathSteps, stepDetails);
    split.setResizeWeight(0.68);
    split.setContinuousLayout(true);

    summaryBody.setLayout(new BoxLayout(summaryBody, BoxLayout.Y_AXIS));
    summaryBody.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    SectionPane comparison = new SectionPane("Critical path summary", summary);
    comparison.setAlignmentX(LEFT_ALIGNMENT);
    detailsPane.setAlignmentX(LEFT_ALIGNMENT);
    detailsPane.setVisible(false);
    summaryBody.add(comparison);
    summaryBody.add(Box.createVerticalStrut(8));
    summaryBody.add(detailsPane);
    summaryScroll.setBorder(BorderFactory.createEmptyBorder());
    summaryScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    summaryScroll.getVerticalScrollBar().setUnitIncrement(16);
    summaryScroll.setMinimumSize(new Dimension(0, 90));
    summaryScroll.setPreferredSize(new Dimension(0, 210));

    JSplitPane analysis = new JSplitPane(JSplitPane.VERTICAL_SPLIT, summaryScroll, split);
    analysis.setResizeWeight(0.0);
    analysis.setContinuousLayout(true);

    content.add(localHeader, BorderLayout.NORTH);
    content.add(analysis, BorderLayout.CENTER);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(content, CARD_CONTENT);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /** Moves page-level actions into the window's shared chrome. */
  @Override
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = toolbar;
    content.remove(localHeader);
    toolbar.addAction(showDetails);
    toolbar.addAction(openGraph);
    syncPageMetadata("", "");
    content.revalidate();
    content.repaint();
  }

  /** Opens only this view's graph-detail reader. Metric collection remains shared. */
  public void openSession(SessionSource opened) {
    Objects.requireNonNull(opened, "opened");
    closeSessionAsync();
    session = opened;
    long wanted = ++generation;
    worker = singleThreadExecutor();
    ExecutorService opening = worker;
    graphMetadataLoaded = false;
    bazelDetailsLoaded = false;
    bazelOpenFailure = null;
    bazelDisplayFailure = null;
    showEmpty("Analyzing critical paths…");
    opening.execute(
        () -> {
          GraphQueries graphCandidate = null;
          MetricQueries metricCandidate = null;
          GraphQueries.GraphSource source = null;
          String graphFailure = null;
          String metricFailure = null;
          try {
            graphCandidate = opened.openGraphQueries();
            source =
                graphCandidate.sources().stream()
                    .filter(item -> item.kind().equals("DECLARED_ACTIONS"))
                    .findFirst()
                    .orElse(null);
          } catch (RuntimeException | SQLException problem) {
            graphFailure = problem.getMessage();
            log.debug("could not open critical-path graph details", problem);
            closeQuietly(graphCandidate);
            graphCandidate = null;
          }
          try {
            metricCandidate = opened.openMetricQueries();
          } catch (RuntimeException problem) {
            metricFailure = problem.getMessage();
            log.debug("could not open Bazel critical-path component details", problem);
            closeQuietly(metricCandidate);
            metricCandidate = null;
          }
          GraphQueries readyGraph = graphCandidate;
          MetricQueries readyMetrics = metricCandidate;
          GraphQueries.GraphSource graphSource = source;
          String readyGraphFailure = graphFailure;
          String readyMetricFailure = metricFailure;
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != generation || session != opened) {
                  ViewClose.runAsync(
                      "bbv-stale-critical-path-close",
                      () -> {
                        closeQuietly(readyGraph);
                        closeQuietly(readyMetrics);
                      });
                  return;
                }
                graphQueries = readyGraph;
                metricQueries = readyMetrics;
                actionGraphSource = graphSource;
                graphMetadataLoaded = true;
                graphOpenFailure = readyGraphFailure;
                bazelDetailsLoaded = true;
                bazelOpenFailure = readyMetricFailure;
                if (result != null) {
                  render(result);
                }
              });
        });
  }

  /** Renders the shared metric scan. Called on the EDT by {@link MetricsService}. */
  public void show(MetricsService.Result next) {
    result = Objects.requireNonNull(next, "next");
    render(next);
  }

  /** Makes a shared metric failure visible instead of leaving an endless loading state. */
  public void showFailure(Throwable failure) {
    if (session == null) {
      return;
    }
    String message =
        failure == null || failure.getMessage() == null
            ? "The critical-path analysis could not be read."
            : "The critical-path analysis could not be read: " + failure.getMessage();
    showEmpty(message);
  }

  /** Installs the same action/target navigation vocabulary used by the entity tables. */
  public void installEntityActions(EntityActions actions) {
    entityActions = Objects.requireNonNull(actions, "actions");
    inspector.installEntityActions(actions, Set.of());
    actions.installRowMenu(dependencyTable, row -> refsFor(dependencyRowAt(row)), Set.of());
    actions.installRowMenu(
        observedFallbackTable, ignored -> refsForObservedFallback(observedFallback), Set.of());
  }

  /** Where the fixed whole-chain button goes. */
  public void onOpenGraph(Runnable listener) {
    onOpenGraph = Objects.requireNonNull(listener, "listener");
  }

  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and closes this view's reader after queued page work stops. */
  public CompletionStage<Void> closeSessionAsync() {
    generation++;
    session = null;
    result = null;
    contributorByAction = Map.of();
    observedFallback = null;
    actionGraphSource = null;
    graphMetadataLoaded = false;
    graphOpenFailure = null;
    bazelDetailsLoaded = false;
    bazelOpenFailure = null;
    bazelDisplayFailure = null;
    bazelModel = null;
    PagedTableModel<CriticalPathRow> oldModel = dependencyModel;
    dependencyModel = null;
    dependencyTable.setModel(emptyDependencyModel());
    observedFallbackTable.setModel(emptyObservedFallbackModel());
    observedFallbackNote.setText(" ");
    inlineBazelModel.setComponents(List.of());
    bazelTable.setModel(inlineBazelModel);
    pathTabs.setEnabledAt(TAB_OBSERVED_FALLBACK, false);
    pathTabs.setSelectedIndex(TAB_BAZEL_PATH);
    inspector.show(Inspection.NONE);
    openGraph.setEnabled(false);
    showDetails.setSelected(false);
    showDetails.setText("Show details");
    detailsPane.setVisible(false);
    showEmpty("No session is open.");

    ExecutorService closingWorker = worker;
    worker = null;
    GraphQueries closingQueries = graphQueries;
    graphQueries = null;
    MetricQueries closingMetrics = metricQueries;
    metricQueries = null;
    if (closingWorker == null
        && closingQueries == null
        && closingMetrics == null
        && oldModel == null) {
      return CompletableFuture.completedFuture(null);
    }
    CompletionStage<Void> workerStopped =
        closingWorker == null
            ? CompletableFuture.completedFuture(null)
            : ExecutorClose.cancelAsync(closingWorker, "bbv-critical-path");
    if (closingQueries == null && closingMetrics == null) {
      return workerStopped;
    }
    // A failed worker shutdown deliberately leaves the reader open. Closing
    // JDBC beneath a query that ignored interruption is less safe than the
    // bounded leak, which the failed completion makes observable.
    return workerStopped.thenCompose(
        ignored ->
            ViewClose.runAsync(
                "bbv-critical-path-reader-close",
                () -> {
                  closeQuietly(closingQueries);
                  closeQuietly(closingMetrics);
                }));
  }

  private void render(MetricsService.Result next) {
    cards.show(deck, CARD_CONTENT);
    contributorByAction =
        Map.copyOf(
            next.metrics().criticalPathActions().stream()
                .collect(
                    Collectors.toMap(
                        ActionMetrics::actionId,
                        action -> action,
                        (first, ignored) -> first,
                        LinkedHashMap::new)));
    CriticalPaths paths = next.metrics().invocation().criticalPaths();
    String bazel =
        paths
            .bazelReportedMicros()
            .value()
            .map(MetricFormat::duration)
            .orElse(EntityFormat.UNKNOWN);
    String dependency =
        paths
            .derived()
            .filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED)
            .map(path -> MetricFormat.duration(path.makespanMicros()))
            .orElse(EntityFormat.UNKNOWN);
    syncPageMetadata(
        "Bazel " + bazel + " · Dependency " + dependency,
        "Bazel-reported critical path: "
            + bazel
            + ". Visualizer-computed dependency critical path: "
            + dependency
            + ".");
    renderSummary(next, paths);
    installBazelPath(paths);
    installDependencyPath(paths.derived(), paths.observedActionLowerBound());
    if (pathTabs.getSelectedIndex() == TAB_DEPENDENCY_PATH
        && dependencyModel != null
        && dependencyModel.getRowCount() > 0) {
      dependencyTable.setRowSelectionInterval(0, 0);
    } else if (pathTabs.getSelectedIndex() == TAB_OBSERVED_FALLBACK && observedFallback != null) {
      observedFallbackTable.setRowSelectionInterval(0, 0);
    } else if (bazelTable.getRowCount() > 0) {
      pathTabs.setSelectedIndex(TAB_BAZEL_PATH);
      bazelTable.setRowSelectionInterval(0, 0);
    } else if (dependencyModel != null && dependencyModel.getRowCount() > 0) {
      pathTabs.setSelectedIndex(TAB_DEPENDENCY_PATH);
      dependencyTable.setRowSelectionInterval(0, 0);
    } else if (observedFallback != null) {
      pathTabs.setSelectedIndex(TAB_OBSERVED_FALLBACK);
      observedFallbackTable.setRowSelectionInterval(0, 0);
    } else {
      inspector.show(noPathInspection(paths));
    }
    openGraph.setEnabled(
        paths
            .derived()
            .filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED)
            .filter(path -> !path.path().isEmpty())
            .isPresent());
    updateStatus();
    revalidate();
    repaint();
  }

  private void showSelectedPathStep() {
    if (pathTabs.getSelectedIndex() == TAB_BAZEL_PATH) {
      if (bazelTable.getSelectedRow() < 0 && bazelTable.getRowCount() > 0) {
        bazelTable.setRowSelectionInterval(0, 0);
      }
      showSelectedBazelComponent();
    } else if (pathTabs.getSelectedIndex() == TAB_DEPENDENCY_PATH) {
      if (dependencyModel != null) {
        if (dependencyTable.getSelectedRow() < 0 && dependencyModel.getRowCount() > 0) {
          dependencyTable.setRowSelectionInterval(0, 0);
        }
        showSelectedDependencyStep();
      } else {
        showPathAvailability();
      }
    } else {
      if (observedFallbackTable.getSelectedRow() < 0 && observedFallback != null) {
        observedFallbackTable.setRowSelectionInterval(0, 0);
      }
      showSelectedObservedFallback();
    }
  }

  private void renderSummary(MetricsService.Result next, CriticalPaths paths) {
    summary.removeAll();
    String bazelValue =
        paths
            .bazelReportedMicros()
            .value()
            .map(MetricFormat::duration)
            .orElse(EntityFormat.UNKNOWN);
    String bazelNote;
    if (paths.bazelComponentCount() > 0) {
      bazelNote = MetricFormat.count(paths.bazelComponentCount()) + " reported components";
      if (!paths.bazelReportedMicros().isKnown()) {
        bazelNote += "; total unavailable";
      }
      bazelNote += paths.bazelReportedMicros().warning().map(warning -> "; " + warning).orElse("");
    } else if (paths.bazelReportedMicros().isKnown()) {
      bazelNote = paths.bazelReportedMicros().warning().orElse("component breakdown unavailable");
    } else {
      bazelNote = paths.bazelReportedMicros().warning().orElse("not reported by this build");
    }
    summary.add(summaryCard(paths.bazelDisplayName(), bazelValue, bazelNote));

    Optional<CriticalPath.Result> derived = paths.derived();
    String derivedValue =
        derived
            .filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED)
            .map(path -> MetricFormat.duration(path.makespanMicros()))
            .orElse(EntityFormat.UNKNOWN);
    String derivedNote =
        derived
            .map(this::derivedNote)
            .orElseGet(
                () ->
                    conciseUnavailableReason(
                        paths
                            .derivedUnavailableReason()
                            .orElseGet(
                                () ->
                                    graphMetadataLoaded
                                            && (actionGraphSource == null
                                                || !actionGraphSource.isTrustworthy())
                                        ? graphTrustProblem()
                                        : "No confirmed action graph and usable duration source")));
    summary.add(summaryCard(paths.derivedDisplayName(), derivedValue, derivedNote));

    Optional<CriticalPaths.ObservedActionLowerBound> fallback = paths.observedActionLowerBound();
    boolean dependencyComputed =
        derived.filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED).isPresent();
    if (!dependencyComputed && fallback.isPresent()) {
      CriticalPaths.ObservedActionLowerBound action = fallback.orElseThrow();
      summary.add(
          summaryCard(
              action.displayName(),
              MetricFormat.duration(action.durationMicros()),
              "Longest of "
                  + MetricFormat.count(action.timedActions())
                  + " actions with usable BEP timing; no dependency links were inferred"));
    } else {
      OptionalLong gap = paths.schedulingGapMicros();
      summary.add(
          summaryCard(
              "Difference between paths",
              gap.isPresent() ? signedDuration(gap.getAsLong()) : EntityFormat.UNKNOWN,
              gap.isPresent() ? gapNote(gap.getAsLong()) : gapUnavailableNote(paths)));
    }

    Optional<ConcurrencySweep.Result> concurrency = next.metrics().invocation().concurrency();
    summary.add(
        summaryCard(
            "Observed idle time",
            concurrency
                .map(value -> MetricFormat.duration(value.idleMicros()))
                .orElse(EntityFormat.UNKNOWN),
            concurrency
                .map(
                    value ->
                        "inside the timed action window; peak "
                            + MetricFormat.count(value.peakActive())
                            + " actions")
                .orElse("No usable action spans; this is not zero")));
  }

  private String derivedNote(CriticalPath.Result path) {
    if (path.outcome() != CriticalPath.Outcome.COMPUTED) {
      return path.describe();
    }
    StringBuilder note =
        new StringBuilder()
            .append(MetricFormat.count(path.path().size()))
            .append(" chain steps; ")
            .append(MetricFormat.count(path.nodeCount() - path.untimedNodes()))
            .append(" of ")
            .append(MetricFormat.count(path.nodeCount()))
            .append(" graph nodes timed");
    if (actionGraphSource != null && !actionGraphSource.isTrustworthy()) {
      note.append("; ").append(graphTrustProblem());
    }
    return note.toString();
  }

  private void installBazelPath(CriticalPaths paths) {
    bazelTable.clearSelection();
    bazelModel = null;
    bazelDisplayFailure = null;
    inlineBazelModel.setComponents(List.of());
    bazelTable.setModel(inlineBazelModel);

    long count = paths.bazelComponentCount();
    if (count == 0) {
      configureBazelColumns();
      return;
    }
    if (paths.bazelComponents().size() == count) {
      inlineBazelModel.setComponents(paths.bazelComponents());
      configureBazelColumns();
      return;
    }

    ExecutorService fetcher = worker;
    MetricQueries queries = metricQueries;
    if (fetcher == null || queries == null) {
      configureBazelColumns();
      return;
    }
    try {
      BazelPathRowSource source = new BazelPathRowSource(count, queries);
      PagedTableModel<CriticalPaths.BazelComponent> model =
          new PagedTableModel<>(
              source,
              bazelColumns(),
              fetcher,
              CriticalPathRowSource.DEFAULT_PAGE_SIZE,
              CriticalPathRowSource.DEFAULT_CACHE_PAGES);
      model.addTableModelListener(event -> showSelectedBazelComponent());
      bazelModel = model;
      bazelTable.setModel(model);
    } catch (IllegalArgumentException problem) {
      bazelDisplayFailure = problem.getMessage();
      log.debug("could not display Bazel critical-path components", problem);
    }
    configureBazelColumns();
  }

  private static List<ColumnSpec<CriticalPaths.BazelComponent>> bazelColumns() {
    return List.of(
        new ColumnSpec<>("Step", component -> (long) component.ordinal() + 1),
        new ColumnSpec<>("Bazel description", CriticalPaths.BazelComponent::description),
        new ColumnSpec<>(
            "Duration", component -> EntityFormat.duration(component.durationMicros())));
  }

  private void configureBazelColumns() {
    bazelTable.getColumnModel().getColumn(0).setPreferredWidth(55);
    bazelTable.getColumnModel().getColumn(1).setPreferredWidth(620);
    bazelTable.getColumnModel().getColumn(2).setPreferredWidth(100);
  }

  private void installDependencyPath(
      Optional<CriticalPath.Result> maybePath,
      Optional<CriticalPaths.ObservedActionLowerBound> maybeFallback) {
    dependencyModel = null;
    dependencyTable.setModel(emptyDependencyModel());
    CriticalPath.Result path = maybePath.orElse(null);
    boolean dependencyAvailable =
        path != null && path.outcome() == CriticalPath.Outcome.COMPUTED && !path.path().isEmpty();
    observedFallback = dependencyAvailable ? null : maybeFallback.orElse(null);
    observedFallbackTable.clearSelection();
    observedFallbackTable.setModel(observedFallbackModel(Optional.ofNullable(observedFallback)));
    pathTabs.setEnabledAt(TAB_OBSERVED_FALLBACK, observedFallback != null);
    ExecutorService fetcher = worker;
    GraphQueries queries = graphQueries;
    if (!dependencyAvailable) {
      observedFallbackNote.setText(
          maybeFallback.isPresent()
              ? "The dependency path is unavailable. This row is only the longest action with"
                  + " usable BEP timing. It does not infer predecessors, a path total, or slack."
              : "The dependency path is unavailable, and the BEP contains no usable action span"
                  + " for a timing-only lower bound.");
      configureObservedFallbackColumns();
      return;
    }
    if (fetcher == null || queries == null) {
      return;
    }
    CriticalPathRowSource source = new CriticalPathRowSource(path, queries);
    PagedTableModel<CriticalPathRow> model =
        new PagedTableModel<>(
            source,
            List.of(
                new ColumnSpec<>("Step", CriticalPathRow::stepNumber),
                new ColumnSpec<>("Action", CriticalPathRow::displayName),
                new ColumnSpec<>("Target", row -> EntityFormat.text(row.targetLabel())),
                new ColumnSpec<>("Path weight", row -> EntityFormat.duration(row.weightMicros())),
                new ColumnSpec<>(
                    "Earliest finish", row -> EntityFormat.duration(row.earliestFinishMicros())),
                new ColumnSpec<>(
                    "Execution match",
                    row ->
                        row.actionId().isPresent()
                            ? "Executed action " + row.actionId().getAsLong()
                            : "Not executed or not matched")),
            fetcher,
            CriticalPathRowSource.DEFAULT_PAGE_SIZE,
            CriticalPathRowSource.DEFAULT_CACHE_PAGES);
    model.addTableModelListener(event -> showSelectedDependencyStep());
    dependencyModel = model;
    dependencyTable.setModel(model);
    dependencyTable.getColumnModel().getColumn(0).setPreferredWidth(55);
    dependencyTable.getColumnModel().getColumn(1).setPreferredWidth(310);
    dependencyTable.getColumnModel().getColumn(2).setPreferredWidth(280);
    dependencyTable.getColumnModel().getColumn(3).setPreferredWidth(95);
    dependencyTable.getColumnModel().getColumn(4).setPreferredWidth(100);
    dependencyTable.getColumnModel().getColumn(5).setPreferredWidth(170);
  }

  private static DefaultTableModel observedFallbackModel(
      Optional<CriticalPaths.ObservedActionLowerBound> maybeFallback) {
    Object[][] rows =
        maybeFallback
            .map(
                action ->
                    new Object[][] {
                      {
                        action.mnemonic().orElse("(mnemonic not recorded)"),
                        action.targetLabel().orElse("(target not recorded)"),
                        action.primaryOutput(),
                        MetricFormat.duration(action.durationMicros())
                      }
                    })
            .orElseGet(() -> new Object[0][0]);
    return new DefaultTableModel(
        rows, new Object[] {"Action", "Target", "Primary output", "Observed duration"}) {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private void configureObservedFallbackColumns() {
    observedFallbackTable.getColumnModel().getColumn(0).setPreferredWidth(170);
    observedFallbackTable.getColumnModel().getColumn(1).setPreferredWidth(280);
    observedFallbackTable.getColumnModel().getColumn(2).setPreferredWidth(360);
    observedFallbackTable.getColumnModel().getColumn(3).setPreferredWidth(130);
  }

  private void showSelectedBazelComponent() {
    int viewRow = bazelTable.getSelectedRow();
    if (viewRow < 0) {
      showPathAvailability();
      return;
    }
    int modelRow = bazelTable.convertRowIndexToModel(viewRow);
    CriticalPaths.BazelComponent component;
    PagedTableModel<CriticalPaths.BazelComponent> paged = bazelModel;
    if (paged == null) {
      if (modelRow >= inlineBazelModel.getRowCount()) {
        showPathAvailability();
        return;
      }
      component = inlineBazelModel.componentAt(modelRow);
    } else {
      component = paged.rowAt(modelRow);
      if (component == null) {
        long page = paged.pageIndexForRow(modelRow);
        Throwable failure = paged.lastFailure();
        if (paged.isPageFailed(page)) {
          String reason =
              pageFailureMessage("The selected Bazel component page could not be loaded", failure);
          inspector.show(
              new Inspection.Builder("Bazel component unavailable")
                  .section("Loading")
                  .field("Status", reason)
                  .build());
        } else {
          inspector.show(
              new Inspection.Builder("Loading Bazel component…")
                  .section("Loading")
                  .field(
                      "Status",
                      "The selected component is loading from the" + " trace-profile index.")
                  .build());
        }
        return;
      }
    }
    Inspection.Builder inspection =
        new Inspection.Builder(component.description())
            .subtitle("Bazel-reported component " + (component.ordinal() + 1))
            .section("Timing")
            .field(
                component.durationMicros().isPresent()
                    ? Inspection.Field.of(
                        "Duration", MetricFormat.duration(component.durationMicros().getAsLong()))
                    : Inspection.Field.unknown(
                        "Duration", "the profile did not record a duration for this component"))
            .section("Source")
            .field("Origin", "Bazel trace profile")
            .field(
                "Action link",
                "Unavailable — Bazel records progress text here, not an action key.");
    inspector.show(inspection.build());
  }

  private void showSelectedDependencyStep() {
    int viewRow = dependencyTable.getSelectedRow();
    if (viewRow < 0) {
      showPathAvailability();
      return;
    }
    int modelRow = dependencyTable.convertRowIndexToModel(viewRow);
    CriticalPathRow row = dependencyRowAt(modelRow);
    if (row == null) {
      PagedTableModel<CriticalPathRow> model = dependencyModel;
      long page = model == null ? -1 : model.pageIndexForRow(modelRow);
      Throwable failure = model == null ? null : model.lastFailure();
      if (model != null && model.isPageFailed(page)) {
        String reason =
            pageFailureMessage("The selected dependency page could not be loaded", failure);
        inspector.show(
            new Inspection.Builder("Dependency step unavailable")
                .section("Loading")
                .field("Status", reason)
                .build());
      } else {
        inspector.show(
            new Inspection.Builder("Loading dependency step…")
                .section("Loading")
                .field("Status", "The selected row's graph details are loading.")
                .build());
      }
      return;
    }
    Inspection.Builder inspection =
        new Inspection.Builder(row.displayName())
            .subtitle("Dependency step " + row.stepNumber() + " of " + row.totalSteps());
    row.actionId().ifPresent(id -> inspection.ref(new EntityRef.ActionId(id)));
    row.targetLabel().ifPresent(label -> inspection.ref(new EntityRef.TargetLabel(label)));
    inspection
        .section("Dependency schedule")
        .field("Graph node", Integer.toString(row.nodeIndex()))
        .field(
            row.weightMicros().isPresent()
                ? Inspection.Field.of(
                    "Path weight", MetricFormat.duration(row.weightMicros().getAsLong()))
                : Inspection.Field.unknown(
                    "Path weight",
                    "this graph node has no measured duration and counts as"
                        + " instantaneous in the lower bound"))
        .field("Earliest start", MetricFormat.duration(row.earliestStartMicros()))
        .field("Earliest finish", MetricFormat.duration(row.earliestFinishMicros()))
        .field("Slack", MetricFormat.duration(row.slackMicros()))
        .field(
            "Weight source",
            result == null
                ? "unknown"
                : result
                    .metrics()
                    .invocation()
                    .criticalPaths()
                    .derived()
                    .map(path -> path.durationSource().pathWeightDescription())
                    .orElse("no dependency path was computed"));
    inspection
        .section("Identity")
        .field(optionalField("Target", row.targetLabel(), "aquery did not record an owning target"))
        .field(optionalField("Mnemonic", row.mnemonic(), "aquery did not record a mnemonic"))
        .field(
            optionalField(
                "Primary output", row.primaryOutput(), "aquery did not record a primary output"))
        .field(
            row.actionId().isPresent()
                ? Inspection.Field.of("Executed action", Long.toString(row.actionId().getAsLong()))
                : Inspection.Field.unknown(
                    "Executed action",
                    "the graph declared this action, but it did not run or could not"
                        + " be correlated to this invocation"));
    ActionMetrics contributor =
        row.actionId().isPresent() ? contributorByAction.get(row.actionId().getAsLong()) : null;
    if (contributor != null) {
      CriticalPath.DurationSource source =
          result
              .metrics()
              .invocation()
              .criticalPaths()
              .derived()
              .map(CriticalPath.Result::durationSource)
              .orElse(CriticalPath.DurationSource.NONE);
      addExecutionDetails(inspection, contributor, source);
    } else if (row.actionId().isPresent()) {
      inspection
          .section("Execution-log signals")
          .field(
              "Detail",
              "Use Reveal action for this step. This page preloads the"
                  + " execution breakdown only for the bounded set of largest matched"
                  + " contributors.");
    }
    inspector.show(inspection.build());
  }

  private void showSelectedObservedFallback() {
    CriticalPaths.ObservedActionLowerBound action = observedFallback;
    if (action == null || observedFallbackTable.getSelectedRow() < 0) {
      showPathAvailability();
      return;
    }
    Inspection.Builder inspection =
        new Inspection.Builder(action.mnemonic().orElse(action.primaryOutput()))
            .subtitle("Longest observed action — not a dependency path")
            .ref(new EntityRef.ActionId(action.actionId()))
            .section("Observed timing")
            .field("Duration", MetricFormat.duration(action.durationMicros()))
            .field("Source", "Action start and end timestamps from the build event stream")
            .field(
                "Timing coverage",
                MetricFormat.count(action.timedActions())
                    + " of "
                    + MetricFormat.count(action.totalActions())
                    + " actions")
            .section("Identity")
            .field(
                optionalField("Target", action.targetLabel(), "the action carried no target label"))
            .field(optionalField("Mnemonic", action.mnemonic(), "the action carried no mnemonic"))
            .field("Primary output", action.primaryOutput())
            .section("Interpretation")
            .field(
                "Limit",
                "No dependency relationship was inferred. This value has no predecessor chain,"
                    + " path total, or slack. It cannot be compared with Bazel's critical path as"
                    + " if it were the missing dependency result.");
    action.targetLabel().ifPresent(label -> inspection.ref(new EntityRef.TargetLabel(label)));
    inspector.show(inspection.build());
  }

  private static void addExecutionDetails(
      Inspection.Builder inspection, ActionMetrics action, CriticalPath.DurationSource pathSource) {
    String section =
        pathSource == CriticalPath.DurationSource.EXECUTION_ATTEMPT
            ? "Execution-log work across all attempts"
            : "Execution-log signals (independent of the path weight)";
    inspection
        .section(section)
        .field(durationField("Queue", action.queueMicros(), "no matched spawn reported queue time"))
        .field(durationField("Setup", action.setupMicros(), "no matched spawn reported setup time"))
        .field(
            durationField(
                "Execution", action.executionMicros(), "no matched spawn reported execution time"))
        .field(
            durationField(
                "Network", action.networkMicros(), "no matched spawn reported network time"))
        .field(
            durationField("Upload", action.uploadMicros(), "no matched spawn reported upload time"))
        .field(durationField("Fetch", action.fetchMicros(), "no matched spawn reported fetch time"))
        .field(
            pathSource == CriticalPath.DurationSource.EXECUTION_ATTEMPT
                ? durationField(
                    "Unaccounted",
                    action.unaccountedMicros(),
                    "the execution log did not provide enough component timings")
                : Inspection.Field.unknown(
                    "Unaccounted",
                    "the path uses a BEP action duration, which cannot be subtracted"
                        + " from execution-log attempt components"))
        .field(
            optionalField(
                "Runner", Optional.ofNullable(action.runner()), "no single runner was reported"))
        .field("Cache state", action.cacheState().displayName())
        .field(
            countField(
                "Concurrency at start",
                action.startConcurrency(),
                "the chosen duration source did not place this action on the clock"))
        .field(
            countField(
                "Concurrency at finish",
                action.completionConcurrency(),
                "the chosen duration source did not place this action on the clock"));
    if (action.attempts() > 1) {
      inspection.field(
          "Interpretation",
          "These values aggregate "
              + action.attempts()
              + " attempts. Reveal action to inspect individual attempts; raced work is"
              + " not added to the dependency path weight.");
    }
  }

  private static Inspection.Field durationField(
      String name, OptionalLong value, String missingReason) {
    return value.isPresent()
        ? Inspection.Field.of(name, MetricFormat.duration(value.getAsLong()))
        : Inspection.Field.unknown(name, missingReason);
  }

  private static Inspection.Field countField(
      String name, OptionalLong value, String missingReason) {
    return value.isPresent()
        ? Inspection.Field.of(name, MetricFormat.count(value.getAsLong()))
        : Inspection.Field.unknown(name, missingReason);
  }

  private static Inspection.Field optionalField(
      String name, Optional<String> value, String missingReason) {
    return value
        .filter(text -> !text.isBlank())
        .map(text -> Inspection.Field.of(name, text))
        .orElseGet(() -> Inspection.Field.unknown(name, missingReason));
  }

  private static String pageFailureMessage(String prefix, Throwable failure) {
    if (failure == null) {
      return prefix + ".";
    }
    String outer = failure.getMessage();
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String detail = root.getMessage();
    if (detail == null || detail.isBlank()) {
      detail = outer;
    } else if (outer != null && !outer.isBlank() && !outer.equals(detail)) {
      detail = outer + ": " + detail;
    }
    return detail == null || detail.isBlank() ? prefix + "." : prefix + ": " + detail;
  }

  private void revealSelectedAction() {
    int selected = dependencyTable.getSelectedRow();
    CriticalPathRow row =
        selected < 0 ? null : dependencyRowAt(dependencyTable.convertRowIndexToModel(selected));
    if (row == null || row.actionId().isEmpty() || entityActions == null) {
      return;
    }
    entityActions.navigate(
        EntityActions.Command.REVEAL_ACTION, new EntityRef.ActionId(row.actionId().getAsLong()));
  }

  private CriticalPathRow dependencyRowAt(int modelRow) {
    PagedTableModel<CriticalPathRow> model = dependencyModel;
    return model == null ? null : model.rowAt(modelRow);
  }

  private static List<EntityRef> refsFor(CriticalPathRow row) {
    if (row == null) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>(2);
    row.actionId().ifPresent(id -> refs.add(new EntityRef.ActionId(id)));
    row.targetLabel().ifPresent(label -> refs.add(new EntityRef.TargetLabel(label)));
    return List.copyOf(refs);
  }

  private static List<EntityRef> refsForObservedFallback(
      CriticalPaths.ObservedActionLowerBound action) {
    if (action == null) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>(2);
    refs.add(new EntityRef.ActionId(action.actionId()));
    action.targetLabel().ifPresent(label -> refs.add(new EntityRef.TargetLabel(label)));
    return List.copyOf(refs);
  }

  private void updateStatus() {
    MetricsService.Result shown = result;
    if (shown == null) {
      status.setText(" ");
      return;
    }
    CriticalPaths paths = shown.metrics().invocation().criticalPaths();
    if (pathTabs.getSelectedIndex() == TAB_BAZEL_PATH) {
      if (paths.bazelComponentCount() == 0) {
        status.setText(
            paths.bazelReportedMicros().isKnown()
                ? "Bazel reported the critical-path total, but "
                    + paths
                        .bazelReportedMicros()
                        .warning()
                        .orElse("its component breakdown is unavailable")
                    + ". This is not an empty path."
                : "Bazel did not provide a critical-path total or component breakdown."
                    + " Unavailable data is not an empty path.");
      } else {
        boolean tableInstalled =
            bazelModel != null || paths.bazelComponents().size() == paths.bazelComponentCount();
        StringBuilder text =
            new StringBuilder(tableInstalled ? "Showing all " : "Bazel reported ")
                .append(MetricFormat.count(paths.bazelComponentCount()))
                .append(" components in Bazel's reported order");
        if (bazelModel != null) {
          text.append("; details load in pages of ")
              .append(CriticalPathRowSource.DEFAULT_PAGE_SIZE)
              .append(" as you scroll");
        }
        text.append(
            ". The trace profile carries progress text but no reliable action"
                + " key, so these rows are not guessed onto Actions.");
        paths
            .bazelReportedMicros()
            .warning()
            .ifPresent(warning -> text.append(' ').append(warning).append('.'));
        if (bazelDisplayFailure != null) {
          text.append(" The component table cannot display this result: ")
              .append(bazelDisplayFailure)
              .append('.');
        } else if (bazelOpenFailure != null) {
          text.append(" Component details could not be opened: ")
              .append(bazelOpenFailure)
              .append('.');
        } else if (!bazelDetailsLoaded
            && paths.bazelComponents().size() < paths.bazelComponentCount()) {
          text.append(" Loading component details…");
        }
        status.setText(text.toString());
      }
      return;
    }
    Optional<CriticalPath.Result> derived = paths.derived();
    if (pathTabs.getSelectedIndex() == TAB_OBSERVED_FALLBACK) {
      CriticalPaths.ObservedActionLowerBound action = paths.observedActionLowerBound().orElse(null);
      if (action == null) {
        status.setText("No observed timing fallback is available.");
        return;
      }
      StringBuilder text =
          new StringBuilder("Observed timing fallback shows the longest individually timed BEP")
              .append(" action only. It is not a dependency path and has no inferred")
              .append(" predecessor chain, path total, slack, or path comparison. Timing")
              .append(" coverage is ")
              .append(MetricFormat.count(action.timedActions()))
              .append(" of ")
              .append(MetricFormat.count(action.totalActions()))
              .append(" actions. ");
      if (derived.isEmpty()) {
        text.append("The dependency path was not computed: ")
            .append(dependencyUnavailableReason(paths))
            .append('.');
      } else {
        text.append("Dependency analysis reported: ").append(derived.orElseThrow().describe());
      }
      status.setText(text.toString());
      return;
    }
    if (derived.isEmpty()) {
      StringBuilder text =
          new StringBuilder("No dependency path was computed: ")
              .append(dependencyUnavailableReason(paths))
              .append('.');
      paths
          .observedActionLowerBound()
          .ifPresent(
              ignored ->
                  text.append(
                      " A separate timing-only lower bound is available in the Observed timing"
                          + " fallback tab; it is not a dependency path."));
      status.setText(text.toString());
      return;
    }
    CriticalPath.Result path = derived.orElseThrow();
    if (path.outcome() != CriticalPath.Outcome.COMPUTED) {
      String text = path.describe();
      if (paths.observedActionLowerBound().isPresent()) {
        text +=
            " A separate timing-only lower bound is available in the Observed timing fallback"
                + " tab; it is not a dependency path.";
      }
      status.setText(text);
      return;
    }
    StringBuilder text =
        new StringBuilder("Showing all ")
            .append(MetricFormat.count(path.path().size()))
            .append(" graph nodes in dependency order; details load in pages of ")
            .append(CriticalPathRowSource.DEFAULT_PAGE_SIZE)
            .append(" as you scroll. ")
            .append(path.describe());
    text.append(" Execution-log breakdown is preloaded for up to ")
        .append(MetricQueries.DEFAULT_CANDIDATE_LIMIT)
        .append(
            " largest matched contributors; Reveal action opens full detail for"
                + " another executed step.");
    shown
        .metrics()
        .invocation()
        .coverage()
        .find("Action-graph correlation")
        .map(Coverage::describe)
        .ifPresent(coverage -> text.append(' ').append(coverage));
    if (graphOpenFailure != null) {
      text.append(" Node details could not be opened: ").append(graphOpenFailure);
    } else if (!graphMetadataLoaded) {
      text.append(" Loading graph-node details…");
    }
    if (graphMetadataLoaded && actionGraphSource != null && !actionGraphSource.isTrustworthy()) {
      text.append(' ').append(graphTrustProblem()).append('.');
    }
    status.setText(text.toString());
  }

  private String dependencyUnavailableReason(CriticalPaths paths) {
    return paths
        .derivedUnavailableReason()
        .orElseGet(
            () ->
                graphMetadataLoaded
                        && (actionGraphSource == null || !actionGraphSource.isTrustworthy())
                    ? graphTrustProblem()
                    : "a confirmed action graph and at least one usable action duration are"
                        + " required");
  }

  private void showPathAvailability() {
    if (result != null) {
      inspector.show(noPathInspection(result.metrics().invocation().criticalPaths()));
    }
  }

  private String graphTrustProblem() {
    if (!graphMetadataLoaded) {
      return "Checking action-graph provenance before comparing paths";
    }
    if (graphOpenFailure != null) {
      return "Action-graph provenance could not be read: " + graphOpenFailure;
    }
    GraphQueries.GraphSource source = actionGraphSource;
    if (source == null) {
      return "No declared action-graph source was recorded";
    }
    if (!source.state().equals("SUCCEEDED")) {
      return "Action-graph import state is "
          + source.state()
          + source.error().map(error -> ": " + error).orElse("");
    }
    if (!source.targetScope().permitsExactClaim()) {
      return source.targetScopeProblem().orElse("The action graph's target scope is unverified.");
    }
    if (!source.configurationMatch().permitsExactClaim()) {
      return source
          .mismatchDetail()
          .filter(detail -> !detail.isBlank())
          .orElse(
              "Action-graph configuration match is "
                  + source.configurationMatch().name().toLowerCase());
    }
    return source.actionGraphCompletenessProblem().orElse("Action graph is trustworthy");
  }

  private static Inspection noPathInspection(CriticalPaths paths) {
    Inspection.Builder builder =
        new Inspection.Builder("No path step selected")
            .section("Availability")
            .field(
                paths.bazelDisplayName(),
                paths.bazelReportedMicros().isKnown()
                    ? "A total is available, but no component is selected."
                    : "Not reported by this build.")
            .field(
                paths.derivedDisplayName(),
                paths.derived().isPresent()
                    ? paths.derived().orElseThrow().describe()
                    : paths
                        .derivedUnavailableReason()
                        .orElse("No confirmed action graph and usable duration source."));
    paths
        .observedActionLowerBound()
        .ifPresent(
            action ->
                builder.field(
                    action.displayName(),
                    MetricFormat.duration(action.durationMicros())
                        + "; this is one observed action, not a dependency chain"));
    return builder.build();
  }

  private static JPanel summaryCard(String title, String value, String note) {
    JPanel card = new JPanel();
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                UIManager.getColor("Separator.foreground") == null
                    ? Color.GRAY
                    : UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(9, 10, 9, 10)));
    JTextArea heading = WrappingLabel.create(title);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD));
    heading.setEnabled(false);
    JTextArea amount = WrappingLabel.create(value);
    amount.setFont(amount.getFont().deriveFont(Font.BOLD, amount.getFont().getSize() + 5f));
    JTextArea detail = WrappingLabel.create(note);
    detail.setEnabled(false);
    heading.setAlignmentX(LEFT_ALIGNMENT);
    amount.setAlignmentX(LEFT_ALIGNMENT);
    detail.setAlignmentX(LEFT_ALIGNMENT);
    card.add(heading);
    card.add(Box.createVerticalStrut(2));
    card.add(amount);
    card.add(Box.createVerticalStrut(2));
    card.add(detail);
    return card;
  }

  private static String signedDuration(long micros) {
    if (micros == 0) {
      return MetricFormat.duration(0);
    }
    return (micros > 0 ? "+" : "−") + MetricFormat.duration(Math.abs(micros));
  }

  private static String gapNote(long micros) {
    if (micros > 0) {
      return "Bazel's path is longer; scheduling, resource limits, or queues may contribute";
    }
    if (micros < 0) {
      return "The dependency result is longer; inspect graph and timing coverage";
    }
    return "The totals match, but the paths still measure different things";
  }

  private static String gapUnavailableNote(CriticalPaths paths) {
    Optional<CriticalPath.Result> derived = paths.derived();
    if (derived
        .filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED)
        .filter(CriticalPath.Result::isPartial)
        .isPresent()) {
      CriticalPath.Result path = derived.orElseThrow();
      return "Comparison withheld: "
          + MetricFormat.count(path.untimedNodes())
          + " graph nodes have no measured duration";
    }
    return "Both a Bazel total and a fully timed, confirmed dependency path are required";
  }

  /** Keeps raw multiline Bazel output in Details instead of letting it set a card's height. */
  private static String conciseUnavailableReason(String reason) {
    if (reason.contains("action-graph import state is FAILED")
        || reason.contains("aquery") && reason.contains("did not run")) {
      return "The action graph is unavailable because aquery failed; open Details for its output";
    }
    int lineEnd = reason.indexOf('\n');
    String firstLine = (lineEnd < 0 ? reason : reason.substring(0, lineEnd)).strip();
    return firstLine.length() <= 180 ? firstLine : firstLine.substring(0, 177) + "…";
  }

  private static void configureTable(JTable table) {
    PlainText.install(table);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setFillsViewportHeight(true);
    table.setRowHeight(Math.max(table.getRowHeight(), 22));
    table.getTableHeader().setReorderingAllowed(false);
  }

  private static DefaultTableModel emptyDependencyModel() {
    return new DefaultTableModel(
        new Object[] {
          "Step", "Action", "Target", "Path weight", "Earliest finish", "Execution match"
        },
        0) {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private static DefaultTableModel emptyObservedFallbackModel() {
    return observedFallbackModel(Optional.empty());
  }

  private void showEmpty(String message) {
    emptyState.setText(message);
    cards.show(deck, CARD_EMPTY);
    syncPageMetadata(message.startsWith("No session") ? "" : message, message);
  }

  private void syncPageMetadata(String concise, String detail) {
    if (pageToolbar != null) {
      pageToolbar.setMetadata(concise, detail);
    }
  }

  private static ExecutorService singleThreadExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "bbv-critical-path");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void closeQuietly(AutoCloseable reader) {
    if (reader == null) {
      return;
    }
    try {
      reader.close();
    } catch (Exception ignored) {
      // Closing a stale read-only view must not replace the active session's UI.
    }
  }

  /** Visible for focused headless tests. */
  EmptyStatePanel emptyStateForTest() {
    return emptyState;
  }

  String summaryTextForTest() {
    StringBuilder text = new StringBuilder();
    collectText(summary, text);
    return text.toString();
  }

  String statusTextForTest() {
    return status.getText();
  }

  JTable bazelTableForTest() {
    return bazelTable;
  }

  JTable dependencyTableForTest() {
    return dependencyTable;
  }

  JTable observedFallbackTableForTest() {
    return observedFallbackTable;
  }

  JScrollPane summaryScrollForTest() {
    return summaryScroll;
  }

  JToggleButton detailsButtonForTest() {
    return showDetails;
  }

  SectionPane detailsPaneForTest() {
    return detailsPane;
  }

  InspectorPanel inspectorForTest() {
    return inspector;
  }

  JButton openGraphForTest() {
    return openGraph;
  }

  void installDependencySourceForTest(CriticalPath.Result path, CriticalPathRowSource source) {
    ExecutorService fetcher = worker;
    if (fetcher == null) {
      worker = singleThreadExecutor();
      fetcher = worker;
    }
    PagedTableModel<CriticalPathRow> model =
        new PagedTableModel<>(
            source,
            List.of(
                new ColumnSpec<>("Step", CriticalPathRow::stepNumber),
                new ColumnSpec<>("Action", CriticalPathRow::displayName),
                new ColumnSpec<>("Target", row -> EntityFormat.text(row.targetLabel())),
                new ColumnSpec<>("Path weight", row -> EntityFormat.duration(row.weightMicros())),
                new ColumnSpec<>(
                    "Earliest finish", row -> EntityFormat.duration(row.earliestFinishMicros())),
                new ColumnSpec<>(
                    "Execution match",
                    row ->
                        row.actionId().isPresent()
                            ? "Executed action " + row.actionId().getAsLong()
                            : "Not executed or not matched")),
            fetcher,
            CriticalPathRowSource.DEFAULT_PAGE_SIZE,
            CriticalPathRowSource.DEFAULT_CACHE_PAGES);
    model.addTableModelListener(event -> showSelectedDependencyStep());
    dependencyModel = model;
    dependencyTable.setModel(model);
    observedFallback = null;
    pathTabs.setEnabledAt(TAB_OBSERVED_FALLBACK, false);
  }

  void installBazelSourceForTest(BazelPathRowSource source) {
    ExecutorService fetcher = worker;
    if (fetcher == null) {
      worker = singleThreadExecutor();
      fetcher = worker;
    }
    PagedTableModel<CriticalPaths.BazelComponent> model =
        new PagedTableModel<>(
            source,
            bazelColumns(),
            fetcher,
            CriticalPathRowSource.DEFAULT_PAGE_SIZE,
            CriticalPathRowSource.DEFAULT_CACHE_PAGES);
    model.addTableModelListener(event -> showSelectedBazelComponent());
    bazelModel = model;
    bazelTable.setModel(model);
    bazelDetailsLoaded = true;
    bazelOpenFailure = null;
    bazelDisplayFailure = null;
    configureBazelColumns();
    updateStatus();
  }

  PagedTableModel<CriticalPaths.BazelComponent> bazelModelForTest() {
    return bazelModel;
  }

  void installGraphSourceForTest(GraphQueries.GraphSource source) {
    graphMetadataLoaded = true;
    actionGraphSource = source;
    graphOpenFailure = null;
  }

  private static void collectText(Component component, StringBuilder into) {
    if (component instanceof JLabel label) {
      into.append(label.getText()).append('\n');
    } else if (component instanceof JTextComponent text) {
      into.append(text.getText()).append('\n');
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectText(child, into);
      }
    }
  }

  private static final class BazelPathTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private List<CriticalPaths.BazelComponent> components = List.of();

    void setComponents(List<CriticalPaths.BazelComponent> next) {
      components = List.copyOf(next);
      fireTableDataChanged();
    }

    CriticalPaths.BazelComponent componentAt(int row) {
      return components.get(row);
    }

    @Override
    public int getRowCount() {
      return components.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case 0 -> "Step";
        case 1 -> "Bazel description";
        case 2 -> "Duration";
        default -> throw new IndexOutOfBoundsException(column);
      };
    }

    @Override
    public Object getValueAt(int row, int column) {
      CriticalPaths.BazelComponent component = components.get(row);
      return switch (column) {
        case 0 -> (long) component.ordinal() + 1;
        case 1 -> component.description();
        case 2 -> EntityFormat.duration(component.durationMicros());
        default -> throw new IndexOutOfBoundsException(column);
      };
    }
  }
}

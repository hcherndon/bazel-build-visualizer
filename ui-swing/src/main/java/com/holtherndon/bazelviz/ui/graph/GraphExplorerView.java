package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphExtract.Mode;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Graph card: the dependency graph, drawn.
 *
 * <h2>The canvas half of the old Graph card</h2>
 *
 * <p>Until the Graph/Tree split this machinery lived behind a "Canvas" sub-tab inside what is now
 * the Tree card. It is its own card because the two views answer different questions at different
 * sizes — the trees work on any graph, the canvas shows shape — and because a card behind a sub-tab
 * is a card nothing can navigate to. {@code OPEN_IN_GRAPH} lands here now.
 *
 * <p>The controls, the drawing, the limits and the weight selector are all {@link
 * GraphCanvasPanel}'s; this class owns what a card owns — the session, the worker that reads it,
 * and the graph-source selector that names which graph is on screen. The Tree card keeps its own
 * selector: two cards, two statements about what is being read, each visible where it applies.
 */
public final class GraphExplorerView extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(GraphExplorerView.class);

  /**
   * The most matches the Find field's dropdown lists.
   *
   * <p>The dropdown is for choosing between near-misses, not for browsing; more matches than this
   * means the pattern is not yet a choice, and the dropdown's last row says so instead of listing
   * on.
   */
  static final int FIND_LIMIT = 12;

  /**
   * The most nodes the Browse panel lists at once.
   *
   * <p>A build has tens of thousands of actions and a tree of all of them answers nothing; the
   * panel lists this many, states that it stopped, and the filter narrows. The limit bounds a
   * per-keystroke query, so it is also what keeps browsing responsive.
   */
  static final int BROWSE_LIMIT = 500;

  private final JComboBox<GraphQueries.GraphSource> sourceChoice = new JComboBox<>();
  private final JTextArea sourceDetail = WrappingLabel.create(" ");
  private final JTextArea warning = WrappingLabel.create(" ");
  private final JToggleButton sourceHelp = new JToggleButton("Source help");
  private final JTextField search = new JTextField(18);
  private final JPopupMenu findPopup = new JPopupMenu();
  private final JToggleButton browse = new JToggleButton("Browse nodes…");
  private final GraphNodeBrowser browser = new GraphNodeBrowser();
  private final JTextArea status = WrappingLabel.create(" ");
  private final JLabel empty =
      new JLabel("No dependency graph has been imported.", SwingConstants.CENTER);

  private final JPanel deck = new JPanel(new CardLayout());
  private final GraphCanvasPanel canvasPanel = new GraphCanvasPanel();
  private final Timer findTimer = new Timer(150, event -> runFindSearch());

  private ExecutorService worker;
  private GraphQueries queries;
  private GraphLayoutService layouts;
  private long generation;
  private boolean graphLoading;
  private String graphLoadFailure;
  private List<Integer> pendingCriticalPath;

  /** Guards stale find answers; read and written on the EDT only. */
  private long findGeneration;

  /** Guards stale browse answers; read and written on the EDT only. */
  private long browseGeneration;

  /** Guards navigation lookups across session and source changes. */
  private long navigationGeneration;

  /** The last find's matches, in the dropdown's order. */
  private List<GraphQueries.GraphNode> findResults = List.of();

  /** True when the last find had more matches than the dropdown lists. */
  private boolean findTruncated;

  public GraphExplorerView() {
    super(new BorderLayout());
    PlainText.disableHtml(sourceDetail);
    PlainText.disableHtml(warning);
    PlainText.disableHtml(status);
    PlainText.disableHtml(empty);
    empty.setEnabled(false);

    sourceChoice.setName("graph.source");
    sourceDetail.setName("graph.sourceExplanation");
    warning.setName("graph.sourceWarning");
    sourceHelp.setName("graph.sourceHelp");
    search.setName("graph.findNode");
    browse.setName("graph.browseNodes");
    status.setName("graph.findStatus");
    sourceChoice.setToolTipText(
        PlainText.tooltip("Choose which imported dependency graph the canvas represents."));
    search.setToolTipText(
        PlainText.tooltip(
            "Find an action or target by target label, mnemonic, rule class, or output."));
    sourceHelp.setToolTipText(
        PlainText.tooltip("Show what the selected graph contains and how trustworthy it is."));
    sourceHelp
        .getAccessibleContext()
        .setAccessibleDescription(
            "Show what the selected graph contains and how trustworthy it is.");

    warning.setFont(warning.getFont().deriveFont(Font.BOLD));
    sourceDetail.setBorder(BorderFactory.createEmptyBorder(1, 6, 2, 6));
    warning.setBorder(BorderFactory.createEmptyBorder(1, 6, 2, 6));
    status.setBorder(BorderFactory.createEmptyBorder(1, 6, 2, 6));
    sourceDetail.setVisible(false);
    warning.setVisible(false);
    status.setVisible(false);
    sourceHelp.addActionListener(
        event -> {
          sourceDetail.setVisible(sourceHelp.isSelected());
          sourceHelp.setText(sourceHelp.isSelected() ? "Hide source help" : "Source help");
          revalidate();
          repaint();
        });
    sourceChoice.setRenderer(new SourceRenderer());
    sourceChoice.addActionListener(event -> sourceChanged());

    // The dropdown must not steal focus: the user is mid-word, and a menu
    // that grabbed the keyboard would end the typing it exists to help.
    findPopup.setFocusable(false);
    findTimer.setRepeats(false);
    search
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent event) {
                findTextChanged();
              }

              @Override
              public void removeUpdate(DocumentEvent event) {
                findTextChanged();
              }

              @Override
              public void changedUpdate(DocumentEvent event) {
                findTextChanged();
              }
            });
    browse.addActionListener(event -> browserToggled());
    browser.setVisible(false);
    browser.onNodeChosen(this::browseChosen);
    browser.onFilterChanged(text -> refreshBrowser());

    JPanel top = new JPanel();
    top.setName("graph.dataHeader");
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(2, 8, 1, 8),
            BorderFactory.createTitledBorder("Graph data")));
    JPanel controls =
        row(
            labelFor("Graph source", sourceChoice, "graph.sourceLabel"),
            sourceChoice,
            sourceHelp,
            labelFor("Find node", search, "graph.findNodeLabel"),
            search,
            button("Open", "graph.openNode", this::showSearched),
            browse);
    controls.setName("graph.dataControls");
    top.add(controls);
    top.add(sourceDetail);
    top.add(warning);
    top.add(status);

    JPanel body = new JPanel(new BorderLayout());
    body.add(top, BorderLayout.NORTH);
    body.add(browser, BorderLayout.WEST);
    body.add(canvasPanel, BorderLayout.CENTER);

    deck.add(empty, "empty");
    deck.add(body, "graph");
    add(deck, BorderLayout.CENTER);
    showCard("empty");
  }

  /**
   * The graph every control on this card is talking about.
   *
   * <p>Read from the canvas panel, which is the <em>only</em> holder of the selection. A second
   * copy here once survived {@code closeSession} while the panel's did not, and the mismatch handed
   * hit-testing wrong nodes with no error. One piece of state, one owner.
   */
  private GraphKind shownGraph() {
    return canvasPanel.shownGraph();
  }

  /** Opens this session's graph, off the EDT. */
  public void openSession(SessionSource source) {
    closeSession();
    long wanted = ++generation;
    graphLoading = true;
    graphLoadFailure = null;
    empty.setText("Loading dependency graph…");
    showCard("empty");
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-graph");
              thread.setDaemon(true);
              return thread;
            });
    worker = executor;
    executor.execute(
        () -> {
          GraphQueries candidate = null;
          List<GraphQueries.GraphSource> sources;
          String[] labels;
          String[] displayLabels;
          long[] durations;
          Map<Integer, Long> actionIds;
          String[] targetLabels;
          try {
            candidate = source.openGraphQueries();
            sources = candidate.sources();
            // Fetched here, once, because the canvas must never need a name
            // or a duration during a paint (plan 17.7). A few queries for
            // the whole session, not one per frame. The display labels —
            // "Mnemonic — output basename" per action — are composed here
            // too, off the event thread, for the same reason.
            labels = candidate.labelsByNodeIndex();
            displayLabels = candidate.displayLabelsByNodeIndex();
            durations = candidate.durationsByNodeIndex(false, GraphModel.UNKNOWN_DURATION);
            actionIds = candidate.actionIdsByNodeIndex();
            targetLabels = candidate.labelsByNodeIndex(GraphKind.CONFIGURED_TARGETS);
          } catch (RuntimeException | SQLException failure) {
            closeQuietly(candidate);
            log.debug("no graph for this session", failure);
            String message =
                failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            SwingUtilities.invokeLater(
                () -> {
                  if (wanted != generation) {
                    return;
                  }
                  graphLoading = false;
                  graphLoadFailure = message;
                  pendingCriticalPath = null;
                  empty.setText("The dependency graph could not be opened: " + message);
                  showCard("empty");
                });
            return;
          }
          GraphQueries opened = candidate;
          GraphLayoutService service = new GraphLayoutService(opened);
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != generation) {
                  closeInBackground(service, opened);
                  return;
                }
                queries = opened;
                layouts = service;
                graphLoading = false;
                graphLoadFailure = null;
                canvasPanel.attach(service, labels, durations, actionIds);
                canvasPanel.attachActionDisplayLabels(displayLabels);
                canvasPanel.attachLabelGraph(targetLabels);
                installSources(sources);
                List<Integer> pending = pendingCriticalPath;
                pendingCriticalPath = null;
                if (pending != null) {
                  drawCriticalPath(pending);
                }
              });
        });
  }

  /** Lets go of the session. */
  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's graph work has stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    generation++;
    ExecutorService executor = worker;
    worker = null;
    GraphQueries open = queries;
    queries = null;
    GraphLayoutService openLayouts = layouts;
    layouts = null;
    canvasPanel.detach();
    sourceChoice.setModel(new DefaultComboBoxModel<>());
    setStatus(" ");
    findGeneration++;
    browseGeneration++;
    navigationGeneration++;
    graphLoading = false;
    graphLoadFailure = null;
    pendingCriticalPath = null;
    empty.setText("No dependency graph has been imported.");
    findResults = List.of();
    findTruncated = false;
    findPopup.setVisible(false);
    browser.clear();
    showCard("empty");
    if (executor == null && openLayouts == null && open == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-graph-close",
        () -> {
          if (executor != null) {
            executor.shutdownNow();
            try {
              executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (openLayouts != null) {
            openLayouts.close();
          }
          closeQuietly(open);
        });
  }

  void installSources(List<GraphQueries.GraphSource> sources) {
    List<GraphQueries.GraphSource> loadable =
        sources.stream().filter(source -> source.state().equals("SUCCEEDED")).toList();
    if (sources.isEmpty()) {
      empty.setText("No dependency graph has been imported.");
      showCard("empty");
      return;
    }
    sourceChoice.setModel(
        new DefaultComboBoxModel<>(sources.toArray(new GraphQueries.GraphSource[0])));
    GraphSourceSummary.preferred(loadable).ifPresent(sourceChoice::setSelectedItem);
    sourceChanged();
    showCard("graph");
  }

  private void sourceChanged() {
    GraphQueries.GraphSource source = (GraphQueries.GraphSource) sourceChoice.getSelectedItem();
    if (source == null) {
      return;
    }
    sourceDetail.setText(GraphSourceSummary.describe(source));
    sourceDetail.setToolTipText(PlainText.tooltip(sourceDetail.getText()));
    sourceChoice.setToolTipText(PlainText.tooltip(sourceDetail.getText()));
    sourceChoice.getAccessibleContext().setAccessibleDescription(sourceDetail.getText());
    String text = GraphSourceSummary.warning(source).orElse(" ");
    warning.setText(text);
    warning.setToolTipText(PlainText.tooltip(text));
    warning.setVisible(!text.isBlank());
    // The selector is a control, not a caption: picking a source switches
    // which graph the canvas draws. The panel owns the selection, resets
    // it with attach and detach, and drops the root rather than
    // reinterpreting it in the other graph's numbering.
    canvasPanel.setShownGraph(source.graphKind().orElse(shownGraph()));
    navigationGeneration++;
    setStatus(" ");
    // Find matches and the browse listing are answers in the old graph's
    // numbering; both restate themselves against the new one.
    findGeneration++;
    findResults = List.of();
    findTruncated = false;
    findPopup.setVisible(false);
    refreshBrowser();
  }

  /**
   * Moves the selector to the source behind {@code kind}, when it exists.
   *
   * @return true when that source is now selected
   */
  private boolean selectSource(GraphKind kind) {
    for (int i = 0; i < sourceChoice.getItemCount(); i++) {
      GraphQueries.GraphSource source = sourceChoice.getItemAt(i);
      if (source.state().equals("SUCCEEDED")
          && source.graphKind().filter(kind::equals).isPresent()) {
        if (sourceChoice.getSelectedIndex() != i) {
          sourceChoice.setSelectedIndex(i);
        }
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------ navigation

  private void showSearched() {
    String pattern = search.getText().trim();
    if (pattern.isEmpty() || queries == null) {
      return;
    }
    findTimer.stop();
    long wanted = ++findGeneration;
    GraphKind kind = shownGraph();
    onWorker(
        work -> {
          List<GraphQueries.GraphNode> found =
              work.search(kind, "%" + pattern + "%", FIND_LIMIT + 1);
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != findGeneration) {
                  return;
                }
                if (found.isEmpty()) {
                  setStatus("Nothing in the " + kind.displayName() + " matches " + pattern + ".");
                  return;
                }
                findTruncated = found.size() > FIND_LIMIT;
                findResults = findTruncated ? found.subList(0, FIND_LIMIT) : found;
                if (findResults.size() == 1 && !findTruncated) {
                  chooseFindResult(findResults.getFirst());
                  return;
                }
                showFindDropdown(kind, pattern);
                setStatus("Choose one of the matching nodes.");
              });
        });
  }

  /**
   * The as-you-type half of Find.
   *
   * <p>A short debounce combines a burst of keystrokes into one bounded search on the worker —
   * never on the event thread — and the answer comes back as a dropdown of up to {@link
   * #FIND_LIMIT} matches under the field. Each entry names its node exactly as the canvas will, and
   * choosing one lands on that exact node — the old behaviour of silently taking the first
   * substring match is what this replaces. A stale answer (the user kept typing) is dropped by
   * generation.
   */
  private void findTextChanged() {
    String pattern = search.getText().trim();
    findGeneration++;
    if (pattern.isEmpty() || queries == null) {
      findTimer.stop();
      findResults = List.of();
      findTruncated = false;
      findPopup.setVisible(false);
      setStatus(" ");
      return;
    }
    findTimer.restart();
  }

  private void runFindSearch() {
    String pattern = search.getText().trim();
    long wanted = findGeneration;
    if (pattern.isEmpty() || queries == null) {
      return;
    }
    GraphKind kind = shownGraph();
    onWorker(
        work -> {
          // One more than the dropdown shows, so "there are more" is a fact
          // rather than a guess.
          List<GraphQueries.GraphNode> found =
              work.search(kind, "%" + pattern + "%", FIND_LIMIT + 1);
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != findGeneration) {
                  return;
                }
                findTruncated = found.size() > FIND_LIMIT;
                findResults = findTruncated ? found.subList(0, FIND_LIMIT) : found;
                showFindDropdown(kind, pattern);
              });
        });
  }

  /** Rebuilds and, when the field is on screen, shows the dropdown. */
  private void showFindDropdown(GraphKind kind, String pattern) {
    findPopup.setVisible(false);
    findPopup.removeAll();
    if (findResults.isEmpty()) {
      setStatus("Nothing in the " + kind.displayName() + " matches " + pattern + ".");
      return;
    }
    setStatus(" ");
    for (GraphQueries.GraphNode found : findResults) {
      JMenuItem item = new JMenuItem(describeMatch(found));
      PlainText.disableHtml(item);
      item.addActionListener(event -> chooseFindResult(found));
      findPopup.add(item);
    }
    if (findTruncated) {
      JMenuItem more =
          new JMenuItem("Only the first " + FIND_LIMIT + " matches are listed. Keep typing.");
      PlainText.disableHtml(more);
      more.setEnabled(false);
      findPopup.add(more);
    }
    // A component that is not on screen cannot anchor a popup; headless
    // tests read findResultsForTesting instead.
    if (search.isShowing()) {
      findPopup.show(search, 0, search.getHeight());
    }
  }

  /** A dropdown row: the label, then what tells this node from its siblings. */
  private String describeMatch(GraphQueries.GraphNode found) {
    String distinct =
        GraphQueries.composeDisplayLabel(
            found.mnemonic().orElse(null), found.primaryOutput().orElse(null), null);
    String label = found.label().orElse(null);
    if (label == null) {
      return distinct == null ? "(name not recorded)" : distinct;
    }
    return distinct == null ? label : label + "  (" + distinct + ")";
  }

  /** Lands on the chosen match's exact node. */
  private void chooseFindResult(GraphQueries.GraphNode found) {
    findPopup.setVisible(false);
    setStatus(" ");
    canvasPanel.showNode(found.nodeIndex());
  }

  // -------------------------------------------------------------- browsing

  /** Shows or hides the browse panel; showing fetches a listing. */
  private void browserToggled() {
    browser.setVisible(browse.isSelected());
    if (browse.isSelected()) {
      refreshBrowser();
    }
    revalidate();
    repaint();
  }

  /**
   * Re-lists the browsable nodes for the shown graph and current filter.
   *
   * <p>On the worker, bounded by {@link #BROWSE_LIMIT}, and guarded by generation like the find —
   * the browser itself holds no connection and is only ever handed a finished list.
   */
  private void refreshBrowser() {
    if (!browser.isVisible() || queries == null) {
      return;
    }
    long wanted = ++browseGeneration;
    GraphKind kind = shownGraph();
    String filter = browser.filterText();
    OptionalLong total = graphTotal();
    onWorker(
        work -> {
          List<GraphQueries.GraphNode> found =
              work.search(kind, "%" + filter + "%", BROWSE_LIMIT + 1);
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != browseGeneration) {
                  return;
                }
                boolean truncated = found.size() > BROWSE_LIMIT;
                browser.show(
                    truncated ? found.subList(0, BROWSE_LIMIT) : found,
                    GraphLayoutService.nounFor(kind),
                    BROWSE_LIMIT,
                    truncated,
                    total);
              });
        });
  }

  /** The shown graph's node count, when the selected source states one. */
  private OptionalLong graphTotal() {
    GraphQueries.GraphSource source = (GraphQueries.GraphSource) sourceChoice.getSelectedItem();
    return source == null ? OptionalLong.empty() : source.declaredActions();
  }

  /** A browsed entry lands exactly like a found one. */
  private void browseChosen(int nodeIndex) {
    setStatus(" ");
    canvasPanel.showNode(nodeIndex);
  }

  /**
   * Draws the neighbourhood of the graph node for an executed action.
   *
   * <p>Where {@code OPEN_IN_GRAPH} lands. An action the graph does not declare — {@code
   * stable-status.txt}, which aquery never mentions (Q7) — says so rather than showing an empty
   * canvas that reads as "this depends on nothing".
   */
  public void showAction(long actionId) {
    // An executed action lives in the action graph, whichever source was
    // on screen; the selector follows so the label above the drawing
    // keeps naming the graph that is actually shown.
    if (!selectSource(GraphKind.DECLARED_ACTIONS)) {
      setStatus("This session has no declared-action dependency graph.");
      return;
    }
    long wanted = ++navigationGeneration;
    GraphQueries expected = queries;
    if (expected == null) {
      return;
    }
    onWorker(
        work -> {
          OptionalLong nodeIndex = work.nodeForAction(actionId);
          SwingUtilities.invokeLater(
              () -> {
                if (wanted != navigationGeneration
                    || expected != queries
                    || shownGraph() != GraphKind.DECLARED_ACTIONS) {
                  return;
                }
                if (nodeIndex.isEmpty()) {
                  setStatus(
                      "This action is not in the dependency graph."
                          + " Some actions run without being declared by analysis.");
                  return;
                }
                setStatus(" ");
                canvasPanel.showNode(Math.toIntExact(nodeIndex.getAsLong()));
              });
        });
  }

  /**
   * Draws the neighbourhood of the graph node for a target label, exactly.
   *
   * <p>Where {@code OPEN_IN_GRAPH} lands when the entity is a target rather than an action. The
   * label graph is tried first — a target label <em>is</em> a node there — and the action graph
   * second, where the node is the lowest-numbered action the label owns.
   *
   * <p>Exact, unlike the Find field above the canvas: a substring jump to {@code //app:server} can
   * land on {@code //app:server_lib}, and a drawing of the wrong node's neighbourhood looks exactly
   * like a right one. A label neither graph carries says so rather than leaving the canvas on
   * whatever it was showing.
   */
  public void showLabel(String label) {
    Objects.requireNonNull(label, "label");
    List<GraphKind> candidates = labelLookupOrder();
    if (candidates.isEmpty()) {
      setStatus("No dependency graph is loaded, so " + label + " cannot be drawn here.");
      return;
    }
    long wanted = ++navigationGeneration;
    GraphQueries expected = queries;
    if (expected == null) {
      return;
    }
    onWorker(
        work -> {
          for (GraphKind kind : candidates) {
            OptionalInt index = work.nodeForLabel(kind, label);
            if (index.isEmpty()) {
              continue;
            }
            int nodeIndex = index.getAsInt();
            SwingUtilities.invokeLater(
                () -> {
                  if (wanted != navigationGeneration || expected != queries) {
                    return;
                  }
                  // Selecting the source first is what makes the node index
                  // mean what it meant when it was looked up; a session that
                  // closed underneath the lookup says so instead.
                  if (!selectSource(kind)) {
                    setStatus(
                        "The "
                            + kind.displayName()
                            + " is no longer loaded, so "
                            + label
                            + " cannot be drawn.");
                    return;
                  }
                  setStatus(" ");
                  canvasPanel.showNode(nodeIndex);
                });
            return;
          }
          SwingUtilities.invokeLater(
              () -> {
                if (wanted == navigationGeneration && expected == queries) {
                  setStatus("No node in this session's graphs is named " + label + ".");
                }
              });
        });
  }

  /**
   * The graphs the selector could switch to, label graph first.
   *
   * <p>A target label is a node in its own right in the configured-target graph, and only the owner
   * of some actions in the action graph, so the first is the better answer to "show me this target"
   * when the session has one.
   */
  private List<GraphKind> labelLookupOrder() {
    List<GraphKind> kinds = new ArrayList<>();
    for (GraphKind kind : List.of(GraphKind.CONFIGURED_TARGETS, GraphKind.DECLARED_ACTIONS)) {
      for (int i = 0; i < sourceChoice.getItemCount(); i++) {
        if (sourceChoice.getItemAt(i).graphKind().filter(kind::equals).isPresent()) {
          kinds.add(kind);
          break;
        }
      }
    }
    return kinds;
  }

  /**
   * Draws a chain of graph nodes on the canvas.
   *
   * <p>The nodes come from the metric collection's derived critical path, which computed them with
   * whichever duration source covered this session — so this draws the chain the findings describe
   * rather than recomputing one from a different weighting and drawing something else.
   */
  public void showCriticalPath(List<Integer> nodes) {
    if (nodes.isEmpty()) {
      setStatus(
          "There is no derived dependency chain to draw:"
              + " this session has no imported action graph.");
      return;
    }
    // CriticalPath.Result exposes an immutable primitive-backed view. Keep
    // that bounded-memory view until GraphExtract has checked the drawing
    // limits on its worker; copying here could box millions of node ids on
    // the Swing event thread merely to discover that the path is too large.
    List<Integer> requested = nodes;
    if (graphLoading) {
      pendingCriticalPath = requested;
      empty.setText("Loading the dependency graph for the requested critical path…");
      showCard("empty");
      return;
    }
    if (queries == null || layouts == null) {
      String reason =
          graphLoadFailure == null
              ? "No dependency graph is open."
              : "The dependency graph could not be opened: " + graphLoadFailure;
      pendingCriticalPath = null;
      empty.setText(reason);
      setStatus(reason);
      showCard("empty");
      return;
    }
    drawCriticalPath(requested);
  }

  private void drawCriticalPath(List<Integer> nodes) {
    // The chain's node indices are action-graph indices.
    if (!selectSource(GraphKind.DECLARED_ACTIONS)) {
      String reason =
          "The successful declared action graph is not available, so the"
              + " dependency critical path cannot be drawn.";
      setStatus(reason);
      if (sourceChoice.getItemCount() == 0) {
        empty.setText(reason);
        showCard("empty");
      }
      return;
    }
    if (!canvasPanel.showPath(nodes, Mode.CRITICAL_PATH)) {
      setStatus(canvasPanel.descriptionText());
      return;
    }
    setStatus(
        "Visualizer-computed dependency critical path selected: "
            + nodes.size()
            + " actions. This is what the dependencies imply, not what Bazel scheduled.");
  }

  /** Called with the executed action behind a node picked on the canvas. */
  public void onActionSelected(LongConsumer listener) {
    canvasPanel.onActionSelected(listener);
  }

  /** Adds shared label actions to a drawn node's context menu. */
  public void installEntityActions(EntityActions actions) {
    canvasPanel.installEntityActions(actions);
  }

  /** The canvas panel, for tests. */
  GraphCanvasPanel canvasPanel() {
    return canvasPanel;
  }

  // ---------------------------------------------------------------- plumbing

  private void setStatus(String text) {
    status.setText(text == null || text.isBlank() ? " " : text);
    status.setToolTipText(PlainText.tooltip(status.getText()));
    status.setVisible(!status.getText().isBlank());
  }

  private interface GraphWork {
    void run(GraphQueries queries) throws Exception;
  }

  private void onWorker(GraphWork work) {
    ExecutorService executor = worker;
    GraphQueries open = queries;
    if (executor == null || open == null) {
      return;
    }
    executor.execute(
        () -> {
          try {
            work.run(open);
          } catch (Exception failure) {
            log.debug("graph query failed", failure);
          }
        });
  }

  private void showCard(String name) {
    ((CardLayout) deck.getLayout()).show(deck, name);
  }

  private static void closeQuietly(GraphQueries open) {
    if (open == null) {
      return;
    }
    try {
      open.close();
    } catch (SQLException ignored) {
      // closing a read connection that is already gone is not news
    }
  }

  private static CompletionStage<Void> closeInBackground(
      GraphLayoutService openLayouts, GraphQueries open) {
    if (openLayouts == null && open == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-graph-close",
        () -> {
          if (openLayouts != null) {
            openLayouts.close();
          }
          closeQuietly(open);
        });
  }

  private static JPanel row(Component... components) {
    JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEADING, 6, 2));
    for (Component component : components) {
      if (component instanceof JLabel label) {
        PlainText.disableHtml(label);
      }
      panel.add(component);
    }
    return panel;
  }

  private static JButton button(String text, String name, Runnable action) {
    JButton button = new JButton(text);
    button.setName(name);
    button.addActionListener(event -> action.run());
    return button;
  }

  private static JLabel labelFor(String text, JComponent target, String name) {
    JLabel label = new JLabel(text + ":");
    label.setLabelFor(target);
    label.setName(name);
    PlainText.disableHtml(label);
    return label;
  }

  /** Renders a source with its trust state, never as a bare enum name. */
  private static final class SourceRenderer extends DefaultListCellRenderer {

    private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected, boolean focused) {
      String text =
          value instanceof GraphQueries.GraphSource source
              ? GraphSourceSummary.label(source)
              : String.valueOf(value);
      Component rendered = super.getListCellRendererComponent(list, text, index, selected, focused);
      if (rendered instanceof JComponent component) {
        PlainText.disableHtml(component);
      }
      return rendered;
    }
  }

  /** Types a pattern and presses Open, for tests. */
  void searchForTesting(String pattern) {
    search.setText(pattern);
    showSearched();
  }

  /** Types into the Find field exactly as a user would, for tests. */
  void typeFindForTesting(String pattern) {
    search.setText(pattern);
  }

  /** The dropdown's current matches, for tests; headless popups cannot show. */
  List<GraphQueries.GraphNode> findResultsForTesting() {
    return List.copyOf(findResults);
  }

  /** True when the last find listed only the first {@link #FIND_LIMIT}. */
  boolean findTruncatedForTesting() {
    return findTruncated;
  }

  /** Chooses the {@code index}th dropdown match, for tests. */
  void chooseFindResultForTesting(int index) {
    chooseFindResult(findResults.get(index));
  }

  /** The browse panel, for tests. */
  GraphNodeBrowser browserForTesting() {
    return browser;
  }

  /** Opens the browse panel exactly as the toggle would, for tests. */
  void openBrowserForTesting() {
    browse.setSelected(true);
    browserToggled();
  }

  /** The status sentence beside the search, for tests. */
  String statusForTesting() {
    return status.getText();
  }

  JTextArea detailLabel() {
    return sourceDetail;
  }

  JComboBox<GraphQueries.GraphSource> sourceSelector() {
    return sourceChoice;
  }
}

package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Graph card: the dependency graph, drawn.
 *
 * <h2>The canvas half of the old Graph card</h2>
 *
 * <p>Until the Graph/Tree split this machinery lived behind a "Canvas" sub-tab
 * inside what is now the Tree card. It is its own card because the two views
 * answer different questions at different sizes — the trees work on any graph,
 * the canvas shows shape — and because a card behind a sub-tab is a card
 * nothing can navigate to. {@code OPEN_IN_GRAPH} lands here now.
 *
 * <p>The controls, the drawing, the limits and the weight selector are all
 * {@link GraphCanvasPanel}'s; this class owns what a card owns — the session,
 * the worker that reads it, and the graph-source selector that names which
 * graph is on screen. The Tree card keeps its own selector: two cards, two
 * statements about what is being read, each visible where it applies.
 */
public final class GraphExplorerView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(GraphExplorerView.class);

    /**
     * The most matches the Find field's dropdown lists.
     *
     * <p>The dropdown is for choosing between near-misses, not for browsing;
     * more matches than this means the pattern is not yet a choice, and the
     * dropdown's last row says so instead of listing on.
     */
    static final int FIND_LIMIT = 12;

    /**
     * The most nodes the Browse panel lists at once.
     *
     * <p>A build has tens of thousands of actions and a tree of all of them
     * answers nothing; the panel lists this many, states that it stopped, and
     * the filter narrows. The limit bounds a per-keystroke query, so it is
     * also what keeps browsing responsive.
     */
    static final int BROWSE_LIMIT = 500;

    private final JComboBox<GraphQueries.GraphSource> sourceChoice = new JComboBox<>();
    private final JLabel sourceDetail = new JLabel(" ");
    private final JLabel warning = new JLabel(" ");
    private final javax.swing.JTextField search = new javax.swing.JTextField(24);
    private final javax.swing.JPopupMenu findPopup = new javax.swing.JPopupMenu();
    private final javax.swing.JToggleButton browse = new javax.swing.JToggleButton("Browse…");
    private final GraphNodeBrowser browser = new GraphNodeBrowser();
    private final JLabel status = new JLabel(" ");
    private final JLabel empty =
            new JLabel("No dependency graph has been imported.", SwingConstants.CENTER);

    private final JPanel deck = new JPanel(new java.awt.CardLayout());
    private final GraphCanvasPanel canvasPanel = new GraphCanvasPanel();

    private ExecutorService worker;
    private GraphQueries queries;
    private GraphLayoutService layouts;
    private long generation;

    /** Guards stale find answers; read and written on the EDT only. */
    private long findGeneration;

    /** Guards stale browse answers; read and written on the EDT only. */
    private long browseGeneration;

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

        warning.setFont(warning.getFont().deriveFont(Font.BOLD));
        sourceChoice.setRenderer(new SourceRenderer());
        sourceChoice.addActionListener(event -> sourceChanged());

        // The dropdown must not steal focus: the user is mid-word, and a menu
        // that grabbed the keyboard would end the typing it exists to help.
        findPopup.setFocusable(false);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                findTextChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                findTextChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                findTextChanged();
            }
        });
        browse.addActionListener(event -> browserToggled());
        browser.setVisible(false);
        browser.onNodeChosen(this::browseChosen);
        browser.onFilterChanged(text -> refreshBrowser());

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(row(new JLabel("Graph:"), sourceChoice));
        top.add(sourceDetail);
        top.add(warning);
        top.add(row(new JLabel("Find:"), search,
                button("Show", this::showSearched), browse, status));

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
     * <p>Read from the canvas panel, which is the <em>only</em> holder of the
     * selection. A second copy here once survived {@code closeSession} while
     * the panel's did not, and the mismatch handed hit-testing wrong nodes
     * with no error. One piece of state, one owner.
     */
    private GraphKind shownGraph() {
        return canvasPanel.shownGraph();
    }

    /** Opens this session's graph, off the EDT. */
    public void openSession(SessionSource source) {
        closeSession();
        long wanted = ++generation;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-graph");
            thread.setDaemon(true);
            return thread;
        });
        worker = executor;
        executor.execute(() -> {
            GraphQueries opened;
            List<GraphQueries.GraphSource> sources;
            String[] labels;
            String[] displayLabels;
            long[] durations;
            java.util.Map<Integer, Long> actionIds;
            String[] targetLabels;
            try {
                opened = source.openGraphQueries();
                sources = opened.sources();
                // Fetched here, once, because the canvas must never need a name
                // or a duration during a paint (plan 17.7). A few queries for
                // the whole session, not one per frame. The display labels —
                // "Mnemonic — output basename" per action — are composed here
                // too, off the event thread, for the same reason.
                labels = opened.labelsByNodeIndex();
                displayLabels = opened.displayLabelsByNodeIndex();
                durations = opened.durationsByNodeIndex(false, GraphModel.UNKNOWN_DURATION);
                actionIds = opened.actionIdsByNodeIndex();
                targetLabels = opened.labelsByNodeIndex(GraphKind.CONFIGURED_TARGETS);
            } catch (RuntimeException | java.sql.SQLException failure) {
                log.debug("no graph for this session", failure);
                return;
            }
            GraphLayoutService service = new GraphLayoutService(opened);
            SwingUtilities.invokeLater(() -> {
                if (wanted != generation) {
                    service.close();
                    closeQuietly(opened);
                    return;
                }
                queries = opened;
                layouts = service;
                canvasPanel.attach(service, labels, durations, actionIds);
                canvasPanel.attachActionDisplayLabels(displayLabels);
                canvasPanel.attachLabelGraph(targetLabels);
                installSources(sources);
            });
        });
    }

    /** Lets go of the session. */
    public void closeSession() {
        generation++;
        ExecutorService executor = worker;
        worker = null;
        GraphQueries open = queries;
        queries = null;
        GraphLayoutService openLayouts = layouts;
        layouts = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        canvasPanel.detach();
        if (openLayouts != null) {
            openLayouts.close();
        }
        closeQuietly(open);
        sourceChoice.setModel(new DefaultComboBoxModel<>());
        status.setText(" ");
        findGeneration++;
        browseGeneration++;
        findResults = List.of();
        findTruncated = false;
        findPopup.setVisible(false);
        browser.clear();
        showCard("empty");
    }

    void installSources(List<GraphQueries.GraphSource> sources) {
        List<GraphQueries.GraphSource> loadable = sources.stream()
                .filter(source -> source.state().equals("SUCCEEDED"))
                .toList();
        if (sources.isEmpty()) {
            showCard("empty");
            return;
        }
        sourceChoice.setModel(new DefaultComboBoxModel<>(
                sources.toArray(new GraphQueries.GraphSource[0])));
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
        String text = GraphSourceSummary.warning(source).orElse(" ");
        warning.setText(text);
        warning.setToolTipText(PlainText.tooltip(text));
        // The selector is a control, not a caption: picking a source switches
        // which graph the canvas draws. The panel owns the selection, resets
        // it with attach and detach, and drops the root rather than
        // reinterpreting it in the other graph's numbering.
        canvasPanel.setShownGraph(source.graphKind().orElse(shownGraph()));
        status.setText(" ");
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
            if (source.graphKind().filter(kind::equals).isPresent()) {
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
        GraphKind kind = shownGraph();
        onWorker(work -> {
            List<GraphQueries.GraphNode> found = work.search(kind, "%" + pattern + "%", 1);
            SwingUtilities.invokeLater(() -> {
                if (found.isEmpty()) {
                    setStatus("Nothing in the "
                            + kind.displayName() + " matches " + pattern + ".");
                    return;
                }
                setStatus(" ");
                canvasPanel.showNode(found.getFirst().nodeIndex());
            });
        });
    }

    /**
     * The as-you-type half of Find.
     *
     * <p>Every keystroke schedules a bounded search on the worker — never on
     * the event thread — and the answer comes back as a dropdown of up to
     * {@link #FIND_LIMIT} matches under the field. Each entry names its node
     * exactly as the canvas will, and choosing one lands on that exact node —
     * the old behaviour of silently taking the first substring match is what
     * this replaces. A stale answer (the user kept typing) is dropped by
     * generation.
     */
    private void findTextChanged() {
        String pattern = search.getText().trim();
        long wanted = ++findGeneration;
        if (pattern.isEmpty() || queries == null) {
            findResults = List.of();
            findTruncated = false;
            findPopup.setVisible(false);
            setStatus(" ");
            return;
        }
        GraphKind kind = shownGraph();
        onWorker(work -> {
            // One more than the dropdown shows, so "there are more" is a fact
            // rather than a guess.
            List<GraphQueries.GraphNode> found =
                    work.search(kind, "%" + pattern + "%", FIND_LIMIT + 1);
            SwingUtilities.invokeLater(() -> {
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
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(describeMatch(found));
            item.addActionListener(event -> chooseFindResult(found));
            findPopup.add(item);
        }
        if (findTruncated) {
            javax.swing.JMenuItem more = new javax.swing.JMenuItem(
                    "Only the first " + FIND_LIMIT + " matches are listed. Keep typing.");
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
        String distinct = GraphQueries.composeDisplayLabel(
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
     * <p>On the worker, bounded by {@link #BROWSE_LIMIT}, and guarded by
     * generation like the find — the browser itself holds no connection and
     * is only ever handed a finished list.
     */
    private void refreshBrowser() {
        if (!browser.isVisible() || queries == null) {
            return;
        }
        long wanted = ++browseGeneration;
        GraphKind kind = shownGraph();
        String filter = browser.filterText();
        java.util.OptionalLong total = graphTotal();
        onWorker(work -> {
            List<GraphQueries.GraphNode> found =
                    work.search(kind, "%" + filter + "%", BROWSE_LIMIT + 1);
            SwingUtilities.invokeLater(() -> {
                if (wanted != browseGeneration) {
                    return;
                }
                boolean truncated = found.size() > BROWSE_LIMIT;
                browser.show(
                        truncated ? found.subList(0, BROWSE_LIMIT) : found,
                        GraphLayoutService.nounFor(kind), BROWSE_LIMIT, truncated, total);
            });
        });
    }

    /** The shown graph's node count, when the selected source states one. */
    private java.util.OptionalLong graphTotal() {
        GraphQueries.GraphSource source =
                (GraphQueries.GraphSource) sourceChoice.getSelectedItem();
        return source == null
                ? java.util.OptionalLong.empty() : source.declaredActions();
    }

    /** A browsed entry lands exactly like a found one. */
    private void browseChosen(int nodeIndex) {
        setStatus(" ");
        canvasPanel.showNode(nodeIndex);
    }

    /**
     * Draws the neighbourhood of the graph node for an executed action.
     *
     * <p>Where {@code OPEN_IN_GRAPH} lands. An action the graph does not
     * declare — {@code stable-status.txt}, which aquery never mentions (Q7) —
     * says so rather than showing an empty canvas that reads as "this depends
     * on nothing".
     */
    public void showAction(long actionId) {
        // An executed action lives in the action graph, whichever source was
        // on screen; the selector follows so the label above the drawing
        // keeps naming the graph that is actually shown.
        selectSource(GraphKind.DECLARED_ACTIONS);
        onWorker(work -> {
            java.util.OptionalLong nodeIndex = work.nodeForAction(actionId);
            SwingUtilities.invokeLater(() -> {
                if (nodeIndex.isEmpty()) {
                    setStatus("This action is not in the dependency graph."
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
     * <p>Where {@code OPEN_IN_GRAPH} lands when the entity is a target rather
     * than an action. The label graph is tried first — a target label
     * <em>is</em> a node there — and the action graph second, where the node
     * is the lowest-numbered action the label owns.
     *
     * <p>Exact, unlike the Find field above the canvas: a substring jump to
     * {@code //app:server} can land on {@code //app:server_lib}, and a drawing
     * of the wrong node's neighbourhood looks exactly like a right one. A
     * label neither graph carries says so rather than leaving the canvas on
     * whatever it was showing.
     */
    public void showLabel(String label) {
        java.util.Objects.requireNonNull(label, "label");
        List<GraphKind> candidates = labelLookupOrder();
        if (candidates.isEmpty()) {
            setStatus("No dependency graph is loaded, so " + label
                    + " cannot be drawn here.");
            return;
        }
        onWorker(work -> {
            for (GraphKind kind : candidates) {
                java.util.OptionalInt index = work.nodeForLabel(kind, label);
                if (index.isEmpty()) {
                    continue;
                }
                int nodeIndex = index.getAsInt();
                SwingUtilities.invokeLater(() -> {
                    // Selecting the source first is what makes the node index
                    // mean what it meant when it was looked up; a session that
                    // closed underneath the lookup says so instead.
                    if (!selectSource(kind)) {
                        setStatus("The " + kind.displayName()
                                + " is no longer loaded, so " + label
                                + " cannot be drawn.");
                        return;
                    }
                    setStatus(" ");
                    canvasPanel.showNode(nodeIndex);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> setStatus(
                    "No node in this session's graphs is named " + label + "."));
        });
    }

    /**
     * The graphs the selector could switch to, label graph first.
     *
     * <p>A target label is a node in its own right in the configured-target
     * graph, and only the owner of some actions in the action graph, so the
     * first is the better answer to "show me this target" when the session
     * has one.
     */
    private List<GraphKind> labelLookupOrder() {
        List<GraphKind> kinds = new java.util.ArrayList<>();
        for (GraphKind kind
                : List.of(GraphKind.CONFIGURED_TARGETS, GraphKind.DECLARED_ACTIONS)) {
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
     * <p>The nodes come from the metric collection's derived critical path,
     * which computed them with whichever duration source covered this session
     * — so this draws the chain the findings describe rather than recomputing
     * one from a different weighting and drawing something else.
     */
    public void showCriticalPath(List<Integer> nodes) {
        if (nodes.isEmpty()) {
            setStatus("There is no derived dependency chain to draw:"
                    + " this session has no imported action graph.");
            return;
        }
        // The chain's node indices are action-graph indices.
        selectSource(GraphKind.DECLARED_ACTIONS);
        canvasPanel.showPath(
                nodes, com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CRITICAL_PATH);
        setStatus("Visualizer-computed dependency critical path: " + nodes.size()
                + " actions. This is what the dependencies imply, not what Bazel scheduled.");
    }

    /** Called with the executed action behind a node picked on the canvas. */
    public void onActionSelected(java.util.function.LongConsumer listener) {
        canvasPanel.onActionSelected(listener);
    }

    /** The canvas panel, for tests. */
    GraphCanvasPanel canvasPanel() {
        return canvasPanel;
    }

    // ---------------------------------------------------------------- plumbing

    private void setStatus(String text) {
        status.setText(text == null || text.isBlank() ? " " : text);
        status.setToolTipText(PlainText.tooltip(status.getText()));
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
        executor.execute(() -> {
            try {
                work.run(open);
            } catch (Exception failure) {
                log.debug("graph query failed", failure);
            }
        });
    }

    private void showCard(String name) {
        ((java.awt.CardLayout) deck.getLayout()).show(deck, name);
    }

    private static void closeQuietly(GraphQueries open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (java.sql.SQLException ignored) {
            // closing a read connection that is already gone is not news
        }
    }

    private static JPanel row(java.awt.Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (java.awt.Component component : components) {
            if (component instanceof JLabel label) {
                PlainText.disableHtml(label);
            }
            panel.add(component);
        }
        return panel;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    /** Renders a source with its trust state, never as a bare enum name. */
    private static final class SourceRenderer extends javax.swing.DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            String text = value instanceof GraphQueries.GraphSource source
                    ? GraphSourceSummary.label(source) : String.valueOf(value);
            java.awt.Component rendered =
                    super.getListCellRendererComponent(list, text, index, selected, focused);
            if (rendered instanceof javax.swing.JComponent component) {
                PlainText.disableHtml(component);
            }
            return rendered;
        }
    }

    /** Types a pattern and presses Show, for tests. */
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

    JLabel detailLabel() {
        return sourceDetail;
    }

    JComboBox<GraphQueries.GraphSource> sourceSelector() {
        return sourceChoice;
    }
}

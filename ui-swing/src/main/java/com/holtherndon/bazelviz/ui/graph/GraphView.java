package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.ShortestPath;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Graph card: what an action depends on, what depends on it, and whether
 * two actions are connected.
 *
 * <h2>Trees, not a picture</h2>
 *
 * <p>Phase 5's UI deliverable is the data — dependency and reverse-dependency
 * trees, a selected-action neighbourhood, path-between-nodes, and a
 * graph-source selector. The rendered canvas with layouts and semantic zoom is
 * Phase 7, and will read the same indexes this uses.
 *
 * <h2>Expanded on demand, never in full</h2>
 *
 * <p>Each tree node fetches its children when it is opened. Plan 13.3 forbids
 * computing a transitive closure, and a tree that expanded itself would compute
 * one for any action near the root of a real build.
 *
 * <h2>The source selector is not a convenience</h2>
 *
 * <p>It names which graph is on screen and whether that graph was confirmed to
 * be this build's. A dependency tree from a query that analysed a different
 * configuration looks exactly like a correct one, so the label above it is the
 * only thing standing between the user and a confident wrong answer.
 */
public final class GraphView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(GraphView.class);

    /** Children listed under one tree node before the rest are summarised. */
    static final int CHILD_LIMIT = 200;

    /** Nodes a path search may visit before it gives up. */
    static final long PATH_BUDGET = 200_000;

    private final JComboBox<GraphQueries.GraphSource> sourceChoice = new JComboBox<>();
    private final JLabel sourceDetail = new JLabel(" ");
    private final JLabel warning = new JLabel(" ");
    private final JTextField search = new JTextField(24);
    private final JTree dependencies = new JTree(new DefaultMutableTreeNode("Dependencies"));
    private final JTree dependents = new JTree(new DefaultMutableTreeNode("Reverse dependencies"));
    private final JTextField pathFrom = new JTextField(18);
    private final JTextField pathTo = new JTextField(18);
    private final JLabel pathResult = new JLabel(" ");
    private final JLabel empty =
            new JLabel("No dependency graph has been imported.", SwingConstants.CENTER);

    private final JPanel deck = new JPanel(new java.awt.CardLayout());
    private final GraphCanvasPanel canvasPanel = new GraphCanvasPanel();
    private final javax.swing.JTabbedPane views = new javax.swing.JTabbedPane();

    private final LazyExpander dependenciesExpander = new LazyExpander(true);
    private final LazyExpander dependentsExpander = new LazyExpander(false);

    private ExecutorService worker;
    private GraphQueries queries;
    private GraphLayoutService layouts;
    private long generation;

    /**
     * The graph every control on this card is talking about.
     *
     * <p>Follows the source selector. The selector used to be a caption — it
     * named the graph and changed nothing — and the trees and canvas silently
     * traversed the action graph whatever it said, which is precisely the
     * conflation rule 13 exists to prevent.
     */
    private GraphKind shownGraph = GraphKind.DECLARED_ACTIONS;

    public GraphView() {
        super(new BorderLayout());
        PlainText.install(dependencies);
        PlainText.install(dependents);
        PlainText.disableHtml(sourceDetail);
        PlainText.disableHtml(warning);
        PlainText.disableHtml(pathResult);
        PlainText.disableHtml(empty);
        empty.setEnabled(false);

        warning.setFont(warning.getFont().deriveFont(Font.BOLD));
        sourceChoice.setRenderer(new SourceRenderer());
        sourceChoice.addActionListener(event -> sourceChanged());

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(row(new JLabel("Graph:"), sourceChoice));
        top.add(sourceDetail);
        top.add(warning);
        top.add(row(new JLabel("Find:"), search, button("Show", this::showSearched)));

        JScrollPane forward = new JScrollPane(dependencies);
        forward.setBorder(BorderFactory.createTitledBorder("Depends on"));
        JScrollPane backward = new JScrollPane(dependents);
        backward.setBorder(BorderFactory.createTitledBorder("Depended on by"));
        JSplitPane trees = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, forward, backward);
        trees.setResizeWeight(0.5);
        trees.setMinimumSize(new Dimension(400, 200));

        JPanel path = new JPanel();
        path.setLayout(new javax.swing.BoxLayout(path, javax.swing.BoxLayout.Y_AXIS));
        path.setBorder(BorderFactory.createTitledBorder("Path between two actions"));
        path.add(row(new JLabel("From:"), pathFrom, new JLabel("To:"), pathTo,
                button("Find path", this::findPath)));
        path.add(pathResult);

        JPanel lists = new JPanel(new BorderLayout());
        lists.add(trees, BorderLayout.CENTER);
        lists.add(path, BorderLayout.SOUTH);

        // Two ways of reading the same graph. The trees answer "what exactly
        // does this depend on" one level at a time; the canvas answers "what
        // shape is this" all at once. Neither replaces the other, and the
        // trees came first because they work at any size.
        views.addTab("Trees", lists);
        views.addTab("Canvas", canvasPanel);

        JPanel body = new JPanel(new BorderLayout());
        body.add(top, BorderLayout.NORTH);
        body.add(views, BorderLayout.CENTER);

        deck.add(empty, "empty");
        deck.add(body, "graph");
        add(deck, BorderLayout.CENTER);
        showCard("empty");

        dependencies.addTreeWillExpandListener(dependenciesExpander);
        dependents.addTreeWillExpandListener(dependentsExpander);
        // Picking a node on the canvas moves the trees to it. Two halves of one
        // view showing two different actions reads as a bug in the data.
        canvasPanel.onNodeSelected(this::rootTreesAt);
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
            long[] durations;
            java.util.Map<Integer, Long> actionIds;
            String[] targetLabels;
            try {
                opened = source.openGraphQueries();
                sources = opened.sources();
                // Fetched here, once, because the canvas must never need a name
                // or a duration during a paint (plan 17.7). A few queries for
                // the whole session, not one per frame.
                labels = opened.labelsByNodeIndex();
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
        clearTrees();
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
        // which graph the search, the trees and the canvas traverse. Node
        // indexes do not translate between graphs, so the rooted views clear
        // and ask for a new seed rather than reinterpreting the old one.
        GraphKind chosen = source.graphKind().orElse(shownGraph);
        if (chosen != shownGraph) {
            shownGraph = chosen;
            clearTrees();
            pathResult.setText(" ");
            canvasPanel.setShownGraph(chosen);
        }
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

    // ------------------------------------------------------------- selection

    private void showSearched() {
        String pattern = search.getText().trim();
        if (pattern.isEmpty() || queries == null) {
            return;
        }
        GraphKind kind = shownGraph;
        onWorker(work -> {
            List<GraphQueries.GraphNode> found = work.search(kind, "%" + pattern + "%", 1);
            SwingUtilities.invokeLater(() -> {
                if (found.isEmpty()) {
                    pathResult.setText("Nothing in the "
                            + kind.displayName() + " matches " + pattern + ".");
                    clearTrees();
                    return;
                }
                showNode(found.getFirst());
            });
        });
    }

    /**
     * Roots the trees at the graph node for an executed action.
     *
     * <p>The "selected-action neighbourhood" of plan 24: a user looking at a row
     * in the actions table asks what it depended on, and this is the bridge.
     *
     * <p>An action the graph does not declare — {@code stable-status.txt}, which
     * aquery never mentions (Q7) — says so rather than showing an empty tree
     * that reads as "nothing depends on it".
     */
    public void showAction(long actionId) {
        // An executed action lives in the action graph, whichever source was
        // on screen; the selector follows so the label above the trees keeps
        // naming the graph that is actually shown.
        selectSource(GraphKind.DECLARED_ACTIONS);
        onWorker(work -> {
            java.util.OptionalLong nodeIndex = work.nodeForAction(actionId);
            if (nodeIndex.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    clearTrees();
                    pathResult.setText("This action is not in the dependency graph."
                            + " Some actions run without being declared by analysis.");
                });
                return;
            }
            Optional<GraphQueries.GraphNode> found =
                    work.node(Math.toIntExact(nodeIndex.getAsLong()));
            found.ifPresent(node -> SwingUtilities.invokeLater(() -> showNode(node)));
        });
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
            pathResult.setText("There is no derived dependency chain to draw:"
                    + " this session has no imported action graph.");
            return;
        }
        // The chain's node indices are action-graph indices.
        selectSource(GraphKind.DECLARED_ACTIONS);
        views.setSelectedComponent(canvasPanel);
        canvasPanel.showPath(
                nodes, com.holtherndon.bazelviz.analysis.GraphExtract.Mode.CRITICAL_PATH);
        pathResult.setText("Visualizer-computed dependency critical path: " + nodes.size()
                + " actions. This is what the dependencies imply, not what Bazel scheduled.");
    }

    /** Roots both trees at {@code node} and loads its immediate neighbours. */
    void showNode(GraphQueries.GraphNode node) {
        setRoot(dependencies, node, true);
        setRoot(dependents, node, false);
        canvasPanel.showNode(node.nodeIndex());
    }

    /**
     * Moves the trees without redrawing the canvas.
     *
     * <p>Separate from {@link #showNode} on purpose: the canvas is what asked,
     * so telling it to redraw would clear the very selection that arrived here.
     */
    private void rootTreesAt(int nodeIndex) {
        GraphKind kind = shownGraph;
        onWorker(work -> {
            Optional<GraphQueries.GraphNode> found = work.node(kind, nodeIndex);
            found.ifPresent(node -> SwingUtilities.invokeLater(() -> {
                setRoot(dependencies, node, true);
                setRoot(dependents, node, false);
            }));
        });
    }

    /** Called with the executed action behind a node picked on the canvas. */
    public void onActionSelected(java.util.function.LongConsumer listener) {
        canvasPanel.onActionSelected(listener);
    }

    /** The canvas half of the view, for tests. */
    GraphCanvasPanel canvasPanel() {
        return canvasPanel;
    }

    private void setRoot(JTree tree, GraphQueries.GraphNode node, boolean dependencies) {
        NodeRef ref = new NodeRef(
                node, dependencies, shownGraph != GraphKind.CONFIGURED_TARGETS);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(ref);
        root.add(new DefaultMutableTreeNode(NodeRef.LOADING));
        tree.setModel(new DefaultTreeModel(root));
        tree.expandPath(new javax.swing.tree.TreePath(root));
        // Loaded directly rather than through the expansion listener: JTree
        // expands a fresh model's root on its own, so by the time expandPath
        // runs there is no collapsed-to-expanded transition left to fire the
        // listener, and the root's children would stay a permanent "…".
        (dependencies ? dependenciesExpander : dependentsExpander).loadInto(root, ref);
    }

    private void clearTrees() {
        dependencies.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Dependencies")));
        dependents.setModel(
                new DefaultTreeModel(new DefaultMutableTreeNode("Reverse dependencies")));
    }

    // ------------------------------------------------------------------ path

    private void findPath() {
        if (queries == null) {
            return;
        }
        String from = pathFrom.getText().trim();
        String to = pathTo.getText().trim();
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        GraphKind kind = shownGraph;
        onWorker(work -> {
            List<GraphQueries.GraphNode> start = work.search(kind, "%" + from + "%", 1);
            List<GraphQueries.GraphNode> end = work.search(kind, "%" + to + "%", 1);
            String text;
            if (start.isEmpty() || end.isEmpty()) {
                text = "One of those is not in the graph.";
            } else {
                Optional<ShortestPath.Result> result = work.path(
                        kind, start.getFirst().nodeIndex(),
                        end.getFirst().nodeIndex(), PATH_BUDGET);
                // describe() is the one place that distinguishes "no path" from
                // "the search gave up", which are different answers and only
                // one of them is a fact about the build.
                text = result.map(ShortestPath.Result::describe)
                        .orElse("There is no index to search.");
                // A found path is drawn as well as described: the words say
                // how long it is, the drawing says what is on it.
                result.flatMap(ShortestPath.Result::found).ifPresent(nodes -> {
                    List<Integer> onPath = new java.util.ArrayList<>(nodes.length);
                    for (int node : nodes) {
                        onPath.add(node);
                    }
                    SwingUtilities.invokeLater(() -> canvasPanel.showPath(
                            onPath,
                            com.holtherndon.bazelviz.analysis.GraphExtract.Mode.PATH));
                });
            }
            SwingUtilities.invokeLater(() -> {
                pathResult.setText(text);
                pathResult.setToolTipText(PlainText.tooltip(text));
            });
        });
    }

    // ------------------------------------------------------------ lazy trees

    /** Loads a node's children the first time it is opened, and never before. */
    private final class LazyExpander implements javax.swing.event.TreeWillExpandListener {

        /** True for the "Depends on" tree, false for "Depended on by". */
        private final boolean dependencies;

        LazyExpander(boolean dependencies) {
            this.dependencies = dependencies;
        }

        @Override
        public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
            Object last = event.getPath().getLastPathComponent();
            if (!(last instanceof DefaultMutableTreeNode parent)
                    || !(parent.getUserObject() instanceof NodeRef ref)) {
                return;
            }
            loadInto(parent, ref);
        }

        /** Fetches a node's neighbours once, and fills its tree row. */
        void loadInto(DefaultMutableTreeNode parent, NodeRef ref) {
            if (ref.loaded) {
                return;
            }
            ref.loaded = true;
            // The forward index is producer-to-consumer, so what a node
            // depends on is its reverse neighbours. This mapping was once the
            // other way round, and the "Depends on" tree listed dependents.
            boolean forwards = !dependencies;
            GraphKind kind = shownGraph;
            onWorker(work -> {
                List<GraphQueries.GraphNode> children = work.neighbours(
                        kind, ref.node.nodeIndex(), forwards, CHILD_LIMIT);
                int degree = work.degree(kind, ref.node.nodeIndex(), forwards);
                SwingUtilities.invokeLater(() -> fill(parent, children, degree));
            });
        }

        @Override
        public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {
            // Collapsing keeps what was loaded; reopening should not re-query.
        }

        private void fill(
                DefaultMutableTreeNode parent, List<GraphQueries.GraphNode> children, int degree) {
            parent.removeAllChildren();
            for (GraphQueries.GraphNode child : children) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new NodeRef(
                        child, dependencies, shownGraph != GraphKind.CONFIGURED_TARGETS));
                node.add(new DefaultMutableTreeNode(NodeRef.LOADING));
                parent.add(node);
            }
            if (degree > children.size()) {
                // Never silently truncated (rule 12): the row says how many
                // were left out, so a shortened list cannot read as the whole.
                parent.add(new DefaultMutableTreeNode(
                        (degree - children.size()) + " more not shown"));
            }
            if (degree == 0) {
                parent.add(new DefaultMutableTreeNode(
                        dependencies ? "nothing it depends on" : "nothing depends on it"));
            }
            JTree tree = dependencies ? GraphView.this.dependencies : dependents;
            ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(parent);
        }
    }

    /** A graph node as a tree node, remembering whether its children are in. */
    static final class NodeRef {

        static final String LOADING = "…";

        final GraphQueries.GraphNode node;

        /** Which tree this row belongs to; the expander asks the index. */
        final boolean dependencies;

        /**
         * False for label-graph rows: a label is never "executed", so the
         * marker below would be a claim about a concept the graph lacks.
         */
        final boolean showsExecution;

        boolean loaded;

        NodeRef(GraphQueries.GraphNode node, boolean dependencies, boolean showsExecution) {
            this.node = node;
            this.dependencies = dependencies;
            this.showsExecution = showsExecution;
        }

        @Override
        public String toString() {
            // A declared action that never ran is marked, because a tree full
            // of actions the build did not run is a fact about the graph and
            // not about the build.
            return showsExecution && node.declaredOnly()
                    ? node.displayName() + "  — not executed"
                    : node.displayName();
        }
    }

    // ---------------------------------------------------------------- plumbing

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

    /** The graph the card is currently traversing, for tests. */
    GraphKind shownGraphForTesting() {
        return shownGraph;
    }

    /** The trees, for tests. */
    JTree dependenciesTree() {
        return dependencies;
    }

    JTree dependentsTree() {
        return dependents;
    }

    JLabel warningLabel() {
        return warning;
    }

    JLabel detailLabel() {
        return sourceDetail;
    }

    JComboBox<GraphQueries.GraphSource> sourceSelector() {
        return sourceChoice;
    }
}

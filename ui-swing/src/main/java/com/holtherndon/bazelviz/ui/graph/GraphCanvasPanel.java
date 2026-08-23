package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * The graph canvas with the controls that decide what it draws.
 *
 * <p>The controller half of the split plan 17.7 requires: this class holds the
 * service that reads the session, and hands the canvas a finished
 * {@link GraphModel}. The canvas can neither query nor lay out, which is checked
 * by reflection in {@code GraphPaintIsolationTest} rather than by review.
 *
 * <h2>The sentence under the drawing is not decoration</h2>
 *
 * <p>Plan 13.6 ends "never claim the omitted nodes do not exist", and a drawing
 * cannot say that on its own — a view of nine actions from a build of ninety
 * thousand looks exactly like a build with nine actions. So every rendering
 * carries a sentence naming the totals, and it is shown whether or not anything
 * was left out.
 */
public final class GraphCanvasPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Depths a neighbourhood may be expanded to. Plan 13.3 forbids "all". */
    private static final int MAX_DEPTH = 12;

    private final GraphCanvas canvas = new GraphCanvas();
    private final JComboBox<GraphExtract.Mode> mode = new JComboBox<>();
    private final JComboBox<GraphLayout.Kind> layout = new JComboBox<>(GraphLayout.Kind.values());
    private final JComboBox<GraphClustering.By> groupBy =
            new JComboBox<>(GraphClustering.By.values());
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(2, 1, MAX_DEPTH, 1));
    private final JLabel description = new JLabel(" ");
    private final JLabel omission = new JLabel(" ");
    private final JLabel selected = new JLabel(" ");

    /**
     * The bar plan 13.6 requires above the limit.
     *
     * <p>"Offer raise limit, export, and refine filter", and never claim the
     * omitted nodes do not exist. It is hidden when there is nothing to say —
     * a standing notice trains a user to stop reading the place the real
     * warning appears.
     */
    private final JPanel overLimit = new JPanel();

    private final JLabel overLimitText = new JLabel(" ");
    private final JButton raiseLimit = new JButton("Draw it anyway");
    private final JButton showClusters = new JButton("Group instead");
    private final JButton exportComplete = new JButton("Export all of it…");
    private final JButton refine = new JButton("Narrow it");

    private GraphLayoutService service;
    private String[] labelsByNodeIndex;
    private long[] durationsByNodeIndex;
    private int rootNode = -1;
    private int nodeLimit = GraphExtract.DEFAULT_NODE_LIMIT;
    private int edgeLimit = GraphExtract.DEFAULT_EDGE_LIMIT;
    private LimitEstimate estimate;

    /**
     * True when the cluster view on screen was chosen by the application, not
     * by the user.
     *
     * <p>It changes what the bar says — "grouped instead" is a different
     * statement from "grouped, as you asked" — and it stops a second automatic
     * switch when the grouping itself does not fit.
     */
    private boolean aggregatedAutomatically;
    private java.util.Map<Integer, Long> actionIdByNodeIndex = java.util.Map.of();
    private java.util.function.LongConsumer actionListener = actionId -> {};
    private java.util.function.IntConsumer nodeListener = nodeIndex -> {};

    public GraphCanvasPanel() {
        super(new BorderLayout());
        PlainText.disableHtml(description);
        PlainText.disableHtml(omission);
        PlainText.disableHtml(selected);
        omission.setFont(omission.getFont().deriveFont(Font.ITALIC));

        // PATH and CRITICAL_PATH are in the list so a found path can be shown
        // as the current mode, but they cannot be reached by picking them: a
        // path needs two endpoints that only a search supplies, and a mode a
        // user could select but never satisfy would be a dead control.
        mode.setModel(new DefaultComboBoxModel<>(new GraphExtract.Mode[] {
            GraphExtract.Mode.NEIGHBOURHOOD, GraphExtract.Mode.DEPENDENCIES,
            GraphExtract.Mode.DEPENDENTS, GraphExtract.Mode.WHOLE, GraphExtract.Mode.CLUSTERS,
            GraphExtract.Mode.PATH, GraphExtract.Mode.CRITICAL_PATH,
        }));
        mode.setRenderer(new Renderer<>(value -> ((GraphExtract.Mode) value).displayName()));
        layout.setRenderer(new Renderer<>(value -> ((GraphLayout.Kind) value).displayName()));
        groupBy.setRenderer(new Renderer<>(value -> ((GraphClustering.By) value).displayName()));

        mode.addActionListener(event -> modeChanged());
        layout.addActionListener(event -> refresh());
        groupBy.addActionListener(event -> refresh());
        depth.addChangeListener(event -> refresh());

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        controls.add(new JLabel("Show:"));
        controls.add(mode);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(new JLabel("Depth:"));
        controls.add(depth);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(new JLabel("Layout:"));
        controls.add(layout);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(new JLabel("Group by:"));
        controls.add(groupBy);
        controls.add(Box.createHorizontalStrut(8));
        JButton fit = new JButton("Fit");
        fit.addActionListener(event -> canvas.fitToView());
        controls.add(fit);
        // Plan 17.7 lists "export visible graph" among the canvas's ordinary
        // actions, not only among the things offered when a graph is too big.
        JButton export = new JButton("Export…");
        export.addActionListener(event -> exportChosen());
        controls.add(export);
        controls.add(Box.createHorizontalGlue());

        overLimit.setLayout(new BoxLayout(overLimit, BoxLayout.X_AXIS));
        overLimit.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        PlainText.disableHtml(overLimitText);
        overLimitText.setFont(overLimitText.getFont().deriveFont(Font.BOLD));
        raiseLimit.addActionListener(event -> drawItAnyway());
        showClusters.addActionListener(
                event -> mode.setSelectedItem(GraphExtract.Mode.CLUSTERS));
        exportComplete.addActionListener(event -> exportCompleteChosen());
        refine.addActionListener(event -> narrow());
        overLimit.add(overLimitText);
        overLimit.add(Box.createHorizontalStrut(8));
        overLimit.add(raiseLimit);
        overLimit.add(refine);
        overLimit.add(showClusters);
        overLimit.add(exportComplete);
        overLimit.add(Box.createHorizontalGlue());
        overLimit.setVisible(false);

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        status.add(description);
        status.add(omission);
        status.add(selected);

        JPanel top = new JPanel(new BorderLayout());
        top.add(controls, BorderLayout.NORTH);
        top.add(overLimit, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        canvas.onSelectionChanged(this::selectionChanged);
        canvas.onViewChanged(this::updateOmission);
        modeChanged();
        showNothing("Open a session with a dependency graph to draw it.");
    }

    /**
     * The canvas itself.
     *
     * <p>Package-private: nothing outside {@code ui.graph} should reach past
     * the panel to the component it manages, and the audit that counts callers
     * is what noticed it was public for no reason.
     */
    GraphCanvas canvas() {
        return canvas;
    }

    /** The sentence naming the totals; plan 13.6 requires it to always be shown. */
    String descriptionText() {
        return description.getText();
    }

    /** What this frame is not drawing, if anything. */
    String omissionText() {
        return omission.getText();
    }

    /** The colour legend, or the current selection when there is one. */
    String legendText() {
        return selected.getText();
    }

    /**
     * Takes over a session's graph.
     *
     * @param labelsByNodeIndex fetched once, off the event thread, so no paint
     *     ever needs a name it does not already have
     */
    public void attach(
            GraphLayoutService service,
            String[] labelsByNodeIndex,
            long[] durationsByNodeIndex,
            java.util.Map<Integer, Long> actionIdByNodeIndex) {
        this.service = service;
        this.labelsByNodeIndex = labelsByNodeIndex;
        this.durationsByNodeIndex = durationsByNodeIndex;
        this.actionIdByNodeIndex = actionIdByNodeIndex == null
                ? java.util.Map.of() : actionIdByNodeIndex;
        showNothing("Pick an action to draw its neighbourhood, or switch to the whole build.");
    }

    /** Lets go of the session. */
    public void detach() {
        this.service = null;
        this.labelsByNodeIndex = null;
        this.durationsByNodeIndex = null;
        this.actionIdByNodeIndex = java.util.Map.of();
        this.rootNode = -1;
        this.aggregatedAutomatically = false;
        canvas.setModel(GraphModel.empty());
        showNothing("No dependency graph is open.");
    }

    /**
     * Draws a path a search has already found.
     *
     * <p>Plan 13.5's "path between two actions", which the trees can state in
     * words but only the canvas can show the shape of.
     */
    public void showPath(java.util.List<Integer> nodes, GraphExtract.Mode pathMode) {
        if (service == null || nodes.isEmpty()) {
            return;
        }
        mode.setSelectedItem(pathMode);
        setText(description, "Drawing…");
        service.submitPath(
                GraphLayoutService.Request.forPath(EdgeDerivation.DECLARED, pathMode),
                nodes, this::rendered, this::failed);
    }

    /** Draws the neighbourhood of one graph node. */
    public void showNode(int nodeIndex) {
        this.rootNode = nodeIndex;
        if (mode.getSelectedItem() == GraphExtract.Mode.WHOLE
                || mode.getSelectedItem() == GraphExtract.Mode.CLUSTERS) {
            mode.setSelectedItem(GraphExtract.Mode.NEIGHBOURHOOD);
            return;
        }
        refresh();
    }

    /**
     * Raises the drawing limits and redraws; plan 13.6's explicit opt-in.
     *
     * <p>Explicit because the point of the limit is that a user chooses to pay
     * for the bigger drawing, having been told what it will cost.
     */
    public void raiseLimits(int nodes, int edges) {
        this.nodeLimit = nodes;
        this.edgeLimit = edges;
        refresh();
    }

    public int nodeLimit() {
        return nodeLimit;
    }

    public int edgeLimit() {
        return edgeLimit;
    }

    private void modeChanged() {
        GraphExtract.Mode chosen = (GraphExtract.Mode) mode.getSelectedItem();
        if (chosen != GraphExtract.Mode.CLUSTERS) {
            aggregatedAutomatically = false;
        }
        boolean rooted = chosen == GraphExtract.Mode.NEIGHBOURHOOD
                || chosen == GraphExtract.Mode.DEPENDENCIES
                || chosen == GraphExtract.Mode.DEPENDENTS;
        depth.setEnabled(rooted);
        groupBy.setEnabled(chosen == GraphExtract.Mode.CLUSTERS);
        layout.setSelectedItem(GraphLayout.defaultFor(chosen));
        refresh();
    }

    /** Rebuilds the request from the controls and submits it. */
    void refresh() {
        if (service == null) {
            return;
        }
        GraphExtract.Mode chosen = (GraphExtract.Mode) mode.getSelectedItem();
        if (chosen == null) {
            return;
        }
        if (chosen == GraphExtract.Mode.PATH || chosen == GraphExtract.Mode.CRITICAL_PATH) {
            // Reached only by showPath, which has already drawn one. Redrawing
            // from the controls would need endpoints the controls do not hold.
            return;
        }
        boolean rooted = chosen == GraphExtract.Mode.NEIGHBOURHOOD
                || chosen == GraphExtract.Mode.DEPENDENCIES
                || chosen == GraphExtract.Mode.DEPENDENTS;
        if (rooted && rootNode < 0) {
            showNothing("Pick an action to draw its neighbourhood.");
            return;
        }

        GraphLayoutService.Request request = switch (chosen) {
            case CLUSTERS -> GraphLayoutService.Request.clustered(
                    EdgeDerivation.DECLARED, (GraphClustering.By) groupBy.getSelectedItem());
            case WHOLE -> GraphLayoutService.Request.whole(
                    EdgeDerivation.DECLARED, nodeLimit, edgeLimit);
            default -> GraphLayoutService.Request.around(
                    EdgeDerivation.DECLARED, chosen,
                    Math.max(0, rootNode), (Integer) depth.getValue());
        };
        request = request
                .withLayout((GraphLayout.Kind) layout.getSelectedItem())
                .withLimits(nodeLimit, edgeLimit);

        description.setText("Drawing…");
        service.estimate(request, found -> this.estimate = found, failure -> { });
        service.submit(request, this::rendered, this::failed);
    }

    private void rendered(GraphLayoutService.Rendered result) {
        // Plan 13.6: above the limit, switch to cluster mode. Switch, not
        // offer -- a user who asked for the whole build and got a blank canvas
        // with an explanation has been told no; one who gets the same build
        // grouped by package has been answered.
        if (result.refused()
                && result.request() != null
                && result.request().mode() == GraphExtract.Mode.WHOLE) {
            aggregatedAutomatically = true;
            setText(description, result.description());
            mode.setSelectedItem(GraphExtract.Mode.CLUSTERS);
            return;
        }

        canvas.setModel(GraphModel.of(result, labelsByNodeIndex, durationsByNodeIndex));
        setText(description, aggregatedAutomatically
                ? "Too big to draw action by action, so it is grouped. "
                        + result.description()
                : result.description());
        updateOmission();
        setText(selected, legend());
        setOverLimit(result.refused() || result.extract().hitLimit()
                || aggregatedAutomatically, result);
    }

    private void setOverLimit(boolean over, GraphLayoutService.Rendered result) {
        overLimit.setVisible(over);
        if (!over) {
            return;
        }
        if (aggregatedAutomatically) {
            setText(overLimitText, "Grouped because the whole build is too big to draw.");
        } else {
            setText(overLimitText, result.refused()
                    ? "Too big to draw in detail."
                    : "Stopped early; there is more than this.");
        }
        // "Draw it anyway" only means something when the exact size is known,
        // which is the whole-graph case. For a traversal that stopped at its
        // budget, doubling the budget is the honest offer.
        raiseLimit.setToolTipText(PlainText.tooltip(result.refused()
                ? "Raise the limit to " + result.extract().totalNodes()
                        + " actions and draw all of it."
                : "Double the search budget and look further."));
    }

    /**
     * Plan 13.6's "refine filter", made an action rather than a hint.
     *
     * <p>For a rooted view the narrower question is a shallower one, so this
     * steps the depth down. For the whole build the narrower question is a
     * neighbourhood, so it becomes one — when there is an action to centre it
     * on, and otherwise it says what it needs instead of quietly doing nothing.
     */
    private void narrow() {
        GraphExtract.Mode chosen = (GraphExtract.Mode) mode.getSelectedItem();
        boolean rooted = chosen == GraphExtract.Mode.NEIGHBOURHOOD
                || chosen == GraphExtract.Mode.DEPENDENCIES
                || chosen == GraphExtract.Mode.DEPENDENTS;
        if (rooted) {
            int depthNow = (Integer) depth.getValue();
            if (depthNow > 1) {
                depth.setValue(depthNow - 1);
            } else {
                setText(description,
                        "This is already the narrowest view: one step from the chosen action.");
            }
            return;
        }
        if (rootNode < 0) {
            setText(description,
                    "Pick an action first — narrowing means drawing the graph around one.");
            return;
        }
        mode.setSelectedItem(GraphExtract.Mode.NEIGHBOURHOOD);
    }

    /** Plan 13.6's "raise limit", which must be a decision rather than a default. */
    private void drawItAnyway() {
        GraphModel model = canvas.model();
        long wantedNodes = Math.max(model.extract().totalNodes(), nodeLimit * 2L);
        long wantedEdges = Math.max(model.extract().totalEdges(), edgeLimit * 2L);
        if (aggregatedAutomatically) {
            // Back to the view they asked for in the first place. Leaving them
            // in the grouped one after they pressed "draw it anyway" would be
            // ignoring the press. The mode change refreshes, so raiseLimits
            // must set the ceiling without also drawing at the old mode.
            this.nodeLimit = (int) Math.min(Integer.MAX_VALUE, wantedNodes);
            this.edgeLimit = (int) Math.min(Integer.MAX_VALUE, wantedEdges);
            aggregatedAutomatically = false;
            mode.setSelectedItem(GraphExtract.Mode.WHOLE);
            return;
        }
        raiseLimits(
                (int) Math.min(Integer.MAX_VALUE, wantedNodes),
                (int) Math.min(Integer.MAX_VALUE, wantedEdges));
    }

    /**
     * Called with the executed action behind a selected node, when there is one.
     *
     * <p>The return leg of {@code GraphView.showAction}: a user who found an
     * action in the table can draw its neighbourhood, and a user who found one
     * in the drawing can open its detail. Nodes with no executed action — every
     * test's TestRunner in a {@code build} invocation — simply do not fire it,
     * rather than firing a zero that would open the wrong row.
     */
    public void onActionSelected(java.util.function.LongConsumer listener) {
        this.actionListener = listener == null ? actionId -> {} : listener;
    }

    /**
     * Called with the graph node index of a single selection.
     *
     * <p>So the trees beside the canvas can follow it. Two halves of one view
     * showing two different nodes is the kind of disagreement a user reads as a
     * bug in the data rather than in the window.
     */
    public void onNodeSelected(java.util.function.IntConsumer listener) {
        this.nodeListener = listener == null ? nodeIndex -> {} : listener;
    }

    /** The executed action behind a drawn node, when the session ran one. */
    java.util.OptionalLong actionIdAt(int position) {
        if (canvas.model().isCluster() || position < 0
                || position >= canvas.model().size()) {
            return java.util.OptionalLong.empty();
        }
        Long actionId = actionIdByNodeIndex.get(canvas.model().nodeAt(position));
        return actionId == null
                ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(actionId);
    }

    /**
     * Writes what is on screen.
     *
     * <p>On a background thread: a visible graph is bounded, but the file
     * system is not, and a slow disk must not freeze the window.
     */
    public void exportVisible(java.nio.file.Path target, GraphExport.Format format) {
        GraphModel model = canvas.model();
        if (service == null) {
            return;
        }
        service.onGraph(
                EdgeDerivation.DECLARED,
                graph -> GraphExport.visible(model, target, format),
                this::exported,
                this::exportFailed);
    }

    /**
     * Writes every node and edge the session holds, drawn or not.
     *
     * <p>Plan 13.6 lists export among the three things offered above the limit,
     * and this is the one that makes the limit acceptable: the drawing is
     * bounded, the data is not.
     */
    public void exportComplete(java.nio.file.Path target, GraphExport.Format format) {
        if (service == null) {
            return;
        }
        String[] labels = labelsByNodeIndex;
        long[] durations = durationsByNodeIndex;
        service.onGraph(
                EdgeDerivation.DECLARED,
                graph -> GraphExport.whole(graph, labels, durations, target, format),
                this::exported,
                this::exportFailed);
    }

    private void exported(GraphExport.Result result) {
        setText(selected, result.describe());
    }

    private void exportFailed(Throwable failure) {
        setText(selected, "The export failed: " + failure.getMessage());
    }

    private void exportCompleteChosen() {
        chooseAndExport("Export the complete graph", true);
    }

    /**
     * Asks which graph, then where.
     *
     * <p>Two exports rather than one because they answer different questions,
     * and a user who exported "the graph" and got only what happened to be on
     * screen would be badly surprised.
     */
    private void exportChosen() {
        Object[] choices = {"Visible graph", "Complete graph"};
        int chosen = javax.swing.JOptionPane.showOptionDialog(
                this,
                "Export what is drawn, or everything the session holds?",
                "Export graph",
                javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, choices, choices[0]);
        if (chosen < 0) {
            return;
        }
        chooseAndExport(
                chosen == 0 ? "Export the visible graph" : "Export the complete graph",
                chosen == 1);
    }

    private void chooseAndExport(String title, boolean complete) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File("build-graph.dot"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File chosen = chooser.getSelectedFile();
        GraphExport.Format format = chosen.getName().endsWith(".csv")
                ? GraphExport.Format.CSV : GraphExport.Format.DOT;
        if (complete) {
            exportComplete(chosen.toPath(), format);
        } else {
            exportVisible(chosen.toPath(), format);
        }
    }

    /** The estimate for the current settings, once one has been fetched. */
    java.util.Optional<LimitEstimate> estimate() {
        return java.util.Optional.ofNullable(estimate);
    }

    /** Whether the over-limit bar is showing; plan 13.6's three offers. */
    boolean isOverLimitShown() {
        return overLimit.isVisible();
    }

    /** Whether the cluster view on screen was chosen by the application. */
    boolean isAutomaticallyGrouped() {
        return aggregatedAutomatically;
    }

    GraphExtract.Mode modeForTesting() {
        return (GraphExtract.Mode) mode.getSelectedItem();
    }

    void drawItAnywayForTesting() {
        drawItAnyway();
    }

    String overLimitText() {
        return overLimitText.getText();
    }

    void narrowForTesting() {
        narrow();
    }

    int depthForTesting() {
        return (Integer) depth.getValue();
    }

    void setDepthForTesting(int value) {
        depth.setValue(value);
    }

    void setModeForTesting(GraphExtract.Mode value) {
        mode.setSelectedItem(value);
    }

    private void failed(Throwable failure) {
        canvas.setModel(GraphModel.empty());
        setText(description, "The graph could not be drawn: " + failure.getMessage());
        setText(omission, " ");
        overLimit.setVisible(false);
    }

    private void updateOmission() {
        setText(omission, canvas.hiddenDetail().orElse(" "));
    }

    private void selectionChanged(int[] positions) {
        if (positions.length == 0) {
            setText(selected, legend());
            return;
        }
        if (positions.length == 1) {
            GraphModel model = canvas.model();
            actionIdAt(positions[0]).ifPresent(actionListener::accept);
            if (!model.isCluster()) {
                nodeListener.accept(canvas.selectedNodes()[0]);
            }
            String text = model.displayLabelAt(positions[0]);
            setText(selected, model.durationAt(positions[0])
                    .stream()
                    .mapToObj(micros -> text + "  —  " + micros / 1_000 + " ms")
                    .findFirst()
                    // Rule 11: an action nothing timed says so rather than
                    // showing a zero that reads as instant.
                    .orElse(text + "  —  not timed in this session"));
            return;
        }
        setText(selected, positions.length + " actions selected");
    }

    /** What the colours mean, including how much of the drawing they cannot speak for. */
    private String legend() {
        GraphModel model = canvas.model();
        if (model.size() == 0) {
            return " ";
        }
        if (model.isCluster()) {
            GraphClustering.Result clustering = model.clustering();
            // The summed counts rather than the graph's totals: these are what
            // the boxes on screen add up to, so a user can check the drawing
            // against the build rather than take it on trust.
            String text = clustering.clusters().size() + " groups holding "
                    + clustering.clusteredNodes() + " actions and "
                    + clustering.clusteredEdges() + " dependencies between them.";
            long unnamed = clustering.clusters().stream()
                    .filter(GraphClustering.Cluster::isUnknown)
                    .mapToLong(GraphClustering.Cluster::nodeCount)
                    .sum();
            // Plan 11.4: a group of actions nobody named has to be visible as
            // that, not folded into the count as though it were a package.
            return unnamed == 0
                    ? text
                    : text + "  " + unnamed + " of them have no recorded name.";
        }
        int untimed = model.untimedCount();
        String scale = model.slowestDuration()
                .stream()
                .mapToObj(slowest -> "Colour is duration, blue to red, up to "
                        + slowest / 1_000 + " ms.")
                .findFirst()
                .orElse("Nothing here was timed, so nothing is coloured by duration.");
        if (untimed == 0) {
            return scale;
        }
        return scale + "  " + untimed + " of " + model.size()
                + " were not timed and are grey.";
    }

    private void showNothing(String why) {
        canvas.setModel(GraphModel.empty());
        overLimit.setVisible(false);
        setText(description, why);
        setText(omission, " ");
        setText(selected, " ");
    }

    private static void setText(JLabel label, String text) {
        label.setText(text == null || text.isBlank() ? " " : text);
        label.setToolTipText(PlainText.tooltip(label.getText()));
    }

    /** Renders an enum by its display name rather than its constant. */
    private static final class Renderer<T> extends javax.swing.DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        private final java.util.function.Function<Object, String> naming;

        Renderer(java.util.function.Function<Object, String> naming) {
            this.naming = naming;
            setHorizontalAlignment(SwingConstants.LEADING);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value != null) {
                setText(naming.apply(value));
            }
            return this;
        }
    }
}

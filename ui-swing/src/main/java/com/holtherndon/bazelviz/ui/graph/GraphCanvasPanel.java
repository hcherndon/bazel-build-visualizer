package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
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

    /**
     * The floor of the user-settable node limit.
     *
     * <p>One, not zero: a limit of zero draws nothing and would read as "the
     * graph is empty", which is a claim about the build rather than about the
     * setting. Any positive budget is a real, if strange, request.
     */
    static final int MIN_NODE_LIMIT = 1;

    /**
     * The ceiling of the user-settable node limit.
     *
     * <p>The largest planned graph is the Tier 3 fixture's five million
     * nodes (plan 19.4), so no honest request needs more — and the spinner
     * needs some ceiling, because Integer.MAX_VALUE in a spinner reads as
     * "no limit", which is exactly the claim plan 13.6 forbids.
     */
    static final int MAX_NODE_LIMIT = 5_000_000;

    private final GraphCanvas canvas = new GraphCanvas();
    private final JComboBox<GraphExtract.Mode> mode = new JComboBox<>();
    private final JComboBox<GraphLayout.Kind> layout = new JComboBox<>(GraphLayout.Kind.values());
    private final JComboBox<GraphEdgeDisplay> edgeDisplay =
            new JComboBox<>(GraphEdgeDisplay.values());
    private final JComboBox<GraphClustering.By> groupBy =
            new JComboBox<>(GraphClustering.By.values());

    /**
     * What node size, edge thickness and colour mean.
     *
     * <p>The weight changes visual encoding only: positions belong to the
     * layouts, so switching weights restyles the current drawing without
     * re-extracting or re-laying-out — and without moving the camera.
     */
    private final JComboBox<GraphWeight> weightChoice =
            new JComboBox<>(GraphWeight.values());
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(2, 1, MAX_DEPTH, 1));

    /**
     * The node limit, as a control rather than a consequence.
     *
     * <p>Plan 13.6's "raise limit" existed only as the over-limit bar's
     * doubling button, so the one way to choose a budget was to be refused at
     * the old one first. The spinner makes the limit a setting: type a number,
     * the next drawing honours it, and the over-limit machinery still says
     * exactly what did not fit.
     */
    private final JSpinner nodeLimitControl = new JSpinner(new SpinnerNumberModel(
            GraphExtract.DEFAULT_NODE_LIMIT, MIN_NODE_LIMIT, MAX_NODE_LIMIT, 1_000));
    private final javax.swing.JTextArea description = WrappingLabel.create(" ");
    private final javax.swing.JTextArea omission = WrappingLabel.create(" ");
    private final javax.swing.JTextArea selected = WrappingLabel.create(" ");
    private final javax.swing.JTextArea scopeExplanation = explanationArea();
    private final javax.swing.JTextArea appearanceExplanation = explanationArea();
    private final JPanel depthSetting;
    private final JPanel budgetSetting;
    private final JLabel budgetLabel;
    private final JPanel groupBySetting;
    private final JToggleButton controlsHelp = new JToggleButton("Control help");
    private final JPanel controlsHelpPanel = new JPanel();

    /**
     * The bar plan 13.6 requires above the limit.
     *
     * <p>"Offer raise limit, export, and refine filter", and never claim the
     * omitted nodes do not exist. It is hidden when there is nothing to say —
     * a standing notice trains a user to stop reading the place the real
     * warning appears.
     */
    private final JPanel overLimit = new JPanel();

    private final javax.swing.JTextArea overLimitText = WrappingLabel.create(" ");
    private final JButton raiseLimit = new JButton("Draw it anyway");
    private final JButton showClusters = new JButton("Group instead");
    private final JButton exportComplete = new JButton("Export all of it…");
    private final JButton refine = new JButton("Narrow it");

    private GraphLayoutService service;
    private GraphKind shownGraph = GraphKind.DECLARED_ACTIONS;
    private String[] actionLabels;

    /**
     * Per-action display names — "Mnemonic — output basename" — for the two
     * action graphs, or null when the caller supplied none.
     *
     * <p>Separate from {@link #actionLabels} because the two answer different
     * questions: the target label is what an action belongs to (what the
     * complete export's {@code label} column must keep meaning), the display
     * name is what distinguishes one of the target's actions from another on
     * the canvas — where every action under one label reading as the same
     * string was the exact complaint.
     */
    private String[] actionDisplayLabels;

    private long[] actionDurations;
    private String[] labelGraphLabels;
    private long[] labelGraphDurations;
    private int rootNode = -1;

    /** True while {@link #raiseLimits} writes the spinner, so it does not echo. */
    private boolean syncingLimitControl;
    private int nodeLimit = GraphExtract.DEFAULT_NODE_LIMIT;
    private int clusterLimit = GraphClustering.DEFAULT_CLUSTER_LIMIT;
    private int edgeLimit = GraphExtract.DEFAULT_EDGE_LIMIT;
    private LimitEstimate estimate;
    private GraphEdgeDisplay hierarchyEdgeDisplay = GraphEdgeDisplay.DECLUTTERED;

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
    private EntityActions entityActions;

    /**
     * Bumped whenever what is on screen changes, so a weight computation or a
     * whole-graph count that finishes late restyles nothing. Read and written
     * on the EDT only.
     */
    private long weightGeneration;

    /** Discards layout/model callbacks for a view the controls have replaced. */
    private long renderGeneration;

    /** Discards export status callbacks after the graph or session changes. */
    private long exportGeneration;

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
        }));
        // The renderer asks at paint time, so the list re-words itself when
        // the source selector switches graphs: "Path between two targets" over
        // the label graph, "...two actions" over the action graphs.
        mode.setRenderer(new Renderer<>(value -> ((GraphExtract.Mode) value).displayName(noun())));
        layout.setRenderer(new Renderer<>(value -> ((GraphLayout.Kind) value).displayName()));
        edgeDisplay.setRenderer(
                new Renderer<>(value -> ((GraphEdgeDisplay) value).displayName()));
        groupBy.setRenderer(new Renderer<>(value -> ((GraphClustering.By) value).displayName()));
        weightChoice.setRenderer(new Renderer<>(value -> ((GraphWeight) value).displayName()));

        mode.addActionListener(event -> modeChanged());
        layout.addActionListener(event -> {
            updateExplanations();
            refresh();
        });
        edgeDisplay.addActionListener(event -> edgeDisplayChanged());
        groupBy.addActionListener(event -> refresh());
        weightChoice.addActionListener(event -> {
            updateExplanations();
            weightChanged();
        });
        depth.addChangeListener(event -> refresh());
        nodeLimitControl.addChangeListener(event -> nodeLimitTyped());

        mode.setName("graph.scope");
        depth.setName("graph.depth");
        nodeLimitControl.setName("graph.nodeBudget");
        layout.setName("graph.layout");
        edgeDisplay.setName("graph.edges");
        groupBy.setName("graph.groupBy");
        weightChoice.setName("graph.nodeEncoding");
        scopeExplanation.setName("graph.scopeExplanation");
        appearanceExplanation.setName("graph.appearanceExplanation");

        JPanel controlsRow = new JPanel(new WrapLayout(FlowLayout.LEADING, 8, 3));
        controlsRow.setName("graph.controlsRow");
        controlsRow.setBorder(BorderFactory.createTitledBorder("Graph controls"));
        controlsRow.add(labeledSetting("Scope", mode, "graph.scopeLabel"));
        depthSetting = labeledSetting(
                "Traversal depth", depth, "graph.depthLabel");
        controlsRow.add(depthSetting);
        budgetSetting = labeledSetting(
                "Node budget", nodeLimitControl, "graph.nodeBudgetLabel");
        budgetLabel = (JLabel) budgetSetting.getComponent(0);
        controlsRow.add(budgetSetting);
        groupBySetting = labeledSetting(
                "Group nodes by", groupBy, "graph.groupByLabel");
        controlsRow.add(groupBySetting);
        controlsRow.add(labeledSetting("Layout", layout, "graph.layoutLabel"));
        controlsRow.add(labeledSetting("Dependencies", edgeDisplay, "graph.edgesLabel"));
        controlsRow.add(labeledSetting(
                "Node size and colour", weightChoice, "graph.nodeEncodingLabel"));

        JButton fit = new JButton("Fit graph");
        fit.setName("graph.fit");
        fit.setToolTipText(PlainText.tooltip("Fit every laid-out node and its visible labels."));
        fit.addActionListener(event -> canvas.fitToView());
        // The drag overlay's explicit way back. A new layout resets dragged
        // positions on its own; this is for undoing a rearrangement of the
        // drawing that is otherwise staying.
        JButton resetPositions = new JButton("Reset moved nodes");
        resetPositions.setName("graph.resetPositions");
        resetPositions.setToolTipText(PlainText.tooltip(
                "Put every dragged node back where the layout placed it."));
        resetPositions.addActionListener(event -> canvas.resetDragOffsets());
        // Plan 17.7 lists "export visible graph" among the canvas's ordinary
        // actions, not only among the things offered when a graph is too big.
        JButton export = new JButton("Export graph…");
        export.setName("graph.export");
        export.addActionListener(event -> exportChosen());

        controlsHelp.setName("graph.controlsHelp");
        controlsHelp.setToolTipText(PlainText.tooltip(
                "Show plain-language explanations for the selected graph controls."));
        controlsHelp.getAccessibleContext().setAccessibleDescription(
                "Show plain-language explanations for the selected graph controls.");
        controlsRow.add(controlsHelp);
        controlsRow.add(fit);
        controlsRow.add(resetPositions);
        controlsRow.add(export);

        controlsHelpPanel.setName("graph.controlsHelpPanel");
        controlsHelpPanel.setLayout(new BoxLayout(controlsHelpPanel, BoxLayout.Y_AXIS));
        controlsHelpPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        controlsHelpPanel.add(scopeExplanation);
        controlsHelpPanel.add(appearanceExplanation);
        controlsHelpPanel.setVisible(false);
        controlsHelp.addActionListener(event -> {
            controlsHelpPanel.setVisible(controlsHelp.isSelected());
            controlsHelp.setText(controlsHelp.isSelected() ? "Hide control help" : "Control help");
            revalidate();
            repaint();
        });

        JPanel controls = new JPanel(new BorderLayout());
        controls.setName("graph.controls");
        controls.setBorder(BorderFactory.createEmptyBorder(2, 8, 1, 8));
        controls.add(controlsRow, BorderLayout.NORTH);
        controls.add(controlsHelpPanel, BorderLayout.CENTER);

        overLimit.setLayout(new BorderLayout(6, 2));
        overLimit.setName("graph.limitWarning");
        overLimit.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        overLimitText.setName("graph.limitWarningText");
        PlainText.disableHtml(overLimitText);
        overLimitText.setFont(overLimitText.getFont().deriveFont(Font.BOLD));
        raiseLimit.addActionListener(event -> drawItAnyway());
        showClusters.addActionListener(
                event -> mode.setSelectedItem(GraphExtract.Mode.CLUSTERS));
        exportComplete.addActionListener(event -> exportCompleteChosen());
        refine.addActionListener(event -> narrow());
        JPanel limitActions = new JPanel(new WrapLayout(FlowLayout.LEADING, 6, 2));
        limitActions.setName("graph.limitActions");
        limitActions.add(raiseLimit);
        limitActions.add(refine);
        limitActions.add(showClusters);
        limitActions.add(exportComplete);
        overLimit.add(overLimitText, BorderLayout.NORTH);
        overLimit.add(limitActions, BorderLayout.CENTER);
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
        canvas.onFocusRequested(this::focusOnPosition);
        installFocusMenu();
        modeChanged();
        edgeDisplay.setSelectedItem(GraphEdgeDisplay.DECLUTTERED);
        updateExplanations();
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
        this.shownGraph = GraphKind.DECLARED_ACTIONS;
        this.actionLabels = labelsByNodeIndex;
        this.actionDurations = durationsByNodeIndex;
        this.actionIdByNodeIndex = actionIdByNodeIndex == null
                ? java.util.Map.of() : actionIdByNodeIndex;
        showNothing(pickPrompt());
    }

    /**
     * Hands over the per-action display names for the action graphs.
     *
     * <p>Composed once, off the event thread, by
     * {@code GraphQueries.displayLabelsByNodeIndex()}: "Mnemonic — output
     * basename", degrading honestly where pieces are absent. The canvas draws
     * these above the owning target label; the complete export keeps the target
     * labels from {@link #attach}, whose {@code label} column would otherwise
     * lie.
     */
    public void attachActionDisplayLabels(String[] displayLabelsByNodeIndex) {
        this.actionDisplayLabels = displayLabelsByNodeIndex;
    }

    /**
     * Hands over the configured-target label graph's per-node names.
     *
     * <p>Nothing times a label — a target's actions are timed, the label is
     * not — so its duration array is all unknown, and the legend says so
     * rather than colouring targets as uniformly fast.
     */
    public void attachLabelGraph(String[] labelsByNodeIndex) {
        this.labelGraphLabels = labelsByNodeIndex;
        long[] unknown = new long[labelsByNodeIndex == null ? 0 : labelsByNodeIndex.length];
        java.util.Arrays.fill(unknown, GraphModel.UNKNOWN_DURATION);
        this.labelGraphDurations = unknown;
    }

    /**
     * Switches which graph the canvas draws.
     *
     * <p>Node indexes do not translate between graphs — index 7 is an action
     * in one numbering and a label in the other — so the root is dropped
     * rather than reinterpreted, and the prompt asks for a new one.
     */
    public void setShownGraph(GraphKind graph) {
        if (graph == null || graph == shownGraph) {
            return;
        }
        this.shownGraph = graph;
        mode.repaint();
        updateExplanations();
        this.rootNode = -1;
        this.aggregatedAutomatically = false;
        // Node numbering is graph-specific. Remove the old model before the
        // asynchronous replacement starts so it cannot be clicked or exported
        // under the new source's name.
        showNothing("Loading the " + graph.displayName() + "…");
        GraphExtract.Mode chosen = (GraphExtract.Mode) mode.getSelectedItem();
        boolean rooted = chosen == GraphExtract.Mode.NEIGHBOURHOOD
                || chosen == GraphExtract.Mode.DEPENDENCIES
                || chosen == GraphExtract.Mode.DEPENDENTS;
        if (rooted || chosen == GraphExtract.Mode.PATH
                || chosen == GraphExtract.Mode.CRITICAL_PATH) {
            if (!rooted) {
                mode.setSelectedItem(GraphExtract.Mode.NEIGHBOURHOOD);
            }
            showNothing(pickPrompt());
            return;
        }
        refresh();
    }

    /** The graph currently on the canvas. */
    public GraphKind shownGraph() {
        return shownGraph;
    }

    private String noun() {
        return GraphLayoutService.nounFor(shownGraph);
    }

    private String pickPrompt() {
        return "Pick " + (shownGraph == GraphKind.CONFIGURED_TARGETS
                        ? "a target" : "an action")
                + " to draw its neighbourhood, or switch to the whole build."
                + " Double-click a node to refocus on it.";
    }

    private String[] currentLabels() {
        return shownGraph == GraphKind.CONFIGURED_TARGETS ? labelGraphLabels : actionLabels;
    }

    /**
     * What the canvas names nodes with: target labels for the label graph —
     * a label <em>is</em> the node there — and the per-action display names
     * for the action graphs, falling back to target labels when no display
     * array was attached rather than showing nothing.
     */
    private String[] currentDisplayLabels() {
        if (shownGraph == GraphKind.CONFIGURED_TARGETS) {
            return labelGraphLabels;
        }
        return actionDisplayLabels != null ? actionDisplayLabels : actionLabels;
    }

    private long[] currentDurations() {
        return shownGraph == GraphKind.CONFIGURED_TARGETS
                ? labelGraphDurations : actionDurations;
    }

    /** Lets go of the session. */
    public void detach() {
        this.service = null;
        this.actionLabels = null;
        this.actionDisplayLabels = null;
        this.actionDurations = null;
        this.labelGraphLabels = null;
        this.labelGraphDurations = null;
        this.shownGraph = GraphKind.DECLARED_ACTIONS;
        this.actionIdByNodeIndex = java.util.Map.of();
        this.rootNode = -1;
        this.aggregatedAutomatically = false;
        mode.setSelectedItem(GraphExtract.Mode.NEIGHBOURHOOD);
        canvas.setModel(GraphModel.empty());
        showNothing("No dependency graph is open.");
    }

    /**
     * Draws a path a search has already found.
     *
     * <p>Plan 13.5's "path between two actions", which the trees can state in
     * words but only the canvas can show the shape of.
     */
    public boolean showPath(java.util.List<Integer> nodes, GraphExtract.Mode pathMode) {
        if (service == null || nodes.isEmpty()) {
            return false;
        }
        try {
            // Check only the size here. The bounded copy and edge materialization
            // happen on GraphLayoutService's worker, never on the Swing EDT.
            GraphExtract.requirePathWithinLimits(
                    nodes.size(), pathMode, nodeLimit, edgeLimit);
        } catch (GraphExtract.PathLimitExceededException tooLarge) {
            canvas.setModel(GraphModel.empty());
            setText(description, tooLarge.getMessage());
            setText(omission, " ");
            overLimit.setVisible(false);
            return false;
        }
        mode.setSelectedItem(pathMode);
        setText(description, "Drawing…");
        long wanted = ++renderGeneration;
        service.submitPath(
                GraphLayoutService.Request.forPath(shownGraph, pathMode)
                        .withLimits(nodeLimit, edgeLimit),
                nodes,
                result -> rendered(result, wanted),
                failure -> failed(failure, wanted));
        return true;
    }

    /** Draws the neighbourhood of one graph node. */
    public void showNode(int nodeIndex) {
        this.rootNode = nodeIndex;
        if (mode.getSelectedItem() == GraphExtract.Mode.WHOLE
                || mode.getSelectedItem() == GraphExtract.Mode.CLUSTERS
                || mode.getSelectedItem() == GraphExtract.Mode.PATH
                || mode.getSelectedItem() == GraphExtract.Mode.CRITICAL_PATH) {
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
        // The spinner is the visible face of the same number; a bar that
        // raised the limit while the control still showed the old one would
        // make the control a lie. The guard stops the write echoing back
        // through the change listener as a second refresh.
        syncingLimitControl = true;
        try {
            if (mode.getSelectedItem() != GraphExtract.Mode.CLUSTERS) {
                nodeLimitControl.setValue(
                        Math.max(MIN_NODE_LIMIT, Math.min(MAX_NODE_LIMIT, nodes)));
            }
        } finally {
            syncingLimitControl = false;
        }
        refresh();
    }

    /** The spinner's half of {@link #raiseLimits}: a typed number is a request. */
    private void nodeLimitTyped() {
        if (syncingLimitControl) {
            return;
        }
        int wanted = (Integer) nodeLimitControl.getValue();
        if (mode.getSelectedItem() == GraphExtract.Mode.CLUSTERS) {
            if (wanted == clusterLimit) {
                return;
            }
            clusterLimit = wanted;
            refresh();
            return;
        }
        if (wanted == nodeLimit) {
            return;
        }
        this.nodeLimit = wanted;
        refresh();
    }

    /**
     * Recentres the drawing on the node at a layout position.
     *
     * <p>The missing half of exploration: before this, clicking selected a
     * node but the canvas kept drawing the old root's neighbourhood, so there
     * was no way to walk the graph by looking at it. Reached by double-click
     * and by the context menu's "Focus here".
     */
    void focusOnPosition(int position) {
        GraphModel model = canvas.model();
        if (position < 0 || position >= model.size()) {
            return;
        }
        if (model.isCluster()) {
            // A box is a summary, not a node; there is nothing to centre on.
            setText(description,
                    "Groups cannot be focused. Switch to a node view and pick one "
                            + noun() + ".");
            return;
        }
        showNode(model.nodeAt(position));
    }

    /**
     * A right-click menu on the canvas, so refocusing is discoverable.
     *
     * <p>The double-click does the same thing faster, but nothing on screen
     * advertises a double-click; a context menu is the affordance a user can
     * find by trying.
     */
    private void installFocusMenu() {
        canvas.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                maybeShow(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                maybeShow(event);
            }

            private void maybeShow(java.awt.event.MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                java.util.OptionalInt hit = canvas.positionAt(event.getX(), event.getY());
                if (hit.isEmpty() || canvas.model().isCluster()) {
                    return;
                }
                int position = hit.getAsInt();
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                javax.swing.JMenuItem focus = new javax.swing.JMenuItem(
                        "Focus here — redraw around "
                                + canvas.model().canvasLabelAt(position));
                PlainText.disableHtml(focus);
                focus.addActionListener(action -> focusOnPosition(position));
                menu.add(focus);
                javax.swing.JMenuItem resetPositions =
                        new javax.swing.JMenuItem("Reset positions");
                resetPositions.setEnabled(canvas.hasDragOffsets());
                resetPositions.addActionListener(action -> canvas.resetDragOffsets());
                menu.add(resetPositions);
                appendEntityActions(menu, position);
                menu.show(canvas, event.getX(), event.getY());
            }
        });
    }

    /** Adds shared actions for nodes carrying target labels. */
    public void installEntityActions(EntityActions actions) {
        this.entityActions = java.util.Objects.requireNonNull(actions, "actions");
    }

    private void appendEntityActions(javax.swing.JPopupMenu menu, int position) {
        EntityActions actions = entityActions;
        java.util.Optional<String> label = targetLabelAt(position);
        if (actions == null || label.isEmpty()) {
            return;
        }
        javax.swing.JPopupMenu shared = actions.popupFor(
                java.util.List.of(new EntityRef.TargetLabel(label.orElseThrow())),
                java.util.Set.of(EntityActions.Command.OPEN_IN_GRAPH));
        if (shared.getComponentCount() == 0) {
            return;
        }
        menu.addSeparator();
        while (shared.getComponentCount() > 0) {
            java.awt.Component item = shared.getComponent(0);
            shared.remove(0);
            menu.add(item);
        }
    }

    private java.util.Optional<String> targetLabelAt(int position) {
        GraphModel model = canvas.model();
        if (model.isCluster() || position < 0 || position >= model.size()) {
            return java.util.Optional.empty();
        }
        int node = model.nodeAt(position);
        String[] labels = currentLabels();
        if (labels == null || node < 0 || node >= labels.length
                || labels[node] == null || labels[node].isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(labels[node]);
    }

    public int nodeLimit() {
        return nodeLimit;
    }

    int clusterLimitForTesting() {
        return clusterLimit;
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
        boolean clustered = chosen == GraphExtract.Mode.CLUSTERS;
        boolean path = chosen == GraphExtract.Mode.PATH
                || chosen == GraphExtract.Mode.CRITICAL_PATH;
        depthSetting.setVisible(rooted);
        groupBySetting.setVisible(clustered);
        budgetSetting.setVisible(!path);
        layout.setEnabled(!path);
        budgetLabel.setText(clustered ? "Group budget:" : "Node budget:");
        budgetLabel.setToolTipText(PlainText.tooltip(clustered
                ? "The most groups this drawing may contain."
                : "The most nodes this drawing may contain."));
        syncingLimitControl = true;
        try {
            nodeLimitControl.setValue(clustered ? clusterLimit : nodeLimit);
        } finally {
            syncingLimitControl = false;
        }
        // A cluster box is a summary, not a node: it has no degree, no
        // transitive count and no output, so the selector goes quiet rather
        // than offering weights nothing could honour.
        weightChoice.setEnabled(!clustered);
        GraphLayout.Kind defaultLayout = GraphLayout.defaultFor(chosen);
        boolean layoutChanged = layout.getSelectedItem() != defaultLayout;
        layout.setSelectedItem(defaultLayout);
        updateExplanations();
        revalidate();
        // Changing the combo selection fires its refresh listener
        // synchronously. Refresh here only when that listener did not run.
        if (!layoutChanged) {
            refresh();
        }
    }

    private void edgeDisplayChanged() {
        GraphEdgeDisplay chosen = (GraphEdgeDisplay) edgeDisplay.getSelectedItem();
        GraphLayout.Kind chosenLayout = (GraphLayout.Kind) layout.getSelectedItem();
        if (chosenLayout == GraphLayout.Kind.HIERARCHY && chosen != null) {
            hierarchyEdgeDisplay = chosen;
        }
        canvas.setEdgeDisplay(
                chosenLayout == GraphLayout.Kind.HIERARCHY
                        ? hierarchyEdgeDisplay : GraphEdgeDisplay.ALL);
        updateExplanations();
        updateOmission();
    }

    private void updateExplanations() {
        GraphExtract.Mode chosenMode = (GraphExtract.Mode) mode.getSelectedItem();
        if (chosenMode != null) {
            String scopeText = scopeDescription(chosenMode);
            setText(scopeExplanation, scopeText);
            mode.setToolTipText(PlainText.tooltip(scopeText));
            mode.getAccessibleContext().setAccessibleDescription(scopeText);
        }
        GraphLayout.Kind chosenLayout = (GraphLayout.Kind) layout.getSelectedItem();
        boolean hierarchy = chosenLayout == GraphLayout.Kind.HIERARCHY;
        GraphEdgeDisplay wantedEdges = hierarchy
                ? hierarchyEdgeDisplay : GraphEdgeDisplay.ALL;
        if (edgeDisplay.getSelectedItem() != wantedEdges) {
            edgeDisplay.setSelectedItem(wantedEdges);
        }
        edgeDisplay.setEnabled(hierarchy);
        canvas.setEdgeDisplay(wantedEdges);
        GraphEdgeDisplay chosenEdges = (GraphEdgeDisplay) edgeDisplay.getSelectedItem();
        GraphWeight chosenWeight = selectedWeight();
        StringBuilder text = new StringBuilder();
        if (chosenLayout != null) {
            text.append(chosenLayout.description());
            layout.setToolTipText(PlainText.tooltip(chosenLayout.description()));
            layout.getAccessibleContext().setAccessibleDescription(chosenLayout.description());
        }
        if (chosenEdges != null) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append(chosenEdges.description());
            edgeDisplay.setToolTipText(PlainText.tooltip(chosenEdges.description()));
            edgeDisplay.getAccessibleContext().setAccessibleDescription(chosenEdges.description());
        }
        if (chosenMode != GraphExtract.Mode.CLUSTERS) {
            text.append("  Node size and colour encode ")
                    .append(chosenWeight.subject()).append('.');
            String weightText = "Node size and colour encode " + chosenWeight.subject() + ".";
            weightChoice.setToolTipText(PlainText.tooltip(weightText));
            weightChoice.getAccessibleContext().setAccessibleDescription(weightText);
        } else {
            String weightText = "Grouped summaries do not have a node size or colour encoding.";
            text.append("  ").append(weightText);
            weightChoice.setToolTipText(PlainText.tooltip(weightText));
            weightChoice.getAccessibleContext().setAccessibleDescription(weightText);
        }
        setText(appearanceExplanation, text.toString());
    }

    private String scopeDescription(GraphExtract.Mode chosen) {
        return switch (chosen) {
            case NEIGHBOURHOOD ->
                    "Shows dependencies and reverse dependencies around the selected " + noun()
                            + ".";
            case DEPENDENCIES -> "Shows what the selected " + noun() + " needs.";
            case DEPENDENTS -> "Shows what directly or indirectly needs the selected " + noun()
                    + ".";
            case WHOLE -> "Shows the complete imported graph when it fits the stated budget.";
            case CLUSTERS -> "Groups the complete graph without sampling or dropping nodes.";
            case PATH -> "Shows the path chosen in the Tree tab.";
            case CRITICAL_PATH -> "Shows the visualizer-computed dependency critical path.";
        };
    }

    /** The selected weight, straight from the control. */
    private GraphWeight selectedWeight() {
        GraphWeight chosen = (GraphWeight) weightChoice.getSelectedItem();
        return chosen == null ? GraphWeight.DURATION : chosen;
    }

    /**
     * Re-encodes the current drawing under the newly selected weight.
     *
     * <p>Restyle, never re-layout: the drawing's positions are
     * weight-independent, so the camera and the selection survive and the
     * layout cache is untouched.
     */
    private void weightChanged() {
        GraphModel model = canvas.model();
        if (model.size() == 0 || model.isCluster()) {
            return;
        }
        long wanted = ++weightGeneration;
        GraphWeight weight = selectedWeight();
        if (weight == GraphWeight.DURATION) {
            GraphLayoutService active = service;
            if (active != null) {
                active.prepare(
                        model::withDurationWeight,
                        styled -> applyRestyle(styled, model, wanted, active),
                        failure -> weightFailed(weight, wanted, failure));
            }
            return;
        }
        setText(selected, "Computing " + weight.subject() + "…");
        submitWeights(weight, model, wanted);
    }

    /** Asks the service for the weight's values over what is drawn. */
    private void submitWeights(GraphWeight weight, GraphModel model, long wanted) {
        GraphLayoutService active = service;
        if (active == null) {
            return;
        }
        active.weights(
                shownGraph, weight, model.extract(),
                set -> weightsArrived(set, model, wanted, active),
                failure -> weightFailed(weight, wanted, failure));
    }

    /** Applies computed weights, unless the drawing has moved on. */
    private void weightsArrived(
            GraphLayoutService.WeightSet set,
            GraphModel model,
            long wanted,
            GraphLayoutService active) {
        if (wanted != weightGeneration || active != service || canvas.model() != model) {
            return;
        }
        active.prepare(
                () -> model.withWeights(
                        set.weight(), set.valueByNode(), set.truncated(), set.note()),
                styled -> applyRestyle(styled, model, wanted, active),
                failure -> weightFailed(set.weight(), wanted, failure));
    }

    private void applyRestyle(
            GraphModel styled,
            GraphModel previous,
            long wanted,
            GraphLayoutService active) {
        if (wanted != weightGeneration || active != service || canvas.model() != previous) {
            return;
        }
        canvas.restyle(styled);
        selectionChanged(canvas.selectedPositions());
    }

    private void weightFailed(GraphWeight weight, long wanted, Throwable failure) {
        if (wanted == weightGeneration) {
            setText(selected, "The " + weight.subject()
                    + " could not be computed: " + failure.getMessage());
        }
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
            showNothing(pickPrompt());
            return;
        }

        GraphLayoutService.Request request = switch (chosen) {
            case CLUSTERS -> GraphLayoutService.Request.clustered(
                    shownGraph, (GraphClustering.By) groupBy.getSelectedItem())
                    .withClusterLimit(clusterLimit);
            case WHOLE -> GraphLayoutService.Request.whole(
                    shownGraph, nodeLimit, edgeLimit);
            default -> GraphLayoutService.Request.around(
                    shownGraph, chosen,
                    Math.max(0, rootNode), (Integer) depth.getValue());
        };
        request = request
                .withLayout((GraphLayout.Kind) layout.getSelectedItem())
                .withLimits(nodeLimit, edgeLimit);

        weightGeneration++;
        exportGeneration++;
        setText(description, "Drawing…");
        long wanted = ++renderGeneration;
        service.estimate(
                request,
                found -> {
                    if (wanted == renderGeneration) {
                        this.estimate = found;
                    }
                },
                failure -> { });
        service.submit(
                request,
                result -> rendered(result, wanted),
                failure -> failed(failure, wanted));
    }

    private void rendered(GraphLayoutService.Rendered result, long wanted) {
        if (wanted != renderGeneration) {
            return;
        }
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

        GraphLayoutService active = service;
        if (active == null) {
            return;
        }
        String[] displayLabels = currentDisplayLabels();
        String[] ownerLabels = currentLabels();
        long[] durations = currentDurations();
        active.prepare(
                () -> GraphModel.of(result, displayLabels, ownerLabels, durations),
                model -> preparedRendering(result, model, wanted, active),
                failure -> failed(failure, wanted));
    }

    private void preparedRendering(
            GraphLayoutService.Rendered result,
            GraphModel model,
            long wanted,
            GraphLayoutService active) {
        if (wanted != renderGeneration || active != service) {
            return;
        }
        if (aggregatedAutomatically && result.isCluster() && result.refused()) {
            // Automatic grouping was attempted, but the grouping itself did
            // not fit. Treat this as the cluster refusal it is so the bar
            // raises the Group budget rather than the unrelated Node budget.
            aggregatedAutomatically = false;
        }
        long weightWanted = ++weightGeneration;
        canvas.setModel(model);
        setText(description, aggregatedAutomatically
                ? "Too big to draw " + noun() + " by " + noun() + ", so it is grouped. "
                        + result.description()
                : result.description());
        updateOmission();
        setText(selected, legend());
        setOverLimit(result.refused() || result.extract().hitLimit()
                || aggregatedAutomatically, result);
        // The drawing goes up immediately in the duration encoding; a
        // non-default weight restyles it when its values arrive, so a slow
        // computation delays the colours, never the graph.
        GraphWeight weight = selectedWeight();
        if (weight != GraphWeight.DURATION && !model.isCluster() && model.size() > 0) {
            submitWeights(weight, model, weightWanted);
        }
    }

    private void setOverLimit(boolean over, GraphLayoutService.Rendered result) {
        overLimit.setVisible(over);
        if (!over) {
            return;
        }
        boolean clustered = result.isCluster() && !aggregatedAutomatically;
        showClusters.setVisible(!result.isCluster());
        refine.setText(clustered ? "Try another grouping" : "Narrow it");
        if (aggregatedAutomatically) {
            setText(overLimitText, "Grouped because the whole build is too big to draw.");
        } else if (clustered) {
            setText(overLimitText, "Too many groups to draw at this group budget.");
        } else {
            setText(overLimitText, result.refused()
                    ? "Too big to draw in detail."
                    : "Stopped early; there is more than this.");
        }
        // "Draw it anyway" only means something when the exact size is known,
        // which is the whole-graph case. For a traversal that stopped at its
        // budget, doubling the budget is the honest offer.
        if (clustered && result.clustering() != null) {
            raiseLimit.setToolTipText(PlainText.tooltip(
                    "Raise the group budget to " + result.clustering().clusterCount()
                            + " and draw every group."));
        } else {
            raiseLimit.setToolTipText(PlainText.tooltip(result.refused()
                    ? "Raise the limit to " + result.extract().totalNodes()
                            + " " + noun() + "s and draw all of it."
                    : "Double the search budget and look further."));
        }
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
        if (aggregatedAutomatically) {
            if (rootNode < 0) {
                setText(description,
                        "Pick an action first — narrowing means drawing the graph around one.");
            } else {
                mode.setSelectedItem(GraphExtract.Mode.NEIGHBOURHOOD);
            }
            return;
        }
        if (chosen == GraphExtract.Mode.CLUSTERS) {
            GraphClustering.By grouping = (GraphClustering.By) groupBy.getSelectedItem();
            // Package and mnemonic counts are independent, so neither may be
            // called universally coarser. Try the next explicit dimension and
            // report its exact count if it also refuses.
            GraphClustering.By next = switch (grouping) {
                case TARGET -> GraphClustering.By.PACKAGE;
                case PACKAGE -> GraphClustering.By.MNEMONIC;
                case MNEMONIC -> GraphClustering.By.TARGET;
            };
            groupBy.setSelectedItem(next);
            return;
        }
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
        if (aggregatedAutomatically) {
            long wantedNodes = Math.max(model.extract().totalNodes(), nodeLimit * 2L);
            long wantedEdges = Math.max(model.extract().totalEdges(), edgeLimit * 2L);
            this.nodeLimit = (int) Math.min(Integer.MAX_VALUE, wantedNodes);
            this.edgeLimit = (int) Math.min(Integer.MAX_VALUE, wantedEdges);
            aggregatedAutomatically = false;
            mode.setSelectedItem(GraphExtract.Mode.WHOLE);
            return;
        }
        if (model.isCluster() && model.clustering() != null) {
            clusterLimit = (int) Math.min(
                    MAX_NODE_LIMIT,
                    Math.max(model.clustering().clusterCount(), clusterLimit * 2L));
            syncingLimitControl = true;
            try {
                nodeLimitControl.setValue(Math.min(MAX_NODE_LIMIT, clusterLimit));
            } finally {
                syncingLimitControl = false;
            }
            refresh();
            return;
        }
        long wantedNodes = Math.max(model.extract().totalNodes(), nodeLimit * 2L);
        long wantedEdges = Math.max(model.extract().totalEdges(), edgeLimit * 2L);
        raiseLimits(
                (int) Math.min(Integer.MAX_VALUE, wantedNodes),
                (int) Math.min(Integer.MAX_VALUE, wantedEdges));
    }

    /**
     * Called with the executed action behind a selected node, when there is one.
     *
     * <p>The return leg of {@code GraphExplorerView.showAction}: a user who found an
     * action in the table can draw its neighbourhood, and a user who found one
     * in the drawing can open its detail. Nodes with no executed action — every
     * test's TestRunner in a {@code build} invocation — simply do not fire it,
     * rather than firing a zero that would open the wrong row.
     */
    public void onActionSelected(java.util.function.LongConsumer listener) {
        this.actionListener = listener == null ? actionId -> {} : listener;
    }

    /** The executed action behind a drawn node, when the session ran one. */
    java.util.OptionalLong actionIdAt(int position) {
        if (canvas.model().isCluster() || position < 0
                || position >= canvas.model().size()
                // A label is not an action; index 7 in the label numbering
                // must not open action 7's detail.
                || shownGraph == GraphKind.CONFIGURED_TARGETS) {
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
        if (model.size() == 0) {
            setText(selected, "Nothing is drawn to export yet.");
            return;
        }
        GraphLayoutService active = service;
        if (active == null) {
            return;
        }
        long wanted = ++exportGeneration;
        active.onGraph(
                shownGraph,
                graph -> GraphExport.visible(model, target, format),
                result -> exported(result, wanted, active),
                failure -> exportFailed(failure, wanted, active));
    }

    /**
     * Writes every node and edge the session holds, drawn or not.
     *
     * <p>Plan 13.6 lists export among the three things offered above the limit,
     * and this is the one that makes the limit acceptable: the drawing is
     * bounded, the data is not.
     */
    public void exportComplete(java.nio.file.Path target, GraphExport.Format format) {
        GraphLayoutService active = service;
        if (active == null) {
            return;
        }
        String[] labels = currentLabels();
        long[] durations = currentDurations();
        String nodeNoun = noun();
        long wanted = ++exportGeneration;
        active.onGraph(
                shownGraph,
                graph -> GraphExport.whole(
                        graph, labels, durations, target, format, nodeNoun),
                result -> exported(result, wanted, active),
                failure -> exportFailed(failure, wanted, active));
    }

    private void exported(
            GraphExport.Result result, long wanted, GraphLayoutService active) {
        if (wanted == exportGeneration && active == service) {
            setText(selected, result.describe());
        }
    }

    private void exportFailed(
            Throwable failure, long wanted, GraphLayoutService active) {
        if (wanted == exportGeneration && active == service) {
            setText(selected, "The export failed: " + failure.getMessage());
        }
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
                "Export the current extracted node set (including paint-hidden links),"
                        + " or everything the session holds?",
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

    /** The node-limit spinner, for tests that type into it. */
    JSpinner nodeLimitControlForTesting() {
        return nodeLimitControl;
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

    String refineTextForTesting() {
        return refine.getText();
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

    void setGroupByForTesting(GraphClustering.By value) {
        groupBy.setSelectedItem(value);
    }

    /** Picks a weight exactly as the selector would, for tests. */
    void setWeightForTesting(GraphWeight value) {
        weightChoice.setSelectedItem(value);
    }

    void setLayoutForTesting(GraphLayout.Kind value) {
        layout.setSelectedItem(value);
    }

    GraphLayout.Kind layoutForTesting() {
        return (GraphLayout.Kind) layout.getSelectedItem();
    }

    void setEdgeDisplayForTesting(GraphEdgeDisplay value) {
        edgeDisplay.setSelectedItem(value);
    }

    GraphEdgeDisplay edgeDisplayForTesting() {
        return (GraphEdgeDisplay) edgeDisplay.getSelectedItem();
    }

    boolean edgeDisplayEnabledForTesting() {
        return edgeDisplay.isEnabled();
    }

    String appearanceExplanationForTesting() {
        return appearanceExplanation.getText();
    }

    String scopeExplanationForTesting() {
        return scopeExplanation.getText();
    }

    GraphWeight weightForTesting() {
        return selectedWeight();
    }

    private void failed(Throwable failure, long wanted) {
        if (wanted != renderGeneration) {
            return;
        }
        canvas.setModel(GraphModel.empty());
        setText(description, "The graph could not be drawn: " + failure.getMessage());
        setText(omission, " ");
        overLimit.setVisible(false);
    }

    private void updateOmission() {
        setText(omission, canvas.hiddenDetail().orElse(" "));
    }

    private void selectionChanged(int[] positions) {
        updateOmission();
        if (positions.length == 0) {
            setText(selected, legend());
            return;
        }
        if (positions.length == 1) {
            GraphModel model = canvas.model();
            actionIdAt(positions[0]).ifPresent(actionListener::accept);
            setText(selected, describeSelection(model, positions[0]));
            if (model.weight().isTransitive() && !model.isCluster()) {
                appendGlobalCount(model, positions[0]);
            }
            return;
        }
        setText(selected, positions.length + " " + noun() + "s selected");
    }

    /** One selected node, in the words of the selected weight. */
    private String describeSelection(GraphModel model, int position) {
        String text = model.canvasLabelAt(position);
        GraphWeight weight = model.weight();
        if (weight == GraphWeight.DURATION) {
            return model.durationAt(position)
                    .stream()
                    .mapToObj(micros -> text + "  —  " + micros / 1_000 + " ms")
                    .findFirst()
                    // Rule 11: an action nothing timed says so rather than
                    // showing a zero that reads as instant.
                    .orElse(text + "  —  not timed in this session");
        }
        return model.weightAt(position)
                .stream()
                .mapToObj(value -> text + "  —  " + weight.subject()
                        + ": " + weight.format(value))
                .findFirst()
                // Same rule, same reason: no value is not a value of zero.
                .orElse(text + "  —  " + weight.subject() + " not recorded");
    }

    /**
     * Adds the whole-graph transitive count for the selected node, budgeted.
     *
     * <p>The on-screen count is exact for what is drawn and silent about the
     * rest; this is the rest, for one node at a time, and it says
     * "≥N (budget reached)" when the traversal gave up — a full transitive
     * closure is forbidden, so giving up must remain possible and visible.
     */
    private void appendGlobalCount(GraphModel model, int position) {
        if (service == null) {
            return;
        }
        long wanted = weightGeneration;
        int node = model.nodeAt(position);
        GraphWeight weight = model.weight();
        service.globalTransitiveCount(
                shownGraph, node, weight.countsForwards(),
                counted -> {
                    int[] now = canvas.selectedPositions();
                    if (wanted != weightGeneration
                            || now.length != 1 || now[0] != position) {
                        return;
                    }
                    setText(selected, describeSelection(canvas.model(), position)
                            + "  —  whole graph: " + counted.describe());
                });
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
                    + clustering.clusteredNodes() + " " + noun() + "s and "
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
        if (model.weight() != GraphWeight.DURATION) {
            return weightLegend(model);
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

    /** The legend for a non-duration weight, absences and budgets included. */
    private String weightLegend(GraphModel model) {
        GraphWeight weight = model.weight();
        StringBuilder text = new StringBuilder();
        java.util.OptionalLong max = model.maxWeight();
        if (max.isPresent()) {
            text.append("Colour and size are ").append(weight.subject())
                    .append(", blue to red, up to ")
                    .append(weight.format(max.getAsLong())).append('.');
            int unweighted = model.unweightedCount();
            if (unweighted > 0) {
                text.append("  ").append(unweighted).append(" of ").append(model.size())
                        .append(" have no recorded value and are grey.");
            }
        } else {
            // Rule 11 again: a drawing with no values is not a drawing of
            // zeros, and the legend is where that distinction lives.
            text.append("Nothing here has a recorded ").append(weight.subject())
                    .append(", so nothing is coloured or sized by it.");
        }
        if (!model.weightNote().isBlank()) {
            text.append("  ").append(model.weightNote());
        }
        return text.toString();
    }

    private void showNothing(String why) {
        renderGeneration++;
        weightGeneration++;
        exportGeneration++;
        if (service != null) {
            service.cancel();
        }
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

    private static void setText(javax.swing.JTextArea label, String text) {
        label.setText(text == null || text.isBlank() ? " " : text);
        label.setToolTipText(PlainText.tooltip(label.getText()));
    }

    private static JPanel labeledSetting(String text, javax.swing.JComponent control, String name) {
        JPanel setting = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        JLabel label = new JLabel(text + ":");
        label.setLabelFor(control);
        label.setName(name);
        PlainText.disableHtml(label);
        setting.add(label);
        setting.add(control);
        return setting;
    }

    private static javax.swing.JTextArea explanationArea() {
        javax.swing.JTextArea area = new javax.swing.JTextArea(1, 20);
        area.setEditable(false);
        area.setFocusable(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(1, 8, 3, 8));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(javax.swing.UIManager.getFont("Label.font"));
        area.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        return area;
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

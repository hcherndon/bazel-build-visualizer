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

    private GraphLayoutService service;
    private String[] labelsByNodeIndex;
    private long[] durationsByNodeIndex;
    private int rootNode = -1;
    private int nodeLimit = GraphExtract.DEFAULT_NODE_LIMIT;
    private int edgeLimit = GraphExtract.DEFAULT_EDGE_LIMIT;

    public GraphCanvasPanel() {
        super(new BorderLayout());
        PlainText.disableHtml(description);
        PlainText.disableHtml(omission);
        PlainText.disableHtml(selected);
        omission.setFont(omission.getFont().deriveFont(Font.ITALIC));

        mode.setModel(new DefaultComboBoxModel<>(new GraphExtract.Mode[] {
            GraphExtract.Mode.NEIGHBOURHOOD, GraphExtract.Mode.DEPENDENCIES,
            GraphExtract.Mode.DEPENDENTS, GraphExtract.Mode.WHOLE, GraphExtract.Mode.CLUSTERS,
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
        controls.add(Box.createHorizontalGlue());

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        status.add(description);
        status.add(omission);
        status.add(selected);

        add(controls, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        canvas.onSelectionChanged(this::selectionChanged);
        canvas.onViewChanged(this::updateOmission);
        modeChanged();
        showNothing("Open a session with a dependency graph to draw it.");
    }

    /** The canvas, for tests and for a container that wants to drive it. */
    public GraphCanvas canvas() {
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
            GraphLayoutService service, String[] labelsByNodeIndex, long[] durationsByNodeIndex) {
        this.service = service;
        this.labelsByNodeIndex = labelsByNodeIndex;
        this.durationsByNodeIndex = durationsByNodeIndex;
        showNothing("Pick an action to draw its neighbourhood, or switch to the whole build.");
    }

    /** Lets go of the session. */
    public void detach() {
        this.service = null;
        this.labelsByNodeIndex = null;
        this.durationsByNodeIndex = null;
        this.rootNode = -1;
        canvas.setModel(GraphModel.empty());
        showNothing("No dependency graph is open.");
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
        boolean rooted = chosen == GraphExtract.Mode.NEIGHBOURHOOD
                || chosen == GraphExtract.Mode.DEPENDENCIES
                || chosen == GraphExtract.Mode.DEPENDENTS;
        if (rooted && rootNode < 0) {
            showNothing("Pick an action to draw its neighbourhood.");
            return;
        }

        GraphLayoutService.Request request = new GraphLayoutService.Request(
                EdgeDerivation.DECLARED,
                chosen,
                Math.max(0, rootNode),
                (Integer) depth.getValue(),
                nodeLimit,
                edgeLimit,
                (GraphLayout.Kind) layout.getSelectedItem(),
                (GraphClustering.By) groupBy.getSelectedItem(),
                GraphClustering.DEFAULT_CLUSTER_LIMIT);

        description.setText("Drawing…");
        service.submit(request, this::rendered, this::failed);
    }

    private void rendered(GraphLayoutService.Rendered result) {
        canvas.setModel(GraphModel.of(result, labelsByNodeIndex, durationsByNodeIndex));
        setText(description, result.description());
        updateOmission();
        setText(selected, legend());
    }

    private void failed(Throwable failure) {
        canvas.setModel(GraphModel.empty());
        setText(description, "The graph could not be drawn: " + failure.getMessage());
        setText(omission, " ");
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
            return "Boxes are groups; the number in each is how many actions it holds.";
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

package com.holtherndon.bazelviz.ui.metrics;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.MetricFormat;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Findings card: what the rules found, what each one rests on, and where to
 * go and look.
 *
 * <h2>The coverage banner is not decoration</h2>
 *
 * <p>Every finding is a statement about the actions some source described, and
 * the header says which sources described how much before any finding is read.
 * A cache-miss finding over a build whose cache state was 12% covered is a
 * different claim from the same finding at 99%, and the two are indistinguishable
 * without the number on the same screen (plan 15.5).
 *
 * <h2>Both critical paths, always both</h2>
 *
 * <p>Plan 24 requires Bazel's and the derived path to remain distinct, so the
 * header shows them as two rows with their own names and never as one figure.
 * When only one exists the other says why it does not, rather than the panel
 * quietly showing whichever it has under the unqualified word.
 *
 * <h2>Nothing here reaches a database</h2>
 *
 * <p>The view renders a {@link MetricsService.Result} it is handed. The service
 * owns the reading, on its own thread; this class has no connection, no
 * executor and no query (rule 8, rule 19).
 */
public final class FindingsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(FindingsView.class);

    private final DefaultListModel<Finding> model = new DefaultListModel<>();
    private final JList<Finding> list = new JList<>(model);
    private final JPanel detail = new JPanel();
    private final JPanel summary = new JPanel(new GridLayout(0, 2, 12, 4));
    private final JLabel headline = new JLabel(" ");
    private final JButton recompute = new JButton("Recompute");
    private final JLabel empty = PlainText.disableHtml(
            new JLabel("No session is open.", SwingConstants.CENTER));

    private MetricsService service;
    private LongConsumer onActionSelected = actionId -> { };
    private Consumer<Finding.Link> onNavigate = link -> { };

    public FindingsView() {
        super(new BorderLayout());

        PlainText.disableHtml(headline);
        PlainText.disableHtml(empty);
        headline.setFont(headline.getFont().deriveFont(
                Font.BOLD, headline.getFont().getSize() + 3f));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new FindingRenderer());
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showDetail(list.getSelectedValue());
            }
        });

        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        summary.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        header.add(headline, BorderLayout.CENTER);
        header.add(recompute, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(summary, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(detail));
        split.setDividerLocation(360);
        split.setResizeWeight(0.35);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        recompute.addActionListener(event -> refresh());
        recompute.setEnabled(false);
        showEmpty();
    }

    /** Where an evidence row sends the reader. */
    public void onActionSelected(LongConsumer listener) {
        this.onActionSelected = Objects.requireNonNull(listener, "listener");
    }

    /** Where a finding's links send the reader. */
    public void onNavigate(Consumer<Finding.Link> listener) {
        this.onNavigate = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Attaches the window's metric service and asks it for a first collection.
     *
     * <p>The service is not created here and is not closed here. One collection
     * feeds this view and the overview's cards, so a session that opened both
     * scans its actions once rather than twice.
     */
    public void attach(MetricsService service) {
        this.service = Objects.requireNonNull(service, "service");
        recompute.setEnabled(true);
        refresh();
    }

    /** Lets go of the service without closing it; the window owns it. */
    public void detach() {
        service = null;
        recompute.setEnabled(false);
        model.clear();
        detail.removeAll();
        summary.removeAll();
        showEmpty();
    }

    private void refresh() {
        MetricsService running = service;
        if (running == null) {
            return;
        }
        headline.setText("Reading the build…");
        recompute.setEnabled(false);
        running.collect(
                result -> {
                    recompute.setEnabled(true);
                    show(result);
                },
                failure -> {
                    recompute.setEnabled(true);
                    log.error("could not collect metrics", failure);
                    headline.setText("Could not read the metrics: " + failure.getMessage());
                });
    }

    /** Renders a collection. On the EDT; visible so a test can drive it. */
    public void show(MetricsService.Result result) {
        Objects.requireNonNull(result, "result");
        model.clear();
        for (Finding finding : result.findings()) {
            model.addElement(finding);
        }
        headline.setText(result.findings().isEmpty()
                ? "No findings. Every rule ran and none of them matched."
                : result.findings().size() + (result.findings().size() == 1
                        ? " finding" : " findings"));

        summary.removeAll();
        for (String[] row : summaryRows(result)) {
            summary.add(label(row[0], true));
            summary.add(label(row[1], false));
        }

        detail.removeAll();
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        } else {
            detail.add(label("Nothing matched. The coverage figures above say how much of the"
                    + " build the rules could see.", false));
        }
        revalidate();
        repaint();
    }

    /** The header rows, in the order plan 15.5 asks for them. */
    private static List<String[]> summaryRows(MetricsService.Result result) {
        var invocation = result.metrics().invocation();
        List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[] {"Duration source",
                result.metrics().durationSource().description()});

        CriticalPaths paths = invocation.criticalPaths();
        rows.add(new String[] {paths.bazelDisplayName(),
                paths.bazelReportedMicros().value()
                        .map(MetricFormat::duration)
                        .orElse(MetricFormat.UNKNOWN + " — "
                                + paths.bazelReportedMicros().warning().orElse("no source"))});
        rows.add(new String[] {paths.derivedDisplayName(),
                paths.derived()
                        .map(derived -> MetricFormat.duration(derived.makespanMicros())
                                + (derived.isPartial() ? " (a lower bound)" : ""))
                        .orElse(MetricFormat.UNKNOWN
                                + " — no imported action graph to compute it over")});
        paths.schedulingGapMicros().ifPresent(gap -> rows.add(new String[] {
            "Difference between them",
            MetricFormat.duration(Math.abs(gap))
                    + " — they measure different things, so a difference is expected"}));

        Optional<ConcurrencySweep.Result> sweep = invocation.concurrency();
        rows.add(new String[] {"Concurrency",
                sweep.map(ConcurrencySweep.Result::describe).orElse(MetricFormat.UNKNOWN)});
        for (Coverage coverage : invocation.coverage().entries()) {
            rows.add(new String[] {coverage.name(), coverage.describe()
                    .substring(coverage.name().length() + 2)});
        }
        return rows;
    }

    private void showDetail(Finding finding) {
        detail.removeAll();
        if (finding == null) {
            revalidate();
            repaint();
            return;
        }
        detail.add(heading(finding.title()));
        detail.add(label("Severity " + finding.severity().displayName()
                + " · confidence " + finding.confidence().displayName()
                + " · rule " + finding.ruleId(), false));
        detail.add(Box.createVerticalStrut(10));

        detail.add(heading("Why it may matter"));
        detail.add(wrapped(finding.whyItMayMatter()));
        detail.add(Box.createVerticalStrut(8));

        detail.add(heading("Threshold used"));
        detail.add(wrapped(finding.thresholdUsed()));
        detail.add(Box.createVerticalStrut(8));

        if (!finding.metrics().isEmpty()) {
            detail.add(heading("Metrics"));
            for (Finding.MetricValue metric : finding.metrics()) {
                detail.add(label(metric.name() + ": " + metric.value(), true));
                detail.add(wrapped("Source — " + metric.source()));
            }
            detail.add(Box.createVerticalStrut(8));
        }

        detail.add(heading("Evidence"));
        for (Finding.Evidence evidence : finding.evidence()) {
            if (evidence.kind() == Finding.Evidence.Kind.ACTION && evidence.id().isPresent()) {
                long actionId = evidence.id().getAsLong();
                JButton open = new JButton(evidence.label() + " — " + evidence.detail());
                open.setHorizontalAlignment(SwingConstants.LEFT);
                open.setAlignmentX(LEFT_ALIGNMENT);
                open.addActionListener(event -> onActionSelected.accept(actionId));
                detail.add(open);
            } else {
                detail.add(wrapped(evidence.label() + " — " + evidence.detail()));
            }
        }
        detail.add(Box.createVerticalStrut(8));

        detail.add(heading("Caveats"));
        detail.add(wrapped(finding.caveats()));
        detail.add(Box.createVerticalStrut(8));

        detail.add(heading("Suggested next investigation"));
        detail.add(wrapped(finding.suggestedInvestigation()));

        if (!finding.links().isEmpty()) {
            detail.add(Box.createVerticalStrut(10));
            detail.add(heading("Where to look"));
            for (Finding.Link link : finding.links()) {
                JButton go = new JButton(link.description());
                go.setHorizontalAlignment(SwingConstants.LEFT);
                go.setAlignmentX(LEFT_ALIGNMENT);
                go.addActionListener(event -> onNavigate.accept(link));
                detail.add(go);
            }
        }
        revalidate();
        repaint();
    }

    private void showEmpty() {
        headline.setText(" ");
        detail.removeAll();
        detail.add(empty);
        revalidate();
        repaint();
    }

    private static JLabel heading(String text) {
        JLabel label = label(text, true);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel label(String text, boolean bold) {
        JLabel label = PlainText.disableHtml(new JLabel(text));
        label.setAlignmentX(LEFT_ALIGNMENT);
        if (bold) {
            label.setFont(label.getFont().deriveFont(Font.BOLD));
        }
        return label;
    }

    /**
     * A read-only, wrapping text area for a sentence.
     *
     * <p>A {@link JLabel} would need HTML to wrap, and the text here contains
     * build labels that came out of the session — exactly the untrusted strings
     * {@link PlainText} exists to keep out of an HTML renderer.
     */
    private static Component wrapped(String text) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        area.setAlignmentX(LEFT_ALIGNMENT);
        area.setFocusable(false);
        return area;
    }

    /** Severity first, so the list reads worst-first without a sort control. */
    private static final class FindingRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof Finding finding) {
                setText(finding.summary());
                setToolTipText(PlainText.tooltip(finding.whyItMayMatter()));
            }
            return this;
        }
    }

    // --- test hooks -------------------------------------------------------

    /** The titles on screen, worst first. */
    public List<String> findingTitlesForTest() {
        List<String> titles = new java.util.ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) {
            titles.add(model.get(i).title());
        }
        return titles;
    }

    /** The header line. */
    public String headlineForTest() {
        return headline.getText();
    }

    /** Every label in the summary grid, joined. */
    public String summaryTextForTest() {
        StringBuilder text = new StringBuilder();
        for (Component component : summary.getComponents()) {
            if (component instanceof JLabel label) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    /** Every string in the detail pane, joined. */
    public String detailTextForTest() {
        StringBuilder text = new StringBuilder();
        collectText(detail, text);
        return text.toString();
    }

    private static void collectText(Component component, StringBuilder into) {
        if (component instanceof JLabel label) {
            into.append(label.getText()).append('\n');
        } else if (component instanceof javax.swing.JTextArea area) {
            into.append(area.getText()).append('\n');
        } else if (component instanceof javax.swing.AbstractButton button) {
            into.append(button.getText()).append('\n');
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectText(child, into);
            }
        }
    }

    /** Selects a finding by index, as a click would. */
    public void selectForTest(int index) {
        list.setSelectedIndex(index);
    }
}

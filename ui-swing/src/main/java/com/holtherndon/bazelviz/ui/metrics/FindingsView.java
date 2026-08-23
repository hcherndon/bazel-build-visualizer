package com.holtherndon.bazelviz.ui.metrics;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.MetricSeries;
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
    private final JPanel catalog = new JPanel();
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
        catalog.setLayout(new BoxLayout(catalog, BoxLayout.Y_AXIS));
        catalog.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        header.add(headline, BorderLayout.CENTER);
        header.add(recompute, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        header.setAlignmentX(LEFT_ALIGNMENT);
        summary.setAlignmentX(LEFT_ALIGNMENT);
        catalog.setAlignmentX(LEFT_ALIGNMENT);
        top.add(header);
        top.add(summary);
        top.add(catalog);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(detail));
        split.setDividerLocation(360);
        split.setResizeWeight(0.35);

        JSplitPane page = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, new JScrollPane(top), split);
        page.setDividerLocation(300);
        page.setResizeWeight(0.35);
        add(page, BorderLayout.CENTER);

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

        catalog.removeAll();
        catalog.add(section("Invocation metrics", invocationRows(result)));
        catalog.add(Box.createVerticalStrut(10));
        result.metrics().aggregate(GroupAggregate.Dimension.MNEMONIC)
                .ifPresent(table -> catalog.add(section(table.describe(), mnemonicRows(table))));

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

    /**
     * Plan 15.2's invocation metrics, each beside what it could not count.
     *
     * <p>These are not on the overview's two-second timer and are not repeated
     * there: the overview counts rows, this reads the catalog. Every entry that
     * can be unavailable says so in words rather than as a zero (rule 11).
     */
    private static List<String[]> invocationRows(MetricsService.Result result) {
        var invocation = result.metrics().invocation();
        var timing = invocation.timing();
        var work = invocation.work();
        var bytes = invocation.bytes();
        var tests = invocation.tests();
        var ingest = invocation.ingest();
        List<String[]> rows = new java.util.ArrayList<>();

        rows.add(new String[] {"Wall time",
                MetricFormat.duration(toOptional(timing.totalWallMicros()))});
        rows.add(new String[] {"Time to the first event received",
                MetricFormat.duration(toOptional(timing.timeToFirstEventMicros()))});
        for (var phase : timing.phases()) {
            rows.add(new String[] {"Phase: " + phase.name(),
                    MetricFormat.duration(phase.durationMicros())
                            + (phase.endIsDerived()
                                    ? " (end derived: the profile records when a phase began and"
                                            + " never when it ended)"
                                    : "")});
        }

        rows.add(new String[] {"Actions", MetricFormat.count(work.actions())
                + " — " + work.actionsSucceeded() + " succeeded, " + work.actionsFailed()
                + " failed, " + work.actionsOtherOutcome() + " neither"});
        rows.add(new String[] {"Attempts", MetricFormat.count(work.attempts())
                + " — " + MetricFormat.ratio(work.attemptsPerAction()) + " per action, which is"
                + " below one because most actions never spawn a subprocess"});
        rows.add(new String[] {"Cache state", work.cacheHits() + " hit, " + work.cacheMisses()
                + " missed, " + work.cacheStateUnknown() + " not reported — hit rate "
                + MetricFormat.percent(work.cacheHitRate()) + " over the ones that reported"});
        rows.add(new String[] {"Runners", work.runners().isEmpty()
                ? MetricFormat.UNKNOWN + " — no execution log"
                : work.runners().stream()
                        .map(runner -> runner.runner() + " ×" + runner.actions())
                        .collect(java.util.stream.Collectors.joining(", "))});

        rows.add(new String[] {"Known input bytes",
                MetricFormat.bytes(toOptional(bytes.knownInputBytes()))
                        + ", " + bytes.actionsWithoutInputBytes() + " actions reported none"});
        rows.add(new String[] {"Known output bytes",
                MetricFormat.bytes(toOptional(bytes.knownOutputBytes()))
                        + ", " + bytes.artifactsWithoutSize() + " artifacts have no recorded size"
                        + (bytes.isPartial() ? " — both totals are lower bounds" : "")});

        invocation.concurrency().ifPresent(sweep -> {
            rows.add(new String[] {"Peak concurrency", MetricFormat.count(sweep.peakActive())
                    + ", first reached "
                    + MetricFormat.duration(
                            sweep.peakFirstSeenMicros() - sweep.windowStartMicros())
                    + " into the observed span"});
            rows.add(new String[] {"Average concurrency",
                    MetricFormat.ratio(sweep.averageActive()) + " across the whole window, "
                            + MetricFormat.ratio(sweep.averageActiveWhileBusy())
                            + " while something was running"});
            rows.add(new String[] {"Parallelism factor",
                    MetricFormat.ratio(sweep.parallelismFactor())
                            + " — an aggregate concurrency indicator, not CPU utilization"});
            rows.add(new String[] {"Time with nothing running",
                    MetricFormat.duration(sweep.idleMicros()) + " of "
                            + MetricFormat.duration(sweep.windowMicros())});
            rows.add(new String[] {"Observed work",
                    MetricFormat.duration(sweep.totalSpanMicros()) + " across "
                            + MetricFormat.count(sweep.sweptSpans()) + " spans"
                            + (sweep.instantaneousSpans() > 0
                                    ? ", plus " + sweep.instantaneousSpans()
                                            + " that start and end at the same instant"
                                    : "")
                            + (sweep.untimedSpans() > 0
                                    ? ", with " + sweep.untimedSpans() + " actions untimed"
                                    : "")});
        });

        rows.add(new String[] {"Tests", MetricFormat.count(tests.total()) + " — "
                + tests.failed() + " not passing, " + tests.flaky() + " flaky, "
                + tests.cached() + " with a cached attempt"});
        rows.add(new String[] {"Events", MetricFormat.count(ingest.rawEvents())
                + " — " + ingest.undecodableEvents() + " could not be decoded, "
                + ingest.notAttemptedEvents() + " were not decoded by choice"});
        rows.add(new String[] {"Ingestion lag",
                MetricFormat.duration(toOptional(ingest.meanIngestLagMicros()))
                        + " on average — a measure of this application, not of Bazel"});
        rows.add(new String[] {"Correlation", MetricFormat.percent(ingest.correlationRate())
                + " — " + ingest.attemptsCorrelated() + " spawns matched an action, "
                + ingest.attemptsUnresolved() + " matched none"});
        return rows;
    }

    /** Plan 15.3's distribution for the dimension a build is usually read by. */
    private static List<String[]> mnemonicRows(GroupAggregate.Table table) {
        List<String[]> rows = new java.util.ArrayList<>();
        table.heaviest().ifPresent(group -> rows.add(new String[] {
            "Most observed work", group.displayKey()}));
        for (GroupAggregate group : table.groups()) {
            MetricSeries duration = group.duration();
            StringBuilder value = new StringBuilder()
                    .append(MetricFormat.count(group.actions()))
                    .append(group.actions() == 1 ? " action, " : " actions, ")
                    .append(duration.exactSum().isPresent() ? "" : "at least ")
                    .append(MetricFormat.duration(duration.observedSum()))
                    .append(" (")
                    .append(MetricFormat.count(duration.observed()))
                    .append(" timed)");
            duration.distribution().min().ifPresent(min -> value
                    .append(", min ").append(MetricFormat.duration(min)));
            value.append(", median ").append(MetricFormat.bounds(duration.median()));
            value.append(", p95 ").append(MetricFormat.bounds(duration.quantile(0.95)));
            duration.distribution().max().ifPresent(max -> value
                    .append(", max ").append(MetricFormat.duration(max)));
            duration.distribution().mean().ifPresent(mean -> value
                    .append(", mean ").append(MetricFormat.duration(Math.round(mean))));
            if (group.cacheHits() + group.cacheMisses() > 0) {
                value.append("; cache ").append(MetricFormat.percent(group.cacheHitRate()))
                        .append(" hit over ").append(group.cacheHits() + group.cacheMisses())
                        .append(" reporting");
            }
            if (group.cacheUnknown() > 0) {
                // Named, not omitted: an action with no execution-log record is
                // neither a hit nor a miss, and a rate shown without this count
                // reads as if it covered the group.
                value.append("; ").append(group.cacheUnknown())
                        .append(" reported no cache state");
            }
            if (group.declaredNotCacheable() > 0) {
                value.append("; ").append(group.declaredNotCacheable())
                        .append(" declared uncacheable by Bazel");
            }
            group.inputBytes().observedSum().ifPresent(input -> value
                    .append("; inputs at least ").append(MetricFormat.bytes(input)));
            rows.add(new String[] {
                group.displayKey() + (group.isUnknownKey() ? " (no mnemonic recorded)" : ""),
                value.toString()});
        }
        return rows;
    }

    private static java.util.OptionalLong toOptional(
            com.holtherndon.bazelviz.core.measure.Measured<Long> measured) {
        return measured.value()
                .map(java.util.OptionalLong::of)
                .orElseGet(java.util.OptionalLong::empty);
    }

    /** A titled block of name/value rows. */
    private static JPanel section(String heading, List<String[]> rows) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 12, 2));
        panel.setBorder(BorderFactory.createTitledBorder(heading));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        for (String[] row : rows) {
            panel.add(label(row[0], true));
            panel.add(label(row[1], false));
        }
        return panel;
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
                + " · rule " + finding.ruleId()
                + (finding.provenByFailureData()
                        ? " · backed by a structured failure record"
                        : " · a correlation over one build, not a diagnosis"), false));
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
        catalog.removeAll();
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

    /** Every string in the invocation-metric and aggregate sections, joined. */
    public String catalogTextForTest() {
        StringBuilder text = new StringBuilder();
        collectText(catalog, text);
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

package com.holtherndon.bazelviz.ui.metrics;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.MetricFormat;
import com.holtherndon.bazelviz.analysis.MetricSeries;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import javax.swing.AbstractButton;
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
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Findings card: what the rules found, what each one rests on, and where to go and look.
 *
 * <h2>The coverage banner is not decoration</h2>
 *
 * <p>Every finding is a statement about the actions some source described, and the header says
 * which sources described how much before any finding is read. A cache-miss finding over a build
 * whose cache state was 12% covered is a different claim from the same finding at 99%, and the two
 * are indistinguishable without the number on the same screen (plan 15.5).
 *
 * <h2>Both critical paths, always both</h2>
 *
 * <p>Plan 24 requires Bazel's and the derived path to remain distinct, so the header shows them as
 * two rows with their own names and never as one figure. When only one exists the other says why it
 * does not, rather than the panel quietly showing whichever it has under the unqualified word.
 *
 * <h2>Nothing here reaches a database</h2>
 *
 * <p>The view renders a {@link MetricsService.Result} it is handed. The service owns the reading,
 * on its own thread; this class has no connection, no executor and no query (rule 8, rule 19).
 */
public final class FindingsView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;
  private static final String CARD_EMPTY = "empty";
  private static final String CARD_CONTENT = "content";

  private static final Logger log = LoggerFactory.getLogger(FindingsView.class);

  private final DefaultListModel<Finding> model = new DefaultListModel<>();
  private final JList<Finding> list = new JList<>(model);
  // ScrollableViewport, not a plain JPanel: a plain JPanel hands its own
  // preferred width straight to the enclosing JScrollPane, and the summary
  // and catalog grids it holds can each be wider than the window on their
  // own. See ScrollableViewport's javadoc — this is the same fix that keeps
  // the Overview tab from growing wider than the window. Exposed to tests
  // via topForTest().
  private final ScrollableViewport top = new ScrollableViewport();
  // ScrollableViewport for the same reason: a finding's evidence and link
  // buttons carry build-reported text of whatever length the build gave
  // them. Exposed to tests via detailForTest().
  private final ScrollableViewport detail = new ScrollableViewport();
  // GridBagLayout, not GridLayout(0, 2, ...): a fixed two-column grid sizes
  // every row in a column to that column's widest cell, so one long value —
  // and these rows carry full sentences, not just numbers — sets the width
  // of every other row regardless of how narrow the window is. See
  // OverviewPanel.section, which this mirrors.
  private final JPanel summary = new JPanel(new GridBagLayout());
  private final JPanel catalog = new JPanel();
  private final JLabel headline = new JLabel(" ");
  private final JButton recompute = new JButton("Recompute");
  private final JPanel localHeader = new JPanel(new BorderLayout());
  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel("No session is open.");

  // Fields, not constructor locals: a scroll pane whose wheel moves 1 pixel
  // per notch (the JScrollBar default) reads as "extremely slow" scrolling
  // over content this tall, and exposing the unit increment lets a test
  // catch a future edit that drops the setUnitIncrement(16) call below
  // without having to reconstruct the whole scroll-pane tree to check it.
  private final JScrollPane topScroll = new JScrollPane(top);
  private final JScrollPane listScroll = new JScrollPane(list);
  private final JScrollPane detailScroll = new JScrollPane(detail);

  private MetricsService service;
  private LongConsumer onActionSelected = actionId -> {};
  private Consumer<Finding.Link> onNavigate = link -> {};
  private PageToolbar pageToolbar;

  public FindingsView() {
    super(new BorderLayout());

    PlainText.disableHtml(headline);
    headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize() + 3f));

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new FindingRenderer());
    list.addListSelectionListener(
        event -> {
          if (!event.getValueIsAdjusting()) {
            showDetail(list.getSelectedValue());
          }
        });

    detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
    detail.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

    summary.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
    catalog.setLayout(new BoxLayout(catalog, BoxLayout.Y_AXIS));
    catalog.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

    localHeader.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
    localHeader.add(headline, BorderLayout.CENTER);
    localHeader.add(recompute, BorderLayout.EAST);

    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    localHeader.setAlignmentX(LEFT_ALIGNMENT);
    summary.setAlignmentX(LEFT_ALIGNMENT);
    catalog.setAlignmentX(LEFT_ALIGNMENT);
    top.add(localHeader);
    top.add(summary);
    top.add(catalog);

    topScroll.getVerticalScrollBar().setUnitIncrement(16);
    listScroll.getVerticalScrollBar().setUnitIncrement(16);
    detailScroll.getVerticalScrollBar().setUnitIncrement(16);

    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("Findings", listScroll),
            new SectionPane("Finding details", detailScroll));
    split.setDividerLocation(360);
    split.setResizeWeight(0.35);

    JSplitPane page =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, new SectionPane("Coverage & analysis", topScroll), split);
    page.setDividerLocation(300);
    page.setResizeWeight(0.35);
    deck.add(emptyState, CARD_EMPTY);
    deck.add(page, CARD_CONTENT);
    add(deck, BorderLayout.CENTER);

    recompute.addActionListener(event -> refresh());
    recompute.setEnabled(false);
    showEmpty();
  }

  /** Moves the page-level status and refresh action into the shared window chrome. */
  @Override
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = toolbar;
    top.remove(localHeader);
    toolbar.addAction(recompute);
    setHeadline(headline.getText());
    top.revalidate();
    top.repaint();
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
   * <p>The service is not created here and is not closed here. One collection feeds this view and
   * the overview's cards, so a session that opened both scans its actions once rather than twice.
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
    cards.show(deck, CARD_CONTENT);
    setHeadline("Reading the build…");
    recompute.setEnabled(false);
    running.collect(
        result -> {
          if (service != running) {
            return;
          }
          recompute.setEnabled(true);
          show(result);
        },
        failure -> {
          if (service != running) {
            return;
          }
          recompute.setEnabled(true);
          log.error("could not collect metrics", failure);
          setHeadline("Could not read the metrics: " + failure.getMessage());
        });
  }

  /** Renders a collection. On the EDT; visible so a test can drive it. */
  public void show(MetricsService.Result result) {
    Objects.requireNonNull(result, "result");
    cards.show(deck, CARD_CONTENT);
    model.clear();
    for (Finding finding : result.findings()) {
      model.addElement(finding);
    }
    setHeadline(
        result.findings().isEmpty()
            ? "No findings. Every rule ran and none of them matched."
            : result.findings().size()
                + (result.findings().size() == 1 ? " finding" : " findings"));

    summary.removeAll();
    addRows(summary, summaryRows(result));

    catalog.removeAll();
    catalog.add(section("Invocation metrics", invocationRows(result)));
    catalog.add(Box.createVerticalStrut(10));
    result
        .metrics()
        .aggregate(GroupAggregate.Dimension.MNEMONIC)
        .ifPresent(table -> catalog.add(section(table.describe(), mnemonicRows(table))));

    detail.removeAll();
    if (!model.isEmpty()) {
      list.setSelectedIndex(0);
    } else {
      detail.add(
          label(
              "Nothing matched. The coverage figures above say how much of the"
                  + " build the rules could see.",
              false));
    }
    revalidate();
    repaint();
  }

  /** The header rows, in the order plan 15.5 asks for them. */
  private static List<String[]> summaryRows(MetricsService.Result result) {
    var invocation = result.metrics().invocation();
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {"Duration source", result.metrics().durationSource().description()});

    CriticalPaths paths = invocation.criticalPaths();
    rows.add(
        new String[] {
          paths.bazelDisplayName(),
          paths
              .bazelReportedMicros()
              .value()
              .map(
                  value ->
                      MetricFormat.duration(value)
                          + paths
                              .bazelReportedMicros()
                              .warning()
                              .map(warning -> " — " + warning)
                              .orElse(""))
              .orElse(
                  MetricFormat.UNKNOWN
                      + " — "
                      + paths.bazelReportedMicros().warning().orElse("no source"))
        });
    rows.add(
        new String[] {
          paths.derivedDisplayName(),
          paths
              .derived()
              .map(
                  derived ->
                      MetricFormat.duration(derived.makespanMicros())
                          + (derived.isPartial() ? " (a lower bound)" : ""))
              .orElse(
                  MetricFormat.UNKNOWN
                      + " — "
                      + paths
                          .derivedUnavailableReason()
                          .orElse("no imported action graph to compute it over"))
        });
    paths
        .schedulingGapMicros()
        .ifPresent(
            gap ->
                rows.add(
                    new String[] {
                      "Difference between them",
                      MetricFormat.duration(Math.abs(gap))
                          + " — they measure different things, so a difference is expected"
                    }));

    Optional<ConcurrencySweep.Result> sweep = invocation.concurrency();
    rows.add(
        new String[] {
          "Concurrency", sweep.map(ConcurrencySweep.Result::describe).orElse(MetricFormat.UNKNOWN)
        });
    for (Coverage coverage : invocation.coverage().entries()) {
      rows.add(
          new String[] {
            coverage.name(), coverage.describe().substring(coverage.name().length() + 2)
          });
    }
    return rows;
  }

  /**
   * Plan 15.2's invocation metrics, each beside what it could not count.
   *
   * <p>These are not on the overview's two-second timer and are not repeated there: the overview
   * counts rows, this reads the catalog. Every entry that can be unavailable says so in words
   * rather than as a zero (rule 11).
   */
  private static List<String[]> invocationRows(MetricsService.Result result) {
    var invocation = result.metrics().invocation();
    var timing = invocation.timing();
    var work = invocation.work();
    var bytes = invocation.bytes();
    var tests = invocation.tests();
    var ingest = invocation.ingest();
    List<String[]> rows = new ArrayList<>();

    rows.add(
        new String[] {"Wall time", MetricFormat.duration(toOptional(timing.totalWallMicros()))});
    rows.add(
        new String[] {
          "Time to the first event received",
          MetricFormat.duration(toOptional(timing.timeToFirstEventMicros()))
        });
    for (var phase : timing.phases()) {
      rows.add(
          new String[] {
            "Phase: " + phase.name(),
            MetricFormat.duration(phase.durationMicros())
                + (phase.endIsDerived()
                    ? " (end derived: the profile records when a phase began and"
                        + " never when it ended)"
                    : "")
          });
    }

    rows.add(
        new String[] {
          "Actions",
          MetricFormat.count(work.actions())
              + " — "
              + work.actionsSucceeded()
              + " succeeded, "
              + work.actionsFailed()
              + " failed, "
              + work.actionsOtherOutcome()
              + " neither"
        });
    rows.add(
        new String[] {
          "Attempts",
          MetricFormat.count(work.attempts())
              + " — "
              + MetricFormat.ratio(work.attemptsPerAction())
              + " per action, which is"
              + " below one because most actions never spawn a subprocess"
        });
    rows.add(
        new String[] {
          "Cache state",
          work.cacheHits()
              + " hit, "
              + work.cacheMisses()
              + " missed, "
              + work.cacheStateUnknown()
              + " not reported — hit rate "
              + MetricFormat.percent(work.cacheHitRate())
              + " over the ones that reported"
        });
    rows.add(
        new String[] {
          "Runners",
          work.runners().isEmpty()
              ? MetricFormat.UNKNOWN + " — no execution log"
              : work.runners().stream()
                  .map(runner -> runner.runner() + " ×" + runner.actions())
                  .collect(Collectors.joining(", "))
        });

    rows.add(
        new String[] {
          "Known input bytes",
          MetricFormat.bytes(toOptional(bytes.knownInputBytes()))
              + ", "
              + bytes.actionsWithoutInputBytes()
              + " actions reported none"
        });
    rows.add(
        new String[] {
          "Known output bytes",
          MetricFormat.bytes(toOptional(bytes.knownOutputBytes()))
              + ", "
              + bytes.artifactsWithoutSize()
              + " artifacts have no recorded size"
              + (bytes.isPartial() ? " — both totals are lower bounds" : "")
        });

    invocation
        .concurrency()
        .ifPresent(
            sweep -> {
              rows.add(
                  new String[] {
                    "Peak concurrency",
                    MetricFormat.count(sweep.peakActive())
                        + ", first reached "
                        + MetricFormat.duration(
                            sweep.peakFirstSeenMicros() - sweep.windowStartMicros())
                        + " into the observed span"
                  });
              rows.add(
                  new String[] {
                    "Average concurrency",
                    MetricFormat.ratio(sweep.averageActive())
                        + " across the whole window, "
                        + MetricFormat.ratio(sweep.averageActiveWhileBusy())
                        + " while something was running"
                  });
              rows.add(
                  new String[] {
                    "Parallelism factor",
                    MetricFormat.ratio(sweep.parallelismFactor())
                        + " — an aggregate concurrency indicator, not CPU utilization"
                  });
              rows.add(
                  new String[] {
                    "Time with nothing running",
                    MetricFormat.duration(sweep.idleMicros())
                        + " of "
                        + MetricFormat.duration(sweep.windowMicros())
                  });
              rows.add(
                  new String[] {
                    "Observed work",
                    MetricFormat.duration(sweep.totalSpanMicros())
                        + " across "
                        + MetricFormat.count(sweep.sweptSpans())
                        + " spans"
                        + (sweep.instantaneousSpans() > 0
                            ? ", plus "
                                + sweep.instantaneousSpans()
                                + " that start and end at the same instant"
                            : "")
                        + (sweep.untimedSpans() > 0
                            ? ", with " + sweep.untimedSpans() + " actions untimed"
                            : "")
                  });
            });

    rows.add(
        new String[] {
          "Tests",
          MetricFormat.count(tests.total())
              + " — "
              + tests.failed()
              + " not passing, "
              + tests.flaky()
              + " flaky, "
              + tests.cached()
              + " with a cached attempt"
        });
    rows.add(
        new String[] {
          "Events",
          MetricFormat.count(ingest.rawEvents())
              + " — "
              + ingest.undecodableEvents()
              + " could not be decoded, "
              + ingest.notAttemptedEvents()
              + " were not decoded by choice"
        });
    rows.add(
        new String[] {
          "Ingestion lag",
          MetricFormat.duration(toOptional(ingest.meanIngestLagMicros()))
              + " on average — a measure of this application, not of Bazel"
        });
    rows.add(
        new String[] {
          "Correlation",
          MetricFormat.percent(ingest.correlationRate())
              + " — "
              + ingest.attemptsCorrelated()
              + " spawns matched an action, "
              + ingest.attemptsUnresolved()
              + " matched none"
        });
    return rows;
  }

  /** Plan 15.3's distribution for the dimension a build is usually read by. */
  private static List<String[]> mnemonicRows(GroupAggregate.Table table) {
    List<String[]> rows = new ArrayList<>();
    table
        .heaviest()
        .ifPresent(group -> rows.add(new String[] {"Most observed work", group.displayKey()}));
    for (GroupAggregate group : table.groups()) {
      MetricSeries duration = group.duration();
      StringBuilder value =
          new StringBuilder()
              .append(MetricFormat.count(group.actions()))
              .append(group.actions() == 1 ? " action, " : " actions, ")
              .append(duration.exactSum().isPresent() ? "" : "at least ")
              .append(MetricFormat.duration(duration.observedSum()))
              .append(" (")
              .append(MetricFormat.count(duration.observed()))
              .append(" timed)");
      duration
          .distribution()
          .min()
          .ifPresent(min -> value.append(", min ").append(MetricFormat.duration(min)));
      value.append(", median ").append(MetricFormat.bounds(duration.median()));
      value.append(", p95 ").append(MetricFormat.bounds(duration.quantile(0.95)));
      duration
          .distribution()
          .max()
          .ifPresent(max -> value.append(", max ").append(MetricFormat.duration(max)));
      duration
          .distribution()
          .mean()
          .ifPresent(
              mean -> value.append(", mean ").append(MetricFormat.duration(Math.round(mean))));
      if (group.cacheHits() + group.cacheMisses() > 0) {
        value
            .append("; cache ")
            .append(MetricFormat.percent(group.cacheHitRate()))
            .append(" hit over ")
            .append(group.cacheHits() + group.cacheMisses())
            .append(" reporting");
      }
      if (group.cacheUnknown() > 0) {
        // Named, not omitted: an action with no execution-log record is
        // neither a hit nor a miss, and a rate shown without this count
        // reads as if it covered the group.
        value.append("; ").append(group.cacheUnknown()).append(" reported no cache state");
      }
      if (group.declaredNotCacheable() > 0) {
        value
            .append("; ")
            .append(group.declaredNotCacheable())
            .append(" declared uncacheable by Bazel");
      }
      group
          .inputBytes()
          .observedSum()
          .ifPresent(input -> value.append("; inputs at least ").append(MetricFormat.bytes(input)));
      rows.add(
          new String[] {
            group.displayKey() + (group.isUnknownKey() ? " (no mnemonic recorded)" : ""),
            value.toString()
          });
    }
    return rows;
  }

  private static OptionalLong toOptional(Measured<Long> measured) {
    return measured.value().map(OptionalLong::of).orElseGet(OptionalLong::empty);
  }

  /** A titled block of name/value rows. */
  private static JPanel section(String heading, List<String[]> rows) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder(heading));
    panel.setAlignmentX(LEFT_ALIGNMENT);
    addRows(panel, rows);
    return panel;
  }

  /**
   * Lays {@code rows} into {@code panel} as a two-column name/value grid, one row per pair.
   *
   * <p>GridBagLayout, not GridLayout: each row is sized to its own content rather than every row
   * being forced to the width of the single widest cell in the whole grid — see
   * OverviewPanel.section, which this mirrors. The name column takes only what its own label needs;
   * the value column gets the rest, via {@code weightx}.
   */
  private static void addRows(JPanel panel, List<String[]> rows) {
    GridBagConstraints name = new GridBagConstraints();
    name.gridx = 0;
    name.anchor = GridBagConstraints.NORTHWEST;
    name.insets = new Insets(1, 4, 1, 12);
    GridBagConstraints value = new GridBagConstraints();
    value.gridx = 1;
    value.weightx = 1;
    value.anchor = GridBagConstraints.NORTHWEST;
    value.fill = GridBagConstraints.HORIZONTAL;
    value.insets = new Insets(1, 0, 1, 4);
    for (int row = 0; row < rows.size(); row++) {
      name.gridy = row;
      value.gridy = row;
      panel.add(label(rows.get(row)[0], true), name);
      panel.add(label(rows.get(row)[1], false), value);
    }
  }

  private void showDetail(Finding finding) {
    detail.removeAll();
    if (finding == null) {
      revalidate();
      repaint();
      return;
    }
    // wrapped, not heading(): a finding's title is a full sentence built
    // from this build's own numbers (plan 15.5's "Something is slow" is
    // the short case; FindingRules also produces sentences like "The
    // dependency chain accounts for 45.2% of the build's wall time"), and
    // an unwrapped JLabel simply does not paint what does not fit.
    detail.add(wrapped(finding.title(), true));
    detail.add(
        label(
            "Severity "
                + finding.severity().displayName()
                + " · confidence "
                + finding.confidence().displayName()
                + " · rule "
                + finding.ruleId()
                + (finding.provenByFailureData()
                    ? " · backed by a structured failure record"
                    : " · a correlation over one build, not a diagnosis"),
            false));
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
        detail.add(wrapped(metric.name() + ": " + metric.value(), true));
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
        open.setToolTipText(PlainText.tooltip(evidence.label() + " — " + evidence.detail()));
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
        go.setToolTipText(PlainText.tooltip(link.description()));
        go.addActionListener(event -> onNavigate.accept(link));
        detail.add(go);
      }
    }
    revalidate();
    repaint();
  }

  private void showEmpty() {
    setHeadline(" ");
    catalog.removeAll();
    detail.removeAll();
    cards.show(deck, CARD_EMPTY);
    revalidate();
    repaint();
  }

  private void setHeadline(String text) {
    String value = text == null ? "" : text;
    headline.setText(value.isBlank() ? " " : value);
    if (pageToolbar != null) {
      String concise = value.length() <= 140 ? value : value.substring(0, 137) + "…";
      pageToolbar.setMetadata(concise, value);
    }
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
   * <p>A {@link JLabel} would need HTML to wrap, and the text here contains build labels that came
   * out of the session — exactly the untrusted strings {@link PlainText} exists to keep out of an
   * HTML renderer.
   */
  private static Component wrapped(String text) {
    return wrapped(text, false);
  }

  /**
   * As {@link #wrapped(String)}, in a bold font: for a finding's title and its metric names, both
   * of which carry build-reported text long enough that an unwrapped {@link JLabel} would simply
   * not paint the overflow — the silent truncation rule 12 forbids.
   */
  private static Component wrapped(String text, boolean bold) {
    JTextArea area = WrappingLabel.create(text);
    area.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    area.setAlignmentX(LEFT_ALIGNMENT);
    if (bold) {
      area.setFont(area.getFont().deriveFont(Font.BOLD));
    }
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
      PlainText.disableHtml(this);
      return this;
    }
  }

  // --- test hooks -------------------------------------------------------

  /** The titles on screen, worst first. */
  public List<String> findingTitlesForTest() {
    List<String> titles = new ArrayList<>(model.size());
    for (int i = 0; i < model.size(); i++) {
      titles.add(model.get(i).title());
    }
    return titles;
  }

  /** The header line. */
  public String headlineForTest() {
    return headline.getText();
  }

  /**
   * Visible for testing: the top scroll pane's view (header, summary and catalog), to confirm it
   * tracks the viewport's width instead of overflowing it — the same property the Overview tab's
   * own {@code contentForTest()} confirms.
   */
  public ScrollableViewport topForTest() {
    return top;
  }

  /**
   * Visible for testing: the detail scroll pane's view, to confirm it tracks the viewport's width
   * instead of overflowing it even when a finding's evidence or link text is long.
   */
  public ScrollableViewport detailForTest() {
    return detail;
  }

  /** Visible for testing: the scroll pane above the summary and catalog. */
  public JScrollPane topScrollForTest() {
    return topScroll;
  }

  /** Visible for testing: the list's scroll pane. */
  public JScrollPane listScrollForTest() {
    return listScroll;
  }

  /** Visible for testing: the detail pane's scroll pane. */
  public JScrollPane detailScrollForTest() {
    return detailScroll;
  }

  /** Visible for testing: the full-pane state shown without a session. */
  JPanel emptyStateForTest() {
    return emptyState;
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
    } else if (component instanceof JTextArea area) {
      into.append(area.getText()).append('\n');
    } else if (component instanceof AbstractButton button) {
      into.append(button.getText()).append('\n');
    }
    if (component instanceof Container container) {
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

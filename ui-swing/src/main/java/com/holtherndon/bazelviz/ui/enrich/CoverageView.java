package com.holtherndon.bazelviz.ui.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ResponsiveGridLayout;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each source covers, what each phase cost, and what the enrichment tasks did.
 *
 * <h2>Coverage is worded, not just counted</h2>
 *
 * <p>"4 of 13 actions have attempt data" is a true sentence that reads like a failure. It is not
 * one: two thirds of a build's actions run inside the Bazel server and never spawn a subprocess, so
 * no attempt record exists for them and none ever will (K1). The panel says why, every time,
 * because a number without that sentence sends the reader looking for missing data.
 *
 * <h2>Enrichment failures are shown here in full</h2>
 *
 * <p>Plan 21.4 lists what a failed task must show: task, source, exit status, error excerpt,
 * retriability, and the metrics that are unavailable as a result. All six are rendered. The last is
 * the one that matters — a user who knows the profile import failed still does not know that they
 * have therefore lost the critical path.
 */
public final class CoverageView extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(CoverageView.class);

  // ScrollableViewport, not a plain JPanel: without it the JScrollPane below
  // hands this panel its own preferred width unconditionally, so one long
  // note makes the whole view wider than the window instead of wrapping.
  private final ScrollableViewport body = new ScrollableViewport(new BorderLayout());

  /** The readable vertical sequence of coverage cards. */
  private final JPanel cards = new JPanel(new ResponsiveGridLayout(1, 480, 0, 12));

  /** Only the two naturally short sections share a row. */
  private final JPanel pairedCards = new JPanel(new ResponsiveGridLayout(2, 480, 12, 12));

  private final JScrollPane scroll;
  private final JPanel dashboard = new JPanel();
  private final CardLayout contentLayout = new CardLayout();
  private final JPanel content = new JPanel(contentLayout);
  private final JPanel emptyState = new JPanel(new BorderLayout());
  private ExecutorService executor;
  private long generation;
  private final JLabel empty =
      PlainText.disableHtml(new JLabel("No session is open.", SwingConstants.CENTER));

  public CoverageView() {
    super(new BorderLayout());
    empty.setEnabled(false);
    cards.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    pairedCards.setAlignmentX(LEFT_ALIGNMENT);
    pairedCards.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    dashboard.setLayout(new BoxLayout(dashboard, BoxLayout.Y_AXIS));
    cards.setAlignmentX(LEFT_ALIGNMENT);
    cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    dashboard.add(cards);
    // Coverage is already inside the Overview split. A second centred,
    // width-capped surface wastes the room that long Bazel descriptions
    // need most.
    body.add(dashboard, BorderLayout.NORTH);

    scroll = new JScrollPane(body);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    emptyState.add(empty, BorderLayout.CENTER);
    content.add(scroll, "dashboard");
    content.add(emptyState, "empty");
    add(content, BorderLayout.CENTER);
    showEmpty();
  }

  /**
   * Reads this session's enrichment state and renders it.
   *
   * <p>Every one of these is a query, so they run on a worker and the render happens on the EDT
   * with the results already in hand (plan rule 8). A generation counter discards a slow read whose
   * session has since been replaced.
   */
  public void openSession(SessionSource opened) {
    closeSession();
    long generation = ++this.generation;
    ExecutorService worker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-coverage");
              thread.setDaemon(true);
              return thread;
            });
    this.executor = worker;
    worker.execute(
        () -> {
          Snapshot snapshot;
          EntityReader reader = opened.openEntityReader();
          try {
            snapshot =
                new Snapshot(
                    reader.enrichmentCoverage(),
                    reader.runnerCounts(),
                    reader.buildPhases(),
                    reader.profileAnchor(),
                    reader.bazelCriticalPath(),
                    reader.enrichmentTasks());
          } catch (RuntimeException failure) {
            log.debug("reading enrichment state", failure);
            return;
          } finally {
            reader.close();
          }
          SwingUtilities.invokeLater(
              () -> {
                if (generation == this.generation) {
                  render(snapshot);
                }
              });
        });
  }

  /** Lets go of the session, without closing it. */
  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's coverage read has stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    generation++;
    ExecutorService worker = executor;
    executor = null;
    cards.removeAll();
    pairedCards.removeAll();
    showEmpty();
    if (worker == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-coverage-close",
        () -> {
          worker.shutdownNow();
          try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
  }

  /** Everything one render needs, read in one pass. */
  record Snapshot(
      EnrichmentQueries.Coverage coverage,
      List<EnrichmentQueries.RunnerCount> runners,
      List<EnrichmentQueries.Phase> phases,
      Optional<ProfileAnchor> anchor,
      List<EnrichmentQueries.CriticalPathComponent> criticalPath,
      List<EnrichmentTask> tasks) {}

  /** Renders a snapshot. Package-private so tests need no session. */
  void render(Snapshot snapshot) {
    cards.removeAll();
    contentLayout.show(content, "dashboard");

    EnrichmentQueries.Coverage coverage = snapshot.coverage();
    Section dataCoverage = new Section("Data coverage", 0.30);
    renderCoverage(dataCoverage, coverage);
    cards.add(dataCoverage.panel());

    Section runners = new Section("Where actions ran");
    renderRunners(runners, snapshot.runners());

    Section phases = new Section("Build phases");
    renderPhases(phases, snapshot.phases(), snapshot.anchor());
    pairedCards.removeAll();
    pairedCards.add(runners.panel());
    pairedCards.add(phases.panel());
    cards.add(pairedCards);

    // Progress messages are often full action descriptions. Giving them
    // 42% of half a dashboard produced the narrow column in the reported
    // screenshot, with empty space beside it. This card owns the row and
    // gives the description most of that width.
    Section criticalPath = new Section("Bazel's critical path", 0.78);
    renderCriticalPath(criticalPath, snapshot.criticalPath());
    cards.add(criticalPath.panel());

    // Task labels are short while paths and diagnostics are not.
    Section tasks = new Section("Enrichment tasks", 0.22);
    renderTasks(tasks, snapshot.tasks());
    cards.add(tasks.panel());

    cards.revalidate();
    cards.repaint();
    scroll.getVerticalScrollBar().setValue(0);
  }

  // ---------------------------------------------------------------- coverage

  private void renderCoverage(Section section, EnrichmentQueries.Coverage coverage) {
    if (coverage.hasNoAttempts()) {
      section.addNote(
          "No execution log has been imported, so nothing is known about where"
              + " actions ran, which were cache hits, or how their time was spent.");
    } else {
      section.addRow("Actions in the build event stream", Long.toString(coverage.actions()));
      section.addRow("Actions with attempt data", Long.toString(coverage.actionsWithAttempts()));
      section.addNote(
          "The difference is not missing data. Most of a build's actions run inside"
              + " the Bazel server — writing scripts, laying out runfiles, resolving"
              + " symlinks — and never start a subprocess, so the execution log has"
              + " nothing to say about them.");
      section.addRow("Spawns recorded", Long.toString(coverage.attempts()));

      for (AttemptCorrelation correlation : AttemptCorrelation.values()) {
        long count = coverage.byCorrelation().getOrDefault(correlation, 0L);
        if (count > 0) {
          section.addRow("  " + AttemptInspection.describe(correlation), Long.toString(count));
        }
      }
      if (coverage.attemptsNeedingAttention() > 0) {
        section.addNote(
            coverage.attemptsNeedingAttention()
                + " spawns could not be matched to"
                + " an action, or matched more than one. Their own measurements are"
                + " still correct; what is undecided is which action they belong to.");
      }
    }

    if (coverage.profileSpans() == 0) {
      section.addNote(
          "No trace profile has been imported, so there are no build phases,"
              + " no critical path from Bazel, and no resource timeline.");
    } else {
      section.addRow("Profile spans", Long.toString(coverage.profileSpans()));
      section.addRow("Spans tied to an action", Long.toString(coverage.attributedProfileSpans()));
      if (coverage.spansCannotBeAttributed()) {
        section.addNote(
            "None of the profile's spans can be tied to an action, because this"
                + " build was captured without"
                + " --experimental_profile_include_primary_output.");
      }
    }
  }

  private void renderRunners(Section section, List<EnrichmentQueries.RunnerCount> runners) {
    if (runners.isEmpty()) {
      section.addNote("Unknown: no execution log.");
      return;
    }
    runners.forEach(runner -> section.addRow(runner.runner(), Long.toString(runner.attempts())));
  }

  // ------------------------------------------------------------------ phases

  private void renderPhases(
      Section section, List<EnrichmentQueries.Phase> phases, Optional<ProfileAnchor> anchor) {
    if (phases.isEmpty()) {
      section.addNote("Unknown: no trace profile.");
      return;
    }
    for (EnrichmentQueries.Phase phase : phases) {
      String duration =
          phase.durationMicros().isPresent()
              ? millis(phase.durationMicros().getAsLong())
              : "unknown — the profile states no end for the last phase";
      section.addRow(phase.name(), duration);
    }
    section.addNote(
        "Phase boundaries are derived: the profile records when each phase began and"
            + " never when it ended, so a phase lasts until the next one starts.");
    // The anchor's uncertainty is a property of the version, and hiding it
    // would let a reader line these up with attempt timings to a precision
    // the data does not support (P1).
    anchor.flatMap(ProfileAnchor::precisionCaveat).ifPresent(section::addNote);
  }

  private void renderCriticalPath(
      Section section, List<EnrichmentQueries.CriticalPathComponent> path) {
    if (path.isEmpty()) {
      section.addNote("Unknown: no trace profile.");
      return;
    }
    for (EnrichmentQueries.CriticalPathComponent component : path) {
      section.addRow(
          component.description(),
          component.durationMicros().isPresent()
              ? millis(component.durationMicros().getAsLong())
              : "unknown");
    }
    section.addNote(
        "This is Bazel's own critical path, kept as Bazel reported it. Its entries"
            + " name themselves with a progress message and nothing else, so they are"
            + " not linked to the actions table — matching them by that text would be a"
            + " guess.");
  }

  // ------------------------------------------------------------------- tasks

  private void renderTasks(Section section, List<EnrichmentTask> tasks) {
    if (tasks.isEmpty()) {
      section.addNote("No enrichment has been attempted for this session.");
      return;
    }
    for (EnrichmentTask task : tasks) {
      section.addRow(task.kind().displayName(), task.state().name());
      task.sourcePath().ifPresent(path -> section.addRow("  Source", path));
      task.exitStatus().ifPresent(status -> section.addRow("  Result", status));
      task.errorExcerpt().ifPresent(error -> section.addRow("  Error", error));
      if (task.state() == EnrichmentTask.State.FAILED
          || task.state() == EnrichmentTask.State.PARTIAL) {
        section.addRow("  Can be retried", task.retriable() ? "yes" : "no");
      }
      if (!task.unavailableMetrics().isEmpty()) {
        section.addNote("Unavailable because this task did not finish:");
        task.unavailableMetrics().forEach(metric -> section.addRow("    •", metric));
      }
    }
  }

  // ----------------------------------------------------------------- drawing

  private void showEmpty() {
    contentLayout.show(content, "empty");
    cards.revalidate();
    cards.repaint();
  }

  /** One cohesive card: wrapped name/value rows and full-width explanatory notes. */
  private static final class Section {

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final double nameWeight;
    private int row;

    private Section(String heading) {
      this(heading, 0.42);
    }

    private Section(String heading, double nameWeight) {
      if (nameWeight <= 0 || nameWeight >= 1) {
        throw new IllegalArgumentException("name weight must be between zero and one");
      }
      this.nameWeight = nameWeight;
      panel.setBorder(BorderFactory.createTitledBorder(heading));
      panel.setAlignmentX(LEFT_ALIGNMENT);
      panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private JPanel panel() {
      return panel;
    }

    private void addRow(String name, String value) {
      JTextArea nameLabel = WrappingLabel.create(name);
      nameLabel.setEnabled(false);
      JTextArea valueLabel = WrappingLabel.create(value);
      valueLabel.setToolTipText(PlainText.tooltip(value));

      GridBagConstraints left = new GridBagConstraints();
      left.gridx = 0;
      left.gridy = row;
      left.weightx = nameWeight;
      left.anchor = GridBagConstraints.NORTHWEST;
      left.fill = GridBagConstraints.HORIZONTAL;
      left.insets = new Insets(1, 4, 1, 12);
      GridBagConstraints right = new GridBagConstraints();
      right.gridx = 1;
      right.gridy = row++;
      right.weightx = 1.0 - nameWeight;
      right.anchor = GridBagConstraints.NORTHWEST;
      right.fill = GridBagConstraints.HORIZONTAL;
      right.insets = new Insets(1, 0, 1, 4);
      panel.add(nameLabel, left);
      panel.add(valueLabel, right);
    }

    private void addNote(String text) {
      JTextArea label = WrappingLabel.create(text);
      label.setFont(label.getFont().deriveFont(Font.ITALIC));
      label.setToolTipText(PlainText.tooltip(text));
      GridBagConstraints note = new GridBagConstraints();
      note.gridx = 0;
      note.gridy = row++;
      note.gridwidth = 2;
      note.weightx = 1;
      note.anchor = GridBagConstraints.NORTHWEST;
      note.fill = GridBagConstraints.HORIZONTAL;
      note.insets = new Insets(3, 4, 3, 4);
      panel.add(label, note);
    }
  }

  private static String millis(long micros) {
    return String.format("%.1f ms", micros / 1000.0);
  }

  /** The rows currently on screen, for tests. */
  List<String> renderedText() {
    List<String> text = new ArrayList<>();
    collect(body, text);
    return text;
  }

  /** The width-tracking scroll body, for responsive-layout tests. */
  ScrollableViewport contentForTest() {
    return body;
  }

  /** The responsive card grid, for wide/narrow placement tests. */
  JPanel cardsForTest() {
    return cards;
  }

  /** The only sections allowed to share a row. */
  JPanel pairedCardsForTest() {
    return pairedCards;
  }

  /** The actual scroll pane, so tests can rule out horizontal overflow. */
  JScrollPane scrollForTest() {
    return scroll;
  }

  /** The dashboard that fills the lower Overview viewport. */
  JPanel dashboardForTest() {
    return dashboard;
  }

  /** The full-pane empty state shown instead of a one-line dashboard card. */
  JPanel emptyStateForTest() {
    return emptyState;
  }

  private static void collect(Container container, List<String> into) {
    for (Component child : container.getComponents()) {
      if (child instanceof JLabel label && label.getText() != null) {
        into.add(label.getText());
      }
      // Notes render as a WrappingLabel (a JTextArea) rather than a
      // JLabel -- see addNote -- so this must be checked too, or every
      // assertion about a note's wording would silently stop seeing it.
      if (child instanceof JTextArea area && area.getText() != null) {
        into.add(area.getText());
      }
      if (child instanceof Container nested) {
        collect(nested, into);
      }
    }
  }
}

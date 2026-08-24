package com.holtherndon.bazelviz.ui.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each source covers, what each phase cost, and what the enrichment tasks
 * did.
 *
 * <h2>Coverage is worded, not just counted</h2>
 *
 * <p>"4 of 13 actions have attempt data" is a true sentence that reads like a
 * failure. It is not one: two thirds of a build's actions run inside the Bazel
 * server and never spawn a subprocess, so no attempt record exists for them and
 * none ever will (K1). The panel says why, every time, because a number without
 * that sentence sends the reader looking for missing data.
 *
 * <h2>Enrichment failures are shown here in full</h2>
 *
 * <p>Plan 21.4 lists what a failed task must show: task, source, exit status,
 * error excerpt, retriability, and the metrics that are unavailable as a
 * result. All six are rendered. The last is the one that matters — a user who
 * knows the profile import failed still does not know that they have therefore
 * lost the critical path.
 */
public final class CoverageView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(CoverageView.class);

    // ScrollableViewport, not a plain JPanel: without it the JScrollPane below
    // hands this panel its own preferred width unconditionally, so one long
    // note (several run past 800px at the default font -- see WrappingLabel's
    // javadoc) makes the whole view wider than the window instead of the
    // window narrowing it. See ScrollableViewport's javadoc for the mechanism.
    private final ScrollableViewport body = new ScrollableViewport();
    private ExecutorService executor;
    private long generation;
    private final JLabel empty =
            PlainText.disableHtml(new JLabel("No session is open.", SwingConstants.CENTER));

    public CoverageView() {
        super(new BorderLayout());
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(new JScrollPane(body), BorderLayout.CENTER);
        showEmpty();
    }

    /**
     * Reads this session's enrichment state and renders it.
     *
     * <p>Every one of these is a query, so they run on a worker and the render
     * happens on the EDT with the results already in hand (plan rule 8). A
     * generation counter discards a slow read whose session has since been
     * replaced.
     */
    public void openSession(SessionSource opened) {
        closeSession();
        long generation = ++this.generation;
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-coverage");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = worker;
        worker.execute(() -> {
            Snapshot snapshot;
            EntityReader reader = opened.openEntityReader();
            try {
                snapshot = new Snapshot(
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
            SwingUtilities.invokeLater(() -> {
                if (generation == this.generation) {
                    render(snapshot);
                }
            });
        });
    }

    /** Lets go of the session, without closing it. */
    public void closeSession() {
        generation++;
        ExecutorService worker = executor;
        executor = null;
        if (worker != null) {
            worker.shutdownNow();
        }
        body.removeAll();
        showEmpty();
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
        body.removeAll();

        EnrichmentQueries.Coverage coverage = snapshot.coverage();
        addHeading("Data coverage");
        renderCoverage(coverage);

        addHeading("Where actions ran");
        renderRunners(snapshot.runners(), coverage);

        addHeading("Build phases");
        renderPhases(snapshot.phases(), snapshot.anchor());

        addHeading("Bazel's critical path");
        renderCriticalPath(snapshot.criticalPath());

        addHeading("Enrichment tasks");
        renderTasks(snapshot.tasks());

        body.revalidate();
        body.repaint();
    }

    // ---------------------------------------------------------------- coverage

    private void renderCoverage(EnrichmentQueries.Coverage coverage) {
        if (coverage.hasNoAttempts()) {
            addNote("No execution log has been imported, so nothing is known about where"
                    + " actions ran, which were cache hits, or how their time was spent.");
        } else {
            addRow("Actions in the build event stream", Long.toString(coverage.actions()));
            addRow("Actions with attempt data", Long.toString(coverage.actionsWithAttempts()));
            addNote("The difference is not missing data. Most of a build's actions run inside"
                    + " the Bazel server — writing scripts, laying out runfiles, resolving"
                    + " symlinks — and never start a subprocess, so the execution log has"
                    + " nothing to say about them.");
            addRow("Spawns recorded", Long.toString(coverage.attempts()));

            for (AttemptCorrelation correlation : AttemptCorrelation.values()) {
                long count = coverage.byCorrelation().getOrDefault(correlation, 0L);
                if (count > 0) {
                    addRow("  " + AttemptInspection.describe(correlation), Long.toString(count));
                }
            }
            if (coverage.attemptsNeedingAttention() > 0) {
                addNote(coverage.attemptsNeedingAttention() + " spawns could not be matched to"
                        + " an action, or matched more than one. Their own measurements are"
                        + " still correct; what is undecided is which action they belong to.");
            }
        }

        if (coverage.profileSpans() == 0) {
            addNote("No trace profile has been imported, so there are no build phases,"
                    + " no critical path from Bazel, and no resource timeline.");
        } else {
            addRow("Profile spans", Long.toString(coverage.profileSpans()));
            addRow("Spans tied to an action",
                    Long.toString(coverage.attributedProfileSpans()));
            if (coverage.spansCannotBeAttributed()) {
                addNote("None of the profile's spans can be tied to an action, because this"
                        + " build was captured without"
                        + " --experimental_profile_include_primary_output.");
            }
        }
    }

    private void renderRunners(
            List<EnrichmentQueries.RunnerCount> runners, EnrichmentQueries.Coverage coverage) {
        if (runners.isEmpty()) {
            addNote("Unknown: no execution log.");
            return;
        }
        runners.forEach(runner ->
                addRow(runner.runner(), Long.toString(runner.attempts())));
    }

    // ------------------------------------------------------------------ phases

    private void renderPhases(
            List<EnrichmentQueries.Phase> phases, Optional<ProfileAnchor> anchor) {
        if (phases.isEmpty()) {
            addNote("Unknown: no trace profile.");
            return;
        }
        for (EnrichmentQueries.Phase phase : phases) {
            String duration = phase.durationMicros().isPresent()
                    ? millis(phase.durationMicros().getAsLong())
                    : "unknown — the profile states no end for the last phase";
            addRow(phase.name(), duration);
        }
        addNote("Phase boundaries are derived: the profile records when each phase began and"
                + " never when it ended, so a phase lasts until the next one starts.");
        // The anchor's uncertainty is a property of the version, and hiding it
        // would let a reader line these up with attempt timings to a precision
        // the data does not support (P1).
        anchor.flatMap(ProfileAnchor::precisionCaveat).ifPresent(this::addNote);
    }

    private void renderCriticalPath(List<EnrichmentQueries.CriticalPathComponent> path) {
        if (path.isEmpty()) {
            addNote("Unknown: no trace profile.");
            return;
        }
        for (EnrichmentQueries.CriticalPathComponent component : path) {
            addRow(component.description(),
                    component.durationMicros().isPresent()
                            ? millis(component.durationMicros().getAsLong())
                            : "unknown");
        }
        addNote("This is Bazel's own critical path, kept as Bazel reported it. Its entries"
                + " name themselves with a progress message and nothing else, so they are"
                + " not linked to the actions table — matching them by that text would be a"
                + " guess.");
    }

    // ------------------------------------------------------------------- tasks

    private void renderTasks(List<EnrichmentTask> tasks) {
        if (tasks.isEmpty()) {
            addNote("No enrichment has been attempted for this session.");
            return;
        }
        for (EnrichmentTask task : tasks) {
            addRow(task.kind().displayName(), task.state().name());
            task.sourcePath().ifPresent(path -> addRow("  Source", path));
            task.exitStatus().ifPresent(status -> addRow("  Result", status));
            task.errorExcerpt().ifPresent(error -> addRow("  Error", error));
            if (task.state() == EnrichmentTask.State.FAILED
                    || task.state() == EnrichmentTask.State.PARTIAL) {
                addRow("  Can be retried", task.retriable() ? "yes" : "no");
            }
            if (!task.unavailableMetrics().isEmpty()) {
                addNote("Unavailable because this task did not finish:");
                task.unavailableMetrics().forEach(metric -> addRow("    •", metric));
            }
        }
    }

    // ----------------------------------------------------------------- drawing

    private void showEmpty() {
        body.add(empty);
        body.revalidate();
        body.repaint();
    }

    private void addHeading(String text) {
        body.add(Box.createVerticalStrut(14));
        JLabel label = PlainText.disableHtml(new JLabel(text));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(LEFT_ALIGNMENT);
        body.add(label);
        body.add(Box.createVerticalStrut(4));
    }

    private void addRow(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(PlainText.disableHtml(new JLabel(name)), BorderLayout.WEST);
        JLabel valueLabel = PlainText.disableHtml(new JLabel(value));
        row.add(valueLabel, BorderLayout.EAST);
        body.add(row);
    }

    private void addNote(String text) {
        // WrappingLabel, not JLabel: several of these notes measure over
        // 800px wide at the default font, well past the narrowest width the
        // scrollable body above is ever given. A JLabel does not wrap, so it
        // would simply not paint whatever did not fit -- the silent
        // truncation plan rule 12 forbids.
        JTextArea label = WrappingLabel.create(text);
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        label.setAlignmentX(LEFT_ALIGNMENT);
        // A tooltip carries the whole sentence on the rare window too narrow
        // even for a wrapped line; the text came from a build and goes
        // through the tooltip guard.
        label.setToolTipText(PlainText.tooltip(text));
        body.add(label);
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

    private static void collect(java.awt.Container container, List<String> into) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) {
                into.add(label.getText());
            }
            // Notes render as a WrappingLabel (a JTextArea) rather than a
            // JLabel -- see addNote -- so this must be checked too, or every
            // assertion about a note's wording would silently stop seeing it.
            if (child instanceof JTextArea area && area.getText() != null) {
                into.add(area.getText());
            }
            if (child instanceof java.awt.Container nested) {
                collect(nested, into);
            }
        }
    }
}

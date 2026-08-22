package com.holtherndon.bazelviz.ui.overview;

import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Overview card: what this build was and what it did.
 *
 * <h2>Live, by re-reading rather than by listening</h2>
 *
 * <p>During a capture the numbers change constantly, so the panel re-reads a
 * whole snapshot on a timer instead of reacting to individual rows. That is
 * what "live updates are coalesced" means here (plan 24, Phase 3): a build
 * writing ten thousand rows a second produces one repaint per interval, and
 * every number on screen comes from the same read, so they are consistent with
 * each other even mid-build.
 *
 * <p>The alternative — updating each tile as its rows arrive — would show a
 * screen whose totals disagreed, which is worse than a screen half a second old.
 *
 * <h2>Two of everything, labelled</h2>
 *
 * <p>Several numbers exist twice: this session's own counts, and Bazel's. They
 * legitimately disagree — {@code actionsExecuted} excludes cache hits, and the
 * elapsed time the user watched differs from Bazel's internal wall time by up
 * to a second — so both are shown under their own names rather than reconciled
 * into one figure that is true of neither.
 */
public final class OverviewPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(OverviewPanel.class);

    /** How often a live session's numbers are re-read. */
    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);

    private final Duration refreshInterval;

    private final JLabel headline = new JLabel(" ");
    private final JLabel subhead = new JLabel(" ");
    private final JPanel tiles = new JPanel(new GridLayout(0, 4, 12, 12));
    private final JPanel details = new JPanel();
    private final JLabel emptyLabel =
            new JLabel("No session is open.", SwingConstants.CENTER);

    private ScheduledExecutorService refresher;
    private SessionSource source;
    private EntityReader reader;
    private java.util.function.Consumer<OverviewSnapshot> snapshotListener = snapshot -> { };

    public OverviewPanel() {
        this(REFRESH_INTERVAL);
    }

    /**
     * @param refreshInterval how often to re-read. A parameter so a test can
     *     drive several intervals in a second; the coalescing it is there to
     *     demonstrate is a property of the interval existing, not of its length.
     */
    public OverviewPanel(Duration refreshInterval) {
        super(new BorderLayout());
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval");

        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize() + 4f));
        subhead.setEnabled(false);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        headline.setAlignmentX(LEFT_ALIGNMENT);
        subhead.setAlignmentX(LEFT_ALIGNMENT);
        header.add(headline);
        header.add(subhead);

        tiles.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        JPanel body = new JPanel(new BorderLayout());
        body.add(tiles, BorderLayout.NORTH);
        body.add(details, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        emptyLabel.setEnabled(false);
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        showEmpty();
    }

    /**
     * Observes every snapshot, on the EDT.
     *
     * <p>The window's status bar uses this so its counts come from the same
     * read as the panel's, rather than from a second query that could disagree
     * with what is on screen.
     */
    public void onSnapshot(java.util.function.Consumer<OverviewSnapshot> listener) {
        this.snapshotListener = Objects.requireNonNull(listener, "listener");
    }

    /** Opens a session and starts refreshing. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        headline.setText("Reading…");
        subhead.setText(" ");
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-overview");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService running = refresher;
        running.execute(() -> {
            try {
                reader = newSource.openEntityReader();
            } catch (RuntimeException failure) {
                log.error("could not open the overview", failure);
                SwingUtilities.invokeLater(() -> headline.setText(failure.getMessage()));
                return;
            }
            refreshOnce(newSource);
            running.scheduleWithFixedDelay(
                    () -> refreshOnce(newSource),
                    refreshInterval.toMillis(),
                    refreshInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        });
    }

    /** Stops refreshing and releases the reader, off the EDT. */
    public void closeSession() {
        ScheduledExecutorService stopping = refresher;
        EntityReader closing = reader;
        source = null;
        refresher = null;
        reader = null;
        showEmpty();
        if (stopping == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            stopping.shutdownNow();
            try {
                stopping.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (closing != null) {
                closing.close();
            }
        }, "bbv-overview-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Renders a snapshot. Must be called on the EDT; visible for testing. */
    public void show(OverviewSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        headline.setText(headlineFor(snapshot));
        subhead.setText(subheadFor(snapshot));
        snapshotListener.accept(snapshot);

        tiles.removeAll();
        tiles.add(tile("Targets", EntityFormat.count(snapshot.configuredTargets()),
                snapshot.targetsFailed() + " failed"));
        tiles.add(tile("Actions", EntityFormat.count(snapshot.actions()),
                snapshot.actionsFailed() + " failed"));
        tiles.add(tile("Tests", EntityFormat.count(snapshot.tests()),
                snapshot.testsFailed() + " not passing"));
        tiles.add(tile("Artifacts", EntityFormat.count(snapshot.artifacts()), " "));

        details.removeAll();
        details.add(section("This session counted", sessionRows(snapshot)));
        details.add(Box.createVerticalStrut(12));
        details.add(section("Bazel reported", bazelRows(snapshot)));
        if (!snapshot.topMnemonics().isEmpty()) {
            details.add(Box.createVerticalStrut(12));
            details.add(section("Work by action type", mnemonicRows(snapshot)));
        }
        revalidate();
        repaint();
    }

    private void showEmpty() {
        headline.setText(" ");
        subhead.setText(" ");
        tiles.removeAll();
        details.removeAll();
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(emptyLabel, BorderLayout.CENTER);
        details.add(wrapper);
        revalidate();
        repaint();
    }

    private void refreshOnce(SessionSource opened) {
        EntityReader current = reader;
        if (current == null) {
            return;
        }
        try {
            OverviewSnapshot snapshot = current.overview();
            SwingUtilities.invokeLater(() -> {
                if (source == opened) {
                    show(snapshot);
                }
            });
        } catch (RuntimeException failure) {
            // A refresh failing mid-capture is not fatal: the next tick tries
            // again. Blanking the panel would lose numbers that were true.
            log.warn("overview refresh failed", failure);
        }
    }

    private static String headlineFor(OverviewSnapshot snapshot) {
        String command = snapshot.command().map(text -> "bazel " + text).orElse("Session");
        return snapshot.overallSuccess()
                .map(success -> command + (success ? " — succeeded" : " — failed"))
                // Three states, and the third is not "failed": a build whose
                // BuildFinished never arrived died before it could say, which
                // is a different thing to report.
                .orElse(command + " — outcome not reported");
    }

    private static String subheadFor(OverviewSnapshot snapshot) {
        List<String> parts = new ArrayList<>();
        snapshot.bazelVersion().ifPresent(version -> parts.add("Bazel " + version));
        if (snapshot.elapsedMicros().isPresent()) {
            parts.add(EntityFormat.duration(snapshot.elapsedMicros()) + " elapsed");
        }
        snapshot.exitCodeName().ifPresent(name -> parts.add("exit " + name));
        if (!snapshot.sawLastMessage()) {
            // Bazel marks the end of a stream with a flag, not with a
            // particular event. Without it the capture may simply be missing
            // its tail -- including every aborted target, which arrives after
            // buildFinished.
            parts.add("stream did not reach its end marker");
        }
        return parts.isEmpty() ? " " : String.join("  ·  ", parts);
    }

    private List<String[]> sessionRows(OverviewSnapshot snapshot) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Targets configured", EntityFormat.count(snapshot.targets())});
        rows.add(new String[] {"Configured targets", EntityFormat.count(snapshot.configuredTargets())});
        rows.add(new String[] {"Built", EntityFormat.count(snapshot.targetsBuilt())});
        rows.add(new String[] {"Failed", EntityFormat.count(snapshot.targetsFailed())});
        rows.add(new String[] {"Configured but never completed",
                EntityFormat.count(snapshot.targetsNotCompleted())});
        rows.add(new String[] {"Targets named by an abort",
                EntityFormat.count(snapshot.abortedEvents())});
        rows.add(new String[] {"Actions observed", EntityFormat.count(snapshot.actions())});
        rows.add(new String[] {"All actions published?",
                EntityFormat.yesNo(snapshot.publishesAllActions())});
        return rows;
    }

    private List<String[]> bazelRows(OverviewSnapshot snapshot) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Actions created", EntityFormat.count(snapshot.bazelActionsCreated())});
        // Presented beside the cache hits and never as a ratio: the two are
        // measured differently, and actionsCreated can be smaller than
        // actionsExecuted.
        rows.add(new String[] {"Actions executed", EntityFormat.count(snapshot.bazelActionsExecuted())});
        rows.add(new String[] {"Action cache hits", EntityFormat.count(snapshot.bazelCacheHits())});
        rows.add(new String[] {"Targets configured", EntityFormat.count(snapshot.bazelTargetsConfigured())});
        rows.add(new String[] {"Packages loaded", EntityFormat.count(snapshot.bazelPackagesLoaded())});
        rows.add(new String[] {"Bazel wall time", millis(snapshot.bazelWallMillis())});
        rows.add(new String[] {"Bazel CPU time", millis(snapshot.bazelCpuMillis())});
        rows.add(new String[] {"Analysis phase", millis(snapshot.analysisPhaseMillis())});
        rows.add(new String[] {"Execution phase", millis(snapshot.executionPhaseMillis())});
        rows.add(new String[] {"Critical path", EntityFormat.duration(snapshot.criticalPathMicros())});
        return rows;
    }

    private List<String[]> mnemonicRows(OverviewSnapshot snapshot) {
        List<String[]> rows = new ArrayList<>();
        for (OverviewSnapshot.MnemonicWork work : snapshot.topMnemonics()) {
            rows.add(new String[] {
                work.mnemonic(),
                EntityFormat.count(work.executed()) + " executed, "
                        + EntityFormat.count(work.created()) + " created"
            });
        }
        return rows;
    }

    private static String millis(OptionalLong value) {
        return value.isEmpty()
                ? EntityFormat.UNKNOWN
                : EntityFormat.duration(value.getAsLong() * 1_000L);
    }

    private static JPanel tile(String name, String value, String note) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel nameLabel = new JLabel(name);
        nameLabel.setEnabled(false);
        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(
                Font.BOLD, valueLabel.getFont().getSize() + 8f));
        JLabel noteLabel = new JLabel(note);
        noteLabel.setEnabled(false);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        valueLabel.setAlignmentX(LEFT_ALIGNMENT);
        noteLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(nameLabel);
        panel.add(valueLabel);
        panel.add(noteLabel);
        return panel;
    }

    private static JPanel section(String heading, List<String[]> rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(heading));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints name = new GridBagConstraints();
        name.gridx = 0;
        name.anchor = GridBagConstraints.WEST;
        name.insets = new Insets(1, 4, 1, 12);
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = 1;
        value.weightx = 1;
        value.anchor = GridBagConstraints.WEST;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.insets = new Insets(1, 0, 1, 4);
        for (int row = 0; row < rows.size(); row++) {
            name.gridy = row;
            value.gridy = row;
            JLabel nameLabel = new JLabel(rows.get(row)[0]);
            nameLabel.setEnabled(false);
            panel.add(nameLabel, name);
            panel.add(new JLabel(rows.get(row)[1]), value);
        }
        return panel;
    }

    /** Visible for testing: the headline currently on screen. */
    public String headlineForTest() {
        return headline.getText();
    }

    /** Visible for testing: the subhead currently on screen. */
    public String subheadForTest() {
        return subhead.getText();
    }

    /** Visible for testing: whether a session is attached. */
    public Optional<SessionSource> attachedSession() {
        return Optional.ofNullable(source);
    }
}

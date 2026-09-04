package com.holtherndon.bazelviz.ui.overview;

import com.holtherndon.bazelviz.analysis.CriticalPath.Outcome;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.MetricFormat;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose;
import com.holtherndon.bazelviz.ui.metrics.MetricsService;
import com.holtherndon.bazelviz.ui.nav.NavEntry;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ResponsiveGridLayout;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Overview card: what this build was and what it did.
 *
 * <h2>Live, by re-reading rather than by listening</h2>
 *
 * <p>During a capture the numbers change constantly, so the panel re-reads a whole snapshot on a
 * timer instead of reacting to individual rows. That is what "live updates are coalesced" means
 * here (plan 24, Phase 3): a build writing ten thousand rows a second produces one repaint per
 * interval, and every number on screen comes from the same read, so they are consistent with each
 * other even mid-build.
 *
 * <p>The alternative — updating each tile as its rows arrive — would show a screen whose totals
 * disagreed, which is worse than a screen half a second old.
 *
 * <h2>Two of everything, labelled</h2>
 *
 * <p>Several numbers exist twice: this session's own counts, and Bazel's. They legitimately
 * disagree — {@code actionsExecuted} excludes cache hits, and the elapsed time the user watched
 * differs from Bazel's internal wall time by up to a second — so both are shown under their own
 * names rather than reconciled into one figure that is true of neither.
 */
public final class OverviewPanel extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(OverviewPanel.class);

  /** How often a live session's numbers are re-read. */
  public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);

  private final Duration refreshInterval;

  private final JTextArea headline = WrappingLabel.create(" ");
  private final JTextArea subhead = WrappingLabel.create(" ");
  private final JPanel localHeader = new JPanel();

  /** One responsive summary grid, regardless of which of its two reads supplied a card. */
  private final JPanel tiles = new JPanel(new ResponsiveGridLayout(4, 210, 12, 12));

  private final List<JPanel> snapshotTiles = new ArrayList<>();
  private final List<JPanel> metricTileCards = new ArrayList<>();

  /** Stable detail rows that split only when both columns remain comfortably readable. */
  private final JPanel details = new JPanel(new ResponsiveGridLayout(2, 600, 12, 12));

  private JPanel sessionDetail;
  private JPanel bazelDetail;
  private JPanel mnemonicDetail;
  private JPanel completenessDetail;
  private final JLabel emptyLabel = new JLabel("No session is open.", SwingConstants.CENTER);

  /** Invalidates results independently of how quickly replacement workers start. */
  private final AtomicLong generation = new AtomicLong();

  /** The reader, timer and render state owned by exactly one call to {@link #openSession}. */
  private volatile RefreshContext activeContext;

  /** The most recently detached context's close, retained only until a newer one detaches. */
  private CompletableFuture<Void> detachedClose = CompletableFuture.completedFuture(null);

  private Consumer<OverviewSnapshot> snapshotListener = snapshot -> {};
  private Consumer<NavEntry> navigate = entry -> {};

  /**
   * The scroll pane's view. A plain {@code JPanel} here would hand the scroll pane its own
   * preferred width, which is exactly what let the overview grow wider than the window; see {@link
   * ScrollableViewport}'s javadoc for why. Exposed to tests via {@link #contentForTest()}, which
   * confirms both properties without a session: that it tracks the viewport's width, and that its
   * preferred width shrinks to match once it is actually given one.
   */
  private final ScrollableViewport body = new ScrollableViewport(new BorderLayout());

  private final JScrollPane scroll;
  private final JPanel dashboard = new JPanel();
  private final CardLayout contentLayout = new CardLayout();
  private final JPanel content = new JPanel(contentLayout);
  private final JPanel emptyState = new JPanel(new BorderLayout());
  private PageToolbar pageToolbar;

  public OverviewPanel() {
    this(REFRESH_INTERVAL);
  }

  /**
   * @param refreshInterval how often to re-read. A parameter so a test can drive several intervals
   *     in a second; the coalescing it is there to demonstrate is a property of the interval
   *     existing, not of its length.
   */
  public OverviewPanel(Duration refreshInterval) {
    super(new BorderLayout());
    this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval");

    PlainText.disableHtml(emptyLabel);
    headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize() + 4f));
    subhead.setEnabled(false);

    localHeader.setLayout(new BoxLayout(localHeader, BoxLayout.Y_AXIS));
    localHeader.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
    headline.setAlignmentX(LEFT_ALIGNMENT);
    subhead.setAlignmentX(LEFT_ALIGNMENT);
    localHeader.add(headline);
    localHeader.add(subhead);

    tiles.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
    details.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

    dashboard.setLayout(new BoxLayout(dashboard, BoxLayout.Y_AXIS));
    localHeader.setAlignmentX(LEFT_ALIGNMENT);
    tiles.setAlignmentX(LEFT_ALIGNMENT);
    details.setAlignmentX(LEFT_ALIGNMENT);
    localHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    tiles.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    details.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    dashboard.add(localHeader);
    dashboard.add(tiles);
    dashboard.add(details);

    // The summary is the page, not a modal card. Let it use the viewport
    // instead of manufacturing wide gutters on a large monitor.
    body.add(dashboard, BorderLayout.NORTH);

    scroll = new JScrollPane(body);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);

    emptyLabel.setEnabled(false);
    emptyState.add(emptyLabel, BorderLayout.CENTER);
    content.add(scroll, "dashboard");
    content.add(emptyState, "empty");
    add(content, BorderLayout.CENTER);
    showEmpty();
  }

  /** Moves the invocation headline out of the scrolled dashboard into the common page toolbar. */
  @Override
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbar != null) {
      throw new IllegalStateException("the Overview page toolbar is already installed");
    }
    pageToolbar = toolbar;
    dashboard.remove(localHeader);
    syncPageMetadata();
    dashboard.revalidate();
  }

  /**
   * Observes every snapshot, on the EDT.
   *
   * <p>The window's status bar uses this so its counts come from the same read as the panel's,
   * rather than from a second query that could disagree with what is on screen.
   */
  public void onSnapshot(Consumer<OverviewSnapshot> listener) {
    this.snapshotListener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Where a card sends the reader when it is clicked (plan 17.3).
   *
   * <p>The panel names a destination; the window decides what showing it means. That keeps the
   * overview free of any knowledge of the card layout it lives in.
   */
  public void onNavigate(Consumer<NavEntry> listener) {
    this.navigate = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Adds the cards that come from the metric collection (plan 17.3's critical path, peak
   * concurrency and data completeness).
   *
   * <p>Separate from {@link #show} because these come from a different read on a different
   * schedule: the counts above refresh every two seconds because they are indexed counts, and this
   * one scans every action. Handing both to one method would put the expensive read on the cheap
   * timer.
   */
  public void showMetrics(MetricsService.Result result) {
    Objects.requireNonNull(result, "result");
    showDashboard();
    CriticalPaths paths = result.metrics().invocation().criticalPaths();
    metricTileCards.clear();
    // Two cards, never one. Plan 24 requires the two critical paths to stay
    // distinct, and a single "Critical path" card would be the exact
    // collapse it forbids.
    metricTileCards.add(
        tile(
            paths.bazelDisplayName(),
            paths
                .bazelReportedMicros()
                .value()
                .map(EntityFormat::duration)
                .orElse(EntityFormat.UNKNOWN),
            paths.bazelReportedMicros().isKnown()
                ? paths
                    .bazelReportedMicros()
                    .warning()
                    .orElseGet(
                        () ->
                            paths.bazelComponentCount() == 0
                                ? "component breakdown unavailable"
                                : MetricFormat.count(paths.bazelComponentCount()) + " components")
                : "not reported by this build",
            NavEntry.CRITICAL_PATH));
    metricTileCards.add(
        tile(
            paths.derivedDisplayName(),
            paths
                .derived()
                .filter(derived -> derived.outcome() == Outcome.COMPUTED)
                .map(derived -> EntityFormat.duration(derived.makespanMicros()))
                .orElse(EntityFormat.UNKNOWN),
            paths
                .derived()
                .map(
                    derived ->
                        derived.outcome() == Outcome.COMPUTED
                            ? (derived.isPartial()
                                ? "a lower bound, some actions untimed"
                                : derived.path().size() + " actions")
                            : derived.describe())
                .orElseGet(
                    () -> paths.derivedUnavailableReason().orElse("no confirmed action graph")),
            NavEntry.CRITICAL_PATH));
    metricTileCards.add(
        tile(
            "Peak concurrency",
            result
                .metrics()
                .invocation()
                .concurrency()
                .map(sweep -> EntityFormat.count(sweep.peakActive()))
                .orElse(EntityFormat.UNKNOWN),
            result
                .metrics()
                .invocation()
                .concurrency()
                .map(sweep -> "average " + MetricFormat.ratio(sweep.parallelismFactor()))
                .orElse("nothing was timed"),
            NavEntry.TIMELINE));
    long incomplete = result.metrics().invocation().coverage().incomplete().size();
    metricTileCards.add(
        tile(
            "Findings",
            EntityFormat.count(result.findings().size()),
            incomplete == 0
                ? "every source complete"
                : incomplete + " coverage gaps to read them against",
            NavEntry.FINDINGS));
    rebuildTiles();

    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {"Duration source", result.metrics().durationSource().description()});
    for (var coverage : result.metrics().invocation().coverage().entries()) {
      rows.add(
          new String[] {
            coverage.name(), coverage.describe().substring(coverage.name().length() + 2)
          });
    }
    completenessDetail = section("Data completeness", rows);
    rebuildDetails();
    revalidate();
    repaint();
  }

  /** Opens a session and starts refreshing. Returns immediately. */
  public synchronized void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    headline.setText("Reading…");
    subhead.setText(" ");
    syncPageMetadata();
    showDashboard();

    long mine = generation.incrementAndGet();
    ScheduledExecutorService worker =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-overview");
              thread.setDaemon(true);
              return thread;
            });
    RefreshContext context = new RefreshContext(mine, newSource, worker);
    activeContext = context;
    worker.execute(() -> startRefreshing(context));
  }

  /** Opens this context's reader and installs its timer without touching another context. */
  private void startRefreshing(RefreshContext context) {
    try {
      context.reader =
          Objects.requireNonNull(
              context.source.openEntityReader(), "openEntityReader returned null");
      if (!owns(context)) {
        closeStaleReader(context);
        return;
      }
      refreshOnce(context);
      if (!owns(context)) {
        closeStaleReader(context);
        return;
      }
      if (context.finished) {
        return;
      }
      context.scheduled =
          context.worker.scheduleWithFixedDelay(
              () -> refreshOnce(context),
              refreshInterval.toMillis(),
              refreshInterval.toMillis(),
              TimeUnit.MILLISECONDS);
      // Closing or observing the final event can race the assignment above.
      if (!owns(context) || context.finished) {
        stopRefreshing(context);
        if (!owns(context)) {
          closeStaleReader(context);
        }
      }
    } catch (RuntimeException failure) {
      if (!owns(context)) {
        closeStaleReader(context);
        return;
      }
      log.error("could not open the overview", failure);
      SwingUtilities.invokeLater(
          () -> {
            if (owns(context)) {
              headline.setText(failure.getMessage());
              syncPageMetadata();
            }
          });
    }
  }

  private boolean owns(RefreshContext context) {
    return activeContext == context && generation.get() == context.generation;
  }

  /** Cancels this context's periodic refresh without touching another context. */
  private static void stopRefreshing(RefreshContext context) {
    ScheduledFuture<?> running = context.scheduled;
    context.scheduled = null;
    if (running != null) {
      running.cancel(false);
    }
  }

  /** Stops refreshing and releases the reader, off the EDT. */
  public void closeSession() {
    closeSessionAsync()
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                log.warn("overview session did not close cleanly", failure);
              }
            });
  }

  /** Detaches immediately and completes after this refresh context has stopped. */
  public synchronized CompletionStage<Void> closeSessionAsync() {
    RefreshContext closing = activeContext;
    activeContext = null;
    generation.incrementAndGet();
    showEmpty();
    if (closing == null) {
      return detachedClose;
    }
    closing.finished = true;
    stopRefreshing(closing);
    detachedClose = closeContext(closing).toCompletableFuture();
    return detachedClose;
  }

  private static CompletionStage<Void> closeContext(RefreshContext context) {
    CompletionStage<Void> workerStopped = ExecutorClose.cancelAsync(context.worker, "bbv-overview");
    // Do not close JDBC beneath an operation that ignored interruption. A
    // failed stage reports the bounded leak and deliberately skips this continuation.
    return workerStopped.thenCompose(ignored -> closeReader(context));
  }

  /** Closes a late reader even when the earlier bounded worker close has already failed. */
  private static void closeStaleReader(RefreshContext context) {
    closeReader(context)
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                log.warn("stale overview reader did not close cleanly", failure);
              }
            });
  }

  /** Claims this context's reader once and shares the same asynchronous close with all callers. */
  private static CompletionStage<Void> closeReader(RefreshContext context) {
    synchronized (context) {
      if (context.readerClose == null) {
        EntityReader closingReader = context.reader;
        context.readerClose =
            closingReader == null
                ? CompletableFuture.completedFuture(null)
                : ViewClose.runAsync("bbv-overview-reader-close", closingReader::close)
                    .toCompletableFuture();
      }
      return context.readerClose;
    }
  }

  private static final class RefreshContext {

    private final long generation;
    private final SessionSource source;
    private final ScheduledExecutorService worker;
    private volatile EntityReader reader;
    private CompletableFuture<Void> readerClose;
    private volatile ScheduledFuture<?> scheduled;
    private volatile boolean everRendered;
    private volatile boolean finished;

    private RefreshContext(long generation, SessionSource source, ScheduledExecutorService worker) {
      this.generation = generation;
      this.source = source;
      this.worker = worker;
    }
  }

  /** Renders a snapshot. Must be called on the EDT; visible for testing. */
  public void show(OverviewSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    showDashboard();
    headline.setText(headlineFor(snapshot));
    subhead.setText(subheadFor(snapshot));
    syncPageMetadata();
    snapshotListener.accept(snapshot);

    snapshotTiles.clear();
    // Every target analysis reported, not only the ones that completed. A
    // build interrupted during analysis has configured targets and no
    // completed ones -- six and zero in one measured interrupt -- so a tile
    // counting completions would show that build as having no targets.
    snapshotTiles.add(
        tile(
            "Targets",
            EntityFormat.count(snapshot.targets()),
            targetsNote(snapshot),
            NavEntry.TARGETS));
    // "Executed", because a cache hit publishes no event and is therefore
    // not here. The unqualified word would name a total the source cannot
    // support.
    snapshotTiles.add(
        tile(
            "Actions executed",
            EntityFormat.count(snapshot.actions()),
            snapshot.actionsFailed() + " failed",
            NavEntry.ACTIONS));
    snapshotTiles.add(
        tile(
            "Tests",
            EntityFormat.count(snapshot.tests()),
            snapshot.testsFailed() + " not passing",
            NavEntry.TESTS));
    // No artifacts view exists in plan 17.1's navigation, so this card has
    // nowhere to send a reader and does not pretend to.
    snapshotTiles.add(tile("Artifacts", EntityFormat.count(snapshot.artifacts()), " "));
    rebuildTiles();

    sessionDetail = section("This session counted", sessionRows(snapshot));
    bazelDetail = section("Bazel reported", bazelRows(snapshot));
    mnemonicDetail =
        snapshot.topMnemonics().isEmpty()
            ? null
            : section(mnemonicHeading(snapshot), mnemonicRows(snapshot));
    rebuildDetails();
    revalidate();
    repaint();
  }

  private void showEmpty() {
    headline.setText(" ");
    subhead.setText(" ");
    syncPageMetadata();
    snapshotTiles.clear();
    metricTileCards.clear();
    tiles.removeAll();
    sessionDetail = null;
    bazelDetail = null;
    mnemonicDetail = null;
    completenessDetail = null;
    details.removeAll();
    contentLayout.show(content, "empty");
    revalidate();
    repaint();
  }

  private void showDashboard() {
    contentLayout.show(content, "dashboard");
  }

  private void refreshOnce(RefreshContext context) {
    if (!owns(context)) {
      closeStaleReader(context);
      return;
    }
    if (context.finished) {
      return;
    }
    EntityReader current = context.reader;
    if (current == null) {
      return;
    }
    try {
      OverviewSnapshot snapshot = current.overview();
      if (!owns(context)) {
        closeStaleReader(context);
        return;
      }
      SwingUtilities.invokeLater(
          () -> {
            if (owns(context)) {
              context.everRendered = true;
              show(snapshot);
            }
          });
      if (snapshot.sawLastMessage()) {
        // The stream ended, so every number here is final. Aborted
        // events arrive after buildFinished and the flag comes after
        // them, so this is the first moment nothing more can arrive.
        context.finished = true;
        stopRefreshing(context);
      }
    } catch (RuntimeException failure) {
      // A refresh failing mid-capture is not fatal: the next tick tries
      // again, and blanking the panel would lose numbers that were true.
      // But the *first* one has nothing to preserve, and staying silent
      // left the panel reading "Reading…" for ever with no explanation.
      log.warn("overview refresh failed", failure);
      if (!owns(context)) {
        closeStaleReader(context);
        return;
      }
      if (!context.everRendered && owns(context)) {
        SwingUtilities.invokeLater(
            () -> {
              if (owns(context) && !context.everRendered) {
                headline.setText("The overview could not be read.");
                subhead.setText(failure.getMessage());
                syncPageMetadata();
              }
            });
      }
    }
  }

  /** What is worth saying under the target count, when there is something. */
  private static String targetsNote(OverviewSnapshot snapshot) {
    List<String> parts = new ArrayList<>();
    if (snapshot.targetsFailed() > 0) {
      parts.add(snapshot.targetsFailed() + " failed");
    }
    if (snapshot.targetsNotCompleted() > 0) {
      parts.add(snapshot.targetsNotCompleted() + " not completed");
    }
    return parts.isEmpty() ? " " : String.join(", ", parts);
  }

  private static String headlineFor(OverviewSnapshot snapshot) {
    String command = snapshot.command().map(text -> "bazel " + text).orElse("Session");
    if (snapshot.wasInterrupted()) {
      // Its own state. The user stopped this build; calling that "failed"
      // tells them something about their own action that they know to be
      // untrue, and the subhead beside it already reads "exit
      // INTERRUPTED" -- so the screen would contradict itself.
      return command + " — interrupted";
    }
    return snapshot
        .overallSuccess()
        .map(success -> command + (success ? " — succeeded" : " — failed"))
        // A fourth state, and it is not "failed" either: a build whose
        // BuildFinished never arrived died before it could say.
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

  private void syncPageMetadata() {
    if (pageToolbar == null) {
      return;
    }
    String primary = headline.getText() == null ? "" : headline.getText().strip();
    String secondary = subhead.getText() == null ? "" : subhead.getText().strip();
    pageToolbar.setMetadata(
        primary + (primary.isEmpty() || secondary.isEmpty() ? "" : " · ") + secondary);
  }

  private List<String[]> sessionRows(OverviewSnapshot snapshot) {
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {"Targets configured", EntityFormat.count(snapshot.targets())});
    rows.add(new String[] {"Configured targets", EntityFormat.count(snapshot.configuredTargets())});
    rows.add(new String[] {"Built", EntityFormat.count(snapshot.targetsBuilt())});
    rows.add(new String[] {"Failed", EntityFormat.count(snapshot.targetsFailed())});
    rows.add(
        new String[] {
          "Configured but never completed", EntityFormat.count(snapshot.targetsNotCompleted())
        });
    // Two numbers, because they are two facts. Abort events ride four id
    // kinds and patterns abort too, so an event count is not a target
    // count -- and the target count is the one the word "targets" belongs
    // to.
    rows.add(
        new String[] {"Targets named by an abort", EntityFormat.count(snapshot.abortedTargets())});
    rows.add(new String[] {"Abort events recorded", EntityFormat.count(snapshot.abortedEvents())});
    rows.add(new String[] {"Actions with an event", EntityFormat.count(snapshot.actions())});
    rows.add(
        new String[] {
          "All actions published?", EntityFormat.yesNo(snapshot.publishesAllActions())
        });
    return rows;
  }

  private List<String[]> bazelRows(OverviewSnapshot snapshot) {
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {"Actions created", EntityFormat.count(snapshot.bazelActionsCreated())});
    // Presented beside the cache hits and never as a ratio: the two are
    // measured differently, and actionsCreated can be smaller than
    // actionsExecuted.
    rows.add(
        new String[] {"Actions executed", EntityFormat.count(snapshot.bazelActionsExecuted())});
    rows.add(new String[] {"Action cache hits", EntityFormat.count(snapshot.bazelCacheHits())});
    rows.add(
        new String[] {"Targets configured", EntityFormat.count(snapshot.bazelTargetsConfigured())});
    rows.add(new String[] {"Packages loaded", EntityFormat.count(snapshot.bazelPackagesLoaded())});
    rows.add(new String[] {"Bazel wall time", millis(snapshot.bazelWallMillis())});
    rows.add(new String[] {"Bazel CPU time", millis(snapshot.bazelCpuMillis())});
    rows.add(new String[] {"Analysis phase", millis(snapshot.analysisPhaseMillis())});
    rows.add(new String[] {"Execution phase", millis(snapshot.executionPhaseMillis())});
    rows.add(
        new String[] {
          "Bazel-reported critical path", EntityFormat.duration(snapshot.criticalPathMicros())
        });
    return rows;
  }

  /**
   * The breakdown's heading, which says when the breakdown is partial.
   *
   * <p>On Bazel 6.5.0 and 7.6.1 a mnemonic whose actions were all cache hits is absent from {@code
   * actionData} entirely (M4), so the list is a subset with nothing in the data to say so. A chart
   * that looked complete and was not is exactly what rule 13 is about.
   */
  private static String mnemonicHeading(OverviewSnapshot snapshot) {
    boolean partial =
        snapshot
            .bazelVersion()
            .map(version -> version.startsWith("6.") || version.startsWith("7."))
            .orElse(false);
    return partial
        ? "Work by action type (partial: this Bazel omits fully-cached types)"
        : "Work by action type";
  }

  private List<String[]> mnemonicRows(OverviewSnapshot snapshot) {
    List<String[]> rows = new ArrayList<>();
    for (OverviewSnapshot.MnemonicWork work : snapshot.topMnemonics()) {
      rows.add(
          new String[] {
            work.mnemonic(),
            EntityFormat.count(work.executed())
                + " executed, "
                + EntityFormat.count(work.created())
                + " created"
          });
    }
    return rows;
  }

  private static String millis(OptionalLong value) {
    return value.isEmpty()
        ? EntityFormat.UNKNOWN
        : EntityFormat.duration(value.getAsLong() * 1_000L);
  }

  private void rebuildTiles() {
    tiles.removeAll();
    snapshotTiles.forEach(tiles::add);
    metricTileCards.forEach(tiles::add);
  }

  private void rebuildDetails() {
    details.removeAll();
    if (sessionDetail != null) {
      details.add(sessionDetail);
    }
    if (bazelDetail != null) {
      details.add(bazelDetail);
    }
    if (completenessDetail != null) {
      details.add(completenessDetail);
    }
    if (mnemonicDetail != null) {
      details.add(mnemonicDetail);
    }
  }

  private JPanel tile(String name, String value, String note) {
    return tile(name, value, note, null);
  }

  /**
   * One dashboard card.
   *
   * <p>Plan 17.3: "every card must navigate to a filtered detailed view". A card with a destination
   * becomes clickable and says so in its tooltip; one without stays inert rather than pretending.
   * That is a real distinction here — a count of artifacts has no view in plan 17.1's navigation to
   * send a reader to.
   *
   * @param destination the card the reader lands on, or null when the number has nowhere to go
   */
  private JPanel tile(String name, String value, String note, NavEntry destination) {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    JTextArea nameLabel = WrappingLabel.create(name);
    nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
    nameLabel.setEnabled(false);
    JLabel valueLabel = PlainText.disableHtml(new JLabel(value));
    valueLabel.setFont(
        valueLabel.getFont().deriveFont(Font.BOLD, valueLabel.getFont().getSize() + 8f));
    JTextArea noteLabel = WrappingLabel.create(note);
    noteLabel.setEnabled(false);
    nameLabel.setAlignmentX(LEFT_ALIGNMENT);
    valueLabel.setAlignmentX(LEFT_ALIGNMENT);
    noteLabel.setAlignmentX(LEFT_ALIGNMENT);
    panel.add(nameLabel);
    panel.add(valueLabel);
    panel.add(noteLabel);
    if (destination != null) {
      installNavigation(panel, nameLabel, valueLabel, noteLabel, name, value, destination);
    }
    return panel;
  }

  /** Makes the whole visible card, including its child labels, one accessible action. */
  private void installNavigation(
      JPanel panel,
      JComponent nameLabel,
      JComponent valueLabel,
      JComponent noteLabel,
      String name,
      String value,
      NavEntry destination) {
    String help = "Open " + destination.title();
    Runnable open = () -> navigate.accept(destination);
    MouseAdapter opener =
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            panel.requestFocusInWindow();
            open.run();
          }
        };
    Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    for (JComponent target : List.of(panel, nameLabel, valueLabel, noteLabel)) {
      target.setCursor(hand);
      target.setToolTipText(PlainText.tooltip(help));
      target.addMouseListener(opener);
    }

    panel.setFocusable(true);
    panel.getAccessibleContext().setAccessibleName(name + ": " + value);
    panel.getAccessibleContext().setAccessibleDescription(help);
    String action = "open-card";
    panel.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), action);
    panel.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), action);
    panel
        .getActionMap()
        .put(
            action,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                open.run();
              }
            });
  }

  private static JPanel section(String heading, List<String[]> rows) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder(heading));
    panel.setAlignmentX(LEFT_ALIGNMENT);
    GridBagConstraints name = new GridBagConstraints();
    name.gridx = 0;
    name.weightx = 0.38;
    name.anchor = GridBagConstraints.NORTHWEST;
    name.fill = GridBagConstraints.HORIZONTAL;
    name.insets = new Insets(1, 4, 1, 12);
    GridBagConstraints value = new GridBagConstraints();
    value.gridx = 1;
    value.weightx = 0.62;
    value.anchor = GridBagConstraints.NORTHWEST;
    value.fill = GridBagConstraints.HORIZONTAL;
    value.insets = new Insets(1, 0, 1, 4);
    for (int row = 0; row < rows.size(); row++) {
      name.gridy = row;
      value.gridy = row;
      JTextArea nameLabel = WrappingLabel.create(rows.get(row)[0]);
      nameLabel.setEnabled(false);
      panel.add(nameLabel, name);
      JTextArea valueLabel = WrappingLabel.create(rows.get(row)[1]);
      valueLabel.setToolTipText(PlainText.tooltip(rows.get(row)[1]));
      panel.add(valueLabel, value);
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
    RefreshContext context = activeContext;
    return context == null ? Optional.empty() : Optional.of(context.source);
  }

  /**
   * Visible for testing: the scroll pane's view, to confirm it tracks the viewport's width instead
   * of overflowing it (plan 26 rule 12 — reflowing must not clip or drop a tile, so the fix is that
   * the content narrows, never that a tile becomes unreadable).
   */
  public ScrollableViewport contentForTest() {
    return body;
  }

  /** Visible for testing: the responsive summary-card grid. */
  JPanel tilesForTest() {
    return tiles;
  }

  /** Visible for testing: the responsive detail-card grid. */
  JPanel detailsForTest() {
    return details;
  }

  /** Visible for testing: the real scroll pane used at narrow widths. */
  JScrollPane scrollForTest() {
    return scroll;
  }

  /** Visible for testing: the dashboard that fills the scroll viewport. */
  JPanel dashboardForTest() {
    return dashboard;
  }

  /** Visible for testing: the true full-pane empty state, not a dashboard card. */
  JPanel emptyStateForTest() {
    return emptyState;
  }
}

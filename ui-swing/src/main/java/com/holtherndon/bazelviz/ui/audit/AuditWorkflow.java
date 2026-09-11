package com.holtherndon.bazelviz.ui.audit;

import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Result;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.SavedOperation;
import com.holtherndon.bazelviz.ui.audit.ComparisonSources.Source;
import com.holtherndon.bazelviz.ui.capture.CaptureStatusModel;
import com.holtherndon.bazelviz.ui.capture.InstrumentationPlanDialog;
import com.holtherndon.bazelviz.ui.repro.HermeticityView;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Coordinates explicit audit actions without making the page depend on storage or SSH. EDT API. */
public final class AuditWorkflow {
  public interface Host {
    void status(CaptureStatusModel status);

    void ready();

    void reveal();

    void openSession(Path directory);
  }

  private final Window owner;
  private final HermeticityView view;
  private final Path auditsRoot;
  private final SessionMutationCoordinator sessions;
  private final Host host;
  private final ExecutorService fileWorker =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("bbv-audit-record").factory());
  private CompletableFuture<Void> fileWork = CompletableFuture.completedFuture(null);
  private AuditLaunchController launch;
  private Source a;
  private Source b;
  private long sourceGeneration;
  private boolean closed;

  public AuditWorkflow(
      Window owner,
      HermeticityView view,
      Path auditsRoot,
      SessionMutationCoordinator sessions,
      Host host) {
    this.owner = owner;
    this.view = view;
    this.auditsRoot = auditsRoot;
    this.sessions = sessions;
    this.host = host;
    view.onChooseA(() -> choose(true));
    view.onChooseB(() -> choose(false));
    view.onCompare(this::compare);
  }

  public void installToolbar(PageToolbar toolbar) {
    JButton open = new JButton("Open audit…");
    open.setToolTipText("Inspect a saved audit; this never resumes builds or cleans files.");
    open.addActionListener(
        event -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setDialogTitle("Open saved audit directory");
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION)
            loadAudit(chooser.getSelectedFile().toPath(), List.of());
        });
    toolbar.addAction(open);
  }

  /** Asked before acquiring a lease or contacting Bazel. Final exact-command review follows. */
  public boolean confirmProtocol() {
    return JOptionPane.showConfirmDialog(
            owner,
            "Run a controlled repeat-build check?\n\n"
                + "This runs two full builds with a clean between them, using a private output"
                + " base.\n"
                + "All bazelrc files are ignored and build-cache reuse is disabled. This is not"
                + " your\n"
                + "ordinary rc-configured build. Source files must stay unchanged during the"
                + " check.\n"
                + "Bazel 9.2.0 is required. Builds still execute repository code on the selected"
                + " machine.\n\n"
                + "The next screen shows the exact commands, changes and capture settings.",
            "Check reproducibility",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.OK_OPTION;
  }

  /**
   * Receives the reservation after successful enqueue, including later preflight failures. The
   * caller retains responsibility if this method throws before enqueueing.
   */
  public void start(CaptureRequest request, AutoCloseable lease) {
    if (isBusy() || closed)
      throw new IllegalStateException("An audit is already active or closed.");
    ReproducibilityCoordinator coordinator =
        new ReproducibilityCoordinator(
            request,
            auditsRoot,
            lease,
            step ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (!closed)
                        host.status(
                            CaptureStatusModel.idle()
                                .withPhase(
                                    CaptureStatusModel.Phase.CAPTURING,
                                    "Reproducibility: "
                                        + step.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
                    }));
    launch =
        new AuditLaunchController(
            coordinator,
            SwingUtilities::invokeLater,
            new AuditLaunchController.Listener() {
              @Override
              public void reviewReady(Review review) {
                review(review);
              }

              @Override
              public void finished(Result result) {
                host.ready();
                CaptureStatusModel.Phase phase =
                    switch (result.state()) {
                      case CAPTURED -> CaptureStatusModel.Phase.DONE;
                      case CANCELLED -> CaptureStatusModel.Phase.CANCELLED;
                      default -> CaptureStatusModel.Phase.FAILED;
                    };
                host.status(
                    CaptureStatusModel.idle()
                        .withPhase(
                            phase,
                            "Audit "
                                + result.state()
                                + " · private-base cleanup "
                                + result.cleanup()));
                loadAudit(result.directory(), result.notices());
              }

              @Override
              public void failed(Throwable failure) {
                host.ready();
                host.status(
                    CaptureStatusModel.idle()
                        .withPhase(CaptureStatusModel.Phase.FAILED, message(failure)));
                alert(failure);
              }
            });
    try {
      launch.preflight();
    } catch (RuntimeException failure) {
      launch.closeAsync();
      throw failure;
    }
  }

  private void review(Review review) {
    if (closed) return;
    AuditReviewDialog dialog = new AuditReviewDialog(owner, review);
    dialog.setVisible(true);
    switch (dialog.choice()) {
      case RUN_BOTH -> launch.launch(review);
      case CANCEL ->
          launch
              .closeAsync()
              .whenComplete(
                  (ignored, failure) ->
                      SwingUtilities.invokeLater(
                          () -> {
                            if (closed) return;
                            host.ready();
                            host.status(CaptureStatusModel.idle());
                            if (failure != null) alert(failure);
                          }));
      case REVIEW_A, REVIEW_B -> {
        InstrumentationPlanDialog capture =
            new InstrumentationPlanDialog(
                owner,
                dialog.choice() == AuditReviewDialog.Choice.REVIEW_A ? review.a() : review.b(),
                "Accept capture settings");
        capture.setVisible(true);
        switch (capture.choice()) {
          case RESOLVE ->
              launch.replan(
                  request ->
                      request.resolving(
                          capture.resolvedKind().orElseThrow(),
                          capture.resolutionId().orElseThrow()));
          case VETO ->
              launch.replan(request -> request.vetoing(capture.vetoedCapability().orElseThrow()));
          case LAUNCH, CANCEL -> SwingUtilities.invokeLater(() -> review(review));
        }
      }
    }
  }

  public boolean isBusy() {
    return launch != null && launch.isBusy();
  }

  public void cancel() {
    if (launch != null) launch.cancel();
  }

  public CompletionStage<Void> closeAsync() {
    closed = true;
    ++sourceGeneration;
    fileWorker.shutdown();
    return CompletableFuture.allOf(
        fileWork,
        view.closeAsync().toCompletableFuture(),
        launch == null
            ? CompletableFuture.completedFuture(null)
            : launch.closeAsync().toCompletableFuture());
  }

  private void choose(boolean left) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Choose execution log or managed session " + (left ? "A" : "B"));
    chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
    ++sourceGeneration;
    Source source = Source.selected(chooser.getSelectedFile().toPath());
    if (left) a = source;
    else b = source;
    view.setContextNotes(
        List.of(
            "Manually selected logs: unchanged source, tool and host state are not established."
                + " This comparison does not prove hermeticity."));
    updateSources();
  }

  private void updateSources() {
    view.setSources(a == null ? "" : a.path().toString(), b == null ? "" : b.path().toString());
    view.onOpenRunA(
        a != null && a.session().isPresent()
            ? () -> host.openSession(a.session().orElseThrow())
            : null);
    view.onOpenRunB(
        b != null && b.session().isPresent()
            ? () -> host.openSession(b.session().orElseThrow())
            : null);
  }

  private void compare() {
    if (closed || a == null || b == null) return;
    Source left = a;
    Source right = b;
    view.openComparison(
        cancelled ->
            ComparisonSources.open(
                left, right, auditsRoot.resolve("comparisons"), sessions, cancelled),
        left.path().toString(),
        right.path().toString());
  }

  private void loadAudit(Path directory, List<String> extraNotes) {
    if (closed) return;
    long generation = ++sourceGeneration;
    CompletableFuture<SavedOperation> read =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return ReproducibilityCoordinator.readSavedOperation(directory);
              } catch (IOException failure) {
                throw new CompletionException(failure);
              }
            },
            fileWorker);
    fileWork =
        fileWork
            .handle((ignored, failure) -> null)
            .thenCombine(read.handle((saved, failure) -> null), (first, second) -> null);
    read.whenComplete(
        (saved, failure) ->
            SwingUtilities.invokeLater(
                () -> {
                  if (closed || generation != sourceGeneration) return;
                  if (failure != null) {
                    alert(failure);
                    return;
                  }
                  a =
                      saved
                          .executionLogA()
                          .map(
                              path ->
                                  new Source(path, saved.sessionA(), saved.executionLogSha256A()))
                          .orElse(null);
                  b =
                      saved
                          .executionLogB()
                          .map(
                              path ->
                                  new Source(path, saved.sessionB(), saved.executionLogSha256B()))
                          .orElse(null);
                  List<String> notes = new ArrayList<>(extraNotes);
                  notes.addAll(saved.notices());
                  notes.add("Saved audit: " + saved.directory());
                  notes.add("Last recorded step: " + saved.step());
                  notes.add(
                      "Capture state: "
                          + saved.state()
                          + "; private-base cleanup: "
                          + saved.cleanup());
                  notes.add(
                      "Controlled rc-free experiment. Two matching runs do not prove hermeticity.");
                  if (!saved.state().equals("CAPTURED"))
                    notes.add(
                        "This experiment did not complete successfully. Do not treat matching"
                            + " actions as a successful repeat-build check.");
                  if (!saved.cleanup().equals("REMOVED")
                      && !saved.cleanup().equals("NOT_ALLOCATED"))
                    notes.add(
                        "Private-base cleanup needs review on the workspace machine: "
                            + saved.privateBase()
                            + ". Reopening this record does not execute cleanup or reconnect SSH.");
                  if (a == null || b == null)
                    notes.add(
                        "One or both verified execution logs are unavailable. Retained run sessions"
                            + " can still be inspected.");
                  view.setContextNotes(notes);
                  updateSources();
                  // Failed or cancelled runs remain inspectable even when no complete log was
                  // preserved.
                  view.onOpenRunA(
                      saved
                          .sessionA()
                          .map(path -> (Runnable) () -> host.openSession(path))
                          .orElse(null));
                  view.onOpenRunB(
                      saved
                          .sessionB()
                          .map(path -> (Runnable) () -> host.openSession(path))
                          .orElse(null));
                  host.reveal();
                  if (a != null && b != null) compare();
                }));
  }

  private void alert(Throwable failure) {
    JOptionPane.showMessageDialog(
        owner, message(failure), "Reproducibility check", JOptionPane.ERROR_MESSAGE);
  }

  private static String message(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
}

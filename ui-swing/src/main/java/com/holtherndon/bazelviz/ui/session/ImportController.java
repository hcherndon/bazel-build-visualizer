package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportProgress;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.core.id.SessionId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one {@link BepImporter} run from the UI: off the EDT, cancellable, and reporting through
 * {@link Listener} on whichever dispatcher the caller supplies.
 *
 * <h2>Threads</h2>
 *
 * <ul>
 *   <li>{@link #start} and {@link #cancel} are called on the EDT and return immediately. Neither
 *       touches the filesystem.
 *   <li>The import itself — detection, hashing, copying, parsing, SQL — runs on {@code worker}.
 *   <li>Every {@link Listener} callback is handed to {@code uiDispatcher}. In the application that
 *       is {@code SwingUtilities::invokeLater}; in the tests it is a direct executor, which is what
 *       makes this class testable headlessly.
 * </ul>
 *
 * <h2>Cancellation</h2>
 *
 * <p>{@link #cancel} sets a flag the importer polls between records. It stops on a record boundary,
 * writes a final checkpoint and leaves the session resumable — so cancelling is not the same as
 * discarding, and the resulting session can be reopened or resumed. The button therefore really
 * cancels rather than merely detaching the UI from a run that keeps going.
 */
public final class ImportController {

  private static final Logger log = LoggerFactory.getLogger(ImportController.class);

  /** Receives the lifecycle of one run, always on the UI dispatcher. */
  public interface Listener {

    /** The run has begun. {@code source} is the file, or the session being resumed. */
    void importStarted(Path source, boolean resuming);

    /** A throttled progress sample. */
    void importProgress(ImportProgressModel.Snapshot snapshot);

    /** The run ended, including when it ended cancelled or truncated. */
    void importFinished(ImportResult result);

    /** The run could not be performed at all. */
    void importFailed(Path source, Throwable failure);
  }

  private final BepImporter importer;
  private final Executor worker;
  private final Executor uiDispatcher;
  private final ImportProgressModel progress;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean cancelRequested = new AtomicBoolean();

  public ImportController(
      BepImporter importer, Executor worker, Executor uiDispatcher, ImportProgressModel progress) {
    this.importer = Objects.requireNonNull(importer, "importer");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  /** True while a run is in flight. */
  public boolean isRunning() {
    return running.get();
  }

  /** True once {@link #cancel} has been called for the current run. */
  public boolean isCancelRequested() {
    return cancelRequested.get();
  }

  /**
   * Starts importing {@code source}.
   *
   * @return false when a run is already in flight; the caller must not start a second one, because
   *     two imports writing two sessions would fight over the same progress view
   */
  public boolean start(Path source, Listener listener) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(listener, "listener");
    return begin(
        source,
        false,
        listener,
        () ->
            importer.importFile(
                source,
                SessionId.random(),
                sample -> publish(listener, sample),
                cancelRequested::get));
  }

  /**
   * Resumes the interrupted import in {@code sessionRoot} — the UI face of Phase 1 exit criterion
   * 2. The source is not re-read from the beginning; see {@link BepImporter#resume}.
   */
  public boolean resume(Path sessionRoot, Listener listener) {
    Objects.requireNonNull(sessionRoot, "sessionRoot");
    Objects.requireNonNull(listener, "listener");
    return begin(
        sessionRoot,
        true,
        listener,
        () ->
            importer.resume(
                sessionRoot, sample -> publish(listener, sample), cancelRequested::get));
  }

  /** Asks the running import to stop at the next record boundary. */
  public void cancel() {
    cancelRequested.set(true);
  }

  private boolean begin(Path source, boolean resuming, Listener listener, ImportCall call) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    cancelRequested.set(false);
    progress.start();
    dispatch(() -> listener.importStarted(source, resuming));
    worker.execute(
        () -> {
          try {
            ImportResult result = call.run();
            dispatch(() -> listener.importFinished(result));
          } catch (Throwable failure) {
            // Including Error: an import that dies must say so rather than
            // leaving a progress bar spinning forever.
            log.error("import of {} failed", source, failure);
            dispatch(() -> listener.importFailed(source, failure));
          } finally {
            running.set(false);
          }
        });
    return true;
  }

  private void dispatch(Runnable action) {
    uiDispatcher.execute(action);
  }

  /**
   * Folds a sample into the model on the importing thread — where the elapsed intervals the rate is
   * computed from are the import's own, not the EDT's queueing delay — and hands the immutable
   * snapshot to the UI.
   */
  private void publish(Listener listener, ImportProgress sample) {
    ImportProgressModel.Snapshot snapshot = progress.update(sample);
    dispatch(() -> listener.importProgress(snapshot));
  }

  @FunctionalInterface
  private interface ImportCall {
    ImportResult run() throws Exception;
  }
}

package com.holtherndon.bazelviz.ui.repro;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns one comparison on one virtual worker; callbacks never own its database or files. */
public final class ComparisonController {
  @FunctionalInterface
  public interface Factory {
    ReproComparison open(BooleanSupplier cancelled) throws IOException;
  }

  public interface Listener {
    void opened(ReproComparison.Summary summary);

    void pageLoaded(ReproComparison.Page page, long afterId);

    void detailsLoaded(ReproComparison.Details details, long offset);

    void failed(String message);
  }

  private final Executor toUi;
  private final Listener listener;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private final Object monitor = new Object();
  private final Thread worker;
  private Request pending;
  private long revision;

  public ComparisonController(Factory factory, Executor toUi, Listener listener) {
    this.toUi = Objects.requireNonNull(toUi, "toUi");
    this.listener = Objects.requireNonNull(listener, "listener");
    Objects.requireNonNull(factory, "factory");
    worker = Thread.ofVirtual().name("bbv-repro-comparison").unstarted(() -> run(factory));
    worker.start();
  }

  /**
   * The latest navigation request replaces queued obsolete requests; result data is never dropped.
   */
  public void page(FilterExpression filter, long afterId, int limit) {
    Objects.requireNonNull(filter, "filter");
    request(new PageRequest(filter, afterId, limit));
  }

  public void details(long id, long offset, int limit) {
    request(new DetailsRequest(id, offset, limit));
  }

  private void request(Request request) {
    synchronized (monitor) {
      if (closed.get()) return;
      pending = request;
      revision++;
      monitor.notifyAll();
    }
  }

  /** Nonblocking. Completion means the worker has closed the comparison and its private files. */
  public CompletionStage<Void> closeAsync() {
    if (closed.compareAndSet(false, true)) {
      synchronized (monitor) {
        pending = null;
        revision++;
        monitor.notifyAll();
      }
      worker.interrupt();
    }
    return completion;
  }

  private void run(Factory factory) {
    ReproComparison comparison = null;
    Exception operationFailure = null;
    try {
      comparison = factory.open(closed::get);
      if (!closed.get()) {
        ReproComparison.Summary summary = comparison.summary();
        post(() -> listener.opened(summary));
      }
      while (!closed.get()) {
        Request request;
        long wanted;
        synchronized (monitor) {
          while (pending == null && !closed.get()) monitor.wait();
          if (closed.get()) break;
          request = pending;
          pending = null;
          wanted = revision;
        }
        try {
          if (request instanceof PageRequest page) {
            ReproComparison.Page result =
                comparison.page(page.filter(), page.afterId(), page.limit());
            postCurrent(wanted, () -> listener.pageLoaded(result, page.afterId()));
          } else if (request instanceof DetailsRequest details) {
            ReproComparison.Details result =
                comparison.details(details.id(), details.offset(), details.limit());
            postCurrent(wanted, () -> listener.detailsLoaded(result, details.offset()));
          }
        } catch (IOException | RuntimeException failure) {
          postCurrent(wanted, () -> listener.failed(message(failure)));
        }
      }
    } catch (InterruptedException interrupted) {
      if (!closed.get()) operationFailure = interrupted;
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException failure) {
      // Cancellation suppresses stale UI callbacks, not factory rollback failures. Until open
      // returns, the factory alone owns its private resources, including any failed cleanup.
      operationFailure = failure;
    } finally {
      // Cancellation interrupts a query/wait, not resource ownership. Clear the interrupt while
      // closing so it cannot mask cleanup failures as a normal cancelled operation.
      boolean interrupted = Thread.interrupted();
      Exception closeFailure = null;
      if (comparison != null) {
        try {
          comparison.close();
        } catch (IOException | RuntimeException cleanupFailure) {
          closeFailure = cleanupFailure;
        }
      }
      if (operationFailure != null) {
        String detail = message(operationFailure);
        post(() -> listener.failed(detail));
      }
      if (closeFailure != null) {
        Exception reported = closeFailure;
        post(() -> listener.failed(message(reported)));
        completion.completeExceptionally(closeFailure);
      } else if (hasCleanupFailure(operationFailure)) {
        // A factory may attach a failed rollback to its opening failure. Do not hide it.
        completion.completeExceptionally(operationFailure);
      } else completion.complete(null);
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void postCurrent(long wanted, Runnable callback) {
    post(
        () -> {
          synchronized (monitor) {
            if (wanted != revision) return;
          }
          callback.run();
        });
  }

  private void post(Runnable callback) {
    toUi.execute(
        () -> {
          if (!closed.get()) callback.run();
        });
  }

  private static String message(Exception failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? "Comparison could not be completed." : message;
  }

  private static boolean hasCleanupFailure(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      // The backend may wrap a database error after attaching its rollback failure. Preserve
      // that evidence even when suppression lives on a cause rather than the outer exception.
      if (cause.getSuppressed().length > 0) return true;
    }
    return false;
  }

  private sealed interface Request permits PageRequest, DetailsRequest {}

  private record PageRequest(FilterExpression filter, long afterId, int limit) implements Request {}

  private record DetailsRequest(long id, long offset, int limit) implements Request {}
}

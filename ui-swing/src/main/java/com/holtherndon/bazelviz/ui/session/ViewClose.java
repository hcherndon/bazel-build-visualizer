package com.holtherndon.bazelviz.ui.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Small shared plumbing for view teardown that must never wait on Swing's EDT. */
public final class ViewClose {

  private ViewClose() {}

  /** Runs one finite cleanup on a daemon virtual thread and exposes when it has finished. */
  public static CompletionStage<Void> runAsync(String threadName, Runnable cleanup) {
    Objects.requireNonNull(threadName, "threadName");
    Objects.requireNonNull(cleanup, "cleanup");
    CompletableFuture<Void> finished = new CompletableFuture<>();
    Thread.ofVirtual()
        .name(threadName)
        .start(
            () -> {
              try {
                cleanup.run();
                finished.complete(null);
              } catch (Throwable failure) {
                finished.completeExceptionally(failure);
              }
            });
    return finished;
  }
}

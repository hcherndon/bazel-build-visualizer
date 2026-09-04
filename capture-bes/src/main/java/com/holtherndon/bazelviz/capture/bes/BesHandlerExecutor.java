package com.holtherndon.bazelviz.capture.bes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The fixed, bounded executor for blocking BES handlers. */
final class BesHandlerExecutor extends ThreadPoolExecutor {

  BesHandlerExecutor(BesResourceLimits limits, BesResources resources) {
    super(
        limits.handlerThreads(),
        limits.handlerThreads(),
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(limits.handlerQueueCapacity()),
        runnable -> {
          Thread thread = new Thread(runnable, "bbv-bes-handler");
          thread.setDaemon(true);
          return thread;
        },
        (task, rejectedExecutor) -> {
          resources.noteHandlerTaskRefusal();
          throw new RejectedExecutionException(
              "embedded BES handler capacity exhausted: "
                  + limits.handlerThreads()
                  + " active threads and "
                  + limits.handlerQueueCapacity()
                  + " queued tasks");
        });
  }
}

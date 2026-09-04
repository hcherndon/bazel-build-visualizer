package com.holtherndon.bazelviz.capture.bes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A sink that keeps what it is given, so a test can assert on it.
 *
 * <p>It journals nothing, but it does model the two properties of the real pipeline that the
 * server's correctness depends on: submission is bounded, and the {@code onJournaled} callback runs
 * on a different thread from the caller. Running the callback inline on the gRPC thread would hide
 * every ordering bug the real pipeline can produce, which is exactly what this needs to expose.
 */
final class RecordingSink implements RawEventSink {

  private final List<RawBesEvent> events = new ArrayList<>();
  private final List<BesStreamKey> opened = new ArrayList<>();
  private final List<BesStreamState> ended = new ArrayList<>();
  private final BlockingQueue<JournalWork> journalWork;
  private final Thread journalThread;

  private volatile boolean rejecting;
  private volatile long journalDelayMillis;
  private volatile boolean running = true;
  private volatile CountDownLatch journalGate = new CountDownLatch(0);
  private volatile CountDownLatch streamEndGate = new CountDownLatch(0);
  private volatile CountDownLatch streamEndEntered = new CountDownLatch(1);
  private volatile Throwable journalFailure;

  private record JournalWork(RawBesEvent event, SubmissionCallback callback) {}

  RecordingSink() {
    this(1024);
  }

  RecordingSink(int capacity) {
    this.journalWork = new ArrayBlockingQueue<>(capacity);
    this.journalThread = new Thread(this::drain, "recording-sink-journal");
    this.journalThread.setDaemon(true);
    this.journalThread.start();
  }

  @Override
  public void submit(RawBesEvent event, SubmissionCallback callback)
      throws InterruptedException, CaptureRejectedException {
    if (rejecting) {
      throw new CaptureRejectedException("this sink was told to reject");
    }
    synchronized (events) {
      events.add(event);
    }
    journalWork.put(new JournalWork(event, callback));
  }

  @Override
  public void streamOpened(BesStreamKey key) {
    synchronized (opened) {
      opened.add(key);
    }
  }

  @Override
  public void streamEnded(BesStreamState finalState) {
    streamEndEntered.countDown();
    awaitUninterruptibly(streamEndGate);
    synchronized (ended) {
      ended.add(finalState);
    }
  }

  private void drain() {
    while (running) {
      JournalWork work = null;
      try {
        work = journalWork.poll(50, TimeUnit.MILLISECONDS);
        if (work == null) {
          continue;
        }
        long delay = journalDelayMillis;
        if (delay > 0) {
          Thread.sleep(delay);
        }
        journalGate.await();
        try {
          Throwable failure = journalFailure;
          if (failure == null) {
            work.callback().onJournaled();
          } else {
            work.callback().onRejected(failure);
          }
        } finally {
          work.event().close();
        }
      } catch (InterruptedException interrupted) {
        if (work != null) {
          work.callback().onRejected(new IllegalStateException("recording sink stopped"));
          work.event().close();
        }
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Makes every later submission fail, as a failed journal write would. */
  void startRejecting() {
    rejecting = true;
  }

  /** Slows journaling, so a test can produce real backpressure. */
  void setJournalDelay(long millis) {
    this.journalDelayMillis = millis;
  }

  void blockJournaling() {
    journalGate = new CountDownLatch(1);
  }

  void releaseJournaling() {
    journalGate.countDown();
  }

  void failJournaling(Throwable failure) {
    journalFailure = failure;
    releaseJournaling();
  }

  void blockStreamEnd() {
    streamEndEntered = new CountDownLatch(1);
    streamEndGate = new CountDownLatch(1);
  }

  boolean awaitStreamEndCallback(long timeoutMillis) throws InterruptedException {
    return streamEndEntered.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  void releaseStreamEnd() {
    streamEndGate.countDown();
  }

  void stop() {
    running = false;
    releaseJournaling();
    releaseStreamEnd();
    journalThread.interrupt();
    JournalWork abandoned;
    while ((abandoned = journalWork.poll()) != null) {
      abandoned.callback().onRejected(new IllegalStateException("recording sink stopped"));
      abandoned.event().close();
    }
  }

  List<RawBesEvent> events() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  List<BesStreamKey> opened() {
    synchronized (opened) {
      return List.copyOf(opened);
    }
  }

  List<BesStreamState> ended() {
    synchronized (ended) {
      return List.copyOf(ended);
    }
  }

  /** Sequence numbers seen, per stream, in arrival order. */
  Map<BesStreamKey, List<Long>> sequencesByStream() {
    Map<BesStreamKey, List<Long>> bySeq = new LinkedHashMap<>();
    for (RawBesEvent event : events()) {
      bySeq.computeIfAbsent(event.stream(), key -> new ArrayList<>()).add(event.sequence());
    }
    return bySeq;
  }

  /** Waits until at least {@code count} events have been submitted. */
  boolean awaitEvents(int count, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (events().size() >= count) {
        return true;
      }
      Thread.sleep(10);
    }
    return events().size() >= count;
  }

  /** Waits until a stream has been reported as ended. */
  boolean awaitStreamEnd(long timeoutMillis) throws InterruptedException {
    return awaitStreamEnds(1, timeoutMillis);
  }

  boolean awaitStreamEnds(int count, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (ended().size() >= count) {
        return true;
      }
      Thread.sleep(10);
    }
    return ended().size() >= count;
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException retry) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

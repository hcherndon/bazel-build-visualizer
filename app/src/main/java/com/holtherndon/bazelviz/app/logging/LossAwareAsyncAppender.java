package com.holtherndon.bazelviz.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, non-blocking asynchronous appender that makes every loss visible.
 *
 * <p>Logback's stock async appender can either block a caller when its queue is full or discard
 * lower-level events without exposing an exact total. Neither is acceptable here: the caller may be
 * Swing's EDT, while the log exists to explain exactly what the application did. This appender
 * always uses {@link java.util.Queue#offer}; a full queue increments an exact counter. Its writer
 * emits a WARN record after each observed overflow burst and, when the destination finishes within
 * the shutdown deadline, one final exact total.
 */
public final class LossAwareAsyncAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  static final String INTERNAL_LOGGER = "com.holtherndon.bazelviz.logging";
  static final String OVERFLOW_PREFIX = "Application log queue overflow: exactly ";
  static final String SHUTDOWN_PREFIX = "Application logging stopped after dropping exactly ";

  private final Appender<ILoggingEvent> delegate;
  private final ArrayBlockingQueue<PendingEvent> queue;
  private final Semaphore pendingSlots;
  private final Duration maxFlushTime;
  private final Runnable beforeOffer;
  private final Runnable afterStop;
  private final Object acceptanceGate = new Object();
  private final Set<PendingEvent> active = ConcurrentHashMap.newKeySet();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong unreportedDropped = new AtomicLong();
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final AtomicBoolean stopRequested = new AtomicBoolean();
  private final AtomicBoolean stopInitiated = new AtomicBoolean();
  private final CountDownLatch writerStopped = new CountDownLatch(1);
  private volatile long stopDeadlineNanos = Long.MAX_VALUE;
  private Thread writer;

  public LossAwareAsyncAppender(
      Appender<ILoggingEvent> delegate, int queueCapacity, Duration maxFlushTime) {
    this(delegate, queueCapacity, maxFlushTime, () -> {}, () -> {});
  }

  /** Test seam for holding an append precisely between its open check and queue offer. */
  LossAwareAsyncAppender(
      Appender<ILoggingEvent> delegate,
      int queueCapacity,
      Duration maxFlushTime,
      Runnable beforeOffer) {
    this(delegate, queueCapacity, maxFlushTime, beforeOffer, () -> {});
  }

  /** Test and ownership seam for work that must follow the destination close. */
  LossAwareAsyncAppender(
      Appender<ILoggingEvent> delegate,
      int queueCapacity,
      Duration maxFlushTime,
      Runnable beforeOffer,
      Runnable afterStop) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.pendingSlots = new Semaphore(queueCapacity);
    this.maxFlushTime = Objects.requireNonNull(maxFlushTime, "maxFlushTime");
    this.beforeOffer = Objects.requireNonNull(beforeOffer, "beforeOffer");
    this.afterStop = Objects.requireNonNull(afterStop, "afterStop");
    if (maxFlushTime.isNegative()) {
      throw new IllegalArgumentException("maxFlushTime must not be negative");
    }
  }

  @Override
  public synchronized void start() {
    if (isStarted()) {
      return;
    }
    if (stopInitiated.get()) {
      addError("A stopped asynchronous appender cannot be restarted");
      return;
    }
    if (!delegate.isStarted()) {
      addError("The destination appender must be started first");
      return;
    }
    if (!(getContext() instanceof LoggerContext)) {
      addError("A LoggerContext is required");
      return;
    }
    accepting.set(true);
    stopRequested.set(false);
    super.start();
    writer = Thread.ofPlatform().name("bbv-log-writer").daemon(true).unstarted(this::runWriter);
    try {
      writer.start();
    } catch (RuntimeException | Error failure) {
      accepting.set(false);
      super.stop();
      writerStopped.countDown();
      throw failure;
    }
  }

  @Override
  protected void append(ILoggingEvent event) {
    Objects.requireNonNull(event, "event");
    event.prepareForDeferredProcessing();
    PendingEvent pending = new PendingEvent(event);
    synchronized (acceptanceGate) {
      if (!accepting.get()) {
        recordDropped(1);
        return;
      }
      if (!pendingSlots.tryAcquire()) {
        recordDropped(1);
        return;
      }
      active.add(pending);
    }
    try {
      beforeOffer.run();
      if (pending.wasDropped()) {
        return;
      }
      if (!queue.offer(pending)) {
        drop(pending);
        return;
      }
      // Shutdown may have begun after the first open check. Either the
      // writer wins this race and delivers the record, or this caller
      // removes and counts it. It can never remain behind an exited
      // writer unaccounted for.
      if (!accepting.get() || pending.wasDropped()) {
        queue.remove(pending);
        drop(pending);
      }
    } catch (RuntimeException | Error failure) {
      drop(pending);
      throw failure;
    }
  }

  /** Exact number of caller records this destination could not retain. */
  public long droppedRecordCount() {
    return dropped.get();
  }

  /** Current bounded queue capacity, exposed for diagnostics and tests. */
  public int queueCapacity() {
    return queue.size() + queue.remainingCapacity();
  }

  /** Records an appender-owned warning without passing through the root threshold. */
  void recordInternalWarning(String message) {
    append(warning(Objects.requireNonNull(message, "message")));
  }

  @Override
  public void stop() {
    if (!isStarted() && !stopInitiated.get()) {
      return;
    }
    if (stopInitiated.compareAndSet(false, true)) {
      long deadline = saturatedAdd(System.nanoTime(), maxFlushTime.toNanos());
      synchronized (acceptanceGate) {
        accepting.set(false);
        super.stop();
        stopDeadlineNanos = deadline;
        stopRequested.set(true);
      }
    }
    boolean finished = false;
    try {
      long remaining = Math.max(0, stopDeadlineNanos - System.nanoTime());
      finished = writerStopped.await(remaining, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    if (!finished) {
      // The delegate may be in an operating-system write that cannot be
      // interrupted safely. Stop waiting at the declared deadline, but
      // own the final handoff and account for every registered record
      // that had not entered that write.
      discardOutstandingAtDeadline();
    }
  }

  private void runWriter() {
    try {
      while (true) {
        if (stopRequested.get()) {
          if (System.nanoTime() >= stopDeadlineNanos) {
            discardOutstandingAtDeadline();
            break;
          }
          if (queue.isEmpty() && active.isEmpty()) {
            break;
          }
        }

        PendingEvent pending;
        try {
          pending = queue.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          // Preserve accepted records even if an outside caller
          // interrupts the daemon. stop() does not interrupt it:
          // doing so while the delegate is writing can corrupt or
          // prematurely abort that destination.
          continue;
        }
        if (pending != null) {
          if (pending.beginWrite()) {
            pendingSlots.release();
            try {
              delegate.doAppend(pending.event());
            } finally {
              pending.finishWrite();
              active.remove(pending);
            }
          } else {
            active.remove(pending);
          }
        }
        emitOverflowWarning();
      }
      emitOverflowWarning();
      long total = dropped.get();
      if (total > 0) {
        delegate.doAppend(warning(SHUTDOWN_PREFIX + total + " record(s) in this process."));
      }
    } finally {
      try {
        delegate.stop();
      } finally {
        try {
          afterStop.run();
        } catch (RuntimeException failure) {
          addError("Work after the logging destination stopped failed", failure);
        } finally {
          writerStopped.countDown();
        }
      }
    }
  }

  private void discardOutstandingAtDeadline() {
    PendingEvent queued;
    while ((queued = queue.poll()) != null) {
      drop(queued);
    }
    // This also finds an append that registered before shutdown and was
    // descheduled before its non-blocking queue offer. The state change
    // prevents it from offering after the writer exits.
    for (PendingEvent pending : active) {
      drop(pending);
    }
    // An append can pass its state check immediately before the scan and
    // complete its offer immediately after it. Its post-offer shutdown
    // check removes it, while this second drain closes the other ordering.
    while ((queued = queue.poll()) != null) {
      drop(queued);
    }
  }

  private void drop(PendingEvent pending) {
    if (pending.drop()) {
      pendingSlots.release();
      active.remove(pending);
      recordDropped(1);
    }
  }

  private void recordDropped(long count) {
    if (count == 0) {
      return;
    }
    dropped.addAndGet(count);
    unreportedDropped.addAndGet(count);
  }

  private void emitOverflowWarning() {
    long count = unreportedDropped.getAndSet(0);
    if (count == 0) {
      return;
    }
    long total = dropped.get();
    delegate.doAppend(
        warning(
            OVERFLOW_PREFIX
                + count
                + " record(s) were dropped before the writer caught up; exact total so far: "
                + total
                + '.'));
  }

  private ILoggingEvent warning(String message) {
    LoggerContext context = (LoggerContext) getContext();
    Logger logger = context.getLogger(INTERNAL_LOGGER);
    LoggingEvent event =
        new LoggingEvent(
            LossAwareAsyncAppender.class.getName(), logger, Level.WARN, message, null, null);
    event.setThreadName(Thread.currentThread().getName());
    event.prepareForDeferredProcessing();
    return event;
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static final class PendingEvent {

    private static final int WAITING = 0;
    private static final int WRITING = 1;
    private static final int WRITTEN = 2;
    private static final int DROPPED = 3;

    private final ILoggingEvent event;
    private final AtomicInteger state = new AtomicInteger(WAITING);

    private PendingEvent(ILoggingEvent event) {
      this.event = event;
    }

    private ILoggingEvent event() {
      return event;
    }

    private boolean beginWrite() {
      return state.compareAndSet(WAITING, WRITING);
    }

    private void finishWrite() {
      state.compareAndSet(WRITING, WRITTEN);
    }

    private boolean drop() {
      return state.compareAndSet(WAITING, DROPPED);
    }

    private boolean wasDropped() {
      return state.get() == DROPPED;
    }
  }
}

package com.holtherndon.bazelviz.capture.bes;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;

/** Mutable admission counters and the payload budget owned by one BES server. */
final class BesResources {

  private final BesResourceLimits limits;
  private final RetainedPayloadBudget payloadBudget;
  private int activeRpcs;
  private long rpcActivityGeneration;
  private int admittedStreamKeys;
  private long rpcRefusals;
  private long streamKeyRefusals;
  private long handlerTaskRefusals;

  BesResources(BesResourceLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.payloadBudget = new RetainedPayloadBudget(limits.retainedPayloadBytes());
  }

  RetainedPayloadBudget payloadBudget() {
    return payloadBudget;
  }

  synchronized boolean tryAcquireRpc() {
    if (activeRpcs >= limits.maxConcurrentRpcs()) {
      rpcRefusals++;
      return false;
    }
    activeRpcs++;
    rpcActivityGeneration++;
    notifyAll();
    return true;
  }

  synchronized void releaseRpc() {
    if (activeRpcs < 1) {
      throw new IllegalStateException("released a BES RPC that was not admitted");
    }
    activeRpcs--;
    notifyAll();
  }

  synchronized boolean tryAdmitStreamKey() {
    if (admittedStreamKeys >= limits.maxAdmittedStreamKeys()) {
      streamKeyRefusals++;
      return false;
    }
    admittedStreamKeys++;
    return true;
  }

  /**
   * Rolls back an admission whose tracker could not be installed. Admitted keys are never evicted.
   */
  synchronized void rollbackStreamKeyAdmission() {
    if (admittedStreamKeys < 1) {
      throw new IllegalStateException("rolled back a stream-key admission that was not held");
    }
    admittedStreamKeys--;
  }

  synchronized void noteHandlerTaskRefusal() {
    handlerTaskRefusals++;
  }

  synchronized int activeRpcs() {
    return activeRpcs;
  }

  /** Waits until zero active RPCs remains true for the configured stable period. */
  synchronized boolean awaitQuiescence(Duration timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative: " + timeout);
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    long zeroSince = -1L;
    long observedActivityGeneration = rpcActivityGeneration;
    while (true) {
      long now = System.nanoTime();
      if (observedActivityGeneration != rpcActivityGeneration) {
        observedActivityGeneration = rpcActivityGeneration;
        zeroSince = -1L;
      }
      if (activeRpcs == 0) {
        if (zeroSince < 0) {
          zeroSince = now;
        }
        long stableRemaining = limits.quiescenceStablePeriod().toNanos() - (now - zeroSince);
        if (stableRemaining <= 0) {
          return true;
        }
        long deadlineRemaining = deadline - now;
        if (deadlineRemaining <= 0) {
          return false;
        }
        waitNanos(Math.min(stableRemaining, deadlineRemaining));
      } else {
        zeroSince = -1L;
        long remaining = deadline - now;
        if (remaining <= 0) {
          return false;
        }
        waitNanos(remaining);
      }
    }
  }

  synchronized BesResourceSnapshot snapshot(ThreadPoolExecutor handlers) {
    return snapshot(handlers.getActiveCount(), handlers.getQueue().size());
  }

  synchronized BesResourceSnapshot snapshot() {
    return snapshot(0, 0);
  }

  private BesResourceSnapshot snapshot(int activeHandlers, int queuedHandlers) {
    return new BesResourceSnapshot(
        payloadBudget.retainedBytes(),
        payloadBudget.highWaterBytes(),
        payloadBudget.limitBytes(),
        activeRpcs,
        limits.maxConcurrentRpcs(),
        admittedStreamKeys,
        limits.maxAdmittedStreamKeys(),
        activeHandlers,
        queuedHandlers,
        rpcRefusals,
        streamKeyRefusals,
        handlerTaskRefusals);
  }

  private void waitNanos(long nanos) throws InterruptedException {
    long millis = nanos / 1_000_000L;
    int extraNanos = (int) (nanos % 1_000_000L);
    wait(millis, extraNanos);
  }
}

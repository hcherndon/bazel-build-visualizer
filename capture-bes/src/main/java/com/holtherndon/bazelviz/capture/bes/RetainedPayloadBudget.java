package com.holtherndon.bazelviz.capture.bes;

import java.util.ArrayDeque;
import java.util.Objects;

/** A fair, interruptible weighted budget shared by every retained raw BES payload. */
final class RetainedPayloadBudget {

  private final long limitBytes;
  private long retainedBytes;
  private long highWaterBytes;
  private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();

  RetainedPayloadBudget(long limitBytes) {
    if (limitBytes < 1) {
      throw new IllegalArgumentException("limitBytes must be positive, got " + limitBytes);
    }
    this.limitBytes = limitBytes;
  }

  /**
   * Waits for the payload's exact weight and returns its sole lease.
   *
   * <p>Requests are admitted in arrival order so a run of tiny events cannot starve one legal large
   * event. An event larger than the whole budget is refused immediately rather than waiting for
   * capacity that can never exist.
   */
  synchronized RawPayloadLease acquire(byte[] bytes)
      throws InterruptedException, PayloadRefusedException {
    Objects.requireNonNull(bytes, "bytes");
    RawPayloadLease lease = reserve(bytes.length);
    lease.attach(bytes, bytes.length);
    return lease;
  }

  synchronized RawPayloadLease reserve(int weight)
      throws InterruptedException, PayloadRefusedException {
    if (weight < 0) {
      throw new IllegalArgumentException("weight must not be negative, got " + weight);
    }
    if (weight > limitBytes) {
      throw new PayloadRefusedException(
          "BES payload of "
              + weight
              + " bytes exceeds the aggregate retained-payload limit of "
              + limitBytes
              + " bytes");
    }
    if (Thread.interrupted()) {
      throw new InterruptedException("interrupted before retained-payload admission");
    }
    Waiter waiter = new Waiter(weight);
    waiters.addLast(waiter);
    boolean admitted = false;
    try {
      while (waiters.peekFirst() != waiter || retainedBytes > limitBytes - weight) {
        wait();
      }
      RawPayloadLease lease = new RawPayloadLease(weight, this);
      waiters.removeFirst();
      retainedBytes += weight;
      highWaterBytes = Math.max(highWaterBytes, retainedBytes);
      admitted = true;
      notifyAll();
      return lease;
    } finally {
      if (!admitted) {
        waiters.remove(waiter);
        notifyAll();
      }
    }
  }

  private record Waiter(int weight) {}

  synchronized long retainedBytes() {
    return retainedBytes;
  }

  synchronized long highWaterBytes() {
    return highWaterBytes;
  }

  long limitBytes() {
    return limitBytes;
  }

  synchronized void release(int weight) {
    if (weight < 0 || weight > retainedBytes) {
      throw new IllegalStateException(
          "invalid retained-payload release " + weight + " from " + retainedBytes + " bytes");
    }
    retainedBytes -= weight;
    notifyAll();
  }

  static final class PayloadRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    PayloadRefusedException(String message) {
      super(message);
    }
  }
}

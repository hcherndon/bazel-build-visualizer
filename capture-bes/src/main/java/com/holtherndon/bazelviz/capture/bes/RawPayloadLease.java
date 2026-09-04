package com.holtherndon.bazelviz.capture.bes;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exclusive ownership of one raw BES payload and its aggregate byte-budget reservation.
 *
 * <p>Closing is idempotent. The bytes are not erased or copied: closing only returns their weight
 * to the server-wide retained-payload budget once the live pipeline no longer retains them.
 */
final class RawPayloadLease implements AutoCloseable {

  private final RetainedPayloadBudget budget;
  private final int reservedBytes;
  private final AtomicBoolean closed = new AtomicBoolean();
  private byte[] bytes;
  private int length;

  RawPayloadLease(byte[] bytes, RetainedPayloadBudget budget) {
    this(bytes.length, budget);
    attach(Objects.requireNonNull(bytes, "bytes"), bytes.length);
  }

  RawPayloadLease(int reservedBytes, RetainedPayloadBudget budget) {
    if (reservedBytes < 0) {
      throw new IllegalArgumentException("reservedBytes must not be negative");
    }
    this.budget = budget;
    this.reservedBytes = reservedBytes;
  }

  static RawPayloadLease unbudgeted(byte[] bytes) {
    return new RawPayloadLease(bytes, null);
  }

  byte[] bytes() {
    if (bytes == null) {
      throw new IllegalStateException("the raw payload has not been attached");
    }
    return bytes;
  }

  int length() {
    return length;
  }

  void attach(byte[] value, int usedLength) {
    Objects.requireNonNull(value, "value");
    if (bytes != null) {
      throw new IllegalStateException("the raw payload is already attached");
    }
    if (usedLength < 0 || usedLength > value.length || value.length > reservedBytes) {
      throw new IllegalArgumentException(
          "payload backing/length "
              + value.length
              + "/"
              + usedLength
              + " exceeds its "
              + reservedBytes
              + "-byte reservation");
    }
    bytes = value;
    length = usedLength;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true) && budget != null) {
      budget.release(reservedBytes);
    }
  }
}

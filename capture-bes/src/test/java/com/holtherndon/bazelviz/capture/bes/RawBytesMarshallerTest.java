package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.KnownLength;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RawBytesMarshallerTest {

  @Test
  @DisplayName("an unknown-length stream is read no farther than max plus one")
  void unknownLengthInputIsBoundedBeforeAllocation() {
    RawBytesMarshaller marshaller = new RawBytesMarshaller(4);
    CountingInputStream input = new CountingInputStream(100);

    assertThatThrownBy(() -> marshaller.parse(input))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure -> {
              assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
              assertThat(failure.getStatus().getDescription())
                  .contains("at least 5 bytes")
                  .contains("4-byte limit");
            });
    assertThat(input.bytesRead).isEqualTo(5);
  }

  @Test
  @DisplayName("a payload exactly at the limit is preserved byte for byte")
  void exactLimitIsAccepted() {
    byte[] payload = {0, 1, 2, 3};

    RawPayloadLease parsed = new RawBytesMarshaller(4).parse(new ByteArrayInputStream(payload));
    try {
      assertThat(parsed.bytes()).startsWith(payload);
      assertThat(parsed.length()).isEqualTo(payload.length);
    } finally {
      parsed.close();
    }
  }

  @Test
  @DisplayName("a known oversized stream is rejected before it is read")
  void knownOversizeIsRejectedBeforeRead() {
    KnownInputStream input = new KnownInputStream(new byte[5]);

    assertThatThrownBy(() -> new RawBytesMarshaller(4).parse(input))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode())
                    .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    assertThat(input.readCalled).isFalse();
  }

  @Test
  @DisplayName("a known payload reserves its weight before any bytes are read")
  void admissionPrecedesKnownLengthAllocationAndRead() throws Exception {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(4);
    RawPayloadLease held = budget.acquire(new byte[4]);
    KnownInputStream input = new KnownInputStream(new byte[4]);
    AtomicReference<RawPayloadLease> parsed = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    parsed.set(new RawBytesMarshaller(4, budget).parse(input));
                  } catch (Throwable problem) {
                    failure.set(problem);
                  }
                });

    awaitBlocked(reader);
    assertThat(input.readCalled).isFalse();
    assertThat(budget.retainedBytes()).isEqualTo(4);

    held.close();
    reader.join(TimeUnit.SECONDS.toMillis(2));
    assertThat(reader.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertThat(parsed.get()).isNotNull();
    parsed.get().close();
    assertThat(budget.retainedBytes()).isZero();
  }

  @Test
  @DisplayName("a read failure releases the reservation")
  void readFailureReleasesReservation() {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(4);
    ThrowingKnownInputStream input = new ThrowingKnownInputStream(4);

    assertThatThrownBy(() -> new RawBytesMarshaller(4, budget).parse(input))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure -> {
              assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(failure.getCause()).isInstanceOf(IOException.class);
            });
    assertThat(budget.retainedBytes()).isZero();
  }

  private static void awaitBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
  }

  private static final class CountingInputStream extends InputStream {

    private final int length;
    private int bytesRead;

    private CountingInputStream(int length) {
      this.length = length;
    }

    @Override
    public int read() {
      if (bytesRead >= length) {
        return -1;
      }
      bytesRead++;
      return bytesRead & 0xff;
    }
  }

  private static class KnownInputStream extends ByteArrayInputStream implements KnownLength {

    private boolean readCalled;

    private KnownInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public synchronized int read(byte[] bytes, int offset, int length) {
      readCalled = true;
      return super.read(bytes, offset, length);
    }

    @Override
    public synchronized int read() {
      readCalled = true;
      return super.read();
    }
  }

  private static final class ThrowingKnownInputStream extends InputStream implements KnownLength {

    private final int length;

    private ThrowingKnownInputStream(int length) {
      this.length = length;
    }

    @Override
    public int available() {
      return length;
    }

    @Override
    public int read() throws IOException {
      throw new IOException("read failed");
    }

    @Override
    public int read(byte[] bytes, int offset, int requested) throws IOException {
      throw new IOException("read failed");
    }
  }
}

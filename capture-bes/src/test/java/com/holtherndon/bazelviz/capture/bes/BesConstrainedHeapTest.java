package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.KnownLength;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class BesConstrainedHeapTest {

  private static final int MIB = 1024 * 1024;

  @Test
  @DisplayName("a 128 MiB process retains at most its 8 MiB raw-payload budget during a burst")
  void burstStaysInsideAggregatePayloadBudget() throws Exception {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(8L * MIB);
    RawBytesMarshaller marshaller = new RawBytesMarshaller(MIB, budget);
    Queue<RawPayloadLease> leases = new ConcurrentLinkedQueue<>();
    Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
    AtomicInteger completed = new AtomicInteger();
    CountDownLatch firstBudgetful = new CountDownLatch(8);
    CountDownLatch all = new CountDownLatch(16);
    ExecutorService readers = Executors.newFixedThreadPool(16);
    try {
      for (int i = 0; i < 16; i++) {
        readers.execute(
            () -> {
              try {
                leases.add(marshaller.parse(new RepeatingKnownInputStream(MIB)));
                if (completed.incrementAndGet() <= 8) {
                  firstBudgetful.countDown();
                }
              } catch (Throwable failure) {
                failures.add(failure);
              } finally {
                all.countDown();
              }
            });
      }

      assertThat(firstBudgetful.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100);
      assertThat(completed).hasValue(8);
      assertThat(budget.retainedBytes()).isEqualTo(8L * MIB);
      assertThat(budget.highWaterBytes()).isEqualTo(8L * MIB);

      leases.forEach(RawPayloadLease::close);
      assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
      leases.forEach(RawPayloadLease::close);
      assertThat(failures).isEmpty();
      assertThat(completed).hasValue(16);
      assertThat(budget.retainedBytes()).isZero();
      assertThat(budget.highWaterBytes()).isEqualTo(8L * MIB);
    } finally {
      leases.forEach(RawPayloadLease::close);
      readers.shutdownNow();
      assertThat(readers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Produces known-length bytes without retaining a second payload-sized source array. */
  private static final class RepeatingKnownInputStream extends InputStream implements KnownLength {

    private final int length;
    private int read;

    private RepeatingKnownInputStream(int length) {
      this.length = length;
    }

    @Override
    public int available() {
      return length - read;
    }

    @Override
    public int read() {
      if (read >= length) {
        return -1;
      }
      read++;
      return 0x5a;
    }

    @Override
    public int read(byte[] bytes, int offset, int requested) {
      if (read >= length) {
        return -1;
      }
      int count = Math.min(requested, length - read);
      Arrays.fill(bytes, offset, offset + count, (byte) 0x5a);
      read += count;
      return count;
    }
  }
}

package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RetainedPayloadBudgetTest {

  private static final BesStreamKey KEY = new BesStreamKey("build", "invocation", "TOOL");

  @Test
  @DisplayName("the exact byte boundary, high-water mark, and idempotent release are observable")
  void exactBoundaryAndRelease() throws Exception {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(8);

    RawPayloadLease first = budget.acquire(new byte[3]);
    RawPayloadLease second = budget.acquire(new byte[5]);
    assertThat(budget.retainedBytes()).isEqualTo(8);
    assertThat(budget.highWaterBytes()).isEqualTo(8);

    first.close();
    first.close();
    second.close();
    assertThat(budget.retainedBytes()).isZero();
    assertThat(budget.highWaterBytes()).isEqualTo(8);
  }

  @Test
  @DisplayName("negative weights and pre-interrupted callers never mutate retained state")
  void invalidAndPreInterruptedAdmissionsDoNotMutateState() {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(8);

    assertThatThrownBy(() -> budget.reserve(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> budget.reserve(1)).isInstanceOf(InterruptedException.class);
    } finally {
      // reserve follows interruptible-operation convention and clears the flag when it throws.
      Thread.interrupted();
    }
    assertThat(budget.retainedBytes()).isZero();
    assertThat(budget.highWaterBytes()).isZero();
  }

  @Test
  @DisplayName("an interrupted waiter leaves no FIFO gap and returns promptly")
  void interruptedWaiterIsRemoved() throws Exception {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(1);
    RawPayloadLease held = budget.acquire(new byte[1]);
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    budget.acquire(new byte[1]);
                  } catch (InterruptedException expected) {
                    interrupted.set(true);
                  } catch (RetainedPayloadBudget.PayloadRefusedException impossible) {
                    throw new AssertionError(impossible);
                  }
                });

    awaitWaiting(waiter);
    waiter.interrupt();
    waiter.join(TimeUnit.SECONDS.toMillis(1));
    assertThat(waiter.isAlive()).isFalse();
    assertThat(interrupted).isTrue();

    held.close();
    RawPayloadLease next = budget.acquire(new byte[1]);
    next.close();
    assertThat(budget.retainedBytes()).isZero();
  }

  @Test
  @DisplayName("invalid event construction releases transferred payload ownership")
  void invalidEventReleasesLease() throws Exception {
    RetainedPayloadBudget budget = new RetainedPayloadBudget(4);
    RawPayloadLease lease = budget.acquire(new byte[4]);

    assertThatThrownBy(() -> new RawBesEvent(SourceKind.BEP_BINARY, KEY, 1, 1, lease))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(budget.retainedBytes()).isZero();
  }

  @Test
  @DisplayName("raw events retain their documented identity equality")
  void rawEventEqualityRemainsIdentityBased() {
    byte[] bytes = {1};
    RawBesEvent first = new RawBesEvent(SourceKind.BES_ENVELOPE, KEY, 1, 1, bytes);
    RawBesEvent second = new RawBesEvent(SourceKind.BES_ENVELOPE, KEY, 1, 1, bytes);

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isNotSameAs(second);
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
  }
}

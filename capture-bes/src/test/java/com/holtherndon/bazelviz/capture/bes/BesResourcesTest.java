package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class BesResourcesTest {

  @Test
  @DisplayName(
      "the handler executor refuses exactly beyond its thread and queue bounds, then recovers")
  void handlerExecutorBoundaryAndRecovery() throws Exception {
    BesResourceLimits limits = new BesResourceLimits(1024, 2, 2, 1, 1, Duration.ofMillis(20));
    BesResources resources = new BesResources(limits);
    BesHandlerExecutor executor = new BesHandlerExecutor(limits, resources);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch firstTwoDone = new CountDownLatch(2);
    try {
      executor.execute(
          () -> {
            firstStarted.countDown();
            await(releaseFirst);
            firstTwoDone.countDown();
          });
      assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
      executor.execute(firstTwoDone::countDown);

      assertThatThrownBy(() -> executor.execute(() -> {}))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("1 active threads and 1 queued tasks");
      assertThat(resources.snapshot(executor).handlerTaskRefusals()).isEqualTo(1);

      releaseFirst.countDown();
      assertThat(firstTwoDone.await(2, TimeUnit.SECONDS)).isTrue();
      CountDownLatch recovered = new CountDownLatch(1);
      executor.execute(recovered::countDown);
      assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("quiescence requires one uninterrupted zero-RPC stable period")
  void quiescenceRestartsWhenAnotherRpcArrives() throws Exception {
    BesResourceLimits limits = new BesResourceLimits(1024, 2, 2, 1, 1, Duration.ofMillis(100));
    BesResources resources = new BesResources(limits);
    assertThat(resources.tryAcquireRpc()).isTrue();
    AtomicBoolean quiescent = new AtomicBoolean();
    Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    quiescent.set(resources.awaitQuiescence(Duration.ofSeconds(2)));
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });

    resources.releaseRpc();
    Thread.sleep(40);
    assertThat(resources.tryAcquireRpc()).isTrue();
    Thread.sleep(80);
    assertThat(quiescent).isFalse();
    resources.releaseRpc();

    waiter.join(2_000);
    assertThat(waiter.isAlive()).isFalse();
    assertThat(quiescent).isTrue();
  }

  @Test
  @DisplayName("a complete RPC pulse still restarts quiescence before the waiter reacquires")
  void quiescenceObservesFastRpcPulse() throws Exception {
    BesResourceLimits limits = new BesResourceLimits(1024, 2, 2, 1, 1, Duration.ofMillis(120));
    BesResources resources = new BesResources(limits);
    AtomicBoolean quiescent = new AtomicBoolean();
    CountDownLatch started = new CountDownLatch(1);
    Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  started.countDown();
                  try {
                    quiescent.set(resources.awaitQuiescence(Duration.ofSeconds(2)));
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    awaitWaiting(waiter);
    synchronized (resources) {
      assertThat(resources.tryAcquireRpc()).isTrue();
      resources.releaseRpc();
      // Both transitions finish while the waiter is awake but unable to reacquire this monitor.
      // Keeping it out past the original stable deadline makes the missed-generation bug exact.
      Thread.sleep(160);
    }

    Thread.sleep(40);
    assertThat(waiter.isAlive()).isTrue();
    assertThat(quiescent).isFalse();
    waiter.join(2_000);
    assertThat(waiter.isAlive()).isFalse();
    assertThat(quiescent).isTrue();
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.TIMED_WAITING
          || thread.getState() == Thread.State.WAITING) {
        return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError("quiescence waiter did not enter its stable-period wait");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry.Acquisition;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry.CaptureLease;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry.Conflict;
import com.holtherndon.bazelviz.ui.capture.CaptureLeaseRegistry.Granted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CaptureLeaseRegistryTest {

  private static final Instant ACQUIRED_AT = Instant.parse("2026-09-01T12:00:00Z");

  @Test
  void canonicalKeysDistinguishExecutionAuthorities(@TempDir Path temporary) throws Exception {
    Path realRoot = Files.createDirectories(temporary.resolve("repo")).toRealPath();

    CaptureLeaseKey local = CaptureLeaseKey.localRealPath(realRoot.resolve("."));
    CaptureLeaseKey sameLocal = CaptureLeaseKey.localRealPath(realRoot);
    CaptureLeaseKey remote =
        CaptureLeaseKey.ssh("builder", OptionalInt.empty(), "/srv/work/./repo/");
    CaptureLeaseKey sameRemote =
        CaptureLeaseKey.ssh("builder", OptionalInt.empty(), "/srv/work/repo");

    assertThat(local).isEqualTo(sameLocal);
    assertThat(remote).isEqualTo(sameRemote);
    assertThat(remote.canonicalRepositoryRoot()).isEqualTo("/srv/work/repo");
    assertThat(remote)
        .isNotEqualTo(CaptureLeaseKey.ssh("builder", OptionalInt.of(22), "/srv/work/repo"));
    assertThat(remote)
        .isNotEqualTo(CaptureLeaseKey.ssh("other-builder", OptionalInt.empty(), "/srv/work/repo"));
    assertThat(local).isNotEqualTo(remote);
  }

  @Test
  void rejectsRootsThatHaveNotBeenMadeAbsolute() {
    assertThatThrownBy(() -> CaptureLeaseKey.localRealPath(Path.of("repo")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be absolute");
    assertThatThrownBy(() -> CaptureLeaseKey.ssh("builder", OptionalInt.empty(), "srv/repo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be absolute");
  }

  @Test
  void conflictReportsTheCurrentOwnerAndAcquisitionTime(@TempDir Path temporary) throws Exception {
    CaptureLeaseRegistry registry = registryAtFixedTime();
    CaptureLeaseKey key = localKey(temporary, "repo");
    CaptureLeaseOwner firstOwner = new CaptureLeaseOwner("workspace-1", "Compiler");
    CaptureLeaseOwner secondOwner = new CaptureLeaseOwner("workspace-2", "Tests");

    Granted granted = assertGranted(registry.tryAcquire(key, firstOwner));
    Conflict conflict = assertConflict(registry.tryAcquire(key, secondOwner));

    assertThat(conflict.activeLease()).isEqualTo(granted.lease().details());
    assertThat(conflict.activeLease().owner()).isEqualTo(firstOwner);
    assertThat(conflict.activeLease().acquiredAt()).isEqualTo(ACQUIRED_AT);
    assertThat(registry.activeLease(key)).contains(conflict.activeLease());
    assertThat(registry.activeLeaseCount()).isEqualTo(1);

    granted.lease().close();
  }

  @Test
  void distinctRepositoriesCanBeLeasedInParallel(@TempDir Path temporary) throws Exception {
    CaptureLeaseRegistry registry = registryAtFixedTime();
    CaptureLeaseOwner owner = new CaptureLeaseOwner("workspace", "Workspace");

    Granted first = assertGranted(registry.tryAcquire(localKey(temporary, "one"), owner));
    Granted second = assertGranted(registry.tryAcquire(localKey(temporary, "two"), owner));

    assertThat(registry.activeLeaseCount()).isEqualTo(2);

    first.lease().close();
    second.lease().close();
    assertThat(registry.activeLeaseCount()).isZero();
  }

  @Test
  void closeIsIdempotentAndAnOldHandleCannotReleaseANewLease(@TempDir Path temporary)
      throws Exception {
    CaptureLeaseRegistry registry = registryAtFixedTime();
    CaptureLeaseKey key = localKey(temporary, "repo");
    CaptureLease first =
        assertGranted(registry.tryAcquire(key, new CaptureLeaseOwner("first", "First"))).lease();

    first.close();
    assertThat(first.isClosed()).isTrue();
    CaptureLease second =
        assertGranted(registry.tryAcquire(key, new CaptureLeaseOwner("second", "Second"))).lease();

    first.close();

    assertThat(registry.activeLease(key)).contains(second.details());
    assertThat(
            assertConflict(registry.tryAcquire(key, new CaptureLeaseOwner("third", "Third")))
                .activeLease()
                .owner())
        .isEqualTo(second.details().owner());

    second.close();
    second.close();
    assertThat(registry.activeLease(key)).isEmpty();
  }

  @Test
  @Timeout(10)
  void concurrentAttemptsGrantExactlyOneLease(@TempDir Path temporary) throws Exception {
    CaptureLeaseRegistry registry = registryAtFixedTime();
    CaptureLeaseKey key = localKey(temporary, "repo");
    int contenders = 24;
    ExecutorService executor = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Acquisition>> attempts = new ArrayList<>();
    try {
      for (int index = 0; index < contenders; index++) {
        int contender = index;
        attempts.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                  return registry.tryAcquire(
                      key,
                      new CaptureLeaseOwner("workspace-" + contender, "Workspace " + contender));
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Acquisition> results = new ArrayList<>();
      for (Future<Acquisition> attempt : attempts) {
        results.add(attempt.get(5, TimeUnit.SECONDS));
      }

      List<Granted> granted =
          results.stream().filter(Granted.class::isInstance).map(Granted.class::cast).toList();
      assertThat(granted).hasSize(1);
      assertThat(results.stream().filter(Conflict.class::isInstance)).hasSize(contenders - 1);
      assertThat(
              results.stream()
                  .filter(Conflict.class::isInstance)
                  .map(Conflict.class::cast)
                  .map(Conflict::activeLease)
                  .map(CaptureLeaseRegistry.ActiveLease::owner)
                  .distinct())
          .containsExactly(granted.getFirst().lease().details().owner());

      granted.getFirst().lease().close();
      assertThat(registry.activeLeaseCount()).isZero();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static CaptureLeaseRegistry registryAtFixedTime() {
    return new CaptureLeaseRegistry(Clock.fixed(ACQUIRED_AT, ZoneOffset.UTC));
  }

  private static CaptureLeaseKey localKey(Path temporary, String directory) throws Exception {
    return CaptureLeaseKey.localRealPath(
        Files.createDirectories(temporary.resolve(directory)).toRealPath());
  }

  private static Granted assertGranted(Acquisition acquisition) {
    assertThat(acquisition).isInstanceOf(Granted.class);
    return (Granted) acquisition;
  }

  private static Conflict assertConflict(Acquisition acquisition) {
    assertThat(acquisition).isInstanceOf(Conflict.class);
    return (Conflict) acquisition;
  }
}

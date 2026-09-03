package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.format.portable.BvizFormatException;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import com.holtherndon.bazelviz.storage.catalog.RetentionPolicy;
import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionMutationCoordinatorTest {

  private static final long CREATED = 1_700_000_000_000_000L;

  @TempDir Path tempDir;

  @Test
  @DisplayName("cleanup in one window keeps a session active in another")
  void cleanupKeepsAnotherWindowsActiveSession() throws Exception {
    Path catalogDirectory = tempDir.resolve("catalog-active");
    Path activeDirectory = sessionDirectory("active");
    Path recentDirectory = sessionDirectory("recent");
    SessionMutationCoordinator coordinator = new SessionMutationCoordinator();
    RetentionPolicy.Plan plan;
    try (SessionMutationCoordinator.ActiveSession firstWindow =
            coordinator.activate("active", activeDirectory);
        SessionMutationCoordinator.ActiveSession secondWindow =
            coordinator.activate("active", activeDirectory)) {
      firstWindow.close();
      try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
        catalog.record(entry("active", activeDirectory, 1));
        catalog.record(entry("recent", recentDirectory, 2));
        plan = catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(1), 10);
      }
      SessionCatalog.SweepResult result = coordinator.applyCleanup(catalogDirectory, plan);

      assertThat(result.removed()).isZero();
      assertThat(result.failures()).anyMatch(message -> message.contains("application window"));
      assertThat(Files.exists(activeDirectory.resolve("manifest.json"))).isTrue();
    }

    SessionCatalog.SweepResult afterBothWindowsClosed =
        coordinator.applyCleanup(catalogDirectory, plan);
    assertThat(afterBothWindowsClosed.removed()).isOne();
    assertThat(Files.exists(activeDirectory)).isFalse();
  }

  @Test
  @DisplayName("cleanup rechecks activation after its plan was created")
  void activationAfterPlanningIsRechecked() throws Exception {
    Path catalogDirectory = tempDir.resolve("catalog-stale-plan");
    Path activeDirectory = sessionDirectory("late-active");
    Path recentDirectory = sessionDirectory("late-recent");
    RetentionPolicy.Plan stalePlan;
    try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
      catalog.record(entry("late-active", activeDirectory, 1));
      catalog.record(entry("late-recent", recentDirectory, 2));
      stalePlan = catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(1), 10);
    }

    SessionMutationCoordinator coordinator = new SessionMutationCoordinator();
    SessionMutationCoordinator.ActiveSession openedAfterPlan =
        coordinator.activate("late-active", activeDirectory);
    try {
      SessionCatalog.SweepResult result = coordinator.applyCleanup(catalogDirectory, stalePlan);

      assertThat(result.removed()).isZero();
      assertThat(Files.exists(activeDirectory)).isTrue();
    } finally {
      openedAfterPlan.close();
    }
  }

  @Test
  @DisplayName("same-UUID archive adoption is serialized and staging is never shared")
  void concurrentSameUuidArchiveImportsAreSerialized() throws Exception {
    Path source = tempDir.resolve("archive-source/session-0193f0aa-1111-7000-8000-000000000000");
    Files.createDirectories(source);
    Files.writeString(source.resolve("manifest.json"), "{\"formatVersion\":1}");
    Files.write(source.resolve("session.sqlite"), new byte[] {'S', 'Q', 'L'});
    Path archive = tempDir.resolve("same-session.bviz");
    BvizWriter.write(source, archive, BvizWriter.Options.complete("race"), "0.1.0", CREATED);

    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maximumInside = new AtomicInteger();
    SessionMutationCoordinator coordinator =
        new SessionMutationCoordinator(
            (validation, sessionsRoot, limits) -> {
              int call = calls.incrementAndGet();
              int concurrent = inside.incrementAndGet();
              maximumInside.accumulateAndGet(concurrent, Math::max);
              if (call == 1) {
                firstEntered.countDown();
                try {
                  if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release first import");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException("interrupted while coordinating import", interrupted);
                }
              } else {
                secondEntered.countDown();
              }
              try {
                return ArchiveImport.into(validation, sessionsRoot, limits);
              } finally {
                inside.decrementAndGet();
              }
            });
    Path library = tempDir.resolve("library");

    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(() -> coordinator.importArchive(archive, library, BvizLimits.defaults()));
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var second =
          workers.submit(
              () -> {
                secondStarted.countDown();
                return coordinator.importArchive(archive, library, BvizLimits.defaults());
              });
      try {
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        releaseFirst.countDown();
      }

      assertThat(first.get(5, TimeUnit.SECONDS).sessionRoot())
          .isEqualTo(library.resolve("session-0193f0aa-1111-7000-8000-000000000000"));
      assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasRootCauseInstanceOf(BvizFormatException.class);
    }

    assertThat(maximumInside.get()).isEqualTo(1);
    try (var entries = Files.list(library)) {
      assertThat(entries.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.contains(".incoming"));
    }
  }

  private Path sessionDirectory(String uuid) throws IOException {
    Path directory = tempDir.resolve("sessions/session-" + uuid);
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("manifest.json"), "{}");
    return directory;
  }

  private static CatalogEntry entry(String uuid, Path directory, long openedMicros) {
    return new CatalogEntry(
        uuid,
        "build //... (" + uuid + ")",
        directory,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "READY",
        OptionalLong.of(openedMicros),
        OptionalLong.of(openedMicros),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.of(100),
        0,
        OptionalLong.of(openedMicros),
        false,
        false,
        Optional.empty());
  }
}

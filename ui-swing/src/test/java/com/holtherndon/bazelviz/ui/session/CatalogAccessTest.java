package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogAccessTest {

  @TempDir Path temporaryDirectory;

  @Test
  void separateWindowsCannotOverlapCatalogOperations() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);

    try (var workers =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("catalog-access-test-", 0).factory())) {
      var first =
          workers.submit(
              () ->
                  CatalogAccess.withCatalog(
                      temporaryDirectory,
                      catalog -> {
                        firstEntered.countDown();
                        releaseFirst.await();
                        return null;
                      }));
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

      var second =
          workers.submit(
              () ->
                  CatalogAccess.withCatalog(
                      temporaryDirectory,
                      catalog -> {
                        secondEntered.countDown();
                        return null;
                      }));
      assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();

      releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      assertThat(secondEntered.getCount()).isZero();
    }
  }

  @Test
  void eventThreadIsRejectedBeforeCatalogIo() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            CatalogAccess.withCatalog(temporaryDirectory, catalog -> null);
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });

    assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("EDT");
    assertThat(temporaryDirectory.resolve("catalog.sqlite")).doesNotExist();
  }
}

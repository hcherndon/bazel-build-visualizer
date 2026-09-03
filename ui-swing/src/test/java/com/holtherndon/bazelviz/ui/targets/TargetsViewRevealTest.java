package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code TargetsView.revealLabel}: random access into the lazily loaded tree by label — the landing
 * point of the shared "Open target" action.
 *
 * <p>The interesting part is the asynchrony: the package's children are not loaded until the reveal
 * expands it, so selection has to wait for the load it triggered, and a label the session never
 * declared has to say so instead of selecting nothing silently.
 */
class TargetsViewRevealTest {

  private static final int EVENT_COUNT = 300;

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  @Test
  @Timeout(180)
  @DisplayName("revealLabel expands the package and selects the target, unprompted by any browsing")
  void revealSelectsTheTarget(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    String label;
    try (EntityReader reader = opened.openEntityReader()) {
      List<TargetQueries.PackageSummary> packages = reader.packages();
      assertThat(packages).isNotEmpty();
      List<TargetRow> rows = reader.targetsInPackage(packages.getFirst().path());
      assertThat(rows).isNotEmpty();
      label = rows.getFirst().label();

      // The byLabel exposure the reveal is built on answers through the
      // service interface, and exactly, not by substring.
      List<TargetRow> byLabel = reader.targetsByLabel(label);
      assertThat(byLabel).isNotEmpty();
      assertThat(byLabel).allMatch(row -> row.label().equals(label));
      assertThat(reader.targetsByLabel("//no/such:target")).isEmpty();
    }

    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          TargetsView view = new TargetsView();
          view.openSession(opened);
          held.set(view);
        });
    TargetsView view = held.get();
    await(() -> onEdt(() -> view.packageCountForTest() > 0));

    SwingUtilities.invokeAndWait(() -> view.revealLabel(label));
    await(() -> onEdt(() -> label.equals(view.selectedLabelForTest())));

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  @Test
  @Timeout(180)
  @DisplayName("a label the session never declared is answered, not swallowed")
  void unknownLabelSaysSo(@TempDir Path temporary) throws Exception {
    SqliteSessionSource opened = openImportedSession(temporary);
    AtomicReference<TargetsView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          TargetsView view = new TargetsView();
          view.openSession(opened);
          held.set(view);
        });
    TargetsView view = held.get();
    await(() -> onEdt(() -> view.packageCountForTest() > 0));

    SwingUtilities.invokeAndWait(() -> view.revealLabel("//no/such:target"));
    await(() -> onEdt(() -> view.statusForTest().contains("//no/such:target")));
    assertThat(onEdt(() -> view.selectedLabelForTest() == null)).isTrue();

    SwingUtilities.invokeAndWait(view::closeSession);
    opened.close();
  }

  private static SqliteSessionSource openImportedSession(Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    return SqliteSessionSource.open(sessions, imported.sessionRoot());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private static <T> T onEdt(Callable<T> read) {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              value.set(read.call());
            } catch (Exception e) {
              failure.set(e);
            }
          });
    } catch (Exception e) {
      throw new AssertionError("EDT read failed", e);
    }
    if (failure.get() != null) {
      throw new AssertionError("EDT read failed", failure.get());
    }
    return value.get();
  }

  private static boolean onEdt(BooleanSupplier read) {
    return onEdt((Callable<Boolean>) read::getAsBoolean);
  }
}

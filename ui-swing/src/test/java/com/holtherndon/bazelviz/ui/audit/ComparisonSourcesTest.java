package com.holtherndon.bazelviz.ui.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.repro.ReproComparison;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import com.holtherndon.bazelviz.storage.catalog.RetentionPolicy;
import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import com.holtherndon.bazelviz.ui.audit.ComparisonSources.Source;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ComparisonSourcesTest {
  @TempDir Path temporary;
  private final SessionMutationCoordinator sessions = new SessionMutationCoordinator();

  @Test
  void explicitlySelectedRawLogsUsePrivateCopiesWithoutInventingCoverage() throws Exception {
    Path a = Files.createFile(temporary.resolve("a.bin"));
    Path b = Files.createFile(temporary.resolve("b.bin"));
    Path scratch = temporary.resolve("scratch");
    try (ReproComparison comparison =
        ComparisonSources.open(
            Source.selected(a), Source.selected(b), scratch, sessions, () -> false)) {
      assertThat(comparison.summary().actionsA()).isZero();
      assertThat(comparison.summary().coverageNotes())
          .anyMatch(note -> note.contains("empty pair"));
      // Successful opening no longer depends on files whose snapshots are already private.
      Files.delete(a);
      Files.delete(b);
      assertThat(comparison.summary().matched()).isZero();
    }
    try (var children = Files.list(scratch)) {
      assertThat(children).isEmpty();
    }
  }

  @Test
  void directorySelectionRequiresExactlyOneStandardExecutionLog() throws Exception {
    Path root = session();
    Path binary = raw(root, InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    try (var source = ComparisonSources.resolve(Source.selected(root), sessions, () -> false)) {
      assertThat(source.log()).isEqualTo(binary.toRealPath());
      assertThat(source.lease()).isNotNull();
    }
    Path compact = raw(root, InstrumentationPlanner.EXECUTION_LOG_FILE);
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("multiple execution logs");
    // Choosing a specific file is explicit even when its parent session has two formats.
    try (var source = ComparisonSources.resolve(Source.selected(compact), sessions, () -> false)) {
      assertThat(source.log()).isEqualTo(compact.toRealPath());
      assertThat(source.lease()).isNotNull();
    }
  }

  @Test
  void emptySessionAndNonRegularCandidatesAreNotSilentlySkipped() throws Exception {
    Path root = session();
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no preserved");
    Files.createDirectory(
        ManagedSessionLayout.at(root)
            .rawDirectory()
            .resolve(InstrumentationPlanner.EXECUTION_LOG_FILE));
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("regular");
    raw(root, InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("multiple");
  }

  @Test
  void manifestMustBeRegularBoundedAndValid() throws Exception {
    Path root = session();
    Path manifest = ManagedSessionLayout.at(root).manifestFile();
    Files.writeString(manifest, " ".repeat(ComparisonSources.MAX_MANIFEST_BYTES + 1));
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("2 MiB");
    Files.writeString(manifest, "{broken");
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("JSON");
    Files.delete(manifest);
    Files.createDirectory(manifest);
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("regular");
  }

  @Test
  void sessionIdentityCannotBorrowAnotherDirectorysRetentionLease() throws Exception {
    Path root = session();
    Files.writeString(
        ManagedSessionLayout.at(root).manifestFile(), manifest(UUID.randomUUID().toString()));
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("identity does not match");
  }

  @Test
  void managedManifestAndLogLinksCannotEscapeTheSelectedSession() throws Exception {
    Path outside = Files.createFile(temporary.resolve("outside.bin"));
    Path root = session();
    Source forged = new Source(outside, Optional.of(root), Optional.empty());
    assertThatThrownBy(() -> ComparisonSources.resolve(forged, sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("escapes");
    Path binary =
        ManagedSessionLayout.at(root)
            .rawDirectory()
            .resolve(InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    Files.createSymbolicLink(binary, outside.toRealPath());
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("escapes");
    Path manifest = ManagedSessionLayout.at(root).manifestFile();
    Path otherManifest =
        Files.writeString(
            temporary.resolve("outside-manifest.json"), manifest(UUID.randomUUID().toString()));
    Files.delete(manifest);
    Files.createSymbolicLink(manifest, otherManifest.toRealPath());
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("escapes");
    assertThat(Files.size(outside)).isZero();
  }

  @Test
  void rawDirectoryLinksCannotEscapeTheSession() throws Exception {
    Path root = session();
    Path raw = ManagedSessionLayout.at(root).rawDirectory();
    Path outside = Files.createDirectory(temporary.resolve("outside-raw"));
    Files.createFile(outside.resolve(InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE));
    Files.delete(raw);
    Files.createSymbolicLink(raw, outside.toRealPath());
    assertThatThrownBy(
            () -> ComparisonSources.resolve(Source.selected(root), sessions, () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("raw directory escapes");
  }

  @Test
  void cancellationIsCheckedBeforeIoAndDuringManifestReading() throws Exception {
    assertThatThrownBy(
            () ->
                ComparisonSources.resolve(
                    Source.selected(temporary.resolve("does-not-exist")), sessions, () -> true))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cancelled");
    Path root = session();
    AtomicInteger checks = new AtomicInteger();
    assertThatThrownBy(
            () ->
                ComparisonSources.resolve(
                    Source.selected(root), sessions, () -> checks.incrementAndGet() >= 2))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  void retentionCannotRemoveASessionWhileItsRawFileIsBeingResolved() throws Exception {
    Path root = session();
    Path binary = raw(root, InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    Path catalog = temporary.resolve("catalog");
    RetentionPolicy.Plan cleanup = cleanupPlan(root, catalog);
    try (var resolved = ComparisonSources.resolve(Source.selected(binary), sessions, () -> false)) {
      assertThat(resolved.lease()).isNotNull();
      SessionCatalog.SweepResult retained = sessions.applyCleanup(catalog, cleanup);
      assertThat(retained.removed()).isZero();
      assertThat(retained.failures()).anyMatch(reason -> reason.contains("application window"));
      assertThat(Files.exists(binary)).isTrue();
    }
    assertThat(sessions.applyCleanup(catalog, cleanup).removed()).isEqualTo(1);
    assertThat(Files.exists(root)).isFalse();
  }

  @Test
  void failureOpeningTheSecondSourceReleasesTheFirstSessionLease() throws Exception {
    Path root = session();
    raw(root, InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    Path catalog = temporary.resolve("catalog");
    RetentionPolicy.Plan cleanup = cleanupPlan(root, catalog);
    assertThatThrownBy(
            () ->
                ComparisonSources.open(
                    Source.selected(root),
                    Source.selected(temporary.resolve("missing.bin")),
                    temporary.resolve("scratch"),
                    sessions,
                    () -> false))
        .isInstanceOf(IOException.class);
    assertThat(sessions.applyCleanup(catalog, cleanup).removed()).isEqualTo(1);
  }

  @Test
  void savedAuditDigestIsCheckedBeforePublishingComparison() throws Exception {
    Path a = Files.createFile(temporary.resolve("a.bin"));
    Path b = Files.createFile(temporary.resolve("b.bin"));
    Source changed = new Source(a, Optional.empty(), Optional.of("0".repeat(64)));
    assertThatThrownBy(
            () ->
                ComparisonSources.open(
                    changed,
                    Source.selected(b),
                    temporary.resolve("scratch"),
                    sessions,
                    () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("evidence changed");
  }

  @Test
  void sourceOpeningRejectsTheEdtBeforeAccessingFiles() throws Exception {
    SwingUtilities.invokeAndWait(
        () ->
            assertThatThrownBy(
                    () ->
                        ComparisonSources.resolve(
                            Source.selected(temporary.resolve("missing.bin")),
                            sessions,
                            () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("off the EDT"));
  }

  private Path session() throws IOException {
    String id = UUID.randomUUID().toString();
    Path root = Files.createDirectory(temporary.resolve("session-" + id));
    Files.createDirectory(ManagedSessionLayout.at(root).rawDirectory());
    Files.writeString(ManagedSessionLayout.at(root).manifestFile(), manifest(id));
    return root.toRealPath();
  }

  private static Path raw(Path root, String name) throws IOException {
    return Files.createFile(ManagedSessionLayout.at(root).rawDirectory().resolve(name));
  }

  private static String manifest(String id) {
    return """
    {"formatVersion":1,"appVersion":"0.1.0","sessionId":"%s","createdMicros":1,"state":"READY"}
    """
        .formatted(id);
  }

  private static RetentionPolicy.Plan cleanupPlan(Path root, Path catalogDirectory)
      throws Exception {
    String id = ManagedSessionLayout.sessionIdFromDirectoryName(root).orElseThrow().toString();
    CatalogEntry entry = CatalogEntry.of(id, "fixture", root, "READY");
    try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
      catalog.record(entry);
    }
    return new RetentionPolicy.Plan(
        List.of(new RetentionPolicy.Candidate(entry, "test fixture")), 0, 0);
  }
}

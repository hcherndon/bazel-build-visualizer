package com.holtherndon.bazelviz.format.portable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive round-trips, and refuses every hostile shape plan 22.4 names.
 *
 * <p>The refusals are the bulk of this file on purpose. A writer that produces a readable archive
 * is the easy half; the half that matters is a reader that declines an archive somebody else built,
 * since plan 24's exit criterion is that archives "validate before opening" and every one of these
 * would otherwise have written something to disk before noticing.
 */
final class BvizArchiveTest {

  private static final long CREATED = 1_700_000_000_000_000L;
  private static final String SESSION_ID = "0193f0aa-1111-7000-8000-000000000000";
  private static final String OTHER_SESSION_ID = "0193f0aa-1111-7000-8000-000000000001";

  @TempDir Path tempDir;

  private Path session;

  @BeforeEach
  void buildSession() throws Exception {
    session = tempDir.resolve("session-0193f0aa-1111-7000-8000-000000000000");
    Files.createDirectories(session.resolve("raw"));
    Files.createDirectories(session.resolve("indexes"));
    Files.createDirectories(session.resolve("locks"));
    Files.writeString(session.resolve("manifest.json"), manifest(SESSION_ID));
    Files.write(session.resolve("session.sqlite"), new byte[] {'S', 'Q', 'L', 'i', 't', 'e'});
    Files.writeString(session.resolve("raw/bes-000001.journal"), "raw event bytes".repeat(20));
    Files.write(session.resolve("indexes/action-forward.csr"), new byte[512]);
    Files.writeString(session.resolve("locks/session.lock"), "pid 1234");
  }

  private Path exportComplete() throws Exception {
    Path archive = tempDir.resolve("out.bviz");
    BvizWriter.write(session, archive, BvizWriter.Options.complete("test"), "0.1.0", CREATED);
    return archive;
  }

  // --- round trip --------------------------------------------------------

  @Test
  @DisplayName("a session round-trips through an archive, byte for byte")
  void roundTrip() throws Exception {
    Path archive = exportComplete();
    Path restored = tempDir.resolve("restored");

    BvizReader.Validation validation = BvizReader.extract(archive, restored, BvizLimits.defaults());

    assertThat(validation.index().entries())
        .extracting(BvizIndex.Entry::path)
        .containsExactlyInAnyOrder(
            "manifest.json", "session.sqlite",
            "raw/bes-000001.journal", "indexes/action-forward.csr");
    assertThat(Files.readString(restored.resolve("manifest.json")))
        .isEqualTo(Files.readString(session.resolve("manifest.json")));
    assertThat(Files.readAllBytes(restored.resolve("session.sqlite")))
        .isEqualTo(Files.readAllBytes(session.resolve("session.sqlite")));
    assertThat(Files.readString(restored.resolve("raw/bes-000001.journal")))
        .isEqualTo(Files.readString(session.resolve("raw/bes-000001.journal")));
  }

  @Test
  @DisplayName("the lock directory is never exported")
  void locksAreNotPortable() throws Exception {
    BvizReader.Validation validation = BvizReader.validate(exportComplete(), BvizLimits.defaults());

    // A lock is a statement about this machine's running processes and
    // means nothing anywhere else.
    assertThat(validation.index().entries())
        .extracting(BvizIndex.Entry::path)
        .noneMatch(path -> path.startsWith("locks/"));
  }

  @Test
  @DisplayName("exporting the same session twice produces the same bytes")
  void exportIsDeterministic() throws Exception {
    Path first = tempDir.resolve("a.bviz");
    Path second = tempDir.resolve("b.bviz");
    BvizWriter.write(session, first, BvizWriter.Options.complete("n"), "0.1.0", CREATED);
    BvizWriter.write(session, second, BvizWriter.Options.complete("n"), "0.1.0", CREATED);

    assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
  }

  @Test
  @DisplayName("nothing appears at the target until every checksum verifies")
  void exportIsAtomic() throws Exception {
    Path archive = tempDir.resolve("atomic.bviz");
    // A directory where the archive should go: the rename cannot succeed,
    // and the partial file must not be left behind either.
    Files.createDirectories(archive);

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, archive, BvizWriter.Options.complete("n"), "0.1.0", CREATED))
        .isInstanceOf(IOException.class);
    assertThat(Files.exists(archive.resolveSibling("atomic.bviz.partial"))).isFalse();
    assertNoWriterScratch();
  }

  @Test
  @DisplayName("a failed export preserves an existing archive and legacy scratch-name sentinels")
  void failedExportPreservesExistingPaths() throws Exception {
    Path archive = tempDir.resolve("existing.bviz");
    Path legacyPartial = tempDir.resolve("existing.bviz.partial");
    Path legacySnapshot = tempDir.resolve(".bviz-manifest-user-data.json");
    Files.writeString(archive, "existing archive bytes");
    Files.writeString(legacyPartial, "user partial");
    Files.writeString(legacySnapshot, "user snapshot");
    Files.writeString(session.resolve("manifest.json"), "not a manifest");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, archive, BvizWriter.Options.complete("failure"), "0.1.0", CREATED))
        .isInstanceOf(BvizFormatException.class);

    assertThat(Files.readString(archive)).isEqualTo("existing archive bytes");
    assertThat(Files.readString(legacyPartial)).isEqualTo("user partial");
    assertThat(Files.readString(legacySnapshot)).isEqualTo("user snapshot");
    assertNoWriterScratch();
  }

  @Test
  @DisplayName("the space estimate is an upper bound, not a guess")
  void spaceEstimate() throws Exception {
    BvizWriter.SpaceEstimate estimate =
        BvizWriter.estimate(
            session, tempDir.resolve("x.bviz"), BvizWriter.Options.complete("n"), "0.1.0", CREATED);

    long sourceBytes =
        Files.size(session.resolve("manifest.json"))
            + Files.size(session.resolve("session.sqlite"))
            + Files.size(session.resolve("raw/bes-000001.journal"))
            + Files.size(session.resolve("indexes/action-forward.csr"));
    assertThat(estimate.sourceBytes()).isEqualTo(sourceBytes);
    assertThat(estimate.entryCount()).isEqualTo(5);
    Path archive = exportComplete();
    assertThat(Files.size(archive)).isLessThanOrEqualTo(estimate.archiveBytesUpperBound());
    assertThat(estimate.requiredBytes())
        .isEqualTo(estimate.sourceBytes() + estimate.archiveBytesUpperBound());
  }

  @Test
  @DisplayName("the generated index consumes one entry at the exact archive cap")
  void generatedIndexCountsTowardEntryLimit() throws Exception {
    BvizLimits defaults = BvizLimits.defaults();
    BvizLimits exact =
        new BvizLimits(
            defaults.maxExpandedBytes(),
            5,
            defaults.maxEntryBytes(),
            defaults.maxCompressionRatio());
    Path exactArchive = tempDir.resolve("exact-cap.bviz");

    BvizWriter.write(
        session, exactArchive, BvizWriter.Options.complete("exact"), "0.1.0", CREATED, exact);
    assertThat(exactArchive).exists();

    BvizLimits oneTooFew =
        new BvizLimits(
            defaults.maxExpandedBytes(),
            4,
            defaults.maxEntryBytes(),
            defaults.maxCompressionRatio());
    Path refused = tempDir.resolve("cap-plus-one.bviz");
    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session,
                    refused,
                    BvizWriter.Options.complete("refused"),
                    "0.1.0",
                    CREATED,
                    oneTooFew))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("entry limit");
    assertThat(refused).doesNotExist();
    assertNoWriterScratch();
  }

  @Test
  @DisplayName("ZIP admission bounds incompressible content, long names, and arithmetic overflow")
  void zipEstimateIsConservativeAndSaturating() throws Exception {
    byte[] incompressible = new byte[256 * 1024];
    for (int index = 0; index < incompressible.length; index++) {
      incompressible[index] = (byte) (index * 131 + index / 251);
    }
    String longName = "n".repeat(200) + ".journal";
    Files.write(session.resolve("raw").resolve(longName), incompressible);
    Path archive = tempDir.resolve("estimate-long.bviz");
    BvizWriter.SpaceEstimate estimate =
        BvizWriter.estimate(
            session, archive, BvizWriter.Options.complete("long"), "0.1.0", CREATED);

    BvizWriter.write(session, archive, BvizWriter.Options.complete("long"), "0.1.0", CREATED);

    assertThat(Files.size(archive)).isLessThanOrEqualTo(estimate.archiveBytesUpperBound());
    assertThat(BvizWriter.conservativeZipEntryBytes(Long.MAX_VALUE, Integer.MAX_VALUE))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(BvizWriter.saturatingAdd(Long.MAX_VALUE - 1, 2)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("export identity comes from the manifest, not the session directory name")
  void renamedSessionsRemainExportable() throws Exception {
    Path renamed = tempDir.resolve("session-" + OTHER_SESSION_ID);
    session = Files.move(session, renamed);

    BvizWriter.Result result =
        BvizWriter.write(
            session,
            tempDir.resolve("renamed.bviz"),
            BvizWriter.Options.complete("renamed"),
            "0.1.0",
            CREATED);

    assertThat(result.index().sessionId()).isEqualTo(SESSION_ID);
  }

  @Test
  @DisplayName("a replacement manifest determines the identity written to the archive")
  void replacementManifestDeterminesExportIdentity() throws Exception {
    Path replacementRoot = tempDir.resolve("replacement-staging");
    Files.createDirectory(replacementRoot);
    Path replacement = replacementRoot.resolve("manifest.json");
    Files.writeString(replacement, manifest(OTHER_SESSION_ID));
    Path replacementDatabase = replacementRoot.resolve("session.sqlite");
    Files.write(replacementDatabase, new byte[] {'r'});
    Path archive = tempDir.resolve("replacement-manifest.bviz");

    BvizWriter.Result result =
        BvizWriter.write(
            session,
            archive,
            BvizWriter.Options.redacted(
                "shared",
                replacementRoot,
                Map.of(
                    "manifest.json", replacement,
                    "session.sqlite", replacementDatabase)),
            "0.1.0",
            CREATED);

    assertThat(result.index().sessionId()).isEqualTo(OTHER_SESSION_ID);
    Path restored = tempDir.resolve("replacement-restored");
    BvizReader.extract(archive, restored, BvizLimits.defaults());
    assertThat(Files.readString(restored.resolve("manifest.json")))
        .isEqualTo(manifest(OTHER_SESSION_ID));
  }

  @Test
  @DisplayName("an invalid manifest identity is refused before archive bytes are streamed")
  void invalidManifestSessionIdIsRefusedBeforeWriting() throws Exception {
    Files.writeString(
        session.resolve("manifest.json"), manifest("0193F0AA-1111-7000-8000-000000000000"));
    Path archive = tempDir.resolve("invalid-manifest.bviz");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, archive, BvizWriter.Options.complete("invalid"), "0.1.0", CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("canonical sessionId");
    assertThat(Files.exists(archive)).isFalse();
    assertThat(Files.exists(archive.resolveSibling("invalid-manifest.bviz.partial"))).isFalse();
    try (var files = Files.list(tempDir)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.startsWith(".bviz-manifest-"));
    }
  }

  // --- redaction ---------------------------------------------------------

  @Test
  @DisplayName("a redacted archive carries no raw capture, and says so")
  void redactedArchivesDropTheRawCapture() throws Exception {
    Files.writeString(session.resolve("instrumentation-plan.json"), "Bearer plan-secret");
    Files.createDirectories(session.resolve("checkpoints"));
    Files.writeString(session.resolve("checkpoints/import.ckpt"), "Bearer checkpoint-secret");
    Files.writeString(session.resolve("indexes/opaque.sidecar"), "Bearer sidecar-secret");
    Path replacementRoot = tempDir.resolve("redacted-staging");
    Files.createDirectory(replacementRoot);
    Path redactedManifest = replacementRoot.resolve("manifest.json");
    Files.copy(session.resolve("manifest.json"), redactedManifest);
    Path redactedDatabase = replacementRoot.resolve("session.sqlite");
    Files.write(redactedDatabase, new byte[] {'r', 'e', 'd'});
    Path archive = tempDir.resolve("redacted.bviz");

    BvizWriter.Result result =
        BvizWriter.write(
            session,
            archive,
            BvizWriter.Options.redacted(
                "shared",
                replacementRoot,
                Map.of(
                    "manifest.json", redactedManifest,
                    "session.sqlite", redactedDatabase)),
            "0.1.0",
            CREATED);

    // The raw journal is the original bytes, secrets included. Exporting it
    // beside a redacted database would undo the redaction entirely.
    assertThat(result.index().entries())
        .extracting(BvizIndex.Entry::path)
        .containsExactlyInAnyOrder("manifest.json", "session.sqlite");
    assertThat(result.index().redacted()).isTrue();
    assertThat(result.index().includesRawSources()).isFalse();
    assertThat(result.describe()).contains("cannot be re-derived");

    Path restored = tempDir.resolve("restored-redacted");
    BvizReader.extract(archive, restored, BvizLimits.defaults());
    assertThat(Files.readAllBytes(restored.resolve("session.sqlite")))
        .isEqualTo(new byte[] {'r', 'e', 'd'});
    assertThat(restored.resolve("instrumentation-plan.json")).doesNotExist();
    assertThat(restored.resolve("checkpoints")).doesNotExist();
    assertThat(restored.resolve("raw")).doesNotExist();
    assertThat(restored.resolve("indexes")).doesNotExist();
  }

  @Test
  @DisplayName("writer discovery rejects source and replacement links before target creation")
  void writerRejectsLinkedInputs() throws Exception {
    Path outside = tempDir.resolve("outside.journal");
    Files.writeString(outside, "outside-secret");
    Path linked = session.resolve("raw/linked.journal");
    Files.createSymbolicLink(linked, outside);
    Path target = tempDir.resolve("linked-source.bviz");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, target, BvizWriter.Options.complete("linked"), "0.1.0", CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("symbolic link");
    assertThat(target).doesNotExist();
    Files.delete(linked);

    Path staging = tempDir.resolve("linked-staging");
    Files.createDirectory(staging);
    Path externalManifest = tempDir.resolve("external-manifest.json");
    Files.writeString(externalManifest, manifest(SESSION_ID));
    Files.createSymbolicLink(staging.resolve("manifest.json"), externalManifest);
    Files.write(staging.resolve("session.sqlite"), new byte[] {'r'});
    Path replacementTarget = tempDir.resolve("linked-replacement.bviz");
    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session,
                    replacementTarget,
                    BvizWriter.Options.redacted(
                        "linked",
                        staging,
                        Map.of(
                            "manifest.json",
                            staging.resolve("manifest.json"),
                            "session.sqlite",
                            staging.resolve("session.sqlite"))),
                    "0.1.0",
                    CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("no-follow");
    assertThat(replacementTarget).doesNotExist();
    assertThat(Files.readString(outside)).isEqualTo("outside-secret");
    assertNoWriterScratch();
  }

  @Test
  @DisplayName("the session root itself cannot be a symbolic link")
  void linkedSessionRootsAreRejected() throws Exception {
    Path linkedRoot = tempDir.resolve("linked-session-root");
    Files.createSymbolicLink(linkedRoot, session);
    Path target = tempDir.resolve("linked-root.bviz");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    linkedRoot,
                    target,
                    BvizWriter.Options.complete("linked-root"),
                    "0.1.0",
                    CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("no-follow session directory");
    assertThat(target).doesNotExist();
  }

  @Test
  @DisplayName("an output path directly inside the session is refused before directory creation")
  void directInSessionTargetsAreRejected() throws Exception {
    byte[] manifestBefore = Files.readAllBytes(session.resolve("manifest.json"));
    byte[] databaseBefore = Files.readAllBytes(session.resolve("session.sqlite"));
    byte[] rawBefore = Files.readAllBytes(session.resolve("raw/bes-000001.journal"));
    Path targetDirectory = session.resolve("exports-created-by-writer");
    Path target = targetDirectory.resolve("inside.bviz");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, target, BvizWriter.Options.complete("inside"), "0.1.0", CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("protected session");

    assertThat(targetDirectory).doesNotExist();
    assertThat(Files.readAllBytes(session.resolve("manifest.json"))).isEqualTo(manifestBefore);
    assertThat(Files.readAllBytes(session.resolve("session.sqlite"))).isEqualTo(databaseBefore);
    assertThat(Files.readAllBytes(session.resolve("raw/bes-000001.journal"))).isEqualTo(rawBefore);
  }

  @Test
  @DisplayName("a symlinked parent cannot disguise an output path inside the session")
  void aliasedInSessionTargetsAreRejected() throws Exception {
    byte[] manifestBefore = Files.readAllBytes(session.resolve("manifest.json"));
    byte[] databaseBefore = Files.readAllBytes(session.resolve("session.sqlite"));
    byte[] rawBefore = Files.readAllBytes(session.resolve("raw/bes-000001.journal"));
    Path alias = tempDir.resolve("session-output-alias");
    Files.createSymbolicLink(alias, session);
    Path hiddenDirectory = session.resolve("aliased-exports");
    Path target = alias.resolve("aliased-exports/inside.bviz");

    assertThatThrownBy(
            () ->
                BvizWriter.write(
                    session, target, BvizWriter.Options.complete("aliased"), "0.1.0", CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("protected session");

    assertThat(hiddenDirectory).doesNotExist();
    assertThat(Files.readAllBytes(session.resolve("manifest.json"))).isEqualTo(manifestBefore);
    assertThat(Files.readAllBytes(session.resolve("session.sqlite"))).isEqualTo(databaseBefore);
    assertThat(Files.readAllBytes(session.resolve("raw/bes-000001.journal"))).isEqualTo(rawBefore);
    assertNoWriterScratch();
  }

  @Test
  @DisplayName("writer scratch directories are owner-only where POSIX modes exist")
  void writerScratchIsOwnerOnly() throws Exception {
    Path scratch = BvizWriter.createOwnerOnlyTempDirectory(tempDir, ".bviz-export-test-");
    try {
      if (Files.getFileStore(scratch).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(scratch))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
      }
    } finally {
      BvizWriter.deleteTree(scratch);
    }
  }

  @Test
  @DisplayName("redacted replacements must have exact safe names under one trusted root")
  void redactedReplacementNamesAndContainmentAreExact() throws Exception {
    Path staging = tempDir.resolve("exact-staging");
    Files.createDirectory(staging);
    Files.writeString(staging.resolve("manifest.json"), manifest(SESSION_ID));
    Files.write(staging.resolve("session.sqlite"), new byte[] {'r'});
    Path external = tempDir.resolve("external.sqlite");
    Files.write(external, new byte[] {'x'});

    assertThatThrownBy(
            () ->
                BvizWriter.estimate(
                    session,
                    tempDir.resolve("wrong-name.bviz"),
                    BvizWriter.Options.redacted(
                        "bad",
                        staging,
                        Map.of(
                            "manifest.json",
                            staging.resolve("manifest.json"),
                            "session.sqlite",
                            external)),
                    "0.1.0",
                    CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("archive name");

    assertThatThrownBy(
            () ->
                BvizWriter.estimate(
                    session,
                    tempDir.resolve("extra-name.bviz"),
                    BvizWriter.Options.redacted(
                        "bad",
                        staging,
                        Map.of(
                            "manifest.json",
                            staging.resolve("manifest.json"),
                            "session.sqlite",
                            staging.resolve("session.sqlite"),
                            "raw/secret",
                            external)),
                    "0.1.0",
                    CREATED))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("exactly manifest.json and session.sqlite");
  }

  @Test
  @DisplayName("an archive claiming to be both redacted and complete is refused")
  void redactedAndCompleteIsContradictory() throws Exception {
    Path archive = tempDir.resolve("lying.bviz");
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            SESSION_ID,
            CREATED,
            /* redacted= */ true,
            /* includesRawSources= */ true,
            "",
            List.of());
    writeRawArchive(archive, index, Map.of());

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("cannot be both");
  }

  // --- refusals ----------------------------------------------------------

  @Test
  @DisplayName("a zip-slip entry name is refused before anything is written")
  void zipSlipIsRefused() throws Exception {
    Path archive = tempDir.resolve("slip.bviz");
    writeHostileArchive(archive, "../../../../tmp/pwned", "x");
    Path destination = tempDir.resolve("dest");

    assertThatThrownBy(() -> BvizReader.extract(archive, destination, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("escapes the archive root");
    assertThat(Files.exists(tempDir.resolve("tmp/pwned"))).isFalse();
  }

  @Test
  @DisplayName("an absolute entry name is refused")
  void absoluteNamesAreRefused() throws Exception {
    Path archive = tempDir.resolve("abs.bviz");
    writeHostileArchive(archive, "/etc/cron.d/root", "x");

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("absolute");
  }

  @Test
  @DisplayName("a native library in an archive is never written to disk")
  void nativeCodeIsRefused() throws Exception {
    // Plan 22.4's "never load native code from a session archive", enforced
    // by never extracting it: a .dylib is not part of a session.
    Path archive = tempDir.resolve("native.bviz");
    writeHostileArchive(archive, "raw/../libevil.dylib", "MZ");

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class);

    Path second = tempDir.resolve("native2.bviz");
    writeHostileArchive(second, "libevil.dylib", "MZ");
    assertThatThrownBy(() -> BvizReader.validate(second, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("not part of a session");
  }

  @Test
  @DisplayName("an entry the index does not list is refused")
  void unlistedEntriesAreRefused() throws Exception {
    Path archive = tempDir.resolve("extra.bviz");
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION, "0.1.0", SESSION_ID, CREATED, false, false, "", List.of());
    writeRawArchive(archive, index, Map.of("manifest.json", "{}"));

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("does not list");
  }

  @Test
  @DisplayName("an entry whose bytes do not match its checksum is refused, and not left behind")
  void checksumMismatchIsRefused() throws Exception {
    Path archive = tempDir.resolve("tampered.bviz");
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            SESSION_ID,
            CREATED,
            false,
            false,
            "",
            List.of(new BvizIndex.Entry("manifest.json", 2, "00".repeat(32))));
    writeRawArchive(archive, index, Map.of("manifest.json", "{}"));
    Path destination = tempDir.resolve("dest2");

    assertThatThrownBy(() -> BvizReader.extract(archive, destination, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("checksum");
    // Bytes of unknown provenance must not be left in a directory the user
    // is about to open.
    assertThat(Files.exists(destination.resolve("manifest.json"))).isFalse();
  }

  @Test
  @DisplayName("a duplicated entry is refused, because nothing says which one wins")
  void duplicateEntriesAreRefused() throws Exception {
    Path archive = tempDir.resolve("dupe.bviz");
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            SESSION_ID,
            CREATED,
            false,
            false,
            "",
            List.of(
                new BvizIndex.Entry("manifest.json", 2, "00".repeat(32)),
                new BvizIndex.Entry("manifest.json", 2, "11".repeat(32))));
    writeRawArchive(archive, index, Map.of());

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("twice");
  }

  @Test
  @DisplayName("an archive with no index is refused rather than guessed at")
  void missingIndexIsRefused() throws Exception {
    Path archive = tempDir.resolve("noindex.bviz");
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write("{}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("no archive.json");
  }

  @Test
  @DisplayName("a future format version is refused rather than misread")
  void futureVersionsAreRefused() throws Exception {
    Path archive = tempDir.resolve("future.bviz");
    writeIndexOnly(archive, indexJson("99", SESSION_ID));

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("format version 99");
  }

  @Test
  @DisplayName("a format version cannot wrap through a narrowing integer conversion")
  void overflowingFormatVersionsAreRefused() throws Exception {
    Path archive = tempDir.resolve("overflow-version.bviz");
    // 2^32 + 1 became 1 when cast to int, which made a hostile future format look supported.
    writeIndexOnly(archive, indexJson("4294967297", SESSION_ID));

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("formatVersion")
        .hasMessageContaining("32-bit integer");
  }

  @Test
  @DisplayName("archive session ids have one canonical UUID spelling")
  void archiveSessionIdsMustBeCanonical() throws Exception {
    assertThatThrownBy(
            () ->
                new BvizIndex(
                    BvizIndex.FORMAT_VERSION,
                    "0.1.0",
                    "../outside",
                    CREATED,
                    false,
                    false,
                    "",
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical UUID");

    Path archive = tempDir.resolve("hostile-session-id.bviz");
    writeIndexOnly(archive, indexJson("1", "../../outside"));
    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("sessionId")
        .hasMessageContaining("canonical UUID");
  }

  @Test
  @DisplayName("a zip bomb stops at the limit, not at the size it declares")
  void zipBombsStopAtTheLimit() throws Exception {
    Path archive = tempDir.resolve("bomb.bviz");
    byte[] zeroes = new byte[4 * 1024 * 1024];
    String digest = BvizWriter.hex(BvizWriter.sha256().digest(zeroes));
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            SESSION_ID,
            CREATED,
            false,
            false,
            "",
            List.of(new BvizIndex.Entry("raw/bomb.journal", zeroes.length, digest)));
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("raw/bomb.journal"));
      zip.write(zeroes);
      zip.closeEntry();
    }

    // Four megabytes of zeroes compresses about 4000:1, so the ratio limit
    // catches it. The count is of bytes the decompressor produced, never of
    // the size the entry declares.
    BvizLimits tight = new BvizLimits(1024L * 1024 * 1024, 100, 1024L * 1024 * 1024, 100);
    assertThatThrownBy(() -> BvizReader.validate(archive, tight))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("expands more than");
  }

  @Test
  @DisplayName("extracting into a directory that already holds files is refused")
  void extractionWillNotOverwrite() throws Exception {
    Path archive = exportComplete();
    Path destination = tempDir.resolve("occupied");
    Files.createDirectories(destination);
    Files.writeString(destination.resolve("something.txt"), "mine");

    assertThatThrownBy(() -> BvizReader.extract(archive, destination, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("already holds files");
  }

  @Test
  @DisplayName("a hostile entry name cannot smuggle control characters into a message")
  void errorMessagesAreEscaped() throws Exception {
    // An archive is untrusted input and its entry names are attacker-chosen
    // text. Printed verbatim into a terminal, an escape sequence clears the
    // screen and rewrites what the user thinks they read.
    Path archive = tempDir.resolve("escape.bviz");
    String hostile = "raw/" + (char) 0x1b + "[2Jcleared";
    writeHostileArchive(archive, hostile, "x");

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("\\u001b");
  }

  // --- helpers -----------------------------------------------------------

  /** An archive with a valid index and the entries given, contents verbatim. */
  private static void writeRawArchive(Path archive, BvizIndex index, Map<String, String> files)
      throws Exception {
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Map.Entry<String, String> file : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }

  private static void writeIndexOnly(Path archive, String json) throws Exception {
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(json.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
  }

  private static String indexJson(String formatVersion, String sessionId) {
    return """
    {"formatVersion":%s,"appVersion":"0.1.0","sessionId":"%s","createdMicros":1,
     "redacted":false,"includesRawSources":false,"note":"","entries":[]}
    """
        .formatted(formatVersion, sessionId);
  }

  private static String manifest(String sessionId) {
    return """
    {
      "formatVersion": 1,
      "appVersion": "0.1.0",
      "sessionId": "%s",
      "createdMicros": %d,
      "state": "READY"
    }
    """
        .formatted(sessionId, CREATED);
  }

  private void assertNoWriterScratch() throws IOException {
    try (var files = Files.list(tempDir)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.startsWith(".bviz-export-"));
    }
  }

  /** An archive whose index is valid and whose entry name is not. */
  private static void writeHostileArchive(Path archive, String name, String content)
      throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            SESSION_ID,
            CREATED,
            false,
            false,
            "",
            List.of(
                new BvizIndex.Entry(
                    name, bytes.length, BvizWriter.hex(BvizWriter.sha256().digest(bytes)))));
    writeRawArchive(archive, index, Map.of(name, content));
  }
}

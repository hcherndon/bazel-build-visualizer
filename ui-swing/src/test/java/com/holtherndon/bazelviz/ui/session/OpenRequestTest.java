package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.format.portable.BvizFormatException;
import com.holtherndon.bazelviz.format.portable.BvizIndex;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a path handed to the application turns out to be, and what happens to an archive brought in
 * from outside.
 *
 * <p>Three routes reach this — the Open menu, a command-line argument, and macOS handing over a
 * double-clicked file — and the point of the type is that all three get the same answer.
 */
final class OpenRequestTest {

  private static final long CREATED = 1_700_000_000_000_000L;
  private static final String SESSION_ID = "0193f0aa-1111-7000-8000-000000000000";
  private static final String OTHER_SESSION_ID = "0193f0aa-1111-7000-8000-000000000001";

  @TempDir Path tempDir;

  private Path session;

  @BeforeEach
  void buildSession() throws Exception {
    session = tempDir.resolve("sessions/session-0193f0aa-1111-7000-8000-000000000000");
    Files.createDirectories(session.resolve("raw"));
    Files.writeString(session.resolve("manifest.json"), manifest(SESSION_ID));
    Files.write(session.resolve("session.sqlite"), new byte[] {'S', 'Q', 'L'});
    Files.writeString(session.resolve("raw/bes-000001.journal"), "bytes".repeat(50));
  }

  private Path archive() throws Exception {
    Path archive = tempDir.resolve("exported.bviz");
    BvizWriter.write(session, archive, BvizWriter.Options.complete("t"), "0.1.0", CREATED);
    return archive;
  }

  @Test
  @DisplayName("a session directory is recognised by its manifest, not by its name")
  void sessionDirectories() throws Exception {
    assertThat(OpenRequest.classify(session).kind()).isEqualTo(OpenRequest.Kind.SESSION_DIRECTORY);

    Path renamed = tempDir.resolve("some-other-name");
    Files.move(session, renamed);
    assertThat(OpenRequest.classify(renamed).kind()).isEqualTo(OpenRequest.Kind.SESSION_DIRECTORY);
  }

  @Test
  @DisplayName("a directory with no manifest is not a session, and the message says why")
  void plainDirectories() throws Exception {
    Path plain = tempDir.resolve("downloads");
    Files.createDirectories(plain);

    OpenRequest request = OpenRequest.classify(plain);

    assertThat(request.kind()).isEqualTo(OpenRequest.Kind.UNSUPPORTED);
    assertThat(request.describeUnsupported()).contains("no manifest.json");
  }

  @Test
  @DisplayName("a .bviz file is an archive and anything else is a build event file")
  void filesAreRoutedByExtension() throws Exception {
    assertThat(OpenRequest.classify(archive()).kind()).isEqualTo(OpenRequest.Kind.PORTABLE_ARCHIVE);

    Path bep = tempDir.resolve("build.bep");
    Files.write(bep, new byte[] {1, 2, 3});
    // Binary or JSON is the importer's question, answered by reading the
    // file; the extension does not decide it.
    assertThat(OpenRequest.classify(bep).kind()).isEqualTo(OpenRequest.Kind.BEP_FILE);
    Path json = tempDir.resolve("build.json");
    Files.writeString(json, "{}");
    assertThat(OpenRequest.classify(json).kind()).isEqualTo(OpenRequest.Kind.BEP_FILE);
  }

  @Test
  @DisplayName("the extension is matched whatever its case")
  void extensionMatchingIsCaseInsensitive() throws Exception {
    Path upper = tempDir.resolve("EXPORTED.BVIZ");
    Files.copy(archive(), upper);

    assertThat(OpenRequest.classify(upper).kind()).isEqualTo(OpenRequest.Kind.PORTABLE_ARCHIVE);
  }

  @Test
  @DisplayName("a path that is not there has no route, and says so")
  void missingPaths() {
    OpenRequest request = OpenRequest.classify(tempDir.resolve("nothing-here"));

    assertThat(request.kind()).isEqualTo(OpenRequest.Kind.UNSUPPORTED);
    assertThat(request.describeUnsupported()).contains("not a file this application opens");
  }

  // --- archive import ----------------------------------------------------

  @Test
  @DisplayName("an archive is validated, extracted, and lands under its own session id")
  void archivesAreImported() throws Exception {
    Path library = tempDir.resolve("library");

    ArchiveImport.Result result = ArchiveImport.into(archive(), library, BvizLimits.defaults());

    assertThat(result.sessionRoot())
        .isEqualTo(library.toRealPath().resolve("session-0193f0aa-1111-7000-8000-000000000000"));
    assertThat(Files.readString(result.sessionRoot().resolve("manifest.json")))
        .isEqualTo(manifest(SESSION_ID));
    assertThat(result.redacted()).isFalse();
    // No staging directory is left behind.
    try (var entries = Files.list(library)) {
      assertThat(entries.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.contains(".incoming"));
    }
  }

  @Test
  @DisplayName("importing the same archive twice is refused, and points at the first copy")
  void duplicatesAreRefused() throws Exception {
    Path library = tempDir.resolve("library");
    Path archive = archive();
    ArchiveImport.into(archive, library, BvizLimits.defaults());

    assertThatThrownBy(() -> ArchiveImport.into(archive, library, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("already in the library")
        .hasMessageContaining("session-0193f0aa");
  }

  @Test
  @DisplayName("archive destinations are normalized and cannot escape the sessions root")
  void archiveDestinationsStayInsideTheLibrary() throws Exception {
    Path library = tempDir.resolve("nested/../library");

    assertThat(ArchiveImport.destinationFor(library, "0193f0aa-1111-7000-8000-000000000000"))
        .isEqualTo(
            tempDir
                .resolve("library/session-0193f0aa-1111-7000-8000-000000000000")
                .toAbsolutePath()
                .normalize());
    assertThatThrownBy(() -> ArchiveImport.destinationFor(library, "../../outside"))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("canonical UUID")
        .hasMessageContaining("no destination was created");
  }

  @Test
  @DisplayName("a symlinked sessions root adopts into and returns the physical root")
  void symlinkedSessionsRootUsesItsPhysicalDestination() throws Exception {
    Path physicalRoot = tempDir.resolve("physical-library");
    Files.createDirectories(physicalRoot);
    Path linkedRoot = tempDir.resolve("linked-library");
    try {
      Files.createSymbolicLink(linkedRoot, physicalRoot);
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
      return;
    }

    ArchiveImport.Result result = ArchiveImport.into(archive(), linkedRoot, BvizLimits.defaults());

    Path realRoot = physicalRoot.toRealPath();
    assertThat(result.sessionRoot()).isEqualTo(realRoot.resolve("session-" + SESSION_ID));
    assertThat(result.sessionRoot().toRealPath()).startsWith(realRoot);
  }

  @Test
  @DisplayName("an extracted manifest cannot claim an identity different from archive.json")
  void archiveManifestIdentityMustMatchItsIndex() throws Exception {
    Path archive = tempDir.resolve("mismatched-manifest.bviz");
    writeManifestOnlyArchive(archive, SESSION_ID, OTHER_SESSION_ID);
    Path library = tempDir.resolve("mismatch-library");

    assertThatThrownBy(() -> ArchiveImport.into(archive, library, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("extracted manifest identifies session")
        .hasMessageContaining(OTHER_SESSION_ID)
        .hasMessageContaining(SESSION_ID);
    try (var files = Files.list(library)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("portable manifests cannot use an alias spelling of the indexed UUID")
  void archiveManifestIdentityMustBeCanonical() throws Exception {
    Path archive = tempDir.resolve("alias-manifest.bviz");
    writeManifestOnlyArchive(archive, SESSION_ID, "0193F0AA-1111-7000-8000-000000000000");
    Path library = tempDir.resolve("alias-library");

    assertThatThrownBy(() -> ArchiveImport.into(archive, library, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("canonical UUID identity")
        .hasMessageContaining("nothing was adopted");
    try (var files = Files.list(library)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  @DisplayName("a rejected archive leaves nothing in the library")
  void aRejectedArchiveLeavesNothing() throws Exception {
    Path library = tempDir.resolve("library");
    Path notAnArchive = tempDir.resolve("random.bviz");
    Files.write(notAnArchive, new byte[] {'n', 'o', 't', ' ', 'a', ' ', 'z', 'i', 'p'});

    assertThatThrownBy(() -> ArchiveImport.into(notAnArchive, library, BvizLimits.defaults()))
        .isInstanceOf(IOException.class);
    assertThat(Files.exists(library) && Files.list(library).findAny().isPresent()).isFalse();
  }

  @Test
  @DisplayName("a redacted archive is imported and says what it does not carry")
  void redactedArchivesAreAnnounced() throws Exception {
    Path redactedDatabase = tempDir.resolve("redacted.sqlite");
    Files.write(redactedDatabase, new byte[] {'r'});
    Path archive = tempDir.resolve("redacted.bviz");
    BvizWriter.write(
        session,
        archive,
        BvizWriter.Options.redacted("shared", Map.of("session.sqlite", redactedDatabase)),
        "0.1.0",
        CREATED);

    ArchiveImport.Result result =
        ArchiveImport.into(archive, tempDir.resolve("library"), BvizLimits.defaults());

    assertThat(result.redacted()).isTrue();
    assertThat(result.describe()).contains("no raw capture").contains("cannot be re-run");
    assertThat(Files.exists(result.sessionRoot().resolve("raw"))).isFalse();
  }

  private static void writeManifestOnlyArchive(
      Path archive, String indexSessionId, String manifestSessionId) throws Exception {
    byte[] manifest = manifest(manifestSessionId).getBytes(StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest));
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            indexSessionId,
            CREATED,
            false,
            false,
            "",
            List.of(new BvizIndex.Entry("manifest.json", manifest.length, digest)));
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(manifest);
      zip.closeEntry();
    }
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
}

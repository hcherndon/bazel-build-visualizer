package com.holtherndon.bazelviz.format.portable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @TempDir Path tempDir;

  private Path session;

  @BeforeEach
  void buildSession() throws Exception {
    session = tempDir.resolve("session-0193f0aa-1111-7000-8000-000000000000");
    Files.createDirectories(session.resolve("raw"));
    Files.createDirectories(session.resolve("indexes"));
    Files.createDirectories(session.resolve("locks"));
    Files.writeString(session.resolve("manifest.json"), "{\"formatVersion\":1}");
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
  }

  @Test
  @DisplayName("the space estimate is an upper bound, not a guess")
  void spaceEstimate() throws Exception {
    BvizWriter.SpaceEstimate estimate =
        BvizWriter.estimate(session, tempDir.resolve("x.bviz"), BvizWriter.Options.complete("n"));

    long sourceBytes =
        Files.size(session.resolve("manifest.json"))
            + Files.size(session.resolve("session.sqlite"))
            + Files.size(session.resolve("raw/bes-000001.journal"))
            + Files.size(session.resolve("indexes/action-forward.csr"));
    assertThat(estimate.sourceBytes()).isEqualTo(sourceBytes);
    assertThat(estimate.entryCount()).isEqualTo(4);
    // Compression can only help, so the source size bounds the archive.
    Path archive = exportComplete();
    assertThat(Files.size(archive)).isLessThanOrEqualTo(estimate.sourceBytes() + 4096);
  }

  // --- redaction ---------------------------------------------------------

  @Test
  @DisplayName("a redacted archive carries no raw capture, and says so")
  void redactedArchivesDropTheRawCapture() throws Exception {
    Path redactedDatabase = tempDir.resolve("redacted.sqlite");
    Files.write(redactedDatabase, new byte[] {'r', 'e', 'd'});
    Path archive = tempDir.resolve("redacted.bviz");

    BvizWriter.Result result =
        BvizWriter.write(
            session,
            archive,
            BvizWriter.Options.redacted("shared", Map.of("session.sqlite", redactedDatabase)),
            "0.1.0",
            CREATED);

    // The raw journal is the original bytes, secrets included. Exporting it
    // beside a redacted database would undo the redaction entirely.
    assertThat(result.index().entries())
        .extracting(BvizIndex.Entry::path)
        .doesNotContain("raw/bes-000001.journal")
        .contains("session.sqlite");
    assertThat(result.index().redacted()).isTrue();
    assertThat(result.index().includesRawSources()).isFalse();
    assertThat(result.describe()).contains("cannot be re-derived");

    Path restored = tempDir.resolve("restored-redacted");
    BvizReader.extract(archive, restored, BvizLimits.defaults());
    assertThat(Files.readAllBytes(restored.resolve("session.sqlite")))
        .isEqualTo(new byte[] {'r', 'e', 'd'});
  }

  @Test
  @DisplayName("an archive claiming to be both redacted and complete is refused")
  void redactedAndCompleteIsContradictory() throws Exception {
    Path archive = tempDir.resolve("lying.bviz");
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            "s",
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
        new BvizIndex(BvizIndex.FORMAT_VERSION, "0.1.0", "s", CREATED, false, false, "", List.of());
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
            "s",
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
            "s",
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
    String json =
        """
        {"formatVersion":99,"appVersion":"9","sessionId":"s","createdMicros":1,
         "redacted":false,"includesRawSources":false,"note":"","entries":[]}\
        """;
    try (OutputStream out = Files.newOutputStream(archive);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(json.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    assertThatThrownBy(() -> BvizReader.validate(archive, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class)
        .hasMessageContaining("format version 99");
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
            "s",
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

  /** An archive whose index is valid and whose entry name is not. */
  private static void writeHostileArchive(Path archive, String name, String content)
      throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            "s",
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

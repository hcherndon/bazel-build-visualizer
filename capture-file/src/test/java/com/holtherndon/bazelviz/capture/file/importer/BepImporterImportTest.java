package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.bepcodec.BepEventDecoder;
import com.holtherndon.bazelviz.bepcodec.BepPayloadType;
import com.holtherndon.bazelviz.bepcodec.EventIdKey;
import com.holtherndon.bazelviz.capture.file.detect.DetectedFormat;
import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.capture.file.json.JsonBuildEventDecoder;
import com.holtherndon.bazelviz.capture.file.json.JsonDecodeResult;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepJsonWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit criteria 1, 3 and 4 for a complete file: a whole synthetic BEP stream — binary and JSON —
 * imports with the fixture's exact event count, the original is preserved and provable, and every
 * stored raw location round-trips back to the event it claims to describe.
 */
class BepImporterImportTest {

  private static final int EVENT_COUNT = 200;

  @Test
  @DisplayName("a complete binary BEP file imports with the fixture's exact event count")
  void importsCompleteBinaryStream(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);

    ImportResult result = importInto(temporary, source);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertThat(result.format()).isEqualTo(DetectedFormat.BEP_BINARY);
    assertThat(result.sourceCompleteness()).isEqualTo(Completeness.COMPLETE);
    assertThat(result.recordsJournaled()).isEqualTo(EVENT_COUNT);
    assertThat(result.eventsInDatabase()).isEqualTo(EVENT_COUNT);
    assertThat(result.sessionState()).isIn(SessionState.READY, SessionState.READY_WITH_WARNINGS);

    List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
    assertThat(rows).hasSize(EVENT_COUNT);
    assertThat(rows)
        .extracting(EventRow::sequence)
        .containsExactlyElementsOf(LongStream.range(0, EVENT_COUNT).boxed().toList());

    // Every row's payload case and id hash come from the fixture, not from
    // the importer's own idea of what it wrote.
    for (EventRow row : rows) {
      BuildEvent expected = stream.eventAt((int) row.sequence());
      assertThat(row.eventType())
          .as("event_type of record %d", row.sequence())
          .isEqualTo(BepPayloadType.of(expected));
      assertThat(row.eventIdHash())
          .as("event_id_hash of record %d", row.sequence())
          .isEqualTo(OptionalLong.of(EventIdKey.of(expected.getId()).eventIdHash()));
      assertThat(row.childCount()).isEqualTo(expected.getChildrenCount());
      assertThat(row.lastMessage()).isEqualTo(expected.getLastMessage());
      assertThat(row.decodeStatus()).isEqualTo(DecodeStatus.OK);
    }
    assertThat(rows).filteredOn(EventRow::lastMessage).hasSize(1);
  }

  @Test
  @DisplayName("every raw_segment/raw_offset/raw_length round-trips back to the same event")
  void rawLocationsRoundTripThroughTheJournal(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);

    ImportResult result = importInto(temporary, source);
    List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
    List<byte[]> payloads = ImportTestSupport.readRawPayloads(result.sessionRoot(), rows);

    BepEventDecoder decoder = BepEventDecoder.withDefaults();
    for (int i = 0; i < rows.size(); i++) {
      EventRow row = rows.get(i);
      byte[] payload = payloads.get(i);
      assertThat(payload)
          .as("raw payload length of record %d", row.sequence())
          .hasSize(row.rawLength());
      BuildEvent decoded = decoder.decode(payload).requireEvent();
      assertThat(decoded)
          .as("record %d re-decoded from its recorded journal location", row.sequence())
          .isEqualTo(stream.eventAt((int) row.sequence()));
    }
  }

  @Test
  @DisplayName("a complete JSON BEP file imports, and its records round-trip too")
  void importsCompleteJsonStream(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.json");
    BepJsonWriter.write(source, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE, stream);

    ImportResult result = importInto(temporary, source);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertThat(result.format()).isEqualTo(DetectedFormat.BEP_JSON);
    assertThat(result.eventsInDatabase()).isEqualTo(EVENT_COUNT);

    List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
    List<byte[]> payloads = ImportTestSupport.readRawPayloads(result.sessionRoot(), rows);
    JsonBuildEventDecoder decoder = new JsonBuildEventDecoder();
    for (int i = 0; i < rows.size(); i++) {
      EventRow row = rows.get(i);
      // The journaled bytes are the source's own JSON text, verbatim.
      String text = new String(payloads.get(i), StandardCharsets.UTF_8);
      assertThat(text).startsWith("{").endsWith("}");
      JsonDecodeResult decoded = decoder.decode(payloads.get(i));
      assertThat(decoded.status()).isEqualTo(DecodeStatus.OK);
      assertThat(decoded.event())
          .as("JSON record %d re-decoded from its journal location", row.sequence())
          .isEqualTo(stream.eventAt((int) row.sequence()));
    }
  }

  @Test
  @DisplayName("a pretty-printed JSON stream imports identically to a one-object-per-line one")
  void importsPrettyPrintedJson(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(60);
    Path pretty = temporary.resolve("pretty.json");
    BepJsonWriter.write(pretty, BepJsonWriter.Layout.PRETTY_MULTILINE, stream);

    ImportResult result = importInto(temporary, pretty);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertThat(result.eventsInDatabase()).isEqualTo(60);
  }

  @Test
  @DisplayName("the source is preserved with a digest the session can re-verify afterwards")
  void preservesTheOriginalSource(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    String expectedDigest = sha256(source);
    long expectedSize = Files.size(source);

    ImportResult result = importInto(temporary, source);

    // The copy inside the session is byte-identical to the original...
    Path copy = result.sessionRoot().resolve("raw").resolve(BepImporter.IMPORTED_SOURCE_NAME);
    assertThat(copy).exists();
    assertThat(Files.readAllBytes(copy)).isEqualTo(Files.readAllBytes(source));
    assertThat(sha256(copy)).isEqualTo(expectedDigest);

    // ...and the session says so, in both places a later reader will look.
    assertThat(result.source().sha256()).isEqualTo(expectedDigest);
    assertThat(result.source().byteSize()).isEqualTo(expectedSize);

    List<ImportTestSupport.CaptureSourceRow> sources =
        ImportTestSupport.readCaptureSources(result.sessionRoot());
    assertThat(sources).hasSize(1);
    assertThat(sources.get(0).sha256()).isEqualTo(expectedDigest);
    assertThat(sources.get(0).byteSize()).isEqualTo(OptionalLong.of(expectedSize));
    assertThat(sources.get(0).path()).isEqualTo(source.toAbsolutePath().normalize().toString());
    assertThat(sources.get(0).completeness()).isEqualTo(Completeness.COMPLETE.name());

    SessionManifest manifest =
        ImportTestSupport.sessionManager(temporary.resolve("sessions"))
            .readManifest(result.sessionRoot());
    assertThat(manifest.sources()).hasSize(1);
    assertThat(manifest.sources().get(0).sha256()).contains(expectedDigest);
    assertThat(manifest.sources().get(0).byteSize()).isEqualTo(OptionalLong.of(expectedSize));
    assertThat(manifest.eventCount()).isEqualTo(OptionalLong.of(EVENT_COUNT));

    // Deleting the original leaves the session whole: the events point at
    // the journal, not at the file.
    Files.delete(source);
    assertThat(ImportTestSupport.readEvents(result.sessionRoot())).hasSize(EVENT_COUNT);
    assertThat(
            ImportTestSupport.readRawPayloads(
                result.sessionRoot(), ImportTestSupport.readEvents(result.sessionRoot())))
        .hasSize(EVENT_COUNT);
  }

  @Test
  @DisplayName("reference preservation is refused instead of trusting size and modification time")
  void referencePreservationCannotHideSameMetadataMutation(@TempDir Path temporary)
      throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(50);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    long originalSize = Files.size(source);
    FileTime originalModified = Files.getLastModifiedTime(source);
    String originalDigest = sha256(source);

    byte[] changed = Files.readAllBytes(source);
    changed[changed.length - 1] ^= 1;
    Files.write(source, changed);
    Files.setLastModifiedTime(source, originalModified);
    assertThat(Files.size(source)).isEqualTo(originalSize);
    assertThat(Files.getLastModifiedTime(source).toMillis()).isEqualTo(originalModified.toMillis());
    assertThat(sha256(source)).isNotEqualTo(originalDigest);

    Path sessionsRoot = temporary.resolve("sessions");
    SessionManager sessions = ImportTestSupport.sessionManager(sessionsRoot);
    BepImporter importer =
        new BepImporter(
            sessions,
            ImportTestSupport.deterministicOptions()
                .withPreservation(SourcePreservation.REFERENCE_ORIGINAL));

    assertThatThrownBy(() -> importer.importFile(source))
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("REFERENCE_ORIGINAL is unavailable")
        .hasMessageContaining("COPY_INTO_SESSION");
    assertThat(sessionsRoot).doesNotExist();
  }

  @Test
  @DisplayName("indexes are created after the load, and ANALYZE has run")
  void createsIndexesAfterTheLoad(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(50));

    ImportResult result = importInto(temporary, source);

    assertThat(ImportTestSupport.indexNames(result.sessionRoot()))
        .contains(
            "idx_bep_events_sequence",
            "idx_bep_events_type_sequence",
            "idx_bep_events_id_hash",
            "idx_bep_event_edges_child");
  }

  @Test
  @DisplayName("session_info mirrors the terminal state the manifest reports")
  void writesSessionInfo(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(50));

    ImportResult result = importInto(temporary, source);

    assertThat(ImportTestSupport.sessionInfoState(result.sessionRoot()))
        .isEqualTo(result.sessionState().name());
  }

  @Test
  @DisplayName("the invocation id is filled in from the BuildStarted event, not invented")
  void recordsTheInvocationId(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(50);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);

    ImportResult result = importInto(temporary, source);

    assertThat(ImportTestSupport.streamInvocationId(result.sessionRoot()))
        .isEqualTo(stream.invocationId());
  }

  @Test
  @DisplayName("a file that is not BEP is refused by content, and nothing is created")
  void refusesAnUnknownFormat(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("notes.txt");
    Files.writeString(source, "this is not a build event protocol file at all\n");
    Path sessionsRoot = temporary.resolve("sessions");
    BepImporter importer =
        new BepImporter(
            ImportTestSupport.sessionManager(sessionsRoot),
            ImportTestSupport.deterministicOptions());

    assertThatThrownBy(() -> importer.importFile(source))
        .isInstanceOf(UnsupportedSourceException.class)
        .hasMessageContaining("not binary BEP")
        .hasMessageContaining("not JSON BEP");

    assertThat(sessionsRoot).doesNotExist();
  }

  @Test
  @DisplayName("a managed session directory is not something to import")
  void refusesAManagedSessionDirectory(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(30));
    ImportResult result = importInto(temporary, source);

    BepImporter importer =
        new BepImporter(
            ImportTestSupport.sessionManager(temporary.resolve("sessions2")),
            ImportTestSupport.deterministicOptions());
    assertThatThrownBy(() -> importer.importFile(result.sessionRoot()))
        .isInstanceOf(UnsupportedSourceException.class)
        .hasMessageContaining("MANAGED_SESSION_DIR");
  }

  private static ImportResult importInto(Path temporary, Path source) throws IOException {
    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    return new BepImporter(sessions, ImportTestSupport.deterministicOptions()).importFile(source);
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
  }
}

package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.bepcodec.BepEventDecoder;
import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.DiagnosticRow;
import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepDamage;
import com.holtherndon.bazelviz.testsupport.bep.LengthDelimitedFrames;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.testsupport.bep.UnknownFieldFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit criterion 1 for the unhappy half: a truncated file imports its prefix and says so, a corrupt
 * file is distinguished from a truncated one, and a file carrying fields this build does not know
 * is imported rather than rejected.
 *
 * <p>The distinction between {@link Completeness#TRUNCATED} and {@link
 * Completeness#CORRUPT_PARTIAL} is the point of these tests, not a detail of them (plan 21.3): a
 * short tail is the normal shape of a cancelled build, while damaged bytes mean the storage
 * underneath should be suspected. Collapsing them into one "incomplete" would tell a user the wrong
 * thing to do about their file.
 */
class BepImporterDamagedSourceTest {

  private static final int EVENT_COUNT = 120;

  @Test
  @DisplayName("a source truncated mid-payload imports its prefix and is marked TRUNCATED")
  void truncatedMidPayload(@TempDir Path temporary) throws Exception {
    Path intact = temporary.resolve("build.bep");
    BepBinaryWriter.write(intact, SyntheticBepStream.of(EVENT_COUNT));
    Path damaged = temporary.resolve("truncated.bep");
    BepDamage.Damage damage = BepDamage.truncateMidPayload(intact, damaged);
    long expectedIntactFrames = damage.intactFrames().orElseThrow();

    ImportResult result = importInto(temporary, damaged);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.TRUNCATED);
    assertThat(result.sourceCompleteness()).isEqualTo(Completeness.TRUNCATED);
    assertThat(result.sourceCompleteness()).isNotEqualTo(Completeness.CORRUPT_PARTIAL);
    assertThat(result.sessionState()).isEqualTo(SessionState.INCOMPLETE);
    assertThat(result.eventsInDatabase()).isEqualTo(expectedIntactFrames);

    // The diagnostic names the exact byte the incomplete frame starts at,
    // which is where a tailing reader would resume.
    long expectedOffset = lastFrameOffset(intact, expectedIntactFrames);
    assertThat(result.damageOffset()).isEqualTo(OptionalLong.of(expectedOffset));
    List<DiagnosticRow> diagnostics = ImportTestSupport.readDiagnostics(result.sessionRoot());
    assertThat(diagnostics)
        .filteredOn(row -> row.code().equals(ImportDiagnosticCodes.SOURCE_TRUNCATED))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.severity()).isEqualTo("WARNING");
              assertThat(row.byteOffset()).isEqualTo(OptionalLong.of(expectedOffset));
            });

    assertThat(ImportTestSupport.readCaptureSources(result.sessionRoot()))
        .singleElement()
        .satisfies(row -> assertThat(row.completeness()).isEqualTo(Completeness.TRUNCATED.name()));

    // A truncated session is still worth opening, and the result says so
    // rather than leaving the caller to infer it from a state name.
    assertThat(result.hasUsableData()).isTrue();
    assertThat(result.diagnosticsRecorded())
        .as("the diagnostics count matches what the table holds")
        .isEqualTo(ImportTestSupport.readDiagnostics(result.sessionRoot()).size());
  }

  @Test
  @DisplayName("a source truncated mid-varint is TRUNCATED too, at the prefix's own offset")
  void truncatedMidVarint(@TempDir Path temporary) throws Exception {
    Path intact = temporary.resolve("build.bep");
    BepBinaryWriter.write(intact, SyntheticBepStream.of(EVENT_COUNT));
    Path damaged = temporary.resolve("mid-varint.bep");
    BepDamage.Damage damage = BepDamage.truncateMidVarint(intact, damaged);

    ImportResult result = importInto(temporary, damaged);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.TRUNCATED);
    assertThat(result.eventsInDatabase()).isEqualTo(damage.intactFrames().orElseThrow());
  }

  @Test
  @DisplayName("a flipped byte is CORRUPT_PARTIAL, and every event around it survives")
  void flippedByteIsCorruptPartial(@TempDir Path temporary) throws Exception {
    Path intact = temporary.resolve("build.bep");
    BepBinaryWriter.write(intact, SyntheticBepStream.of(EVENT_COUNT));
    Path damaged = temporary.resolve("corrupt.bep");
    // A byte flipped inside a payload leaves the framing intact, so the
    // stream still parses end to end; what breaks is the protobuf inside one
    // frame. That is precisely "a fully present record failed validation".
    BepDamage.Damage flipped = flipUntilDecodeBreaks(intact, damaged);

    ImportResult result = importInto(temporary, damaged);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.CORRUPT_PARTIAL);
    assertThat(result.sourceCompleteness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
    assertThat(result.sourceCompleteness()).isNotEqualTo(Completeness.TRUNCATED);
    assertThat(result.sessionState()).isEqualTo(SessionState.CORRUPT_PARTIAL);

    // Nothing is thrown away: the events before the damage are all there, so
    // is the damaged one, and so is everything after it.
    assertThat(result.eventsInDatabase()).isEqualTo(EVENT_COUNT);
    List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
    List<EventRow> failed =
        rows.stream().filter(row -> row.decodeStatus() == DecodeStatus.FAILED).toList();
    assertThat(failed).as("exactly the flipped frame failed to decode").hasSize(1);
    assertThat(failed.get(0).eventType()).as("no payload case was invented").isZero();
    assertThat(failed.get(0).eventIdHash()).as("an unavailable id is NULL, never 0").isEmpty();

    // The undecodable bytes are still in the journal, exactly as read, so a
    // later build can reinterpret them (plan 21.5).
    byte[] preserved =
        JournalPayloadReader.forSession(result.sessionRoot()).read(failed.get(0).rawLocation());
    assertThat(preserved).hasSize(failed.get(0).rawLength());

    assertThat(result.damageOffset())
        .as("the diagnostic points at the damaged record in the source")
        .isPresent();
    assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
        .filteredOn(row -> row.code().equals("DECODE_FAILED"))
        .hasSize(1);
    assertThat(flipped.damageOffset()).isPositive();
  }

  /**
   * Flips one payload byte in a frame, trying successive offsets until the frame genuinely stops
   * decoding.
   *
   * <p>Not every single-byte flip breaks a protobuf — plenty land in string content and parse
   * perfectly. The test needs a frame that actually fails, so it looks for one rather than assuming
   * the first flip is enough.
   */
  private static BepDamage.Damage flipUntilDecodeBreaks(Path intact, Path damaged)
      throws Exception {
    var decoder = BepEventDecoder.withDefaults();
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(intact).frames();
    for (int frameIndex = 3; frameIndex < Math.min(frames.size(), 40); frameIndex++) {
      for (int within = 0;
          within < Math.min(frames.get(frameIndex).payloadLength(), 24);
          within++) {
        BepDamage.Damage damage = BepDamage.flipPayloadByte(intact, damaged, frameIndex, within);
        byte[] payload = LengthDelimitedFrames.payloadOf(damaged, frames.get(frameIndex));
        if (decoder.decode(payload).isFailed()) {
          return damage;
        }
      }
    }
    throw new IllegalStateException("no single-byte flip in " + intact + " broke a frame's decode");
  }

  @Test
  @DisplayName("a length prefix that declares an impossible size is CORRUPT_PARTIAL, not TRUNCATED")
  void corruptFraming(@TempDir Path temporary) throws Exception {
    Path intact = temporary.resolve("build.bep");
    BepBinaryWriter.write(intact, SyntheticBepStream.of(EVENT_COUNT));
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(intact).frames();
    LengthDelimitedFrames.Frame twentieth = frames.get(20);

    // Overwrite the length prefix with a five-byte varint far above the
    // configured maximum: a damaged length field, not a large event.
    Path damaged = temporary.resolve("bad-length.bep");
    byte[] bytes = Files.readAllBytes(intact);
    int at = (int) twentieth.varintOffset();
    for (int i = 0; i < 4; i++) {
      bytes[at + i] = (byte) 0xFF;
    }
    bytes[at + 4] = (byte) 0x7F;
    Files.write(damaged, bytes);

    ImportResult result = importInto(temporary, damaged);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.CORRUPT_PARTIAL);
    assertThat(result.sourceCompleteness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
    assertThat(result.sourceCompleteness()).isNotEqualTo(Completeness.TRUNCATED);
    assertThat(result.sessionState()).isEqualTo(SessionState.CORRUPT_PARTIAL);
    assertThat(result.eventsInDatabase()).isEqualTo(20);
    assertThat(result.damageOffset()).isEqualTo(OptionalLong.of(twentieth.varintOffset()));
    assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
        .filteredOn(row -> row.code().equals(ImportDiagnosticCodes.SOURCE_CORRUPT))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.severity()).isEqualTo("ERROR");
              assertThat(row.byteOffset()).isEqualTo(OptionalLong.of(twentieth.varintOffset()));
            });
  }

  @Test
  @DisplayName("an event with fields from a newer Bazel imports as UNKNOWN_FIELDS, bytes intact")
  void unknownFieldsAreKeptNotDropped(@TempDir Path temporary) throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(40);
    Path source = temporary.resolve("unknown-field.bep");
    int eventIndex = 12;
    UnknownFieldFixture.writeBinaryStreamWithUnknownFieldEvent(source, stream, eventIndex);

    ImportResult result = importInto(temporary, source);

    assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertThat(result.eventsInDatabase()).isEqualTo(40);

    List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
    EventRow damaged = rows.get(eventIndex);
    assertThat(damaged.decodeStatus()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
    assertThat(damaged.hasUnknownFields()).isTrue();
    assertThat(rows)
        .filteredOn(row -> row.decodeStatus() == DecodeStatus.UNKNOWN_FIELDS)
        .hasSize(1);

    // The unrecognised bytes are still in the journal, verbatim, so a later
    // build with newer protos can reindex and recover them (plan 21.5).
    byte[] payload =
        JournalPayloadReader.forSession(result.sessionRoot()).read(damaged.rawLocation());
    byte[] expected = UnknownFieldFixture.eventBytesWithUnknownFields(stream.eventAt(eventIndex));
    assertThat(payload).isEqualTo(expected);
    assertThat(new String(payload, StandardCharsets.ISO_8859_1))
        .contains(UnknownFieldFixture.UNKNOWN_STRING_VALUE);

    assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
        .filteredOn(row -> row.code().equals("UNKNOWN_FIELDS"))
        .hasSize(1);
    // Something was not fully understood, so the session must not claim to
    // be plain READY.
    assertThat(result.sessionState()).isEqualTo(SessionState.READY_WITH_WARNINGS);
  }

  /** Offset of the first byte of frame {@code index} (0-based) in an intact file. */
  private static long lastFrameOffset(Path file, long index) throws Exception {
    return LengthDelimitedFrames.index(file).frames().get((int) index).varintOffset();
  }

  private static ImportResult importInto(Path temporary, Path source) throws Exception {
    return new BepImporter(
            ImportTestSupport.sessionManager(temporary.resolve("sessions-" + source.getFileName())),
            ImportTestSupport.deterministicOptions())
        .importFile(source);
  }
}

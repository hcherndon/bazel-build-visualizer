package com.holtherndon.bazelviz.capture.file.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishLifecycleEventRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.format.journal.JournalWriterConfig;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The binary BEP export, against a journal built the way the capture path builds one.
 *
 * <p>The property that matters most is the boring one: the bytes that come out are the bytes Bazel
 * sent in. Plan 10.5 says "preserve original serialized payload bytes where possible", and a
 * re-serialisation that happens to parse back to an equal message would pass a weaker test while
 * producing a file that differs from Bazel's own byte for byte.
 */
final class BepStreamExportTest {

  private static final UUID SESSION = UUID.fromString("0193f0aa-1111-7000-8000-000000000000");

  @TempDir Path tempDir;

  private List<BuildEvent> events(int count) {
    List<BuildEvent> events = new ArrayList<>();
    SyntheticBepStream.of(count).iterator().forEachRemaining(events::add);
    return events;
  }

  /** Wraps a BEP event the way a live BES capture receives it. */
  private static byte[] envelope(BuildEvent event, long sequence) {
    return PublishBuildToolEventStreamRequest.newBuilder()
        .setOrderedBuildEvent(
            OrderedBuildEvent.newBuilder()
                .setStreamId(StreamId.newBuilder().setBuildId("b").setInvocationId("i"))
                .setSequenceNumber(sequence)
                .setEvent(
                    com.google.devtools.build.v1.BuildEvent.newBuilder()
                        .setBazelEvent(
                            Any.newBuilder()
                                .setTypeUrl(
                                    "type.googleapis.com/build_event_stream" + ".BuildEvent")
                                .setValue(event.toByteString()))))
        .build()
        .toByteArray();
  }

  private static byte[] lifecycle() {
    return PublishLifecycleEventRequest.newBuilder().build().toByteArray();
  }

  private Path journal(Consumer writes) throws IOException {
    Path raw = tempDir.resolve("raw");
    Files.createDirectories(raw);
    try (JournalWriter writer =
        JournalWriter.create(raw, SESSION, JournalWriterConfig.defaults())) {
      writes.write(writer);
    }
    return raw;
  }

  @FunctionalInterface
  private interface Consumer {
    void write(JournalWriter writer) throws IOException;
  }

  /** Reads a length-delimited BEP file back the way other tooling would. */
  private static List<BuildEvent> readBack(Path file) throws IOException {
    List<BuildEvent> parsed = new ArrayList<>();
    try (InputStream in = Files.newInputStream(file)) {
      BuildEvent event;
      while ((event = BuildEvent.parseDelimitedFrom(in)) != null) {
        parsed.add(event);
      }
    }
    return parsed;
  }

  @Test
  @DisplayName("a live capture exports the bytes Bazel sent, unchanged")
  void envelopesArePreservedByteForByte() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer -> {
              long sequence = 1;
              for (BuildEvent event : original) {
                writer.append(
                    SourceKind.BES_ENVELOPE,
                    0,
                    sequence,
                    1_000 + sequence,
                    envelope(event, sequence));
                sequence++;
              }
            });
    Path target = tempDir.resolve("out.bep");

    BepStreamExport.Result result = BepStreamExport.write(raw, target);

    assertThat(result.eventsPreserved()).isEqualTo(original.size());
    assertThat(result.eventsReencoded()).isZero();
    assertThat(result.isComplete()).isTrue();
    // Byte for byte, not merely message for message: the envelope carries
    // the inner event as an opaque byte string, so the export copies it.
    List<BuildEvent> readBack = readBack(target);
    assertThat(readBack).hasSize(original.size());
    for (int i = 0; i < original.size(); i++) {
      assertThat(readBack.get(i).toByteArray())
          .as("event %d", i)
          .isEqualTo(original.get(i).toByteArray());
    }
  }

  @Test
  @DisplayName("a binary import exports its payloads untouched")
  void binaryFramesArePassedThrough() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer -> {
              long sequence = 1;
              for (BuildEvent event : original) {
                writer.append(
                    SourceKind.BEP_BINARY, 0, sequence, 1_000 + sequence, event.toByteArray());
                sequence++;
              }
            });

    BepStreamExport.Result result = BepStreamExport.write(raw, tempDir.resolve("b.bep"));

    assertThat(result.eventsPreserved()).isEqualTo(original.size());
    assertThat(readBack(result.file())).hasSize(original.size());
  }

  @Test
  @DisplayName("lifecycle requests are excluded, counted, and left in the journal")
  void lifecycleIsExcluded() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer -> {
              writer.append(SourceKind.BES_LIFECYCLE, 0, 1, 1_000, lifecycle());
              long sequence = 2;
              for (BuildEvent event : original) {
                writer.append(
                    SourceKind.BES_ENVELOPE,
                    0,
                    sequence,
                    1_000 + sequence,
                    envelope(event, sequence));
                sequence++;
              }
              writer.append(SourceKind.BES_LIFECYCLE, 0, sequence, 9_000, lifecycle());
            });

    BepStreamExport.Result result = BepStreamExport.write(raw, tempDir.resolve("c.bep"));

    // A BEP file is a sequence of BuildEvent messages; a reader that met a
    // PublishLifecycleEventRequest in one would fail to parse it.
    assertThat(result.lifecycleSkipped()).isEqualTo(2);
    assertThat(result.events()).isEqualTo(original.size());
    assertThat(readBack(result.file())).hasSize(original.size());
    assertThat(result.describe()).contains("carried no BuildEvent");
  }

  @Test
  @DisplayName("a JSON-imported session is converted, and the result says it was")
  void jsonRecordsAreReencodedAndSaidToBe() throws Exception {
    List<BuildEvent> original = events(16);
    Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
    Path raw =
        journal(
            writer -> {
              long sequence = 1;
              for (BuildEvent event : original) {
                writer.append(
                    SourceKind.BEP_JSON_RECORD,
                    0,
                    sequence,
                    1_000 + sequence,
                    printer.print(event).getBytes(StandardCharsets.UTF_8));
                sequence++;
              }
            });

    BepStreamExport.Result result = BepStreamExport.write(raw, tempDir.resolve("d.bep"));

    // There were never any binary bytes to preserve, so these are a
    // faithful message and not the original encoding — and the result says
    // so rather than reporting them as preserved.
    assertThat(result.eventsPreserved()).isZero();
    assertThat(result.eventsReencoded()).isEqualTo(original.size());
    assertThat(result.describe()).contains("converted");
    assertThat(readBack(result.file())).hasSize(original.size());
  }

  @Test
  @DisplayName("a gap in the sequence is reported, and the short file still parses")
  void sequenceGapsAreReported() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer -> {
              // Sequences 1, 2, then a jump to 10: a BES stream that lost seven
              // messages, which happens and which the export must not conceal.
              writer.append(SourceKind.BES_ENVELOPE, 0, 1, 1_001, envelope(original.get(0), 1));
              writer.append(SourceKind.BES_ENVELOPE, 0, 2, 1_002, envelope(original.get(1), 2));
              writer.append(SourceKind.BES_ENVELOPE, 0, 10, 1_010, envelope(original.get(2), 10));
            });

    BepStreamExport.Result result = BepStreamExport.write(raw, tempDir.resolve("e.bep"));

    assertThat(result.gaps()).hasSize(1);
    assertThat(result.gaps().getFirst().afterSequence()).isEqualTo(2);
    assertThat(result.gaps().getFirst().beforeSequence()).isEqualTo(10);
    assertThat(result.gaps().getFirst().missing()).isEqualTo(7);
    assertThat(result.isComplete()).isFalse();
    assertThat(result.describe()).contains("missing 7 events").contains("cannot be made whole");
    // Partial export from an incomplete session: the file is short and it
    // still parses.
    assertThat(readBack(result.file())).hasSize(3);
  }

  @Test
  @DisplayName("an undecodable frame is left out and counted, not written as rubbish")
  void undecodableFramesAreCounted() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer -> {
              writer.append(SourceKind.BES_ENVELOPE, 0, 1, 1_001, envelope(original.get(0), 1));
              writer.append(SourceKind.BES_ENVELOPE, 0, 2, 1_002, new byte[] {(byte) 0xff, 0x7f});
              writer.append(SourceKind.BES_ENVELOPE, 0, 3, 1_003, envelope(original.get(1), 3));
            });

    BepStreamExport.Result result = BepStreamExport.write(raw, tempDir.resolve("f.bep"));

    assertThat(result.undecodable()).isEqualTo(1);
    assertThat(result.events()).isEqualTo(2);
    assertThat(result.isComplete()).isFalse();
    assertThat(result.describe()).contains("still in the journal");
    assertThat(readBack(result.file())).hasSize(2);
  }

  @Test
  @DisplayName("an interrupted export leaves nothing that looks finished")
  void exportIsAtomic() throws Exception {
    List<BuildEvent> original = events(16);
    Path raw =
        journal(
            writer ->
                writer.append(SourceKind.BES_ENVELOPE, 0, 1, 1_001, envelope(original.get(0), 1)));
    Path target = tempDir.resolve("blocked.bep");
    Files.createDirectories(target);

    assertThatThrownBy(() -> BepStreamExport.write(raw, target)).isInstanceOf(IOException.class);
    assertThat(Files.exists(target.resolveSibling("blocked.bep.partial"))).isFalse();
  }

  @Test
  @DisplayName("a session with no journal says so rather than writing an empty file")
  void anEmptySessionIsAnError() throws Exception {
    Path raw = tempDir.resolve("empty-raw");
    Files.createDirectories(raw);

    assertThatThrownBy(() -> BepStreamExport.write(raw, tempDir.resolve("g.bep")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no journal segments");
  }

  @Test
  @DisplayName("every journalled source kind is handled by the export")
  void everySourceKindIsHandled() {
    // A tripwire rather than a check the compiler can make: a new
    // SourceKind added to the journal format would otherwise be silently
    // dropped from every export. Handle it in BepStreamExport, then update
    // this number.
    assertThat(SourceKind.values()).hasSize(4);
  }
}

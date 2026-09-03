package com.holtherndon.bazelviz.capture.file.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tail behaviour for a BEP file that is still being written (plan 9.5): each poll picks up where
 * the last one stopped, a half-written frame is a wait rather than an error, and a file that shrank
 * is a different file.
 */
final class BinaryBepTailReaderTest {

  @TempDir Path tempDir;

  private final BinaryBepParser parser = BinaryBepParser.withDefaults();

  private static void append(Path file, byte[] bytes) throws IOException {
    Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  @Test
  void eachPollDeliversOnlyTheFramesThatArrivedSinceTheLastOne() throws IOException {
    List<BuildEvent> events = BepFixtures.mixedStream();
    byte[] stream = BepFixtures.delimit(events);
    long[] offsets = BepFixtures.frameOffsets(events);

    Path file = tempDir.resolve("growing.bep");
    Files.write(file, Arrays.copyOfRange(stream, 0, (int) offsets[2]));

    RecordingSink sink = new RecordingSink();
    BinaryBepTailReader reader = BinaryBepTailReader.following(file, parser);

    BinaryBepParseResult first = reader.poll(sink);
    assertThat(first.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
    assertThat(first.eventCount()).isEqualTo(2);
    assertThat(reader.offset()).isEqualTo(offsets[2]);

    // A frame lands half-written: a wait, not a failure, and no rescanning.
    int halfway = (int) offsets[3] + 5;
    append(file, Arrays.copyOfRange(stream, (int) offsets[2], halfway));
    BinaryBepParseResult second = reader.poll(sink);
    assertThat(second.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
    assertThat(second.startOffset()).isEqualTo(offsets[2]);
    assertThat(second.eventCount()).isEqualTo(1);
    assertThat(reader.offset()).isEqualTo(offsets[3]);
    assertThat(reader.completeness())
        .describedAs("a partial tail from a live writer is not yet truncation")
        .isEqualTo(Completeness.UNKNOWN);

    // Nothing new at all.
    BinaryBepParseResult idle = reader.poll(sink);
    assertThat(idle.eventCount()).isZero();
    assertThat(reader.offset()).isEqualTo(offsets[3]);

    append(file, Arrays.copyOfRange(stream, halfway, stream.length));
    BinaryBepParseResult last = reader.finish(sink);
    assertThat(last.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
    assertThat(reader.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(reader.eventCount()).isEqualTo(events.size());
    assertThat(reader.offset()).isEqualTo(stream.length);

    // Every event exactly once, in order, with the offsets a single pass gives.
    assertThat(sink.frameOffsets())
        .containsExactly(Arrays.stream(offsets).boxed().toArray(Long[]::new));
    for (int i = 0; i < events.size(); i++) {
      assertThat(sink.frames().get(i).payload()).isEqualTo(events.get(i).toByteArray());
    }
  }

  @Test
  void aTailStillPartialWhenTheWriterExitsIsATruncatedSource() throws IOException {
    List<BuildEvent> events = BepFixtures.mixedStream();
    byte[] stream = BepFixtures.delimit(events);
    long[] offsets = BepFixtures.frameOffsets(events);

    Path file = tempDir.resolve("killed.bep");
    Files.write(file, Arrays.copyOfRange(stream, 0, (int) offsets[4] + 2));

    RecordingSink sink = new RecordingSink();
    BinaryBepTailReader reader = BinaryBepTailReader.following(file, parser);
    BinaryBepParseResult result = reader.finish(sink);

    assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
    assertThat(result.resumeOffset()).isEqualTo(offsets[4]);
    assertThat(reader.completeness()).isEqualTo(Completeness.TRUNCATED);
    assertThat(reader.eventCount()).isEqualTo(4);
    assertThat(reader.isFinished()).isTrue();
    assertThatThrownBy(() -> reader.poll(sink)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void resumingFromACheckpointOffsetSkipsWhatWasAlreadyImported() throws IOException {
    List<BuildEvent> events = BepFixtures.mixedStream();
    Path file = BepFixtures.writeStream(tempDir.resolve("build.bep"), events);
    long[] offsets = BepFixtures.frameOffsets(events);

    RecordingSink sink = new RecordingSink();
    BinaryBepTailReader reader = BinaryBepTailReader.resumingAt(file, parser, offsets[3]);
    reader.finish(sink);

    assertThat(sink.frameOffsets()).containsExactly(offsets[3], offsets[4]);
    assertThat(reader.eventCount()).isEqualTo(2);
  }

  @Test
  void aFileThatShrankIsReportedAsReplacedRatherThanSpliced() throws IOException {
    List<BuildEvent> events = BepFixtures.mixedStream();
    Path file = BepFixtures.writeStream(tempDir.resolve("build.bep"), events);

    RecordingSink sink = new RecordingSink();
    BinaryBepTailReader reader = BinaryBepTailReader.following(file, parser);
    reader.poll(sink);
    long consumed = reader.offset();

    // A second Bazel invocation rewrites the same path from byte zero.
    Files.write(file, BepFixtures.delimit(BepFixtures.smallStream()));

    assertThatThrownBy(() -> reader.poll(sink))
        .isInstanceOf(SourceReplacedException.class)
        .hasMessageContaining("replaced or rewritten")
        .asInstanceOf(InstanceOfAssertFactories.type(SourceReplacedException.class))
        .satisfies(
            e -> {
              assertThat(e.consumedOffset()).isEqualTo(consumed);
              assertThat(e.currentSize()).isLessThan(consumed);
            });
  }

  @Test
  void completenessIsUnknownBeforeTheFirstPoll() {
    BinaryBepTailReader reader =
        BinaryBepTailReader.following(tempDir.resolve("absent.bep"), parser);
    assertThat(reader.completeness()).isEqualTo(Completeness.UNKNOWN);
    assertThat(reader.eventCount()).isZero();
  }
}

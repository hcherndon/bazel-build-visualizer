package com.holtherndon.bazelviz.enrich.starlark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StarlarkCpuProfileParserTest {

  @TempDir Path tempDir;

  @Test
  void readsProtoPackedRepeatedValues() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("packed.gz"), StarlarkProfileFixture.standard());
    CountingSink sink = new CountingSink();

    StarlarkCpuProfileParser.ParseResult result =
        new StarlarkCpuProfileParser().parse(profile, sink);

    assertThat(sink.samples).isEqualTo(2);
    assertThat(sink.frames).isEqualTo(5);
    assertThat(sink.values).isEqualTo(2);
    assertThat(result.topLevelRecords()).isEqualTo(20);
    assertThat(result.childRecords()).isEqualTo(12);
    assertThat(result.uncompressedBytes()).isPositive();
  }

  @Test
  void readsBazelsUnpackedRepeatedValues() throws Exception {
    Path profile =
        StarlarkProfileFixture.writeUnpacked(
            tempDir.resolve("unpacked.gz"), StarlarkProfileFixture.standard());
    CountingSink sink = new CountingSink();

    new StarlarkCpuProfileParser().parse(profile, sink);

    assertThat(sink.samples).isEqualTo(2);
    assertThat(sink.frames).isEqualTo(5);
    assertThat(sink.values).isEqualTo(2);
    assertThat(sink.comments).isEqualTo(1);
  }

  @Test
  void enforcesStackDepthLimit() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("stack.gz"), StarlarkProfileFixture.standard());
    StarlarkCpuProfileParser parser = parserWith(1L << 20, 1 << 16, 2, 100, 100);

    assertThatThrownBy(() -> parser.parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("stack depth")
        .hasMessageContaining("2");
  }

  @Test
  void enforcesPerRecordLimit() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("record.gz"), StarlarkProfileFixture.standard());
    StarlarkCpuProfileParser parser = parserWith(1L << 20, 3, 100, 100, 100);

    assertThatThrownBy(() -> parser.parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("per-record limit 3 bytes");
  }

  @Test
  void enforcesDecompressedLimit() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("decompressed.gz"), StarlarkProfileFixture.standard());
    StarlarkCpuProfileParser parser = parserWith(10, 100, 100, 100, 100);

    assertThatThrownBy(() -> parser.parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("decompressed size exceeds limit 10 bytes");
  }

  @Test
  void countsSkippedUnknownFieldsTowardDecompressedLimit() throws Exception {
    Path profile =
        StarlarkProfileFixture.writeUnknownLengthDelimitedFields(
            tempDir.resolve("unknown-fields.gz"), 100, 8_000, 3);
    StarlarkCpuProfileParser parser = parserWith(12_000, 10_000, 100, 100, 100);

    assertThatThrownBy(() -> parser.parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("decompressed size exceeds limit 12000 bytes");
  }

  @Test
  void enforcesTopLevelAndNormalizedRecordLimits() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("records.gz"), StarlarkProfileFixture.standard());

    assertThatThrownBy(
            () -> parserWith(1L << 20, 1 << 16, 100, 1, 100).parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("top-level record limit 1");
    assertThatThrownBy(
            () -> parserWith(1L << 20, 1 << 16, 100, 100, 1).parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("child-record limit 1");
  }

  @Test
  void boundsCompactRepeatedChildrenByCountAsWellAsEncodedBytes() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("child-lists.gz"), StarlarkProfileFixture.expandedChildLists());

    assertThatThrownBy(() -> parserWithChildCaps(1, 100, 100).parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("sample value count exceeds limit 1");
    assertThatThrownBy(() -> parserWithChildCaps(100, 1, 100).parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("sample label count exceeds limit 1");
    assertThatThrownBy(() -> parserWithChildCaps(100, 100, 1).parse(profile, new CountingSink()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("location line count exceeds limit 1");
  }

  private static StarlarkCpuProfileParser parserWith(
      long bytes, int recordBytes, int stack, long top, long children) {
    return new StarlarkCpuProfileParser(
        new StarlarkCpuProfileParser.Limits(
            bytes,
            recordBytes,
            stack,
            StarlarkCpuProfileParser.MAX_SAMPLE_VALUES,
            StarlarkCpuProfileParser.MAX_SAMPLE_LABELS,
            StarlarkCpuProfileParser.MAX_LOCATION_LINES,
            top,
            children));
  }

  private static StarlarkCpuProfileParser parserWithChildCaps(int values, int labels, int lines) {
    return new StarlarkCpuProfileParser(
        new StarlarkCpuProfileParser.Limits(
            1L << 20, 1 << 16, 100, values, labels, lines, 100, 100));
  }

  private static final class CountingSink implements StarlarkCpuProfileParser.Sink {
    private int samples;
    private int frames;
    private int values;
    private int comments;

    @Override
    public void sampleType(StarlarkCpuProfileParser.ValueTypeRecord value) {}

    @Override
    public void sample(StarlarkCpuProfileParser.SampleRecord value) {
      samples++;
      frames += value.locationIds().length;
      values += value.values().length;
    }

    @Override
    public void mapping(StarlarkCpuProfileParser.MappingRecord value) {}

    @Override
    public void location(StarlarkCpuProfileParser.LocationRecord value) {}

    @Override
    public void function(StarlarkCpuProfileParser.FunctionRecord value) {}

    @Override
    public void string(String value) {}

    @Override
    public void comment(long stringIndex) {
      comments++;
    }
  }
}

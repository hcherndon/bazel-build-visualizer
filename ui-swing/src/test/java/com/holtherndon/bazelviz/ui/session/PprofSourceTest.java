package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.perftools.profiles.ProfileProto;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.OptionalLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PprofSourceTest {
  @TempDir Path directory;

  @Test
  void metricLabelsAreBoundedAndUnknownValuesStayUnknown() {
    var metric = new StarlarkProfileReader.SampleMetric("allocations", "count");
    assertThat(metric.format(OptionalLong.empty())).isEqualTo("—");
    assertThat(metric.format(OptionalLong.of(0))).isEqualTo("0 count");
    assertThatThrownBy(() -> new StarlarkProfileReader.SampleMetric("heap", "x".repeat(257)))
        .hasMessageContaining("256-character display limit");
  }

  @Test
  void rejectsOversizedSourcesBeforeReadingThemAndLeavesNoTemporaryFiles() throws Exception {
    Path source = directory.resolve("oversized.pprof");
    try (var channel =
        Files.newByteChannel(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(PprofSource.MAX_SOURCE_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    assertThatThrownBy(() -> PprofSource.open(source, directory))
        .hasMessageContaining("1073741824-byte source-file limit");
    try (var entries = Files.list(directory)) {
      assertThat(entries.toList()).containsExactly(source);
    }
  }

  @Test
  void standaloneReaderUsesExactBytesAndDeletesOnlyItsOwnTemporaryDatabase() throws Exception {
    Path source = directory.resolve("heap.pprof.gz");
    ProfileProto.Profile profile =
        ProfileProto.Profile.newBuilder()
            .addAllStringTable(List.of("", "inuse_space", "bytes", "allocate", "src/main.cc"))
            .addSampleType(ProfileProto.ValueType.newBuilder().setType(1).setUnit(2))
            .addFunction(ProfileProto.Function.newBuilder().setId(1).setName(3).setFilename(4))
            .addLocation(
                ProfileProto.Location.newBuilder()
                    .setId(1)
                    .addLine(ProfileProto.Line.newBuilder().setFunctionId(1).setLine(12)))
            .addSample(ProfileProto.Sample.newBuilder().addLocationId(1).addValue(12345))
            .build();
    try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(source))) {
      profile.writeTo(gzip);
    }
    byte[] original = Files.readAllBytes(source);
    try (StarlarkProfileReader reader = PprofSource.open(source, directory)) {
      assertThat(reader.sampleMetric())
          .contains(new StarlarkProfileReader.SampleMetric("inuse_space", "bytes"));
      assertThat(reader.summary().sampledCpuMicros()).hasValue(12345);
      assertThat(reader.summary().correlation())
          .isEqualTo(StarlarkProfileReader.Correlation.STANDALONE);
      assertThat(
              reader.hotFunctions(
                  new StarlarkProfileReader.FunctionQuery(
                      "", StarlarkProfileReader.FunctionSort.SELF_CPU, true),
                  0,
                  20))
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.name()).isEqualTo("allocate");
                assertThat(row.selfCpuMicros()).hasValue(12345);
              });
      assertThat(reader.directedCallGraph(OptionalLong.empty(), 80, 600).nodes()).hasSize(1);
      assertThat(reader.flameRows(OptionalLong.empty(), 100).totalCpuMicros()).hasValue(12345);
    }
    assertThat(Files.readAllBytes(source)).isEqualTo(original);
    try (var entries = Files.list(directory)) {
      assertThat(entries.toList()).containsExactly(source);
    }
  }

  @Test
  void failedImportCleansUpAndPreservesOriginal() throws Exception {
    Path source = Files.writeString(directory.resolve("bad.pprof"), "not a profile");
    assertThatThrownBy(() -> PprofSource.open(source, directory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Could not open pprof");
    try (var entries = Files.list(directory)) {
      assertThat(entries.toList()).containsExactly(source);
    }
    assertThat(Files.readString(source)).isEqualTo("not a profile");
  }
}

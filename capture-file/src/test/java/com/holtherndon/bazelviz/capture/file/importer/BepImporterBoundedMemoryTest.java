package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit criterion 5: no full file is loaded into memory.
 *
 * <p>Proved two ways, because either alone is weak.
 *
 * <p><b>By instrumentation.</b> {@link ImportResult#peakBufferBytes()} reports the high-water mark
 * of every buffer the pipeline allocates — the source read window, the journal staging buffer and
 * the payload scratch array. The test asserts it stays at the same value while the file grows
 * tenfold — the bound is the largest single record plus the configured buffers, so a footprint that
 * tracked the input would show up as a rising number rather than as a slow test.
 *
 * <p><b>By denying the memory.</b> A number the implementation reports about itself is only as
 * trustworthy as the implementation, so the same import is also run in a child JVM whose maximum
 * heap is a fraction of the file. Nothing that retained the source could survive that, and a
 * regression that started buffering — a list of records, a decoded event per row, an array of the
 * whole file — fails here with {@code OutOfMemoryError} instead of passing quietly.
 */
class BepImporterBoundedMemoryTest {

  /** Heap for the child JVM. Deliberately far below the file it must import. */
  private static final int CHILD_HEAP_MEGABYTES = 48;

  /** Events in the large fixture; sized in {@link #largeSource} to overshoot the heap. */
  private static final int LARGE_EVENT_COUNT = 400_000;

  @Test
  @DisplayName("the peak buffer stays at its bound while the file grows tenfold")
  void peakBufferDoesNotTrackFileSize(@TempDir Path temporary) throws Exception {
    ImportOptions options =
        ImportTestSupport.deterministicOptions()
            .withReadBufferBytes(8 * 1024)
            .withJournalBufferBytes(64 * 1024)
            .withCheckpointEveryRecords(500);

    long smallPeak = importAndReportPeak(temporary.resolve("small"), 500, options);
    long largePeak = importAndReportPeak(temporary.resolve("large"), 5_000, options);

    long smallBytes = Files.size(temporary.resolve("small").resolve("build.bep"));
    long largeBytes = Files.size(temporary.resolve("large").resolve("build.bep"));
    assertThat(largeBytes).as("the fixture really did grow").isGreaterThan(smallBytes * 5);

    assertThat(largePeak).as("peak buffer bytes must not grow with the file").isEqualTo(smallPeak);
    // The ceiling is the sum of the configured buffers plus the largest
    // single record, never a fraction of the file.
    assertThat(largePeak).isLessThan(largeBytes);
    assertThat(largePeak).isLessThan(1L << 20);
  }

  @Test
  @DisplayName("a file far larger than the heap imports in a child JVM that cannot buffer it")
  void importsAFileLargerThanTheHeap(@TempDir Path temporary) throws Exception {
    Path source = largeSource(temporary);
    long fileBytes = Files.size(source);
    long heapBytes = CHILD_HEAP_MEGABYTES * 1024L * 1024L;
    assertThat(fileBytes)
        .as("the fixture must be substantially larger than the child's whole heap")
        .isGreaterThan(heapBytes * 2);

    Map<String, String> output = runChildJvm(source, temporary.resolve("sessions"));

    assertThat(output.get("outcome")).isEqualTo(ImportOutcome.COMPLETE.name());
    assertThat(Long.parseLong(output.get("events"))).isEqualTo(LARGE_EVENT_COUNT);
    assertThat(Long.parseLong(output.get("fileBytes"))).isEqualTo(fileBytes);
    assertThat(Long.parseLong(output.get("maxHeapBytes")))
        .as("the child really was heap-limited")
        .isLessThan(fileBytes);

    long peak = Long.parseLong(output.get("peakBufferBytes"));
    // The claim is that the footprint is bounded by the largest single
    // record plus the configured buffers, not by the file. Both bounds are
    // asserted: a fraction of this file, and an absolute ceiling that a
    // ten-times-larger file would still have to respect.
    assertThat(peak).as("peak buffer against file size").isLessThan(fileBytes / 10);
    assertThat(peak).as("absolute peak buffer ceiling").isLessThan(16L * 1024 * 1024);
    assertThat(peak).as("the buffers fit inside the child's whole heap").isLessThan(heapBytes);
  }

  // ------------------------------------------------------------------ helpers

  private static long importAndReportPeak(Path directory, int events, ImportOptions options)
      throws IOException {
    Files.createDirectories(directory);
    Path source = directory.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(events));
    ImportResult result =
        new BepImporter(ImportTestSupport.sessionManager(directory.resolve("sessions")), options)
            .importFile(source);
    assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertThat(result.eventsInDatabase()).isEqualTo(events);
    return result.peakBufferBytes();
  }

  /** Writes a BEP file several times the child JVM's heap. */
  private static Path largeSource(Path temporary) throws IOException {
    Path source = temporary.resolve("large.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(LARGE_EVENT_COUNT));
    return source;
  }

  private static Map<String, String> runChildJvm(Path source, Path sessionsRoot) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    List<String> command =
        new ArrayList<>(
            List.of(
                java.toString(),
                "-Xmx" + CHILD_HEAP_MEGABYTES + "m",
                // sqlite-jdbc loads a native library; without the grant the JDK
                // warns today and will refuse tomorrow (see the build's comment).
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                BoundedMemoryImportMain.class.getName(),
                source.toString(),
                sessionsRoot.toString()));

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output;
    try (var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    boolean finished = process.waitFor(10, TimeUnit.MINUTES);
    assertThat(finished).as("the child JVM finished within ten minutes").isTrue();
    assertThat(process.exitValue()).as("child JVM output:%n%s", output).isZero();
    assertThat(output)
        .as("an OutOfMemoryError here means something retained the file")
        .doesNotContain("OutOfMemoryError");

    Map<String, String> fields = new HashMap<>();
    for (String line : output.split("\\R")) {
      if (!line.startsWith("maxHeapBytes=")) {
        continue;
      }
      for (String pair : line.split(" ")) {
        int equals = pair.indexOf('=');
        if (equals > 0) {
          fields.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
      }
    }
    assertThat(fields).as("child JVM output:%n%s", output).containsKey("outcome");
    return fields;
  }
}

package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Progress reporting: enough to drive a bar, throttled enough not to flood the caller, and never a
 * fabricated denominator.
 *
 * <p>The last point is the one with teeth. A progress API that reports a total it does not have —
 * so the bar can reach a satisfying 100% — is the same defect as a metric shown as zero when it is
 * unknown (plan 11.4). The total is an {@link OptionalLong} and stays empty when it is not known,
 * and {@link ImportProgress#fractionComplete()} refuses to invent one.
 */
class ImportProgressTest {

  @Test
  @DisplayName("progress is throttled well below one sample per record")
  void progressIsThrottled(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(4_000));
    long fileBytes = Files.size(source);

    List<ImportProgress> samples = new CopyOnWriteArrayList<>();
    ImportResult result =
        new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions")),
                ImportTestSupport.deterministicOptions()
                    .withProgressEveryRecords(100)
                    .withProgressIntervalMillis(0))
            .importFile(source, SessionId.random(), samples::add, () -> false);

    assertThat(result.eventsInDatabase()).isEqualTo(4_000);
    assertThat(samples).as("some progress was reported").isNotEmpty();
    assertThat(samples.size())
        .as("throttling holds the sample count far below the record count")
        .isLessThan(100);

    // Records read never go backwards, and never exceed what was imported.
    assertThat(samples)
        .extracting(ImportProgress::recordsRead)
        .isSortedAccordingTo(Comparator.naturalOrder());
    assertThat(samples)
        .allSatisfy(sample -> assertThat(sample.recordsRead()).isLessThanOrEqualTo(4_000L));
    // Before the source has been measured the total is genuinely unknown,
    // and the sample says so instead of guessing at the file's size.
    assertThat(samples)
        .filteredOn(
            sample ->
                sample.phase() == ImportPhase.DETECTING || sample.phase() == ImportPhase.PRESERVING)
        .isNotEmpty()
        .allSatisfy(
            sample -> {
              assertThat(sample.totalBytes()).isEmpty();
              assertThat(sample.fractionComplete()).isEmpty();
            });

    // Once it has been measured, bytes read never exceed the file, so the
    // bar cannot overshoot and cannot claim completion early.
    assertThat(samples)
        .filteredOn(
            sample ->
                sample.phase() == ImportPhase.READING || sample.phase() == ImportPhase.FINALIZING)
        .isNotEmpty()
        .allSatisfy(
            sample -> {
              assertThat(sample.totalBytes()).hasValue(fileBytes);
              assertThat(sample.bytesRead()).isLessThanOrEqualTo(fileBytes);
              assertThat(sample.fractionComplete()).isPresent();
              assertThat(sample.fractionComplete().getAsDouble()).isBetween(0.0, 1.0);
            });
    assertThat(samples)
        .extracting(ImportProgress::phase)
        .contains(ImportPhase.READING, ImportPhase.FINALIZING);
    assertThat(samples.get(samples.size() - 1).recordsRead()).isEqualTo(4_000);
  }

  @Test
  @DisplayName("an unknown total stays unknown rather than becoming a fake 100%")
  void unknownTotalIsNotInvented() {
    ImportProgress unknown =
        new ImportProgress(ImportPhase.READING, 12, 3_456, OptionalLong.empty());

    assertThat(unknown.totalBytes()).isEmpty();
    assertThat(unknown.fractionComplete()).isEmpty();

    // A zero total is not a denominator either: dividing by it would be an
    // instant, meaningless 100%.
    ImportProgress zeroTotal = new ImportProgress(ImportPhase.READING, 0, 0, OptionalLong.of(0));
    assertThat(zeroTotal.fractionComplete()).isEmpty();
  }

  @Test
  @DisplayName("a listener that is never called still gets an import")
  void noListenerIsFine(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(40));

    ImportResult result =
        new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions")),
                ImportTestSupport.deterministicOptions())
            .importFile(source, SessionId.random(), ImportProgressListener.NONE, () -> false);

    assertThat(result.eventsInDatabase()).isEqualTo(40);
  }
}

package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.holtherndon.bazelviz.capture.file.importer.ImportPhase;
import com.holtherndon.bazelviz.capture.file.importer.ImportProgress;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The progress model, driven by a fake clock so the rate arithmetic is exact.
 *
 * <p>The important assertions here are the negative ones: no percentage when
 * there is no total, and no rate before one could be measured. A progress bar
 * that always shows a number is easy to write and lies during exactly the
 * phases — detection, hashing, copying — where the user most wants to know
 * whether anything is happening.
 */
class ImportProgressModelTest {

    private final AtomicLong clock = new AtomicLong();
    private final ImportProgressModel model = new ImportProgressModel(clock::get);

    @Test
    @DisplayName("an unknown total means indeterminate, never a fabricated percentage")
    void unknownTotalIsIndeterminate() {
        model.start();

        ImportProgressModel.Snapshot snapshot = model.update(
                new ImportProgress(ImportPhase.PRESERVING, 0, 4_096, OptionalLong.empty()));

        assertThat(snapshot.determinate()).isFalse();
        assertThat(snapshot.totalBytes()).isEmpty();
        assertThat(snapshot.fractionComplete()).isEmpty();
        assertThat(EventValueFormat.percentage(snapshot.fractionComplete()))
                .isEqualTo(EventValueFormat.UNKNOWN);
    }

    @Test
    @DisplayName("detection reports no total at all, so nothing about it is determinate")
    void detectionIsIndeterminate() {
        model.start();

        ImportProgressModel.Snapshot snapshot = model.update(
                new ImportProgress(ImportPhase.DETECTING, 0, 0, OptionalLong.empty()));

        assertThat(snapshot.determinate()).isFalse();
        assertThat(snapshot.phase()).isEqualTo(ImportPhase.DETECTING);
    }

    @Test
    @DisplayName("a known total gives a real fraction")
    void knownTotalIsDeterminate() {
        model.start();

        ImportProgressModel.Snapshot snapshot = model.update(
                new ImportProgress(ImportPhase.READING, 500, 250, OptionalLong.of(1_000)));

        assertThat(snapshot.determinate()).isTrue();
        assertThat(snapshot.fractionComplete()).hasValue(0.25);
        assertThat(EventValueFormat.percentage(snapshot.fractionComplete())).isEqualTo("25.0%");
    }

    @Test
    @DisplayName("a total of zero bytes is not a denominator")
    void zeroTotalIsNotDeterminate() {
        model.start();

        ImportProgressModel.Snapshot snapshot = model.update(
                new ImportProgress(ImportPhase.READING, 0, 0, OptionalLong.of(0)));

        assertThat(snapshot.determinate()).isFalse();
    }

    @Test
    @DisplayName("no rate is reported until enough time has passed to measure one")
    void rateIsUnknownBeforeItCanBeMeasured() {
        model.start();

        ImportProgressModel.Snapshot immediate = model.update(
                new ImportProgress(ImportPhase.READING, 100, 1_000, OptionalLong.of(10_000)));

        assertThat(immediate.recordsPerSecond()).isEmpty();
        assertThat(immediate.bytesPerSecond()).isEmpty();
        assertThat(EventValueFormat.rate(immediate.recordsPerSecond(), "records"))
                .isEqualTo(EventValueFormat.UNKNOWN);
    }

    @Test
    @DisplayName("once a measurable interval has passed the rate is the measured one")
    void rateIsMeasuredOverTheInterval() {
        model.start();
        model.update(new ImportProgress(ImportPhase.READING, 100, 1_000, OptionalLong.of(10_000)));

        clock.set(500_000_000L); // half a second later
        ImportProgressModel.Snapshot later = model.update(
                new ImportProgress(ImportPhase.READING, 1_100, 51_000, OptionalLong.of(100_000)));

        // 1,100 records and 51,000 bytes in the first half second.
        assertThat(later.recordsPerSecond().orElseThrow()).isCloseTo(2_200, within(1.0));
        assertThat(later.bytesPerSecond().orElseThrow()).isCloseTo(102_000, within(1.0));
        assertThat(later.elapsedNanos()).isEqualTo(500_000_000L);
    }

    @Test
    @DisplayName("a sample too soon after the last one keeps the rate already measured")
    void rateIsCarriedRatherThanRecomputedFromNoTime() {
        model.start();
        model.update(new ImportProgress(ImportPhase.READING, 0, 0, OptionalLong.of(10_000)));
        clock.set(200_000_000L);
        model.update(new ImportProgress(ImportPhase.READING, 200, 2_000, OptionalLong.of(10_000)));
        double measured = model.snapshot().recordsPerSecond().orElseThrow();

        clock.set(200_001_000L); // a microsecond later
        ImportProgressModel.Snapshot soon = model.update(
                new ImportProgress(ImportPhase.READING, 201, 2_010, OptionalLong.of(10_000)));

        assertThat(soon.recordsPerSecond()).hasValue(measured);
    }

    @Test
    @DisplayName("starting a run clears the previous run's rate")
    void startClearsPreviousMeasurements() {
        model.start();
        model.update(new ImportProgress(ImportPhase.READING, 0, 0, OptionalLong.of(10)));
        clock.set(1_000_000_000L);
        model.update(new ImportProgress(ImportPhase.READING, 10, 10, OptionalLong.of(10)));
        assertThat(model.snapshot().recordsPerSecond()).isNotEmpty();

        model.start();

        assertThat(model.snapshot().recordsPerSecond()).isEmpty();
        assertThat(model.snapshot().determinate()).isFalse();
    }
}

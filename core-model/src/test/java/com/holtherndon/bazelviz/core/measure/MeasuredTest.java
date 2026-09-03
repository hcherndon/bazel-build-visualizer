package com.holtherndon.bazelviz.core.measure;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rule that unavailable data never becomes zero, in executable form. */
class MeasuredTest {

  @Test
  @DisplayName("an unknown measurement is not zero and says which source was missing")
  void unknownIsNotZero() {
    Measured<Long> bytes = Measured.unknown(DataSource.BEP, Completeness.UNKNOWN);

    assertThat(bytes.isKnown()).isFalse();
    assertThat(bytes.value()).isEmpty();
    // The source travels with the absence: "the BEP did not carry it" is a
    // different message from "we never looked", and the user gets the right
    // one.
    assertThat(bytes.source()).isEqualTo(DataSource.BEP);
  }

  @Test
  @DisplayName("a known value carries the source that observed it")
  void knownValueCarriesItsSource() {
    Measured<Long> bytes = Measured.of(4_200L, DataSource.EXECUTION_LOG);

    assertThat(bytes.isKnown()).isTrue();
    assertThat(bytes.value()).contains(4_200L);
    assertThat(bytes.source()).isEqualTo(DataSource.EXECUTION_LOG);
    assertThat(bytes.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(bytes.isCompleteObservation()).isTrue();
  }

  @Test
  @DisplayName("a derived value is sourced as derived, not as its inputs")
  void derivedValuesAreNotObservations() {
    Measured<Long> criticalPath = Measured.derived(9_000L, Completeness.COMPLETE);

    // Plan rule 14: a computed number is this application's claim, and
    // presenting it as an observation from the execution log would let a
    // reader treat a model output as a measurement.
    assertThat(criticalPath.source()).isEqualTo(DataSource.DERIVED);
    assertThat(criticalPath.source().isObserved()).isFalse();
  }

  @Test
  @DisplayName("a known value from a truncated source is real but not complete")
  void truncatedSourcesAreNotCompleteObservations() {
    Measured<Long> partial =
        new Measured<>(Optional.of(12L), DataSource.BEP, Completeness.TRUNCATED, Optional.empty());

    assertThat(partial.isKnown()).isTrue();
    // Summing this into a total presented as complete would overstate what
    // the session knows, so a total-builder can tell the difference.
    assertThat(partial.isCompleteObservation()).isFalse();
  }

  @Test
  @DisplayName("mapping keeps the provenance, because a unit change is not a new claim")
  void mappingPreservesProvenance() {
    Measured<Long> millis = Measured.of(1_500L, DataSource.PROFILE).warn("clock skew suspected");

    Measured<Double> seconds = millis.map(value -> value / 1000.0);

    assertThat(seconds.value()).contains(1.5);
    assertThat(seconds.source()).isEqualTo(DataSource.PROFILE);
    assertThat(seconds.warning()).contains("clock skew suspected");
  }

  @Test
  @DisplayName("mapping an unknown value stays unknown")
  void mappingAnUnknownStaysUnknown() {
    Measured<Long> unknown = Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE);

    assertThat(unknown.map(value -> value * 2).isKnown()).isFalse();
  }
}

package com.holtherndon.bazelviz.ui.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How values reach a cell, and what an absence looks like. */
class EntityFormatTest {

  @Test
  @DisplayName("every kind of unknown renders as the same mark")
  void unknownsAreUniform() {
    // One mark for every absence, so a user scanning a column can tell
    // values from gaps without reading each cell.
    assertThat(EntityFormat.duration(OptionalLong.empty())).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.count(OptionalLong.empty())).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.count(OptionalInt.empty())).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.text(Optional.empty())).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.exitCode(OptionalInt.empty())).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.yesNo(Optional.empty())).isEqualTo(EntityFormat.UNKNOWN);
  }

  @Test
  @DisplayName("an empty string is not an unknown value")
  void emptyTextIsTreatedAsAbsent() {
    // Bazel never sends a meaningful empty string in these positions --
    // proto3 omits a field at its default -- so an empty value here is the
    // wire's way of saying nothing, and it renders as nothing known.
    assertThat(EntityFormat.text(Optional.of(""))).isEqualTo(EntityFormat.UNKNOWN);
    assertThat(EntityFormat.text(Optional.of("x"))).isEqualTo("x");
  }

  @Test
  @DisplayName("a known zero is a zero, not an unknown")
  void zeroIsAValue() {
    // A build that executed no actions really executed none. Rendering that
    // as an em dash would be the opposite mistake to rendering an unknown
    // as zero, and just as wrong.
    assertThat(EntityFormat.count(OptionalLong.of(0))).isEqualTo("0");
    assertThat(EntityFormat.exitCode(OptionalInt.of(0))).isEqualTo("0");
    assertThat(EntityFormat.yesNo(Optional.of(false))).isEqualTo("no");
  }

  @Test
  @DisplayName("durations keep three significant figures across seven orders of magnitude")
  void durationsScale() {
    assertThat(EntityFormat.duration(42L)).isEqualTo("42 µs");
    assertThat(EntityFormat.duration(1_500L)).isEqualTo("1.5 ms");
    assertThat(EntityFormat.duration(2_500_000L)).isEqualTo("2.50 s");
    assertThat(EntityFormat.duration(125_000_000L)).isEqualTo("2 m 05 s");
  }

  @Test
  @DisplayName("a negative span is refused rather than rendered")
  void negativeSpansAreNotDurations() {
    // Bazel was measured reporting an end before a start. "-3 ms" would
    // invite the reader to believe it happened.
    assertThat(EntityFormat.duration(-3_000L)).isEqualTo(EntityFormat.UNKNOWN);
  }

  @Test
  @DisplayName("a measured duration carries its explanation into the inspector")
  void measuredFieldsCarryTheirWarning() {
    Measured<Long> missing =
        Measured.unknown(
            DataSource.BEP,
            Completeness.UNAVAILABLE,
            "this Bazel version does not report action timestamps");

    Inspection.Field field = EntityFormat.durationField("Duration", missing);

    assertThat(field.isKnown()).isFalse();
    assertThat(field.unknownNote())
        .hasValue("this Bazel version does not report action timestamps");

    Inspection.Field known =
        EntityFormat.durationField("Duration", Measured.of(1_500L, DataSource.BEP));
    assertThat(known.value()).hasValue("1.5 ms");
    assertThat(known.unknownNote()).isEmpty();
  }
}

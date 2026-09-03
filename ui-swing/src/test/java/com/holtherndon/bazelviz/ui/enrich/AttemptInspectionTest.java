package com.holtherndon.bazelviz.ui.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the attempt inspector says, and the things it must not say.
 *
 * <p>Pure: no database and no screen, because the decisions worth testing here are about wording
 * and omission rather than about layout.
 */
final class AttemptInspectionTest {

  @Test
  @DisplayName("an attempt with a duration and no start explains the gap")
  void aDurationWithNoStartExplainsItself() {
    AttemptRow row =
        attempt()
            .startMicros(OptionalLong.empty())
            .startUnknownReason(
                Optional.of(
                    "this Bazel version does not report when a spawn started,"
                        + " only how long it took"))
            .build();

    Inspection inspection = AttemptInspection.of(row);

    // Every Bazel 6.5.0 attempt is this shape (S2). A blank would read as
    // "started at the beginning of time".
    assertThat(fieldNamed(inspection, "Start"))
        .satisfies(
            field -> {
              assertThat(field.value()).isEmpty();
              assertThat(field.unknownNote())
                  .hasValueSatisfying(reason -> assertThat(reason).contains("does not report"));
            });
  }

  @Test
  @DisplayName("only the timing components that were measured are shown")
  void unmeasuredComponentsAreNotZeroes() {
    AttemptRow row =
        attempt()
            .timing(
                new AttemptRow.Timing(
                    OptionalLong.of(25_800),
                    OptionalLong.of(24_000),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty()))
            .build();

    Inspection inspection = AttemptInspection.of(row);

    // A local spawn never queued and never uploaded. "Queue: 0ms" would be
    // an answer to a question nobody asked, and would suggest a
    // measurement that was not taken.
    assertThat(fieldNames(inspection)).contains("Execution");
    assertThat(fieldNames(inspection)).doesNotContain("Queue", "Upload", "Fetch outputs");
  }

  @Test
  @DisplayName("the remainder between the total and its parts is shown as unaccounted")
  void theRemainderIsNamed() {
    AttemptRow row =
        attempt()
            .timing(
                new AttemptRow.Timing(
                    OptionalLong.of(100_000),
                    OptionalLong.of(60_000),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty()))
            .build();

    // total_time is measured separately from its parts, so the difference
    // is real. Folding it into execution would claim a measurement that
    // was not made.
    assertThat(fieldNames(AttemptInspection.of(row))).contains("Unaccounted");
  }

  @Test
  @DisplayName("an ambiguous correlation is stated, not hidden behind a blank")
  void ambiguityIsVisible() {
    AttemptRow row =
        attempt()
            .correlation(AttemptCorrelation.AMBIGUOUS)
            .correlationNote(
                Optional.of(
                    "2 actions name one of this spawn's outputs as their primary output,"
                        + " so which one ran here is undecided"))
            .build();

    Inspection inspection = AttemptInspection.of(row);

    // Plan 24's exit criterion is this reaching the screen.
    assertThat(fieldNamed(inspection, "Attached to").value())
        .hasValueSatisfying(value -> assertThat(value).contains("more than one action"));
    assertThat(fieldNamed(inspection, "Why").value())
        .hasValueSatisfying(value -> assertThat(value).contains("undecided"));
  }

  @Test
  @DisplayName("a test attempt says it is attached to a test, so nobody hunts for an action")
  void aTestSaysWhyItHasNoAction() {
    AttemptRow row =
        attempt()
            .correlation(AttemptCorrelation.MATCHED_BY_TEST_LABEL)
            .correlationNote(Optional.of("a test execution, attached to its test"))
            .build();

    assertThat(fieldNamed(AttemptInspection.of(row), "Attached to").value())
        .hasValueSatisfying(value -> assertThat(value).contains("test"));
  }

  @Test
  @DisplayName("declared-but-unproduced outputs are counted where there are any")
  void unproducedOutputsAreShown() {
    assertThat(fieldNames(AttemptInspection.of(attempt().unproducedOutputs(9).build())))
        .contains("Declared but not produced");
    // And are not mentioned when there are none, so the row is not noise.
    assertThat(fieldNames(AttemptInspection.of(attempt().unproducedOutputs(0).build())))
        .doesNotContain("Declared but not produced");
  }

  @Test
  @DisplayName("every correlation has a sentence, so none renders as an enum name")
  void everyCorrelationIsWorded() {
    for (AttemptCorrelation correlation : AttemptCorrelation.values()) {
      assertThat(AttemptInspection.describe(correlation))
          .as("%s", correlation)
          .isNotBlank()
          .doesNotContain("_");
    }
  }

  // ---------------------------------------------------------------- fixture

  private static Builder attempt() {
    return new Builder();
  }

  private static final class Builder {
    private OptionalLong startMicros = OptionalLong.of(1_000_000);
    private Optional<String> startUnknownReason = Optional.empty();
    private AttemptRow.Timing timing =
        new AttemptRow.Timing(
            OptionalLong.of(25_800),
            OptionalLong.of(25_000),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    private AttemptCorrelation correlation = AttemptCorrelation.MATCHED_BY_OUTPUT;
    private Optional<String> correlationNote = Optional.empty();
    private long unproducedOutputs;

    Builder startMicros(OptionalLong value) {
      this.startMicros = value;
      return this;
    }

    Builder startUnknownReason(Optional<String> value) {
      this.startUnknownReason = value;
      return this;
    }

    Builder timing(AttemptRow.Timing value) {
      this.timing = value;
      return this;
    }

    Builder correlation(AttemptCorrelation value) {
      this.correlation = value;
      return this;
    }

    Builder correlationNote(Optional<String> value) {
      this.correlationNote = value;
      return this;
    }

    Builder unproducedOutputs(long value) {
      this.unproducedOutputs = value;
      return this;
    }

    AttemptRow build() {
      return new AttemptRow(
          1,
          OptionalLong.of(7),
          correlation,
          correlationNote,
          Optional.of("//pkg:gen_a"),
          Optional.of("Genrule"),
          Optional.of("darwin-sandbox"),
          false,
          OptionalInt.of(0),
          Optional.empty(),
          startMicros,
          startUnknownReason,
          timing,
          Optional.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          1,
          unproducedOutputs);
    }
  }

  private static Inspection.Field fieldNamed(Inspection inspection, String name) {
    return inspection.sections().stream()
        .flatMap(section -> section.fields().stream())
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError("no field named " + name + "; have " + fieldNames(inspection)));
  }

  private static List<String> fieldNames(Inspection inspection) {
    return inspection.sections().stream()
        .flatMap(section -> section.fields().stream())
        .map(Inspection.Field::name)
        .toList();
  }
}

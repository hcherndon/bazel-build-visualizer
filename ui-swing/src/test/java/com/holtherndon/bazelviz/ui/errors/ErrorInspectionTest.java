package com.holtherndon.bazelviz.ui.errors;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the Errors inspector says about a console row, in each of the four states its journal read
 * can be in.
 *
 * <p>The bug this suite pins: an OUTPUT row's diagnostic is {@code progress.stderr} and the row
 * carries no message column, so the inspector used to render the em-dash "unknown" over text that
 * existed the whole time. Every case here is about the difference between "there is none", "it is
 * elsewhere", and "it is coming".
 */
final class ErrorInspectionTest {

  private static final RawLocation SOMEWHERE = new RawLocation(0, 4_096, 512);

  @Test
  @DisplayName("ANSI console text stays out of ordinary inspector fields")
  void consoleTextUsesItsDedicatedRenderer() {
    Inspection inspection =
        ErrorInspection.of(
            outputRow(),
            ErrorInspection.Console.text(
                "ERROR: /ws/failsyntax/BUILD.bazel:3:5: syntax error at 'outs': expected ,\n"
                    + "ERROR: error loading package 'failsyntax'\n",
                ""));

    assertThat(headings(inspection)).containsExactly("Failure");
  }

  @Test
  @DisplayName("non-empty stdout and stderr both use the dedicated renderer")
  void bothStreamsUseTheDedicatedRenderer() {
    Inspection both =
        ErrorInspection.of(outputRow(), ErrorInspection.Console.text("on stderr\n", "on stdout\n"));

    assertThat(headings(both)).containsExactly("Failure");
  }

  @Test
  @DisplayName("a console row's Message field points at the journal instead of saying unknown")
  void messageFieldPointsAtTheJournal() {
    Inspection inspection = ErrorInspection.of(outputRow());

    Inspection.Field message = field(inspection, "Failure", "Message");
    assertThat(message.isKnown()).isFalse();
    assertThat(message.unknownNote())
        .hasValueSatisfying(note -> assertThat(note).contains("in the journal"));
  }

  @Test
  @DisplayName("a read still running says so rather than showing an absence")
  void readingIsItsOwnState() {
    Inspection inspection = ErrorInspection.of(outputRow(), ErrorInspection.Console.reading());

    Inspection.Field text = field(inspection, "Console output", "Text");
    assertThat(text.isKnown()).isFalse();
    assertThat(text.unknownNote())
        .hasValueSatisfying(note -> assertThat(note).contains("reading it back from the journal"));
  }

  @Test
  @DisplayName("bytes that cannot be reached say why, and invent no text")
  void unavailableSaysWhy() {
    Inspection inspection =
        ErrorInspection.of(
            outputRow(),
            ErrorInspection.Console.unavailable("this session's raw/ directory was removed"));

    Inspection.Field text = field(inspection, "Console output", "Text");
    assertThat(text.isKnown()).isFalse();
    assertThat(text.unknownNote())
        .hasValueSatisfying(note -> assertThat(note).contains("raw/ directory was removed"));
    assertThat(headings(inspection)).doesNotContain("Console output (stderr)");
  }

  @Test
  @DisplayName("an event that decoded and said nothing is not the same as a failed read")
  void decodedSilenceIsDistinct() {
    Inspection inspection = ErrorInspection.of(outputRow(), ErrorInspection.Console.text("", ""));

    assertThat(field(inspection, "Console output", "Text").unknownNote())
        .hasValueSatisfying(
            note -> assertThat(note).contains("decoded and carried no console text"));
  }

  @Test
  @DisplayName("rows that carry their own message are untouched by any of this")
  void ordinaryRowsAreUnchanged() {
    ErrorRow failed =
        new ErrorRow(
            ErrorRow.Kind.ACTION,
            7,
            "//pkg:lib",
            Optional.of("CppCompile"),
            Optional.of("undeclared inclusion(s)"),
            OptionalLong.of(19));

    Inspection inspection = ErrorInspection.of(failed);

    assertThat(field(inspection, "Failure", "Message").value()).contains("undeclared inclusion(s)");
    assertThat(headings(inspection)).containsExactly("Failure");
  }

  @Test
  @DisplayName("a row with neither a message nor an address still says unknown, with no promise")
  void nothingToPointAtStaysUnknown() {
    ErrorRow bare =
        new ErrorRow(
            ErrorRow.Kind.TARGET,
            3,
            "//pkg:app",
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty());

    Inspection.Field message = field(ErrorInspection.of(bare), "Failure", "Message");

    assertThat(message.isKnown()).isFalse();
    assertThat(message.unknownNote()).isEmpty();
  }

  @Test
  @DisplayName("a not-built row keeps its explanation alongside the console machinery")
  void notBuiltKeepsItsNote() {
    ErrorRow aborted =
        new ErrorRow(
            ErrorRow.Kind.NOT_BUILT,
            4,
            "//pkg:sibling",
            Optional.of("INCOMPLETE"),
            Optional.empty(),
            OptionalLong.of(21));

    Inspection inspection = ErrorInspection.of(aborted);

    assertThat(field(inspection, "Failure", "Reason").value()).contains("INCOMPLETE");
    assertThat(field(inspection, "What this means", "Not built").value())
        .hasValueSatisfying(text -> assertThat(text).contains("--nokeep_going"));
  }

  // ------------------------------------------------------------- plumbing

  private static ErrorRow outputRow() {
    return new ErrorRow(
        ErrorRow.Kind.OUTPUT,
        12,
        "console output at event 9",
        Optional.of("512 bytes on stderr"),
        Optional.empty(),
        OptionalLong.of(12),
        Optional.of(SOMEWHERE));
  }

  private static List<String> headings(Inspection inspection) {
    return inspection.sections().stream().map(Inspection.Section::heading).toList();
  }

  private static Inspection.Section section(Inspection inspection, String heading) {
    return inspection.sections().stream()
        .filter(candidate -> candidate.heading().equals(heading))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no section " + heading + " in " + headings(inspection)));
  }

  private static Inspection.Field field(Inspection inspection, String heading, String name) {
    return section(inspection, heading).fields().stream()
        .filter(candidate -> candidate.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no field " + name + " under " + heading));
  }

  private static List<String> values(Inspection.Section section) {
    return section.fields().stream()
        .map(
            field ->
                field
                    .value()
                    .orElseThrow(
                        () -> new AssertionError("field " + field.name() + " has no value")))
        .toList();
  }
}

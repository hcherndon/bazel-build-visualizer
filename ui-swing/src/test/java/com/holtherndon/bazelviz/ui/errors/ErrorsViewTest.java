package com.holtherndon.bazelviz.ui.errors;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Errors card's console rows, end to end: a row on screen, a selection, a journal read on a
 * background thread, and the stderr Bazel actually wrote.
 *
 * <p>The fixture is finding X2's build — a syntax error, no failed action, no failed target, one
 * abort, and the entire diagnostic living in {@code progress.stderr}. That is the case the card
 * exists for and the case that used to render an em dash.
 */
final class ErrorsViewTest {

  private ErrorsView view;

  @AfterEach
  void closeView() throws Exception {
    if (view != null) {
      SwingUtilities.invokeAndWait(() -> view.closeSession());
    }
  }

  @Test
  @DisplayName("selecting a console row shows the stderr the source event holds")
  void selectionShowsTheStderr() throws Exception {
    FakeErrorSession session =
        new FakeErrorSession()
            .withAbortedTarget("//failsyntax:oops")
            .withConsoleEvent(
                11,
                4,
                "ERROR: /ws/failsyntax/BUILD.bazel:3:5: syntax error at 'outs': expected ," + "\n",
                "");
    open(session);

    select(0);

    awaitCondition(
        () -> headings().contains("Console output (stderr)"), "the stderr section to appear");
    assertThat(values("Console output (stderr)"))
        .containsExactly(
            "ERROR: /ws/failsyntax/BUILD.bazel:3:5: syntax error at 'outs': expected ,");
    // The whole point of reading on selection: one payload, for the one row.
    assertThat(session.rawPayloadCalls()).isEqualTo(1);
    assertThat(session.edtCalls()).as("reads that happened on the event dispatch thread").isZero();
  }

  @Test
  @DisplayName("the Message column points at the journal instead of saying unknown")
  void messageColumnPointsAtTheJournal() throws Exception {
    open(
        new FakeErrorSession()
            .withAbortedTarget("//failsyntax:oops")
            .withConsoleEvent(11, 4, "boom\n", ""));

    assertThat(onEdt(() -> view.messageCellForTest(0))).isEqualTo(ErrorsView.MESSAGE_IN_JOURNAL);
  }

  @Test
  @DisplayName("a session whose raw journal is gone says so and shows no dialog")
  void redactedSessionSaysSo() throws Exception {
    open(
        new FakeErrorSession()
            .withAbortedTarget("//failsyntax:oops")
            .withRedactedConsoleEvent(11, 4, 1_203));

    select(0);

    awaitCondition(
        () ->
            unknownNote("Console output", "Text")
                .filter(note -> note.contains("could not be read back"))
                .isPresent(),
        "the honest absence");
    // Absence, not invention: no stderr section claiming empty output.
    assertThat(headings()).doesNotContain("Console output (stderr)");
    // And the row still says how much text there was, so the user knows
    // something was lost rather than that nothing was written.
    assertThat(onEdt(() -> view.inspectionForTest()).sections().getFirst().fields())
        .anySatisfy(field -> assertThat(field.value()).contains("1,203 bytes on stderr"));
  }

  @Test
  @DisplayName("a journal that will not open costs the console rows their text, not the card")
  void anUnopenableJournalDoesNotSinkTheCard() throws Exception {
    open(
        new FakeErrorSession()
            .withUnopenableJournal()
            .withAbortedTarget("//failsyntax:oops")
            .withConsoleEvent(11, 4, "boom\n", ""));

    // The rows that never needed the journal are on screen regardless.
    assertThat(onEdt(view::rowCountForTest)).isEqualTo(1);

    select(0);

    assertThat(unknownNote("Console output", "Text"))
        .hasValueSatisfying(note -> assertThat(note).contains("no raw/ directory"));
  }

  @Test
  @DisplayName("a row that is not console output reads nothing at all")
  void otherKindsReadNothing() throws Exception {
    FakeErrorSession session =
        new FakeErrorSession()
            .withAbortedTarget("//failsyntax:oops")
            .withConsoleEvent(11, 4, "boom\n", "");
    open(session);

    // Row 0 is the console row; the abort arrives on the next page.
    awaitCondition(() -> onEdt(view::rowCountForTest) == 1, "the console row");
    SwingUtilities.invokeAndWait(() -> view.loadMoreForTest());
    awaitCondition(() -> onEdt(view::rowCountForTest) == 2, "the abort row");

    select(1);

    awaitCondition(() -> headings().contains("What this means"), "the not-built explanation");
    assertThat(session.rawPayloadCalls())
        .as("journal reads for a row whose text is already in the row")
        .isZero();
    assertThat(headings()).doesNotContain("Console output");
  }

  @Test
  @DisplayName("closing the session releases both readers it opened")
  void closingReleasesBothReaders() throws Exception {
    FakeErrorSession session =
        new FakeErrorSession()
            .withAbortedTarget("//failsyntax:oops")
            .withConsoleEvent(11, 4, "boom\n", "");
    open(session);

    SwingUtilities.invokeAndWait(() -> view.closeSession());
    view = null;

    awaitCondition(() -> session.closedReaders() == 2, "both readers to close");
  }

  // ------------------------------------------------------------- plumbing

  private void open(FakeErrorSession session) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          view = new ErrorsView();
          view.setSize(900, 500);
          view.openSession(session);
        });
    awaitCondition(() -> onEdt(view::rowCountForTest) > 0, "the first page of rows");
  }

  private void select(int row) throws Exception {
    SwingUtilities.invokeAndWait(() -> view.selectForTest(row));
  }

  private List<String> headings() {
    return onEdt(() -> view.inspectionForTest()).sections().stream()
        .map(Inspection.Section::heading)
        .toList();
  }

  private List<String> values(String heading) {
    return onEdt(() -> view.inspectionForTest()).sections().stream()
        .filter(section -> section.heading().equals(heading))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no section " + heading))
        .fields()
        .stream()
        .map(field -> field.value().orElseThrow())
        .toList();
  }

  private Optional<String> unknownNote(String heading, String name) {
    return onEdt(() -> view.inspectionForTest()).sections().stream()
        .filter(section -> section.heading().equals(heading))
        .flatMap(section -> section.fields().stream())
        .filter(field -> field.name().equals(name))
        .findFirst()
        .flatMap(Inspection.Field::unknownNote);
  }

  private static <T> T onEdt(Supplier<T> read) {
    try {
      AtomicReference<T> value = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> value.set(read.get()));
      return value.get();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private void awaitCondition(BooleanSupplier done, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      if (done.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "timed out waiting for " + what + "; the inspector shows " + headings());
  }
}

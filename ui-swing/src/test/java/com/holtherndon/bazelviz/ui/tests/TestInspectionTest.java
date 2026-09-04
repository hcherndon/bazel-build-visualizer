package com.holtherndon.bazelviz.ui.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the inspector says about a test, and what it declines to imply. */
class TestInspectionTest {

  @Test
  @DisplayName("a flaky test shows the attempt that failed")
  void flakyTestsShowTheirEvidence() {
    TestRow flaky = test(TestOutcome.FLAKY, OptionalInt.empty(), 2, 1);
    List<TestAttemptRow> attempts =
        List.of(attempt(1, TestOutcome.FAILED, false), attempt(2, TestOutcome.PASSED, false));

    Inspection inspection = TestInspection.of(flaky, attempts, List.of());

    // FLAKY is a summary-only status. Without the attempts beneath it the
    // verdict would have no evidence on the same screen.
    assertThat(inspection.subtitle()).hasValue("FLAKY");
    assertThat(fieldValues(inspection)).anyMatch(text -> text.startsWith("FAILED"));
    assertThat(fieldValues(inspection)).anyMatch(text -> text.startsWith("PASSED"));
  }

  @Test
  @DisplayName("a cached attempt says its timestamps predate the build")
  void cachedAttemptsAreFlagged() {
    TestRow cached = test(TestOutcome.PASSED, OptionalInt.empty(), 1, 0);
    Inspection inspection =
        TestInspection.of(cached, List.of(attempt(1, TestOutcome.PASSED, true)), List.of());

    // A cached attempt replays timestamps from before this build started --
    // measured 2.1 s earlier -- so plotting it naively would look like a
    // clock error.
    assertThat(fieldValues(inspection))
        .anyMatch(text -> text.contains("timestamps predate this build"));
  }

  @Test
  @DisplayName("an unsharded test says so rather than reporting zero shards")
  void unshardedTestsSaySo() {
    Inspection inspection =
        TestInspection.of(
            test(TestOutcome.PASSED, OptionalInt.empty(), 1, 0), List.of(), List.of());

    assertThat(valueOf(inspection, "Shards")).hasValue("not sharded");
  }

  @Test
  @DisplayName("Bazel's duration is labelled as Bazel's and says what it leaves out")
  void bazelsDurationIsLabelled() {
    TestRow row =
        new TestRow(
            1,
            "//t:t",
            Optional.of("cfg"),
            TestOutcome.FLAKY,
            OptionalInt.of(1),
            OptionalInt.of(1),
            OptionalInt.empty(),
            OptionalInt.of(2),
            0,
            OptionalLong.of(1_000_000L),
            OptionalLong.of(14_000_000L),
            OptionalLong.of(1_000_000L),
            OptionalLong.of(300),
            2,
            1,
            OptionalLong.empty());

    Inspection inspection = TestInspection.of(row, List.of(), List.of());

    // The two disagree by design: Bazel's excludes failed retries, and
    // understated real wall time by 13x on a measured six-attempt test.
    assertThat(valueOf(inspection, "Elapsed across attempts")).hasValue("13.00 s");
    assertThat(valueOf(inspection, "Bazel's reported duration"))
        .hasValueSatisfying(text -> assertThat(text).contains("excludes failed retries"));
  }

  @Test
  @DisplayName("a test with no attempts says why rather than showing nothing")
  void missingAttemptsAreExplained() {
    Inspection inspection =
        TestInspection.of(
            test(TestOutcome.FAILED_TO_BUILD, OptionalInt.empty(), 0, 0), List.of(), List.of());

    assertThat(noteOf(inspection, "Attempts"))
        .hasValueSatisfying(note -> assertThat(note).contains("may not have been run"));
  }

  @Test
  @DisplayName("a log is offered as a location, not as content")
  void logsAreLocations() {
    Inspection inspection =
        TestInspection.of(
            test(TestOutcome.FAILED, OptionalInt.empty(), 1, 1),
            List.of(attempt(1, TestOutcome.FAILED, false)),
            List.of(
                new TestQueries.TestLog(
                    Optional.of("test.log"),
                    "file:///out/t/test.log",
                    Optional.of("FAILED"),
                    OptionalLong.of(1))));

    assertThat(valueOf(inspection, "test.log")).hasValue("file:///out/t/test.log");
    assertThat(field(inspection, "test.log").orElseThrow().fileLink())
        .contains(FileLink.testLog("test.log", "file:///out/t/test.log"));
  }

  @Test
  @DisplayName("a stored timeout too large for microseconds is unknown rather than wrapped")
  void timeoutDisplayCannotOverflow() {
    TestRow row =
        new TestRow(
            1,
            "//t:huge_timeout_test",
            Optional.of("cfg"),
            TestOutcome.PASSED,
            OptionalInt.of(1),
            OptionalInt.of(1),
            OptionalInt.empty(),
            OptionalInt.of(1),
            0,
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(Long.MAX_VALUE),
            0,
            0,
            OptionalLong.empty());

    Inspection inspection = TestInspection.of(row, List.of(), List.of());

    assertThat(valueOf(inspection, "Timeout")).isEmpty();
    assertThat(noteOf(inspection, "Timeout"))
        .hasValueSatisfying(note -> assertThat(note).contains("too large"));
  }

  private static TestRow test(
      TestOutcome status, OptionalInt shards, long attempts, long failedAttempts) {
    return new TestRow(
        1,
        "//t:example_test",
        Optional.of("cfg"),
        status,
        OptionalInt.of(1),
        OptionalInt.of(1),
        shards,
        OptionalInt.of((int) Math.max(attempts, 1)),
        0,
        OptionalLong.of(1_000_000L),
        OptionalLong.of(2_000_000L),
        OptionalLong.of(900_000L),
        OptionalLong.of(300),
        attempts,
        failedAttempts,
        OptionalLong.of(55));
  }

  private static TestAttemptRow attempt(int number, TestOutcome status, boolean cached) {
    return new TestAttemptRow(
        number,
        1,
        1,
        1,
        number,
        status,
        cached,
        OptionalLong.of(1_000_000L),
        OptionalLong.of(120_000L),
        status == TestOutcome.PASSED ? OptionalInt.empty() : OptionalInt.of(3),
        Optional.of("darwin-sandbox"),
        OptionalLong.of(60));
  }

  private static List<String> fieldValues(Inspection inspection) {
    return inspection.sections().stream()
        .flatMap(section -> section.fields().stream())
        .flatMap(field -> field.value().stream())
        .toList();
  }

  private static Optional<String> valueOf(Inspection inspection, String name) {
    return field(inspection, name).flatMap(Inspection.Field::value);
  }

  private static Optional<String> noteOf(Inspection inspection, String name) {
    return field(inspection, name).flatMap(Inspection.Field::unknownNote);
  }

  private static Optional<Inspection.Field> field(Inspection inspection, String name) {
    return inspection.sections().stream()
        .flatMap(section -> section.fields().stream())
        .filter(field -> field.name().equals(name))
        .findFirst();
  }
}

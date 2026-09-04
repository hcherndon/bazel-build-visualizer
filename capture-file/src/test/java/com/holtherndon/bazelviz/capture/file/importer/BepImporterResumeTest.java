package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.journal.JournalReader;
import com.holtherndon.bazelviz.format.journal.JournalReaderConfig;
import com.holtherndon.bazelviz.format.journal.JournalSegments;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepJsonWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit criterion 2: restart resumes interrupted indexing.
 *
 * <p>The assertion that matters is not "the resume ran" but "the resume landed in the same place".
 * An import interrupted part way and continued must end with the same event count, the same journal
 * offsets and no duplicated rows as one that was never interrupted — otherwise a crash silently
 * changes what a session says about a build, which is worse than a crash that loses it.
 *
 * <p>Two kinds of interruption are covered, because they leave different amounts of work behind:
 *
 * <ul>
 *   <li>a cooperative cancel, which writes a final checkpoint on its way out, so the journal and
 *       the resume point agree exactly; and
 *   <li>a failure part way through a checkpoint interval, which leaves the journal ahead of the
 *       resume point and the database behind it. That is the case that exercises journal replay and
 *       record skipping, and it is the shape a real crash takes.
 * </ul>
 */
class BepImporterResumeTest {

  private static final int EVENT_COUNT = 200;
  private static final int STOP_AFTER = 60;

  @Test
  @DisplayName("a cancelled import resumes to exactly the state an uninterrupted one reaches")
  void cancelThenResumeMatchesAnUninterruptedImport(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));

    Reference reference = importUninterrupted(temporary.resolve("reference"), source);

    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    ImportResult cancelled =
        importer.importFile(
            source, SessionId.random(), ImportProgressListener.NONE, cancelAfter(STOP_AFTER));

    assertThat(cancelled.outcome()).isEqualTo(ImportOutcome.CANCELLED);
    assertThat(cancelled.eventsInDatabase()).isEqualTo(STOP_AFTER);
    // Left resumable on purpose: INCOMPLETE is terminal and would strand it.
    assertThat(cancelled.sessionState()).isEqualTo(SessionState.CAPTURING);
    assertThat(sessions.findInterrupted())
        .extracting(SessionManager.RecoveryCandidate::sessionRoot)
        .contains(cancelled.sessionRoot());

    ImportResult resumed = importer.resume(cancelled.sessionRoot());

    assertThat(resumed.resumedFromCheckpoint()).isTrue();
    assertThat(resumed.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertEndsUpLike(reference, resumed);
  }

  @Test
  @DisplayName("an import that fails between checkpoints replays the journal and loses nothing")
  void crashBetweenCheckpointsResumesWithoutDuplicates(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));

    Reference reference = importUninterrupted(temporary.resolve("reference"), source);

    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    // Checkpoints every 25 records, failure after 60: the journal holds 10
    // frames the resume point does not know about, and the database holds
    // fewer still because the last batch was never committed.
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    AtomicLong polls = new AtomicLong();
    assertThatThrownBy(
            () ->
                importer.importFile(
                    source,
                    SessionId.random(),
                    ImportProgressListener.NONE,
                    () -> {
                      if (polls.incrementAndGet() > STOP_AFTER) {
                        throw new SimulatedCrash();
                      }
                      return false;
                    }))
        .isInstanceOf(SimulatedCrash.class);

    Path sessionRoot = onlySessionUnder(temporary.resolve("sessions"));
    assertThat(sessions.readManifest(sessionRoot).state()).isEqualTo(SessionState.CAPTURING);
    long journalFramesBefore = countJournalFrames(sessionRoot);
    assertThat(journalFramesBefore).isEqualTo(STOP_AFTER);
    assertThat(ImportTestSupport.readEvents(sessionRoot).size())
        .as("the database lags the journal, which is what replay is for")
        .isLessThanOrEqualTo(STOP_AFTER);

    ImportResult resumed = importer.resume(sessionRoot);

    assertThat(resumed.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    // Replay really did re-offer rows the database already held — that is the
    // path idempotence exists for, and a resume that never exercised it would
    // prove nothing about it.
    assertThat(resumed.ingest()).isPresent();
    assertThat(resumed.ingest().get().duplicatesIgnored())
        .as("frames replayed from the journal that were already stored")
        .isPositive();
    assertThat(resumed.ingest().get().reconciles()).isTrue();
    assertEndsUpLike(reference, resumed);
  }

  @Test
  @DisplayName("a cancelled JSON import resumes at the recorded record boundary")
  void cancelledJsonImportResumes(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.json");
    BepJsonWriter.write(
        source, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE, SyntheticBepStream.of(EVENT_COUNT));

    Reference reference = importUninterrupted(temporary.resolve("reference"), source);

    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    AtomicLong seen = new AtomicLong();
    ImportResult cancelled =
        importer.importFile(
            source,
            SessionId.random(),
            ImportProgressListener.NONE,
            () -> seen.incrementAndGet() > STOP_AFTER);

    assertThat(cancelled.outcome()).isEqualTo(ImportOutcome.CANCELLED);
    assertThat(cancelled.eventsInDatabase()).isBetween(1L, (long) EVENT_COUNT - 1);

    ImportResult resumed = importer.resume(cancelled.sessionRoot());

    assertThat(resumed.outcome()).isEqualTo(ImportOutcome.COMPLETE);
    assertEndsUpLike(reference, resumed);
  }

  @Test
  @DisplayName("resuming refuses to continue over a source that changed underneath it")
  void refusesToResumeOverChangedBytes(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));

    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    ImportResult cancelled =
        importer.importFile(
            source, SessionId.random(), ImportProgressListener.NONE, cancelAfter(STOP_AFTER));

    // Corrupt the preserved copy: resuming over it would splice two
    // different files into one session.
    Path copy = cancelled.sessionRoot().resolve("raw").resolve(BepImporter.IMPORTED_SOURCE_NAME);
    byte[] bytes = Files.readAllBytes(copy);
    bytes[bytes.length - 1] ^= 0x5A;
    Files.write(copy, bytes);

    assertThatThrownBy(() -> importer.resume(cancelled.sessionRoot()))
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("changed since the import started");
  }

  @Test
  @DisplayName("a legacy reference checkpoint is refused before session recovery")
  void refusesToResumeLegacyReferenceCheckpoint(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));

    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    ImportResult cancelled =
        importer.importFile(
            source, SessionId.random(), ImportProgressListener.NONE, cancelAfter(STOP_AFTER));
    SourceCheckpointStore store =
        new SourceCheckpointStore(cancelled.sessionRoot().resolve("checkpoints"));
    SourceCheckpoint checkpoint = store.read().orElseThrow();
    store.write(
        new SourceCheckpoint(
            checkpoint.formatVersion(),
            checkpoint.sourceOffset(),
            checkpoint.framesWritten(),
            checkpoint.format(),
            SourcePreservation.REFERENCE_ORIGINAL,
            checkpoint.originalPath(),
            checkpoint.sha256(),
            checkpoint.byteSize()));

    assertThatThrownBy(() -> importer.resume(cancelled.sessionRoot()))
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("REFERENCE_ORIGINAL is unavailable")
        .hasMessageContaining("COPY_INTO_SESSION");
    assertThat(sessions.readManifest(cancelled.sessionRoot()).state())
        .isEqualTo(SessionState.CAPTURING);
  }

  @Test
  @DisplayName("a finished session has nothing to resume, and says so")
  void refusesToResumeAFinishedSession(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(40));
    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    ImportResult done = importer.importFile(source);

    assertThatThrownBy(() -> importer.resume(done.sessionRoot()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already finished");
  }

  @Test
  @DisplayName("abandoning a cancelled import marks it INCOMPLETE and keeps the data")
  void abandonMarksIncomplete(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));
    SessionManager sessions = ImportTestSupport.sessionManager(temporary.resolve("sessions"));
    BepImporter importer = new BepImporter(sessions, ImportTestSupport.deterministicOptions());
    ImportResult cancelled =
        importer.importFile(
            source, SessionId.random(), ImportProgressListener.NONE, cancelAfter(STOP_AFTER));

    importer.abandon(cancelled.sessionRoot());

    assertThat(sessions.readManifest(cancelled.sessionRoot()).state())
        .isEqualTo(SessionState.INCOMPLETE);
    assertThat(ImportTestSupport.readEvents(cancelled.sessionRoot())).hasSize(STOP_AFTER);
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Asserts that a resumed session is indistinguishable from one that was never interrupted: same
   * rows in the same order with the same journal offsets, no duplicated {@code (stream_id,
   * sequence)}, and a journal whose bytes match frame for frame.
   */
  private static void assertEndsUpLike(Reference reference, ImportResult resumed) throws Exception {
    List<EventRow> rows = ImportTestSupport.readEvents(resumed.sessionRoot());

    assertThat(rows).as("event count").hasSize(reference.rows().size());
    assertThat(rows)
        .extracting(EventRow::reproducibleForm)
        .containsExactlyElementsOf(
            reference.rows().stream().map(EventRow::reproducibleForm).toList());
    assertThat(rows)
        .extracting(EventRow::rawOffset)
        .containsExactlyElementsOf(reference.rows().stream().map(EventRow::rawOffset).toList());
    assertThat(rows)
        .extracting(row -> row.streamId() + ":" + row.sequence())
        .as("no duplicated (stream_id, sequence)")
        .doesNotHaveDuplicates();
    assertThat(resumed.eventsInDatabase()).isEqualTo(reference.result().eventsInDatabase());
    assertThat(resumed.sourceCompleteness()).isEqualTo(reference.result().sourceCompleteness());

    // The data is identical; the session state may carry one extra warning
    // that the reference cannot have, because this session genuinely was
    // interrupted and recovered. Erasing that would be the session lying
    // about its own history, so it is asserted rather than ignored.
    assertThat(resumed.sessionState()).isEqualTo(SessionState.READY_WITH_WARNINGS);
    assertThat(reference.result().sessionState())
        .isIn(SessionState.READY, SessionState.READY_WITH_WARNINGS);

    // The journal itself must match too: a resume that re-journaled records
    // it had already written would produce the same rows but a fatter file
    // with different offsets after the seam.
    byte[] expected =
        Files.readAllBytes(
            reference.result().sessionRoot().resolve("raw").resolve("bes-000000.journal"));
    byte[] actual =
        Files.readAllBytes(resumed.sessionRoot().resolve("raw").resolve("bes-000000.journal"));
    assertThat(actual.length).as("journal length").isEqualTo(expected.length);
    assertThat(Arrays.copyOfRange(actual, 32, actual.length))
        .as("journal frames past the per-session segment header")
        .isEqualTo(Arrays.copyOfRange(expected, 32, expected.length));
  }

  private static BooleanSupplier cancelAfter(long records) {
    AtomicLong polls = new AtomicLong();
    return () -> polls.incrementAndGet() > records;
  }

  private static Reference importUninterrupted(Path sessionsRoot, Path source) throws Exception {
    ImportResult result =
        new BepImporter(
                ImportTestSupport.sessionManager(sessionsRoot),
                ImportTestSupport.deterministicOptions())
            .importFile(source);
    return new Reference(result, ImportTestSupport.readEvents(result.sessionRoot()));
  }

  private static Path onlySessionUnder(Path sessionsRoot) throws Exception {
    try (var entries = Files.list(sessionsRoot)) {
      List<Path> directories = entries.filter(Files::isDirectory).toList();
      assertThat(directories).hasSize(1);
      return directories.get(0);
    }
  }

  /** Counts intact frames in a session's journal without decoding them. */
  private static long countJournalFrames(Path sessionRoot) throws Exception {
    long frames = 0;
    Path raw = sessionRoot.resolve("raw");
    for (int index : JournalSegments.listSegmentIndexes(raw)) {
      try (var reader =
          JournalReader.open(
              JournalSegments.segmentFile(raw, index), JournalReaderConfig.verifyOnly())) {
        frames += reader.verify().framesRead();
      }
    }
    return frames;
  }

  private record Reference(ImportResult result, List<EventRow> rows) {}

  /** Stands in for a process dying part way through a checkpoint interval. */
  private static final class SimulatedCrash extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SimulatedCrash() {
      super("simulated crash between checkpoints");
    }
  }
}

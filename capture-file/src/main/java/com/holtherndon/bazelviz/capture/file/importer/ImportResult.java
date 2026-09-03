package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.capture.file.detect.DetectedFormat;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.storage.events.EventWriter.IngestSummary;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What one import run did, in enough detail to write a user-facing report without re-opening the
 * session.
 *
 * <p>The counts are separate rather than summed because they answer different questions: {@code
 * recordsJournaled} is how much of the source reached the journal, {@code eventsNormalized} is how
 * much of the journal reached the database, and {@code ingest.eventsInserted} is how many rows the
 * database actually gained. On a first import all three agree; on a resume they do not, and the
 * difference is exactly the work the resume repeated idempotently.
 *
 * @param outcome how the import ended
 * @param sessionRoot the managed session directory
 * @param sessionId stable identity of the session
 * @param sessionState the state the session was left in
 * @param format what the source was detected to be
 * @param source the preserved-source record: path, digest, size
 * @param sourceCompleteness completeness recorded for the source
 * @param recordsJournaled records read from the source and appended to the journal by this run
 * @param eventsNormalized journal frames turned into rows by this run, including any replayed from
 *     a checkpoint
 * @param ingest the database's own reconciliation of that work
 * @param eventsInDatabase total {@code bep_events} rows after this run
 * @param damageOffset byte offset of the truncation or corruption in the source, empty when there
 *     was none. Never zero as a stand-in for absent
 * @param resumedFromCheckpoint true when this run continued an earlier one
 * @param peakBufferBytes the largest number of bytes the pipeline's buffers held at once during
 *     this run — read buffer, journal staging buffer and payload scratch together. Bounded by
 *     configuration, never by the size of the source; this is the number the bounded-memory test
 *     asserts against
 * @param diagnosticsRecorded rows added to {@code import_diagnostics}
 */
public record ImportResult(
    ImportOutcome outcome,
    Path sessionRoot,
    SessionId sessionId,
    SessionState sessionState,
    DetectedFormat format,
    PreservedSource source,
    Completeness sourceCompleteness,
    long recordsJournaled,
    long eventsNormalized,
    Optional<IngestSummary> ingest,
    long eventsInDatabase,
    OptionalLong damageOffset,
    boolean resumedFromCheckpoint,
    long peakBufferBytes,
    long diagnosticsRecorded) {

  public ImportResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(sessionRoot, "sessionRoot");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(sessionState, "sessionState");
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(sourceCompleteness, "sourceCompleteness");
    Objects.requireNonNull(ingest, "ingest");
    Objects.requireNonNull(damageOffset, "damageOffset");
  }

  /** True when the session can be opened and analysed, whatever the outcome. */
  public boolean hasUsableData() {
    return sourceCompleteness.hasUsableData() || eventsInDatabase > 0;
  }
}

package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParser;
import com.holtherndon.bazelviz.capture.file.json.JsonBepParserOptions;
import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.format.journal.JournalWriterConfig;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Tunables for {@link BepImporter}. Every default is stated here rather than scattered through the
 * pipeline, so the memory ceiling of an import can be read off one page.
 *
 * <p>The buffer sizes are the whole of the importer's per-record footprint: {@code readBufferBytes}
 * for the source window, {@code journalBufferBytes} for the journal staging buffer, and one payload
 * scratch array that grows to the largest single record. None of them is derived from the length of
 * the input (Phase 1 exit criterion "no full file is loaded into memory"), and the observed
 * high-water mark of all of them comes back in {@link ImportResult} so a test can assert the bound
 * instead of trusting it.
 *
 * @param preservation how the original file is preserved
 * @param checkpointEveryRecords records between durability checkpoints; each one flushes the event
 *     batch, forces the journal and rewrites the resume point, so an interrupted import loses at
 *     most this many records of work (never any data — the journal still holds it)
 * @param progressEveryRecords minimum records between progress samples
 * @param progressIntervalMillis minimum wall-clock gap between progress samples
 * @param batchSize events per committed database transaction
 * @param readBufferBytes source read window
 * @param journalBufferBytes journal staging buffer
 * @param maxRecordBytes largest single record accepted, shared by the parsers and the journal so
 *     the two limits cannot disagree
 * @param segmentBytes journal segment rotation size
 * @param maxDiagnosticsPerCode cap on repeated per-record diagnostic rows of one code. Reaching it
 *     never drops information: the true total is always recorded as a summary diagnostic at
 *     finalization
 * @param verifyDigestOnResume re-hash the preserved source when resuming, so a resume proves it is
 *     continuing over the same bytes it started on
 * @param clock source of epoch-microsecond timestamps; injectable so tests can make {@code
 *     receive_micros} reproducible
 */
public record ImportOptions(
    SourcePreservation preservation,
    long checkpointEveryRecords,
    long progressEveryRecords,
    long progressIntervalMillis,
    int batchSize,
    int readBufferBytes,
    int journalBufferBytes,
    int maxRecordBytes,
    long segmentBytes,
    int maxDiagnosticsPerCode,
    boolean verifyDigestOnResume,
    MicrosClock clock) {

  /** Source of epoch-microsecond timestamps. */
  @FunctionalInterface
  public interface MicrosClock {
    long nowMicros();

    static MicrosClock system() {
      Clock clock = Clock.systemUTC();
      return () -> {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
      };
    }
  }

  /**
   * Records between checkpoints. Small enough that a crash replays a trivial backlog, large enough
   * that the fsync it implies is amortized over thousands of events.
   */
  public static final long DEFAULT_CHECKPOINT_EVERY_RECORDS = 10_000;

  public static final long DEFAULT_PROGRESS_EVERY_RECORDS = 250;

  /** Ten samples a second is more than a progress bar can usefully show. */
  public static final long DEFAULT_PROGRESS_INTERVAL_MILLIS = 100;

  public static final int DEFAULT_MAX_DIAGNOSTICS_PER_CODE = 1_000;

  public ImportOptions {
    Objects.requireNonNull(preservation, "preservation");
    Objects.requireNonNull(clock, "clock");
    if (checkpointEveryRecords < 1) {
      throw new IllegalArgumentException(
          "checkpointEveryRecords must be >= 1, got " + checkpointEveryRecords);
    }
    if (progressEveryRecords < 1) {
      throw new IllegalArgumentException(
          "progressEveryRecords must be >= 1, got " + progressEveryRecords);
    }
    if (progressIntervalMillis < 0) {
      throw new IllegalArgumentException(
          "progressIntervalMillis must be >= 0, got " + progressIntervalMillis);
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
    }
    if (readBufferBytes < JsonBepParserOptions.MIN_READ_BUFFER_BYTES) {
      throw new IllegalArgumentException(
          "readBufferBytes must be >= "
              + JsonBepParserOptions.MIN_READ_BUFFER_BYTES
              + ", got "
              + readBufferBytes);
    }
    if (maxRecordBytes < 1) {
      throw new IllegalArgumentException("maxRecordBytes must be >= 1, got " + maxRecordBytes);
    }
    if (maxDiagnosticsPerCode < 1) {
      throw new IllegalArgumentException(
          "maxDiagnosticsPerCode must be >= 1, got " + maxDiagnosticsPerCode);
    }
    // Validated by constructing the config the journal will actually use, so
    // an illegal combination fails here rather than half way through a file.
    new JournalWriterConfig(segmentBytes, maxRecordBytes, journalBufferBytes);
  }

  public static ImportOptions defaults() {
    return new ImportOptions(
        SourcePreservation.COPY_INTO_SESSION,
        DEFAULT_CHECKPOINT_EVERY_RECORDS,
        DEFAULT_PROGRESS_EVERY_RECORDS,
        DEFAULT_PROGRESS_INTERVAL_MILLIS,
        EventWriter.DEFAULT_BATCH_SIZE,
        BinaryBepParser.DEFAULT_BUFFER_BYTES,
        JournalWriterConfig.DEFAULT_BUFFER_BYTES,
        JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES,
        JournalFormat.DEFAULT_SEGMENT_BYTES,
        DEFAULT_MAX_DIAGNOSTICS_PER_CODE,
        true,
        MicrosClock.system());
  }

  public ImportOptions withPreservation(SourcePreservation value) {
    return new ImportOptions(
        value,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withCheckpointEveryRecords(long value) {
    return new ImportOptions(
        preservation,
        value,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withProgressEveryRecords(long value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        value,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withProgressIntervalMillis(long value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        value,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withBatchSize(int value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        value,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withReadBufferBytes(int value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        value,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withJournalBufferBytes(int value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        value,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withMaxRecordBytes(int value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        value,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withSegmentBytes(long value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        value,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withMaxDiagnosticsPerCode(int value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        value,
        verifyDigestOnResume,
        clock);
  }

  public ImportOptions withVerifyDigestOnResume(boolean value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        value,
        clock);
  }

  public ImportOptions withClock(MicrosClock value) {
    return new ImportOptions(
        preservation,
        checkpointEveryRecords,
        progressEveryRecords,
        progressIntervalMillis,
        batchSize,
        readBufferBytes,
        journalBufferBytes,
        maxRecordBytes,
        segmentBytes,
        maxDiagnosticsPerCode,
        verifyDigestOnResume,
        value);
  }

  JournalWriterConfig journalWriterConfig() {
    return new JournalWriterConfig(segmentBytes, maxRecordBytes, journalBufferBytes);
  }

  JsonBepParserOptions jsonParserOptions() {
    return new JsonBepParserOptions(maxRecordBytes, readBufferBytes, true);
  }

  BinaryBepParser binaryParser() {
    return new BinaryBepParser(maxRecordBytes, readBufferBytes);
  }
}

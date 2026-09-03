package com.holtherndon.bazelviz.capture.file.importer;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.bepcodec.entity.EntityTranslator;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParseResult;
import com.holtherndon.bazelviz.capture.file.detect.DetectedFormat;
import com.holtherndon.bazelviz.capture.file.detect.FormatDetection;
import com.holtherndon.bazelviz.capture.file.detect.FormatDetector;
import com.holtherndon.bazelviz.capture.file.json.JsonBepListener;
import com.holtherndon.bazelviz.capture.file.json.JsonBepParseResult;
import com.holtherndon.bazelviz.capture.file.json.JsonBepParser;
import com.holtherndon.bazelviz.capture.file.json.JsonBepRecord;
import com.holtherndon.bazelviz.capture.file.json.JsonParseDiagnostic;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.journal.ImportCheckpoint;
import com.holtherndon.bazelviz.format.journal.ImportCheckpointStore;
import com.holtherndon.bazelviz.format.journal.JournalFrame;
import com.holtherndon.bazelviz.format.journal.JournalLocation;
import com.holtherndon.bazelviz.format.journal.JournalPosition;
import com.holtherndon.bazelviz.format.journal.JournalReader;
import com.holtherndon.bazelviz.format.journal.JournalReaderConfig;
import com.holtherndon.bazelviz.format.journal.JournalRecovery;
import com.holtherndon.bazelviz.format.journal.JournalScanStatus;
import com.holtherndon.bazelviz.format.journal.JournalSegments;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.format.journal.RecoveryReport;
import com.holtherndon.bazelviz.format.journal.SegmentScan;
import com.holtherndon.bazelviz.format.session.ManagedSession;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.DiagnosticCodes;
import com.holtherndon.bazelviz.storage.events.DiagnosticSeverity;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.events.StringDictionary;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.storage.schema.SchemaV2;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports a BEP capture file into a managed session.
 *
 * <p>This is the component the Phase 1 exit criteria are about, so the order of operations below is
 * the contract, not an implementation detail:
 *
 * <ol>
 *   <li><b>Detect by content.</b> {@link FormatDetector} decides what the file is from its bytes,
 *       never its name (plan 5.2). {@link DetectedFormat#UNKNOWN} is refused with the detector's
 *       own reason attached, before a session directory exists.
 *   <li><b>Preserve the source.</b> Path, size and SHA-256 are recorded in the manifest and in
 *       {@code capture_sources} before anything is parsed, and — by default — the file is copied
 *       into {@code raw/imported-source.bep} and the copy is what gets indexed. See {@link
 *       SourcePreservation} for the alternative and for what each mode costs.
 *   <li><b>Journal first, then normalize</b> (ADR-004). Every record's payload bytes go to the
 *       {@link JournalWriter} verbatim before anything decodes them, and the {@link
 *       JournalLocation} that comes back is what {@code
 *       bep_events.raw_segment/raw_offset/raw_length} store. The event rows therefore point at the
 *       journal, not at the source file, so the session stays whole even if the source is deleted.
 *   <li><b>Normalize.</b> Decode, derive the id key and display, the payload case and the announced
 *       children, and write through {@link EventWriter}. A failed decode still lands a row with its
 *       raw location, because those bytes remain re-interpretable by a later build (plan 21.5).
 *   <li><b>Checkpoint.</b> Every {@link ImportOptions#checkpointEveryRecords()} records the event
 *       batch is flushed, the journal forced, and both halves of the resume point rewritten
 *       atomically.
 *   <li><b>Finalize.</b> Build the indexes after the load, resolve announced children, {@code
 *       ANALYZE}, write counts and completeness into the manifest, and transition to a terminal
 *       state that matches what actually happened.
 * </ol>
 *
 * <h2>Resuming</h2>
 *
 * <p>{@link #resume} does <em>not</em> re-read the source from the beginning. It replays the
 * journal from the checkpointed position — re-normalizing only the frames the journal holds past
 * the last recorded one — and then continues reading the source at the recorded byte offset.
 * Re-normalizing a frame is safe because {@code UNIQUE (stream_id, sequence)} makes the insert a
 * no-op and because the ordinal and raw location come from the frame itself rather than from a
 * counter, so a replayed row is byte-for-byte the row the first pass wrote. {@code
 * BepImporterResumeTest} asserts that a cancelled-then- resumed import ends in exactly the state an
 * uninterrupted one does.
 *
 * <h2>Memory</h2>
 *
 * <p>Nothing here allocates in proportion to the source. The footprint is the source read window,
 * the journal staging buffer and one payload scratch array sized to the largest single record — all
 * three configured, all three reported as {@link ImportResult#peakBufferBytes()} so a test can
 * check the bound rather than trust it.
 *
 * <h2>Threading</h2>
 *
 * <p>One import per instance-call, on one thread, and never the Swing EDT: every method here does
 * file I/O and SQL. Progress reaches a UI through {@link ImportProgressListener}, which this module
 * deliberately defines without any Swing types.
 */
public final class BepImporter {

  private static final Logger log = LoggerFactory.getLogger(BepImporter.class);

  /**
   * Entity commands held before the batch is forced out.
   *
   * <p>Sized in commands, not in events, because one event's commands range from none to one per
   * file in a named set. Twenty thousand small records is a few megabytes; the same count of events
   * could be anything.
   */
  static final int MAX_PENDING_ENTITY_COMMANDS = 20_000;

  /**
   * Name of the preserved copy inside {@code raw/}, fixed by plan 10.2. It keeps the {@code .bep}
   * suffix for a JSON source too: the name is a slot in the session layout, and the source's real
   * format is recorded in {@code capture_sources.kind} rather than guessed from a file name — which
   * is the same rule the detector follows.
   */
  public static final String IMPORTED_SOURCE_NAME = "imported-source.bep";

  /** Never cancels. */
  private static final BooleanSupplier NEVER_CANCELLED = () -> false;

  private final SessionManager sessions;
  private final ImportOptions options;

  public BepImporter(SessionManager sessions) {
    this(sessions, ImportOptions.defaults());
  }

  public BepImporter(SessionManager sessions, ImportOptions options) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.options = Objects.requireNonNull(options, "options");
  }

  public ImportOptions options() {
    return options;
  }

  // ------------------------------------------------------------------ import

  /** Imports {@code source} into a new session with a random identity. */
  public ImportResult importFile(Path source) throws IOException {
    return importFile(source, SessionId.random(), ImportProgressListener.NONE, NEVER_CANCELLED);
  }

  /**
   * Imports {@code source} into a new managed session.
   *
   * @param sessionId identity of the session to create
   * @param listener throttled progress sink; {@link ImportProgressListener#NONE} to ignore
   * @param cancelRequested polled between records; when it turns true the import stops on a record
   *     boundary, leaving a resumable checkpoint and a session that is honestly marked as
   *     unfinished rather than corrupt
   * @throws UnsupportedSourceException if the content is not a BEP file this build can read.
   *     Nothing is created on disk in that case
   */
  public ImportResult importFile(
      Path source,
      SessionId sessionId,
      ImportProgressListener listener,
      BooleanSupplier cancelRequested)
      throws IOException {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(listener, "listener");
    Objects.requireNonNull(cancelRequested, "cancelRequested");

    long startedNanos = System.nanoTime();
    Path absoluteSource = source.toAbsolutePath().normalize();
    log.info("import {} started for {}", sessionId, absoluteSource.getFileName());
    log.debug("import {} source path is {}", sessionId, absoluteSource);
    listener.onProgress(new ImportProgress(ImportPhase.DETECTING, 0, 0, OptionalLong.empty()));
    FormatDetection detection = FormatDetector.withDefaults().detect(absoluteSource);
    DetectedFormat format = detection.format();
    log.debug(
        "import {} detected {} after inspecting {} byte(s): {}",
        sessionId,
        format,
        detection.bytesInspected(),
        detection.reason());
    if (format != DetectedFormat.BEP_BINARY && format != DetectedFormat.BEP_JSON) {
      // Including MANAGED_SESSION_DIR: a session directory is opened, not
      // imported, and guessing otherwise would create a session inside a
      // session. Never guessed at (plan 5.2).
      throw new UnsupportedSourceException(absoluteSource, detection);
    }

    ManagedSession session =
        sessions.create(
            sessionId,
            ManagedSessionLayout.CAPTURE_DIRECTORIES,
            builder ->
                builder.addSource(
                    SessionManifest.CaptureSourceEntry.pending(
                        format.name(), absoluteSource.toString())));
    ImportResult result;
    try (Run run = new Run(session, format, listener, cancelRequested)) {
      result = run.runFreshImport(absoluteSource);
    } catch (IOException | RuntimeException failure) {
      log.error("import {} failed after {} ms", sessionId, elapsedMillis(startedNanos), failure);
      closeQuietly(session);
      throw failure;
    }
    logCompletion(result, startedNanos);
    return result;
  }

  // ------------------------------------------------------------------ resume

  /** Resumes the interrupted import in {@code sessionRoot}. */
  public ImportResult resume(Path sessionRoot) throws IOException {
    return resume(sessionRoot, ImportProgressListener.NONE, NEVER_CANCELLED);
  }

  /**
   * Resumes an interrupted import.
   *
   * <p>The session is reopened through {@link SessionManager#recover(Path,
   * SessionManager.RecoveryDecision)} with {@code RESUME}, which breaks a stale lock and records
   * permanently in the manifest that this session was interrupted. The journal is recovered to its
   * last intact frame first, so nothing is appended after a torn tail.
   *
   * @throws IllegalStateException if the session has already reached a terminal state; a finished
   *     import has nothing to continue
   * @throws ImportFormatException if the resume point is missing or unreadable, or the preserved
   *     source no longer matches its digest
   */
  public ImportResult resume(
      Path sessionRoot, ImportProgressListener listener, BooleanSupplier cancelRequested)
      throws IOException {
    Objects.requireNonNull(sessionRoot, "sessionRoot");
    Objects.requireNonNull(listener, "listener");
    Objects.requireNonNull(cancelRequested, "cancelRequested");

    long startedNanos = System.nanoTime();
    SessionManifest existing = sessions.readManifest(sessionRoot);
    log.info("import {} resume started from state {}", existing.sessionId(), existing.state());
    log.debug("import {} resume root is {}", existing.sessionId(), sessionRoot);
    if (existing.state().isTerminal()) {
      throw new IllegalStateException(
          "session "
              + existing.sessionId()
              + " at "
              + sessionRoot
              + " is already finished in state "
              + existing.state()
              + "; there is nothing to resume");
    }

    SourceCheckpoint sourceCheckpoint =
        new SourceCheckpointStore(ManagedSessionLayout.at(sessionRoot).checkpointsDirectory())
            .read()
            .orElseThrow(
                () ->
                    new ImportFormatException(
                        "session "
                            + sessionRoot
                            + " has no import resume point ("
                            + SourceCheckpointStore.FILE_NAME
                            + "); it was interrupted before the first checkpoint and must"
                            + " be imported again"));

    SessionManager.Recovered recovered =
        sessions.recover(sessionRoot, SessionManager.RecoveryDecision.RESUME);
    ManagedSession session =
        recovered
            .session()
            .orElseThrow(
                () -> new IllegalStateException("recovery declined to reopen " + sessionRoot));
    ImportResult result;
    try (Run run = new Run(session, sourceCheckpoint.format(), listener, cancelRequested)) {
      result = run.runResume(sourceCheckpoint);
    } catch (IOException | RuntimeException failure) {
      log.error(
          "import {} resume failed after {} ms",
          existing.sessionId(),
          elapsedMillis(startedNanos),
          failure);
      closeQuietly(session);
      throw failure;
    }
    logCompletion(result, startedNanos);
    return result;
  }

  /**
   * Gives up on an interrupted import, walking the session to {@link SessionState#INCOMPLETE} so
   * the data already on disk stays inspectable and is honestly labelled as partial.
   *
   * <p>This is a one-way door: {@code INCOMPLETE} is terminal, so the session can no longer be
   * resumed. That is why cancellation alone does not do it.
   */
  public void abandon(Path sessionRoot) throws IOException {
    Objects.requireNonNull(sessionRoot, "sessionRoot");
    sessions.recover(sessionRoot, SessionManager.RecoveryDecision.MARK_INCOMPLETE);
  }

  private static void closeQuietly(ManagedSession session) {
    try {
      session.close();
    } catch (IOException e) {
      log.warn("failed to release the lock on {}", session.root(), e);
    }
  }

  private static void logCompletion(ImportResult result, long startedNanos) {
    log.info(
        "import {} finished: outcome={}, state={}, journaled={}, normalized={},"
            + " stored={}, diagnostics={}, elapsed={} ms",
        result.sessionId(),
        result.outcome(),
        result.sessionState(),
        result.recordsJournaled(),
        result.eventsNormalized(),
        result.eventsInDatabase(),
        result.diagnosticsRecorded(),
        elapsedMillis(startedNanos));
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  // ================================================================== the run

  /**
   * One import run. Everything mutable lives here rather than on the importer, so a {@link
   * BepImporter} can be shared and a run cannot leak state into the next one.
   */
  private final class Run implements AutoCloseable {

    private final ManagedSession session;
    private final ManagedSessionLayout layout;
    private final DetectedFormat format;
    private final ImportProgressListener listener;
    private final BooleanSupplier cancelRequested;
    private final EventNormalizer normalizer = new EventNormalizer(options.maxRecordBytes());
    private final ImportCheckpointStore journalCheckpoints;
    private final SourceCheckpointStore sourceCheckpoints;

    private SessionDatabase database;
    private EventWriter events;
    private EntityWriter entities;

    /**
     * Entity commands waiting for their {@code bep_events} rows.
     *
     * <p>{@link EventWriter} batches, so the event row for a sequence does not exist until the
     * batch is executed — and every entity row resolves its provenance by looking that row up. So
     * the commands are held until {@link #flushEvents()} has put the events in, and only then
     * applied.
     *
     * <p>Bounded by {@link #MAX_PENDING_ENTITY_COMMANDS} rather than by the event batch size. Those
     * are not the same bound: one event can carry thousands of commands — a {@code NamedSetOfFiles}
     * holds a file list — so a buffer sized in events is a buffer of unbounded bytes. Plan rule 9
     * requires the import path's memory to be bounded by configuration rather than by the size of
     * the input.
     */
    private final List<PendingEntities> pendingEntities = new ArrayList<>();

    /** Commands currently held in {@link #pendingEntities}. */
    private int pendingEntityCommands;

    private final EntityTranslator translator = new EntityTranslator();

    /** One event's worth of entity commands, and the sequence they came from. */
    private record PendingEntities(long sequence, List<EntityCommand> commands) {}

    private StreamRegistry streams;
    private JournalWriter journal;

    private PreservedSource preserved;
    private long captureSourceId = -1;
    private long streamId = -1;

    /** Next import ordinal, which is the {@code sequence} of the next event. */
    private long nextOrdinal;

    /** Frames already in the journal when this run started appending. */
    private long framesAtStart;

    private long recordsJournaled;
    private long eventsNormalized;
    private long recordsSkipped;
    private long sourceOffset;
    private long bytesRead;
    private long totalBytes;
    private long peakScratchBytes;
    private long peakParserBytes;
    private byte[] scratch = new byte[0];

    private long recordsSinceCheckpoint;
    private long recordsSinceProgress;
    private long lastProgressNanos;
    private ImportPhase phase = ImportPhase.DETECTING;

    private final Map<String, long[]> diagnosticCounts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private Optional<String> invocationId = Optional.empty();
    private boolean resumed;
    private OptionalLong damageOffset = OptionalLong.empty();

    /** Source offset of the first record whose bytes would not decode. */
    private OptionalLong firstDecodeFailureOffset = OptionalLong.empty();

    /** Where the record currently being stored began in the source; -1 during replay. */
    private long currentRecordSourceOffset = -1;

    Run(
        ManagedSession session,
        DetectedFormat format,
        ImportProgressListener listener,
        BooleanSupplier cancelRequested) {
      this.session = session;
      this.layout = session.layout();
      this.format = format;
      this.listener = listener;
      this.cancelRequested = cancelRequested;
      this.journalCheckpoints = new ImportCheckpointStore(layout.checkpointsDirectory());
      this.sourceCheckpoints = new SourceCheckpointStore(layout.checkpointsDirectory());
    }

    // ------------------------------------------------------------ fresh run

    ImportResult runFreshImport(Path source) throws IOException {
      session.transitionTo(SessionState.PREFLIGHT);

      beginPhase(ImportPhase.PRESERVING);
      emitProgress(true);
      preserved = preserve(source);
      totalBytes = preserved.byteSize();
      recordManifestSource(Completeness.UNKNOWN);

      openDatabase();
      try {
        captureSourceId =
            SessionTables.insertCaptureSource(
                writerConnection(),
                format.name(),
                preserved.originalPath().toString(),
                preserved.sha256(),
                OptionalLong.of(preserved.byteSize()),
                Completeness.UNKNOWN,
                preservationNote());
      } catch (SQLException e) {
        throw new IOException("failed to record the capture source", e);
      }
      writeSessionInfo(SessionState.PREFLIGHT, OptionalLong.empty());

      openEventWriter();
      session.transitionTo(SessionState.CAPTURING);
      journal =
          JournalWriter.create(
              layout.rawDirectory(), session.id().value(), options.journalWriterConfig());

      beginPhase(ImportPhase.READING);
      ImportOutcome outcome = readSource(0, 0);
      return finish(outcome);
    }

    // ----------------------------------------------------------- resumed run

    ImportResult runResume(SourceCheckpoint sourceCheckpoint) throws IOException {
      resumed = true;
      preserved =
          new PreservedSource(
              sourceCheckpoint.preservation(),
              Path.of(sourceCheckpoint.originalPath()),
              sourceCheckpoint.preservation() == SourcePreservation.COPY_INTO_SESSION
                  ? Optional.of(layout.rawDirectory().resolve(IMPORTED_SOURCE_NAME))
                  : Optional.empty(),
              sourceCheckpoint.sha256(),
              sourceCheckpoint.byteSize(),
              0);
      totalBytes = preserved.byteSize();

      openDatabase();
      try {
        captureSourceId = SessionTables.findCaptureSource(writerConnection()).orElse(-1);
      } catch (SQLException e) {
        throw new IOException("failed to read the capture source record", e);
      }
      // The writer is opened before the digest check so that a refusal can
      // still record why it refused: a session that cannot be resumed must
      // say so in its own diagnostics, not only in an exception message.
      openEventWriter();
      verifyPreservedSource();

      // Plan 21.1 steps 2 and 3: scan to the last valid checksum and
      // truncate only invalid trailing bytes, before anything is appended.
      RecoveryReport recovery = JournalRecovery.recover(layout.rawDirectory());
      recordJournalRecovery(recovery);

      Optional<ImportCheckpoint> checkpoint = journalCheckpoints.read();
      beginPhase(ImportPhase.REPLAYING_JOURNAL);
      emitProgress(true);
      long journalFrames = replayJournal(checkpoint);
      framesAtStart = journalFrames;

      // The journal is the authority on frame identity (ADR-004). It is
      // forced before the checkpoint is written, so a crash can leave the
      // database holding committed rows whose frames were still in the
      // writer's staging buffer. Those rows name a raw location that no
      // longer exists — unreadable, and worse, they would push the next
      // ordinal past the true frame count while the source skip is
      // counted from the journal, so every later record would land under
      // a shifted sequence. Make the database agree with the journal
      // before appending anything, and say so.
      try {
        long dropped =
            SessionTables.deleteEventsFromSequence(writerConnection(), streamId, journalFrames);
        if (dropped > 0) {
          recordDiagnostic(
              DiagnosticSeverity.WARNING,
              ImportDiagnosticCodes.IMPORT_RESUMED,
              dropped
                  + " stored event(s) referenced journal frames that did not"
                  + " survive the interruption and were discarded; the journal"
                  + " holds "
                  + journalFrames
                  + " frame(s) and they are being"
                  + " re-read from the source",
              OptionalLong.empty());
        }
      } catch (SQLException e) {
        throw new IOException("cannot reconcile the database with the journal", e);
      }
      nextOrdinal = journalFrames;

      journal =
          JournalWriter.resume(
              layout.rawDirectory(), session.id().value(), options.journalWriterConfig());

      long startOffset;
      long skip;
      if (journalFrames >= sourceCheckpoint.framesWritten()) {
        startOffset = sourceCheckpoint.sourceOffset();
        skip = journalFrames - sourceCheckpoint.framesWritten();
      } else {
        // The sidecar claims more frames than the journal holds, which the
        // write order makes impossible unless the journal lost a tail to
        // recovery. Re-read the source from the start and skip what is
        // already journaled: slower, but it cannot lose a record.
        startOffset = 0;
        skip = journalFrames;
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            ImportDiagnosticCodes.IMPORT_RESUMED,
            "the resume point named "
                + sourceCheckpoint.framesWritten()
                + " journaled record(s) but the journal holds "
                + journalFrames
                + " after recovery; the source is being re-read from the beginning"
                + " and the first "
                + journalFrames
                + " record(s) skipped",
            OptionalLong.empty());
      }
      bytesRead = startOffset;
      sourceOffset = startOffset;
      recordDiagnostic(
          DiagnosticSeverity.INFO,
          ImportDiagnosticCodes.IMPORT_RESUMED,
          "resumed at source byte offset "
              + startOffset
              + " with "
              + journalFrames
              + " record(s) already journaled",
          OptionalLong.of(startOffset));
      warnings.add("import resumed at source byte offset " + startOffset);

      beginPhase(ImportPhase.READING);
      ImportOutcome outcome = readSource(startOffset, skip);
      return finish(outcome);
    }

    // ------------------------------------------------------------- preserve

    private PreservedSource preserve(Path source) throws IOException {
      long lastModified = Files.getLastModifiedTime(source).toMillis();
      return switch (options.preservation()) {
        case COPY_INTO_SESSION -> {
          Path target = layout.rawDirectory().resolve(IMPORTED_SOURCE_NAME);
          SourceDigest.Result result = SourceDigest.copy(source, target);
          yield new PreservedSource(
              SourcePreservation.COPY_INTO_SESSION,
              source,
              Optional.of(target),
              result.sha256(),
              result.byteSize(),
              lastModified);
        }
        case REFERENCE_ORIGINAL -> {
          SourceDigest.Result result = SourceDigest.hash(source);
          yield new PreservedSource(
              SourcePreservation.REFERENCE_ORIGINAL,
              source,
              Optional.empty(),
              result.sha256(),
              result.byteSize(),
              lastModified);
        }
      };
    }

    private Optional<String> preservationNote() {
      return switch (preserved.preservation()) {
        case COPY_INTO_SESSION ->
            Optional.of(
                "copied into raw/"
                    + IMPORTED_SOURCE_NAME
                    + " before parsing; the session"
                    + " does not depend on the original file");
        case REFERENCE_ORIGINAL ->
            Optional.of(
                "indexed in place; the session holds every payload in its journal but"
                    + " cannot re-verify this digest if the original moves or changes");
      };
    }

    /**
     * A resume must be continuing over the same bytes it started on. If the preserved source is
     * gone or no longer hashes to what was recorded, appending more frames to the same journal
     * would splice two different files into one session, so this refuses rather than continues.
     */
    private void verifyPreservedSource() throws IOException {
      Path effective = preserved.effectivePath();
      if (!Files.isRegularFile(effective)) {
        recordDiagnostic(
            DiagnosticSeverity.ERROR,
            ImportDiagnosticCodes.SOURCE_DIGEST_MISMATCH,
            "the preserved source "
                + effective
                + " is missing, so the import cannot"
                + " continue over the same bytes it started on",
            OptionalLong.empty());
        flushEvents();
        throw new ImportFormatException(
            "cannot resume " + layout.root() + ": its source " + effective + " no longer exists");
      }
      if (!options.verifyDigestOnResume()) {
        return;
      }
      SourceDigest.Result actual = SourceDigest.hash(effective);
      if (!actual.sha256().equals(preserved.sha256())) {
        recordDiagnostic(
            DiagnosticSeverity.ERROR,
            ImportDiagnosticCodes.SOURCE_DIGEST_MISMATCH,
            "the preserved source "
                + effective
                + " now hashes to "
                + actual.sha256()
                + " but the import began on "
                + preserved.sha256()
                + "; resuming would mix two different files into one session",
            OptionalLong.empty());
        flushEvents();
        throw new ImportFormatException(
            "cannot resume "
                + layout.root()
                + ": "
                + effective
                + " changed since the import started (recorded "
                + preserved.sha256()
                + ", found "
                + actual.sha256()
                + ")");
      }
    }

    // -------------------------------------------------------------- database

    /**
     * Opens the session database and brings it to the current schema.
     *
     * <p>Both callers migrate, including resume. That is not an oversight corrected — it is the
     * correction: resume used to only check compatibility, which rejects a database newer than this
     * build and applies nothing to an older one. A session interrupted under a build that predates
     * a schema version would then be replayed into tables it does not have, and the first write
     * would fail with "no such table" on a session the UI had just offered to resume.
     *
     * <p>Migration is forward-only and transactional, so applying it to a session that is already
     * current does nothing at all.
     */
    private void openDatabase() throws IOException {
      try {
        database = SessionDatabase.open(layout.databaseFile());
        MigrationRunner.standard().migrate(database);
      } catch (SQLException e) {
        throw new IOException("cannot open the session database at " + layout.databaseFile(), e);
      }
    }

    private void openEventWriter() throws IOException {
      try {
        events =
            new EventWriter(
                database.writerConnection(),
                options.batchSize(),
                StringDictionary.DEFAULT_CACHE_ENTRIES);
        entities = new EntityWriter(database.writerConnection());
        streams = new StreamRegistry(database.writerConnection());
        streamId = streams.open(streamKey());
        nextOrdinal =
            SessionTables.maxSequence(database.writerConnection(), streamId).stream()
                .map(last -> last + 1)
                .findFirst()
                .orElse(0L);
      } catch (SQLException e) {
        throw new IOException("cannot prepare the ingestion path", e);
      }
    }

    /**
     * The stream key. Derived from the source digest so that importing the same file twice produces
     * the same key — which is what makes two independent imports comparable row for row (exit
     * criterion "event counts and offsets are reproducible").
     */
    private String streamKey() {
      return "file:" + preserved.sha256();
    }

    private Connection writerConnection() {
      return database.writerConnection();
    }

    // ---------------------------------------------------------- journal replay

    /**
     * Re-normalizes the frames the journal holds past the last checkpoint and returns the total
     * number of frames in the journal.
     *
     * <p>Replay reads the ordinal and the raw location out of each frame rather than recomputing
     * them, so a replayed row is identical to the one the interrupted run wrote and {@code UNIQUE
     * (stream_id, sequence)} turns the repeat into a no-op. That identity is the whole reason a
     * resume can be asserted equal to an uninterrupted import.
     */
    private long replayJournal(Optional<ImportCheckpoint> checkpoint) throws IOException {
      JournalPosition start =
          checkpoint
              .map(ImportCheckpoint::position)
              .orElseGet(() -> JournalPosition.startOfSegment(0));
      long framesBefore = checkpoint.map(ImportCheckpoint::framesWritten).orElse(0L);

      List<Integer> segments = JournalSegments.listSegmentIndexes(layout.rawDirectory());
      if (segments.isEmpty()) {
        return 0;
      }
      if (start.segmentIndex() > segments.get(segments.size() - 1) || !segmentCanStartAt(start)) {
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            ImportDiagnosticCodes.IMPORT_RESUMED,
            "the journal checkpoint points at segment "
                + start.segmentIndex()
                + " offset "
                + start.byteOffset()
                + ", which is not inside the journal on disk; every frame is being"
                + " replayed instead",
            OptionalLong.of(start.byteOffset()));
        start = JournalPosition.startOfSegment(segments.get(0));
        framesBefore = 0;
      }

      long replayed = 0;
      JournalReaderConfig readerConfig =
          new JournalReaderConfig(
              options.maxRecordBytes(), JournalReaderConfig.DEFAULT_BUFFER_BYTES, true);
      for (int segmentIndex : segments) {
        if (segmentIndex < start.segmentIndex()) {
          continue;
        }
        long offset =
            segmentIndex == start.segmentIndex()
                ? start.byteOffset()
                : JournalFormat.SEGMENT_HEADER_BYTES;
        Path segmentFile = JournalSegments.segmentFile(layout.rawDirectory(), segmentIndex);
        try (JournalReader reader = JournalReader.open(segmentFile, offset, readerConfig)) {
          JournalFrame frame;
          while ((frame = reader.next()) != null) {
            replayFrame(frame);
            replayed++;
          }
          SegmentScan scan = reader.result();
          if (!scan.status().isClean()) {
            // JournalRecovery already truncated what it could; anything
            // left is a frame this build cannot interpret, which must be
            // reported and must never be silently skipped.
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                scan.status().diagnosticCode(),
                "journal segment "
                    + segmentIndex
                    + " stops at offset "
                    + scan.endOffset()
                    + ": "
                    + scan.detail(),
                OptionalLong.of(scan.endOffset()),
                OptionalLong.of(segmentIndex));
          }
        }
      }
      flushEvents();
      // eventsNormalized is not adjusted here: store() already counted each
      // replayed frame as it went, and adding the total again would report
      // twice the work that was done.
      long total = framesBefore + replayed;
      if (replayed > 0) {
        log.info(
            "replayed {} journal frame(s) from {} while resuming {}",
            replayed,
            start,
            layout.root());
      }
      return total;
    }

    private boolean segmentCanStartAt(JournalPosition start) {
      Path file = JournalSegments.segmentFile(layout.rawDirectory(), start.segmentIndex());
      try {
        return Files.isRegularFile(file)
            && start.byteOffset() >= JournalFormat.SEGMENT_HEADER_BYTES
            && start.byteOffset() <= Files.size(file);
      } catch (IOException e) {
        return false;
      }
    }

    private void replayFrame(JournalFrame frame) throws IOException {
      // Replay has no source offset to attribute a failure to: these bytes
      // are being re-read from the journal, not from the file.
      currentRecordSourceOffset = -1;
      byte[] payload = frame.requirePayload();
      EventNormalizer.Normalization normalization =
          normalizer.normalize(
              frame.header().sourceKind(),
              payload,
              0,
              payload.length,
              streamId,
              frame.header().sequence(),
              frame.location(),
              frame.header().receiveMicros());
      store(normalization, frame.location());
      nextOrdinal = Math.max(nextOrdinal, frame.header().sequence() + 1);
    }

    // ---------------------------------------------------------------- reading

    private ImportOutcome readSource(long startOffset, long skipRecords) throws IOException {
      Path effective = preserved.effectivePath();
      emitProgress(true);
      ImportOutcome outcome =
          format == DetectedFormat.BEP_BINARY
              ? readBinary(effective, startOffset, skipRecords)
              : readJson(effective, startOffset, skipRecords);
      if (recordsSkipped > 0) {
        // Re-read but not re-journaled: these records were already in the
        // journal when this run started, and appending them again would
        // change every offset after the seam.
        log.info(
            "skipped {} record(s) already journaled before resuming {}",
            recordsSkipped,
            layout.root());
      }
      checkReferenceSourceUnchanged();
      return outcome;
    }

    private ImportOutcome readBinary(Path file, long startOffset, long skipRecords)
        throws IOException {
      long[] skip = {skipRecords};
      BinaryBepParseResult result =
          options
              .binaryParser()
              .parseFile(
                  file,
                  startOffset,
                  frame -> {
                    if (skip[0] > 0) {
                      skip[0]--;
                      recordsSkipped++;
                      advance(frame.endOffset());
                      return;
                    }
                    int length = frame.payloadLength();
                    byte[] buffer = scratch(length);
                    ByteBuffer view = frame.payload().duplicate();
                    view.get(buffer, 0, length);
                    handleRecord(
                        SourceKind.BEP_BINARY,
                        buffer,
                        0,
                        length,
                        null,
                        null,
                        null,
                        frame.frameOffset(),
                        frame.endOffset());
                  },
                  cancelRequested);
      peakParserBytes = Math.max(peakParserBytes, result.peakBufferBytes());
      return switch (result.outcome()) {
        case COMPLETE -> ImportOutcome.COMPLETE;
        case CANCELLED -> ImportOutcome.CANCELLED;
        case TRUNCATED -> {
          damageOffset = OptionalLong.of(result.resumeOffset());
          recordDiagnostic(
              DiagnosticSeverity.WARNING,
              ImportDiagnosticCodes.SOURCE_TRUNCATED,
              result.detail(),
              OptionalLong.of(result.resumeOffset()));
          warnings.add(
              "the source ends mid-record at byte offset "
                  + result.resumeOffset()
                  + "; every complete record before it was imported");
          yield ImportOutcome.TRUNCATED;
        }
        case CORRUPT -> {
          damageOffset = OptionalLong.of(result.resumeOffset());
          recordDiagnostic(
              DiagnosticSeverity.ERROR,
              ImportDiagnosticCodes.SOURCE_CORRUPT,
              result.detail(),
              OptionalLong.of(result.resumeOffset()));
          warnings.add(
              "the source is damaged at byte offset "
                  + result.resumeOffset()
                  + "; reading stopped there rather than guessing at the next record"
                  + " boundary");
          yield ImportOutcome.CORRUPT_PARTIAL;
        }
      };
    }

    private ImportOutcome readJson(Path file, long startOffset, long skipRecords)
        throws IOException {
      long[] skip = {skipRecords};
      JsonBepParser parser = new JsonBepParser(options.jsonParserOptions());
      JsonBepParseResult result;
      boolean cancelled = false;
      try (InputStream in = Files.newInputStream(file)) {
        in.skipNBytes(startOffset);
        result =
            parser.parse(
                in,
                new JsonBepListener() {
                  @Override
                  public void onRecord(JsonBepRecord record) throws IOException {
                    long endOffset = startOffset + record.byteOffset() + record.byteLength();
                    if (skip[0] > 0) {
                      skip[0]--;
                      recordsSkipped++;
                      advance(endOffset);
                      return;
                    }
                    byte[] raw = record.rawBytes();
                    handleRecord(
                        SourceKind.BEP_JSON_RECORD,
                        raw,
                        0,
                        raw.length,
                        record.decodeStatus(),
                        record.event(),
                        record.decodeMessage(),
                        startOffset + record.byteOffset(),
                        endOffset);
                    if (cancelRequested.getAsBoolean()) {
                      throw new CancelSignal();
                    }
                  }

                  @Override
                  public void onDiagnostic(JsonParseDiagnostic diagnostic) throws IOException {
                    recordJsonDiagnostic(diagnostic, startOffset);
                  }
                });
      } catch (CancelSignal cancel) {
        cancelled = true;
        result = null;
      }
      if (cancelled) {
        return ImportOutcome.CANCELLED;
      }
      peakParserBytes =
          Math.max(
              peakParserBytes, (long) result.readBufferBytes() + result.peakRecordBufferBytes());
      return switch (result.completeness()) {
        case COMPLETE -> ImportOutcome.COMPLETE;
        case TRUNCATED -> {
          long offset = startOffset + result.truncatedTailOffset().orElse(0);
          damageOffset = OptionalLong.of(offset);
          warnings.add(
              "the source ends inside the JSON object at byte offset "
                  + offset
                  + "; every complete record before it was imported");
          yield ImportOutcome.TRUNCATED;
        }
        case CORRUPT_PARTIAL -> {
          warnings.add(
              "the source contains a byte that cannot begin a JSON record;"
                  + " reading stopped there");
          yield ImportOutcome.CORRUPT_PARTIAL;
        }
        default -> ImportOutcome.COMPLETE;
      };
    }

    /** Signals cancellation out of the JSON parser, which has no cancel hook of its own. */
    private static final class CancelSignal extends IOException {
      private static final long serialVersionUID = 1L;

      CancelSignal() {
        super("import cancelled");
      }
    }

    // ------------------------------------------------------------ one record

    /**
     * Journal first, then normalize (ADR-004). The append happens before any decode, and its result
     * is what the event row points at, so the database always references bytes that are already
     * durable in the journal rather than bytes that only exist in a parser's buffer.
     */
    private void handleRecord(
        SourceKind kind,
        byte[] payload,
        int offset,
        int length,
        DecodeStatus preDecodedStatus,
        BuildEvent preDecodedEvent,
        String preDecodedDetail,
        long sourceStartOffset,
        long sourceEndOffset)
        throws IOException {
      long ordinal = nextOrdinal++;
      currentRecordSourceOffset = sourceStartOffset;
      long receiveMicros = options.clock().nowMicros();
      JournalLocation location =
          journal.append(kind, 0, ordinal, receiveMicros, payload, offset, length);
      recordsJournaled++;

      EventNormalizer.Normalization normalization =
          preDecodedStatus == null
              ? normalizer.normalize(
                  kind, payload, offset, length, streamId, ordinal, location, receiveMicros)
              : normalizer.fromDecoded(
                  preDecodedStatus,
                  preDecodedEvent,
                  preDecodedDetail,
                  streamId,
                  ordinal,
                  location,
                  receiveMicros);
      store(normalization, location);
      currentRecordSourceOffset = -1;

      advance(sourceEndOffset);
      maybeCheckpoint();
      emitProgress(false);
    }

    private void store(EventNormalizer.Normalization normalization, JournalLocation location)
        throws IOException {
      try {
        events.write(normalization.normalized());
      } catch (SQLException e) {
        throw new IOException(
            "failed to write event "
                + normalization.normalized().event().sequence()
                + " into the session database",
            e);
      }
      eventsNormalized++;
      normalization
          .event()
          .ifPresent(
              event -> {
                List<EntityCommand> commands = translator.translate(event);
                if (!commands.isEmpty()) {
                  pendingEntities.add(
                      new PendingEntities(normalization.normalized().event().sequence(), commands));
                  pendingEntityCommands += commands.size();
                }
              });
      if (pendingEntityCommands >= MAX_PENDING_ENTITY_COMMANDS) {
        // The events have to go in first, or the provenance lookups
        // find nothing. Flushing early costs a commit; not flushing
        // costs whatever the largest file set in the build weighs.
        flushEvents();
      }
      if (invocationId.isEmpty() && normalization.invocationId().isPresent()) {
        invocationId = normalization.invocationId();
      }
      switch (normalization.status()) {
        case FAILED -> {
          if (firstDecodeFailureOffset.isEmpty() && currentRecordSourceOffset >= 0) {
            firstDecodeFailureOffset = OptionalLong.of(currentRecordSourceOffset);
          }
          recordDiagnostic(
              DiagnosticSeverity.ERROR,
              DiagnosticCodes.DECODE_FAILED,
              "record "
                  + normalization.normalized().event().sequence()
                  + " could not be decoded ("
                  + normalization.failureDetail()
                  + "); its raw bytes are preserved in journal segment "
                  + location.segmentIndex()
                  + " at offset "
                  + location.frameOffset()
                  + " so a later version can reinterpret it",
              OptionalLong.of(location.frameOffset()),
              OptionalLong.of(location.segmentIndex()));
        }
        case UNKNOWN_FIELDS ->
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                DiagnosticCodes.UNKNOWN_FIELDS,
                "record "
                    + normalization.normalized().event().sequence()
                    + " carries fields this build's protos do not define; the event was"
                    + " kept without them and its raw bytes are preserved in full",
                OptionalLong.of(location.frameOffset()),
                OptionalLong.of(location.segmentIndex()));
        default -> {
          /* OK needs no diagnostic. */
        }
      }
    }

    private void advance(long sourceEndOffset) {
      sourceOffset = sourceEndOffset;
      bytesRead = sourceEndOffset;
    }

    private byte[] scratch(int length) {
      if (scratch.length < length) {
        int grown =
            Math.max(
                length, Math.min(Math.max(scratch.length * 2, 8192), options.maxRecordBytes()));
        scratch = new byte[Math.max(grown, length)];
        peakScratchBytes = Math.max(peakScratchBytes, scratch.length);
      }
      return scratch;
    }

    // ----------------------------------------------------------- checkpoints

    private void maybeCheckpoint() throws IOException {
      if (++recordsSinceCheckpoint < options.checkpointEveryRecords()) {
        return;
      }
      checkpoint();
    }

    /**
     * Writes both halves of the resume point, in the only safe order: everything already normalized
     * is committed, the journal is forced, the journal checkpoint is replaced, and only then is the
     * source offset recorded. The source sidecar can therefore lag the journal but never lead it —
     * see {@link SourceCheckpoint} for why that matters.
     */
    private void checkpoint() throws IOException {
      recordsSinceCheckpoint = 0;
      flushEvents();
      journal.force();
      long totalFrames = framesAtStart + journal.framesWritten();
      journalCheckpoints.write(
          ImportCheckpoint.at(
              journal.position(),
              journal.lastSequence(),
              totalFrames,
              // Normalization is inline, so at a record boundary everything
              // journaled is already stored. The backlog a crash creates is
              // the frames written after this point, and recovery replays
              // exactly those.
              totalFrames,
              options.clock().nowMicros()));
      sourceCheckpoints.write(SourceCheckpoint.at(sourceOffset, totalFrames, format, preserved));
    }

    /**
     * Records what normalization could not reconcile.
     *
     * <p>Both counters are evidence the contracts promise to surface, and both were computed and
     * thrown away before this existed. A duplicate primary output means one of two actions is
     * missing from the table; a file set referenced and never defined means every byte total that
     * walks through it is a lower bound. Neither is visible any other way, because both look
     * exactly like a smaller build.
     */
    private void reportNormalizationAnomalies() throws IOException {
      if (entities == null) {
        return;
      }
      long conflicts = entities.conflictingActions();
      if (conflicts > 0) {
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            DiagnosticCodes.DUPLICATE_ACTION_OUTPUT,
            conflicts
                + " action event(s) repeated a primary output already recorded;"
                + " the first row for each was kept. This is expected after a"
                + " resumed import, which replays events the previous run already"
                + " normalized, and unexpected otherwise -- the path is measured"
                + " unique across a stream on every supported Bazel version.",
            OptionalLong.empty());
        warnings.add(conflicts + " action(s) repeated a primary output already recorded");
      }
      long undefined;
      try {
        undefined = entities.undefinedDepsets(streamId);
      } catch (SQLException e) {
        throw new IOException("failed to check for undefined file sets", e);
      }
      if (undefined > 0) {
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            DiagnosticCodes.UNDEFINED_FILE_SET,
            undefined
                + " named set(s) of files were referenced and never defined, so"
                + " any byte total reached through them is a lower bound rather"
                + " than a total. Zero of 1,829 references were forward references"
                + " in measurement, so this is evidence the capture is missing"
                + " events.",
            OptionalLong.empty());
        warnings.add(undefined + " file set(s) were referenced and never defined");
      }
    }

    private void flushEvents() throws IOException {
      try {
        events.flush();
      } catch (SQLException e) {
        throw new IOException("failed to commit a batch of events", e);
      }
      drainEntities();
    }

    /**
     * Applies the buffered entity commands, now that their events are in the database and their
     * provenance lookups can resolve.
     */
    private void drainEntities() throws IOException {
      if (pendingEntities.isEmpty()) {
        return;
      }
      try {
        for (PendingEntities pending : pendingEntities) {
          for (EntityCommand command : pending.commands()) {
            entities.apply(streamId, pending.sequence(), command);
          }
        }
        entities.flush();
      } catch (SQLException e) {
        throw new IOException("failed to normalize a batch of entities", e);
      } finally {
        pendingEntities.clear();
        pendingEntityCommands = 0;
      }
    }

    // -------------------------------------------------------------- progress

    private void emitProgress(boolean force) {
      if (listener == ImportProgressListener.NONE) {
        return;
      }
      if (!force) {
        if (++recordsSinceProgress < options.progressEveryRecords()) {
          return;
        }
        long now = System.nanoTime();
        if (lastProgressNanos != 0
            && now - lastProgressNanos < options.progressIntervalMillis() * 1_000_000L) {
          return;
        }
        lastProgressNanos = now;
        recordsSinceProgress = 0;
      } else {
        lastProgressNanos = System.nanoTime();
        recordsSinceProgress = 0;
      }
      listener.onProgress(
          new ImportProgress(
              phase,
              recordsJournaled,
              bytesRead,
              totalBytes > 0 ? OptionalLong.of(totalBytes) : OptionalLong.empty()));
    }

    // ------------------------------------------------------------ diagnostics

    private void recordDiagnostic(
        DiagnosticSeverity severity, String code, String message, OptionalLong byteOffset)
        throws IOException {
      recordDiagnostic(severity, code, message, byteOffset, OptionalLong.empty());
    }

    /**
     * Records one diagnostic, capping how many rows a single repeating code may add.
     *
     * <p>The cap is not a silent truncation: every occurrence is still counted, and {@link
     * #flushDiagnostics()} writes the true total as a summary row. Without a cap, a file whose
     * every record fails to decode would write one diagnostic row per event — doubling the size of
     * the database to say the same sentence a million times.
     */
    private void recordDiagnostic(
        DiagnosticSeverity severity,
        String code,
        String message,
        OptionalLong byteOffset,
        OptionalLong segmentIndex)
        throws IOException {
      long[] counts = diagnosticCounts.computeIfAbsent(code, key -> new long[2]);
      counts[0]++;
      if (counts[0] > options.maxDiagnosticsPerCode()) {
        return;
      }
      counts[1]++;
      ImportDiagnostic diagnostic =
          segmentIndex.isPresent() && byteOffset.isPresent()
              ? ImportDiagnostic.at(
                  severity,
                  code,
                  message,
                  (int) segmentIndex.getAsLong(),
                  byteOffset.getAsLong(),
                  options.clock().nowMicros())
              : new ImportDiagnostic(
                  severity,
                  code,
                  message,
                  segmentIndex.isPresent()
                      ? OptionalInt.of((int) segmentIndex.getAsLong())
                      : OptionalInt.empty(),
                  byteOffset,
                  options.clock().nowMicros());
      try {
        events.recordDiagnostic(diagnostic);
      } catch (SQLException e) {
        throw new IOException("failed to record an import diagnostic", e);
      }
    }

    private void recordJsonDiagnostic(JsonParseDiagnostic diagnostic, long baseOffset)
        throws IOException {
      DiagnosticSeverity severity =
          switch (diagnostic.severity()) {
            case INFO -> DiagnosticSeverity.INFO;
            case WARNING -> DiagnosticSeverity.WARNING;
            case ERROR -> DiagnosticSeverity.ERROR;
          };
      String code =
          switch (diagnostic.code()) {
            case RECORD_TOO_LARGE -> ImportDiagnosticCodes.RECORD_TOO_LARGE;
            case TRUNCATED_TAIL -> ImportDiagnosticCodes.SOURCE_TRUNCATED;
            case MALFORMED_TOP_LEVEL -> ImportDiagnosticCodes.MALFORMED_TOP_LEVEL;
            case DECODE_FAILED -> DiagnosticCodes.DECODE_FAILED;
            case UNKNOWN_FIELDS -> DiagnosticCodes.UNKNOWN_FIELDS;
            case EMPTY_INPUT, BYTE_ORDER_MARK_SKIPPED, COMMA_SEPARATED_RECORDS ->
                ImportDiagnosticCodes.SOURCE_NOTE;
          };
      if (diagnostic.code() == JsonParseDiagnostic.Code.MALFORMED_TOP_LEVEL) {
        damageOffset = OptionalLong.of(baseOffset + diagnostic.byteOffset());
      }
      // DECODE_FAILED and UNKNOWN_FIELDS are recorded against the journal
      // location by store(); recording the parser's copy too would double
      // every row.
      if (code.equals(DiagnosticCodes.DECODE_FAILED)
          || code.equals(DiagnosticCodes.UNKNOWN_FIELDS)) {
        return;
      }
      recordDiagnostic(
          severity,
          code,
          diagnostic.message(),
          OptionalLong.of(baseOffset + diagnostic.byteOffset()));
    }

    /**
     * Turns per-record decode problems into session-level warnings.
     *
     * <p>Without this a session in which a thousand events carried fields this build could not read
     * would still be labelled plainly {@code READY}, and a user told "ready" reasonably assumes
     * nothing was missed. The raw bytes are all preserved, so the honest statement is "usable, and
     * here is what was not understood".
     */
    private void summarizeDecodeOutcomes() {
      long unknownFields = diagnosticTotal(DiagnosticCodes.UNKNOWN_FIELDS);
      if (unknownFields > 0) {
        warnings.add(
            unknownFields
                + " event(s) carry fields this build's protos do not"
                + " define; their raw bytes are preserved in full and a later version can"
                + " reindex them");
      }
      long failed = diagnosticTotal(DiagnosticCodes.DECODE_FAILED);
      if (failed > 0) {
        warnings.add(
            failed
                + " event(s) could not be decoded at all; their raw bytes are"
                + " preserved so a later version can reinterpret them");
      }
      long oversized = diagnosticTotal(ImportDiagnosticCodes.RECORD_TOO_LARGE);
      if (oversized > 0) {
        warnings.add(
            oversized
                + " record(s) exceeded the configured per-record limit of "
                + options.maxRecordBytes()
                + " bytes and were not imported; raise the limit"
                + " and import again to include them");
      }
    }

    private long diagnosticTotal(String code) {
      long[] counts = diagnosticCounts.get(code);
      return counts == null ? 0 : counts[0];
    }

    /**
     * Writes the true totals for any code whose per-code cap was reached.
     *
     * <p>Iterates a snapshot because the loop inserts the summary code into the same map. Iterating
     * the live view threw {@link java.util.ConcurrentModificationException} on the next entry,
     * which failed the whole import at finalization — the cap exists to surface a limit, so
     * destroying the session instead was the worst possible outcome.
     */
    private void flushDiagnostics() throws IOException {
      for (Map.Entry<String, long[]> entry : List.copyOf(diagnosticCounts.entrySet())) {
        long total = entry.getValue()[0];
        long written = entry.getValue()[1];
        if (total > written) {
          long[] counts = diagnosticCounts.get(ImportDiagnosticCodes.DIAGNOSTIC_SUMMARY);
          if (counts == null) {
            counts = new long[2];
            diagnosticCounts.put(ImportDiagnosticCodes.DIAGNOSTIC_SUMMARY, counts);
          }
          counts[0]++;
          counts[1]++;
          try {
            events.recordDiagnostic(
                ImportDiagnostic.general(
                    DiagnosticSeverity.WARNING,
                    ImportDiagnosticCodes.DIAGNOSTIC_SUMMARY,
                    entry.getKey()
                        + " occurred "
                        + total
                        + " time(s); "
                        + written
                        + " were recorded individually and the rest are"
                        + " represented by this row",
                    options.clock().nowMicros()));
          } catch (SQLException e) {
            throw new IOException("failed to record a diagnostic summary", e);
          }
          warnings.add(entry.getKey() + " occurred " + total + " time(s)");
        }
      }
    }

    private void recordJournalRecovery(RecoveryReport recovery) throws IOException {
      for (SegmentScan scan : recovery.scans()) {
        if (scan.status() == JournalScanStatus.OK) {
          continue;
        }
        recordDiagnostic(
            scan.status() == JournalScanStatus.TRUNCATED_TAIL
                ? DiagnosticSeverity.WARNING
                : DiagnosticSeverity.ERROR,
            scan.status().diagnosticCode(),
            "journal segment "
                + scan.segmentIndex()
                + " was recovered to offset "
                + scan.endOffset()
                + ": "
                + scan.detail(),
            OptionalLong.of(scan.endOffset()),
            OptionalLong.of(scan.segmentIndex()));
        warnings.add(
            "journal segment "
                + scan.segmentIndex()
                + " lost "
                + scan.trailingBytes()
                + " trailing byte(s) to recovery");
      }
      recordDiagnostic(
          DiagnosticSeverity.INFO,
          DiagnosticCodes.RECOVERED_SESSION,
          "session recovered before resuming: "
              + recovery.segmentsScanned()
              + " journal segment(s) scanned, completeness "
              + recovery.completeness(),
          OptionalLong.empty());
    }

    /**
     * After reading a source that was left where it was, checks that it is still the file whose
     * digest was recorded.
     *
     * <p>Skipped on a resume, and not for lack of care: a resume has already re-hashed the source
     * in {@link #verifyPreservedSource()}, which is the stronger check. The size-and-timestamp
     * comparison exists only because re-hashing a large file at the end of every first import would
     * double the bytes read for a weaker guarantee than the one a resume needs.
     */
    private void checkReferenceSourceUnchanged() throws IOException {
      if (preserved.preservation() != SourcePreservation.REFERENCE_ORIGINAL || resumed) {
        return;
      }
      Path original = preserved.originalPath();
      try {
        long size = Files.size(original);
        long modified = Files.getLastModifiedTime(original).toMillis();
        if (size != preserved.byteSize() || modified != preserved.lastModifiedMillis()) {
          recordDiagnostic(
              DiagnosticSeverity.WARNING,
              ImportDiagnosticCodes.SOURCE_CHANGED,
              "the referenced source "
                  + original
                  + " changed while it was being read"
                  + " (was "
                  + preserved.byteSize()
                  + " bytes modified at "
                  + preserved.lastModifiedMillis()
                  + ", now "
                  + size
                  + " bytes at "
                  + modified
                  + "); the recorded digest no longer describes the file"
                  + " on disk",
              OptionalLong.empty());
          warnings.add("the referenced source changed while it was being read");
        }
      } catch (NoSuchFileException gone) {
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            ImportDiagnosticCodes.SOURCE_CHANGED,
            "the referenced source "
                + original
                + " disappeared while it was being read;"
                + " every record already journaled is still intact",
            OptionalLong.empty());
        warnings.add("the referenced source disappeared while it was being read");
      }
    }

    // ---------------------------------------------------------------- finish

    private ImportResult finish(ImportOutcome outcome) throws IOException {
      return outcome == ImportOutcome.CANCELLED ? finishCancelled() : finishTerminal(outcome);
    }

    private ImportResult finishCancelled() throws IOException {
      checkpoint();
      recordDiagnostic(
          DiagnosticSeverity.WARNING,
          ImportDiagnosticCodes.IMPORT_CANCELLED,
          "the import was cancelled after "
              + recordsJournaled
              + " record(s), at source byte offset "
              + sourceOffset
              + "; the session is resumable from this point",
          OptionalLong.of(sourceOffset));
      flushDiagnostics();
      flushEvents();
      updateCaptureSource(Completeness.UNKNOWN);
      updateStream("CANCELLED");
      long eventCount = eventsInDatabase();
      // Deliberately left non-terminal: INCOMPLETE has no successor state,
      // so marking it here would make the session permanently unresumable.
      session.updateManifest(
          builder ->
              builder
                  .addWarning(
                      "import cancelled at source byte offset "
                          + sourceOffset
                          + "; resume to continue, or abandon to mark it incomplete")
                  .sources(List.of(manifestSource(Completeness.UNKNOWN)))
                  .eventCount(OptionalLong.of(eventCount))
                  .schemaVersion(OptionalInt.of(SchemaV2.VERSION)));
      writeSessionInfo(session.state(), OptionalLong.empty());
      return result(ImportOutcome.CANCELLED, Optional.empty());
    }

    private ImportResult finishTerminal(ImportOutcome requested) throws IOException {
      ImportOutcome outcome = requested;
      beginPhase(ImportPhase.FINALIZING);
      emitProgress(true);
      checkpoint();
      reportNormalizationAnomalies();
      flushDiagnostics();
      summarizeDecodeOutcomes();

      EventWriter.IngestSummary summary;
      try {
        // finalizeIngest flushes, creates the indexes after the bulk load,
        // resolves bep_announced_missing and runs ANALYZE, in that order
        // (see EventWriter). Calling resolveAnnouncedMissing() separately
        // beforehand would repeat a full scan for the same answer.
        summary = events.finalizeIngest();
      } catch (SQLException e) {
        throw new IOException("failed to finalize ingestion", e);
      }
      if (summary.announcedMissing() > 0) {
        warnings.add(summary.announcedMissing() + " announced child event(s) never arrived");
      }
      if (!summary.reconciles()) {
        warnings.add(
            "the ingest did not reconcile: "
                + summary.eventsOffered()
                + " event(s) offered, "
                + summary.eventsInserted()
                + " inserted, "
                + summary.duplicatesIgnored()
                + " duplicate(s)");
      }

      // A record that was fully present and still would not decode is
      // exactly what Completeness.CORRUPT_PARTIAL describes — "a fully
      // present record failed validation" — even though the framing held
      // and reading ran to the end of the file. Reporting such a source as
      // COMPLETE would tell a user their capture is intact when some of its
      // bytes are wrong. Every event is still stored, before and after the
      // damage, with its raw bytes preserved for a later reindex; what
      // changes here is only the honesty of the label.
      if (outcome == ImportOutcome.COMPLETE && diagnosticTotal(DiagnosticCodes.DECODE_FAILED) > 0) {
        outcome = ImportOutcome.CORRUPT_PARTIAL;
        if (damageOffset.isEmpty()) {
          damageOffset = firstDecodeFailureOffset;
        }
      }

      Completeness completeness = outcome.completeness();
      updateCaptureSource(completeness);
      updateStream(completeness.name());

      long eventCount = eventsInDatabase();
      SessionState terminal = terminalStateFor(outcome);
      session.transitionTo(SessionState.BUILD_FINISHED);
      session.transitionTo(SessionState.INDEXING);
      session.updateManifest(
          builder -> {
            builder
                .sources(List.of(manifestSource(completeness)))
                .eventCount(OptionalLong.of(eventCount))
                .schemaVersion(OptionalInt.of(SchemaV2.VERSION))
                .indexVersions(Optional.of(Map.of("sqlite-schema", SchemaV2.VERSION)))
                .containsAbsolutePaths(Optional.of(true));
            for (String warning : warnings) {
              builder.addWarning(warning);
            }
            return builder;
          });
      session.finalizeSession(terminal);
      writeSessionInfo(terminal, session.manifest().finalizedMicros());

      return result(outcome, Optional.of(summary));
    }

    private void beginPhase(ImportPhase next) {
      phase = next;
      log.debug("import {} entered {}", session.id(), next);
    }

    /**
     * A clean import with nothing to report is {@code READY}; a clean import that lost or could not
     * interpret something is {@code READY_WITH_WARNINGS}, because a user who is told "ready" will
     * reasonably assume nothing was missed.
     */
    private SessionState terminalStateFor(ImportOutcome outcome) {
      SessionState state = outcome.sessionState();
      if (state == SessionState.READY && !warnings.isEmpty()) {
        return SessionState.READY_WITH_WARNINGS;
      }
      return state;
    }

    private void updateCaptureSource(Completeness completeness) throws IOException {
      if (captureSourceId < 0) {
        return;
      }
      try {
        SessionTables.updateCaptureSource(
            writerConnection(),
            captureSourceId,
            format.name(),
            preserved.originalPath().toString(),
            preserved.sha256(),
            OptionalLong.of(preserved.byteSize()),
            completeness,
            preservationNote());
      } catch (SQLException e) {
        throw new IOException("failed to update the capture source record", e);
      }
    }

    private void updateStream(String state) throws IOException {
      if (streamId < 0) {
        return;
      }
      try {
        OptionalLong last = SessionTables.maxSequence(writerConnection(), streamId);
        OptionalLong first = last.isPresent() ? OptionalLong.of(0) : OptionalLong.empty();
        streams.updateProgress(streamId, first, last, last, 0, 0, state);
        SessionTables.updateStreamIdentity(
            writerConnection(), streamId, invocationId, Optional.empty());
      } catch (SQLException e) {
        throw new IOException("failed to update the event stream record", e);
      }
    }

    private void writeSessionInfo(SessionState state, OptionalLong finalizedMicros)
        throws IOException {
      try {
        SessionTables.writeSessionInfo(
            writerConnection(),
            session.id(),
            state,
            session.manifest().createdMicros(),
            finalizedMicros,
            sessions.appVersion());
      } catch (SQLException e) {
        throw new IOException("failed to update session_info", e);
      }
    }

    private void recordManifestSource(Completeness completeness) throws IOException {
      session.updateManifest(builder -> builder.sources(List.of(manifestSource(completeness))));
    }

    private SessionManifest.CaptureSourceEntry manifestSource(Completeness completeness) {
      return SessionManifest.CaptureSourceEntry.of(
          format.name(),
          Optional.of(preserved.originalPath().toString()),
          Optional.of(preserved.sha256()),
          OptionalLong.of(preserved.byteSize()),
          completeness,
          preservationNote());
    }

    private long eventsInDatabase() throws IOException {
      try {
        return SessionTables.countEvents(writerConnection());
      } catch (SQLException e) {
        throw new IOException("failed to count stored events", e);
      }
    }

    private long diagnosticsInDatabase() throws IOException {
      try {
        return SessionTables.countDiagnostics(writerConnection());
      } catch (SQLException e) {
        throw new IOException("failed to count import diagnostics", e);
      }
    }

    private ImportResult result(ImportOutcome outcome, Optional<EventWriter.IngestSummary> summary)
        throws IOException {
      long parsePeak = peakParserBytes + options.journalBufferBytes() + peakScratchBytes;
      return new ImportResult(
          outcome,
          layout.root(),
          session.id(),
          session.state(),
          format,
          preserved,
          outcome.completeness(),
          recordsJournaled,
          eventsNormalized,
          summary,
          eventsInDatabase(),
          damageOffset,
          resumed,
          Math.max(parsePeak, SourceDigest.BUFFER_BYTES),
          diagnosticsInDatabase());
    }

    // ----------------------------------------------------------------- close

    @Override
    public void close() throws IOException {
      IOException failure = null;
      failure =
          closeStep(
              failure,
              () -> {
                if (journal != null) {
                  journal.close();
                }
              });
      failure =
          closeStep(
              failure,
              () -> {
                if (streams != null) {
                  try {
                    streams.close();
                  } catch (SQLException e) {
                    throw new IOException("failed to close the stream registry", e);
                  }
                }
              });
      failure =
          closeStep(
              failure,
              () -> {
                if (entities != null) {
                  try {
                    entities.close();
                  } catch (SQLException e) {
                    throw new IOException("failed to close the entity writer", e);
                  }
                }
              });
      failure =
          closeStep(
              failure,
              () -> {
                if (events != null) {
                  try {
                    events.close();
                  } catch (SQLException e) {
                    throw new IOException("failed to close the event writer", e);
                  }
                }
              });
      failure =
          closeStep(
              failure,
              () -> {
                if (database != null) {
                  try {
                    database.close();
                  } catch (SQLException e) {
                    throw new IOException("failed to close the session database", e);
                  }
                }
              });
      failure = closeStep(failure, session::close);
      if (failure != null) {
        throw failure;
      }
    }

    private IOException closeStep(IOException existing, IoStep step) {
      try {
        step.run();
        return existing;
      } catch (IOException | UncheckedIOException e) {
        IOException failure =
            e instanceof IOException io ? io : ((UncheckedIOException) e).getCause();
        if (existing == null) {
          return failure;
        }
        existing.addSuppressed(failure);
        return existing;
      }
    }

    @FunctionalInterface
    private interface IoStep {
      void run() throws IOException;
    }
  }
}

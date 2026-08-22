package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import com.holtherndon.bazelviz.capture.bes.RawBesEvent;
import com.holtherndon.bazelviz.capture.bes.RawEventSink;
import com.holtherndon.bazelviz.bepcodec.entity.EntityTranslator;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.format.journal.ImportCheckpoint;
import com.holtherndon.bazelviz.format.journal.ImportCheckpointStore;
import com.holtherndon.bazelviz.format.journal.JournalLocation;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.storage.events.DiagnosticSeverity;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live half of plan 9.3: everything between the gRPC callback and the
 * database.
 *
 * <pre>{@code
 *   submit()  ->  receive queue  ->  journal thread  ->  ack
 *                                          |
 *                                          v
 *                                  normalize queue  ->  store thread  ->  SQLite
 * }</pre>
 *
 * <h2>Two stages, two threads, two bounded queues</h2>
 *
 * <p>The split is what lets acknowledgement be fast and correct at the same
 * time. The journal thread does one cheap thing — append bytes — and then
 * acknowledges, so Bazel is never waiting on protobuf decoding or on SQLite.
 * Normalization runs behind it and may fall arbitrarily far behind without
 * risking anything, because the frames it has not reached yet are already
 * durable and are replayed from the journal after a crash.
 *
 * <p>Acknowledgement happens <em>before</em> the event is offered to the
 * normalize queue, deliberately. If the order were reversed, a full normalize
 * queue would delay acknowledgements for events that are already safely
 * journaled, and Bazel would slow down for a backlog that costs it nothing.
 *
 * <h2>Failure is terminal and loud</h2>
 *
 * <p>A journal write failure marks the pipeline failed. Every later
 * {@link #submit} throws {@link RawEventSink.CaptureRejectedException}, the
 * server fails its RPCs, and Bazel reports the upload failure to the user. The
 * alternative — swallowing the error and continuing — produces a session that
 * looks complete and is not, which plan rule 12 forbids and which is the worst
 * possible outcome for a tool whose entire value is being trusted about what
 * happened.
 */
public final class LiveCapturePipeline implements RawEventSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveCapturePipeline.class);

    /**
     * Entity commands held before the batch is forced out.
     *
     * <p>Sized in commands, not in events, because one event's commands range
     * from none to one per file in a named set. A live capture's memory has to
     * be bounded by configuration rather than by the build (plan rule 9).
     */
    static final int MAX_PENDING_ENTITY_COMMANDS = 20_000;

    /**
     * How long {@link #finish()} waits to hand the journal thread its sentinel
     * before concluding that it is never going to take it.
     */
    private static final java.time.Duration SENTINEL_HANDOVER = java.time.Duration.ofSeconds(5);

    /** Queue sentinel meaning "no more work is coming". */
    private static final Submission END_OF_STREAM =
            new Submission(null, null);

    private record Submission(RawBesEvent event, Runnable onJournaled) {}

    private record Journaled(RawBesEvent event, JournalLocation location) {}

    /**
     * The stream a locally-captured BEP file becomes.
     *
     * <p>A file is not a BES stream and does not pretend to be one: it gets its
     * own {@code event_streams} row so that the session can say which of its
     * events arrived live and which were read from a file afterwards
     * (plan 11.5, provenance).
     */
    public static final String FILE_STREAM_KEY = "file:bep-fallback";

    private static final Journaled NORMALIZE_END =
            new Journaled(null, null);

    private final JournalWriter journal;
    private final EventWriter events;
    private final EntityWriter entities;
    private final EntityTranslator translator = new EntityTranslator();

    /**
     * Entity commands waiting for their {@code bep_events} rows.
     *
     * <p>{@link EventWriter} batches, so an event's row does not exist until the
     * batch is executed — and every entity row finds its provenance by looking
     * that row up. The commands therefore wait here until {@code events.flush()}
     * has run, and are applied immediately afterwards.
     *
     * <p>Bounded by {@link #MAX_PENDING_ENTITY_COMMANDS} rather than by the
     * event batch size. Those are not the same bound: one event can carry
     * thousands of commands — a {@code NamedSetOfFiles} holds a file list — so
     * a buffer sized in events is a buffer of unbounded bytes, and this one
     * sits behind a live capture that must not grow with the build.
     *
     * <p>Touched only by whichever thread is currently storing: the store thread
     * while the build runs, and the coordinator afterwards for fallback file
     * ingestion. The two never overlap, because fallback ingestion begins only
     * after {@link #finish()} has joined the store thread.
     */
    private final List<PendingEntities> pendingEntities = new ArrayList<>();

    /** Commands currently held in {@link #pendingEntities}. */
    private int pendingEntityCommands;
    private final StreamRegistry streams;
    private final EventNormalizer normalizer;
    private final ImportCheckpointStore checkpoints;
    private final CaptureOptions options;
    private final CaptureProgressListener listener;
    private final Clock clock;

    private final BlockingQueue<Submission> receiveQueue;
    private final BlockingQueue<Journaled> normalizeQueue;

    /** Journal stream ordinals, assigned on the receive thread as streams appear. */
    private final Map<BesStreamKey, Integer> streamOrdinals = new ConcurrentHashMap<>();
    private final AtomicInteger nextStreamOrdinal = new AtomicInteger();

    /** Database stream ids, resolved on the store thread only. */
    private final Map<BesStreamKey, Long> streamRowIds = new LinkedHashMap<>();

    /** Final stream states, recorded as streams end and persisted at finalization. */
    private final Map<BesStreamKey, BesStreamState> finalStates = new ConcurrentHashMap<>();

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong journaledCount = new AtomicLong();
    private final AtomicLong normalizedCount = new AtomicLong();
    private final AtomicLong bytesJournaled = new AtomicLong();
    private final AtomicLong decodeFailures = new AtomicLong();
    private final AtomicLong nonEventEnvelopes = new AtomicLong();
    private final AtomicBoolean lagged = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    private final AtomicInteger fileStreamOrdinal = new AtomicInteger(-1);
    private long fileStreamRowId = -1;

    private volatile Throwable failure;
    private volatile long lastProgressMillis;

    private Thread journalThread;
    private Thread storeThread;
    private boolean closed;

    public LiveCapturePipeline(
            JournalWriter journal,
            EventWriter events,
            EntityWriter entities,
            StreamRegistry streams,
            EventNormalizer normalizer,
            ImportCheckpointStore checkpoints,
            CaptureOptions options,
            CaptureProgressListener listener) {
        this(journal, events, entities, streams, normalizer, checkpoints, options, listener,
                Clock.systemUTC());
    }

    public LiveCapturePipeline(
            JournalWriter journal,
            EventWriter events,
            EntityWriter entities,
            StreamRegistry streams,
            EventNormalizer normalizer,
            ImportCheckpointStore checkpoints,
            CaptureOptions options,
            CaptureProgressListener listener,
            Clock clock) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.events = Objects.requireNonNull(events, "events");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.options = Objects.requireNonNull(options, "options");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.receiveQueue = new ArrayBlockingQueue<>(options.receiveQueueCapacity());
        this.normalizeQueue = new ArrayBlockingQueue<>(options.normalizeQueueCapacity());
    }

    /** Starts the two pipeline threads. Call before the BES server accepts anything. */
    public synchronized void start() {
        if (journalThread != null) {
            throw new IllegalStateException("this pipeline has already been started");
        }
        journalThread = new Thread(this::runJournal, "bbv-capture-journal");
        storeThread = new Thread(this::runStore, "bbv-capture-store");
        journalThread.setDaemon(true);
        storeThread.setDaemon(true);
        journalThread.start();
        storeThread.start();
    }

    // ------------------------------------------------------------------ sink

    @Override
    public void submit(RawBesEvent event, Runnable onJournaled)
            throws InterruptedException, CaptureRejectedException {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(onJournaled, "onJournaled");
        rejectIfUnusable();
        if (event.payloadLength() > options.maxMessageBytes()) {
            throw new CaptureRejectedException("event " + event.sequence() + " is "
                    + event.payloadLength() + " bytes, above the " + options.maxMessageBytes()
                    + "-byte limit this capture accepts");
        }
        streamOrdinals.computeIfAbsent(event.stream(), key -> nextStreamOrdinal.getAndIncrement());
        received.incrementAndGet();

        Submission submission = new Submission(event, onJournaled);
        if (!receiveQueue.offer(submission)) {
            // The queue is full: this is the backpressure. Recorded before
            // blocking so the lag indicator lights up while the user is
            // waiting, not afterwards when it no longer matters.
            noteLag();
            receiveQueue.put(submission);
        }
        // Re-checked after the wait: the pipeline may have failed while this
        // event sat in the queue, and reporting success for an event that will
        // never be journaled is exactly the silent drop the contract forbids.
        rejectIfUnusable();
    }

    @Override
    public void streamOpened(BesStreamKey key) {
        streamOrdinals.computeIfAbsent(key, ignored -> nextStreamOrdinal.getAndIncrement());
        log.info("BES stream opened: {}", key);
    }

    @Override
    public void streamEnded(BesStreamState finalState) {
        finalStates.put(finalState.key(), finalState);
        log.info("BES stream {} ended: {} events, {} duplicates, completion {}",
                finalState.key(), finalState.eventsAccepted(),
                finalState.duplicateCount(), finalState.completion());
    }

    // -------------------------------------------------------- journal thread

    private void runJournal() {
        long framesSinceFlush = 0;
        long lastFlushMillis = clock.millis();
        try {
            while (true) {
                Submission submission = receiveQueue.poll(50, TimeUnit.MILLISECONDS);
                if (submission == null) {
                    if (shouldFlush(framesSinceFlush, lastFlushMillis)) {
                        journal.flush();
                        framesSinceFlush = 0;
                        lastFlushMillis = clock.millis();
                    }
                    continue;
                }
                if (submission == END_OF_STREAM) {
                    journal.flush();
                    break;
                }
                RawBesEvent event = submission.event();
                JournalLocation location = journal.append(
                        event.sourceKind(),
                        streamOrdinals.get(event.stream()),
                        event.sequence(),
                        event.receiveMicros(),
                        event.payload());
                journaledCount.incrementAndGet();
                bytesJournaled.addAndGet(event.payloadLength());
                framesSinceFlush++;

                // Acknowledge first. The frame is durable enough to promise
                // (plan 9.3), and delaying the ack behind a full normalize
                // queue would slow Bazel down for a backlog that cannot lose
                // anything.
                submission.onJournaled().run();

                Journaled journaled = new Journaled(event, location);
                if (!normalizeQueue.offer(journaled)) {
                    noteLag();
                    normalizeQueue.put(journaled);
                }
                if (shouldFlush(framesSinceFlush, lastFlushMillis)) {
                    journal.flush();
                    framesSinceFlush = 0;
                    lastFlushMillis = clock.millis();
                }
                publishProgress(false);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException problem) {
            fail(problem);
        } finally {
            // Whatever happened, the store thread must be told to stop, or
            // close() waits forever for a thread with no work coming.
            try {
                normalizeQueue.put(NORMALIZE_END);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean shouldFlush(long framesSinceFlush, long lastFlushMillis) {
        if (framesSinceFlush == 0) {
            return false;
        }
        return clock.millis() - lastFlushMillis >= options.journalFlushInterval().toMillis();
    }

    // ---------------------------------------------------------- store thread

    private void runStore() {
        long sinceCheckpoint = 0;
        long lastCommitMillis = clock.millis();
        int pending = 0;
        try {
            while (true) {
                Journaled journaled = normalizeQueue.poll(50, TimeUnit.MILLISECONDS);
                if (journaled == null) {
                    if (pending > 0 && clock.millis() - lastCommitMillis >= options.flushInterval().toMillis()) {
                        events.flush();
                        drainEntities();
                        pending = 0;
                        lastCommitMillis = clock.millis();
                        publishProgress(true);
                    }
                    continue;
                }
                if (journaled == NORMALIZE_END) {
                    events.flush();
                    drainEntities();
                    break;
                }
                if (store(journaled)) {
                    normalizedCount.incrementAndGet();
                    pending++;
                }
                sinceCheckpoint++;

                if (pending >= options.batchSize()
                        || pendingEntityCommands >= MAX_PENDING_ENTITY_COMMANDS
                        || clock.millis() - lastCommitMillis >= options.flushInterval().toMillis()) {
                    events.flush();
                    drainEntities();
                    pending = 0;
                    lastCommitMillis = clock.millis();
                    publishProgress(true);
                }
                if (sinceCheckpoint >= options.checkpointEveryFrames()) {
                    writeCheckpoint();
                    sinceCheckpoint = 0;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException problem) {
            fail(problem);
        }
    }

    /**
     * Turns one journaled frame into rows.
     *
     * @return true when a {@code bep_events} row was written. False for an
     *     envelope that carries no build event — which is counted separately,
     *     and must not also be counted as normalized: the two counters together
     *     have to account for every frame exactly once, and that arithmetic is
     *     what {@link CaptureSummary#isComplete()} checks
     */
    private boolean store(Journaled journaled) throws SQLException {
        RawBesEvent event = journaled.event();
        long streamId = resolveStreamRow(event.stream());
        Optional<EventNormalizer.Normalization> normalization = normalizer.normalizeBesEnvelope(
                event.sourceKind(),
                event.payload(),
                0,
                event.payloadLength(),
                streamId,
                event.sequence(),
                journaled.location(),
                event.receiveMicros());

        if (normalization.isEmpty()) {
            // Lifecycle, console output, or the stream terminator. Journaled,
            // counted, and deliberately not a row — see EventNormalizer.
            nonEventEnvelopes.incrementAndGet();
            return false;
        }

        EventNormalizer.Normalization result = normalization.get();
        events.write(result.normalized());
        bufferEntities(streamId, event.sequence(), result);
        if (result.status() == DecodeStatus.FAILED) {
            decodeFailures.incrementAndGet();
            events.recordDiagnostic(ImportDiagnostic.at(
                    DiagnosticSeverity.ERROR,
                    com.holtherndon.bazelviz.storage.events.DiagnosticCodes.DECODE_FAILED,
                    "sequence " + event.sequence() + ": " + result.failureDetail(),
                    journaled.location().segmentIndex(),
                    journaled.location().frameOffset(),
                    nowMicros()));
        }
        return true;
    }

    /** Holds one event's entity commands until its {@code bep_events} row exists. */
    private void bufferEntities(
            long streamId, long sequence, EventNormalizer.Normalization normalization) {
        normalization.event().ifPresent(event -> {
            List<EntityCommand> commands = translator.translate(event);
            if (!commands.isEmpty()) {
                pendingEntities.add(new PendingEntities(streamId, sequence, commands));
                pendingEntityCommands += commands.size();
            }
        });
    }

    /** Applies the buffered commands, whose provenance lookups can now resolve. */
    private void drainEntities() throws SQLException {
        if (pendingEntities.isEmpty()) {
            return;
        }
        try {
            for (PendingEntities pending : pendingEntities) {
                for (EntityCommand command : pending.commands()) {
                    entities.apply(pending.streamId(), pending.sequence(), command);
                }
            }
            entities.flush();
        } finally {
            pendingEntities.clear();
            pendingEntityCommands = 0;
        }
    }

    /** One event's worth of entity commands, and where it came from. */
    private record PendingEntities(long streamId, long sequence, List<EntityCommand> commands) {}

    /**
     * The {@code event_streams} row id for a stream, created on first use.
     *
     * <p>Resolved here rather than when the stream opens because this is the
     * only thread that touches the database. A row created from the gRPC thread
     * would be a second writer on a connection that has exactly one.
     */
    private long resolveStreamRow(BesStreamKey key) throws SQLException {
        Long existing = streamRowIds.get(key);
        if (existing != null) {
            return existing;
        }
        long id = streams.open(
                key.storageKey(),
                Optional.of(key.invocationId()),
                Optional.of(key.buildId()));
        streamRowIds.put(key, id);
        return id;
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Journals and indexes one record read from a local BEP file.
     *
     * <p>The fallback path for a BES conflict (plan 8.5 option 2): the user
     * keeps their own backend and this application reads a local copy instead.
     * Called from the coordinator's thread after the build has finished and
     * both pipeline threads have been joined, so this is the only writer —
     * which is why it appends directly rather than going through the queue.
     *
     * <p>The bytes are journaled with {@link SourceKind#BEP_BINARY}, not a BES
     * kind, because that is what they are. A session that mixed the two without
     * saying so could not later tell a live event from a file-read one.
     *
     * @return true when a {@code bep_events} row was written
     */
    public boolean ingestFileRecord(byte[] payload, int offset, int length, long ordinal)
            throws IOException, SQLException {
        long receiveMicros = nowMicros();
        JournalLocation location = journal.append(
                SourceKind.BEP_BINARY, fileStreamOrdinal(), ordinal, receiveMicros,
                payload, offset, length);
        journaledCount.incrementAndGet();
        bytesJournaled.addAndGet(length);
        received.incrementAndGet();

        long streamId = resolveFileStreamRow();
        EventNormalizer.Normalization normalization = normalizer.normalize(
                SourceKind.BEP_BINARY, payload, offset, length, streamId, ordinal,
                location, receiveMicros);
        events.write(normalization.normalized());
        bufferEntities(streamId, ordinal, normalization);
        normalizedCount.incrementAndGet();
        if (normalization.status() == DecodeStatus.FAILED) {
            decodeFailures.incrementAndGet();
            events.recordDiagnostic(ImportDiagnostic.at(
                    DiagnosticSeverity.ERROR,
                    com.holtherndon.bazelviz.storage.events.DiagnosticCodes.DECODE_FAILED,
                    "record " + ordinal + " of the local build event file: "
                            + normalization.failureDetail(),
                    location.segmentIndex(),
                    location.frameOffset(),
                    receiveMicros));
        }
        return true;
    }

    /** Commits whatever {@link #ingestFileRecord} has written. */
    public void flushFileRecords() throws IOException, SQLException {
        events.flush();
        drainEntities();
        journal.flush();
        writeCheckpoint();
    }

    private int fileStreamOrdinal() {
        return fileStreamOrdinal.updateAndGet(
                current -> current >= 0 ? current : nextStreamOrdinal.getAndIncrement());
    }

    private long resolveFileStreamRow() throws SQLException {
        if (fileStreamRowId < 0) {
            fileStreamRowId = streams.open(FILE_STREAM_KEY, Optional.empty(), Optional.empty());
        }
        return fileStreamRowId;
    }

    /**
     * Stops accepting events, drains what is already queued, and commits.
     *
     * <p>Draining rather than discarding is what makes a cancelled build
     * produce an inspectable session (a Phase 2 exit criterion): the events
     * Bazel had already handed over are ours, and throwing them away because
     * the user pressed cancel would lose exactly the evidence they wanted.
     *
     * @return what the capture ended up containing
     */
    public CaptureSummary finish() throws IOException, SQLException, InterruptedException {
        accepting.set(false);
        signalEndOfStream();
        joinQuietly(journalThread);
        joinQuietly(storeThread);

        persistStreamStates();
        events.flush();
        writeCheckpoint();
        // Skipped when the journal has already failed: force() throws
        // IllegalStateException on a failed writer, and that unchecked
        // exception escaping here aborted the caller's entire cleanup — the
        // session was left non-terminal with its lock still on disk. There is
        // nothing to force in that state anyway; the writer dropped its staged
        // bytes when it failed, and said so.
        if (!journal.isFailed()) {
            journal.force();
        }
        publishProgress(true);

        return new CaptureSummary(
                received.get(),
                journaledCount.get(),
                normalizedCount.get(),
                nonEventEnvelopes.get(),
                decodeFailures.get(),
                bytesJournaled.get(),
                List.copyOf(finalStates.values()),
                lagged.get(),
                Optional.ofNullable(failure));
    }

    /**
     * Tells the journal thread to stop, without waiting forever for it.
     *
     * <p>The sentinel goes through the same bounded queue as everything else,
     * and the journal thread is its only consumer. When that thread has already
     * died — which is exactly what a failed journal write does to it — a
     * blocking {@code put} on a full queue never returns, and the caller hangs
     * holding the session lock with the database open. So the handover is
     * bounded, and a queue that will never drain is drained here instead.
     *
     * <p>Also tolerant of an interrupted caller. {@code put} throws before
     * touching the queue when the interrupt flag is set, which would skip the
     * entire drain — the persist, the flush, the checkpoint — on the one path
     * where finishing matters most.
     */
    private void signalEndOfStream() {
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + SENTINEL_HANDOVER.toNanos();
            while (System.nanoTime() < deadline) {
                if (receiveQueue.offer(END_OF_STREAM)) {
                    return;
                }
                if (!journalThread.isAlive()) {
                    break;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
            // The consumer is gone or wedged. Whatever is still queued was
            // accepted and will never be journaled, which the counters already
            // report as received != journaled; clearing the queue is what lets
            // this method return at all.
            int abandoned = receiveQueue.size();
            receiveQueue.clear();
            if (abandoned > 0) {
                log.error("{} accepted event(s) were never journaled: the journal writer stopped",
                        abandoned);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Writes the final {@code event_streams} bookkeeping for every stream seen. */
    private void persistStreamStates() throws SQLException {
        for (BesStreamState state : finalStates.values()) {
            Long rowId = streamRowIds.get(state.key());
            if (rowId == null) {
                // A stream that opened and ended without a single event
                // reaching the store thread. It still gets a row: "a stream
                // arrived and produced nothing" is a fact worth keeping.
                rowId = streams.open(
                        state.key().storageKey(),
                        Optional.of(state.key().invocationId()),
                        Optional.of(state.key().buildId()));
                streamRowIds.put(state.key(), rowId);
            }
            streams.updateProgress(
                    rowId,
                    state.eventsAccepted() == 0
                            ? OptionalLong.empty()
                            : OptionalLong.of(BesStreamState.FIRST_SEQUENCE),
                    state.highestReceived() == 0
                            ? OptionalLong.empty()
                            : OptionalLong.of(state.highestReceived()),
                    state.highestContiguous() == 0
                            ? OptionalLong.empty()
                            : OptionalLong.of(state.highestContiguous()),
                    state.duplicateCount(),
                    state.hasGap() ? 1 : 0,
                    state.completion().name());
            recordStreamDiagnostic(state);
        }
    }

    private void recordStreamDiagnostic(BesStreamState state) throws SQLException {
        switch (state.completion()) {
            case ABORTED -> events.recordDiagnostic(ImportDiagnostic.general(
                    DiagnosticSeverity.WARNING,
                    CaptureDiagnosticCodes.STREAM_ABORTED,
                    "stream " + state.key() + " ended without its terminating event after "
                            + state.eventsAccepted() + " events; the capture is partial",
                    nowMicros()));
            case FAILED -> events.recordDiagnostic(ImportDiagnostic.general(
                    DiagnosticSeverity.ERROR,
                    CaptureDiagnosticCodes.STREAM_FAILED,
                    "stream " + state.key() + " was refused: " + state.error().orElse("no detail"),
                    nowMicros()));
            case OPEN, FINISHED -> {
                // FINISHED needs no diagnostic. OPEN cannot occur in a final
                // state, and if it somehow does, the state column already says
                // so more precisely than a message would.
            }
        }
        if (state.hasGap()) {
            events.recordDiagnostic(ImportDiagnostic.general(
                    DiagnosticSeverity.ERROR,
                    com.holtherndon.bazelviz.storage.events.DiagnosticCodes.SEQUENCE_GAP,
                    "stream " + state.key() + " received up to sequence " + state.highestReceived()
                            + " but is only contiguous through " + state.highestContiguous(),
                    nowMicros()));
        }
    }

    /** True once a journal or storage failure has made the capture unreliable. */
    public boolean hasFailed() {
        return failure != null;
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    public CaptureProgress progress() {
        List<String> streamLines = new ArrayList<>();
        finalStates.forEach((key, state) -> streamLines.add(
                key + ": " + state.eventsAccepted() + " events, " + state.completion()));
        return new CaptureProgress(
                received.get(),
                journaledCount.get(),
                normalizedCount.get(),
                bytesJournaled.get(),
                decodeFailures.get(),
                streamLines,
                lagged.get());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        accepting.set(false);
        // Interrupting is deliberate here and not in finish(): close() is the
        // abandon path. JournalWriter documents that interrupting its thread
        // ends the journal early, so the orderly shutdown goes through
        // finish(), and this exists for the case where that already failed.
        if (journalThread != null) {
            journalThread.interrupt();
        }
        if (storeThread != null) {
            storeThread.interrupt();
        }
    }

    // ------------------------------------------------------------- internals

    private void rejectIfUnusable() throws CaptureRejectedException {
        Throwable problem = failure;
        if (problem != null) {
            throw new CaptureRejectedException(
                    "this capture failed and cannot accept further events: " + problem, problem);
        }
        if (!accepting.get()) {
            throw new CaptureRejectedException("this capture has stopped accepting events");
        }
    }

    private void fail(Throwable problem) {
        if (failure == null) {
            failure = problem;
            log.error("live capture failed; no further events will be accepted", problem);
        }
        accepting.set(false);
    }

    private void noteLag() {
        if (lagged.compareAndSet(false, true)) {
            log.info("capture is applying backpressure: a pipeline queue is full");
        }
    }

    private void writeCheckpoint() {
        try {
            checkpoints.write(ImportCheckpoint.at(
                    journal.position(),
                    journal.lastSequence(),
                    journal.framesWritten(),
                    Math.min(normalizedCount.get() + nonEventEnvelopes.get(), journal.framesWritten()),
                    nowMicros()));
        } catch (IOException problem) {
            // A checkpoint is an optimization for resume, not a correctness
            // requirement: the journal is the source of truth and can be
            // replayed from its start. Failing the capture over one would trade
            // a slower restart for no session at all.
            log.warn("could not write the capture checkpoint", problem);
        }
    }

    private void publishProgress(boolean force) {
        long now = clock.millis();
        if (!force && now - lastProgressMillis < options.progressInterval().toMillis()) {
            return;
        }
        lastProgressMillis = now;
        try {
            listener.progressed(progress());
        } catch (RuntimeException misbehaving) {
            log.warn("a capture progress listener failed", misbehaving);
        }
    }

    private long nowMicros() {
        return clock.instant().getEpochSecond() * 1_000_000L + clock.instant().getNano() / 1_000L;
    }

    private static void joinQuietly(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}

package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileAccess;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the raw inspector: one selection in, one payload read out.
 *
 * <h2>Only the selected event, and only once</h2>
 *
 * <p>Plan 17.11 requires that full protobuf text be rendered for the selected
 * event only. This model is where that is enforced: the table's page fetches
 * never touch payload bytes, and this model reads exactly one payload per
 * distinct selection.
 *
 * <p>"Per distinct selection" is doing real work. {@code JTable} fires
 * selection events liberally — while a drag is adjusting, when focus moves,
 * when the model announces that a page arrived — and a payload can be
 * megabytes off disk. Re-selecting the row that is already shown is therefore a
 * no-op, and a selection that is superseded before its fetch starts is dropped
 * instead of being read and discarded.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #select} is called on the EDT and returns immediately. The read
 * runs on {@code fetchExecutor} — single-threaded, owning the
 * {@link SessionReader}, separate from the table's page executor so a slow
 * journal read cannot stall scrolling. Results reach listeners through
 * {@code uiDispatcher}. Nothing here does I/O on the calling thread.
 */
public final class EventInspectorModel {

    private static final Logger log = LoggerFactory.getLogger(EventInspectorModel.class);

    /** Receives inspector state, always on the UI dispatcher. */
    @FunctionalInterface
    public interface Listener {
        void inspectionChanged(EventInspection inspection);
    }

    /** Receives lazy file-tab state, always on the UI dispatcher. */
    @FunctionalInterface
    public interface FileListener {
        void filesChanged(EventFileInspection inspection);
    }

    private final SessionReader reader;
    private final Executor fetchExecutor;
    private final Executor uiDispatcher;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<FileListener> fileListeners = new CopyOnWriteArrayList<>();
    private volatile Optional<WorkspaceFileAccess> fileAccess;

    /** Bumped by every selection change; a fetch whose generation is stale is dropped. */
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong fileGeneration = new AtomicLong();

    private final AtomicLong payloadFetches = new AtomicLong();
    private final AtomicLong fileLoads = new AtomicLong();

    private volatile OptionalLong selected = OptionalLong.empty();
    private volatile EventInspection current = EventInspection.none();
    private volatile EventFileInspection currentFiles = EventFileInspection.none();

    public EventInspectorModel(SessionReader reader, Executor fetchExecutor, Executor uiDispatcher) {
        this(reader, fetchExecutor, uiDispatcher, Optional.empty());
    }

    public EventInspectorModel(
            SessionReader reader,
            Executor fetchExecutor,
            Executor uiDispatcher,
            SessionInfo session) {
        this(reader, fetchExecutor, uiDispatcher,
                WorkspaceFileAccess.fromSession(Objects.requireNonNull(session, "session")));
    }

    public EventInspectorModel(
            SessionReader reader,
            Executor fetchExecutor,
            Executor uiDispatcher,
            WorkspaceFileAccess fileAccess) {
        this(reader, fetchExecutor, uiDispatcher,
                Optional.of(Objects.requireNonNull(fileAccess, "fileAccess")));
    }

    private EventInspectorModel(
            SessionReader reader,
            Executor fetchExecutor,
            Executor uiDispatcher,
            Optional<WorkspaceFileAccess> fileAccess) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.fetchExecutor = Objects.requireNonNull(fetchExecutor, "fetchExecutor");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.fileAccess = Objects.requireNonNull(fileAccess, "fileAccess");
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void addFileListener(FileListener listener) {
        fileListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeFileListener(FileListener listener) {
        fileListeners.remove(listener);
    }

    /** The most recently published state. */
    public EventInspection current() {
        return current;
    }

    /** The event currently selected, empty when none is. */
    public OptionalLong selectedEventId() {
        return selected;
    }

    /**
     * Raw payload reads performed since this model was created. The inspector's
     * central promise is measurable rather than asserted in a comment.
     */
    public long payloadFetchCount() {
        return payloadFetches.get();
    }

    /** Lazy file metadata loads performed since this model was created. */
    public long fileLoadCount() {
        return fileLoads.get();
    }

    public EventFileInspection currentFiles() {
        return currentFiles;
    }

    /**
     * Detaches execution-backed file access without closing the analysis reader.
     *
     * <p>The returned barrier is queued behind every already accepted inspector/file task on the
     * model's serial executor. Once it completes, no task can still be using the old execution
     * filesystem; later file requests retain recorded paths but omit host metadata.
     */
    public CompletionStage<Void> detachExecutionFileAccessAsync() {
        fileAccess = Optional.empty();
        fileGeneration.incrementAndGet();
        publishFiles(EventFileInspection.none());
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            fetchExecutor.execute(() -> barrier.complete(null));
        } catch (RuntimeException failure) {
            barrier.completeExceptionally(failure);
        }
        return barrier;
    }

    /** Clears the selection without reading anything. */
    public void clearSelection() {
        if (selected.isEmpty()) {
            return;
        }
        selected = OptionalLong.empty();
        generation.incrementAndGet();
        publishFiles(EventFileInspection.none());
        publish(EventInspection.none());
    }

    /**
     * Selects {@code eventId}, reading its detail and payload off the EDT.
     * Selecting the event that is already shown does nothing.
     */
    public void select(long eventId) {
        OptionalLong previous = selected;
        if (previous.isPresent() && previous.getAsLong() == eventId) {
            return;
        }
        selected = OptionalLong.of(eventId);
        long mine = generation.incrementAndGet();
        publishFiles(EventFileInspection.none());
        publish(EventInspection.loading(eventId));
        fetchExecutor.execute(() -> {
            if (generation.get() != mine) {
                // Superseded while queued: reading it would cost a disk seek
                // whose result is already unwanted.
                return;
            }
            EventInspection result = load(eventId);
            if (generation.get() != mine) {
                return;
            }
            publish(result);
        });
    }

    /** Lazily decodes and stats files for the currently loaded event. */
    public void requestFiles() {
        EventInspection snapshot = current;
        if (snapshot.state() != EventInspection.State.LOADED
                || snapshot.eventId().isEmpty()
                || snapshot.payload().isEmpty()) {
            return;
        }
        long eventId = snapshot.eventId().getAsLong();
        EventFileInspection previous = currentFiles;
        if (previous.eventId().isPresent()
                && previous.eventId().getAsLong() == eventId
                && previous.state() != EventFileInspection.State.NONE) {
            return;
        }
        long mine = generation.get();
        long fileMine = fileGeneration.get();
        Optional<WorkspaceFileAccess> access = fileAccess;
        publishFiles(EventFileInspection.loading(eventId));
        RawPayload payload = snapshot.payload().orElseThrow();
        fetchExecutor.execute(() -> {
            if (generation.get() != mine || fileGeneration.get() != fileMine) {
                return;
            }
            EventFileInspection result;
            try {
                fileLoads.incrementAndGet();
                result = EventFileLoader.load(eventId, payload, access);
            } catch (RuntimeException failure) {
                log.warn("could not read files from event {}", eventId, failure);
                result = EventFileInspection.failed(eventId, describe(failure));
            }
            if (generation.get() == mine && fileGeneration.get() == fileMine) {
                publishFiles(result);
            }
        });
    }

    /** Runs on the fetch executor. Never throws; every failure becomes a state. */
    private EventInspection load(long eventId) {
        try {
            Optional<EventDetail> detail = reader.event(eventId);
            if (detail.isEmpty()) {
                return EventInspection.failed(eventId, "There is no event with row id " + eventId
                        + " in this session.");
            }
            EventRow row = EventRow.of(detail.get());
            payloadFetches.incrementAndGet();
            RawPayload payload = reader.rawPayload(row.rawLocation());
            RawPayloadRenderer.Rendered rendered =
                    RawPayloadRenderer.render(payload, row.decodeStatus());
            String hex = RawPayloadRenderer.hexDump(payload.bytes());
            return EventInspection.loaded(row, payload, rendered, hex);
        } catch (RuntimeException failure) {
            log.warn("could not inspect event {}", eventId, failure);
            return EventInspection.failed(eventId, describe(failure));
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.toString());
        Throwable cause = failure.getCause();
        while (cause != null) {
            text.append("\ncaused by: ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }

    private void publish(EventInspection inspection) {
        current = inspection;
        uiDispatcher.execute(() -> {
            for (Listener listener : listeners) {
                listener.inspectionChanged(inspection);
            }
        });
    }

    private void publishFiles(EventFileInspection inspection) {
        currentFiles = inspection;
        uiDispatcher.execute(() -> {
            for (FileListener listener : fileListeners) {
                listener.filesChanged(inspection);
            }
        });
    }
}

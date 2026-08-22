package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventIdentity;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/**
 * An in-memory {@link SessionReader} for the Events view tests.
 *
 * <p>The whole point of {@code SessionReader} being an interface (plan rule 19)
 * is here: every model in {@code ui.events} can be driven headlessly, with
 * exact control over what the store contains, without a SQLite file or a
 * journal on disk. It also counts calls and records which thread made them,
 * which is how the tests assert that the inspector fetches once and that it
 * never fetches on the EDT.
 *
 * <p>Deliberately keyset-only, exactly like the real one: there is no way to
 * ask it for "row N", so a test that passes here cannot be relying on an
 * offset-shaped shortcut the real store does not offer.
 */
final class FakeSessionReader implements SessionReader {

    private final List<EventSummary> summaries = new ArrayList<>();
    private final Map<Long, RawLocation> locations = new HashMap<>();
    private final Map<Long, EventIdentity> identities = new HashMap<>();
    private final Map<Long, RawPayload> payloads = new HashMap<>();

    private final AtomicInteger pageAfterCalls = new AtomicInteger();
    private final AtomicInteger pageBeforeCalls = new AtomicInteger();
    private final AtomicInteger eventCalls = new AtomicInteger();
    private final AtomicInteger rawPayloadCalls = new AtomicInteger();
    private final Set<String> callingThreads = ConcurrentHashMap.newKeySet();
    private final AtomicInteger edtCalls = new AtomicInteger();

    private boolean closed;

    /** Ids {@code 1..count}, contiguous: the shape a Phase 1 import produces. */
    static FakeSessionReader dense(int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = i + 1;
        }
        return withIds(ids);
    }

    /** An explicit id list, so a test can create the gaps the sparse path needs. */
    static FakeSessionReader withIds(long... ids) {
        FakeSessionReader reader = new FakeSessionReader();
        for (int i = 0; i < ids.length; i++) {
            reader.add(ids[i], i, DecodeStatus.OK);
        }
        return reader;
    }

    /** Adds one event with a decoded id and a distinctive payload. */
    void add(long id, long sequence, DecodeStatus status) {
        long hash = 0x1000 + id;
        byte[] payloadBytes = ("payload-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RawLocation location = new RawLocation(0, 32 + id * 64, payloadBytes.length);
        summaries.add(new EventSummary(
                id,
                1,
                sequence,
                status.hasEvent() ? 3 : 0,
                status.hasEvent() ? OptionalLong.of(hash) : OptionalLong.empty(),
                false,
                status.hasEvent() ? 2 : 0,
                status,
                false,
                status.hasEvent() ? OptionalLong.of(1_700_000_000_000_000L + id) : OptionalLong.empty(),
                1_700_000_000_500_000L + id,
                location,
                status.hasEvent() ? Optional.of("//target:" + id) : Optional.empty()));
        locations.put(id, location);
        if (status.hasEvent()) {
            identities.put(id, new EventIdentity(hash, 3, new byte[] {1, 2, 3}, "//target:" + id));
        }
        payloads.put(id, new RawPayload(payloadBytes, SourceKind.BEP_BINARY));
    }

    /**
     * Drops every event past {@code lastKeptId}, simulating a store that
     * changed underneath an already-open view.
     */
    void truncateTo(long lastKeptId) {
        summaries.removeIf(summary -> summary.id() > lastKeptId);
    }

    /**
     * Renumbers every event by {@code delta}, keeping the row count and the
     * ordering. Nothing real does this; it is the cheapest way to make a store
     * that passed the density probe stop agreeing with it, which is the
     * situation {@code reportPredictionMismatch} exists for.
     */
    void shiftIdsBy(long delta) {
        List<EventSummary> shifted = new ArrayList<>(summaries.size());
        Map<Long, RawLocation> newLocations = new HashMap<>();
        Map<Long, EventIdentity> newIdentities = new HashMap<>();
        Map<Long, RawPayload> newPayloads = new HashMap<>();
        for (EventSummary summary : summaries) {
            long id = summary.id();
            long moved = id + delta;
            shifted.add(new EventSummary(
                    moved, summary.streamId(), summary.sequence(), summary.eventType(),
                    summary.eventIdHash(), summary.lastMessage(), summary.childCount(),
                    summary.decodeStatus(), summary.hasUnknownFields(), summary.eventMicros(),
                    summary.receiveMicros(), summary.rawLocation(), summary.idDisplay()));
            newLocations.put(moved, locations.get(id));
            if (identities.containsKey(id)) {
                newIdentities.put(moved, identities.get(id));
            }
            newPayloads.put(moved, payloads.get(id));
        }
        summaries.clear();
        summaries.addAll(shifted);
        locations.clear();
        locations.putAll(newLocations);
        identities.clear();
        identities.putAll(newIdentities);
        payloads.clear();
        payloads.putAll(newPayloads);
    }

    /** Replaces one event's stored payload, for the undecodable-bytes tests. */
    void setPayload(long id, byte[] bytes, SourceKind kind) {
        payloads.put(id, new RawPayload(bytes, kind));
        RawLocation existing = locations.get(id);
        RawLocation moved = new RawLocation(existing.segment(), existing.offset(), bytes.length);
        locations.put(id, moved);
        // The summary carries the location too, exactly as a real page does.
        // Updating only the map would leave the fake describing a frame that
        // does not exist, which is a bug in the fake rather than in the code
        // under test.
        summaries.replaceAll(summary -> summary.id() != id ? summary : new EventSummary(
                summary.id(), summary.streamId(), summary.sequence(), summary.eventType(),
                summary.eventIdHash(), summary.lastMessage(), summary.childCount(),
                summary.decodeStatus(), summary.hasUnknownFields(), summary.eventMicros(),
                summary.receiveMicros(), moved, summary.idDisplay()));
    }

    int pageAfterCalls() {
        return pageAfterCalls.get();
    }

    int pageBeforeCalls() {
        return pageBeforeCalls.get();
    }

    int eventCalls() {
        return eventCalls.get();
    }

    int rawPayloadCalls() {
        return rawPayloadCalls.get();
    }

    /** How many calls arrived on the Swing event dispatch thread. Must stay 0. */
    int edtCalls() {
        return edtCalls.get();
    }

    Set<String> callingThreads() {
        return Set.copyOf(callingThreads);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public long eventCount() {
        record();
        return summaries.size();
    }

    @Override
    public List<EventSummary> pageAfter(OptionalLong afterId, int limit) {
        record();
        pageAfterCalls.incrementAndGet();
        List<EventSummary> page = new ArrayList<>(limit);
        for (EventSummary summary : summaries) {
            if (afterId.isPresent() && summary.id() <= afterId.getAsLong()) {
                continue;
            }
            page.add(summary);
            if (page.size() == limit) {
                break;
            }
        }
        return page;
    }

    @Override
    public List<EventSummary> pageBefore(OptionalLong beforeId, int limit) {
        record();
        pageBeforeCalls.incrementAndGet();
        List<EventSummary> matching = new ArrayList<>();
        for (EventSummary summary : summaries) {
            if (beforeId.isPresent() && summary.id() >= beforeId.getAsLong()) {
                continue;
            }
            matching.add(summary);
        }
        int from = Math.max(0, matching.size() - limit);
        return List.copyOf(matching.subList(from, matching.size()));
    }

    @Override
    public Optional<EventDetail> event(long id) {
        record();
        eventCalls.incrementAndGet();
        for (EventSummary summary : summaries) {
            if (summary.id() == id) {
                return Optional.of(new EventDetail(
                        summary,
                        locations.get(id),
                        Optional.ofNullable(identities.get(id))));
            }
        }
        return Optional.empty();
    }

    @Override
    public RawPayload rawPayload(RawLocation location) {
        record();
        rawPayloadCalls.incrementAndGet();
        for (Map.Entry<Long, RawLocation> entry : locations.entrySet()) {
            if (entry.getValue().equals(location)) {
                return payloads.get(entry.getKey());
            }
        }
        throw new SessionDataException("no journal frame at " + location);
    }

    @Override
    public void cancelRunningQuery() {
        // Nothing runs long enough here to cancel.
    }

    @Override
    public void close() {
        closed = true;
    }

    private void record() {
        callingThreads.add(Thread.currentThread().getName());
        if (SwingUtilities.isEventDispatchThread()) {
            edtCalls.incrementAndGet();
        }
    }
}

package com.holtherndon.bazelviz.ui.errors;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.ErrorQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/**
 * A session the Errors card can open, holding console events and nothing else
 * of substance.
 *
 * <p>Two readers, exactly as the real source hands out: an
 * {@link EntityReader} for the rows and a {@link SessionReader} for the journal
 * bytes behind them. Both count the thread they were called on, which is how
 * the suite asserts that no console read ever happens on the EDT.
 *
 * <p>A console event can be registered <em>with</em> its payload or without
 * one. Without is not a broken fixture: a redacted session keeps its database
 * and drops its {@code raw/} directory, so its rows name frames that are no
 * longer there, and the view has to say so rather than fail.
 */
final class FakeErrorSession implements SessionSource {

    private final List<ErrorQueries.ProgressRef> progress = new CopyOnWriteArrayList<>();
    private final Map<RawLocation, RawPayload> payloads = new ConcurrentHashMap<>();
    private final List<ErrorRow> aborted = new CopyOnWriteArrayList<>();

    private final AtomicInteger rawPayloadCalls = new AtomicInteger();
    private final AtomicInteger edtCalls = new AtomicInteger();
    private final AtomicInteger closedReaders = new AtomicInteger();

    private volatile boolean journalOpenFails;

    /** A console event whose bytes are in the journal, as a captured one is. */
    FakeErrorSession withConsoleEvent(long eventId, long sequence, String stderr, String stdout) {
        byte[] bytes = BuildEvent.newBuilder()
                .setProgress(Progress.newBuilder().setStderr(stderr).setStdout(stdout))
                .build()
                .toByteArray();
        RawLocation location = location(eventId, bytes.length);
        payloads.put(location, new RawPayload(bytes, SourceKind.BEP_BINARY));
        progress.add(ref(eventId, sequence, stderr, location));
        return this;
    }

    /** A console row whose journal is gone — a redacted session's shape. */
    FakeErrorSession withRedactedConsoleEvent(long eventId, long sequence, int stderrBytes) {
        progress.add(new ErrorQueries.ProgressRef(
                eventId, sequence, stderrBytes, 0, 4_096 + eventId * 512, 128));
        return this;
    }

    /** A session whose journal cannot be opened at all, not merely read. */
    FakeErrorSession withUnopenableJournal() {
        journalOpenFails = true;
        return this;
    }

    /** One abort, which is what a syntax-error build's Errors card is made of. */
    FakeErrorSession withAbortedTarget(String label) {
        aborted.add(new ErrorRow(
                ErrorRow.Kind.NOT_BUILT,
                aborted.size() + 1L,
                label,
                Optional.of("LOADING_FAILURE"),
                Optional.of("no such target " + label),
                OptionalLong.of(2)));
        return this;
    }

    int rawPayloadCalls() {
        return rawPayloadCalls.get();
    }

    /** Calls that arrived on the Swing event dispatch thread. Must stay 0. */
    int edtCalls() {
        return edtCalls.get();
    }

    int closedReaders() {
        return closedReaders.get();
    }

    private static ErrorQueries.ProgressRef ref(
            long eventId, long sequence, String stderr, RawLocation location) {
        return new ErrorQueries.ProgressRef(
                eventId,
                sequence,
                stderr.getBytes(StandardCharsets.UTF_8).length,
                location.segment(),
                location.offset(),
                location.length());
    }

    private static RawLocation location(long eventId, int length) {
        return new RawLocation(0, 4_096 + eventId * 512, length);
    }

    private void record() {
        if (SwingUtilities.isEventDispatchThread()) {
            edtCalls.incrementAndGet();
        }
    }

    @Override
    public SessionInfo info() {
        throw new UnsupportedOperationException("the Errors card never asks");
    }

    @Override
    public SessionReader openReader() {
        if (journalOpenFails) {
            throw new SessionDataException("this session has no raw/ directory");
        }
        return new Journal();
    }

    @Override
    public EntityReader openEntityReader() {
        return new Rows();
    }

    @Override
    public GraphQueries openGraphQueries() {
        throw new UnsupportedOperationException("the Errors card never asks");
    }

    @Override
    public MetricQueries openMetricQueries() {
        throw new UnsupportedOperationException("the Errors card never asks");
    }

    @Override
    public QueryReader openQueryReader() {
        throw new UnsupportedOperationException("the Errors card never asks");
    }

    @Override
    public Connection openTimelineConnection() {
        throw new UnsupportedOperationException("the Errors card never asks");
    }

    @Override
    public void close() {
        // The view closes the readers it opened; nothing else is held.
    }

    /** The journal half: verbatim bytes, or an honest failure for a row without any. */
    private final class Journal implements SessionReader {

        @Override
        public long eventCount() {
            record();
            return progress.size();
        }

        @Override
        public List<EventSummary> pageAfter(OptionalLong afterId, int limit) {
            throw new UnsupportedOperationException("the Errors card never pages events");
        }

        @Override
        public List<EventSummary> pageBefore(OptionalLong beforeId, int limit) {
            throw new UnsupportedOperationException("the Errors card never pages events");
        }

        @Override
        public Optional<EventDetail> event(long id) {
            throw new UnsupportedOperationException("the Errors card never asks for a detail row");
        }

        @Override
        public RawPayload rawPayload(RawLocation location) {
            record();
            rawPayloadCalls.incrementAndGet();
            RawPayload payload = payloads.get(location);
            if (payload == null) {
                throw new SessionDataException("no journal frame at segment " + location.segment()
                        + " offset " + location.offset());
            }
            return payload;
        }

        @Override
        public void cancelRunningQuery() {
            // Nothing here runs long enough to cancel.
        }

        @Override
        public void close() {
            closedReaders.incrementAndGet();
        }
    }

    /** The row half. Answers the three error questions and stubs the rest. */
    private final class Rows implements EntityReader {

        @Override
        public ErrorCounts errorCounts() {
            record();
            // The shape of finding X2's syntax-error build: no failed action,
            // no failed target, one abort, and the whole diagnostic on stderr.
            return new ErrorCounts(0, 0, aborted.size());
        }

        @Override
        public List<ErrorQueries.ProgressRef> progressOutputEvents(int limit) {
            record();
            return List.copyOf(progress.subList(0, Math.min(limit, progress.size())));
        }

        @Override
        public List<ErrorRow> abortedTargets(OptionalLong afterId, int limit) {
            record();
            List<ErrorRow> page = new ArrayList<>();
            for (ErrorRow row : aborted) {
                if (afterId.isPresent() && row.id() <= afterId.getAsLong()) {
                    continue;
                }
                page.add(row);
                if (page.size() == limit) {
                    break;
                }
            }
            return page;
        }

        @Override
        public List<ErrorQueries.ReasonCount> abortReasons() {
            record();
            return aborted.isEmpty()
                    ? List.of()
                    : List.of(new ErrorQueries.ReasonCount("LOADING_FAILURE", aborted.size()));
        }

        @Override
        public List<ErrorRow> failedActions(OptionalLong afterId, int limit) {
            record();
            return List.of();
        }

        @Override
        public List<ErrorRow> failedTargets(OptionalLong afterId, int limit) {
            record();
            return List.of();
        }

        @Override
        public void close() {
            closedReaders.incrementAndGet();
        }

        // ---- everything the Errors card never asks about --------------------

        @Override
        public OverviewSnapshot overview() {
            throw new UnsupportedOperationException("the Errors card never asks");
        }

        @Override
        public long actionCount() {
            return 0;
        }

        @Override
        public long actionCount(ActionFilter filter) {
            return 0;
        }

        @Override
        public List<ActionRow> firstActionPage(
                ActionFilter filter, ActionSort sort, boolean descending, int limit) {
            return List.of();
        }

        @Override
        public List<ActionRow> actionsAfter(
                ActionQueries.Anchor anchor,
                ActionFilter filter,
                ActionSort sort,
                boolean descending,
                int limit) {
            return List.of();
        }

        @Override
        public ActionQueries.Index actionIndex(
                ActionFilter filter, ActionSort sort, boolean descending, int pageSize) {
            return new ActionQueries.Index(0, List.of());
        }

        @Override
        public Optional<ActionRow> action(long id) {
            return Optional.empty();
        }

        @Override
        public List<ActionQueries.MnemonicCount> mnemonics() {
            return List.of();
        }

        @Override
        public List<TargetQueries.PackageSummary> packages() {
            return List.of();
        }

        @Override
        public List<TargetRow> targetsInPackage(String packagePath) {
            return List.of();
        }

        @Override
        public List<TargetRow> targetsByLabel(String label) {
            return List.of();
        }

        @Override
        public Optional<TargetRow> target(long id) {
            return Optional.empty();
        }

        @Override
        public List<TargetQueries.Tag> targetTags(long targetId) {
            return List.of();
        }

        @Override
        public List<TargetQueries.OutputGroup> outputGroups(long configuredTargetId) {
            return List.of();
        }

        @Override
        public long testCount() {
            return 0;
        }

        @Override
        public List<TestRow> firstTestPage(int limit) {
            return List.of();
        }

        @Override
        public List<TestRow> testsAfter(TestQueries.Anchor anchor, int limit) {
            return List.of();
        }

        @Override
        public TestQueries.Index testIndex(int pageSize) {
            return new TestQueries.Index(0, List.of());
        }

        @Override
        public Optional<TestRow> test(long id) {
            return Optional.empty();
        }

        @Override
        public List<TestAttemptRow> testAttempts(long testId) {
            return List.of();
        }

        @Override
        public List<TestQueries.TestLog> testLogs(long testId) {
            return List.of();
        }

        @Override
        public List<AttemptRow> attemptsForAction(long actionId) {
            return List.of();
        }

        @Override
        public List<AttemptRow> attemptsForLabel(String label) {
            return List.of();
        }

        @Override
        public EnrichmentQueries.Coverage enrichmentCoverage() {
            return new EnrichmentQueries.Coverage(0, 0, 0, 0, 0, Map.of());
        }

        @Override
        public List<EnrichmentQueries.Phase> buildPhases() {
            return List.of();
        }

        @Override
        public List<EnrichmentQueries.CriticalPathComponent> bazelCriticalPath() {
            return List.of();
        }

        @Override
        public List<EnrichmentQueries.RunnerCount> runnerCounts() {
            return List.of();
        }

        @Override
        public Optional<ProfileAnchor> profileAnchor() {
            return Optional.empty();
        }

        @Override
        public List<EnrichmentTask> enrichmentTasks() {
            return List.of();
        }

        @Override
        public void cancelRunningQuery() {
            // Nothing runs long enough to need stopping.
        }
    }
}

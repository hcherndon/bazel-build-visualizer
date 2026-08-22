package com.holtherndon.bazelviz.testsupport.bep;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import java.util.ArrayList;
import java.util.List;

/**
 * The expected structure of a {@link SyntheticBepStream}, derived from the
 * documented layout rules rather than by reading the generator's output.
 *
 * <h2>Why this is a separate implementation</h2>
 *
 * A test that checks a parser against structure recovered from the same code
 * that produced the bytes proves only that the generator is self-consistent. So
 * this class re-derives event count, payload-case order, id-case order and the
 * full announcement graph from the layout arithmetic alone, with no call into
 * {@link SyntheticBepStream}'s message building. When the two disagree, one of
 * them is wrong and the fixture's own test says so.
 *
 * <p>{@link #payloadCaseAt(int)} and {@link #idCaseAt(int)} are O(1) and
 * allocate nothing. The list-returning methods materialize one small value per
 * event and are fixture-scale conveniences — do not call {@link #payloadCases()}
 * on a 200,000-event stream and expect it to be free.
 */
public final class BepFixtureCatalog {

    /**
     * One announced parent → child relationship.
     *
     * @param parentIndex index of the event whose {@code children} list carries the id
     * @param ordinal position within that {@code children} list
     * @param childIndex index of the event that later carries the id, or
     *     {@link #CHILD_NEVER_ARRIVES} when the child is announced and never emitted
     * @param childIdCase which {@code BuildEventId} variant the announced id is
     */
    public record AnnouncedEdge(int parentIndex, int ordinal, int childIndex, BuildEventId.IdCase childIdCase) {

        public boolean childArrives() {
            return childIndex != CHILD_NEVER_ARRIVES;
        }
    }

    /** Sentinel {@code childIndex} for a child that is announced but never emitted (plan 17.11). */
    public static final int CHILD_NEVER_ARRIVES = -1;

    private final long seed;
    private final int eventCount;
    private final boolean announceMissingTargetSummary;
    private final int targetUnits;
    private final int paddingProgress;

    private BepFixtureCatalog(SyntheticBepStream.Options options) {
        this.seed = options.seed();
        this.eventCount = options.eventCount();
        this.announceMissingTargetSummary = options.announceMissingTargetSummary();
        int body = eventCount - SyntheticBepStream.MIN_EVENT_COUNT;
        this.targetUnits = body / SyntheticBepStream.EVENTS_PER_TARGET_UNIT;
        this.paddingProgress = body % SyntheticBepStream.EVENTS_PER_TARGET_UNIT;
    }

    public static BepFixtureCatalog of(SyntheticBepStream.Options options) {
        return new BepFixtureCatalog(options);
    }

    public static BepFixtureCatalog of(SyntheticBepStream stream) {
        return new BepFixtureCatalog(stream.options());
    }

    public static BepFixtureCatalog of(int eventCount) {
        return new BepFixtureCatalog(SyntheticBepStream.Options.of(eventCount));
    }

    public long seed() {
        return seed;
    }

    public int eventCount() {
        return eventCount;
    }

    public int targetUnitCount() {
        return targetUnits;
    }

    public int paddingProgressCount() {
        return paddingProgress;
    }

    /** Progress-payload events in the stream: prologue + one per target unit + padding. */
    public int progressEventCount() {
        return 1 + targetUnits + paddingProgress;
    }

    /** Index of the single event with {@code last_message = true}. */
    public int lastMessageIndex() {
        return eventCount - 1;
    }

    private int testUnitStart() {
        return SyntheticBepStream.PROLOGUE_EVENTS
                + SyntheticBepStream.EVENTS_PER_TARGET_UNIT * targetUnits;
    }

    private int paddingStart() {
        return testUnitStart() + SyntheticBepStream.TEST_UNIT_EVENTS;
    }

    private int epilogueStart() {
        return paddingStart() + paddingProgress;
    }

    /** Index of the progress event whose {@code opaque_count} is {@code opaque}. */
    public int progressEventIndex(int opaque) {
        if (opaque < 0 || opaque >= progressEventCount()) {
            throw new IndexOutOfBoundsException(
                    "opaque " + opaque + " outside [0, " + progressEventCount() + ")");
        }
        if (opaque == 0) {
            return 1;
        }
        if (opaque <= targetUnits) {
            return SyntheticBepStream.PROLOGUE_EVENTS
                    + SyntheticBepStream.EVENTS_PER_TARGET_UNIT * (opaque - 1) + 1;
        }
        return paddingStart() + (opaque - targetUnits - 1);
    }

    /** Payload case expected at {@code index}. O(1). */
    public BuildEvent.PayloadCase payloadCaseAt(int index) {
        checkIndex(index);
        if (index < SyntheticBepStream.PROLOGUE_EVENTS) {
            return switch (index) {
                case 0 -> BuildEvent.PayloadCase.STARTED;
                case 1 -> BuildEvent.PayloadCase.PROGRESS;
                case 2 -> BuildEvent.PayloadCase.OPTIONS_PARSED;
                case 3, 4 -> BuildEvent.PayloadCase.STRUCTURED_COMMAND_LINE;
                case 5 -> BuildEvent.PayloadCase.WORKSPACE_STATUS;
                case 6 -> BuildEvent.PayloadCase.CONFIGURATION;
                default -> BuildEvent.PayloadCase.EXPANDED;
            };
        }
        if (index < testUnitStart()) {
            return switch ((index - SyntheticBepStream.PROLOGUE_EVENTS)
                    % SyntheticBepStream.EVENTS_PER_TARGET_UNIT) {
                case 0 -> BuildEvent.PayloadCase.CONFIGURED;
                case 1 -> BuildEvent.PayloadCase.PROGRESS;
                case 2 -> BuildEvent.PayloadCase.NAMED_SET_OF_FILES;
                case 3 -> BuildEvent.PayloadCase.ACTION;
                default -> BuildEvent.PayloadCase.COMPLETED;
            };
        }
        if (index < paddingStart()) {
            return switch (index - testUnitStart()) {
                case 0 -> BuildEvent.PayloadCase.CONFIGURED;
                case 1 -> BuildEvent.PayloadCase.NAMED_SET_OF_FILES;
                case 2 -> BuildEvent.PayloadCase.COMPLETED;
                case 3 -> BuildEvent.PayloadCase.TEST_RESULT;
                default -> BuildEvent.PayloadCase.TEST_SUMMARY;
            };
        }
        if (index < epilogueStart()) {
            return BuildEvent.PayloadCase.PROGRESS;
        }
        return switch (index - epilogueStart()) {
            case 0 -> BuildEvent.PayloadCase.FINISHED;
            case 1 -> BuildEvent.PayloadCase.BUILD_METRICS;
            default -> BuildEvent.PayloadCase.BUILD_TOOL_LOGS;
        };
    }

    /** Event-id case expected at {@code index}. O(1). */
    public BuildEventId.IdCase idCaseAt(int index) {
        checkIndex(index);
        if (index < SyntheticBepStream.PROLOGUE_EVENTS) {
            return switch (index) {
                case 0 -> BuildEventId.IdCase.STARTED;
                case 1 -> BuildEventId.IdCase.PROGRESS;
                case 2 -> BuildEventId.IdCase.OPTIONS_PARSED;
                case 3, 4 -> BuildEventId.IdCase.STRUCTURED_COMMAND_LINE;
                case 5 -> BuildEventId.IdCase.WORKSPACE_STATUS;
                case 6 -> BuildEventId.IdCase.CONFIGURATION;
                default -> BuildEventId.IdCase.PATTERN;
            };
        }
        if (index < testUnitStart()) {
            return switch ((index - SyntheticBepStream.PROLOGUE_EVENTS)
                    % SyntheticBepStream.EVENTS_PER_TARGET_UNIT) {
                case 0 -> BuildEventId.IdCase.TARGET_CONFIGURED;
                case 1 -> BuildEventId.IdCase.PROGRESS;
                case 2 -> BuildEventId.IdCase.NAMED_SET;
                case 3 -> BuildEventId.IdCase.ACTION_COMPLETED;
                default -> BuildEventId.IdCase.TARGET_COMPLETED;
            };
        }
        if (index < paddingStart()) {
            return switch (index - testUnitStart()) {
                case 0 -> BuildEventId.IdCase.TARGET_CONFIGURED;
                case 1 -> BuildEventId.IdCase.NAMED_SET;
                case 2 -> BuildEventId.IdCase.TARGET_COMPLETED;
                case 3 -> BuildEventId.IdCase.TEST_RESULT;
                default -> BuildEventId.IdCase.TEST_SUMMARY;
            };
        }
        if (index < epilogueStart()) {
            return BuildEventId.IdCase.PROGRESS;
        }
        return switch (index - epilogueStart()) {
            case 0 -> BuildEventId.IdCase.BUILD_FINISHED;
            case 1 -> BuildEventId.IdCase.BUILD_METRICS;
            default -> BuildEventId.IdCase.BUILD_TOOL_LOGS;
        };
    }

    /** Expected payload cases, in stream order. Fixture-scale convenience. */
    public List<BuildEvent.PayloadCase> payloadCases() {
        List<BuildEvent.PayloadCase> out = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            out.add(payloadCaseAt(i));
        }
        return List.copyOf(out);
    }

    /** Expected event-id cases, in stream order. Fixture-scale convenience. */
    public List<BuildEventId.IdCase> idCases() {
        List<BuildEventId.IdCase> out = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            out.add(idCaseAt(i));
        }
        return List.copyOf(out);
    }

    /**
     * Every announced parent → child pair, in the order the announcing events
     * appear and, within an event, in {@code children} order. Fixture-scale
     * convenience.
     */
    public List<AnnouncedEdge> announcedEdges() {
        List<AnnouncedEdge> edges = new ArrayList<>();
        int epilogue = epilogueStart();
        int testStart = testUnitStart();
        int progressCount = progressEventCount();

        // started
        edges.add(new AnnouncedEdge(0, 0, progressEventIndex(0), BuildEventId.IdCase.PROGRESS));
        edges.add(new AnnouncedEdge(0, 1, 2, BuildEventId.IdCase.OPTIONS_PARSED));
        edges.add(new AnnouncedEdge(0, 2, 3, BuildEventId.IdCase.STRUCTURED_COMMAND_LINE));
        edges.add(new AnnouncedEdge(0, 3, 4, BuildEventId.IdCase.STRUCTURED_COMMAND_LINE));
        edges.add(new AnnouncedEdge(0, 4, 5, BuildEventId.IdCase.WORKSPACE_STATUS));
        edges.add(new AnnouncedEdge(0, 5, 6, BuildEventId.IdCase.CONFIGURATION));
        edges.add(new AnnouncedEdge(0, 6, 7, BuildEventId.IdCase.PATTERN));
        edges.add(new AnnouncedEdge(0, 7, epilogue, BuildEventId.IdCase.BUILD_FINISHED));
        edges.add(new AnnouncedEdge(0, 8, epilogue + 1, BuildEventId.IdCase.BUILD_METRICS));

        // progress chain: each progress announces the next, the last announces nothing
        for (int opaque = 0; opaque < progressCount - 1; opaque++) {
            edges.add(new AnnouncedEdge(
                    progressEventIndex(opaque), 0, progressEventIndex(opaque + 1), BuildEventId.IdCase.PROGRESS));
        }

        // pattern announces every configured target, ordinary targets first then the test
        for (int u = 0; u < targetUnits; u++) {
            edges.add(new AnnouncedEdge(
                    7,
                    u,
                    SyntheticBepStream.PROLOGUE_EVENTS + SyntheticBepStream.EVENTS_PER_TARGET_UNIT * u,
                    BuildEventId.IdCase.TARGET_CONFIGURED));
        }
        edges.add(new AnnouncedEdge(7, targetUnits, testStart, BuildEventId.IdCase.TARGET_CONFIGURED));

        // each target unit's target_configured announces its named set, action and completion
        for (int u = 0; u < targetUnits; u++) {
            int base = SyntheticBepStream.PROLOGUE_EVENTS + SyntheticBepStream.EVENTS_PER_TARGET_UNIT * u;
            edges.add(new AnnouncedEdge(base, 0, base + 2, BuildEventId.IdCase.NAMED_SET));
            edges.add(new AnnouncedEdge(base, 1, base + 3, BuildEventId.IdCase.ACTION_COMPLETED));
            edges.add(new AnnouncedEdge(base, 2, base + 4, BuildEventId.IdCase.TARGET_COMPLETED));
        }

        // the test target's target_configured announces its set, completion, result and summary
        edges.add(new AnnouncedEdge(testStart, 0, testStart + 1, BuildEventId.IdCase.NAMED_SET));
        edges.add(new AnnouncedEdge(testStart, 1, testStart + 2, BuildEventId.IdCase.TARGET_COMPLETED));
        edges.add(new AnnouncedEdge(testStart, 2, testStart + 3, BuildEventId.IdCase.TEST_RESULT));
        edges.add(new AnnouncedEdge(testStart, 3, testStart + 4, BuildEventId.IdCase.TEST_SUMMARY));
        if (announceMissingTargetSummary) {
            edges.add(new AnnouncedEdge(testStart, 4, CHILD_NEVER_ARRIVES, BuildEventId.IdCase.TARGET_SUMMARY));
        }

        // build_finished announces the final build_tool_logs event
        edges.add(new AnnouncedEdge(epilogue, 0, epilogue + 2, BuildEventId.IdCase.BUILD_TOOL_LOGS));

        edges.sort((a, b) -> a.parentIndex() != b.parentIndex()
                ? Integer.compare(a.parentIndex(), b.parentIndex())
                : Integer.compare(a.ordinal(), b.ordinal()));
        return List.copyOf(edges);
    }

    /** Announced children that this stream never emits; empty unless the option is on. */
    public List<AnnouncedEdge> announcedButNeverArrived() {
        return announcedEdges().stream().filter(e -> !e.childArrives()).toList();
    }

    /** Total announced children, including any that never arrive. */
    public int announcedChildCount() {
        return announcedEdges().size();
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= eventCount) {
            throw new IndexOutOfBoundsException("index " + index + " outside [0, " + eventCount + ")");
        }
    }
}

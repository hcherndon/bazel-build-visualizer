package com.holtherndon.bazelviz.enrich.profile;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Reads a Bazel JSON trace profile a token at a time.
 *
 * <h2>Streaming, because the file is not small</h2>
 *
 * <p>A profile grows with everything the build did, not just its actions —
 * Skyframe evaluation, package loading, module resolution. Measured on a
 * six-target workspace it was already 1,613 events on Bazel 9.2.0, and plan
 * 4.1 calls it "potentially very large". So this reads with a pull parser and
 * emits commands as it goes; nothing accumulates but the phase markers, which
 * need their successor's start to get an end (P2).
 *
 * <h2>What it keeps</h2>
 *
 * <p>Not everything. The plan says "normalize build phases and selected spans",
 * and most of a profile is bookkeeping nobody asked about. Kept: phase markers,
 * action spans that can be attributed, counter series, thread names, and
 * Bazel's own critical path. Everything else is counted and skipped, and the
 * count is reported so "selected" does not quietly become "some".
 */
public final class ProfileParser {

    /** Categories whose spans carry an attributable primary output (P4). */
    static final String ACTION_PROCESSING = "action processing";

    /** Phase markers, which are instant events (P2). */
    static final String BUILD_PHASE_MARKER = "build phase marker";

    /** Bazel's own critical path, kept unjoined (P5). */
    static final String CRITICAL_PATH_COMPONENT = "critical path component";

    private final Consumer<EnrichmentCommand> sink;
    private long skippedEvents;
    private long keptEvents;

    public ProfileParser(Consumer<EnrichmentCommand> sink) {
        this.sink = sink;
    }

    /**
     * Reads the whole profile.
     *
     * <p>{@code otherData} may appear before or after {@code traceEvents}; both
     * orders were observed. So the header is emitted whenever it is reached,
     * and the writer tolerates spans arriving before the anchor.
     */
    public void parse(Reader source) throws IOException {
        try (JsonReader reader = new JsonReader(source)) {
            reader.setStrictness(com.google.gson.Strictness.LENIENT);
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "otherData" -> readOtherData(reader);
                    case "traceEvents" -> readTraceEvents(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
        }
    }

    // ------------------------------------------------------------- otherData

    private void readOtherData(JsonReader reader) throws IOException {
        String buildId = null;
        String bazelVersion = null;
        String outputBase = null;
        String anchorKey = null;
        long anchorMillis = 0;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "build_id" -> buildId = reader.nextString();
                case "bazel_version" -> bazelVersion = reader.nextString();
                case "output_base" -> outputBase = reader.nextString();
                // The two spellings of the anchor. They do NOT mean the same
                // thing, which is the whole reason ProfileAnchor exists (P1).
                case "profile_start_ts", "profile_finish_ts" -> {
                    anchorKey = name;
                    anchorMillis = reader.nextLong();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        sink.accept(new EnrichmentCommand.ProfileHeaderSeen(
                Optional.ofNullable(buildId),
                Optional.ofNullable(bazelVersion),
                Optional.ofNullable(outputBase),
                anchorFor(anchorKey, anchorMillis)));
    }

    /**
     * The anchor, with what the key actually meant.
     *
     * <p>{@code profile_finish_ts} on Bazel 6.5.0 and 7.6.1 holds the START,
     * floored to the whole second: measured 895 ms and 756 ms before the BEP's
     * {@code buildStarted}, while the trace itself spans 1.2–1.5 s, so it
     * cannot be a finish. {@code profile_start_ts} on 8.4.1+ is exact and
     * matches {@code otherData.date} to the millisecond (P1).
     */
    private static ProfileAnchor anchorFor(String key, long millis) {
        if (key == null) {
            return ProfileAnchor.absent();
        }
        long micros = millis * 1_000L;
        return key.equals("profile_start_ts")
                ? ProfileAnchor.exact(micros, key)
                : ProfileAnchor.flooredStart(micros, key);
    }

    // ----------------------------------------------------------- traceEvents

    private void readTraceEvents(JsonReader reader) throws IOException {
        List<EnrichmentCommand.PhaseMarkerSeen> phases = new ArrayList<>();
        int criticalPathOrdinal = 0;

        reader.beginArray();
        while (reader.hasNext()) {
            Event event = readEvent(reader);
            if (event.category != null && event.category.equals(BUILD_PHASE_MARKER)) {
                phases.add(new EnrichmentCommand.PhaseMarkerSeen(
                        phases.size(), event.name, event.timestamp));
                keptEvents++;
                continue;
            }
            if (event.category != null && event.category.equals(CRITICAL_PATH_COMPONENT)) {
                sink.accept(new EnrichmentCommand.CriticalPathComponentSeen(
                        criticalPathOrdinal++, event.name,
                        OptionalLong.of(event.timestamp), event.duration,
                        event.threadId));
                keptEvents++;
                continue;
            }
            switch (event.phase == null ? "" : event.phase) {
                case "M" -> {
                    if (event.name.equals("thread_name") && event.threadId.isPresent()
                            && event.argName != null) {
                        sink.accept(new EnrichmentCommand.ThreadNamed(
                                event.threadId.getAsLong(), event.argName, OptionalInt.empty()));
                        keptEvents++;
                    } else {
                        skippedEvents++;
                    }
                }
                case "C" -> {
                    if (event.counterValue.isPresent()) {
                        sink.accept(new EnrichmentCommand.CounterSampled(
                                event.name, event.timestamp, event.counterValue.getAsDouble()));
                        keptEvents++;
                    } else {
                        skippedEvents++;
                    }
                }
                case "X" -> {
                    if (ACTION_PROCESSING.equals(event.category)) {
                        sink.accept(new EnrichmentCommand.SpanObserved(
                                event.category, event.name, event.threadId, event.timestamp,
                                event.duration, Optional.ofNullable(event.out),
                                emptyToAbsent(event.argTarget),
                                emptyToAbsent(event.argMnemonic)));
                        keptEvents++;
                    } else {
                        skippedEvents++;
                    }
                }
                default -> skippedEvents++;
            }
        }
        reader.endArray();

        phases.forEach(sink::accept);
    }

    /**
     * One trace event, read into locals rather than a map.
     *
     * <p>A map per event would allocate several objects for each of a profile's
     * hundreds of thousands of events, nearly all of which are then discarded.
     */
    private static final class Event {
        String phase;
        String category;
        String name = "";
        long timestamp;
        OptionalLong duration = OptionalLong.empty();
        OptionalLong threadId = OptionalLong.empty();
        String out;
        String argTarget;
        String argMnemonic;
        String argName;
        java.util.OptionalDouble counterValue = java.util.OptionalDouble.empty();
    }

    private Event readEvent(JsonReader reader) throws IOException {
        Event event = new Event();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "ph" -> event.phase = reader.nextString();
                case "cat" -> event.category = reader.nextString();
                case "name" -> event.name = reader.nextString();
                // ts is legitimately negative: Launch Blaze starts before zero
                // because zero is "Initialize command" (P3).
                case "ts" -> event.timestamp = reader.nextLong();
                case "dur" -> {
                    long duration = reader.nextLong();
                    event.duration = duration == 0
                            ? OptionalLong.empty() : OptionalLong.of(duration);
                }
                case "tid" -> event.threadId = OptionalLong.of(reader.nextLong());
                case "out" -> event.out = reader.nextString();
                case "args" -> readArgs(reader, event);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return event;
    }

    private void readArgs(JsonReader reader, Event event) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return;
        }
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            switch (key) {
                case "target" -> event.argTarget = reader.nextString();
                case "mnemonic" -> event.argMnemonic = reader.nextString();
                case "name" -> event.argName = reader.nextString();
                default -> {
                    // Counter events carry their value under the series name,
                    // which varies, so the first number in args is taken.
                    if (event.counterValue.isEmpty() && reader.peek() == JsonToken.NUMBER) {
                        event.counterValue =
                                java.util.OptionalDouble.of(reader.nextDouble());
                    } else {
                        reader.skipValue();
                    }
                }
            }
        }
        reader.endObject();
    }

    private static Optional<String> emptyToAbsent(String value) {
        // args.target is present but empty on 7.6.1 for the workspace-status
        // action and populated on 9.2.0. An empty string is not a target (P4).
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Events read and not kept. Reported so "selected spans" stays honest. */
    public long skippedEvents() {
        return skippedEvents;
    }

    /** Events turned into commands. */
    public long keptEvents() {
        return keptEvents;
    }
}

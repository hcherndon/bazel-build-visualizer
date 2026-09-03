package com.holtherndon.bazelviz.storage.enrich;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Turns profile commands into rows.
 *
 * <h2>Two things it will not do</h2>
 *
 * <p>It does not join Bazel's critical path to actions. The components name
 * themselves with a progress message and nothing else (P5), and ADR-009 wants
 * Bazel's own answer preserved as Bazel's regardless.
 *
 * <p>It does not write an anchor without saying what the anchor means. On
 * Bazel 6.5.0 and 7.6.1 the profile's absolute reference is floored to the
 * whole second and published under a key that calls it a finish, so the
 * meaning and the uncertainty are columns beside the value rather than
 * knowledge a reader is expected to have.
 */
public final class ProfileWriter implements AutoCloseable {

    private static final String INSERT_METADATA =
            "INSERT INTO profile_metadata (id, task_id, build_id, build_id_matches,"
                    + " bazel_version, output_base, anchor_micros, anchor_source_key,"
                    + " anchor_meaning, uncertainty_micros, trace_min_micros, trace_max_micros)"
                    + " VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (id) DO UPDATE SET"
                    + " task_id = excluded.task_id, build_id = excluded.build_id,"
                    + " build_id_matches = excluded.build_id_matches,"
                    + " bazel_version = excluded.bazel_version,"
                    + " output_base = excluded.output_base,"
                    + " anchor_micros = excluded.anchor_micros,"
                    + " anchor_source_key = excluded.anchor_source_key,"
                    + " anchor_meaning = excluded.anchor_meaning,"
                    + " uncertainty_micros = excluded.uncertainty_micros,"
                    + " trace_min_micros = excluded.trace_min_micros,"
                    + " trace_max_micros = excluded.trace_max_micros";
    private static final String UPDATE_TRACE_RANGE =
            "UPDATE profile_metadata SET trace_min_micros = ?, trace_max_micros = ? WHERE id = 1";
    private static final String INSERT_THREAD =
            "INSERT INTO profile_threads (thread_id, name, sort_index) VALUES (?, ?, ?)"
                    + " ON CONFLICT (thread_id) DO UPDATE SET name = excluded.name,"
                    + " sort_index = excluded.sort_index";
    private static final String INSERT_PHASE =
            "INSERT INTO build_phases (ordinal, name, start_micros, end_micros, end_is_derived)"
                    + " VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (ordinal) DO UPDATE SET name = excluded.name,"
                    + " start_micros = excluded.start_micros, end_micros = excluded.end_micros,"
                    + " end_is_derived = excluded.end_is_derived";
    private static final String INSERT_SPAN =
            "INSERT INTO profile_spans (category, name, thread_id, start_micros,"
                    + " duration_micros, primary_output, action_id, label_id, mnemonic_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?,"
                    + " (SELECT id FROM actions WHERE primary_output = ?),"
                    + " (SELECT id FROM labels WHERE value = ?),"
                    + " (SELECT id FROM mnemonics WHERE value = ?))";
    private static final String INSERT_COUNTER =
            "INSERT INTO profile_counters (series, at_micros, value) VALUES (?, ?, ?)";
    private static final String INSERT_CRITICAL_PATH =
            "INSERT INTO bazel_critical_path (ordinal, description, start_micros,"
                    + " duration_micros, thread_id) VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (ordinal) DO UPDATE SET"
                    + " description = excluded.description,"
                    + " start_micros = excluded.start_micros,"
                    + " duration_micros = excluded.duration_micros,"
                    + " thread_id = excluded.thread_id";
    private static final String INSERT_LABEL =
            "INSERT INTO labels (value) VALUES (?) ON CONFLICT (value) DO NOTHING";
    private static final String INSERT_MNEMONIC =
            "INSERT INTO mnemonics (value) VALUES (?) ON CONFLICT (value) DO NOTHING";

    private final Connection connection;
    private final long taskId;
    private final Optional<String> sessionBuildId;

    private final PreparedStatement insertThread;
    private final PreparedStatement insertSpan;
    private final PreparedStatement insertCounter;
    private final PreparedStatement insertCriticalPath;
    private final PreparedStatement insertLabel;
    private final PreparedStatement insertMnemonic;

    /**
     * Phase markers, held until the end.
     *
     * <p>A marker is an instant event, so a phase's end is the next one's
     * start (P2). There are five to seven of them, so holding them costs
     * nothing; every other command is written as it arrives.
     */
    private final List<EnrichmentCommand.PhaseMarkerSeen> phases = new ArrayList<>();

    /**
     * Rows allowed to accumulate in one JDBC batch.
     *
     * <p>The first version of this class added every span, counter and thread
     * to a batch and executed all of them in {@link #finish()}. That is
     * correct for the fifteen action spans a six-target build produces and
     * indefensible for a real one: a profile grows with everything the build
     * did, and plan 19.4 requires bounded memory of every import path. The
     * driver holds each batched statement's parameters until execution.
     */
    private static final int BATCH = 5_000;

    private int pendingSpans;
    private int pendingCounters;
    private int pendingThreads;
    private int pendingCriticalPathComponents;
    private long spansWritten;
    private long attributedSpans;
    private long traceMin = Long.MAX_VALUE;
    private long traceMax = Long.MIN_VALUE;
    private Optional<ProfileAnchor> anchor = Optional.empty();
    private Optional<Boolean> buildIdMatches = Optional.empty();

    public ProfileWriter(Connection connection, long taskId, Optional<String> sessionBuildId)
            throws SQLException {
        this.connection = connection;
        this.taskId = taskId;
        this.sessionBuildId = sessionBuildId;
        this.insertThread = connection.prepareStatement(INSERT_THREAD);
        this.insertSpan = connection.prepareStatement(INSERT_SPAN);
        this.insertCounter = connection.prepareStatement(INSERT_COUNTER);
        this.insertCriticalPath = connection.prepareStatement(INSERT_CRITICAL_PATH);
        this.insertLabel = connection.prepareStatement(INSERT_LABEL);
        this.insertMnemonic = connection.prepareStatement(INSERT_MNEMONIC);
    }

    /** Applies one command. */
    public void apply(EnrichmentCommand command) throws SQLException {
        switch (command) {
            case EnrichmentCommand.ProfileHeaderSeen header -> writeHeader(header);
            case EnrichmentCommand.ThreadNamed thread -> writeThread(thread);
            case EnrichmentCommand.PhaseMarkerSeen phase -> phases.add(phase);
            case EnrichmentCommand.SpanObserved span -> writeSpan(span);
            case EnrichmentCommand.CounterSampled counter -> writeCounter(counter);
            case EnrichmentCommand.CriticalPathComponentSeen component ->
                    writeCriticalPath(component);
            default -> {
                // Execution-log commands go to AttemptWriter.
            }
        }
    }

    /**
     * Writes what had to wait for the whole file: the phases, whose ends are
     * the next marker's start, and the trace's extent.
     */
    public void finish() throws SQLException {
        writePhases();
        if (traceMin != Long.MAX_VALUE && anchor.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_TRACE_RANGE)) {
                statement.setLong(1, traceMin);
                statement.setLong(2, traceMax);
                statement.executeUpdate();
            }
        }
        // Whatever is left below the batch threshold.
        insertThread.executeBatch();
        insertSpan.executeBatch();
        insertCounter.executeBatch();
        insertCriticalPath.executeBatch();
        pendingThreads = 0;
        pendingSpans = 0;
        pendingCounters = 0;
        pendingCriticalPathComponents = 0;
    }

    private void writeHeader(EnrichmentCommand.ProfileHeaderSeen header) throws SQLException {
        anchor = Optional.of(header.anchor());
        buildIdMatches = sessionBuildId.isPresent() && header.buildId().isPresent()
                ? Optional.of(sessionBuildId.get().equals(header.buildId().get()))
                : Optional.empty();

        try (PreparedStatement statement = connection.prepareStatement(INSERT_METADATA)) {
            int i = 1;
            statement.setLong(i++, taskId);
            setNullableString(statement, i++, header.buildId());
            if (buildIdMatches.isPresent()) {
                statement.setInt(i++, buildIdMatches.get() ? 1 : 0);
            } else {
                // Unknown, not false: one of the two ids is missing, and
                // recording a zero would read as "checked, and it is wrong".
                statement.setNull(i++, Types.INTEGER);
            }
            setNullableString(statement, i++, header.bazelVersion());
            setNullableString(statement, i++, header.outputBase());
            ProfileAnchor value = header.anchor();
            if (value.canPlaceAbsolutely()) {
                statement.setLong(i++, value.epochMicros());
            } else {
                statement.setNull(i++, Types.INTEGER);
            }
            statement.setString(i++, value.sourceKey());
            statement.setString(i++, value.meaning().name());
            statement.setLong(i++, value.uncertaintyMicros());
            statement.setNull(i++, Types.INTEGER);
            statement.setNull(i, Types.INTEGER);
            statement.executeUpdate();
        }
    }

    private void writeThread(EnrichmentCommand.ThreadNamed thread) throws SQLException {
        insertThread.setLong(1, thread.threadId());
        insertThread.setString(2, thread.name());
        if (thread.sortIndex().isPresent()) {
            insertThread.setInt(3, thread.sortIndex().getAsInt());
        } else {
            insertThread.setNull(3, Types.INTEGER);
        }
        insertThread.addBatch();
        if (++pendingThreads >= BATCH) {
            insertThread.executeBatch();
            pendingThreads = 0;
        }
    }

    /**
     * Phases, with ends derived from the next marker.
     *
     * <p>{@code end_is_derived} is set for every one of them because every one
     * of them is: the profile states starts and never ends. The last phase has
     * no successor and gets a null end rather than the trace's maximum, which
     * would be a boundary nothing measured.
     */
    private void writePhases() throws SQLException {
        if (phases.isEmpty()) {
            return;
        }
        phases.sort(java.util.Comparator.comparingLong(
                EnrichmentCommand.PhaseMarkerSeen::startMicros));
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PHASE)) {
            for (int i = 0; i < phases.size(); i++) {
                EnrichmentCommand.PhaseMarkerSeen phase = phases.get(i);
                statement.setInt(1, i);
                statement.setString(2, phase.name());
                statement.setLong(3, phase.startMicros());
                if (i + 1 < phases.size()) {
                    statement.setLong(4, phases.get(i + 1).startMicros());
                } else {
                    statement.setNull(4, Types.INTEGER);
                }
                statement.setInt(5, 1);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writeSpan(EnrichmentCommand.SpanObserved span) throws SQLException {
        note(span.startMicros(), span.durationMicros());
        intern(insertLabel, span.targetLabel());
        intern(insertMnemonic, span.mnemonic());

        int i = 1;
        insertSpan.setString(i++, span.category());
        insertSpan.setString(i++, span.name());
        if (span.threadId().isPresent()) {
            insertSpan.setLong(i++, span.threadId().getAsLong());
        } else {
            insertSpan.setNull(i++, Types.INTEGER);
        }
        insertSpan.setLong(i++, span.startMicros());
        if (span.durationMicros().isPresent()) {
            insertSpan.setLong(i++, span.durationMicros().getAsLong());
        } else {
            insertSpan.setNull(i++, Types.INTEGER);
        }
        setNullableString(insertSpan, i++, span.primaryOutput());
        // The action lookup uses the same value; a span with no primary output
        // gets a null action rather than matching an action called null.
        setNullableString(insertSpan, i++, span.primaryOutput());
        setNullableString(insertSpan, i++, span.targetLabel());
        setNullableString(insertSpan, i, span.mnemonic());
        insertSpan.addBatch();
        if (++pendingSpans >= BATCH) {
            insertSpan.executeBatch();
            pendingSpans = 0;
        }
        spansWritten++;
        if (span.primaryOutput().isPresent()) {
            attributedSpans++;
        }
    }

    private void writeCounter(EnrichmentCommand.CounterSampled counter) throws SQLException {
        note(counter.atMicros(), OptionalLong.empty());
        insertCounter.setString(1, counter.series());
        insertCounter.setLong(2, counter.atMicros());
        insertCounter.setDouble(3, counter.value());
        insertCounter.addBatch();
        if (++pendingCounters >= BATCH) {
            insertCounter.executeBatch();
            pendingCounters = 0;
        }
    }

    private void writeCriticalPath(EnrichmentCommand.CriticalPathComponentSeen component)
            throws SQLException {
        insertCriticalPath.setInt(1, component.ordinal());
        insertCriticalPath.setString(2, component.description());
        setNullableLong(insertCriticalPath, 3, component.startMicros());
        setNullableLong(insertCriticalPath, 4, component.durationMicros());
        setNullableLong(insertCriticalPath, 5, component.threadId());
        insertCriticalPath.addBatch();
        if (++pendingCriticalPathComponents >= BATCH) {
            insertCriticalPath.executeBatch();
            pendingCriticalPathComponents = 0;
        }
    }

    private void note(long start, OptionalLong duration) {
        traceMin = Math.min(traceMin, start);
        traceMax = Math.max(traceMax, Math.addExact(start, duration.orElse(0)));
    }

    private void intern(PreparedStatement statement, Optional<String> value) throws SQLException {
        if (value.isEmpty() || value.get().isEmpty()) {
            return;
        }
        statement.setString(1, value.get());
        statement.executeUpdate();
    }

    private static void setNullableString(
            PreparedStatement statement, int index, Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, OptionalLong value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    /** How many spans were written. */
    public long spansWritten() {
        return spansWritten;
    }

    /**
     * How many of those carry a primary output.
     *
     * <p>The difference is what {@code --experimental_profile_include_primary_output}
     * would have bought: without it the profile has spans and no way to say
     * which action each belongs to (P4).
     */
    public long attributedSpans() {
        return attributedSpans;
    }

    /**
     * Whether the profile's build id matched the session's; empty when one of
     * them was absent and the question could not be asked.
     */
    public Optional<Boolean> buildIdMatches() {
        return buildIdMatches;
    }

    @Override
    public void close() throws SQLException {
        SQLException first = null;
        for (PreparedStatement statement : List.of(
                insertThread, insertSpan, insertCounter, insertCriticalPath,
                insertLabel, insertMnemonic)) {
            try {
                statement.close();
            } catch (SQLException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}

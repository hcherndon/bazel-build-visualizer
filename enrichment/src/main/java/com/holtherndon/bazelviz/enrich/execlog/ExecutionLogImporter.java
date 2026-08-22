package com.holtherndon.bazelviz.enrich.execlog;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ExecLogFormat;
import com.holtherndon.bazelviz.storage.enrich.AttemptWriter;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Imports an execution log into a session, and records what happened either
 * way.
 *
 * <h2>Failure is contained here</h2>
 *
 * <p>Plan 21.4: a failed enrichment must not invalidate the BEP. That is
 * enforced by construction rather than by care — this class writes only to
 * tables schema v4 added, and it wraps the import in its own transaction. A
 * failure rolls back the attempt rows and then commits the task row saying so,
 * so the session is left with its Phase 3 data intact and an explanation of why
 * there is no Phase 4 data.
 *
 * <p>The one thing it will not do is decide the import succeeded when it did
 * not. A log whose invocation id disagrees with the session's build is a
 * different build's log, and importing it would attach one build's timings to
 * another's actions.
 */
public final class ExecutionLogImporter {

    /**
     * What the user loses when the execution log does not import.
     *
     * <p>Named in the words the UI uses, because plan 21.4 asks for "resulting
     * unavailable metrics" and a list of column names is not that.
     */
    static final List<String> METRICS_LOST = List.of(
            "where each action ran (local, sandbox, worker, remote)",
            "which actions were cache hits",
            "the detailed timing breakdown: queue, setup, execution, upload",
            "the actual inputs each action consumed",
            "per-attempt memory and input sizes");

    private final Connection connection;
    private final EnvironmentRedactor redactor;
    private final java.util.function.LongSupplier clock;

    public ExecutionLogImporter(Connection connection, EnvironmentRedactor redactor) {
        this(connection, redactor, () -> System.currentTimeMillis() * 1_000L);
    }

    ExecutionLogImporter(
            Connection connection,
            EnvironmentRedactor redactor,
            java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.redactor = redactor;
        this.clock = clock;
    }

    /**
     * Imports {@code file}.
     *
     * <p>Never throws for a bad log: a failure becomes a {@code FAILED} task
     * row and a returned result saying so. It does throw for a broken database,
     * because that is not something the session can carry on around.
     */
    public Result importFrom(Path file) throws SQLException {
        EnrichmentTaskStore tasks = new EnrichmentTaskStore(connection);
        long taskId = tasks.begin(
                EnrichmentTask.Kind.EXECUTION_LOG, Optional.of(file.toString()), clock.getAsLong());

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Result result = read(file, taskId);
            connection.commit();
            tasks.finish(taskId, result.state(), Optional.of(result.summary()),
                    result.error(), result.retriable(), result.unavailableMetrics(),
                    OptionalLong.of(result.spawnsRead()), OptionalLong.empty(), clock.getAsLong());
            connection.commit();
            return result;
        } catch (IOException | RuntimeException failure) {
            rollbackQuietly();
            Result result = Result.failed(failure);
            tasks.finish(taskId, EnrichmentTask.State.FAILED, Optional.of(result.summary()),
                    result.error(), result.retriable(), METRICS_LOST,
                    OptionalLong.empty(), OptionalLong.empty(), clock.getAsLong());
            connection.commit();
            return result;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private Result read(Path file, long taskId) throws IOException, SQLException {
        try (ExecLogSource source = ExecLogSource.open(file);
                AttemptWriter writer = new AttemptWriter(connection, taskId)) {

            List<EnrichmentCommand> pending = new ArrayList<>();
            long spawns = switch (source.format()) {
                case COMPACT -> new CompactExecLogParser(
                        buffered(writer, pending), redactor).parse(source.stream());
                case BINARY, JSON -> new BinaryExecLogParser(
                        buffered(writer, pending), redactor).parse(source.stream());
            };
            drain(writer, pending);

            Optional<String> mismatch = checkInvocation(source.format(), writer.invocationId());
            if (mismatch.isPresent()) {
                throw new WrongInvocationException(mismatch.get());
            }

            return new Result(
                    Optional.of(source.format()),
                    EnrichmentTask.State.SUCCEEDED,
                    spawns,
                    writer.attemptsWritten(),
                    writer.undeclaredReferences(),
                    verification(source.format(), writer.invocationId()),
                    Optional.empty(),
                    false,
                    List.of());
        }
    }

    /**
     * Buffers commands so the writer is fed in batches rather than one
     * statement per record.
     *
     * <p>Bounded in commands, not in bytes: an execution log's records vary
     * enormously in size — a spawn with ten thousand inputs against one with
     * two — and a byte bound would let a handful of large records occupy the
     * whole buffer. The same reasoning fixed the BEP importer's bound in
     * Phase 3.
     */
    private static final int BATCH = 2_000;

    private java.util.function.Consumer<EnrichmentCommand> buffered(
            AttemptWriter writer, List<EnrichmentCommand> pending) {
        return command -> {
            pending.add(command);
            if (pending.size() >= BATCH) {
                drain(writer, pending);
            }
        };
    }

    private void drain(AttemptWriter writer, List<EnrichmentCommand> pending) {
        try {
            for (EnrichmentCommand command : pending) {
                writer.apply(command);
            }
        } catch (SQLException failure) {
            throw new UncheckedWriteException(failure);
        } finally {
            pending.clear();
        }
    }

    /**
     * Whether the log belongs to this session's build.
     *
     * <p>Only answerable for the compact format, which is the only one with an
     * invocation header (V2, V3). Returns the mismatch message when the ids
     * disagree, and empty when they agree or when the question cannot be asked.
     */
    private Optional<String> checkInvocation(ExecLogFormat format, Optional<String> logId)
            throws SQLException {
        if (!format.carriesInvocationId() || logId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> sessionId = sessionInvocationId();
        if (sessionId.isEmpty() || sessionId.get().isEmpty()) {
            return Optional.empty();
        }
        if (sessionId.get().equals(logId.get())) {
            return Optional.empty();
        }
        return Optional.of(
                "this execution log is from build " + logId.get() + ", and this session"
                        + " captured build " + sessionId.get() + ". Importing it would attach"
                        + " one build's timings to another build's actions.");
    }

    private Optional<String> sessionInvocationId() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invocation_id FROM build_invocation WHERE singleton = 1");
                ResultSet rows = statement.executeQuery()) {
            return rows.next()
                    ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
        }
    }

    private static Verification verification(ExecLogFormat format, Optional<String> logId) {
        if (!format.carriesInvocationId()) {
            return Verification.UNVERIFIABLE;
        }
        return logId.isPresent() ? Verification.MATCHES_SESSION : Verification.NO_HEADER;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The failure being reported is the one worth reporting.
        }
    }

    /** Whether the log could be shown to belong to this session. */
    public enum Verification {
        /** Its invocation id equals the session's build id. */
        MATCHES_SESSION,
        /** The format carries no invocation id, so the question cannot be asked (V3). */
        UNVERIFIABLE,
        /** The format should have had a header and did not. */
        NO_HEADER
    }

    /**
     * What an import did.
     *
     * @param spawnsRead entries the log contained; zero is a valid outcome for
     *     a build in which every action hit the action cache (S3)
     * @param undeclaredReferences ids the log referenced and never defined,
     *     which make every input total reached through them a lower bound
     */
    public record Result(
            Optional<ExecLogFormat> format,
            EnrichmentTask.State state,
            long spawnsRead,
            long attemptsWritten,
            long undeclaredReferences,
            Verification verification,
            Optional<String> error,
            boolean retriable,
            List<String> unavailableMetrics) {

        /**
         * A failure.
         *
         * <p>The format is empty rather than guessed: an import can fail before
         * the file's first four bytes have said what it is, and naming a format
         * anyway would put a fact in the record that nothing established.
         *
         * <p>Retriable, unless the log is a different build's — running that
         * again produces the same wrong answer, so offering a retry would waste
         * the user's time on the one failure that cannot be fixed by trying.
         */
        static Result failed(Throwable failure) {
            boolean retriable = !(failure instanceof WrongInvocationException);
            return new Result(
                    Optional.empty(), EnrichmentTask.State.FAILED, 0, 0, 0,
                    Verification.NO_HEADER,
                    Optional.of(describe(failure)), retriable, METRICS_LOST);
        }

        private static String describe(Throwable failure) {
            String message = failure.getMessage();
            return message == null || message.isEmpty()
                    ? failure.getClass().getSimpleName() : message;
        }

        String summary() {
            return switch (state) {
                case SUCCEEDED -> spawnsRead == 0
                        ? "no spawns: every action hit the action cache"
                        : attemptsWritten + " attempts from " + spawnsRead + " spawns";
                case FAILED -> "failed";
                default -> state.name().toLowerCase(java.util.Locale.ROOT);
            };
        }

        /** True when the log was complete and held no executions. */
        public boolean everythingWasCached() {
            return state == EnrichmentTask.State.SUCCEEDED && spawnsRead == 0;
        }
    }

    /** The log belongs to a different build. */
    static final class WrongInvocationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WrongInvocationException(String message) {
            super(message);
        }
    }

    /** Carries a {@link SQLException} out of a consumer. */
    static final class UncheckedWriteException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UncheckedWriteException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}

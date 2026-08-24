package com.holtherndon.bazelviz.storage.query;

import java.util.Objects;

/**
 * A statement was sent to SQLite and SQLite refused it, or it was stopped.
 *
 * <p>Carries the two things a person needs in order to act: SQLite's own
 * message (as {@link #getMessage()}), and the exact text that was executed
 * ({@link #statement()}) — which is not always what the user typed, because
 * counting and paging wrap a tabular query in {@code SELECT … FROM (…)}. An
 * error naming a statement the user never wrote, with no way to see it, is the
 * kind of error people learn to ignore.
 *
 * <p>{@link #stage()} says which of the three executions failed, because
 * "columns", "count" and "page 41" fail for different reasons and the fix
 * differs.
 */
public final class QueryFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which execution failed. */
    public enum Stage {
        /** Reading result columns with a {@code LIMIT 0} probe. */
        COLUMNS("reading the result columns"),
        /** The {@code SELECT COUNT(*) FROM (…)} that sizes the grid. */
        COUNT("counting the matching rows"),
        /** One page of rows. */
        PAGE("fetching a page of rows"),
        /** Reading {@code sqlite_master} / {@code PRAGMA table_info}. */
        SCHEMA("reading the schema");

        private final String description;

        Stage(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final Stage stage;
    private final String statement;
    private final boolean stopped;

    QueryFailedException(Stage stage, String statement, boolean stopped, Throwable cause) {
        super(message(stage, stopped, cause), cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.statement = Objects.requireNonNull(statement, "statement");
        this.stopped = stopped;
    }

    private static String message(Stage stage, boolean stopped, Throwable cause) {
        if (stopped) {
            return "The query was stopped while " + stage.description() + ".";
        }
        return "SQLite refused the statement while " + stage.description() + ": "
                + cause.getMessage();
    }

    public Stage stage() {
        return stage;
    }

    /** The exact SQL that was executed, wrapping and all. */
    public String statement() {
        return statement;
    }

    /**
     * True when the statement was interrupted on purpose — by the user, or by
     * the runaway-query deadline — rather than refused.
     *
     * <p>The distinction matters on screen: a cancelled query is not a broken
     * one, and reporting it as an error teaches people to distrust errors.
     */
    public boolean wasStopped() {
        return stopped;
    }
}

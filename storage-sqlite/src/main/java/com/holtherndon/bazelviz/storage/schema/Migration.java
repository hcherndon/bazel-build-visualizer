package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One forward step of the session-database schema.
 *
 * <p>A migration is identified only by the version it produces. {@link
 * MigrationRunner} applies every migration whose {@link #version()} is above
 * the version currently recorded in {@code schema_metadata}, in ascending
 * order, so adding v2 later means adding one more {@code Migration} to the
 * runner's list — never editing the runner or a migration that has already
 * shipped. Editing a shipped migration would silently give two databases the
 * same recorded version with different shapes.
 *
 * <p>{@link #apply(Connection)} must not commit, roll back, or change the
 * connection's auto-commit mode: the runner owns the transaction so that a
 * failure half way through leaves the database exactly as it was.
 */
public interface Migration {

    /** The schema version this migration produces. Must be >= 1 and unique within a runner. */
    int version();

    /** Human-readable summary, used in error messages and logs. */
    String description();

    /** Executes the DDL/DML for this step on an open, caller-managed transaction. */
    void apply(Connection connection) throws SQLException;
}

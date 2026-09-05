package com.holtherndon.bazelviz.storage.schema;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies session-database schema migrations forward, once, transactionally.
 *
 * <p>Four properties matter, and each one is a rule from the plan rather than a preference:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The applied version lives in {@code schema_metadata}; a run that finds
 *       the database already at the latest version does nothing. Crash recovery (plan 21.1) opens
 *       sessions unconditionally, so "open" must not mean "migrate again".
 *   <li><b>Transactional.</b> SQLite makes DDL transactional, so every pending migration
 *       <em>and</em> the version bump commit together. A crash or a full disk mid-migration (plan
 *       21.2) therefore leaves the database at its previous version, never half-shaped.
 *   <li><b>Forward only.</b> A database recording a version newer than this build knows is refused
 *       with {@link SchemaVersionException}. Silently treating it as current would let this build
 *       write rows that the newer build's constraints reject.
 *   <li><b>Extensible.</b> Versions come from the {@link Migration} list, not from code in this
 *       class. Adding v2 is adding a list element.
 * </ul>
 *
 * <p>Indexes are not created here — see {@link SchemaIndexes} for why.
 */
public final class MigrationRunner {

  private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

  /** Version recorded for a database that has never been migrated. */
  public static final int UNMIGRATED = 0;

  /**
   * The newest schema this build ships — what {@link #standard()} migrates to.
   *
   * <p>It exists because {@link #currentVersion(Connection)} is static and so has no runner to ask,
   * yet the errors it raises must tell the user which version this build actually supports. {@code
   * standardIsTheLatestVersion} in the migration tests keeps the two from drifting.
   */
  public static final int LATEST_VERSION = SchemaV10.VERSION;

  private static final String SELECT_METADATA_TABLE =
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_metadata'";
  private static final String SELECT_VERSION = "SELECT value FROM schema_metadata WHERE key = ?";
  private static final String UPSERT_VERSION =
      "INSERT INTO schema_metadata (key, value) VALUES (?, ?)"
          + " ON CONFLICT (key) DO UPDATE SET value = excluded.value";

  private final List<Migration> migrations;

  /**
   * @param migrations forward migrations; order is irrelevant, they are sorted by version. Versions
   *     must be unique and >= 1.
   */
  public MigrationRunner(List<Migration> migrations) {
    List<Migration> sorted = new ArrayList<>(migrations);
    sorted.sort(Comparator.comparingInt(Migration::version));
    Set<Integer> seen = new HashSet<>();
    for (Migration migration : sorted) {
      if (migration.version() < 1) {
        throw new IllegalArgumentException(
            "migration versions start at 1, got " + migration.version());
      }
      if (!seen.add(migration.version())) {
        throw new IllegalArgumentException("duplicate migration version " + migration.version());
      }
    }
    this.migrations = List.copyOf(sorted);
  }

  /** The runner this application ships: schema v1 through v10, in order. */
  public static MigrationRunner standard() {
    return new MigrationRunner(
        List.of(
            new V1Migration(),
            new V2Migration(),
            new V3Migration(),
            new V4Migration(),
            new V5Migration(),
            new V6Migration(),
            new V7Migration(),
            new V8Migration(),
            new V9Migration(),
            new V10Migration()));
  }

  /** The newest version this runner can produce. */
  public int latestVersion() {
    return migrations.isEmpty() ? UNMIGRATED : migrations.getLast().version();
  }

  /** The migrations this runner knows, ascending by version. */
  public List<Migration> migrations() {
    return migrations;
  }

  /**
   * Reads the version recorded in the database.
   *
   * @return {@link #UNMIGRATED} when the database is empty or has no {@code schema_metadata} table
   * @throws SchemaVersionException when {@code schema_metadata} exists but holds no usable version
   *     — an ambiguous state that must not be resolved by guessing
   */
  public static int currentVersion(Connection connection) throws SQLException {
    if (!hasMetadataTable(connection)) {
      return UNMIGRATED;
    }
    try (PreparedStatement statement = connection.prepareStatement(SELECT_VERSION)) {
      statement.setString(1, SchemaV1.VERSION_KEY);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw SchemaVersionException.unreadable(
              "the schema_metadata table exists but carries no '" + SchemaV1.VERSION_KEY + "' row",
              LATEST_VERSION);
        }
        String raw = rows.getString(1);
        try {
          return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
          throw SchemaVersionException.unreadable(
              "the recorded version '" + raw + "' is not an integer", LATEST_VERSION);
        }
      }
    }
  }

  /**
   * Fails fast when the database was written by a newer build, without modifying anything.
   * Read-only callers use this instead of {@link #migrate(Connection)}.
   */
  public void requireCompatible(Connection connection) throws SQLException {
    int current = currentVersion(connection);
    if (current > latestVersion()) {
      throw SchemaVersionException.tooNew(current, latestVersion());
    }
  }

  /** Convenience overload operating on a session database's writer connection. */
  public int migrate(SessionDatabase database) throws SQLException {
    return migrate(database.writerConnection());
  }

  /**
   * Brings {@code connection}'s database up to {@link #latestVersion()}.
   *
   * @return the version in effect afterwards
   * @throws SchemaVersionException when the database is newer than this build
   */
  public int migrate(Connection connection) throws SQLException {
    int current = currentVersion(connection);
    int latest = latestVersion();
    if (current > latest) {
      throw SchemaVersionException.tooNew(current, latest);
    }
    if (current == latest) {
      log.trace("database schema is already at version {}", current);
      return current;
    }

    long startedNanos = System.nanoTime();
    log.info("migrating database schema from version {} to {}", current, latest);
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    int applied = current;
    try {
      for (Migration migration : migrations) {
        if (migration.version() <= current) {
          continue;
        }
        long migrationStartedNanos = System.nanoTime();
        migration.apply(connection);
        applied = migration.version();
        recordVersion(connection, applied);
        log.debug(
            "applied database schema version {} in {} ms",
            applied,
            elapsedMillis(migrationStartedNanos));
      }
      connection.commit();
    } catch (SQLException failure) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
    log.info(
        "database schema migration finished at version {} in {} ms",
        applied,
        elapsedMillis(startedNanos));
    return applied;
  }

  private static void recordVersion(Connection connection, int version) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPSERT_VERSION)) {
      statement.setString(1, SchemaV1.VERSION_KEY);
      statement.setString(2, Integer.toString(version));
      statement.executeUpdate();
    }
  }

  private static boolean hasMetadataTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(SELECT_METADATA_TABLE)) {
      return rows.next();
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}

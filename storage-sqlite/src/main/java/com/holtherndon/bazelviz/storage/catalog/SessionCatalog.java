package com.holtherndon.bazelviz.storage.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The application's index of the sessions on this machine (plan 10.6).
 *
 * <h2>An index, never a second copy</h2>
 *
 * <p>Plan 10.6: "the catalog must not contain the full build data". This holds
 * one small row per session so the start screen can list forty of them without
 * opening forty databases. Everything in it is derived from a manifest, so
 * {@link #rescan} can rebuild the whole table from the directories — which is
 * what makes the catalog a cache rather than a second source of truth.
 *
 * <h2>Relocation is a rescan, not a repair</h2>
 *
 * <p>Plan 24's Phase 9 exit criterion is that "sessions survive application
 * restart and relocation". Restart is easy — the catalog is on disk. Relocation
 * is the interesting half: a user who moves their sessions root invalidates
 * every path here and none of the sessions. So a rescan reads the directories
 * that are actually there, matches them by session UUID rather than by path,
 * updates the paths of the ones it finds and marks the ones it does not. The
 * UUID is in the directory name and in the manifest, and it is the only thing a
 * move does not change.
 *
 * <h2>Threading</h2>
 *
 * <p>One connection, so one instance belongs to one thread. Every method
 * blocks; none may be called on the Swing EDT.
 */
public final class SessionCatalog implements AutoCloseable {

    /** The catalog file, inside the directory the caller nominates. */
    public static final String FILE_NAME = "catalog.sqlite";

    /** The schema version this build writes. */
    static final int SCHEMA_VERSION = 1;

    private static final String CREATE_METADATA =
            "CREATE TABLE IF NOT EXISTS catalog_metadata ("
                    + " key TEXT PRIMARY KEY, value TEXT NOT NULL)";

    /**
     * One row per session and nothing else.
     *
     * <p>Every column is a scalar a manifest can supply. There is deliberately
     * no table for actions, events or anything else the session database holds:
     * a catalog that carried those would have to be migrated whenever the
     * session schema changed, and would be able to disagree with the thing it
     * indexes.
     */
    private static final String CREATE_SESSIONS =
            "CREATE TABLE IF NOT EXISTS sessions ("
                    + " session_uuid       TEXT PRIMARY KEY,"
                    + " display_name       TEXT NOT NULL,"
                    + " directory          TEXT NOT NULL,"
                    + " workspace          TEXT,"
                    + " command_summary    TEXT,"
                    + " bazel_version      TEXT,"
                    + " state              TEXT NOT NULL,"
                    + " started_micros     INTEGER,"
                    + " finished_micros    INTEGER,"
                    + " action_count       INTEGER,"
                    + " event_count        INTEGER,"
                    + " total_bytes        INTEGER,"
                    + " warning_count      INTEGER NOT NULL DEFAULT 0,"
                    + " last_opened_micros INTEGER,"
                    + " pinned             INTEGER NOT NULL DEFAULT 0,"
                    + " missing            INTEGER NOT NULL DEFAULT 0,"
                    + " summary            TEXT)";

    private static final String CREATE_RECENT_INDEX =
            "CREATE INDEX IF NOT EXISTS ix_sessions_recent"
                    + " ON sessions (last_opened_micros DESC, started_micros DESC)";

    private static final String COLUMNS =
            "session_uuid, display_name, directory, workspace, command_summary, bazel_version,"
                    + " state, started_micros, finished_micros, action_count, event_count,"
                    + " total_bytes, warning_count, last_opened_micros, pinned, missing, summary";

    private final Connection connection;
    private final Path file;

    private SessionCatalog(Connection connection, Path file) {
        this.connection = connection;
        this.file = file;
    }

    /** Opens or creates the catalog under {@code directory}. */
    public static SessionCatalog open(Path directory) throws IOException, SQLException {
        Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);
        Connection connection =
                DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        boolean ok = false;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute(CREATE_METADATA);
                statement.execute(CREATE_SESSIONS);
                statement.execute(CREATE_RECENT_INDEX);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO catalog_metadata (key, value) VALUES ('version', ?)")) {
                statement.setString(1, Integer.toString(SCHEMA_VERSION));
                statement.executeUpdate();
            }
            ok = true;
            return new SessionCatalog(connection, file);
        } finally {
            if (!ok) {
                connection.close();
            }
        }
    }

    /** Where the catalog lives. */
    public Path file() {
        return file;
    }

    /** Inserts or updates one session's row. */
    public void record(CatalogEntry entry) throws SQLException {
        Objects.requireNonNull(entry, "entry");
        String sql = "INSERT INTO sessions (" + COLUMNS + ")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(session_uuid) DO UPDATE SET"
                + " display_name = excluded.display_name,"
                + " directory = excluded.directory,"
                + " workspace = excluded.workspace,"
                + " command_summary = excluded.command_summary,"
                + " bazel_version = excluded.bazel_version,"
                + " state = excluded.state,"
                + " started_micros = excluded.started_micros,"
                + " finished_micros = excluded.finished_micros,"
                + " action_count = excluded.action_count,"
                + " event_count = excluded.event_count,"
                + " total_bytes = excluded.total_bytes,"
                + " warning_count = excluded.warning_count,"
                + " summary = excluded.summary,"
                + " missing = excluded.missing,"
                // Pinning and last-opened belong to the user, not to whatever
                // just re-read the manifest. An import that rewrote them would
                // unpin a session as a side effect of refreshing its counts.
                + " last_opened_micros = COALESCE(sessions.last_opened_micros,"
                + "     excluded.last_opened_micros),"
                + " pinned = sessions.pinned";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, entry);
            statement.executeUpdate();
        }
    }

    /** The most recently opened sessions, newest first. */
    public List<CatalogEntry> recent(int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("a limit below one returns nothing: " + limit);
        }
        String sql = "SELECT " + COLUMNS + " FROM sessions"
                + " ORDER BY COALESCE(last_opened_micros, started_micros, 0) DESC,"
                + " session_uuid DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            return readAll(statement);
        }
    }

    /** Every pinned session, newest first. */
    public List<CatalogEntry> pinned() throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM sessions WHERE pinned = 1"
                + " ORDER BY COALESCE(last_opened_micros, started_micros, 0) DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return readAll(statement);
        }
    }

    /** One session by uuid. */
    public Optional<CatalogEntry> find(String sessionUuid) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM sessions WHERE session_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionUuid);
            List<CatalogEntry> found = readAll(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    /** Records that a session was opened. */
    public void touch(String sessionUuid, long openedMicros) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sessions SET last_opened_micros = ?, missing = 0"
                        + " WHERE session_uuid = ?")) {
            statement.setLong(1, openedMicros);
            statement.setString(2, sessionUuid);
            statement.executeUpdate();
        }
    }

    /** Pins or unpins a session. */
    public void setPinned(String sessionUuid, boolean pinned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sessions SET pinned = ? WHERE session_uuid = ?")) {
            statement.setInt(1, pinned ? 1 : 0);
            statement.setString(2, sessionUuid);
            statement.executeUpdate();
        }
    }

    /**
     * Removes a session's row without touching its directory.
     *
     * <p>Forgetting and deleting are different actions and this is the first.
     * A user who moved a session out of the managed root wants it off the list
     * and still on their disk.
     */
    public void forget(String sessionUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM sessions WHERE session_uuid = ?")) {
            statement.setString(1, sessionUuid);
            statement.executeUpdate();
        }
    }

    /** What a rescan found. */
    public record RescanResult(int seen, int relocated, int added, int missing) {

        public String describe() {
            return seen + " sessions on disk: " + added + " new, " + relocated
                    + " in a different place than the catalog recorded, " + missing
                    + " listed here and not found.";
        }
    }

    /**
     * Reconciles the catalog against the directories actually present.
     *
     * <p>Matching is by session UUID, taken from the directory name. A user who
     * moves their sessions root changes every path and no UUID, which is why
     * this can put the catalog right rather than having to be told.
     *
     * @param sessionsRoot the directory sessions live under now
     * @param describe supplies a fresh entry for a directory — the caller reads
     *     the manifest, because this module knows about databases and the
     *     manifest belongs to {@code session-format}
     */
    public RescanResult rescan(Path sessionsRoot, DirectoryReader describe)
            throws IOException, SQLException {
        Objects.requireNonNull(sessionsRoot, "sessionsRoot");
        Objects.requireNonNull(describe, "describe");
        Map<String, Path> onDisk = new LinkedHashMap<>();
        if (Files.isDirectory(sessionsRoot)) {
            try (var listing = Files.list(sessionsRoot)) {
                listing.filter(Files::isDirectory).forEach(directory -> {
                    String name = directory.getFileName().toString();
                    if (name.startsWith("session-")) {
                        onDisk.put(name.substring("session-".length()), directory);
                    }
                });
            }
        }
        Map<String, CatalogEntry> known = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM sessions")) {
            for (CatalogEntry entry : readAll(statement)) {
                known.put(entry.sessionUuid(), entry);
            }
        }

        int relocated = 0;
        int added = 0;
        for (Map.Entry<String, Path> found : onDisk.entrySet()) {
            CatalogEntry existing = known.get(found.getKey());
            Optional<CatalogEntry> fresh = describe.read(found.getValue());
            if (fresh.isEmpty()) {
                continue;
            }
            if (existing == null) {
                added++;
            } else if (!existing.directory().equals(found.getValue())) {
                relocated++;
            }
            record(fresh.orElseThrow().withMissing(false));
        }

        int missing = 0;
        for (CatalogEntry entry : known.values()) {
            if (!onDisk.containsKey(entry.sessionUuid())) {
                missing++;
                // Marked, not deleted. A user whose external disk is unmounted
                // has not lost their sessions, and a list that silently shrank
                // would tell them they had.
                record(entry.withMissing(true));
            }
        }
        return new RescanResult(onDisk.size(), relocated, added, missing);
    }

    /** Reads a session directory into an entry, or nothing when it is not one. */
    @FunctionalInterface
    public interface DirectoryReader {
        Optional<CatalogEntry> read(Path sessionDirectory);
    }

    /** Works out which sessions a policy would remove, without removing any. */
    public RetentionPolicy.Plan plan(RetentionPolicy policy, long nowMicros) throws SQLException {
        Objects.requireNonNull(policy, "policy");
        if (policy.keepsEverything()) {
            return new RetentionPolicy.Plan(List.of(), 0, 0);
        }
        List<CatalogEntry> all;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM sessions")) {
            all = new ArrayList<>(readAll(statement));
        }
        // Oldest first, so the "keep the newest N" and "fit in N bytes" rules
        // both drop from the same end.
        all.sort(Comparator.comparingLong(
                entry -> entry.lastOpenedMicros().orElse(entry.startedMicros().orElse(0))));

        Map<String, String> reasons = new LinkedHashMap<>();
        policy.maxAgeMicros().ifPresent(maxAge -> {
            for (CatalogEntry entry : all) {
                long age = nowMicros - entry.lastOpenedMicros()
                        .orElse(entry.startedMicros().orElse(nowMicros));
                if (age > maxAge) {
                    reasons.putIfAbsent(entry.sessionUuid(),
                            "older than " + maxAge / 1_000_000 + " s");
                }
            }
        });
        policy.maxSessions().ifPresent(maxSessions -> {
            long excess = all.size() - maxSessions;
            for (int i = 0; i < excess && i < all.size(); i++) {
                reasons.putIfAbsent(all.get(i).sessionUuid(),
                        "beyond the newest " + maxSessions);
            }
        });
        policy.maxTotalBytes().ifPresent(maxBytes -> {
            long total = all.stream().mapToLong(entry -> entry.totalBytes().orElse(0)).sum();
            for (CatalogEntry entry : all) {
                if (total <= maxBytes) {
                    break;
                }
                // Selected by an earlier rule or by this one; either way its
                // bytes come off the running total, because it is going.
                reasons.putIfAbsent(entry.sessionUuid(),
                        "over the " + maxBytes + "-byte budget");
                total -= entry.totalBytes().orElse(0);
            }
        });

        List<RetentionPolicy.Candidate> candidates = new ArrayList<>();
        long pinnedSkipped = 0;
        long bytes = 0;
        for (CatalogEntry entry : all) {
            String reason = reasons.get(entry.sessionUuid());
            if (reason == null) {
                continue;
            }
            if (entry.pinned()) {
                pinnedSkipped++;
                continue;
            }
            candidates.add(new RetentionPolicy.Candidate(entry, reason));
            bytes += entry.totalBytes().orElse(0);
        }
        return new RetentionPolicy.Plan(candidates, pinnedSkipped, bytes);
    }

    /** What a sweep actually did. */
    public record SweepResult(int removed, long bytesFreed, List<String> failures) {

        public SweepResult {
            failures = List.copyOf(failures);
        }

        public String describe() {
            String base = "Removed " + removed
                    + (removed == 1 ? " session, " : " sessions, ") + bytesFreed
                    + " bytes freed.";
            return failures.isEmpty() ? base
                    : base + " " + failures.size() + " could not be removed: "
                            + String.join("; ", failures);
        }
    }

    /**
     * Carries out a plan, deleting each session's directory and its row.
     *
     * <p>Takes the plan rather than the policy, so what is deleted is exactly
     * what was shown. Re-deriving the list here would let the two differ by
     * whatever changed in between.
     */
    public SweepResult apply(RetentionPolicy.Plan plan) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        int removed = 0;
        long bytes = 0;
        List<String> failures = new ArrayList<>();
        for (RetentionPolicy.Candidate candidate : plan.candidates()) {
            CatalogEntry entry = candidate.entry();
            if (entry.pinned()) {
                // Pinned between the plan and the sweep. The user's decision is
                // newer than the plan, so it wins.
                failures.add(entry.displayName() + " was pinned after the plan was made");
                continue;
            }
            try {
                deleteRecursively(entry.directory());
                forget(entry.sessionUuid());
                removed++;
                bytes += entry.totalBytes().orElse(0);
            } catch (IOException failure) {
                failures.add(entry.displayName() + ": " + failure.getMessage());
            }
        }
        return new SweepResult(removed, bytes, failures);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void bind(PreparedStatement statement, CatalogEntry entry) throws SQLException {
        statement.setString(1, entry.sessionUuid());
        statement.setString(2, entry.displayName());
        statement.setString(3, entry.directory().toString());
        setText(statement, 4, entry.workspace());
        setText(statement, 5, entry.commandSummary());
        setText(statement, 6, entry.bazelVersion());
        statement.setString(7, entry.state());
        setNumber(statement, 8, entry.startedMicros());
        setNumber(statement, 9, entry.finishedMicros());
        setNumber(statement, 10, entry.actionCount());
        setNumber(statement, 11, entry.eventCount());
        setNumber(statement, 12, entry.totalBytes());
        statement.setLong(13, entry.warningCount());
        setNumber(statement, 14, entry.lastOpenedMicros());
        statement.setInt(15, entry.pinned() ? 1 : 0);
        statement.setInt(16, entry.missing() ? 1 : 0);
        setText(statement, 17, entry.summary());
    }

    private static void setText(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
    }

    private static void setNumber(PreparedStatement statement, int index, OptionalLong value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static List<CatalogEntry> readAll(PreparedStatement statement) throws SQLException {
        List<CatalogEntry> entries = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                entries.add(new CatalogEntry(
                        rows.getString(1),
                        rows.getString(2),
                        Path.of(rows.getString(3)),
                        text(rows, 4),
                        text(rows, 5),
                        text(rows, 6),
                        rows.getString(7),
                        number(rows, 8),
                        number(rows, 9),
                        number(rows, 10),
                        number(rows, 11),
                        number(rows, 12),
                        rows.getLong(13),
                        number(rows, 14),
                        rows.getInt(15) != 0,
                        rows.getInt(16) != 0,
                        text(rows, 17)));
            }
        }
        return entries;
    }

    private static Optional<String> text(ResultSet rows, int index) throws SQLException {
        String value = rows.getString(index);
        return value == null ? Optional.empty() : Optional.of(value);
    }

    private static OptionalLong number(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

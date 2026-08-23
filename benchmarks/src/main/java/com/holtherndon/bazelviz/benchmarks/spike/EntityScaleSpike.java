package com.holtherndon.bazelviz.benchmarks.spike;

import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.entities.OverviewQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.storage.schema.SchemaIndexes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.random.RandomGenerator;

/**
 * Phase 3 spike: what do the entity write path and the actions table cost at
 * scale?
 *
 * <p>Three numbers the Phase 3 code asserts nothing about and should:
 *
 * <ul>
 *   <li><b>Normalization throughput</b> — actions per second through
 *       {@link EntityWriter}, which executes several statements per action and
 *       resolves every foreign key with a SELECT inside the INSERT.</li>
 *   <li><b>Anchor-index cost per sort</b> — one ordered scan per (filter, sort,
 *       direction), which is what a user pays when they click a column header.
 *       Five of the six sorts ride an index; {@code DURATION} is a computed
 *       expression and SQLite builds a temporary b-tree for it. The gap between
 *       those two is the point of measuring.</li>
 *   <li><b>Page fetch</b> — the seek itself, which the plan wants under 100 ms
 *       for a cached page and 500 ms uncached, at any scroll depth.</li>
 * </ul>
 *
 * <p>And one more: the overview's eleven correlated subqueries, which run on a
 * two-second timer during a live capture and therefore have to finish inside
 * one.
 *
 * <p>Console output only. {@code --rows=N} sets the action count;
 * {@code --offscreen} is accepted for spike-runner uniformity and ignored.
 */
public final class EntityScaleSpike {

    private static final int PAGE_SIZE = 200;
    private static final int SEEK_SAMPLES = 50;

    /** The plan's cached-page target (17.4), repeated so a regression is visible. */
    private static final double CACHED_PAGE_TARGET_MS = 100.0;

    /** The overview refreshes on a two-second timer, so it has to beat one. */
    private static final double OVERVIEW_TARGET_MS = 1_000.0;

    private static final String[] MNEMONICS = {
        "CppCompile", "Javac", "Genrule", "SolibSymlink", "TestRunner",
        "SourceSymlinkManifest", "FileWrite", "CppLink", "ProtoCompile", "Turbine",
    };

    private EntityScaleSpike() {}

    public static void main(String[] args) throws Exception {
        long rows = 1_000_000L;
        for (String arg : args) {
            if (arg.startsWith("--rows=")) {
                rows = Long.parseLong(arg.substring("--rows=".length()));
            }
        }

        Path directory = Path.of(System.getProperty("java.io.tmpdir"),
                "bbv-entity-spike-" + ProcessHandle.current().pid());
        Files.createDirectories(directory);
        Path dbFile = directory.resolve("entities.db");
        System.out.printf("EntityScaleSpike: %,d actions, db %s%n", rows, dbFile);

        boolean pass = true;
        try (SessionDatabase db = SessionDatabase.open(dbFile)) {
            MigrationRunner.standard().migrate(db);
            long streamId = prepareStream(db.writerConnection(), rows);

            double writeSeconds = load(db, streamId, rows);
            System.out.printf(Locale.ROOT,
                    "  normalize: %,d actions in %.2f s = %,.0f actions/s%n",
                    rows, writeSeconds, rows / writeSeconds);

            double attemptSeconds = loadAttempts(db, rows);
            System.out.printf(Locale.ROOT,
                    "  attempts:  %,d spawns in %.2f s (one per three actions, the measured"
                            + " ratio)%n",
                    rows / 3 + 1, attemptSeconds);

            long indexSeconds = time(() -> SchemaIndexes.createAll(db.writerConnection()));
            System.out.printf(Locale.ROOT, "  indexes:   %.2f s%n", indexSeconds / 1e9);

            try (Connection read = db.newReadConnection()) {
                ActionQueries queries = new ActionQueries(read);
                for (ActionSort sort : ActionSort.values()) {
                    long nanos = time(() ->
                            queries.buildIndex(ActionFilter.NONE, sort, false, PAGE_SIZE));
                    ActionQueries.Index index =
                            queries.buildIndex(ActionFilter.NONE, sort, false, PAGE_SIZE);
                    System.out.printf(Locale.ROOT,
                            "  anchor index %-10s %8.0f ms  (%,d anchors)%n",
                            sort, nanos / 1e6, index.anchors().size());
                    pass &= reportSeeks(queries, index, sort);
                    pass &= verifyOrdering(read, queries, sort);
                }
            }

            try (Connection read = db.newReadConnection()) {
                OverviewQueries overview = new OverviewQueries(read);
                overview.snapshot();
                long nanos = time(overview::snapshot);
                double millis = nanos / 1e6;
                boolean ok = millis < OVERVIEW_TARGET_MS;
                pass &= ok;
                System.out.printf(Locale.ROOT,
                        "  overview snapshot %8.1f ms  target < %.0f ms  %s%n",
                        millis, OVERVIEW_TARGET_MS, ok ? "PASS" : "FAIL");
            }

            System.out.printf(Locale.ROOT, "  db size:   %,d bytes%n", Files.size(dbFile));
        }

        // Plan 24's Phase 10 exit criterion: "a Tier 3 indexed session can be
        // reopened and queried". Everything above ran against a database this
        // process had just written, with its pages warm and its connection
        // open. Reopening from cold is the question the criterion asks, and it
        // is a different one: the objective is that the overview appears within
        // five seconds without loading all actions.
        pass &= reopenAndQuery(dbFile);

        System.out.println(pass ? "EntityScaleSpike: PASS" : "EntityScaleSpike: FAIL");
        if (!pass) {
            System.exit(1);
        }
    }

    /**
     * Closes everything, opens the file again, and asks it the two questions a
     * user asks first.
     *
     * <p>Objective 8 (plan 20.2): "opening an already indexed Tier 3 session
     * shows its overview within five seconds without loading all actions". The
     * second half is the part worth checking structurally — the overview is a
     * handful of aggregate queries over indexed columns, so its cost must not
     * scale with the number of actions, and the first page must be a seek
     * rather than a scan.
     */
    private static boolean reopenAndQuery(Path dbFile) throws Exception {
        System.out.println("  -- reopened from cold --");
        boolean pass = true;
        long openNanos = System.nanoTime();
        try (SessionDatabase reopened = SessionDatabase.open(dbFile)) {
            long openMillis = (System.nanoTime() - openNanos) / 1_000_000L;
            System.out.printf(Locale.ROOT, "  open:      %,d ms%n", openMillis);

            try (Connection read = reopened.newReadConnection()) {
                long nanos = time(() -> new OverviewQueries(read).snapshot());
                double millis = nanos / 1e6;
                boolean ok = millis < 5_000.0;
                pass &= ok;
                System.out.printf(Locale.ROOT,
                        "  overview (cold) %8.1f ms  target < 5000 ms  %s%n",
                        millis, ok ? "PASS" : "FAIL");
            }
            try (Connection read = reopened.newReadConnection()) {
                ActionQueries queries = new ActionQueries(read);
                long nanos = time(() ->
                        queries.firstPage(ActionFilter.NONE, ActionSort.ARRIVAL, false, PAGE_SIZE));
                double millis = nanos / 1e6;
                boolean ok = millis < 500.0;
                pass &= ok;
                System.out.printf(Locale.ROOT,
                        "  first page (cold) %6.1f ms  target < 500 ms  %s%n",
                        millis, ok ? "PASS" : "FAIL");
            }
        }
        return pass;
    }

    /**
     * Checks that walking the pages produces exactly the rows, in exactly the
     * order, that one big {@code ORDER BY} would.
     *
     * <p>Speed is the easy half. A page composed from several seekable segments
     * can drop a row at a boundary or repeat one, and neither shows up as an
     * error — the table simply holds fewer rows than its own count, or the same
     * action twice. This is the assertion that fails when that happens.
     *
     * <p>Only the first few thousand rows: the reference query has to hold its
     * result, and the boundaries — which are the whole risk — are as dense at
     * the head of the table as anywhere.
     */
    private static boolean verifyOrdering(
            Connection read, ActionQueries queries, ActionSort sort) throws SQLException {
        int checked = 5_000;
        List<Long> reference = new ArrayList<>(checked);
        String sql = "SELECT a.id FROM actions a"
                + " LEFT JOIN labels l ON l.id = a.label_id"
                + " LEFT JOIN mnemonics m ON m.id = a.mnemonic_id"
                + " ORDER BY " + sortColumn(sort) + " ASC, a.id ASC LIMIT " + checked;
        try (var statement = read.prepareStatement(sql);
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                reference.add(rows.getLong(1));
            }
        }

        List<Long> walked = new ArrayList<>(checked);
        List<ActionRow> page = queries.firstPage(ActionFilter.NONE, sort, false, PAGE_SIZE);
        while (!page.isEmpty() && walked.size() < checked) {
            page.forEach(row -> walked.add(row.id()));
            page = queries.pageAfter(page.getLast(), ActionFilter.NONE, sort, false, PAGE_SIZE);
        }
        List<Long> trimmed = walked.subList(0, Math.min(checked, walked.size()));
        boolean ok = trimmed.equals(reference);
        System.out.printf(Locale.ROOT,
                "      ordering over %,d rows: %s%n", checked, ok ? "PASS" : "FAIL");
        if (!ok) {
            for (int i = 0; i < Math.min(trimmed.size(), reference.size()); i++) {
                if (!trimmed.get(i).equals(reference.get(i))) {
                    System.out.printf(Locale.ROOT,
                            "        first difference at %d: paged %d, reference %d%n",
                            i, trimmed.get(i), reference.get(i));
                    break;
                }
            }
        }
        return ok;
    }

    /** The same expression {@link ActionSort} orders by, for the reference query. */
    private static String sortColumn(ActionSort sort) {
        return switch (sort) {
            case ARRIVAL -> "a.id";
            case START_TIME -> "a.start_micros";
            case DURATION -> "(CASE WHEN a.duration_unknown_reason IS NULL"
                    + " THEN a.end_micros - a.start_micros END)";
            case MNEMONIC -> "m.value";
            case LABEL -> "l.value";
            case OUTCOME -> "a.outcome";
        };
    }

    /**
     * Times a page fetch from the shallowest, middle and deepest anchors.
     *
     * <p>Depth is the whole question: {@code OFFSET} would make the deepest one
     * the slowest by a wide margin, and a keyset seek should not care.
     */
    private static boolean reportSeeks(
            ActionQueries queries, ActionQueries.Index index, ActionSort sort) throws SQLException {
        if (index.anchors().isEmpty()) {
            return true;
        }
        long[] pages = {
            1, index.anchors().size() / 2, index.anchors().size(),
        };
        boolean pass = true;
        for (long page : pages) {
            Optional<ActionQueries.Anchor> anchor = index.anchorFor(page);
            if (anchor.isEmpty()) {
                continue;
            }
            long total = 0;
            for (int sample = 0; sample < SEEK_SAMPLES; sample++) {
                total += time(() ->
                        queries.pageAfter(anchor.get(), ActionFilter.NONE, sort, false, PAGE_SIZE));
            }
            double millis = total / 1e6 / SEEK_SAMPLES;
            boolean ok = millis < CACHED_PAGE_TARGET_MS;
            pass &= ok;
            System.out.printf(Locale.ROOT,
                    "      page %,10d  %8.2f ms  %s%n", page, millis, ok ? "PASS" : "FAIL");
        }
        return pass;
    }

    /**
     * Writes {@code rows} actions with a realistic spread: ten mnemonics, one
     * label per twenty actions, a tenth untimed, and one in a thousand failed.
     */
    private static double load(SessionDatabase db, long streamId, long rows) throws SQLException {
        RandomGenerator random = RandomGenerator.getDefault();
        long start = System.nanoTime();
        try (EntityWriter writer = new EntityWriter(db.writerConnection())) {
            writer.apply(streamId, 1, new EntityCommand.ConfigurationDeclared(
                    "cfg", "darwin_arm64-fastbuild", "darwin", "darwin_arm64", false,
                    java.util.Map.of()));
            for (long row = 0; row < rows; row++) {
                boolean timed = row % 10 != 0;
                boolean failed = row % 1_000 == 0;
                long micros = 1_000_000L + row * 37;
                writer.apply(streamId, 2, new EntityCommand.ActionCompleted(
                        "bazel-out/darwin_arm64-fastbuild/bin/pkg" + (row / 20) + "/out" + row + ".o",
                        Optional.of("//pkg" + (row / 20) + ":lib" + (row / 20)),
                        "cfg",
                        Optional.of(MNEMONICS[(int) (row % MNEMONICS.length)]),
                        !failed,
                        failed ? OptionalInt.of(1) : OptionalInt.empty(),
                        Optional.empty(),
                        timed
                                ? ActionTiming.of(
                                        OptionalLong.of(micros),
                                        OptionalLong.of(micros + 1 + random.nextInt(500_000)))
                                : ActionTiming.NONE,
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
            }
            writer.flush();
        }
        return (System.nanoTime() - start) / 1e9;
    }

    /**
     * An execution-log attempt for a third of the actions.
     *
     * <p>The proportion is measured rather than chosen: a real build produced 4
     * spawns against 13 published actions, because the rest run inside the
     * Bazel server and never spawn a subprocess (K1 in
     * docs/exec-log-and-profile.md).
     *
     * <p>This exists because the actions table's Runner and Cached columns are
     * correlated subqueries over {@code action_attempts}, and measuring them
     * against an empty table would measure nothing. The first run of this spike
     * after those columns landed did exactly that and looked fine.
     */
    private static double loadAttempts(SessionDatabase db, long rows) throws SQLException {
        long start = System.nanoTime();
        Connection connection = db.writerConnection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement task = connection.prepareStatement(
                        "INSERT INTO enrichment_tasks (kind, state) VALUES"
                                + " ('EXECUTION_LOG', 'SUCCEEDED')");
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO action_attempts (task_id, log_entry_index, action_id,"
                                + " correlation, runner, cache_hit, exit_code, start_micros,"
                                + " total_micros) VALUES (1, ?, ?, 'MATCHED_BY_OUTPUT', ?, ?, 0,"
                                + " ?, ?)")) {
            task.executeUpdate();
            long index = 0;
            for (long actionId = 1; actionId <= rows; actionId += 3) {
                insert.setLong(1, index++);
                insert.setLong(2, actionId);
                insert.setString(3, RUNNERS[(int) (actionId % RUNNERS.length)]);
                insert.setInt(4, actionId % 7 == 0 ? 1 : 0);
                insert.setLong(5, 1_000_000L + actionId * 37);
                insert.setLong(6, 1 + actionId % 500_000);
                insert.addBatch();
                if (index % 10_000 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
            connection.commit();
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
        return (System.nanoTime() - start) / 1e9;
    }

    private static final String[] RUNNERS =
            {"darwin-sandbox", "worker", "remote", "disk cache hit", "local"};

    /**
     * One stream row and one event row.
     *
     * <p>Every action points at the same event, which is not what a real
     * session looks like but is what keeps this spike measuring the entity path
     * rather than the Phase 1 event path — that one is already measured in
     * {@link SqlPagingSpike} and {@link BesThroughputSpike}.
     */
    private static long prepareStream(Connection connection, long rows) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO event_streams (stream_key, state) VALUES ('spike', 'OPEN')");
            long streamId;
            try (var result = statement.executeQuery("SELECT last_insert_rowid()")) {
                result.next();
                streamId = result.getLong(1);
            }
            for (int sequence = 1; sequence <= 2; sequence++) {
                statement.execute(
                        "INSERT INTO bep_events (stream_id, sequence, event_type, raw_segment,"
                                + " raw_offset, raw_length, decode_status, receive_micros) VALUES ("
                                + streamId + ", " + sequence
                                + ", 1, 0, 0, 0, 'OK', 0)");
            }
            statement.execute(
                    "INSERT INTO build_invocation (singleton, stream_id, build_tool_version,"
                            + " command) VALUES (1, " + streamId + ", '9.2.0', 'build')");
            statement.execute(
                    "INSERT INTO build_metrics (singleton, actions_executed) VALUES (1, " + rows + ")");
            return streamId;
        }
    }

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    private static long time(Work work) throws SQLException {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }
}

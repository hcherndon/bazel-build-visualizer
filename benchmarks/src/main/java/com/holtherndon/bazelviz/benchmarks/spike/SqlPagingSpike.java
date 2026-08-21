package com.holtherndon.bazelviz.benchmarks.spike;

import com.holtherndon.bazelviz.storage.BatchedInsert;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticAction;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * Phase 0 spike: is plain SQLite fast enough to back a sortable, pageable
 * table of millions of actions? Loads N synthetic actions with
 * {@link BatchedInsert}, builds indexes after the load (bulk load into an
 * indexed table is much slower), then measures three retrieval shapes the
 * table UI needs: OFFSET paging, keyset paging, and point lookup by id.
 *
 * <p>Always console output; the --offscreen flag other spikes accept is
 * ignored because there is nothing to render here. Plan targets: cached page
 * &lt; 100 ms, uncached &lt; 500 ms.
 */
public final class SqlPagingSpike {

    private static final int PAGE_SIZE = 100;
    private static final int SAMPLES = 200;
    private static final int INSERT_BATCH_SIZE = 20_000;
    private static final long SEED = 42L;
    private static final double CACHED_TARGET_MS = 100.0;
    private static final double UNCACHED_TARGET_MS = 500.0;

    private SqlPagingSpike() {}

    public static void main(String[] args) throws Exception {
        long rows = 2_000_000L;
        for (String arg : args) {
            if (arg.startsWith("--rows=")) {
                rows = Long.parseLong(arg.substring("--rows=".length()));
            }
            // --offscreen is accepted for spike-runner uniformity and ignored.
        }

        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"),
                "bbv-spike-" + ProcessHandle.current().pid());
        Files.createDirectories(tempDir);
        Path dbFile = tempDir.resolve("paging.db");
        System.out.printf("SqlPagingSpike: %,d rows, db %s%n", rows, dbFile);

        try (SessionDatabase db = SessionDatabase.open(dbFile)) {
            createSchema(db.writerConnection());
            loadRows(db, rows);
            indexAndAnalyze(db.writerConnection());
            try (Connection read = db.newReadConnection()) {
                boolean pass = true;
                pass &= report("OFFSET page (ORDER BY duration DESC LIMIT 100)",
                        measureOffsetPaging(read, rows));
                pass &= report("keyset page (duration,id anchor)",
                        measureKeysetPaging(read, rows));
                pass &= report("point lookup by id",
                        measurePointLookup(read, rows));
                System.out.println(pass ? "RESULT: PASS" : "RESULT: FAIL");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void createSchema(Connection writer) throws SQLException {
        try (Statement statement = writer.createStatement()) {
            statement.execute("""
                    CREATE TABLE actions (
                        id INTEGER PRIMARY KEY,
                        start_micros INTEGER NOT NULL,
                        end_micros INTEGER NOT NULL,
                        duration_micros INTEGER NOT NULL,
                        mnemonic_ix INTEGER NOT NULL,
                        target_ix INTEGER NOT NULL,
                        status INTEGER NOT NULL,
                        cache_state INTEGER NOT NULL,
                        runner INTEGER NOT NULL,
                        input_bytes INTEGER NOT NULL
                    )""");
        }
    }

    private static void loadRows(SessionDatabase db, long rows) throws SQLException {
        // TIER3 caps actionAt() at 5M; reuse indices modulo actionCount if the
        // caller asks for more (ids stay unique regardless).
        SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER3, SEED);
        long start = System.nanoTime();
        try (BatchedInsert insert = new BatchedInsert(
                db.writerConnection(),
                "INSERT INTO actions (id, start_micros, end_micros, duration_micros, mnemonic_ix,"
                        + " target_ix, status, cache_state, runner, input_bytes)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                INSERT_BATCH_SIZE)) {
            PreparedStatement statement = insert.statement();
            for (long i = 0; i < rows; i++) {
                SyntheticAction action = generator.actionAt(i % generator.actionCount());
                statement.setLong(1, i);
                statement.setLong(2, action.startMicros());
                statement.setLong(3, action.endMicros());
                statement.setLong(4, action.durationMicros());
                statement.setInt(5, action.mnemonicIndex());
                statement.setInt(6, action.targetIndex());
                statement.setInt(7, action.status());
                statement.setInt(8, action.cacheState());
                statement.setInt(9, action.runner());
                statement.setLong(10, action.knownInputBytes());
                insert.add();
            }
            insert.flush();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "insert: %,d rows in %.1f s = %,.0f rows/sec (batch %,d)%n",
                rows, seconds, rows / seconds, INSERT_BATCH_SIZE);
    }

    private static void indexAndAnalyze(Connection writer) throws SQLException {
        long start = System.nanoTime();
        try (Statement statement = writer.createStatement()) {
            // (duration_micros, id) rather than duration alone: keyset paging
            // needs a total order, and the composite index serves both.
            statement.execute(
                    "CREATE INDEX idx_actions_duration ON actions(duration_micros, id)");
            statement.execute("CREATE INDEX idx_actions_start ON actions(start_micros)");
            statement.execute("CREATE INDEX idx_actions_mnemonic ON actions(mnemonic_ix)");
            statement.execute("ANALYZE");
        }
        System.out.printf(Locale.ROOT, "indexes + ANALYZE: %.1f s%n",
                (System.nanoTime() - start) / 1e9);
    }

    private static double[] measureOffsetPaging(Connection read, long rows) throws SQLException {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        double[] samples = new double[SAMPLES];
        try (PreparedStatement statement = read.prepareStatement(
                "SELECT id, start_micros, duration_micros, mnemonic_ix, status FROM actions"
                        + " ORDER BY duration_micros DESC, id DESC LIMIT " + PAGE_SIZE + " OFFSET ?")) {
            for (int s = 0; s < SAMPLES; s++) {
                long offset = random.nextLong(Math.max(1, rows - PAGE_SIZE));
                long start = System.nanoTime();
                statement.setLong(1, offset);
                int fetched = drain(statement);
                samples[s] = (System.nanoTime() - start) / 1e6;
                requireFullPage(fetched);
            }
        }
        return samples;
    }

    private static double[] measureKeysetPaging(Connection read, long rows) throws SQLException {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        double[] samples = new double[SAMPLES];
        try (PreparedStatement anchor = read.prepareStatement(
                        "SELECT duration_micros FROM actions WHERE id = ?");
                PreparedStatement statement = read.prepareStatement(
                        "SELECT id, start_micros, duration_micros, mnemonic_ix, status FROM actions"
                                + " WHERE (duration_micros, id) < (?, ?)"
                                + " ORDER BY duration_micros DESC, id DESC LIMIT " + PAGE_SIZE)) {
            for (int s = 0; s < SAMPLES; s++) {
                // A random existing row stands in for "the last row of the
                // previous page"; the anchor fetch is outside the timed window.
                long anchorId = random.nextLong(rows);
                anchor.setLong(1, anchorId);
                long anchorDuration;
                try (ResultSet rs = anchor.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("missing anchor id " + anchorId);
                    }
                    anchorDuration = rs.getLong(1);
                }
                long start = System.nanoTime();
                statement.setLong(1, anchorDuration);
                statement.setLong(2, anchorId);
                drain(statement);
                samples[s] = (System.nanoTime() - start) / 1e6;
            }
        }
        return samples;
    }

    private static double[] measurePointLookup(Connection read, long rows) throws SQLException {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        double[] samples = new double[SAMPLES];
        try (PreparedStatement statement = read.prepareStatement(
                "SELECT * FROM actions WHERE id = ?")) {
            for (int s = 0; s < SAMPLES; s++) {
                long id = random.nextLong(rows);
                long start = System.nanoTime();
                statement.setLong(1, id);
                int fetched = drain(statement);
                samples[s] = (System.nanoTime() - start) / 1e6;
                if (fetched != 1) {
                    throw new IllegalStateException("expected 1 row for id " + id + ", got " + fetched);
                }
            }
        }
        return samples;
    }

    /** Executes the query and touches every row so timings include full result transfer. */
    private static int drain(PreparedStatement statement) throws SQLException {
        int count = 0;
        long sink = 0;
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                sink += rs.getLong(1);
                count++;
            }
        }
        if (sink == Long.MIN_VALUE) {
            System.out.print(""); // defeat dead-code elimination of the drain
        }
        return count;
    }

    private static void requireFullPage(int fetched) {
        if (fetched != PAGE_SIZE) {
            throw new IllegalStateException("expected a full page, got " + fetched + " rows");
        }
    }

    /**
     * Prints the sample distribution and evaluates plan targets: p50 stands in
     * for the cached-page case (steady-state, OS page cache warm) and worst
     * observed latency for the uncached case.
     */
    private static boolean report(String label, double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        double p50 = percentile(sorted, 50);
        double p95 = percentile(sorted, 95);
        double p99 = percentile(sorted, 99);
        double max = sorted[sorted.length - 1];
        boolean cachedOk = p50 < CACHED_TARGET_MS;
        boolean uncachedOk = max < UNCACHED_TARGET_MS;
        System.out.printf(Locale.ROOT,
                "%-45s n=%d mean=%8.3f ms p50=%8.3f p95=%8.3f p99=%8.3f max=%8.3f"
                        + "  [cached<%.0fms: %s, uncached<%.0fms: %s]%n",
                label, samples.length, mean, p50, p95, p99, max,
                CACHED_TARGET_MS, cachedOk ? "PASS" : "FAIL",
                UNCACHED_TARGET_MS, uncachedOk ? "PASS" : "FAIL");
        return cachedOk && uncachedOk;
    }

    private static double percentile(double[] sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}

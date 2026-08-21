package com.holtherndon.bazelviz.benchmarks.jmh;

import com.holtherndon.bazelviz.storage.BatchedInsert;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticAction;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Batched-insert throughput of synthetic actions into a fresh SQLite database,
 * across transaction sizes. Ops/sec is reported per ROW thanks to
 * {@link OperationsPerInvocation}, so the score reads directly as rows/sec.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SqliteInsertBench {

    private static final int ROWS_PER_INVOCATION = 40_000;

    @Param({"1000", "5000", "20000"})
    private int batchSize;

    private Path tempDir;
    private SessionDatabase db;
    private SyntheticActionGenerator generator;
    private long nextIndex;
    private long rowId;

    @Setup(Level.Trial)
    public void openDatabase() throws IOException, SQLException {
        tempDir = Files.createTempDirectory("bbv-jmh-sqlite");
        db = SessionDatabase.open(tempDir.resolve("insert-bench.db"));
        try (Statement statement = db.writerConnection().createStatement()) {
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
        generator = new SyntheticActionGenerator(SyntheticScale.TIER2, 42L);
        nextIndex = 0;
    }

    @Setup(Level.Iteration)
    public void truncate() throws SQLException {
        // Keep the table from growing across iterations so every measured
        // invocation inserts into a comparably sized table.
        try (Statement statement = db.writerConnection().createStatement()) {
            statement.execute("DELETE FROM actions");
        }
    }

    @TearDown(Level.Trial)
    public void closeDatabase() throws IOException, SQLException {
        db.close();
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    public long insertRows() throws SQLException {
        long inserted;
        try (BatchedInsert insert = new BatchedInsert(
                db.writerConnection(),
                "INSERT INTO actions (id, start_micros, end_micros, duration_micros, mnemonic_ix,"
                        + " target_ix, status, cache_state, runner, input_bytes)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchSize)) {
            PreparedStatement statement = insert.statement();
            for (int i = 0; i < ROWS_PER_INVOCATION; i++) {
                SyntheticAction action = generator.actionAt(nextIndex);
                nextIndex = nextIndex + 1 == generator.actionCount() ? 0 : nextIndex + 1;
                // id must stay unique within an iteration even after nextIndex
                // wraps or the iteration reuses indices post-truncate; use a
                // monotonically increasing rowid instead of the action index.
                statement.setLong(1, rowId++);
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
            inserted = insert.rowCount();
        }
        return inserted;
    }
}

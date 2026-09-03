package com.holtherndon.bazelviz.storage.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.FindingRules;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A quarter of a million actions through the real collection path, measured.
 *
 * <p>Small next to the plan's five-million Tier 3 ceiling, and large enough
 * that the two things that would make this unusable — a query per action, or an
 * aggregation that retains every duration — show up as minutes rather than
 * seconds. The figures it prints go into docs/performance.md.
 *
 * <p>Synthetic rather than a real Bazel invocation, deliberately: a build of
 * this size costs a Bazel server and several gigabytes, and nothing being
 * measured here depends on Bazel's behaviour.
 */
final class MetricScaleTest {

    private static final int ACTIONS = 250_000;
    private static final int MNEMONICS = 24;
    private static final int PACKAGES = 400;

    @TempDir
    Path tempDir;

    @Test
    void aQuarterMillionActionsCollectInOnePass() throws Exception {
        try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("scale.db"))) {
            MigrationRunner.standard().migrate(database);
            Connection writer = database.writerConnection();
            long written = System.nanoTime();
            populate(writer);
            long writeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - written);

            long started = System.nanoTime();
            SessionMetrics metrics;
            try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
                metrics = queries.collect(MetricQueries.Request.everything(
                        CriticalPath.DurationSource.EXECUTION_ATTEMPT));
            }
            long collectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            long ruled = System.nanoTime();
            var findings = FindingRules.run(
                    metrics.findingInputs(FindingThresholds.defaults()));
            long ruleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ruled);

            ConcurrencySweep.Result sweep = metrics.concurrency();
            System.out.printf(
                    "MetricScale: %d actions | write %d ms | collect %d ms | rules %d ms%n"
                            + "  peak concurrency %d, parallelism %.2f, %d findings%n"
                            + "  mnemonic groups %d, package groups %d, candidates %d%n",
                    ACTIONS, writeMillis, collectMillis, ruleMillis,
                    sweep.peakActive(), sweep.parallelismFactor().orElse(0), findings.size(),
                    metrics.aggregate(GroupAggregate.Dimension.MNEMONIC).orElseThrow()
                            .totalGroups(),
                    metrics.aggregate(GroupAggregate.Dimension.PACKAGE).orElseThrow()
                            .totalGroups(),
                    metrics.candidates().size());

            // Correctness first: the aggregation must account for every action
            // in every dimension, or the timing above is a measurement of
            // something that does not work.
            for (GroupAggregate.Dimension dimension : GroupAggregate.Dimension.values()) {
                GroupAggregate.Table table = metrics.aggregate(dimension).orElseThrow();
                assertThat(table.totalActions()).as("%s", dimension).isEqualTo(ACTIONS);
            }
            assertThat(metrics.aggregate(GroupAggregate.Dimension.MNEMONIC).orElseThrow()
                    .totalGroups()).isEqualTo(MNEMONICS);
            assertThat(sweep.sweptSpans()).isEqualTo(ACTIONS);
            // The candidate set stays bounded however large the build is; that
            // is the property that lets the rules run at Tier 3 at all.
            assertThat(metrics.candidates().size())
                    .isLessThanOrEqualTo(6 * MetricQueries.DEFAULT_CANDIDATE_LIMIT);
        }
    }

    private void populate(Connection writer) throws Exception {
        try (Statement statement = writer.createStatement()) {
            statement.execute("INSERT INTO event_streams (id, stream_key, state)"
                    + " VALUES (1, 's', 'CLOSED')");
            statement.execute("INSERT INTO enrichment_tasks (id, kind, state)"
                    + " VALUES (1, 'EXECUTION_LOG', 'SUCCEEDED')");
            for (int i = 0; i < MNEMONICS; i++) {
                statement.execute("INSERT INTO mnemonics (id, value) VALUES ("
                        + (i + 1) + ", 'Mnemonic" + i + "')");
            }
            for (int i = 0; i < PACKAGES; i++) {
                statement.execute("INSERT INTO labels (id, value) VALUES ("
                        + (i + 1) + ", '//pkg/" + i + ":lib')");
            }
        }
        boolean autoCommit = writer.getAutoCommit();
        writer.setAutoCommit(false);
        try (PreparedStatement action = writer.prepareStatement(
                "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome)"
                        + " VALUES (?, ?, ?, ?, 'SUCCESS')");
                PreparedStatement attempt = writer.prepareStatement(
                        "INSERT INTO action_attempts (id, task_id, log_entry_index, action_id,"
                                + " correlation, runner, cache_hit, start_micros, total_micros,"
                                + " input_bytes, queue_micros, cacheable, remotable)"
                                + " VALUES (?, 1, ?, ?, 'MATCHED_BY_OUTPUT', ?, ?, ?, ?, ?, ?,"
                                + " 1, 1)")) {
            for (int i = 0; i < ACTIONS; i++) {
                long id = i + 1;
                action.setLong(1, id);
                action.setString(2, "bazel-out/o" + i + ".o");
                action.setLong(3, (i % PACKAGES) + 1);
                action.setLong(4, (i % MNEMONICS) + 1);
                action.addBatch();

                // Eight lanes of work, so the sweep has a real peak to find and
                // the durations spread over four orders of magnitude.
                long start = (i / 8L) * 1_000L;
                long duration = 500L + (i % 977) * 137L;
                attempt.setLong(1, id);
                attempt.setInt(2, i);
                attempt.setLong(3, id);
                attempt.setString(4, i % 3 == 0 ? "remote" : "darwin-sandbox");
                attempt.setInt(5, i % 5 == 0 ? 1 : 0);
                attempt.setLong(6, start);
                attempt.setLong(7, duration);
                attempt.setLong(8, 4_096L * (i % 64 + 1));
                attempt.setLong(9, i % 11 == 0 ? duration * 3 / 4 : 100);
                attempt.addBatch();
                if (i % 10_000 == 0) {
                    action.executeBatch();
                    attempt.executeBatch();
                }
            }
            action.executeBatch();
            attempt.executeBatch();
            writer.commit();
        } finally {
            writer.setAutoCommit(autoCommit);
        }
    }
}

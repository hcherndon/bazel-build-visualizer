package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import java.util.List;

/** The tests table's columns. */
public final class TestTableColumns {

    private TestTableColumns() {}

    public static List<ColumnSpec<TestRow>> columns() {
        return List.of(
                new ColumnSpec<>("Test", TestRow::label),
                new ColumnSpec<>("Status", row -> row.overallStatus().name()),
                // Attempts as they actually happened, not Bazel's attemptCount:
                // that field is the maximum any (run, shard) needed and equals
                // the run count for a healthy multi-run test, so labelling it
                // "retries" would report retries that did not occur.
                new ColumnSpec<>("Attempts", row -> attempts(row)),
                new ColumnSpec<>("Runs", row -> EntityFormat.count(row.totalRunCount())),
                new ColumnSpec<>("Shards", row -> EntityFormat.count(row.shardCount())),
                new ColumnSpec<>("Elapsed", row -> EntityFormat.duration(row.wallMicros())),
                new ColumnSpec<>("Bazel's duration",
                        row -> EntityFormat.duration(row.bazelReportedDurationMicros())),
                new ColumnSpec<>("Cached", row -> Integer.toString(row.totalNumCached())));
    }

    private static String attempts(TestRow row) {
        if (row.attemptRows() == 0) {
            return EntityFormat.UNKNOWN;
        }
        return row.failedAttemptRows() > 0
                ? row.attemptRows() + " (" + row.failedAttemptRows() + " failed)"
                : Long.toString(row.attemptRows());
    }

    public static int[] widths() {
        return new int[] {360, 110, 140, 70, 70, 110, 130, 80};
    }
}

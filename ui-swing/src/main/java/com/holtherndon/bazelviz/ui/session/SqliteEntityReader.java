package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.FailureQueries;
import com.holtherndon.bazelviz.storage.entities.FailureRow;
import com.holtherndon.bazelviz.storage.entities.OverviewQueries;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;

/**
 * {@link EntityReader} over one SQLite connection.
 *
 * <p>The five query classes share the connection, which is why they are
 * constructed here rather than each opening their own: one reader is one
 * thread's worth of reads, and the entity views ask about several kinds of
 * thing while assembling one screen.
 *
 * <p>Each query class implements {@code AutoCloseable} by closing the
 * connection, so exactly one of them may be closed — this class closes the
 * connection itself and never delegates, which keeps "closed twice" from
 * meaning "closed something else's connection".
 */
final class SqliteEntityReader implements EntityReader {

    private final String describedSession;
    private final Connection connection;
    private final OverviewQueries overview;
    private final ActionQueries actions;
    private final TargetQueries targets;
    private final TestQueries tests;
    private final FailureQueries failures;
    private boolean closed;

    SqliteEntityReader(String describedSession, Connection connection) {
        this.describedSession = Objects.requireNonNull(describedSession, "describedSession");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.overview = new OverviewQueries(connection);
        this.actions = new ActionQueries(connection);
        this.targets = new TargetQueries(connection);
        this.tests = new TestQueries(connection);
        this.failures = new FailureQueries(connection);
    }

    @Override
    public OverviewSnapshot overview() {
        return call("reading the overview", overview::snapshot);
    }

    @Override
    public long actionCount() {
        return call("counting actions", actions::totalCount);
    }

    @Override
    public long actionCount(ActionFilter filter) {
        return call("counting matching actions", () -> actions.count(filter));
    }

    @Override
    public List<ActionRow> firstActionPage(
            ActionFilter filter, ActionSort sort, boolean descending, int limit) {
        return call("reading the first page of actions",
                () -> actions.firstPage(filter, sort, descending, limit));
    }

    @Override
    public List<ActionRow> actionsAfter(
            ActionQueries.Anchor anchor,
            ActionFilter filter,
            ActionSort sort,
            boolean descending,
            int limit) {
        return call("reading actions after row " + anchor.id(),
                () -> actions.pageAfter(anchor, filter, sort, descending, limit));
    }

    @Override
    public ActionQueries.Index actionIndex(
            ActionFilter filter, ActionSort sort, boolean descending, int pageSize) {
        return call("indexing actions by " + sort,
                () -> actions.buildIndex(filter, sort, descending, pageSize));
    }

    @Override
    public Optional<ActionRow> action(long id) {
        return call("reading action " + id, () -> actions.action(id));
    }

    @Override
    public List<ActionQueries.MnemonicCount> mnemonics() {
        return call("listing action mnemonics", actions::mnemonics);
    }

    @Override
    public List<TargetQueries.PackageSummary> packages() {
        return call("listing packages", targets::packages);
    }

    @Override
    public List<TargetRow> targetsInPackage(String packagePath) {
        return call("listing targets in " + packagePath, () -> targets.inPackage(packagePath));
    }

    @Override
    public Optional<TargetRow> target(long id) {
        return call("reading target " + id, () -> targets.byId(id));
    }

    @Override
    public List<TargetQueries.Tag> targetTags(long targetId) {
        return call("reading tags of target " + targetId, () -> targets.tags(targetId));
    }

    @Override
    public List<TargetQueries.OutputGroup> outputGroups(long configuredTargetId) {
        return call("reading output groups of " + configuredTargetId,
                () -> targets.outputGroups(configuredTargetId));
    }

    @Override
    public long testCount() {
        return call("counting tests", tests::count);
    }

    @Override
    public List<TestRow> firstTestPage(int limit) {
        return call("reading the first page of tests", () -> tests.firstPage(limit));
    }

    @Override
    public List<TestRow> testsAfter(TestQueries.Anchor anchor, int limit) {
        return call("reading tests after row " + anchor.id(), () -> tests.pageAfter(anchor, limit));
    }

    @Override
    public TestQueries.Index testIndex(int pageSize) {
        return call("indexing tests", () -> tests.buildIndex(pageSize));
    }

    @Override
    public Optional<TestRow> test(long id) {
        return call("reading test " + id, () -> tests.test(id));
    }

    @Override
    public List<TestAttemptRow> testAttempts(long testId) {
        return call("reading attempts of test " + testId, () -> tests.attempts(testId));
    }

    @Override
    public List<TestQueries.TestLog> testLogs(long testId) {
        return call("reading logs of test " + testId, () -> tests.logs(testId));
    }

    @Override
    public FailureCounts failureCounts() {
        return call("counting failures", () -> new FailureCounts(
                failures.failedActionCount(),
                failures.failedTargetCount(),
                failures.abortedCount()));
    }

    @Override
    public List<FailureRow> failedActions(OptionalLong afterId, int limit) {
        return call("reading failed actions", () -> failures.failedActions(afterId, limit));
    }

    @Override
    public List<FailureRow> failedTargets(OptionalLong afterId, int limit) {
        return call("reading failed targets", () -> failures.failedTargets(afterId, limit));
    }

    @Override
    public List<FailureRow> abortedTargets(OptionalLong afterId, int limit) {
        return call("reading aborted targets", () -> failures.abortedTargets(afterId, limit));
    }

    @Override
    public List<FailureQueries.ReasonCount> abortReasons() {
        return call("reading abort reasons", failures::abortReasons);
    }

    @Override
    public List<FailureQueries.ProgressRef> progressOutputEvents(int limit) {
        return call("reading progress output events", () -> failures.progressOutputEvents(limit));
    }

    /**
     * Stops the actions query, which is the only one long enough to be worth
     * abandoning.
     *
     * <p>The others answer in single-digit milliseconds at a million rows
     * (docs/performance.md), so a cancel would arrive after they had already
     * finished. If one of them ever grows a long form, it gets a cancel then;
     * pretending to cancel something that cannot be cancelled would be worse
     * than not offering it.
     */
    @Override
    public void cancelRunningQuery() {
        actions.cancel();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException e) {
            throw new SessionDataException("closing a reader for " + describedSession + " failed", e);
        }
    }

    /**
     * Runs a query and turns its {@code SQLException} into the exception the
     * views understand, with what was being read attached.
     *
     * <p>The description matters: "reading action 41 812 failed" tells the user
     * which part of the screen is missing, where a bare SQL error tells them
     * only that something is.
     */
    private <T> T call(String what, Callable<T> query) {
        try {
            return query.call();
        } catch (Exception e) {
            // Callable widens to Exception, and every query here throws only
            // SQLException; catching the wider type is what the signature
            // forces, not a claim that anything else is expected.
            throw new SessionDataException(what + " in " + describedSession + " failed", e);
        }
    }
}

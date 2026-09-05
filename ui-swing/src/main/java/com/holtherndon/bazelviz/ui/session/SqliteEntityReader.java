package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorRow;
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
 * <p>The five query classes share the connection, which is why they are constructed here rather
 * than each opening their own: one reader is one thread's worth of reads, and the entity views ask
 * about several kinds of thing while assembling one screen.
 *
 * <p>Each query class implements {@code AutoCloseable} by closing the connection, so exactly one of
 * them may be closed — this class closes the connection itself and never delegates, which keeps
 * "closed twice" from meaning "closed something else's connection".
 */
final class SqliteEntityReader implements EntityReader {

  private final String describedSession;
  private final Connection connection;
  private final OverviewQueries overview;
  private final ActionQueries actions;
  private final TargetQueries targets;
  private final TestQueries tests;
  private final ErrorQueries errors;
  private final ConfigurationQueries configurations;
  private final EnrichmentQueries enrichment;
  private boolean closed;

  SqliteEntityReader(String describedSession, Connection connection) {
    this.describedSession = Objects.requireNonNull(describedSession, "describedSession");
    this.connection = Objects.requireNonNull(connection, "connection");
    this.overview = new OverviewQueries(connection);
    this.actions = new ActionQueries(connection);
    this.targets = new TargetQueries(connection);
    this.tests = new TestQueries(connection);
    this.errors = new ErrorQueries(connection);
    this.configurations = new ConfigurationQueries(connection);
    this.enrichment = new EnrichmentQueries(connection);
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
    return call(
        "reading the first page of actions",
        () -> actions.firstPage(filter, sort, descending, limit));
  }

  @Override
  public List<ActionRow> actionsAfter(
      ActionQueries.Anchor anchor,
      ActionFilter filter,
      ActionSort sort,
      boolean descending,
      int limit) {
    return call(
        "reading actions after row " + anchor.id(),
        () -> actions.pageAfter(anchor, filter, sort, descending, limit));
  }

  @Override
  public ActionQueries.Index actionIndex(
      ActionFilter filter, ActionSort sort, boolean descending, int pageSize) {
    return call(
        "indexing actions by " + sort,
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
  public CountedPage<TargetQueries.PackageSummary, String> packagePage(
      String labelText, Optional<String> afterPath, int limit) {
    return call(
        "paging packages matching " + labelText,
        () -> targets.packagePage(labelText, afterPath, limit));
  }

  @Override
  public CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsInPackagePage(
      String packagePath, String labelText, Optional<TargetQueries.TargetAnchor> after, int limit) {
    return call(
        "paging targets in " + packagePath,
        () -> targets.targetsInPackagePage(packagePath, labelText, after, limit));
  }

  @Override
  public CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsByLabelPage(
      String label, Optional<TargetQueries.TargetAnchor> after, int limit) {
    return call(
        "paging targets labelled " + label, () -> targets.targetsByLabelPage(label, after, limit));
  }

  @Override
  public CountedPage<String, String> topLevelLabelPage(
      String labelText, Optional<String> after, int limit) {
    return call(
        "paging top-level target labels matching " + labelText,
        () -> targets.topLevelLabelPage(labelText, after, limit));
  }

  @Override
  public CountedPage<TargetQueries.LabelSummary, String> labelPage(
      String labelText, Optional<String> after, int limit) {
    return call(
        "paging configured-target labels matching " + labelText,
        () -> targets.labelPage(labelText, after, limit));
  }

  @Override
  public CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor>
      configurationGroupPage(
          String label, Optional<TargetQueries.ConfigurationAnchor> after, int limit) {
    return call(
        "paging cquery configuration groups for " + label,
        () -> targets.configurationGroupPage(label, after, limit));
  }

  @Override
  public CountedPage<TargetQueries.ConfiguredTarget, Long> configuredTargetPage(
      String label, Optional<String> configuration, OptionalLong afterId, int limit) {
    return call(
        "paging cquery target variants for " + label,
        () -> targets.configuredTargetPage(label, configuration, afterId, limit));
  }

  @Override
  public CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor> tagPage(
      long targetId, Optional<TargetQueries.TagAnchor> after, int limit) {
    return call("paging tags of target " + targetId, () -> targets.tagPage(targetId, after, limit));
  }

  @Override
  public CountedPage<TargetQueries.OutputGroup, Long> outputGroupPage(
      long configuredTargetId, OptionalLong afterOrdinal, int limit) {
    return call(
        "paging output groups of " + configuredTargetId,
        () -> targets.outputGroupPage(configuredTargetId, afterOrdinal, limit));
  }

  @Override
  public Optional<TargetQueries.ConfiguredSource> configuredTargetSource() {
    return call("reading the cquery target source", targets::configuredSource);
  }

  @Override
  public Optional<TargetRow> target(long id) {
    return call("reading target " + id, () -> targets.byId(id));
  }

  @Override
  public long configurationCount() {
    return call("counting configurations", configurations::count);
  }

  @Override
  public List<ConfigurationQueries.Summary> configurations(long offset, int limit) {
    return call(
        "reading configurations at offset " + offset, () -> configurations.page(offset, limit));
  }

  @Override
  public Optional<ConfigurationQueries.Summary> configuration(String checksum) {
    return call("reading configuration " + checksum, () -> configurations.summary(checksum));
  }

  @Override
  public OptionalLong configurationPosition(String checksum) {
    return call("locating configuration " + checksum, () -> configurations.position(checksum));
  }

  @Override
  public Optional<ConfigurationQueries.Source> configurationSource() {
    return call("reading configuration source", configurations::source);
  }

  @Override
  public long configurationValueCount(String checksum) {
    return call(
        "counting values for configuration " + checksum, () -> configurations.valueCount(checksum));
  }

  @Override
  public List<ConfigurationQueries.Value> configurationValues(
      String checksum, long offset, int limit) {
    return call(
        "reading values for configuration " + checksum,
        () -> configurations.values(checksum, offset, limit));
  }

  @Override
  public long configurationDifferenceCount(String baseline, String candidate) {
    return call(
        "comparing configurations " + baseline + " and " + candidate,
        () -> configurations.differenceCount(baseline, candidate));
  }

  @Override
  public List<ConfigurationQueries.Difference> configurationDifferences(
      String baseline, String candidate, long offset, int limit) {
    return call(
        "reading configuration differences at offset " + offset,
        () -> configurations.differences(baseline, candidate, offset, limit));
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
  public CountedPage<TestAttemptRow, TestQueries.TestAttemptAnchor> testAttemptPage(
      long testId, Optional<TestQueries.TestAttemptAnchor> after, int limit) {
    return call("paging attempts of test " + testId, () -> tests.attemptPage(testId, after, limit));
  }

  @Override
  public CountedPage<TestQueries.TestLog, Long> testLogPage(
      long testId, Optional<Long> afterId, int limit) {
    return call("paging logs of test " + testId, () -> tests.logPage(testId, afterId, limit));
  }

  @Override
  public ErrorCounts errorCounts() {
    return call(
        "counting errors",
        () ->
            new ErrorCounts(
                errors.failedActionCount(), errors.failedTargetCount(), errors.abortedCount()));
  }

  @Override
  public List<ErrorRow> failedActions(OptionalLong afterId, int limit) {
    return call("reading failed actions", () -> errors.failedActions(afterId, limit));
  }

  @Override
  public List<ErrorRow> failedTargets(OptionalLong afterId, int limit) {
    return call("reading failed targets", () -> errors.failedTargets(afterId, limit));
  }

  @Override
  public List<ErrorRow> abortedTargets(OptionalLong afterId, int limit) {
    return call("reading aborted targets", () -> errors.abortedTargets(afterId, limit));
  }

  @Override
  public List<ErrorQueries.ReasonCount> abortReasons() {
    return call("reading abort reasons", errors::abortReasons);
  }

  @Override
  public List<ErrorQueries.ProgressRef> progressOutputEvents(int limit) {
    return call("reading progress output events", () -> errors.progressOutputEvents(limit));
  }

  // ------------------------------------------------------------ enrichment

  @Override
  public CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForActionPage(
      long actionId, Optional<EnrichmentQueries.AttemptAnchor> after, int limit) {
    return call(
        "paging attempts for action " + actionId,
        () -> enrichment.attemptsForActionPage(actionId, after, limit));
  }

  @Override
  public CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForLabelPage(
      String label, Optional<EnrichmentQueries.AttemptAnchor> after, int limit) {
    return call(
        "paging attempts for " + label, () -> enrichment.attemptsForLabelPage(label, after, limit));
  }

  @Override
  public EnrichmentQueries.Coverage enrichmentCoverage() {
    return call("reading enrichment coverage", enrichment::coverage);
  }

  @Override
  public List<EnrichmentQueries.Phase> buildPhases() {
    return call("reading build phases", enrichment::phases);
  }

  @Override
  public List<EnrichmentQueries.CriticalPathComponent> bazelCriticalPath() {
    return call("reading Bazel's critical path", enrichment::bazelCriticalPath);
  }

  @Override
  public List<EnrichmentQueries.RunnerCount> runnerCounts() {
    return call("counting runners", enrichment::runnerCounts);
  }

  @Override
  public Optional<ProfileAnchor> profileAnchor() {
    return call("reading the profile anchor", enrichment::anchor);
  }

  @Override
  public List<EnrichmentTask> enrichmentTasks() {
    return call("reading enrichment tasks", () -> new EnrichmentTaskStore(connection).all());
  }

  @Override
  public void cancelRunningQuery() {
    actions.cancel();
    targets.cancel();
    tests.cancel();
    enrichment.cancel();
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
   * Runs a query and turns its {@code SQLException} into the exception the views understand, with
   * what was being read attached.
   *
   * <p>The description matters: "reading action 41 812 failed" tells the user which part of the
   * screen is missing, where a bare SQL error tells them only that something is.
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

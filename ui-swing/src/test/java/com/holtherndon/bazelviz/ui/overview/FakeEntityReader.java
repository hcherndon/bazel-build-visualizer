package com.holtherndon.bazelviz.ui.overview;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link EntityReader} whose numbers change on every read, counting how often it is asked.
 *
 * <p>Exists so the overview's coalescing can be measured rather than asserted: a panel that
 * refreshed per change would call {@link #overview()} as fast as the numbers move, and a panel that
 * refreshes on a timer calls it once per interval however fast they move.
 */
class FakeEntityReader implements EntityReader {

  private final AtomicLong overviewReads = new AtomicLong();

  /** A number that advances on its own, standing in for a build in progress. */
  private final AtomicLong actions = new AtomicLong();

  long overviewReads() {
    return overviewReads.get();
  }

  /** Advances the underlying data, as a running capture would. */
  void advance() {
    actions.incrementAndGet();
  }

  @Override
  public OverviewSnapshot overview() {
    overviewReads.incrementAndGet();
    return new OverviewSnapshot(
        Optional.of("9.2.0"),
        Optional.of("build"),
        Optional.of("/ws"),
        Optional.of(true),
        Optional.empty(),
        Optional.empty(),
        OptionalLong.empty(),
        false,
        0,
        0,
        0,
        0,
        0,
        actions.get(),
        0,
        0,
        0,
        0,
        0,
        0,
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        List.of());
  }

  @Override
  public long actionCount() {
    return actions.get();
  }

  @Override
  public long actionCount(ActionFilter filter) {
    return 0;
  }

  @Override
  public List<ActionRow> firstActionPage(
      ActionFilter filter, ActionSort sort, boolean descending, int limit) {
    return List.of();
  }

  @Override
  public List<ActionRow> actionsAfter(
      ActionQueries.Anchor anchor,
      ActionFilter filter,
      ActionSort sort,
      boolean descending,
      int limit) {
    return List.of();
  }

  @Override
  public ActionQueries.Index actionIndex(
      ActionFilter filter, ActionSort sort, boolean descending, int pageSize) {
    return new ActionQueries.Index(0, List.of());
  }

  @Override
  public Optional<ActionRow> action(long id) {
    return Optional.empty();
  }

  @Override
  public List<ActionQueries.MnemonicCount> mnemonics() {
    return List.of();
  }

  @Override
  public CountedPage<TargetQueries.PackageSummary, String> packagePage(
      String labelText, Optional<String> afterPath, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsInPackagePage(
      String packagePath, String labelText, Optional<TargetQueries.TargetAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsByLabelPage(
      String label, Optional<TargetQueries.TargetAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<String, String> topLevelLabelPage(
      String labelText, Optional<String> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetQueries.LabelSummary, String> labelPage(
      String labelText, Optional<String> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor>
      configurationGroupPage(
          String label, Optional<TargetQueries.ConfigurationAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetQueries.ConfiguredTarget, Long> configuredTargetPage(
      String label, Optional<String> configuration, OptionalLong afterId, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor> tagPage(
      long targetId, Optional<TargetQueries.TagAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TargetQueries.OutputGroup, Long> outputGroupPage(
      long configuredTargetId, OptionalLong afterOrdinal, int limit) {
    return emptyPage();
  }

  @Override
  public Optional<TargetQueries.ConfiguredSource> configuredTargetSource() {
    return Optional.empty();
  }

  @Override
  public Optional<TargetRow> target(long id) {
    return Optional.empty();
  }

  @Override
  public long configurationCount() {
    return 0;
  }

  @Override
  public List<ConfigurationQueries.Summary> configurations(long offset, int limit) {
    return List.of();
  }

  @Override
  public Optional<ConfigurationQueries.Summary> configuration(String checksum) {
    return Optional.empty();
  }

  @Override
  public OptionalLong configurationPosition(String checksum) {
    return OptionalLong.empty();
  }

  @Override
  public Optional<ConfigurationQueries.Source> configurationSource() {
    return Optional.empty();
  }

  @Override
  public long configurationValueCount(String checksum) {
    return 0;
  }

  @Override
  public List<ConfigurationQueries.Value> configurationValues(
      String checksum, long offset, int limit) {
    return List.of();
  }

  @Override
  public long configurationDifferenceCount(String baseline, String candidate) {
    return 0;
  }

  @Override
  public List<ConfigurationQueries.Difference> configurationDifferences(
      String baseline, String candidate, long offset, int limit) {
    return List.of();
  }

  @Override
  public long testCount() {
    return 0;
  }

  @Override
  public List<TestRow> firstTestPage(int limit) {
    return List.of();
  }

  @Override
  public List<TestRow> testsAfter(TestQueries.Anchor anchor, int limit) {
    return List.of();
  }

  @Override
  public TestQueries.Index testIndex(int pageSize) {
    return new TestQueries.Index(0, List.of());
  }

  @Override
  public Optional<TestRow> test(long id) {
    return Optional.empty();
  }

  @Override
  public CountedPage<TestAttemptRow, TestQueries.TestAttemptAnchor> testAttemptPage(
      long testId, Optional<TestQueries.TestAttemptAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<TestQueries.TestLog, Long> testLogPage(
      long testId, Optional<Long> afterId, int limit) {
    return emptyPage();
  }

  @Override
  public ErrorCounts errorCounts() {
    return new ErrorCounts(0, 0, 0);
  }

  @Override
  public List<ErrorRow> failedActions(OptionalLong afterId, int limit) {
    return List.of();
  }

  @Override
  public List<ErrorRow> failedTargets(OptionalLong afterId, int limit) {
    return List.of();
  }

  @Override
  public List<ErrorRow> abortedTargets(OptionalLong afterId, int limit) {
    return List.of();
  }

  @Override
  public List<ErrorQueries.ReasonCount> abortReasons() {
    return List.of();
  }

  @Override
  public List<ErrorQueries.ProgressRef> progressOutputEvents(int limit) {
    return List.of();
  }

  // ---- enrichment: this fake has no enrichment data, and says so honestly ----

  @Override
  public CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForActionPage(
      long actionId, Optional<EnrichmentQueries.AttemptAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForLabelPage(
      String label, Optional<EnrichmentQueries.AttemptAnchor> after, int limit) {
    return emptyPage();
  }

  @Override
  public EnrichmentQueries.Coverage enrichmentCoverage() {
    return new EnrichmentQueries.Coverage(0, 0, 0, 0, 0, Map.of());
  }

  @Override
  public List<EnrichmentQueries.Phase> buildPhases() {
    return List.of();
  }

  @Override
  public List<EnrichmentQueries.CriticalPathComponent> bazelCriticalPath() {
    return List.of();
  }

  @Override
  public List<EnrichmentQueries.RunnerCount> runnerCounts() {
    return List.of();
  }

  @Override
  public Optional<ProfileAnchor> profileAnchor() {
    return Optional.empty();
  }

  @Override
  public List<EnrichmentTask> enrichmentTasks() {
    return List.of();
  }

  @Override
  public void cancelRunningQuery() {
    // Nothing runs long enough to need stopping.
  }

  @Override
  public void close() {
    // No resources.
  }

  private static <T, A> CountedPage<T, A> emptyPage() {
    return new CountedPage<>(List.of(), 0, 0, Optional.empty());
  }
}

package com.holtherndon.bazelviz.ui.session;

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
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The read service the Phase 3 entity views are written against — the same arrangement {@link
 * SessionReader} makes for the Events view, and for the same reason (plan rule 19: UI components
 * depend on service interfaces, not on SQLite implementation classes).
 *
 * <p>The row records come from {@code storage-sqlite} and are shared; connections, statements and
 * {@code SQLException} do not cross this line. A failure surfaces as {@link SessionDataException},
 * which the views can show, rather than as a checked exception every model would have to relay.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method blocks on SQL, so <b>none may be called on the Swing EDT</b>. One reader is bound
 * to one thread; each view's fetch executor gets its own from {@link
 * SessionSource#openEntityReader()}.
 */
public interface EntityReader extends AutoCloseable {

  /** Everything the overview shows, from one read. */
  OverviewSnapshot overview();

  // --- actions ----------------------------------------------------------

  /** How many actions the session holds, before any filter. */
  long actionCount();

  /** How many actions match {@code filter}. */
  long actionCount(ActionFilter filter);

  /** The first page under {@code sort}. */
  List<ActionRow> firstActionPage(
      ActionFilter filter, ActionSort sort, boolean descending, int limit);

  /** The page after {@code anchor}, under the sort the anchor came from. */
  List<ActionRow> actionsAfter(
      ActionQueries.Anchor anchor,
      ActionFilter filter,
      ActionSort sort,
      boolean descending,
      int limit);

  /**
   * Anchors for every page boundary under one (filter, sort, direction), plus the matching row
   * count.
   *
   * <p>One ordered scan reading only the sort value and the id. It is what lets the table jump to
   * any page without OFFSET and without walking the pages before it. Rebuilt whenever any of the
   * three changes, because all three change which row sits at a given index.
   */
  ActionQueries.Index actionIndex(
      ActionFilter filter, ActionSort sort, boolean descending, int pageSize);

  Optional<ActionRow> action(long id);

  /** The action types present, most common first, for the filter control. */
  List<ActionQueries.MnemonicCount> mnemonics();

  // --- targets ----------------------------------------------------------

  CountedPage<TargetQueries.PackageSummary, String> packagePage(
      String labelText, Optional<String> afterPath, int limit);

  CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsInPackagePage(
      String packagePath, String labelText, Optional<TargetQueries.TargetAnchor> after, int limit);

  CountedPage<TargetRow, TargetQueries.TargetAnchor> targetsByLabelPage(
      String label, Optional<TargetQueries.TargetAnchor> after, int limit);

  CountedPage<String, String> topLevelLabelPage(
      String labelText, Optional<String> after, int limit);

  CountedPage<TargetQueries.LabelSummary, String> labelPage(
      String labelText, Optional<String> after, int limit);

  CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor>
      configurationGroupPage(
          String label, Optional<TargetQueries.ConfigurationAnchor> after, int limit);

  CountedPage<TargetQueries.ConfiguredTarget, Long> configuredTargetPage(
      String label, Optional<String> configuration, OptionalLong afterId, int limit);

  CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor> tagPage(
      long targetId, Optional<TargetQueries.TagAnchor> after, int limit);

  CountedPage<TargetQueries.OutputGroup, Long> outputGroupPage(
      long configuredTargetId, OptionalLong afterOrdinal, int limit);

  Optional<TargetQueries.ConfiguredSource> configuredTargetSource();

  Optional<TargetRow> target(long id);

  // --- configurations --------------------------------------------------

  long configurationCount();

  List<ConfigurationQueries.Summary> configurations(long offset, int limit);

  Optional<ConfigurationQueries.Summary> configuration(String checksum);

  /** Zero-based position in the checksum-ordered configuration list. */
  OptionalLong configurationPosition(String checksum);

  Optional<ConfigurationQueries.Source> configurationSource();

  long configurationValueCount(String checksum);

  List<ConfigurationQueries.Value> configurationValues(String checksum, long offset, int limit);

  long configurationDifferenceCount(String baseline, String candidate);

  List<ConfigurationQueries.Difference> configurationDifferences(
      String baseline, String candidate, long offset, int limit);

  // --- tests ------------------------------------------------------------

  long testCount();

  /** The first page, worst results first. */
  List<TestRow> firstTestPage(int limit);

  List<TestRow> testsAfter(TestQueries.Anchor anchor, int limit);

  /** Anchors for every page boundary of the tests table, plus the row count. */
  TestQueries.Index testIndex(int pageSize);

  Optional<TestRow> test(long id);

  CountedPage<TestAttemptRow, TestQueries.TestAttemptAnchor> testAttemptPage(
      long testId, Optional<TestQueries.TestAttemptAnchor> after, int limit);

  CountedPage<TestQueries.TestLog, Long> testLogPage(
      long testId, Optional<Long> afterId, int limit);

  // --- errors -----------------------------------------------------------

  /** The three failure counts, which do not scale together. */
  ErrorCounts errorCounts();

  List<ErrorRow> failedActions(OptionalLong afterId, int limit);

  List<ErrorRow> failedTargets(OptionalLong afterId, int limit);

  /** Targets an abort named. Listed on request; the count is what is shown. */
  List<ErrorRow> abortedTargets(OptionalLong afterId, int limit);

  /** Abort reasons and their counts. */
  List<ErrorQueries.ReasonCount> abortReasons();

  /** Progress events carrying console error output, for compiler diagnostics. */
  List<ErrorQueries.ProgressRef> progressOutputEvents(int limit);

  /**
   * How much of each kind of bad news there is.
   *
   * <p>Kept as one record because the view has to show all three together: "3 actions failed"
   * beside "12,000 targets not built" is a different message from either alone, and the second
   * number is the one that explains the first.
   */
  record ErrorCounts(long failedActions, long failedTargets, long aborted) {
    public long total() {
      return failedActions + failedTargets + aborted;
    }

    public boolean isEmpty() {
      return total() == 0;
    }
  }

  // ------------------------------------------------------------ enrichment

  CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForActionPage(
      long actionId, Optional<EnrichmentQueries.AttemptAnchor> after, int limit);

  CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> attemptsForLabelPage(
      String label, Optional<EnrichmentQueries.AttemptAnchor> after, int limit);

  /** How much of the build each enrichment source covers. */
  EnrichmentQueries.Coverage enrichmentCoverage();

  /** The build's phases, in order, as the profile reported them. */
  List<EnrichmentQueries.Phase> buildPhases();

  /** Bazel's own critical path, in order and unjoined to actions. */
  List<EnrichmentQueries.CriticalPathComponent> bazelCriticalPath();

  /** How many attempts ran under each runner. */
  List<EnrichmentQueries.RunnerCount> runnerCounts();

  /** The profile's absolute anchor and what it means, or empty when no profile was imported. */
  Optional<ProfileAnchor> profileAnchor();

  /** Every enrichment task and how it ended. */
  List<EnrichmentTask> enrichmentTasks();

  /** Requests cancellation without making the caller perform JDBC I/O. */
  void cancelRunningQuery();

  @Override
  void close();
}

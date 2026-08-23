package com.holtherndon.bazelviz.ui.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the findings card puts on screen, rendered headlessly from a result the
 * test builds by hand.
 *
 * <p>Building the result by hand is the point of the fixture as much as the
 * convenience: this view is handed a finished collection and renders it, so a
 * test that needs no database is evidence that the view needs none either.
 */
final class FindingsViewTest {

    private static InvocationMetrics invocation(CriticalPaths paths, List<Coverage> coverage) {
        ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
        spans.add(0, 1_000_000);
        spans.add(500_000, 1_500_000);
        return new InvocationMetrics(
                new InvocationMetrics.Timing(
                        Measured.of(2_000_000L, DataSource.BEP),
                        Measured.unknown(DataSource.BES_ENVELOPE, Completeness.UNAVAILABLE, "n/a"),
                        List.of()),
                new InvocationMetrics.Work(2, 2, 2, 0, 0, 1, 1, 0, List.of()),
                new InvocationMetrics.Bytes(
                        Measured.of(1_024L, DataSource.EXECUTION_LOG), 0,
                        Measured.of(2_048L, DataSource.BEP), 1),
                Optional.of(ConcurrencySweep.sweep(spans)),
                paths,
                new InvocationMetrics.Tests(0, 0, 0, 0),
                new InvocationMetrics.Ingest(4, 0, 0,
                        Measured.of(300L, DataSource.BES_ENVELOPE), 2, 0),
                new Coverage.Report(coverage));
    }

    private static SessionMetrics metrics(CriticalPaths paths, List<Coverage> coverage) {
        ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
        spans.add(0, 1_000_000);
        return new SessionMetrics(
                CriticalPath.DurationSource.EXECUTION_ATTEMPT,
                invocation(paths, coverage),
                Map.of(),
                spans,
                ConcurrencySweep.sweep(spans),
                List.of(),
                List.of());
    }

    private static Finding finding(String title) {
        return new Finding(
                "test-rule", title, Finding.Severity.HIGH, Finding.Confidence.MEDIUM,
                List.of(Finding.Evidence.action(11, "//pkg:lib", "took 4.2 s")),
                List.of(new Finding.MetricValue("Duration", "4.2 s", "execution log")),
                "above 1 s",
                "This may be worth investigating.",
                "Measured over one build on one machine.",
                "Open the action and look at its inputs.",
                List.of(Finding.Link.to(Finding.Link.View.TIMELINE, "See it on the timeline")),
                false);
    }

    private static CriticalPaths bothPaths() {
        CriticalPath.Result derived = CriticalPath.compute(
                CsrBuilder.build(2, visitor -> visitor.edge(0, 1)),
                new long[] {400_000, 300_000},
                CriticalPath.DurationSource.EXECUTION_ATTEMPT);
        return new CriticalPaths(
                Measured.of(1_100_000L, DataSource.PROFILE),
                List.of(new CriticalPaths.BazelComponent(
                        0, "action 'Compiling foo.cc'", OptionalLong.of(1_100_000))),
                Optional.of(derived));
    }

    @Test
    @DisplayName("both critical paths appear, under their own names, as two rows")
    void bothCriticalPathsAreShown() {
        FindingsView view = new FindingsView();

        view.show(new MetricsService.Result(
                metrics(bothPaths(), List.of()), List.of(finding("Something is slow")),
                FindingThresholds.defaults()));

        String summary = view.summaryTextForTest();
        assertThat(summary)
                .contains("Bazel-reported critical path")
                .contains("Visualizer-computed dependency critical path")
                .contains("they measure different things");
        // There is no row that simply says "Critical path": the unqualified
        // name is the one this whole arrangement exists to prevent.
        assertThat(summary.lines().filter(line -> line.equals("Critical path")))
                .as("an unqualified critical-path row")
                .isEmpty();
    }

    @Test
    @DisplayName("a missing path says why it is missing rather than borrowing the other's number")
    void anAbsentPathExplainsItself() {
        FindingsView view = new FindingsView();
        CriticalPaths onlyBazel = new CriticalPaths(
                Measured.of(900_000L, DataSource.PROFILE), List.of(), Optional.empty());

        view.show(new MetricsService.Result(
                metrics(onlyBazel, List.of()), List.of(), FindingThresholds.defaults()));

        assertThat(view.summaryTextForTest())
                .contains("no imported action graph to compute it over");
        assertThat(view.headlineForTest()).contains("No findings");
    }

    @Test
    @DisplayName("coverage is on the same screen as the findings that rest on it")
    void coverageIsShownBesideTheFindings() {
        FindingsView view = new FindingsView();
        List<Coverage> coverage = List.of(
                Coverage.of("Timing coverage", 4, 13, DataSource.EXECUTION_LOG,
                        "most actions never spawn a subprocess"),
                Coverage.unavailable("Action-graph coverage", 13, DataSource.AQUERY,
                        "no aquery output was imported"));

        view.show(new MetricsService.Result(
                metrics(bothPaths(), coverage), List.of(finding("Something is slow")),
                FindingThresholds.defaults()));

        assertThat(view.summaryTextForTest())
                .contains("Timing coverage")
                .contains("30.8%")
                .contains("Action-graph coverage")
                .contains("unavailable");
    }

    @Test
    @DisplayName("selecting a finding shows every part plan 16 requires")
    void everyPartOfAFindingIsRendered() {
        FindingsView view = new FindingsView();

        view.show(new MetricsService.Result(
                metrics(bothPaths(), List.of()), List.of(finding("Something is slow")),
                FindingThresholds.defaults()));
        view.selectForTest(0);

        String detail = view.detailTextForTest();
        assertThat(detail)
                .contains("Something is slow")
                .contains("Severity High")
                .contains("confidence Medium")
                .contains("Why it may matter")
                .contains("This may be worth investigating.")
                .contains("Threshold used")
                .contains("above 1 s")
                .contains("Duration: 4.2 s")
                .contains("Source — execution log")
                .contains("//pkg:lib")
                .contains("Caveats")
                .contains("Suggested next investigation")
                .contains("See it on the timeline");
    }

    @Test
    @DisplayName("the evidence button reports the action it points at")
    void evidenceNavigates() {
        FindingsView view = new FindingsView();
        List<Long> opened = new ArrayList<>();
        view.onActionSelected(opened::add);

        view.show(new MetricsService.Result(
                metrics(bothPaths(), List.of()), List.of(finding("Something is slow")),
                FindingThresholds.defaults()));
        view.selectForTest(0);
        clickButtonLabelled(view, "//pkg:lib — took 4.2 s");

        assertThat(opened).containsExactly(11L);
    }

    @Test
    @DisplayName("a link reports the view the rule named")
    void linksNavigate() {
        FindingsView view = new FindingsView();
        List<Finding.Link> followed = new ArrayList<>();
        view.onNavigate(followed::add);

        view.show(new MetricsService.Result(
                metrics(bothPaths(), List.of()), List.of(finding("Something is slow")),
                FindingThresholds.defaults()));
        view.selectForTest(0);
        clickButtonLabelled(view, "See it on the timeline");

        assertThat(followed).hasSize(1);
        assertThat(followed.getFirst().view()).isEqualTo(Finding.Link.View.TIMELINE);
    }

    @Test
    @DisplayName("the view holds no connection and no executor; the service holds both")
    void theViewCannotRead() throws Exception {
        // The Phase 6 and Phase 7 pattern: assert what the class can reach, not
        // what it happens to call. A view that could open a connection would be
        // one release away from doing so on the EDT.
        for (Field field : FindingsView.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("FindingsView.%s", field.getName())
                    .doesNotContain("java.sql")
                    .doesNotContain("storage.metrics.MetricQueries");
            assertThat(Executor.class.isAssignableFrom(field.getType()))
                    .as("FindingsView.%s is an executor", field.getName())
                    .isFalse();
        }
        // And the split has not collapsed: the service really does hold one.
        assertThat(java.util.Arrays.stream(MetricsService.class.getDeclaredFields())
                        .anyMatch(field -> ExecutorService.class.isAssignableFrom(field.getType())))
                .as("MetricsService must own the executor")
                .isTrue();
    }

    private static void clickButtonLabelled(java.awt.Container root, String text) {
        javax.swing.AbstractButton button = findButton(root, text);
        assertThat(button).as("a button labelled \"%s\"", text).isNotNull();
        button.doClick();
    }

    private static javax.swing.AbstractButton findButton(java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.AbstractButton button
                    && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container container) {
                javax.swing.AbstractButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

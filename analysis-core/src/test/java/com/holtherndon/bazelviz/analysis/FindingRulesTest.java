package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each rule fires on the shape it is about, and does not fire on the shape it
 * is not.
 *
 * <p>Plan 24's Phase 8 exit criterion is that formulas have deterministic unit
 * tests, and a rule has two halves that can each be wrong: the arithmetic, and
 * the threshold it compares against. Both halves get a test, because a rule
 * that fires on everything is as useless as one that never fires and looks
 * considerably more helpful.
 */
final class FindingRulesTest {

    private static final FindingThresholds THRESHOLDS = FindingThresholds.defaults();

    // --- fixture builders --------------------------------------------------

    private static InvocationMetrics invocation(
            OptionalLong wallMicros,
            Optional<ConcurrencySweep.Result> sweep,
            CriticalPaths paths,
            List<Coverage> coverage) {
        return new InvocationMetrics(
                new InvocationMetrics.Timing(
                        wallMicros.isPresent()
                                ? Measured.of(wallMicros.getAsLong(), DataSource.BEP)
                                : Measured.unknown(DataSource.BEP, Completeness.UNAVAILABLE,
                                        "no finish"),
                        Measured.unknown(DataSource.BES_ENVELOPE, Completeness.UNAVAILABLE, "n/a"),
                        List.of()),
                new InvocationMetrics.Work(0, 0, 0, 0, 0, 0, 0, 0, List.of()),
                new InvocationMetrics.Bytes(
                        Measured.unknown(DataSource.EXECUTION_LOG, Completeness.UNAVAILABLE, "n/a"),
                        0,
                        Measured.unknown(DataSource.BEP, Completeness.UNAVAILABLE, "n/a"),
                        0),
                sweep,
                paths,
                new InvocationMetrics.Tests(0, 0, 0, 0),
                new InvocationMetrics.Ingest(0, 0, 0,
                        Measured.unknown(DataSource.BES_ENVELOPE, Completeness.UNAVAILABLE, "n/a"),
                        0, 0),
                new Coverage.Report(coverage));
    }

    private static ActionMetrics action(long id, String label, String mnemonic) {
        return new ActionMetrics(
                id, label, mnemonic, "remote", "SUCCESS",
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), 1, ActionMetrics.CacheState.MISS,
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), false,
                OptionalLong.empty(), OptionalLong.empty());
    }

    private static ActionMetrics with(ActionMetrics base, Mutator mutator) {
        return mutator.apply(base);
    }

    @FunctionalInterface
    private interface Mutator {
        ActionMetrics apply(ActionMetrics base);
    }

    private static GroupAggregate group(
            String key, long actions, long durationEach, long hits, long misses, long unknown,
            long notCacheable) {
        MetricSeries.Builder duration = MetricSeries.builder(
                "Subprocess time", MetricSeries.Units.MICROSECONDS, DataSource.EXECUTION_LOG);
        for (long i = 0; i < actions; i++) {
            duration.observe(durationEach);
        }
        MetricSeries.Builder inputs = MetricSeries.builder(
                "Known input bytes", MetricSeries.Units.BYTES, DataSource.EXECUTION_LOG);
        for (long i = 0; i < actions; i++) {
            inputs.unavailable();
        }
        return new GroupAggregate(
                GroupAggregate.Dimension.MNEMONIC, key, actions, duration.build(), inputs.build(),
                hits, misses, unknown, notCacheable, 0);
    }

    private static Map<GroupAggregate.Dimension, GroupAggregate.Table> byMnemonic(
            GroupAggregate... groups) {
        Map<GroupAggregate.Dimension, GroupAggregate.Table> tables =
                new EnumMap<>(GroupAggregate.Dimension.class);
        long actions = 0;
        for (GroupAggregate aggregate : groups) {
            actions += aggregate.actions();
        }
        tables.put(GroupAggregate.Dimension.MNEMONIC, new GroupAggregate.Table(
                GroupAggregate.Dimension.MNEMONIC, List.of(groups), groups.length, actions));
        return tables;
    }

    private static CriticalPaths noPaths() {
        return CriticalPaths.none("no profile imported");
    }

    private static List<Finding> run(FindingInputs inputs) {
        return FindingRules.run(inputs);
    }

    private static List<String> ruleIds(List<Finding> findings) {
        List<String> ids = new ArrayList<>();
        for (Finding finding : findings) {
            ids.add(finding.ruleId());
        }
        return ids;
    }

    // --- rules -------------------------------------------------------------

    @Test
    @DisplayName("a chain accounting for most of the wall clock is reported, a short one is not")
    void longCriticalChain() {
        // A four-node chain of 250 ms each: a one-second dependency chain.
        CsrGraph chain = CsrBuilder.build(4, visitor -> {
            visitor.edge(0, 1);
            visitor.edge(1, 2);
            visitor.edge(2, 3);
        });
        CriticalPath.Result path = CriticalPath.compute(
                chain, new long[] {250_000, 250_000, 250_000, 250_000},
                CriticalPath.DurationSource.EXECUTION_ATTEMPT);
        CriticalPaths paths = new CriticalPaths(
                Measured.of(900_000L, DataSource.PROFILE), List.of(), Optional.of(path));

        FindingInputs slowBuild = new FindingInputs(
                invocation(OptionalLong.of(1_200_000), Optional.empty(), paths, List.of()),
                Map.of(), List.of(),
                List.of(with(action(1, "//pkg:a", "Javac"), base -> withDuration(base, 250_000))),
                List.of(with(action(1, "//pkg:a", "Javac"), base -> withDuration(base, 250_000))),
                THRESHOLDS);
        assertThat(ruleIds(run(slowBuild))).contains("long-critical-chain");

        // The same chain inside a build that took ten times as long is not
        // what set the build's length.
        FindingInputs longBuild = new FindingInputs(
                invocation(OptionalLong.of(12_000_000), Optional.empty(), paths, List.of()),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS);
        assertThat(ruleIds(run(longBuild))).doesNotContain("long-critical-chain");
    }

    @Test
    @DisplayName("the chain finding says it is derived and uses structural completeness")
    void longCriticalChainNamesItself() {
        CsrGraph chain = CsrBuilder.build(2, visitor -> visitor.edge(0, 1));
        CriticalPath.Result path = CriticalPath.compute(
                chain, new long[] {500_000, CriticalPath.UNKNOWN_DURATION},
                CriticalPath.DurationSource.EXECUTION_ATTEMPT);
        CriticalPaths paths = new CriticalPaths(
                Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE, "no profile"),
                List.of(), Optional.of(path));

        Coverage completeGraph = Coverage.of(
                "Action-graph completeness", 2, 2, DataSource.AQUERY,
                "every artifact path and depset reference resolved");
        Finding finding = run(new FindingInputs(
                invocation(OptionalLong.of(600_000), Optional.empty(), paths,
                        List.of(completeGraph)),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS)).getFirst();

        assertThat(finding.metrics()).extracting(Finding.MetricValue::name)
                .contains("Visualizer-computed dependency critical path")
                .contains("Action-graph completeness")
                .doesNotContain("Action-graph correlation");
        assertThat(finding.metrics().stream()
                .filter(metric -> metric.name().equals("Action-graph completeness"))
                .findFirst().orElseThrow().value()).isEqualTo("confirmed");
        assertThat(finding.caveats())
                .contains("not what Bazel scheduled")
                .contains("counted as instantaneous");
        assertThat(finding.confidence()).isEqualTo(Finding.Confidence.LOW);
    }

    @Test
    @DisplayName("a fully timed chain needs verified graph structure for high confidence")
    void criticalChainConfidenceNeedsStructuralCompleteness() {
        CsrGraph chain = CsrBuilder.build(2, visitor -> visitor.edge(0, 1));
        CriticalPath.Result path = CriticalPath.compute(
                chain, new long[] {300_000, 300_000},
                CriticalPath.DurationSource.BEP_ACTION);
        CriticalPaths paths = new CriticalPaths(
                Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE, "no profile"),
                List.of(), Optional.of(path));

        Finding withoutProof = run(new FindingInputs(
                invocation(OptionalLong.of(700_000), Optional.empty(), paths, List.of()),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS)).getFirst();
        Coverage completeGraph = Coverage.of(
                "Action-graph completeness", 2, 2, DataSource.AQUERY,
                "every artifact path and depset reference resolved");
        Finding withProof = run(new FindingInputs(
                invocation(OptionalLong.of(700_000), Optional.empty(), paths,
                        List.of(completeGraph)),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS)).getFirst();

        assertThat(withoutProof.confidence()).isEqualTo(Finding.Confidence.LOW);
        assertThat(withProof.confidence()).isEqualTo(Finding.Confidence.HIGH);
        assertThat(withProof.whyItMayMatter())
                .contains("cannot overlap")
                .contains("individual durations can still change")
                .doesNotContain("adding machines");
    }

    @Test
    @DisplayName("low-parallelism stretches are reported with the threshold that found them")
    void lowParallelismWindows() {
        ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
        for (int i = 0; i < 8; i++) {
            spans.add(0, 1_000_000);
        }
        spans.add(1_000_000, 3_000_000);
        ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
        List<ConcurrencySweep.Window> windows = spans.windowsBelow(4, 500_000);

        List<Finding> findings = run(new FindingInputs(
                invocation(OptionalLong.of(3_000_000), Optional.of(sweep), noPaths(), List.of()),
                Map.of(), windows, List.of(), List.of(), THRESHOLDS));

        Finding low = findings.stream()
                .filter(finding -> finding.ruleId().equals("low-parallelism-window"))
                .findFirst()
                .orElseThrow();
        assertThat(low.thresholdUsed()).contains("typical concurrency divided by 2");
        assertThat(low.evidence()).isNotEmpty();
        assertThat(low.evidence().getFirst().kind())
                .isEqualTo(Finding.Evidence.Kind.TIME_WINDOW);
    }

    @Test
    @DisplayName("a trailing straggler is reported as a slow tail with the action that caused it")
    void slowTail() {
        ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
        for (int i = 0; i < 8; i++) {
            spans.add(0, 1_000_000);
        }
        spans.add(1_000_000, 3_000_000);
        ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
        ActionMetrics straggler = with(action(9, "//pkg:slow", "Javac"),
                base -> withSpan(withDuration(base, 2_000_000), 1_000_000, 3_000_000));

        List<Finding> findings = run(new FindingInputs(
                invocation(OptionalLong.of(3_000_000), Optional.of(sweep), noPaths(), List.of()),
                Map.of(), spans.windowsBelow(4, 500_000), List.of(straggler), List.of(),
                THRESHOLDS));

        Finding tail = findings.stream()
                .filter(finding -> finding.ruleId().equals("slow-tail"))
                .findFirst()
                .orElseThrow();
        assertThat(tail.evidence()).extracting(Finding.Evidence::label).contains("//pkg:slow");
    }

    @Test
    @DisplayName("many short actions of one mnemonic are reported, a few are not")
    void manyTinyActions() {
        FindingInputs many = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("Javac", 900, 1_000, 0, 0, 900, 0)),
                List.of(), List.of(), List.of(), THRESHOLDS);
        assertThat(ruleIds(run(many))).contains("many-tiny-actions");

        FindingInputs few = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("Javac", 20, 1_000, 0, 0, 20, 0)),
                List.of(), List.of(), List.of(), THRESHOLDS);
        assertThat(ruleIds(run(few))).doesNotContain("many-tiny-actions");

        // Many actions that are not short is a different build, not a finding.
        FindingInputs slow = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("Javac", 900, 4_000_000, 0, 0, 900, 0)),
                List.of(), List.of(), List.of(), THRESHOLDS);
        assertThat(ruleIds(run(slow))).doesNotContain("many-tiny-actions");
    }

    @Test
    @DisplayName("a cache finding needs coverage, not just a miss rate")
    void cacheMissConcentrationNeedsCoverage() {
        // 90% misses, but only 4 of 100 actions reported a cache state at all.
        FindingInputs thin = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("CppCompile", 100, 5_000, 1, 3, 96, 0)),
                List.of(), List.of(), List.of(), THRESHOLDS);
        assertThat(ruleIds(run(thin))).doesNotContain("cache-miss-concentration");

        // The same rate over a group that mostly reported is a finding.
        FindingInputs covered = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("CppCompile", 100, 5_000, 10, 90, 0, 0)),
                List.of(), List.of(), List.of(), THRESHOLDS);
        Finding finding = run(covered).stream()
                .filter(item -> item.ruleId().equals("cache-miss-concentration"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.caveats()).contains("is not a miss");
        assertThat(finding.evidence()).extracting(Finding.Evidence::kind)
                .contains(Finding.Evidence.Kind.COVERAGE);
    }

    @Test
    @DisplayName("Bazel's own uncacheable declaration is read, not inferred from a runner name")
    void nonCacheableConcentration() {
        FindingInputs inputs = new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                byMnemonic(group("Genrule", 40, 5_000, 0, 0, 40, 30)),
                List.of(), List.of(), List.of(), THRESHOLDS);

        Finding finding = run(inputs).stream()
                .filter(item -> item.ruleId().equals("non-cacheable-concentration"))
                .findFirst()
                .orElseThrow();
        assertThat(finding.caveats()).contains("not an inference from a");
        assertThat(finding.metrics()).extracting(Finding.MetricValue::source)
                .allMatch(source -> source.contains("execution log")
                        || source.contains("this session"));
    }

    @Test
    @DisplayName("fan-out, input volume, output volume, queue and transfer each need their own shape")
    void perActionRulesFireOnTheirOwnShape() {
        ActionMetrics wide = with(action(1, "//pkg:hub", "Genrule"),
                base -> withConsumers(base, 400));
        ActionMetrics fat = with(action(2, "//pkg:big", "CppCompile"),
                base -> withInputs(withDuration(base, 1_000_000), 900L * 1024 * 1024));
        ActionMetrics producer = with(action(3, "//pkg:out", "Genrule"),
                base -> withOutputs(withDuration(base, 1_000_000), 700L * 1024 * 1024));
        ActionMetrics queued = with(action(4, "//pkg:wait", "Javac"),
                base -> withQueue(withDuration(base, 1_000_000), 800_000));
        ActionMetrics moving = with(action(5, "//pkg:move", "Javac"),
                base -> withTransfer(withDuration(base, 1_000_000), 700_000));
        ActionMetrics retried = with(action(6, "//pkg:flake", "TestRunner"),
                base -> withAttempts(base, 3));

        List<Finding> findings = run(new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                Map.of(), List.of(),
                List.of(wide, fat, producer, queued, moving, retried), List.of(), THRESHOLDS));

        assertThat(ruleIds(findings)).contains(
                "high-fan-out", "high-input-volume", "high-output-volume",
                "queue-dominated", "transfer-dominated", "repeated-attempts");
    }

    @Test
    @DisplayName("an ordinary action produces no findings at all")
    void quietBuildsAreQuiet() {
        ActionMetrics ordinary = with(action(1, "//pkg:a", "Javac"),
                base -> withDuration(base, 200_000));

        assertThat(run(new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                Map.of(), List.of(), List.of(ordinary), List.of(), THRESHOLDS))).isEmpty();
    }

    @Test
    @DisplayName("a poorly correlated action graph is reported, an absent one is not")
    void graphMismatch() {
        Coverage poor = Coverage.of(
                "Action-graph correlation", 100, 1_000, DataSource.AQUERY, "why");
        assertThat(ruleIds(run(new FindingInputs(
                invocation(OptionalLong.of(10_000), Optional.empty(), noPaths(), List.of(poor)),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS))))
                .contains("graph-mismatch");

        // No graph is not a mismatch: nothing was claimed, so nothing is wrong.
        Coverage absent = Coverage.unavailable(
                "Action-graph correlation", 1_000, DataSource.AQUERY, "no aquery output");
        assertThat(ruleIds(run(new FindingInputs(
                invocation(OptionalLong.of(10_000), Optional.empty(), noPaths(), List.of(absent)),
                Map.of(), List.of(), List.of(), List.of(), THRESHOLDS))))
                .doesNotContain("graph-mismatch");
    }

    @Test
    @DisplayName("findings come back worst first, and every one of them carries a threshold")
    void findingsAreOrderedAndComplete() {
        ActionMetrics wide = with(action(1, "//pkg:hub", "Genrule"),
                base -> withConsumers(base, 400));
        ActionMetrics retried = with(action(2, "//pkg:flake", "TestRunner"),
                base -> withAttempts(base, 3));

        List<Finding> findings = run(new FindingInputs(
                invocation(OptionalLong.of(10_000_000), Optional.empty(), noPaths(), List.of()),
                Map.of(), List.of(), List.of(wide, retried), List.of(), THRESHOLDS));

        assertThat(findings).isNotEmpty();
        for (int i = 1; i < findings.size(); i++) {
            assertThat(findings.get(i - 1).severity().ordinal())
                    .isGreaterThanOrEqualTo(findings.get(i).severity().ordinal());
        }
        for (Finding finding : findings) {
            assertThat(finding.thresholdUsed()).as("%s", finding.ruleId()).isNotBlank();
            assertThat(finding.caveats()).as("%s", finding.ruleId()).isNotBlank();
            assertThat(finding.evidence()).as("%s", finding.ruleId()).isNotEmpty();
            assertThat(finding.links()).as("%s", finding.ruleId()).isNotEmpty();
            for (Finding.MetricValue metric : finding.metrics()) {
                assertThat(metric.source())
                        .as("%s: %s must say where it came from", finding.ruleId(), metric.name())
                        .isNotBlank();
            }
        }
    }

    // --- mutators ----------------------------------------------------------

    private static ActionMetrics withDuration(ActionMetrics base, long micros) {
        return copy(base, OptionalLong.of(micros), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(), base.outputBytes(),
                base.attempts(), base.directConsumers(), base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withSpan(ActionMetrics base, long start, long end) {
        return copy(base, base.durationMicros(), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(), base.outputBytes(),
                base.attempts(), base.directConsumers(),
                OptionalLong.of(start), OptionalLong.of(end));
    }

    private static ActionMetrics withQueue(ActionMetrics base, long micros) {
        return copy(base, base.durationMicros(), OptionalLong.of(micros), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(), base.outputBytes(),
                base.attempts(), base.directConsumers(), base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withTransfer(ActionMetrics base, long micros) {
        return copy(base, base.durationMicros(), base.queueMicros(), OptionalLong.of(micros),
                OptionalLong.of(0), OptionalLong.of(0), base.inputBytes(), base.outputBytes(),
                base.attempts(), base.directConsumers(), base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withInputs(ActionMetrics base, long bytes) {
        return copy(base, base.durationMicros(), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), OptionalLong.of(bytes),
                base.outputBytes(), base.attempts(), base.directConsumers(),
                base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withOutputs(ActionMetrics base, long bytes) {
        return copy(base, base.durationMicros(), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(),
                OptionalLong.of(bytes), base.attempts(), base.directConsumers(),
                base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withAttempts(ActionMetrics base, long attempts) {
        return copy(base, base.durationMicros(), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(), base.outputBytes(),
                attempts, base.directConsumers(), base.startMicros(), base.endMicros());
    }

    private static ActionMetrics withConsumers(ActionMetrics base, long consumers) {
        return copy(base, base.durationMicros(), base.queueMicros(), base.networkMicros(),
                base.uploadMicros(), base.fetchMicros(), base.inputBytes(), base.outputBytes(),
                base.attempts(), OptionalLong.of(consumers), base.startMicros(), base.endMicros());
    }

    private static ActionMetrics copy(
            ActionMetrics base, OptionalLong duration, OptionalLong queue, OptionalLong network,
            OptionalLong upload, OptionalLong fetch, OptionalLong inputBytes,
            OptionalLong outputBytes, long attempts, OptionalLong consumers,
            OptionalLong start, OptionalLong end) {
        return new ActionMetrics(
                base.actionId(), base.label(), base.mnemonic(), base.runner(), base.outcome(),
                start, end, duration, queue, base.setupMicros(), base.executionMicros(),
                network, upload, fetch, inputBytes, base.inputFiles(), outputBytes,
                base.outputFiles(), attempts, base.cacheState(), base.directDependencies(),
                consumers, base.slackMicros(), base.onDerivedCriticalPath(),
                base.startConcurrency(), base.completionConcurrency());
    }
}
